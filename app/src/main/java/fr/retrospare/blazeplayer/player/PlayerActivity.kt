package fr.retrospare.blazeplayer.player

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var mediaRepository: MediaRepository

    private lateinit var binding: ActivityPlayerBinding
    lateinit var player: Player
    /** Complété une fois que le MediaController est connecté à VideoPlaybackService et assigné à
     *  [player]. Permet de séquencer correctement le chargement des préférences (asynchrone via
     *  DataStore) avant toute mutation du player, et d'éviter d'y accéder avant son initialisation. */
    private val playerReady = CompletableDeferred<Unit>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var castManager: fr.retrospare.blazeplayer.cast.VideoCastManager? = null
    private lateinit var audioManager: AudioManager

    private var prefSpeedIndex = 3
    private var prefResumeMode = 1
    private var prefAutoPlay = true
    private var prefSeekIndex = 1
    private var prefPip = false
    private var prefAudioLangIndex = 0
    private var prefRememberVolume = false
    private var prefSubtitlesDefault = false
    private var prefSubtitleLangIndex = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideUI() }
    private var uiVisible = true
    private var mediaPath = ""
    private var mediaName = ""
    private var resumeHandled = false
    private var playNextCalled = false
    private var seekBarDragging = false
    private var videoThumbnail: android.graphics.Bitmap? = null
    private var zoneTouching = false
    private var gestureStartY = 0f
    private var initialBrightness = 0.5f
    private var initialVolume = 0
    private var maxVolume = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // Orientation async - sans bloquer le main thread
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
        // Applique les insets pour éviter la barre nav en portrait
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.uiOverlay.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        mediaPath = intent.getStringExtra("mediaPath") ?: return finish()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBackToHistory()
            }
        })
        mediaName = intent.getStringExtra("mediaName") ?: File(mediaPath).name

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Charge les preferences puis les applique au player UNE FOIS que le MediaController est
        // pret (sequencement explicite via CompletableDeferred, voir playerReady ci-dessus).
        // Avant ce correctif, ce bloc touchait directement `player` ici alors que le
        // MediaController.buildAsync() n'avait pas encore termine -> UninitializedPropertyAccessException
        // garantie a chaque ouverture de video.
        lifecycleScope.launch {
            val prefs = dataStore.data.first()
            prefSpeedIndex = prefs[intPreferencesKey("speed_index")] ?: 3
            prefResumeMode = prefs[intPreferencesKey("resume_mode")] ?: 1
            prefAutoPlay = prefs[booleanPreferencesKey("auto_play")] ?: true
            prefSeekIndex = prefs[intPreferencesKey("seek_time_index")] ?: 1
            prefPip = prefs[booleanPreferencesKey("pip")] ?: false
            prefAudioLangIndex = prefs[intPreferencesKey("audio_lang")] ?: 0
            prefRememberVolume = prefs[booleanPreferencesKey("remember_volume")] ?: false
            prefSubtitlesDefault = prefs[booleanPreferencesKey("subtitles_default")] ?: false
            prefSubtitleLangIndex = prefs[intPreferencesKey("subtitle_lang")] ?: 0

            // Restaure le volume mémorisé (ne touche pas au player, sans danger ici)
            if (prefRememberVolume) {
                val savedVol = prefs[intPreferencesKey("saved_volume")] ?: -1
                if (savedVol >= 0) audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVol, 0)
            }

            val labels = listOf("5s", "10s", "15s", "30s", "60s")
            binding.tvRewindLabel.text = "−${labels.getOrElse(prefSeekIndex) { "10s" }}"
            binding.tvForwardLabel.text = "+${labels.getOrElse(prefSeekIndex) { "10s" }}"

            // À partir d'ici on a besoin du player : on attend que le MediaController soit connecté.
            playerReady.await()

            val subLangCodes = listOf(null, "fra", "eng", "spa", "deu", "ita", "jpn", "por", "nld", "rus", "zho")
            val subLang = subLangCodes.getOrNull(prefSubtitleLangIndex)
            val preferredLang = subLangCodes.getOrNull(prefAudioLangIndex)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !prefSubtitlesDefault)
                .apply { if (subLang != null) setPreferredTextLanguage(subLang) }
                .apply { if (preferredLang != null) setPreferredAudioLanguage(preferredLang) }
                .build()

            val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            player.setPlaybackSpeed(speeds.getOrElse(prefSpeedIndex) { 1.0f })

            // Le media n'est chargé/préparé qu'une fois les préférences ci-dessus appliquées, pour
            // que la sélection de piste initiale (langue audio/sous-titres) en tienne compte.
            onPlayerReady()
        }

        // Arrete le service audio (BlazePlayerService) pour n avoir qu une seule MediaSession active
        stopService(android.content.Intent(this, BlazePlayerService::class.java))

        // Demarre VideoPlaybackService (ExoPlayer + CastPlayer + MediaSession video)
        startService(android.content.Intent(this, VideoPlaybackService::class.java))

        // Demarre le serveur HTTP local pour le Cast (SMB/local -> HTTP)
        castManager = fr.retrospare.blazeplayer.cast.VideoCastManager(applicationContext, lifecycleScope)
        castManager!!.startServer(mediaPath, mediaName)

        // Connexion MediaController -> VideoPlaybackService
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
        // Extrait miniature vidéo en arrière-plan
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var smbDataSourceThumb: SmbMediaDataSource? = null
            try {
                val r = android.media.MediaMetadataRetriever()
                when {
                    mediaPath.startsWith("smb://") -> {
                        smbDataSourceThumb = SmbMediaDataSource(mediaPath)
                        r.setDataSource(smbDataSourceThumb)
                    }
                    mediaPath.startsWith("content://") -> r.setDataSource(this@PlayerActivity, android.net.Uri.parse(mediaPath))
                    else -> r.setDataSource(mediaPath)
                }
                val frame = r.getFrameAtTime(1_000_000)
                videoThumbnail = frame?.let {
                    val scale = 256f / maxOf(it.width, it.height)
                    if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(it, (it.width*scale).toInt(), (it.height*scale).toInt(), true).also { _ -> it.recycle() }
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

    private var currentRatioIndex = 0
    private val ratioLabels = listOf("Auto", "Zoom", "Étiré", "Plein")

    private fun cycleAspectRatio() {
        currentRatioIndex = (currentRatioIndex + 1) % ratioLabels.size
        when (currentRatioIndex) {
            0 -> { // Auto - ratio natif de la video, letterbox si besoin
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            1 -> { // Zoom - remplit l'ecran en recadrant les bords, sans deformer
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            2 -> { // Étiré - force le ratio de l'ecran sans recadrer (deforme l'image)
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            3 -> { // Plein - identique a Étiré (alias conserve pour compatibilite du libelle)
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
        // Affiche le ratio au centre pendant 2 secondes
        binding.seekIndicator.text = ratioLabels[currentRatioIndex]
        binding.seekIndicator.visibility = android.view.View.VISIBLE
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ binding.seekIndicator.visibility = android.view.View.GONE }, 2000)
    }

    private fun showAudioTracks() {
        val tracks = player.currentTracks
        val audioGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
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
        val selectedIndex = audioGroups.indexOfFirst { it.isSelected }.let { if (it == -1) -1 else it }
        showTrackSelector("Piste audio", labels, selectedIndex) { i ->
            val override = androidx.media3.common.TrackSelectionOverride(audioGroups[i].mediaTrackGroup, 0)
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                .addOverride(override)
                .build()
        }
    }

    /** Affiche un selecteur de piste (audio ou sous-titre) sous forme de bottom sheet custom. */
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
                    if (position == selectedIndex) android.view.View.VISIBLE else android.view.View.INVISIBLE
                v.setOnClickListener {
                    onSelect(position)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showSubtitles() {
        val tracks = player.currentTracks
        val subGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        val labels = mutableListOf("Désactivés")
        subGroups.forEachIndexed { i, group ->
            val format = group.getTrackFormat(0)
            val baseLang = format.language ?: "ST"
            val label = format.label
            val lang = when {
                !label.isNullOrBlank() -> label
                subGroups.count { it.getTrackFormat(0).language == format.language } > 1 -> "$baseLang ${i + 1}"
                else -> baseLang
            }
            labels.add(lang)
        }
        val selectedIndex = if (subGroups.none { it.isSelected }) 0 else subGroups.indexOfFirst { it.isSelected } + 1
        showTrackSelector("Sous-titres", labels, selectedIndex) { i ->
            if (i == 0) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .build()
            } else {
                val selectedGroup = subGroups[i - 1]
                val override = androidx.media3.common.TrackSelectionOverride(selectedGroup.mediaTrackGroup, 0)
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .addOverride(override)
                    .build()
            }
        }
    }


    override fun onPause() {
        super.onPause()
        // Déclenche PiP quand l'utilisateur quitte l'app
        if (prefPip && player.isPlaying) {
            enterPipIfEnabled()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) return
        // Sauvegarde le volume si option activée
        if (prefRememberVolume) {
            val vol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            lifecycleScope.launch {
                dataStore.edit { it[intPreferencesKey("saved_volume")] = vol }
            }
        }
        val pos = player.currentPosition
        if (mediaPath.isNotEmpty() && pos > 0) {
            getSharedPreferences("blaze_positions", MODE_PRIVATE)
                .edit().putLong(mediaPath, pos).apply()
            lifecycleScope.launch {
                mediaRepository.updateProgress(mediaPath, pos / 1000)
            }
        }
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        val isCasting = if (::player.isInitialized) player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE else false
        if (!isCasting) {
            castManager?.stopServer()
            castManager = null
        }
        // Relance le service audio apres fermeture du player video
        startService(android.content.Intent(this, BlazePlayerService::class.java))
    }

    private fun onPlayerReady() {
        binding.playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (mediaPath.startsWith("smb://") || mediaPath.startsWith("ftp://")) {
                    runOnUiThread { showNetworkErrorDialog(error) }
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // Tient le ratio PiP à jour avec la résolution réelle de la vidéo (autoEnter API 31+).
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
                if (state == Player.STATE_ENDED) {
                    runOnUiThread {
                        updatePlayPauseBtn(false)
                        cancelHide()
                        showUI()
                        if (!playNextCalled) {
                            playNextCalled = true
                            lifecycleScope.launch {
                                val prefs = dataStore.data.first()
                                val autoPlay = prefs[booleanPreferencesKey("auto_play")] ?: true
                                if (autoPlay) playNext()
                            }
                        }
                    }
                }
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                // Bascule affichage local <-> Chromecast
                binding.playerView.visibility =
                    if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) View.INVISIBLE
                    else View.VISIBLE
                // Envoie le media au Chromecast via VideoCastManager
                val currentPos = player.currentPosition
                if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                    castManager?.castMedia(player, currentPos)
                } else {
                    val localItem = MediaItem.Builder()
                        .setUri(Uri.parse(mediaPath))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaName).build())
                        .build()
                    player.setMediaItem(localItem, currentPos)
                    player.prepare()
                    player.play()
                }
            }
        })

        // Charge le media local
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(mediaPath))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaName).build())
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        startProgressLoop()
    }

    private fun handleResume() {
        resumeHandled = true
        if (prefResumeMode == 2) return
        val savedMs = getSharedPreferences("blaze_positions", MODE_PRIVATE)
            .getLong(mediaPath, 0L)
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
            // Vient du réseau : ramène directement à l'accueil, onglet Réseau, avec l'historique à jour
            val intent = android.content.Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra("requestedTab", 2) // Onglet Reseau
            startActivity(intent)
            finish()
        } else {
            finish()
        }
    }

    private var networkErrorDialogShown = false

    private fun showNetworkErrorDialog(error: androidx.media3.common.PlaybackException) {
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
            if (player.isPlaying) player.pause() else player.play()
            scheduleHide()
        }

        binding.btnRewind.setOnClickListener {
            player.seekTo((player.currentPosition - seekMs()).coerceAtLeast(0))
            scheduleHide()
        }

        binding.btnForward.setOnClickListener {
            val dur = player.duration.takeIf { it > 0 } ?: return@setOnClickListener
            player.seekTo((player.currentPosition + seekMs()).coerceAtMost(dur))
            scheduleHide()
        }

        binding.btnRatio.setOnClickListener {
            scheduleHide()
            cycleAspectRatio()
        }
        binding.btnAudio.setOnClickListener {
            scheduleHide()
            showAudioTracks()
        }
        binding.btnSubtitles.setOnClickListener {
            scheduleHide()
            showSubtitles()
        }
        binding.uiOverlay.setOnClickListener { if (uiVisible) hideUI() else showUI() }
        binding.playerView.setOnClickListener { if (uiVisible) hideUI() else showUI() }
    }

    private fun seekMs() = when (prefSeekIndex) {
        0 -> 5_000L; 1 -> 10_000L; 2 -> 15_000L; 3 -> 30_000L; 4 -> 60_000L; else -> 10_000L
    }

    private fun setupProgressBar() {
        binding.progressContainer.setOnTouchListener { _, ev ->
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
        if (player.isPlaying) uiHandler.postDelayed(hideRunnable, 3000)
    }

    private fun cancelHide() = uiHandler.removeCallbacks(hideRunnable)

    private fun updatePlayPauseBtn(playing: Boolean) {
        if (isDestroyed || isFinishing) return
        binding.ivPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun getBrightness(): Float { val b = window.attributes.screenBrightness; return if (b < 0) 0.5f else b }
    private fun setBrightness(v: Float) { val lp = window.attributes; lp.screenBrightness = v; window.attributes = lp }

    private fun playNext() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val col = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.DATA,
                    android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                )
                // Charge tous les fichiers du même dossier que la vidéo courante
                var curBucket = ""
                var curName = ""
                // Trouve le dossier de la vidéo courante via son ID (extrait du content URI)
                contentResolver.query(Uri.parse(mediaPath), arrayOf(
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                ), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        curName = c.getString(0) ?: ""
                        curBucket = c.getString(1) ?: ""
                    }
                }
                if (curBucket.isEmpty() && curName.isEmpty()) return@launch

                // Charge tous les fichiers du même bucket (dossier)
                val list = mutableListOf<Pair<String, String>>()
                val where = if (curBucket.isNotEmpty())
                    "${android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?"
                else null
                val args = if (curBucket.isNotEmpty()) arrayOf(curBucket) else null

                contentResolver.query(col, proj, where, args,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID))
                        val name = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)) ?: continue
                        list.add(android.content.ContentUris.withAppendedId(col, id).toString() to name)
                    }
                }

                val idx = list.indexOfFirst { it.second == curName }
                if (idx >= 0 && idx < list.size - 1) {
                    val next = list[idx + 1]
                    withContext(Dispatchers.Main) {
                        if (isDestroyed || isFinishing) return@withContext
                        // Fondu vers noir
                        binding.root.animate().alpha(0f).setDuration(400).withEndAction {
                            mediaPath = next.first
                            mediaName = next.second
                            binding.tvTitle.text = mediaName
                            resumeHandled = true
                            playNextCalled = false
                            player.setMediaItem(MediaItem.fromUri(Uri.parse(next.first)))
                            player.prepare()
                            player.play()
                            // Fondu retour après 1.5s
                            uiHandler.postDelayed({
                                binding.root.animate().alpha(1f).setDuration(400).start()
                            }, 1500)
                        }.start()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun saveHistory() {
        val ext = mediaName.substringAfterLast('.', "").lowercase()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val info = VideoMetadataExtractor.extract(applicationContext, mediaPath)
            val resolution = info.resolutionLabel
            mediaRepository.saveRecentItem(fr.retrospare.blazeplayer.data.model.MediaItem(
                id = mediaPath, name = mediaName, path = mediaPath,
                extension = ext, mimeType = "video/$ext",
                duration = info.duration,
                resolution = resolution,
                videoCodec = info.videoCodec,
                audioCodec = info.audioCodec,
                isNetwork = mediaPath.startsWith("smb://") || mediaPath.startsWith("ftp://"), lastPlayedAt = System.currentTimeMillis()
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
            binding.uiOverlay.visibility = android.view.View.GONE
            binding.touchZoneLeft.visibility = android.view.View.GONE
            binding.touchZoneRight.visibility = android.view.View.GONE
        } else {
            showUI()
        }
    }

    private fun enterPipIfEnabled() {
        if (!prefPip) return
        if (!player.isPlaying) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(buildPipParams(autoEnter = false))
            } catch (e: Exception) {}
        }
    }

    /**
     * Construit les [android.app.PictureInPictureParams] à partir du ratio vidéo réel.
     * Sur API 31+, [autoEnter] = true permet au système de déclencher automatiquement le PiP
     * lorsque l'utilisateur quitte l'app (transition fluide, sans dépendre de [onUserLeaveHint]
     * qui peut arriver trop tard ou pas du tout selon le geste système) — c'est le comportement
     * recommandé par la documentation Android/Media3 pour la lecture vidéo.
     */
    private fun buildPipParams(autoEnter: Boolean): android.app.PictureInPictureParams {
        val videoSize = player.videoSize
        val rational = if (videoSize.width > 0 && videoSize.height > 0) {
            // Ratio video réel, limité entre 0.418 et 2.39 (limites Android)
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

    /** Tient les paramètres PiP à jour (ratio vidéo, autoEnter) à chaque changement d'état
     *  pertinent, pour que le système dispose toujours d'une valeur fraîche sur API 31+. */
    private fun updatePipParamsIfSupported() {
        if (!::player.isInitialized) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        try { setPictureInPictureParams(buildPipParams(autoEnter = true)) } catch (e: Exception) {}
    }
}
