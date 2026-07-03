package fr.retrospare.blazeplayer.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Point d'entree des intents audio externes.
 *
 * Depuis la demande d'afficher la barre d'onglets dans Blaze Audio, cette Activity ne doit plus
 * rester en plein écran pour les fichiers audio : elle sert de trampoline léger vers MainActivity,
 * qui conserve les onglets Local / Réseau / Blaze Tube / Blaze Audio et délègue la lecture au
 * MediaSessionService stable. Le fragment plein écran reste uniquement en repli si l'Activity est
 * appelée sans média exploitable.
 */
@AndroidEntryPoint
class AudioPlayerActivity : AppCompatActivity() {

    private var containerId: Int = View.NO_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (forwardExternalAudioToMain(intent)) return

        containerId = View.generateViewId()
        setContentView(FrameLayout(this).apply {
            id = containerId
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(containerId, AudioPlayerFragment(), TAG_AUDIO_FRAGMENT)
                .commitNowAllowingStateLoss()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (forwardExternalAudioToMain(intent)) return
    }

    private fun forwardExternalAudioToMain(intent: Intent?): Boolean {
        val external = ExternalMediaIntentUtils.fromExternalIntent(this, intent)
        if (external?.kind == ExternalMediaIntentUtils.ExternalMedia.Kind.VIDEO) {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("mediaPath", external.path)
                putExtra("mediaName", external.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = external.uri
                clipData = android.content.ClipData.newUri(contentResolver, external.name, external.uri)
            })
            finish()
            return true
        }

        val path = intent?.getStringExtra("mediaPath") ?: external?.path ?: return false
        val name = intent?.getStringExtra("mediaName") ?: external?.name
            ?: android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
            ?: "Audio"
        val grantUri = external?.uri ?: intent?.data

        startActivity(Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java).apply {
            putExtra("externalAudioPath", path)
            putExtra("externalAudioName", name)
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            if (grantUri != null) {
                data = grantUri
                clipData = android.content.ClipData.newUri(contentResolver, name, grantUri)
            }
        })
        consumeIntent(intent)
        finish()
        overridePendingTransition(0, 0)
        return true
    }

    private fun consumeIntent(intent: Intent?) {
        intent ?: return
        intent.data = null
        intent.clipData = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.action = null
        intent.type = null
    }

    companion object {
        private const val TAG_AUDIO_FRAGMENT = "external_blaze_audio"
    }
}
