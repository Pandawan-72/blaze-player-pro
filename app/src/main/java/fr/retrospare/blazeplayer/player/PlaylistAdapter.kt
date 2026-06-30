package fr.retrospare.blazeplayer.player

import android.util.LruCache
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

    data class TrackMeta(
        val artist: String,
        val codec: String,
        val bitrateLabel: String,
        val durationLabel: String,
        val artworkBitmap: android.graphics.Bitmap?
    )

    companion object {
        private val metaCache = object : LruCache<String, TrackMeta>(60) {}
        // Pool limite a 2 threads concurrents pour eviter de saturer le NAS/reseau lors du scroll
        private val loadExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    }

    private var currentPlayingIndex = -1
    private var currentIndex = 0

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

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelPendingLoad()
    }

    override fun getItemCount(): Int = player()?.mediaItemCount ?: 0

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvTrackName)
        private val tvIndex: TextView = view.findViewById(R.id.tvTrackIndex)
        private val indicator: View = view.findViewById(R.id.playingIndicator)

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

            val tvArtist = itemView.findViewById<TextView>(R.id.tvTrackArtist)
            val tvCodec = itemView.findViewById<TextView>(R.id.tvPlaylistCodec)
            val tvBitrate = itemView.findViewById<TextView>(R.id.tvPlaylistBitrate)
            val ivCover = itemView.findViewById<android.widget.ImageView>(R.id.ivPlaylistCover)

            // Affiche d'abord ce qu'on a directement depuis le MediaItem (rapide, pas de connexion)
            val mediaItem = itemAt(position)
            val metaArtist = mediaItem?.mediaMetadata?.artist?.toString()
            val artworkData = mediaItem?.mediaMetadata?.artworkData

            val cached = metaCache.get(path)
            if (cached != null) {
                applyMeta(cached, trackTitle, tvArtist, tvCodec, tvBitrate, ivCover, tvName)
            } else {
                tvArtist?.text = metaArtist ?: "Artiste inconnu"
                tvArtist?.visibility = View.VISIBLE
                tvCodec?.visibility = View.GONE
                tvBitrate?.visibility = View.GONE
                if (artworkData != null) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
                    ivCover?.setImageBitmap(bmp)
                } else {
                    ivCover?.setImageDrawable(null)
                }

                if (path.isNotEmpty()) {
                    val token = path
                    loadToken = token
                    loadExecutor.submit {
                        // Si la vue a deja ete recyclee pour un autre item avant meme le debut du chargement, on annule
                        if (loadToken != token) return@submit
                        // Extension toujours derivee du path (URI reelle), pas du nom affiche (titre sans extension)
                        val meta = extractTrackMeta(path, path.substringAfterLast("/"))
                        metaCache.put(path, meta)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (loadToken == token) {
                                applyMeta(meta, trackTitle, tvArtist, tvCodec, tvBitrate, ivCover, tvName)
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
            meta: TrackMeta,
            trackTitle: String,
            tvArtist: TextView?,
            tvCodec: TextView?,
            tvBitrate: TextView?,
            ivCover: android.widget.ImageView?,
            tvName: TextView
        ) {
            tvArtist?.text = meta.artist
            tvArtist?.visibility = View.VISIBLE

            tvCodec?.text = meta.codec
            tvCodec?.visibility = if (meta.codec.isNotEmpty()) View.VISIBLE else View.GONE

            if (meta.bitrateLabel.isNotEmpty()) {
                tvBitrate?.text = meta.bitrateLabel
                tvBitrate?.visibility = View.VISIBLE
            } else {
                tvBitrate?.visibility = View.GONE
            }

            tvName.text = if (meta.durationLabel.isNotEmpty()) "$trackTitle  •  ${meta.durationLabel}" else trackTitle

            if (meta.artworkBitmap != null) {
                ivCover?.setImageBitmap(meta.artworkBitmap)
            }
        }

        private fun extractTrackMeta(path: String, name: String): TrackMeta {
            var smbDataSource: SmbMediaDataSource? = null
            return try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    when {
                        path.startsWith("smb://") -> {
                            smbDataSource = SmbMediaDataSource(path)
                            retriever.setDataSource(smbDataSource)
                        }
                        path.startsWith("content://") -> retriever.setDataSource(itemView.context, android.net.Uri.parse(path))
                        else -> retriever.setDataSource(path)
                    }

                    val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.ifEmpty { null } ?: "Artiste inconnu"
                    val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val durationSec = durationMs / 1000
                    val art = retriever.embeddedPicture
                    val bmp = art?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }

                    val ext = name.substringAfterLast(".", "").uppercase()
                    val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
                    val bitrateLabel = when {
                        lossless -> "Lossless"
                        bitrate > 0 -> "${bitrate / 1000} kbps"
                        else -> ""
                    }
                    val durationLabel = if (durationSec > 0) {
                        val h = durationSec / 3600
                        val m = (durationSec % 3600) / 60
                        val s = durationSec % 60
                        if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
                    } else ""

                    TrackMeta(artist, ext, bitrateLabel, durationLabel, bmp)
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
                TrackMeta("Artiste inconnu", "", "", "", null)
            } finally {
                try { smbDataSource?.close() } catch (_: Exception) {}
            }
        }
    }
}
