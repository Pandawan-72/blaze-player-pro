package fr.retrospare.blazeplayer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.playlist.PlaylistTrackRef

/**
 * Adapter independant pour la file Blaze Party.
 * Meme layout/code visuel que la file standard, mais source de verite = playlist Party sauvegardee.
 */
class PartyPlaylistAdapter(
    private val voteCountProvider: (String) -> Int = { 0 },
    private val onItemClick: (PlaylistTrackRef) -> Unit = {}
) : RecyclerView.Adapter<PartyPlaylistAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_TIME = "payload_time"
    }

    private var rawItems: List<PlaylistTrackRef> = emptyList()
    private var items: List<PlaylistTrackRef> = emptyList()
    private var currentPath: String? = null
    private var currentPositionMs: Long = 0L
    private var currentDurationMs: Long = 0L
    /** Compatibilité avec le contrôleur de scroll : aucune extraction de tags n'est lancée. */
    fun setMetadataLoadsEnabled(enabled: Boolean) = Unit

    fun submitList(newItems: List<PlaylistTrackRef>) {
        rawItems = newItems
        resortItems()
        notifyDataSetChanged()
    }

    fun setCurrentPath(path: String?) {
        val old = currentPath
        currentPath = path
        val oldIndex = items.indexOfFirst { it.path == old }
        val newIndex = items.indexOfFirst { it.path == path }
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
    }

    fun updateCurrentProgress(path: String?, positionMs: Long, durationMs: Long) {
        currentPath = path
        currentPositionMs = positionMs.coerceAtLeast(0L)
        currentDurationMs = durationMs.coerceAtLeast(0L)
        val index = items.indexOfFirst { it.path == path }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_TIME)
    }

    private fun resortItems() {
        // Le nombre de votes remplace le numéro de piste, mais l'hôte peut aussi indiquer
        // qu'un morceau a déjà été joué : il doit alors rester en bas de la file partagée.
        items = rawItems.withIndex()
            .sortedWith(
                compareBy<IndexedValue<PlaylistTrackRef>> { if (it.value.playedOrder > 0) 1 else 0 }
                    .thenByDescending { if (it.value.playedOrder == 0) voteCountProvider(it.value.path) else 0 }
                    .thenBy { if (it.value.playedOrder > 0) it.value.playedOrder else it.index }
            )
            .map { it.value }
    }

    fun refreshVotesAndOrder() {
        resortItems()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = items[position]
        holder.bind(track, track.path == currentPath)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TIME)) holder.updateTimeBadge(items.getOrNull(position)?.path == currentPath)
        else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelPendingLoad()
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvTrackName)
        private val tvIndex: TextView = view.findViewById(R.id.tvTrackIndex)
        private val indicator: View = view.findViewById(R.id.playingIndicator)
        private val queueCard: View = view.findViewById(R.id.queueCard)
        private val tvArtist: TextView? = view.findViewById(R.id.tvTrackArtist)
        private val tvCodec: TextView? = view.findViewById(R.id.tvPlaylistCodec)
        private val tvFormatBadge: TextView? = view.findViewById(R.id.tvPlaylistFormatBadge)
        private val tvTime: TextView? = view.findViewById(R.id.tvPlaylistBitrate)
        private val eqView: fr.retrospare.blazeplayer.widget.MiniEqualizerView? = view.findViewById(R.id.eqView)
        private var boundDurationMs: Long = 0L

        fun cancelPendingLoad() = Unit

        fun bind(track: PlaylistTrackRef, isCurrent: Boolean) {
            val path = track.path
            val folderMeta = AudioLibraryHeuristics.folderMetadata(path, track.name)
            val title = folderMeta.title.ifBlank { track.name.substringBeforeLast(".") }
            val voteCount = voteCountProvider(path)
            tvIndex.text = voteCount.toString()
            tvIndex.setTextColor(itemView.context.getColor(R.color.yellow_accent))
            tvName.text = title
            tvName.setTextColor(itemView.context.getColor(if (isCurrent) R.color.yellow_accent else R.color.on_surface))
            indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            try { indicator.backgroundTintList = android.content.res.ColorStateList.valueOf(itemView.context.getColor(R.color.yellow_accent)) } catch (_: Exception) {}
            queueCard.setBackgroundResource(if (isCurrent) R.drawable.bg_queue_card_party_current else R.drawable.bg_surface_card)

            if (folderMeta.artist.isNotBlank()) {
                tvArtist?.text = folderMeta.artist
                tvArtist?.visibility = View.VISIBLE
            } else {
                tvArtist?.text = ""
                tvArtist?.visibility = View.GONE
            }
            val cached = AudioMediaCache.getCachedMetadata(itemView.context, path)
            AudioQualityBadgeBinder.bind(tvCodec, tvFormatBadge, path, track.name, fallbackExt(track))
            boundDurationMs = (cached?.duration ?: 0L) * 1000L
            applyTimeBadge(isCurrent)


            if (isCurrent) { eqView?.visibility = View.VISIBLE; eqView?.start() } else { eqView?.stop(); eqView?.visibility = View.GONE }
            itemView.setOnClickListener { onItemClick(track) }
        }

        private fun fallbackExt(track: PlaylistTrackRef): String =
            track.extension.ifBlank { track.name.substringAfterLast('.', "").ifBlank { track.path.substringBefore('?').substringAfterLast('.', "") } }

        fun updateTimeBadge(isCurrent: Boolean) { applyTimeBadge(isCurrent) }

        private fun applyTimeBadge(isCurrent: Boolean) {
            val label = if (isCurrent && currentDurationMs > 0L) {
                "${formatMs(currentPositionMs)} / ${formatMs(currentDurationMs)}"
            } else if (boundDurationMs > 0L) {
                formatMs(boundDurationMs)
            } else ""
            if (label.isNotEmpty()) {
                tvTime?.text = label
                tvTime?.setTextColor(itemView.context.getColor(if (isCurrent) R.color.yellow_accent else R.color.on_surface_variant))
                tvTime?.visibility = View.VISIBLE
            } else tvTime?.visibility = View.GONE
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
