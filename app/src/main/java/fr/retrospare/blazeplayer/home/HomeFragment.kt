package fr.retrospare.blazeplayer.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var selectedTab = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        val tabs = listOf(binding.tabAll, binding.tabNetwork, binding.tabLocal, binding.tabRecent)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                selectedTab = index
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
                tab.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tab.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_tab_inactive)
                tab.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
                tab.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun setupButtons() {
        binding.btnBrowseNetwork.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_browser)
        }
        binding.btnBrowseLocal.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_browser)
        }
        binding.heroCard.setOnClickListener {
            viewModel.lastPlayedItem.value?.let { item -> openPlayer(item) }
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_language -> true
                R.id.nav_subtitles -> true
                R.id.nav_settings -> {
                    findNavController().navigate(R.id.action_home_to_settings)
                    true
                }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.lastPlayedItem.collect { item -> updateHeroCard(item) }
                }
                launch {
                    viewModel.recentNetworkItems.collect { items -> updateNetworkList(items) }
                }
                launch {
                    viewModel.recentLocalItems.collect { items -> updateLocalList(items) }
                }
            }
        }
    }

    private fun updateHeroCard(item: MediaItem?) {
        if (item == null) {
            binding.tvHeroTitle.text = "Aucune lecture récente"
            binding.tvHeroDuration.text = ""
            binding.tvHeroResolution.text = ""
            return
        }
        binding.tvHeroTitle.text = item.name
        binding.tvHeroDuration.text = item.formattedDuration
        binding.tvHeroResolution.text = item.resolution ?: ""
        binding.tvHeroBadge.text = if (item.isNetwork) "SMB" else "LOCAL"
    }

    private fun updateNetworkList(items: List<MediaItem>) {
        binding.listNetwork.removeAllViews()
        items.take(2).forEach { item ->
            val v = layoutInflater.inflate(R.layout.item_media_file, binding.listNetwork, false)
            bindMediaItem(v, item)
            binding.listNetwork.addView(v)
        }
    }

    private fun updateLocalList(items: List<MediaItem>) {
        binding.listLocal.removeAllViews()
        items.take(2).forEach { item ->
            val v = layoutInflater.inflate(R.layout.item_media_file, binding.listLocal, false)
            bindMediaItem(v, item)
            binding.listLocal.addView(v)
        }
    }

    private fun bindMediaItem(view: View, item: MediaItem) {
        view.findViewById<TextView>(R.id.tvFileName).text = item.name
        view.findViewById<TextView>(R.id.tvFormat).text = item.extension.uppercase()
        view.findViewById<TextView>(R.id.tvDuration).text = item.formattedDuration
        val tvRes = view.findViewById<TextView>(R.id.tvResolution)
        tvRes.text = item.resolution ?: ""
        tvRes.visibility = if (item.resolution != null) View.VISIBLE else View.GONE
        view.setOnClickListener { openPlayer(item) }
    }

    private fun openPlayer(item: MediaItem) {
        val bundle = Bundle().apply {
            putString("mediaPath", item.path)
            putString("mediaName", item.name)
        }
        findNavController().navigate(R.id.action_home_to_player, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
