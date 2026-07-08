package fr.retrospare.blazeplayer.ui

import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.widget.Button
import androidx.core.content.ContextCompat
import fr.retrospare.blazeplayer.R

object DialogButtonStyler {
    fun style(dialog: android.app.AlertDialog) {
        dialog.window?.decorView?.post {
            styleButton(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE))
            styleButton(dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE))
            styleButton(dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL))
        }
    }

    fun style(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.decorView?.post {
            styleButton(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE))
            styleButton(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE))
            styleButton(dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL))
        }
    }

    fun styleButton(button: Button?) {
        button ?: return
        val ctx = button.context
        val height = (40f * ctx.resources.displayMetrics.density).toInt()
        val horizontalPadding = (18f * ctx.resources.displayMetrics.density).toInt()

        button.minHeight = 0
        button.minimumHeight = 0
        button.setMinHeight(0)
        button.setMinimumHeight(0)
        button.height = height
        button.setPadding(horizontalPadding, 0, horizontalPadding, 0)
        button.gravity = Gravity.CENTER
        button.includeFontPadding = false
        button.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        button.setAllCaps(false)
        button.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        button.setTextColor(ContextCompat.getColor(ctx, R.color.green_accent))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.backgroundTintList = null
            button.stateListAnimator = null
        }
        button.background = ContextCompat.getDrawable(ctx, R.drawable.bg_queue_action_fixed_secondary)
        button.layoutParams = button.layoutParams?.apply { this.height = height }
        button.requestLayout()
    }
}

fun android.app.AlertDialog.premiumButtons(): android.app.AlertDialog = apply {
    DialogButtonStyler.style(this)
}

fun androidx.appcompat.app.AlertDialog.premiumButtons(): androidx.appcompat.app.AlertDialog = apply {
    DialogButtonStyler.style(this)
}

fun android.app.AlertDialog.Builder.showPremium(): android.app.AlertDialog = show().premiumButtons()

fun androidx.appcompat.app.AlertDialog.Builder.showPremium(): androidx.appcompat.app.AlertDialog = show().premiumButtons()

fun com.google.android.material.dialog.MaterialAlertDialogBuilder.showPremium(): androidx.appcompat.app.AlertDialog = show().premiumButtons()
