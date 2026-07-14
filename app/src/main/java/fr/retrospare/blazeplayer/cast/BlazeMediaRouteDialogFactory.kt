package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.mediarouter.app.MediaRouteControllerDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import com.google.android.gms.cast.framework.CastContext
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.debug.CrashReporter

/**
 * Remplace uniquement le panneau de contrôle Cast affiché quand une session est déjà active.
 *
 * Le sélecteur Chromecast natif reste inchangé pour la connexion initiale, mais le second clic sur
 * l'icône Cast n'affiche plus le vieux panneau MediaRouter gris/vert mal calibré : il ouvre ce
 * panneau sombre, aligné avec le design du lecteur vidéo.
 */
class BlazeMediaRouteDialogFactory : MediaRouteDialogFactory() {

    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment {
        return super.onCreateChooserDialogFragment()
    }

    override fun onCreateControllerDialogFragment(): MediaRouteControllerDialogFragment {
        return BlazeCastControllerDialogFragment()
    }

    class BlazeCastControllerDialogFragment : MediaRouteControllerDialogFragment() {
        override fun onCreateControllerDialog(context: Context, savedInstanceState: Bundle?): MediaRouteControllerDialog {
            return BlazeCastControllerDialog(context)
        }
    }

    class BlazeCastControllerDialog(context: Context) : MediaRouteControllerDialog(context) {
        override fun onCreate(savedInstanceState: Bundle?) {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            super.onCreate(savedInstanceState)

            val view = LayoutInflater.from(context).inflate(R.layout.dialog_blaze_cast_controller, null, false)
            setContentView(view)

            val deviceName = currentDeviceName(context)
            val mediaTitle = currentMediaTitle(context)
            view.findViewById<TextView>(R.id.tvCastDialogDevice).text = deviceName
            view.findViewById<TextView>(R.id.tvCastDialogTitle).text = mediaTitle
            view.findViewById<TextView>(R.id.btnCastDialogStop).setOnClickListener {
                try {
                    CastContext.getSharedInstance(context.applicationContext)
                        .sessionManager
                        .endCurrentSession(true)
                } catch (e: Exception) {
                    CrashReporter.log(context.applicationContext, "Failed to stop Chromecast from custom route dialog", e)
                }
                dismiss()
            }
            view.findViewById<android.view.View>(R.id.btnCastDialogClose).setOnClickListener { dismiss() }

            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window?.setDimAmount(0.55f)
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(window)
            window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        private fun currentDeviceName(context: Context): String {
            return try {
                CastContext.getSharedInstance(context.applicationContext)
                    .sessionManager
                    .currentCastSession
                    ?.castDevice
                    ?.friendlyName
                    ?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.casting_chromecast)
            } catch (_: Exception) {
                context.getString(R.string.casting_chromecast)
            }
        }

        private fun currentMediaTitle(context: Context): String {
            return try {
                val client = CastContext.getSharedInstance(context.applicationContext)
                    .sessionManager
                    .currentCastSession
                    ?.remoteMediaClient
                client?.mediaInfo?.metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.app_name)
            } catch (_: Exception) {
                context.getString(R.string.app_name)
            }
        }
    }
}
