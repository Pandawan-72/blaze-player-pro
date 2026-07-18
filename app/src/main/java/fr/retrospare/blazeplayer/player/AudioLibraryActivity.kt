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
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Bibliothèque audio : écran fin, branché sur [AudioLibraryViewModel] (état + logique métier)
 * et [AudioLibraryRepository] (Room + scan). Cette Activity ne garde que ce qui est réellement
 * spécifique à l'écran : construction des vues (pixel perfect, inchangée), mini player, dialogues,
 * couleur dynamique de fond, et le binding paresseux des covers visibles.
 */
@AndroidEntryPoint
class AudioLibraryActivity : AppCompatActivity() {
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository


    private val viewModel: AudioLibraryViewModel by viewModels()

    // Un seul dispatcher restant côté Activity : le chargement paresseux des covers pour les
    // lignes réellement visibles (bind-time), distinct de l'enrichissement en masse du ViewModel.
    private val artworkDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) } catch (_: Exception) {}
            runnable.run()
        }.apply { name = "BlazeLibraryCover"; isDaemon = true; priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()

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
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniArtwork: fr.retrospare.blazeplayer.ui.RoundedImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniTime: TextView
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniSpinner: ImageView
    private lateinit var albumStickyHeader: LinearLayout

    private var knownWatchedFolders: Map<String, AudioProSettings.WatchedFolder> = emptyMap()
    /** Dernier état reçu du ViewModel — sert aux fonctions appelées hors du flux de rendu
     *  (ex. handleTopBack) qui ont besoin de savoir si un détail album est ouvert. */
    private var currentState: LibraryUiState = LibraryUiState()
    private var miniArtworkJob: Job? = null
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
    private var miniTicker = Handler(Looper.getMainLooper())
    private val miniTick = object : Runnable {
        override fun run() {
            updateMiniTime()
            miniTicker.postDelayed(this, 1000L)
        }
    }
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

        /**
         * Appelé depuis l'écran player avant d'ouvrir la bibliothèque. Ne fait plus que
         * pré-chauffer la connexion Room (page cache SQLite) : l'ancien cache mémoire
         * inter-instances a été retiré, l'index composite Room rend désormais la lecture
         * assez rapide pour ne plus en avoir besoin.
         */
        fun warmUpForFastOpen(context: Context) {
            val appContext = context.applicationContext
            Thread {
                runCatching { runBlocking { AudioLibraryRoomStore.loadActive(appContext, 1) } }
            }.apply {
                name = "BlazeLibraryWarmup"
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // La bibliothèque est entièrement reconstruite depuis Room. On ignore le
        // savedInstanceState Android pour éviter qu'un ancien état de vue soit restauré après
        // suppression/vidage de cache.
        super.onCreate(null)
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
        refreshMiniPlayer()
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
        updateSectionVisibility(state)
        val rows = if (state.isInitialLoad) listOf(LibraryRow.Status(getString(R.string.audio_loading_in_progress))) else state.rows

        // ListAdapter applique son diff de manière asynchrone. Le LayoutManager doit être changé
        // uniquement une fois la nouvelle liste réellement active : sinon le GridLayoutManager
        // peut mesurer les anciennes lignes du détail avec les règles de span de la grille albums,
        // puis conserver cette géométrie incohérente jusqu'au premier scroll.
        adapter.submitList(rows) {
            if (renderGeneration != libraryRenderGeneration || isFinishing || isDestroyed) return@submitList
            configureListLayoutForCurrentView(state)
            restoreAlbumGridAnchorIfNeeded(state, renderGeneration)
        }
    }

    private fun updateSectionVisibility(state: LibraryUiState) {
        val detailAlbum = state.openedAlbum
        val isHome = state.tab == LibraryTab.ALBUMS && detailAlbum == null
        renderStickyAlbumHeader(detailAlbum)

        tvLibraryTitle.text = getString(R.string.audio_library_title)
        tvSubtitle.text = getString(R.string.audio_library_subtitle)
        tvSubtitle.visibility = View.VISIBLE

        // L'accueil est désormais une grille d'albums unique. Les anciens filtres
        // Titres / Albums / Artistes sont volontairement retirés.
        tabContainer.visibility = View.GONE

        // Dossiers surveillés et statistiques : uniquement sur l'accueil albums.
        val homeOnlyVisibility = if (isHome) View.VISIBLE else View.GONE
        listOf(R.id.watchedSummaryCard, R.id.libraryStats).forEach { id -> findViewById<View>(id)?.visibility = homeOnlyVisibility }
        findViewById<View>(R.id.dividerLibraryStats)?.visibility = View.GONE

        listOf(R.id.resumeSectionHeader, R.id.resumeScroll, R.id.dividerLibraryResume).forEach { id -> findViewById<View>(id)?.visibility = View.GONE }

        findViewById<View>(R.id.dividerLibraryAlbums)?.visibility = View.GONE
        findViewById<View>(R.id.albumsSectionHeader)?.visibility = if (isHome) View.VISIBLE else View.GONE
        findViewById<View>(R.id.albumsScroll)?.visibility = View.GONE
        findViewById<TextView>(R.id.tvAlbumsTitle)?.text = getString(R.string.audio_recent_albums)
        albumViewModeContainer.visibility = View.GONE

        findViewById<View>(R.id.dividerLibraryTracks)?.visibility = View.GONE
        findViewById<View>(R.id.tracksContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.tracksSectionHeader)?.visibility = View.GONE
        findViewById<TextView>(R.id.tvTracksTitle)?.apply {
            visibility = if (detailAlbum != null || isHome) View.INVISIBLE else View.VISIBLE
            text = when (state.tab) {
                LibraryTab.ARTISTS -> getString(R.string.audio_tab_artists)
                LibraryTab.TITLES -> getString(R.string.audio_tab_titles)
                LibraryTab.PLAYLISTS -> getString(R.string.audio_tab_playlists)
                else -> ""
            }
        }
        sortButton.visibility = View.GONE
    }

    private fun configureListLayoutForCurrentView(state: LibraryUiState) {
        val isAlbumDetail = state.openedAlbum != null
        val shouldUseGrid = state.tab == LibraryTab.ALBUMS && !isAlbumDetail
        val spanCount = 4
        val current = recyclerView.layoutManager
        if (shouldUseGrid) {
            val currentGrid = current as? GridLayoutManager
            if (currentGrid == null || currentGrid.spanCount != spanCount) {
                recyclerView.layoutManager = GridLayoutManager(this, spanCount).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int =
                            if (adapter.getItemViewType(position) == VIEW_TYPE_ALBUM_TILE) 1 else spanCount
                    }
                }
            }
        } else if (current is GridLayoutManager) {
            recyclerView.layoutManager = LinearLayoutManager(this)
        }
        recyclerView.clipToPadding = false
        recyclerView.setPadding(0, if (isAlbumDetail) dp(2) else if (shouldUseGrid) dp(8) else 0, 0, if (shouldUseGrid) dp(28) else dp(24))
        recyclerView.invalidateItemDecorations()
        recyclerView.requestLayout()
    }

    // -----------------------------------------------------------------
    // Construction de la coquille UI (inchangée, pixel perfect).
    // -----------------------------------------------------------------

    private fun buildUi() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(R.layout.activity_blaze_audio_library)
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
        miniPlayer = findViewById(R.id.libraryMiniPlayer)
        miniArtwork = findViewById(R.id.ivLibraryMiniArtwork)
        miniTitle = findViewById(R.id.tvLibraryMiniTitle)
        miniArtist = findViewById(R.id.tvLibraryMiniArtist)
        miniTime = findViewById(R.id.tvLibraryMiniTime)
        miniPlayPause = findViewById(R.id.btnLibraryMiniPlayPause)
        miniSpinner = findViewById(R.id.libraryMiniPlayingIndicator)
        albumStickyHeader = findViewById(R.id.albumStickyHeader)
        renderEmptyMiniPlayer()
        applyAccentToStaticChrome()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { handleTopBack() }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener { showSearchDialog() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, AudioProSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnManageWatched).setOnClickListener { openWatchedFoldersBrowser() }
        miniPlayer.setOnClickListener { openFullAudioPlayer() }
        findViewById<ImageButton>(R.id.btnLibraryMiniPrev).setOnClickListener { controller?.seekToPreviousMediaItem() }
        miniPlayPause.setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        findViewById<ImageButton>(R.id.btnLibraryMiniNext).setOnClickListener { controller?.seekToNextMediaItem() }

        // Pas de barre de filtres : la bibliothèque s'ouvre directement sur la grille albums.
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
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playbackCriticalSnapshot = isPlaying
                        refreshMiniPlayer()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        refreshPlaybackCriticalSnapshot(controller)
                        refreshMiniPlayer()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        refreshPlaybackCriticalSnapshot(controller)
                        refreshMiniPlayer()
                    }
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        // Même stratégie que le mini-player global : rafraîchir la vue uniquement
                        // quand les métadonnées utiles changent, pas à chaque événement Media3.
                        refreshMiniPlayer()
                    }
                })
                refreshMiniPlayer()
                miniTicker.post(miniTick)
            }.onFailure { renderEmptyMiniPlayer() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun manualRefresh() {
        val started = viewModel.refresh(manual = true, isPlaybackCritical = ::isPlaybackCritical)
        if (!started) return
        AudioProSettings.consumeLibraryRefreshPending(this)
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
    }

    private fun isPlaybackCritical(): Boolean = playbackCriticalSnapshot

    // -----------------------------------------------------------------
    // Navigation : tabs, recherche, détail album.
    // -----------------------------------------------------------------

    private fun handleTopBack() {
        when {
            currentState.openedAlbum != null -> returnToLibraryHome()
            else -> finish()
        }
    }

    private fun returnToLibraryHome() {
        restoreAlbumGridAfterCommit = albumGridAnchorStableId != null
        viewModel.closeAlbumDetail()
        viewModel.setTab(LibraryTab.ALBUMS)
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putString(KEY_TAB, LibraryTab.ALBUMS.name).apply()
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
        val isAlbumGrid = state.tab == LibraryTab.ALBUMS && state.openedAlbum == null
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

    private fun closeAlbumDetailView() = returnToLibraryHome()

    private fun openArtist(artist: LibraryArtist) {
        // Un artiste ouvre désormais la grille d'albums filtrée, jamais l'ancienne vue Titres.
        viewModel.setSearchQuery(artist.name)
        viewModel.setTab(LibraryTab.ALBUMS)
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
        val pendingRefresh = AudioProSettings.consumeLibraryRefreshPending(this)
        knownWatchedFolders = current

        if (removed.isNotEmpty()) {
            viewModel.onFolderRemoved(removed.map { AudioWatchedLibraryCache.key(it) }.toSet())
            Toast.makeText(this, getString(R.string.audio_watched_folder_removed), Toast.LENGTH_SHORT).show()
        }
        if (removed.isNotEmpty() || added.isNotEmpty() || pendingRefresh) updateWatchedSummary()
        if ((added.isNotEmpty() || pendingRefresh) && current.isNotEmpty()) {
            requestAutomaticLibraryRefresh(force = true)
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
        if (force) AudioProSettings.consumeLibraryRefreshPending(this)
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
        appendSingleTrackToAudioQueueAndPlay(track)
        // Lecture immédiate sans navigation forcée : l'utilisateur reste dans la bibliothèque.
        miniPlayer.postDelayed({ refreshMiniPlayer() }, 250L)
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

    private fun playPlaylist(playlist: LibraryPlaylist) {
        if (playlist.tracks.isEmpty()) {
            Toast.makeText(this, R.string.playlist_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AudioRepository.save(this, playlist.tracks.map { PlaylistItem(it.path, it.title, it.artworkPath) }, 0)
        openFullAudioPlayer()
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

    private fun showAlbumPlaylistChoiceDialog(album: LibraryAlbum, tracks: List<LibraryTrack>) {
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
            this,
            PlaylistCategory.AUDIO,
            tracks.distinctBy { it.path }.map { it.toPlaylistRef() }
        ) {
            if (viewModel.uiState.value.tab == LibraryTab.PLAYLISTS) viewModel.setTab(LibraryTab.PLAYLISTS)
        }
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

    private fun refreshMiniPlayer() {
        val c = controller
        if (c == null || c.mediaItemCount <= 0 || c.currentMediaItem == null) {
            renderEmptyMiniPlayer()
            return
        }
        miniPlayer.visibility = View.VISIBLE
        setMiniControlsEnabled(true)
        val item = c.currentMediaItem
        val meta = item?.mediaMetadata
        val path = originalPathOf(item) ?: item?.mediaId.orEmpty()
        val cached = AudioMediaCache.getCachedMetadata(this, path)
        val originalName = meta?.extras?.getString("blaze_original_name")
            .orEmpty().ifBlank { AudioLibraryHeuristics.fileNameFromPath(path) }
        val folderMeta = AudioLibraryHeuristics.folderMetadata(path, originalName)
        miniTitle.text = folderMeta.title.ifBlank {
            cached?.title.orEmpty().ifBlank { getString(R.string.unknown_title) }
        }
        currentMiniArtistTag = folderMeta.artist
        currentMiniAlbumTag = folderMeta.album
        renderLibraryMiniTags()
        miniPlayPause.setImageResource(if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_white)
        if (c.isPlaying) startMiniSpin() else stopMiniSpin(keepVisible = false)
        val memoryArtworkAvailable = AudioArtworkResolver.memoryCachedBitmap(path) != null
        val shouldBindArtwork = path != currentMiniArtworkPath ||
            (!currentMiniArtworkHasImage && miniArtworkJob?.isActive != true && memoryArtworkAvailable)
        if (shouldBindArtwork || (path.isNotBlank() && !currentMiniArtworkHasImage && miniArtworkJob?.isActive != true)) {
            bindMiniArtwork(path)
        }
        updateMiniTime()
    }

    private fun renderEmptyMiniPlayer() {
        if (!::miniPlayer.isInitialized) return
        miniArtworkJob?.cancel()
        currentMiniArtworkPath = ""
        currentMiniArtworkHasImage = false
        miniPlayer.visibility = View.VISIBLE
        miniTitle.text = getString(R.string.audio_no_current_track)
        currentMiniArtistTag = ""
        currentMiniAlbumTag = ""
        currentMiniAccentColor = AudioDynamicColor.DEFAULT_ACCENT
        miniArtist.text = ""
        val zero = getString(R.string.zero_time)
        miniTime.text = "$zero / $zero"
        miniArtwork.setImageResource(R.drawable.ic_audio)
        applyFullscreenLibraryBackground(resolveInitialLibraryBackgroundColor())
        miniPlayPause.setImageResource(R.drawable.ic_play_white)
        stopMiniSpin(keepVisible = false)
        setMiniControlsEnabled(false)
    }

    private fun setMiniControlsEnabled(enabled: Boolean) {
        listOf(
            findViewById<ImageButton>(R.id.btnLibraryMiniPrev),
            miniPlayPause,
            findViewById<ImageButton>(R.id.btnLibraryMiniNext)
        ).forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.38f
        }
    }

    private fun bindMiniArtwork(path: String) {
        val pathChanged = currentMiniArtworkPath != path
        if (!pathChanged && miniArtworkJob?.isActive == true) return
        currentMiniArtworkPath = path
        miniArtworkJob?.cancel()
        val memoryBitmap = AudioArtworkResolver.memoryCachedBitmap(path)
        if (memoryBitmap != null) {
            currentMiniArtworkHasImage = true
            miniArtwork.setImageBitmap(memoryBitmap)
            applyLibraryDynamicBackgroundFromBitmap(memoryBitmap)
        } else if (pathChanged) {
            // Ne remplace le visuel par le placeholder qu'au vrai changement de piste. Les simples
            // événements de lecture (buffering, play/pause, timeline) conservent le bitmap courant.
            currentMiniArtworkHasImage = false
            miniArtwork.setImageResource(R.drawable.ic_audio)
            applyFullscreenLibraryBackground(resolveInitialLibraryBackgroundColor())
        }
        miniArtworkJob = lifecycleScope.launch(artworkDispatcher) {
            val bmp = memoryBitmap
                ?: AudioArtworkResolver.cachedBitmap(this@AudioLibraryActivity, path)
                ?: runCatching {
                    AudioArtworkResolver.resolveBitmap(this@AudioLibraryActivity, path)
                }.getOrNull()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (currentMiniArtworkPath == path && bmp != null) {
                    currentMiniArtworkHasImage = true
                    miniArtwork.setImageBitmap(bmp)
                    applyLibraryDynamicBackgroundFromBitmap(bmp)
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
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
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

    private fun updateMiniTime() {
        val c = controller ?: return
        if (miniPlayer.visibility != View.VISIBLE || c.currentMediaItem == null) return
        val duration = c.duration.takeIf { it > 0 } ?: 0L
        val position = c.currentPosition.coerceAtLeast(0L)
        miniTime.text = if (duration > 0L) {
            "${AudioLibraryHeuristics.formatDuration(position)} / ${AudioLibraryHeuristics.formatDuration(duration)}"
        } else {
            AudioLibraryHeuristics.formatDuration(position)
        }
    }

    private fun openFullAudioPlayer() {
        startActivity(Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
            putExtra("openBlazeAudio", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun startMiniSpin() {
        findViewById<View>(R.id.libraryMiniEqOverlay)?.visibility = View.VISIBLE
        miniSpinner.visibility = View.VISIBLE
        if (miniSpinAnimator?.isRunning == true) return
        miniSpinAnimator = ObjectAnimator.ofFloat(miniSpinner, View.ROTATION, 0f, 360f).apply {
            duration = 2200L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
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

    private fun renderStickyAlbumHeader(album: LibraryAlbum?) {
        if (!::albumStickyHeader.isInitialized) return
        albumStickyHeader.removeAllViews()
        if (album == null) {
            albumStickyHeader.visibility = View.GONE
            return
        }
        albumStickyHeader.visibility = View.VISIBLE
        val ordered = albumPlaybackTracks(album)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable(Color.argb(30, 255, 255, 255), dp(22), stroke = Color.argb(18, 255, 255, 255))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        albumStickyHeader.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val cover = fr.retrospare.blazeplayer.ui.RoundedImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            radiusDp = 16f
            setImageResource(R.drawable.ic_audio)
            background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_cover_placeholder)
        }
        card.addView(cover, LinearLayout.LayoutParams(dp(132), dp(132)).apply { rightMargin = dp(14) })

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        panel.addView(TextView(this).apply {
            setTextColor(textMain)
            textSize = 20f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            text = album.title
            visibility = if (album.title.isBlank()) View.GONE else View.VISIBLE
        })
        panel.addView(TextView(this).apply {
            val taggedArtist = albumDisplayArtist(album)
            setTextColor(accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
            text = taggedArtist
            visibility = if (taggedArtist.isBlank()) View.GONE else View.VISIBLE
        })
        panel.addView(TextView(this).apply {
            setTextColor(textMuted)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(10))
            text = albumMetaLine(ordered)
        })
        panel.addView(stickyAlbumActionButton(getString(R.string.audio_play_album), R.drawable.ic_play_white, primary = true).apply {
            setOnClickListener { playAlbumNow(ordered) }
        })
        panel.addView(stickyAlbumActionButton(getString(R.string.audio_add_album_queue), R.drawable.ic_add, primary = false).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            setOnClickListener { appendTracksToAudioQueue(ordered) }
        })

        val representative = album.tracks.firstOrNull()?.copy(artworkPath = album.artworkPath)
        if (representative != null) bindOriginalArtwork(cover, representative)

        val stickyDivider = View(this).apply {
            background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_section_divider)
        }
        albumStickyHeader.addView(stickyDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
    }

    private fun stickyAlbumActionButton(label: String, icon: Int, primary: Boolean): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.015f
        compoundDrawablePadding = dp(7)
        setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
        setPadding(dp(12), 0, dp(12), 0)

        val foreground = when {
            primary && isDarkColor(accent) -> Color.WHITE
            primary -> Color.rgb(8, 15, 24)
            else -> ContextCompat.getColor(this@AudioLibraryActivity, R.color.audio_library_accent)
        }
        setTextColor(foreground)
        compoundDrawableTintList = ColorStateList.valueOf(foreground)
        setShadowLayer(
            if (primary) dp(2).toFloat() else dp(1).toFloat(),
            0f,
            dp(1).toFloat(),
            if (primary) Color.argb(105, 0, 0, 0) else Color.argb(80, 0, 0, 0)
        )
        background = premiumAlbumActionBackground(primary)
        elevation = dp(if (primary) 4 else 2).toFloat()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        isClickable = true
        isFocusable = true
        isAllCaps = false

        // Les traductions longues restent toujours entièrement visibles, même sur un petit écran.
        ButtonTextFitter.fit(this, minSp = 8, maxSp = 13)
    }

    private fun premiumAlbumActionBackground(primary: Boolean): RippleDrawable {
        val radius = dp(18).toFloat()
        val glacier = ContextCompat.getColor(this, R.color.audio_library_accent_stroke)
        val start = if (primary) {
            blendColors(Color.WHITE, accent, 0.18f)
        } else {
            Color.rgb(29, 39, 55)
        }
        val end = if (primary) {
            blendColors(Color.BLACK, accent, 0.20f)
        } else {
            Color.rgb(7, 12, 21)
        }
        val stroke = if (primary) {
            blendColors(Color.WHITE, accent, 0.42f)
        } else {
            glacier
        }

        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(48, 255, 255, 255), Color.TRANSPARENT)).apply {
            cornerRadius = radius - dp(1)
        }
        val content = LayerDrawable(arrayOf(base, sheen)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(22))
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val ripple = if (primary) Color.argb(72, 255, 255, 255) else Color.argb(62, 185, 223, 255)
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    private fun installStickyHero() {
        val hero = findViewById<View>(R.id.libraryHero) ?: return
        val container = findViewById<LinearLayout>(R.id.libraryScreenContainer)
        // Ne jamais appeler hero.bringToFront() ici : dans un LinearLayout vertical,
        // bringToFront() déplace réellement la vue en dernière position dans l'ordre des enfants.
        if (container != null && hero.parent === container && container.indexOfChild(hero) != 0) {
            container.removeView(hero)
            container.addView(hero, 0)
        }
        container?.setBackgroundColor(Color.TRANSPARENT)
        hero.setBackgroundColor(Color.TRANSPARENT)
        albumStickyHeader.setBackgroundColor(Color.TRANSPARENT)
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

    private fun albumMetaLine(tracks: List<LibraryTrack>): String {
        val uniqueTracks = tracks.distinctBy { it.path }
        val count = resources.getQuantityString(R.plurals.audio_track_count_compact, uniqueTracks.size, uniqueTracks.size)
        val hasCompleteDuration = uniqueTracks.isNotEmpty() && uniqueTracks.all { it.durationMs > 0L }
        val totalDurationMs = if (hasCompleteDuration) uniqueTracks.sumOf { it.durationMs } else 0L
        val duration = totalDurationMs.takeIf { it > 0L }
            ?.let(AudioLibraryHeuristics::formatDuration)
        return listOfNotNull(count, duration).joinToString(" · ")
    }


    private fun albumCardPrimaryText(album: LibraryAlbum): String = album.title

    private fun albumCardSecondaryText(album: LibraryAlbum): String = albumDisplayArtist(album)

    private fun albumDisplayArtist(album: LibraryAlbum): String = album.artist.trim()

    private fun albumTrackCountText(album: LibraryAlbum): String {
        val tracks = album.tracks.distinctBy { it.path }
        val count = resources.getQuantityString(R.plurals.audio_track_count_compact, tracks.size, tracks.size)
        val hasCompleteDuration = tracks.isNotEmpty() && tracks.all { it.durationMs > 0L }
        val totalDurationMs = if (hasCompleteDuration) tracks.sumOf { it.durationMs } else 0L
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
                prefs.contains(KEY_DYNAMIC_BG) -> prefs.getInt(KEY_DYNAMIC_BG, fallback)
                prefs.contains(KEY_DYNAMIC_ACCENT) -> AudioDynamicColor.backgroundFromAccent(prefs.getInt(KEY_DYNAMIC_ACCENT, AudioDynamicColor.DEFAULT_ACCENT))
                else -> fallback
            }
        }.getOrDefault(fallback)
    }

    private fun applyLibraryDynamicBackgroundFromBitmap(bitmap: android.graphics.Bitmap?) {
        if (!runCatching { AudioProSettings.read(this).dynamicTheme }.getOrDefault(true) || bitmap == null) {
            applyFullscreenLibraryBackground(resolveInitialLibraryBackgroundColor())
            return
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val dynamicAccent = runCatching {
                AudioDynamicColor.accentFromBitmap(bitmap)
            }.getOrDefault(AudioDynamicColor.DEFAULT_ACCENT)
            val dynamicBg = AudioDynamicColor.backgroundFromAccent(dynamicAccent)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    currentMiniAccentColor = dynamicAccent
                    renderLibraryMiniTags()
                    miniSpinner.imageTintList = ColorStateList.valueOf(dynamicAccent)
                    applyFullscreenLibraryBackground(dynamicBg)
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
        applySystemBarColors(libraryBackgroundColor)
        findViewById<View>(R.id.libraryRoot)?.background = buildLibraryBackgroundDrawable(libraryBackgroundColor)
        findViewById<View>(R.id.libraryScreenContainer)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(R.id.libraryGlobalCard)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(R.id.libraryHero)?.setBackgroundColor(Color.TRANSPARENT)
        findViewById<View>(R.id.albumStickyHeader)?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun applyAccentToStaticChrome() {
        listOf(
            findViewById<TextView>(R.id.badgeLibraryPro),
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
        listOf(R.id.btnBack, R.id.btnSearch, R.id.btnSettings).forEach { id ->
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

        init { setHasStableIds(true) }

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
            VIEW_TYPE_ARTIST -> ArtistHolder(rowView(withCover = false))
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

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is MediaHolder) boundArtworkJobs.remove(holder.cover)?.cancel()
            if (holder is AlbumTileHolder) boundArtworkJobs.remove(holder.cover)?.cancel()
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
            addView(ImageButton(this@AudioLibraryActivity).apply {
                background = ContextCompat.getDrawable(this@AudioLibraryActivity, R.drawable.bg_audio_library_action_button)
                setImageResource(R.drawable.ic_play_small)
                imageTintList = ColorStateList.valueOf(accent)
                setPadding(dp(8))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
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
                id = View.generateViewId(); setTextColor(textMain); textSize = 13f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId(); setTextColor(textMuted); textSize = 11f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@AudioLibraryActivity).apply {
                id = View.generateViewId(); setTextColor(accent); textSize = 10f
                typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }

        private fun trackMetaRowView(): LinearLayout = LinearLayout(this@AudioLibraryActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable(Color.argb(26, 255, 255, 255), dp(16), stroke = Color.argb(16, 255, 255, 255))
            setPadding(dp(12), dp(9), dp(10), dp(9))
            layoutParams = RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }

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
            private val action = view.getChildAt(2) as ImageButton
            fun bindTrack(track: LibraryTrack) {
                title.text = track.title
                sub.text = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
                sub.visibility = if (sub.text.isBlank()) View.GONE else View.VISIBLE
                meta.text = listOf(AudioLibraryHeuristics.containerLabel(track), AudioLibraryHeuristics.formatDuration(track.durationMs).takeIf { track.durationMs > 0L }, track.sourceLabel).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                itemView.setOnClickListener { playFrom(track) }
                action.visibility = View.VISIBLE
                action.setImageResource(R.drawable.ic_more_vert)
                action.imageTintList = ColorStateList.valueOf(textMuted)
                action.contentDescription = getString(R.string.audio_track_more_options)
                action.setOnClickListener { showTrackOverflowActions(track) }
                bindArtwork(cover, track)
            }
        }

        private inner class AlbumTileHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            val cover = view.getChildAt(0) as ImageView
            private val title = view.getChildAt(1) as TextView
            private val sub = view.getChildAt(2) as TextView
            private val meta = view.getChildAt(3) as TextView
            fun bind(album: LibraryAlbum) {
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
                bindArtwork(cover, album.tracks.firstOrNull()?.copy(artworkPath = album.artworkPath) ?: return)
            }
        }

        private inner class TrackMetaHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            private val texts = view.getChildAt(0) as LinearLayout
            private val titleLine = texts.getChildAt(0) as LinearLayout
            private val title = titleLine.getChildAt(0) as TextView
            private val artist = texts.getChildAt(1) as TextView
            private val bottomLine = texts.getChildAt(2) as LinearLayout
            private val meta = bottomLine.getChildAt(0) as TextView
            private val codec = bottomLine.getChildAt(1) as TextView
            private val quality = bottomLine.getChildAt(2) as TextView
            private val action = view.getChildAt(1) as ImageButton
            fun bind(track: LibraryTrack, index: Int) {
                val number = track.trackNo.takeIf { it > 0 } ?: index
                title.text = "%02d. %s".format(
                    number.coerceAtMost(99),
                    track.title.ifBlank {
                        AudioLibraryHeuristics.inferTitleFromName(AudioLibraryHeuristics.fileNameFromPath(track.path))
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
                itemView.setOnClickListener { playFrom(track) }
                action.visibility = View.VISIBLE
                action.contentDescription = getString(R.string.audio_track_more_options)
                action.setOnClickListener { showTrackOverflowActions(track) }
            }
        }

        private inner class ArtistHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            private val avatar = view.getChildAt(0) as TextView
            private val texts = view.getChildAt(1) as LinearLayout
            private val title = texts.getChildAt(0) as TextView
            private val sub = texts.getChildAt(1) as TextView
            private val meta = texts.getChildAt(2) as TextView
            private val action = view.getChildAt(2) as ImageButton
            fun bind(artist: LibraryArtist) {
                avatar.text = artist.name.take(1).uppercase(Locale.getDefault())
                title.text = artist.name
                sub.text = resources.getQuantityString(R.plurals.audio_album_count_compact, artist.albums, artist.albums)
                meta.text = resources.getQuantityString(R.plurals.audio_track_count_compact, artist.tracks.size, artist.tracks.size)
                action.visibility = View.VISIBLE
                action.setImageResource(R.drawable.ic_play_small)
                action.imageTintList = ColorStateList.valueOf(accent)
                action.setOnClickListener { openArtist(artist) }
                itemView.setOnClickListener { openArtist(artist) }
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

        private fun bindArtwork(view: ImageView, track: LibraryTrack) {
            boundArtworkJobs.remove(view)?.cancel()
            val primary = viewModel.primaryArtworkPathFor(track)
            val fallback = viewModel.fallbackArtworkPathFor(track)
            val requestKey = "$primary|${fallback.orEmpty()}"
            view.tag = requestKey
            AudioArtworkResolver.memoryCachedBitmap(track.path, primary)?.let {
                if (view.tag == requestKey) view.setImageBitmap(it)
                return
            }
            view.setImageResource(R.drawable.ic_audio)
            val job = lifecycleScope.launch(artworkDispatcher) {
                val bitmap = viewModel.loadArtworkForBinding(track)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed && bitmap != null && view.tag == requestKey) {
                        view.setImageBitmap(bitmap)
                    }
                }
            }
            boundArtworkJobs[view] = job
        }
    }

    private object RowDiffCallback : DiffUtil.ItemCallback<LibraryRow>() {
        override fun areItemsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean = oldItem.stableId == newItem.stableId
        override fun areContentsTheSame(oldItem: LibraryRow, newItem: LibraryRow): Boolean = oldItem == newItem
    }
}
