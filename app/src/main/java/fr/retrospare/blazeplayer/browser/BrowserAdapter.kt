package fr.retrospare.blazeplayer.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BrowserAdapter(
    private val onFolderClick: (MediaItem) -> Unit,
    private val onFileClick: (MediaItem) -> Unit
) : ListAdapter<MediaItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_FILE = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).mimeType == "folder") TYPE_FOLDER else TYPE_FILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_FOLDER) {
            FolderViewHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_folder, parent, false))
        } else {
            FileViewHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_file, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is FolderViewHolder -> holder.bind(item, onFolderClick)
            is FileViewHolder -> holder.bind(item, onFileClick)
        }
    }

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvFolderName)
        private val tvCount: TextView = view.findViewById(R.id.tvFolderCount)
        fun bind(item: MediaItem, onClick: (MediaItem) -> Unit) {
            tvName.text = item.name
            tvCount.text = ""
            itemView.setOnClickListener { onClick(item) }
        }
    }

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvFileName)
        private val tvResolution: TextView = view.findViewById(R.id.tvResolution)
        private val tvFormat: TextView = view.findViewById(R.id.tvFormat)
        private val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        private val progressFill: View = view.findViewById(R.id.progressFill)
        private val btnMore: ImageView = view.findViewById(R.id.btnMore)
        private val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        private val ivPlayOverlay: ImageView = view.findViewById(R.id.ivPlayOverlay)
        private val tvVideoCodec: TextView = view.findViewById(R.id.tvVideoCodec)
        private val tvAudioCodec: TextView = view.findViewById(R.id.tvAudioCodec)

        fun bind(item: MediaItem, onClick: (MediaItem) -> Unit) {
            tvName.text = item.name
            tvFormat.text = item.extension.uppercase()
            // Badge orange pour les fichiers audio
            val audioExts = setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")
            if (item.extension.lowercase() in audioExts) {
                tvFormat.setBackgroundResource(fr.retrospare.blazeplayer.R.drawable.bg_badge_orange)
                tvFormat.setTextColor(itemView.context.getColor(fr.retrospare.blazeplayer.R.color.orange_accent))
            } else {
                tvFormat.setBackgroundResource(fr.retrospare.blazeplayer.R.drawable.bg_badge_gray)
                tvFormat.setTextColor(itemView.context.getColor(fr.retrospare.blazeplayer.R.color.on_surface_variant))
            }
            tvDuration.text = item.formattedDuration

            // Résolution
            tvResolution.visibility = if (item.resolution != null) View.VISIBLE else View.GONE
            tvResolution.text = item.resolution ?: ""

            // Codecs
            if (!item.videoCodec.isNullOrEmpty()) {
                tvVideoCodec.visibility = View.VISIBLE
                tvVideoCodec.text = item.videoCodec
            } else tvVideoCodec.visibility = View.GONE

            if (!item.audioCodec.isNullOrEmpty()) {
                tvAudioCodec.visibility = View.VISIBLE
                tvAudioCodec.text = item.audioCodec
            } else tvAudioCodec.visibility = View.GONE

            if (item.duration > 0 && item.lastPosition > 0) {
                progressFill.visibility = View.VISIBLE
                val pct = item.lastPosition.toFloat() / item.duration
                val params = progressFill.layoutParams
                params.width = (itemView.width * pct).toInt()
                progressFill.layoutParams = params
            } else {
                progressFill.visibility = View.GONE
            }

            // Reset thumbnail
            ivThumbnail.setImageBitmap(null)
            ivPlayOverlay.visibility = View.VISIBLE

            // Charge le thumbnail si fichier local
            if (!item.isNetwork && item.path.isNotEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    ThumbnailUtils.loadThumbnail(
                        itemView.context, item.path, ivThumbnail
                    )
                    if (ivThumbnail.drawable != null) {
                        ivPlayOverlay.visibility = View.GONE
                    }
                }
            }

            itemView.setOnClickListener { onClick(item) }
            btnMore.setOnClickListener { }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(old: MediaItem, new: MediaItem) = old.id == new.id
        override fun areContentsTheSame(old: MediaItem, new: MediaItem) = old == new
    }
}
