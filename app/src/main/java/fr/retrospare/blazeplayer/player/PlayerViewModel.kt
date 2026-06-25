package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor() : ViewModel() {

    var player: ExoPlayer? = null
        private set

    private val ratios = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )
    private var currentRatioIndex = 0

    fun initPlayer(context: Context) {
        if (player != null) return
        player = ExoPlayer.Builder(context).build()
    }

    fun playUri(uri: String) {
        val player = player ?: return
        val mediaItem = MediaItem.fromUri(Uri.parse(uri))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun cycleAspectRatio(playerView: PlayerView) {
        currentRatioIndex = (currentRatioIndex + 1) % ratios.size
        playerView.resizeMode = ratios[currentRatioIndex]
    }

    fun releasePlayer() {
        player?.release()
        player = null
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }
}
