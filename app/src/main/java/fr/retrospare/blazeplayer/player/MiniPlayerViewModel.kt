package fr.retrospare.blazeplayer.player

import android.app.Application
import android.content.ComponentName
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
    val artworkData: ByteArray? = null,
    val isPlaying: Boolean = false
)

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val KEY_MINI_PLAYER = booleanPreferencesKey("mini_player_enabled")

    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.filesDir.resolve("datastore/settings.preferences_pb") }
    )

    // Flows internes — chaque changement déclenche le recalcul de state
    private val _hasMedia = MutableStateFlow(false)
    private val _isPlaying = MutableStateFlow(false)
    private val _title = MutableStateFlow("")
    private val _artist = MutableStateFlow("")
    private val _artworkData = MutableStateFlow<ByteArray?>(null)
    private val _inAudioPlayer = MutableStateFlow(false)

    val miniPlayerEnabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY_MINI_PLAYER] ?: false }

    var controller: MediaController? = null
        private set

    // State entièrement réactif — tous les flows sont dans combine
    val state: StateFlow<MiniPlayerState> = combine(
        combine(_hasMedia, _isPlaying, _title) { a, b, c -> Triple(a, b, c) },
        combine(_artist, _artworkData, _inAudioPlayer) { a, b, c -> Triple(a, b, c) },
        miniPlayerEnabledFlow
    ) { (hasMedia, isPlaying, title), (artist, artwork, inAudioPlayer), enabled ->
        MiniPlayerState(
            isVisible = hasMedia && enabled && !inAudioPlayer,
            title = title,
            artist = artist,
            artworkData = artwork,
            isPlaying = isPlaying
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MiniPlayerState())

    fun connect() {
        if (controller != null) {
            refreshFromController()
            return
        }
        val token = SessionToken(context, ComponentName(context, BlazePlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
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
                _hasMedia.value = false
            }
        }, MoreExecutors.directExecutor())
    }

    fun setInAudioPlayer(inPlayer: Boolean) {
        _inAudioPlayer.value = inPlayer
        if (!inPlayer) refreshFromController()
    }

    fun refresh() {
        refreshFromController()
    }

    private fun refreshFromController() {
        val ctrl = controller ?: return
        val hasMedia = ctrl.mediaItemCount > 0
        _hasMedia.value = hasMedia
        _isPlaying.value = ctrl.isPlaying
        if (hasMedia) {
            val meta = ctrl.currentMediaItem?.mediaMetadata
            _title.value = meta?.title?.toString() ?: ""
            _artist.value = meta?.artist?.toString() ?: ""
            _artworkData.value = meta?.artworkData
        }
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_MINI_PLAYER] = enabled }
        }
    }

    fun getMiniPlayerEnabled(): Flow<Boolean> = miniPlayerEnabledFlow
}
