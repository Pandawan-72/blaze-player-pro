package fr.retrospare.blazeplayer.home

import android.content.ContentUris
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import fr.retrospare.blazeplayer.browser.BrowserAdapter
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.databinding.FragmentSearchBinding
import fr.retrospare.blazeplayer.player.PlayerRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: BrowserAdapter
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = BrowserAdapter(
            onFolderClick = { item -> PlayerRouter.open(requireContext(), item.path, item.name) },
            onFileClick = { item -> PlayerRouter.open(requireContext(), item.path, item.name) }
        )
        binding.recyclerSearch.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSearch.adapter = adapter

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        binding.searchView.requestFocus()
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                val q = newText?.trim() ?: return true
                if (q.length < 2) {
                    adapter.submitList(emptyList())
                    binding.tvCount.text = ""
                    return true
                }
                searchJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    val results = searchMedia(q)
                    adapter.submitList(results)
                    binding.tvCount.text = "${results.size} résultat(s)"
                }
                return true
            }
        })
    }

    private suspend fun searchMedia(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MediaItem>()

        // Vidéos
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videoProj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE
        )
        requireContext().contentResolver.query(
            videoUri, videoProj,
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            MediaStore.Video.Media.DISPLAY_NAME
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(videoUri, id).toString()
                results.add(MediaItem(
                    id = id.toString(), name = name, path = uri,
                    size = cursor.getLong(sizeCol),
                    duration = cursor.getLong(durCol) / 1000,
                    mimeType = cursor.getString(mimeCol) ?: "",
                    extension = name.substringAfterLast('.', "").lowercase(),
                    isNetwork = false
                ))
            }
        }

        // Audio
        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val audioProj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )
        requireContext().contentResolver.query(
            audioUri, audioProj,
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            MediaStore.Audio.Media.DISPLAY_NAME
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(audioUri, id).toString()
                results.add(MediaItem(
                    id = id.toString(), name = name, path = uri,
                    size = cursor.getLong(sizeCol),
                    duration = cursor.getLong(durCol) / 1000,
                    mimeType = cursor.getString(mimeCol) ?: "",
                    extension = name.substringAfterLast('.', "").lowercase(),
                    isNetwork = false
                ))
            }
        }

        results
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
