package fr.retrospare.blazeplayer.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.browser.BrowserFragment

@AndroidEntryPoint
class AudioPickActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_IS_NETWORK = "isNetwork"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_pick)

        if (savedInstanceState == null) {
            val isNetwork = intent.getBooleanExtra(EXTRA_IS_NETWORK, false)
            val fragment = BrowserFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("audioPickMode", true)
                    putBoolean("isNetwork", isNetwork)
                    putBoolean("audioOnlyMode", true)
                    putString("path", "")
                    putString("shareId", "")
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.audio_pick_container, fragment)
                .commit()
        }
    }

    fun onFilePicked(path: String, name: String) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_NAME, name)
        })
        finish()
    }
}
