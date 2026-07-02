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
                popup.menu.add(0, 1, 0, "Ajouter dossier favori")
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

        fun bind(item: MediaItem, onClick: (MediaItem) -> Unit, onRemove: ((MediaItem) -> Unit)? = null, isSelectionMode: Boolean = false, selected: MutableSet<String> = mutableSetOf(), onSelectionChanged: ((Set<String>) -> Unit)? = null) {
            tvName.text = item.name
            tvFormat.text = item.extension.uppercase()
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

            itemView.setOnClickListener { onClick(item) }
            btnMore.setOnClickListener { v ->
                val popup = android.widget.PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, "Lire")
                popup.menu.add(0, 2, 1, "Informations")
                                popup.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> { onClick(item); true }
                        2 -> {
                            scope.launch {
                                val info = fr.retrospare.blazeplayer.player.VideoMetadataExtractor.extract(v.context, item.path)
                                val sz = when {
                                    info.sizeBytes > 0 -> android.text.format.Formatter.formatShortFileSize(v.context, info.sizeBytes)
                                    item.size > 0 -> android.text.format.Formatter.formatShortFileSize(v.context, item.size)
                                    else -> "Inconnue"
                                }
                                val ds = if (info.duration > 0) info.formattedDuration
                                    else if (item.duration > 0) "%d:%02d".format(item.duration / 60, item.duration % 60)
                                    else "N/A"
                                val msg = "Chemin : ${item.path}\n\n" +
                                    "Conteneur : ${item.extension.uppercase()}\n" +
                                    "Durée : $ds\n" +
                                    "Taille : $sz"
                                android.app.AlertDialog.Builder(v.context)
                                    .setTitle(item.name)
                                    .setMessage(msg)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
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
