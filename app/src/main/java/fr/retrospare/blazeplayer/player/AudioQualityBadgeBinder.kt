package fr.retrospare.blazeplayer.player

import android.content.Context
import android.view.View
import android.widget.TextView
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.ui.BadgeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/** Source unique pour le couple de badges « conteneur + qualité ». */
object AudioQualityBadgeBinder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val qualityJobs = WeakHashMap<TextView, Job>()
    private val losslessExtensions = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV")

    fun bind(
        codecView: TextView?,
        qualityView: TextView?,
        path: String,
        originalName: String = "",
        fallbackExtension: String = "",
        codecTextColor: Int? = null,
        knownDurationMs: Long = 0L,
        knownBitrate: Long = 0L,
        knownSizeBytes: Long = 0L,
        textOnly: Boolean = false
    ) {
        val context = codecView?.context ?: qualityView?.context ?: return
        val ext = normalizedExtension(path, originalName, fallbackExtension)
        if (ext.isNotBlank()) {
            if (textOnly) applyTextOnlyStyle(codecView) else BadgeStyle.applyContainerBadge(codecView, ext)
            codecTextColor?.let { codecView?.setTextColor(it) }
            codecView?.visibility = View.VISIBLE
        } else {
            codecView?.text = ""
            codecView?.visibility = View.GONE
        }

        val view = qualityView ?: return
        qualityJobs.remove(view)?.cancel()
        if (textOnly) applyTextOnlyStyle(view) else BadgeStyle.applyTechnicalBadge(view)
        view.text = ""
        view.visibility = View.GONE

        // Le caractère lossless dépend uniquement du conteneur : rendu immédiat, sans accès au
        // fichier ni au NAS. Le badge ne doit jamais disparaître pendant une indexation.
        if (renderQuality(context, view, AudioTechnicalInfo(extension = ext), ext)) return
        if (
            knownBitrate > 0L &&
            renderQuality(
                context,
                view,
                AudioTechnicalInfo(
                    bitrate = knownBitrate,
                    extension = ext
                ),
                ext
            )
        ) return

        val cached = path.takeIf { it.isNotBlank() }
            ?.let { AudioMetadataExtractor.getCached(context.applicationContext, it) }
        if (renderQuality(context, view, cached, ext)) return

        val immediateEstimate = estimateTechnicalInfo(ext, knownDurationMs, knownSizeBytes)
        if (immediateEstimate != null) {
            renderQuality(context, view, immediateEstimate, ext)
            if (path.isNotBlank()) {
                scope.launch(AudioPlaybackDispatchers.io) {
                    AudioMetadataExtractor.putCached(context.applicationContext, path, immediateEstimate)
                }
            }
            return
        }
        if (path.isBlank() || ext.isBlank()) return

        // Un token objet neuf évite qu'un résultat d'une ancienne ligne recyclée réapparaisse sur
        // une autre ligne portant éventuellement le même chemin texte.
        val token = Any()
        view.tag = token
        val job = scope.launch {
            // Room contient déjà taille et durée après le scan. On calcule d'abord le débit moyen
            // depuis cette persistance locale, sans ouvrir une seconde fois le fichier réseau.
            val snapshot = withContext(AudioPlaybackDispatchers.io) {
                runCatching {
                    AudioLibraryRoomStore.loadTechnicalSnapshot(context.applicationContext, path)
                }.getOrNull()
            }
            if (view.tag !== token) return@launch

            val roomBitrate = snapshot?.bitrate
                ?.takeIf { it > 0L }
            if (roomBitrate != null) {
                val roomInfo = AudioTechnicalInfo(
                    duration = snapshot?.durationMs
                        ?.takeIf { it > 0L }
                        ?.let { ((it + 500L) / 1000L).coerceAtLeast(1L) }
                        ?: 0L,
                    bitrate = roomBitrate,
                    extension = snapshot?.extension.orEmpty().ifBlank { ext }
                )
                withContext(AudioPlaybackDispatchers.compute) {
                    AudioLibraryMemoryStore.updateMetadata(mapOf(path to roomInfo))
                }
                if (view.tag === token && renderQuality(context, view, roomInfo, ext)) {
                    return@launch
                }
            }

            val roomEstimate = estimateTechnicalInfo(
                ext = snapshot?.extension?.takeIf { it.isNotBlank() } ?: ext,
                durationMs = knownDurationMs.takeIf { it > 0L } ?: snapshot?.durationMs ?: 0L,
                sizeBytes = knownSizeBytes.takeIf { it > 0L } ?: snapshot?.sizeBytes ?: 0L
            )
            if (roomEstimate != null) {
                withContext(AudioPlaybackDispatchers.io) {
                    AudioMetadataExtractor.putCached(context.applicationContext, path, roomEstimate)
                }
                withContext(AudioPlaybackDispatchers.compute) {
                    AudioLibraryMemoryStore.updateMetadata(mapOf(path to roomEstimate))
                }
                if (view.tag === token) renderQuality(context, view, roomEstimate, ext)
                return@launch
            }

            // Aucun accès au fichier audio depuis un bind RecyclerView. L'unique pipeline du
            // repository hydratera le snapshot pendant une vraie fenêtre d'inactivité. Cela évite
            // qu'un simple changement d'écran concurrence Media3 ou fige l'égaliseur visuel.
        }
        qualityJobs[view] = job
        job.invokeOnCompletion {
            scope.launch {
                if (qualityJobs[view] === job) qualityJobs.remove(view)
            }
        }
    }


    private fun applyTextOnlyStyle(view: TextView?) {
        view ?: return
        view.background = null
        view.setPadding(0, 0, 0, 0)
        view.includeFontPadding = false
        view.minWidth = 0
        view.setTypeface(
            android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD),
            android.graphics.Typeface.BOLD
        )
    }

    private fun estimateTechnicalInfo(ext: String, durationMs: Long, sizeBytes: Long): AudioTechnicalInfo? {
        val normalizedExt = ext.uppercase()
        if (normalizedExt in losslessExtensions) {
            return AudioTechnicalInfo(extension = normalizedExt, isLossless = true)
        }
        if (durationMs <= 0L || sizeBytes <= 0L) return null
        val bitrate = ((sizeBytes.toDouble() * 8_000.0) / durationMs.toDouble()).toLong()
        if (bitrate !in 8_000L..10_000_000L) return null
        return AudioTechnicalInfo(
            duration = ((durationMs + 500L) / 1000L).coerceAtLeast(1L),
            bitrate = bitrate,
            extension = normalizedExt,
            isLossless = false
        )
    }

    private fun renderQuality(
        context: Context,
        view: TextView?,
        info: AudioTechnicalInfo?,
        ext: String
    ): Boolean {
        val lossless = info?.isLossless == true || ext.uppercase() in losslessExtensions
        return when {
            lossless -> {
                view?.text = context.getString(R.string.lossless_label)
                view?.visibility = View.VISIBLE
                view?.requestLayout()
                (view?.parent as? View)?.requestLayout()
                true
            }
            (info?.bitrate ?: 0L) > 0L -> {
                view?.text = "${info!!.bitrate / 1000} kbps"
                view?.visibility = View.VISIBLE
                view?.requestLayout()
                (view?.parent as? View)?.requestLayout()
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
