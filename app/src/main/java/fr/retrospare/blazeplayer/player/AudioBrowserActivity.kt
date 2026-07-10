package fr.retrospare.blazeplayer.player

import fr.retrospare.blazeplayer.ui.showPremium
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
        /** Résultat renvoyé quand le navigateur a servi uniquement à alimenter la file Blaze Party. */
        const val EXTRA_BLAZE_PARTY_CHANGED = "extra_blaze_party_changed"
        /** Chemin d'un dossier favori (local ou réseau) sur lequel démarrer directement,
         *  au lieu de la racine locale par défaut. */
        const val EXTRA_FAVORITE_PATH = "extra_favorite_path"
        /** Identifiant du partage réseau associé au favori, si c'en est un — absent pour un
         *  favori local. */
        const val EXTRA_FAVORITE_SHARE_ID = "extra_favorite_share_id"
        /** Mode lancé depuis les paramètres audio : le navigateur sert à choisir les dossiers à surveiller. */
        const val EXTRA_WATCHED_FOLDERS_MODE = "extra_watched_folders_mode"
    }

    @Inject lateinit var networkRepository: NetworkRepository
    @Inject lateinit var smbBrowser: SmbBrowser
    @Inject lateinit var upnpBrowser: UpnpBrowser

    private lateinit var binding: ActivityAudioBrowserBinding
    private val selectedItems = mutableListOf<Pair<String, String>>() // path, name
    private var currentMode = Mode.LOCAL
    private var watchedFoldersMode = false
    private var currentLocalFolder: java.io.File? = null

    enum class Mode { LOCAL, NETWORK, FOLDER }

    private val audioExtensions = setOf("mp3","flac","aac","ogg","opus","wav","m4a","wma","ape","dts","ac3","mka")
    private val folderHistory = mutableListOf<String>()

    data class AudioFile(
        val name: String,
        val path: String,
        val duration: Long,
        val artist: String,
        val bitrate: Int = 0,
        val album: String = "",
        val trackNumber: Int = 0,
        val size: Long = 0L,
        val modifiedAt: Long = 0L
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        watchedFoldersMode = intent.getBooleanExtra(EXTRA_WATCHED_FOLDERS_MODE, false)
        if (watchedFoldersMode) configureWatchedFoldersMode()

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
        updateAudioSortLabel()
        val sortClick = View.OnClickListener {
            cycleAudioSortMode()
            updateAudioSortLabel()
            renderCurrentAudioList()
        }
        binding.audioSortChip.setOnClickListener(sortClick)
        binding.btnSortChevron.setOnClickListener(sortClick)
        // Boutons action
        binding.btnAddAll.setOnClickListener {
            if (watchedFoldersMode) addCurrentFolderToWatched() else selectAllCurrentFolderTracks()
        }
        binding.btnConfirm.setOnClickListener {
            if (watchedFoldersMode) {
                setResult(android.app.Activity.RESULT_OK)
                finish()
                return@setOnClickListener
            }
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
            val tracks = selectedPlaylistRefs()
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
        binding.btnAddToBlazeParty.setOnClickListener {
            if (selectedItems.isEmpty()) {
                android.widget.Toast.makeText(this, getString(R.string.toast_select_tracks_first), android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tracks = selectedPlaylistRefs()
            val added = fr.retrospare.blazeplayer.playlist.PlaylistManager.addToBlazePartyPlaylist(this, tracks)
            val msg = if (added > 0) {
                resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.blaze_party_items_added, added, added)
            } else {
                getString(fr.retrospare.blazeplayer.R.string.blaze_party_items_already_present)
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            setResult(android.app.Activity.RESULT_OK, Intent().putExtra(EXTRA_BLAZE_PARTY_CHANGED, true))
            finish()
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

    private enum class AudioListKind { NONE, ROOT_FOLDERS, LOCAL_MIXED, NETWORK_MIXED, FILES, FOLDER_BROWSER }
    private enum class AudioSortMode { TRACK_NUMBER, NAME_ASC, NAME_DESC, DATE_DESC, SIZE_DESC }
    private var audioListKind: AudioListKind = AudioListKind.NONE
    private var audioSortMode: AudioSortMode = AudioSortMode.TRACK_NUMBER
    private var rootLocalFolders: List<java.io.File> = emptyList()
    private var mixedLocalFolders: List<java.io.File> = emptyList()
    private var mixedLocalFiles: List<AudioFile> = emptyList()
    private var networkAudioFolders: List<Pair<String, String>> = emptyList()
    private var networkAudioFiles: MutableList<AudioFile> = mutableListOf()
    private var simpleAudioFiles: List<AudioFile> = emptyList()
    private var folderBrowserPath: String = ""
    private var folderBrowserFolders: List<Pair<String, String>> = emptyList()
    private var folderBrowserFiles: List<AudioFile> = emptyList()

    private fun configureWatchedFoldersMode() {
        binding.tvBrowserTitle.text = getString(R.string.audio_watched_folders)
        // En mode "dossiers surveillés", les cases à cocher sur les dossiers + le bouton
        // Terminer suffisent. On masque donc l'ancien bouton "Ajouter dossier" pour réduire
        // la friction et éviter une action redondante.
        binding.btnAddAll.visibility = View.GONE
        binding.btnConfirm.text = getString(R.string.action_done)
        binding.btnConfirm.setIconResource(R.drawable.ic_check)
        (binding.btnAddToSavedPlaylist.parent as? View)?.visibility = View.GONE
    }

    private fun addCurrentFolderToWatched() {
        val folder = currentWatchedFolder()
        if (isFolderWatched(folder)) removeWatchedFolder(folder) else addWatchedFolder(folder)
        updateWatchedModeControls()
        renderCurrentAudioList()
    }

    private fun currentWatchedFolder(): AudioProSettings.WatchedFolder {
        val share = currentNetworkShare
        return if (share != null) {
            val cleanPath = currentNetworkPath.ifBlank { if (share.type == ShareType.UPNP) "0" else "" }
            val name = when {
                cleanPath.isBlank() || cleanPath == "0" -> share.name
                else -> cleanPath.replace('\\', '/').trim('/').substringAfterLast('/').ifBlank { share.name }
            }
            AudioProSettings.WatchedFolder(
                name = name,
                path = cleanPath,
                isNetwork = true,
                shareId = share.id,
                shareName = share.name
            )
        } else {
            val local = currentLocalFolder ?: android.os.Environment.getExternalStorageDirectory()
            AudioProSettings.WatchedFolder(local.name.ifBlank { getString(R.string.local_storage) }, local.absolutePath, isNetwork = false)
        }
    }

    private fun watchedFolderForLocal(folder: java.io.File): AudioProSettings.WatchedFolder =
        AudioProSettings.WatchedFolder(folder.name.ifBlank { getString(R.string.local_storage) }, folder.absolutePath, isNetwork = false)

    private fun watchedFolderForNetwork(share: NetworkShare, folderPath: String, folderName: String): AudioProSettings.WatchedFolder =
        AudioProSettings.WatchedFolder(
            name = folderName.ifBlank { share.name },
            path = folderPath.ifBlank { if (share.type == ShareType.UPNP) "0" else "" },
            isNetwork = true,
            shareId = share.id,
            shareName = share.name
        )

    private fun isFolderWatched(folder: AudioProSettings.WatchedFolder): Boolean =
        AudioProSettings.isWatchedFolder(this, folder)

    private fun updateWatchedModeControls() {
        if (!watchedFoldersMode) return
        // Le dossier courant se gère maintenant depuis les cases à cocher de la liste.
        binding.btnAddAll.visibility = View.GONE
    }

    private fun setActiveTab(index: Int) {
        val green = getColor(fr.retrospare.blazeplayer.R.color.green_accent)
        val blue = getColor(fr.retrospare.blazeplayer.R.color.blue_accent)
        val dim = 0xFF6B6E80.toInt()
        binding.btnLocal.backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 0) green else dim)
        binding.btnNetwork.backgroundTintList = android.content.res.ColorStateList.valueOf(if (index == 1) blue else dim)
    }

    private fun cycleAudioSortMode() {
        audioSortMode = when (audioSortMode) {
            AudioSortMode.TRACK_NUMBER -> AudioSortMode.NAME_ASC
            AudioSortMode.NAME_ASC -> AudioSortMode.NAME_DESC
            AudioSortMode.NAME_DESC -> AudioSortMode.DATE_DESC
            AudioSortMode.DATE_DESC -> AudioSortMode.SIZE_DESC
            AudioSortMode.SIZE_DESC -> AudioSortMode.TRACK_NUMBER
        }
    }

    private fun updateAudioSortLabel() {
        binding.tvSortLabel.text = when (audioSortMode) {
            AudioSortMode.TRACK_NUMBER -> getString(R.string.sort_album_order)
            AudioSortMode.NAME_ASC -> getString(R.string.sort_name_az)
            AudioSortMode.NAME_DESC -> getString(R.string.sort_name_za)
            AudioSortMode.DATE_DESC -> getString(R.string.sort_date_recent)
            AudioSortMode.SIZE_DESC -> getString(R.string.sort_size)
        }
    }

    private fun inferredTrackNumber(name: String): Int? {
        val cleanName = name.substringBeforeLast(".")
        val match = Regex("""^\s*(?:cd\s*\d+\s*[-_. ]*)?(\d{1,3})(?:\s*[-_.)]|\s+)""", RegexOption.IGNORE_CASE)
            .find(cleanName)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun trackSortNumber(item: AudioFile): Int =
        item.trackNumber.takeIf { it > 0 } ?: inferredTrackNumber(item.name) ?: Int.MAX_VALUE

    private fun sortedAudioFiles(files: List<AudioFile>): List<AudioFile> = when (audioSortMode) {
        AudioSortMode.TRACK_NUMBER -> files.sortedWith(
            compareBy<AudioFile>(
                { it.album.lowercase() },
                { trackSortNumber(it) },
                { it.name.lowercase() }
            )
        )
        AudioSortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
        AudioSortMode.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
        AudioSortMode.DATE_DESC -> files.sortedWith(
            compareByDescending<AudioFile> { it.modifiedAt }.thenBy { it.name.lowercase() }
        )
        AudioSortMode.SIZE_DESC -> files.sortedWith(
            compareByDescending<AudioFile> { it.size }.thenBy { it.name.lowercase() }
        )
    }

    private fun sortedLocalFolders(folders: List<java.io.File>): List<java.io.File> = when (audioSortMode) {
        AudioSortMode.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
        else -> folders.sortedBy { it.name.lowercase() }
    }

    private fun sortedNetworkFolders(folders: List<Pair<String, String>>): List<Pair<String, String>> = when (audioSortMode) {
        AudioSortMode.NAME_DESC -> folders.sortedByDescending { it.first.lowercase() }
        else -> folders.sortedBy { it.first.lowercase() }
    }


    private fun showLocalFolderActions(folder: java.io.File) {
        val watched = watchedFolderForLocal(folder)
        val alreadyWatched = isFolderWatched(watched)
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(arrayOf(getString(if (alreadyWatched) R.string.audio_remove_watched_folder else R.string.audio_add_to_watched), getString(R.string.audio_add_to_favorites))) { _, which ->
                when (which) {
                    0 -> setWatchedFolder(watched, !alreadyWatched)
                    1 -> fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showAddFavoriteDialog(
                        this,
                        fr.retrospare.blazeplayer.favorites.FavoriteCategory.AUDIO,
                        fr.retrospare.blazeplayer.favorites.FavoriteFolder(path = folder.absolutePath, name = folder.name)
                    )
                }
            }
            .showPremium()
    }

    private fun showNetworkFolderActions(share: NetworkShare, folderPath: String, folderName: String) {
        val watched = watchedFolderForNetwork(share, folderPath, folderName)
        val alreadyWatched = isFolderWatched(watched)
        AlertDialog.Builder(this)
            .setTitle(folderName)
            .setItems(arrayOf(getString(if (alreadyWatched) R.string.audio_remove_watched_folder else R.string.audio_add_to_watched), getString(R.string.audio_add_to_favorites))) { _, which ->
                when (which) {
                    0 -> setWatchedFolder(watched, !alreadyWatched)
                    1 -> fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showAddFavoriteDialog(
                        this,
                        fr.retrospare.blazeplayer.favorites.FavoriteCategory.AUDIO,
                        fr.retrospare.blazeplayer.favorites.FavoriteFolder(
                            path = folderPath, name = folderName,
                            shareId = share.id, shareName = share.name
                        )
                    )
                }
            }
            .showPremium()
    }

    private fun addWatchedFolder(folder: AudioProSettings.WatchedFolder) {
        val added = AudioProSettings.addWatchedFolder(this, folder)
        Toast.makeText(
            this,
            if (added) getString(R.string.audio_watched_folder_added) else getString(R.string.audio_watched_folder_already),
            Toast.LENGTH_SHORT
        ).show()
        renderCurrentAudioList()
    }

    private fun removeWatchedFolder(folder: AudioProSettings.WatchedFolder) {
        AudioProSettings.removeWatchedFolder(this, folder)
        Toast.makeText(this, R.string.audio_watched_folder_removed, Toast.LENGTH_SHORT).show()
        renderCurrentAudioList()
    }

    private fun setWatchedFolder(folder: AudioProSettings.WatchedFolder, checked: Boolean) {
        if (checked) addWatchedFolder(folder) else removeWatchedFolder(folder)
        updateWatchedModeControls()
    }

    private fun selectedAudioPaths(): Set<String> = selectedItems.map { it.first }.toSet()

    private fun renderCurrentAudioList() {
        when (audioListKind) {
            AudioListKind.ROOT_FOLDERS -> renderRootFolderList()
            AudioListKind.LOCAL_MIXED -> renderLocalMixedList()
            AudioListKind.NETWORK_MIXED -> renderNetworkMixedList()
            AudioListKind.FILES -> renderSimpleFileList()
            AudioListKind.FOLDER_BROWSER -> renderFolderBrowserList()
            AudioListKind.NONE -> Unit
        }
    }

    private fun renderRootFolderList() {
        val folders = sortedLocalFolders(rootLocalFolders)
        val adapter = FolderAdapter(
            folders = folders,
            onClick = { folder -> browseFolderAudio(folder) },
            onMoreClick = { folder -> showLocalFolderActions(folder) },
            watchedFoldersMode = watchedFoldersMode,
            isWatched = { folder -> isFolderWatched(watchedFolderForLocal(folder)) },
            onWatchedToggle = { folder, checked -> setWatchedFolder(watchedFolderForLocal(folder), checked) }
        )
        currentItems = emptyList()
        binding.recyclerAudio.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        binding.tvSelected.text = resources.getQuantityString(R.plurals.folder_count, folders.size, folders.size)
    }

    private fun renderLocalMixedList() {
        val folders = sortedLocalFolders(mixedLocalFolders)
        val files = sortedAudioFiles(mixedLocalFiles)
        currentItems = files
        val adapter = MixedAudioAdapter(
            folders = folders,
            files = files,
            preselectedPaths = selectedAudioPaths(),
            onFolderClick = { browseFolderAudio(it) },
            onFileToggle = { path, name, checked ->
                if (checked) selectedItems.add(Pair(path, name))
                else selectedItems.removeAll { it.first == path }
                updateCounter()
            },
            onFolderMoreClick = { folder -> showLocalFolderActions(folder) },
            watchedFoldersMode = watchedFoldersMode,
            isFolderWatched = { folder -> isFolderWatched(watchedFolderForLocal(folder)) },
            onFolderWatchedToggle = { folder, checked -> setWatchedFolder(watchedFolderForLocal(folder), checked) }
        )
        binding.recyclerAudio.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        if (selectedItems.isEmpty()) binding.tvSelected.text = resources.getQuantityString(R.plurals.track_count_found, files.size, files.size) else updateCounter()
    }

    private fun renderNetworkMixedList() {
        val folders = sortedNetworkFolders(networkAudioFolders)
        val files = sortedAudioFiles(networkAudioFiles)
        currentItems = files
        val currentShare = currentNetworkShare
        val adapter = CombinedAudioAdapter(
            folders = folders,
            files = files,
            preselectedPaths = selectedAudioPaths(),
            onFolderClick = onFolderClick@ { folderPath ->
                val share = currentShare ?: return@onFolderClick
                val previousPath = currentNetworkPath.ifBlank { if (share.type == ShareType.UPNP) "0" else "" }
                folderStack.addLast { browseNetworkShare(share, previousPath) }
                browseNetworkShare(share, folderPath)
            },
            onFileToggle = { path2, name, checked ->
                if (checked) selectedItems.add(Pair(path2, name))
                else selectedItems.removeAll { it.first == path2 }
                updateCounter()
            },
            onFolderMoreClick = onFolderMoreClick@ { folderPath, folderName ->
                val share = currentShare ?: return@onFolderMoreClick
                showNetworkFolderActions(share, folderPath, folderName)
            },
            watchedFoldersMode = watchedFoldersMode,
            isFolderWatched = isWatched@ { folderPath, folderName ->
                val share = currentShare ?: return@isWatched false
                isFolderWatched(watchedFolderForNetwork(share, folderPath, folderName))
            },
            onFolderWatchedToggle = onToggle@ { folderPath, folderName, checked ->
                val share = currentShare ?: return@onToggle
                setWatchedFolder(watchedFolderForNetwork(share, folderPath, folderName), checked)
            }
        )
        binding.recyclerAudio.layoutManager = LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        if (selectedItems.isEmpty()) binding.tvSelected.text = resources.getQuantityString(R.plurals.track_count_found, files.size, files.size) else updateCounter()
    }

    private fun renderSimpleFileList() {
        val files = sortedAudioFiles(simpleAudioFiles)
        currentItems = files
        val adapter = AudioBrowserAdapter(files, selectedAudioPaths()) { _, path, name, checked ->
            if (checked) selectedItems.add(Pair(path, name))
            else selectedItems.removeAll { it.first == path }
            updateCounter()
        }
        binding.recyclerAudio.layoutManager = LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
        if (selectedItems.isEmpty()) binding.tvSelected.text = resources.getQuantityString(R.plurals.track_count_found, files.size, files.size) else updateCounter()
    }

    private fun renderFolderBrowserList() {
        val folders = sortedNetworkFolders(folderBrowserFolders)
        val files = sortedAudioFiles(folderBrowserFiles)
        currentItems = files
        binding.tvSelected.text = resources.getQuantityString(R.plurals.track_count_found, files.size, files.size)
        val combinedAdapter = FolderBrowserAdapter(
            currentPath = folderBrowserPath,
            folders = folders,
            files = files,
            onBack = { navigateFolderBack() },
            onFolderClick = { loadFolderBrowser(it) },
            onAddAll = { tracks ->
                tracks.forEach { selectedItems.add(Pair(it.path, it.name)) }
                updateCounter()
                confirmSelection()
            }
        )
        binding.recyclerAudio.layoutManager = LinearLayoutManager(this)
        binding.recyclerAudio.adapter = combinedAdapter
    }

    private fun loadLocalFiles() {
        folderStack.clear()
        currentNetworkShare = null
        currentNetworkPath = ""
        currentLocalFolder = android.os.Environment.getExternalStorageDirectory()
        updateWatchedModeControls()
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
        currentLocalFolder = folder
        currentNetworkShare = null
        currentNetworkPath = ""
        updateWatchedModeControls()
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
        audioListKind = AudioListKind.ROOT_FOLDERS
        rootLocalFolders = folders
        renderCurrentAudioList()
    }

    private fun showMixedList(folders: List<java.io.File>, files: List<AudioFile>) {
        audioListKind = AudioListKind.LOCAL_MIXED
        mixedLocalFolders = folders
        mixedLocalFiles = files
        renderCurrentAudioList()
    }

    private fun loadNetworkShares() {
        folderStack.clear()
        currentLocalFolder = null
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
                }.showPremium()
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
        currentLocalFolder = null
        currentNetworkShare = share
        currentNetworkPath = path
        updateWatchedModeControls()
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
                        bitrate = (cached?.bitrate ?: 0L).toInt(),
                        album = cached?.album.orEmpty(),
                        trackNumber = cached?.trackNumber ?: 0,
                        size = item.size,
                        modifiedAt = item.lastPlayedAt
                    )
                }.toMutableList()
                displayItems.addAll(fileItems)
                audioListKind = AudioListKind.NETWORK_MIXED
                networkAudioFolders = folders.map { it.name to it.path }
                networkAudioFiles = fileItems
                renderCurrentAudioList()

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
                            bitrate = if (meta.bitrate > 0L) meta.bitrate.toInt() else fileItems[idx].bitrate,
                            album = meta.album.takeIf { it.isNotBlank() } ?: fileItems[idx].album,
                            trackNumber = if (meta.trackNumber > 0) meta.trackNumber else fileItems[idx].trackNumber
                        )
                        if (updated != fileItems[idx]) {
                            fileItems[idx] = updated
                            networkAudioFiles = fileItems
                            if (audioListKind == AudioListKind.NETWORK_MIXED) renderCurrentAudioList()
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
        audioListKind = AudioListKind.FILES
        simpleAudioFiles = items
        renderCurrentAudioList()
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
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK
        )
        contentResolver.query(collection, projection, null, null, MediaStore.Audio.Media.TITLE)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val bitrateCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE) } catch (_: Exception) { -1 }
            val sizeCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE) } catch (_: Exception) { -1 }
            val modifiedCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED) } catch (_: Exception) { -1 }
            val albumCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM) } catch (_: Exception) { -1 }
            val trackCol = try { cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK) } catch (_: Exception) { -1 }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val durationMs = cursor.getLong(durationCol)
                val duration = durationMs / 1000
                val artist = cursor.getString(artistCol) ?: ""
                val title = cursor.getString(titleCol) ?: name
                val rawBitrate = if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0
                val album = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else ""
                val trackNumber = if (trackCol >= 0) cursor.getInt(trackCol) % 1000 else 0
                // La colonne BITRATE de MediaStore est très souvent vide pour l'audio (fiable
                // surtout pour la vidéo) : on calcule un débit moyen de repli à partir de la
                // taille et de la durée plutôt que de ne jamais afficher de badge.
                val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val modifiedAt = if (modifiedCol >= 0) cursor.getLong(modifiedCol) * 1000L else 0L
                val bitrate = if (rawBitrate > 0) {
                    rawBitrate
                } else if (sizeBytes > 0L && durationMs > 0L) {
                    ((sizeBytes * 8_000L) / durationMs).toInt()
                } else 0
                val uri = ContentUris.withAppendedId(collection, id).toString()
                items.add(AudioFile(title.takeIf { it.isNotBlank() } ?: name, uri, duration, artist, bitrate, album, trackNumber, sizeBytes, modifiedAt))
            }
        }
        return items
    }

    private fun updateCounter() {
        val n = selectedItems.size
        binding.tvSelected.text = resources.getQuantityString(R.plurals.selected_tracks_count, n, n)
    }

    private fun selectedPlaylistRefs(): List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef> {
        val currentByPath = currentItems.associateBy { it.path }
        return selectedItems.map { (path, fallbackName) ->
            val item = currentByPath[path]
            val title = item?.name?.takeIf { it.isNotBlank() } ?: fallbackName
            fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(
                path = path,
                name = title,
                artist = item?.artist.orEmpty(),
                title = title,
                bitrate = (item?.bitrate ?: 0).toLong(),
                durationMs = (item?.duration ?: 0L).let { if (it > 0L) it * 1000L else 0L },
                album = item?.album.orEmpty(),
                trackNumber = item?.trackNumber ?: 0
            )
        }
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
            audioListKind = AudioListKind.FOLDER_BROWSER
            folderBrowserPath = path
            folderBrowserFolders = folders
            folderBrowserFiles = audioFiles
            renderCurrentAudioList()
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
                    bitrate = info.bitrate.toInt(),
                    album = info.album,
                    trackNumber = info.trackNumber,
                    size = file.length(),
                    modifiedAt = file.lastModified()
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
    preselectedPaths: Set<String> = emptySet(),
    private val onToggle: (Int, String, String, Boolean) -> Unit
) : RecyclerView.Adapter<AudioBrowserAdapter.ViewHolder>() {

    companion object {
        private val coverExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    }

    private val selected = mutableSetOf<Int>().apply {
        items.forEachIndexed { index, item -> if (item.path in preselectedPaths) add(index) }
    }

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
    private val onFolderMoreClick: (String, String) -> Unit = { _, _ -> },
    private val watchedFoldersMode: Boolean = false,
    private val isFolderWatched: (String, String) -> Boolean = { _, _ -> false },
    private val onFolderWatchedToggle: (String, String, Boolean) -> Unit = { _, _, _ -> },
    preselectedPaths: Set<String> = emptySet()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val selected = mutableSetOf<Int>().apply {
        files.forEachIndexed { index, item -> if (item.path in preselectedPaths) add(index) }
    }
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
            val watchedCheck = holder.itemView.findViewById<CheckBox>(R.id.checkWatchedFolder)
            if (watchedFoldersMode) {
                watchedCheck?.visibility = View.VISIBLE
                watchedCheck?.setOnCheckedChangeListener(null)
                watchedCheck?.isChecked = isFolderWatched(folder.second, folder.first)
                watchedCheck?.setOnCheckedChangeListener { _, checked ->
                    onFolderWatchedToggle(folder.second, folder.first, checked)
                }
            } else {
                watchedCheck?.visibility = View.GONE
            }
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
    private val onClick: (java.io.File) -> Unit,
    private val onMoreClick: (java.io.File) -> Unit = {},
    private val watchedFoldersMode: Boolean = false,
    private val isWatched: (java.io.File) -> Boolean = { false },
    private val onWatchedToggle: (java.io.File, Boolean) -> Unit = { _, _ -> }
) : androidx.recyclerview.widget.RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
        ViewHolder(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_audio_folder, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.itemView.findViewById<android.widget.TextView>(R.id.tvFolderName).text = folder.name
        val watchedCheck = holder.itemView.findViewById<android.widget.CheckBox>(R.id.checkWatchedFolder)
        if (watchedFoldersMode) {
            watchedCheck?.visibility = android.view.View.VISIBLE
            watchedCheck?.setOnCheckedChangeListener(null)
            watchedCheck?.isChecked = isWatched(folder)
            watchedCheck?.setOnCheckedChangeListener { _, checked -> onWatchedToggle(folder, checked) }
        } else {
            watchedCheck?.visibility = android.view.View.GONE
        }
        holder.itemView.setOnClickListener { onClick(folder) }
        // Le "..." doit toujours permettre d'ajouter le dossier aux favoris, même s'il
        // contient d'autres sous-dossiers à l'intérieur.
        holder.itemView.findViewById<android.view.View>(R.id.btnFolderMore)?.setOnClickListener {
            onMoreClick(folder)
        }
    }

    override fun getItemCount() = folders.size

    class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)
}

