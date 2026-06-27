package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.ViewModel
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class PlayerViewModel : ViewModel() {

    var libVLC: LibVLC? = null
        private set
    var player: MediaPlayer? = null
        private set

    private val scaleTypes = listOf("", "16:9", "4:3", "1:1", "fill")
    private var scaleIndex = 0

    fun initPlayer(context: Context) {
        if (player != null) return
        libVLC = LibVLC(context, arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--avcodec-hw=any",
            "--audio-resampler=soxr"
        ))
        player = MediaPlayer(libVLC)
    }

    fun playUri(path: String, context: Context) {
        val vlc = libVLC ?: return
        val p = player ?: return
        val media = try {
            if (path.startsWith("content://")) {
                val fd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.fileDescriptor ?: return
                Media(vlc, fd)
            } else {
                Media(vlc, Uri.parse(path))
            }
        } catch (e: Exception) { return }
        p.media = media
        media.release()
        p.play()
    }

    fun attachSurface(surfaceView: SurfaceView, holder: SurfaceHolder) {
        val p = player ?: return
        val vout = p.vlcVout
        vout.setVideoSurface(holder.surface, holder)
        if (!vout.areViewsAttached()) vout.attachViews()
    }

    fun updateSurfaceSize(w: Int, h: Int) {
        player?.vlcVout?.setWindowSize(w, h)
    }

    fun detachSurface() {
        try { player?.vlcVout?.detachViews() } catch (e: Exception) {}
    }

    fun cycleAspectRatio() {
        scaleIndex = (scaleIndex + 1) % scaleTypes.size
        player?.aspectRatio = scaleTypes[scaleIndex].ifEmpty { null }
    }

    override fun onCleared() {
        super.onCleared()
        detachSurface()
        player?.release()
        libVLC?.release()
        player = null
        libVLC = null
    }
}
