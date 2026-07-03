package fr.retrospare.blazeplayer.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.network.SmbBrowser
import fr.retrospare.blazeplayer.network.UpnpBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class NetworkVideoBrowserActivity : AppCompatActivity() {

    @Inject lateinit var smbBrowser: SmbBrowser
    @Inject lateinit var upnpBrowser: UpnpBrowser
    @Inject lateinit var networkRepository: fr.retrospare.blazeplayer.data.repository.NetworkRepository

    private lateinit var tvTitle: TextView
    private lateinit var tvPath: TextView
    private lateinit var tvCount: TextView
    private lateinit var recyclerNetwork: RecyclerView

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "3gp", "ts")
    private val folderStack = ArrayDeque<Pair<String, String>>() // (path, displayName)

    private lateinit var share: NetworkShare
    private var currentPath: String = ""
    private var lastFolderClickAtMs: Long = 0L

    private fun containerBadgeFrom(video: MediaItem): String {
        fun clean(value: String): String {
            if (value.isBlank()) return ""
            val ext = value.substringBefore('?').substringBefore('#')
                .substringAfterLast('.', "")
                .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
                ?: ""
            return ext.uppercase()
        }
        val stored = clean(video.extension)
        if (stored.isNotEmpty()) return stored
        val name = clean(video.name)
        if (name.isNotEmpty()) return name
        val path = clean(video.path)
        if (path.isNotEmpty()) return path
        return when {
            video.mimeType.contains("matroska", true) || video.mimeType.contains("mkv", true) -> "MKV"
            video.mimeType.contains("mp4", true) -> "MP4"
            video.mimeType.contains("avi", true) -> "AVI"
            video.mimeType.contains("webm", true) -> "WEBM"
            else -> ""
        }
    }

    /**
     * Les favoris réseau ont existé sous plusieurs formats au fil des versions :
     * - chemin relatif au partage : Films/Action
     * - chemin préfixé par le nom du partage en mode multi-share : Videos/Films
     * - URI SMB complète : smb://host/share/Films
     * Le navigateur SMB, lui, attend toujours un chemin canonique en '/' adapté au
     * NetworkShare courant. Une valeur non normalisée peut faire lister un mauvais partage
     * ou un mauvais parent, donnant l'impression de revenir en arrière puis de bloquer.
     */
    private fun normalizeInitialPath(raw: String, share: NetworkShare): String {
        if (share.type == ShareType.UPNP) return raw.trim().ifBlank { "0" }
        var p = raw.trim().replace('\\', '/')
        if (p.isEmpty()) return ""
        if (p.startsWith("smb://", ignoreCase = true)) {
            p = try {
                val uri = android.net.Uri.parse(p)
                val segments = uri.pathSegments.map { java.net.URLDecoder.decode(it, "UTF-8") }
                if (segments.isEmpty()) "" else {
                    val smbShare = segments.first()
                    val insideShare = segments.drop(1).joinToString("/")
                    if (share.shareName.isBlank()) {
                        if (insideShare.isBlank()) smbShare else "$smbShare/$insideShare"
                    } else {
                        if (smbShare.equals(share.shareName, ignoreCase = true)) insideShare else segments.joinToString("/")
                    }
                }
            } catch (_: Exception) { raw }
        }
        p = p.removePrefix("/").trim('/')
        // Si le partage est déjà fixé, ne garde pas un préfixe redondant du nom de partage.
        if (share.shareName.isNotBlank()) {
            val prefix = share.shareName.trim('/') + "/"
            if (p.equals(share.shareName, ignoreCase = true)) return ""
            if (p.startsWith(prefix, ignoreCase = true)) p = p.substring(prefix.length)
        }
        return p
    }

    /**
     * Une seule navigation réseau à la fois. Sans ça, un listing SMB ou une recherche qui
     * termine après un clic dossier peut réécrire la RecyclerView avec un ancien chemin :
     * l'utilisateur a alors l'impression de revenir en arrière, puis les jobs empilés finissent
     * par saturer le threadpool SMB/IO et provoquer un ANR.
     */
    private var browseJob: Job? = null
    private var metadataJob: Job? = null
    private var loadGeneration: Long = 0L
    private var isBrowsing = false

    // Sélection multiple (pour "Ajouter à la playlist")
    private val selectedVideos = mutableSetOf<String>() // path
    private var currentVideos: List<MediaItem> = emptyList()

    private fun updateSelectionToolbar() {
        val toolbar = findViewById<View>(R.id.toolbarSelection) ?: return
        val tvSelectionCount = findViewById<TextView>(R.id.tvSelectionCount)
        if (selectedVideos.isEmpty()) {
            toolbar.visibility = View.GONE
        } else {
            toolbar.visibility = View.VISIBLE
            tvSelectionCount.text = "${selectedVideos.size} sélectionné(s)"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_video_browser)

        tvTitle = findViewById(R.id.tvTitle)
        tvPath = findViewById(R.id.tvPath)
        tvCount = findViewById(R.id.tvCount)
        recyclerNetwork = findViewById(R.id.recyclerNetwork)
        recyclerNetwork.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btnHome)?.setOnClickListener {
            val intent = Intent(this, fr.retrospare.blazeplayer.MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            intent.putExtra("requestedTab", 2) // Onglet Réseau
            startActivity(intent)
            finish()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener {
            // Annule d'abord tout travail en cours : un job SMB plus ancien ne doit jamais
            // pouvoir reposter une ancienne liste après un retour manuel.
            cancelNetworkWork()
            if (folderStack.isNotEmpty()) {
                val (path, _) = folderStack.removeLast()
                currentPath = path
                loadCurrentPath(pushToStack = false)
            } else {
                finish()
            }
        }

        findViewById<View>(R.id.btnCancelSelection)?.setOnClickListener {
            selectedVideos.clear()
            updateSelectionToolbar()
            recyclerNetwork.adapter?.notifyDataSetChanged()
        }
        findViewById<View>(R.id.btnAddToPlaylist)?.setOnClickListener {
            val tracks = currentVideos.filter { it.path in selectedVideos }
                .map { fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(it.path, it.name) }
            fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
                this, fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO, tracks
            ) {
                selectedVideos.clear()
                updateSelectionToolbar()
                recyclerNetwork.adapter?.notifyDataSetChanged()
            }
        }

        val shareId = intent.getStringExtra("shareId")
        if (shareId.isNullOrEmpty()) { finish(); return }
        val initialPathRaw = intent.getStringExtra("initialPath") ?: ""

        lifecycleScope.launch {
            val loadedShare = withContext(Dispatchers.IO) { networkRepository.getShareById(shareId) }
            if (loadedShare == null) { finish(); return@launch }
            share = loadedShare
            currentPath = normalizeInitialPath(initialPathRaw, share)
            tvTitle.text = share.name
            loadCurrentPath(pushToStack = false)
        }
    }

    private var currentAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    private fun cancelNetworkWork() {
        browseJob?.cancel()
        browseJob = null
        metadataJob?.cancel()
        metadataJob = null
        loadGeneration++
        isBrowsing = false
    }

    override fun onDestroy() {
        cancelNetworkWork()
        super.onDestroy()
    }

    private fun openFolder(folder: MediaItem) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFolderClickAtMs < 450L) return
        lastFolderClickAtMs = now
        if (isBrowsing) return
        val nextPath = normalizeInitialPath(folder.path, share)
        if (nextPath == currentPath) return
        cancelNetworkWork()
        folderStack.addLast(currentPath to tvTitle.text.toString())
        currentPath = nextPath
        loadCurrentPath(pushToStack = false)
    }

    private fun loadCurrentPath(pushToStack: Boolean = false) {
        val requestedPath = currentPath
        val generation = ++loadGeneration
        browseJob?.cancel()
        metadataJob?.cancel()
        metadataJob = null
        isBrowsing = true

        tvPath.text = if (share.type == ShareType.UPNP) (if (requestedPath == "0" || requestedPath.isEmpty()) share.name else requestedPath) else if (requestedPath.isEmpty()) share.host else "${share.host}/$requestedPath"
        tvCount.text = getString(R.string.loading)
        selectedVideos.clear()
        updateSelectionToolbar()

        browseJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    if (share.type == ShareType.UPNP) {
                        withTimeoutOrNull(20_000L) { upnpBrowser.listFiles(share, requestedPath.ifBlank { "0" }) }
                            ?: Result.failure(java.util.concurrent.TimeoutException("UPnP listing timeout"))
                    } else {
                        withTimeoutOrNull(20_000L) { smbBrowser.listFiles(share, requestedPath) }
                            ?: Result.failure(java.util.concurrent.TimeoutException("SMB listing timeout"))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            // Résultat obsolète : l'utilisateur a déjà navigué ailleurs. On l'ignore, sinon la
            // liste revient visuellement dans l'ancien dossier.
            if (generation != loadGeneration || currentPath != requestedPath || isFinishing || isDestroyed) return@launch
            isBrowsing = false

            result.onSuccess { items ->
                val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" }.map { it.copy(isNetwork = true, networkShareId = share.id) }.sortedBy { it.name.lowercase() }
                val rawVideos = items.filter {
                    it.mimeType != "folder" && it.mimeType != "share" &&
                        (it.extension.lowercase() in videoExtensions || it.mimeType.startsWith("video/"))
                }.map { it.copy(isNetwork = true, networkShareId = share.id) }.sortedBy { it.name.lowercase() }

                val videos = fr.retrospare.blazeplayer.player.VideoMetadataExtractor
                    .fastDecorateList(this@NetworkVideoBrowserActivity, rawVideos)
                    .toMutableList()
                currentVideos = videos
                tvCount.text = resources.getQuantityString(R.plurals.folder_count, folders.size, folders.size) + " - " + resources.getQuantityString(R.plurals.video_count, videos.size, videos.size)
                showList(folders, videos, generation)

                // UPnP expose déjà durée/taille via DIDL, puis on complète comme SMB avec le
                // cache disque + extraction en arrière-plan pour obtenir résolution/codecs et les
                // rendre aussi visibles dans le navigateur réseau que dans l'historique réseau.
                metadataJob = lifecycleScope.launch {
                    fr.retrospare.blazeplayer.player.VideoMetadataExtractor.enrichVideoItemsIncremental(
                        this@NetworkVideoBrowserActivity, videos, maxConcurrent = if (share.type == ShareType.UPNP) 2 else 1
                    ) { index, enriched ->
                        if (generation == loadGeneration && currentPath == requestedPath) {
                            withContext(Dispatchers.Main) {
                                if (index in videos.indices) {
                                    videos[index] = enriched.copy(isNetwork = true, networkShareId = share.id)
                                    currentVideos = videos
                                    recyclerNetwork.adapter?.notifyItemChanged(folders.size + index)
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                if (generation == loadGeneration && currentPath == requestedPath) {
                    tvCount.text = getString(R.string.toast_error_generic, it.message)
                }
            }
        }
        browseJob?.invokeOnCompletion {
            if (generation == loadGeneration) {
                isBrowsing = false
            }
        }
    }


    private val TYPE_FOLDER = 0
    private val TYPE_VIDEO = 1

    private fun showList(folders: List<MediaItem>, videos: List<MediaItem>, generation: Long) {
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

            override fun getItemViewType(position: Int) = if (position < folders.size) TYPE_FOLDER else TYPE_VIDEO
            override fun getItemCount() = folders.size + videos.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val layoutId = if (viewType == TYPE_FOLDER) R.layout.item_network_folder else R.layout.item_network_video
                val v = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (position < folders.size) {
                    val folder = folders[position]
                    holder.itemView.findViewById<TextView>(R.id.tvFolderName)?.text = folder.name
                    holder.itemView.setOnClickListener {
                        if (generation == loadGeneration) openFolder(folder)
                    }
                    holder.itemView.findViewById<View>(R.id.btnFolderMore)?.setOnClickListener { anchor ->
                        val popup = android.widget.PopupMenu(this@NetworkVideoBrowserActivity, anchor)
                        popup.menu.add(0, 1, 0, getString(R.string.dialog_add_favorite_folder))
                        popup.setOnMenuItemClickListener { mi ->
                            when (mi.itemId) {
                                1 -> {
                                    val favorite = fr.retrospare.blazeplayer.favorites.FavoriteFolder(
                                        path = folder.path, name = folder.name,
                                        shareId = share.id, shareName = share.name
                                    )
                                    fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showAddFavoriteDialog(
                                        this@NetworkVideoBrowserActivity,
                                        fr.retrospare.blazeplayer.favorites.FavoriteCategory.NETWORK,
                                        favorite
                                    )
                                    true
                                }
                                else -> false
                            }
                        }
                        popup.show()
                    }
                } else {
                    val video = videos[position - folders.size]
                    val v = holder.itemView
                    v.findViewById<TextView>(R.id.tvFileName)?.text = video.name
                    v.findViewById<TextView>(R.id.tvDuration)?.text = video.formattedDuration

                    // Miniature réseau à gauche du titre : cache local d'abord, extraction SMB
                    // asynchrone ensuite via ThumbnailUtils si rien n'est encore en cache.
                    v.findViewById<android.widget.ImageView>(R.id.ivThumbnail)?.let { thumb ->
                        (thumb.parent as? android.view.View)?.visibility = android.view.View.VISIBLE
                        thumb.setImageDrawable(null)
                        thumb.setTag(R.id.ivThumbnail, video.path)
                        val cached = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getCachedThumbnailBitmap(this@NetworkVideoBrowserActivity, video.path)
                        if (cached != null) {
                            thumb.setImageBitmap(cached)
                        } else {
                            lifecycleScope.launch {
                                val bitmap = fr.retrospare.blazeplayer.ui.ThumbnailUtils.getThumbnailBitmap(this@NetworkVideoBrowserActivity, video.path)
                                if (thumb.getTag(R.id.ivThumbnail) == video.path && bitmap != null) thumb.setImageBitmap(bitmap)
                            }
                        }
                    }
                    v.findViewById<android.widget.ImageView>(R.id.ivPlayOverlay)?.visibility = android.view.View.VISIBLE

                    val ext = containerBadgeFrom(video)
                    val tvFmt = v.findViewById<TextView>(R.id.tvFormat)
                    fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvFmt, ext)
                    tvFmt?.visibility = if (ext.isNotEmpty()) View.VISIBLE else View.GONE

                    // Affichage direct et synchrone — un premier badge deviné depuis l'extension
                    // est déjà là (VideoMetadataExtractor.fastDecorateList, à l'affichage), puis
                    // remplacé par la vraie résolution/durée dès qu'elle arrive en arrière-plan
                    // (enrichVideoItemsIncremental + notifyItemChanged). Plus d'attente sur toute
                    // la liste, plus de risque de badge manquant après un défilement.
                    val tvRes = v.findViewById<TextView>(R.id.tvResolution)
                    val tvVid = v.findViewById<TextView>(R.id.tvVideoCodec)
                    val tvAud = v.findViewById<TextView>(R.id.tvAudioCodec)
                    fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvRes)
                    fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvVid)
                    fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvAud)
                    tvRes?.text = video.resolution ?: ""
                    tvRes?.visibility = if (!video.resolution.isNullOrEmpty()) View.VISIBLE else View.GONE
                    tvVid?.text = video.videoCodec ?: ""
                    tvVid?.visibility = if (!video.videoCodec.isNullOrEmpty()) View.VISIBLE else View.GONE
                    tvAud?.text = video.audioCodec ?: ""
                    tvAud?.visibility = if (!video.audioCodec.isNullOrEmpty()) View.VISIBLE else View.GONE

                    fun openVideo() {
                        startActivity(Intent(this@NetworkVideoBrowserActivity, PlayerActivity::class.java).apply {
                            putExtra("mediaPath", video.path)
                            putExtra("mediaName", video.name)
                            putExtra("isNetworkMedia", true)
                            putExtra("networkShareId", share.id)
                        })
                    }

                    v.setOnTouchListener(null)
                    v.setOnClickListener { openVideo() }

                    v.findViewById<View>(R.id.btnMore)?.setOnClickListener { anchor ->
                        val popup = android.widget.PopupMenu(this@NetworkVideoBrowserActivity, anchor)
                        popup.menu.add(0, 1, 0, getString(R.string.action_play))
                        popup.menu.add(0, 2, 1, getString(R.string.action_information))
                        popup.setOnMenuItemClickListener { mi ->
                            when (mi.itemId) {
                                1 -> {
                                    startActivity(Intent(this@NetworkVideoBrowserActivity, PlayerActivity::class.java).apply {
                                        putExtra("mediaPath", video.path)
                                        putExtra("mediaName", video.name)
                                        putExtra("isNetworkMedia", true)
                                        putExtra("networkShareId", share.id)
                                    })
                                    true
                                }
                                2 -> {
                                    fr.retrospare.blazeplayer.ui.VideoInfoDialog.show(
                                        context = this@NetworkVideoBrowserActivity,
                                        scope = lifecycleScope,
                                        title = video.name,
                                        mediaPath = video.path,
                                        displayName = video.name,
                                        extension = video.extension.uppercase(),
                                        itemSizeBytes = video.size,
                                        itemDurationSeconds = video.duration,
                                        fullExtract = true
                                    )
                                    true
                                }
                                else -> false
                            }
                        }
                        popup.show()
                    }

                    val checkbox = v.findViewById<android.widget.CheckBox>(R.id.checkboxSelect)
                    checkbox?.setOnCheckedChangeListener(null)
                    checkbox?.isChecked = selectedVideos.contains(video.path)
                    checkbox?.setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedVideos.add(video.path) else selectedVideos.remove(video.path)
                        updateSelectionToolbar()
                    }
                }
            }
        }
        recyclerNetwork.adapter = adapter
        currentAdapter = adapter
    }
}
