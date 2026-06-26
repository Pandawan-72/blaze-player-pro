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
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupButtons()
        setupBottomNav()
        observeViewModel()
    }

    private fun setupTabs() {
        val tabs = listOf(binding.tabAll, binding.tabNetwork, binding.tabLocal)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                updateTabStyles(tabs, index)
                viewModel.onTabSelected(index)
            }
        }
        updateTabStyles(tabs, 0)
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
        binding.btnBrowseNetwork.setOnClickListener { findNavController().navigate(R.id.action_home_to_network) }
        binding.btnBrowseLocal.setOnClickListener { findNavController().navigate(R.id.action_home_to_browser) }
        binding.heroCard.setOnClickListener { viewModel.lastPlayedItem.value?.let { PlayerRouter.open(requireContext(), it.path, it.name) } }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_language -> true
                R.id.nav_subtitles -> true
                R.id.nav_settings -> { findNavController().navigate(R.id.action_home_to_settings); true }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_home
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
            container.addView(v)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
