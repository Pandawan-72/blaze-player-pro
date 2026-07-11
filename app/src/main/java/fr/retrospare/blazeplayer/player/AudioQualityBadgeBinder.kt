package fr.retrospare.blazeplayer.player

import android.content.Context
import android.view.View
import android.widget.TextView
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.ui.BadgeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Source unique pour le couple de badges « conteneur + qualité ». */
object AudioQualityBadgeBinder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val losslessExtensions = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV")

    fun bind(
        codecView: TextView?,
        qualityView: TextView?,
        path: String,
        originalName: String = "",
        fallbackExtension: String = "",
        codecTextColor: Int? = null
    ) {
        val context = codecView?.context ?: qualityView?.context ?: return
        val ext = normalizedExtension(path, originalName, fallbackExtension)
        if (ext.isNotBlank()) {
            BadgeStyle.applyContainerBadge(codecView, ext)
            codecTextColor?.let { codecView?.setTextColor(it) }
            codecView?.visibility = View.VISIBLE
        } else {
            codecView?.text = ""
            codecView?.visibility = View.GONE
        }

        BadgeStyle.applyTechnicalBadge(qualityView)
        val token = path.ifBlank { "name:$originalName:$ext" }
        qualityView?.tag = token
        val cached = path.takeIf { it.isNotBlank() }
            ?.let { AudioMetadataExtractor.getCached(context.applicationContext, it) }
        if (renderQuality(context, qualityView, cached, ext)) return

        qualityView?.text = ""
        qualityView?.visibility = View.GONE
        if (path.isBlank() || ext.isBlank()) return

        scope.launch {
            val info = AudioMetadataExtractor.extractQualityOnly(
                context.applicationContext,
                path,
                originalName.ifBlank { AudioLibraryHeuristics.fileNameFromPath(path) }
            )
            if (qualityView?.tag != token) return@launch
            renderQuality(context, qualityView, info, ext)
        }
    }

    private fun renderQuality(
        context: Context,
        view: TextView?,
        info: AudioTechnicalInfo?,
        ext: String
    ): Boolean {
        val lossless = info?.isLossless == true || ext in losslessExtensions
        return when {
            lossless -> {
                view?.text = context.getString(R.string.lossless_label)
                view?.visibility = View.VISIBLE
                true
            }
            (info?.bitrate ?: 0L) > 0L -> {
                view?.text = "${info!!.bitrate / 1000} kbps"
                view?.visibility = View.VISIBLE
                true
            }
            else -> false
        }
    }

    private fun normalizedExtension(path: String, name: String, fallback: String): String {
        fun ext(value: String): String {
            val clean = value.substringBefore('?').substringBefore('#').trim()
            val candidate = if (clean.contains('.')) clean.substringAfterLast('.') else clean
            return candidate.uppercase()
                .takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }.orEmpty()
        }
        return ext(fallback).ifBlank { ext(name) }.ifBlank {
            if (path.startsWith("content://", true)) "" else ext(path)
        }
    }
}
