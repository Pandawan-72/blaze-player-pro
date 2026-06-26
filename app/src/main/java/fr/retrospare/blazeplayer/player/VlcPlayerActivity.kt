package fr.retrospare.blazeplayer.player

import android.net.Uri
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

@AndroidEntryPoint
class VlcPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("mediaPath") ?: return finish()
        val title = intent.getStringExtra("mediaName") ?: ""
        binding.tvTitle.text = title

        val options = ArrayList<String>().apply {
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--rtsp-tcp")
            add("--avcodec-hw=any") // décodage matériel si disponible
            add("--audio-resampler=soxr")
        }

        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)

        surfaceView = SurfaceView(this)
        binding.playerView.addView(surfaceView)
        surfaceView.holder.addCallback(this)

        binding.btnBack.setOnClickListener { finish() }

        val media = Media(libVLC, Uri.parse(path))
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mediaPlayer.vlcVout.apply {
            setVideoSurface(holder.surface, holder)
            attachViews()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mediaPlayer.vlcVout.setWindowSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mediaPlayer.vlcVout.detachViews()
    }

    override fun onStop() {
        super.onStop()
        mediaPlayer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.release()
        libVLC.release()
    }
}
