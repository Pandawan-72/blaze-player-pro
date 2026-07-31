package fr.retrospare.blazeplayer.browser

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.databinding.FragmentBrowserBinding
import fr.retrospare.blazeplayer.player.PlayerRouter
import fr.retrospare.blazeplayer.ui.showPremium
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BrowserFragment : Fragment() {
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository

    private enum class SourceMode { LOCAL, NETWORK }
    private data class BrowserCrumb(val name: String, val path: String, val shareId: String? = null)

    private var audioOnlyMode = false
    private var videoActionsEnabled = true
    private var sourceMode = SourceMode.LOCAL

    private val viewModel: BrowserViewModel by viewModels()
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: BrowserAdapter
    private val breadcrumbParts = mutableListOf<BrowserCrumb>()
    private var globalSearchJob: kotlinx.coroutines.Job? = null
    private var searchGeneration = 0

    private data class PendingVideoRename(val item: MediaItem, val baseName: String)
    private var pendingVideoRename: PendingVideoRename? = null
    private val renamePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingVideoRename
        pendingVideoRename = null
        if (result.resultCode == Activity.RESULT_OK && pending != null && isAdded && _binding != null) {
            executeVideoRename(pending.item, pending.baseName)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audioOnlyMode = arguments?.getBoolean("audioOnlyMode") ?: false
        sourceMode = if (arguments?.getBoolean("isNetwork", false) == true) SourceMode.NETWORK else SourceMode.LOCAL
        videoActionsEnabled = !audioOnlyMode
        binding.videoSelectionActions.visibility = if (videoActionsEnabled) View.VISIBLE else View.GONE
        binding.videoBrowserSourceTabs.visibility = View.VISIBLE
        if (audioOnlyMode) viewModel.setAudioOnlyMode(true)

        setupRecyclerView()
        setupButtons()
        observeViewModel()
        setupSelectionToolbar()
        updateSourceTabs()
        monitorNetworkTrialExpiry()

        val shareId = arguments?.getString("shareId")
        val initPath = arguments?.getString("path") ?: ""
        if (sourceMode == SourceMode.NETWORK) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (!fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)) {
                    sourceMode = SourceMode.LOCAL
                    updateSourceTabs()
                    updateBreadcrumb()
                    viewModel.loadLocalFiles("")
                    openPaywall()
                } else {
                    if (!shareId.isNullOrEmpty()) {
                        if (initPath.isNotBlank()) breadcrumbParts += BrowserCrumb(initPath.substringAfterLast('/').ifBlank { initPath }, initPath, shareId)
                        viewModel.loadNetworkFilesById(shareId, initPath)
                    } else {
                        viewModel.loadNetworkShares()
                    }
                    updateBreadcrumb()
                }
            }
        } else {
            viewModel.loadLocalFiles(initPath)
            updateBreadcrumb()
        }
    }

    private fun monitorNetworkTrialExpiry() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val state = userRepository.currentAccessState()
                if (state.isTrialActive) {
                    kotlinx.coroutines.delay(
                        (state.trialEndMillis - state.evaluatedAtMillis).coerceAtLeast(1L)
                    )
                }
                if (_binding != null && isAdded && sourceMode == SourceMode.NETWORK && !userRepository.currentAccessState().hasProAccess) {
                    switchToLocalRoot()
                    openPaywall()
                }
            }
        }
    }

    private fun isVideoItem(item: MediaItem): Boolean {
        if (item.mimeType == "folder" || item.mimeType == "share" || item.mimeType == "network") return false
        val ext = item.extension.lowercase().ifBlank { item.name.substringAfterLast('.', "").lowercase() }
        return item.mimeType.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "ts", "m2ts", "mts", "3gp", "3g2", "mpg", "mpeg", "divx")
    }

    private fun selectedVideoRefs(): List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef> =
        adapter.getSelectedItems()
            .filter { isVideoItem(it) }
            .map { fr.retrospare.blazeplayer.player.VideoQueueManager.fromMediaItem(it) }

    /** Depuis l'onglet unique Blaze Video, les playlists 1–5 et la file d'attente sont unifiées :
     *  elles acceptent les fichiers locaux, SMB et UPnP dans la même catégorie historique. */
    private fun currentVideoCategory(): fr.retrospare.blazeplayer.playlist.PlaylistCategory =
        fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO

    private fun clearVideoSelection() {
        adapter.clearSelection()
    }

    private fun setupSelectionToolbar() {
        binding.root.findViewById<android.widget.TextView>(R.id.btnAddToPlaylist)
            ?.setOnClickListener {
                val tracks = selectedVideoRefs()
                if (tracks.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_video_queue_no_video_selected), android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(requireContext(), currentVideoCategory(), tracks) {
                    clearVideoSelection()
                }
            }
        binding.root.findViewById<android.widget.TextView>(R.id.btnAddToQueue)
            ?.setOnClickListener {
                val tracks = selectedVideoRefs()
                if (tracks.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_video_queue_no_video_selected), android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val added = fr.retrospare.blazeplayer.player.VideoQueueManager.addToQueue(requireContext(), currentVideoCategory(), tracks)
                val already = tracks.size - added
                val msg = if (already > 0) getString(R.string.toast_video_queue_added_partial, added, already)
                else getString(R.string.toast_video_queue_added, added)
                android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
                clearVideoSelection()
            }
        adapter.onSelectionChanged = { }
    }

    private fun setupRecyclerView() {
        adapter = BrowserAdapter(
            onFolderClick = { item -> openFolderLikeItem(item) },
            onFileClick = { item -> PlayerRouter.open(requireContext(), item.path, item.name) },
            onRenameRequested = { item -> showRenameVideoDialog(item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        // Les métadonnées arrivent progressivement. Sans animateur de changement, la mise à jour
        // des badges ne fait plus clignoter/recomposer visuellement le titre long.
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = adapter
    }

    private fun showRenameVideoDialog(item: MediaItem) {
        val extension = VideoFileRenamer.originalExtension(item)
        val currentBaseName = VideoFileRenamer.originalBaseName(item)
        val density = resources.displayMetrics.density
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (4 * density).toInt(), (20 * density).toInt(), 0)
        }
        val input = EditText(requireContext()).apply {
            setSingleLine(true)
            setText(currentBaseName)
            setSelection(text.length)
            hint = getString(R.string.browser_rename_video_hint)
            setSelectAllOnFocus(true)
        }
        container.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (extension.isNotBlank()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.browser_rename_extension_preserved, ".$extension")
                textSize = 11f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
                setPadding(0, (4 * density).toInt(), 0, 0)
            })
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.browser_rename_video_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .showPremium()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val baseName = VideoFileRenamer.normalizeBaseName(input.text?.toString().orEmpty(), extension)
            if (!VideoFileRenamer.isValidBaseName(baseName)) {
                input.error = getString(R.string.browser_rename_video_invalid_name)
                return@setOnClickListener
            }
            dialog.dismiss()
            executeVideoRename(item, baseName)
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun executeVideoRename(item: MediaItem, baseName: String) {
        if (!isAdded || _binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = VideoFileRenamer.rename(requireContext(), item, baseName)) {
                VideoFileRenamer.Result.Success -> {
                    Toast.makeText(requireContext(), R.string.browser_rename_video_success, Toast.LENGTH_SHORT).show()
                    refreshCurrentBrowserLocation()
                }
                VideoFileRenamer.Result.AlreadyExists -> {
                    Toast.makeText(requireContext(), R.string.browser_rename_video_already_exists, Toast.LENGTH_LONG).show()
                }
                VideoFileRenamer.Result.ReadOnlyNetwork -> {
                    Toast.makeText(requireContext(), R.string.browser_rename_video_network_read_only, Toast.LENGTH_LONG).show()
                }
                is VideoFileRenamer.Result.PermissionRequired -> {
                    pendingVideoRename = PendingVideoRename(item, baseName)
                    runCatching {
                        renamePermissionLauncher.launch(
                            IntentSenderRequest.Builder(result.intentSender).build()
                        )
                    }.onFailure {
                        pendingVideoRename = null
                        Toast.makeText(requireContext(), R.string.browser_rename_video_error, Toast.LENGTH_LONG).show()
                    }
                }
                is VideoFileRenamer.Result.Failure -> {
                    android.util.Log.w("BrowserFragment", "Video rename failed", result.error)
                    Toast.makeText(requireContext(), R.string.browser_rename_video_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshCurrentBrowserLocation() {
        when (sourceMode) {
            SourceMode.LOCAL -> viewModel.loadLocalFiles(viewModel.currentPath.value)
            SourceMode.NETWORK -> {
                val share = viewModel.currentShare
                if (share != null) viewModel.loadNetworkFiles(share, viewModel.currentPath.value)
                else viewModel.loadNetworkShares()
            }
        }
    }

    private fun openFolderLikeItem(item: MediaItem) {
        when {
            item.mimeType == "network" -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (!fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)) {
                        openPaywall()
                        return@launch
                    }
                    sourceMode = SourceMode.NETWORK
                    breadcrumbParts.clear()
                    breadcrumbParts += BrowserCrumb(item.name, "", item.id)
                    updateSourceTabs()
                    updateBreadcrumb()
                    viewModel.loadNetworkFilesById(item.id, "")
                }
            }
            sourceMode == SourceMode.NETWORK || item.isNetwork -> {
                val shareId = item.networkShareId ?: viewModel.currentShare?.id
                breadcrumbParts += BrowserCrumb(item.name, item.path, shareId)
                updateBreadcrumb()
                val share = viewModel.currentShare
                if (share != null) viewModel.loadNetworkFiles(share, item.path)
                else if (!shareId.isNullOrEmpty()) viewModel.loadNetworkFilesById(shareId, item.path)
            }
            else -> {
                breadcrumbParts += BrowserCrumb(item.name, item.path)
                updateBreadcrumb()
                viewModel.loadLocalFiles(item.path)
            }
        }
    }

    private fun setupButtons() {
        binding.btnHome?.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }
        binding.btnLocal.setOnClickListener { switchToLocalRoot() }
        binding.btnNetwork.setOnClickListener { switchToNetworkRoot() }
        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnSort.setOnClickListener {
            viewModel.cycleSortMode()
            binding.tvSortLabel.text = when (viewModel.sortMode.value) {
                BrowserViewModel.SortMode.NAME_ASC -> getString(R.string.sort_name_az)
                BrowserViewModel.SortMode.NAME_DESC -> getString(R.string.sort_name_za)
                BrowserViewModel.SortMode.DATE_DESC -> getString(R.string.sort_date_recent)
                BrowserViewModel.SortMode.SIZE_DESC -> getString(R.string.sort_size)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showAudio.collect { active ->
                val settingEnabled = viewModel.isShowAudioFromSettings()
                if (settingEnabled) {
                    binding.btnToggleAudio.alpha = 0.3f
                    binding.btnToggleAudio.isEnabled = false
                    binding.btnToggleAudio.setColorFilter(fr.retrospare.blazeplayer.theme.AccentColorManager.accent(requireContext()))
                } else {
                    binding.btnToggleAudio.alpha = 1f
                    binding.btnToggleAudio.isEnabled = true
                    binding.btnToggleAudio.setColorFilter(
                        if (active) fr.retrospare.blazeplayer.theme.AccentColorManager.accent(requireContext())
                        else resources.getColor(R.color.on_surface_variant, null)
                    )
                }
            }
        }
        binding.btnToggleAudio.setOnClickListener {
            if (!viewModel.isShowAudioFromSettings()) viewModel.toggleShowAudio()
        }
        binding.btnSearch.setOnClickListener {
            val searchView = binding.root.findViewById<android.widget.EditText>(R.id.etSearch)
            val searchVisible = searchView?.visibility == View.VISIBLE
            if (searchVisible) {
                searchView.visibility = View.GONE
                searchView.text?.clear()
                adapter.filter("")
            } else {
                val et = binding.root.findViewById<android.widget.EditText>(R.id.etSearch)
                et.visibility = View.VISIBLE
                et.requestFocus()
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        binding.root.findViewById<android.widget.EditText>(R.id.etSearch)?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performBrowserSearch(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun restoreVisibleBrowserItems(filterQuery: String = "") {
        val current = (viewModel.state.value as? BrowserViewModel.BrowserState.Success)?.items
        if (current != null) adapter.setFullList(current)
        adapter.filter(filterQuery)
    }

    private fun performBrowserSearch(rawQuery: String) {
        val query = rawQuery.trim()
        globalSearchJob?.cancel()
        val generation = ++searchGeneration
        setSearchProgressVisible(false)
        if (query.isBlank()) {
            restoreVisibleBrowserItems("")
            return
        }

        if (query.length < 2) {
            restoreVisibleBrowserItems(query)
            return
        }

        globalSearchJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(250)
            if (generation == searchGeneration) setSearchProgressVisible(true)
            try {
                val results = when {
                    sourceMode == SourceMode.LOCAL -> viewModel.searchLocalVideos(query)
                    sourceMode == SourceMode.NETWORK -> viewModel.searchNetworkVideos(query)
                    else -> null
                }
                if (generation != searchGeneration) return@launch
                if (results != null) {
                    adapter.setFullList(results)
                    adapter.filter("")
                } else {
                    restoreVisibleBrowserItems(query)
                }
            } finally {
                if (generation == searchGeneration) setSearchProgressVisible(false)
            }
        }
    }

    private fun setSearchProgressVisible(visible: Boolean) {
        _binding?.tvSearchProgress?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun switchToLocalRoot() {
        sourceMode = SourceMode.LOCAL
        breadcrumbParts.clear()
        updateSourceTabs()
        updateBreadcrumb()
        viewModel.currentShare = null
        viewModel.loadLocalFiles("")
    }

    private fun switchToNetworkRoot() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)) {
                openPaywall()
                return@launch
            }
            sourceMode = SourceMode.NETWORK
            breadcrumbParts.clear()
            updateSourceTabs()
            updateBreadcrumb()
            viewModel.loadNetworkShares()
        }
    }

    private fun openPaywall() {
        val nav = findNavController()
        if (nav.currentDestination?.id != R.id.paywallFragment) {
            runCatching { nav.navigate(R.id.paywallFragment) }
        }
    }

    private fun navigateBack() {
        if (breadcrumbParts.isNotEmpty()) {
            breadcrumbParts.removeAt(breadcrumbParts.lastIndex)
            updateBreadcrumb()
            when (sourceMode) {
                SourceMode.LOCAL -> viewModel.loadLocalFiles(breadcrumbParts.lastOrNull()?.path.orEmpty())
                SourceMode.NETWORK -> {
                    val target = breadcrumbParts.lastOrNull()
                    if (target == null) {
                        viewModel.currentShare = null
                        viewModel.loadNetworkShares()
                    } else {
                        val shareId = target.shareId ?: viewModel.currentShare?.id
                        if (!shareId.isNullOrEmpty()) viewModel.loadNetworkFilesById(shareId, target.path)
                        else viewModel.loadNetworkShares()
                    }
                }
            }
        } else {
            findNavController().popBackStack()
        }
    }

    private fun updateSourceTabs() {
        fun style(button: MaterialButton, selected: Boolean) {
            button.setTextColor(ContextCompat.getColor(requireContext(), if (selected) R.color.black else R.color.on_surface))
            button.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), if (selected) R.color.black else R.color.on_surface))
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (selected) fr.retrospare.blazeplayer.theme.AccentColorManager.accent(requireContext())
                else ContextCompat.getColor(requireContext(), R.color.surface_variant)
            )
            button.strokeColor = android.content.res.ColorStateList.valueOf(fr.retrospare.blazeplayer.theme.AccentColorManager.accentStroke(requireContext()))
            button.strokeWidth = if (selected) 0 else (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
        style(binding.btnLocal, sourceMode == SourceMode.LOCAL)
        style(binding.btnNetwork, sourceMode == SourceMode.NETWORK)
        binding.tvTitle.text = when {
            audioOnlyMode -> getString(R.string.browser_files_audio)
            sourceMode == SourceMode.NETWORK -> getString(R.string.tab_network)
            else -> getString(R.string.tab_blaze_video)
        }
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
                                val folders = state.items.count { it.mimeType == "folder" || it.mimeType == "share" || it.mimeType == "network" }
                                val files = state.items.count { it.mimeType != "folder" && it.mimeType != "share" && it.mimeType != "network" }
                                val folderText = resources.getQuantityString(R.plurals.folder_count, folders, folders)
                                val fileText = resources.getQuantityString(R.plurals.file_count, files, files)
                                binding.tvFileCount.text = getString(R.string.browser_folder_file_count, folderText, fileText)
                            }
                            is BrowserViewModel.BrowserState.Error -> {
                                binding.recyclerView.visibility = View.VISIBLE
                                fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_error), getString(R.string.toast_error_generic, state.resId?.let { getString(it) } ?: state.message))
                            }
                        }
                    }
                }
                launch {
                    viewModel.currentPath.collect { path ->
                        binding.tvPath.text = when {
                            sourceMode == SourceMode.NETWORK && breadcrumbParts.isEmpty() -> getString(R.string.tab_network)
                            path.isEmpty() -> getString(R.string.path_internal_storage)
                            else -> path
                        }
                        binding.tvTitle.text = when {
                            breadcrumbParts.isNotEmpty() -> breadcrumbParts.last().name
                            audioOnlyMode -> getString(R.string.browser_files_audio)
                            sourceMode == SourceMode.NETWORK -> getString(R.string.tab_network)
                            else -> getString(R.string.tab_blaze_video)
                        }
                    }
                }
            }
        }
    }

    private fun updateBreadcrumb() {
        binding.breadcrumbContainer.removeAllViews()
        val rootLabel = if (sourceMode == SourceMode.NETWORK) getString(R.string.tab_network) else getString(R.string.breadcrumb_home)
        val allParts = listOf(BrowserCrumb(rootLabel, "", null)) + breadcrumbParts
        allParts.forEachIndexed { index, part ->
            val tv = TextView(requireContext()).apply {
                text = part.name
                textSize = 12f
                val isLast = index == allParts.lastIndex
                setTextColor(
                    if (isLast) fr.retrospare.blazeplayer.theme.AccentColorManager.accent(requireContext())
                    else resources.getColor(R.color.on_surface_variant, null)
                )
                setPadding(8, 6, 8, 6)
                setOnClickListener {
                    if (!isLast) {
                        val keep = index.coerceAtLeast(0)
                        while (breadcrumbParts.size > keep) breadcrumbParts.removeAt(breadcrumbParts.lastIndex)
                        updateBreadcrumb()
                        when (sourceMode) {
                            SourceMode.LOCAL -> viewModel.loadLocalFiles(breadcrumbParts.lastOrNull()?.path.orEmpty())
                            SourceMode.NETWORK -> {
                                val target = breadcrumbParts.lastOrNull()
                                if (target == null) {
                                    viewModel.currentShare = null
                                    viewModel.loadNetworkShares()
                                } else {
                                    val shareId = target.shareId ?: viewModel.currentShare?.id
                                    if (!shareId.isNullOrEmpty()) viewModel.loadNetworkFilesById(shareId, target.path)
                                    else viewModel.loadNetworkShares()
                                }
                            }
                        }
                    }
                }
            }
            binding.breadcrumbContainer.addView(tv)
            if (index < allParts.lastIndex) {
                binding.breadcrumbContainer.addView(TextView(requireContext()).apply {
                    text = "›"
                    textSize = 12f
                    setTextColor(resources.getColor(R.color.on_surface_variant, null))
                    setPadding(4, 6, 4, 6)
                })
            }
        }
    }

    override fun onDestroyView() {
        globalSearchJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
