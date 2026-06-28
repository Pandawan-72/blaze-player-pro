package fr.retrospare.blazeplayer.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.browser.BrowserFragment

@AndroidEntryPoint
class AudioPickActivity : AppCompatActivity(), fr.retrospare.blazeplayer.browser.AudioPickCallback {

    companion object {
        const val EXTRA_PATHS = "extra_paths"
        const val EXTRA_NAMES = "extra_names"
        const val EXTRA_IS_NETWORK = "isNetwork"
    }

    private val selectedPaths = arrayListOf<String>()
    private val selectedNames = arrayListOf<String>()

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

    override fun onFilePicked(path: String, name: String) {
        android.widget.Toast.makeText(this, "DEBUG: onFilePicked appelé: $name", android.widget.Toast.LENGTH_LONG).show()
        if (!selectedPaths.contains(path)) {
            selectedPaths.add(path)
            selectedNames.add(name)
            android.widget.Toast.makeText(this, "$name ajouté", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            selectedPaths.remove(path)
            selectedNames.remove(name)
            android.widget.Toast.makeText(this, "$name retiré", android.widget.Toast.LENGTH_SHORT).show()
        }
        // Met à jour le bouton avec le compteur
    }



    fun confirmSelection() {
        if (selectedPaths.isEmpty()) { finish(); return }
        setResult(Activity.RESULT_OK, Intent().apply {
            putStringArrayListExtra(EXTRA_PATHS, selectedPaths)
            putStringArrayListExtra(EXTRA_NAMES, selectedNames)
        })
        finish()
    }
}
