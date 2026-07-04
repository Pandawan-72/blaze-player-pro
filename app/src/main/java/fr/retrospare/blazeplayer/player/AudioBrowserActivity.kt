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
import fr.retrospare.blazeplayer.network.UpnpBrowser
import fr.retrospare.blazeplayer.data.model.ShareType
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
        /** Chemin d'un dossier favori (local ou réseau) sur lequel démarrer directement,
         *  au lieu de la racine locale par défaut. */
        const val EXTRA_FAVORITE_PATH = "extra_favorite_path"
        /** Identifiant du partage réseau associé au favori, si c'en est un — absent pour un
         *  favori local. */
        const val EXTRA_FAVORITE_SHARE_ID = "extra_favorite_share_id"
    }

    @Inject lateinit var networkRepository: NetworkRepository
    @Inject lateinit var smbBrowser: SmbBrowser
    @Inject lateinit var upnpBrowser: UpnpBrowser

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
            // Dans le navigateur audio, la maison ne doit pas revenir à l'accueil général :
            // elle ramène directement à la file/lecteur Blaze Audio déjà ouvert.
            val intent = android.content.Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            intent.putExtra("openBlazeAudio", true)
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
                android.widget.Toast.makeText(this, getString(R.string.toast_no_track_selected), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = android.content.Intent().apply {
                putStringArrayListExtra(EXTRA_PATHS, ArrayList(selectedItems.map { it.first }))
                putStringArrayListExtra(EXTRA_NAMES, ArrayList(selectedItems.map { it.second }))
            }
            setResult(android.app.Activity.RESULT_OK, intent)
            finish()
        }
        binding.btnAddToSavedPlaylist.setOnClickListener {
            if (selectedItems.isEmpty()) {
                android.widget.Toast.makeText(this, getString(R.string.toast_select_tracks_first), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tracks = selectedItems.map { fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(it.first, it.second) }
            fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
                this, fr.retrospare.blazeplayer.playlist.PlaylistCategory.AUDIO, tracks
            ) {
                selectedItems.clear()
                (binding.recyclerAudio.adapter as? AudioBrowserAdapter)?.clearSelection()
                (binding.recyclerAudio.adapter as? CombinedAudioAdapter)?.clearSelection()
                (binding.recyclerAudio.adapter as? MixedAudioAdapter)?.clearSelection()
                updateCounter()
            }
        }

        // Recherche globale dans tous les fichiers audio locaux
        binding.btnSearch.setOnClickListener {
            val searchBar = android.widget.SearchView(this).apply {
                queryHint = getString(R.string.search_hint_all_folders)
                isIconified = false
            }
            var allAudioFiles: List<AudioFile> = emptyList()
            var lastFilteredResults: List<AudioFile> = emptyList()
            var searchJob: kotlinx.coroutines.Job? = null
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.action_search))
                .setView(searchBar)
                .setPositiveButton(getString(R.string.action_show_results)) { d, _ ->
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
                .setNegativeButton(getString(R.string.action_cancel)) { d, _ ->
                    d.dismiss()
                    if (folderStack.isEmpty()) loadLocalFiles()
                }
                .create()

            dialog.setOnDismissListener {
                searchJob?.cancel()
            }

            // Recherche locale uniquement — tous dossiers confondus via un scan local, qui est
            // rapide (accès disque, pas de réseau). Pas de recherche réseau ici.
            searchJob = lifecycleScope.launch {
                allAudioFiles = withContext(kotlinx.coroutines.Dispatchers.IO) { scanLocalAudio() }
            }

            searchBar.setOnQueryTextListener(object : android.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    val q = newText?.lowercase() ?: ""
                    if (q.isEmpty()) {
                        // Vide - ne rien afficher, attendre une saisie
                        binding.recyclerAudio.adapter = null
                        binding.tvSelected.text = getString(R.string.search_enter_term)
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
                    binding.tvSelected.text = resources.getQuantityString(R.plurals.track_count_found, filtered.size, filtered.size)
                    return true
                }
            })
            dialog.show()
        }

        // Chargement initial : soit un dossier favori précis (local ou réseau), soit la
        // racine locale par défaut.
        val favoritePath = intent.getStringExtra(EXTRA_FAVORITE_PATH)
        val favoriteShareId = intent.getStringExtra(EXTRA_FAVORITE_SHARE_ID)
        when {
            favoritePath != null && favoriteShareId != null -> {
                setActiveTab(1)
                lifecycleScope.launch {
                    val share = withContext(Dispatchers.IO) { networkRepository.getShareById(favoriteShareId) }
                    if (share != null) {
                        browseNetworkShare(share, favoritePath)
                    } else {
                        loadLocalFiles()
                    }
                }
            }
            favoritePath != null -> {
                setActiveTab(0)
                browseFolderAudio(java.io.File(favoritePath), pushBack = false)
            }
            else -> loadLocalFiles()
        }
    }

    private var currentItems: List<AudioFile> = emptyList()
    private val folderStack = ArrayDeque<() -> Unit>() // pile pour navigation retour
    /** Partage réseau actuellement parcouru (null en mode Local) — permet au bouton de
     *  recherche de savoir s'il doit chercher en local ou dans ce partage précis. */
    private var currentNetworkShare: NetworkShare? = null
    /** Sous-chemin actuellement parcouru à l'intérieur de [currentNetworkShare] — sans ça, la
     *  recherche repartait toujours de la racine du partage au lieu du dossier où l'utilisateur
     *  se trouve réellement. */
    private var currentNetworkPath: String = ""

    private fun setActiveTab(index: Int) {
        val green = getColor(fr.retrospare.blazeplayer.R.color.green_accent)
        val blue = getColor(fr.retrospare.blazeplayer.R.color.blue_accent)
        val purple = 0xFF9C6FD6.toInt()
        val dim = 0xFF6B6E80.toInt()
        binding.btnLocal.backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 0) green else dim)
        binding.btnNetwork.backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 1) blue else dim)
    }

    private fun loadLocalFiles() {
        folderStack.clear()
        currentNetworkShare = null
        currentNetworkPath = ""
        lifecycleScope.launch {
            binding.tvSelected.text = getString(R.string.loading)
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
            binding.tvSelected.text = getString(R.string.loading)
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
        binding.tvSelected.text = resources.getQuantityString(R.plurals.folder_count, folders.size, folders.size)
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
            },
            onFolderMoreClick = { folder ->
                fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showAddFavoriteDialog(
                    this,
                    fr.retrospare.blazeplayer.favorites.FavoriteCategory.AUDIO,
                    fr.retrospare.blazeplayer.favorites.FavoriteFolder(path = folder.absolutePath, name = folder.name)
                )
            }
        )
        binding.recyclerAudio.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        binding.tvSelected.text = resources.getQuantityString(R.plurals.track_count_found, files.size, files.size)
    }

    private fun loadNetworkShares() {
        folderStack.clear()
        lifecycleScope.launch {
            val shares = networkRepository.getShares().first()
            if (shares.isEmpty()) {
                fr.retrospare.blazeplayer.ui.InfoDialog.show(this@AudioBrowserActivity, getString(R.string.info_dialog_title_info), getString(R.string.toast_no_network_path_configured))
                return@launch
            }
            AlertDialog.Builder(this@AudioBrowserActivity)
                .setTitle(getString(R.string.dialog_choose_network_path))
                .setItems(shares.map { share ->
                    "${share.name} (${if (share.type == ShareType.UPNP) "UPNP" else "SMB"})"
                }.toTypedArray()) { _, i ->
                    browseNetworkShare(shares[i], if (shares[i].type == ShareType.UPNP) "0" else "")
                }.show()
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
        currentNetworkShare = share
        currentNetworkPath = path
        lifecycleScope.launch {
            binding.tvSelected.text = getString(R.string.loading)
            val browsePath = if (share.type == ShareType.UPNP) path.ifBlank { "0" } else path
            val result = withContext(Dispatchers.IO) {
                if (share.type == ShareType.UPNP) upnpBrowser.listFiles(share, browsePath)
                else smbBrowser.listFiles(share, browsePath)
            }
            result.onSuccess { items ->
                val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" }
                val audioFiles = items.filter { it.extension.lowercase() in audioExtensions || it.mimeType.startsWith("audio/", ignoreCase = true) }
                val displayItems = mutableListOf<AudioFile>()
                
                // Dossiers navigables
                val folderNames = folders.map { "📁 ${it.name}" }
                val fileItems = audioFiles.map { item ->
                    val cached = AudioMetadataExtractor.getCached(this@AudioBrowserActivity, item.path)
                    AudioFile(
                        name = cached?.title?.takeIf { it.isNotBlank() } ?: item.name,
                        path = item.path,
                        duration = if ((cached?.duration ?: 0L) > 0L) cached!!.duration else item.duration,
                        artist = cached?.artist?.takeIf { it.isNotBlank() } ?: getString(R.string.tab_network),
                        bitrate = (cached?.bitrate ?: 0L).toInt()
                    )
                }.toMutableList()
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
                    onFolderClick = { folderPath ->
                        val previousPath = browsePath
                        folderStack.addLast { browseNetworkShare(share, previousPath) }
                        browseNetworkShare(share, folderPath)
                    },
                    onFileToggle = { path2, name, checked ->
                        if (checked) selectedItems.add(Pair(path2, name))
                        else selectedItems.removeAll { it.first == path2 }
                        updateCounter()
                    },
                    onFolderMoreClick = { folderPath, folderName ->
                        fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showAddFavoriteDialog(
                            this@AudioBrowserActivity,
                            fr.retrospare.blazeplayer.favorites.FavoriteCategory.AUDIO,
                            fr.retrospare.blazeplayer.favorites.FavoriteFolder(
                                path = folderPath, name = folderName,
                                shareId = share.id, shareName = share.name
                            )
                        )
                    }
                )
                binding.recyclerAudio.adapter = combinedAdapter
                updateCounter()

                // Enrichissement asynchrone des titres audio réseau depuis les tags ID3/FLAC.
                // Le cache disque est consulté avant extraction ; l'extraction est bornée dans
                // AudioMetadataExtractor pour ne pas bloquer le listing SMB.
                audioFiles.forEachIndexed { idx, item ->
                    if (idx >= fileItems.size) return@forEachIndexed
                    lifecycleScope.launch {
                        val meta = if (share.type == ShareType.UPNP) {
                            AudioMetadataExtractor.getCached(this@AudioBrowserActivity, item.path)
                                ?: AudioTechnicalInfo(title = item.name, duration = item.duration, extension = item.extension.uppercase())
                        } else {
                            AudioMetadataExtractor.extract(this@AudioBrowserActivity, item.path, item.name)
                        }
                        if (isFinishing || isDestroyed) return@launch
                        val updated = fileItems[idx].copy(
                            name = meta.title.takeIf { it.isNotBlank() } ?: fileItems[idx].name,
                            artist = meta.artist.takeIf { it.isNotBlank() } ?: fileItems[idx].artist,
                            duration = if (meta.duration > 0L) meta.duration else fileItems[idx].duration,
                            bitrate = if (meta.bitrate > 0L) meta.bitrate.toInt() else fileItems[idx].bitrate
                        )
                        if (updated != fileItems[idx]) {
                            fileItems[idx] = updated
                            currentItems = fileItems
                            combinedAdapter.notifyItemChanged(folders.size + idx)
                        }
                    }
                }
            }.onFailure { e ->
                val message = e.message ?: e.javaClass.simpleName
                fr.retrospare.blazeplayer.ui.InfoDialog.show(this@AudioBrowserActivity, getString(R.string.info_dialog_title_error), getString(R.string.toast_error_generic, message))
                binding.tvSelected.text = getString(R.string.toast_error_generic, message)
                binding.recyclerAudio.adapter = null
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
        binding.tvSelected.text = resources.getQuantityString(R.plurals.track_count_found, items.size, items.size)
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
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.TITLE
        )
        contentResolver.query(collection, projection, null, null, MediaStore.Audio.Media.TITLE)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val bitrateCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE) } catch (_: Exception) { -1 }
            val sizeCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE) } catch (_: Exception) { -1 }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val durationMs = cursor.getLong(durationCol)
                val duration = durationMs / 1000
                val artist = cursor.getString(artistCol) ?: ""
                val title = cursor.getString(titleCol) ?: name
                val rawBitrate = if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0
                // La colonne BITRATE de MediaStore est très souvent vide pour l'audio (fiable
                // surtout pour la vidéo) : on calcule un débit moyen de repli à partir de la
                // taille et de la durée plutôt que de ne jamais afficher de badge.
                val bitrate = if (rawBitrate > 0) {
                    rawBitrate
                } else if (sizeCol >= 0 && durationMs > 0L) {
                    val sizeBytes = cursor.getLong(sizeCol)
                    if (sizeBytes > 0L) ((sizeBytes * 8_000L) / durationMs).toInt() else 0
                } else 0
                val uri = ContentUris.withAppendedId(collection, id).toString()
                items.add(AudioFile(title.takeIf { it.isNotBlank() } ?: name, uri, duration, artist, bitrate))
            }
        }
        return items
    }

    private fun updateCounter() {
        val n = selectedItems.size
        binding.tvSelected.text = resources.getQuantityString(R.plurals.selected_tracks_count, n, n)
    }

    private fun selectAllCurrentFolderTracks() {
        if (currentItems.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_track_selected), Toast.LENGTH_SHORT).show()
            return
        }

        currentItems.forEach { item ->
            if (selectedItems.none { it.first == item.path }) {
                selectedItems.add(Pair(item.path, item.name))
            }
        }

        when (val adapter = binding.recyclerAudio.adapter) {
            is AudioBrowserAdapter -> adapter.selectAll()
            is CombinedAudioAdapter -> adapter.selectAllFiles()
            is MixedAudioAdapter -> adapter.selectAllFiles()
            else -> adapter?.notifyDataSetChanged()
        }
        updateCounter()
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
            ?.map { file ->
                // extract() (et non getCached()) : déclenche une vraie extraction si le fichier n'a
                // jamais été ouvert, pour que le badge bitrate/lossless apparaisse dès la première
                // visite du dossier plutôt que de rester à 0 tant qu'aucun autre écran ne l'a mis en
                // cache. Fichiers locaux uniquement ici, donc coût réseau nul et extraction rapide.
                val info = AudioMetadataExtractor.extract(this@AudioBrowserActivity, file.absolutePath, file.name)
                AudioFile(
                    name = info.title.takeIf { it.isNotBlank() } ?: file.name,
                    path = file.absolutePath,
                    duration = info.duration.takeIf { it > 0L } ?: 0L,
                    artist = info.artist,
                    bitrate = info.bitrate.toInt()
                )
            }
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


