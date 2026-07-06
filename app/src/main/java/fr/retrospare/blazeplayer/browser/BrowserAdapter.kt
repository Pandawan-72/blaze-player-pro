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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        private val btnMore: ImageView? = view.findViewById(fr.retrospare.blazeplayer.R.id.btnFolderMore)
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
            btnMore?.setOnClickListener { v ->
                val popup = android.widget.PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, v.context.getString(R.string.dialog_add_favorite_folder))
                popup.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> {
                            val category = if (item.isNetwork) fr.retrospare.blazeplayer.favorites.FavoriteCategory.NETWORK
                                else fr.retrospare.blazeplayer.favorites.FavoriteCategory.LOCAL
                            val favorite = fr.retrospare.blazeplayer.favorites.FavoriteFolder(
                                path = item.path, name = item.name, shareId = item.networkShareId
                            )
                            fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showAddFavoriteDialog(v.context, category, favorite)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
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

        private fun isEventInsideChild(parent: View, child: View?, event: android.view.MotionEvent): Boolean {
            if (child == null || child.visibility != View.VISIBLE) return false
            val parentLocation = IntArray(2)
            val childLocation = IntArray(2)
            parent.getLocationOnScreen(parentLocation)
            child.getLocationOnScreen(childLocation)
            val rawX = parentLocation[0] + event.x
            val rawY = parentLocation[1] + event.y
            return rawX >= childLocation[0] && rawX <= childLocation[0] + child.width &&
                rawY >= childLocation[1] && rawY <= childLocation[1] + child.height
        }

        fun bind(item: MediaItem, onClick: (MediaItem) -> Unit, onRemove: ((MediaItem) -> Unit)? = null, isSelectionMode: Boolean = false, selected: MutableSet<String> = mutableSetOf(), onSelectionChanged: ((Set<String>) -> Unit)? = null) {
            tvName.text = item.name
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvFormat, item.extension)
            tvFormat.visibility = if (item.extension.isNotEmpty()) View.VISIBLE else View.GONE
            tvDuration.text = item.formattedDuration

            // Résolution - calcule depuis item.resolution comme les autres badges
            val rawRes = item.resolution ?: ""
            val badge = when {
                rawRes.contains("x", ignoreCase = true) || rawRes.contains("×") -> {
                    val h = rawRes.replace("×","x").substringAfter("x").toIntOrNull() ?: 0
                    when { h >= 1080 -> "FHD"; h >= 720 -> "HD"; h > 0 -> "SD"; else -> "" }
                }
                rawRes.isNotEmpty() -> rawRes
                else -> ""
            }
            tvResolution.text = badge
            tvResolution.visibility = if (badge.isNotEmpty()) View.VISIBLE else View.GONE

            // Codecs
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvResolution)
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvVideoCodec)
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvAudioCodec)
            tvVideoCodec.text = item.videoCodec ?: ""
            tvVideoCodec.visibility = if (!item.videoCodec.isNullOrEmpty()) View.VISIBLE else View.GONE
            tvAudioCodec.text = item.audioCodec ?: ""
            tvAudioCodec.visibility = if (!item.audioCodec.isNullOrEmpty()) View.VISIBLE else View.GONE

            if (item.duration > 0 && item.lastPosition > 0) {
                progressFill.visibility = View.VISIBLE
                val pct = item.lastPosition.toFloat() / item.duration
                val params = progressFill.layoutParams
                params.width = (itemView.width * pct).toInt()
                progressFill.layoutParams = params
            } else {
                progressFill.visibility = View.GONE
            }

            // Miniature à gauche du titre : cache RAM/disque en priorité, puis extraction
            // asynchrone bornée par ThumbnailUtils. Cela couvre local, réseau SMB et audio
            // affiché dans le navigateur sans bloquer le scroll.
            thumbnailJob?.cancel()
            (ivThumbnail.parent as? View)?.visibility = View.VISIBLE
            ivThumbnail.setImageDrawable(null)
            ivPlayOverlay.visibility = View.VISIBLE
            ivThumbnail.setTag(R.id.ivThumbnail, item.path)
            val cachedThumb = if (item.mimeType.startsWith("audio/") || item.extension.lowercase() in setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")) {
                fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(itemView.context, item.path)
                    ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else {
                fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedThumbnailBitmap(itemView.context, item.path)
            }
            if (cachedThumb != null) {
                ivThumbnail.setImageBitmap(cachedThumb)
            } else {
                thumbnailJob = scope.launch {
                    val bitmap = if (item.mimeType.startsWith("audio/") || item.extension.lowercase() in setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkJpegBytes(itemView.context, item.path)
                                ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    } else {
                        fr.retrospare.blazeplayer.ui.ThumbnailUtils.getThumbnailBitmap(itemView.context, item.path)
                    }
                    if (ivThumbnail.getTag(R.id.ivThumbnail) == item.path && bitmap != null) {
                        ivThumbnail.setImageBitmap(bitmap)
                    }
                }
            }

            // Case à cocher (sélection multiple) : toujours visible, comme dans le navigateur
            // audio, à gauche de la ligne. Cocher sélectionne le fichier ; taper la ligne l'ouvre
            // normalement (plus besoin d'appui long pour activer un "mode sélection").
            val checkbox = itemView.findViewById<android.widget.CheckBox>(fr.retrospare.blazeplayer.R.id.checkboxSelect)
            checkbox?.visibility = View.VISIBLE
            checkbox?.isChecked = selected.contains(item.id)
            checkbox?.setOnCheckedChangeListener(null)
            checkbox?.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(item.id) else selected.remove(item.id)
                onSelectionChanged?.invoke(selected.toSet())
            }

            itemView.setOnTouchListener(null)
            itemView.setOnClickListener { onClick(item) }
            btnMore.setOnClickListener { v ->
                val popup = android.widget.PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, v.context.getString(R.string.action_play))
                popup.menu.add(0, 2, 1, v.context.getString(R.string.action_information))
                                popup.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> { onClick(item); true }
                        2 -> {
                            fr.retrospare.blazeplayer.ui.VideoInfoDialog.show(
                                context = v.context,
                                scope = scope,
                                title = item.name,
                                mediaPath = item.path,
                                displayName = item.name,
                                extension = item.extension.uppercase(),
                                itemSizeBytes = item.size,
                                itemDurationSeconds = item.duration,
                                resolution = item.resolution,
                                videoCodec = item.videoCodec,
                                audioCodec = item.audioCodec,
                                fullExtract = false
                            )
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    private fun isVideoItem(item: MediaItem): Boolean {
        val ext = item.extension.lowercase()
        return item.mimeType.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "3gp", "ts")
    }

    class DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(old: MediaItem, new: MediaItem) = old.id == new.id
        override fun areContentsTheSame(old: MediaItem, new: MediaItem) = old == new
    }
}
