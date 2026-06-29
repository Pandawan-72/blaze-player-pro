package fr.retrospare.blazeplayer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R

class PlaylistAdapter(
    private val items: MutableList<PlaylistItem>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {
    private var currentPlayingIndex = -1

    fun setPlayingIndex(index: Int) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        if (old != -1 && old < items.size) notifyItemChanged(old)
        if (index != -1 && index < items.size) notifyItemChanged(index)
    }

    private var currentIndex = 0

    fun setCurrentIndex(index: Int) {
        val old = currentIndex
        currentIndex = index
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    fun addItem(item: PlaylistItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun getItems() = items.toList()

    fun removeItem(item: PlaylistItem) {
        val idx = items.indexOf(item)
        if (idx >= 0) { items.removeAt(idx); notifyItemRemoved(idx) }
    }

    fun clearAll() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == currentIndex, position == currentPlayingIndex)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvTrackName)
        private val tvIndex: TextView = view.findViewById(R.id.tvTrackIndex)
        private val indicator: View = view.findViewById(R.id.playingIndicator)

        fun bind(item: PlaylistItem, isCurrent: Boolean, isPlaying: Boolean) {
            tvName.text = item.name.substringBeforeLast(".")
            // Artiste depuis métadonnées
            val tvArtist = itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvTrackArtist)
            Thread {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    if (item.path.startsWith("content://"))
                        retriever.setDataSource(itemView.context, android.net.Uri.parse(item.path))
                    else retriever.setDataSource(item.path)
                    val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.ifEmpty { "Artiste inconnu" } ?: "Artiste inconnu"
                    val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val durationSec = durationMs / 1000
                    val art = retriever.embeddedPicture
                    retriever.release()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        // Artiste
                        tvArtist?.text = artist
                        tvArtist?.visibility = View.VISIBLE
                        // Codec
                        val ext = item.name.substringAfterLast(".", "").uppercase()
                        val tvCodec = itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvPlaylistCodec)
                        tvCodec?.text = ext
                        tvCodec?.visibility = if (ext.isNotEmpty()) View.VISIBLE else View.GONE
                        // Bitrate ou Lossless
                        val tvBitrate = itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvPlaylistBitrate)
                        val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
                        when {
                            lossless -> { tvBitrate?.text = "Lossless"; tvBitrate?.visibility = View.VISIBLE }
                            bitrate > 0 -> { tvBitrate?.text = "${bitrate / 1000} kbps"; tvBitrate?.visibility = View.VISIBLE }
                            else -> tvBitrate?.visibility = View.GONE
                        }
                        // Durée dans le titre : "nom - durée"
                        val durStr = if (durationSec > 0) {
                            val h = durationSec / 3600
                            val m = (durationSec % 3600) / 60
                            val s = durationSec % 60
                            if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
                        } else ""
                        val trackTitle = item.name.substringBeforeLast(".")
                        val tvName = itemView.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.tvTrackName)
                        tvName?.text = if (durStr.isNotEmpty()) "$trackTitle  •  $durStr" else trackTitle
                        // Cover
                        val ivCover = itemView.findViewById<android.widget.ImageView>(fr.retrospare.blazeplayer.R.id.ivPlaylistCover)
                        if (art != null) {
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                            ivCover?.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Exception) {}
            }.start()
            tvIndex.text = (adapterPosition + 1).toString()
            indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE

            // EQ animé
            val eqView = itemView.findViewById<fr.retrospare.blazeplayer.widget.MiniEqualizerView>(fr.retrospare.blazeplayer.R.id.eqView)
            if (isPlaying) {
                eqView?.visibility = android.view.View.VISIBLE
                eqView?.start()
            } else {
                eqView?.stop()
                eqView?.visibility = android.view.View.GONE
            }
            tvName.setTextColor(
                itemView.context.getColor(
                    if (isCurrent) R.color.green_accent else R.color.on_surface
                )
            )
            itemView.setOnClickListener { val pos = adapterPosition; if (pos != RecyclerView.NO_ID.toInt()) onItemClick(pos) }
        }
    }
}
