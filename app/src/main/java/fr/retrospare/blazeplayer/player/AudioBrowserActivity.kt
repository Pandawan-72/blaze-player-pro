package fr.retrospare.blazeplayer.player

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.databinding.ActivityAudioBrowserBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class AudioBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "extra_paths"
        const val EXTRA_NAMES = "extra_names"
    }

    private lateinit var binding: ActivityAudioBrowserBinding
    private val selectedItems = mutableSetOf<Int>()
    private var allItems = listOf<AudioFile>()

    data class AudioFile(val id: Long, val name: String, val path: String, val duration: Long, val artist: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnConfirm.setOnClickListener { confirmSelection() }

        loadAudioFiles()
    }

    private fun loadAudioFiles() {
        CoroutineScope(Dispatchers.Main).launch {
            allItems = withContext(Dispatchers.IO) { scanAudioFiles() }
            setupRecycler()
        }
    }

    private suspend fun scanAudioFiles(): List<AudioFile> {
        val items = mutableListOf<AudioFile>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TITLE
        )
        contentResolver.query(
            collection, projection, null, null,
            MediaStore.Audio.Media.DISPLAY_NAME
        )?.use { cursor ->
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
                items.add(AudioFile(id, title, uri, duration, artist))
            }
        }
        return items
    }

    private fun setupRecycler() {
        val adapter = AudioBrowserAdapter(allItems) { index, checked ->
            if (checked) selectedItems.add(index) else selectedItems.remove(index)
            updateCounter()
        }
        binding.recyclerAudio.layoutManager = LinearLayoutManager(this)
        binding.recyclerAudio.adapter = adapter
    }

    private fun updateCounter() {
        val n = selectedItems.size
        binding.tvSelected.text = "$n piste${if (n > 1) "s" else ""} sélectionnée${if (n > 1) "s" else ""}"
    }

    private fun confirmSelection() {
        val paths = ArrayList<String>()
        val names = ArrayList<String>()
        selectedItems.sorted().forEach { idx ->
            paths.add(allItems[idx].path)
            names.add(allItems[idx].name)
        }
        val intent = Intent().apply {
            putStringArrayListExtra(EXTRA_PATHS, paths)
            putStringArrayListExtra(EXTRA_NAMES, names)
        }
        setResult(RESULT_OK, intent)
        finish()
    }
}

class AudioBrowserAdapter(
    private val items: List<AudioBrowserActivity.AudioFile>,
    private val onToggle: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<AudioBrowserAdapter.ViewHolder>() {

    private val selected = mutableSetOf<Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_browser, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position in selected) { checked ->
            if (checked) selected.add(position) else selected.remove(position)
            onToggle(position, checked)
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvAudioTitle)
        private val tvArtist: TextView = view.findViewById(R.id.tvAudioArtist)
        private val tvDuration: TextView = view.findViewById(R.id.tvAudioDuration)
        private val checkbox: CheckBox = view.findViewById(R.id.checkAudio)

        fun bind(item: AudioBrowserActivity.AudioFile, isSelected: Boolean, onToggle: (Boolean) -> Unit) {
            tvTitle.text = item.name
            tvArtist.text = item.artist
            tvDuration.text = formatTime(item.duration)
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = isSelected
            checkbox.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            itemView.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
        }

        private fun formatTime(s: Long) = "%d:%02d".format(s / 60, s % 60)
    }
}