class MixedAudioAdapter(
    private val folders: List<java.io.File>,
    private val files: List<AudioBrowserActivity.AudioFile>,
    private val onFolderClick: (java.io.File) -> Unit,
    private val onFileToggle: (String, String, Boolean) -> Unit,
    private val onFolderMoreClick: (java.io.File) -> Unit = {},
    private val watchedFoldersMode: Boolean = false,
    private val isFolderWatched: (java.io.File) -> Boolean = { false },
    private val onFolderWatchedToggle: (java.io.File, Boolean) -> Unit = { _, _ -> },
    private val preselectedPaths: Set<String> = emptySet()
) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

    private val selected = mutableSetOf<Int>().apply {
        files.forEachIndexed { index, item -> if (item.path in preselectedPaths) add(index) }
    }
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
            val watchedCheck = holder.itemView.findViewById<android.widget.CheckBox>(R.id.checkWatchedFolder)
            if (watchedFoldersMode) {
                watchedCheck?.visibility = android.view.View.VISIBLE
                watchedCheck?.setOnCheckedChangeListener(null)
                watchedCheck?.isChecked = isFolderWatched(folder)
                watchedCheck?.setOnCheckedChangeListener { _, checked -> onFolderWatchedToggle(folder, checked) }
            } else {
                watchedCheck?.visibility = android.view.View.GONE
            }
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
