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

    data class AudioFile(val name: String, val path: String, val duration: Long, val artist: String, val bitrate: Int = 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHome?.setOnClickListener {
            val intent = android.content.Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra("requestedTab", 1) // Onglet Local
            startActivity(intent)
            finish()
        }

        binding.btnBack.setOnClickListener {
            if (folderStack.isNotEmpty()) {
                folderStack.removeLast().invoke()
            } else {
                finish()
            }
        }

        // Boutons source
        binding.btnLocal.setOnClickListener {
            setActiveTab(0)
            loadLocalFiles()
        }
        binding.btnNetwork.setOnClickListener {
            setActiveTab(1)
            loadNetworkShares()
        }
        // Boutons action
        binding.btnAddAll.setOnClickListener {
            val all = currentItems.map { Pair(it.path, it.name) }
            val intent = android.content.Intent().apply {
                putStringArrayListExtra(EXTRA_PATHS, ArrayList(all.map { it.first }))
                putStringArrayListExtra(EXTRA_NAMES, ArrayList(all.map { it.second }))
            }
            setResult(android.app.Activity.RESULT_OK, intent)
            finish()
        }
        binding.btnConfirm.setOnClickListener {
            if (selectedItems.isEmpty()) {
                android.widget.Toast.makeText(this, "Aucune piste sélectionnée", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = android.content.Intent().apply {
                putStringArrayListExtra(EXTRA_PATHS, ArrayList(selectedItems.map { it.first }))
                putStringArrayListExtra(EXTRA_NAMES, ArrayList(selectedItems.map { it.second }))
            }
            setResult(android.app.Activity.RESULT_OK, intent)
            finish()
        }

        // Recherche globale dans tous les fichiers audio locaux
        binding.btnSearch.setOnClickListener {
            val searchBar = android.widget.SearchView(this).apply {
                queryHint = "Rechercher dans tous les dossiers..."
                isIconified = false
            }
            var allAudioFiles: List<AudioFile> = emptyList()
            var lastFilteredResults: List<AudioFile> = emptyList()
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Rechercher")
                .setView(searchBar)
                .setPositiveButton("Afficher les résultats") { d, _ ->
                    d.dismiss()
                    if (lastFilteredResults.isNotEmpty()) {
                        // Pousse l'état actuel pour pouvoir revenir
                        val prevAdapter = binding.recyclerAudio.adapter
                        val prevText = binding.tvSelected.text.toString()
                        folderStack.addLast {
                            binding.recyclerAudio.adapter = prevAdapter
                            binding.tvSelected.text = prevText
                        }
                        showFileList(lastFilteredResults)
                    } else {
                        if (folderStack.isEmpty()) loadLocalFiles()
                    }
                }
                .setNegativeButton("Annuler") { d, _ ->
                    d.dismiss()
                    if (folderStack.isEmpty()) loadLocalFiles()
                }
                .create()

            // Charge tous les fichiers audio en arrière plan
            lifecycleScope.launch {
                allAudioFiles = withContext(kotlinx.coroutines.Dispatchers.IO) { scanLocalAudio() }
            }

            searchBar.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    val q = newText?.lowercase() ?: ""
                    if (q.isEmpty()) {
                        // Vide - ne rien afficher, attendre une saisie
                        binding.recyclerAudio.adapter = null
                        binding.tvSelected.text = "Saisissez un terme de recherche"
                        return true
                    }
                    val filtered = allAudioFiles.filter { it.name.lowercase().contains(q) }
                    lastFilteredResults = filtered
                    val adapter = AudioBrowserAdapter(filtered) { _, path, name, checked ->
                        if (checked) selectedItems.add(Pair(path, name))
                        else selectedItems.removeAll { it.first == path }
                        updateCounter()
                    }
                    binding.recyclerAudio.adapter = adapter
                    binding.tvSelected.text = "${filtered.size} piste${if (filtered.size > 1) "s" else ""} trouvée${if (filtered.size > 1) "s" else ""}"
                    return true
                }
            })
            dialog.show()
        }

        // Chargement initial
        loadLocalFiles()
    }

    private var currentItems: List<AudioFile> = emptyList()
    private val folderStack = ArrayDeque<() -> Unit>() // pile pour navigation retour

    private fun setActiveTab(index: Int) {
        val green = getColor(fr.retrospare.blazeplayer.R.color.green_accent)
        val blue = getColor(fr.retrospare.blazeplayer.R.color.blue_accent)
        val purple = 0xFF9C6FD6.toInt()
        val dim = 0xFF3A3A3A.toInt()
        binding.btnLocal.backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 0) green else dim)
        binding.btnNetwork.backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 1) blue else dim)
    }

    private fun loadLocalFiles() {
        folderStack.clear()
        lifecycleScope.launch {
            binding.tvSelected.text = "Chargement..."
            val folders = withContext(Dispatchers.IO) {
                android.os.Environment.getExternalStorageDirectory()
                    .listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name } ?: emptyList()
            }
            showFolderList(folders)
        }
    }

    private fun browseFolderAudio(folder: java.io.File, pushBack: Boolean = true) {
        if (pushBack) {
            // Sauvegarde l'état courant pour pouvoir y revenir
            val prevAdapter = binding.recyclerAudio.adapter
            val prevText = binding.tvSelected.text.toString()
            folderStack.addLast {
                binding.recyclerAudio.adapter = prevAdapter
                binding.tvSelected.text = prevText
            }
        }
        lifecycleScope.launch {
            binding.tvSelected.text = "Chargement..."
            val subFolders = withContext(Dispatchers.IO) {
                folder.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name } ?: emptyList()
            }
            val audioItems = withContext(Dispatchers.IO) { scanFolderAudio(folder.absolutePath) }
            showMixedList(subFolders, audioItems)
        }
    }

    private fun showFolderList(folders: List<java.io.File>) {
        val adapter = FolderAdapter(folders) { folder -> browseFolderAudio(folder) }
        binding.recyclerAudio.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        binding.tvSelected.text = "${folders.size} dossier${if (folders.size > 1) "s" else ""}"
    }

    private fun showMixedList(folders: List<java.io.File>, files: List<AudioFile>) {
        currentItems = files
        val adapter = MixedAudioAdapter(
            folders = folders,
            files = files,
            onFolderClick = { browseFolderAudio(it) },
            onFileToggle = { path, name, checked ->
                if (checked) selectedItems.add(Pair(path, name))
                else selectedItems.removeAll { it.first == path }
                updateCounter()
            }
        )
        binding.recyclerAudio.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        binding.tvSelected.text = "${files.size} piste${if (files.size > 1) "s" else ""} trouvée${if (files.size > 1) "s" else ""}"
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

    /**
     * Navigue dans un chemin réseau en gérant le mode multi-share (shareName vide = liste des partages)
     */
    private fun browseNetworkPath(share: fr.retrospare.blazeplayer.data.model.NetworkShare, navPath: String) {
        // Si shareName est vide, le navPath encode "nomPartage/sousChemin"
        if (share.shareName.isBlank()) {
            browseNetworkShare(share, navPath)
        } else {
            browseNetworkShare(share, navPath)
        }
    }

    private fun browseNetworkShare(share: NetworkShare, path: String) {
        lifecycleScope.launch {
            binding.tvSelected.text = "Chargement..."
            val result = withContext(Dispatchers.IO) { smbBrowser.listFiles(share, path) }
            result.onSuccess { items ->
                val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" }
                val audioFiles = items.filter { it.extension.lowercase() in audioExtensions }
                val displayItems = mutableListOf<AudioFile>()
                
                // Dossiers navigables
                val folderNames = folders.map { "📁 ${it.name}" }
                val fileItems = audioFiles.map { AudioFile(it.name, it.path, it.duration, "Réseau") }
                displayItems.addAll(fileItems)
                currentItems = fileItems // Necessaire pour le bouton "Tout ajouter"

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
        currentItems = items  // Mise à jour de currentItems
        val adapter = AudioBrowserAdapter(items) { _, path, name, checked ->
            if (checked) selectedItems.add(Pair(path, name))
            else selectedItems.removeAll { it.first == path }
            updateCounter()
        }
        binding.recyclerAudio.layoutManager = LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        binding.tvSelected.text = "${items.size} piste${if (items.size > 1) "s" else ""} trouvée${if (items.size > 1) "s" else ""}"
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
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.TITLE
        )
        contentResolver.query(collection, projection, null, null, MediaStore.Audio.Media.TITLE)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val bitrateCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE) } catch (_: Exception) { -1 }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val duration = cursor.getLong(durationCol) / 1000
                val artist = cursor.getString(artistCol) ?: ""
                val title = cursor.getString(titleCol) ?: name
                val bitrate = if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0
                val uri = ContentUris.withAppendedId(collection, id).toString()
                items.add(AudioFile(name, uri, duration, artist, bitrate))
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
        private val ivCover: android.widget.ImageView = view.findViewById(R.id.ivAudioCover)
        private val tvCodec: TextView = view.findViewById(R.id.tvAudioCodec)
        private val tvBitrate: TextView = view.findViewById(R.id.tvAudioBitrate)

        fun bind(item: AudioBrowserActivity.AudioFile, isSelected: Boolean, onToggle: (Boolean) -> Unit) {
            tvTitle.text = item.name.substringBeforeLast(".")
            tvArtist.text = item.artist.ifEmpty { "<inconnu>" }
            val dur = item.duration
            tvDuration.text = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = isSelected
            checkbox.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            itemView.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }

            // Codec depuis extension
            val ext = item.name.substringAfterLast(".", "").uppercase()
            tvCodec.text = ext
            tvCodec.visibility = if (ext.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE

            // Badge lossless ou bitrate
            val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
            when {
                lossless -> {
                    tvBitrate.text = "Lossless"
                    tvBitrate.visibility = android.view.View.VISIBLE
                }
                item.bitrate > 0 -> {
                    tvBitrate.text = "${item.bitrate / 1000} kbps"
                    tvBitrate.visibility = android.view.View.VISIBLE
                }
                else -> tvBitrate.visibility = android.view.View.GONE
            }

            // Cover depuis métadonnées sur thread IO
            ivCover.setImageResource(fr.retrospare.blazeplayer.R.drawable.bg_artwork)
            Thread {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    if (item.path.startsWith("content://"))
                        retriever.setDataSource(itemView.context, android.net.Uri.parse(item.path))
                    else
                        retriever.setDataSource(item.path)
                    val art = retriever.embeddedPicture
                    retriever.release()
                    if (art != null) {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            ivCover.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Exception) {}
            }.start()
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

class FolderAdapter(
    private val folders: List<java.io.File>,
    private val onClick: (java.io.File) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        ViewHolder(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_audio_folder, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.itemView.findViewById<android.widget.TextView>(R.id.tvFolderName).text = folder.name
        holder.itemView.setOnClickListener { onClick(folder) }
    }

    override fun getItemCount() = folders.size

    class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)
}

class MixedAudioAdapter(
    private val folders: List<java.io.File>,
    private val files: List<AudioBrowserActivity.AudioFile>,
    private val onFolderClick: (java.io.File) -> Unit,
    private val onFileToggle: (String, String, Boolean) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

    private val selected = mutableSetOf<Int>()
    companion object { const val TYPE_FOLDER = 0; const val TYPE_FILE = 1 }

    override fun getItemViewType(position: Int) = if (position < folders.size) TYPE_FOLDER else TYPE_FILE
    override fun getItemCount() = folders.size + files.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER)
            object : androidx.recyclerview.widget.RecyclerView.ViewHolder(inflater.inflate(R.layout.item_audio_folder, parent, false)) {}
        else
            object : androidx.recyclerview.widget.RecyclerView.ViewHolder(inflater.inflate(R.layout.item_audio_browser, parent, false)) {}
    }

    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
        if (position < folders.size) {
            val folder = folders[position]
            holder.itemView.findViewById<android.widget.TextView>(R.id.tvFolderName)?.text = folder.name
            holder.itemView.setOnClickListener { onFolderClick(folder) }
        } else {
            val filePos = position - folders.size
            val item = files[filePos]
            val v = holder.itemView
            v.findViewById<android.widget.TextView>(R.id.tvAudioTitle)?.text = item.name.substringBeforeLast(".")
            v.findViewById<android.widget.TextView>(R.id.tvAudioArtist)?.text = item.artist.ifEmpty { "<inconnu>" }
            val dur = item.duration
            v.findViewById<android.widget.TextView>(R.id.tvAudioDuration)?.text = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
            val ext = item.name.substringAfterLast(".", "").uppercase()
            val tvCodec = v.findViewById<android.widget.TextView>(R.id.tvAudioCodec)
            tvCodec?.text = ext
            tvCodec?.visibility = if (ext.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
            val tvBitrate = v.findViewById<android.widget.TextView>(R.id.tvAudioBitrate)
            when {
                lossless -> { tvBitrate?.text = "Lossless"; tvBitrate?.visibility = android.view.View.VISIBLE }
                item.bitrate > 0 -> { tvBitrate?.text = "${item.bitrate / 1000} kbps"; tvBitrate?.visibility = android.view.View.VISIBLE }
                else -> tvBitrate?.visibility = android.view.View.GONE
            }
            val checkbox = v.findViewById<android.widget.CheckBox>(R.id.checkAudio)
            val isSelected = filePos in selected
            checkbox?.setOnCheckedChangeListener(null)
            checkbox?.isChecked = isSelected
            checkbox?.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(filePos) else selected.remove(filePos)
                onFileToggle(item.path, item.name, checked)
            }
            v.setOnClickListener { checkbox?.isChecked = !(checkbox?.isChecked ?: false) }
            val ivCover = v.findViewById<android.widget.ImageView>(R.id.ivAudioCover)
            ivCover?.setImageResource(R.drawable.bg_artwork)
            Thread {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    if (item.path.startsWith("content://"))
                        retriever.setDataSource(v.context, android.net.Uri.parse(item.path))
                    else retriever.setDataSource(item.path)
                    val art = retriever.embeddedPicture
                    retriever.release()
                    if (art != null) {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { ivCover?.setImageBitmap(bmp) }
                    }
                } catch (_: Exception) {}
            }.start()
        }
    }
}
