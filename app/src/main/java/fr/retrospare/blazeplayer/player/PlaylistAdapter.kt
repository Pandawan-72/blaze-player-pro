package fr.retrospare.blazeplayer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R

/**
 * Adapter dont la SEULE source de verite est le Player (MediaController) lui-meme.
 * Pas de liste interne maintenue manuellement : on lit toujours player.getMediaItemAt(i)
 * et player.mediaItemCount. Cela elimine les desynchronisations UI <-> lecteur.
 */
class PlaylistAdapter(
    private val player: () -> Player?,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    /** Ligne artiste uniquement, en gras mais dans la couleur neutre du layout. La coloration
     *  dynamique reste réservée au lecteur et aux mini-players, pas aux files d'attente. */
    private fun buildArtistText(artist: String): CharSequence {
        val builder = android.text.SpannableStringBuilder(artist)
        builder.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0, artist.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    companion object {
        private const val PAYLOAD_TIME = "payload_time"
    }

    private var currentPlayingIndex = -1
    private var currentIndex = 0
    private var currentPositionMs = 0L
    private var currentDurationMs = 0L
    private var overrideItems: List<MediaItem>? = null
    private var queueDragInProgress = false
    /** Conservé pour l'API de la feuille de file : aucune extraction de tags n'est désormais
     *  déclenchée au scroll, donc il n'y a plus rien à suspendre/réactiver. */
    fun setMetadataLoadsEnabled(enabled: Boolean) = Unit

    /**
     * File affichée indépendante du Player. Utilisé pendant Blaze Party pour que la
     * file locale personnelle de l’hôte ne soit jamais remplacée visuellement par
     * la file Party, même si le Player lit temporairement la Party.
     */
    fun setOverrideItems(items: List<MediaItem>?) {
        overrideItems = items
        currentIndex = currentIndex.coerceAtMost((items?.size ?: (player()?.mediaItemCount ?: 1)) - 1).coerceAtLeast(0)
        if (!queueDragInProgress) notifyDataSetChanged()
    }

    /**
     * Active une copie locale de la file pour le drag & drop sans notifier toute la liste au
     * moment où le doigt commence à bouger. Un notifyDataSetChanged() ici invaliderait le
     * ViewHolder pris en charge par ItemTouchHelper et faisait échouer certains déplacements
     * (notamment quand on remontait le 3e élément).
     */
    fun beginDragOverrideItems(items: List<MediaItem>, selectedIndex: Int) {
        overrideItems = items
        currentIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun setQueueDragInProgress(active: Boolean) {
        queueDragInProgress = active
    }

    fun hasOverrideItems(): Boolean = overrideItems != null

    fun overrideItemsSnapshot(): List<MediaItem> = overrideItems.orEmpty()

    fun moveOverrideItem(fromPosition: Int, toPosition: Int): Boolean {
        val current = overrideItems?.toMutableList() ?: return false
        if (fromPosition !in current.indices || toPosition !in current.indices || fromPosition == toPosition) return false
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) java.util.Collections.swap(current, i, i + 1)
        } else {
            for (i in fromPosition downTo toPosition + 1) java.util.Collections.swap(current, i, i - 1)
        }
        overrideItems = current
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun overrideItemAt(position: Int): MediaItem? = overrideItems?.let {
        if (position in it.indices) it[position] else null
    }

    fun setPlayingIndex(index: Int) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        if (queueDragInProgress) return
        val count = itemCount
        if (old != -1 && old < count) notifyItemChanged(old)
        if (index != -1 && index < count) notifyItemChanged(index)
    }

    fun setCurrentIndex(index: Int) {
        val old = currentIndex
        currentIndex = index
        if (queueDragInProgress) return
        val count = itemCount
        if (old in 0 until count) notifyItemChanged(old)
        if (index in 0 until count) notifyItemChanged(index)
    }

    fun updateCurrentProgress(index: Int, positionMs: Long, durationMs: Long) {
        val oldIndex = currentIndex
        currentIndex = index
        currentPositionMs = positionMs.coerceAtLeast(0L)
        currentDurationMs = durationMs.coerceAtLeast(0L)
        if (queueDragInProgress) return
        val count = itemCount
        if (oldIndex != index && oldIndex in 0 until count) notifyItemChanged(oldIndex)
        if (index in 0 until count) notifyItemChanged(index, PAYLOAD_TIME)
    }

    /** A appeler a chaque changement de timeline (ajout/suppression/reorder) du Player. */
    fun refresh() {
        if (!queueDragInProgress) notifyDataSetChanged()
    }

    private fun itemAt(position: Int): MediaItem? {
        overrideItems?.let { return if (position in it.indices) it[position] else null }
        return player()?.let { if (position in 0 until it.mediaItemCount) it.getMediaItemAt(position) else null }
    }

    private fun pathAt(position: Int): String {
        val item = itemAt(position) ?: return ""
        return item.mediaMetadata.extras?.getString("blaze_original_path")?.takeIf { it.isNotBlank() }
            ?: item.mediaId.takeIf { it.isNotBlank() }
            ?: item.localConfiguration?.uri?.toString().orEmpty()
    }

    private fun nameAt(position: Int): String {
        val item = itemAt(position) ?: return ""
        val path = pathAt(position)
        if (path.startsWith("content://", ignoreCase = true)) {
            return item.mediaMetadata.extras?.getString("blaze_original_name").orEmpty().ifBlank {
                item.localConfiguration?.uri?.lastPathSegment.orEmpty()
            }
        }
        return AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
            item.localConfiguration?.uri?.lastPathSegment.orEmpty()
        }
    }

    private fun containerExtFor(item: MediaItem?, name: String, path: String): String =
        item?.mediaMetadata?.extras
            ?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION)
            ?.takeIf { it.isNotBlank() }
            ?: name.substringAfterLast(".", "").takeIf { it.isNotBlank() }
            ?: path.substringBefore('?').substringAfterLast(".", "")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position, position == currentIndex, position == currentPlayingIndex)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TIME)) {
            holder.updateTimeBadge(position == currentIndex)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelPendingLoad()
    }

    override fun getItemCount(): Int = overrideItems?.size ?: (player()?.mediaItemCount ?: 0)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvTrackName)
        private val tvIndex: TextView = view.findViewById(R.id.tvTrackIndex)
        private val indicator: View = view.findViewById(R.id.playingIndicator)
        private val queueCard: View = view.findViewById(R.id.queueCard)
        private val tvArtist: TextView? = view.findViewById(R.id.tvTrackArtist)
        private val tvCodec: TextView? = view.findViewById(R.id.tvPlaylistCodec)
        private val tvFormatBadge: TextView? = view.findViewById(R.id.tvPlaylistFormatBadge)
        private val tvBitrate: TextView? = view.findViewById(R.id.tvPlaylistBitrate)

        fun cancelPendingLoad() = Unit

        fun bind(position: Int, isCurrent: Boolean, isPlaying: Boolean) {
            val path = pathAt(position)
            val name = nameAt(position)
            val trackTitle = name.substringBeforeLast(".")
            tvName.text = trackTitle
            tvIndex.text = (position + 1).toString()
            indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            queueCard.setBackgroundResource(if (isCurrent) R.drawable.bg_queue_card_current else R.drawable.bg_surface_card)

            // Les libellés de la file viennent uniquement de l'arborescence, sans extraction
            // ID3/FLAC. Les données techniques éventuelles sont seulement relues du cache.
            val mediaItem = itemAt(position)
            val folderMeta = AudioLibraryHeuristics.folderMetadata(path, name)
            tvName.text = folderMeta.title.ifBlank { trackTitle }
            if (folderMeta.artist.isNotBlank()) {
                tvArtist?.text = buildArtistText(folderMeta.artist)
                tvArtist?.visibility = View.VISIBLE
            } else {
                tvArtist?.text = ""
                tvArtist?.visibility = View.GONE
            }
            val cached = AudioMediaCache.getCachedMetadata(itemView.context, path)
            val extension = containerExtFor(mediaItem, name, path)
            AudioQualityBadgeBinder.bind(tvCodec, tvFormatBadge, path, name, extension)
            applyTimeBadge(tvBitrate, (cached?.duration ?: 0L) * 1000L, isCurrent)

            val eqView = itemView.findViewById<fr.retrospare.blazeplayer.widget.MiniEqualizerView>(R.id.eqView)
            if (isPlaying) {
                eqView?.visibility = View.VISIBLE
                eqView?.start()
            } else {
                eqView?.stop()
                eqView?.visibility = View.GONE
            }
            tvName.setTextColor(
                itemView.context.getColor(if (isCurrent) R.color.green_accent else R.color.on_surface)
            )
            itemView.setOnClickListener { val pos = adapterPosition; if (pos != RecyclerView.NO_ID.toInt()) onItemClick(pos) }
        }

        private fun applyTechnicalMeta(
            meta: AudioTechnicalInfo?,
            fallbackExtension: String,
            tvCodec: TextView?,
            tvFormatBadge: TextView?,
            tvTime: TextView?,
            isCurrent: Boolean
        ) {
            val ext = meta?.extension?.ifBlank { fallbackExtension }?.uppercase()
                ?: fallbackExtension.uppercase()
            if (ext.isNotEmpty()) {
                fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvCodec, ext)
                tvCodec?.visibility = View.VISIBLE
            } else {
                tvCodec?.visibility = View.GONE
            }
            val lossless = meta?.isLossless == true || ext in setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV")
            when {
                lossless -> {
                    tvFormatBadge?.text = itemView.context.getString(R.string.lossless_label)
                    tvFormatBadge?.visibility = View.VISIBLE
                }
                (meta?.bitrate ?: 0L) > 0L -> {
                    tvFormatBadge?.text = "${meta!!.bitrate / 1000} kbps"
                    tvFormatBadge?.visibility = View.VISIBLE
                }
                else -> tvFormatBadge?.visibility = View.GONE
            }
            applyTimeBadge(tvTime, (meta?.duration ?: 0L) * 1000L, isCurrent)
        }

        fun updateTimeBadge(isCurrent: Boolean) {
            val fallbackDurationMs = if (currentDurationMs > 0L) currentDurationMs else 0L
            applyTimeBadge(tvBitrate, fallbackDurationMs, isCurrent)
        }

        private fun applyTimeBadge(tvTime: TextView?, durationMs: Long, isCurrent: Boolean) {
            val label = if (isCurrent && currentDurationMs > 0L) {
                "${formatMs(currentPositionMs)} / ${formatMs(currentDurationMs)}"
            } else if (durationMs > 0L) {
                formatMs(durationMs)
            } else {
                ""
            }
            if (label.isNotEmpty()) {
                tvTime?.text = label
                tvTime?.setTextColor(itemView.context.getColor(if (isCurrent) R.color.green_accent else R.color.on_surface_variant))
                tvTime?.visibility = View.VISIBLE
            } else {
                tvTime?.visibility = View.GONE
            }
        }

        private fun formatMs(ms: Long): String {
            val total = (ms / 1000L).coerceAtLeast(0L)
            val h = total / 3600L
            val m = (total % 3600L) / 60L
            val sec = total % 60L
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
