package fr.retrospare.blazeplayer.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView

fun TextView.setBlazeTitle() {
    val full = "BlazePlayer"
    val spannable = SpannableString(full)
    // "Blaze" en blanc
    spannable.setSpan(
        ForegroundColorSpan(Color.WHITE),
        0, 5,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    // "Player" en vert #3DD68C
    spannable.setSpan(
        ForegroundColorSpan(Color.parseColor("#3DD68C")),
        5, full.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    text = spannable
}
