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

        val shareId = intent.getStringExtra("shareId")
        if (shareId.isNullOrEmpty()) { finish(); return }

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
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { smbBrowser.listFiles(share, currentPath) }
            result.onSuccess { items ->
                val folders = items.filter { it.mimeType == "folder" || it.mimeType == "share" }.sortedBy { it.name.lowercase() }
                val videos = items.filter {
                    it.mimeType != "folder" && it.mimeType != "share" && it.extension.lowercase() in videoExtensions
                }.sortedBy { it.name.lowercase() }
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
                } else {
                    val video = videos[position - folders.size]
                    val v = holder.itemView
                    v.findViewById<TextView>(R.id.tvFileName)?.text = video.name
                    v.findViewById<TextView>(R.id.tvDuration)?.text = video.formattedDuration

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
                }
            }
        }
    }
}
