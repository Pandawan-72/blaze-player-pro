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
import fr.retrospare.blazeplayer.utils.AudioArtworkHelper
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BrowserAdapter(
    val onFolderClick: (MediaItem) -> Unit,
    val onFileClick: (MediaItem) -> Unit,
    val onRemoveFromHistory: ((MediaItem) -> Unit)? = null
) : ListAdapter<MediaItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_FILE = 1
        private const val TYPE_FILE_GRID = 2
    }

    override fun getItemViewType(position: Int): Int =
if (getItem(position).mimeType == "folder") TYPE_FOLDER else if (isGridMode) TYPE_FILE_GRID else TYPE_FILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> FolderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))
            TYPE_FILE_GRID -> FileViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media_file_grid, parent, false))
            else -> FileViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media_file, parent, false))
        }
    }

    private val selectedItems = mutableSetOf<String>()
    var isGridMode = false
    var selectionMode = false
    var onSelectionChanged: ((Set<String>) -> Unit)? = null
    private var fullList: List<fr.retrospare.blazeplayer.data.model.MediaItem> = emptyList()
    private var currentQuery = ""

    fun getSelectedItems() = currentList.filter { selectedItems.contains(it.id) }
    fun clearSelection() { selectedItems.clear(); selectionMode = false; notifyDataSetChanged() }
    fun selectAll() { selectedItems.addAll(currentList.map { it.id }); notifyDataSetChanged() }

    fun setFullList(list: List<fr.retrospare.blazeplayer.data.model.MediaItem>) {
        fullList = list
        applyFilter()
    }

    fun filter(query: String) {
        currentQuery = query
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isEmpty()) fullList
        else fullList.filter { it.name.contains(currentQuery, ignoreCase = true) }
        // Force le refresh en soumettant null puis la nouvelle liste
        super.submitList(null)
        super.submitList(filtered)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is FileViewHolder) holder.thumbnailJob?.cancel()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is FolderViewHolder -> holder.bind(item, onFolderClick)
            is FileViewHolder -> holder.bind(item, onFileClick, onRemoveFromHistory, selectionMode, selectedItems, onSelectionChanged)
        }
    }

    inner class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvFolderName)
        private val tvCount: TextView = view.findViewById(R.id.tvFolderCount)
        fun bind(item: MediaItem, onClick: (MediaItem) -> Unit, onRemove: ((MediaItem) -> Unit)? = null, isSelectionMode: Boolean = false, selected: MutableSet<String> = mutableSetOf(), onSelectionChanged: ((Set<String>) -> Unit)? = null) {
            tvName.text = item.name
            tvCount.text = ""
            // Checkbox visibilité
            val checkbox = itemView.findViewById<android.widget.CheckBox>(fr.retrospare.blazeplayer.R.id.checkboxSelect)
            checkbox?.visibility = if (isSelectionMode) android.view.View.VISIBLE else android.view.View.GONE
            checkbox?.isChecked = selected.contains(item.id)
            checkbox?.setOnCheckedChangeListener(null)
            checkbox?.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(item.id) else selected.remove(item.id)
                onSelectionChanged?.invoke(selected.toSet())
            }
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    val checked = !selected.contains(item.id)
                    if (checked) selected.add(item.id) else selected.remove(item.id)
                    checkbox?.isChecked = checked
                    onSelectionChanged?.invoke(selected.toSet())
                } else {
                    onClick(item)
                }
            }
            itemView.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                    selectedItems.add(item.id)
                    notifyDataSetChanged()
                    onSelectionChanged?.invoke(selectedItems.toSet())
                }
                true
            }
        }
    }

    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvFileName)
        private val tvResolution: TextView = view.findViewById(R.id.tvResolution)
        private val scope = kotlinx.coroutines.MainScope()
        var thumbnailJob: kotlinx.coroutines.Job? = null
        private val tvFormat: TextView = view.findViewById(R.id.tvFormat)
        private val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        private val progressFill: View = view.findViewById(R.id.progressFill)
        private val btnMore: ImageView = view.findViewById(R.id.btnMore)
        private val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        private val ivPlayOverlay: ImageView = view.findViewById(R.id.ivPlayOverlay)
        private val tvVideoCodec: TextView = view.findViewById(R.id.tvVideoCodec)
        private val tvAudioCodec: TextView = view.findViewById(R.id.tvAudioCodec)

        fun bind(item: MediaItem, onClick: (MediaItem) -> Unit, onRemove: ((MediaItem) -> Unit)? = null, isSelectionMode: Boolean = false, selected: MutableSet<String> = mutableSetOf(), onSelectionChanged: ((Set<String>) -> Unit)? = null) {
            tvName.text = item.name
            tvFormat.text = item.extension.uppercase()
            val audioExts = setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")
            val isAudio = item.extension.lowercase() in audioExts
            if (isAudio) {
                tvFormat.setBackgroundResource(fr.retrospare.blazeplayer.R.drawable.bg_badge_orange)
                tvFormat.setTextColor(itemView.context.getColor(fr.retrospare.blazeplayer.R.color.orange_accent))
                // Artwork audio chargé via ThumbnailUtils (cache LRU)
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
            thumbnailJob?.cancel()
            ivThumbnail.setImageBitmap(null)
            ivPlayOverlay.visibility = View.VISIBLE

            // Charge le thumbnail si fichier local
            if (!item.isNetwork && item.path.isNotEmpty()) {
                thumbnailJob = scope.launch {
                    ThumbnailUtils.loadThumbnail(itemView.context, item.path, ivThumbnail)
                }
            }

            itemView.setOnClickListener { onClick(item) }
            btnMore.setOnClickListener { v ->
                val popup = android.widget.PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, "Lire")
                popup.menu.add(0, 2, 1, "Informations")
                                popup.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> { onClick(item); true }
                        2 -> {
                            val sz = android.text.format.Formatter.formatShortFileSize(v.context, item.size)
                            val dur = item.duration
                            val ds = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else "N/A"
                            val msg = "Taille : " + sz + " | Duree : " + ds + " | Format : " + item.extension.uppercase()
                            android.app.AlertDialog.Builder(v.context)
                                .setTitle(item.name)
                                .setMessage(msg)
                                .setPositiveButton("OK", null)
                                .show()
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(old: MediaItem, new: MediaItem) = old.id == new.id
        override fun areContentsTheSame(old: MediaItem, new: MediaItem) = old == new
    }
}
