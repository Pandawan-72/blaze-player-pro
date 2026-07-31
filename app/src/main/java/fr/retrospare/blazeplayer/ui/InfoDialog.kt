package fr.retrospare.blazeplayer.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R

/**
 * Popup d'information centrée à l'écran, au même design que la fenêtre de réglages vidéo du
 * lecteur (PlayerActivity.showVideoSettingsDialog) : carte sombre arrondie, badge d'icône
 * circulaire, titre, croix de fermeture, séparateur, puis contenu.
 *
 * Remplace les Toast pour tous les messages qui apportent une vraie information à comprendre
 * (erreur, incompatibilité, fonctionnalité indisponible...), par opposition aux simples
 * confirmations fugaces d'action ("Ajouté aux favoris"...) qui restent en Toast.
 */
object InfoDialog {

    fun show(
        context: Context,
        title: String,
        message: String,
        iconRes: Int = R.drawable.ic_info,
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        val dialog = Dialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            setBackgroundResource(R.drawable.bg_dialog_rounded)
        }

        // En-tête : badge d'icône, titre, croix de fermeture (identique à showVideoSettingsDialog)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconWrap = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_cast_icon_circle)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        iconWrap.addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        })
        val titleView = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }
        val close = ImageButton(context).apply {
            setBackgroundResource(R.drawable.bg_top_icon_btn)
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            contentDescription = context.getString(R.string.action_close)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(iconWrap)
        header.addView(titleView)
        header.addView(close)
        root.addView(header)

        root.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
        })

        root.addView(TextView(context).apply {
            text = message
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        root.addView(TextView(context).apply {
            text = context.getString(R.string.action_ok)
            setTextColor(fr.retrospare.blazeplayer.theme.AccentColorManager.accent(context))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(24), 0, dp(24), 0)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                topMargin = dp(20)
                gravity = Gravity.END
            }
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }
}
