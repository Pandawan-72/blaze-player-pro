package fr.retrospare.blazeplayer.home

import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
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
            com.google.android.gms.cast.framework.CastButtonFactory
                .setUpMediaRouteButton(requireContext(), binding.btnCast)
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
                    currentTabIndex = 3
                    updateTabStyles(3)
                    showAudioTab()
                }
            }
        }
    }

    private fun setupTabs() {
        listOf(binding.tabLocal, binding.tabNetwork, binding.tabAudio).forEachIndexed { i, tab ->
            val index = i + 1
            tab.setOnClickListener {
                currentTabIndex = index
                updateTabStyles(index)
                if (index == 3) {
                    showAudioTab()
                } else {
                    hideAudioTab()
                    viewModel.onTabSelected(index)
                    updateSectionTitles(index)
                }
            }
        }
        val activeTab = if (viewModel.currentTabIndex.value == 0) 1 else viewModel.currentTabIndex.value
        updateTabStyles(activeTab)
        updateSectionTitles(activeTab)
        if (activeTab == 3) showAudioTab()
        else { hideAudioTab(); viewModel.onTabSelected(activeTab) }
    }

    private fun showAudioTab() {
        (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(true)
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

    fun switchToAudioTab() {
        val tabs = listOf(binding.tabAll as? android.widget.TextView, binding.tabLocal, binding.tabNetwork, binding.tabAudio)
        currentTabIndex = 3
        updateTabStyles(3)
        showAudioTab()
    }

    fun switchToTab(index: Int) {
        currentTabIndex = index
        viewModel.onTabSelected(index)
        updateTabStyles(index)
        if (index == 3) {
            showAudioTab()
        } else {
            hideAudioTab()
            updateSectionTitles(index)
        }
    }

    fun returnToHome() {
        currentTabIndex = 1
        updateTabStyles(1)
        hideAudioTab()
        viewModel.onTabSelected(1)
        updateSectionTitles(1)
    }

    private fun hideAudioTab() {
        (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.setInAudioPlayer(false)
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
        updateTabStyles(3)
        showAudioTab()
        audioPlayerFragment?.addTrack(path, name) ?: Unit
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
            1 -> {
                binding.sectionLocal.visibility = View.VISIBLE
                binding.sectionNetwork.visibility = View.GONE
                viewModel.onTabSelected(1)
            }
            2 -> {
                binding.sectionNetwork.visibility = View.VISIBLE
                binding.sectionLocal.visibility = View.GONE
                viewModel.onTabSelected(2)
            }
            3 -> {
                binding.sectionLocal.visibility = View.GONE
                binding.sectionNetwork.visibility = View.GONE
            }
            else -> {
                binding.sectionLocal.visibility = View.VISIBLE
                binding.sectionNetwork.visibility = View.GONE
                viewModel.onTabSelected(1)
            }
        }
    }

    private fun updateTabStyles(selectedIndex: Int) {
        // selectedIndex: 1=Local, 2=Réseau, 3=Audio
        val tabViews = listOf(binding.tabLocal, binding.tabNetwork, binding.tabAudio)
        val tabIcons = listOf(binding.tabLocalIcon, binding.tabNetworkIcon, binding.tabAudioIcon)
        val tabTexts = listOf(binding.tabLocalText, binding.tabNetworkText, binding.tabAudioText)

        tabViews.forEachIndexed { i, tab ->
            val isActive = (i + 1) == selectedIndex
            tab.background = ContextCompat.getDrawable(requireContext(),
                if (isActive) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
            tabTexts[i].setTextColor(ContextCompat.getColor(requireContext(),
                if (isActive) R.color.green_accent else R.color.on_surface_variant))
            tabIcons[i].setColorFilter(ContextCompat.getColor(requireContext(),
                if (isActive) R.color.green_accent else R.color.on_surface_variant))
        }
    }

    private fun setupButtons() {
        binding.btnBrowseNetwork.setOnClickListener {
            audioPlayerFragment?.savePlaylistFromController() ?: Unit
            findNavController().navigate(R.id.action_home_to_network)
        }
        binding.btnBrowseLocal.setOnClickListener {
            audioPlayerFragment?.savePlaylistFromController() ?: Unit
            findNavController().navigate(R.id.action_home_to_browser)
        }
    }


    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.recentNetworkItems.collect { updateRecycler(binding.listNetwork, it) } }
                launch { viewModel.recentLocalItems.collect { updateRecycler(binding.listLocal, it) } }
            }
        }
    }

    private fun updateRecycler(recycler: androidx.recyclerview.widget.RecyclerView, items: List<MediaItem>) {
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_media_file, parent, false)
                ) {}
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val item = items[position]
                val v = holder.itemView

                // Nom du fichier
                v.findViewById<TextView>(R.id.tvFileName).text = item.name

                // État initial vide - sera rempli par VideoMetadataExtractor
                val tvDur = v.findViewById<TextView>(R.id.tvDuration)
                val tvRes = v.findViewById<TextView>(R.id.tvResolution)
                val tvVid = v.findViewById<TextView>(R.id.tvVideoCodec)
                val tvAud = v.findViewById<TextView>(R.id.tvAudioCodec)
                val tvFmt = v.findViewById<TextView>(R.id.tvFormat)
                val ivThumb = v.findViewById<ImageView>(R.id.ivThumbnail)

                tvDur.text = ""
                tvRes.visibility = View.GONE
                tvVid.visibility = View.GONE
                tvAud.visibility = View.GONE
                // Badge conteneur immédiat depuis item.extension
                val ext = item.extension.ifEmpty { item.name.substringAfterLast(".", "") }.uppercase()
                if (ext.isNotEmpty()) {
                    tvFmt.text = ext
                    tvFmt.visibility = View.VISIBLE
                    tvFmt.setBackgroundResource(fr.retrospare.blazeplayer.R.drawable.bg_badge_orange)
                    tvFmt.setTextColor(requireContext().getColor(fr.retrospare.blazeplayer.R.color.orange_accent))
                } else {
                    tvFmt.visibility = View.GONE
                }

                // Thumbnail (local ou réseau SMB)
                if (item.path.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        ThumbnailUtils.loadThumbnail(requireContext(), item.path, ivThumb)
                    }
                }

                // Click
                v.setOnClickListener { PlayerRouter.open(requireContext(), item.path, item.name) }

                // VideoMetadataExtractor - source unique de vérité
                if (item.path.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val info = fr.retrospare.blazeplayer.player.VideoMetadataExtractor.extract(requireContext(), item.path)
                        android.util.Log.d("META", "path=${item.path} w=${info.width} h=${info.height} res=${info.resolutionLabel} badge=${info.qualityBadge} dur=${info.formattedDuration}")
                        tvDur.text = if (info.resolutionLabel.isNotEmpty())
                            "${info.formattedDuration} • ${info.resolutionLabel}"
                        else
                            info.formattedDuration
                        android.util.Log.d("UI", "tvDuration=${tvDur.text}")
                        tvRes.text = info.qualityBadge
                        tvRes.visibility = if (info.qualityBadge.isNotEmpty()) View.VISIBLE else View.GONE
                        tvVid.text = info.videoCodec
                        tvVid.visibility = if (info.videoCodec.isNotEmpty()) View.VISIBLE else View.GONE
                        tvAud.text = info.audioCodec
                        tvAud.visibility = if (info.audioCodec.isNotEmpty()) View.VISIBLE else View.GONE
                        // tvFmt géré depuis item.extension - ne pas écraser
                    }
                }

                // Bouton 3 points
                val btnMore = v.findViewById<android.view.View>(R.id.btnMore)
                btnMore?.setOnClickListener { anchor ->
                    val popup = android.widget.PopupMenu(requireContext(), anchor)
                    popup.menu.add(0, 1, 0, "Lire")
                    popup.menu.add(0, 2, 1, "Informations")
                    popup.menu.add(0, 3, 2, "Retirer de l'historique")
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> { PlayerRouter.open(requireContext(), item.path, item.name); true }
                            2 -> {
                                android.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Informations")
                                    .setMessage("Fichier : ${item.name}\nChemin : ${item.path}")
                                    .setPositiveButton("OK", null)
                                    .show()
                                true
                            }
                            3 -> { viewModel.removeFromHistory(item); true }
                            else -> false
                        }
                    }
                    popup.show()
                }
            }
        }
    }

}
