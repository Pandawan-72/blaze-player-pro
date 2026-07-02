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
    private var globalSearchJob: kotlinx.coroutines.Job? = null

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
            binding.tvTitle.text = if (isNetwork) getString(R.string.tab_network) else getString(R.string.browser_files_audio)
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
            else -> viewModel.loadLocalFiles(initPath)
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
        binding.root.findViewById<android.widget.Button>(R.id.btnAddToPlaylist)
            ?.setOnClickListener {
                val selected = adapter.getSelectedItems()
                val isNetwork = arguments?.getBoolean("isNetwork", false) ?: false
                val category = if (isNetwork)
                    fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO
                else
                    fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO
                val tracks = selected.map { fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(it.path, it.name) }
                fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(requireContext(), category, tracks) {
                    adapter.clearSelection()
                    binding.toolbarSelection.visibility = android.view.View.GONE
                }
            }
        // Sans ça, le mode sélection s'activait (case à cocher visible) mais la barre d'actions
        // en haut ne s'affichait jamais : onSelectionChanged n'était jamais branché.
        adapter.onSelectionChanged = { selected ->
            if (selected.isEmpty()) {
                binding.toolbarSelection.visibility = android.view.View.GONE
            } else {
                binding.toolbarSelection.visibility = android.view.View.VISIBLE
                binding.tvSelectionCount.text = "${selected.size} sélectionné(s)"
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
        binding.btnHome?.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }
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
            binding.tvSortLabel.text = when (viewModel.sortMode.value) {
                fr.retrospare.blazeplayer.browser.BrowserViewModel.SortMode.NAME_ASC -> getString(R.string.sort_name_az)
                fr.retrospare.blazeplayer.browser.BrowserViewModel.SortMode.NAME_DESC -> getString(R.string.sort_name_za)
                fr.retrospare.blazeplayer.browser.BrowserViewModel.SortMode.DATE_DESC -> getString(R.string.sort_date_recent)
                fr.retrospare.blazeplayer.browser.BrowserViewModel.SortMode.SIZE_DESC -> getString(R.string.sort_size)
            }
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
                val query = s?.toString() ?: ""
                globalSearchJob?.cancel()
                val isNetworkMode = arguments?.getBoolean("isNetwork", false) ?: false
                if (!isNetworkMode && query.length >= 3) {
                    // Recherche globale parmi toutes les vidéos locales (tous dossiers confondus)
                    // via l'index MediaStore, plutôt que de filtrer seulement le dossier courant.
                    globalSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(300)
                        val results = viewModel.searchAllLocalVideos(query)
                        adapter.setFullList(results)
                        adapter.filter("")
                    }
                } else {
                    // Repasse en filtrage simple du dossier courant (comportement d'origine)
                    val current = (viewModel.state.value as? BrowserViewModel.BrowserState.Success)?.items
                    if (current != null) adapter.setFullList(current)
                    adapter.filter(query)
                }
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
                                val folderText = resources.getQuantityString(R.plurals.folder_count, folders, folders)
                                val fileText = resources.getQuantityString(R.plurals.file_count, files, files)
                                binding.tvFileCount.text = getString(R.string.browser_folder_file_count, folderText, fileText)
                            }
                            is BrowserViewModel.BrowserState.Error -> {
                                binding.recyclerView.visibility = View.VISIBLE
                                android.widget.Toast.makeText(requireContext(), getString(R.string.toast_error_generic, state.message), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                launch {
                    viewModel.currentPath.collect { path ->
                        binding.tvPath.text = path.ifEmpty { getString(R.string.path_internal_storage) }
                        val audioOnly = arguments?.getBoolean("audioOnlyMode") ?: false
                        val isNet = arguments?.getBoolean("isNetwork", false) ?: false
                        binding.tvTitle.text = when {
                            breadcrumbParts.isNotEmpty() -> breadcrumbParts.last()
                            audioOnly && isNet -> getString(R.string.tab_network)
                            audioOnly -> getString(R.string.browser_files_audio)
                            else -> getString(R.string.browser_files_local)
                        }
                    }
                }
            }
        }
    }

    private fun updateBreadcrumb() {
        binding.breadcrumbContainer.removeAllViews()
        val allParts = listOf(getString(R.string.breadcrumb_home)) + breadcrumbParts
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
