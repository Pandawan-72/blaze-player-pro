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

    companion object {
        // Pool limite a 2 threads concurrents pour eviter de saturer le NAS/reseau lors du scroll
        private val loadExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
        private const val PAYLOAD_TIME = "payload_time"
    }

    private var currentPlayingIndex = -1
    private var currentIndex = 0
    private var currentPositionMs = 0L
    private var currentDurationMs = 0L

    fun setPlayingIndex(index: Int) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        val count = itemCount
        if (old != -1 && old < count) notifyItemChanged(old)
        if (index != -1 && index < count) notifyItemChanged(index)
    }

    fun setCurrentIndex(index: Int) {
        val old = currentIndex
        currentIndex = index
        val count = itemCount
        if (old in 0 until count) notifyItemChanged(old)
        if (index in 0 until count) notifyItemChanged(index)
    }

    fun updateCurrentProgress(index: Int, positionMs: Long, durationMs: Long) {
        val oldIndex = currentIndex
        currentIndex = index
        currentPositionMs = positionMs.coerceAtLeast(0L)
        currentDurationMs = durationMs.coerceAtLeast(0L)
        val count = itemCount
        if (oldIndex != index && oldIndex in 0 until count) notifyItemChanged(oldIndex)
        if (index in 0 until count) notifyItemChanged(index, PAYLOAD_TIME)
    }

    /** A appeler a chaque changement de timeline (ajout/suppression/reorder) du Player. */
    fun refresh() {
        notifyDataSetChanged()
    }

    private fun itemAt(position: Int): MediaItem? = player()?.let {
        if (position in 0 until it.mediaItemCount) it.getMediaItemAt(position) else null
    }

    private fun pathAt(position: Int): String =
        itemAt(position)?.localConfiguration?.uri?.toString() ?: ""

    private fun nameAt(position: Int): String {
        val item = itemAt(position) ?: return ""
        val title = item.mediaMetadata.title?.toString()
        if (!title.isNullOrEmpty()) return title
        return item.localConfiguration?.uri?.lastPathSegment ?: ""
    }

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

    override fun getItemCount(): Int = player()?.mediaItemCount ?: 0

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvTrackName)
        private val tvIndex: TextView = view.findViewById(R.id.tvTrackIndex)
        private val indicator: View = view.findViewById(R.id.playingIndicator)
        private val queueCard: View = view.findViewById(R.id.queueCard)
        private val tvArtist: TextView? = view.findViewById(R.id.tvTrackArtist)
        private val tvCodec: TextView? = view.findViewById(R.id.tvPlaylistCodec)
        private val tvBitrate: TextView? = view.findViewById(R.id.tvPlaylistBitrate)

        @Volatile private var loadToken: String? = null

        fun cancelPendingLoad() {
            loadToken = null
        }

        fun bind(position: Int, isCurrent: Boolean, isPlaying: Boolean) {
            val path = pathAt(position)
            val name = nameAt(position)
            val trackTitle = name.substringBeforeLast(".")
            tvName.text = trackTitle
            tvIndex.text = (position + 1).toString()
            indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            queueCard.setBackgroundResource(if (isCurrent) R.drawable.bg_queue_card_current else R.drawable.bg_surface_card)

            // Affiche d'abord ce qu'on a directement depuis le MediaItem (rapide, pas de connexion)
            val mediaItem = itemAt(position)
            val metaArtist = mediaItem?.mediaMetadata?.artist?.toString()

            // Pas de pochette dans la file d'attente : pour des raisons de performance réseau
            // (un scroll dans une longue liste de morceaux réseau ne doit pas déclencher une
            // extraction par ligne). Les pochettes restent affichées dans le lecteur audio et
            // le mini-lecteur, qui n'en chargent qu'une à la fois.

            val cached = AudioMetadataExtractor.getCached(itemView.context, path)
            if (cached != null) {
                applyMeta(cached, trackTitle, tvArtist, tvCodec, tvBitrate, tvName, isCurrent)
            } else {
                tvArtist?.text = metaArtist ?: itemView.context.getString(R.string.unknown_artist)
                tvArtist?.visibility = View.VISIBLE
                tvCodec?.visibility = View.GONE
                tvBitrate?.visibility = View.GONE

                if (path.isNotEmpty()) {
                    val token = path
                    loadToken = token
                    loadExecutor.submit {
                        // Si la vue a deja ete recyclee pour un autre item avant meme le debut du chargement, on annule
                        if (loadToken != token) return@submit
                        // Extension toujours derivee du path (URI reelle), pas du nom affiche (titre sans extension)
                        val meta = kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                                AudioMetadataExtractor.extract(itemView.context, path, path.substringAfterLast("/"))
                            }
                        }
                        if (meta != null) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (loadToken == token) {
                                    applyMeta(meta, trackTitle, tvArtist, tvCodec, tvBitrate, tvName, isCurrent)
                                }
                            }
                        }
                    }
                }
            }

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

        private fun applyMeta(
            meta: AudioTechnicalInfo,
            trackTitle: String,
            tvArtist: TextView?,
            tvCodec: TextView?,
            tvBitrate: TextView?,
            tvName: TextView,
            isCurrent: Boolean
        ) {
            if (meta.title.isNotEmpty()) tvName.text = meta.title
            tvArtist?.text = meta.artist.ifEmpty { itemView.context.getString(R.string.unknown_artist) }
            tvArtist?.visibility = View.VISIBLE

            fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvCodec, meta.extension)
            tvCodec?.visibility = if (meta.extension.isNotEmpty()) View.VISIBLE else View.GONE

            applyTimeBadge(tvBitrate, meta.duration * 1000L, isCurrent)

            tvName.text = meta.title.ifEmpty { trackTitle }
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
