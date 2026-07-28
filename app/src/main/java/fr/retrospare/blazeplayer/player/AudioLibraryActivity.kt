package fr.retrospare.blazeplayer.player

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.playlist.PlaylistCategory
import fr.retrospare.blazeplayer.playlist.PlaylistManager
import fr.retrospare.blazeplayer.playlist.PlaylistTrackRef
import fr.retrospare.blazeplayer.ui.ButtonTextFitter
import fr.retrospare.blazeplayer.ui.DialogButtonStyler
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Bibliothèque audio : écran fin, branché sur [AudioLibraryViewModel] (état + logique métier)
 * et sur le snapshot mémoire de [AudioLibraryRepository]. Cette Activity ne garde que ce qui est
 * spécifique à l'écran : construction des vues (pixel perfect, inchangée), mini player, dialogues,
 * couleur dynamique de fond, et le binding paresseux des covers visibles.
 */
@AndroidEntryPoint
class AudioLibraryActivity : AppCompatActivity() {
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository


    private val viewModel: AudioLibraryViewModel by viewModels()

    // Les covers visibles appartiennent à la bibliothèque, pas au cycle de lecture. Elles utilisent
    // donc un pool dédié de priorité arrière-plan, distinct des pochettes du player/mini-player.
    private val artworkDispatcher: CoroutineDispatcher =
        AudioLibraryBackgroundDispatchers.visibleArtwork

    private lateinit var root: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LibraryAdapter
    private lateinit var tvTrackCount: TextView
    private lateinit var tvAlbumCount: TextView
    private lateinit var tvArtistCount: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvLibraryTitle: TextView
    private lateinit var tvWatchedSummary: TextView
    private lateinit var sortButton: TextView
    private lateinit var manualScanButton: TextView
    private lateinit var albumViewModeContainer: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var homeButton: ImageButton
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniArtwork: fr.retrospare.blazeplayer.ui.RoundedImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniTime: TextView
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniSpinner: ImageView
    private lateinit var albumStickyHeader: LinearLayout
    private lateinit var libraryRootFrame: FrameLayout
    private lateinit var libraryScreenContainer: LinearLayout
    private lateinit var libraryHero: LinearLayout
    private var artistFullBleedHero: FrameLayout? = null
    private var artistHeroTopOverlayContent: View? = null
    private var statusBarInsetTop: Int = 0
    private var navigationBarInsetBottom: Int = 0

