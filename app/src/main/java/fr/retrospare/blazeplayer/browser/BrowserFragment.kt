package fr.retrospare.blazeplayer.browser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.databinding.FragmentBrowserBinding
import fr.retrospare.blazeplayer.player.PlayerRouter
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BrowserFragment : Fragment() {

    private val viewModel: BrowserViewModel by viewModels()
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: BrowserAdapter
    private val breadcrumbParts = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeViewModel()
        val shareId = arguments?.getString("shareId")
        val isNetwork = arguments?.getBoolean("isNetwork", false) ?: false
        val initPath = arguments?.getString("path") ?: ""

        if (isNetwork && !shareId.isNullOrEmpty()) {
            viewModel.loadNetworkFilesById(shareId, initPath)
        } else {
            viewModel.loadLocalFiles()
        }
    }

    private fun setupRecyclerView() {
        adapter = BrowserAdapter(
            onFolderClick = { item ->
                breadcrumbParts.add(item.name)
                updateBreadcrumb()
                val share = viewModel.currentShare
                if (share != null) {
                    viewModel.loadNetworkFiles(share, item.path)
                } else {
                    viewModel.loadLocalFiles(item.path)
                }
            },
            onFileClick = { item ->
                PlayerRouter.open(requireContext(), item.path, item.name)
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            if (breadcrumbParts.isNotEmpty()) {
                breadcrumbParts.removeAt(breadcrumbParts.lastIndex)
                updateBreadcrumb()
                val share = viewModel.currentShare
                val path = if (breadcrumbParts.isEmpty()) "" else breadcrumbParts.last()
                if (share != null) {
                    viewModel.loadNetworkFiles(share, path)
                } else {
                    viewModel.loadLocalFiles(path)
                }
            } else {
                findNavController().popBackStack()
            }
        }
        binding.btnSort.setOnClickListener {
            viewModel.cycleSortMode()
            binding.tvSortLabel.text = viewModel.sortLabel()
        }
        binding.btnToggleView.setOnClickListener { }
        // Applique les préférences settings
        val prefs = requireContext().getSharedPreferences("datastore-preferences", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("show_audio", false) != viewModel.showAudio.value) {
            viewModel.toggleShowAudio()
        }

        binding.btnToggleAudio.setOnClickListener {
            viewModel.toggleShowAudio()
            val active = viewModel.showAudio.value
            binding.btnToggleAudio.setColorFilter(
                if (active) resources.getColor(R.color.green_accent, null)
                else resources.getColor(R.color.on_surface_variant, null)
            )
        }
        binding.btnSearch.setOnClickListener { }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        when (state) {
                            is BrowserViewModel.BrowserState.Loading -> binding.recyclerView.visibility = View.GONE
                            is BrowserViewModel.BrowserState.Success -> {
                                binding.recyclerView.visibility = View.VISIBLE
                                adapter.submitList(state.items)
                                val folders = state.items.count { it.mimeType == "folder" }
                                val files = state.items.count { it.mimeType != "folder" }
                                binding.tvFileCount.text = "$folders dossiers · $files fichiers"
                            }
                            is BrowserViewModel.BrowserState.Error -> binding.recyclerView.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    viewModel.currentPath.collect { path ->
                        binding.tvPath.text = path.ifEmpty { "/stockage/interne" }
                        binding.tvTitle.text = if (breadcrumbParts.isEmpty()) "Mes fichiers locaux" else breadcrumbParts.last()
                    }
                }
            }
        }
    }

    private fun updateBreadcrumb() {
        binding.breadcrumbContainer.removeAllViews()
        val allParts = listOf("Accueil") + breadcrumbParts
        allParts.forEachIndexed { index, part ->
            val tv = TextView(requireContext()).apply {
                text = part
                textSize = 12f
                val isLast = index == allParts.lastIndex
                setTextColor(resources.getColor(if (isLast) R.color.green_accent else R.color.on_surface_variant, null))
                setPadding(8, 6, 8, 6)
                setOnClickListener {
                    if (!isLast) {
                        repeat(allParts.size - 1 - index) { if (breadcrumbParts.isNotEmpty()) breadcrumbParts.removeAt(breadcrumbParts.lastIndex) }
                        updateBreadcrumb()
                        viewModel.loadLocalFiles(if (breadcrumbParts.isEmpty()) "" else breadcrumbParts.last())
                    }
                }
            }
            binding.breadcrumbContainer.addView(tv)
            if (index < allParts.lastIndex) {
                val sep = TextView(requireContext()).apply {
                    text = "›"
                    textSize = 12f
                    setTextColor(resources.getColor(R.color.on_surface_variant, null))
                    setPadding(4, 6, 4, 6)
                }
                binding.breadcrumbContainer.addView(sep)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
