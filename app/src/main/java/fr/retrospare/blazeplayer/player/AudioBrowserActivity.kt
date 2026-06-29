package fr.retrospare.blazeplayer.player

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import fr.retrospare.blazeplayer.databinding.ActivityAudioBrowserBinding
import fr.retrospare.blazeplayer.network.SmbBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class AudioBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "extra_paths"
        const val EXTRA_NAMES = "extra_names"
    }

    @Inject lateinit var networkRepository: NetworkRepository
    @Inject lateinit var smbBrowser: SmbBrowser

    private lateinit var binding: ActivityAudioBrowserBinding
    private val selectedItems = mutableListOf<Pair<String, String>>() // path, name
    private var currentMode = Mode.LOCAL

    enum class Mode { LOCAL, NETWORK, FOLDER }

    private val audioExtensions = setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")
    private val folderHistory = mutableListOf<String>()

    data class AudioFile(val name: String, val path: String, val duration: Long, val artist: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnConfirm.setOnClickListener { confirmSelection() }
        binding.btnAddAll.setOnClickListener { addAllVisible() }
        binding.btnLocal.setOnClickListener { switchMode(Mode.LOCAL) }
        binding.btnNetwork.setOnClickListener { switchMode(Mode.NETWORK) }
        binding.btnFolder.setOnClickListener { switchMode(Mode.FOLDER) }

        switchMode(Mode.LOCAL)
    }

    private fun switchMode(mode: Mode) {
        currentMode = mode
        binding.btnLocal.alpha = if (mode == Mode.LOCAL) 1f else 0.5f
        binding.btnNetwork.alpha = if (mode == Mode.NETWORK) 1f else 0.5f

        binding.btnFolder.alpha = if (mode == Mode.FOLDER) 1f else 0.5f
        when (mode) {
            Mode.LOCAL -> loadLocalFiles()
            Mode.NETWORK -> loadNetworkShares()
            Mode.FOLDER -> {
                folderHistory.clear()
                loadFolderBrowser("/sdcard")
            }
        }
    }

    private fun loadLocalFiles() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { scanLocalAudio() }
            showFileList(items)
        }
    }

    private fun loadNetworkShares() {
        lifecycleScope.launch {
            val shares = networkRepository.getShares().first()
            if (shares.isEmpty()) {
                Toast.makeText(this@AudioBrowserActivity, "Aucun chemin réseau configuré", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (shares.size == 1) {
                browseNetworkShare(shares.first(), "")
            } else {
                AlertDialog.Builder(this@AudioBrowserActivity)
                    .setTitle("Choisir un chemin réseau")
                    .setItems(shares.map { it.name }.toTypedArray()) { _, i ->
                        browseNetworkShare(shares[i], "")
                    }.show()
            }
        }
    }

    private fun browseNetworkShare(share: NetworkShare, path: String) {
        lifecycleScope.launch {
            binding.tvSelected.text = "Chargement..."
            val result = withContext(Dispatchers.IO) { smbBrowser.listFiles(share, path) }
            result.onSuccess { items ->
                val folders = items.filter { it.mimeType == "folder" }
                val audioFiles = items.filter { it.extension.lowercase() in audioExtensions }
                val displayItems = mutableListOf<AudioFile>()
                
                // Dossiers navigables
                val folderNames = folders.map { "📁 ${it.name}" }
                val fileItems = audioFiles.map { AudioFile(it.name, it.path, it.duration, "Réseau") }
                displayItems.addAll(fileItems)

                val adapter = AudioBrowserAdapter(displayItems) { _, path2, name, checked ->
                    if (checked) selectedItems.add(Pair(path2, name))
                    else selectedItems.removeAll { it.first == path2 }
                    updateCounter()
                }

                // Ajoute les dossiers cliquables en haut
                binding.recyclerAudio.layoutManager = LinearLayoutManager(this@AudioBrowserActivity)
                
                // Vue combinée dossiers + fichiers
                val combinedAdapter = CombinedAudioAdapter(
                    folders = folders.map { it.name to it.path },
                    files = fileItems,
                    onFolderClick = { folderPath -> browseNetworkShare(share, folderPath) },
                    onFileToggle = { path2, name, checked ->
                        if (checked) selectedItems.add(Pair(path2, name))
                        else selectedItems.removeAll { it.first == path2 }
                        updateCounter()
                    }
                )
                binding.recyclerAudio.adapter = combinedAdapter
                updateCounter()
            }.onFailure {

            }
        }
    }

    private fun showFileList(items: List<AudioFile>) {
        val adapter = AudioBrowserAdapter(items) { _, path, name, checked ->
            if (checked) selectedItems.add(Pair(path, name))
            else selectedItems.removeAll { it.first == path }
            updateCounter()
        }
        binding.recyclerAudio.layoutManager = LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        updateCounter()
    }

    private suspend fun scanLocalAudio(): List<AudioFile> {
        val items = mutableListOf<AudioFile>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE
        )
        contentResolver.query(collection, projection, null, null, MediaStore.Audio.Media.TITLE)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val duration = cursor.getLong(durationCol) / 1000
                val artist = cursor.getString(artistCol) ?: ""
                val title = cursor.getString(titleCol) ?: name
                val uri = ContentUris.withAppendedId(collection, id).toString()
                items.add(AudioFile(name, uri, duration, artist))
            }
        }
        return items
    }

    private fun updateCounter() {
        val n = selectedItems.size
        binding.tvSelected.text = "$n piste${if (n > 1) "s" else ""} sélectionnée${if (n > 1) "s" else ""}"
    }

    private fun navigateFolderBack() {
        if (folderHistory.size > 1) {
            folderHistory.removeAt(folderHistory.lastIndex)
            loadFolderBrowser(folderHistory.removeAt(folderHistory.lastIndex))
        } else {
            folderHistory.clear()
            loadFolderBrowser("/sdcard")
        }
    }

    private fun loadFolderBrowser(path: String) {
        folderHistory.add(path)
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { scanFolders(path) }
            val audioFiles = withContext(Dispatchers.IO) { scanFolderAudio(path) }
            binding.tvSelected.text = "${audioFiles.size} piste(s) dans ce dossier"

            val combinedAdapter = FolderBrowserAdapter(
                currentPath = path,
                folders = folders,
                files = audioFiles,
                onBack = { navigateFolderBack() },
                onFolderClick = { loadFolderBrowser(it) },
                onAddAll = { files ->
                    files.forEach { selectedItems.add(Pair(it.path, it.name)) }
                    updateCounter()
                    confirmSelection()
                }
            )
            binding.recyclerAudio.layoutManager = LinearLayoutManager(this@AudioBrowserActivity)
            binding.recyclerAudio.adapter = combinedAdapter
        }
    }

    private suspend fun scanFolders(path: String): List<Pair<String, String>> {
        val dir = java.io.File(path)
        return dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.map { it.name to it.absolutePath }
            ?: emptyList()
    }

    private suspend fun scanFolderAudio(path: String): List<AudioFile> {
        val dir = java.io.File(path)
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in audioExtensions }
            ?.sortedBy { it.name }
            ?.map { AudioFile(it.name, it.absolutePath, 0, "") }
            ?: emptyList()
    }

    private fun addAllVisible() {
        // Ajoute toutes les pistes visibles dans la liste courante
        val allItems = (binding.recyclerAudio.adapter as? AudioBrowserAdapter)?.getAllItems() ?: return
        allItems.forEach { (path, name) ->
            if (selectedItems.none { it.first == path }) {
                selectedItems.add(Pair(path, name))
            }
        }
        updateCounter()
        confirmSelection()
    }

    private fun confirmSelection() {
        if (selectedItems.isEmpty()) {
            finish()
            return
        }
        val paths = ArrayList(selectedItems.map { it.first })
        val names = ArrayList(selectedItems.map { it.second })
        setResult(RESULT_OK, Intent().apply {
            putStringArrayListExtra(EXTRA_PATHS, paths)
            putStringArrayListExtra(EXTRA_NAMES, names)
        })
        finish()
    }
}

