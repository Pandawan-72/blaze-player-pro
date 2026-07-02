package fr.retrospare.blazeplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.cast.VideoStreamServerManager
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
class PlayerActivity : AppCompatActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>
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
    private var resumeHandled = false
    private var lastKnownIsRemote = false
    private var lastKnownLocalPosition = 0L
    private var lastKnownRemotePosition = 0L
    private var playNextCalled = false
    private var seekBarDragging = false
    // File d'attente de lecture (playlist "Jouer la playlist") : quand non vide, playNext()
    // enchaîne sur l'élément suivant de cette liste au lieu de chercher dans le même dossier local.
    private var videoQueuePaths: ArrayList<String> = arrayListOf()
    private var videoQueueNames: ArrayList<String> = arrayListOf()
    private var videoQueueIndex: Int = 0
    private var zoneTouching = false
    private var gestureStartY = 0f
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var maxVolume = 0
    private var networkErrorDialogShown = false
    private var compatWarningShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
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
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.uiOverlay.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        mediaPath = intent.getStringExtra("mediaPath") ?: return finish()
        intent.getStringArrayListExtra("queuePaths")?.let { videoQueuePaths = it }
        intent.getStringArrayListExtra("queueNames")?.let { videoQueueNames = it }
        videoQueueIndex = intent.getIntExtra("queueIndex", 0)
        mediaName = intent.getStringExtra("mediaName") ?: File(mediaPath).name

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBackToHistory() }
        })

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Démarre tout de suite le relais HTTP local : le MediaItem construit plus bas en a besoin.
        VideoStreamServerManager.startServer(applicationContext, mediaPath)

        // Met l'audio en pause (sans arrêter le service ni sa notification) pour pouvoir la
        // relancer facilement une fois la vidéo terminée, au lieu de couper BlazePlayerService.
        pauseAudioPlaybackKeepingNotification()

        // Démarre VideoPlaybackService (ExoPlayer + CastPlayer + MediaSession vidéo)
        startService(android.content.Intent(this, VideoPlaybackService::class.java))

        val token = SessionToken(this, android.content.ComponentName(this, VideoPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture!!.addListener({
            player = controllerFuture!!.get()
            playerReady.complete(Unit)
        }, MoreExecutors.directExecutor())

        binding.tvTitle.text = mediaName
        binding.tvCurrentTime.text = "0:00:00"
        binding.tvTotalTime.text = "0:00:00"

        setupControls()
        setupProgressBar()
        setupGestures()
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

            if (prefRememberVolume) {
                val savedVol = prefs[intPreferencesKey("saved_volume")] ?: -1
                if (savedVol >= 0) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
            }

            binding.tvRewindLabel.text = "−${SEEK_LABELS.getOrElse(prefSeekIndex) { "10s" }}"
            binding.tvForwardLabel.text = "+${SEEK_LABELS.getOrElse(prefSeekIndex) { "10s" }}"

            playerReady.await()
            onPlayerReady()

            val audioLang = SUB_LANG_CODES.getOrNull(prefAudioLangIndex)
            applySubtitleTrackSelection()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .apply { if (audioLang != null) setPreferredAudioLanguage(audioLang) }
                .build()
            player.setPlaybackSpeed(SPEEDS.getOrElse(prefSpeedIndex) { 1.0f })

            val savedMs = getSharedPreferences("blaze_positions", MODE_PRIVATE).getLong(mediaPath, 0L)
            val startPosition = if (prefResumeMode != 2 && savedMs > 3000L) savedMs else 0L
            if (startPosition > 0L) resumeHandled = true

            loadMedia(mediaPath, mediaName, startPosition)

            startProgressLoop()
        }

        // Extrait miniature vidéo en arrière-plan (pour l'historique/les vignettes)
        lifecycleScope.launch(Dispatchers.IO) {
            var smbDataSourceThumb: SmbMediaDataSource? = null
            try {
                val r = android.media.MediaMetadataRetriever()
                when {
                    mediaPath.startsWith("smb://") -> {
                        smbDataSourceThumb = SmbMediaDataSource(mediaPath)
                        r.setDataSource(smbDataSourceThumb)
                    }
                    mediaPath.startsWith("content://") -> r.setDataSource(this@PlayerActivity, Uri.parse(mediaPath))
                    else -> r.setDataSource(mediaPath)
                }
                val frame = r.getFrameAtTime(1_000_000)
                frame?.let {
                    val scale = 256f / maxOf(it.width, it.height)
                    if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true).also { _ -> it.recycle() }
                    else it
                }
                r.release()
                try { smbDataSourceThumb?.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Thumbnail extraction failed for $mediaPath", e)
                try { smbDataSourceThumb?.close() } catch (_: Exception) {}
            }
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

    private fun guessVideoMimeType(path: String): String {
        return when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> androidx.media3.common.MimeTypes.VIDEO_MP4
            "mkv" -> "video/x-matroska"
            "webm" -> androidx.media3.common.MimeTypes.VIDEO_WEBM
            "mov" -> "video/quicktime"
            "ts", "m2ts", "mts" -> "video/mp2t"
            else -> androidx.media3.common.MimeTypes.VIDEO_MP4
        }
    }

    /** Construit le MediaItem unique utilisé pour la lecture — valide et identique pour le local
     *  ET le Cast (URL réseau dans les deux cas, cf. doc de VideoPlaybackService). Attache la
     *  piste sous-titres depuis le cache si elle est déjà disponible. */
    private fun buildMediaItem(path: String, name: String): MediaItem {
        VideoStreamServerManager.startServer(applicationContext, path)
        val url = VideoStreamServerManager.getStreamUrl() ?: path
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(path)
            .setMimeType(guessVideoMimeType(path))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE)
                    .build()
            )
        return builder.build()
    }

    /** Point d'entrée UNIQUE pour charger un média. API Player standard uniquement :
     *  setMediaItem -> prepare -> play. Pas de RemoteMediaClient, pas de MediaQueueItem manuel. */
    private fun loadMedia(path: String, name: String, positionMs: Long = 0L) {
        val item = buildMediaItem(path, name)
        android.util.Log.i(
            "CAST",
            "LOAD média path=$path uri=${item.localConfiguration?.uri} positionMs=$positionMs remote=${player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE}"
        )
        // IMPORTANT : ne PAS appeler player.stop() ici. Dans cette app, le Player est exposé
        // via MediaSessionService ; stop() est interprété comme une commande STOP de session et
        // peut arrêter VideoPlaybackService pendant que CastPlayer est encore actif, ce qui
        // provoque des crashs quelques secondes après le début du cast.
        player.setMediaItem(item, positionMs)
        player.prepare()
        applySubtitleTrackSelection()
        player.play()
    }

    private var currentRatioIndex = 0
    private val ratioLabels = listOf("Auto", "Zoom", "Étiré", "Plein")

    private fun cycleAspectRatio() {
        currentRatioIndex = (currentRatioIndex + 1) % ratioLabels.size
        binding.playerView.resizeMode = when (currentRatioIndex) {
            0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        binding.seekIndicator.text = ratioLabels[currentRatioIndex]
        binding.seekIndicator.visibility = View.VISIBLE
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ binding.seekIndicator.visibility = View.GONE }, 2000)
    }

    private fun showAudioTracks() {
        val tracks = player.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucune piste audio", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val labels = audioGroups.mapIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val baseLang = format.language ?: "Piste"
            val label = format.label
            when {
                !label.isNullOrBlank() -> label
                audioGroups.count { it.getTrackFormat(0).language == format.language } > 1 -> "$baseLang ${i + 1}"
                else -> baseLang
            }
        }
        val selectedIndex = audioGroups.indexOfFirst { it.isSelected }
        showTrackSelector("Piste audio", labels, selectedIndex) { i ->
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
        val tracks = player.currentTracks
        val subGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val labels = mutableListOf("Désactivés")
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
        showTrackSelector("Sous-titres", labels, selectedIndex) { i ->
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
            binding.playerView.player = player
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        if (prefPip && player.isPlaying) enterPipIfEnabled()
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) return
        if (!::player.isInitialized) return
        if (prefRememberVolume) {
            val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            lifecycleScope.launch { dataStore.edit { it[intPreferencesKey("saved_volume")] = vol } }
        }
        val pos = player.currentPosition
        if (mediaPath.isNotEmpty() && pos > 0) {
            getSharedPreferences("blaze_positions", MODE_PRIVATE).edit().putLong(mediaPath, pos).apply()
            lifecycleScope.launch { mediaRepository.updateProgress(mediaPath, pos / 1000) }
        }
        val isCasting = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        binding.playerView.player = null
        if (!isCasting) player.pause()
    }

    override fun onDestroy() {
        try { binding.playerView.player = null } catch (_: Exception) {}
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        val isCasting = if (::player.isInitialized) player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE else false
        if (!isCasting) VideoStreamServerManager.stopServer()
        // BlazePlayerService n'est plus jamais arrêté à l'ouverture d'une vidéo (juste mis en
        // pause) : cet appel garantit simplement qu'il tourne toujours après fermeture du player.
        startService(android.content.Intent(this, BlazePlayerService::class.java))
    }

    /** Met en pause la lecture audio (BlazePlayerService) sans arrêter le service ni sa
     *  notification, pour pouvoir la relancer facilement une fois la vidéo terminée. */
    private fun pauseAudioPlaybackKeepingNotification() {
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
                val info = VideoMetadataExtractor.extract(applicationContext, mediaPath)
                val reason = fr.retrospare.blazeplayer.cast.ChromecastCompatibility.incompatibilityReason(info, modelName)
                if (reason != null) {
                    compatWarningShown = true
                    withContext(Dispatchers.Main) {
                        if (isDestroyed || isFinishing) return@withContext
                        android.widget.Toast.makeText(
                            this@PlayerActivity,
                            "Ton Chromecast (${modelName ?: "modèle inconnu"}) n'est probablement pas compatible avec cette vidéo : $reason.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun onPlayerReady() {
        binding.playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e(
                    "PlayerActivity",
                    "onPlayerError code=${error.errorCode} name=${PlaybackException.getErrorCodeName(error.errorCode)} " +
                        "message=${error.message} cause=${error.cause}",
                    error
                )
                val isCasting = ::player.isInitialized && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                if (mediaPath.startsWith("smb://") || mediaPath.startsWith("ftp://") || isCasting) {
                    runOnUiThread { showNetworkErrorDialog() }
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
                        if (!resumeHandled) handleResume()
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
                android.util.Log.i("CAST", "onPlaybackStateChanged: $stateName (isRemote=${player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE})")
                if (state == Player.STATE_ENDED) {
                    runOnUiThread {
                        updatePlayPauseBtn(false)
                        cancelHide()
                        showUI()
                        if (!playNextCalled) {
                            playNextCalled = true
                            if (prefAutoPlay) playNext()
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
                    lastKnownIsRemote = isRemote
                    android.util.Log.i("CAST", "Transition ${if (isRemote) "vers" else "depuis"} Cast détectée")
                    applySubtitleTrackSelection()
                }

                if (isRemote) {
                    val session = try {
                        com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext)
                            .sessionManager.currentCastSession
                    } catch (e: Exception) { null }
                    val deviceName = session?.castDevice?.friendlyName
                    binding.tvSubtitle.text = if (deviceName != null) "Diffusion sur $deviceName" else "Diffusion Chromecast"
                    binding.tvSubtitle.visibility = View.VISIBLE
                    warnIfIncompatible(session?.castDevice?.modelName)
                } else {
                    binding.tvSubtitle.visibility = View.GONE
                }
            }
        })
    }

    private fun handleResume() {
        resumeHandled = true
        if (prefResumeMode == 2) return
        val savedMs = getSharedPreferences("blaze_positions", MODE_PRIVATE).getLong(mediaPath, 0L)
        if (savedMs <= 3000L) return
        when (prefResumeMode) {
            0 -> player.seekTo(savedMs)
            1 -> android.app.AlertDialog.Builder(this)
                .setTitle("Reprendre la lecture")
                .setMessage("Reprendre depuis %d:%02d ?".format(savedMs / 60000, (savedMs / 1000) % 60))
                .setPositiveButton("Reprendre") { _, _ -> player.seekTo(savedMs) }
                .setNegativeButton("Depuis le début") { _, _ -> player.seekTo(0) }
                .show()
        }
    }

    private fun goBackToHistory() {
        if (mediaPath.startsWith("smb://") || mediaPath.startsWith("ftp://")) {
            val intent = android.content.Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra("requestedTab", 2)
            startActivity(intent)
            finish()
        } else {
            finish()
        }
    }

    private fun showNetworkErrorDialog() {
        if (networkErrorDialogShown) return
        networkErrorDialogShown = true
        android.app.AlertDialog.Builder(this)
            .setTitle("Erreur de lecture réseau")
            .setMessage("La lecture de cette vidéo a échoué (connexion au NAS interrompue, Wi-Fi instable, ou fichier inaccessible).")
            .setCancelable(false)
            .setPositiveButton("Réessayer") { _, _ ->
                networkErrorDialogShown = false
                player.prepare()
                player.play()
            }
            .setNegativeButton("Quitter") { _, _ -> finish() }
            .show()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { goBackToHistory() }

        binding.btnPlayPause.setOnClickListener {
            if (!::player.isInitialized) return@setOnClickListener
            if (player.isPlaying) player.pause() else player.play()
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

        binding.btnRatio.setOnClickListener { scheduleHide(); cycleAspectRatio() }
        binding.btnAudio.setOnClickListener { scheduleHide(); showAudioTracks() }
        binding.btnSubtitles.setOnClickListener { scheduleHide(); showSubtitles() }
        binding.uiOverlay.setOnClickListener { if (uiVisible) hideUI() else showUI() }
        binding.playerView.setOnClickListener { if (uiVisible) hideUI() else showUI() }

        // Stop réel (pas juste pause) : remet la lecture au tout début et met en pause, à la
        // différence de play/pause qui ne fait qu'alterner l'état sans revenir en arrière.
        binding.btnStop.setOnClickListener {
            scheduleHide()
            if (::player.isInitialized) {
                player.pause()
                player.seekTo(0L)
            }
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

    private fun setupGestures() {
        binding.touchZoneLeft.setOnTouchListener { _, ev ->
            val h = binding.touchZoneLeft.height.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zoneTouching = true; cancelHide()
                    gestureStartY = ev.y; initialBrightness = getBrightness()
                }
                MotionEvent.ACTION_MOVE -> {
                    val b = (initialBrightness - (ev.y - gestureStartY) / h * 1.5f).coerceIn(0.01f, 1f)
                    setBrightness(b)
                    binding.touchZoneLeftFill.layoutParams.height = (h * b).toInt()
                    binding.touchZoneLeftFill.requestLayout()
                    binding.tvBrightnessZone.text = "${(b * 100).toInt()}%"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { zoneTouching = false; scheduleHide() }
            }
            true
        }
        binding.touchZoneRight.setOnTouchListener { _, ev ->
            val h = binding.touchZoneRight.height.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zoneTouching = true; cancelHide()
                    gestureStartY = ev.y
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                MotionEvent.ACTION_MOVE -> {
                    val v = (initialVolume - ((ev.y - gestureStartY) / h * maxVolume * 1.5f).toInt()).coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    val pct = v.toFloat() / maxVolume
                    binding.touchZoneRightFill.layoutParams.height = (h * pct).toInt()
                    binding.touchZoneRightFill.requestLayout()
                    binding.tvVolumeZone.text = "${(pct * 100).toInt()}%"
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { zoneTouching = false; scheduleHide() }
            }
            true
        }
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
                    if (!seekBarDragging) withContext(Dispatchers.Main) { updateProgressUI(pos, dur) }
                } catch (e: Exception) {}
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
        binding.uiOverlay.visibility = View.VISIBLE
        binding.uiOverlay.animate().alpha(1f).setDuration(200).start()
        binding.touchZoneLeft.visibility = View.VISIBLE
        binding.touchZoneLeft.animate().alpha(1f).setDuration(200).start()
        binding.touchZoneRight.visibility = View.VISIBLE
        binding.touchZoneRight.animate().alpha(1f).setDuration(200).start()
        scheduleHide()
    }

    private fun hideUI() {
        if (zoneTouching || seekBarDragging) return
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
    private fun setBrightness(v: Float) { val lp = window.attributes; lp.screenBrightness = v; window.attributes = lp }

    /** Change de média en gardant le même player (donc la même session Cast active si on caste) :
     *  démarre le relais pour le nouveau fichier et construit un nouveau MediaItem unique. */
    private fun switchTo(path: String, name: String) {
        binding.root.animate().alpha(0f).setDuration(400).withEndAction {
            mediaPath = path
            mediaName = name
            binding.tvTitle.text = mediaName
            resumeHandled = true
            playNextCalled = false
            loadMedia(path, name)
            saveHistory()
            binding.root.alpha = 0f
            binding.root.animate().alpha(1f).setDuration(400).start()
        }.start()
    }

    private fun playNext() {
        // File d'attente de playlist ("Jouer la playlist") : prioritaire sur la logique de
        // dossier local ci-dessous, et fonctionne aussi bien pour du contenu réseau (smb://)
        // que local, contrairement au fallback MediaStore qui suit.
        if (videoQueuePaths.isNotEmpty() && videoQueueIndex < videoQueuePaths.size - 1) {
            videoQueueIndex++
            val nextPath = videoQueuePaths[videoQueueIndex]
            val nextName = videoQueueNames.getOrElse(videoQueueIndex) { File(nextPath).name }
            runOnUiThread { if (!isDestroyed && !isFinishing) switchTo(nextPath, nextName) }
            return
        }
        if (videoQueuePaths.isNotEmpty()) return // fin de la playlist, pas de fallback dossier

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val next = findAdjacentMediaStoreItem(offset = 1) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing) switchTo(next.first, next.second)
                }
            } catch (e: Exception) {}
        }
    }

    private fun playPrevious() {
        if (videoQueuePaths.isNotEmpty() && videoQueueIndex > 0) {
            videoQueueIndex--
            val prevPath = videoQueuePaths[videoQueueIndex]
            val prevName = videoQueueNames.getOrElse(videoQueueIndex) { File(prevPath).name }
            runOnUiThread { if (!isDestroyed && !isFinishing) switchTo(prevPath, prevName) }
            return
        }
        if (videoQueuePaths.isNotEmpty()) {
            // Déjà au tout début de la playlist : redémarre juste la vidéo courante.
            if (::player.isInitialized) player.seekTo(0)
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
            } catch (e: Exception) {}
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

    private fun saveHistory() {
        val ext = mediaName.substringAfterLast('.', "").lowercase()
        lifecycleScope.launch(Dispatchers.IO) {
            val info = VideoMetadataExtractor.extract(applicationContext, mediaPath)
            mediaRepository.saveRecentItem(fr.retrospare.blazeplayer.data.model.MediaItem(
                id = mediaPath, name = mediaName, path = mediaPath,
                extension = ext, mimeType = "video/$ext",
                duration = info.duration,
                size = info.sizeBytes,
                resolution = info.resolutionLabel,
                videoCodec = info.videoCodec,
                audioCodec = info.audioCodec,
                isNetwork = mediaPath.startsWith("smb://") || mediaPath.startsWith("ftp://"),
                lastPlayedAt = System.currentTimeMillis()
            ))
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfEnabled()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.uiOverlay.visibility = View.GONE
            binding.touchZoneLeft.visibility = View.GONE
            binding.touchZoneRight.visibility = View.GONE
        } else {
            showUI()
        }
    }

    private fun enterPipIfEnabled() {
        if (!prefPip) return
        if (!player.isPlaying) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try { enterPictureInPictureMode(buildPipParams(autoEnter = false)) } catch (e: Exception) {}
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
        try { setPictureInPictureParams(buildPipParams(autoEnter = true)) } catch (e: Exception) {}
    }
}
