package fr.retrospare.blazeplayer.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.databinding.ActivityPlayerBinding

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"
    }

    private val viewModel: PlayerViewModel by viewModels()
    private lateinit var binding: ActivityPlayerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intent.getStringExtra(EXTRA_MEDIA_URI) ?: return finish()
        viewModel.initPlayer()
        binding.playerView.player = viewModel.player
        viewModel.playUri(uri)
    }

    override fun onStop() {
        super.onStop()
        viewModel.player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.playerView.player = null
    }
}
