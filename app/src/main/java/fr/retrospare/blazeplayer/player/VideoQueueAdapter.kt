package fr.retrospare.blazeplayer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.playlist.PlaylistTrackRef

/** Même item visuel que la file audio (item_playlist), adapté aux métadonnées vidéo. */
class VideoQueueAdapter(
    tracks: List<PlaylistTrackRef>,
    private val currentPath: String? = null,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<VideoQueueAdapter.ViewHolder>() {

    private var tracks: MutableList<PlaylistTrackRef> = tracks.toMutableList()

    init {
        setHasStableIds(true)
    }

    fun submit(newTracks: List<PlaylistTrackRef>) {
        tracks = newTracks.toMutableList()
        notifyDataSetChanged()
    }

    fun currentItems(): List<PlaylistTrackRef> = tracks.toList()

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in tracks.indices || toPosition !in tracks.indices || fromPosition == toPosition) return false
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) Collections.swap(tracks, i, i + 1)
        } else {
            for (i in fromPosition downTo toPosition + 1) Collections.swap(tracks, i, i - 1)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun getItemId(position: Int): Long = tracks.getOrNull(position)?.path?.hashCode()?.toLong() ?: RecyclerView.NO_ID

    override fun getItemCount(): Int = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(tracks[position], position, tracks[position].path == currentPath)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvTrackName)
        private val tvIndex: TextView = view.findViewById(R.id.tvTrackIndex)
        private val indicator: View = view.findViewById(R.id.playingIndicator)
        private val queueCard: View = view.findViewById(R.id.queueCard)
        private val tvSubtitle: TextView? = view.findViewById(R.id.tvTrackArtist)
        private val tvContainer: TextView? = view.findViewById(R.id.tvPlaylistCodec)
        private val tvQuality: TextView? = view.findViewById(R.id.tvPlaylistFormatBadge)
        private val tvTime: TextView? = view.findViewById(R.id.tvPlaylistBitrate)
        private val eqView: fr.retrospare.blazeplayer.widget.MiniEqualizerView? = view.findViewById(R.id.eqView)

        fun bind(track: PlaylistTrackRef, position: Int, isCurrent: Boolean) {
            val title = track.name.substringBeforeLast('.').ifBlank { track.name }
            tvName.text = title
            tvIndex.text = (position + 1).toString()
            indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            queueCard.setBackgroundResource(if (isCurrent) R.drawable.bg_queue_card_current else R.drawable.bg_surface_card)
            tvName.setTextColor(itemView.context.getColor(if (isCurrent) R.color.green_accent else R.color.on_surface))

            val ext = track.extension.ifBlank {
                track.name.substringAfterLast('.', "").ifBlank {
                    track.path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
                }
            }.uppercase()
            if (ext.isNotBlank()) {
                fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvContainer, ext)
                tvContainer?.visibility = View.VISIBLE
            } else {
                tvContainer?.visibility = View.GONE
            }

            val quality = track.videoQuality.ifBlank {
                VideoMetadataExtractor.getCached(itemView.context, track.path, track.sizeBytes)?.qualityBadge.orEmpty()
            }
            if (quality.isNotBlank()) {
                tvQuality?.text = quality
                fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvQuality)
                tvQuality?.visibility = View.VISIBLE
            } else {
                tvQuality?.visibility = View.GONE
            }

            val cached = VideoMetadataExtractor.getCached(itemView.context, track.path, track.sizeBytes)
            val durationMs = when {
                track.durationMs > 0L -> track.durationMs
                cached != null && cached.duration > 0L -> cached.duration * 1000L
                else -> 0L
            }
            if (durationMs > 0L) {
                tvTime?.text = formatMs(durationMs)
                tvTime?.setTextColor(itemView.context.getColor(if (isCurrent) R.color.green_accent else R.color.on_surface_variant))
                tvTime?.visibility = View.VISIBLE
            } else {
                tvTime?.visibility = View.GONE
            }

            val detail = listOf(track.videoCodec, track.audioCodec).filter { it.isNotBlank() }.joinToString(" • ")
            if (detail.isNotBlank()) {
                tvSubtitle?.text = detail
                tvSubtitle?.visibility = View.VISIBLE
            } else {
                tvSubtitle?.visibility = View.GONE
            }

            if (isCurrent) {
                eqView?.visibility = View.VISIBLE
                eqView?.start()
            } else {
                eqView?.stop()
                eqView?.visibility = View.GONE
            }

            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(pos)
            }
            itemView.setOnLongClickListener(null)
        }

        private fun formatMs(ms: Long): String {
            val total = (ms / 1000L).coerceAtLeast(0L)
            val h = total / 3600L
            val m = (total % 3600L) / 60L
            val s = total % 60L
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
