package fr.retrospare.blazeplayer.player

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import fr.retrospare.blazeplayer.debug.CrashReporter
import fr.retrospare.blazeplayer.databinding.ActivityAudioPlayerBinding
import fr.retrospare.blazeplayer.home.SharedAudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlayerFragment : Fragment() {

    @Inject lateinit var mediaRepository: MediaRepository
    private var _binding: ActivityAudioPlayerBinding? = null
    private val binding get() = _binding!!
    private val sharedVm: SharedAudioViewModel by activityViewModels()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var playlistAdapter: PlaylistAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false
    private var sleepTimerJob: Job? = null
    private var eqManager: EqualizerManager? = null
    private var dancerFrame = 0
    private var currentDynamicBgColor: Int = Color.rgb(10, 12, 14)
    private var currentAccentColor: Int = Color.rgb(63, 215, 143)
    private var bgAnimator: ValueAnimator? = null

    private val dancerFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_2
    )
    private val dancerFFrames = listOf(
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f1,
        fr.retrospare.blazeplayer.R.drawable.pixel_dancer_f2
    )

    // ── Ajout de fichiers depuis le navigateur ─────────────────────────────────
    private val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_PATHS) ?: return@registerForActivityResult
            val names = result.data?.getStringArrayListExtra(AudioBrowserActivity.EXTRA_NAMES) ?: return@registerForActivityResult
            val ctrl = controller ?: return@registerForActivityResult

            // Source unique de verite = le Player. Ajout immediat avec MediaItem simples (sans connexion reseau).
            val simpleMediaItems = paths.mapIndexed { i, path ->
                AudioRepository.buildSimpleMediaItem(requireContext(), path, names[i])
            }
            val wasEmpty = ctrl.mediaItemCount == 0
            ctrl.addMediaItems(simpleMediaItems)
            playlistAdapter.refresh()
            if (wasEmpty || ctrl.playbackState == Player.STATE_IDLE || ctrl.playbackState == Player.STATE_ENDED) {
                ctrl.prepare()
                ctrl.play()
            }
            savePlaylistFromController()
            binding.recyclerPlaylist.scrollToPosition(ctrl.mediaItemCount - 1)

            // Enrichissement metadonnees + cover en arriere-plan, sans bloquer la lecture
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                paths.forEachIndexed { i, path ->
                    try {
                        val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, names[i])
                        launch(Dispatchers.Main) {
                            val c = controller ?: return@launch
                            val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == path }
                            if (idx != null) {
                                c.replaceMediaItem(idx, enriched)
                                playlistAdapter.notifyItemChanged(idx)
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityAudioPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
        // Bouton retour supprimé visuellement : le lecteur audio reste accessible via la navigation principale.
        binding.btnBack.visibility = android.view.View.GONE
        setupSquareArtwork()
        restorePersistedDynamicAudioColors()

        initPlaylistUi()
        setupControls()
        setupSeekBar()
        startProgressUpdate()
        connectMediaController()
    }

    override fun onResume() {
        super.onResume()
        if (isHidden) return
        (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
        playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        } else {
            (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(false)
        }
    }

    override fun onDestroyView() {
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        handler.removeCallbacksAndMessages(null)
        sleepTimerJob?.cancel()
        eqManager?.release()
        savePlaylistFromController()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        try { bgAnimator?.cancel() } catch (_: Exception) {}
        controller = null
        squareArtworkListener?.let { squareArtworkContainer?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        squareArtworkListener = null
        squareArtworkContainer = null
        _binding = null
        super.onDestroyView()
    }


    // ── MediaController ────────────────────────────────────────────────────────

    private fun connectMediaController() {
        val token = SessionToken(requireContext(), ComponentName(requireContext(), BlazePlayerService::class.java))
        controllerFuture = MediaController.Builder(requireContext(), token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                onControllerReady()
            } catch (e: Exception) {
                CrashReporter.log(requireContext(), "AudioPlayer MediaController connection failed", e)
                controller = null
            }
        }, MoreExecutors.directExecutor())
    }

    private fun onControllerReady() {
        val ctrl = controller ?: return

        // Garde-fou d'isolation : si une ancienne version a laissé un MediaItem vidéo/cast dans la
        // session audio, on l'élimine immédiatement avant que l'UI ou Play/Pause ne puisse le piloter.
        purgeNonAudioItems(ctrl)

        // Les fichiers ouverts depuis Android (DocumentsUI / navigateur externe) sont prioritaires :
        // ils doivent remplacer le morceau/la playlist en cours et démarrer immédiatement, comme la vidéo.
        // Ce cas couvre aussi le démarrage à froid, quand l'intent arrive avant que le MediaController soit prêt.
        val priorityExternalTrack = sharedVm.consumePriorityExternalTrack()
        if (priorityExternalTrack != null) {
            startExternalAudioServiceFallback(priorityExternalTrack.path, priorityExternalTrack.name)
        }

        // Charge la playlist sauvegardée dans ExoPlayer si vide, sauf si un fichier externe vient
        // déjà d'être chargé en priorité. Le Player reste la seule source de verite ;
        // AudioRepository ne sert qu'a la persistance disque entre lancements de l'app.
        if (priorityExternalTrack == null && ctrl.mediaItemCount == 0) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val savedState = AudioRepository.loadState(requireContext())
                val savedItems = savedState.items
                if (savedItems.isNotEmpty()) {
                    // Chargement rapide : MediaItem simples d'abord, metadonnees enrichies ensuite
                    val simpleItems = savedItems.map { AudioRepository.buildSimpleMediaItem(requireContext(), it.path, it.name) }
                    launch(Dispatchers.Main) {
                        ctrl.setMediaItems(
                            simpleItems,
                            savedState.index.coerceIn(0, savedItems.size - 1),
                            savedState.positionMs
                        )
                        ctrl.repeatMode = savedState.repeatMode
                        ctrl.shuffleModeEnabled = savedState.shuffle
                        ctrl.prepare()
                        playlistAdapter.refresh()
                        syncSelection()
                        syncMetadata()
                        syncButtons()
                    }
                    // Enrichissement en arriere-plan. On traite d'abord le morceau courant :
                    // après une fermeture complète, la position revient immédiatement, mais les
                    // métadonnées/cover du FLAC SMB peuvent être absentes du MediaItem minimal.
                    // Prioriser l'index courant évite d'attendre toute la queue avant de revoir
                    // titre/artiste/album/cover dans le lecteur.
                    val ordered = savedItems.indices
                        .sortedBy { if (it == savedState.index) 0 else 1 }
                    ordered.forEach { i ->
                        val item = savedItems[i]
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), item.path, item.name)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                if (i < c.mediaItemCount) {
                                    c.replaceMediaItem(i, enriched)
                                    playlistAdapter.notifyItemChanged(i)
                                    if (i == c.currentMediaItemIndex) {
                                        syncMetadata()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            CrashReporter.log(requireContext(), "Audio metadata enrichment failed", e)
                        }
                    }
                }
            }
        } else {
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        }

        // Pending tracks depuis SharedViewModel. Quand un fichier audio arrive depuis Android
        // (DocumentsUI / navigateur de fichiers), le dernier fichier cliqué doit prendre la main
        // immédiatement, même si une ancienne playlist est déjà chargée. On l'ajoute si besoin,
        // puis on seek explicitement dessus avant prepare/play.
        val pending = sharedVm.consumePendingTracks()
        if (pending.isNotEmpty()) {
            val newTracks = mutableListOf<fr.retrospare.blazeplayer.home.AudioTrack>()
            pending.forEach { track ->
                val exists = (0 until ctrl.mediaItemCount).any { originalPathOf(ctrl.getMediaItemAt(it)) == track.path }
                if (!exists) {
                    ctrl.addMediaItem(AudioRepository.buildSimpleMediaItem(requireContext(), track.path, track.name))
                    newTracks += track
                }
            }

            val priorityTrack = pending.last()
            val priorityIndex = (0 until ctrl.mediaItemCount)
                .firstOrNull { originalPathOf(ctrl.getMediaItemAt(it)) == priorityTrack.path }
            if (priorityIndex != null) {
                ctrl.seekTo(priorityIndex, 0L)
                ctrl.prepare()
                ctrl.play()
            }
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
            savePlaylistFromController()

            if (newTracks.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    newTracks.forEach { track ->
                        try {
                            val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), track.path, track.name)
                            launch(Dispatchers.Main) {
                                val c = controller ?: return@launch
                                val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == track.path }
                                if (idx != null) {
                                    c.replaceMediaItem(idx, enriched)
                                    playlistAdapter.notifyItemChanged(idx)
                                    if (idx == c.currentMediaItemIndex) {
                                        syncMetadata()
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        // Listener natif Media3 - source unique de vérité pour toute la playlist
        ctrl.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                playlistAdapter.refresh()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncButtons()
                val idx = ctrl.currentMediaItemIndex
                if (isPlaying) playlistAdapter.setPlayingIndex(idx)
                else playlistAdapter.setPlayingIndex(-1)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = ctrl.currentMediaItemIndex
                syncSelection()
                syncMetadata()
                savePlaylistFromController()
                playlistAdapter.setCurrentIndex(idx)
                playlistAdapter.setPlayingIndex(if (ctrl.isPlaying) idx else -1)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    syncButtons()
                }
            }
        })
    }

    // ── Sync UI depuis MediaController (source unique) ─────────────────────────

    private fun syncSelection() {
        val ctrl = controller ?: return
        if (!::playlistAdapter.isInitialized) return
        playlistAdapter.setCurrentIndex(ctrl.currentMediaItemIndex)
    }

    private fun syncMetadata() {
        val ctrl = controller ?: return
        val mediaItem = ctrl.currentMediaItem ?: return
        val meta = mediaItem.mediaMetadata

        val pathForMeta = originalPathOf(mediaItem)
        val cachedMeta = fr.retrospare.blazeplayer.player.AudioMetadataExtractor.getCached(requireContext(), pathForMeta)
        val unknownArtist = getString(fr.retrospare.blazeplayer.R.string.unknown_artist)
        val metaArtist = meta.artist?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(unknownArtist, ignoreCase = true) && !it.equals("unknown", ignoreCase = true) }

        // Cache local prioritaire pour éviter le retour de "Unknown" après fermeture complète.
        _binding?.tvTitle?.text = cachedMeta?.title?.ifEmpty { null }
            ?: meta.title?.toString()?.ifEmpty { null }
            ?: mediaItem.localConfiguration?.uri?.lastPathSegment ?: getString(fr.retrospare.blazeplayer.R.string.unknown_title)
        _binding?.tvArtist?.text = cachedMeta?.artist?.ifEmpty { null }
            ?: metaArtist
            ?: unknownArtist
        val safeAlbum = sanitizeAudioSecondaryText(
            cachedMeta?.album?.ifEmpty { null } ?: meta.albumTitle?.toString()
        )
        _binding?.tvAlbum?.text = safeAlbum
        // Évite l'ancien badge/chemin SAF résiduel issu des essais Cloud : le lecteur ne doit
        // jamais afficher un content:// ou STORAGE/DOCUMENT sous le titre.
        _binding?.tvAlbum?.visibility = if (safeAlbum.isBlank()) View.GONE else View.GONE

        val ext = mediaItem.mediaMetadata.extras
            ?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION)
            ?.takeIf { it.isNotBlank() }
            ?: cachedMeta?.extension?.takeIf { it.isNotBlank() }
            ?: run {
                val sourceName = meta.title?.toString()?.takeIf { it.contains('.') }
                    ?: mediaItem.mediaMetadata.displayTitle?.toString()?.takeIf { it.contains('.') }
                    ?: mediaItem.mediaMetadata.extras?.getString("blaze_original_name")?.takeIf { it.contains('.') }
                    ?: mediaItem.localConfiguration?.uri?.lastPathSegment?.takeIf { it.contains('.') }
                    ?: pathForMeta.takeIf { it.contains('.') }
                sourceName?.substringAfterLast('.', "")?.uppercase().orEmpty()
            }
        val safeExt = sanitizeAudioExtension(ext)
        if (safeExt.isNotEmpty()) {
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(_binding?.tvCodec, safeExt)
            _binding?.tvCodec?.visibility = View.VISIBLE
        } else {
            _binding?.tvCodec?.text = ""
            _binding?.tvCodec?.visibility = View.GONE
        }

        // Affichage immédiat du badge qualité existant : ne pas le remplacer par le badge
        // conteneur. Si le cache possède déjà le débit/lossless, on le garde visible pendant
        // que l'extraction complète se fait en tâche IO.
        val losslessExt = safeExt.uppercase() in setOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
        when {
            cachedMeta?.isLossless == true || losslessExt -> {
                _binding?.tvBitrate?.text = getString(fr.retrospare.blazeplayer.R.string.lossless_label)
                _binding?.tvBitrate?.visibility = View.VISIBLE
            }
            (cachedMeta?.bitrate ?: 0L) > 0L -> {
                _binding?.tvBitrate?.text = "${cachedMeta!!.bitrate / 1000} kbps"
                _binding?.tvBitrate?.visibility = View.VISIBLE
            }
            else -> _binding?.tvBitrate?.visibility = View.GONE
        }

        // Bitrate via AudioMetadataExtractor (gère aussi smb://, avec cache disque — évite de
        // ré-extraire à chaque fois qu'on rouvre le même morceau)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val path = pathForMeta.ifEmpty { return@launch }
            val info = fr.retrospare.blazeplayer.player.AudioMetadataExtractor.extract(
                requireContext(), path, path.substringAfterLast("/")
            )
            launch(Dispatchers.Main) {
                if (originalPathOf(controller?.currentMediaItem ?: return@launch) == path) {
                    if (info.title.isNotEmpty()) _binding?.tvTitle?.text = info.title
                    if (info.artist.isNotEmpty()) _binding?.tvArtist?.text = info.artist
                    sanitizeAudioSecondaryText(info.album).takeIf { it.isNotBlank() }?.let { _binding?.tvAlbum?.text = it }
                }
                when {
                    info.isLossless || losslessExt -> {
                        _binding?.tvBitrate?.text = getString(fr.retrospare.blazeplayer.R.string.lossless_label)
                        _binding?.tvBitrate?.visibility = View.VISIBLE
                    }
                    info.bitrate > 0 -> {
                        _binding?.tvBitrate?.text = "${info.bitrate / 1000} kbps"
                        _binding?.tvBitrate?.visibility = View.VISIBLE
                    }
                    else -> _binding?.tvBitrate?.visibility = View.GONE
                }
            }
        }

        // Artwork depuis MediaMetadata, puis cache disque/RAM si absent (réouverture app, SMB/FLAC).
        val artworkData = meta.artworkData
        if (artworkData != null) {
            fr.retrospare.blazeplayer.ui.ThumbnailUtils.cacheAudioArtworkData(requireContext(), originalPathOf(mediaItem), artworkData)
            val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
            _binding?.ivArtwork?.setImageBitmap(bitmap)
            applyDynamicBackgroundFromBitmap(bitmap)
        } else {
            val path = originalPathOf(mediaItem)
            val cached = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(requireContext(), path)
            if (cached != null) {
                val bitmap = BitmapFactory.decodeByteArray(cached, 0, cached.size)
                _binding?.ivArtwork?.setImageBitmap(bitmap)
                applyDynamicBackgroundFromBitmap(bitmap)
            } else {
                _binding?.ivArtwork?.setImageResource(fr.retrospare.blazeplayer.R.drawable.bg_thumbnail)
                resetDynamicBackground()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val bytes = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkJpegBytes(requireContext(), path)
                    if (bytes != null) {
                        launch(Dispatchers.Main) {
                            val c = controller ?: return@launch
                            val current = c.currentMediaItem ?: return@launch
                            if (originalPathOf(current) != path) return@launch
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            _binding?.ivArtwork?.setImageBitmap(bitmap)
                            applyDynamicBackgroundFromBitmap(bitmap)
                            val enrichedMeta = current.mediaMetadata.buildUpon()
                                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                            val enriched = current.buildUpon().setMediaMetadata(enrichedMeta).build()
                            c.replaceMediaItem(c.currentMediaItemIndex, enriched)
                        }
                    }
                }
            }
        }
    }


    /**
     * Fond dynamique plus visible, inspiré des lecteurs audio modernes : on extrait une couleur
     * dominante robuste depuis la pochette, puis on renforce légèrement saturation/luminosité.
     * Le fond reste sombre via un dégradé noir -> accent afin de garder les contrôles lisibles.
     */
    private fun applyDynamicBackgroundFromBitmap(bitmap: Bitmap?) {
        bitmap ?: return resetDynamicBackground()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val accent = try {
                val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                var r = 0L; var g = 0L; var b = 0L; var count = 0L
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                for (px in pixels) {
                    val alpha = Color.alpha(px)
                    if (alpha < 48) continue
                    val hsv = FloatArray(3)
                    Color.colorToHSV(px, hsv)
                    if (hsv[2] < 0.10f) continue
                    r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++
                }
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                val base = if (count <= 0L) Color.rgb(63, 215, 143) else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
                boostAudioAccent(base)
            } catch (_: Exception) {
                Color.rgb(63, 215, 143)
            }
            val bg = mixColors(Color.rgb(8, 10, 12), accent, 0.30f)
            launch(Dispatchers.Main) { animateDynamicBackground(bg, accent) }
        }
    }

    private fun boostAudioAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.35f + 0.12f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 1.22f + 0.08f).coerceIn(0.35f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun mixColors(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
        )
    }

    private fun resetDynamicBackground() {
        animateDynamicBackground(Color.rgb(10, 12, 14), Color.rgb(63, 215, 143))
    }

    private fun animateDynamicBackground(targetColor: Int, accentColor: Int = currentAccentColor) {
        val root = _binding?.root ?: return
        if (currentDynamicBgColor == targetColor && currentAccentColor == accentColor) return
        bgAnimator?.cancel()
        bgAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentDynamicBgColor, targetColor).apply {
            duration = 380L
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(mixColors(Color.rgb(5, 7, 9), color, 0.58f), color, Color.rgb(5, 7, 9))
                )
                root.background = gradient
                currentDynamicBgColor = color
            }
            start()
        }
        currentAccentColor = accentColor
        val tint = ColorStateList.valueOf(accentColor)
        _binding?.seekBar?.progressTintList = tint
        _binding?.seekBar?.thumbTintList = tint
        _binding?.btnPlayPause?.backgroundTintList = null
        _binding?.btnPlayPause?.background = buildPlayButtonBackground(accentColor)
        _binding?.btnPlayPause?.elevation = dp(10f)
        _binding?.btnPlayPause?.translationZ = dp(6f)
        _binding?.tvArtist?.setTextColor(appGreenColor())
        persistDynamicAudioColors(targetColor, accentColor)
    }

    private fun restorePersistedDynamicAudioColors() {
        val prefs = requireContext().getSharedPreferences(DYNAMIC_AUDIO_PREFS, android.content.Context.MODE_PRIVATE)
        val bg = prefs.getInt(KEY_DYNAMIC_BG, Color.rgb(10, 12, 14))
        val accent = prefs.getInt(KEY_DYNAMIC_ACCENT, Color.rgb(63, 215, 143))
        currentDynamicBgColor = bg
        currentAccentColor = accent
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mixColors(Color.rgb(5, 7, 9), bg, 0.58f), bg, Color.rgb(5, 7, 9))
        )
        _binding?.root?.background = gradient
        val tint = ColorStateList.valueOf(accent)
        _binding?.seekBar?.progressTintList = tint
        _binding?.seekBar?.thumbTintList = tint
        _binding?.btnPlayPause?.backgroundTintList = null
        _binding?.btnPlayPause?.background = buildPlayButtonBackground(accent)
        _binding?.btnPlayPause?.elevation = dp(10f)
        _binding?.btnPlayPause?.translationZ = dp(6f)
        _binding?.tvArtist?.setTextColor(appGreenColor())
    }

    private fun persistDynamicAudioColors(bg: Int, accent: Int) {
        try {
            requireContext().getSharedPreferences(DYNAMIC_AUDIO_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DYNAMIC_BG, bg)
                .putInt(KEY_DYNAMIC_ACCENT, accent)
                .apply()
        } catch (_: Exception) { }
    }

    private fun appGreenColor(): Int = try {
        androidx.core.content.ContextCompat.getColor(requireContext(), fr.retrospare.blazeplayer.R.color.green_accent)
    } catch (_: Exception) {
        Color.rgb(63, 215, 143)
    }


    private fun buildPlayButtonBackground(accentColor: Int): GradientDrawable {
        val light = mixColors(accentColor, Color.WHITE, 0.28f)
        val dark = mixColors(accentColor, Color.BLACK, 0.30f)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(light, accentColor, dark)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1.5f).toInt(), mixColors(accentColor, Color.WHITE, 0.42f))
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sanitizeAudioExtension(raw: String?): String {
        val ext = raw.orEmpty().trim().removePrefix(".").uppercase()
        if (ext.isBlank() || ext.length !in 2..5 || !ext.all { it.isLetterOrDigit() }) return ""
        val allowed = setOf("MP3", "FLAC", "M4A", "AAC", "WAV", "OGG", "OGA", "OPUS", "WMA", "APE", "DTS", "AC3", "EAC3", "MKA", "WV", "AIFF", "ALAC")
        return ext.takeIf { it in allowed }.orEmpty()
    }

    private fun sanitizeAudioSecondaryText(raw: CharSequence?): String {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isBlank()) return ""
        val lower = text.lowercase()
        if (lower.startsWith("content://") || lower.contains("storage/document") || lower.contains("documents/document")) return ""
        if (text.length > 80 && (text.contains('%') || text.contains('/'))) return ""
        return text
    }

    private fun syncButtons() {
        _binding?.btnPlayPause?.setImageResource(
            if (controller?.isPlaying == true) fr.retrospare.blazeplayer.R.drawable.ic_pause
            else fr.retrospare.blazeplayer.R.drawable.ic_play
        )
    }

    // ── Playlist UI ───────────────────────────────────────────────────────────

    /** Force la pochette à rester parfaitement carrée : sa taille = le plus petit des deux côtés
     *  disponibles dans son conteneur, recalculé à chaque passage de layout (rotation, ajout de
     *  la rangée playlists en dessous...) au lieu d'un simple match_parent qui l'étirait. */
    private var squareArtworkListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var squareArtworkContainer: View? = null

    private fun setupSquareArtwork() {
        val container = binding.ivArtwork.parent as? View ?: return
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val b = _binding ?: return@OnGlobalLayoutListener
            val availableWidth = container.width - container.paddingLeft - container.paddingRight
            val availableHeight = container.height - container.paddingTop - container.paddingBottom
            if (availableWidth <= 0 || availableHeight <= 0) return@OnGlobalLayoutListener
            val size = minOf(availableWidth, availableHeight)
            val params = b.ivArtwork.layoutParams
            if (params.width != size || params.height != size) {
                params.width = size
                params.height = size
                b.ivArtwork.layoutParams = params
            }
        }
        container.viewTreeObserver.addOnGlobalLayoutListener(listener)
        squareArtworkListener = listener
        squareArtworkContainer = container
    }

    private fun initPlaylistUi() {
        playlistAdapter = PlaylistAdapter({ controller }) { index ->
            controller?.seekToDefaultPosition(index)
            controller?.play()
        }
        binding.recyclerPlaylist.apply {
            // File d'attente plus dense : deux colonnes, lecture naturelle gauche → droite.
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
            adapter = playlistAdapter
        }
        binding.btnCleanPlaylist.setOnClickListener { showCleanDialog() }
        binding.btnAddFolder.setOnClickListener {
            pickAudio.launch(android.content.Intent(requireContext(), AudioBrowserActivity::class.java))
        }
        binding.btnAudioFavoriteFolders.setOnClickListener {
            fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showFavoritesList(
                requireContext(),
                fr.retrospare.blazeplayer.favorites.FavoriteCategory.AUDIO
            ) { favorite ->
                pickAudio.launch(android.content.Intent(requireContext(), AudioBrowserActivity::class.java).apply {
                    putExtra(AudioBrowserActivity.EXTRA_FAVORITE_PATH, favorite.path)
                    favorite.shareId?.let { putExtra(AudioBrowserActivity.EXTRA_FAVORITE_SHARE_ID, it) }
                })
            }
        }

        fun openPlaylist() {
            binding.playlistSheet.visibility = android.view.View.VISIBLE
            binding.playlistSheet.translationY = binding.playlistSheet.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
            binding.playlistSheet.animate()
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            _binding?.btnBack?.visibility = android.view.View.GONE
        }

        fun closePlaylist() {
            binding.playlistSheet.animate()
                .translationY(resources.displayMetrics.heightPixels.toFloat())
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    _binding?.playlistSheet?.visibility = android.view.View.GONE
                }
                .start()
            _binding?.btnBack?.visibility = android.view.View.GONE
        }

        binding.btnPlaylistSheet.setOnClickListener {
            if (binding.playlistSheet.visibility == android.view.View.VISIBLE) closePlaylist() else openPlaylist()
        }
        binding.btnClosePlaylist.setOnClickListener { closePlaylist() }

        setupSavedPlaylistDrawers()
    }

    /** Les 3 tiroirs (1/2/3) sur le bord droit de l'écran, pour les playlists audio sauvegardées
     *  (différentes de la file d'attente en cours, ouverte via btnPlaylistSheet). */
    private fun setupSavedPlaylistDrawers() {
        val buttons = listOf(
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist1),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist2),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist3),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist4),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnAudioPlaylist5)
        )
        val ctx = context
        val lastPlayed = if (ctx != null) fr.retrospare.blazeplayer.playlist.PlaylistManager
            .getLastPlayed(ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO) else 0
        buttons.forEachIndexed { i, btn ->
            if (ctx != null) {
                val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager
                    .getPlaylist(ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, i + 1).isNotEmpty()
                btn?.isSelected = (lastPlayed == i + 1) && hasItems
            }
            btn?.setOnClickListener { openSavedAudioPlaylist(i + 1) }
        }
    }

    private fun openSavedAudioPlaylist(slot: Int) {
        val ctx = context ?: return
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showPlaylistViewer(
            ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, slot,
            onPlayAll = { tracks ->
                val ctrl = controller
                ctrl?.clearMediaItems()
                tracks.forEach { addTrack(it.path, it.name) }
                fr.retrospare.blazeplayer.playlist.PlaylistManager.setLastPlayed(ctx, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, slot)
                setupSavedPlaylistDrawers()
            },
            onPlayOne = { track -> addTrack(track.path, track.name) }
        )
    }

    /** Sauvegarde sur disque l'etat courant du Player (seule source de verite). */
    fun savePlaylistFromController() {
        val ctx = context ?: return
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) return
        val items = (0 until ctrl.mediaItemCount).mapNotNull { i ->
            val mi = ctrl.getMediaItemAt(i)
            val path = originalPathOf(mi)
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapNotNull null
            val name = mi.mediaMetadata.title?.toString()?.ifEmpty { null }
                ?: mi.localConfiguration?.uri?.lastPathSegment ?: ""
            PlaylistItem(path, name)
        }
        if (items.isEmpty()) return
        AudioRepository.save(
            ctx,
            items,
            ctrl.currentMediaItemIndex,
            ctrl.currentPosition.coerceAtLeast(0L),
            ctrl.repeatMode,
            ctrl.shuffleModeEnabled
        )
    }

    fun playExternalTrack(path: String, name: String) {
        restartAudioServiceForExternalTrack(path, name)
    }

    fun restartAudioServiceForExternalTrack(path: String, name: String) {
        // Ancien nom conservé pour compatibilité avec MainActivity, mais la logique change :
        // on ne tue plus le service audio et on ne libère plus le MediaController depuis l'UI.
        // Media3 est conçu pour recevoir des commandes via le MediaSessionService stable.
        startExternalAudioServiceFallback(path, name)
    }

    fun onExternalAudioReplaced() {
        handler.postDelayed({
            if (_binding == null) return@postDelayed
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        }, 200L)
    }

    private fun playExternalTrackOnController(ctrl: MediaController, path: String, name: String) {
        // Cas critique "Ouvrir avec" depuis un navigateur de fichiers.
        // Quand le lecteur audio est déjà ouvert, le second ACTION_VIEW arrive ici via onNewIntent().
        // Ne pas utiliser startService() en priorité dans ce cas : sur certains appareils l'intent
        // explicite du service est retardé/ignoré alors que la MediaSession existe déjà, ce qui
        // laisse l'ancien morceau actif. On envoie donc une commande MediaSession directe au
        // BlazePlayerService existant. Le service fait ensuite le remplacement strict de sa file.
        val args = Bundle().apply {
            putString(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_PATH, path)
            putString(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_NAME, name)
        }
        try {
            val future = ctrl.sendCustomCommand(
                SessionCommand(BlazePlayerService.COMMAND_PLAY_EXTERNAL_AUDIO, Bundle.EMPTY),
                args
            )
            future.addListener({
                try {
                    val result = future.get()
                    if (result.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                        startExternalAudioServiceFallback(path, name)
                    }
                } catch (e: Exception) {
                    CrashReporter.log(requireContext(), "External audio session command failed for $path", e)
                    startExternalAudioServiceFallback(path, name)
                }
                handler.post {
                    if (_binding == null) return@post
                    playlistAdapter.refresh()
                    syncSelection()
                    syncMetadata()
                    syncButtons()
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Send external audio session command failed for $path", e)
            startExternalAudioServiceFallback(path, name)
        }
    }

    private fun startExternalAudioServiceFallback(path: String, name: String) {
        try {
            requireContext().startService(android.content.Intent(requireContext(), BlazePlayerService::class.java).apply {
                action = BlazePlayerService.ACTION_PLAY_EXTERNAL_AUDIO
                putExtra(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_PATH, path)
                putExtra(BlazePlayerService.EXTRA_EXTERNAL_AUDIO_NAME, name)
            })
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Start external audio service fallback failed for $path", e)
        }
        handler.postDelayed({
            if (_binding == null) return@postDelayed
            playlistAdapter.refresh()
            syncSelection()
            syncMetadata()
            syncButtons()
        }, 250L)
    }

    fun addTrack(path: String, name: String) {
        // Ne jamais manipuler ce fragment s'il n'est plus attaché. C'est la signature exacte
        // observée dans les logs : addTrack() -> sharedVm/activityViewModels -> requireActivity()
        // alors que FragmentManager a rendu une ancienne instance détachée.
        if (!isAdded || context == null) return
        val ctrl = controller
        if (ctrl == null) {
            // Quand Blaze Audio vient d'être affiché depuis un intent Android externe, la
            // connexion MediaController n'est pas toujours prête au moment exact de l'appel.
            // Avant, le morceau était simplement perdu : l'onglet audio s'ouvrait mais rien ne
            // se lançait. On le place dans la file d'attente partagée pour qu'il soit consommé
            // dès que le contrôleur est prêt.
            sharedVm.addToPlaylist(path, name)
            return
        }
        val exists = (0 until ctrl.mediaItemCount).any { originalPathOf(ctrl.getMediaItemAt(it)) == path }
        if (exists) {
            val index = (0 until ctrl.mediaItemCount).firstOrNull { originalPathOf(ctrl.getMediaItemAt(it)) == path } ?: return
            ctrl.seekTo(index, 0L)
            if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
            ctrl.play()
            return
        }

        val simpleItem = AudioRepository.buildSimpleMediaItem(requireContext(), path, name)
        ctrl.addMediaItem(simpleItem)
        val newIndex = ctrl.mediaItemCount - 1
        // Un fichier ouvert depuis un navigateur externe est une demande de lecture immédiate,
        // pas seulement un ajout en fin de playlist.
        ctrl.seekTo(newIndex, 0L)
        ctrl.prepare()
        ctrl.play()
        playlistAdapter.refresh()
        syncSelection()
        syncMetadata()
        syncButtons()
        savePlaylistFromController()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enriched = AudioRepository.buildMediaItemWithMetadata(requireContext(), path, name)
                launch(Dispatchers.Main) {
                    val c = controller ?: return@launch
                    val idx = (0 until c.mediaItemCount).firstOrNull { originalPathOf(c.getMediaItemAt(it)) == path }
                    if (idx != null) {
                        c.replaceMediaItem(idx, enriched)
                        playlistAdapter.notifyItemChanged(idx)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ── Contrôles ─────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            val ctrl = controller ?: return@setOnClickListener
            if (ctrl.isPlaying) ctrl.pause()
            else {
                if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
                ctrl.play()
            }
        }
        binding.btnPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }
        binding.btnNext.setOnClickListener { controller?.seekToNextMediaItem() }
        binding.btnRewind.setOnClickListener {
            controller?.seekTo((controller!!.currentPosition - 10_000).coerceAtLeast(0))
        }
        binding.btnForward.setOnClickListener {
            controller?.seekTo((controller!!.currentPosition + 10_000).coerceAtMost(controller!!.duration))
        }

        var repeatMode = 0
        binding.btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            when (repeatMode) {
                0 -> { controller?.repeatMode = Player.REPEAT_MODE_OFF
                    binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant)) }
                1 -> { controller?.repeatMode = Player.REPEAT_MODE_ALL
                    binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)) }
                2 -> { controller?.repeatMode = Player.REPEAT_MODE_ONE
                    binding.btnRepeat.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_repeat_one)
                    binding.btnRepeat.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)) }
            }
            savePlaylistFromController()
        }

        var isShuffled = controller?.shuffleModeEnabled ?: false
        binding.btnShuffle.setOnClickListener {
            isShuffled = !isShuffled
            controller?.shuffleModeEnabled = isShuffled
            savePlaylistFromController()
            binding.btnShuffle.setColorFilter(
                if (isShuffled) requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent)
                else requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant)
            )
        }

        binding.btnEq.setOnClickListener {
            val existing = eqManager
            if (existing != null) {
                EqualizerDialog(existing).show(parentFragmentManager, "eq")
                return@setOnClickListener
            }
            val ctrl = controller ?: return@setOnClickListener
            // L'audioSessionId n'est pas exposé par l'API Player standard : on le récupère via une
            // commande de session personnalisée plutôt qu'une référence statique vers le service
            // (cf. BlazePlayerService.SessionCallback), conformément aux best practices Media3.
            val future = ctrl.sendCustomCommand(
                androidx.media3.session.SessionCommand(BlazePlayerService.COMMAND_GET_AUDIO_SESSION_ID, android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY
            )
            future.addListener({
                val sessionId = try {
                    future.get().extras.getInt(BlazePlayerService.EXTRA_AUDIO_SESSION_ID, 0)
                } catch (_: Exception) { 0 }
                if (sessionId != 0) {
                    try {
                        eqManager = EqualizerManager(sessionId, requireContext()).also { it.restoreLastSession() }
                        eqManager?.let { eq -> EqualizerDialog(eq).show(parentFragmentManager, "eq") }
                    } catch (_: Exception) { }
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(requireContext()))
        }

        binding.btnInfos?.setOnClickListener {
            val ctrl = controller ?: return@setOnClickListener
            val meta = ctrl.currentMediaItem?.mediaMetadata
            val title = meta?.title ?: getString(fr.retrospare.blazeplayer.R.string.unknown_generic)
            val artist = meta?.artist ?: getString(fr.retrospare.blazeplayer.R.string.unknown_generic)
            val album = meta?.albumTitle ?: getString(fr.retrospare.blazeplayer.R.string.unknown_generic)
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(fr.retrospare.blazeplayer.R.string.info))
                .setMessage(getString(fr.retrospare.blazeplayer.R.string.dialog_track_info_message, title, artist, album))
                .setPositiveButton("OK", null)
                .show()
        }
        binding.btnSleepTimer.setOnClickListener {
            val options = arrayOf(getString(fr.retrospare.blazeplayer.R.string.minutes_5), getString(fr.retrospare.blazeplayer.R.string.minutes_15), getString(fr.retrospare.blazeplayer.R.string.minutes_30), getString(fr.retrospare.blazeplayer.R.string.hour_1), getString(fr.retrospare.blazeplayer.R.string.action_cancel))
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(fr.retrospare.blazeplayer.R.string.sleep_timer_title))
                .setItems(options) { _, which ->
                    sleepTimerJob?.cancel()
                    val minutes = when (which) { 0->5L; 1->15L; 2->30L; 3->60L; else->0L }
                    if (minutes > 0) {
                        (binding.btnSleepTimer.getChildAt(0) as? android.widget.ImageView)
                            ?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.green_accent))
                        sleepTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(minutes * 60 * 1000)
                            controller?.pause()
                            (_binding?.btnSleepTimer?.getChildAt(0) as? android.widget.ImageView)
                                ?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                        }
                    } else {
                        (binding.btnSleepTimer.getChildAt(0) as? android.widget.ImageView)
                            ?.setColorFilter(requireContext().getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
                    }
                }.show()
        }
    }

    // ── SeekBar ────────────────────────────────────────────────────────────────

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = controller?.duration ?: 0L
                    if (dur > 0) controller?.seekTo(dur * progress / 100)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isSeekBarTracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeekBarTracking = false
                val dur = controller?.duration ?: 0L
                if (dur > 0) {
                    controller?.seekTo(dur * seekBar.progress / 100)
                    savePlaylistFromController()
                }
            }
        })
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val ctrl = controller
                val dur = ctrl?.duration ?: 0L
                if (!isSeekBarTracking && dur > 0 && ctrl != null) {
                    _binding?.seekBar?.progress = ((ctrl.currentPosition * 100) / dur).toInt()
                    _binding?.tvCurrentTime?.text = formatTime(ctrl.currentPosition)
                    _binding?.tvTotalTime?.text = formatTime(dur)
                    playlistAdapter.updateCurrentProgress(ctrl.currentMediaItemIndex, ctrl.currentPosition, dur)
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    // ── Dancer ─────────────────────────────────────────────────────────────────

    private fun startDancerAnimation() {
        handler.post(object : Runnable {
            override fun run() {
                if (controller?.isPlaying == true) {
                    dancerFrame = (dancerFrame + 1) % dancerFrames.size
                }
                handler.postDelayed(this, 300)
            }
        })
    }

    // ── Utils ──────────────────────────────────────────────────────────────────

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    private fun showCleanDialog() {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) {
            android.widget.Toast.makeText(requireContext(), getString(fr.retrospare.blazeplayer.R.string.toast_list_already_empty), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val itemsSnapshot = (0 until ctrl.mediaItemCount).map { i ->
            val mi = ctrl.getMediaItemAt(i)
            mi.mediaMetadata.title?.toString()?.ifEmpty { null } ?: mi.localConfiguration?.uri?.lastPathSegment ?: "?"
        }
        val checked = BooleanArray(itemsSnapshot.size) { false }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(fr.retrospare.blazeplayer.R.string.dialog_clean_list))
            .setMultiChoiceItems(itemsSnapshot.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton(getString(fr.retrospare.blazeplayer.R.string.action_remove_selection)) { _, _ ->
                val c = controller ?: return@setPositiveButton
                // Supprime du plus grand index au plus petit pour ne pas decaler les indices
                checked.indices.reversed().forEach { i ->
                    if (checked[i] && i < c.mediaItemCount) {
                        c.removeMediaItem(i)
                    }
                }
                playlistAdapter.refresh()
                savePlaylistFromController()
            }
            .setNeutralButton(getString(fr.retrospare.blazeplayer.R.string.action_clear_all)) { _, _ ->
                controller?.clearMediaItems()
                playlistAdapter.refresh()
                AudioRepository.clear(requireContext())
            }
            .setNegativeButton(getString(fr.retrospare.blazeplayer.R.string.action_cancel), null)
            .show()
    }
    private fun purgeNonAudioItems(ctrl: MediaController) {
        try {
            for (i in ctrl.mediaItemCount - 1 downTo 0) {
                val path = originalPathOf(ctrl.getMediaItemAt(i))
                if (path.isBlank() || !AudioRepository.isAudioExtension(path)) {
                    ctrl.removeMediaItem(i)
                }
            }
        } catch (e: Exception) {
            CrashReporter.log(requireContext(), "Purge non-audio items from audio player failed", e)
        }
    }

    private fun originalPathOf(item: androidx.media3.common.MediaItem): String {
        val fromExtras = item.mediaMetadata.extras?.getString("blaze_original_path")
            ?.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
        if (fromExtras != null) return fromExtras
        return item.mediaId.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
            ?: item.localConfiguration?.uri?.toString()?.takeIf { AudioRepository.isAudioExtension(it) }
            ?: ""
    }



    companion object {
        private const val DYNAMIC_AUDIO_PREFS = "blaze_audio_dynamic_colors"
        private const val KEY_DYNAMIC_BG = "dynamic_bg"
        private const val KEY_DYNAMIC_ACCENT = "dynamic_accent"
    }
}
