package fr.retrospare.blazeplayer.player

import android.app.AlertDialog
import fr.retrospare.blazeplayer.widget.MiniEqualizerView
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaController
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import android.os.Looper
import android.os.Handler
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.content.ComponentName
import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import fr.retrospare.blazeplayer.network.SmbBrowser
import fr.retrospare.blazeplayer.network.UpnpBrowser
import fr.retrospare.blazeplayer.playlist.PlaylistCategory
import fr.retrospare.blazeplayer.playlist.PlaylistManager
import fr.retrospare.blazeplayer.playlist.PlaylistTrackRef
import fr.retrospare.blazeplayer.ui.DialogButtonStyler
import fr.retrospare.blazeplayer.ui.ButtonTextFitter
import fr.retrospare.blazeplayer.ui.RoundedImageView
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class AudioLibraryActivity : AppCompatActivity() {

    @Inject lateinit var networkRepository: NetworkRepository
    @Inject lateinit var smbBrowser: SmbBrowser
    @Inject lateinit var upnpBrowser: UpnpBrowser

    private enum class TrackSource { ANDROID, WATCHED_LOCAL, WATCHED_NETWORK, QUEUE }

    private data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val durationMs: Long,
        val trackNo: Int,
        val path: String,
        val addedAt: Long,
        val artworkPath: String = "",
        val source: TrackSource = TrackSource.ANDROID,
        val sourceLabel: String = ""
    )

    private data class Album(
        val title: String,
        val artist: String,
        val albumId: Long,
        val artworkPath: String,
        val tracks: List<Track>,
        val addedAt: Long
    )

    private data class ArtistSummary(
        val name: String,
        val tracks: List<Track>,
        val albumsCount: Int,
        val addedAt: Long
    )

    private data class PlaylistSummary(
        val title: String,
        val subtitle: String,
        val tracks: List<PlaylistTrackRef>,
        val isParty: Boolean = false
    )

    private val libraryDynamicAccentColor by lazy { AudioPremiumUi.resolveAccentColor(this) }
    private val accentColor by lazy { ContextCompat.getColor(this, R.color.audio_library_accent) }
    private val textMain by lazy { ContextCompat.getColor(this, R.color.on_background) }
    private val textMuted by lazy { ContextCompat.getColor(this, R.color.audio_library_text_muted) }
    private val tabButtons = mutableListOf<MaterialButton>()
    private val libraryMiniHandler = Handler(Looper.getMainLooper())
    private val libraryMiniTimeTicker = object : Runnable {
        override fun run() {
            updateLibraryMiniPlayerTime()
            libraryMiniHandler.postDelayed(this, 1000L)
        }
    }
    private var libraryMiniControllerFuture: ListenableFuture<MediaController>? = null
    private var libraryMiniController: MediaController? = null
    private var libraryMiniVisualizerSessionId: Int = 0
    private var libraryMiniAccentColor: Int = AudioDynamicColor.DEFAULT_ACCENT

    private var selectedTab: Int = 0
    private var renderSeq: Int = 0
    private var firstResume = true
    private var searchQuery: String = ""
    private var librarySortMode: Int = 0
    private var resumeMode: Int = 0
    private var cachedSettings: AudioProSettings.Values? = null
    private var cachedTracks: List<Track> = emptyList()
    private var renderedTracksSnapshot: List<Track> = emptyList()
    private var renderedAlbumsSnapshot: List<Album> = emptyList()
    private var openedAlbumDetailKey: String? = null
    private var scanJob: Job? = null
    private var metadataJob: Job? = null
    private var artworkJob: Job? = null
    private var lastRenderedFingerprint: Int = 0

    companion object {
        private const val LIBRARY_SCAN_PREFS = "blaze_audio_library_scan_state"
        private const val KEY_LAST_SCAN_MS = "last_scan_ms"
        private const val KEY_LAST_SCAN_SIGNATURE = "last_scan_signature"
        private const val KEY_SCAN_ENGINE_VERSION = "scan_engine_version"
        private const val SCAN_ENGINE_VERSION = 6
        private const val PREFS_LIBRARY_UI = "blaze_audio_library_ui"
        private const val KEY_RESUME_MODE = "resume_mode"
        private const val AUTO_SCAN_COOLDOWN_MS = 6L * 60L * 60L * 1000L
        private const val MAX_ROWS_RENDERED = 120
        private const val MAX_METADATA_AUTO_EXTRACT = 96
        private const val MAX_ARTWORK_PREFETCH = 48
        private const val MAX_WATCHED_SCAN_TRACKS_PER_FOLDER = 20_000
        private const val MAX_WATCHED_NETWORK_SCAN_TRACKS = 20_000
        private const val LIBRARY_SNAPSHOT_FILE = "blaze_audio_library_snapshot_v7.json"
        private const val MAX_SNAPSHOT_TRACKS = 1800

        @Volatile private var lastTracksSnapshot: List<Track> = emptyList()
        @Volatile private var lastSettingsSnapshot: AudioProSettings.Values? = null
        @Volatile private var lastSnapshotPersistAt: Long = 0L

        // Trois pools volontairement séparés :
        // - cache rapide : sert l'UI au démarrage sans attendre les scans lourds ;
        // - scan : I/O dossiers/NAS ;
        // - metadata/artwork : très basse priorité pour ne pas couper la lecture audio.
        private val LIBRARY_CACHE_DISPATCHER = Executors.newSingleThreadExecutor { runnable ->
            Thread {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) } catch (_: Exception) {}
                runnable.run()
            }.apply { name = "BlazeAudioLibraryCache"; isDaemon = true }
        }.asCoroutineDispatcher()

        private val LIBRARY_SCAN_DISPATCHER = Executors.newSingleThreadExecutor { runnable ->
            Thread {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + 3) } catch (_: Exception) {}
                runnable.run()
            }.apply { name = "BlazeAudioLibraryScan"; isDaemon = true; priority = Thread.MIN_PRIORITY }
        }.asCoroutineDispatcher()

        private val LIBRARY_METADATA_DISPATCHER = Executors.newSingleThreadExecutor { runnable ->
            Thread {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + 8) } catch (_: Exception) {}
                runnable.run()
            }.apply { name = "BlazeAudioLibraryMetadata"; isDaemon = true; priority = Thread.MIN_PRIORITY }
        }.asCoroutineDispatcher()

        private val LIBRARY_ARTWORK_DISPATCHER = Executors.newSingleThreadExecutor { runnable ->
            Thread {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + 9) } catch (_: Exception) {}
                runnable.run()
            }.apply { name = "BlazeAudioLibraryArtwork"; isDaemon = true; priority = Thread.MIN_PRIORITY }
        }.asCoroutineDispatcher()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blaze_audio_library)
        val libraryBackgroundColor = runCatching {
            AudioDynamicColor.backgroundFromAccent(libraryDynamicAccentColor)
        }.getOrElse {
            ContextCompat.getColor(this, R.color.background)
        }

        resumeMode = getSharedPreferences(PREFS_LIBRARY_UI, MODE_PRIVATE).getInt(KEY_RESUME_MODE, 0).coerceIn(0, 1)
        applyFullscreenLibraryBackground(libraryBackgroundColor)
        applyAccentToStaticChrome()
        setupResumeModeToggle()
        setupLibraryMiniPlayer()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, AudioProSettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener { showSearchDialog() }
        findViewById<TextView>(R.id.btnManageWatched).setOnClickListener { openWatchedFoldersBrowser() }
        findViewById<TextView>(R.id.btnManualScan).setOnClickListener { manualScanWatchedFolders() }
        findViewById<TextView>(R.id.tvShowAllAlbums).visibility = View.GONE
        findViewById<TextView>(R.id.tvSortMode).setOnClickListener {
            librarySortMode = (librarySortMode + 1) % 3
            renderFromCache()
        }

        setupTabs()
        fitLibraryButtonLabels()
        renderLibrary(forceReload = true)
    }

    private fun fitLibraryButtonLabels() {
        runCatching {
            val content = findViewById<View>(android.R.id.content)
            content.post { ButtonTextFitter.fitRecursively(content, minSp = 9, maxSp = 13) }
        }
    }

    private fun applyFullscreenLibraryBackground(libraryBackgroundColor: Int) {
        runCatching {
            window.statusBarColor = libraryBackgroundColor
            window.navigationBarColor = libraryBackgroundColor
            window.decorView.setBackgroundColor(libraryBackgroundColor)

            val libraryRoot: View? = findViewById(R.id.libraryRoot)
            val libraryScroll: ScrollView? = findViewById(R.id.libraryScroll)
            val libraryGlobalCard: View? = findViewById(R.id.libraryGlobalCard)

            libraryRoot?.setBackgroundColor(libraryBackgroundColor)
            libraryScroll?.apply {
                setBackgroundColor(libraryBackgroundColor)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isScrollbarFadingEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            libraryGlobalCard?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            if (libraryScroll != null && libraryGlobalCard != null) {
                libraryGlobalCard.post {
                    val minCardHeight = libraryScroll.height
                    if (minCardHeight > 0) libraryGlobalCard.minimumHeight = minCardHeight
                }
            }
        }.onFailure {
            // Sécurité : une erreur de décoration ne doit jamais empêcher l'ouverture
            // de la bibliothèque audio. On garde au pire le fond statique du thème.
            runCatching {
                val fallbackCard: View? = findViewById(R.id.libraryGlobalCard)
                fallbackCard?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    override fun onPause() {
        stopLibraryMiniVisualizer()
        findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)?.setIdle()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshLibraryMiniPlayer()
        if (firstResume) {
            firstResume = false
        } else {
            if (AudioPremiumUi.resolveAccentColor(this) != libraryDynamicAccentColor) {
                recreate()
                return
            }
            renderLibrary(forceReload = true)
        }
    }

    private fun setupLibraryMiniPlayer() {
        findViewById<View>(R.id.libraryMiniPlayer)?.setOnClickListener { openFullAudioPlayerFromLibraryMini() }
        findViewById<ImageButton>(R.id.btnLibraryMiniPlayPause)?.setOnClickListener {
            libraryMiniController?.let { c -> if (c.isPlaying) c.pause() else c.play() }
        }
        findViewById<ImageButton>(R.id.btnLibraryMiniPrev)?.setOnClickListener { libraryMiniController?.seekToPreviousMediaItem() }
        findViewById<ImageButton>(R.id.btnLibraryMiniNext)?.setOnClickListener { libraryMiniController?.seekToNextMediaItem() }
        connectLibraryMiniController()
        libraryMiniHandler.removeCallbacks(libraryMiniTimeTicker)
        libraryMiniHandler.post(libraryMiniTimeTicker)
    }

    private fun openFullAudioPlayerFromLibraryMini() {
        startActivity(Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
            putExtra("openBlazeAudio", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun connectLibraryMiniController() {
        if (libraryMiniController != null) {
            refreshLibraryMiniPlayer()
            return
        }
        val token = SessionToken(this, ComponentName(this, BlazePlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        libraryMiniControllerFuture = future
        future.addListener({
            try {
                val ctrl = future.get()
                libraryMiniController = ctrl
                ctrl.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        refreshLibraryMiniPlayer()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        refreshLibraryMiniPlayer()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        refreshLibraryMiniPlayer()
                    }
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                            refreshLibraryMiniPlayer()
                        }
                    }
                })
                refreshLibraryMiniPlayer()
            } catch (_: Exception) {
                hideLibraryMiniPlayer()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun refreshLibraryMiniPlayer() {
        val ctrl = libraryMiniController ?: run {
            connectLibraryMiniController()
            return
        }
        val container = findViewById<View>(R.id.libraryMiniPlayer) ?: return
        val divider = findViewById<View>(R.id.dividerLibraryMiniPlayer)
        if (ctrl.mediaItemCount <= 0 || ctrl.currentMediaItem == null) {
            hideLibraryMiniPlayer()
            return
        }
        container.visibility = View.VISIBLE
        divider?.visibility = View.VISIBLE

        val item = ctrl.currentMediaItem
        val path = originalAudioPathOf(item)
        val cached = path.takeIf { it.isNotBlank() }?.let { AudioMetadataExtractor.getCached(this, it) }
        val title = item?.mediaMetadata?.title?.toString()?.ifEmpty { null }
            ?: cached?.title?.ifEmpty { null }
            ?: path.substringAfterLast('/').substringBeforeLast('.').ifBlank { getString(R.string.unknown_title) }
        val artist = item?.mediaMetadata?.artist?.toString()?.trim()?.ifEmpty { null }
            ?: cached?.artist?.trim()?.ifEmpty { null }
            ?: getString(R.string.unknown_artist)
        val album = cached?.album?.trim()?.ifEmpty { null }.orEmpty()

        findViewById<TextView>(R.id.tvLibraryMiniTitle)?.text = title
        findViewById<TextView>(R.id.tvLibraryMiniArtist)?.text = buildLibraryMiniArtistAlbumText(artist, album)
        findViewById<ImageButton>(R.id.btnLibraryMiniPlayPause)?.setImageResource(
            if (ctrl.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updateLibraryMiniPlayerTime()

        val eq = findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)
        val eqOverlay = findViewById<View>(R.id.libraryMiniEqOverlay)
        eq?.setAccentColor(libraryMiniAccentColor)
        eqOverlay?.visibility = if (ctrl.isPlaying) View.VISIBLE else View.GONE
        if (ctrl.isPlaying) {
            eq?.start()
            ensureLibraryMiniVisualizer()
        } else {
            stopLibraryMiniVisualizer()
            eq?.stop()
        }

        applyLibraryMiniArtwork(item, path)
    }

    private fun hideLibraryMiniPlayer() {
        findViewById<View>(R.id.libraryMiniPlayer)?.visibility = View.GONE
        findViewById<View>(R.id.dividerLibraryMiniPlayer)?.visibility = View.GONE
        stopLibraryMiniVisualizer()
        findViewById<View>(R.id.libraryMiniEqOverlay)?.visibility = View.GONE
        findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)?.stop()
    }

    private fun updateLibraryMiniPlayerTime() {
        val c = libraryMiniController ?: return
        val tv = findViewById<TextView>(R.id.tvLibraryMiniTime) ?: return
        val position = c.currentPosition.coerceAtLeast(0L)
        val duration = c.duration.takeIf { it > 0L } ?: 0L
        tv.text = if (duration > 0L) "${formatDuration(position)} / ${formatDuration(duration)}" else formatDuration(position)
    }

    private fun applyLibraryMiniArtwork(item: MediaItem?, path: String) {
        val iv = findViewById<ImageView>(R.id.ivLibraryMiniArtwork) ?: return
        val fromCache = path.takeIf { it.isNotBlank() }?.let { fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(this, it) }
        val art = fromCache ?: item?.mediaMetadata?.artworkData
        if (art != null) {
            val bitmap = runCatching { BitmapFactory.decodeByteArray(art, 0, art.size) }.getOrNull()
            if (bitmap != null) {
                iv.setImageBitmap(bitmap)
                val accent = AudioDynamicColor.accentFromBitmap(bitmap)
                libraryMiniAccentColor = accent
                findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)?.setAccentColor(accent)
            } else {
                iv.setImageResource(R.drawable.ic_music_note_large)
            }
        } else {
            iv.setImageResource(R.drawable.ic_music_note_large)
        }
        if (path.isNotBlank() && fromCache == null) {
            lifecycleScope.launch(LIBRARY_ARTWORK_DISPATCHER) {
                val bytes = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkJpegBytes(this@AudioLibraryActivity, path)
                if (bytes != null) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val current = originalAudioPathOf(libraryMiniController?.currentMediaItem)
                        if (current == path) applyLibraryMiniArtwork(libraryMiniController?.currentMediaItem, path)
                    }
                }
            }
        }
    }

    private fun buildLibraryMiniArtistAlbumText(artist: String, album: String): CharSequence {
        val safeArtist = artist.ifBlank { getString(R.string.unknown_artist) }
        val builder = android.text.SpannableStringBuilder(safeArtist)
        builder.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, safeArtist.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(android.text.style.ForegroundColorSpan(libraryMiniAccentColor), 0, safeArtist.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (album.isNotBlank()) builder.append("  •  ").append(album)
        return builder
    }

    private fun originalAudioPathOf(item: MediaItem?): String {
        item ?: return ""
        val extras = item.mediaMetadata.extras?.getString("blaze_original_path")
            ?.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
        if (extras != null) return extras
        return item.mediaId.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
            ?: item.localConfiguration?.uri?.toString()?.takeIf { AudioRepository.isAudioExtension(it) }
            ?: ""
    }

    private fun ensureLibraryMiniVisualizer() {
        val c = libraryMiniController ?: return
        val future = c.sendCustomCommand(
            SessionCommand(BlazePlayerService.COMMAND_GET_AUDIO_SESSION_ID, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            val sessionId = try { future.get().extras.getInt(BlazePlayerService.EXTRA_AUDIO_SESSION_ID, 0) } catch (_: Exception) { 0 }
            if (sessionId != 0) startLibraryMiniVisualizer(sessionId) else findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)?.setIdle()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLibraryMiniVisualizer(sessionId: Int) {
        if (sessionId == 0) return
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)?.setIdle()
            return
        }
        if (libraryMiniVisualizerSessionId == sessionId) return
        libraryMiniVisualizerSessionId = sessionId
        try {
            AudioFftStream.attach("library-mini-player", sessionId) { fft ->
                findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)?.takeIf { it.isShown }?.updateFft(fft)
            }
        } catch (_: Exception) {
            findViewById<MiniEqualizerView>(R.id.libraryMiniEqView)?.setIdle()
            stopLibraryMiniVisualizer()
        }
    }

    private fun stopLibraryMiniVisualizer() {
        AudioFftStream.detach("library-mini-player")
        libraryMiniVisualizerSessionId = 0
    }

    private fun setupTabs() {
        val container = findViewById<LinearLayout>(R.id.tabContainer)
        container.removeAllViews()
        tabButtons.clear()
        val tabs = listOf(
            R.drawable.ic_audio to R.string.audio_tab_albums,
            R.drawable.ic_person_single to R.string.audio_tab_artists,
            R.drawable.ic_music_note_large to R.string.audio_tab_titles,
            R.drawable.ic_layout_list to R.string.audio_tab_playlists
        )
        tabs.forEachIndexed { index, (icon, label) ->
            val btn = MaterialButton(this).apply {
                text = getString(label)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                includeFontPadding = false
                minWidth = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(dp(14), 0, dp(14), 0)
                setIconResource(icon)
                iconSize = dp(16)
                iconPadding = dp(8)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                cornerRadius = dp(20)
                strokeWidth = dp(1)
                ButtonTextFitter.fit(this, minSp = 9, maxSp = 13)
                setOnClickListener {
                    if (selectedTab != index) {
                        selectedTab = index
                        openedAlbumDetailKey = null
                        renderFromCache()
                    }
                }
            }
            tabButtons += btn
            container.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginEnd = dp(8)
            })
        }
        updateTabs()
    }

    private fun updateTabs() {
        tabButtons.forEachIndexed { index, button ->
            val selected = index == selectedTab
            val tint = if (selected) accentColor else textMuted
            button.strokeColor = android.content.res.ColorStateList.valueOf(if (selected) accentColor else ContextCompat.getColor(this, R.color.audio_library_accent_stroke))
            button.setTextColor(tint)
            button.iconTint = android.content.res.ColorStateList.valueOf(tint)
            button.backgroundTintList = ColorStateList.valueOf(if (selected) fr.retrospare.blazeplayer.player.AudioDynamicColor.mix(0xFF111A28.toInt(), accentColor, 0.22f) else 0xB0111A28.toInt())
        }
        updateResumeModeToggle()
    }

    private fun setupResumeModeToggle() {
        findViewById<TextView>(R.id.btnResumeTracks).setOnClickListener { setResumeMode(0) }
        findViewById<TextView>(R.id.btnResumeAlbums).setOnClickListener { setResumeMode(1) }
        updateResumeModeToggle()
    }

    private fun setResumeMode(mode: Int) {
        val safe = mode.coerceIn(0, 1)
        if (resumeMode == safe) return
        resumeMode = safe
        getSharedPreferences(PREFS_LIBRARY_UI, MODE_PRIVATE)
            .edit()
            .putInt(KEY_RESUME_MODE, resumeMode)
            .apply()
        updateResumeModeToggle()
        renderResumeModeOnly()
    }

    private fun renderResumeModeOnly() {
        val tracks = renderedTracksSnapshot.takeIf { it.isNotEmpty() }
            ?: sortTracks(filterTracks(cachedTracks))
        val albums = renderedAlbumsSnapshot.takeIf { it.isNotEmpty() }
            ?: buildAlbumsForTracks(tracks)
        renderResume(tracks, albums)
    }

    private fun updateResumeModeToggle() {
        runCatching {
            val tracksBtn = findViewById<TextView>(R.id.btnResumeTracks)
            val albumsBtn = findViewById<TextView>(R.id.btnResumeAlbums)
            listOf(tracksBtn to 0, albumsBtn to 1).forEach { (button, mode) ->
                val selected = resumeMode == mode
                styleLibraryPill(
                    button,
                    selected = selected,
                    enabled = true,
                    fillMix = if (selected) 0.34f else 0.0f,
                    transparentWhenIdle = true
                )
                button.setTextColor(if (selected) accentColor else textMuted)
            }
            styleLibraryPill(
                findViewById<LinearLayout>(R.id.resumeModeToggle),
                selected = false,
                enabled = true,
                fillMix = 0.16f
            )
        }
    }

    private fun renderLibrary(forceReload: Boolean) {
        if (!forceReload && cachedSettings != null) {
            renderFromCache()
            return
        }

        // Réouverture instantanée : RAM d'abord, snapshot disque ensuite, scans lourds après.
        val hadInstantSnapshot = renderInstantSnapshotIfAvailable()

        val seq = ++renderSeq
        scanJob?.cancel()
        metadataJob?.cancel()
        artworkJob?.cancel()
        lifecycleScope.launch {
            val settings = withContext(LIBRARY_CACHE_DISPATCHER) { AudioProSettings.read(this@AudioLibraryActivity) }
            cachedSettings = settings
            if (!hadInstantSnapshot && cachedTracks.isEmpty()) {
                val diskSnapshot = withContext(LIBRARY_CACHE_DISPATCHER) { loadPersistedSnapshot() }
                if (diskSnapshot.isNotEmpty() && seq == renderSeq && !isFinishing && !isDestroyed) {
                    cachedTracks = removeStaleWatchedTracks(diskSnapshot)
                    rememberSnapshot(settings, cachedTracks)
                    renderLibraryData(settings, cachedTracks)
                }
            }

            val watchedFolders = withContext(LIBRARY_CACHE_DISPATCHER) { AudioProSettings.watchedFolders(this@AudioLibraryActivity) }
            val baseTracks = withContext(LIBRARY_CACHE_DISPATCHER) {
                // Affichage non bloquant : MediaStore + dernier index connu des dossiers surveillés.
                // Les scans profonds FLAC/SMB restent séparés du thread de lecture audio.
                (loadTracks() + loadCachedWatchedTracks()).distinctBy { it.path }
            }
            if (seq != renderSeq || isFinishing || isDestroyed) return@launch
            cachedSettings = settings
            cachedTracks = removeStaleWatchedTracks(baseTracks)
            rememberSnapshot(settings, cachedTracks)
            val freshFingerprint = libraryFingerprint(cachedTracks)
            if (lastRenderedFingerprint != freshFingerprint || lastRenderedFingerprint == 0) {
                renderLibraryData(settings, cachedTracks)
            }
            persistSnapshotInBackground(cachedTracks)
            prefetchArtworkInBackground(seq, visibleTracksForBackgroundWork(cachedTracks), highPriority = true)
            enrichMetadataInBackground(seq, visibleTracksForBackgroundWork(cachedTracks))
            if (shouldAutoScanWatchedFolders(settings, watchedFolders)) {
                refreshWatchedFoldersInBackground(seq, manual = false)
            }
        }
    }

    private fun renderInstantSnapshotIfAvailable(): Boolean {
        val settings = cachedSettings ?: lastSettingsSnapshot ?: return false
        val tracks = when {
            cachedTracks.isNotEmpty() -> cachedTracks
            lastTracksSnapshot.isNotEmpty() -> lastTracksSnapshot
            else -> return false
        }
        cachedSettings = settings
        cachedTracks = removeStaleWatchedTracks(tracks)
        renderLibraryData(settings, cachedTracks)
        return true
    }

    private fun rememberSnapshot(settings: AudioProSettings.Values, tracks: List<Track>) {
        lastSettingsSnapshot = settings
        lastTracksSnapshot = tracks
    }

    private fun persistSnapshotInBackground(tracks: List<Track>) {
        val now = System.currentTimeMillis()
        if (tracks.isEmpty() || now - lastSnapshotPersistAt < 12_000L) return
        lastSnapshotPersistAt = now
        val snapshot = tracks.take(MAX_SNAPSHOT_TRACKS)
        lifecycleScope.launch {
            withContext(LIBRARY_CACHE_DISPATCHER) { savePersistedSnapshot(snapshot) }
        }
    }

    private fun savePersistedSnapshot(tracks: List<Track>) {
        runCatching {
            val arr = JSONArray()
            tracks.distinctBy { it.path }.take(MAX_SNAPSHOT_TRACKS).forEach { track ->
                arr.put(JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("albumId", track.albumId)
                    put("durationMs", track.durationMs)
                    put("trackNo", track.trackNo)
                    put("path", track.path)
                    put("addedAt", track.addedAt)
                    put("artworkPath", track.artworkPath)
                    put("source", track.source.name)
                    put("sourceLabel", track.sourceLabel)
                })
            }
            val root = JSONObject().apply {
                put("version", 6)
                put("updatedAt", System.currentTimeMillis())
                put("items", arr)
            }
            File(cacheDir, LIBRARY_SNAPSHOT_FILE).writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun loadPersistedSnapshot(): List<Track> {
        val file = File(cacheDir, LIBRARY_SNAPSHOT_FILE)
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version") != 6) return@runCatching emptyList<Track>()
            val arr = root.optJSONArray("items") ?: JSONArray()
            buildList {
                val limit = minOf(arr.length(), MAX_SNAPSHOT_TRACKS)
                for (i in 0 until limit) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val path = obj.optString("path")
                    if (path.isBlank()) continue
                    val sourceName = obj.optString("source", TrackSource.ANDROID.name)
                    val source = runCatching { TrackSource.valueOf(sourceName) }.getOrDefault(TrackSource.ANDROID)
                    add(Track(
                        id = obj.optLong("id", -kotlin.math.abs(path.hashCode()).toLong()),
                        title = obj.optString("title", inferTitleFromName(fileNameFromPath(path))),
                        artist = obj.optString("artist", getString(R.string.unknown_artist)),
                        album = obj.optString("album", getString(R.string.unknown_generic)),
                        albumId = obj.optLong("albumId", 0L),
                        durationMs = obj.optLong("durationMs", 0L),
                        trackNo = obj.optInt("trackNo", 0),
                        path = path,
                        addedAt = obj.optLong("addedAt", 0L),
                        artworkPath = obj.optString("artworkPath"),
                        source = source,
                        sourceLabel = obj.optString("sourceLabel", "")
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun renderFromCache() {
        updateTabs()
        val settings = cachedSettings ?: AudioProSettings.read(this)
        rememberSnapshot(settings, cachedTracks)
        renderLibraryData(settings, cachedTracks)
    }

    private fun libraryFingerprint(tracks: List<Track>): Int {
        var hash = tracks.size
        tracks.asSequence().take(2400).forEach { track ->
            hash = 31 * hash + track.path.hashCode()
            hash = 31 * hash + track.title.hashCode()
            hash = 31 * hash + track.album.hashCode()
            hash = 31 * hash + track.artist.hashCode()
            hash = 31 * hash + track.artworkPath.hashCode()
        }
        return hash
    }

    private fun buildAlbumsForTracks(tracks: List<Track>): List<Album> = tracks.groupBy { albumGroupingKey(it) }
        .map { (_, albumTracks) ->
            val ordered = albumTracks.sortedWith(
                compareBy<Track> { discNumberFromPath(it.path) }
                    .thenBy { normalizeTrackNumber(it.trackNo) }
                    .thenBy { normalizeForSearch(it.title) }
            )
            val first = ordered.first()
            val artists = ordered.map { it.artist.ifBlank { getString(R.string.unknown_artist) } }
                .filter { it.isNotBlank() }
                .distinctBy { normalizeArtistForGrouping(it) }
            Album(
                chooseAlbumDisplayTitle(ordered),
                when {
                    artists.isEmpty() -> getString(R.string.unknown_artist)
                    artists.size == 1 -> artists.first()
                    else -> getString(R.string.audio_various_artists)
                },
                first.albumId,
                pickAlbumArtworkPath(ordered),
                ordered,
                ordered.maxOfOrNull { it.addedAt } ?: 0L
            )
        }
        .let { list ->
            when (librarySortMode) {
                1 -> list.sortedBy { normalizeForSearch(it.title) }
                2 -> list.sortedByDescending { it.addedAt }
                else -> list.sortedWith(compareBy<Album> { normalizeForSearch(it.artist) }.thenBy { normalizeForSearch(it.title) })
            }
        }

    private fun renderLibraryData(settings: AudioProSettings.Values, baseTracks: List<Track>) {
        lastRenderedFingerprint = libraryFingerprint(baseTracks)
        rememberSnapshot(settings, baseTracks)
        updateTabs()
        val filteredBase = filterTracks(baseTracks)
        val tracks = sortTracks(filteredBase)
        val albums = buildAlbumsForTracks(tracks)
        renderedTracksSnapshot = tracks
        renderedAlbumsSnapshot = albums
        val artists = tracks.groupBy { it.artist.ifBlank { getString(R.string.unknown_artist) } }
            .map { (artist, artistTracks) ->
                ArtistSummary(
                    artist,
                    artistTracks.sortedWith(compareBy<Track> { it.album.lowercase(Locale.getDefault()) }.thenBy { normalizeTrackNumber(it.trackNo) }.thenBy { it.title.lowercase(Locale.getDefault()) }),
                    artistTracks.map { it.album.ifBlank { getString(R.string.unknown_generic) } }.toSet().size,
                    artistTracks.maxOfOrNull { it.addedAt } ?: 0L
                )
            }
            .let { list ->
                when (librarySortMode) {
                    2 -> list.sortedByDescending { it.addedAt }
                    else -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
                }
            }

        findViewById<TextView>(R.id.tvTrackCount).text = resources.getQuantityString(R.plurals.audio_track_count_compact, tracks.size, tracks.size)
        findViewById<TextView>(R.id.tvAlbumCount).text = resources.getQuantityString(R.plurals.audio_album_count_compact, albums.size, albums.size)
        findViewById<TextView>(R.id.tvArtistCount).text = resources.getQuantityString(R.plurals.audio_artist_count_compact, artists.size, artists.size)
        updateWatchedSummary()
        findViewById<TextView>(R.id.btnManualScan).visibility = if (settings.autoScan) View.GONE else View.VISIBLE

        if (selectedTab != 0) openedAlbumDetailKey = null

        when (selectedTab) {
            0 -> renderAlbumsTab(tracks, albums)
            1 -> renderArtistsTab(artists)
            2 -> renderTitlesTab(tracks, albums)
            else -> renderPlaylistsTab()
        }
    }

    private fun applyAccentToStaticChrome() {
        listOfNotNull(
            findViewById<TextView>(R.id.badgeLibraryPro),
            findViewById<TextView>(R.id.btnManageWatched),
            findViewById<TextView>(R.id.btnManualScan),
            findViewById<TextView>(R.id.tvShowAllAlbums),
            findViewById<TextView>(R.id.tvSortMode)
        ).forEach { view ->
            view.setTextColor(accentColor)
            styleLibraryPill(view, selected = false, enabled = true, fillMix = 0.18f)
        }
        findViewById<ImageView>(R.id.iconWatchedFolders)?.apply {
            setColorFilter(accentColor)
            styleLibraryPill(this, selected = false, enabled = true, fillMix = 0.14f)
        }
        updateResumeModeToggle()
    }

    private fun styleLibraryPill(
        view: View,
        selected: Boolean = false,
        enabled: Boolean = true,
        fillMix: Float = 0.18f,
        transparentWhenIdle: Boolean = false
    ) {
        val stroke = when {
            selected -> accentColor
            enabled -> ContextCompat.getColor(this, R.color.audio_library_accent_stroke)
            else -> Color.argb(68, 185, 223, 255)
        }
        val fill = when {
            !enabled -> 0x66111A28
            transparentWhenIdle && fillMix <= 0f -> Color.TRANSPARENT
            else -> AudioDynamicColor.mix(0xFF111A28.toInt(), accentColor, fillMix.coerceIn(0f, 0.42f))
        }
        view.backgroundTintList = null
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
        if (view is TextView && view.text.isNotBlank()) {
            ButtonTextFitter.fit(view, minSp = 9, maxSp = 13)
        }
    }

    override fun onDestroy() {
        libraryMiniHandler.removeCallbacks(libraryMiniTimeTicker)
        stopLibraryMiniVisualizer()
        runCatching { libraryMiniControllerFuture?.let { MediaController.releaseFuture(it) } }
        libraryMiniControllerFuture = null
        libraryMiniController = null
        super.onDestroy()
    }

    private fun removeStaleWatchedTracks(tracks: List<Track>): List<Track> {
        val watched = AudioProSettings.watchedFolders(this)
        val watchedLocal = watched.filterNot { it.isNetwork }
        return tracks.filter { track ->
            when (track.source) {
                TrackSource.WATCHED_LOCAL, TrackSource.WATCHED_NETWORK -> watched.any { belongsToWatchedFolder(track.path, it) }
                TrackSource.ANDROID -> watchedLocal.any { belongsToWatchedFolder(track.path, it) }
                TrackSource.QUEUE -> true
            }
        }
    }

    private fun manualScanWatchedFolders() {
        val seq = ++renderSeq
        Toast.makeText(this, R.string.scan_in_progress, Toast.LENGTH_SHORT).show()
        refreshWatchedFoldersInBackground(seq, manual = true)
    }

    private fun scanPrefs() = getSharedPreferences(LIBRARY_SCAN_PREFS, MODE_PRIVATE)

    private fun watchedFoldersSignature(folders: List<AudioProSettings.WatchedFolder>): String = folders
        .sortedWith(compareBy<AudioProSettings.WatchedFolder> { it.shareId }.thenBy { it.path }.thenBy { it.isNetwork })
        .joinToString("|") { "${it.isNetwork}:${it.shareId}:${it.path}" }

    private fun shouldAutoScanWatchedFolders(settings: AudioProSettings.Values, folders: List<AudioProSettings.WatchedFolder>): Boolean {
        if (!settings.autoScan || folders.isEmpty()) return false
        val signature = watchedFoldersSignature(folders)
        val p = scanPrefs()
        if (p.getInt(KEY_SCAN_ENGINE_VERSION, 0) != SCAN_ENGINE_VERSION) return true
        if (p.getString(KEY_LAST_SCAN_SIGNATURE, "") != signature) return true
        if (folders.any { AudioWatchedLibraryCache.load(this, it).isEmpty() }) return true
        val last = p.getLong(KEY_LAST_SCAN_MS, 0L)
        return last <= 0L || System.currentTimeMillis() - last > AUTO_SCAN_COOLDOWN_MS
    }

    private fun markAutoScanDone(folders: List<AudioProSettings.WatchedFolder>) {
        scanPrefs().edit()
            .putString(KEY_LAST_SCAN_SIGNATURE, watchedFoldersSignature(folders))
            .putLong(KEY_LAST_SCAN_MS, System.currentTimeMillis())
            .putInt(KEY_SCAN_ENGINE_VERSION, SCAN_ENGINE_VERSION)
            .apply()
    }

    private fun visibleTracksForBackgroundWork(tracks: List<Track>): List<Track> {
        val current = when (selectedTab) {
            0 -> tracks.sortedWith(compareBy<Track> { it.album.lowercase(Locale.getDefault()) }.thenBy { normalizeTrackNumber(it.trackNo) })
            1 -> tracks.sortedBy { it.artist.lowercase(Locale.getDefault()) }
            2 -> sortTracks(tracks)
            else -> tracks
        }
        return current.distinctBy { albumKey(it) }.take(MAX_ARTWORK_PREFETCH) + current.take(MAX_METADATA_AUTO_EXTRACT)
    }

    private fun addOverflowNotice(container: LinearLayout, hiddenCount: Int) {
        if (hiddenCount <= 0) return
        container.addView(subText("+ $hiddenCount", 13f, 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(accentColor)
        })
    }

    private fun filterTracks(baseTracks: List<Track>): List<Track> {
        val tokens = normalizeForSearch(searchQuery).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return baseTracks
        return baseTracks.filter { track ->
            val haystack = listOf(track.title, track.artist, track.album, fileNameFromPath(track.path), track.path)
                .joinToString(" ")
                .let { normalizeForSearch(it) }
            tokens.all { haystack.contains(it) }
        }
    }



    private fun sortTracks(tracks: List<Track>): List<Track> = when (librarySortMode) {
        1 -> tracks.sortedBy { it.title.lowercase(Locale.getDefault()) }
        2 -> tracks.sortedByDescending { it.addedAt }
        else -> tracks.sortedWith(compareBy<Track> { it.album.lowercase(Locale.getDefault()) }
            .thenBy { normalizeTrackNumber(it.trackNo) }
            .thenBy { it.title.lowercase(Locale.getDefault()) })
    }

    private fun sortModeText(): String = when {
        searchQuery.isNotBlank() -> searchQuery
        librarySortMode == 1 -> getString(R.string.sort_name_az)
        librarySortMode == 2 -> getString(R.string.sort_date_recent)
        else -> getString(R.string.sort_album_order)
    }

    private fun pickAlbumArtworkPath(tracks: List<Track>): String {
        // Priorité absolue aux images placées à la racine du dossier d'album : elles sont plus
        // fiables que les tags embarqués, surtout quand les MP3/FLAC n'ont pas tous les mêmes tags.
        tracks.asSequence().map { it.artworkPath }.firstOrNull { it.isNotBlank() }?.let { return it }
        preferredFolderCoverPathForTracks(tracks)?.let { return it }
        // Repli : on prend un représentant stable et ThumbnailUtils tentera la pochette embarquée.
        return tracks.firstOrNull { it.path.isNotBlank() }?.path.orEmpty()
    }

    private fun artworkPathForTrack(track: Track): String = track.artworkPath
        .takeIf { it.isNotBlank() }
        ?: preferredFolderCoverPathForAudioPath(track.path)
        ?: track.path

    private fun prefetchArtworkInBackground(seq: Int, tracks: List<Track>, highPriority: Boolean) {
        val orderedPaths = tracks
            .asSequence()
            .filter { it.path.isNotBlank() }
            .groupBy { albumGroupingKey(it) }
            .values
            .mapNotNull { group -> pickAlbumArtworkPath(group).takeIf { it.isNotBlank() } }
            .distinct()
            .sortedBy { if (fr.retrospare.blazeplayer.ui.ThumbnailUtils.hasCachedAudioArtwork(this, it)) 1 else 0 }
            .take(if (highPriority) MAX_ARTWORK_PREFETCH else 18)
            .toList()
        if (orderedPaths.isEmpty()) return
        artworkJob?.cancel()
        artworkJob = lifecycleScope.launch {
            withContext(LIBRARY_ARTWORK_DISPATCHER) {
                coroutineScope {
                    orderedPaths.chunked(2).forEach { chunk ->
                        chunk.map { artworkPath ->
                            async {
                                fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkBitmap(this@AudioLibraryActivity.applicationContext, artworkPath)
                            }
                        }.awaitAll()
                    }
                }
            }
            // Les vues visibles chargent elles-mêmes leur jaquette de façon asynchrone.
            // On ne ré-inflate pas toute la bibliothèque après un simple préchargement d'artwork :
            // cela évite des latences UI et laisse le thread audio respirer.
        }
    }

    private fun renderAlbumsTab(tracks: List<Track>, albums: List<Album>) {
        val openedKey = openedAlbumDetailKey
        val openedAlbum = openedKey?.let { key -> albums.firstOrNull { albumDetailKey(it) == key } }
        if (openedKey != null && openedAlbum != null) {
            renderAlbumDetailTab(openedAlbum)
            return
        } else if (openedKey != null) {
            openedAlbumDetailKey = null
        }

        setVisible(R.id.resumeSectionHeader, true)
        setVisible(R.id.resumeScroll, true)
        setVisible(R.id.albumsSectionHeader, true)
        setVisible(R.id.albumsScroll, true)
        setVisible(R.id.tracksSectionHeader, true)
        setLibrarySectionDividers(showResume = true, showAlbums = true, showTracks = true)
        findViewById<TextView>(R.id.tvResumeTitle).text = getString(R.string.audio_resume_listening)
        findViewById<TextView>(R.id.tvAlbumsTitle).text = getString(R.string.audio_recent_albums)
        findViewById<TextView>(R.id.tvTracksTitle).text = getString(R.string.audio_tab_albums)
        configureSortModeButton(sortModeText())

        renderResume(tracks, albums)
        renderAlbumRail(albums)
        val list = findViewById<LinearLayout>(R.id.tracksContainer)
        list.removeAllViews()
        if (albums.isEmpty()) {
            list.addView(emptyText())
        } else {
            val visibleAlbums = albums.take(MAX_ROWS_RENDERED)
            visibleAlbums.forEachIndexed { index, album ->
                list.addView(albumRow(album))
                if (index != visibleAlbums.lastIndex) list.addView(separator())
            }
            addOverflowNotice(list, albums.size - visibleAlbums.size)
        }
    }

    private fun renderAlbumDetailTab(album: Album) {
        val tracks = albumPlaybackTracks(album)
        setVisible(R.id.resumeSectionHeader, false)
        setVisible(R.id.resumeScroll, false)
        setVisible(R.id.albumsSectionHeader, false)
        setVisible(R.id.albumsScroll, false)
        setVisible(R.id.tracksSectionHeader, true)
        setLibrarySectionDividers(showResume = false, showAlbums = false, showTracks = true)
        findViewById<TextView>(R.id.tvTracksTitle).text = album.title
        configureHeaderActionButton(getString(R.string.audio_play_album), enabled = tracks.isNotEmpty()) {
            playAlbumFromLibrary(tracks)
        }

        val list = findViewById<LinearLayout>(R.id.tracksContainer)
        list.removeAllViews()
        list.addView(albumDetailHeader(album, tracks))
        list.addView(separator())
        list.addView(albumDetailBackRow())
        list.addView(separator())
        if (tracks.isEmpty()) {
            list.addView(emptyText().apply { setPadding(dp(16)) })
        } else {
            tracks.forEachIndexed { index, track ->
                list.addView(trackRow(index + 1, track))
                if (index != tracks.lastIndex) list.addView(separator())
            }
        }
    }

    private fun renderArtistsTab(artists: List<ArtistSummary>) {
        setVisible(R.id.resumeSectionHeader, false)
        setVisible(R.id.resumeScroll, false)
        setVisible(R.id.albumsSectionHeader, false)
        setVisible(R.id.albumsScroll, false)
        setVisible(R.id.tracksSectionHeader, true)
        setLibrarySectionDividers(showResume = false, showAlbums = false, showTracks = true)
        findViewById<TextView>(R.id.tvTracksTitle).text = getString(R.string.audio_tab_artists)
        configureSortModeButton(sortModeText())
        val list = findViewById<LinearLayout>(R.id.tracksContainer)
        list.removeAllViews()
        if (artists.isEmpty()) {
            list.addView(emptyText())
        } else {
            val visibleArtists = artists.take(MAX_ROWS_RENDERED)
            visibleArtists.forEachIndexed { index, artist ->
                list.addView(artistRow(artist))
                if (index != visibleArtists.lastIndex) list.addView(separator())
            }
            addOverflowNotice(list, artists.size - visibleArtists.size)
        }
    }

    private fun renderTitlesTab(tracks: List<Track>, albums: List<Album>) {
        setVisible(R.id.resumeSectionHeader, true)
        setVisible(R.id.resumeScroll, true)
        setVisible(R.id.albumsSectionHeader, false)
        setVisible(R.id.albumsScroll, false)
        setVisible(R.id.tracksSectionHeader, true)
        setLibrarySectionDividers(showResume = true, showAlbums = false, showTracks = true)
        findViewById<TextView>(R.id.tvResumeTitle).text = getString(R.string.audio_resume_listening)
        findViewById<TextView>(R.id.tvTracksTitle).text = getString(R.string.audio_tab_titles)
        configureSortModeButton(sortModeText())
        renderResume(tracks, albums)
        val list = findViewById<LinearLayout>(R.id.tracksContainer)
        list.removeAllViews()
        if (tracks.isEmpty()) {
            list.addView(emptyText().apply { setPadding(dp(16)) })
        } else {
            val visibleTracks = tracks.take(MAX_ROWS_RENDERED)
            visibleTracks.forEachIndexed { index, track ->
                list.addView(trackRow(index + 1, track))
                if (index != visibleTracks.lastIndex) list.addView(separator())
            }
            addOverflowNotice(list, tracks.size - visibleTracks.size)
        }
    }

    private fun renderPlaylistsTab() {
        setVisible(R.id.resumeSectionHeader, false)
        setVisible(R.id.resumeScroll, false)
        setVisible(R.id.albumsSectionHeader, false)
        setVisible(R.id.albumsScroll, false)
        setVisible(R.id.tracksSectionHeader, true)
        setLibrarySectionDividers(showResume = false, showAlbums = false, showTracks = true)
        findViewById<TextView>(R.id.tvTracksTitle).text = getString(R.string.audio_tab_playlists)
        configureHeaderActionButton(getString(R.string.audio_all), enabled = false)
        val list = findViewById<LinearLayout>(R.id.tracksContainer)
        list.removeAllViews()
        val playlists = loadPlaylistSummaries()
        if (playlists.all { it.tracks.isEmpty() }) {
            list.addView(emptyText())
        }
        playlists.forEachIndexed { index, playlist ->
            list.addView(playlistRow(playlist))
            if (index != playlists.lastIndex) list.addView(separator())
        }
    }

    private fun renderResume(tracks: List<Track>, albums: List<Album>) {
        val resumeContainer = findViewById<LinearLayout>(R.id.resumeContainer)
        resumeContainer.removeAllViews()
        updateResumeModeToggle()
        if (resumeMode == 1) {
            val recentAlbums = recentPlayedAlbums(tracks, albums)
            if (recentAlbums.isEmpty()) resumeContainer.addView(emptyText())
            else recentAlbums.take(6).forEach { resumeContainer.addView(albumCard(it)) }
            return
        }
        val recent = recentPlayedTracks(tracks)
        if (recent.isEmpty()) {
            resumeContainer.addView(emptyText())
        } else {
            recent.take(6).forEach { resumeContainer.addView(resumeCard(it, recent + tracks)) }
        }
    }

    private fun renderAlbumRail(albums: List<Album>) {
        val albumsContainer = findViewById<LinearLayout>(R.id.albumsContainer)
        albumsContainer.removeAllViews()
        val recentAlbums = albums.sortedByDescending { it.addedAt }.take(10)
        if (recentAlbums.isEmpty()) {
            albumsContainer.addView(emptyText())
        } else {
            recentAlbums.forEach { albumsContainer.addView(albumCard(it)) }
        }
    }

    private fun recentPlayedTracks(tracks: List<Track>): List<Track> {
        val byPath = tracks.associateBy { it.path }
        val historyTracks = AudioPlaybackHistory.load(this)
            .mapNotNull { entry -> byPath[entry.path] ?: trackFromHistory(entry) }
            .distinctBy { it.path }
        return if (historyTracks.isNotEmpty()) historyTracks else tracks.sortedByDescending { it.addedAt }.take(6)
    }

    private fun recentPlayedAlbums(tracks: List<Track>, albums: List<Album>): List<Album> {
        val byPath = tracks.associateBy { it.path }
        val byAlbum = albums.mapNotNull { album -> album.tracks.firstOrNull()?.let { albumGroupingKey(it) to album } }.toMap()
        val orderedKeys = AudioPlaybackHistory.load(this)
            .mapNotNull { entry ->
                val track = byPath[entry.path] ?: trackFromHistory(entry) ?: return@mapNotNull null
                albumGroupingKey(track)
            }
            .distinct()
        val fromHistory = orderedKeys.mapNotNull { key -> byAlbum[key] }
        return if (fromHistory.isNotEmpty()) fromHistory else albums.sortedByDescending { it.addedAt }.take(6)
    }

    private fun trackFromHistory(entry: AudioPlaybackHistory.Entry): Track? {
        if (entry.path.isBlank()) return null
        return Track(
            id = -kotlin.math.abs(entry.path.hashCode()).toLong(),
            title = entry.title.ifBlank { inferTitleFromName(fileNameFromPath(entry.path)) },
            artist = entry.artist.ifBlank { getString(R.string.unknown_artist) },
            album = entry.album.ifBlank { getString(R.string.unknown_generic) },
            albumId = 0L,
            durationMs = entry.durationMs,
            trackNo = entry.trackNumber,
            path = entry.path,
            addedAt = entry.playedAtMs / 1000L,
            artworkPath = preferredFolderCoverPathForAudioPath(entry.path).orEmpty(),
            source = TrackSource.QUEUE,
            sourceLabel = entry.extension.ifBlank { getString(R.string.audio_tab_titles) }
        )
    }

    private fun updateWatchedSummary() {
        val folders = AudioProSettings.watchedFolders(this)
        val local = folders.count { !it.isNetwork }
        val network = folders.count { it.isNetwork }
        findViewById<TextView>(R.id.tvWatchedSummary).text = if (folders.isEmpty()) {
            getString(R.string.audio_watched_folders_empty_info)
        } else {
            val total = resources.getQuantityString(R.plurals.audio_folder_count_compact, folders.size, folders.size)
            "$total  •  ${getString(R.string.tab_local)} $local  •  ${getString(R.string.tab_network)} $network"
        }
    }

    private fun loadPlaylistSummaries(): List<PlaylistSummary> {
        val regular = (1..PlaylistManager.SLOT_COUNT).map { slot ->
            val tracks = PlaylistManager.getPlaylist(this, PlaylistCategory.AUDIO, slot)
            val count = resources.getQuantityString(R.plurals.audio_track_count_compact, tracks.size, tracks.size)
            val first = tracks.firstOrNull()?.let { it.title.ifBlank { it.name.substringBeforeLast('.') } }.orEmpty()
            PlaylistSummary(getString(R.string.playlist_slot_name, slot), listOf(count, first).filter { it.isNotBlank() }.joinToString(" • "), tracks)
        }
        return regular
    }

    private fun loadCachedWatchedTracks(): List<Track> {
        val folders = AudioProSettings.watchedFolders(this)
        if (folders.isEmpty()) return emptyList()
        return AudioWatchedLibraryCache.loadAll(this, folders)
            .map { cachedEntryToTrack(it) }
    }

    private fun cachedEntryToTrack(entry: AudioWatchedLibraryCache.Entry): Track {
        val cached = AudioMetadataExtractor.getCached(this, entry.path)
        val rawTitle = cached?.title?.ifBlank { null } ?: entry.title.ifBlank { inferTitleFromName(entry.name.ifBlank { entry.path.substringAfterLast('/') }) }
        val rawArtist = cached?.artist?.ifBlank { null } ?: entry.artist.ifBlank { inferArtistFromPath(entry.path, entry.sourceLabel.ifBlank { getString(R.string.unknown_artist) }) }
        val rawAlbum = cached?.album?.ifBlank { null } ?: entry.album.ifBlank { inferAlbumFromPath(entry.path, getString(R.string.unknown_generic)) }
        val over = AudioLocalEnhancements.applyOverride(this, entry.path, rawTitle, rawArtist, rawAlbum)
        return Track(
            id = -kotlin.math.abs(entry.path.hashCode()).toLong(),
            title = over.title.ifBlank { rawTitle },
            artist = over.artist.ifBlank { rawArtist },
            album = over.album.ifBlank { rawAlbum },
            albumId = 0L,
            durationMs = (cached?.duration?.takeIf { it > 0L }?.times(1000L)) ?: entry.durationMs,
            trackNo = cached?.trackNumber?.takeIf { it > 0 } ?: entry.trackNumber,
            path = entry.path,
            addedAt = entry.addedAt,
            artworkPath = entry.artworkPath.ifBlank { preferredFolderCoverPathForAudioPath(entry.path).orEmpty() },
            source = if (entry.isNetwork) TrackSource.WATCHED_NETWORK else TrackSource.WATCHED_LOCAL,
            sourceLabel = entry.sourceLabel
        )
    }

    private fun cacheEntryFromTrack(folder: AudioProSettings.WatchedFolder, track: Track, name: String = ""): AudioWatchedLibraryCache.Entry {
        return AudioWatchedLibraryCache.Entry(
            path = track.path,
            name = name.ifBlank { fileNameFromPath(track.path).ifBlank { track.title } },
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            trackNumber = track.trackNo,
            addedAt = track.addedAt,
            extension = track.path.substringBefore('?').substringAfterLast('.', "").uppercase(Locale.getDefault()),
            isNetwork = folder.isNetwork,
            shareId = folder.shareId,
            sourceLabel = track.sourceLabel,
            artworkPath = track.artworkPath.ifBlank { preferredFolderCoverPathForAudioPath(track.path).orEmpty() }
        )
    }

    private fun saveWatchedFolderCache(folder: AudioProSettings.WatchedFolder, tracks: List<Track>) {
        AudioWatchedLibraryCache.save(this, folder, tracks.map { cacheEntryFromTrack(folder, it) })
    }

    private fun refreshWatchedFoldersInBackground(seq: Int, manual: Boolean) {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            val foldersAtStart = AudioProSettings.watchedFolders(this@AudioLibraryActivity)
            val scanned = withContext(LIBRARY_SCAN_DISPATCHER) {
                val local = scanAllWatchedLocalFolders()
                val network = loadWatchedNetworkTracks()
                (local + network).distinctBy { it.path }
            }
            if (seq != renderSeq || isFinishing || isDestroyed) return@launch
            markAutoScanDone(foldersAtStart)
            val kept = removeStaleWatchedTracks(cachedTracks).filter { it.source == TrackSource.QUEUE }
            cachedTracks = (kept + scanned).distinctBy { it.path }
            val settings = cachedSettings ?: AudioProSettings.read(this@AudioLibraryActivity)
            renderLibraryData(settings, cachedTracks)
            persistSnapshotInBackground(cachedTracks)
            val visible = visibleTracksForBackgroundWork(cachedTracks)
            prefetchArtworkInBackground(seq, visible, highPriority = true)
            enrichMetadataInBackground(seq, visible)
            if (manual) Toast.makeText(this@AudioLibraryActivity, R.string.scan_complete, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enrichMetadataInBackground(seq: Int, input: List<Track>) {
        val candidates = input
            .asSequence()
            .filter { it.path.isNotBlank() }
            // Les chemins SMB/UPnP lourds restent en fallback/cache : leur extraction complète
            // se fait au moment de la lecture ou sur demande, pas en masse pendant l'affichage.
            .filterNot { it.path.startsWith("smb://", true) || it.path.startsWith("http://", true) || it.path.startsWith("https://", true) }
            .filter { track ->
                val cached = AudioMetadataExtractor.getCached(this, track.path)
                cached == null || cached.title.isBlank() || cached.artist.isBlank() || cached.album.isBlank() || cached.duration <= 0L || cached.trackNumber <= 0
            }
            .distinctBy { it.path }
            .take(MAX_METADATA_AUTO_EXTRACT)
            .toList()
        if (candidates.isEmpty()) return
        metadataJob?.cancel()
        metadataJob = lifecycleScope.launch {
            val enriched = withContext(LIBRARY_METADATA_DISPATCHER) {
                val out = mutableListOf<Track>()
                candidates.chunked(2).forEach { chunk ->
                    val batch = coroutineScope {
                        chunk.map { track ->
                            async {
                                val info = AudioMetadataExtractor.extract(this@AudioLibraryActivity, track.path, fileNameFromPath(track.path).ifBlank { track.title })
                                track.mergeMetadata(info)
                            }
                        }.awaitAll()
                    }
                    out += batch
                }
                out
            }
            if (seq != renderSeq || isFinishing || isDestroyed) return@launch
            if (enriched.isNotEmpty()) {
                val byPath = enriched.associateBy { it.path }
                cachedTracks = cachedTracks.map { byPath[it.path] ?: it }.distinctBy { it.path }
                cachedSettings?.let {
                    rememberSnapshot(it, cachedTracks)
                    renderLibraryData(it, cachedTracks)
                }
                persistEnrichedWatchedCache(cachedTracks)
                persistSnapshotInBackground(cachedTracks)
                prefetchArtworkInBackground(seq, visibleTracksForBackgroundWork(cachedTracks), highPriority = true)
                // L'extraction est faite en basse priorité ; on ré-affiche seulement le résultat
                // final pour que les titres/artistes/pochettes corrigés soient visibles sans
                // attendre la prochaine ouverture de l'écran.
            }
        }
    }

    private fun Track.mergeMetadata(info: AudioTechnicalInfo): Track {
        val rawTitle = info.title.ifBlank { title }
        val rawArtist = info.artist.ifBlank { artist }
        val rawAlbum = info.album.ifBlank { album }
        val over = AudioLocalEnhancements.applyOverride(this@AudioLibraryActivity, path, rawTitle, rawArtist, rawAlbum)
        return copy(
            title = over.title.ifBlank { rawTitle },
            artist = over.artist.ifBlank { rawArtist },
            album = over.album.ifBlank { rawAlbum },
            durationMs = if (info.duration > 0L) info.duration * 1000L else durationMs,
            trackNo = if (info.trackNumber > 0) info.trackNumber else trackNo
        )
    }

    private fun persistEnrichedWatchedCache(tracks: List<Track>) {
        val folders = AudioProSettings.watchedFolders(this)
        folders.forEach { folder ->
            val folderTracks = tracks.filter { it.source != TrackSource.ANDROID && it.source != TrackSource.QUEUE && belongsToWatchedFolder(it.path, folder) }
            if (folderTracks.isNotEmpty()) saveWatchedFolderCache(folder, folderTracks)
        }
    }

    private fun belongsToWatchedFolder(path: String, folder: AudioProSettings.WatchedFolder): Boolean {
        val cleanFolder = AudioProSettings.normalizeFolder(folder)
        return if (cleanFolder.isNetwork) {
            val normalizedFolderPath = cleanFolder.path.trim('/').lowercase(Locale.getDefault())
            val normalizedPath = path.lowercase(Locale.getDefault())
            val shareMatch = path.startsWith("smb://", true) && cleanFolder.shareName.isNotBlank() && normalizedPath.contains("/${cleanFolder.shareName.lowercase(Locale.getDefault())}/")
            shareMatch || (normalizedFolderPath.isNotBlank() && normalizedPath.contains(normalizedFolderPath))
        } else {
            val cleanPath = runCatching { File(path).canonicalFile.absolutePath }
                .getOrElse { File(path).absolutePath }
                .trimEnd('/')
            val folderPath = cleanFolder.path.trimEnd('/')
            cleanPath == folderPath || cleanPath.startsWith("$folderPath/")
        }
    }

    private fun cacheTrackMetadata(track: Track) {
        AudioMetadataExtractor.putCached(
            this,
            track.path,
            AudioTechnicalInfo(
                artist = track.artist,
                duration = track.durationMs / 1000L,
                extension = track.path.substringBefore('?').substringAfterLast('.', "").uppercase(Locale.getDefault()),
                title = track.title,
                album = track.album,
                trackNumber = track.trackNo
            )
        )
    }

    private fun scanAllWatchedLocalFolders(): List<Track> {
        val known = mutableSetOf<String>()
        val tracks = mutableListOf<Track>()
        AudioProSettings.watchedFolders(this)
            .filterNot { it.isNetwork }
            .forEach { folder ->
                val folderTracks = scanWatchedLocalFolder(folder, known)
                saveWatchedFolderCache(folder, folderTracks)
                tracks += folderTracks
            }
        return tracks
    }

    private suspend fun loadWatchedNetworkTracks(): List<Track> {
        val watched = AudioProSettings.watchedFolders(this).filter { it.isNetwork }
        if (watched.isEmpty()) return emptyList()
        val shares = runCatching { networkRepository.getShares().first().associateBy { it.id } }.getOrDefault(emptyMap())
        return coroutineScope {
            watched.map { folder ->
                async(LIBRARY_SCAN_DISPATCHER) {
                    val share = shares[folder.shareId] ?: return@async emptyList<Track>()
                    val result = mutableListOf<Track>()
                    val seen = mutableSetOf<String>()
                    val settings = AudioProSettings.read(this@AudioLibraryActivity)
                    scanNetworkFolder(share, folder, depth = 0, result = result, seen = seen, settings = settings, inheritedCoverPath = "")
                    saveWatchedFolderCache(folder, result)
                    result
                }
            }.awaitAll().flatten().distinctBy { it.path }
        }
    }

    private suspend fun scanNetworkFolder(
        share: NetworkShare,
        watchedFolder: AudioProSettings.WatchedFolder,
        depth: Int,
        result: MutableList<Track>,
        seen: MutableSet<String>,
        settings: AudioProSettings.Values,
        inheritedCoverPath: String
    ) {
        if (depth > 12 || result.size >= MAX_WATCHED_NETWORK_SCAN_TRACKS) return
        val browsePath = if (share.type == ShareType.UPNP) watchedFolder.path.ifBlank { "0" } else watchedFolder.path
        val items = runCatching {
            if (share.type == ShareType.UPNP) upnpBrowser.listFiles(share, browsePath).getOrThrow()
            else smbBrowser.listFiles(share, browsePath).getOrThrow()
        }.getOrDefault(emptyList())
        val audioExtensions = setOf("mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "ape", "dts", "ac3", "mka", "wv", "aiff", "alac")
        val localFolderCoverPath = pickNetworkFolderCoverPath(items)
        val folderCoverPath = localFolderCoverPath.ifBlank { inheritedCoverPath }
        val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" }
        val files = items.filter { it.extension.lowercase(Locale.getDefault()) in audioExtensions || it.mimeType.startsWith("audio/", true) }
        files.forEach { item ->
            if (result.size >= MAX_WATCHED_NETWORK_SCAN_TRACKS) return
            if (!seen.add(item.path)) return@forEach
            val cached = AudioMetadataExtractor.getCached(this, item.path)
            val name = item.name.ifBlank { fileNameFromPath(item.path) }
            val fallbackTitle = inferTitleFromName(name)
            val fallbackAlbum = inferAlbumFromPath(item.path, watchedFolder.name.ifBlank { share.name.ifBlank { getString(R.string.unknown_generic) } })
            val fallbackArtist = inferArtistFromPath(item.path, share.name.ifBlank { getString(R.string.tab_network) })
            val rawTitle = cached?.title?.ifBlank { null } ?: fallbackTitle
            val rawArtist = cached?.artist?.ifBlank { null } ?: fallbackArtist
            val rawAlbum = cached?.album?.ifBlank { null } ?: fallbackAlbum
            val duration = cached?.duration?.takeIf { it > 0L } ?: item.duration
            if (settings.ignoreShort && duration in 1 until 30) return@forEach
            val over = AudioLocalEnhancements.applyOverride(this, item.path, rawTitle, rawArtist, rawAlbum)
            val track = Track(
                id = -kotlin.math.abs(item.path.hashCode()).toLong(),
                title = over.title.ifBlank { rawTitle },
                artist = over.artist.ifBlank { rawArtist },
                album = over.album.ifBlank { rawAlbum },
                albumId = 0L,
                durationMs = duration * 1000L,
                trackNo = cached?.trackNumber?.takeIf { it > 0 } ?: inferTrackNumberFromName(name),
                path = item.path,
                addedAt = System.currentTimeMillis() / 1000L,
                artworkPath = folderCoverPath,
                source = TrackSource.WATCHED_NETWORK,
                sourceLabel = share.name.ifBlank { getString(R.string.tab_network) }
            )
            result += track
        }
        folders.forEach { item ->
            scanNetworkFolder(share, watchedFolder.copy(path = item.path), depth + 1, result, seen, settings, folderCoverPath)
        }
    }

    private fun loadTracks(): List<Track> {
        val settings = AudioProSettings.read(this)
        if (!settings.autoScan) return loadQueueTracks()
        val watchedLocalFolders = AudioProSettings.watchedFolders(this).filterNot { it.isNetwork }
        // La bibliothèque Pro+ ne doit plus aspirer automatiquement le dossier Android /Music.
        // Elle affiche uniquement les dossiers explicitement cochés comme surveillés, plus la
        // file audio courante en fallback. Cela évite que /storage/.../Music reste visible alors
        // qu'il n'est pas coché dans le navigateur des dossiers surveillés.
        if (watchedLocalFolders.isEmpty()) return loadQueueTracks()
        val result = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = if (settings.ignoreShort) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }
        runCatching {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val addedIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val path = c.getString(dataIdx).orEmpty().ifBlank {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    }
                    if (watchedLocalFolders.none { belongsToWatchedFolder(path, it) }) continue
                    val rawTitle = c.getString(titleIdx).orEmpty().ifBlank { getString(R.string.unknown_title) }
                    val rawArtist = c.getString(artistIdx).orEmpty().ifBlank { getString(R.string.unknown_artist) }
                    val rawAlbum = c.getString(albumIdx).orEmpty().ifBlank { getString(R.string.unknown_generic) }
                    val over = AudioLocalEnhancements.applyOverride(this, path, rawTitle, rawArtist, rawAlbum)
                    result.add(
                        Track(
                            id = id,
                            title = over.title.ifBlank { rawTitle },
                            artist = over.artist.ifBlank { rawArtist },
                            album = over.album.ifBlank { rawAlbum },
                            albumId = c.getLong(albumIdIdx),
                            durationMs = c.getLong(durIdx),
                            trackNo = c.getInt(trackIdx),
                            path = path,
                            addedAt = c.getLong(addedIdx),
                            artworkPath = preferredFolderCoverPathForAudioPath(path).orEmpty(),
                            source = TrackSource.WATCHED_LOCAL,
                            sourceLabel = getString(R.string.tab_local)
                        )
                    )
                }
            }
        }
        // Les dossiers surveillés sont affichés depuis le cache puis rafraîchis en arrière-plan.
        // On évite ici de walker des dossiers potentiellement énormes avant le premier rendu.
        return result
    }

    private fun scanWatchedLocalFolder(folder: AudioProSettings.WatchedFolder, knownPaths: MutableSet<String>): List<Track> {
        val root = File(folder.path)
        if (!root.exists() || !root.isDirectory) return emptyList()
        val settings = AudioProSettings.read(this)
        val extensions = setOf("mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "ape", "dts", "ac3", "mka", "wv", "aiff", "alac")
        val result = mutableListOf<Track>()
        val folderCoverIndex = indexLocalFolderCovers(root)
        root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase(Locale.getDefault()) in extensions }
            .take(MAX_WATCHED_SCAN_TRACKS_PER_FOLDER)
            .forEach { file ->
                if (!knownPaths.add(file.absolutePath)) return@forEach
                val cached = AudioMetadataExtractor.getCached(this, file.absolutePath)
                val rawTitle = cached?.title?.ifBlank { null } ?: inferTitleFromName(file.name)
                val rawArtist = cached?.artist?.ifBlank { null } ?: inferArtistFromFile(file, root)
                val rawAlbum = cached?.album?.ifBlank { null } ?: inferAlbumFromFile(file, root, folder.name)
                val duration = cached?.duration ?: 0L
                if (settings.ignoreShort && duration in 1 until 30) return@forEach
                val over = AudioLocalEnhancements.applyOverride(this, file.absolutePath, rawTitle, rawArtist, rawAlbum)
                val track = Track(
                    id = -kotlin.math.abs(file.absolutePath.hashCode()).toLong(),
                    title = over.title.ifBlank { rawTitle },
                    artist = over.artist.ifBlank { rawArtist },
                    album = over.album.ifBlank { rawAlbum },
                    albumId = 0L,
                    durationMs = duration * 1000L,
                    trackNo = cached?.trackNumber?.takeIf { it > 0 } ?: inferTrackNumberFromName(file.name),
                    path = file.absolutePath,
                    addedAt = file.lastModified() / 1000L,
                    artworkPath = preferredFolderCoverPathForFile(file, folderCoverIndex).orEmpty(),
                    source = TrackSource.WATCHED_LOCAL,
                    sourceLabel = getString(R.string.tab_local)
                )
                result += track
            }
        return result
    }

    private fun loadQueueTracks(): List<Track> {
        val state = AudioRepository.loadState(this)
        return state.items.mapIndexed { index, item ->
            val cached = AudioMetadataExtractor.getCached(this, item.path)
            val rawTitle = cached?.title?.ifBlank { null } ?: item.name.substringBeforeLast('.')
            val rawArtist = cached?.artist?.ifBlank { null } ?: getString(R.string.unknown_artist)
            val rawAlbum = cached?.album?.ifBlank { null } ?: getString(R.string.unknown_generic)
            val over = AudioLocalEnhancements.applyOverride(this, item.path, rawTitle, rawArtist, rawAlbum)
            Track(
                id = index.toLong(),
                title = over.title.ifBlank { rawTitle },
                artist = over.artist.ifBlank { rawArtist },
                album = over.album.ifBlank { rawAlbum },
                albumId = 0L,
                durationMs = (cached?.duration ?: 0L) * 1000L,
                trackNo = cached?.trackNumber ?: 0,
                path = item.path,
                addedAt = System.currentTimeMillis() / 1000L - index,
                artworkPath = preferredFolderCoverPathForAudioPath(item.path).orEmpty(),
                source = TrackSource.QUEUE,
                sourceLabel = getString(R.string.audio_tab_playlists)
            )
        }.filter { !AudioProSettings.read(this).ignoreShort || it.durationMs >= 30_000L || it.durationMs == 0L }
    }

    private fun configureSortModeButton(label: String) {
        configureHeaderActionButton(label, enabled = true) {
            openedAlbumDetailKey = null
            librarySortMode = (librarySortMode + 1) % 3
            renderFromCache()
        }
    }

    private fun configureHeaderActionButton(label: String, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
        findViewById<TextView>(R.id.tvSortMode).apply {
            text = label
            isEnabled = enabled
            isClickable = enabled && onClick != null
            isFocusable = enabled && onClick != null
            alpha = if (enabled) 1f else 0.65f
            setTextColor(if (enabled) accentColor else textMuted)
            styleLibraryPill(this, selected = false, enabled = enabled, fillMix = 0.14f)
            setOnClickListener { if (enabled) onClick?.invoke() }
        }
    }

    private fun albumDetailKey(album: Album): String = album.tracks.firstOrNull()?.let { albumGroupingKey(it) }
        ?: "${normalizeForSearch(album.artist)}|${normalizeForSearch(album.title)}"

    private fun openAlbumDetailView(album: Album) {
        openedAlbumDetailKey = albumDetailKey(album)
        selectedTab = 0
        renderFromCache()
        findViewById<ScrollView>(R.id.libraryScroll).post {
            runCatching { findViewById<ScrollView>(R.id.libraryScroll).smoothScrollTo(0, findViewById<View>(R.id.tracksSectionHeader).top) }
        }
    }

    private fun closeAlbumDetailView() {
        openedAlbumDetailKey = null
        renderFromCache()
    }

    private fun albumPlaybackTracks(album: Album): List<Track> = album.tracks
        .distinctBy { it.path }
        .sortedWith(
            compareBy<Track> { discNumberFromPath(it.path) }
                .thenBy { normalizeTrackNumber(it.trackNo) }
                .thenBy { normalizeForSearch(it.title) }
        )

    private fun playAlbumFromLibrary(tracks: List<Track>) {
        val ordered = tracks.distinctBy { it.path }
        if (ordered.isEmpty()) {
            Toast.makeText(this, R.string.audio_no_music, Toast.LENGTH_SHORT).show()
            return
        }
        appendAndPlayTracksFromLibrary(ordered, ordered.first())
    }

    private fun albumDetailHeader(album: Album, tracks: List<Track>): View {
        val root = baseRow().apply {
            isClickable = false
            isFocusable = false
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }
        root.addView(createCoverView(album.albumId, album.artworkPath, 72, false), LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(titleText(album.title, 18f, 2))
        texts.addView(subText(album.artist, 13f, 1).apply { setTextColor(accentColor); setPadding(0, dp(4), 0, 0) })
        val count = resources.getQuantityString(R.plurals.audio_track_count_compact, tracks.size, tracks.size)
        texts.addView(subText("$count • ${formatDuration(tracks.sumOf { it.durationMs })}", 12f, 1).apply { setPadding(0, dp(4), 0, 0) })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(albumOverflowButton(album, tracks))
        return root
    }

    private fun albumDetailBackRow(): View {
        val root = baseRow()
        root.setOnClickListener { closeAlbumDetailView() }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(accentColor)
            styleLibraryPill(this, selected = false, enabled = true, fillMix = 0.14f)
            setPadding(dp(9))
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(titleText(getString(R.string.audio_back_to_albums), 15f, 1))
        texts.addView(subText(getString(R.string.audio_back_to_albums_hint), 12f, 1))
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return root
    }

    private fun resumeCard(track: Track, queue: List<Track>): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(8), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { appendAndPlaySingleTrackFromLibrary(track) }
        }
        root.layoutParams = LinearLayout.LayoutParams(dp(218), dp(94)).apply { marginEnd = dp(10) }
        root.addView(createCoverView(track.albumId, artworkPathForTrack(track), 72, true))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(10) })
        texts.addView(titleText(track.title, 15f, 2))
        texts.addView(subText(track.artist, 13f, 1).apply { setTextColor(accentColor) })
        texts.addView(subText(trackBadgeText(track), 12f, 1).apply { setPadding(0, dp(4), 0, 0) })
        return root
    }

    private fun albumCard(album: Album): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openAlbumDetailView(album) }
        }
        root.layoutParams = LinearLayout.LayoutParams(dp(122), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }
        root.addView(createCoverView(album.albumId, album.artworkPath, 122, false))
        root.addView(titleText(album.title, 14f, 1).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(subText(album.artist, 12f, 1))
        root.addView(subText(resources.getQuantityString(R.plurals.audio_track_count_compact, album.tracks.size, album.tracks.size), 11f, 1).apply { setTextColor(accentColor) })
        return root
    }

    private fun albumRow(album: Album): View {
        val root = baseRow()
        root.setOnClickListener { openAlbumDetailView(album) }
        root.addView(createCoverView(album.albumId, album.artworkPath, 52, false), LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(titleText(album.title, 15f, 1))
        texts.addView(subText(album.artist, 12f, 1).apply { setTextColor(accentColor) })
        texts.addView(subText(resources.getQuantityString(R.plurals.audio_track_count_compact, album.tracks.size, album.tracks.size) + " • " + formatDuration(album.tracks.sumOf { it.durationMs }), 12f, 1))
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(rowPlayBadge())
        return root
    }

    private fun artistRow(artist: ArtistSummary): View {
        val root = baseRow()
        root.setOnClickListener { openArtistAlbumsView(artist) }
        root.addView(avatar(artist.name), LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(titleText(artist.name, 16f, 1))
        val albumText = resources.getQuantityString(R.plurals.audio_album_count_compact, artist.albumsCount, artist.albumsCount)
        val trackText = resources.getQuantityString(R.plurals.audio_track_count_compact, artist.tracks.size, artist.tracks.size)
        texts.addView(subText("$albumText • $trackText", 12f, 1).apply { setTextColor(accentColor) })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(rowPlayBadge())
        return root
    }

    private fun trackRow(index: Int, track: Track): View {
        val root = baseRow()
        root.setOnClickListener { appendAndPlaySingleTrackFromLibrary(track) }
        val number = TextView(this).apply {
            text = normalizeTrackNumber(track.trackNo).takeIf { it > 0 }?.toString() ?: index.toString()
            setTextColor(textMuted)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        root.addView(number, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(createCoverView(track.albumId, artworkPathForTrack(track), 44, false), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6); marginEnd = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(titleText(track.title, 15f, 1))
        texts.addView(subText("${track.artist} • ${track.album}", 12f, 1).apply { setTextColor(accentColor) })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(formatBadge(track.path.substringAfterLast('.').uppercase(Locale.getDefault()).take(5)))
        root.addView(subText(formatDuration(track.durationMs), 13f, 1))
        root.addView(trackOverflowButton(track))
        return root
    }

    private fun trackOverflowButton(track: Track): View = ImageButton(this).apply {
        setImageResource(R.drawable.ic_more_vert)
        setColorFilter(textMuted)
        background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_action_button)
        contentDescription = getString(R.string.audio_track_more_options)
        setPadding(dp(9))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
        setOnClickListener { showTrackOverflowActions(track) }
    }

    private fun albumOverflowButton(album: Album, tracks: List<Track>): View = ImageButton(this).apply {
        setImageResource(R.drawable.ic_more_vert)
        setColorFilter(textMuted)
        background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_action_button)
        contentDescription = getString(R.string.audio_album_more_options)
        setPadding(dp(9))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(10) }
        setOnClickListener { showAlbumOverflowActions(album, tracks) }
    }

    private fun showTrackOverflowActions(track: Track) {
        val options = arrayOf(
            getString(R.string.audio_add_track_queue),
            getString(R.string.audio_add_track_playlist),
            getString(R.string.audio_add_track_blaze_party)
        )
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> appendSingleTrackToAudioQueue(track)
                    1 -> showTrackPlaylistChoiceDialog(track)
                    else -> addTracksToBlazeParty(listOf(track))
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun showTrackPlaylistChoiceDialog(track: Track) {
        val labels = (1..PlaylistManager.SLOT_COUNT)
            .map { slot -> getString(R.string.playlist_slot_name, slot) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(labels) { _, which -> addTracksToPlaylist(which + 1, listOf(track)) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun showAlbumOverflowActions(album: Album, tracks: List<Track>) {
        val ordered = tracks.distinctBy { it.path }
        if (ordered.isEmpty()) {
            Toast.makeText(this, R.string.audio_no_music, Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            getString(R.string.audio_add_album_queue),
            getString(R.string.audio_add_track_playlist),
            getString(R.string.audio_add_track_blaze_party)
        )
        AlertDialog.Builder(this)
            .setTitle(album.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> appendTracksToAudioQueue(ordered)
                    1 -> showAlbumPlaylistChoiceDialog(album, ordered)
                    else -> addTracksToBlazeParty(ordered)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun showAlbumPlaylistChoiceDialog(album: Album, tracks: List<Track>) {
        val labels = (1..PlaylistManager.SLOT_COUNT)
            .map { slot -> getString(R.string.playlist_slot_name, slot) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(album.title)
            .setItems(labels) { _, which -> addTracksToPlaylist(which + 1, tracks) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun playlistRow(playlist: PlaylistSummary): View {
        val root = baseRow()
        root.setOnClickListener {
            if (playlist.tracks.isEmpty()) {
                Toast.makeText(this, R.string.playlist_empty, Toast.LENGTH_SHORT).show()
            } else {
                showPlaylistActions(playlist)
            }
        }
        val icon = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_cover_placeholder)
            val image = ImageView(this@AudioLibraryActivity).apply {
                setImageResource(if (playlist.isParty) R.drawable.ic_group_people else R.drawable.ic_layout_list)
                setColorFilter(accentColor)
                setPadding(dp(13))
            }
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(titleText(playlist.title, 16f, 1))
        texts.addView(subText(playlist.subtitle.ifBlank { resources.getQuantityString(R.plurals.audio_track_count_compact, 0, 0) }, 12f, 1).apply { setTextColor(if (playlist.tracks.isEmpty()) textMuted else accentColor) })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(rowPlayBadge(enabled = playlist.tracks.isNotEmpty()))
        return root
    }

    private fun baseRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        isFocusable = true
    }

    private fun rowPlayBadge(enabled: Boolean = true): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_play_white)
        styleLibraryPill(this, selected = false, enabled = enabled, fillMix = 0.14f)
        setColorFilter(if (enabled) accentColor else textMuted)
        alpha = if (enabled) 1f else 0.45f
        setPadding(dp(9))
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(10) }
    }

    private fun formatBadge(value: String): TextView = TextView(this).apply {
        text = value.ifBlank { "AUDIO" }
        setTextColor(accentColor)
        textSize = 11f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_badge_black)
        includeFontPadding = false
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)).apply { marginStart = dp(8); marginEnd = dp(8) }
    }

    private fun avatar(name: String): TextView = TextView(this).apply {
        text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        setTextColor(accentColor)
        textSize = 22f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_cover_placeholder)
    }

    private fun createCoverView(albumId: Long, artworkPath: String, sizeDp: Int, overlayPlay: Boolean): View {
        val frame = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_cover_placeholder)
            clipToOutline = false
        }
        frame.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        val image = RoundedImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            radiusDp = 12f
            setBackgroundResource(R.drawable.bg_audio_cover_placeholder)
            val cachedBitmap = if (artworkPath.isNotBlank()) fr.retrospare.blazeplayer.ui.ThumbnailUtils.getMemoryCachedAudioArtworkBitmap(artworkPath) else null
            when {
                cachedBitmap != null -> runCatching {
                    clearColorFilter()
                    setPadding(0)
                    setImageBitmap(cachedBitmap)
                }
                // Pas de setImageURI synchrone ici : décoder les jaquettes MediaStore
                // pendant l'inflation de centaines de lignes peut figer l'UI. Les pochettes
                // sont chargées via ThumbnailUtils en basse priorité juste après.
            }
            if (drawable == null) {
                setImageResource(R.drawable.ic_audio)
                setColorFilter(textMuted)
                setPadding(dp(sizeDp / 4))
            }
            if (cachedBitmap == null && artworkPath.isNotBlank()) {
                val expectedPath = artworkPath
                setTag(R.id.ivThumbnail, expectedPath)
                lifecycleScope.launch {
                    val bitmap = withContext(LIBRARY_ARTWORK_DISPATCHER) {
                        fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkBitmap(this@AudioLibraryActivity.applicationContext, expectedPath)
                    }
                    if (bitmap != null && getTag(R.id.ivThumbnail) == expectedPath && !isFinishing && !isDestroyed) {
                        clearColorFilter()
                        setPadding(0)
                        setImageBitmap(bitmap)
                    }
                }
            }
        }
        frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (overlayPlay) {
            val play = ImageView(this).apply {
                setImageResource(R.drawable.ic_play_white)
                background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_circle_translucent)
                setPadding(dp(8))
            }
            frame.addView(play, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(4); bottomMargin = dp(4) })
        }
        return frame
    }

    private fun titleText(value: String, sp: Float, maxLines: Int): TextView = TextView(this).apply {
        text = value
        setTextColor(textMain)
        textSize = sp
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        includeFontPadding = false
        setSingleLine(maxLines == 1)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun subText(value: String, sp: Float, maxLines: Int): TextView = TextView(this).apply {
        text = value
        setTextColor(textMuted)
        textSize = sp
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        includeFontPadding = false
        setSingleLine(maxLines == 1)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun emptyText(): TextView = subText(getString(R.string.audio_no_music), 14f, 2).apply {
        gravity = Gravity.CENTER
        minWidth = dp(220)
        minHeight = dp(56)
        setPadding(dp(8))
    }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(0x18FFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun showAlbumActions(album: Album) {
        val tracks = albumPlaybackTracks(album)
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.audio_no_music, Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            getString(R.string.audio_play_album),
            getString(R.string.audio_add_album_queue),
            getString(R.string.audio_select_tracks)
        )
        AlertDialog.Builder(this)
            .setTitle(album.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playAlbumFromLibrary(tracks)
                    1 -> appendTracksToAudioQueue(tracks)
                    2 -> showAlbumTrackPicker(album, tracks)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun showAlbumTrackPicker(album: Album, tracks: List<Track>) {
        val checked = BooleanArray(tracks.size) { true }
        val checks = mutableListOf<CheckBox>()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val selectAll = modalMiniButton(getString(R.string.select_all)).apply {
            setOnClickListener {
                checked.indices.forEach { index -> checked[index] = true }
                checks.forEach { it.isChecked = true }
            }
        }
        val deselectAll = modalMiniButton(getString(R.string.deselect_all)).apply {
            setOnClickListener {
                checked.indices.forEach { index -> checked[index] = false }
                checks.forEach { it.isChecked = false }
            }
        }
        actionRow.addView(selectAll, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        actionRow.addView(deselectAll, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(actionRow)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        tracks.forEachIndexed { index, track ->
            val number = normalizeTrackNumber(track.trackNo).takeIf { it > 0 } ?: (index + 1)
            val box = CheckBox(this).apply {
                text = "$number. ${track.title}"
                setTextColor(textMain)
                textSize = 14f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                setButtonTintList(ColorStateList.valueOf(accentColor))
                isChecked = true
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, isChecked -> checked[index] = isChecked }
            }
            checks += box
            list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330)))
        AlertDialog.Builder(this)
            .setTitle(album.title)
            .setView(root)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val selected = tracks.filterIndexed { index, _ -> checked.getOrNull(index) == true }
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.toast_select_tracks_first, Toast.LENGTH_SHORT).show()
                } else {
                    showTrackDestinationDialog(selected)
                }
            }
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun modalMiniButton(label: String): MaterialButton = MaterialButton(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minWidth = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(18)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this@AudioLibraryActivity, R.color.audio_library_accent_stroke))
        setTextColor(accentColor)
        backgroundTintList = ColorStateList.valueOf(0x00000000)
        ButtonTextFitter.fit(this, minSp = 9, maxSp = 13)
    }

    private fun showTrackDestinationDialog(tracks: List<Track>) {
        val labels = buildList {
            add(getString(R.string.queue_short))
            (1..PlaylistManager.SLOT_COUNT).forEach { slot -> add(getString(R.string.playlist_slot_name, slot)) }
            add(getString(R.string.blaze_party_playlist_title))
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.audio_track_count_compact, tracks.size, tracks.size))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> appendTracksToAudioQueue(tracks)
                    in 1..PlaylistManager.SLOT_COUNT -> addTracksToPlaylist(which, tracks)
                    else -> addTracksToBlazeParty(tracks)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun playTrackList(tracks: List<Track>) {
        val unique = tracks.distinctBy { it.path }
        appendAndPlayTracksFromLibrary(unique, unique.firstOrNull())
    }

    private fun appendTracksToAudioQueue(tracks: List<Track>) {
        val unique = tracks.distinctBy { it.path }
        unique.forEach { cacheTrackMetadata(it) }
        val state = AudioRepository.loadState(this)
        val existing = state.items.map { it.path }.toHashSet()
        val additions = unique
            .filter { existing.add(it.path) }
            .map { PlaylistItem(it.path, it.title.ifBlank { fileNameFromPath(it.path) }) }
        if (additions.isEmpty()) {
            Toast.makeText(this, resources.getQuantityString(R.plurals.playlist_items_already_present, unique.size, unique.size), Toast.LENGTH_SHORT).show()
            return
        }
        val newItems = state.items + additions
        val newIndex = if (state.items.isEmpty()) 0 else state.index
        AudioRepository.save(this, newItems, newIndex, state.positionMs, state.repeatMode, state.shuffle)
        requestAudioQueueAppend(additions)
        Toast.makeText(this, resources.getQuantityString(R.plurals.playlist_items_added, additions.size, additions.size), Toast.LENGTH_SHORT).show()
    }

    private fun appendSingleTrackToAudioQueue(track: Track) {
        appendTracksToAudioQueue(listOf(track))
    }

    private fun appendAndPlaySingleTrackFromLibrary(track: Track) {
        appendAndPlayTracksFromLibrary(listOf(track), track)
    }

    private fun appendAndPlayTracksFromLibrary(tracks: List<Track>, selected: Track?) {
        val unique = tracks.distinctBy { it.path }
        val target = selected ?: unique.firstOrNull() ?: return
        unique.forEach { cacheTrackMetadata(it) }
        val items = unique.map { PlaylistItem(it.path, it.title.ifBlank { fileNameFromPath(it.path) }) }
        requestAudioQueueAppendAndPlay(items, target.path)
    }

    private fun requestAudioQueueAppend(items: List<PlaylistItem>) {
        if (items.isEmpty()) return
        try {
            startService(Intent(this, BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_APPEND_AUDIO_QUEUE
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_PATHS, ArrayList(items.map { it.path }))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_NAMES, ArrayList(items.map { it.name }))
            })
        } catch (_: Exception) {
            // La sauvegarde dans AudioRepository a déjà été faite : la file sera restaurée à
            // la prochaine ouverture du lecteur même si le service ne peut pas être lancé.
        }
    }

    private fun requestAudioQueueAppendAndPlay(items: List<PlaylistItem>, targetPath: String) {
        if (items.isEmpty() || targetPath.isBlank()) return
        val startIndex = items.indexOfFirst { it.path == targetPath }.coerceAtLeast(0)
        runCatching {
            val state = AudioRepository.loadState(this)
            val existing = state.items.map { it.path }.toMutableSet()
            val additions = items.filter { existing.add(it.path) }
            val merged = state.items + additions
            if (merged.isNotEmpty()) {
                val targetIndex = state.items.indexOfFirst { it.path == targetPath }.takeIf { it >= 0 }
                    ?: additions.indexOfFirst { it.path == targetPath }.takeIf { it >= 0 }?.let { state.items.size + it }
                    ?: state.index.coerceIn(0, (merged.size - 1).coerceAtLeast(0))
                AudioRepository.save(this, merged, targetIndex, 0L, state.repeatMode, state.shuffle)
            }
        }
        try {
            startService(Intent(this, BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_APPEND_AUDIO_QUEUE_AND_PLAY
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_PATHS, ArrayList(items.map { it.path }))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_NAMES, ArrayList(items.map { it.name }))
                putExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_INDEX, startIndex)
                if (targetPath.startsWith("content://", ignoreCase = true)) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    data = Uri.parse(targetPath)
                    clipData = android.content.ClipData.newUri(contentResolver, items.getOrNull(startIndex)?.name ?: targetPath, Uri.parse(targetPath))
                }
            })
        } catch (_: Exception) {
            // Pas de navigation vers le lecteur : si le service n'est pas disponible, on préserve
            // au moins la bibliothèque ouverte et la file sauvegardée par le prochain appel manuel.
        }
    }

    private fun addTracksToPlaylist(slot: Int, tracks: List<Track>) {
        val refs = tracks.distinctBy { it.path }.map { it.toPlaylistRef() }
        val added = PlaylistManager.addToPlaylist(this, PlaylistCategory.AUDIO, slot, refs)
        Toast.makeText(this, resources.getQuantityString(R.plurals.playlist_items_added, added, added), Toast.LENGTH_SHORT).show()
        renderFromCache()
    }

    private fun addTracksToBlazeParty(tracks: List<Track>) {
        val refs = tracks.distinctBy { it.path }.map { it.toPlaylistRef() }
        val added = PlaylistManager.addToBlazePartyPlaylist(this, refs)
        Toast.makeText(this, resources.getQuantityString(R.plurals.blaze_party_items_added, added, added), Toast.LENGTH_SHORT).show()
        renderFromCache()
    }

    private fun Track.toPlaylistRef(): PlaylistTrackRef {
        val cached = AudioMetadataExtractor.getCached(this@AudioLibraryActivity, path)
        return PlaylistTrackRef(
            path = path,
            name = fileNameFromPath(path).ifBlank { title },
            artist = artist,
            title = title,
            album = album,
            trackNumber = trackNo,
            extension = cached?.extension?.ifBlank { path.substringBefore('?').substringAfterLast('.', "").uppercase(Locale.getDefault()) }
                ?: path.substringBefore('?').substringAfterLast('.', "").uppercase(Locale.getDefault()),
            bitrate = cached?.bitrate ?: 0L,
            isLossless = cached?.isLossless ?: false,
            durationMs = durationMs
        )
    }

    private fun openTrackQueue(queue: List<Track>, selected: Track) {
        val unique = queue.distinctBy { it.path }
        if (unique.isEmpty()) return
        unique.forEach { cacheTrackMetadata(it) }
        val index = unique.indexOfFirst { it.path == selected.path }.coerceAtLeast(0)
        val items = unique.map { PlaylistItem(it.path, it.title.ifBlank { fileNameFromPath(it.path) }) }
        AudioRepository.save(this, items, index)
        startService(Intent(this, BlazePlayerService::class.java).apply {
            action = BlazePlayerService.ACTION_PLAY_AUDIO_QUEUE
            putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_PATHS, ArrayList(items.map { it.path }))
            putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_NAMES, ArrayList(items.map { it.name }))
            putExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_INDEX, index)
            if (selected.path.startsWith("content://", ignoreCase = true)) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = Uri.parse(selected.path)
                clipData = android.content.ClipData.newUri(contentResolver, selected.title, Uri.parse(selected.path))
            }
        })
        startActivity(Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
            putExtra("openBlazeAudio", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (selected.path.startsWith("content://", ignoreCase = true)) data = Uri.parse(selected.path)
        })
        finish()
    }

    private fun openPlaylist(tracks: List<PlaylistTrackRef>) {
        val items = tracks
            .map { PlaylistItem(it.path, it.title.ifBlank { it.name }) }
            .distinctBy { it.path }
        if (items.isEmpty()) return
        AudioRepository.save(this, items, 0)
        val first = items.first()
        startService(Intent(this, BlazePlayerService::class.java).apply {
            action = BlazePlayerService.ACTION_PLAY_AUDIO_QUEUE
            putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_PATHS, ArrayList(items.map { it.path }))
            putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_NAMES, ArrayList(items.map { it.name }))
            putExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_INDEX, 0)
            if (first.path.startsWith("content://", ignoreCase = true)) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = Uri.parse(first.path)
                clipData = android.content.ClipData.newUri(contentResolver, first.name, Uri.parse(first.path))
            }
        })
        startActivity(Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
            putExtra("openBlazeAudio", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (first.path.startsWith("content://", ignoreCase = true)) data = Uri.parse(first.path)
        })
        finish()
    }

    private fun openTrack(track: Track) {
        val dataUri = if (track.path.startsWith("content://")) Uri.parse(track.path) else null
        startActivity(Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
            putExtra("externalAudioPath", track.path)
            putExtra("externalAudioName", track.title)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (dataUri != null) data = dataUri
        })
        finish()
    }

    private fun openWatchedFoldersBrowser() {
        startActivity(Intent(this, AudioBrowserActivity::class.java).apply {
            putExtra(AudioBrowserActivity.EXTRA_WATCHED_FOLDERS_MODE, true)
        })
    }

    private fun openArtistAlbumsView(artist: ArtistSummary) {
        openedAlbumDetailKey = null
        searchQuery = artist.name
        selectedTab = 0
        renderFromCache()
    }

    private fun showPlaylistActions(playlist: PlaylistSummary) {
        val options = arrayOf(
            getString(R.string.audio_add_playlist_queue),
            getString(R.string.audio_add_playlist_blaze_party)
        )
        AlertDialog.Builder(this)
            .setTitle(playlist.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> appendPlaylistRefsToAudioQueue(playlist.tracks)
                    else -> addPlaylistRefsToBlazeParty(playlist.tracks)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun appendPlaylistRefsToAudioQueue(refs: List<PlaylistTrackRef>) {
        val uniqueRefs = refs.distinctBy { it.path }
        if (uniqueRefs.isEmpty()) return
        uniqueRefs.forEach { ref ->
            AudioMetadataExtractor.putCached(
                this,
                ref.path,
                AudioTechnicalInfo(
                    artist = ref.artist,
                    duration = ref.durationMs / 1000L,
                    extension = ref.extension,
                    title = ref.title.ifBlank { ref.name },
                    album = ref.album,
                    trackNumber = ref.trackNumber,
                    bitrate = ref.bitrate,
                    isLossless = ref.isLossless
                )
            )
        }
        val state = AudioRepository.loadState(this)
        val existing = state.items.map { it.path }.toHashSet()
        val additions = uniqueRefs
            .filter { existing.add(it.path) }
            .map { PlaylistItem(it.path, it.title.ifBlank { it.name }) }
        if (additions.isEmpty()) {
            Toast.makeText(this, resources.getQuantityString(R.plurals.playlist_items_already_present, uniqueRefs.size, uniqueRefs.size), Toast.LENGTH_SHORT).show()
            return
        }
        val newItems = state.items + additions
        val newIndex = if (state.items.isEmpty()) 0 else state.index
        AudioRepository.save(this, newItems, newIndex, state.positionMs, state.repeatMode, state.shuffle)
        requestAudioQueueAppend(additions)
        Toast.makeText(this, resources.getQuantityString(R.plurals.playlist_items_added, additions.size, additions.size), Toast.LENGTH_SHORT).show()
    }

    private fun addPlaylistRefsToBlazeParty(refs: List<PlaylistTrackRef>) {
        val added = PlaylistManager.addToBlazePartyPlaylist(this, refs.distinctBy { it.path })
        Toast.makeText(this, resources.getQuantityString(R.plurals.blaze_party_items_added, added, added), Toast.LENGTH_SHORT).show()
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            setText(searchQuery)
            setSingleLine(true)
            hint = getString(R.string.audio_search)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(textMain)
            setHintTextColor(textMuted)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_search))
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), 0)
                addView(input)
            })
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setNeutralButton(getString(R.string.action_clear)) { _, _ ->
                searchQuery = ""
                renderFromCache()
            }
            .setPositiveButton(getString(R.string.action_ok)) { _, _ ->
                searchQuery = input.text.toString().trim()
                renderFromCache()
            }
            .show()
            .also { DialogButtonStyler.style(it) }
    }


    private fun fileNameFromPath(path: String): String = runCatching {
        Uri.decode(path.substringBefore('?').trimEnd('/').substringAfterLast('/'))
    }.getOrDefault(path.substringBefore('?').substringAfterLast('/'))

    private fun inferTitleFromName(name: String): String {
        val base = Uri.decode(name.substringBefore('?').substringBeforeLast('.', name)).trim()
        return base
            .replace('_', ' ')
            .replace(Regex("""^\s*(?:CD|Disc|Disk|Disque|Vol(?:ume)?)\s*\d+\s*[-_. ]+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*\d{1,2}[-_. ]+\d{1,3}(?:[-_. ]+|\s+)"""), "")
            .replace(Regex("""^\s*\d{1,3}(?:[-_. ]+|\s+)"""), "")
            .replace(Regex("""\s*\[(?:flac|mp3|aac|ogg|opus|wav|m4a|320|lossless|remaster(?:ed)?|explicit)\]\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\((?:official\s+audio|official\s+video|lyrics?|audio|hq|hd|remaster(?:ed)?)\)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { base }
    }

    private fun preferredFolderCoverPathForTracks(tracks: List<Track>): String? {
        return tracks.asSequence()
            .mapNotNull { preferredFolderCoverPathForAudioPath(it.path) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun preferredFolderCoverPathForAudioPath(path: String): String? {
        if (path.isBlank() || !canInferFoldersFromPath(path)) return null
        return fr.retrospare.blazeplayer.ui.ThumbnailUtils.preferredFolderCoverPathForAudioPath(path)
    }

    private fun localCoverSearchDirectories(path: String): List<File> {
        val clean = runCatching { Uri.decode(path.substringBefore('?').substringBefore('#')) }
            .getOrDefault(path.substringBefore('?').substringBefore('#'))
        val file = if (clean.startsWith("file://", true)) {
            File(Uri.parse(clean).path.orEmpty())
        } else {
            File(clean)
        }
        val parent = file.parentFile ?: return emptyList()
        val albumDir = if (isDiscFolderName(parent.name) && parent.parentFile != null) parent.parentFile else parent
        return listOfNotNull(parent, albumDir)
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    private fun indexLocalFolderCovers(root: File): Map<String, String> {
        val byDirectory = mutableMapOf<String, MutableList<File>>()
        runCatching {
            root.walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && isCoverImageExtension(it.extension) }
                .forEach { image ->
                    val parent = image.parentFile ?: return@forEach
                    byDirectory.getOrPut(parent.absolutePath) { mutableListOf() }.add(image)
                }
        }
        return byDirectory.mapValues { (_, images) ->
            images.sortedWith(Comparator { a, b -> naturalFileNameCompare(a.name, b.name) })
                .firstOrNull()
                ?.absolutePath
                .orEmpty()
        }.filterValues { it.isNotBlank() }
    }

    private fun preferredFolderCoverPathForFile(file: File, folderCoverIndex: Map<String, String>): String? {
        localCoverSearchDirectories(file.absolutePath).forEach { dir ->
            folderCoverIndex[dir.absolutePath]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return preferredFolderCoverPathForAudioPath(file.absolutePath)
    }

    private fun pickNetworkFolderCoverPath(items: List<fr.retrospare.blazeplayer.data.model.MediaItem>): String {
        return items
            .asSequence()
            .filter { it.mimeType != "folder" && it.mimeType != "share" }
            .filter { isCoverImageExtension(it.extension) || it.mimeType.startsWith("image/", true) }
            .sortedWith(Comparator { a, b -> naturalFileNameCompare(a.name.ifBlank { a.path }, b.name.ifBlank { b.path }) })
            .firstOrNull()
            ?.path
            .orEmpty()
    }

    private fun isCoverImageExtension(extension: String): Boolean {
        return extension.lowercase(Locale.getDefault()) in setOf("jpg", "jpeg", "png")
    }

    private fun naturalFileNameCompare(left: String, right: String): Int {
        val a = Regex("\\d+|\\D+").findAll(left.lowercase(Locale.getDefault())).map { it.value }.toList()
        val b = Regex("\\d+|\\D+").findAll(right.lowercase(Locale.getDefault())).map { it.value }.toList()
        val maxParts = maxOf(a.size, b.size)
        for (i in 0 until maxParts) {
            val av = a.getOrNull(i) ?: return -1
            val bv = b.getOrNull(i) ?: return 1
            val ad = av.all { it.isDigit() }
            val bd = bv.all { it.isDigit() }
            val cmp = if (ad && bd) {
                val an = av.trimStart('0').ifBlank { "0" }
                val bn = bv.trimStart('0').ifBlank { "0" }
                when {
                    an.length != bn.length -> an.length.compareTo(bn.length)
                    an != bn -> an.compareTo(bn)
                    else -> av.length.compareTo(bv.length)
                }
            } else {
                av.compareTo(bv)
            }
            if (cmp != 0) return cmp
        }
        return left.compareTo(right, ignoreCase = true)
    }

    private fun inferTrackNumberFromName(name: String): Int {
        val base = name.substringBeforeLast('.', name).trim()
        val cdTrack = Regex("^\\s*\\d{1,2}[-_. ]+(\\d{1,3})(?:[-_. ]+|\\s+)").find(base)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (cdTrack != null && cdTrack > 0) return cdTrack
        return Regex("^\\s*(\\d{1,3})(?:[-_. ]+|\\s+)").find(base)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun pathSegments(path: String): List<String> = runCatching { Uri.decode(path.substringBefore('?')) }
        .getOrDefault(path.substringBefore('?'))
        .replace('\\', '/')
        .trimEnd('/')
        .split('/')
        .filter { segment ->
            segment.isNotBlank() &&
                !segment.startsWith("smb:", ignoreCase = true) &&
                !segment.startsWith("file:", ignoreCase = true)
        }

    private fun canInferFoldersFromPath(path: String): Boolean {
        val clean = path.substringBefore('?').trim().lowercase(Locale.getDefault())
        return clean.isNotBlank() &&
            !clean.startsWith("content://") &&
            !clean.startsWith("http://") &&
            !clean.startsWith("https://") &&
            !clean.startsWith("upnp://")
    }

    private fun inferAlbumFromPath(path: String, fallback: String): String {
        return albumFolderNameFromPath(path).takeIf { it.isNotBlank() } ?: fallback
    }

    private fun inferArtistFromPath(path: String, fallback: String): String {
        return artistFolderNameFromPath(path).takeIf { it.isNotBlank() } ?: fallback
    }

    private fun inferAlbumFromFile(file: File, root: File, folderName: String): String {
        val parent = file.parentFile
        val albumDir = if (parent != null && isDiscFolderName(parent.name) && parent.parentFile != null) parent.parentFile else parent
        return albumDir
            ?.takeIf { it.absolutePath != root.absolutePath }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: folderName.ifBlank { root.name.ifBlank { getString(R.string.unknown_generic) } }
    }

    private fun inferArtistFromFile(file: File, root: File): String {
        val parent = file.parentFile
        val albumDir = if (parent != null && isDiscFolderName(parent.name) && parent.parentFile != null) parent.parentFile else parent
        val artistDir = albumDir?.parentFile?.takeIf { it.absolutePath != root.absolutePath }
        return artistDir?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_artist)
    }

    private fun albumKey(track: Track): String = albumGroupingKey(track)

    private fun albumKey(album: String, artist: String): String = "${normalizeAlbumForGrouping(album)}|${normalizeArtistForGrouping(artist)}"

    private fun albumGroupingKey(track: Track): String {
        val folder = smartAlbumFolderKey(track.path)
        if (folder.isNotBlank()) return "folder:$folder"

        val album = track.album.ifBlank { inferAlbumFromPath(track.path, getString(R.string.unknown_generic)) }
        val normalizedAlbum = normalizeAlbumForGrouping(album)
        val normalizedArtist = normalizeArtistForGrouping(track.artist)
        return when {
            normalizedAlbum.isNotBlank() && normalizedArtist.isNotBlank() -> "album:$normalizedAlbum|artist:$normalizedArtist"
            normalizedAlbum.isNotBlank() -> "album:$normalizedAlbum"
            track.albumId > 0L && track.source == TrackSource.ANDROID -> "android:${track.albumId}"
            else -> "path:${normalizeForSearch(track.path)}"
        }
    }

    private fun smartAlbumFolderKey(path: String): String {
        if (!canInferFoldersFromPath(path)) return ""
        val segments = pathSegments(path)
        val dirs = segments.dropLast(1)
        if (dirs.isEmpty()) return ""
        val parent = dirs.last()
        val albumDir = if (isDiscFolderName(parent) && dirs.size >= 2) dirs[dirs.size - 2] else parent
        val artistDir = when {
            isDiscFolderName(parent) && dirs.size >= 3 -> dirs[dirs.size - 3]
            !isDiscFolderName(parent) && dirs.size >= 2 -> dirs[dirs.size - 2]
            else -> ""
        }
        return listOf(artistDir, albumDir)
            .filter { it.isNotBlank() }
            .joinToString("/") { normalizePathSegmentForGrouping(it) }
            .trim('/')
    }

    private fun chooseAlbumDisplayTitle(tracks: List<Track>): String {
        val first = tracks.firstOrNull() ?: return getString(R.string.unknown_generic)
        val folderAlbum = albumFolderNameFromPath(first.path)
        val usefulTaggedAlbums = tracks.map { it.album.trim() }
            .filter { it.isNotBlank() && !isWeakMetadataValue(it) }
            .distinctBy { normalizeAlbumForGrouping(it) }
        return when {
            folderAlbum.isNotBlank() && usefulTaggedAlbums.size != 1 -> folderAlbum
            usefulTaggedAlbums.size == 1 -> usefulTaggedAlbums.first()
            first.album.isNotBlank() && !isWeakMetadataValue(first.album) -> first.album
            folderAlbum.isNotBlank() -> folderAlbum
            else -> inferAlbumFromPath(first.path, getString(R.string.unknown_generic))
        }
    }

    private fun albumFolderNameFromPath(path: String): String {
        if (!canInferFoldersFromPath(path)) return ""
        val dirs = pathSegments(path).dropLast(1)
        if (dirs.isEmpty()) return ""
        val parent = dirs.last()
        return when {
            isDiscFolderName(parent) && dirs.size >= 2 -> cleanFolderDisplayName(dirs[dirs.size - 2])
            else -> cleanFolderDisplayName(parent)
        }
    }

    private fun artistFolderNameFromPath(path: String): String {
        if (!canInferFoldersFromPath(path)) return ""
        val dirs = pathSegments(path).dropLast(1)
        if (dirs.isEmpty()) return ""
        val parent = dirs.last()
        return when {
            isDiscFolderName(parent) && dirs.size >= 3 -> cleanFolderDisplayName(dirs[dirs.size - 3])
            !isDiscFolderName(parent) && dirs.size >= 2 -> cleanFolderDisplayName(dirs[dirs.size - 2])
            else -> ""
        }
    }

    private fun cleanFolderDisplayName(value: String): String {
        return Uri.decode(value)
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeForSearch(value: String): String {
        val decoded = runCatching { Uri.decode(value) }.getOrDefault(value)
        val noAccents = Normalizer.normalize(decoded, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return noAccents
            .lowercase(Locale.getDefault())
            .replace('&', ' ')
            .replace(Regex("""[’'`´]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizePathSegmentForGrouping(value: String): String {
        return normalizeForSearch(value)
            .replace(Regex("""\b(?:cd|disc|disk|disque|vol|volume)\s*\d+\b"""), " ")
            .replace(Regex("""\b(?:album|music|musique|audio)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeAlbumForGrouping(value: String): String {
        return normalizeForSearch(value)
            .replace(Regex("""\b(?:cd|disc|disk|disque|vol|volume)\s*\d+\b"""), " ")
            .replace(Regex("""\b(?:deluxe|expanded|remaster(?:ed)?|remastered|anniversary|edition|explicit|clean|bonus|tracks?)\b"""), " ")
            .replace(Regex("""\b(?:20th|25th|30th|40th|50th)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeArtistForGrouping(value: String): String {
        return normalizeForSearch(value)
            .replace(Regex("""\b(?:feat|featuring|ft)\b.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isWeakMetadataValue(value: String): Boolean {
        if (isDiscFolderName(value)) return true
        val key = normalizeForSearch(value)
        if (key.isBlank()) return true
        val localizedUnknowns = listOf(
            getString(R.string.unknown_generic),
            getString(R.string.unknown_artist),
            getString(R.string.unknown_title)
        ).map { normalizeForSearch(it) }
        return key in localizedUnknowns || key in setOf(
            "unknown", "unknown album", "unknown artist", "unknown title",
            "inconnu", "album inconnu", "artiste inconnu", "titre inconnu",
            "desconocido", "sconosciuto", "unbekannt"
        )
    }

    private fun discNumberFromPath(path: String): Int {
        val parent = pathSegments(path).dropLast(1).lastOrNull().orEmpty()
        return Regex("(?:disc|disk|cd)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(parent)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun isDiscFolderName(value: String): Boolean = Regex("^(?:cd|disc|disk|disque|vol(?:ume)?)\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())

    private fun trackBadgeText(track: Track): String {
        val ext = track.path.substringAfterLast('.').uppercase(Locale.getDefault()).take(5).ifBlank { "AUDIO" }
        return listOf(formatDuration(track.durationMs), ext, track.sourceLabel).filter { it.isNotBlank() }.joinToString("  •  ")
    }

    private fun formatDuration(ms: Long): String {
        val total = max(0L, ms / 1000L)
        val min = total / 60
        val sec = total % 60
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    private fun normalizeTrackNumber(raw: Int): Int {
        val low = raw % 1000
        return if (low > 0) low else raw
    }


    private fun setLibrarySectionDividers(showResume: Boolean, showAlbums: Boolean, showTracks: Boolean) {
        setVisible(R.id.dividerLibraryResume, showResume)
        setVisible(R.id.dividerLibraryAlbums, showAlbums)
        setVisible(R.id.dividerLibraryTracks, showTracks)
    }

    private fun setVisible(id: Int, visible: Boolean) {
        findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
