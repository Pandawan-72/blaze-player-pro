package fr.retrospare.blazeplayer.gallery.slideshow

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/**
 * Sélecteur de photos dédié à Diapo.
 *
 * La navigation reste volontairement par dossiers, comme Blaze Gallery : l'utilisateur ouvre un
 * dossier, coche des photos, valide "Ajouter à diapo", revient aux dossiers et peut recommencer.
 * "Créer diapo" renvoie la sélection globale. Lorsqu'il est lancé depuis un projet existant, les
 * photos déjà présentes sont préchargées afin de pouvoir ajouter depuis d'autres dossiers.
 */
class SlideshowPhotoPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXISTING_PATHS = "slideshow_picker_existing_paths"
        const val EXTRA_EXISTING_NAMES = "slideshow_picker_existing_names"
        const val EXTRA_RESULT_PATHS = "slideshow_picker_result_paths"
        const val EXTRA_RESULT_NAMES = "slideshow_picker_result_names"
    }

    private data class Folder(
        val id: String,
        val name: String,
        var count: Int = 0,
        val previews: MutableList<String> = mutableListOf()
    )

    private data class Photo(val name: String, val path: String)

    private lateinit var recycler: RecyclerView
    private lateinit var title: TextView
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var addButton: MaterialButton
    private lateinit var createButton: MaterialButton
    private lateinit var backButton: ImageButton

    /** Photos définitivement ajoutées au projet de sélection. */
    private val selected = LinkedHashMap<String, String>()
    /** Photos cochées dans le dossier courant, pas encore ajoutées via le bouton. */
    private val pending = LinkedHashMap<String, String>()
    private var currentFolder: Folder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slideshow_photo_picker)

        SystemBarsInsets.apply(this, R.id.rootSlideshowPicker)

        val existingPaths = intent.getStringArrayListExtra(EXTRA_EXISTING_PATHS).orEmpty()
        val existingNames = intent.getStringArrayListExtra(EXTRA_EXISTING_NAMES).orEmpty()
        existingPaths.forEachIndexed { index, path ->
            if (path.isNotBlank()) selected[path] = existingNames.getOrNull(index).orEmpty()
        }

        recycler = findViewById(R.id.listSlideshowPicker)
        title = findViewById(R.id.tvSlideshowPickerTitle)
        count = findViewById(R.id.tvSlideshowPickerCount)
        empty = findViewById(R.id.tvSlideshowPickerEmpty)
        addButton = findViewById(R.id.btnSlideshowPickerAdd)
        createButton = findViewById(R.id.btnSlideshowPickerCreate)
        backButton = findViewById(R.id.btnSlideshowPickerBack)

        recycler.itemAnimator = null
        backButton.setOnClickListener { handleBack() }
        addButton.setOnClickListener { commitPending() }
        createButton.setOnClickListener { finishSelection() }

        showFolders()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (currentFolder != null) {
            pending.clear()
            showFolders()
        } else {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun showFolders() {
        currentFolder = null
        pending.clear()
        title.text = getString(R.string.slideshow_picker_folders)
        addButton.visibility = View.GONE
        updateSelectionUi()
        recycler.setItemViewCacheSize(24)
        recycler.recycledViewPool.setMaxRecycledViews(0, 32)
        recycler.layoutManager = GridLayoutManager(this, 2).apply { initialPrefetchItemCount = 12 }
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { loadFolders() }
            val warmupJob = lifecycleScope.launch(Dispatchers.IO) {
                ThumbnailUtils.warmImageThumbnails(
                    applicationContext,
                    folders.flatMap { it.previews }.take(18),
                    maxSize = 260,
                    concurrency = 6
                )
            }
            kotlinx.coroutines.withTimeoutOrNull(220L) { warmupJob.join() }
            recycler.adapter = FolderAdapter(folders)
            setEmpty(folders.isEmpty(), R.string.gallery_empty_folders)
        }
    }

    private fun openFolder(folder: Folder) {
        currentFolder = folder
        pending.clear()
        title.text = folder.name
        addButton.visibility = View.VISIBLE
        updateSelectionUi()
        recycler.setItemViewCacheSize(72)
        recycler.recycledViewPool.setMaxRecycledViews(0, 96)
        recycler.layoutManager = GridLayoutManager(this, 4).apply { initialPrefetchItemCount = 32 }
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) { loadPhotos(folder.id) }
            val warmupJob = lifecycleScope.launch(Dispatchers.IO) {
                ThumbnailUtils.warmImageThumbnails(
                    applicationContext,
                    photos.take(36).map { it.path },
                    maxSize = 420,
                    concurrency = 6
                )
            }
            kotlinx.coroutines.withTimeoutOrNull(260L) { warmupJob.join() }
            recycler.adapter = PhotoAdapter(photos)
            setEmpty(photos.isEmpty(), R.string.gallery_empty_photos)
        }
    }

    private fun commitPending() {
        if (pending.isEmpty()) return
        pending.forEach { (path, name) -> selected[path] = name }
        val added = pending.size
        pending.clear()
        recycler.adapter?.notifyDataSetChanged()
        updateSelectionUi()
        Toast.makeText(this, getString(R.string.slideshow_added_count, added), Toast.LENGTH_SHORT).show()
    }

    private fun finishSelection() {
        if (pending.isNotEmpty()) commitPending()
        if (selected.size < 2) {
            Toast.makeText(this, R.string.slideshow_need_two_photos, Toast.LENGTH_SHORT).show()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putStringArrayListExtra(EXTRA_RESULT_PATHS, ArrayList(selected.keys))
                putStringArrayListExtra(EXTRA_RESULT_NAMES, ArrayList(selected.values))
            }
        )
        finish()
    }

    private fun updateSelectionUi() {
        count.text = getString(R.string.slideshow_picker_selected_count, selected.size, pending.size)
        addButton.isEnabled = pending.isNotEmpty()
        addButton.alpha = if (addButton.isEnabled) 1f else 0.45f
        createButton.isEnabled = selected.size + pending.size >= 2
        createButton.alpha = if (createButton.isEnabled) 1f else 0.45f
    }

    private fun setEmpty(isEmpty: Boolean, messageRes: Int) {
        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) empty.setText(messageRes)
    }

    private fun loadFolders(): List<Folder> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val folders = LinkedHashMap<String, Folder>()
        contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucket = cursor.getString(bucketCol) ?: continue
                val folder = folders.getOrPut(bucket) {
                    Folder(bucket, cursor.getString(nameCol).orEmpty().ifBlank { getString(R.string.slideshow_picker_folders) })
                }
                folder.count++
                if (folder.previews.size < 3) {
                    val id = cursor.getLong(idCol)
                    folder.previews += ContentUris.withAppendedId(uri, id).toString()
                }
            }
        }
        return folders.values.sortedBy { it.name.lowercase() }
    }

    private fun loadPhotos(bucketId: String): List<Photo> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val result = mutableListOf<Photo>()
        contentResolver.query(
            uri,
            projection,
            "${MediaStore.Images.Media.BUCKET_ID}=?",
            arrayOf(bucketId),
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += Photo(
                    cursor.getString(nameCol).orEmpty(),
                    ContentUris.withAppendedId(uri, id).toString()
                )
            }
        }
        return result
    }

    private inner class FolderAdapter(private val items: List<Folder>) : RecyclerView.Adapter<FolderHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderHolder =
            FolderHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_folder_tile, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: FolderHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.count.text = item.count.toString()
            holder.folderIcon.visibility = View.VISIBLE
            holder.more.visibility = View.GONE
            holder.previews.forEachIndexed { index, image ->
                val path = item.previews.getOrNull(index)
                val previous = image.getTag(R.id.ivThumbnail) as? String
                image.setTag(R.id.ivThumbnail, path)
                image.visibility = if (path == null) View.INVISIBLE else View.VISIBLE
                if (path != null) {
                    ThumbnailUtils.peekMemoryImageThumbnailBitmap(path, 260)?.let { bitmap ->
                        image.setImageBitmap(bitmap)
                        image.scaleType = ImageView.ScaleType.CENTER_CROP
                    } ?: run {
                        if (previous != path) image.setImageResource(R.drawable.bg_thumbnail)
                        lifecycleScope.launch {
                            ThumbnailUtils.loadImageThumbnail(this@SlideshowPhotoPickerActivity, path, image, 260)
                        }
                    }
                }
            }
            holder.itemView.setOnClickListener { openFolder(item) }
        }

        override fun onViewRecycled(holder: FolderHolder) {
            super.onViewRecycled(holder)
        }
    }

    private class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvFolderName)
        val count: TextView = view.findViewById(R.id.tvFolderCount)
        val more: ImageButton = view.findViewById(R.id.btnFolderMore)
        val folderIcon: ImageView = view.findViewById(R.id.ivGalleryFolderIcon)
        val previews: List<ImageView> = listOf(
            view.findViewById(R.id.ivPreview1),
            view.findViewById(R.id.ivPreview2),
            view.findViewById(R.id.ivPreview3)
        )
    }

    private inner class PhotoAdapter(private val items: List<Photo>) : RecyclerView.Adapter<PhotoHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoHolder =
            PhotoHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_photo, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: PhotoHolder, position: Int) {
            val item = items[position]
            holder.more.visibility = View.GONE
            val checked = selected.containsKey(item.path) || pending.containsKey(item.path)
            holder.check.visibility = View.VISIBLE
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = checked
            val previous = holder.image.getTag(R.id.ivThumbnail) as? String
            holder.image.setTag(R.id.ivThumbnail, item.path)
            ThumbnailUtils.peekMemoryImageThumbnailBitmap(item.path, 420)?.let { bitmap ->
                holder.image.setImageBitmap(bitmap)
                holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
            } ?: run {
                if (previous != item.path) holder.image.setImageResource(R.drawable.bg_thumbnail)
                lifecycleScope.launch {
                    ThumbnailUtils.loadImageThumbnail(this@SlideshowPhotoPickerActivity, item.path, holder.image, 420)
                }
            }
            holder.itemView.setOnClickListener { toggle(item) }
            holder.check.setOnClickListener { toggle(item) }
        }

        override fun onViewRecycled(holder: PhotoHolder) {
            super.onViewRecycled(holder)
        }

        private fun toggle(item: Photo) {
            when {
                pending.remove(item.path) != null -> Unit
                selected.remove(item.path) != null -> Unit
                else -> pending[item.path] = item.name
            }
            notifyDataSetChanged()
            updateSelectionUi()
        }
    }

    private class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.ivThumbnail)
        val more: ImageButton = view.findViewById(R.id.btnMore)
        val check: CheckBox = view.findViewById(R.id.cbSelected)
    }
}
