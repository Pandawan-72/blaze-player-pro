package fr.retrospare.blazeplayer.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.databinding.FragmentHomeBinding
import fr.retrospare.blazeplayer.data.model.MediaItem
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

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
        val tabs = listOf("Tous", "Réseau", "Local", "Récents")
        tabs.forEach { title ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(title))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                viewModel.onTabSelected(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupButtons() {
        binding.btnBrowseNetwork.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_browser)
        }
        binding.btnBrowseLocal.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_browser)
        }
        binding.heroCard.setOnClickListener {
            viewModel.lastPlayedItem.value?.let { item ->
                openPlayer(item)
            }
        }
        binding.btnCast.setOnClickListener {
            // TODO: Chromecast picker
        }
        binding.btnSearch.setOnClickListener {
            // TODO: Search
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_language -> {
                    // TODO: Bottom sheet langue
                    true
                }
                R.id.nav_subtitles -> {
                    // TODO: Bottom sheet sous-titres
                    true
                }
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
                    viewModel.lastPlayedItem.collect { item ->
                        updateHeroCard(item)
                    }
                }

                launch {
                    viewModel.recentNetworkItems.collect { items ->
                        updateNetworkList(items)
                    }
                }

                launch {
                    viewModel.recentLocalItems.collect { items ->
                        updateLocalList(items)
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
            binding.progressFill.layoutParams.width = 0
            return
        }
        binding.tvHeroTitle.text = item.name
        binding.tvHeroDuration.text = item.formattedDuration
        binding.tvHeroResolution.text = item.resolution ?: ""
        binding.tvHeroBadge.text = if (item.isNetwork) "SMB" else "LOCAL"

        // Barre de progression
        if (item.duration > 0 && item.lastPosition > 0) {
            val progress = (item.lastPosition.toFloat() / item.duration * binding.progressBg.width).toInt()
            binding.progressFill.layoutParams.width = progress
            binding.progressFill.requestLayout()
        }
    }

    private fun updateNetworkList(items: List<MediaItem>) {
        binding.listNetwork.removeAllViews()
        items.take(2).forEach { item ->
            val itemView = layoutInflater.inflate(
                R.layout.item_media_file, binding.listNetwork, false
            )
            bindMediaItem(itemView, item)
            binding.listNetwork.addView(itemView)
        }
    }

    private fun updateLocalList(items: List<MediaItem>) {
        binding.listLocal.removeAllViews()
        items.take(2).forEach { item ->
            val itemView = layoutInflater.inflate(
                R.layout.item_media_file, binding.listLocal, false
            )
            bindMediaItem(itemView, item)
            binding.listLocal.addView(itemView)
        }
    }

    private fun bindMediaItem(view: View, item: MediaItem) {
        val tvName = view.findViewById<android.widget.TextView>(R.id.tvFileName)
        val tvResolution = view.findViewById<android.widget.TextView>(R.id.tvResolution)
        val tvFormat = view.findViewById<android.widget.TextView>(R.id.tvFormat)
        val tvDuration = view.findViewById<android.widget.TextView>(R.id.tvDuration)
        val btnMore = view.findViewById<android.widget.ImageButton>(R.id.btnMore)

        tvName.text = item.name
        tvResolution.text = item.resolution ?: ""
        tvResolution.visibility = if (item.resolution != null) View.VISIBLE else View.GONE
        tvFormat.text = item.extension.uppercase()
        tvDuration.text = item.formattedDuration

        view.setOnClickListener { openPlayer(item) }
        btnMore.setOnClickListener {
            // TODO: Menu contextuel
        }
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