    private var knownWatchedFolders: Map<String, AudioProSettings.WatchedFolder> = emptyMap()
    /** Dernier état reçu du ViewModel — sert aux fonctions appelées hors du flux de rendu
     *  (ex. handleTopBack) qui ont besoin de savoir si un détail album est ouvert. */
    private var currentState: LibraryUiState = LibraryUiState()
    private var miniArtworkJob: Job? = null
    private var artistImageJob: Job? = null
    private var artistImageUpdatesJob: Job? = null
    private var artistHeroImageView: ImageView? = null
    private var artistHeroCreditView: TextView? = null
    private var artistHeroBoundName: String = ""
    private val artistBitmapCache = object : LruCache<String, android.graphics.Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private var renderedDetailHeaderKey: String = ""
    private var lastLibraryChromeKey: String = ""
    private var albumHeroMetadataView: TextView? = null
    private var albumStickyMetadataView: TextView? = null
    private var currentArtistImage: ArtistImageRepository.ArtistImage? = null
    private var pendingArtistPhotoSelection: LibraryArtist? = null
    private val artistPhotoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val artist = pendingArtistPhotoSelection
        pendingArtistPhotoSelection = null
        if (uri == null || artist == null) return@registerForActivityResult
        lifecycleScope.launch {
            val updated = ArtistImageRepository.setManualImage(
                this@AudioLibraryActivity,
                artist.name,
                uri
            )
            if (updated == null) {
                Toast.makeText(
                    this@AudioLibraryActivity,
                    R.string.audio_artist_photo_update_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            Toast.makeText(
                this@AudioLibraryActivity,
                R.string.audio_artist_photo_updated,
                Toast.LENGTH_SHORT
            ).show()
            currentArtistImage = updated
            if (::adapter.isInitialized) adapter.notifyArtistImageChanged(artist.name)
            if (currentState.openedArtist?.name == artist.name) {
                renderedDetailHeaderKey = ""
                renderStickyDetailHeader(null, currentState.openedArtist)
            }
        }
    }
    private var watchedFolderAutoRefreshJob: Job? = null
    private var automaticScanLoopJob: Job? = null
    private var librarySettingsReceiverRegistered = false

    private val librarySettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioProSettings.ACTION_LIBRARY_SETTINGS_CHANGED) return
            val changes = AudioProSettings.consumePendingLibrarySettingChanges(this@AudioLibraryActivity)
            handleLibrarySettingChanges(changes.ifEmpty {
                intent.getStringExtra(AudioProSettings.EXTRA_CHANGED_KEY)?.let { setOf(it) } ?: emptySet()
            })
        }
    }

    /** Ancre visuelle de la grille avant l'ouverture d'un album. La restauration est effectuée
     * seulement après que la liste d'albums et le GridLayoutManager sont tous deux prêts. */
    private var albumGridAnchorStableId: Long? = null
    private var albumGridAnchorPosition: Int = 0
    private var albumGridAnchorOffset: Int = 0
    private var restoreAlbumGridAfterCommit: Boolean = false
    private var libraryRenderGeneration: Long = 0L

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    /** Media3 controllers must only be queried from their application thread. Le scan en arrière
     *  plan (Repository) lit ce volatile plutôt que controller.isPlaying directement. */
    @Volatile private var playbackCriticalSnapshot: Boolean = false

    /** Clé canonique du morceau actuellement sélectionné par Media3. */
    private var nowPlayingTrackKey: String = ""
    /** L'égaliseur factice s'anime uniquement lorsque la lecture est réellement active. */
    private var nowPlayingIsPlaying: Boolean = false

    private var miniTicker = Handler(Looper.getMainLooper())
    private var miniSpinAnimator: ObjectAnimator? = null

    private val textMain: Int by lazy { ContextCompat.getColor(this, R.color.on_background) }
    private val textMuted: Int by lazy { ContextCompat.getColor(this, R.color.audio_library_text_muted) }
    // Accent volontairement fixe : le fond peut suivre la pochette en cours, mais les contrôles
    // de la bibliothèque restent dans l'identité visuelle "gris bleuté glacier".
    private val accent: Int by lazy { ContextCompat.getColor(this, R.color.audio_library_accent) }
    private var bg: Int = AudioDynamicColor.DEFAULT_BACKGROUND
    private var currentMiniArtworkPath: String = ""
    /** Vrai lorsque la vue affiche déjà une pochette pour currentMiniArtworkPath. Empêche les
     *  événements Media3 sans changement de piste de remettre le placeholder et de relancer le
     *  même décodage, ce qui produisait un scintillement permanent. */
    private var currentMiniArtworkHasImage: Boolean = false
    private var currentMiniArtistTag: String = ""
    private var currentMiniAlbumTag: String = ""
    private var currentMiniAccentColor: Int = AudioDynamicColor.DEFAULT_ACCENT

    companion object {
        private const val PREFS_UI = "blaze_audio_library_premium_ui"
        private const val KEY_TAB = "tab"
        private const val KEY_ALBUM_VIEW_MODE = "album_view_mode"
        private const val DYNAMIC_AUDIO_PREFS = "blaze_audio_dynamic_colors"
        private const val KEY_DYNAMIC_BG = "dynamic_bg"
        private const val KEY_DYNAMIC_ACCENT = "dynamic_accent"

        private const val VIEW_TYPE_STATUS = 0
        private const val VIEW_TYPE_TRACK = 2
        private const val VIEW_TYPE_ARTIST = 4
        private const val VIEW_TYPE_PLAYLIST = 5
        private const val VIEW_TYPE_ALBUM_TILE = 8
        private const val VIEW_TYPE_ALBUM_TRACK = 9

        /** Trois grandes tuiles par ligne pour les albums et les artistes. */
        private const val LIBRARY_GRID_SPAN_COUNT = 3

        private const val PAYLOAD_ARTIST_IMAGE = "artist_image"
        private const val PAYLOAD_ALBUM_METADATA = "album_metadata"
        private const val PAYLOAD_ARTIST_METADATA = "artist_metadata"
        private const val PAYLOAD_TRACK_METADATA = "track_metadata"
        private const val PAYLOAD_TRACK_ARTWORK = "track_artwork"
        private const val PAYLOAD_NOW_PLAYING = "now_playing"

        /**
         * Point d'entrée historique conservé pour AudioPlayerFragment. Le repository est déjà
         * démarré depuis Application.onCreate() ; cet appel idempotent évite surtout qu'une ancienne
         * implémentation relance une seconde lecture Room si l'utilisateur ouvre Blaze Audio très vite.
         */
        fun warmUpForFastOpen(context: Context) {
            runCatching {
                (context.applicationContext as? fr.retrospare.blazeplayer.BlazePlayerApp)
                    ?.audioLibraryRepository
                    ?.get()
                    ?.start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // La bibliothèque est fournie par le snapshot mémoire partagé. On ignore le
        // savedInstanceState Android pour éviter qu'un ancien état de vue soit restauré après
        // suppression/vidage de cache.
        super.onCreate(null)
        fr.retrospare.blazeplayer.BlazeStartupWarmup.requestAudioPriority(this)
        if (!fr.retrospare.blazeplayer.paywall.AccessGateUi.enforceNow(
                this,
                userRepository,
                fr.retrospare.blazeplayer.paywall.AccessLevel.PRO_PLUS
            )) return
        fr.retrospare.blazeplayer.paywall.AccessGateUi.monitor(
            this,
            userRepository,
            fr.retrospare.blazeplayer.paywall.AccessLevel.PRO_PLUS
        )
        bg = resolveInitialLibraryBackgroundColor()
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
            .putString(KEY_TAB, LibraryTab.ALBUMS.name)
            .putInt(KEY_ALBUM_VIEW_MODE, 1)
            .apply()
        knownWatchedFolders = currentWatchedFolderMap()
        buildUi()
        adapter = LibraryAdapter()
        recyclerView.adapter = adapter
        setupActions()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentState.openedAlbum != null || currentState.openedArtist != null) {
                    handleTopBack()
                } else {
                    finish()
                }
            }
        })
        observeArtistImageUpdates()
        updateWatchedSummary()
        val settingsFilter = IntentFilter(AudioProSettings.ACTION_LIBRARY_SETTINGS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(librarySettingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(librarySettingsReceiver, settingsFilter)
        }
        librarySettingsReceiverRegistered = true
        // First frame first : la connexion Media3 et l'observation du ViewModel démarrent après
        // que la coquille visuelle soit déjà à l'écran, pour éviter un ANR si le player/NAS est occupé.
        root.post {
            if (!isFinishing && !isDestroyed) {
                setupMiniPlayer()
                observeViewModel()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPlaybackCriticalSnapshot(controller)
        handleLibrarySettingChanges(AudioProSettings.consumePendingLibrarySettingChanges(this))
        synchronizeWatchedFoldersAndRefreshIfNeeded()
        startAutomaticScanLoop()
    }

    override fun onPause() {
        automaticScanLoopJob?.cancel()
        automaticScanLoopJob = null
        stopMiniSpin(keepVisible = false)
        super.onPause()
    }

    override fun onDestroy() {
        miniArtworkJob?.cancel()
        artistImageJob?.cancel()
        artistImageUpdatesJob?.cancel()
        clearFullBleedArtistHero()
        watchedFolderAutoRefreshJob?.cancel()
        automaticScanLoopJob?.cancel()
        if (librarySettingsReceiverRegistered) {
            runCatching { unregisterReceiver(librarySettingsReceiver) }
            librarySettingsReceiverRegistered = false
        }
        miniTicker.removeCallbacksAndMessages(null)
        stopMiniSpin(keepVisible = false)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    private fun observeArtistImageUpdates() {
        artistImageUpdatesJob?.cancel()
        artistImageUpdatesJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ArtistImageRepository.imageUpdates.collect { artistName ->
                    if (::adapter.isInitialized) adapter.notifyArtistImageChanged(artistName)
                    val opened = currentState.openedArtist
                    if (opened != null && currentState.openedAlbum == null &&
                        AudioLibraryHeuristics.normalize(opened.name) ==
                        AudioLibraryHeuristics.normalize(artistName)
                    ) {
                        loadArtistHeroImage(opened)
                    }
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    // -----------------------------------------------------------------
    // Rendu de l'état ViewModel -> vues.
    // -----------------------------------------------------------------

    private fun render(state: LibraryUiState) {
        if (isFinishing || isDestroyed) return
        val renderGeneration = ++libraryRenderGeneration
        currentState = state
        manualScanButton.isEnabled = !state.isRefreshing
        manualScanButton.alpha = if (state.isRefreshing) 0.55f else 1f
        if (state.isInitialLoad) {
            tvTrackCount.text = "—"
            tvAlbumCount.text = "—"
            tvArtistCount.text = "—"
        } else {
            tvTrackCount.text = resources.getQuantityString(R.plurals.audio_track_count_compact, state.trackCount, state.trackCount)
            tvAlbumCount.text = resources.getQuantityString(R.plurals.audio_album_count_compact, state.albumCount, state.albumCount)
            tvArtistCount.text = resources.getQuantityString(R.plurals.audio_artist_count_compact, state.artistCount, state.artistCount)
        }
        val chromeKey = buildString {
            append(state.tab.name)
            append('|').append(state.openedAlbum?.key.orEmpty())
            append('|').append(state.openedAlbum?.artworkPath.orEmpty())
            append('|').append(state.openedAlbum?.title.orEmpty())
            append('|').append(state.openedAlbum?.artist.orEmpty())
            append('|').append(state.openedArtist?.name.orEmpty())
            append('|').append(state.artistDetailTab.name)
            append('|').append(state.isInitialLoad)
        }
        val chromeChanged = chromeKey != lastLibraryChromeKey
        if (chromeChanged) {
            lastLibraryChromeKey = chromeKey
            updateSectionVisibility(state)
        } else {
            updateVisibleAlbumMetadata(state.openedAlbum)
        }
        // Ne jamais remplacer la grille par une ligne « Chargement ». Le snapshot binaire ou la
        // projection Room légère arrive en arrière-plan ; conserver une surface stable évite le
        // flash de dix secondes observé sur les grosses bibliothèques.
        val rows = if (state.isInitialLoad) emptyList() else state.rows

        // ListAdapter applique son diff de manière asynchrone. Le LayoutManager doit être changé
        // uniquement une fois la nouvelle liste réellement active : sinon le GridLayoutManager
        // peut mesurer les anciennes lignes du détail avec les règles de span de la grille albums,
        // puis conserver cette géométrie incohérente jusqu'au premier scroll.
        adapter.submitList(rows) {
            if (renderGeneration != libraryRenderGeneration || isFinishing || isDestroyed) return@submitList
            if (chromeChanged) configureListLayoutForCurrentView(state)
            restoreAlbumGridAnchorIfNeeded(state, renderGeneration)
        }
    }

    private fun updateSectionVisibility(state: LibraryUiState) {
        val detailAlbum = state.openedAlbum
        val detailArtist = state.openedArtist
        val isHome = detailAlbum == null && detailArtist == null
        homeButton.visibility = if (isHome) View.GONE else View.VISIBLE
        renderStickyDetailHeader(detailAlbum, detailArtist)
        renderLibraryTabs(state)

        when {
            detailArtist != null && detailAlbum == null -> {
                tvLibraryTitle.text = getString(R.string.audio_artist_page_title)
                tvSubtitle.text = artistSummaryText(detailArtist)
                tvSubtitle.visibility = View.VISIBLE
            }
            detailAlbum != null -> {
                tvLibraryTitle.text = getString(R.string.audio_album_page_title)
                tvSubtitle.text = detailAlbum.artist
                tvSubtitle.visibility = if (detailAlbum.artist.isBlank()) View.GONE else View.VISIBLE
            }
            else -> {
                tvLibraryTitle.text = getString(R.string.audio_library_title)
                tvSubtitle.text = getString(R.string.audio_library_subtitle)
                tvSubtitle.visibility = View.VISIBLE
            }
        }
        applyLibraryWindowInsets()

        // Dossiers surveillés et statistiques : uniquement sur les vues principales Albums/Artistes.
        val homeOnlyVisibility = if (isHome) View.VISIBLE else View.GONE
        listOf(R.id.watchedSummaryCard, R.id.libraryStats).forEach { id ->
            findViewById<View>(id)?.visibility = homeOnlyVisibility
        }
        findViewById<View>(R.id.dividerLibraryStats)?.visibility = View.GONE

        listOf(R.id.resumeSectionHeader, R.id.resumeScroll, R.id.dividerLibraryResume).forEach { id ->
            findViewById<View>(id)?.visibility = View.GONE
        }

        findViewById<View>(R.id.dividerLibraryAlbums)?.visibility = View.GONE
        findViewById<View>(R.id.albumsSectionHeader)?.visibility = if (isHome) View.VISIBLE else View.GONE
        findViewById<View>(R.id.albumsScroll)?.visibility = View.GONE
        findViewById<TextView>(R.id.tvAlbumsTitle)?.text = when (state.tab) {
            LibraryTab.ARTISTS -> getString(R.string.audio_my_artists)
            else -> getString(R.string.audio_recent_albums)
        }
        albumViewModeContainer.visibility = View.GONE

        findViewById<View>(R.id.dividerLibraryTracks)?.visibility = View.GONE
        findViewById<View>(R.id.tracksContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.tracksSectionHeader)?.visibility = View.GONE
        findViewById<TextView>(R.id.tvTracksTitle)?.visibility = View.GONE
        sortButton.visibility = View.GONE
    }

    private fun renderLibraryTabs(state: LibraryUiState) {
        if (state.openedAlbum != null) {
            tabContainer.visibility = View.GONE
            return
        }
        tabContainer.removeAllViews()
        tabContainer.visibility = View.VISIBLE

        if (state.openedArtist != null) {
            addLibraryTab(
                label = getString(R.string.audio_tab_albums),
                selected = state.artistDetailTab == ArtistDetailTab.ALBUMS
            ) { viewModel.setArtistDetailTab(ArtistDetailTab.ALBUMS) }
            addLibraryTab(
                label = getString(R.string.audio_tab_titles),
                selected = state.artistDetailTab == ArtistDetailTab.TITLES
            ) { viewModel.setArtistDetailTab(ArtistDetailTab.TITLES) }
        } else {
            addLibraryTab(
                label = getString(R.string.audio_tab_albums),
                selected = state.tab == LibraryTab.ALBUMS
            ) {
                viewModel.setSearchQuery("")
                viewModel.setTab(LibraryTab.ALBUMS)
            }
            addLibraryTab(
                label = getString(R.string.audio_tab_artists),
                selected = state.tab == LibraryTab.ARTISTS
            ) {
                viewModel.setSearchQuery("")
                viewModel.setTab(LibraryTab.ARTISTS)
            }
        }
    }

    private fun addLibraryTab(label: String, selected: Boolean, onClick: () -> Unit) {
        tabContainer.addView(TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = 13f
            setTextColor(if (selected) textMain else textMuted)
            background = ContextCompat.getDrawable(
                this@AudioLibraryActivity,
                if (selected) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun configureListLayoutForCurrentView(state: LibraryUiState) {
        val isAlbumDetail = state.openedAlbum != null
        val isArtistAlbumGrid = state.openedArtist != null &&
            state.artistDetailTab == ArtistDetailTab.ALBUMS && !isAlbumDetail
        val isLibraryAlbumGrid = state.openedArtist == null &&
            state.tab == LibraryTab.ALBUMS && !isAlbumDetail
        val isLibraryArtistGrid = state.openedArtist == null &&
            state.tab == LibraryTab.ARTISTS && !isAlbumDetail
        val shouldUseGrid = isArtistAlbumGrid || isLibraryAlbumGrid || isLibraryArtistGrid
        val spanCount = LIBRARY_GRID_SPAN_COUNT
        val current = recyclerView.layoutManager
        if (shouldUseGrid) {
            val currentGrid = current as? GridLayoutManager
            if (currentGrid == null || currentGrid.spanCount != spanCount) {
                recyclerView.layoutManager = GridLayoutManager(
                    this,
                    spanCount,
                    RecyclerView.VERTICAL,
                    false
                ).apply {
                    // Ne pas restaurer une géométrie de l'ancienne grille 4 colonnes.
                    recycleChildrenOnDetach = true
                    initialPrefetchItemCount = spanCount * 4
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int = when (adapter.getItemViewType(position)) {
                            VIEW_TYPE_ALBUM_TILE, VIEW_TYPE_ARTIST -> 1
                            else -> spanCount
                        }
                    }
                }
            }
        } else if (current is GridLayoutManager) {
            recyclerView.layoutManager = LinearLayoutManager(this)
        }
        recyclerView.clipToPadding = false
        recyclerView.setPadding(
            0,
            if (isAlbumDetail) dp(2) else if (shouldUseGrid) dp(8) else 0,
            0,
            if (shouldUseGrid) dp(28) else dp(24)
        )
        recyclerView.invalidateItemDecorations()
        recyclerView.requestLayout()
    }

    // -----------------------------------------------------------------
    // Construction de la coquille UI (inchangée, pixel perfect).
    // -----------------------------------------------------------------

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(R.layout.activity_blaze_audio_library)

        libraryRootFrame = findViewById(R.id.libraryRoot)
        libraryScreenContainer = findViewById(R.id.libraryScreenContainer)
        libraryHero = findViewById(R.id.libraryHero)
        libraryRootFrame.clipChildren = false
        libraryRootFrame.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(libraryRootFrame) { _, insets ->
            statusBarInsetTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            navigationBarInsetBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            applyLibraryWindowInsets()
            insets
        }
        ViewCompat.requestApplyInsets(libraryRootFrame)
        applyFullscreenLibraryBackground(bg)

        root = findViewById(R.id.libraryGlobalCard)
        tvTrackCount = findViewById(R.id.tvTrackCount)
        tvAlbumCount = findViewById(R.id.tvAlbumCount)
        tvArtistCount = findViewById(R.id.tvArtistCount)
        tvSubtitle = findViewById(R.id.tvLibrarySubtitle)
        tvSubtitle.text = getString(R.string.audio_library_subtitle)
        tvLibraryTitle = findViewById(R.id.tvLibraryTitle)
        tvWatchedSummary = findViewById(R.id.tvWatchedSummary)
        sortButton = findViewById(R.id.tvSortMode)
        manualScanButton = findViewById(R.id.btnManualScan)
        albumViewModeContainer = findViewById(R.id.albumViewModeContainer)
        albumViewModeContainer.visibility = View.GONE
        tabContainer = findViewById(R.id.tabContainer)
        homeButton = findViewById(R.id.btnLibraryHome)
        miniPlayer = findViewById(R.id.libraryMiniPlayer)
        miniArtwork = findViewById(R.id.ivLibraryMiniArtwork)
        miniTitle = findViewById(R.id.tvLibraryMiniTitle)
        miniArtist = findViewById(R.id.tvLibraryMiniArtist)
        miniTime = findViewById(R.id.tvLibraryMiniTime)
        miniPlayPause = findViewById(R.id.btnLibraryMiniPlayPause)
        miniSpinner = findViewById(R.id.libraryMiniPlayingIndicator)
        albumStickyHeader = findViewById(R.id.albumStickyHeader)
        miniPlayer.visibility = View.GONE
        applyAccentToStaticChrome()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { handleTopBack() }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener { showSearchDialog() }
        homeButton.setOnClickListener { openFullAudioPlayer() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, AudioProSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnManageWatched).setOnClickListener { openWatchedFoldersBrowser() }
        // Le mini-player a été retiré de la bibliothèque. Le contrôleur Media3 reste connecté
        // uniquement pour savoir si une lecture est critique pendant un scan en arrière-plan.

        // La barre est alimentée au rendu : Albums/Artistes sur la bibliothèque, puis
        // Albums/Titres à l’intérieur de la page artiste.
        tabContainer.removeAllViews()
        tabContainer.visibility = View.GONE
        installStickyHero()

        val tracksContainer = findViewById<LinearLayout>(R.id.tracksContainer)
        tracksContainer.removeAllViews()
        recyclerView = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@AudioLibraryActivity)
            itemAnimator = null
            setHasFixedSize(false)
            isNestedScrollingEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
            overScrollMode = View.OVER_SCROLL_NEVER
            // Conserve plusieurs rangées de tuiles déjà liées pendant un fling. Les pochettes
            // visibles ne sont ainsi pas immédiatement recyclées/remplacées par le placeholder.
            setItemViewCacheSize(64)
            recycledViewPool.setMaxRecycledViews(VIEW_TYPE_ALBUM_TILE, 80)
            recycledViewPool.setMaxRecycledViews(VIEW_TYPE_ARTIST, 48)
        }
        // tracksContainer est le seul élément scrollable de l'écran (layout_height=0dp,
        // layout_weight=1 dans le XML) : le RecyclerView remplit tout l'espace disponible, sans
        // ScrollView englobante ni double-scroll imbriqué.
        tracksContainer.addView(recyclerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun setupActions() {
        manualScanButton.text = getString(R.string.action_refresh)
        manualScanButton.visibility = View.VISIBLE
        manualScanButton.setOnClickListener { manualRefresh() }
    }

    private fun setupMiniPlayer() {
        val token = SessionToken(this, ComponentName(this, BlazePlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching {
                controller = future.get()
                refreshPlaybackCriticalSnapshot(controller)
                refreshLibraryNowPlaying(controller)
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playbackCriticalSnapshot = isPlaying
                        refreshLibraryNowPlaying(controller)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        refreshPlaybackCriticalSnapshot(controller)
                        refreshLibraryNowPlaying(controller)
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        refreshPlaybackCriticalSnapshot(controller)
                        refreshLibraryNowPlaying(controller)
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        refreshPlaybackCriticalSnapshot(controller)
                        refreshLibraryNowPlaying(controller)
                    }
                })
                miniPlayer.visibility = View.GONE
            }.onFailure {
                playbackCriticalSnapshot = false
                miniPlayer.visibility = View.GONE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun manualRefresh() {
        val started = viewModel.refresh(manual = true, isPlaybackCritical = ::isPlaybackCritical)
        if (!started) {
            // Un scan est déjà actif : conserver une demande conflated pour refaire une passe avec
            // les dossiers cochés après la fin du scan courant.
            (applicationContext as? fr.retrospare.blazeplayer.BlazePlayerApp)
                ?.audioLibraryRepository
                ?.get()
                ?.apply {
                    setInteractiveLoading(true)
                    requestWatchedFoldersRefresh()
                }
            return
        }
        AudioProSettings.markAutomaticScanStarted(this)
        Toast.makeText(this, getString(R.string.audio_library_refresh_started), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            viewModel.refreshState.first { refreshing -> !refreshing }
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this@AudioLibraryActivity, getString(R.string.audio_library_refresh_done), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPlaybackCriticalSnapshot(player: Player?) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        playbackCriticalSnapshot = runCatching { player?.isPlaying == true }.getOrDefault(playbackCriticalSnapshot)

        // La bibliothèque audio suit elle aussi la cover réellement associée au titre courant.
        // Le chemin explicite couvre cover.jpg/png et albumArtURI ; artworkData est le repli direct
        // pour une pochette embarquée déjà fournie par Media3.
        val item = player?.currentMediaItem
        val path = mediaPathOf(item)
        if (path.isNotBlank() && ::miniArtwork.isInitialized) {
            bindMiniArtwork(
                path = path,
                preferredArtworkPath = item?.mediaMetadata?.extras
                    ?.getString(AudioRepository.EXTRA_ARTWORK_PATH)
                    .orEmpty(),
                fallbackArtworkData = item?.mediaMetadata?.artworkData
            )
        }
    }

    private fun isPlaybackCritical(): Boolean = playbackCriticalSnapshot

    private fun mediaPathOf(item: MediaItem?): String {
        if (item == null) return ""
        return originalPathOf(item)
            ?.takeIf { it.isNotBlank() }
            ?: item.mediaId.takeIf { it.isNotBlank() }
            ?: item.localConfiguration?.uri?.toString().orEmpty()
    }

    private fun trackPlaybackKey(path: String): String =
        AudioLibraryHeuristics.canonicalPathKey(path)

    private fun isTrackCurrent(track: LibraryTrack): Boolean =
        nowPlayingTrackKey.isNotBlank() &&
            trackPlaybackKey(track.path) == nowPlayingTrackKey

    private fun isTrackCurrentlyPlaying(track: LibraryTrack): Boolean =
        nowPlayingIsPlaying && isTrackCurrent(track)

    /**
     * Le bouton placé dans une ligne contrôle le morceau courant sans reconstruire la file.
     * Pour une autre piste, il conserve le comportement habituel et démarre ce titre.
     */
    private fun toggleTrackPlayback(track: LibraryTrack) {
        val mediaController = controller
        if (mediaController != null && isTrackCurrent(track)) {
            val shouldPlay = !mediaController.isPlaying
            if (shouldPlay) {
                if (mediaController.playbackState == Player.STATE_ENDED) {
                    mediaController.seekToDefaultPosition()
                }
                mediaController.play()
            } else {
                mediaController.pause()
            }
            val previousKey = nowPlayingTrackKey
            nowPlayingIsPlaying = shouldPlay
            adapter.notifyNowPlayingChanged(previousKey, nowPlayingTrackKey)
            return
        }
        playFrom(track)
    }

    /**
     * Met à jour uniquement l'ancienne et la nouvelle ligne concernées. La liste entière n'est
     * jamais reconstruite lors d'un play/pause ou d'un passage au morceau suivant.
     */
    private fun refreshLibraryNowPlaying(player: Player?) {
        if (Looper.myLooper() != Looper.getMainLooper()) return

        val previousKey = nowPlayingTrackKey
        val previousPlaying = nowPlayingIsPlaying

        val currentPath = mediaPathOf(player?.currentMediaItem)
        nowPlayingTrackKey = currentPath
            .takeIf { it.isNotBlank() }
            ?.let(::trackPlaybackKey)
            .orEmpty()
        nowPlayingIsPlaying = runCatching {
            player?.isPlaying == true && player.playbackState != Player.STATE_IDLE
        }.getOrDefault(false)

        if (
            previousKey != nowPlayingTrackKey ||
            previousPlaying != nowPlayingIsPlaying
        ) {
            if (::adapter.isInitialized) {
                adapter.notifyNowPlayingChanged(previousKey, nowPlayingTrackKey)
            }
        }
    }

    /**
     * Rend l'indicateur visible sans attendre le retour asynchrone du service après le clic.
     * Le contrôleur Media3 reprend ensuite la main et corrige l'état si nécessaire.
     */
    private fun markTrackAsStarting(track: LibraryTrack) {
        val previousKey = nowPlayingTrackKey
        nowPlayingTrackKey = trackPlaybackKey(track.path)
        nowPlayingIsPlaying = true
        if (::adapter.isInitialized) {
            adapter.notifyNowPlayingChanged(previousKey, nowPlayingTrackKey)
        }
    }

    // -----------------------------------------------------------------
    // Navigation : tabs, recherche, détail album.
    // -----------------------------------------------------------------

    private fun handleTopBack() {
        when {
            currentState.openedAlbum != null -> closeAlbumAndRestorePreviousPage()
            currentState.openedArtist != null -> {
                renderedDetailHeaderKey = ""
                artistImageJob?.cancel()
                viewModel.closeArtistDetail()
            }
            else -> finish()
        }
    }

    private fun returnToLibraryHome() {
        restoreAlbumGridAfterCommit = false
        albumGridAnchorStableId = null
        artistImageJob?.cancel()
        currentArtistImage = null
        viewModel.returnToLibraryHome()
    }

    private fun closeAlbumAndRestorePreviousPage() {
        restoreAlbumGridAfterCommit = albumGridAnchorStableId != null
        renderedDetailHeaderKey = ""
        viewModel.closeAlbumDetail()
    }

    private fun saveAlbumGridAnchor() {
        val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val row = adapter.currentList.getOrNull(position)
        val firstView = layoutManager.findViewByPosition(position)
        albumGridAnchorStableId = row?.stableId
        albumGridAnchorPosition = position
        albumGridAnchorOffset = (firstView?.top ?: recyclerView.paddingTop) - recyclerView.paddingTop
    }

    private fun restoreAlbumGridAnchorIfNeeded(state: LibraryUiState, renderGeneration: Long) {
        val isAlbumGrid = state.openedAlbum == null && (
            (state.openedArtist != null && state.artistDetailTab == ArtistDetailTab.ALBUMS) ||
                (state.openedArtist == null && state.tab == LibraryTab.ALBUMS)
            )
        if (!isAlbumGrid || !restoreAlbumGridAfterCommit) {
            recyclerView.requestLayout()
            return
        }
        val stableId = albumGridAnchorStableId
        val fallbackPosition = albumGridAnchorPosition
        val offset = albumGridAnchorOffset
        restoreAlbumGridAfterCommit = false
        albumGridAnchorStableId = null

        recyclerView.postOnAnimation {
            if (renderGeneration != libraryRenderGeneration || isFinishing || isDestroyed) return@postOnAnimation
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return@postOnAnimation
            val positionFromId = stableId?.let { id -> adapter.currentList.indexOfFirst { it.stableId == id } } ?: -1
            val targetPosition = (if (positionFromId >= 0) positionFromId else fallbackPosition)
                .coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
            if (adapter.itemCount > 0) layoutManager.scrollToPositionWithOffset(targetPosition, offset)
            recyclerView.invalidateItemDecorations()
            recyclerView.requestLayout()
        }
    }

    private fun openAlbumDetailView(album: LibraryAlbum) {
        if (album.tracks.none { it.path.isNotBlank() }) {
            Toast.makeText(this, getString(R.string.audio_no_music), Toast.LENGTH_SHORT).show()
            return
        }
        // Conserver une ancre stable avant de remplacer la grille par la liste des titres.
        saveAlbumGridAnchor()
        restoreAlbumGridAfterCommit = false
        viewModel.openAlbumDetail(album)
    }

    private fun openArtist(artist: LibraryArtist) {
        renderedDetailHeaderKey = ""
        viewModel.openArtistDetail(artist)
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            setText(currentState.searchQuery)
            hint = getString(R.string.audio_search)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setTextColor(textMain)
            setHintTextColor(textMuted)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_search))
            .setView(input)
            .setPositiveButton(getString(R.string.audio_search)) { _, _ ->
                viewModel.setSearchQuery(input.text?.toString().orEmpty())
            }
            .setNeutralButton(getString(R.string.audio_tag_reset)) { _, _ ->
                viewModel.setSearchQuery("")
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun openWatchedFoldersBrowser() {
        startActivity(Intent(this, AudioBrowserActivity::class.java).apply {
            putExtra(AudioBrowserActivity.EXTRA_WATCHED_FOLDERS_MODE, true)
        })
    }

    private fun updateWatchedSummary() {
        manualScanButton.text = getString(R.string.action_refresh)
        manualScanButton.visibility = View.VISIBLE
        val folders = AudioProSettings.watchedFolders(this)
        val local = folders.count { !it.isNetwork }
        val network = folders.count { it.isNetwork }
        val networkLabel = getString(R.string.tab_network)
        tvWatchedSummary.text = when {
            folders.isEmpty() -> getString(R.string.audio_watched_folders_empty_short)
            network > 0 && local > 0 -> getString(R.string.audio_watched_folders_summary_mixed, local, network, networkLabel)
            network > 0 -> getString(R.string.audio_watched_folders_summary_network, network, networkLabel)
            else -> getString(R.string.audio_watched_folders_summary_local, local)
        }
    }

    private fun currentWatchedFolderMap(): Map<String, AudioProSettings.WatchedFolder> = AudioProSettings
        .watchedFolders(this)
        .associateBy { AudioWatchedLibraryCache.key(it) }

    private fun synchronizeWatchedFoldersAndRefreshIfNeeded() {
        val current = currentWatchedFolderMap()
        val removed = knownWatchedFolders.filterKeys { it !in current }.values.toList()
        val added = current.filterKeys { it !in knownWatchedFolders }.values.toList()
        // L'ajout d'un dossier déclenche toujours son indexage initial. Le réglage
        // « Scanner automatiquement » ne doit désactiver que les rescans périodiques, sinon une
        // bibliothèque nouvellement ajoutée reste vide jusqu'à un clic manuel sur Actualiser.
        val pendingRefresh = AudioProSettings.isLibraryRefreshPending(this)
        knownWatchedFolders = current

        if (removed.isNotEmpty()) {
            viewModel.onFolderRemoved(removed.map { AudioWatchedLibraryCache.key(it) }.toSet())
            Toast.makeText(this, getString(R.string.audio_watched_folder_removed), Toast.LENGTH_SHORT).show()
        }
        if (removed.isNotEmpty() || added.isNotEmpty() || pendingRefresh) updateWatchedSummary()
        // L'ajout marque toujours le refresh comme pending avant d'émettre la notification.
        // Si le repository a déjà terminé le scan pendant que cet écran était en pause, le drapeau
        // est déjà effacé et on évite ainsi de relancer inutilement toute la bibliothèque au retour.
        if (pendingRefresh && current.isNotEmpty()) {
            (applicationContext as? fr.retrospare.blazeplayer.BlazePlayerApp)
                ?.audioLibraryRepository
                ?.get()
                ?.requestWatchedFoldersRefresh()
        }
    }

    private fun handleLibrarySettingChanges(changedKeys: Set<String>) {
        viewModel.reloadLibrarySettings()
        if (AudioProSettings.KEY_AUTO_SCAN in changedKeys) {
            if (AudioProSettings.read(this).autoScan) {
                startAutomaticScanLoop()
                requestAutomaticLibraryRefresh(force = true)
            } else {
                automaticScanLoopJob?.cancel()
                automaticScanLoopJob = null
            }
        }
    }

    private fun startAutomaticScanLoop() {
        automaticScanLoopJob?.cancel()
        if (!AudioProSettings.read(this).autoScan) return
        automaticScanLoopJob = lifecycleScope.launch {
            requestAutomaticLibraryRefresh(force = false)
            while (isActive) {
                delay(AudioProSettings.AUTO_SCAN_INTERVAL_MS)
                requestAutomaticLibraryRefresh(force = false)
            }
        }
    }

    private fun requestAutomaticLibraryRefresh(force: Boolean) {
        if (!AudioProSettings.isAutomaticScanDue(this, force)) return
        if (watchedFolderAutoRefreshJob?.isActive == true) return
        watchedFolderAutoRefreshJob = lifecycleScope.launch {
            var started = false
            while (!started && isActive) {
                started = viewModel.refresh(manual = false, isPlaybackCritical = ::isPlaybackCritical)
                if (!started) delay(250L)
            }
            if (started) AudioProSettings.markAutomaticScanStarted(this@AudioLibraryActivity)
        }
    }

    // -----------------------------------------------------------------
    // Playback / file d'attente / playlists / Blaze Party.
    // -----------------------------------------------------------------

    private fun playFrom(track: LibraryTrack) {
        markTrackAsStarting(track)
        appendSingleTrackToAudioQueueAndPlay(track)
        // Lecture immédiate sans navigation forcée : l'utilisateur reste dans la bibliothèque.
    }

    private fun playAlbumNow(tracks: List<LibraryTrack>) {
        val ordered = tracks.distinctBy { it.path }
        if (ordered.isEmpty()) return
        val items = ordered.map { PlaylistItem(it.path, it.title.ifBlank { AudioLibraryHeuristics.fileNameFromPath(it.path) }, it.artworkPath) }
        AudioRepository.save(this, items, 0, 0L, Player.REPEAT_MODE_OFF, false)
        runCatching {
            startService(Intent(this, BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_PLAY_AUDIO_QUEUE
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_PATHS, ArrayList(items.map { it.path }))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_NAMES, ArrayList(items.map { it.name }))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_ARTWORK_PATHS, ArrayList(items.map { it.artworkPath }))
                putExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_INDEX, 0)
            })
        }
        Toast.makeText(this, getString(R.string.audio_play_album), Toast.LENGTH_SHORT).show()
    }

    private fun showTrackOverflowActions(track: LibraryTrack) {
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

    private fun showTrackPlaylistChoiceDialog(track: LibraryTrack) {
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
            this,
            PlaylistCategory.AUDIO,
            listOf(track.toPlaylistRef())
        ) {
            if (viewModel.uiState.value.tab == LibraryTab.PLAYLISTS) viewModel.setTab(LibraryTab.PLAYLISTS)
        }
    }

    private fun showAlbumCoverActions(album: LibraryAlbum, tracks: List<LibraryTrack>) {
        val ordered = tracks.distinctBy { it.path }
        if (ordered.isEmpty()) {
            Toast.makeText(this, R.string.audio_no_music, Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            getString(R.string.audio_play_album),
            getString(R.string.audio_add_album_queue)
        )
        AlertDialog.Builder(this)
            .setTitle(album.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playAlbumNow(ordered)
                    1 -> appendTracksToAudioQueue(ordered)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun showPlaylistActions(playlist: LibraryPlaylist) {
        if (playlist.tracks.isEmpty()) {
            Toast.makeText(this, R.string.playlist_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(getString(R.string.audio_add_playlist_queue), getString(R.string.audio_add_playlist_blaze_party))
        AlertDialog.Builder(this)
            .setTitle(playlist.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> appendTracksToAudioQueue(playlist.tracks)
                    else -> addTracksToBlazeParty(playlist.tracks)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun appendSingleTrackToAudioQueue(track: LibraryTrack) = appendTracksToAudioQueue(listOf(track))

    private fun appendSingleTrackToAudioQueueAndPlay(track: LibraryTrack) {
        if (track.path.isBlank()) return
        val item = PlaylistItem(track.path, track.title.ifBlank { AudioLibraryHeuristics.fileNameFromPath(track.path) }, track.artworkPath)
        val state = AudioRepository.loadState(this)
        val existingIndex = state.items.indexOfFirst { it.path == item.path }
        val newItems = if (existingIndex >= 0) {
            state.items.toMutableList().also { items ->
                val previous = items[existingIndex]
                if (item.artworkPath.isNotBlank() && previous.artworkPath != item.artworkPath) {
                    items[existingIndex] = previous.copy(artworkPath = item.artworkPath)
                }
            }
        } else state.items + item
        val targetIndex = if (existingIndex >= 0) existingIndex else newItems.lastIndex.coerceAtLeast(0)
        if (newItems.isNotEmpty()) {
            AudioRepository.save(this, newItems, targetIndex, 0L, state.repeatMode, state.shuffle)
        }
        runCatching {
            startService(Intent(this, BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_APPEND_AUDIO_QUEUE_AND_PLAY
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_PATHS, arrayListOf(item.path))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_NAMES, arrayListOf(item.name))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_ARTWORK_PATHS, arrayListOf(item.artworkPath))
                putExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_INDEX, 0)
            })
        }
    }

    private fun appendTracksToAudioQueue(tracks: List<LibraryTrack>) {
        val unique = tracks.distinctBy { it.path }
        if (unique.isEmpty()) return
        val state = AudioRepository.loadState(this)
        val existing = state.items.map { it.path }.toHashSet()
        val additions = unique.filter { existing.add(it.path) }.map { PlaylistItem(it.path, it.title.ifBlank { AudioLibraryHeuristics.fileNameFromPath(it.path) }, it.artworkPath) }
        if (additions.isEmpty()) {
            Toast.makeText(this, resources.getQuantityString(R.plurals.playlist_items_already_present, unique.size, unique.size), Toast.LENGTH_SHORT).show()
            return
        }
        val newItems = state.items + additions
        AudioRepository.save(this, newItems, if (state.items.isEmpty()) 0 else state.index, state.positionMs, state.repeatMode, state.shuffle)
        requestAudioQueueAppend(additions)
        Toast.makeText(this, resources.getQuantityString(R.plurals.playlist_items_added, additions.size, additions.size), Toast.LENGTH_SHORT).show()
    }

    private fun requestAudioQueueAppend(items: List<PlaylistItem>) {
        if (items.isEmpty()) return
        runCatching {
            startService(Intent(this, BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_APPEND_AUDIO_QUEUE
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_PATHS, ArrayList(items.map { it.path }))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_NAMES, ArrayList(items.map { it.name }))
                putStringArrayListExtra(BlazePlayerService.EXTRA_AUDIO_QUEUE_ARTWORK_PATHS, ArrayList(items.map { it.artworkPath }))
            })
        }
    }

    private fun addTracksToBlazeParty(tracks: List<LibraryTrack>) {
        val refs = tracks.distinctBy { it.path }.map { it.toPlaylistRef() }
        val added = PlaylistManager.addToBlazePartyPlaylist(this, refs)
        Toast.makeText(this, resources.getQuantityString(R.plurals.blaze_party_items_added, added, added), Toast.LENGTH_SHORT).show()
    }

    private fun LibraryTrack.toPlaylistRef(): PlaylistTrackRef {
        val cached = AudioMediaCache.getCachedMetadata(this@AudioLibraryActivity, path)
        val fallbackExt = AudioLibraryHeuristics.containerLabel(this).ifBlank { path.substringBefore('?').substringAfterLast('.', "").uppercase(Locale.getDefault()) }
        return PlaylistTrackRef(
            path = path,
            name = AudioLibraryHeuristics.fileNameFromPath(path).ifBlank { title },
            artist = artist,
            title = title,
            album = album,
            trackNumber = trackNo,
            extension = cached?.extension?.ifBlank { fallbackExt } ?: fallbackExt,
            bitrate = cached?.bitrate ?: 0L,
            isLossless = cached?.isLossless ?: false,
            durationMs = durationMs
        )
    }

    // -----------------------------------------------------------------
    // Mini player.
    // -----------------------------------------------------------------

    private fun bindMiniArtwork(
        path: String,
        preferredArtworkPath: String = "",
        fallbackArtworkData: ByteArray? = null
    ) {
        val artworkKey = "$path\u0000$preferredArtworkPath"
        val pathChanged = currentMiniArtworkPath != artworkKey
        if (!pathChanged && (currentMiniArtworkHasImage || miniArtworkJob?.isActive == true)) return
        currentMiniArtworkPath = artworkKey
        miniArtworkJob?.cancel()

        val fallbackBitmap = fallbackArtworkData
            ?.takeIf { it.isNotEmpty() }
            ?.let { decodeArtworkBytesSampled(it, 512) }
        val memoryBitmap = AudioArtworkResolver.memoryCachedBitmap(path, preferredArtworkPath)
            ?: fallbackBitmap
        if (memoryBitmap != null) {
            currentMiniArtworkHasImage = true
            miniArtwork.setImageBitmap(memoryBitmap)
            applyLibraryDynamicBackgroundFromBitmap(memoryBitmap, artworkKey)
        } else if (pathChanged) {
            // Ne remplace le visuel par le placeholder qu'au vrai changement de piste. Les simples
            // événements de lecture (buffering, play/pause, timeline) conservent le bitmap courant.
            currentMiniArtworkHasImage = false
            miniArtwork.setImageResource(R.drawable.ic_audio)
            applyFullscreenLibraryBackground(resolveInitialLibraryBackgroundColor())
        }
        miniArtworkJob = lifecycleScope.launch(AudioPlaybackDispatchers.io) {
            val cached = AudioArtworkResolver.memoryCachedBitmap(path, preferredArtworkPath)
                ?: AudioArtworkResolver.cachedBitmap(
                    this@AudioLibraryActivity,
                    path,
                    preferredArtworkPath
                )
            if (cached != null) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (currentMiniArtworkPath == artworkKey) {
                        currentMiniArtworkHasImage = true
                        miniArtwork.setImageBitmap(cached)
                        applyLibraryDynamicBackgroundFromBitmap(cached, artworkKey)
                    }
                }
                return@launch
            }

            // Le mini-player de la bibliothèque ne doit jamais ouvrir le fichier audio ou lister
            // le NAS pendant que celui-ci alimente Media3. Le fallback Media3 déjà affiché reste en
            // place ; la résolution complète reprend à la pause.
            if (AudioLibraryWorkState.isPlaybackProtected()) {
                AudioLibraryWorkState.awaitPlaybackIdle()
            } else {
                AudioLibraryWorkState.awaitPlaybackCriticalWindowEnd()
            }
            val resolved = runCatching {
                AudioArtworkResolver.resolveBitmap(
                    this@AudioLibraryActivity,
                    path,
                    preferredArtworkPath
                )
            }.getOrNull()
            val bmp = resolved ?: fallbackArtworkData
                ?.takeIf { it.isNotEmpty() }
                ?.let { decodeArtworkBytesSampled(it, 512) }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (currentMiniArtworkPath == artworkKey && bmp != null) {
                    currentMiniArtworkHasImage = true
                    miniArtwork.setImageBitmap(bmp)
                    applyLibraryDynamicBackgroundFromBitmap(bmp, artworkKey)
                }
            }
        }
    }

    private fun renderLibraryMiniTags() {
        val miniTags = buildLibraryMiniArtistAlbumText(
            currentMiniArtistTag,
            currentMiniAlbumTag,
            currentMiniAccentColor
        )
        miniArtist.text = miniTags
        miniArtist.visibility = if (miniTags.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun buildLibraryMiniArtistAlbumText(artist: String, album: String, accentColor: Int): CharSequence {
        val safeArtist = artist.trim()
        val safeAlbum = album.trim()
        val builder = android.text.SpannableStringBuilder()
        if (safeArtist.isNotBlank()) {
            val start = builder.length
            builder.append(safeArtist)
            builder.setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                start,
                builder.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                android.text.style.ForegroundColorSpan(accentColor),
                start,
                builder.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (safeAlbum.isNotBlank()) {
            if (builder.isNotEmpty()) builder.append("  •  ")
            builder.append(safeAlbum)
        }
        return builder
    }

    private fun decodeArtworkBytesSampled(data: ByteArray, maxSizePx: Int): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > maxSizePx * 2 || bounds.outHeight / sample > maxSizePx * 2) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
        ) ?: return@runCatching null
        if (decoded.width <= maxSizePx && decoded.height <= maxSizePx) decoded
        else {
            val ratio = minOf(maxSizePx.toFloat() / decoded.width, maxSizePx.toFloat() / decoded.height)
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== decoded) decoded.recycle()
            scaled
        }
    }.getOrNull()

    private fun openFullAudioPlayer() {
        startActivity(Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
            putExtra("openBlazeAudio", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun stopMiniSpin(keepVisible: Boolean) {
        miniSpinAnimator?.cancel()
        miniSpinAnimator = null
        miniSpinner.rotation = 0f
        findViewById<View>(R.id.libraryMiniEqOverlay)?.visibility = if (keepVisible) View.VISIBLE else View.GONE
        miniSpinner.visibility = if (keepVisible) View.VISIBLE else View.GONE
    }

    private fun originalPathOf(item: MediaItem?): String? = item?.mediaMetadata?.extras?.getString("blaze_original_path")

    // -----------------------------------------------------------------
    // Header sticky (détail album) — inchangé, pixel perfect.
    // -----------------------------------------------------------------

    private fun renderStickyDetailHeader(album: LibraryAlbum?, artist: LibraryArtist?) {
        val nextKey = when {
            album != null -> "album:${album.key}:${album.artworkPath}:${album.tracks.size}"
            artist != null -> "artist:${AudioLibraryHeuristics.normalize(artist.name)}:${artist.albums}:${artist.tracks.size}"
            else -> ""
        }

        // Un retour depuis un artiste ou un album doit toujours nettoyer le héros avant de
        // réafficher la bibliothèque. Auparavant handleTopBack() remettait déjà la clé à vide :
        // le rendu suivant considérait alors à tort que le header vide était inchangé et gardait
        // la photo précédente à l'écran.
        if (nextKey.isBlank()) {
            renderedDetailHeaderKey = ""
            artistImageJob?.cancel()
            artistImageJob = null
            currentArtistImage = null
            artistHeroImageView = null
            artistHeroCreditView = null
            artistHeroBoundName = ""
            clearFullBleedArtistHero()
            albumStickyHeader.removeAllViews()
            albumStickyMetadataView = null
            albumStickyHeader.visibility = View.GONE
            applyLibraryWindowInsets()
            return
        }

        val alreadyRendered = when {
            album != null -> nextKey == renderedDetailHeaderKey && artistFullBleedHero != null
            artist != null -> nextKey == renderedDetailHeaderKey && artistFullBleedHero != null
            else -> false
        }
        if (alreadyRendered) return

        renderedDetailHeaderKey = nextKey
        artistImageJob?.cancel()
        currentArtistImage = null
        when {
            album != null -> {
                artistHeroImageView = null
                artistHeroCreditView = null
                artistHeroBoundName = ""
                albumStickyHeader.removeAllViews()
                albumStickyHeader.visibility = View.GONE
                renderFullBleedAlbumHeader(album)
            }
            artist != null -> {
                albumHeroMetadataView = null
                albumStickyMetadataView = null
                albumStickyHeader.removeAllViews()
                albumStickyHeader.visibility = View.GONE
                renderFullBleedArtistHeader(artist)
            }
        }
        applyLibraryWindowInsets()
    }

    private fun renderFullBleedAlbumHeader(album: LibraryAlbum) {
        clearFullBleedArtistHero()
        libraryHero.visibility = View.GONE
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, libraryRootFrame).isAppearanceLightStatusBars = false

        val ordered = albumPlaybackTracks(album)
        val hero = FrameLayout(this).apply {
            background = ColorDrawable(Color.rgb(18, 24, 34))
            clipChildren = true
            clipToPadding = true
            elevation = 0f
        }
        // Le même conteneur plein écran sert aux détails Artiste et Album. Le nom historique de
        // la variable est conservé pour limiter le risque de régression dans la navigation.
        artistFullBleedHero = hero
        libraryRootFrame.addView(
            hero,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                artistHeroTotalHeightPx()
            ).apply { gravity = Gravity.TOP }
        )

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_audio)
            background = ColorDrawable(Color.rgb(18, 24, 34))
        }
        hero.addView(
            cover,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        album.tracks.firstOrNull()?.copy(artworkPath = album.artworkPath)?.let { representative ->
            bindOriginalArtwork(cover, representative)
        }

        // Dégradé inférieur pour le titre et les actions, identique à la fiche Artiste.
        hero.addView(
            View(this).apply {
                background = ContextCompat.getDrawable(
                    this@AudioLibraryActivity,
                    R.drawable.bg_audio_artist_hero_scrim
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Overlay supérieur opaque sous la barre système : la navigation reste lisible même sur
        // une pochette très claire ou très contrastée.
        hero.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.argb(238, 4, 7, 12),
                        Color.argb(190, 4, 7, 12),
                        Color.argb(92, 4, 7, 12),
                        Color.TRANSPARENT
                    )
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(164)
            ).apply { gravity = Gravity.TOP }
        )

        val topContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), statusBarInsetTop + dp(10), dp(18), dp(10))
        }
        artistHeroTopOverlayContent = topContent
        hero.addView(
            topContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                statusBarInsetTop + dp(82),
                Gravity.TOP
            )
        )

        topContent.addView(artistOverlayActionButton(R.drawable.ic_arrow_back, getString(R.string.action_back)).apply {
            setOnClickListener { handleTopBack() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topContent.addView(
            heading,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
            }
        )
        heading.addView(TextView(this).apply {
            text = getString(R.string.audio_album_page_title)
            setTextColor(Color.WHITE)
            textSize = 25f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        })
        topContent.addView(artistOverlayActionButton(R.drawable.ic_search, getString(R.string.audio_search)).apply {
            setOnClickListener { showSearchDialog() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        topContent.addView(
            artistOverlayActionButton(
                R.drawable.ic_home,
                getString(R.string.audio_open_full_player)
            ).apply {
                setOnClickListener { openFullAudioPlayer() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) }
        )
        topContent.addView(artistOverlayActionButton(R.drawable.ic_settings, getString(R.string.audio_settings_title)).apply {
            setOnClickListener {
                startActivity(Intent(this@AudioLibraryActivity, AudioProSettingsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })

        val bottomContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(18), dp(8), dp(18), dp(22))
        }
        hero.addView(
            bottomContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        bottomContent.addView(TextView(this).apply {
            text = album.title
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
        })
        val albumDetails = albumArtistMetaLine(album, ordered)
        if (albumDetails.isNotBlank()) {
            val detailsView = TextView(this).apply {
                text = albumDetails
                setTextColor(Color.argb(225, 255, 255, 255))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            }
            albumHeroMetadataView = detailsView
            bottomContent.addView(detailsView)
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        bottomContent.addView(
            actionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        actionRow.addView(
            artistHeroPlayAllButton(getString(R.string.audio_play_album)).apply {
                setOnClickListener { playAlbumNow(ordered) }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        actionRow.addView(
            albumHeroQueueButton(getString(R.string.audio_album_queue_button)).apply {
                contentDescription = getString(R.string.audio_add_album_queue)
                tooltipText = getString(R.string.audio_add_album_queue)
                setOnClickListener { appendTracksToAudioQueue(ordered) }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) }
        )
    }

    private fun renderFullBleedArtistHeader(artist: LibraryArtist) {
        clearFullBleedArtistHero()
        libraryHero.visibility = View.GONE
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, libraryRootFrame).isAppearanceLightStatusBars = false

        val hero = FrameLayout(this).apply {
            background = ColorDrawable(Color.rgb(18, 24, 34))
            clipChildren = true
            clipToPadding = true
            elevation = 0f
        }
        artistFullBleedHero = hero
        libraryRootFrame.addView(
            hero,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                artistHeroTotalHeightPx()
            ).apply { gravity = Gravity.TOP }
        )

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_audio)
            background = ColorDrawable(Color.rgb(18, 24, 34))
        }
        hero.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        artistHeroImageView = image
        artistHeroBoundName = artist.name

        // La pochette d'un album n'est qu'un fallback immédiat. Une photo artiste déjà en mémoire
        // est appliquée sans transition pour éviter tout scintillement lors des mises à jour du snapshot.
        val readyArtistImage = ArtistImageRepository.peekMemory(artist.name)
        if (readyArtistImage == null) {
            artist.tracks.firstOrNull()?.let { representative ->
                bindOriginalArtwork(
                    image,
                    representative.copy(
                        artworkPath = AudioLibraryHeuristics.bestArtworkPath(artist.tracks)
                    )
                )
            }
        }

        // Dégradé inférieur pour le nom, les actions et les crédits.
        hero.addView(
            View(this).apply {
                background = ContextCompat.getDrawable(
                    this@AudioLibraryActivity,
                    R.drawable.bg_audio_artist_hero_scrim
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Overlay supérieur plus opaque : la navigation reste lisible quelle que soit la photo.
        val topScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(238, 4, 7, 12),
                    Color.argb(190, 4, 7, 12),
                    Color.argb(92, 4, 7, 12),
                    Color.TRANSPARENT
                )
            )
        }
        hero.addView(
            topScrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(164)
            ).apply { gravity = Gravity.TOP }
        )

        val topContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), statusBarInsetTop + dp(10), dp(18), dp(10))
        }
        artistHeroTopOverlayContent = topContent
        hero.addView(
            topContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                statusBarInsetTop + dp(82),
                Gravity.TOP
            )
        )

        topContent.addView(artistOverlayActionButton(R.drawable.ic_arrow_back, getString(R.string.action_back)).apply {
            setOnClickListener { handleTopBack() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topContent.addView(
            heading,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
            }
        )
        heading.addView(TextView(this).apply {
            text = getString(R.string.audio_artist_page_title)
            setTextColor(Color.WHITE)
            textSize = 25f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        })
        heading.addView(TextView(this).apply {
            text = artistSummaryText(artist)
            setTextColor(Color.argb(225, 255, 255, 255))
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        })

        topContent.addView(artistOverlayActionButton(R.drawable.ic_search, getString(R.string.audio_search)).apply {
            setOnClickListener { showSearchDialog() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
        topContent.addView(
            artistOverlayActionButton(
                R.drawable.ic_home,
                getString(R.string.audio_open_full_player)
            ).apply {
                setOnClickListener { openFullAudioPlayer() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) }
        )
        topContent.addView(artistOverlayActionButton(R.drawable.ic_settings, getString(R.string.audio_settings_title)).apply {
            setOnClickListener {
                startActivity(Intent(this@AudioLibraryActivity, AudioProSettingsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })

        val bottomContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(18), dp(8), dp(18), dp(22))
        }
        hero.addView(
            bottomContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        bottomContent.addView(TextView(this).apply {
            text = artist.name
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        bottomContent.addView(
            actionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        actionRow.addView(artistHeroPlayAllButton(getString(R.string.audio_artist_play_all)).apply {
            setOnClickListener { playAlbumNow(artistPlaybackTracks(artist)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        actionRow.addView(artistHeroPhotoButton().apply {
            contentDescription = getString(R.string.audio_artist_change_photo)
            tooltipText = getString(R.string.audio_artist_change_photo)
            setOnClickListener { showArtistPhotoActions(artist) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })

        val credit = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 9f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.END
            setPadding(dp(12), 0, dp(10), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { currentArtistImage?.let(::showArtistPhotoCredits) }
        }
        hero.addView(
            credit,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            )
        )
        artistHeroCreditView = credit
        loadArtistHeroImage(artist)
    }

    private fun artistOverlayActionButton(iconRes: Int, description: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            tooltipText = description
            setPadding(dp(9))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(126, 6, 10, 16))
                setStroke(dp(1), Color.argb(92, 255, 255, 255))
            }
            isClickable = true
            isFocusable = true
        }

    private fun clearFullBleedArtistHero() {
        artistFullBleedHero?.let { hero ->
            (hero.parent as? ViewGroup)?.removeView(hero)
        }
        artistFullBleedHero = null
        artistHeroTopOverlayContent = null
        albumHeroMetadataView = null
        if (::libraryHero.isInitialized) libraryHero.visibility = View.VISIBLE
        if (::libraryRootFrame.isInitialized) {
            window.statusBarColor = bg
            WindowCompat.getInsetsController(window, libraryRootFrame).isAppearanceLightStatusBars = false
        }
    }

    private fun artistHeroVisibleHeightPx(): Int = dp(332)

    private fun artistHeroTotalHeightPx(): Int = statusBarInsetTop + artistHeroVisibleHeightPx()

    private fun applyLibraryWindowInsets() {
        if (!::libraryScreenContainer.isInitialized) return
        val detailHeroMode = currentState.openedArtist != null || currentState.openedAlbum != null
        val topPadding = if (detailHeroMode) artistHeroTotalHeightPx() else statusBarInsetTop
        libraryScreenContainer.setPadding(0, topPadding, 0, navigationBarInsetBottom)

        artistFullBleedHero?.let { hero ->
            val params = hero.layoutParams as? FrameLayout.LayoutParams ?: return@let
            val wantedHeight = artistHeroTotalHeightPx()
            if (params.height != wantedHeight) {
                params.height = wantedHeight
                hero.layoutParams = params
            }
        }
        artistHeroTopOverlayContent?.let { content ->
            content.setPadding(dp(18), statusBarInsetTop + dp(10), dp(18), dp(10))
            content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
                height = statusBarInsetTop + dp(82)
            }
        }

        if (detailHeroMode) {
            libraryHero.visibility = View.GONE
            window.statusBarColor = Color.TRANSPARENT
        } else {
            libraryHero.visibility = View.VISIBLE
            window.statusBarColor = bg
        }
        window.navigationBarColor = bg
    }

    private fun loadArtistHeroImage(artist: LibraryArtist) {
        val image = artistHeroImageView ?: return
        val credit = artistHeroCreditView
        val requestedName = artist.name
        if (AudioLibraryHeuristics.normalize(artistHeroBoundName) != AudioLibraryHeuristics.normalize(requestedName)) return
        artistImageJob?.cancel()
        artistImageJob = lifecycleScope.launch {
            val result = ArtistImageRepository.peekMemory(requestedName)
                ?: ArtistImageRepository.resolve(this@AudioLibraryActivity, requestedName, artist.tracks)
                ?: return@launch
            val bitmap = withContext(artworkDispatcher) { cachedArtistBitmap(result.localPath, 1800) }
                ?: return@launch
            if (isFinishing || isDestroyed || currentState.openedArtist?.name != requestedName ||
                artistHeroImageView !== image
            ) return@launch
            val imageTag = "artist_remote:${result.localPath}:${java.io.File(result.localPath).lastModified()}"
            if (image.tag != imageTag) {
                image.tag = imageTag
                image.clearColorFilter()
                image.setPadding(0)
                image.setImageBitmap(bitmap)
            }
            currentArtistImage = result
            if (credit != null) {
                if (!result.isLocalFile) {
                    credit.text = buildArtistPhotoCreditLabel(result)
                    credit.visibility = if (credit.text.isBlank()) View.GONE else View.VISIBLE
                } else {
                    credit.visibility = View.GONE
                }
            }
        }
    }

    private fun cachedArtistBitmap(path: String, maxSizePx: Int): android.graphics.Bitmap? {
        val file = java.io.File(path)
        val sizeBucket = if (maxSizePx >= 1200) "hero" else "tile"
        val key = "$path:$sizeBucket:${file.lastModified()}:${file.length()}"
        artistBitmapCache.get(key)?.let { return it }
        return decodeArtistImageFile(path, maxSizePx)?.also { artistBitmapCache.put(key, it) }
    }

    private fun decodeArtistImageFile(path: String, maxSizePx: Int): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > maxSizePx * 2 || bounds.outHeight / sample > maxSizePx * 2) sample *= 2
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    private fun artistPlaybackTracks(artist: LibraryArtist): List<LibraryTrack> {
        val trackOrder = AudioProSettings.read(this).trackOrder
        val base = compareBy<LibraryTrack> {
            AudioLibraryHeuristics.normalize(AudioLibraryHeuristics.albumFolderNameFromPath(it.path))
        }.thenBy { AudioLibraryHeuristics.discNumberFromPath(it.path) }
        return if (trackOrder) {
            artist.tracks.distinctBy { it.path }.sortedWith(
                base.thenBy { AudioLibraryHeuristics.normalizedTrackNo(it.trackNo) }
                    .thenBy { AudioLibraryHeuristics.normalize(it.title) }
            )
        } else {
            artist.tracks.distinctBy { it.path }.sortedWith(
                base.thenBy { AudioLibraryHeuristics.normalize(it.title) }
            )
        }
    }

    private fun artistSummaryText(artist: LibraryArtist): String = listOf(
        resources.getQuantityString(R.plurals.audio_album_count_compact, artist.albums, artist.albums),
        resources.getQuantityString(R.plurals.audio_track_count_compact, artist.tracks.size, artist.tracks.size)
    ).joinToString(" • ")

    private fun showArtistPhotoActions(artist: LibraryArtist) {
        val hasManual = ArtistImageRepository.hasManualImage(this, artist.name)
        val labels = buildList {
            add(getString(R.string.audio_artist_photo_choose_device))
            if (hasManual) add(getString(R.string.audio_artist_photo_restore_auto))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_artist_photo_actions_title)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        pendingArtistPhotoSelection = artist
                        artistPhotoPicker.launch(arrayOf("image/*"))
                    }
                    1 -> if (hasManual) {
                        ArtistImageRepository.clearManualImage(this, artist.name)
                        currentArtistImage = null
                        if (::adapter.isInitialized) adapter.notifyArtistImageChanged(artist.name)
                        renderedDetailHeaderKey = ""
                        renderStickyDetailHeader(null, currentState.openedArtist)
                        Toast.makeText(
                            this,
                            R.string.audio_artist_photo_restored,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
            .also { DialogButtonStyler.style(it) }
    }

    private fun buildArtistPhotoCreditLabel(image: ArtistImageRepository.ArtistImage): String {
        val author = image.author.trim()
        val license = image.licenseName.trim()
        return when {
            author.isNotBlank() && license.isNotBlank() -> getString(R.string.audio_artist_photo_credit, author, license)
            author.isNotBlank() -> getString(R.string.audio_artist_photo_credit_author, author)
            license.isNotBlank() -> license
            image.sourcePageUrl.isNotBlank() -> getString(R.string.audio_artist_photo_source)
            else -> ""
        }
    }

    private fun showArtistPhotoCredits(image: ArtistImageRepository.ArtistImage) {
        val details = buildList {
            image.author.takeIf { it.isNotBlank() }?.let { add(getString(R.string.audio_artist_photo_author, it)) }
            image.licenseName.takeIf { it.isNotBlank() }?.let { add(getString(R.string.audio_artist_photo_license, it)) }
            image.description.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("\n\n")
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.audio_artist_photo_credits_title)
            .setMessage(details.ifBlank { getString(R.string.audio_artist_photo_source) })
            .setNegativeButton(R.string.action_close, null)
        if (image.sourcePageUrl.isNotBlank()) {
            builder.setPositiveButton(R.string.audio_artist_photo_open_source) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(image.sourcePageUrl)))
                }
            }
        }
        builder.show().also { DialogButtonStyler.style(it) }
    }

    private fun artistHeroPlayAllButton(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 13f
        maxLines = 1
        isSingleLine = true
        ellipsize = null
        compoundDrawablePadding = dp(8)
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_white, 0, 0, 0)
        setPadding(dp(18), dp(11), dp(18), dp(11))
        minHeight = dp(48)
        minWidth = 0
        maxWidth = resources.displayMetrics.widthPixels - dp(104)
        val foreground = if (isDarkColor(accent)) Color.WHITE else Color.rgb(8, 15, 24)
        setTextColor(foreground)
        compoundDrawableTintList = ColorStateList.valueOf(foreground)
        background = premiumArtistHeroPillBackground()
        elevation = dp(4).toFloat()
        isClickable = true
        isFocusable = true
        isAllCaps = false
        ButtonTextFitter.fit(this, minSp = 9, maxSp = 13)
    }

    private fun albumHeroQueueButton(label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        minimumHeight = dp(48)
        minimumWidth = 0
        background = premiumArtistHeroPillBackground()
        elevation = dp(4).toFloat()
        isClickable = true
        isFocusable = true
        clipChildren = true
        clipToPadding = true

        addView(ImageView(this@AudioLibraryActivity).apply {
            setImageResource(R.drawable.ic_add_circle_black)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(23), dp(23)).apply {
            marginEnd = dp(8)
        })

        addView(TextView(this@AudioLibraryActivity).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (isDarkColor(accent)) Color.WHITE else Color.rgb(8, 15, 24))
            isAllCaps = false
            maxLines = 1
            isSingleLine = true
            ellipsize = null
            minWidth = 0
            ButtonTextFitter.fit(this, minSp = 8, maxSp = 13)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun artistHeroPhotoButton(): ImageButton = ImageButton(this).apply {
        setImageResource(R.drawable.ic_camera)
        setPadding(dp(13))
        imageTintList = ColorStateList.valueOf(
            if (isDarkColor(accent)) Color.WHITE else Color.rgb(8, 15, 24)
        )
        background = premiumArtistHeroPillBackground()
        elevation = dp(4).toFloat()
        isClickable = true
        isFocusable = true
    }

    private fun premiumArtistHeroPillBackground(): RippleDrawable {
        val radius = dp(24).toFloat()
        val start = blendColors(Color.WHITE, accent, 0.18f)
        val end = blendColors(Color.BLACK, accent, 0.20f)
        val stroke = blendColors(Color.WHITE, accent, 0.42f)
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(72, 255, 255, 255)), base, mask)
    }

    private fun installStickyHero() {
        val hero = libraryHero
        val container = libraryScreenContainer
        // Ne jamais appeler hero.bringToFront() ici : dans un LinearLayout vertical,
        // bringToFront() déplace réellement la vue en dernière position dans l'ordre des enfants.
        if (container != null && hero.parent === container && container.indexOfChild(hero) != 0) {
            container.removeView(hero)
            container.addView(hero, 0)
        }
        container?.setBackgroundColor(Color.TRANSPARENT)
        hero.setBackgroundColor(Color.TRANSPARENT)
        albumStickyHeader.setBackgroundColor(Color.TRANSPARENT)
        albumStickyHeader.clipChildren = false
        albumStickyHeader.clipToPadding = false
        (albumStickyHeader.parent as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        hero.elevation = 0f
        hero.translationZ = 0f
        hero.translationY = 0f
        hero.y = 0f
    }

    private fun albumPlaybackTracks(album: LibraryAlbum): List<LibraryTrack> =
        AudioLibraryHeuristics.sortAlbumTracks(
            album.tracks,
            AudioProSettings.read(this).trackOrder
        )

    private fun updateVisibleAlbumMetadata(album: LibraryAlbum?) {
        if (album == null) return
        val ordered = albumPlaybackTracks(album)
        val details = albumArtistMetaLine(album, ordered)
        albumHeroMetadataView?.text = details
        albumStickyMetadataView?.text = details
    }

    private fun albumMetaParts(tracks: List<LibraryTrack>): List<String> {
        val uniqueTracks = tracks.distinctBy { it.path }
        val count = resources.getQuantityString(
            R.plurals.audio_track_count_compact,
            uniqueTracks.size,
            uniqueTracks.size
        )
        val totalDurationMs = uniqueTracks.asSequence()
            .map(LibraryTrack::durationMs)
            .filter { it > 0L }
            .sum()
        val duration = totalDurationMs.takeIf { it > 0L }
            ?.let(AudioLibraryHeuristics::formatDuration)
        return listOfNotNull(count, duration)
    }

    private fun albumArtistMetaLine(album: LibraryAlbum, tracks: List<LibraryTrack>): String =
        buildList {
            albumDisplayArtist(album).takeIf { it.isNotBlank() }?.let(::add)
            addAll(albumMetaParts(tracks))
        }.joinToString(" - ")


    private fun albumCardPrimaryText(album: LibraryAlbum): String = album.title

    private fun albumCardSecondaryText(album: LibraryAlbum): String = albumDisplayArtist(album)

    private fun albumDisplayArtist(album: LibraryAlbum): String = album.artist.trim()

    private fun albumTrackCountText(album: LibraryAlbum): String {
        val tracks = album.tracks.distinctBy { it.path }
        val count = resources.getQuantityString(R.plurals.audio_track_count_compact, tracks.size, tracks.size)
        val totalDurationMs = tracks.asSequence()
            .map(LibraryTrack::durationMs)
            .filter { it > 0L }
            .sum()
        val duration = totalDurationMs.takeIf { it > 0L }
            ?.let(AudioLibraryHeuristics::formatDuration)
        return listOfNotNull(count, duration).joinToString(" · ")
    }

    // -----------------------------------------------------------------
    // Résolution / binding des covers (chemin de lecture visible, paresseux).
    // -----------------------------------------------------------------

    private fun bindOriginalArtwork(view: ImageView, track: LibraryTrack) {
        val primary = viewModel.primaryArtworkPathFor(track)
        val fallback = viewModel.fallbackArtworkPathFor(track)
        val requestKey = "$primary|${fallback.orEmpty()}"
        view.tag = requestKey
        AudioArtworkResolver.memoryCachedBitmap(track.path, primary)?.let {
            if (view.tag == requestKey) {
                view.clearColorFilter()
                view.setPadding(0)
                view.setImageBitmap(it)
            }
            return
        }
        lifecycleScope.launch(artworkDispatcher) {
            val bitmap = viewModel.loadArtworkForBinding(track)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (bitmap != null && !isFinishing && !isDestroyed && view.tag == requestKey) {
                    view.clearColorFilter()
                    view.setPadding(0)
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // -----------------------------------------------------------------
    // Couleur dynamique / thème (inchangé).
    // -----------------------------------------------------------------

    private fun applySystemBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
    }

    private fun resolveInitialLibraryBackgroundColor(): Int {
        val fallback = AudioDynamicColor.backgroundFromAccent(AudioDynamicColor.DEFAULT_ACCENT)
        if (!runCatching { AudioProSettings.read(this).dynamicTheme }.getOrDefault(true)) {
            return ContextCompat.getColor(this, R.color.background)
        }
        return runCatching {
            val prefs = getSharedPreferences(DYNAMIC_AUDIO_PREFS, MODE_PRIVATE)
            when {
                // Ne jamais restaurer directement un ancien fond calculé par une version
                // précédente : il peut encore être noir, brun sombre ou bleu marine. Le fond est
                // systématiquement recalculé depuis un accent désormais normalisé et lisible.
                prefs.contains(KEY_DYNAMIC_ACCENT) -> AudioDynamicColor.backgroundFromAccent(
                    AudioDynamicColor.ensureReadableAccent(
                        prefs.getInt(KEY_DYNAMIC_ACCENT, AudioDynamicColor.DEFAULT_ACCENT)
                    )
                )
                else -> fallback
            }
        }.getOrDefault(fallback)
    }

    private fun applyLibraryDynamicBackgroundFromBitmap(
        bitmap: android.graphics.Bitmap?,
        artworkKey: String = currentMiniArtworkPath
    ) {
        if (!runCatching { AudioProSettings.read(this).dynamicTheme }.getOrDefault(true) || bitmap == null) {
            applyFullscreenLibraryBackground(resolveInitialLibraryBackgroundColor())
            return
        }
        lifecycleScope.launch(AudioPlaybackDispatchers.compute) {
            val dynamicAccent = runCatching {
                AudioDynamicColor.accentFromBitmap(bitmap)
            }.getOrDefault(AudioDynamicColor.DEFAULT_ACCENT)
            val dynamicBg = AudioDynamicColor.backgroundFromAccent(dynamicAccent)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                // Une extraction de l'ancienne cover ne doit jamais recolorer l'écran après le
                // passage au titre suivant.
                if (!isFinishing && !isDestroyed && currentMiniArtworkPath == artworkKey) {
                    currentMiniAccentColor = dynamicAccent
                    renderLibraryMiniTags()
                    miniSpinner.imageTintList = ColorStateList.valueOf(dynamicAccent)
                    applyFullscreenLibraryBackground(dynamicBg)
                    getSharedPreferences(DYNAMIC_AUDIO_PREFS, MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_DYNAMIC_BG, dynamicBg)
                        .putInt(KEY_DYNAMIC_ACCENT, dynamicAccent)
                        .apply()
                }
            }
        }
    }

    private fun buildLibraryBackgroundDrawable(color: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            AudioDynamicColor.mix(Color.rgb(2, 7, 9), color, 0.56f),
            AudioDynamicColor.mix(color, Color.rgb(18, 24, 30), 0.08f),
            AudioDynamicColor.mix(Color.rgb(2, 7, 9), color, 0.34f)
        )
    )

    private fun applyFullscreenLibraryBackground(libraryBackgroundColor: Int) {
        bg = libraryBackgroundColor
        if (currentState.openedArtist != null && currentState.openedAlbum == null) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = libraryBackgroundColor
        } else {
            applySystemBarColors(libraryBackgroundColor)
        }
        findViewById<View>(R.id.libraryRoot)?.background = buildLibraryBackgroundDrawable(libraryBackgroundColor)
        findViewById<View>(R.id.libraryScreenContainer)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(R.id.libraryGlobalCard)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(R.id.libraryHero)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(R.id.albumStickyHeader)?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun applyAccentToStaticChrome() {
        findViewById<TextView>(R.id.badgeLibraryPro)?.apply {
            setTextColor(accent)
            setBackgroundResource(R.drawable.bg_pro_badge)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        listOf(
            findViewById<TextView>(R.id.btnManageWatched),
            findViewById<TextView>(R.id.btnManualScan),
            findViewById<TextView>(R.id.tvAlbumViewGrid3),
            findViewById<TextView>(R.id.tvAlbumViewGrid4),
            findViewById<TextView>(R.id.tvAlbumViewList),
            findViewById<TextView>(R.id.tvSortMode)
        ).forEach { chip -> chip?.let { styleLibraryPill(it, selected = false, enabled = true, fillMix = 0.12f) } }
        findViewById<TextView>(R.id.tvAlbumSortMode)?.setTextColor(accent)
        findViewById<ImageView>(R.id.iconWatchedFolders)?.apply {
            imageTintList = ColorStateList.valueOf(accent)
            background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_pill)
        }
        listOf(R.id.btnBack, R.id.btnSearch, R.id.btnLibraryHome, R.id.btnSettings).forEach { id ->
            findViewById<ImageButton>(id)?.imageTintList = ColorStateList.valueOf(textMain)
        }
        miniSpinner.imageTintList = ColorStateList.valueOf(accent)
    }

    private fun styleLibraryPill(view: TextView, selected: Boolean, enabled: Boolean, fillMix: Float = 0.14f) {
        val fillColor = when {
            selected -> accent
            enabled -> blendColors(accent, Color.TRANSPARENT, fillMix)
            else -> Color.argb(36, 255, 255, 255)
        }
        view.setTextColor(
            when {
                selected && isDarkColor(accent) -> Color.WHITE
                selected -> Color.BLACK
                enabled -> accent
                else -> textMuted
            }
        )
        view.background = pillDrawable(fillColor, dp(18), stroke = blendColors(accent, Color.TRANSPARENT, if (selected) 0.55f else 0.22f))
    }

    private fun pillDrawable(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun blendColors(foreground: Int, background: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val bgA = Color.alpha(background) / 255f
        val fgA = (Color.alpha(foreground) / 255f) * a
        val outA = fgA + bgA * (1f - fgA)
        if (outA <= 0f) return Color.TRANSPARENT
        fun channel(fg: Int, bg: Int): Int = (((fg * fgA) + (bg * bgA * (1f - fgA))) / outA).toInt().coerceIn(0, 255)
        return Color.argb((outA * 255).toInt().coerceIn(0, 255), channel(Color.red(foreground), Color.red(background)), channel(Color.green(foreground), Color.green(background)), channel(Color.blue(foreground), Color.blue(background)))
    }

    private fun isDarkColor(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return luminance < 0.55
    }

    // -----------------------------------------------------------------
    // Adapter — ListAdapter + AsyncListDiffer natif AndroidX (remplace le DiffUtil fait main).
    // Row.Section / Row.AlbumItem / Row.AlbumBack retirés : jamais construits par le ViewModel
    // (vérifié zéro appelant dans l'ancien code), donc leurs ViewHolders sont retirés aussi.
    // -----------------------------------------------------------------

    private inner class LibraryAdapter : ListAdapter<LibraryRow, RecyclerView.ViewHolder>(RowDiffCallback) {
        private val boundArtworkJobs = ConcurrentHashMap<ImageView, Job>()
        private val boundArtistImageJobs = ConcurrentHashMap<ImageView, Job>()
        private val appliedArtistImages = ConcurrentHashMap<ImageView, String>()
        // Cache indexé avec la clé exacte de binding de la tuile. Il complète le cache global
        // ThumbnailUtils, dont les alias peuvent évoluer quand une cover.jpg remplace une embedded.
        // Une pochette déjà affichée reste donc disponible de façon synchrone pendant le scroll.
        private val boundArtworkBitmapCache = object : LruCache<String, android.graphics.Bitmap>(48 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }

        init { setHasStableIds(true) }

        fun notifyNowPlayingChanged(previousKey: String, currentKey: String) {
            if (previousKey.isBlank() && currentKey.isBlank()) return

            currentList.forEachIndexed { index, row ->
                val track = when (row) {
                    is LibraryRow.AlbumTrackItem -> row.track
                    is LibraryRow.TrackItem -> row.track
                    else -> return@forEachIndexed
                }
                val key = trackPlaybackKey(track.path)
                if (key == previousKey || key == currentKey) {
                    notifyItemChanged(index, PAYLOAD_NOW_PLAYING)
                }
            }
        }

        fun notifyArtistImageChanged(artistName: String) {
            val normalized = AudioLibraryHeuristics.normalize(artistName)
            currentList.forEachIndexed { index, row ->
                if (row is LibraryRow.ArtistItem &&
                    AudioLibraryHeuristics.normalize(row.artist.name) == normalized
                ) {
                    notifyItemChanged(index, PAYLOAD_ARTIST_IMAGE)
                }
            }
        }

        override fun getItemId(position: Int): Long = getItem(position).stableId
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is LibraryRow.Status -> VIEW_TYPE_STATUS
            is LibraryRow.TrackItem -> VIEW_TYPE_TRACK
            is LibraryRow.AlbumTrackItem -> VIEW_TYPE_ALBUM_TRACK
            is LibraryRow.AlbumTile -> VIEW_TYPE_ALBUM_TILE
            is LibraryRow.ArtistItem -> VIEW_TYPE_ARTIST
            is LibraryRow.PlaylistItem -> VIEW_TYPE_PLAYLIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            VIEW_TYPE_STATUS -> TextHolder(statusTextView())
            VIEW_TYPE_ARTIST -> ArtistHolder(artistTileView())
            VIEW_TYPE_PLAYLIST -> PlaylistHolder(rowView(withCover = false))
            VIEW_TYPE_ALBUM_TILE -> AlbumTileHolder(albumTileView())
            VIEW_TYPE_ALBUM_TRACK -> TrackMetaHolder(trackMetaRowView())
            else -> MediaHolder(rowView(withCover = true))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is LibraryRow.Status -> (holder as TextHolder).bind(row)
                is LibraryRow.TrackItem -> (holder as MediaHolder).bindTrack(row.track)
                is LibraryRow.AlbumTrackItem -> (holder as TrackMetaHolder).bind(row.track, row.indexInAlbum)
                is LibraryRow.AlbumTile -> (holder as AlbumTileHolder).bind(row.album)
                is LibraryRow.ArtistItem -> (holder as ArtistHolder).bind(row.artist)
                is LibraryRow.PlaylistItem -> (holder as PlaylistHolder).bind(row.playlist)
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            when {
                payloads.contains(PAYLOAD_ARTIST_IMAGE) && holder is ArtistHolder -> {
                    val row = getItem(position) as? LibraryRow.ArtistItem ?: return
                    holder.bindArtworkOnly(row.artist)
                    return
                }
                payloads.contains(PAYLOAD_ALBUM_METADATA) && holder is AlbumTileHolder -> {
                    val row = getItem(position) as? LibraryRow.AlbumTile ?: return
                    holder.bindMetadataOnly(row.album)
                    return
                }
                payloads.contains(PAYLOAD_ARTIST_METADATA) && holder is ArtistHolder -> {
                    val row = getItem(position) as? LibraryRow.ArtistItem ?: return
                    holder.bindMetadataOnly(row.artist)
                    return
                }
                payloads.contains(PAYLOAD_TRACK_METADATA) && holder is MediaHolder -> {
                    val row = getItem(position) as? LibraryRow.TrackItem ?: return
                    holder.bindMetadataOnly(row.track)
                    return
                }
                payloads.contains(PAYLOAD_TRACK_ARTWORK) && holder is MediaHolder -> {
                    val row = getItem(position) as? LibraryRow.TrackItem ?: return
                    holder.bindMetadataOnly(row.track)
                    holder.bindArtworkOnly(row.track)
                    return
                }
                payloads.contains(PAYLOAD_TRACK_METADATA) && holder is TrackMetaHolder -> {
                    val row = getItem(position) as? LibraryRow.AlbumTrackItem ?: return
                    holder.bindMetadataOnly(row.track, row.indexInAlbum)
                    return
                }
                payloads.contains(PAYLOAD_NOW_PLAYING) && holder is TrackMetaHolder -> {
                    val row = getItem(position) as? LibraryRow.AlbumTrackItem ?: return
                    holder.bindPlayingState(row.track)
                    return
                }
                payloads.contains(PAYLOAD_NOW_PLAYING) && holder is MediaHolder -> {
                    val row = getItem(position) as? LibraryRow.TrackItem ?: return
                    holder.bindPlayingState(row.track)
                    return
                }
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is TrackMetaHolder) {
                // Un ViewHolder peut revenir après un scroll/navigation sans nouveau bind.
                holder.onAttachedToWindow()
            }
            if (holder is MediaHolder) holder.onAttachedToWindow()
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            // Ne pas annuler les décodages locaux en cours : le tag empêche toute image erronée et
            // le résultat réchauffe le cache pour la tuile qui réapparaît pendant le même fling.
            if (holder is MediaHolder) {
                boundArtworkJobs.remove(holder.cover)
                holder.recycle()
            }
            if (holder is AlbumTileHolder) boundArtworkJobs.remove(holder.cover)
            if (holder is TrackMetaHolder) holder.recycle()
            if (holder is ArtistHolder) {
                // Les jobs terminent et réchauffent les caches ; le tag empêche toute écriture sur
                // une tuile recyclée pour un autre artiste. Les annuler provoquait les photos qui
                // disparaissaient pendant un fling puis ne revenaient qu'à l'arrêt.
                boundArtworkJobs.remove(holder.photo)
                boundArtistImageJobs.remove(holder.photo)
                appliedArtistImages.remove(holder.photo)
            }
            super.onViewRecycled(holder)
        }

        private fun statusTextView() = TextView(this@AudioLibraryActivity).apply {
            setTextColor(textMuted)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(42), dp(18), dp(42))
            layoutParams = RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        private fun rowView(withCover: Boolean): LinearLayout = LinearLayout(this@AudioLibraryActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable(Color.argb(30, 255, 255, 255), dp(18), stroke = Color.argb(18, 255, 255, 255))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            if (withCover) {
                val cover = fr.retrospare.blazeplayer.ui.RoundedImageView(this@AudioLibraryActivity).apply {
                    id = View.generateViewId()
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.ic_audio)
                    background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_cover_placeholder)
                }
                addView(cover, LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(12) })
            } else {
                val avatar = TextView(this@AudioLibraryActivity).apply {
                    id = View.generateViewId()
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                    background = pillDrawable(accent, dp(29))
                }
                addView(avatar, LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(12) })
            }
            val texts = LinearLayout(this@AudioLibraryActivity).apply { orientation = LinearLayout.VERTICAL; id = View.generateViewId() }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            texts.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId(); setTextColor(textMain); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId(); setTextColor(textMuted); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId(); setTextColor(Color.argb(190, 255, 255, 255)); textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (withCover) {
                addView(ImageButton(this@AudioLibraryActivity).apply {
                    background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_action_button)
                    setImageResource(R.drawable.ic_play)
                    imageTintList = ColorStateList.valueOf(accent)
                    contentDescription = getString(R.string.action_play_pause)
                    setPadding(dp(9))
                }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(6) })
            }
            addView(ImageButton(this@AudioLibraryActivity).apply {
                background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_action_button)
                setImageResource(if (withCover) R.drawable.ic_more_vert else R.drawable.ic_layout_list)
                imageTintList = ColorStateList.valueOf(textMuted)
                setPadding(dp(8))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { if (withCover) marginStart = dp(8) })
        }

        private fun albumTileView(): LinearLayout = LinearLayout(this@AudioLibraryActivity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(4), dp(4), dp(4), dp(14))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val cover = fr.retrospare.blazeplayer.ui.RoundedImageView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                scaleType = ImageView.ScaleType.CENTER_CROP
                radiusDp = 12f
                forceSquare = true
                setImageResource(R.drawable.ic_audio)
                background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_cover_placeholder)
            }
            addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(textMain)
                textSize = 15f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                includeFontPadding = false
                maxLines = 2
                ellipsize = null
            })
            addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(textMuted)
                textSize = 12f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 2
                ellipsize = null
            })
            addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(accent)
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 1
                ellipsize = null
            })
        }

        private fun artistTileView(): LinearLayout = LinearLayout(this@AudioLibraryActivity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(4), dp(4), dp(4), dp(14))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val photo = fr.retrospare.blazeplayer.ui.RoundedImageView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                scaleType = ImageView.ScaleType.CENTER_CROP
                radiusDp = 12f
                forceSquare = true
                setImageResource(R.drawable.ic_audio)
                background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_cover_placeholder)
            }
            addView(photo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
            addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(textMain)
                textSize = 15f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                includeFontPadding = false
                maxLines = 2
                ellipsize = null
            })
            addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(accent)
                textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 1
                ellipsize = null
            })
        }

        private fun trackMetaRowView(): LinearLayout = LinearLayout(this@AudioLibraryActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable(Color.argb(26, 255, 255, 255), dp(16), stroke = Color.argb(16, 255, 255, 255))
            setPadding(dp(12), dp(9), dp(10), dp(9))
            layoutParams = RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }

            val playingIndicator =
                fr.retrospare.blazeplayer.widget.MiniEqualizerView(
                    this@AudioLibraryActivity
                ).apply {
                    id = View.generateViewId()
                    visibility = View.GONE
                    setAccentColor(accent)
                    contentDescription = getString(R.string.audio_now_playing)
                }
            addView(
                playingIndicator,
                LinearLayout.LayoutParams(dp(22), dp(30)).apply {
                    marginEnd = dp(10)
                }
            )

            val texts = LinearLayout(this@AudioLibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                id = View.generateViewId()
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // Le titre occupe toute la largeur disponible. Les badges techniques sont placés
            // sur la ligne basse afin qu'un titre long ne soit plus tronqué prématurément.
            val titleLine = LinearLayout(this@AudioLibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                id = View.generateViewId()
            }
            texts.addView(titleLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            titleLine.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(textMain)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            texts.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(textMuted)
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })

            val bottomLine = LinearLayout(this@AudioLibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                id = View.generateViewId()
                setPadding(0, dp(3), 0, 0)
            }
            texts.addView(bottomLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bottomLine.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                setTextColor(Color.argb(190, 255, 255, 255))
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bottomLine.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                visibility = View.GONE
                includeFontPadding = false
                textSize = 10f
                maxLines = 1
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            bottomLine.addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId()
                visibility = View.GONE
                includeFontPadding = false
                textSize = 10f
                maxLines = 1
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })

            addView(ImageButton(this@AudioLibraryActivity).apply {
                background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_action_button)
                setImageResource(R.drawable.ic_play)
                imageTintList = ColorStateList.valueOf(accent)
                contentDescription = getString(R.string.action_play_pause)
                setPadding(dp(9))
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(6) })

            addView(ImageButton(this@AudioLibraryActivity).apply {
                background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_action_button)
                setImageResource(R.drawable.ic_more_vert)
                imageTintList = ColorStateList.valueOf(textMuted)
                setPadding(dp(8))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        }

        private inner class TextHolder(view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(row: LibraryRow.Status) {
                (itemView as TextView).text = if (row.detail.isBlank()) row.text else "${row.text}\n${row.detail}"
            }
        }

        private inner class MediaHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            val cover = view.getChildAt(0) as ImageView
            private val texts = view.getChildAt(1) as LinearLayout
            private val title = texts.getChildAt(0) as TextView
            private val sub = texts.getChildAt(1) as TextView
            private val meta = texts.getChildAt(2) as TextView
            private val playPause = view.getChildAt(2) as ImageButton
            private val action = view.getChildAt(3) as ImageButton
            private var boundTrack: LibraryTrack? = null

            fun bindTrack(track: LibraryTrack) {
                boundTrack = track
                bindMetadataOnly(track)
                itemView.setOnClickListener { playFrom(track) }
                playPause.visibility = View.VISIBLE
                playPause.setOnClickListener { toggleTrackPlayback(track) }
                action.visibility = View.VISIBLE
                action.setImageResource(R.drawable.ic_more_vert)
                action.imageTintList = ColorStateList.valueOf(textMuted)
                action.contentDescription = getString(R.string.audio_track_more_options)
                action.setOnClickListener { showTrackOverflowActions(track) }
                bindPlayingState(track)
                bindArtwork(cover, track)
            }

            fun bindArtworkOnly(track: LibraryTrack) {
                bindArtwork(cover, track)
            }

            fun bindMetadataOnly(track: LibraryTrack) {
                boundTrack = track
                title.text = track.title
                sub.text = listOf(track.artist, track.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                sub.visibility = if (sub.text.isBlank()) View.GONE else View.VISIBLE
                meta.text = listOf(
                    AudioLibraryHeuristics.containerLabel(track),
                    AudioLibraryHeuristics.formatDuration(track.durationMs)
                        .takeIf { track.durationMs > 0L },
                    track.sourceLabel
                ).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
            }

            fun bindPlayingState(track: LibraryTrack) {
                boundTrack = track
                val playing = isTrackCurrentlyPlaying(track)
                playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                playPause.imageTintList = ColorStateList.valueOf(if (playing) accent else textMain)
                playPause.contentDescription = getString(R.string.action_play_pause)
            }

            fun onAttachedToWindow() {
                boundTrack?.let(::bindPlayingState)
            }

            fun recycle() {
                boundTrack = null
                playPause.setOnClickListener(null)
            }
        }

        private inner class AlbumTileHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            val cover = view.getChildAt(0) as ImageView
            private val title = view.getChildAt(1) as TextView
            private val sub = view.getChildAt(2) as TextView
            private val meta = view.getChildAt(3) as TextView
            fun bind(album: LibraryAlbum) {
                bindMetadataOnly(album)
                bindArtwork(
                    cover,
                    album.tracks.firstOrNull()?.copy(artworkPath = album.artworkPath) ?: return,
                    albumKey = album.key
                )
            }

            fun bindMetadataOnly(album: LibraryAlbum) {
                title.text = albumCardPrimaryText(album)
                title.visibility = if (title.text.isBlank()) View.GONE else View.VISIBLE
                sub.text = albumCardSecondaryText(album)
                sub.visibility = if (sub.text.isBlank()) View.GONE else View.VISIBLE
                meta.text = albumTrackCountText(album)
                itemView.setOnClickListener { openAlbumDetailView(album) }
                cover.setOnClickListener { openAlbumDetailView(album) }
                cover.setOnLongClickListener {
                    showAlbumCoverActions(album, albumPlaybackTracks(album))
                    true
                }
            }
        }

        private inner class TrackMetaHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            private val playingIndicator =
                view.getChildAt(0) as fr.retrospare.blazeplayer.widget.MiniEqualizerView
            private val texts = view.getChildAt(1) as LinearLayout
            private val titleLine = texts.getChildAt(0) as LinearLayout
            private val title = titleLine.getChildAt(0) as TextView
            private val artist = texts.getChildAt(1) as TextView
            private val bottomLine = texts.getChildAt(2) as LinearLayout
            private val meta = bottomLine.getChildAt(0) as TextView
            private val codec = bottomLine.getChildAt(1) as TextView
            private val quality = bottomLine.getChildAt(2) as TextView
            private val playPause = view.getChildAt(2) as ImageButton
            private val action = view.getChildAt(3) as ImageButton
            private var boundTrack: LibraryTrack? = null

            fun bind(track: LibraryTrack, index: Int) {
                boundTrack = track
                bindMetadataOnly(track, index)
                itemView.setOnClickListener { playFrom(track) }
                playPause.visibility = View.VISIBLE
                playPause.setOnClickListener { toggleTrackPlayback(track) }
                action.visibility = View.VISIBLE
                action.contentDescription = getString(R.string.audio_track_more_options)
                action.setOnClickListener { showTrackOverflowActions(track) }
                bindPlayingState(track)
            }

            fun bindMetadataOnly(track: LibraryTrack, index: Int) {
                boundTrack = track
                val number = track.trackNo.takeIf { it > 0 } ?: index
                title.text = "%02d. %s".format(
                    number.coerceAtMost(99),
                    track.title.ifBlank {
                        AudioLibraryHeuristics.inferTitleFromName(
                            AudioLibraryHeuristics.fileNameFromPath(track.path)
                        )
                    }
                )
                val container = AudioLibraryHeuristics.containerLabel(track)
                AudioQualityBadgeBinder.bind(
                    codecView = codec,
                    qualityView = quality,
                    path = track.path,
                    originalName = AudioLibraryHeuristics.fileNameFromPath(track.path),
                    fallbackExtension = container,
                    knownDurationMs = track.durationMs,
                    knownBitrate = track.bitrate,
                    knownSizeBytes = track.sizeBytes
                )
                artist.text = track.artist
                artist.visibility = if (track.artist.isBlank()) View.GONE else View.VISIBLE
                val details = buildList {
                    track.durationMs.takeIf { it > 0L }
                        ?.let(AudioLibraryHeuristics::formatDuration)
                        ?.let { add(it) }
                    AudioLibraryHeuristics.discNumberFromPath(track.path)
                        .takeIf { it > 1 }
                        ?.let { add("Disque $it") }
                }
                meta.text = details.joinToString(" · ")
                meta.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
            }

            fun bindPlayingState(track: LibraryTrack) {
                boundTrack = track
                val playing = isTrackCurrentlyPlaying(track)
                playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                playPause.imageTintList = ColorStateList.valueOf(if (playing) accent else textMain)
                playPause.contentDescription = getString(R.string.action_play_pause)
                if (playing) {
                    playingIndicator.setAccentColor(accent)
                    playingIndicator.start()
                } else {
                    playingIndicator.stop()
                }
            }

            fun onAttachedToWindow() {
                val track = boundTrack ?: return
                bindPlayingState(track)
                if (isTrackCurrentlyPlaying(track)) playingIndicator.ensureAnimationRunning()
            }

            fun recycle() {
                boundTrack = null
                playPause.setOnClickListener(null)
                playingIndicator.stop()
            }
        }

        private inner class ArtistHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            val photo = view.getChildAt(0) as ImageView
            private val title = view.getChildAt(1) as TextView
            private val albumCount = view.getChildAt(2) as TextView

            fun bind(artist: LibraryArtist) {
                bindMetadataOnly(artist)
                bindArtistArtwork(photo, artist, force = false)
            }

            fun bindMetadataOnly(artist: LibraryArtist) {
                title.text = artist.name
                title.visibility = if (artist.name.isBlank()) View.GONE else View.VISIBLE
                albumCount.text = resources.getQuantityString(
                    R.plurals.audio_album_count_compact,
                    artist.albums,
                    artist.albums
                )
                itemView.setOnClickListener { openArtist(artist) }
                photo.setOnClickListener { openArtist(artist) }
            }

            fun bindArtworkOnly(artist: LibraryArtist) {
                bindArtistArtwork(photo, artist, force = true)
            }
        }

        private inner class PlaylistHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            private val avatar = view.getChildAt(0) as TextView
            private val texts = view.getChildAt(1) as LinearLayout
            private val title = texts.getChildAt(0) as TextView
            private val sub = texts.getChildAt(1) as TextView
            private val meta = texts.getChildAt(2) as TextView
            private val action = view.getChildAt(2) as ImageButton
            fun bind(playlist: LibraryPlaylist) {
                avatar.text = if (playlist.isParty) "\uD83D\uDC65" else "\u266B"
                title.text = playlist.title
                sub.text = playlist.subtitle.ifBlank { resources.getQuantityString(R.plurals.audio_track_count_compact, 0, 0) }
                meta.text = if (playlist.tracks.isEmpty()) getString(R.string.playlist_empty) else getString(R.string.audio_add_playlist_queue)
                itemView.alpha = if (playlist.tracks.isEmpty()) 0.55f else 1f
                action.visibility = View.VISIBLE
                action.setImageResource(if (playlist.isParty) R.drawable.ic_group_people else R.drawable.ic_layout_list)
                action.imageTintList = ColorStateList.valueOf(if (playlist.tracks.isEmpty()) textMuted else accent)
                action.setOnClickListener { showPlaylistActions(playlist) }
                itemView.setOnClickListener { showPlaylistActions(playlist) }
            }
        }

        private fun bindArtistArtwork(view: ImageView, artist: LibraryArtist, force: Boolean) {
            val requestKey = "artist_tile:${AudioLibraryHeuristics.normalize(artist.name)}"
            val sameRequest = view.tag == requestKey
            if (!force && sameRequest && appliedArtistImages[view] == requestKey && view.drawable != null) {
                return
            }
            if (!sameRequest || force) {
                boundArtworkJobs.remove(view)
                boundArtistImageJobs.remove(view)
                appliedArtistImages.remove(view)
            }
            view.tag = requestKey
            view.clearColorFilter()
            view.setPadding(0)

            val ready = ArtistImageRepository.peekMemory(artist.name)
            if (ready != null) {
                val cacheKey = "${ready.localPath}:tile:${java.io.File(ready.localPath).lastModified()}:${java.io.File(ready.localPath).length()}"
                artistBitmapCache.get(cacheKey)?.let { bitmap ->
                    if (view.tag == requestKey) {
                        appliedArtistImages[view] = requestKey
                        view.setImageBitmap(bitmap)
                    }
                    return
                }
                if (!sameRequest || view.drawable == null) view.setImageResource(R.drawable.ic_audio)
                val decodeJob = lifecycleScope.launch(artworkDispatcher) {
                    val bitmap = cachedArtistBitmap(ready.localPath, 900)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed && bitmap != null && view.tag == requestKey) {
                            appliedArtistImages[view] = requestKey
                            view.setImageBitmap(bitmap)
                        }
                    }
                }
                boundArtistImageJobs[view] = decodeJob
                return
            }

            if (!sameRequest || view.drawable == null) view.setImageResource(R.drawable.ic_audio)
            // La pochette n'est qu'un fallback provisoire. Elle n'est chargée que tant qu'aucune
            // vraie photo n'est connue et ne pourra pas repasser par-dessus une photo résolue.
            artist.tracks.firstOrNull()?.let { representative ->
                val fallbackTrack = representative.copy(
                    artworkPath = AudioLibraryHeuristics.bestArtworkPath(artist.tracks)
                )
                AudioArtworkResolver.memoryCachedBitmap(
                    fallbackTrack.path,
                    viewModel.primaryArtworkPathFor(fallbackTrack)
                )?.let { bitmap ->
                    if (view.tag == requestKey && ArtistImageRepository.peekMemory(artist.name) == null) {
                        view.setImageBitmap(bitmap)
                    }
                } ?: run {
                    val fallbackJob = lifecycleScope.launch(artworkDispatcher) {
                        delay(120L)
                        val bitmap = viewModel.loadArtworkForBinding(fallbackTrack)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed && bitmap != null &&
                                view.tag == requestKey && appliedArtistImages[view] != requestKey &&
                                ArtistImageRepository.peekMemory(artist.name) == null
                            ) {
                                view.setImageBitmap(bitmap)
                            }
                        }
                    }
                    boundArtworkJobs[view] = fallbackJob
                }
            }

            val artistJob = lifecycleScope.launch {
                val result = ArtistImageRepository.resolve(
                    this@AudioLibraryActivity,
                    artist.name,
                    artist.tracks
                ) ?: return@launch
                val bitmap = withContext(artworkDispatcher) {
                    cachedArtistBitmap(result.localPath, 900)
                } ?: return@launch
                if (isFinishing || isDestroyed || view.tag != requestKey) return@launch
                appliedArtistImages[view] = requestKey
                boundArtworkJobs.remove(view)?.cancel()
                view.clearColorFilter()
                view.setPadding(0)
                view.setImageBitmap(bitmap)
            }
            boundArtistImageJobs[view] = artistJob
        }

        private fun bindArtwork(view: ImageView, track: LibraryTrack, albumKey: String? = null) {
            val primary = viewModel.primaryArtworkPathFor(track)
            val fallback = viewModel.fallbackArtworkPathFor(track)
            val requestKey = "${albumKey.orEmpty()}|$primary|${fallback.orEmpty()}"
            val sameRequest = view.tag == requestKey
            val activeJob = boundArtworkJobs[view]
            if (!sameRequest) boundArtworkJobs.remove(view)
            view.tag = requestKey

            // Chemin réellement instantané après redémarrage : l'atlas a été décodé une seule fois
            // dans Application.onCreate(), donc aucun accès Room/disque n'est nécessaire ici.
            albumKey?.let { key -> AudioAlbumArtworkAtlas.peek(key, primary) }?.let { bitmap ->
                boundArtworkBitmapCache.put(requestKey, bitmap)
                if (view.tag == requestKey) view.setImageBitmap(bitmap)
                return
            }

            boundArtworkBitmapCache.get(requestKey)?.let { bitmap ->
                if (view.tag == requestKey) view.setImageBitmap(bitmap)
                return
            }
            AudioArtworkResolver.memoryCachedBitmap(track.path, primary)?.let { bitmap ->
                boundArtworkBitmapCache.put(requestKey, bitmap)
                if (view.tag == requestKey) view.setImageBitmap(bitmap)
                return
            }

            if (sameRequest && activeJob?.isActive == true) return
            if (!sameRequest || view.drawable == null) view.setImageResource(R.drawable.ic_audio)
            val job = lifecycleScope.launch(artworkDispatcher) {
                val bitmap = viewModel.loadArtworkForBinding(track)
                if (bitmap != null) boundArtworkBitmapCache.put(requestKey, bitmap)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && bitmap != null && view.tag == requestKey) {
                        view.setImageBitmap(bitmap)
                    }
                }
            }
            boundArtworkJobs[view] = job
            job.invokeOnCompletion { boundArtworkJobs.remove(view, job) }
        }
    }

    private object RowDiffCallback : DiffUtil.ItemCallback<LibraryRow>() {
        override fun areItemsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean =
            oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean =
            oldItem == newItem

        override fun getChangePayload(oldItem: LibraryRow, newItem: LibraryRow): Any? {
            return when {
                oldItem is LibraryRow.TrackItem && newItem is LibraryRow.TrackItem &&
                    oldItem.track.path == newItem.track.path &&
                    oldItem.track.artworkPath != newItem.track.artworkPath -> PAYLOAD_TRACK_ARTWORK
                oldItem is LibraryRow.TrackItem && newItem is LibraryRow.TrackItem &&
                    oldItem.track.path == newItem.track.path -> PAYLOAD_TRACK_METADATA
                oldItem is LibraryRow.AlbumTrackItem && newItem is LibraryRow.AlbumTrackItem &&
                    oldItem.track.path == newItem.track.path -> PAYLOAD_TRACK_METADATA
                oldItem is LibraryRow.AlbumTile && newItem is LibraryRow.AlbumTile &&
                    oldItem.album.key == newItem.album.key &&
                    oldItem.album.artworkPath == newItem.album.artworkPath -> PAYLOAD_ALBUM_METADATA
                oldItem is LibraryRow.ArtistItem && newItem is LibraryRow.ArtistItem &&
                    AudioLibraryHeuristics.normalize(oldItem.artist.name) ==
                        AudioLibraryHeuristics.normalize(newItem.artist.name) -> PAYLOAD_ARTIST_METADATA
                else -> null
            }
        }
    }
}
