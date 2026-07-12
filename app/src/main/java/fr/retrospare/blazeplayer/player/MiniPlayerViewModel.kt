package fr.retrospare.blazeplayer.player

import android.app.Application
import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.debug.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MiniPlayerState(
    val isVisible: Boolean = false,
    val title: String = "",
    val artist: String = "",
    // Album dérivé du dossier qui contient le titre, sans extraction ID3/FLAC.
    val album: String = "",
    val artworkData: ByteArray? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val backgroundColor: Int = AudioDynamicColor.DEFAULT_BACKGROUND,
    val accentColor: Int = AudioDynamicColor.DEFAULT_ACCENT
)

/** Regroupe artiste/album/pochette/état-plein-écran pour tenir dans la limite du combine() à 4
 *  flows de kotlinx.coroutines (types hétérogènes, un simple Triple/Quadruple générique ne suffit
 *  pas proprement). */
private data class ArtistAlbumGroup(
    val artist: String,
    val album: String,
    val artwork: ByteArray?,
    val inAudioPlayer: Boolean
)

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<Preferences>,
    private val userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val KEY_MINI_PLAYER = booleanPreferencesKey("mini_player_enabled")

    // Flows internes — chaque changement déclenche le recalcul de state
    private val _hasMedia = MutableStateFlow(false)
    private val _isPlaying = MutableStateFlow(false)
    private val _title = MutableStateFlow("")
    private val _artist = MutableStateFlow("")
    private val _album = MutableStateFlow("")
    private val _artworkData = MutableStateFlow<ByteArray?>(null)
    private val _inAudioPlayer = MutableStateFlow(false)
    private val _dismissed = MutableStateFlow(false)
    private val _backgroundColor = MutableStateFlow(AudioDynamicColor.DEFAULT_BACKGROUND)
    private val _accentColor = MutableStateFlow(AudioDynamicColor.DEFAULT_ACCENT)

    val miniPlayerEnabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY_MINI_PLAYER] ?: false }

    var controller: MediaController? = null
        private set
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var connectionJob: Job? = null

    // State entièrement réactif — tous les flows sont dans combine
    val state: StateFlow<MiniPlayerState> = combine(
        combine(_hasMedia, _isPlaying, _title) { a, b, c -> Triple(a, b, c) },
        combine(_artist, _album, _artworkData, _inAudioPlayer) { a, b, c, d -> ArtistAlbumGroup(a, b, c, d) },
        miniPlayerEnabledFlow,
        combine(_dismissed, _backgroundColor, _accentColor) { a, b, c -> Triple(a, b, c) }
    ) { (hasMedia, isPlaying, title), group, enabled, (dismissed, bgColor, accentColor) ->
        MiniPlayerState(
            isVisible = hasMedia && enabled && !group.inAudioPlayer && !dismissed,
            title = title,
            artist = group.artist,
            album = group.album,
            artworkData = group.artwork,
            isPlaying = isPlaying,
            positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L,
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L,
            backgroundColor = bgColor,
            accentColor = accentColor
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MiniPlayerState())

    /** Connexion défensive : le simple affichage de MainActivity ne doit jamais démarrer
     * BlazePlayerService. On vérifie d'abord, hors thread principal, le réglage, le droit Pro+ et
     * l'existence d'une session audio déjà active. */
    fun connect() {
        if (controller != null) {
            refreshFromController()
            return
        }
        if (connectionJob?.isActive == true || controllerFuture != null) return

        connectionJob = viewModelScope.launch {
            val enabled = miniPlayerEnabledFlow.first()
            val allowed = runCatching {
                fr.retrospare.blazeplayer.paywall.FeatureAccess.isProPlus(userRepository)
            }.getOrElse { error ->
                CrashReporter.log(context, "MiniPlayer access check failed", error)
                false
            }
            if (!enabled || !allowed || !BlazePlayerService.isAudioSessionActive) {
                _hasMedia.value = false
                return@launch
            }
            connectController()
        }
    }

    private fun connectController() {
        if (controller != null || controllerFuture != null || !BlazePlayerService.isAudioSessionActive) return
        val token = SessionToken(context, ComponentName(context, BlazePlayerService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controllerRef: MediaController) {
                    controller = null
                    controllerFuture = null
                    _hasMedia.value = false
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            controllerFuture = null
            try {
                val ctrl = future.get()
                if (!BlazePlayerService.isAudioSessionActive) {
                    ctrl.release()
                    _hasMedia.value = false
                    return@addListener
                }
                controller = ctrl
                ctrl.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        refreshFromController()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        refreshFromController()
                    }
                })
                refreshFromController()
            } catch (e: Exception) {
                CrashReporter.log(context, "MiniPlayer MediaController connection failed", e)
                _hasMedia.value = false
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        controllerFuture?.cancel(true)
        controllerFuture = null
        runCatching { controller?.release() }
        controller = null
        _hasMedia.value = false
        _isPlaying.value = false
    }

    fun setInAudioPlayer(inPlayer: Boolean) {
        _inAudioPlayer.value = inPlayer
        // Ré-ouvrir Blaze Audio "réarme" le mini player : une fermeture manuelle précédente ne
        // doit plus le masquer une fois qu'on retourne dans le lecteur puis qu'on en ressort.
        if (inPlayer) _dismissed.value = false
        else refreshFromController()
    }

    /** Ferme manuellement le mini player (croix). Reste masqué jusqu'à la prochaine visite de
     *  Blaze Audio (cf. setInAudioPlayer), même si la lecture continue en arrière-plan. */
    fun dismiss() {
        _dismissed.value = true
    }

    fun refresh() {
        if (controller == null) {
            if (BlazePlayerService.isAudioSessionActive) connect() else _hasMedia.value = false
        } else {
            refreshFromController()
        }
    }

    private var colorComputeToken = 0
    private var currentArtworkPath = ""
    private var artworkLoadJob: Job? = null

    /** Met à jour la pochette affichée ET recalcule la couleur dynamique associée (même
     *  algorithme que l'écran Blaze Audio, cf. AudioDynamicColor), pour que le mini player suive
     *  la couleur du morceau même si l'écran complet n'a jamais été ouvert pour ce morceau. */
    private fun applyArtwork(art: ByteArray?) {
        _artworkData.value = art
        val token = ++colorComputeToken
        if (art == null) {
            _backgroundColor.value = AudioDynamicColor.DEFAULT_BACKGROUND
            _accentColor.value = AudioDynamicColor.DEFAULT_ACCENT
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = try {
                android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
            } catch (_: Exception) {
                null
            }
            if (bitmap == null) return@launch
            val accent = AudioDynamicColor.accentFromBitmap(bitmap)
            val bg = AudioDynamicColor.backgroundFromAccent(accent)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (token == colorComputeToken) {
                    _accentColor.value = accent
                    _backgroundColor.value = bg
                }
            }
        }
    }

    private fun refreshFromController() {
        val ctrl = controller ?: return
        val hasMedia = ctrl.mediaItemCount > 0
        _hasMedia.value = hasMedia
        _isPlaying.value = ctrl.isPlaying
        if (hasMedia) {
            val item = ctrl.currentMediaItem
            val meta = item?.mediaMetadata
            val path = item?.mediaMetadata?.extras?.getString("blaze_original_path")?.takeIf { fr.retrospare.blazeplayer.player.AudioRepository.isAudioExtension(it) }
                ?: item?.mediaId.orEmpty().takeIf { fr.retrospare.blazeplayer.player.AudioRepository.isAudioExtension(it) }
                ?: ""
            if (path.isEmpty()) {
                _hasMedia.value = false
                _album.value = ""
                applyArtwork(null)
                return
            }
            val originalName = meta?.extras?.getString("blaze_original_name")
                .orEmpty().ifBlank { AudioLibraryHeuristics.fileNameFromPath(path) }
            val folder = AudioLibraryHeuristics.folderMetadata(path, originalName)
            _title.value = folder.title
            _artist.value = folder.artist
            _album.value = folder.album

            // La seule extraction autorisée ici est celle de la pochette : cover.jpg, cover.png,
            // puis embedded. On attend la résolution centrale au lieu d'afficher immédiatement
            // artworkData, qui peut contenir une ancienne embedded moins prioritaire.
            val pathChanged = currentArtworkPath != path
            if (pathChanged) {
                currentArtworkPath = path
                artworkLoadJob?.cancel()
            }
            val cachedArtwork = AudioArtworkResolver.cachedJpegBytes(context, path)
            if (cachedArtwork != null) {
                applyArtwork(cachedArtwork)
            } else if (pathChanged) {
                // Conserver la pochette pendant les simples événements play/pause/buffering ; le
                // placeholder n'est appliqué qu'au vrai changement de piste.
                applyArtwork(null)
            }
            if (pathChanged || (cachedArtwork == null && artworkLoadJob?.isActive != true)) {
                artworkLoadJob = viewModelScope.launch(Dispatchers.IO) {
                    val art = AudioArtworkResolver.resolveJpegBytes(context, path)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        val currentItem = controller?.currentMediaItem
                        val current = currentItem?.mediaMetadata?.extras?.getString("blaze_original_path")
                            ?: currentItem?.mediaId.orEmpty()
                        if (current == path) applyArtwork(art)
                    }
                }
            }
        }
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_MINI_PLAYER] = enabled }
            if (!enabled) disconnect() else refresh()
        }
    }

    fun getMiniPlayerEnabled(): Flow<Boolean> = miniPlayerEnabledFlow

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
