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
import fr.retrospare.blazeplayer.player.AudioQualityBadgeBinder
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

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        val isFolderLike = item.mimeType == "folder" || item.mimeType == "share" || item.mimeType == "network"
        return if (isFolderLike) TYPE_FOLDER else if (isGridMode) TYPE_FILE_GRID else TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> FolderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))
            TYPE_FILE_GRID -> FileViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media_file_grid, parent, false))
            else -> FileViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media_file, parent, false))
        }
    }

    private val selectedItems = linkedSetOf<String>()
    var isGridMode = false
    var selectionMode = false
    var onSelectionChanged: ((Set<String>) -> Unit)? = null
    private var fullList: List<fr.retrospare.blazeplayer.data.model.MediaItem> = emptyList()
    private var currentQuery = ""

    /**
     * La sélection est indexée par chemin, pas par l'id temporaire de la ligne. Les listes du
     * navigateur sont régulièrement remplacées pendant l'enrichissement des métadonnées ; le
     * chemin reste stable alors que l'objet MediaItem et parfois son id peuvent changer.
     *
     * L'ordre de sélection est conservé et aucune limite n'est appliquée : les éléments hors écran
     * restent sélectionnés lorsque RecyclerView recycle leurs ViewHolder.
     */
    fun getSelectedItems(): List<MediaItem> {
        if (selectedItems.isEmpty()) return emptyList()
        val latestByPath = LinkedHashMap<String, MediaItem>()
        fullList.forEach { latestByPath[selectionKey(it)] = it }
        currentList.forEach { latestByPath[selectionKey(it)] = it }
        return selectedItems.mapNotNull { latestByPath[it] }
    }

    fun clearSelection() {
        selectedItems.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke(emptySet())
    }

    fun selectAll() {
        currentList.forEach { selectedItems.add(selectionKey(it)) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.toSet())
    }

    private fun selectionKey(item: MediaItem): String = item.path.ifBlank { item.id }

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
        // Ne jamais soumettre null avant la nouvelle liste : cela vidait brièvement le
        // RecyclerView à chaque enrichissement metadata et faisait sauter le scroll.
        super.submitList(filtered.toList())
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // Un listener de checkbox ne doit jamais survivre au recyclage de sa ligne.
        holder.itemView.findViewById<android.widget.CheckBox>(R.id.checkboxSelect)
            ?.setOnCheckedChangeListener(null)
        if (holder is FileViewHolder) holder.thumbnailJob?.cancel()
        super.onViewRecycled(holder)
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
            val key = selectionKey(item)
            tvName.text = item.name
            tvCount.text = when (item.mimeType) {
                "network" -> itemView.context.getString(R.string.tab_network)
                "share" -> itemView.context.getString(R.string.saved_paths)
                else -> ""
            }
            // Checkbox visibilité
            val checkbox = itemView.findViewById<android.widget.CheckBox>(fr.retrospare.blazeplayer.R.id.checkboxSelect)
            checkbox?.visibility = if (isSelectionMode) android.view.View.VISIBLE else android.view.View.GONE
            // Toujours détacher l'ancien listener AVANT de modifier isChecked. Sinon le listener
            // du ViewHolder recyclé modifie la sélection de l'ancienne ligne.
            checkbox?.setOnCheckedChangeListener(null)
            checkbox?.isChecked = selected.contains(key)
            checkbox?.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(key) else selected.remove(key)
                onSelectionChanged?.invoke(selected.toSet())
            }
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    val checked = !selected.contains(key)
                    if (checked) selected.add(key) else selected.remove(key)
                    checkbox?.isChecked = checked
                    onSelectionChanged?.invoke(selected.toSet())
                } else {
                    onClick(item)
                }
            }
            itemView.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                    selectedItems.add(key)
                    notifyDataSetChanged()
                    onSelectionChanged?.invoke(selectedItems.toSet())
                }
                true
            }
            btnMore?.setOnClickListener { v ->
                val popup = android.widget.PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, v.context.getString(R.string.dialog_add_favorite_folder))
                popup.setOnMenuItemClickListener { mi ->
                    fr.retrospare.blazeplayer.ui.HapticFeedbackManager.perform(v)
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
        private val tvAudioQuality: TextView = view.findViewById(R.id.tvAudioQuality)

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
            val key = selectionKey(item)
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
            val isAudio = item.mimeType.startsWith("audio/") || item.extension.lowercase() in setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka","wv","aiff","alac")
            if (isAudio) {
                // Pour l'audio, le conteneur et la qualité restent côte à côte dans la rangée.
                tvFormat.visibility = View.GONE
                AudioQualityBadgeBinder.bind(
                    tvAudioCodec,
                    tvAudioQuality,
                    item.path,
                    item.name,
                    item.extension,
                    knownDurationMs = item.duration.takeIf { it > 0L }?.times(1000L) ?: 0L,
                    knownSizeBytes = item.size
                )
            } else {
                tvAudioQuality.visibility = View.GONE
            }

            if (item.duration > 0 && item.lastPosition > 0) {
                progressFill.visibility = View.VISIBLE
                val pct = item.lastPosition.toFloat() / item.duration
                val params = progressFill.layoutParams
                params.width = (itemView.width * pct).toInt()
                progressFill.layoutParams = params
            } else {
                progressFill.visibility = View.GONE
            }

            // Miniature navigateur : pour les vidéos, on n'extrait plus jamais une frame ici.
            // On affiche uniquement une miniature personnalisée ou un snapshot capturé pendant
            // la lecture. L'extraction de frame à l'affichage était trop coûteuse et provoquait
            // des saccades, surtout sur MP4/SMB. L'audio garde son extraction artwork dédiée.
            thumbnailJob?.cancel()
            (ivThumbnail.parent as? View)?.visibility = View.VISIBLE
            ivThumbnail.setImageDrawable(null)
            ivThumbnail.setBackgroundResource(R.drawable.bg_thumbnail)
            ivThumbnail.scaleType = ImageView.ScaleType.CENTER
            ivThumbnail.setImageResource(R.drawable.ic_video_camera)
            ivPlayOverlay.visibility = View.VISIBLE
            ivThumbnail.setTag(R.id.ivThumbnail, item.path)
            if (isAudio) ivThumbnail.setImageResource(R.drawable.ic_music_note_large)
            val cachedThumb = if (isAudio) {
                fr.retrospare.blazeplayer.player.AudioArtworkResolver.cachedJpegBytes(itemView.context, item.path)
                    ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else {
                fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedThumbnailBitmap(itemView.context, item.path)
            }
            if (cachedThumb != null) {
                ivThumbnail.setImageBitmap(cachedThumb)
                ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                ivThumbnail.setBackgroundColor(0x00000000)
            } else if (isAudio) {
                thumbnailJob = scope.launch {
                    val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        fr.retrospare.blazeplayer.player.AudioArtworkResolver.resolveJpegBytes(itemView.context, item.path)
                            ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                    if (ivThumbnail.getTag(R.id.ivThumbnail) == item.path && bitmap != null) {
                        ivThumbnail.setImageBitmap(bitmap)
                        ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                        ivThumbnail.setBackgroundColor(0x00000000)
                    }
                }
            }

            // Case à cocher (sélection multiple) : toujours visible, comme dans le navigateur
            // audio, à gauche de la ligne. Cocher sélectionne le fichier ; taper la ligne l'ouvre
            // normalement (plus besoin d'appui long pour activer un "mode sélection").
            val checkbox = itemView.findViewById<android.widget.CheckBox>(fr.retrospare.blazeplayer.R.id.checkboxSelect)
            checkbox?.visibility = View.VISIBLE
            // Le listener du ViewHolder précédent doit être retiré avant isChecked. C'était la
            // cause des sélections tronquées au nombre de lignes visibles (souvent 8).
            checkbox?.setOnCheckedChangeListener(null)
            checkbox?.isChecked = selected.contains(key)
            checkbox?.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(key) else selected.remove(key)
                onSelectionChanged?.invoke(selected.toSet())
            }

            itemView.setOnTouchListener(null)
            itemView.setOnClickListener { onClick(item) }
            btnMore.setOnClickListener { v ->
                val popup = android.widget.PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, v.context.getString(R.string.action_play))
                popup.menu.add(0, 2, 1, v.context.getString(R.string.action_information))
                                popup.setOnMenuItemClickListener { mi ->
                    fr.retrospare.blazeplayer.ui.HapticFeedbackManager.perform(v)
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
