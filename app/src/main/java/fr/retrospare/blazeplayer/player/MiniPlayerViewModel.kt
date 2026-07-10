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
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.debug.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MiniPlayerState(
    val isVisible: Boolean = false,
    val title: String = "",
    val artist: String = "",
    // Uniquement un vrai tag ID3/FLAC (AudioMetadataExtractor) : jamais de nom de dossier ici,
    // contrairement à ce que peut afficher Track.album côté bibliothèque. Vide si le morceau n'a
    // pas de vraie tag album, auquel cas rien ne doit être affiché.
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
    private val dataStore: DataStore<Preferences>
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

    fun connect() {
        if (controller != null) {
            refreshFromController()
            return
        }
        val token = SessionToken(context, ComponentName(context, BlazePlayerService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controllerRef: MediaController) {
                    // Le service audio a été tué/déconnecté (ex: manque de mémoire) : on efface la
                    // référence pour qu'un prochain connect()/refresh() la reconstruise proprement,
                    // au lieu de garder indéfiniment un contrôleur mort (mini player figé/invisible).
                    controller = null
                    _hasMedia.value = false
                }
            })
            .buildAsync()
        future.addListener({
            try {
                val ctrl = future.get()
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
            connect()
        } else {
            refreshFromController()
        }
    }

    private var colorComputeToken = 0

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
            val cached = AudioMetadataExtractor.getCached(context, path)
            _title.value = meta?.title?.toString()?.ifEmpty { null }
                ?: cached?.title?.ifEmpty { null }
                ?: path.substringAfterLast('/').substringBeforeLast('.')
            val unknownArtist = context.getString(fr.retrospare.blazeplayer.R.string.unknown_artist)
            val metaArtist = meta?.artist?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(unknownArtist, ignoreCase = true) && !it.equals("unknown", ignoreCase = true) }
            _artist.value = cached?.artist?.ifEmpty { null }
                ?: metaArtist
                ?: ""
            // Uniquement le vrai tag album (AudioMetadataExtractor) : pas de repli sur
            // meta.albumTitle ni sur un quelconque nom de dossier. Vide si aucune vraie tag.
            _album.value = cached?.album?.ifEmpty { null } ?: ""
            // Priorité au cache ThumbnailUtils (qui respecte déjà l'ordre cover-dossier > pochette
            // embarquée) plutôt qu'à meta.artworkData brut : sinon une pochette embarquée "de
            // secours" fournie par Media3 gagnait systématiquement contre un cover.jpg/png déjà
            // résolu et caché juste avant, y compris pour la couleur dynamique du mini player.
            val cachedArtwork = if (path.isNotEmpty()) fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(context, path) else null
            applyArtwork(cachedArtwork ?: meta?.artworkData)

            // hasPreferredFolderCoverForAudio() ne sait rien dire pour smb:// sans I/O réseau (elle
            // reste volontairement synchrone/locale) : on force donc toujours une résolution complète
            // en tâche de fond pour le réseau, en plus du cas "aucune pochette du tout" déjà couvert.
            val mightHaveBetterCover = path.startsWith("smb://", true) ||
                fr.retrospare.blazeplayer.ui.ThumbnailUtils.hasPreferredFolderCoverForAudio(path)
            if (path.isNotEmpty() && (cached == null || cachedArtwork == null || mightHaveBetterCover)) {
                viewModelScope.launch(Dispatchers.IO) {
                    val name = path.substringAfterLast('/')
                    val info = AudioMetadataExtractor.extract(context, path, name)
                    val art = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkJpegBytes(context, path)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        val currentItem = controller?.currentMediaItem
                        val current = currentItem?.mediaMetadata?.extras?.getString("blaze_original_path") ?: currentItem?.mediaId.orEmpty()
                        if (current == path) {
                            if (info.title.isNotEmpty()) _title.value = info.title
                            if (info.artist.isNotEmpty()) _artist.value = info.artist
                            if (info.album.isNotEmpty()) _album.value = info.album
                            if (art != null) applyArtwork(art)
                        }
                    }
                }
            }
        }
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_MINI_PLAYER] = enabled }
        }
    }

    fun getMiniPlayerEnabled(): Flow<Boolean> = miniPlayerEnabledFlow
}
