package fr.retrospare.blazeplayer.player

import fr.retrospare.blazeplayer.ui.showPremium
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.graphics.ColorMatrix
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Paint
import android.provider.MediaStore
import android.os.Environment
import android.view.TextureView
import android.view.SurfaceView
import android.view.PixelCopy
import android.graphics.RenderEffect
import android.content.res.Configuration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.cast.VideoStreamServerManager
import fr.retrospare.blazeplayer.cast.BlazeMediaRouteDialogFactory
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.debug.CrashReporter
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import fr.retrospare.blazeplayer.playlist.PlaylistCategory
import fr.retrospare.blazeplayer.ui.InfoDialog
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Écran de lecture vidéo.
 *
 * Architecture (Media3 1.9) : toute la lecture — locale ET Chromecast — passe par UN SEUL
 * [MediaItem], dont l'URI pointe vers notre propre relais HTTP local ([VideoStreamServerManager]),
 * jamais directement vers smb:// ou content://. C'est ce qui permet à [androidx.media3.cast.CastPlayer]
 * (construit dans [VideoPlaybackService]) de basculer tout seul entre local et distant — position
 * et sous-titres compris — sans la moindre reconstruction manuelle ici : on ne fait JAMAIS de
 * `setMediaItem()` spécifique au cast, on laisse Media3 s'en charger.
 */
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity(), ChromecastRemoteCommandBridge.Target {

    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository
    @Inject lateinit var mediaRepository: MediaRepository

    companion object {
        val SUB_LANG_CODES = listOf(null, "fra", "eng", "spa", "deu", "ita", "jpn", "por", "nld", "rus", "zho")
        val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val SEEK_LABELS = listOf("5s", "10s", "15s", "30s", "60s")
    }

    private lateinit var binding: ActivityPlayerBinding
    lateinit var player: Player
    /** Complété une fois que le MediaController est connecté à VideoPlaybackService et assigné à
     *  [player]. Permet de séquencer correctement le chargement des préférences (asynchrone via
     *  DataStore) avant toute mutation du player. */
    private val playerReady = CompletableDeferred<Unit>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private lateinit var audioManager: AudioManager

    private var prefSpeedIndex = 3
    private var prefResumeMode = 1
    private var prefAutoPlay = true
    private var prefSeekIndex = 1
    private var prefPip = false
    private var prefAudioLangIndex = 0
    private var prefRememberVolume = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideUI() }
    private var uiVisible = true
    private var mediaPath = ""
    private var mediaName = ""
    private var isNetworkMedia = false
    private var resumeHandled = false
    private var lastKnownIsRemote = false
    private var lastKnownLocalPosition = 0L
    private var lastKnownRemotePosition = 0L
    private var playNextCalled = false
    private var mediaSwitchRequestId = 0L
    private var mediaReplacementInProgress = false
    private var castReplacementRetryCount = 0
    private var pendingCastReplacementVerification: Runnable? = null
    private var resumeChoiceDialog: android.app.AlertDialog? = null
    /** Tant que cette valeur est vraie, aucun callback du player ne doit prendre une décision de
     *  reprise à la place du modal lié au nouveau média. C'est indispensable quand une activité
     *  s'ouvre alors qu'un ancien Cast est encore en lecture. */
    private var resumeDecisionPending = true
    private var resumeDialogRequestId = Long.MIN_VALUE

    /** Fenêtre de grâce pendant laquelle STATE_IDLE/BUFFERING fait partie d'un LOAD ou d'un
     *  transfert Cast normal et ne doit surtout pas déclencher la récupération réseau. */
    private var suppressNetworkRecoveryUntilMs = 0L
    private var pendingExpectedMediaPath: String? = null
    private var pendingExpectedStartPositionMs = 0L
    private var pendingExpectedAutoPlay = true
    private var pendingExpectedSeekApplied = false
    /** Média pour lequel l'autoplay Cast doit encore être confirmé. Dès que le récepteur publie
     *  playWhenReady/BUFFERING/READY, la garde est levée afin de ne jamais annuler ensuite une
     *  pause volontaire de l'utilisateur. */
    private var pendingRemoteAutoplayMediaId: String? = null
    private var networkEarlyEndRecoveries = 0
    private var lastNetworkRecoverAtMs = 0L
    private var lastNetworkBufferingAtMs = 0L
    private var lastNetworkBufferPositionMs = 0L
    private var networkStarvationRecoveries = 0
    private val networkStarvationRunnable: Runnable = Runnable {
        if (!::player.isInitialized || !isNetworkPlayback()) return@Runnable
        if (networkRecoveryIsSuppressed()) {
            uiHandler.postDelayed(networkStarvationRunnable, 3_000L)
            return@Runnable
        }
        if (player.playbackState != Player.STATE_BUFFERING) return@Runnable
        val pos = player.currentPosition.coerceAtLeast(lastKnownPositionForCurrentOutput())
        val buffered = player.bufferedPosition
        val stalled = kotlin.math.abs(pos - lastNetworkBufferPositionMs) < 1_500L && buffered <= pos + 1_500L
        val bufferingFor = android.os.SystemClock.elapsedRealtime() - lastNetworkBufferingAtMs
        if (bufferingFor >= 8_000L && stalled && networkStarvationRecoveries < 8) {
            recoverNetworkStarvation(pos)
        } else if (bufferingFor < 45_000L) {
            uiHandler.postDelayed(networkStarvationRunnable, 4_000L)
        } else {
            showNetworkBufferingMessage("Réseau instable, toujours en tampon…")
            uiHandler.postDelayed(networkStarvationRunnable, 6_000L)
        }
    }
    private var seekBarDragging = false
    // File d'attente de lecture (playlist "Jouer la playlist") : quand non vide, playNext()
    // enchaîne sur l'élément suivant de cette liste au lieu de chercher dans le même dossier local.
    private var videoQueuePaths: ArrayList<String> = arrayListOf()
    private var videoQueueNames: ArrayList<String> = arrayListOf()
    private var videoQueueIndex: Int = 0
    private var videoBrightness = -1f      // -1 = luminosité système, 0..100 = luminosité native Android de la fenêtre
    private var videoContrast = 0f         // -100..100
    private var videoHue = 0f              // -100..100
    private var videoSaturation = 0f       // -100..100
    private var videoVolumeBoost = 0f      // 0..20
    private var videoDialogueMode = 0f      // 0..100, clarifie les voix et calme les effets forts
    private var usingFilteredTextureView = false
    private var maxVolume = 0
    private var networkErrorDialogShown = false
    private var compatWarningShown = false
    private var hasEnteredPip = false
    private var videoStoppedByUser = false
    private var closingPlayerExplicitly = false
    private var openingCastRemote = false
    private var lastProgressPersistAt = 0L
    private var networkPlaybackReachedNaturalEnd = false
    private var prematureLocalEndRecoveries = 0
    private var lastLocalRecoverAtMs = 0L
    private val playbackThumbnailCapturedPaths = linkedSetOf<String>()
    private val playbackThumbnailRetryAfter = mutableMapOf<String, Long>()
    private var playbackThumbnailCaptureInProgress = false


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReplacementIntent(intent)
    }

    /**
     * PlayerActivity est en singleTop : quand l'utilisateur clique un second fichier vidéo dans
     * un explorateur Android, l'Activity existante reçoit onNewIntent() au lieu d'être recréée.
     * On doit donc remplacer le média courant explicitement, comme si l'utilisateur avait choisi
     * une autre vidéo depuis l'app.
     */
    private fun handleReplacementIntent(intent: Intent?) {
        intent ?: return
        val externalMedia = ExternalMediaIntentUtils.fromExternalIntent(this, intent)
        if (externalMedia?.kind == ExternalMediaIntentUtils.ExternalMedia.Kind.AUDIO) {
            startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
                putExtra("mediaPath", externalMedia.path)
                putExtra("mediaName", externalMedia.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = externalMedia.uri
                clipData = android.content.ClipData.newUri(contentResolver, externalMedia.name, externalMedia.uri)
            })
            finish()
            return
        }

        val newPath = intent.getStringExtra("mediaPath") ?: externalMedia?.path ?: return
        val newName = intent.getStringExtra("mediaName") ?: externalMedia?.name ?: File(newPath).name
        val incomingIsNetwork = intent.getBooleanExtra("isNetworkMedia", false) ||
            fr.retrospare.blazeplayer.paywall.FeatureAccess.isNetworkMediaPath(newPath)
        if (incomingIsNetwork && !kotlinx.coroutines.runBlocking {
                fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            }) {
            fr.retrospare.blazeplayer.paywall.AccessGateUi.redirectToPaywall(this)
            return
        }
        val incomingQueuePaths = intent.getStringArrayListExtra("queuePaths")
        val incomingQueueNames = intent.getStringArrayListExtra("queueNames")
        if (incomingQueuePaths != null) {
            videoQueuePaths = incomingQueuePaths
            videoQueueNames = incomingQueueNames ?: arrayListOf()
            videoQueueIndex = intent.getIntExtra("queueIndex", incomingQueuePaths.indexOf(newPath).coerceAtLeast(0))
        } else {
            videoQueuePaths.clear()
            videoQueueNames.clear()
            videoQueueIndex = 0
        }
        if (newPath.isBlank()) return
        if (newPath == mediaPath) {
            persistRemoteQueueState()
            isNetworkMedia = intent.getBooleanExtra("isNetworkMedia", false) ||
                newPath.startsWith("smb://", true) || newPath.startsWith("ftp://", true) ||
                newPath.startsWith("http://", true) || newPath.startsWith("https://", true)
            if (::player.isInitialized && playerReady.isCompleted) switchTo(newPath, newName)
            return
        }

        if (newPath.startsWith("content://", true)) {
            try {
                val grantUri = externalMedia?.uri ?: intent.data ?: Uri.parse(newPath)
                grantUriPermission(packageName, grantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        isNetworkMedia = intent.getBooleanExtra("isNetworkMedia", false) ||
            newPath.startsWith("smb://", true) || newPath.startsWith("ftp://", true) ||
            newPath.startsWith("http://", true) || newPath.startsWith("https://", true)

        if (::player.isInitialized && playerReady.isCompleted) {
            switchTo(newPath, newName)
        } else {
            mediaPath = newPath
            mediaName = newName
            lifecycleScope.launch {
                playerReady.await()
                if (!isFinishing && !isDestroyed) switchTo(newPath, newName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            val orientIdx = dataStore.data.first()[intPreferencesKey("orientation")] ?: 0
            requestedOrientation = when (orientIdx) {
                1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
        }

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImmersiveMode()
        applyResponsivePlayerLayout()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                binding.uiOverlay.setPadding(0, 0, 0, 0)
            } else {
                binding.uiOverlay.setPadding(0, systemBars.top, 0, systemBars.bottom)
            }
            insets
        }

        val externalMedia = ExternalMediaIntentUtils.fromExternalIntent(this, intent)
        mediaPath = intent.getStringExtra("mediaPath") ?: externalMedia?.path ?: return finish()
        intent.getStringArrayListExtra("queuePaths")?.let { videoQueuePaths = it }
        intent.getStringArrayListExtra("queueNames")?.let { videoQueueNames = it }
        videoQueueIndex = intent.getIntExtra("queueIndex", 0)
        mediaName = intent.getStringExtra("mediaName") ?: externalMedia?.name ?: File(mediaPath).name
        if (mediaPath.startsWith("content://", true)) {
            try {
                val grantUri = externalMedia?.uri ?: intent.data ?: Uri.parse(mediaPath)
                grantUriPermission(packageName, grantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }
        isNetworkMedia = intent.getBooleanExtra("isNetworkMedia", false) ||
            fr.retrospare.blazeplayer.paywall.FeatureAccess.isNetworkMediaPath(mediaPath)
        if (isNetworkMedia && !kotlinx.coroutines.runBlocking {
                fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            }) {
            fr.retrospare.blazeplayer.paywall.AccessGateUi.redirectToPaywall(this)
            return
        }
        monitorNetworkTrialExpiry()
        persistRemoteQueueState()
        ChromecastRemoteCommandBridge.attach(this)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBackToHistory() }
        })

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Prépare le relais HTTP local seulement comme URL de secours pour Chromecast.
        // La lecture locale utilise désormais directement file/content/http/smb via la DataSource
        // Media3 appropriée : éviter le détour 127.0.0.1 supprime une source de coupures locales
        // et réduit la latence sur les vidéos réseau.
        try {
            VideoStreamServerManager.startServer(applicationContext, mediaPath)
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to prepare local video stream server for cast fallback $mediaPath", e)
        }

        // Met l'audio en pause (sans arrêter le service ni sa notification) pour pouvoir la
        // relancer facilement une fois la vidéo terminée, au lieu de couper BlazePlayerService.
        pauseAudioPlaybackKeepingNotification()

        // Démarre VideoPlaybackService (ExoPlayer + CastPlayer + MediaSession vidéo). Toute erreur
        // ici était auparavant fatale ou silencieuse selon les appareils : on la loggue et on sort
        // proprement au lieu de laisser l'Activity attendre un player qui n'arrivera jamais.
        try {
            startService(android.content.Intent(this, VideoPlaybackService::class.java))
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to start VideoPlaybackService", e)
            InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.error_loading_media))
            finish()
            return
        }

        val token = SessionToken(this, android.content.ComponentName(this, VideoPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture!!.addListener({
            try {
                player = controllerFuture!!.get()
                playerReady.complete(Unit)
            } catch (e: Exception) {
                CrashReporter.log(this, "MediaController connection failed for video service", e)
                if (!playerReady.isCompleted) playerReady.completeExceptionally(e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.error_loading_media))
                        finish()
                    }
                }
            }
        }, MoreExecutors.directExecutor())

        binding.tvTitle.text = mediaName
        binding.tvCurrentTime.text = "0:00:00"
        binding.tvTotalTime.text = "0:00:00"

        setupControls()
        setupCastButton()
        setupProgressBar()
        saveHistory()
        scheduleHide()

        // Charge les préférences puis les applique au player UNE FOIS que le MediaController est
        // prêt (séquencement explicite via CompletableDeferred).
        lifecycleScope.launch {
            val prefs = dataStore.data.first()
            prefSpeedIndex = prefs[intPreferencesKey("speed_index")] ?: 3
            prefResumeMode = prefs[intPreferencesKey("resume_mode")] ?: 1
            prefAutoPlay = prefs[booleanPreferencesKey("auto_play")] ?: true
            prefSeekIndex = prefs[intPreferencesKey("seek_time_index")] ?: 1
            prefPip = prefs[booleanPreferencesKey("pip")] ?: false
            prefAudioLangIndex = prefs[intPreferencesKey("audio_lang")] ?: 0
            prefRememberVolume = prefs[booleanPreferencesKey("remember_volume")] ?: false
            videoBrightness = (prefs[intPreferencesKey("video_brightness")] ?: -1).toFloat()
            videoContrast = (prefs[intPreferencesKey("video_contrast")] ?: 0).toFloat()
            videoHue = (prefs[intPreferencesKey("video_hue")] ?: 0).toFloat()
            videoSaturation = (prefs[intPreferencesKey("video_saturation")] ?: 0).toFloat()
            videoVolumeBoost = (prefs[intPreferencesKey("video_volume_boost")] ?: 0).toFloat()
            videoDialogueMode = (prefs[intPreferencesKey("video_dialogue_mode")] ?: 0).toFloat()
            applyVideoAdjustments()

            if (prefRememberVolume) {
                val savedVol = prefs[intPreferencesKey("saved_volume")] ?: -1
                if (savedVol >= 0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
            }

            binding.tvRewindLabel.text = "−${SEEK_LABELS.getOrElse(prefSeekIndex) { "10s" }}"
            binding.tvForwardLabel.text = "+${SEEK_LABELS.getOrElse(prefSeekIndex) { "10s" }}"

            val ready = try {
                withTimeoutOrNull(6_000) {
                    playerReady.await()
                    true
                } ?: false
            } catch (e: Exception) {
                CrashReporter.log(this@PlayerActivity, "Video player controller failed for $mediaPath", e)
                false
            }
            if (!ready || !::player.isInitialized) {
                CrashReporter.log(this@PlayerActivity, "Video player controller timeout for $mediaPath")
                InfoDialog.show(this@PlayerActivity, getString(R.string.info_dialog_title_error), getString(R.string.error_loading_media))
                finish()
                return@launch
            }
            onPlayerReady()

            val audioLang = SUB_LANG_CODES.getOrNull(prefAudioLangIndex)
            applySubtitleTrackSelection()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .apply { if (audioLang != null) setPreferredAudioLanguage(audioLang) }
                .build()
            player.setPlaybackSpeed(SPEEDS.getOrElse(prefSpeedIndex) { 1.0f })
            updateSpeedLabel()

            val savedMs = savedResumePositionMs(mediaPath)
            val askResume = prefResumeMode == 1 && savedMs > 3000L
            val startPosition = when {
                // Mode "toujours reprendre" : on démarre directement à la dernière position.
                prefResumeMode == 0 && savedMs > 3000L -> savedMs
                // Mode "demander" : on charge au début mais on NE lance pas la lecture tant
                // que l'utilisateur n'a pas choisi. Important pour Chromecast : si une session
                // Cast est déjà active, CastPlayer reçoit exactement la même décision que le
                // lecteur local au lieu de démarrer automatiquement à 0.
                else -> 0L
            }
            resumeHandled = prefResumeMode != 1 && startPosition > 0L
            resumeDecisionPending = askResume

            if (askResume) {
                // Important pour Chromecast : ne pas charger le MediaItem avant le choix.
                // Sinon CastPlayer peut recevoir immédiatement le média et démarrer à 0 pendant
                // que le modal est encore affiché. Ici le LOAD local/Cast ne part qu'après
                // "Reprendre" ou "Depuis le début".
                showInitialResumeChoice(savedMs, mediaPath, mediaName, mediaSwitchRequestId)
            } else {
                resumeDecisionPending = false
                loadMedia(mediaPath, mediaName, startPosition, autoPlay = true)
            }

            startProgressLoop()
        }

        // Garde la valeur à jour si l'utilisateur change le réglage puis revient dans une
        // PlayerActivity déjà existante (notamment après un passage par les Paramètres).
        lifecycleScope.launch {
            dataStore.data
                .map { prefs -> prefs[booleanPreferencesKey("auto_play")] ?: true }
                .distinctUntilChanged()
                .collect { enabled -> prefAutoPlay = enabled }
        }

        // Ne lance plus d'extraction de miniature en parallèle pendant une lecture réseau :
        // MediaMetadataRetriever ouvre un second flux/décodeur et peut provoquer exactement le
        // type de retard de rendu vidéo observé alors que l'audio reste fluide.
        if (!isNetworkPlayback()) lifecycleScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(2_000) {
                var smbDataSourceThumb: SmbMediaDataSource? = null
                val r = android.media.MediaMetadataRetriever()
                try {
                    when {
                        mediaPath.startsWith("smb://") -> {
                            smbDataSourceThumb = SmbMediaDataSource(mediaPath)
                            r.setDataSource(smbDataSourceThumb)
                        }
                        mediaPath.startsWith("content://") -> r.setDataSource(this@PlayerActivity, Uri.parse(mediaPath))
                        mediaPath.startsWith("http://", true) || mediaPath.startsWith("https://", true) -> r.setDataSource(mediaPath, emptyMap())
                        else -> r.setDataSource(mediaPath)
                    }
                    val frame = r.getFrameAtTime(10_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let {
                        val scale = 256f / maxOf(it.width, it.height)
                        if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true).also { _ -> it.recycle() }
                        else it
                    }
                } catch (e: Exception) {
                    CrashReporter.log(this@PlayerActivity, "Thumbnail extraction failed for $mediaPath", e)
                } finally {
                    try { r.release() } catch (_: Exception) {}
                    try { smbDataSourceThumb?.close() } catch (_: Exception) {}
                }
            } ?: CrashReporter.log(this@PlayerActivity, "Thumbnail extraction timeout for $mediaPath")
        }
    }

    /** Active toujours les pistes texte lorsqu'un MediaItem contient un WebVTT sidecar.
     *  Les deux anciens réglages globaux (sous-titres par défaut et langue préférée) ont été
     *  supprimés : on ne bloque plus jamais TRACK_TYPE_TEXT à cause d'une préférence obsolète. */
    private fun applySubtitleTrackSelection() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
    }

    private fun buildMediaItem(path: String, name: String): MediaItem =
        VideoMediaItemFactory.build(this, path, name)

    private fun persistRemoteQueueState() {
        if (mediaPath.isBlank()) return
        VideoRemoteQueueState.save(
            context = applicationContext,
            queuePaths = videoQueuePaths,
            queueNames = videoQueueNames,
            index = videoQueueIndex,
            currentPath = mediaPath,
            currentName = mediaName
        )
    }

    /** Point d'entrée UNIQUE pour charger un média. API Player standard uniquement :
     *  setMediaItem -> prepare -> play. Pas de RemoteMediaClient, pas de MediaQueueItem manuel. */
    private fun loadMedia(path: String, name: String, positionMs: Long = 0L, autoPlay: Boolean = true) {
        videoStoppedByUser = false
        val previousMediaId = runCatching { player.currentMediaItem?.mediaId }.getOrNull()
        val replacingMedia = mediaReplacementInProgress || (previousMediaId != null && previousMediaId != path)
        val isRemote = runCatching {
            player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        }.getOrDefault(false)
        // Une vidéo envoyée au Chromecast doit toujours démarrer immédiatement. L'état pause du
        // lecteur local ne doit jamais être transféré au récepteur ni affecter une vidéo choisie
        // depuis la file d'attente, l'historique ou le navigateur de fichiers.
        val effectiveAutoPlay = autoPlay || isRemote
        markExpectedMediaLoad(path, positionMs, effectiveAutoPlay)

        if (replacingMedia || path != mediaPath || positionMs <= 1000L) {
            networkPlaybackReachedNaturalEnd = false
            networkEarlyEndRecoveries = 0
            networkStarvationRecoveries = 0
            prematureLocalEndRecoveries = 0
        }
        if (replacingMedia) {
            // Une position observée sur l'ancienne vidéo ne doit jamais être réutilisée par une
            // récupération du nouveau média, surtout en Cast où l'ancien LOAD peut répondre en
            // retard. On repart strictement de la position choisie par l'utilisateur.
            lastKnownLocalPosition = positionMs.coerceAtLeast(0L)
            lastKnownRemotePosition = positionMs.coerceAtLeast(0L)
            cancelNetworkStarvationWatch()
            mediaReplacementInProgress = true
            castReplacementRetryCount = 0
        }

        val item = buildMediaItem(path, name)
        android.util.Log.i(
            "CAST",
            "LOAD média path=$path uri=${item.localConfiguration?.uri} positionMs=$positionMs remote=$isRemote replacing=$replacingMedia"
        )

        // Ne jamais vider la file distante juste avant setMediaItem(). Sur CastPlayer, clear puis
        // set partent comme deux commandes asynchrones distinctes et peuvent arriver dans l'ordre
        // inverse : le nouveau média est alors chargé puis aussitôt effacé. Une simple pause suivie
        // de setMediaItem() réalise le remplacement atomique attendu par Media3.
        if (replacingMedia && isRemote) {
            runCatching { player.pause() }
        }

        // Ne PAS appeler player.stop() ici : stop() peut arrêter le MediaSessionService alors que
        // le CastPlayer est encore actif. setMediaItem/prepare est le remplacement Media3 prévu.
        player.setMediaItem(item, positionMs)
        player.playWhenReady = effectiveAutoPlay
        player.prepare()
        applySubtitleTrackSelection()
        if (effectiveAutoPlay) {
            if (isRemote) scheduleRemoteAutoplayChecks("remote media load") else player.play()
        } else player.pause()
        loadNotificationMetadata(path, name)
        loadNotificationArtwork(path)

        if (replacingMedia && isRemote) {
            scheduleCastReplacementVerification(
                requestId = mediaSwitchRequestId,
                path = path,
                name = name,
                positionMs = positionMs,
                autoPlay = effectiveAutoPlay
            )
        } else if (!isRemote) {
            mediaReplacementInProgress = false
        }
    }

    private fun scheduleCastReplacementVerification(
        requestId: Long,
        path: String,
        name: String,
        positionMs: Long,
        autoPlay: Boolean
    ) {
        pendingCastReplacementVerification?.let(uiHandler::removeCallbacks)
        val verification = Runnable {
            if (requestId != mediaSwitchRequestId || mediaPath != path || !::player.isInitialized) return@Runnable
            val stillRemote = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            if (!stillRemote) {
                mediaReplacementInProgress = false
                castReplacementRetryCount = 0
                return@Runnable
            }
            val correctItem = player.currentMediaItem?.mediaId == path
            val state = player.playbackState
            val expectedUrl = VideoStreamServerManager.getLanStreamUrl()
            val remoteContentUrl = runCatching {
                val client = com.google.android.gms.cast.framework.CastContext
                    .getSharedInstance(applicationContext)
                    .sessionManager.currentCastSession?.remoteMediaClient
                client?.mediaInfo?.contentUrl?.takeIf { it.isNotBlank() } ?: client?.mediaInfo?.contentId
            }.getOrNull()
            val receiverAccepted = expectedUrl != null && remoteContentUrl == expectedUrl
            // Sur le player distant, currentMediaItem peut être mis à jour avant que la commande
            // LOAD soit réellement acceptée par le récepteur. Le contentUrl du RemoteMediaClient
            // est donc la source de vérité pour éviter une fausse confirmation sur l'ancien média.
            val accepted = correctItem && receiverAccepted &&
                (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            if (accepted) {
                if (autoPlay) ensureRemotePlaybackStarted("replacement confirmed")
                mediaReplacementInProgress = false
                castReplacementRetryCount = 0
                pendingCastReplacementVerification = null
                android.util.Log.i("CAST", "Replacement confirmed for $path state=$state")
                return@Runnable
            }

            if (castReplacementRetryCount < 2) {
                castReplacementRetryCount++
                android.util.Log.w(
                    "CAST",
                    "Replacement not confirmed for $path state=$state item=${player.currentMediaItem?.mediaId}; retry $castReplacementRetryCount/2"
                )
                retryCastReplacement(requestId, path, name, positionMs, autoPlay)
            } else {
                mediaReplacementInProgress = false
                pendingCastReplacementVerification = null
                CrashReporter.log(
                    this@PlayerActivity,
                    "Chromecast replacement failed after retries for $path state=$state item=${player.currentMediaItem?.mediaId}"
                )
                if (!isFinishing && !isDestroyed) {
                    InfoDialog.show(
                        this@PlayerActivity,
                        getString(R.string.info_dialog_title_chromecast),
                        getString(R.string.error_loading_media),
                        R.drawable.ic_cast
                    )
                }
            }
        }
        pendingCastReplacementVerification = verification
        uiHandler.postDelayed(verification, 6_500L)
    }

    private fun retryCastReplacement(
        requestId: Long,
        path: String,
        name: String,
        positionMs: Long,
        autoPlay: Boolean
    ) {
        if (requestId != mediaSwitchRequestId || mediaPath != path || !::player.isInitialized) return
        runCatching { VideoStreamServerManager.startServer(applicationContext, path) }
            .onFailure { CrashReporter.log(this, "Failed to refresh Cast relay before replacement retry for $path", it) }
        val item = buildMediaItem(path, name)
        val effectiveAutoPlay = autoPlay || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        markExpectedMediaLoad(path, positionMs, effectiveAutoPlay)
        runCatching { player.pause() }
        player.setMediaItem(item, positionMs)
        player.playWhenReady = effectiveAutoPlay
        player.prepare()
        applySubtitleTrackSelection()
        if (effectiveAutoPlay) {
            if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                scheduleRemoteAutoplayChecks("replacement retry")
            } else player.play()
        } else player.pause()
        scheduleCastReplacementVerification(requestId, path, name, positionMs, effectiveAutoPlay)
    }

    /** Force la lecture distante après un transfert ou un LOAD Cast. Plusieurs appels sont
     *  intentionnellement sans danger : play() est idempotent, tandis que certains récepteurs
     *  publient brièvement un état pause/idle après avoir accepté le média. */
    private fun ensureRemotePlaybackStarted(reason: String) {
        if (!::player.isInitialized || resumeDecisionPending || videoStoppedByUser) return
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val target = pendingRemoteAutoplayMediaId ?: return
        if (target != mediaId) return
        // Dès que la commande est acceptée, ne plus insister : une pause ultérieure est forcément
        // volontaire et ne doit pas être annulée par un callback différé.
        if (player.playWhenReady && (player.playbackState == Player.STATE_BUFFERING ||
                player.playbackState == Player.STATE_READY || player.isPlaying)
        ) {
            pendingRemoteAutoplayMediaId = null
            return
        }
        runCatching {
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            android.util.Log.i("CAST", "Autoplay distant demandé reason=$reason media=$mediaId state=${player.playbackState}")
        }.onFailure { CrashReporter.log(this, "Failed to force Cast autoplay: $reason", it) }
    }

    private fun scheduleRemoteAutoplayChecks(reason: String) {
        pendingRemoteAutoplayMediaId = player.currentMediaItem?.mediaId ?: mediaPath.takeIf { it.isNotBlank() }
        ensureRemotePlaybackStarted(reason)
        listOf(450L, 1_400L, 3_000L).forEach { delayMs ->
            uiHandler.postDelayed({
                if (!isFinishing && !isDestroyed) ensureRemotePlaybackStarted("$reason +${delayMs}ms")
            }, delayMs)
        }
    }


    private fun lastKnownPositionForCurrentOutput(): Long {
        if (!::player.isInitialized) return lastKnownLocalPosition.coerceAtLeast(0L)
        return if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            lastKnownRemotePosition.coerceAtLeast(0L)
        } else {
            lastKnownLocalPosition.coerceAtLeast(0L)
        }
    }

    private fun isNetworkPlayback(): Boolean {
        return isNetworkMedia || mediaPath.startsWith("smb://", true) || mediaPath.startsWith("ftp://", true) ||
            mediaPath.startsWith("http://", true) || mediaPath.startsWith("https://", true)
    }

    private fun markExpectedMediaLoad(
        path: String,
        positionMs: Long,
        autoPlay: Boolean,
        graceMs: Long = 15_000L
    ) {
        pendingExpectedMediaPath = path
        pendingExpectedStartPositionMs = positionMs.coerceAtLeast(0L)
        pendingExpectedAutoPlay = autoPlay
        pendingExpectedSeekApplied = false
        suppressNetworkRecoveryUntilMs = maxOf(
            suppressNetworkRecoveryUntilMs,
            android.os.SystemClock.elapsedRealtime() + graceMs
        )
        cancelNetworkStarvationWatch()
    }

    private fun networkRecoveryIsSuppressed(): Boolean {
        return android.os.SystemClock.elapsedRealtime() < suppressNetworkRecoveryUntilMs
    }

    /** Réapplique une seule fois la position choisie lorsque le récepteur confirme le média.
     *  CastPlayer peut publier brièvement READY à 0 pendant le transfert, même si setMediaItem()
     *  contenait une position de reprise. Cette vérification garantit que Reprendre et Depuis le
     *  début produisent exactement le même résultat localement et sur le Chromecast. */
    private fun confirmExpectedMediaLoadAtReady() {
        val expectedPath = pendingExpectedMediaPath ?: return
        if (player.currentMediaItem?.mediaId != expectedPath) return
        val desired = pendingExpectedStartPositionMs.coerceAtLeast(0L)
        val current = player.currentPosition.coerceAtLeast(0L)
        val delta = kotlin.math.abs(current - desired)
        if (!pendingExpectedSeekApplied && delta > 2_500L) {
            pendingExpectedSeekApplied = true
            android.util.Log.i("CAST", "Correction position après LOAD path=$expectedPath current=$current desired=$desired")
            player.seekTo(desired)
            if (pendingExpectedAutoPlay) {
                if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                    scheduleRemoteAutoplayChecks("resume position corrected")
                } else player.play()
            } else player.pause()
            suppressNetworkRecoveryUntilMs = android.os.SystemClock.elapsedRealtime() + 8_000L
            return
        }
        if (delta <= 3_500L) {
            if (pendingExpectedAutoPlay && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                scheduleRemoteAutoplayChecks("expected media ready")
            }
            pendingExpectedMediaPath = null
            pendingExpectedSeekApplied = false
            suppressNetworkRecoveryUntilMs = android.os.SystemClock.elapsedRealtime() + 2_500L
        }
    }

    private fun showNetworkBufferingMessage(message: String = "Mise en tampon…") {
        // Ce message restait affiché en permanence par-dessus la vidéo : rien ne le masquait
        // quand l'état repassait à STATE_READY (contrairement aux autres usages de seekIndicator,
        // qui se cachent tous eux-mêmes via un postDelayed). On désactive donc l'affichage du
        // texte, sans toucher à la logique de reprise réseau (scheduleNetworkStarvationWatch /
        // recoverNetworkStarvation), qui continue de fonctionner en silence pour relancer la
        // lecture en cas de coupure.
    }

    private fun scheduleNetworkStarvationWatch() {
        if (!isNetworkPlayback()) return
        lastNetworkBufferingAtMs = android.os.SystemClock.elapsedRealtime()
        lastNetworkBufferPositionMs = if (::player.isInitialized) player.currentPosition else 0L
        uiHandler.removeCallbacks(networkStarvationRunnable)
        uiHandler.postDelayed(networkStarvationRunnable, 8_000L)
    }

    private fun cancelNetworkStarvationWatch() {
        uiHandler.removeCallbacks(networkStarvationRunnable)
        lastNetworkBufferingAtMs = 0L
        lastNetworkBufferPositionMs = 0L
    }

    private fun recoverNetworkStarvation(positionMs: Long = if (::player.isInitialized) player.currentPosition else lastKnownLocalPosition) {
        if (::player.isInitialized && networkRecoveryIsSuppressed()) {
            uiHandler.postDelayed(networkStarvationRunnable, 3_000L)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNetworkRecoverAtMs < 4_000L) {
            uiHandler.postDelayed(networkStarvationRunnable, 4_000L)
            return
        }
        lastNetworkRecoverAtMs = now
        networkStarvationRecoveries++
        val retryPosition = positionMs.coerceAtLeast(lastKnownPositionForCurrentOutput()).coerceAtLeast(0L)
        android.util.Log.w("PlayerActivity", "Network starvation recovery at ${retryPosition}ms, retry #$networkStarvationRecoveries for $mediaPath")
        showNetworkBufferingMessage("Réseau instable, reprise du tampon…")
        try {
            if (!mediaPath.startsWith("http://", true) && !mediaPath.startsWith("https://", true)) {
                VideoStreamServerManager.startServer(applicationContext, mediaPath)
            }
            loadMedia(mediaPath, mediaName, retryPosition, autoPlay = true)
            scheduleNetworkStarvationWatch()
        } catch (e: Exception) {
            CrashReporter.log(this, "Network starvation recovery failed", e)
            if (networkStarvationRecoveries < 8) {
                uiHandler.postDelayed(networkStarvationRunnable, 5_000L)
            } else {
                showNetworkErrorDialog()
            }
        }
    }

    /** Certains NAS/serveurs UPnP coupent silencieusement une connexion HTTP/SMB 4K : ExoPlayer
     *  peut alors recevoir une fin de flux propre au lieu d'une erreur réseau. Sans cette garde,
     *  l'app croyait que la vidéo était terminée, enchaînait/quittait le lecteur et revenait à
     *  l'accueil. Pour les médias réseau uniquement, si STATE_ENDED arrive loin avant la durée
     *  réelle, on traite ça comme une coupure réseau et on relance le même média au même offset. */
    private fun shouldRecoverPrematureNetworkEnd(): Boolean {
        if (!isNetworkPlayback()) return false
        if (!::player.isInitialized) return false
        if (closingPlayerExplicitly || videoStoppedByUser || networkPlaybackReachedNaturalEnd) return false
        if (networkEarlyEndRecoveries >= 8) return false

        val duration = player.duration
        val position = player.currentPosition.coerceAtLeast(lastKnownPositionForCurrentOutput()).coerceAtLeast(0L)

        // Certains flux SMB/UPnP/HTTP locaux ne publient pas de durée fiable. Hors file
        // d'attente, un STATE_ENDED après quelques secondes est souvent une coupure réseau/proxy.
        // En file d'attente (et surtout en Cast), on privilégie l'enchaînement demandé par
        // l'utilisateur au lieu de bloquer sur une reprise infinie du même média.
        if (duration <= 0 || duration == C.TIME_UNSET) return videoQueuePaths.isEmpty() && position > 5_000L

        val remaining = duration - position
        if (remaining <= 8_000L) {
            networkPlaybackReachedNaturalEnd = true
            return false
        }
        return true
    }

    private fun recoverPrematureNetworkEnd() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNetworkRecoverAtMs < 2_500L) return
        lastNetworkRecoverAtMs = now
        networkEarlyEndRecoveries++
        val retryPosition = player.currentPosition.coerceAtLeast(lastKnownPositionForCurrentOutput()).coerceAtLeast(0L)
        android.util.Log.w("PlayerActivity", "Premature network end at ${retryPosition}ms, retry #$networkEarlyEndRecoveries for $mediaPath")
        updatePlayPauseBtn(false)
        cancelHide()
        showUI()
        try {
            // Redémarre aussi le relais local pour forcer un nouveau handle SMB/HTTP propre.
            if (!mediaPath.startsWith("http://", true) && !mediaPath.startsWith("https://", true)) {
                VideoStreamServerManager.startServer(applicationContext, mediaPath)
            }
            loadMedia(mediaPath, mediaName, retryPosition, autoPlay = true)
        } catch (e: Exception) {
            CrashReporter.log(this, "Network premature end recovery failed", e)
            showNetworkErrorDialog()
        }
    }

    private fun shouldRecoverPrematureLocalEnd(): Boolean {
        if (isNetworkPlayback()) return false
        if (!::player.isInitialized) return false
        if (closingPlayerExplicitly || videoStoppedByUser) return false
        if (prematureLocalEndRecoveries >= 3) return false
        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return false
        val position = player.currentPosition.coerceAtLeast(lastKnownLocalPosition).coerceAtLeast(0L)
        return position > 3_000L && duration - position > 12_000L
    }

    private fun recoverPrematureLocalEnd() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLocalRecoverAtMs < 2_500L) return
        lastLocalRecoverAtMs = now
        prematureLocalEndRecoveries++
        val retryPosition = player.currentPosition.coerceAtLeast(lastKnownLocalPosition).coerceAtLeast(0L)
        android.util.Log.w("PlayerActivity", "Premature local end at ${retryPosition}ms, retry #$prematureLocalEndRecoveries for $mediaPath")
        updatePlayPauseBtn(false)
        cancelHide()
        showUI()
        try {
            loadMedia(mediaPath, mediaName, retryPosition, autoPlay = true)
        } catch (e: Exception) {
            CrashReporter.log(this, "Local premature end recovery failed", e)
            InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.error_loading_media))
        }
    }

    private fun loadNotificationMetadata(path: String, name: String) {
        if (name.isBlank()) return
        val args = android.os.Bundle().apply {
            putString(fr.retrospare.blazeplayer.player.VideoPlaybackService.EXTRA_ARTWORK_MEDIA_ID, path)
            putString(fr.retrospare.blazeplayer.player.VideoPlaybackService.EXTRA_METADATA_TITLE, name.substringBeforeLast('.').ifBlank { name })
        }
        (player as? MediaController)?.sendCustomCommand(
            androidx.media3.session.SessionCommand(
                fr.retrospare.blazeplayer.player.VideoPlaybackService.CUSTOM_COMMAND_SET_VIDEO_METADATA,
                android.os.Bundle.EMPTY
            ),
            args
        )
    }

    /** Récupère la miniature déjà en cache et l'ajoute aux métadonnées du média.
     *
     * Pour les vidéos réseau, on évite volontairement toute extraction MediaMetadataRetriever
     * pendant la lecture : cela ouvre un second flux/décodeur en parallèle du player et peut
     * créer des retards de rendu vidéo alors que l'audio continue normalement. */
    private fun loadNotificationArtwork(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val networkPath = path.startsWith("smb://", true) || path.startsWith("ftp://", true) ||
                path.startsWith("http://", true) || path.startsWith("https://", true)

            val artworkData = fr.retrospare.blazeplayer.ui.ThumbnailUtils
                .getCachedThumbnailJpegBytes(applicationContext, path) ?: run {
                if (networkPath) {
                    android.util.Log.i("PlayerActivity", "Skip live network thumbnail extraction during playback for $path")
                    return@launch
                }

                val bitmap = try {
                    withTimeoutOrNull(2_000) {
                        fr.retrospare.blazeplayer.ui.ThumbnailUtils.getThumbnailBitmap(applicationContext, path, 5_000_000L)
                    }
                } catch (e: Exception) {
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video notification thumbnail extraction failed", e)
                    null
                } ?: return@launch

                try {
                    val scaled = if (bitmap.width > 512 || bitmap.height > 512) {
                        val ratio = 512f / maxOf(bitmap.width, bitmap.height)
                        android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    } else bitmap
                    val stream = java.io.ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, stream)
                    stream.toByteArray()
                } catch (e: Exception) {
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video notification thumbnail encoding failed", e)
                    null
                }
            } ?: return@launch

            withContext(Dispatchers.Main) {
                val current = player.currentMediaItem ?: return@withContext
                if (current.mediaId != path) return@withContext
                val args = android.os.Bundle().apply {
                    putString(fr.retrospare.blazeplayer.player.VideoPlaybackService.EXTRA_ARTWORK_MEDIA_ID, path)
                    putByteArray(fr.retrospare.blazeplayer.player.VideoPlaybackService.EXTRA_ARTWORK_DATA, artworkData)
                    putString(fr.retrospare.blazeplayer.player.VideoPlaybackService.EXTRA_METADATA_TITLE, mediaName.substringBeforeLast('.').ifBlank { mediaName })
                }
                val future = (player as? MediaController)?.sendCustomCommand(
                    androidx.media3.session.SessionCommand(
                        fr.retrospare.blazeplayer.player.VideoPlaybackService.CUSTOM_COMMAND_SET_VIDEO_METADATA,
                        android.os.Bundle.EMPTY
                    ),
                    args
                )
                future?.addListener({
                    try {
                        val result = future.get()
                        if (result.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                            android.util.Log.w("PlayerActivity", "Artwork command failed result=${result.resultCode}")
                        }
                    } catch (e: Exception) {
                        fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Video notification artwork command failed", e)
                    }
                }, androidx.core.content.ContextCompat.getMainExecutor(this@PlayerActivity))
            }
        }
    }

    private var currentRatioIndex = 0
    // "by lazy" : évalué au premier accès plutôt qu'à la construction de l'Activity. Les
    // getString() ici s'exécutaient sinon dans le constructeur, avant que le Context ne soit
    // prêt (avant attachBaseContext()/onCreate()) — ce qui provoquait un crash immédiat au clic
    // sur une vidéo ("Attempt to invoke virtual method getResources() on a null object
    // reference"), avant même que l'ANR ne puisse se manifester.
    private val ratioLabels by lazy { listOf(getString(R.string.ratio_auto), getString(R.string.ratio_zoom), getString(R.string.ratio_stretched), getString(R.string.ratio_full)) }


    private fun isCastingVideo(): Boolean =
        ::player.isInitialized && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    private fun hasVisualVideoAdjustments(): Boolean =
        videoSaturation.toInt() != 0 || videoContrast.toInt() != 0 || videoHue.toInt() != 0

    private fun desiredFilteredTextureView(): Boolean =
        hasVisualVideoAdjustments() && !isCastingVideo()

    private fun activePlayerView(): PlayerView =
        if (usingFilteredTextureView) binding.filteredPlayerView else binding.playerView

    private fun currentResizeMode(): Int = when (currentRatioIndex) {
        0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    private fun applyResizeModeToPlayerViews() {
        val mode = currentResizeMode()
        binding.playerView.resizeMode = mode
        binding.filteredPlayerView.resizeMode = mode
    }

    private fun attachPlayerToPreferredView() {
        val useFiltered = desiredFilteredTextureView()
        val target = if (useFiltered) binding.filteredPlayerView else binding.playerView
        val other = if (useFiltered) binding.playerView else binding.filteredPlayerView

        applyResizeModeToPlayerViews()
        if (usingFilteredTextureView != useFiltered) {
            other.player = null
            other.setRenderEffect(null)
            other.setLayerType(View.LAYER_TYPE_NONE, null)
            other.visibility = View.GONE
            target.visibility = View.VISIBLE
            usingFilteredTextureView = useFiltered
            android.util.Log.i(
                "PlayerActivity",
                if (useFiltered) "Video renderer switched to TextureView for visual adjustments"
                else "Video renderer switched to SurfaceView for smooth neutral playback"
            )
        } else {
            target.visibility = View.VISIBLE
            other.visibility = View.GONE
        }

        if (::player.isInitialized && target.player !== player) target.player = player
        if (other.player != null) other.player = null
        target.setShutterBackgroundColor(android.graphics.Color.BLACK)
        target.setBackgroundColor(android.graphics.Color.BLACK)
    }

    private fun detachPlayerViews() {
        // Détache les deux PlayerView sans rappel récursif.
        // La v13 appelait accidentellement detachPlayerViews() depuis elle-même,
        // ce qui provoquait un StackOverflowError au stop/retour/destruction du lecteur.
        try { binding.playerView.player = null } catch (_: Exception) {}
        try { binding.filteredPlayerView.player = null } catch (_: Exception) {}
        try { binding.playerView.setRenderEffect(null) } catch (_: Exception) {}
        try { binding.filteredPlayerView.setRenderEffect(null) } catch (_: Exception) {}
        try { binding.playerView.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Exception) {}
        try { binding.filteredPlayerView.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Exception) {}
    }

    private fun cycleAspectRatio() {
        currentRatioIndex = (currentRatioIndex + 1) % ratioLabels.size
        applyResizeModeToPlayerViews()
        binding.seekIndicator.text = ratioLabels[currentRatioIndex]
        binding.seekIndicator.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ binding.seekIndicator.visibility = View.GONE }, 2000)
    }

    private fun showAudioTracks() {
        if (isCastingVideo()) {
            InfoDialog.show(this, getString(R.string.info_dialog_title_unavailable), getString(R.string.cast_audio_language_unavailable), R.drawable.ic_cast)
            return
        }
        val tracks = player.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) {
            InfoDialog.show(this, getString(R.string.info_dialog_title_info), getString(R.string.toast_no_audio_track))
            return
        }
        val labels = audioGroups.mapIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val baseLang = format.language ?: getString(R.string.track_generic)
            val label = format.label
            when {
                !label.isNullOrBlank() -> label
                audioGroups.count { it.getTrackFormat(0).language == format.language } > 1 -> "$baseLang ${i + 1}"
                else -> baseLang
            }
        }
        val selectedIndex = audioGroups.indexOfFirst { it.isSelected }
        showTrackSelector(getString(R.string.audio_track), labels, selectedIndex) { i ->
            val override = TrackSelectionOverride(audioGroups[i].mediaTrackGroup, 0)
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(override)
                .build()
        }
    }

    /** Affiche un sélecteur de piste (audio ou sous-titre) sous forme de bottom sheet custom. */
    private fun showTrackSelector(title: String, labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_track_selector, null)
        dialogView.findViewById<android.widget.TextView>(R.id.tvTrackDialogTitle).text = title
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerTracks)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = labels.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_track_option, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val v = holder.itemView
                v.findViewById<android.widget.TextView>(R.id.tvTrackLabel).text = labels[position]
                v.findViewById<android.widget.ImageView>(R.id.ivTrackCheck).visibility =
                    if (position == selectedIndex) View.VISIBLE else View.INVISIBLE
                v.setOnClickListener { onSelect(position); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun showSubtitles() {
        if (isCastingVideo()) {
            InfoDialog.show(this, getString(R.string.info_dialog_title_unavailable), getString(R.string.cast_subtitles_unavailable), R.drawable.ic_cast)
            return
        }
        val tracks = player.currentTracks
        val subGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val labels = mutableListOf(getString(R.string.subtitles_disabled))
        subGroups.forEachIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val baseLang = format.language ?: "ST"
            val label = format.label
            labels.add(when {
                !label.isNullOrBlank() -> label
                subGroups.count { it.getTrackFormat(0).language == format.language } > 1 -> "$baseLang ${i + 1}"
                else -> baseLang
            })
        }
        val selectedIndex = if (subGroups.none { it.isSelected }) 0 else subGroups.indexOfFirst { it.isSelected } + 1
        showTrackSelector(getString(R.string.dialog_title_subtitles), labels, selectedIndex) { i ->
            if (i == 0) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
            } else {
                val selectedGroup = subGroups[i - 1]
                val override = TrackSelectionOverride(selectedGroup.mediaTrackGroup, 0)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .addOverride(override)
                    .build()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) {
            attachPlayerToPreferredView()
        }
    }

    override fun onResume() {
        super.onResume()
        openingCastRemote = false
        applyImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        if (!openingCastRemote && prefPip && player.isPlaying) enterPipIfEnabled()
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) return
        if (!::player.isInitialized) return
        if (prefRememberVolume) {
            val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            lifecycleScope.launch { dataStore.edit { it[intPreferencesKey("saved_volume")] = vol } }
        }
        saveCurrentVideoPosition()
        if (closingPlayerExplicitly) {
            detachPlayerViews()
            return
        }
        // Home / app switch : on détache uniquement la surface pour éviter les leaks UI.
        // La lecture continue dans VideoPlaybackService ; si le PiP est activé, Android garde
        // la surface PiP. L'arrêt réel se fait dans VideoPlaybackService.onTaskRemoved() lors
        // du swipe depuis les tâches récentes.
        detachPlayerViews()
    }

    override fun onDestroy() {
        resumeChoiceDialog?.dismiss()
        resumeChoiceDialog = null
        pendingCastReplacementVerification?.let(uiHandler::removeCallbacks)
        pendingCastReplacementVerification = null
        ChromecastRemoteCommandBridge.detach(this)
        detachPlayerViews()
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        // Si l'utilisateur ferme la fenêtre Picture-in-Picture avec la croix système, Android
        // détruit l'Activity PiP sans passer par notre bouton Stop. Dans ce cas seulement, on
        // coupe la lecture et le service pour supprimer immédiatement la notification vidéo.
        if (hasEnteredPip && !isChangingConfigurations) {
            try {
                if (::player.isInitialized) {
                    player.pause()
                    player.stop()
                    player.clearMediaItems()
                }
            } catch (e: Exception) {
                CrashReporter.log(this, "Failed to stop video after PiP close", e)
            }
            try { stopService(android.content.Intent(this, VideoPlaybackService::class.java)) } catch (e: Exception) {
                CrashReporter.log(this, "Failed to stop VideoPlaybackService after PiP close", e)
            }
            try { VideoStreamServerManager.stopServer() } catch (e: Exception) {
                CrashReporter.log(this, "Failed to stop local video stream server after PiP close", e)
            }
        }
        // Le relais appartient au service vidéo. Une destruction transitoire de l'Activity
        // (rotation, changement de surface, ouverture de la télécommande) ne doit jamais couper
        // l'URL déjà chargée par le Chromecast. Les chemins explicites Stop/Retour arrêtent déjà
        // le service et le relais ensemble.
        // Ne pas relancer artificiellement le service audio ici : après un swipe de fermeture,
        // cela recréait une notification audio fantôme. La reprise audio doit venir d'une action
        // utilisateur explicite ou d'un contrôleur déjà connecté, pas de la destruction du player.
    }

    /** Met en pause la lecture audio (BlazePlayerService) sans arrêter le service ni sa
     *  notification, pour pouvoir la relancer facilement une fois la vidéo terminée. */
    private fun pauseAudioPlaybackKeepingNotification() {
        // Ne jamais binder/démarrer BlazePlayerService uniquement pour mettre en pause une session
        // qui n'existe pas. Au premier lancement (mini-player désactivé par défaut), cette garde
        // évite qu'une simple lecture vidéo ne crée le service audio en arrière-plan.
        if (!BlazePlayerService.isAudioSessionActive) return
        try {
            val audioToken = SessionToken(this, android.content.ComponentName(this, BlazePlayerService::class.java))
            val audioControllerFuture = MediaController.Builder(this, audioToken).buildAsync()
            audioControllerFuture.addListener({
                try {
                    val controller = audioControllerFuture.get()
                    if (controller.isPlaying) controller.pause()
                } catch (e: Exception) {
                    android.util.Log.w("PlayerActivity", "pauseAudioPlaybackKeepingNotification failed", e)
                } finally {
                    MediaController.releaseFuture(audioControllerFuture)
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            android.util.Log.w("PlayerActivity", "pauseAudioPlaybackKeepingNotification failed", e)
        }
    }

    /** Avertissement informatif (non bloquant) si le Chromecast connecté est probablement
     *  incompatible avec cette vidéo — la bascule automatique de CastPlayer a de toute façon déjà
     *  démarré à ce stade, donc on informe plutôt que d'essayer d'intercepter. */
    private fun warnIfIncompatible(modelName: String?) {
        if (compatWarningShown) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = withTimeoutOrNull(2_000) {
                    VideoMetadataExtractor.extractLight(applicationContext, mediaPath)
                } ?: VideoTechnicalInfo()
                val reason = fr.retrospare.blazeplayer.cast.ChromecastCompatibility.incompatibilityReason(info, modelName)
                if (reason != null) {
                    compatWarningShown = true
                    withContext(Dispatchers.Main) {
                        if (isDestroyed || isFinishing) return@withContext
                        InfoDialog.show(
                            this@PlayerActivity,
                            getString(R.string.info_dialog_title_chromecast),
                            getString(R.string.toast_chromecast_incompatible, modelName ?: getString(R.string.unknown_model), reason),
                            R.drawable.ic_cast
                        )
                    }
                }
            } catch (e: Exception) {
                CrashReporter.log(this@PlayerActivity, "Chromecast compatibility check failed for $mediaPath", e)
            }
        }
    }

    private fun onPlayerReady() {
        attachPlayerToPreferredView()
        applyVideoAdjustments()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e(
                    "PlayerActivity",
                    "onPlayerError code=${error.errorCode} name=${PlaybackException.getErrorCodeName(error.errorCode)} " +
                        "message=${error.message} cause=${error.cause}",
                    error
                )
                val isCasting = ::player.isInitialized && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (mediaReplacementInProgress && isCasting) {
                    // Le LOAD distant peut produire un état transitoire pendant le remplacement.
                    // La vérification dédiée effectuera au maximum deux retries propres, sans
                    // réutiliser la position de l'ancienne vidéo.
                    pendingCastReplacementVerification?.let(uiHandler::removeCallbacks)
                    pendingCastReplacementVerification?.let { uiHandler.postDelayed(it, 500L) }
                    return
                }
                if (isNetworkPlayback() || isCasting) {
                    runOnUiThread {
                        if (networkStarvationRecoveries < 8) {
                            recoverNetworkStarvation(player.currentPosition.coerceAtLeast(lastKnownPositionForCurrentOutput()))
                        } else {
                            showNetworkErrorDialog()
                        }
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                runOnUiThread { updatePipParamsIfSupported() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                runOnUiThread {
                    updatePlayPauseBtn(isPlaying)
                    updatePipParamsIfSupported()
                    if (isPlaying) {
                        scheduleHide()
                        if (!resumeHandled && !resumeDecisionPending) handleResume()
                    } else {
                        cancelHide()
                        showUI()
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                val stateName = when (state) {
                    Player.STATE_IDLE -> "STATE_IDLE"
                    Player.STATE_BUFFERING -> "STATE_BUFFERING"
                    Player.STATE_READY -> "STATE_READY"
                    Player.STATE_ENDED -> "STATE_ENDED"
                    else -> "UNKNOWN($state)"
                }
                android.util.Log.i(
                    "CAST",
                    "onPlaybackStateChanged: $stateName pos=${player.currentPosition} buffered=${player.bufferedPosition} duration=${player.duration} network=${isNetworkPlayback()} remote=${player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE}"
                )
                if (state == Player.STATE_BUFFERING && isNetworkPlayback()) {
                    showNetworkBufferingMessage()
                    // Le watcher reste armé, mais sa propre garde attend la fin de la fenêtre de
                    // grâce Cast avant toute récupération. Ainsi un vrai blocage finit quand même
                    // par être traité sans envoyer de LOAD concurrent pendant la transition.
                    scheduleNetworkStarvationWatch()
                    return
                }
                if (state == Player.STATE_READY) {
                    cancelNetworkStarvationWatch()
                    if (isNetworkPlayback()) networkStarvationRecoveries = 0
                    ensureRemotePlaybackStarted("remote reached READY")
                    confirmExpectedMediaLoadAtReady()
                    if (mediaReplacementInProgress && player.currentMediaItem?.mediaId == mediaPath &&
                        player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE
                    ) {
                        // En local READY suffit. En Cast, seule la vérification du contentUrl
                        // distant peut confirmer que le récepteur a réellement accepté le média.
                        mediaReplacementInProgress = false
                        castReplacementRetryCount = 0
                        pendingCastReplacementVerification?.let(uiHandler::removeCallbacks)
                        pendingCastReplacementVerification = null
                    }
                }
                if (state == Player.STATE_IDLE && isNetworkPlayback() && !mediaReplacementInProgress &&
                    !closingPlayerExplicitly && !videoStoppedByUser && !networkRecoveryIsSuppressed() &&
                    player.currentMediaItem != null
                ) {
                    runOnUiThread { recoverNetworkStarvation(player.currentPosition.coerceAtLeast(lastKnownPositionForCurrentOutput())) }
                    return
                }
                if (state == Player.STATE_ENDED) {
                    if (mediaReplacementInProgress) return
                    runOnUiThread {
                        cancelNetworkStarvationWatch()
                        if (shouldRecoverPrematureNetworkEnd()) {
                            recoverPrematureNetworkEnd()
                            return@runOnUiThread
                        }
                        if (shouldRecoverPrematureLocalEnd()) {
                            recoverPrematureLocalEnd()
                            return@runOnUiThread
                        }
                        updatePlayPauseBtn(false)
                        cancelHide()
                        showUI()
                        if (!playNextCalled) {
                            playNextCalled = true
                            if (prefAutoPlay) {
                                if (videoQueuePaths.isNotEmpty()) {
                                    // La file d'attente vidéo est maintenant reliée au réglage global
                                    // "Lecture automatique suivante" : OFF = arrêt en fin de vidéo,
                                    // ON = enchaînement de l'élément suivant, y compris en Cast.
                                    playNext()
                                } else if (!isNetworkPlayback() || networkPlaybackReachedNaturalEnd) {
                                    playNext()
                                }
                            }
                        }
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                android.util.Log.i("CAST", "onMediaItemTransition: mediaId=${mediaItem?.mediaId} uri=${mediaItem?.localConfiguration?.uri} reason=$reason")
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                val isRemote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                binding.castBlackout.visibility = if (isRemote) View.VISIBLE else View.GONE
                cancelHide()
                showUI()

                // Media3 1.9 CastPlayer transfère automatiquement l'état entre ExoPlayer et
                // RemoteCastPlayer. Ne jamais recharger ici : recharger pendant la transition Cast
                // créait des LOAD concurrents, tracks=[] et des retours Surface invalides.
                if (isRemote != lastKnownIsRemote) {
                    val previousWasRemote = lastKnownIsRemote
                    lastKnownIsRemote = isRemote
                    android.util.Log.i("CAST", "Transition ${if (isRemote) "vers" else "depuis"} Cast détectée")
                    // STATE_IDLE et currentMediaItem=null sont normaux pendant un transfert. Ils ne
                    // doivent jamais être interprétés comme une coupure réseau, sinon l'app envoie
                    // un second LOAD à 0 ms qui annule la reprise choisie.
                    val transferPosition = if (isRemote) {
                        maxOf(lastKnownLocalPosition, player.currentPosition.coerceAtLeast(0L))
                    } else if (previousWasRemote) {
                        maxOf(lastKnownRemotePosition, player.currentPosition.coerceAtLeast(0L))
                    } else 0L
                    // Le lancement d'un Cast est toujours une intention de lecture. Ne pas
                    // recopier un éventuel état pause local vers le Chromecast.
                    markExpectedMediaLoad(mediaPath, transferPosition, autoPlay = isRemote || player.playWhenReady, graceMs = 18_000L)
                    applyVideoAdjustments(applyVolumeBoost = false)
                    applySubtitleTrackSelection()
                    if (isRemote) scheduleRemoteAutoplayChecks("device switched to Cast")
                }

                if (isRemote) {
                    val session = try {
                        com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext)
                            .sessionManager.currentCastSession
                    } catch (e: Exception) { null }
                    val deviceName = session?.castDevice?.friendlyName
                    binding.tvSubtitle.text = if (deviceName != null) getString(R.string.casting_on_device, deviceName) else getString(R.string.casting_chromecast)
                    binding.tvSubtitle.visibility = View.VISIBLE
                    binding.castStatusCard.visibility = View.GONE
                    binding.tvCastDeviceName.text = deviceName ?: getString(R.string.casting_chromecast)
                    binding.tvCastMediaTitle.text = mediaName.ifBlank { binding.tvTitle.text?.toString().orEmpty() }
                    // En Cast, l'écran local devient noir, mais les contrôles doivent rester
                    // utilisables : ils pilotent le CastPlayer distant via Media3.
                    binding.centerTransportControls.visibility = View.VISIBLE
                    binding.bottomControlsContainer.visibility = View.VISIBLE
                    binding.touchZoneLeft.visibility = View.GONE
                    binding.touchZoneRight.visibility = View.GONE
                    warnIfIncompatible(session?.castDevice?.modelName)
                } else {
                    binding.tvSubtitle.visibility = View.GONE
                    binding.castStatusCard.visibility = View.GONE
                    binding.centerTransportControls.visibility = View.VISIBLE
                    binding.bottomControlsContainer.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun savedResumePositionMs(path: String = mediaPath): Long {
        return getSharedPreferences("blaze_positions", MODE_PRIVATE).getLong(path, 0L)
    }

    /** Applique le modèle de reprise pour le player local ET pour CastPlayer.
     *
     * Avant ce correctif, le Cast pouvait recevoir le MediaItem et démarrer à 0 avant que le
     * modal "reprendre / recommencer" ne soit pris en compte. Désormais, en mode "demander",
     * la lecture est préparée mais reste en pause jusqu'au choix ; le seek/play qui suit pilote
     * le même Player Media3, donc la TV et le téléphone restent synchronisés.
     */
    private fun showResumeChoice(playAfterChoice: Boolean = false): Boolean {
        if (!::player.isInitialized || resumeHandled || resumeDecisionPending || prefResumeMode == 2) return false
        val savedMs = savedResumePositionMs()
        if (savedMs <= 3000L) {
            resumeHandled = true
            return false
        }

        when (prefResumeMode) {
            0 -> {
                resumeHandled = true
                player.seekTo(savedMs)
                if (playAfterChoice) player.play()
                return true
            }
            1 -> {
                resumeHandled = true
                val wasPlaying = player.isPlaying
                if (wasPlaying) player.pause()
                cancelHide()
                showUI()
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.settings_resume_playback))
                    .setMessage(getString(R.string.dialog_resume_message, savedMs / 60000, (savedMs / 1000) % 60))
                    .setPositiveButton(getString(R.string.action_resume)) { _, _ ->
                        player.seekTo(savedMs)
                        if (playAfterChoice || wasPlaying) player.play()
                        scheduleHide()
                    }
                    .setNegativeButton(getString(R.string.action_from_beginning)) { _, _ ->
                        player.seekTo(0)
                        if (playAfterChoice || wasPlaying) player.play()
                        scheduleHide()
                    }
                    .setOnCancelListener {
                        // Fermeture par retour/tap extérieur : comportement sûr et prévisible,
                        // on recommence au début au lieu de laisser un Cast en pause indéfiniment.
                        player.seekTo(0)
                        if (playAfterChoice || wasPlaying) player.play()
                        scheduleHide()
                    }
                    .showPremium()
                return true
            }
        }
        return false
    }

    private fun handleResume() {
        showResumeChoice(playAfterChoice = false)
    }

    /** Modal de reprise AVANT chargement du média.
     *
     * Utilisé à l'ouverture initiale et après STOP -> PLAY. C'est volontairement séparé de
     * showResumeChoice(), qui agit sur un Player déjà chargé. Pour Cast, cette variante évite
     * d'envoyer un LOAD au Chromecast avant la décision de l'utilisateur.
     */
    private fun showInitialResumeChoice(
        savedMs: Long,
        targetPath: String = mediaPath,
        targetName: String = mediaName,
        requestId: Long = mediaSwitchRequestId
    ) {
        if (isFinishing || isDestroyed) return
        if (resumeChoiceDialog?.isShowing == true && resumeDialogRequestId == requestId) return
        resumeHandled = true
        resumeDecisionPending = true
        cancelHide()
        showUI()
        resumeChoiceDialog?.dismiss()
        resumeDialogRequestId = requestId
        android.util.Log.i("CAST", "Modal reprise affiché path=$targetPath savedMs=$savedMs request=$requestId")

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_resume_playback))
            .setMessage(getString(R.string.dialog_resume_message, savedMs / 60000, (savedMs / 1000) % 60))
            .setPositiveButton(getString(R.string.action_resume)) { _, _ ->
                if (requestId != mediaSwitchRequestId || targetPath != mediaPath) return@setPositiveButton
                resumeDecisionPending = false
                android.util.Log.i("CAST", "Choix reprise path=$targetPath positionMs=$savedMs request=$requestId")
                loadMedia(targetPath, targetName, savedMs, autoPlay = true)
                scheduleHide()
            }
            .setNegativeButton(getString(R.string.action_from_beginning)) { _, _ ->
                if (requestId != mediaSwitchRequestId || targetPath != mediaPath) return@setNegativeButton
                resumeDecisionPending = false
                android.util.Log.i("CAST", "Choix début path=$targetPath request=$requestId")
                loadMedia(targetPath, targetName, 0L, autoPlay = true)
                scheduleHide()
            }
            .setOnCancelListener {
                if (requestId != mediaSwitchRequestId || targetPath != mediaPath) return@setOnCancelListener
                resumeDecisionPending = false
                android.util.Log.i("CAST", "Modal reprise annulé, début path=$targetPath request=$requestId")
                loadMedia(targetPath, targetName, 0L, autoPlay = true)
                scheduleHide()
            }
            .showPremium()
        resumeChoiceDialog = dialog
        dialog.setOnDismissListener {
            if (resumeChoiceDialog === dialog) {
                resumeChoiceDialog = null
                resumeDialogRequestId = Long.MIN_VALUE
            }
        }
    }



    private fun saveCurrentVideoPosition() {
        if (!::player.isInitialized || mediaPath.isEmpty()) return
        val current = try { player.currentPosition } catch (_: Exception) { 0L }
        val tracked = try {
            if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) lastKnownRemotePosition else lastKnownLocalPosition
        } catch (_: Exception) { 0L }
        // MediaController peut parfois renvoyer l'ancienne position juste avant/pendant stop().
        // On garde donc la position la plus récente observée par la boucle de progression si elle
        // est plus avancée, ce qui corrige le cas: reprise à 30 min puis fermeture à 55 min.
        val pos = when {
            tracked > current + 1500L -> tracked
            current > 0L -> current
            tracked > 0L -> tracked
            else -> 0L
        }
        persistVideoPosition(pos, immediate = true)
    }

    private fun persistVideoPosition(positionMs: Long, immediate: Boolean = false) {
        if (mediaPath.isEmpty() || positionMs <= 0L) return
        val editor = getSharedPreferences("blaze_positions", MODE_PRIVATE).edit().putLong(mediaPath, positionMs)
        if (immediate) editor.commit() else editor.apply()
        lifecycleScope.launch { mediaRepository.updateProgress(mediaPath, positionMs) }
    }

    private fun stopVideoPlaybackAndNotification() {
        closingPlayerExplicitly = true
        saveCurrentVideoPosition()
        try {
            if (::player.isInitialized) {
                player.pause()
                player.stop()
                player.clearMediaItems()
            }
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to stop video playback on back", e)
        }
        detachPlayerViews()
        try { stopService(android.content.Intent(this, VideoPlaybackService::class.java)) } catch (e: Exception) {
            CrashReporter.log(this, "Failed to stop VideoPlaybackService on back", e)
        }
        try { VideoStreamServerManager.stopServer() } catch (e: Exception) {
            CrashReporter.log(this, "Failed to stop local video stream server on back", e)
        }
    }

    private fun goBackToHistory() {
        // Ne relance plus MainActivity avec NEW_TASK/CLEAR_TOP. Sur les flux réseau 4K, ce chemin
        // créait un clear-task-stack : PlayerActivity était détruite et la vidéo revenait à l'accueil
        // comme si la lecture était terminée. MainActivity est déjà sous le lecteur dans la pile ;
        // il suffit de finir explicitement le lecteur quand l'utilisateur appuie sur retour.
        closingPlayerExplicitly = true
        if (isCastingVideo()) {
            // Pendant un cast, la flèche retour ne doit fermer que cet écran, pas la diffusion sur
            // le second écran : on ne touche ni à player.stop()/clearMediaItems() (interprété comme
            // un STOP de session par VideoPlaybackService, cf. loadMedia() plus haut), ni au service,
            // ni au serveur de streaming local qui relaie le flux vers le Chromecast. Seule la sortie
            // complète de l'application (VideoPlaybackService.onTaskRemoved) doit couper le cast.
            detachPlayerViews()
            finish()
            return
        }
        stopVideoPlaybackAndNotification()
        finish()
    }

    private fun showNetworkErrorDialog() {
        if (networkErrorDialogShown) return
        networkErrorDialogShown = true
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_network_error))
            .setMessage(getString(R.string.dialog_network_error_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.action_retry)) { _, _ ->
                networkErrorDialogShown = false
                player.prepare()
                player.play()
            }
            .setNegativeButton(getString(R.string.action_quit)) { _, _ -> goBackToHistory() }
            .showPremium()
    }

    private fun showQuickVideoInfo() {
        val duration = if (::player.isInitialized && player.duration > 0) formatTime(player.duration) else "--:--"
        val sizeLabel = if (mediaPath.startsWith("smb://") || mediaPath.startsWith("ftp://")) {
            getString(R.string.category_network)
        } else {
            getString(R.string.tab_local)
        }
        binding.seekIndicator.text = "$mediaName • $duration • $sizeLabel"
        binding.seekIndicator.visibility = View.VISIBLE
        uiHandler.postDelayed({ binding.seekIndicator.visibility = View.GONE }, 2600)
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
        applyResponsivePlayerLayout()
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Ajustements fins du layout vidéo selon l'orientation.
     *
     * Portrait : les boutons -10/+10 sont placés plus près du vrai milieu entre le bouton Play
     * et les barres tactiles latérales, pour éviter l'impression de contrôles éparpillés.
     * Paysage : la timeline est volontairement plus courte, et les barres tactiles remontent un
     * peu en se rapprochant du centre afin de ne plus chevaucher les contrôles système/lecteur.
     */
    private fun applyResponsivePlayerLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        (binding.playPauseStack.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val margin = if (landscape) dp(34) else dp(18)
            lp.marginStart = margin
            lp.marginEnd = margin
            binding.playPauseStack.layoutParams = lp
        }

        binding.bottomControlsContainer.setPadding(
            if (landscape) dp(54) else dp(14),
            binding.bottomControlsContainer.paddingTop,
            if (landscape) dp(54) else dp(14),
            if (landscape) dp(14) else dp(18)
        )

        binding.timelineRow.setPadding(
            if (landscape) dp(10) else dp(2),
            binding.timelineRow.paddingTop,
            if (landscape) dp(10) else dp(2),
            binding.timelineRow.paddingBottom
        )

        fun tuneTouchPanel(panel: View, gravitySide: Int) {
            val lp = (panel.layoutParams as? FrameLayout.LayoutParams) ?: return
            lp.gravity = android.view.Gravity.CENTER_VERTICAL or gravitySide
            if (gravitySide == android.view.Gravity.START) {
                lp.marginStart = if (landscape) dp(72) else dp(24)
                lp.marginEnd = 0
            } else {
                lp.marginEnd = if (landscape) dp(72) else dp(24)
                lp.marginStart = 0
            }
            panel.translationY = if (landscape) -dp(38).toFloat() else 0f
            panel.layoutParams = lp
        }
        tuneTouchPanel(binding.touchZoneLeft, android.view.Gravity.START)
        tuneTouchPanel(binding.touchZoneRight, android.view.Gravity.END)
    }


    private fun monitorNetworkTrialExpiry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val state = userRepository.currentAccessState()
                if (state.isTrialActive) {
                    delay((state.trialEndMillis - state.evaluatedAtMillis).coerceAtLeast(1L))
                }
                val refreshed = userRepository.currentAccessState()
                if (isNetworkMedia && !refreshed.hasProAccess && !isFinishing && !isDestroyed) {
                    fr.retrospare.blazeplayer.paywall.AccessGateUi.redirectToPaywall(this@PlayerActivity)
                }
            }
        }
    }

    private fun setupCastButton() {
        // Bouton Cast natif dans le lecteur vidéo : il ouvre le sélecteur Chromecast et laisse
        // CastPlayer/Media3 transférer automatiquement le MediaItem courant, sa position et son état.
        // On diffère l'initialisation pour ne pas bloquer le premier frame si CastContext scanne le réseau.
        binding.btnHeaderCast.post {
            if (isFinishing || isDestroyed) return@post
            try {
                binding.btnHeaderCast.setDialogFactory(BlazeMediaRouteDialogFactory())
                com.google.android.gms.cast.framework.CastButtonFactory
                    .setUpMediaRouteButton(this, binding.btnHeaderCast)
                binding.btnHeaderCast.visibility = View.VISIBLE
            } catch (e: Exception) {
                CrashReporter.log(this, "Player Cast button setup failed", e)
                binding.btnHeaderCast.visibility = View.GONE
            }
        }
    }

    private fun isNetworkVideoPath(path: String): Boolean = path.startsWith("smb://", true) ||
        path.startsWith("ftp://", true) ||
        path.startsWith("http://", true) ||
        path.startsWith("https://", true)

    private fun currentVideoQueueCategory(): PlaylistCategory {
        return if (isNetworkMedia || isNetworkVideoPath(mediaPath) || videoQueuePaths.any { isNetworkVideoPath(it) }) {
            PlaylistCategory.NETWORK_VIDEO
        } else {
            PlaylistCategory.LOCAL_VIDEO
        }
    }

    private fun syncPlayerQueueFromStoredVideoQueue(category: PlaylistCategory) {
        val queue = VideoQueueManager.getQueue(this, category)
        if (queue.isEmpty()) {
            videoQueuePaths.clear()
            videoQueueNames.clear()
            videoQueueIndex = 0
            return
        }
        videoQueuePaths = ArrayList(queue.map { it.path })
        videoQueueNames = ArrayList(queue.map { it.name })
        val current = queue.indexOfFirst { it.path == mediaPath }
        videoQueueIndex = if (current >= 0) current else videoQueueIndex.coerceIn(0, queue.size - 1)
    }

    private fun showVideoQueueOverlay() {
        cancelHide()
        val category = currentVideoQueueCategory()
        VideoQueueSheet.show(
            context = this,
            category = category,
            currentPath = mediaPath,
            onItemSelected = { queue, index ->
                if (queue.isNotEmpty() && index in queue.indices) {
                    videoQueuePaths = ArrayList(queue.map { it.path })
                    videoQueueNames = ArrayList(queue.map { it.name })
                    videoQueueIndex = index
                    val selected = queue[index]
                    isNetworkMedia = category == PlaylistCategory.NETWORK_VIDEO || isNetworkVideoPath(selected.path)
                    switchTo(selected.path, selected.name)
                    scheduleHide()
                }
            },
            onChanged = {
                syncPlayerQueueFromStoredVideoQueue(category)
            }
        )
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { goBackToHistory() }

        binding.btnPlayPause.setOnClickListener {
            if (videoStoppedByUser) {
                lifecycleScope.launch { restartVideoAfterUserStop() }
                return@setOnClickListener
            }
            if (!::player.isInitialized) return@setOnClickListener
            if (player.isPlaying || player.playWhenReady) {
                pendingRemoteAutoplayMediaId = null
                player.pause()
            } else {
                player.playWhenReady = true
                player.play()
            }
            scheduleHide()
        }

        binding.btnScreenshot.setOnClickListener {
            captureCurrentVideoFrame()
            scheduleHide()
        }

        binding.btnRewind.setOnClickListener {
            if (!::player.isInitialized) return@setOnClickListener
            player.seekTo((player.currentPosition - seekMs()).coerceAtLeast(0))
            scheduleHide()
        }

        binding.btnForward.setOnClickListener {
            if (!::player.isInitialized) return@setOnClickListener
            val dur = player.duration.takeIf { it > 0 } ?: return@setOnClickListener
            player.seekTo((player.currentPosition + seekMs()).coerceAtMost(dur))
            scheduleHide()
        }

        binding.btnPrevious.setOnClickListener { scheduleHide(); playPrevious() }
        binding.btnNext.setOnClickListener { scheduleHide(); playNext() }
        binding.btnVideoQueue.setOnClickListener { showVideoQueueOverlay() }

        binding.btnRatio.setOnClickListener { scheduleHide(); cycleAspectRatio() }
        binding.btnSeekInfo.setOnClickListener { scheduleHide(); showQuickVideoInfo() }
        binding.btnAudio.setOnClickListener { scheduleHide(); showAudioTracks() }
        binding.btnSubtitles.setOnClickListener { scheduleHide(); showSubtitles() }
        binding.btnVideoSettings.setOnClickListener { cancelHide(); showVideoSettingsDialog() }
        binding.btnSpeed.setOnClickListener { scheduleHide(); cyclePlaybackSpeed() }
        binding.uiOverlay.setOnClickListener { if (uiVisible) hideUI() else showUI() }
        binding.playerView.setOnClickListener { if (uiVisible) hideUI() else showUI() }
        binding.filteredPlayerView.setOnClickListener { if (uiVisible) hideUI() else showUI() }

        // Stop réel : coupe la session vidéo, remet l'écran du lecteur au noir et laisse
        // VideoPlaybackService se détruire pour retirer immédiatement la notification Android.
        binding.btnStop.setOnClickListener {
            stopVideoPlaybackFromUi()
        }

        binding.btnStopCasting.setOnClickListener {
            stopChromecastFromUi()
        }

        binding.btnCastRemote.setOnClickListener {
            openingCastRemote = true
            startActivity(Intent(this, ChromecastRemoteActivity::class.java))
        }
    }

    override fun onChromecastRemoteCommand(command: ChromecastRemoteCommandBridge.Command): Boolean {
        if (!::player.isInitialized || isDestroyed || isFinishing) return false
        runOnUiThread {
            try {
                when (command) {
                    ChromecastRemoteCommandBridge.Command.PLAY_PAUSE -> {
                        if (videoStoppedByUser) {
                            lifecycleScope.launch { restartVideoAfterUserStop() }
                        } else if (player.isPlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                    }
                    ChromecastRemoteCommandBridge.Command.PREVIOUS -> playPrevious()
                    ChromecastRemoteCommandBridge.Command.NEXT -> playNext()
                    ChromecastRemoteCommandBridge.Command.SEEK_BACK -> {
                        player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    }
                    ChromecastRemoteCommandBridge.Command.SEEK_FORWARD -> {
                        val duration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration))
                    }
                    ChromecastRemoteCommandBridge.Command.STOP -> stopVideoPlaybackFromUi()
                }
                showUI()
            } catch (e: Exception) {
                CrashReporter.log(applicationContext, "Player remote command failed: $command", e)
            }
        }
        return true
    }

    private fun maybeCapturePlaybackThumbnail(posMs: Long, durMs: Long) {
        if (!::player.isInitialized || mediaPath.isBlank()) return
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
        val path = mediaPath
        if (playbackThumbnailCapturedPaths.contains(path) || playbackThumbnailCaptureInProgress) return
        if (ThumbnailUtils.hasCustomVideoThumbnail(applicationContext, path) ||
            ThumbnailUtils.hasCachedVideoPlaybackSnapshot(applicationContext, path)) {
            playbackThumbnailCapturedPaths.add(path)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val retryAt = playbackThumbnailRetryAfter[path] ?: 0L
        if (now < retryAt) return

        val thresholdMs = if (durMs in 1L until 20_000L) {
            5_000L.coerceAtMost((durMs * 0.60f).toLong().coerceAtLeast(1_000L))
        } else {
            20_000L
        }
        if (posMs < thresholdMs) return
        capturePlaybackThumbnailSilently(path)
    }

    private fun capturePlaybackThumbnailSilently(path: String) {
        if (isDestroyed || isFinishing) return
        playbackThumbnailCaptureInProgress = true

        fun failQuietly() {
            playbackThumbnailCaptureInProgress = false
            playbackThumbnailRetryAfter[path] = android.os.SystemClock.elapsedRealtime() + 7_500L
        }

        fun commit(bitmap: Bitmap?) {
            if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                failQuietly()
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val saved = ThumbnailUtils.cacheVideoPlaybackSnapshot(applicationContext, path, bitmap)
                withContext(Dispatchers.Main) {
                    playbackThumbnailCaptureInProgress = false
                    if (saved) {
                        playbackThumbnailCapturedPaths.add(path)
                        if (path == mediaPath) loadNotificationArtwork(path)
                    } else {
                        playbackThumbnailRetryAfter[path] = android.os.SystemClock.elapsedRealtime() + 10_000L
                    }
                }
            }
        }

        val activeView = activePlayerView()
        val textureView = activeView.videoSurfaceView as? TextureView
        if (textureView != null && textureView.isAvailable && textureView.width > 0 && textureView.height > 0) {
            val bitmap = try {
                textureView.bitmap ?: textureView.getBitmap(textureView.width, textureView.height)
            } catch (e: Exception) {
                CrashReporter.log(this, "Silent playback thumbnail TextureView capture failed for $path", e)
                null
            }
            commit(bitmap)
            return
        }

        val surfaceView = activeView.videoSurfaceView as? SurfaceView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && surfaceView != null &&
            surfaceView.width > 0 && surfaceView.height > 0 && surfaceView.holder.surface.isValid) {
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            val thread = HandlerThread("BlazeVideoSilentThumb").apply { start() }
            try {
                PixelCopy.request(surfaceView, bitmap, { result ->
                    try { thread.quitSafely() } catch (_: Exception) {}
                    runOnUiThread {
                        if (result == PixelCopy.SUCCESS) commit(bitmap) else {
                            try { bitmap.recycle() } catch (_: Exception) {}
                            failQuietly()
                        }
                    }
                }, Handler(thread.looper))
            } catch (e: Exception) {
                try { thread.quitSafely() } catch (_: Exception) {}
                try { bitmap.recycle() } catch (_: Exception) {}
                CrashReporter.log(this, "Silent playback thumbnail SurfaceView PixelCopy failed for $path", e)
                failQuietly()
            }
            return
        }

        failQuietly()
    }

    /** Capture uniquement la surface vidéo. Le lecteur principal utilise maintenant SurfaceView
     * pour un rendu vidéo plus fluide ; la capture passe donc par PixelCopy quand nécessaire. */
    private fun captureCurrentVideoFrame() {
        if (!::player.isInitialized) return
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            InfoDialog.show(this, getString(R.string.info_dialog_title_unavailable), getString(R.string.toast_capture_unavailable_cast), R.drawable.ic_camera)
            return
        }

        val activeView = activePlayerView()
        val textureView = activeView.videoSurfaceView as? TextureView
        if (textureView != null && textureView.isAvailable && textureView.width > 0 && textureView.height > 0) {
            val bitmap = try {
                textureView.bitmap ?: textureView.getBitmap(textureView.width, textureView.height)
            } catch (e: Exception) {
                CrashReporter.log(this, "Video screenshot TextureView capture failed for $mediaPath", e)
                null
            }
            handleCapturedVideoBitmap(bitmap)
            return
        }

        val surfaceView = activeView.videoSurfaceView as? SurfaceView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && surfaceView != null &&
            surfaceView.width > 0 && surfaceView.height > 0 && surfaceView.holder.surface.isValid) {
            val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            val thread = HandlerThread("BlazeVideoPixelCopy").apply { start() }
            try {
                PixelCopy.request(surfaceView, bitmap, { result ->
                    try { thread.quitSafely() } catch (_: Exception) {}
                    runOnUiThread {
                        if (result == PixelCopy.SUCCESS) {
                            handleCapturedVideoBitmap(bitmap)
                        } else {
                            try { bitmap.recycle() } catch (_: Exception) {}
                            InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.toast_capture_failed), R.drawable.ic_camera)
                        }
                    }
                }, Handler(thread.looper))
            } catch (e: Exception) {
                try { thread.quitSafely() } catch (_: Exception) {}
                try { bitmap.recycle() } catch (_: Exception) {}
                CrashReporter.log(this, "Video screenshot SurfaceView PixelCopy failed for $mediaPath", e)
                InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.toast_capture_failed), R.drawable.ic_camera)
            }
            return
        }

        InfoDialog.show(this, getString(R.string.info_dialog_title_unavailable), getString(R.string.toast_capture_unavailable), R.drawable.ic_camera)
    }

    private fun handleCapturedVideoBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.toast_capture_failed), R.drawable.ic_camera)
            return
        }
        val currentName = mediaName.ifBlank { File(mediaPath).name }.substringBeforeLast('.', mediaName.ifBlank { "video" })
        val filename = "${sanitizeScreenshotPart(currentName)}_${formatScreenshotTimecode(player.currentPosition)}_screenshot.jpg"
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = saveScreenshotBitmap(bitmap, filename)
            try { bitmap.recycle() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (saved) {
                    android.widget.Toast.makeText(this@PlayerActivity, getString(R.string.toast_screenshot_saved), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    InfoDialog.show(this@PlayerActivity, getString(R.string.info_dialog_title_error), getString(R.string.toast_screenshot_save_error), R.drawable.ic_camera)
                }
            }
        }
    }

    private fun saveScreenshotBitmap(bitmap: Bitmap, filename: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "Blaze Screenshots")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    } ?: return false
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (e: Exception) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    CrashReporter.log(this, "Video screenshot save failed for $mediaPath", e)
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Blaze Screenshots")
                if (!dir.exists() && !dir.mkdirs()) return false
                val file = File(dir, filename)
                file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                true
            }
        } catch (e: Exception) {
            CrashReporter.log(this, "Video screenshot save failed for $mediaPath", e)
            false
        }
    }

    private fun sanitizeScreenshotPart(raw: String): String {
        return raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .ifBlank { "video" }
            .take(80)
    }

    private fun formatScreenshotTimecode(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "%02d-%02d-%02d".format(Locale.US, hours, minutes, seconds)
    }

    /**
     * Bouton STOP du lecteur vidéo.
     *
     * Comportement attendu : contrairement au bouton Home, c'est une fermeture explicite de la
     * lecture. On remet l'image au noir, on vide la session Media3, on coupe le relais HTTP local
     * et on arrête VideoPlaybackService. Les contrôles restent cependant visibles afin que
     * l'utilisateur puisse relancer le média ou choisir une autre action.
     */
    private fun stopVideoPlaybackFromUi() {
        cancelHide()
        videoStoppedByUser = true
        try {
            detachPlayerViews()
            binding.playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
            binding.filteredPlayerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
            binding.playerView.setBackgroundColor(android.graphics.Color.BLACK)
            binding.filteredPlayerView.setBackgroundColor(android.graphics.Color.BLACK)
            binding.tvCurrentTime.text = "0:00:00"
            binding.tvTotalTime.text = "0:00:00"
            binding.progressFill.layoutParams.width = 0
            binding.progressFill.requestLayout()
            binding.progressThumb.translationX = 0f
            binding.progressBuffer.layoutParams.width = 0
            binding.progressBuffer.requestLayout()
            binding.ivPlayPause.setImageResource(R.drawable.ic_play)
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to reset video UI on stop", e)
        }

        if (::player.isInitialized) {
            try {
                player.pause()
                player.stop()
                player.clearMediaItems()
            } catch (e: Exception) {
                CrashReporter.log(this, "Failed to stop video player from stop button", e)
            }
        }

        try {
            VideoStreamServerManager.stopServer()
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to stop local video stream server from stop button", e)
        }

        try {
            stopService(android.content.Intent(this, VideoPlaybackService::class.java))
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to stop VideoPlaybackService from stop button", e)
        }

        // Un stop remet la vidéo à zéro mais ne transforme pas l'écran en surface vide :
        // les commandes restent visibles pour permettre une relance ou une nouvelle action.
        showUI()
        cancelHide()
    }


    private fun stopChromecastFromUi() {
        cancelHide()
        try {
            val castContext = com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext)
            castContext.sessionManager.endCurrentSession(true)
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to stop Chromecast session from player card", e)
        }
        try {
            binding.castStatusCard.visibility = View.GONE
            binding.castBlackout.visibility = View.GONE
            binding.centerTransportControls.visibility = View.VISIBLE
            binding.bottomControlsContainer.visibility = View.VISIBLE
            showUI()
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to reset cast card UI", e)
        }
    }

    private fun updateSpeedLabel() {
        val speed = SPEEDS.getOrElse(prefSpeedIndex) { 1.0f }
        binding.tvSpeedValue.text = String.format(Locale.US, "%.2f×", speed)
    }

    private fun cyclePlaybackSpeed() {
        if (!::player.isInitialized) return
        prefSpeedIndex = (prefSpeedIndex + 1) % SPEEDS.size
        val speed = SPEEDS.getOrElse(prefSpeedIndex) { 1.0f }
        player.setPlaybackSpeed(speed)
        updateSpeedLabel()
        binding.seekIndicator.text = String.format(Locale.US, "%.2f×", speed)
        binding.seekIndicator.visibility = View.VISIBLE
        uiHandler.postDelayed({ binding.seekIndicator.visibility = View.GONE }, 1400)
        lifecycleScope.launch {
            dataStore.edit { it[intPreferencesKey("speed_index")] = prefSpeedIndex }
        }
    }

    private fun seekMs() = when (prefSeekIndex) {
        0 -> 5_000L; 1 -> 10_000L; 2 -> 15_000L; 3 -> 30_000L; 4 -> 60_000L; else -> 10_000L
    }

    private fun setupProgressBar() {
        binding.progressContainer.setOnTouchListener { _, ev ->
            if (!::player.isInitialized) return@setOnTouchListener true
            val dur = player.duration.takeIf { it > 0 } ?: return@setOnTouchListener true
            val w = binding.progressContainer.width.toFloat().coerceAtLeast(1f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    seekBarDragging = true; cancelHide()
                    player.seekTo((ev.x / w * dur).toLong().coerceIn(0, dur))
                    updateProgressUI(player.currentPosition, dur)
                }
                MotionEvent.ACTION_MOVE -> {
                    val ms = (ev.x / w * dur).toLong().coerceIn(0, dur)
                    player.seekTo(ms)
                    updateProgressUI(ms, dur)
                    binding.seekIndicator.visibility = View.VISIBLE
                    binding.seekIndicator.text = formatTime(ms)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    seekBarDragging = false
                    binding.seekIndicator.visibility = View.GONE
                    player.play()
                    scheduleHide()
                }
            }
            true
        }
    }


    private suspend fun restartVideoAfterUserStop() {
        if (mediaPath.isBlank()) return
        cancelHide()
        try {
            VideoStreamServerManager.startServer(applicationContext, mediaPath)
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to restart local video stream server after user stop for $mediaPath", e)
            InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.error_loading_media))
            return
        }

        try {
            startService(android.content.Intent(this, VideoPlaybackService::class.java))
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to restart VideoPlaybackService after user stop", e)
            InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.error_loading_media))
            return
        }

        try {
            controllerFuture?.let { MediaController.releaseFuture(it) }
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to release old video MediaController after user stop", e)
        }

        val token = SessionToken(this, android.content.ComponentName(this, VideoPlaybackService::class.java))
        val newFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture = newFuture
        val controller = try {
            withContext(Dispatchers.IO) { newFuture.get(6, TimeUnit.SECONDS) }
        } catch (e: Exception) {
            CrashReporter.log(this, "Failed to reconnect video MediaController after user stop", e)
            InfoDialog.show(this, getString(R.string.info_dialog_title_error), getString(R.string.error_loading_media))
            return
        }

        player = controller
        attachPlayerToPreferredView()
        binding.playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
        binding.filteredPlayerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
        binding.playerView.setBackgroundColor(android.graphics.Color.BLACK)
        binding.filteredPlayerView.setBackgroundColor(android.graphics.Color.BLACK)
        playNextCalled = false
        // Après STOP puis PLAY, on relance le média comme une nouvelle ouverture :
        // le mode "demander" doit donc afficher le modal de reprise si une position existe,
        // y compris lorsque la sortie courante est un Chromecast.
        val savedMs = savedResumePositionMs(mediaPath)
        val askResume = prefResumeMode == 1 && savedMs > 3000L
        val startPosition = if (prefResumeMode == 0 && savedMs > 3000L) savedMs else 0L
        resumeHandled = prefResumeMode != 1 && startPosition > 0L
        resumeDecisionPending = askResume
        if (askResume) {
            showInitialResumeChoice(savedMs, mediaPath, mediaName, mediaSwitchRequestId)
        } else {
            resumeDecisionPending = false
            loadMedia(mediaPath, mediaName, startPosition, autoPlay = true)
        }
        startProgressLoop()
        showUI()
        scheduleHide()
    }

    private fun showVideoSettingsDialog() {
        showUI()

        val dialog = android.app.Dialog(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            setBackgroundResource(R.drawable.bg_cast_status_card)
        }

        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val iconWrap = android.widget.FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_cast_icon_circle)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
        }
        iconWrap.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(android.graphics.Color.WHITE)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(25), dp(25), android.view.Gravity.CENTER)
        })
        val titleBlock = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }
        titleBlock.addView(android.widget.TextView(this).apply {
            text = getString(R.string.video_settings_title)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleBlock.addView(android.widget.TextView(this).apply {
            text = mediaName
            setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        val close = android.widget.ImageButton(this).apply {
            setBackgroundResource(R.drawable.bg_top_icon_btn)
            setImageResource(R.drawable.ic_close)
            setColorFilter(android.graphics.Color.WHITE)
            contentDescription = getString(R.string.action_close)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(iconWrap)
        header.addView(titleBlock)
        header.addView(close)
        root.addView(header)

        val dividerTop = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#1FFFFFFF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(20)
                bottomMargin = dp(8)
            }
        }
        root.addView(dividerTop)

        fun addSeek(label: String, min: Int, max: Int, value: Float, boost: Boolean = false, audioEffect: Boolean = false, valueFormatter: ((Int) -> String)? = null, onChange: (Int) -> Unit) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(2))
            }
            val title = android.widget.TextView(this).apply {
                text = label
                setTextColor(android.graphics.Color.parseColor("#E6FFFFFF"))
                textSize = 14f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valueText = android.widget.TextView(this).apply {
                text = valueFormatter?.invoke(value.toInt()) ?: if (boost) "+${value.toInt()}%" else value.toInt().toString()
                setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                textSize = 13f
            }
            row.addView(title)
            row.addView(valueText)
            val seek = android.widget.SeekBar(this).apply {
                this.max = max - min
                progress = value.toInt().coerceIn(min, max) - min
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        val v = min + progress
                        valueText.text = valueFormatter?.invoke(v) ?: if (boost) "+$v%" else v.toString()
                        onChange(v)
                        if (boost || audioEffect) applyVideoVolumeBoost() else applyVideoAdjustments(applyVolumeBoost = false)
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                        if (boost || audioEffect) applyVideoVolumeBoost() else applyVideoAdjustments(applyVolumeBoost = false)
                        persistVideoAdjustments()
                    }
                })
            }
            root.addView(row)
            root.addView(seek)
        }

        addSeek(getString(R.string.video_setting_brightness), -1, 100, videoBrightness, valueFormatter = { v -> if (v < 0) "Auto" else "$v%" }) { videoBrightness = it.toFloat() }
        addSeek(getString(R.string.video_setting_contrast), -100, 100, videoContrast) { videoContrast = it.toFloat() }
        addSeek(getString(R.string.video_setting_color), -100, 100, videoHue) { videoHue = it.toFloat() }
        addSeek(getString(R.string.video_setting_saturation), -100, 100, videoSaturation) { videoSaturation = it.toFloat() }

        root.addView(android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#1FFFFFFF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
        })
        addSeek(getString(R.string.video_setting_volume_boost), 0, 20, videoVolumeBoost, boost = true) { videoVolumeBoost = it.toFloat() }
        addSeek(getString(R.string.video_setting_dialogue_mode), 0, 100, videoDialogueMode, audioEffect = true, valueFormatter = { v -> if (v == 0) getString(R.string.action_off) else "$v%" }) { videoDialogueMode = it.toFloat() }

        val reset = android.widget.TextView(this).apply {
            text = getString(R.string.action_reset)
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_queue_action_remove_pill)
            isClickable = true
            isFocusable = true
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(18)
            }
            setOnClickListener {
                videoBrightness = -1f
                videoContrast = 0f
                videoHue = 0f
                videoSaturation = 0f
                videoVolumeBoost = 0f
                videoDialogueMode = 0f
                applyVideoAdjustments(applyVolumeBoost = true)
                persistVideoAdjustments()
                dialog.dismiss()
            }
        }
        root.addView(reset)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.setOnDismissListener { persistVideoAdjustments(); scheduleHide() }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun persistVideoAdjustments() {
        lifecycleScope.launch {
            dataStore.edit {
                it[intPreferencesKey("video_brightness")] = videoBrightness.toInt()
                it[intPreferencesKey("video_contrast")] = videoContrast.toInt()
                it[intPreferencesKey("video_hue")] = videoHue.toInt()
                it[intPreferencesKey("video_saturation")] = videoSaturation.toInt()
                it[intPreferencesKey("video_volume_boost")] = videoVolumeBoost.toInt()
                it[intPreferencesKey("video_dialogue_mode")] = videoDialogueMode.toInt()
            }
        }
    }

    private fun applyVideoAdjustments(applyVolumeBoost: Boolean = true) {
        // La luminosité ne doit pas modifier l'image vidéo : elle pilote uniquement la luminosité
        // native Android de la fenêtre, comme le réglage système de l'écran.
        applyNativeScreenBrightness()

        val hasVisualAdjustment = hasVisualVideoAdjustments()
        if (::binding.isInitialized) {
            attachPlayerToPreferredView()
        }

        // Mode neutre : aucun filtre, SurfaceView actif, zéro composition GPU supplémentaire.
        if (!hasVisualAdjustment || !usingFilteredTextureView) {
            binding.playerView.setRenderEffect(null)
            binding.filteredPlayerView.setRenderEffect(null)
            binding.playerView.setLayerType(View.LAYER_TYPE_NONE, null)
            binding.filteredPlayerView.setLayerType(View.LAYER_TYPE_NONE, null)
            if (applyVolumeBoost) applyVideoVolumeBoost()
            return
        }

        val saturation = (1f + videoSaturation / 100f).coerceIn(0f, 2f)
        val contrast = (1f + videoContrast / 100f).coerceIn(0f, 2f)
        val hueDegrees = (videoHue / 100f * 180f).coerceIn(-180f, 180f)

        val matrix = ColorMatrix()
        matrix.setSaturation(saturation)
        // Contraste centré autour de 128, sinon l'image s'assombrit trop quand on augmente
        // le contraste. Ce rendu est plus proche des lecteurs vidéo premium.
        val contrastTranslate = 128f * (1f - contrast)
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, contrastTranslate,
            0f, contrast, 0f, 0f, contrastTranslate,
            0f, 0f, contrast, 0f, contrastTranslate,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(contrastMatrix)
        matrix.postConcat(hueColorMatrix(hueDegrees))

        // Le filtre s'applique uniquement au PlayerView TextureView. Le PlayerView SurfaceView
        // reste totalement propre pour la lecture réseau fluide quand les réglages sont neutres.
        binding.playerView.setRenderEffect(null)
        binding.playerView.setLayerType(View.LAYER_TYPE_NONE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.filteredPlayerView.setLayerType(View.LAYER_TYPE_NONE, null)
            binding.filteredPlayerView.setRenderEffect(
                RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix))
            )
        } else {
            binding.filteredPlayerView.setRenderEffect(null)
            binding.filteredPlayerView.setLayerType(
                View.LAYER_TYPE_HARDWARE,
                Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(matrix) }
            )
        }

        if (applyVolumeBoost) applyVideoVolumeBoost()
    }

    private fun applyNativeScreenBrightness() {
        val lp = window.attributes
        lp.screenBrightness = if (videoBrightness < 0f) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            (videoBrightness / 100f).coerceIn(0.01f, 1f)
        }
        window.attributes = lp
    }

    private fun applyVideoVolumeBoost() {
        if (!::player.isInitialized) return
        val args = Bundle().apply {
            putInt(VideoPlaybackService.EXTRA_VOLUME_BOOST_PERCENT, videoVolumeBoost.toInt().coerceIn(0, 20))
            putInt(VideoPlaybackService.EXTRA_DIALOGUE_MODE_PERCENT, videoDialogueMode.toInt().coerceIn(0, 100))
        }
        try {
            (player as? MediaController)?.sendCustomCommand(
                SessionCommand(VideoPlaybackService.CUSTOM_COMMAND_SET_VOLUME_BOOST, Bundle.EMPTY),
                args
            )
        } catch (e: Exception) {
            CrashReporter.log(this, "Video volume boost command failed", e)
        }
    }

    private fun hueColorMatrix(degrees: Float): ColorMatrix {
        val angle = Math.toRadians(degrees.toDouble()).toFloat()
        val cosVal = kotlin.math.cos(angle)
        val sinVal = kotlin.math.sin(angle)
        val lumR = 0.213f
        val lumG = 0.715f
        val lumB = 0.072f
        return ColorMatrix(floatArrayOf(
            lumR + cosVal * (1 - lumR) + sinVal * (-lumR), lumG + cosVal * (-lumG) + sinVal * (-lumG), lumB + cosVal * (-lumB) + sinVal * (1 - lumB), 0f, 0f,
            lumR + cosVal * (-lumR) + sinVal * 0.143f, lumG + cosVal * (1 - lumG) + sinVal * 0.140f, lumB + cosVal * (-lumB) + sinVal * -0.283f, 0f, 0f,
            lumR + cosVal * (-lumR) + sinVal * (-(1 - lumR)), lumG + cosVal * (-lumG) + sinVal * lumG, lumB + cosVal * (1 - lumB) + sinVal * lumB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun startProgressLoop() {
        lifecycleScope.launch {
            while (!isDestroyed && !isFinishing) {
                delay(500)
                if (isDestroyed || isFinishing) break
                try {
                    val dur = player.duration.takeIf { it > 0 } ?: continue
                    val pos = player.currentPosition
                    if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                        lastKnownLocalPosition = pos
                    } else {
                        lastKnownRemotePosition = pos
                    }
                    maybeCapturePlaybackThumbnail(pos, dur)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (pos > 0L && now - lastProgressPersistAt >= 5_000L) {
                        lastProgressPersistAt = now
                        persistVideoPosition(pos)
                    }
                    if (!seekBarDragging) withContext(Dispatchers.Main) { updateProgressUI(pos, dur) }
                } catch (e: Exception) {
                    CrashReporter.log(this@PlayerActivity, "Progress loop failed", e)
                }
            }
        }
    }

    private fun updateProgressUI(pos: Long, dur: Long) {
        if (isDestroyed || isFinishing) return
        binding.tvCurrentTime.text = formatTime(pos)
        binding.tvTotalTime.text = formatTime(dur)
        val pct = (pos.toFloat() / dur).coerceIn(0f, 1f)
        val w = binding.progressContainer.width.toFloat()
        val thumbHalf = 7f * resources.displayMetrics.density
        binding.progressFill.layoutParams.width = ((w - thumbHalf * 2) * pct).toInt()
        binding.progressFill.requestLayout()
        binding.progressThumb.translationX = (w - thumbHalf * 2) * pct
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun showUI() {
        uiVisible = true
        binding.uiOverlay.animate().cancel()
        binding.touchZoneLeft.animate().cancel()
        binding.touchZoneRight.animate().cancel()
        binding.uiOverlay.visibility = View.VISIBLE
        binding.uiOverlay.alpha = 1f
        binding.castStatusCard.visibility = View.GONE
        binding.centerTransportControls.visibility = View.VISIBLE
        binding.bottomControlsContainer.visibility = View.VISIBLE
        binding.touchZoneLeft.visibility = View.GONE
        binding.touchZoneRight.visibility = View.GONE
        if (videoStoppedByUser) cancelHide() else scheduleHide()
    }

    private fun hideUI() {
        if (seekBarDragging) return
        uiVisible = false
        binding.uiOverlay.animate().alpha(0f).setDuration(200).withEndAction { binding.uiOverlay.visibility = View.GONE }.start()
        binding.touchZoneLeft.animate().alpha(0f).setDuration(200).withEndAction { binding.touchZoneLeft.visibility = View.GONE }.start()
        binding.touchZoneRight.animate().alpha(0f).setDuration(200).withEndAction { binding.touchZoneRight.visibility = View.GONE }.start()
    }

    private fun scheduleHide() {
        cancelHide()
        val isCasting = ::player.isInitialized && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        // Ne masque jamais les contrôles pendant un cast : pas d'image locale à voir de toute
        // façon.
        if (isCasting) return
        if (::player.isInitialized && player.isPlaying) uiHandler.postDelayed(hideRunnable, 3000)
    }

    private fun cancelHide() = uiHandler.removeCallbacks(hideRunnable)

    private fun updatePlayPauseBtn(playing: Boolean) {
        if (isDestroyed || isFinishing) return
        binding.ivPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun getBrightness(): Float { val b = window.attributes.screenBrightness; return if (b < 0) 0.5f else b }

    /** Change de média en gardant le même player et la même session Cast.
     *
     * Chaque sélection est sérialisée par [mediaSwitchRequestId]. Le choix de reprise est présenté
     * AVANT setMediaItem(), de sorte qu'aucun LOAD à 0 ne parte vers le Chromecast avant la réponse
     * de l'utilisateur. */
    private fun switchTo(path: String, name: String) {
        // Dernier garde-fou commun à toutes les navigations internes (file d'attente, suivant,
        // précédent, commandes Cast/remote). Une file locale ancienne ne peut donc pas contourner
        // le verrou Pro en contenant une URL SMB/UPnP/HTTP.
        if (fr.retrospare.blazeplayer.paywall.FeatureAccess.isNetworkMediaPath(path) &&
            !kotlinx.coroutines.runBlocking {
                fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            }
        ) {
            fr.retrospare.blazeplayer.paywall.AccessGateUi.redirectToPaywall(this)
            return
        }
        if (path.isBlank()) return
        val requestId = ++mediaSwitchRequestId
        resumeChoiceDialog?.dismiss()
        pendingCastReplacementVerification?.let(uiHandler::removeCallbacks)
        pendingCastReplacementVerification = null
        binding.root.animate().cancel()

        // Sauvegarder l'ancienne vidéo avant de remplacer mediaPath. Le drapeau est posé AVANT
        // pause(), afin qu'un STATE_IDLE/ENDED tardif de l'ancienne vidéo ne déclenche ni reprise
        // réseau ni lecture automatique suivante pendant que le modal est affiché.
        saveCurrentVideoPosition()
        mediaReplacementInProgress = true
        cancelNetworkStarvationWatch()
        if (::player.isInitialized) runCatching { player.pause() }

        mediaPath = path
        mediaName = name
        isNetworkMedia = fr.retrospare.blazeplayer.paywall.FeatureAccess.isNetworkMediaPath(path)
        persistRemoteQueueState()
        binding.tvTitle.text = mediaName
        playNextCalled = false
        networkPlaybackReachedNaturalEnd = false
        networkEarlyEndRecoveries = 0
        networkStarvationRecoveries = 0
        prematureLocalEndRecoveries = 0
        lastKnownLocalPosition = 0L
        lastKnownRemotePosition = 0L
        castReplacementRetryCount = 0

        // Transition courte purement visuelle, sans callback différé : plusieurs clics rapides dans
        // l'historique ne peuvent plus exécuter dans le désordre d'anciens withEndAction().
        binding.root.alpha = 0.45f
        binding.root.animate().alpha(1f).setDuration(180L).start()

        val savedMs = savedResumePositionMs(path)
        val askResume = prefResumeMode == 1 && savedMs > 3000L
        val startPosition = if (prefResumeMode == 0 && savedMs > 3000L) savedMs else 0L
        resumeHandled = !askResume
        resumeDecisionPending = askResume
        saveHistory()

        if (askResume) {
            showInitialResumeChoice(savedMs, path, name, requestId)
        } else {
            resumeDecisionPending = false
            loadMedia(path, name, startPosition, autoPlay = true)
        }
    }


    private fun playQueueItem(targetIndex: Int, source: String): Boolean {
        if (targetIndex !in videoQueuePaths.indices) return false
        videoQueueIndex = targetIndex
        val targetPath = videoQueuePaths[targetIndex]
        val targetName = videoQueueNames.getOrElse(targetIndex) { File(targetPath).name }
        persistRemoteQueueState()
        android.util.Log.i("CAST", "Queue navigation source=$source index=$targetIndex/${videoQueuePaths.size} path=$targetPath")
        runOnUiThread {
            if (!isDestroyed && !isFinishing) switchTo(targetPath, targetName)
        }
        return true
    }

    private fun playNext() {
        // File d'attente de playlist ("Jouer la playlist") : prioritaire sur la logique de
        // dossier local ci-dessous, et fonctionne aussi bien pour du contenu réseau (smb://)
        // que local, contrairement au fallback MediaStore qui suit.
        if (videoQueuePaths.isNotEmpty() && videoQueueIndex < videoQueuePaths.size - 1) {
            playQueueItem(videoQueueIndex + 1, "next")
            return
        }
        if (videoQueuePaths.isNotEmpty()) return // fin de la playlist, pas de fallback dossier

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val next = findAdjacentMediaStoreItem(offset = 1) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing) switchTo(next.first, next.second)
                }
            } catch (e: Exception) {
                CrashReporter.log(this@PlayerActivity, "playNext failed", e)
            }
        }
    }

    private fun playPrevious() {
        if (videoQueuePaths.isNotEmpty() && videoQueueIndex > 0) {
            playQueueItem(videoQueueIndex - 1, "previous")
            return
        }
        if (videoQueuePaths.isNotEmpty()) {
            // Déjà au tout début de la playlist : redémarre et relance la vidéo courante.
            if (::player.isInitialized) {
                player.seekTo(0)
                if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                    scheduleRemoteAutoplayChecks("queue previous at start")
                } else {
                    player.playWhenReady = true
                    player.play()
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prev = findAdjacentMediaStoreItem(offset = -1)
                if (prev != null) {
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing) switchTo(prev.first, prev.second)
                    }
                } else if (::player.isInitialized) {
                    withContext(Dispatchers.Main) { player.seekTo(0) }
                }
            } catch (e: Exception) {
                CrashReporter.log(this@PlayerActivity, "playPrevious failed", e)
            }
        }
    }

    /** Cherche le fichier voisin (précédent/suivant, selon [offset]) dans le même dossier
     *  MediaStore que la vidéo courante. */
    private fun findAdjacentMediaStoreItem(offset: Int): Pair<String, String>? {
        val col = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var curBucket = ""
        var curName = ""
        contentResolver.query(Uri.parse(mediaPath), arrayOf(
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        ), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                curName = c.getString(0) ?: ""
                curBucket = c.getString(1) ?: ""
            }
        }
        if (curBucket.isEmpty() && curName.isEmpty()) return null

        val proj = arrayOf(
            android.provider.MediaStore.Video.Media._ID,
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val where = if (curBucket.isNotEmpty()) "${android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?" else null
        val args = if (curBucket.isNotEmpty()) arrayOf(curBucket) else null

        val list = mutableListOf<Pair<String, String>>()
        contentResolver.query(col, proj, where, args, android.provider.MediaStore.Video.Media.DISPLAY_NAME)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID))
                val name = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)) ?: continue
                list.add(android.content.ContentUris.withAppendedId(col, id).toString() to name)
            }
        }
        val idx = list.indexOfFirst { it.second == curName }
        val targetIdx = idx + offset
        if (idx < 0 || targetIdx !in list.indices) return null
        return list[targetIdx]
    }


    private data class ExternalVideoHistoryInfo(val name: String, val extension: String)

    private fun resolveExternalVideoHistoryInfo(path: String, fallbackName: String): ExternalVideoHistoryInfo {
        fun guessExt(value: String): String {
            val cleaned = value.substringBefore('?').substringBefore('#')
            val ext = cleaned.substringAfterLast('.', "").lowercase()
            return ext.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: ""
        }
        var resolvedName = fallbackName
        var mimeExt = ""
        if (path.startsWith("content://", true)) {
            val uri = Uri.parse(path)
            try {
                contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        resolvedName = c.getString(idx)?.takeIf { it.isNotBlank() } ?: resolvedName
                    }
                }
            } catch (_: Exception) { }
            try {
                val mime = contentResolver.getType(uri).orEmpty()
                mimeExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty().lowercase()
            } catch (_: Exception) { }
        }
        val extension = guessExt(resolvedName).ifBlank { guessExt(path) }.ifBlank { mimeExt }
        return ExternalVideoHistoryInfo(resolvedName, extension)
    }

    private fun saveHistory() {
        val pathSnapshot = mediaPath
        val nameSnapshot = mediaName
        val networkShareIdSnapshot = intent.getStringExtra("networkShareId")
        val isNetwork = isNetworkMedia || pathSnapshot.startsWith("smb://", true) || pathSnapshot.startsWith("ftp://", true) || pathSnapshot.startsWith("http://", true) || pathSnapshot.startsWith("https://", true)
        lifecycleScope.launch(Dispatchers.IO) {
            // Pour les vidéos ouvertes depuis un explorateur Android, l'intent content:// ne donne
            // pas toujours le vrai nom dans lastPathSegment. Contrairement au décodage initial
            // (chemin critique de lecture), on peut interroger OpenableColumns ici en tâche IO et
            // bornée dans le temps afin que l'historique de l'accueil affiche le même titre/badge
            // que les vidéos ouvertes depuis le navigateur in-app.
            val historyInfo = withTimeoutOrNull(1200) {
                resolveExternalVideoHistoryInfo(pathSnapshot, nameSnapshot)
            } ?: resolveExternalVideoHistoryInfo("", nameSnapshot)
            val historyName = historyInfo.name
            val ext = historyInfo.extension

            // Sauvegarde d'abord un historique minimal, sans extraction — l'extraction SMB au
            // lancement de la lecture (en parallèle de l'initialisation du service vidéo)
            // pouvait contribuer à un ANR au clic sur une vidéo. L'entrée est enrichie ensuite,
            // sans urgence, avec un délai maximal pour ne jamais bloquer indéfiniment.
            mediaRepository.saveRecentItem(fr.retrospare.blazeplayer.data.model.MediaItem(
                id = pathSnapshot, name = historyName, path = pathSnapshot,
                extension = ext, mimeType = if (ext.isNotBlank()) "video/$ext" else "video/*",
                isNetwork = isNetwork,
                networkShareId = networkShareIdSnapshot,
                lastPlayedAt = System.currentTimeMillis()
            ))

            val info = withTimeoutOrNull(1500) {
                VideoMetadataExtractor.extractLight(applicationContext, pathSnapshot)
            } ?: VideoTechnicalInfo()

            val container = info.container.lowercase().ifBlank { ext }
            if (info.duration > 0L || info.videoCodec.isNotEmpty() || info.audioCodec.isNotEmpty() || container.isNotBlank()) {
                mediaRepository.saveRecentItem(fr.retrospare.blazeplayer.data.model.MediaItem(
                    id = pathSnapshot, name = historyName, path = pathSnapshot,
                    extension = container, mimeType = if (container.isNotBlank()) "video/$container" else "video/*",
                    duration = info.duration,
                    size = info.sizeBytes,
                    resolution = info.qualityBadge,
                    videoCodec = info.videoCodec,
                    audioCodec = info.audioCodec,
                    isNetwork = isNetwork,
                        networkShareId = networkShareIdSnapshot,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfEnabled()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hasEnteredPip = true
            binding.uiOverlay.visibility = View.GONE
            binding.touchZoneLeft.visibility = View.GONE
            binding.touchZoneRight.visibility = View.GONE
        } else {
            hasEnteredPip = false
            showUI()
        }
    }

    private fun enterPipIfEnabled() {
        if (!prefPip) return
        if (!player.isPlaying) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try { enterPictureInPictureMode(buildPipParams(autoEnter = false)) } catch (e: Exception) { CrashReporter.log(this, "enterPictureInPictureMode failed", e) }
        }
    }

    /**
     * Construit les [android.app.PictureInPictureParams] à partir du ratio vidéo réel.
     * Sur API 31+, [autoEnter] = true permet au système de déclencher automatiquement le PiP
     * lorsque l'utilisateur quitte l'app — comportement recommandé par Media3/Android.
     */
    private fun buildPipParams(autoEnter: Boolean): android.app.PictureInPictureParams {
        val videoSize = player.videoSize
        val rational = if (videoSize.width > 0 && videoSize.height > 0) {
            val r = android.util.Rational(videoSize.width, videoSize.height)
            val float = videoSize.width.toFloat() / videoSize.height
            if (float < 0.418f) android.util.Rational(1, 2)
            else if (float > 2.39f) android.util.Rational(239, 100)
            else r
        } else android.util.Rational(16, 9)

        val builder = android.app.PictureInPictureParams.Builder().setAspectRatio(rational)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter && prefPip && player.isPlaying)
        }
        return builder.build()
    }

    private fun updatePipParamsIfSupported() {
        if (!::player.isInitialized) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        try { setPictureInPictureParams(buildPipParams(autoEnter = true)) } catch (e: Exception) { CrashReporter.log(this, "setPictureInPictureParams failed", e) }
    }
}
