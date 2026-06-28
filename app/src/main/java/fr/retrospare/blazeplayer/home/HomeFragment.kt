package fr.retrospare.blazeplayer.home

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.databinding.FragmentHomeBinding
import fr.retrospare.blazeplayer.player.PlayerRouter
import fr.retrospare.blazeplayer.player.AudioPlayerFragment
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()
    private var currentTabIndex = 0
    private var audioPlayerFragment: AudioPlayerFragment? = null
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentTabIndex = viewModel.currentTabIndex.value
        try {
            binding.btnCast.setOnClickListener {
                try {
                    androidx.mediarouter.app.MediaRouteChooserDialog(requireContext()).show()
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            binding.btnCast.visibility = android.view.View.GONE
        }
        binding.btnSearch.setOnClickListener {
            try {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_search)
            } catch (e: Exception) {
                // fallback
            }
        }

        binding.btnSettings.setOnClickListener {
            findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_settings)
        }
        setupTabs()
        setupButtons()
        observeViewModel()
        // Switche vers Blaze Audio quand un fichier audio est ajouté depuis le navigateur
        val sharedAudioVm = androidx.lifecycle.ViewModelProvider(requireActivity())[fr.retrospare.blazeplayer.home.SharedAudioViewModel::class.java]
        viewLifecycleOwner.lifecycleScope.launch {
            sharedAudioVm.pendingTracks.collect { tracks ->
                if (tracks.isNotEmpty()) {
                    val tabs = listOf(binding.tabAll, binding.tabNetwork, binding.tabLocal, binding.tabAudio)
                    currentTabIndex = 3
                    updateTabStyles(tabs, 3)
                    showAudioTab()
                }
            }
        }
    }

    private fun setupTabs() {
        val tabs = listOf(binding.tabAll, binding.tabNetwork, binding.tabLocal, binding.tabAudio)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                currentTabIndex = index
                updateTabStyles(tabs, index)
                if (index == 3) {
                    showAudioTab()
                } else {
                    hideAudioTab()
                    viewModel.onTabSelected(index)
                    updateSectionTitles(index)
                }
            }
        }
        // Restaure l'onglet actif depuis le ViewModel
        val activeTab = viewModel.currentTabIndex.value
        updateTabStyles(tabs, activeTab)
        updateSectionTitles(activeTab)
        if (activeTab == 3) showAudioTab()
        else hideAudioTab()
    }

    private fun showAudioTab() {
        binding.scrollContent.visibility = android.view.View.GONE
        binding.audioContainer.visibility = android.view.View.VISIBLE
        // Récupère le fragment existant par tag
        val existing = childFragmentManager.findFragmentByTag("blaze_audio")
        if (existing == null) {
            audioPlayerFragment = fr.retrospare.blazeplayer.player.AudioPlayerFragment()
            childFragmentManager.beginTransaction()
                .add(fr.retrospare.blazeplayer.R.id.audioContainer, audioPlayerFragment!!, "blaze_audio")
                .setMaxLifecycle(audioPlayerFragment!!, androidx.lifecycle.Lifecycle.State.RESUMED)
                .commitAllowingStateLoss()
        } else {
            audioPlayerFragment = existing as? fr.retrospare.blazeplayer.player.AudioPlayerFragment
            childFragmentManager.beginTransaction()
                .setMaxLifecycle(existing, androidx.lifecycle.Lifecycle.State.RESUMED)
                .show(existing)
                .commitAllowingStateLoss()
        }
    }

    fun returnToHome() {
        val tabs = listOf(binding.tabAll, binding.tabNetwork, binding.tabLocal, binding.tabAudio)
        updateTabStyles(tabs, 0)
        hideAudioTab()
        viewModel.onTabSelected(0)
        updateSectionTitles(0)
    }

    private fun hideAudioTab() {
        binding.scrollContent.visibility = android.view.View.VISIBLE
        binding.audioContainer.visibility = android.view.View.GONE
        audioPlayerFragment?.let { frag ->
            // Sauvegarde avant de cacher
            if (!frag.isHidden) {
                childFragmentManager.beginTransaction().hide(frag).commitAllowingStateLoss()
            }
        }
    }

    fun openAudioPlayer(path: String, name: String) {
        val tabs = listOf(binding.tabAll, binding.tabNetwork, binding.tabLocal, binding.tabAudio)
        updateTabStyles(tabs, 3)
        showAudioTab()
        audioPlayerFragment?.playPath(path, name)
            ?: run {
                audioPlayerFragment = AudioPlayerFragment().apply {
                    arguments = android.os.Bundle().apply {
                        putString("mediaPath", path)
                        putString("mediaName", name)
                    }
                }
                childFragmentManager.beginTransaction()
                    .replace(fr.retrospare.blazeplayer.R.id.audioContainer, audioPlayerFragment!!)
                    .commit()
            }
    }

    private fun updateSectionTitles(tabIndex: Int) {
        when (tabIndex) {
            0 -> {
                binding.tvSectionNetwork.text = "HISTORIQUE RÉSEAU (3 DERNIERS)"
                binding.tvSectionLocal.text = "HISTORIQUE LOCAL (3 DERNIERS)"
            }
            1 -> binding.tvSectionNetwork.text = "HISTORIQUE RÉSEAU"
            2 -> binding.tvSectionLocal.text = "HISTORIQUE LOCAL"
        }
    }

    private fun updateTabStyles(tabs: List<TextView>, selectedIndex: Int) {
        tabs.forEachIndexed { index, tab ->
            if (index == selectedIndex) {
                tab.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_tab_active)
                tab.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_accent))
                tab.setTypeface(null, Typeface.BOLD)
            } else {
                tab.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_tab_inactive)
                tab.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
                tab.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun setupButtons() {
        binding.btnBrowseNetwork.setOnClickListener {
            audioPlayerFragment?.savePlaylist()
            findNavController().navigate(R.id.action_home_to_network)
        }
        binding.btnBrowseLocal.setOnClickListener {
            audioPlayerFragment?.savePlaylist()
            findNavController().navigate(R.id.action_home_to_browser)
        }
        binding.heroCard.setOnClickListener { viewModel.lastPlayedItem.value?.let { PlayerRouter.open(requireContext(), it.path, it.name) } }
    }


    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.lastPlayedItem.collect { updateHeroCard(it) } }
                launch { viewModel.recentNetworkItems.collect { updateList(binding.listNetwork, it) } }
                launch { viewModel.recentLocalItems.collect { updateList(binding.listLocal, it) } }
                launch {
                    viewModel.showNetwork.collect { show ->
                        binding.sectionNetwork.visibility = if (show) View.VISIBLE else View.GONE
                        binding.divider.visibility = if (show && viewModel.showLocal.value) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.showLocal.collect { show ->
                        binding.sectionLocal.visibility = if (show) View.VISIBLE else View.GONE
                        binding.divider.visibility = if (viewModel.showNetwork.value && show) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun updateHeroCard(item: MediaItem?) {
        if (item == null) {
            binding.tvHeroTitle.text = "Aucune lecture récente"
            binding.tvHeroDuration.text = ""
            binding.tvHeroResolution.text = ""
            binding.tvHeroVideoCodec.visibility = View.GONE
            binding.tvHeroAudioCodec.visibility = View.GONE
            binding.tvHeroFormat.visibility = View.GONE
            return
        }
        binding.tvHeroTitle.text = item.name
        binding.tvHeroDuration.text = item.formattedDuration
        binding.tvHeroBadge.text = if (item.isNetwork) "SMB" else "LOCAL"

        val ext = item.extension.ifEmpty { item.name.substringAfterLast(".", "").lowercase() }

        binding.tvHeroResolution.text = item.resolution ?: ""
        binding.tvHeroResolution.visibility = if (!item.resolution.isNullOrEmpty()) View.VISIBLE else View.GONE

        binding.tvHeroFormat.text = ext.uppercase()
        binding.tvHeroFormat.visibility = if (ext.isNotEmpty()) View.VISIBLE else View.GONE

        binding.tvHeroVideoCodec.text = item.videoCodec ?: ""
        binding.tvHeroVideoCodec.visibility = if (!item.videoCodec.isNullOrEmpty()) View.VISIBLE else View.GONE

        binding.tvHeroAudioCodec.text = item.audioCodec ?: ""
        binding.tvHeroAudioCodec.visibility = if (!item.audioCodec.isNullOrEmpty()) View.VISIBLE else View.GONE

        if (!item.isNetwork && item.path.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                ThumbnailUtils.loadThumbnail(requireContext(), item.path, binding.ivHeroThumb)
            }
        }
    }

    private fun updateList(container: LinearLayout, items: List<MediaItem>) {
        container.removeAllViews()
        items.forEach { item ->
            val v = layoutInflater.inflate(R.layout.item_media_file, container, false)
            val ext = item.extension.ifEmpty { item.name.substringAfterLast(".", "").lowercase() }

            v.findViewById<TextView>(R.id.tvFileName).text = item.name
            v.findViewById<TextView>(R.id.tvDuration).text = item.formattedDuration

            val tvFormat = v.findViewById<TextView>(R.id.tvFormat)
            val tvRes = v.findViewById<TextView>(R.id.tvResolution)
            val tvVideo = v.findViewById<TextView>(R.id.tvVideoCodec)
            val tvAudio = v.findViewById<TextView>(R.id.tvAudioCodec)
            val ivThumb = v.findViewById<ImageView>(R.id.ivThumbnail)

            tvFormat.text = ext.uppercase()
            tvVideo.text = item.videoCodec ?: ""
            tvVideo.visibility = if (!item.videoCodec.isNullOrEmpty()) View.VISIBLE else View.GONE
            tvAudio.text = item.audioCodec ?: ""
            tvAudio.visibility = if (!item.audioCodec.isNullOrEmpty()) View.VISIBLE else View.GONE
            tvRes.text = item.resolution ?: ""
            tvRes.visibility = if (!item.resolution.isNullOrEmpty()) View.VISIBLE else View.GONE

            if (!item.isNetwork && item.path.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    ThumbnailUtils.loadThumbnail(requireContext(), item.path, ivThumb)
                }
            }

            v.setOnClickListener { PlayerRouter.open(requireContext(), item.path, item.name) }

            val btnMore = v.findViewById<android.view.View>(fr.retrospare.blazeplayer.R.id.btnMore)
            btnMore.setOnClickListener { anchor ->
                val popup = android.widget.PopupMenu(requireContext(), anchor)
                popup.menu.add(0, 1, 0, "Lire")
                popup.menu.add(0, 2, 1, "Informations")
                popup.menu.add(0, 3, 2, "Retirer de l'historique")
                popup.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> { PlayerRouter.open(requireContext(), item.path, item.name); true }
                        2 -> {
                            // Lit les infos depuis MediaStore
                            viewLifecycleOwner.lifecycleScope.launch {
                                val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val uri = android.net.Uri.parse(item.path)
                                        val proj = arrayOf(
                                            android.provider.MediaStore.Video.Media.SIZE,
                                            android.provider.MediaStore.Video.Media.DURATION,
                                            android.provider.MediaStore.Video.Media.WIDTH,
                                            android.provider.MediaStore.Video.Media.HEIGHT,
                                            android.provider.MediaStore.Video.Media.MIME_TYPE
                                        )
                                        requireContext().contentResolver.query(uri, proj, null, null, null)?.use { c ->
                                            if (c.moveToFirst()) {
                                                val size = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE))
                                                val dur = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)) / 1000
                                                val w = c.getInt(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.WIDTH))
                                                val h = c.getInt(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.HEIGHT))
                                                val mime = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.MIME_TYPE)) ?: ""
                                                Triple(size, dur, "$w×$h")
                                            } else null
                                        }
                                    } catch (e: Exception) { null }
                                }
                                val sz = android.text.format.Formatter.formatShortFileSize(requireContext(), info?.first ?: item.size)
                                val dur = info?.second ?: item.duration
                                val ds = if (dur > 0) "%d:%02d:%02d".format(dur / 3600, (dur % 3600) / 60, dur % 60) else "N/A"
                                val res = info?.third ?: ""
                                val ext = item.extension.uppercase()
                                val videoCodec = when (item.extension.lowercase()) {
                                    "mkv" -> "H.265"; "mp4", "m4v" -> "H.264"
                                    "avi" -> "DIVX"; "webm" -> "VP9"; else -> ext
                                }
                                android.app.AlertDialog.Builder(requireContext())
                                    .setTitle(item.name)
                                    .setMessage(
                                        "Taille : $sz\nDuree : $ds\nResolution : $res\nConteneur : $ext\nCodec video : $videoCodec"
                                    )
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                            true
                        }
                        3 -> { viewModel.removeFromHistory(item); true }
                        else -> false
                    }
                }
                popup.show()
            }
            container.addView(v)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab_index", currentTabIndex)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
