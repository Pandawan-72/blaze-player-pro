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
import fr.retrospare.blazeplayer.network.SmbBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class NetworkVideoBrowserActivity : AppCompatActivity() {

    @Inject lateinit var smbBrowser: SmbBrowser
    @Inject lateinit var networkRepository: fr.retrospare.blazeplayer.data.repository.NetworkRepository

    private lateinit var tvTitle: TextView
    private lateinit var tvPath: TextView
    private lateinit var tvCount: TextView
    private lateinit var recyclerNetwork: RecyclerView

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "flv", "wmv", "3gp", "ts")
    private val folderStack = ArrayDeque<Pair<String, String>>() // (path, displayName)

    private lateinit var share: NetworkShare
    private var currentPath: String = ""

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
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            intent.putExtra("requestedTab", 2) // Onglet Reseau
            startActivity(intent)
            finish()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (folderStack.isNotEmpty()) {
                val (path, _) = folderStack.removeLast()
                currentPath = path
                loadCurrentPath()
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
        currentPath = intent.getStringExtra("initialPath") ?: ""

        lifecycleScope.launch {
            val loadedShare = withContext(Dispatchers.IO) { networkRepository.getShareById(shareId) }
            if (loadedShare == null) { finish(); return@launch }
            share = loadedShare
            tvTitle.text = share.name
            loadCurrentPath()
        }
    }

    private fun loadCurrentPath() {
        tvPath.text = if (currentPath.isEmpty()) share.host else "${share.host}/$currentPath"
        tvCount.text = "Chargement..."
        selectedVideos.clear()
        updateSelectionToolbar()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { smbBrowser.listFiles(share, currentPath) }
            result.onSuccess { items ->
                val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" }.sortedBy { it.name.lowercase() }
                val videos = items.filter {
                    it.mimeType != "folder" && it.mimeType != "share" && it.extension.lowercase() in videoExtensions
                }.sortedBy { it.name.lowercase() }
                currentVideos = videos
                tvCount.text = "${folders.size} dossier${if (folders.size > 1) "s" else ""} - ${videos.size} video${if (videos.size > 1) "s" else ""}"
                showList(folders, videos)
            }.onFailure {
                tvCount.text = "Erreur: ${it.message}"
            }
        }
    }


    private val TYPE_FOLDER = 0
    private val TYPE_VIDEO = 1

    private fun showList(folders: List<MediaItem>, videos: List<MediaItem>) {
        recyclerNetwork.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                        folderStack.addLast(currentPath to (tvTitle.text.toString()))
                        currentPath = folder.path
                        loadCurrentPath()
                    }
                    holder.itemView.findViewById<View>(R.id.btnFolderMore)?.setOnClickListener { anchor ->
                        val popup = android.widget.PopupMenu(this@NetworkVideoBrowserActivity, anchor)
                        popup.menu.add(0, 1, 0, "Ajouter dossier favori")
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

                    // Miniature (cache mémoire + disque via ThumbnailUtils, comme les autres
                    // navigateurs) : n'existait pas du tout ici auparavant.
                    val ivThumb = v.findViewById<android.widget.ImageView>(R.id.ivThumbnail)
                    ivThumb?.setImageBitmap(null)
                    lifecycleScope.launch {
                        fr.retrospare.blazeplayer.ui.ThumbnailUtils.loadThumbnail(
                            this@NetworkVideoBrowserActivity, video.path, ivThumb ?: return@launch
                        )
                    }

                    val ext = video.extension.uppercase()
                    val tvFmt = v.findViewById<TextView>(R.id.tvFormat)
                    tvFmt?.text = ext
                    tvFmt?.visibility = if (ext.isNotEmpty()) View.VISIBLE else View.GONE

                    // État initial
                    val tvRes = v.findViewById<TextView>(R.id.tvResolution)
                    val tvVid = v.findViewById<TextView>(R.id.tvVideoCodec)
                    val tvAud = v.findViewById<TextView>(R.id.tvAudioCodec)
                    tvRes?.visibility = View.GONE
                    tvVid?.visibility = View.GONE
                    tvAud?.visibility = View.GONE

                    // Extraction des métadonnées (résolution, codecs) via VideoMetadataExtractor
                    lifecycleScope.launch {
                        val info = VideoMetadataExtractor.extract(this@NetworkVideoBrowserActivity, video.path)
                        if (v.findViewById<TextView>(R.id.tvFileName)?.text == video.name) {
                            tvRes?.text = info.qualityBadge
                            tvRes?.visibility = if (info.qualityBadge.isNotEmpty()) View.VISIBLE else View.GONE
                            tvVid?.text = info.videoCodec
                            tvVid?.visibility = if (info.videoCodec.isNotEmpty()) View.VISIBLE else View.GONE
                            tvAud?.text = info.audioCodec
                            tvAud?.visibility = if (info.audioCodec.isNotEmpty()) View.VISIBLE else View.GONE
                            if (info.duration > 0) {
                                v.findViewById<TextView>(R.id.tvDuration)?.text =
                                    if (info.resolutionLabel.isNotEmpty()) "${info.formattedDuration} - ${info.resolutionLabel}"
                                    else info.formattedDuration
                            }
                        }
                    }

                    v.setOnClickListener {
                        startActivity(Intent(this@NetworkVideoBrowserActivity, PlayerActivity::class.java).apply {
                            putExtra("mediaPath", video.path)
                            putExtra("mediaName", video.name)
                        })
                    }

                    v.findViewById<View>(R.id.btnMore)?.setOnClickListener { anchor ->
                        val popup = android.widget.PopupMenu(this@NetworkVideoBrowserActivity, anchor)
                        popup.menu.add(0, 1, 0, "Lire")
                        popup.menu.add(0, 2, 1, "Informations")
                        popup.setOnMenuItemClickListener { mi ->
                            when (mi.itemId) {
                                1 -> {
                                    startActivity(Intent(this@NetworkVideoBrowserActivity, PlayerActivity::class.java).apply {
                                        putExtra("mediaPath", video.path)
                                        putExtra("mediaName", video.name)
                                    })
                                    true
                                }
                                2 -> {
                                    lifecycleScope.launch {
                                        val info = VideoMetadataExtractor.extract(this@NetworkVideoBrowserActivity, video.path)
                                        val sz = if (info.sizeBytes > 0) android.text.format.Formatter.formatShortFileSize(this@NetworkVideoBrowserActivity, info.sizeBytes) else "Inconnue"
                                        val ds = if (info.duration > 0) info.formattedDuration else "N/A"
                                        val msg = "Chemin : ${video.path}\n\n" +
                                            "Conteneur : ${video.extension.uppercase()}\n" +
                                            "Durée : $ds\n" +
                                            "Taille : $sz"
                                        android.app.AlertDialog.Builder(this@NetworkVideoBrowserActivity)
                                            .setTitle(video.name)
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
    }
}
