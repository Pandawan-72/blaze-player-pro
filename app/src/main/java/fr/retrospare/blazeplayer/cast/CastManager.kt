package fr.retrospare.blazeplayer.cast

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener

class CastManager(
    private val context: Context,
    private val localPlayer: ExoPlayer,
    private val onPlayerChanged: (Player) -> Unit
) {
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var currentPlayer: Player = localPlayer

    private val castStateListener = CastStateListener { state ->
        when (state) {
            CastState.CONNECTED -> switchToRemotePlayer()
            CastState.NOT_CONNECTED, CastState.NO_DEVICES_AVAILABLE -> switchToLocalPlayer()
        }
    }

    fun init() {
        try {
            castContext = CastContext.getSharedInstance(context)
            castPlayer = CastPlayer(castContext!!).apply {
                setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() { switchToRemotePlayer() }
                    override fun onCastSessionUnavailable() { switchToLocalPlayer() }
                })
            }
            castContext?.addCastStateListener(castStateListener)
        } catch (e: Exception) { }
    }

    private fun switchToRemotePlayer() {
        if (currentPlayer == castPlayer) return
        castPlayer?.let { cast ->
            val mediaItem = localPlayer.currentMediaItem
            val position = localPlayer.currentPosition
            localPlayer.pause()
            mediaItem?.let {
                cast.setMediaItem(it, position)
                cast.prepare()
                cast.play()
            }
            currentPlayer = cast
            onPlayerChanged(cast)
        }
    }

    private fun switchToLocalPlayer() {
        if (currentPlayer == localPlayer) return
        castPlayer?.let { cast ->
            val position = cast.currentPosition
            cast.stop()
            localPlayer.seekTo(position)
            localPlayer.play()
        }
        currentPlayer = localPlayer
        onPlayerChanged(localPlayer)
    }

    fun isCasting(): Boolean = currentPlayer == castPlayer

    fun release() {
        castContext?.removeCastStateListener(castStateListener)
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
    }
}
