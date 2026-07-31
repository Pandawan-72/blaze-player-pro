package fr.retrospare.blazeplayer.player

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import fr.retrospare.blazeplayer.R
import kotlin.math.ceil

/** Modal de saisie directe HH:MM pour le minuteur de veille. */
object SleepTimerDialog {
    fun show(
        context: Context,
        initialRemainingMs: Long,
        onStart: (durationMs: Long) -> Unit,
        onCancelTimer: () -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        val targetWidth = (context.resources.displayMetrics.widthPixels * 0.92f)
            .toInt()
            .coerceAtMost(dp(480))
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = context.getDrawable(R.drawable.bg_dialog_rounded)
            isFocusableInTouchMode = true
        }
        root.addView(TextView(context).apply {
            setText(R.string.sleep_timer_title)
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(context).apply {
            setText(R.string.sleep_timer_instruction)
            setTextColor(context.getColor(R.color.on_surface_variant))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        })

        val totalMinutes = if (initialRemainingMs > 0L) {
            ceil(initialRemainingMs / 60_000.0).toLong().coerceAtMost(99L * 60L + 59L)
        } else 0L
        val initialHours = totalMinutes / 60L
        val initialMinutes = totalMinutes % 60L

        fun digitalField(value: Long, description: String): EditText = EditText(context).apply {
            setText("%02d".format(value))
            setTextColor(Color.WHITE)
            textSize = 43f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(2))
            setSelectAllOnFocus(true)
            contentDescription = description
            background = context.getDrawable(R.drawable.bg_setting_item)
            includeFontPadding = false
            setPadding(dp(8), 0, dp(8), 0)
        }

        val hoursField = digitalField(initialHours, context.getString(R.string.sleep_timer_hours))
        val minutesField = digitalField(initialMinutes, context.getString(R.string.sleep_timer_minutes))
        val clock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(hoursField, LinearLayout.LayoutParams(dp(106), dp(88)))
            addView(TextView(context).apply {
                text = ":"
                setTextColor(fr.retrospare.blazeplayer.theme.AccentColorManager.accent(context))
                textSize = 43f
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
            }, LinearLayout.LayoutParams(dp(40), dp(88)))
            addView(minutesField, LinearLayout.LayoutParams(dp(106), dp(88)))
        }
        root.addView(clock)

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                setText(R.string.sleep_timer_hours)
                setTextColor(context.getColor(R.color.on_surface_variant))
                gravity = Gravity.CENTER
                textSize = 12f
            }, LinearLayout.LayoutParams(dp(126), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                setText(R.string.sleep_timer_minutes)
                setTextColor(context.getColor(R.color.on_surface_variant))
                gravity = Gravity.CENTER
                textSize = 12f
            }, LinearLayout.LayoutParams(dp(126), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(labels)

        val error = TextView(context).apply {
            setTextColor(context.getColor(R.color.red_accent))
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(error)

        val status = TextView(context).apply {
            setTextColor(fr.retrospare.blazeplayer.theme.AccentColorManager.accent(context))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            visibility = if (initialRemainingMs > 0L) View.VISIBLE else View.GONE
            if (initialRemainingMs > 0L) {
                text = context.getString(
                    R.string.sleep_timer_active_remaining,
                    formatDuration(initialRemainingMs)
                )
            }
        }
        root.addView(status)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelTimer = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.action_cancel)
            isAllCaps = false
            visibility = if (initialRemainingMs > 0L) View.VISIBLE else View.GONE
            setOnClickListener {
                onCancelTimer()
                dialog.dismiss()
            }
        }
        val start = MaterialButton(context).apply {
            setText(R.string.sleep_timer_start)
            isAllCaps = false
            setOnClickListener {
                val hours = hoursField.text?.toString()?.toIntOrNull()?.coerceIn(0, 99) ?: 0
                val minutesRaw = minutesField.text?.toString()?.toIntOrNull() ?: 0
                if (minutesRaw !in 0..59) {
                    error.setText(R.string.sleep_timer_minutes_error)
                    error.visibility = View.VISIBLE
                    minutesField.requestFocus()
                    return@setOnClickListener
                }
                val durationMs = (hours * 60L + minutesRaw) * 60_000L
                if (durationMs <= 0L) {
                    error.setText(R.string.sleep_timer_zero_error)
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                onStart(durationMs)
                dialog.dismiss()
            }
        }
        actions.addView(cancelTimer, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
        actions.addView(start, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(8) })
        root.addView(actions)

        val handler = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + initialRemainingMs
        val countdown = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || initialRemainingMs <= 0L) return
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                status.text = context.getString(R.string.sleep_timer_active_remaining, formatDuration(remaining))
                if (remaining > 0L) handler.postDelayed(this, 1_000L)
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dialog.setContentView(
            root,
            android.view.ViewGroup.LayoutParams(
                targetWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dialog.setOnShowListener {
            root.requestFocus()
            if (initialRemainingMs > 0L) handler.postDelayed(countdown, 1_000L)
        }
        dialog.show()
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}
