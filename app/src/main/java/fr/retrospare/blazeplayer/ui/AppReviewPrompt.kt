package fr.retrospare.blazeplayer.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import fr.retrospare.blazeplayer.R
import java.lang.ref.WeakReference

/**
 * Sollicitation Play Store non intrusive, limitée à trois apparitions au maximum.
 *
 * Google Play ne fournit pas à une application d'API permettant de lire l'avis déjà publié par
 * l'utilisateur. La suppression permanente est donc enregistrée localement dès que l'utilisateur
 * choisit « Noter maintenant » ou « Déjà noté ». La troisième apparition clôt aussi définitivement
 * le calendrier, même si la fenêtre est simplement fermée.
 */
object AppReviewPrompt {
    private const val PREFS = "blaze_app_review_prompt"
    private const val KEY_FIRST_USE_AT = "first_use_at"
    private const val KEY_NEXT_STAGE = "next_stage"
    private const val KEY_LAST_SHOWN_AT = "last_shown_at"
    private const val KEY_NEVER_SHOW = "never_show"

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val MIN_GAP_BETWEEN_PROMPTS_MS = 20L * 60L * 60L * 1000L
    private val thresholdsMs = longArrayOf(7L * DAY_MS, 15L * DAY_MS, 30L * DAY_MS)

    private var currentDialog: WeakReference<Dialog>? = null

    fun maybeShow(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (currentDialog?.get()?.isShowing == true) return

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NEVER_SHOW, false)) return

        val now = System.currentTimeMillis()
        var firstUseAt = prefs.getLong(KEY_FIRST_USE_AT, 0L)
        if (firstUseAt <= 0L || firstUseAt > now) {
            val installAt = runCatching {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime
            }.getOrDefault(now)
            firstUseAt = installAt.takeIf { it in 1..now } ?: now
            prefs.edit().putLong(KEY_FIRST_USE_AT, firstUseAt).apply()
        }

        val stage = prefs.getInt(KEY_NEXT_STAGE, 0)
        if (stage !in thresholdsMs.indices) return
        if (now - firstUseAt < thresholdsMs[stage]) return

        // Évite que les trois étapes deviennent successives sur la même journée pour une ancienne
        // installation qui découvre cette fonction après plus d'un mois.
        val lastShownAt = prefs.getLong(KEY_LAST_SHOWN_AT, 0L)
        if (lastShownAt > 0L && now - lastShownAt < MIN_GAP_BETWEEN_PROMPTS_MS) return

        // L'étape est consommée avant l'affichage afin qu'une rotation, une fermeture forcée ou un
        // retour instantané dans l'activité ne puisse jamais dupliquer la même sollicitation.
        prefs.edit()
            .putInt(KEY_NEXT_STAGE, stage + 1)
            .putLong(KEY_LAST_SHOWN_AT, now)
            .apply()

        showDialog(activity)
    }

    fun markRatedOrHandled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NEVER_SHOW, true)
            .putInt(KEY_NEXT_STAGE, thresholdsMs.size)
            .apply()
    }

    fun openPlayStoreListing(context: Context): Boolean {
        val packageName = context.packageName
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$packageName")
        ).apply {
            setPackage("com.android.vending")
            addFlags(
                Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        }
        try {
            context.startActivity(marketIntent)
            return true
        } catch (_: Exception) {
            // Le Play Store peut être absent sur certains appareils : repli navigateur.
        }

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(webIntent)
            return true
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.toast_play_store_unavailable), Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun showDialog(activity: AppCompatActivity) {
        fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(20))
            setBackgroundResource(R.drawable.bg_dialog_rounded)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconWrap = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.bg_cast_icon_circle)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        iconWrap.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_star)
            setColorFilter(ContextCompat.getColor(activity, R.color.green_accent))
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        })
        val title = TextView(activity).apply {
            text = activity.getString(R.string.review_prompt_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        }
        val close = ImageButton(activity).apply {
            setBackgroundResource(R.drawable.bg_top_icon_btn)
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            contentDescription = activity.getString(R.string.action_close)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(iconWrap)
        header.addView(title)
        header.addView(close)
        root.addView(header)

        root.addView(View(activity).apply {
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(18)
                bottomMargin = dp(16)
            }
        })

        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.review_prompt_message)
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        val stars = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(14)
            }
        }
        repeat(5) { index ->
            stars.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_star)
                setColorFilter(ContextCompat.getColor(activity, R.color.green_accent))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    if (index > 0) marginStart = dp(5)
                }
            })
        }
        root.addView(stars)

        fun actionText(textRes: Int, color: Int, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = activity.getString(textRes)
            setTextColor(color)
            textSize = 14f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = ColorDrawable(Color.TRANSPARENT)
            minHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

        val rateNow = actionText(
            R.string.review_prompt_rate_now,
            ContextCompat.getColor(activity, R.color.green_accent)
        ) {
            if (openPlayStoreListing(activity)) {
                markRatedOrHandled(activity)
                dialog.dismiss()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
        }
        root.addView(rateNow)

        val secondaryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        val alreadyRated = actionText(
            R.string.review_prompt_already_rated,
            Color.parseColor("#BFFFFFFF")
        ) {
            markRatedOrHandled(activity)
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        val later = actionText(
            R.string.review_prompt_later,
            Color.parseColor("#BFFFFFFF")
        ) {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        secondaryRow.addView(alreadyRated)
        secondaryRow.addView(later)
        root.addView(secondaryRow)

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            if (currentDialog?.get() === dialog) currentDialog = null
        }
        dialog.show()
        currentDialog = WeakReference(dialog)
        HapticFeedbackManager.attachToWindow(dialog.window)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }
}