private fun bindCachedAudioCover(row: android.view.View, path: String) {
    val cover = row.findViewById<android.widget.ImageView>(fr.retrospare.blazeplayer.R.id.ivAudioCover) ?: return
    (cover.parent as? android.view.View)?.visibility = android.view.View.VISIBLE
    cover.setImageDrawable(null)
    cover.setTag(fr.retrospare.blazeplayer.R.id.ivAudioCover, path)

    val cached = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedAudioArtworkJpegBytes(row.context, path)
    if (cached != null) {
        android.graphics.BitmapFactory.decodeByteArray(cached, 0, cached.size)?.let { cover.setImageBitmap(it) }
        return
    }

    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fr.retrospare.blazeplayer.ui.ThumbnailUtils.getAudioArtworkJpegBytes(row.context.applicationContext, path)
        }
        if (cover.getTag(fr.retrospare.blazeplayer.R.id.ivAudioCover) != path || bytes == null) return@launch
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { cover.setImageBitmap(it) }
    }
}

class AudioBrowserAdapter(
    private val items: List<AudioBrowserActivity.AudioFile>,
    private val onToggle: (Int, String, String, Boolean) -> Unit
) : RecyclerView.Adapter<AudioBrowserAdapter.ViewHolder>() {

    companion object {
        private val coverExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    }

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
    fun selectAll() {
        selected.clear()
        selected.addAll(items.indices)
        notifyDataSetChanged()
    }
    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

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
            tvArtist.text = item.artist.ifEmpty { itemView.context.getString(R.string.unknown_artist) }
            val dur = item.duration
            tvDuration.text = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = isSelected
            checkbox.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            itemView.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }

            // Codec depuis extension
            val ext = item.path.substringAfterLast(".", "").uppercase()
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvCodec, ext)
            tvCodec.visibility = if (ext.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE

            // Badge lossless ou bitrate
            val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
            when {
                lossless -> {
                    tvBitrate.text = itemView.context.getString(R.string.lossless_label)
                    tvBitrate.visibility = android.view.View.VISIBLE
                }
                item.bitrate > 0 -> {
                    tvBitrate.text = "${item.bitrate / 1000} kbps"
                    tvBitrate.visibility = android.view.View.VISIBLE
                }
                else -> tvBitrate.visibility = android.view.View.GONE
            }

            // Cover à gauche du titre : cache local d'abord, extraction asynchrone ensuite.
            bindCachedAudioCover(itemView, item.path)
        }
    }
}