class AudioBrowserAdapter(
    private val items: List<AudioBrowserActivity.AudioFile>,
    private val onToggle: (Int, String, String, Boolean) -> Unit
) : RecyclerView.Adapter<AudioBrowserAdapter.ViewHolder>() {

    private val selected = mutableSetOf<Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_audio_browser, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, position in selected) { checked ->
            if (checked) selected.add(position) else selected.remove(position)
            onToggle(position, item.path, item.name, checked)
        }
    }

    override fun getItemCount() = items.size
    fun getAllItems() = items.map { Pair(it.path, it.name) }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvAudioTitle)
        private val tvArtist: TextView = view.findViewById(R.id.tvAudioArtist)
        private val tvDuration: TextView = view.findViewById(R.id.tvAudioDuration)
        private val checkbox: CheckBox = view.findViewById(R.id.checkAudio)

        fun bind(item: AudioBrowserActivity.AudioFile, isSelected: Boolean, onToggle: (Boolean) -> Unit) {
            tvTitle.text = item.name
            tvArtist.text = item.artist
            tvDuration.text = "%d:%02d".format(item.duration / 60, item.duration % 60)
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = isSelected
            checkbox.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            itemView.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
        }
    }
}

class CombinedAudioAdapter(
    private val folders: List<Pair<String, String>>,
    private val files: List<AudioBrowserActivity.AudioFile>,
    private val onFolderClick: (String) -> Unit,
    private val onFileToggle: (String, String, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val selected = mutableSetOf<Int>()
    companion object { const val TYPE_FOLDER = 0; const val TYPE_FILE = 1 }

    override fun getItemViewType(position: Int) = if (position < folders.size) TYPE_FOLDER else TYPE_FILE
    override fun getItemCount() = folders.size + files.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_FOLDER) {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
            object : RecyclerView.ViewHolder(v) {}
        } else {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_audio_browser, parent, false)
            object : RecyclerView.ViewHolder(v) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < folders.size) {
            val folder = folders[position]
            holder.itemView.findViewById<TextView>(R.id.tvFolderName)?.text = folder.first
            holder.itemView.setOnClickListener { onFolderClick(folder.second) }
        } else {
            val fileIdx = position - folders.size
            val file = files[fileIdx]
            holder.itemView.findViewById<TextView>(R.id.tvAudioTitle)?.text = file.name
            holder.itemView.findViewById<TextView>(R.id.tvAudioArtist)?.text = file.artist
            holder.itemView.findViewById<TextView>(R.id.tvAudioDuration)?.text =
                "%d:%02d".format(file.duration / 60, file.duration % 60)
            val cb = holder.itemView.findViewById<CheckBox>(R.id.checkAudio)
            cb?.setOnCheckedChangeListener(null)
            cb?.isChecked = fileIdx in selected
            cb?.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(fileIdx) else selected.remove(fileIdx)
                onFileToggle(file.path, file.name, checked)
            }
            holder.itemView.setOnClickListener { cb?.isChecked = !(cb?.isChecked ?: false) }
        }
    }
}

