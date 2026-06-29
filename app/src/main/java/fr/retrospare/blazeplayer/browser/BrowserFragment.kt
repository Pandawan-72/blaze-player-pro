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
    private var audioPickMode = false

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
        audioPickMode = arguments?.getBoolean("audioPickMode") ?: false
        val audioOnlyMode = arguments?.getBoolean("audioOnlyMode") ?: false
        val isNetwork = arguments?.getBoolean("isNetwork", false) ?: false
        if (audioOnlyMode) {
            viewModel.setAudioOnlyMode(true)
            // Titre correct selon le type de navigateur
            binding.tvTitle.text = if (isNetwork) "Réseau" else "Mes fichiers audio"
        }

        setupRecyclerView()
        setupButtons()
        observeViewModel()
        setupSelectionToolbar()
        val shareId = arguments?.getString("shareId")
        val initPath = arguments?.getString("path") ?: ""

        when {
            isNetwork && !shareId.isNullOrEmpty() -> viewModel.loadNetworkFilesById(shareId, initPath)
            isNetwork -> viewModel.loadNetworkShares() // Affiche la liste des partages réseau
            else -> viewModel.loadLocalFiles()
        }
    }

    private fun setupSelectionToolbar() {
        binding.root.findViewById<android.widget.ImageButton>(R.id.btnCancelSelection)
            ?.setOnClickListener {
                adapter.clearSelection()
                binding.toolbarSelection.visibility = android.view.View.GONE
            }
        binding.root.findViewById<android.widget.ImageButton>(R.id.btnSelectAll)
            ?.setOnClickListener {
                adapter.selectAll()
                val count = adapter.itemCount
                binding.tvSelectionCount.text = "$count sélectionné(s)"
                adapter.onSelectionChanged?.invoke(adapter.getSelectedItems().map { it.id }.toSet())
            }
        binding.root.findViewById<android.widget.Button>(R.id.btnAddSelected)
            ?.setOnClickListener {
                val selected = adapter.getSelectedItems()
                if (selected.isNotEmpty()) {
                    selected.forEach { item ->
                        PlayerRouter.open(requireContext(), item.path, item.name)
                    }
                    adapter.clearSelection()
                    binding.toolbarSelection.visibility = android.view.View.GONE
                }
            }
    }


    private fun setupRecyclerView() {
        // Toolbar sélection multiple
        adapter = BrowserAdapter(
            onFolderClick = { item ->
                breadcrumbParts.add(item.name)
                updateBreadcrumb()
                val share = viewModel.currentShare
                when {
                    share != null -> viewModel.loadNetworkFiles(share, item.path)
                    item.mimeType == "network" -> {
                        // Clic sur un favori réseau — charge ses fichiers
                        viewModel.loadNetworkFilesById(item.id, "")
                    }
                    else -> viewModel.loadLocalFiles(item.path)
                }
            },
            onFileClick = { item ->
                // DEBUG
                android.widget.Toast.makeText(requireContext(), "clic fichier audioPickMode=$audioPickMode args=${arguments?.keySet()}", android.widget.Toast.LENGTH_LONG).show()
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

        // showAudio et showHidden sont chargés automatiquement depuis DataStore dans le ViewModel

        // Si le paramètre global est activé, l'icone est non cliquable
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showAudio.collect { active ->
                val settingEnabled = viewModel.isShowAudioFromSettings()
                if (settingEnabled) {
                    binding.btnToggleAudio.alpha = 0.3f
                    binding.btnToggleAudio.isEnabled = false
                    binding.btnToggleAudio.setColorFilter(resources.getColor(R.color.green_accent, null))
                } else {
                    binding.btnToggleAudio.alpha = 1f
                    binding.btnToggleAudio.isEnabled = true
                    binding.btnToggleAudio.setColorFilter(
                        if (active) resources.getColor(R.color.green_accent, null)
                        else resources.getColor(R.color.on_surface_variant, null)
                    )
                }
            }
        }
        binding.btnToggleAudio.setOnClickListener {
            if (!viewModel.isShowAudioFromSettings()) {
                viewModel.toggleShowAudio()
            }
        }
        binding.btnSearch.setOnClickListener {
            val searchView = binding.root.findViewById<android.widget.EditText>(R.id.etSearch)
            val searchVisible = searchView?.visibility == android.view.View.VISIBLE
            if (searchVisible) {
                binding.root.findViewById<android.widget.EditText>(R.id.etSearch).visibility = android.view.View.GONE
                binding.root.findViewById<android.widget.EditText>(R.id.etSearch).text?.clear()
                adapter.filter("")
            } else {
                val et = binding.root.findViewById<android.widget.EditText>(R.id.etSearch)
                et.visibility = android.view.View.VISIBLE
                et.requestFocus()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        binding.root.findViewById<android.widget.EditText>(R.id.etSearch)?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
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
                                adapter.setFullList(state.items)
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
                        val audioOnly = arguments?.getBoolean("audioOnlyMode") ?: false
                        val isNet = arguments?.getBoolean("isNetwork", false) ?: false
                        binding.tvTitle.text = when {
                            breadcrumbParts.isNotEmpty() -> breadcrumbParts.last()
                            audioOnly && isNet -> "Réseau"
                            audioOnly -> "Mes fichiers audio"
                            else -> "Mes fichiers locaux"
                        }
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