class CombinedAudioAdapter(
    private val folders: List<Pair<String, String>>,
    private val files: List<AudioBrowserActivity.AudioFile>,
    private val onFolderClick: (String) -> Unit,
    private val onFileToggle: (String, String, Boolean) -> Unit,
    private val onFolderMoreClick: (String, String) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val selected = mutableSetOf<Int>()
    companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_FILE = 1
        private val coverExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    }

    override fun getItemViewType(position: Int) = if (position < folders.size) TYPE_FOLDER else TYPE_FILE
    override fun getItemCount() = folders.size + files.size
    fun selectAllFiles() {
        selected.clear()
        selected.addAll(files.indices)
        notifyDataSetChanged()
    }
    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

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
            holder.itemView.findViewById<View>(R.id.btnFolderMore)?.setOnClickListener {
                onFolderMoreClick(folder.second, folder.first)
            }
        } else {
            val fileIdx = position - folders.size
            val file = files[fileIdx]
            holder.itemView.findViewById<TextView>(R.id.tvAudioTitle)?.text = file.name
            holder.itemView.findViewById<TextView>(R.id.tvAudioArtist)?.text = file.artist
            holder.itemView.findViewById<TextView>(R.id.tvAudioDuration)?.text =
                if (file.duration > 0) "%d:%02d".format(file.duration / 60, file.duration % 60) else ""
            val ext = file.path.substringAfterLast(".", "").uppercase()
            val tvCodec = holder.itemView.findViewById<TextView>(R.id.tvAudioCodec)
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvCodec, ext)
            tvCodec?.visibility = if (ext.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            val tvBitrate = holder.itemView.findViewById<TextView>(R.id.tvAudioBitrate)
            when {
                ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF") -> { tvBitrate?.text = holder.itemView.context.getString(R.string.lossless_label); tvBitrate?.visibility = android.view.View.VISIBLE }
                file.bitrate > 0 -> { tvBitrate?.text = "${file.bitrate / 1000} kbps"; tvBitrate?.visibility = android.view.View.VISIBLE }
                else -> tvBitrate?.visibility = android.view.View.GONE
            }
            bindCachedAudioCover(holder.itemView, file.path)
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
                holder.itemView.findViewById<TextView>(R.id.tvFolderPath)?.text = currentPath.replace("/sdcard", holder.itemView.context.getString(R.string.tab_local))
                holder.itemView.findViewById<TextView>(R.id.tvFolderName2)?.text = if (folderName.isEmpty() || folderName == "sdcard") holder.itemView.context.getString(R.string.local_storage) else folderName
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
                val ext = file.path.substringAfterLast(".", "").uppercase()
                val tvCodec = holder.itemView.findViewById<TextView>(R.id.tvAudioSimpleCodec)
                fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvCodec, ext)
                tvCodec?.visibility = if (ext.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                val tvFormatBadge = holder.itemView.findViewById<TextView>(R.id.tvAudioSimpleFormatBadge)
                when {
                    ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF") -> {
                        tvFormatBadge?.text = holder.itemView.context.getString(R.string.lossless_label)
                        tvFormatBadge?.visibility = android.view.View.VISIBLE
                    }
                    file.bitrate > 0 -> {
                        tvFormatBadge?.text = "${file.bitrate / 1000} kbps"
                        tvFormatBadge?.visibility = android.view.View.VISIBLE
                    }
                    else -> tvFormatBadge?.visibility = android.view.View.GONE
                }
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
    private val onFileToggle: (String, String, Boolean) -> Unit,
    private val onFolderMoreClick: (java.io.File) -> Unit = {}
) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

    private val selected = mutableSetOf<Int>()
    companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_FILE = 1
        private val coverExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    }

    override fun getItemViewType(position: Int) = if (position < folders.size) TYPE_FOLDER else TYPE_FILE
    override fun getItemCount() = folders.size + files.size
    fun selectAllFiles() {
        selected.clear()
        selected.addAll(files.indices)
        notifyDataSetChanged()
    }
    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
    }

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
            holder.itemView.findViewById<android.view.View>(R.id.btnFolderMore)?.setOnClickListener {
                onFolderMoreClick(folder)
            }
        } else {
            val filePos = position - folders.size
            val item = files[filePos]
            val v = holder.itemView
            v.findViewById<android.widget.TextView>(R.id.tvAudioTitle)?.text = item.name.substringBeforeLast(".")
            v.findViewById<android.widget.TextView>(R.id.tvAudioArtist)?.text = item.artist.ifEmpty { v.context.getString(R.string.unknown_artist) }
            val dur = item.duration
            v.findViewById<android.widget.TextView>(R.id.tvAudioDuration)?.text = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
            val ext = item.path.substringAfterLast(".", "").uppercase()
            val tvCodec = v.findViewById<android.widget.TextView>(R.id.tvAudioCodec)
            fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvCodec, ext)
            tvCodec?.visibility = if (ext.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            val lossless = ext in listOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
            val tvBitrate = v.findViewById<android.widget.TextView>(R.id.tvAudioBitrate)
            when {
                lossless -> { tvBitrate?.text = v.context.getString(R.string.lossless_label); tvBitrate?.visibility = android.view.View.VISIBLE }
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
            bindCachedAudioCover(v, item.path)
        }
    }
}