class FolderBrowserAdapter(
    private val currentPath: String,
    private val folders: List<Pair<String, String>>,
    private val files: List<AudioBrowserActivity.AudioFile>,
    private val onBack: () -> Unit,
    private val onFolderClick: (String) -> Unit,
    private val onAddAll: (List<AudioBrowserActivity.AudioFile>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_FOLDER = 1
        const val TYPE_FILE = 2
    }

    override fun getItemViewType(position: Int) = when {
        position == 0 -> TYPE_HEADER
        position <= folders.size -> TYPE_FOLDER
        else -> TYPE_FILE
    }

    override fun getItemCount() = 1 + folders.size + files.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> object : RecyclerView.ViewHolder(
                inflater.inflate(R.layout.item_folder_header, parent, false)) {}
            TYPE_FOLDER -> object : RecyclerView.ViewHolder(
                inflater.inflate(R.layout.item_folder, parent, false)) {}
            else -> object : RecyclerView.ViewHolder(
                inflater.inflate(R.layout.item_audio_simple, parent, false)) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            TYPE_HEADER -> {
                val folderName = currentPath.substringAfterLast("/")
                holder.itemView.findViewById<TextView>(R.id.tvFolderPath)?.text = currentPath.replace("/sdcard", "Local")
                holder.itemView.findViewById<TextView>(R.id.tvFolderName2)?.text = if (folderName.isEmpty() || folderName == "sdcard") "Stockage local" else folderName
                holder.itemView.findViewById<View>(R.id.btnAddAllFolder)?.setOnClickListener {
                    onAddAll(files)
                }
                holder.itemView.findViewById<View>(R.id.btnFolderBack)?.setOnClickListener {
                    onBack()
                }
            }
            TYPE_FOLDER -> {
                val folder = folders[position - 1]
                holder.itemView.findViewById<TextView>(R.id.tvFolderName)?.text = folder.first
                holder.itemView.setOnClickListener { onFolderClick(folder.second) }
            }
            TYPE_FILE -> {
                val file = files[position - 1 - folders.size]
                holder.itemView.findViewById<TextView>(R.id.tvAudioSimpleName)?.text = file.name
                holder.itemView.setOnClickListener { 
                    onAddAll(listOf(file))
                }
            }
        }
    }
}
