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
        // Applique uniquement l'inset du haut (status bar) : le bas est déjà géré une seule fois
        // par MainActivity pour toute la navigation, pour éviter un double padding sous la barre
        // de menu (qui la poussait trop haut et faisait chevaucher le mini player).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBar = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, statusBar.top, 0, 0)
            insets
        }
        currentTabIndex = viewModel.currentTabIndex.value
        // Différé : setUpMediaRouteButton() déclenche en interne CastContext.getSharedInstance(),
        // un appel synchrone connu pour provoquer des ANR sur le thread principal (même cause que
        // le correctif appliqué dans PlayerActivity). On évite qu'il bloque la mise en place
        // initiale de la vue, qui se répète à chaque recréation de HomeFragment.
        view.post {
            if (!isAdded) return@post
            try {
                com.google.android.gms.cast.framework.CastButtonFactory
                    .setUpMediaRouteButton(requireContext(), binding.btnCast)
            } catch (e: Exception) {
                binding.btnCast.visibility = android.view.View.GONE
            }
        }
        binding.btnSearch.setOnClickListener {
            try {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_search)
            } catch (e: Exception) {
                // fallback
            }
        }

        // "Caster l'écran du téléphone" : ouvre directement les paramètres système Android de
        // diffusion d'écran, plutôt qu'un pipeline maison (capture MediaProjection + encodage
        // vidéo temps réel + serveur de streaming live) — l'API officielle de Google pour ça
        // (CastRemoteDisplay) est abandonnée depuis plusieurs années, et reconstruire l'équivalent
        // soi-même serait un chantier bien plus lourd que tout le reste de l'app, avec un risque
        // élevé de ne pas fonctionner de façon fiable. Android sait déjà le faire nativement.
        binding.btnScreenCast.setOnClickListener {
            val activity = requireActivity()
            val candidates = listOf(
                "android.settings.CAST_SETTINGS",
                "android.settings.WIFI_DISPLAY_SETTINGS",
                android.provider.Settings.ACTION_SETTINGS
            )
            var opened = false
            for (action in candidates) {
                try {
                    activity.startActivity(android.content.Intent(action))
                    opened = true
                    break
                } catch (e: Exception) {
                    // essaie l'option suivante
                }
            }
            if (!opened) {
                android.widget.Toast.makeText(activity, "Impossible d'ouvrir les paramètres de diffusion d'écran", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSettings.setOnClickListener {
            findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_settings)
        }
        setupTabs()
        setupButtons()
        setupYoutubeTab()
        observeViewModel()
        // Force la réapparition du mini player si nécessaire : recréer cette vue (retour de
        // Réglages, d'une vidéo locale...) ne déclenche pas onResume() de l'Activity, donc rien
        // d'autre ne le refaisait apparaître automatiquement dans ces cas-là.
        (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.refreshMiniPlayer()
        // Switche vers Blaze Audio quand un fichier audio est ajouté depuis le navigateur
        val sharedAudioVm = androidx.lifecycle.ViewModelProvider(requireActivity())[fr.retrospare.blazeplayer.home.SharedAudioViewModel::class.java]
        viewLifecycleOwner.lifecycleScope.launch {
            sharedAudioVm.pendingTracks.collect { tracks ->
                if (tracks.isNotEmpty()) {
                    currentTabIndex = 4
                    updateTabStyles(4)
                    showAudioTab()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Retour systématique sur l'historique par défaut au retour du lecteur YouTube (ou de
        // tout autre écran), quelle que soit la façon dont la vidéo a été ouverte (recherche,
        // favoris, historique) — l'historique vient d'être mis à jour par YouTubePlayerActivity
        // à l'ouverture de la vidéo qu'on vient de quitter.
        if (currentTabIndex == 3) {
            showYoutubeDefaultContent()
        }
    }

    private fun setupTabs() {
        listOf(binding.tabLocal, binding.tabNetwork, binding.tabYoutube, binding.tabAudio).forEachIndexed { i, tab ->
            val index = i + 1
            tab.setOnClickListener {
                currentTabIndex = index
                updateTabStyles(index)
                if (index == 4) {
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
        if (activeTab == 4) showAudioTab()
        else { hideAudioTab(); viewModel.onTabSelected(activeTab) }
    }

    /** Configure la recherche et les listes de l'onglet Blaze Tube. Recherche déclenchée par la
     *  touche "Rechercher" du clavier (pas de recherche à chaque frappe, pour ménager le quota
     *  gratuit de l'API — ~100 unités par recherche, 10 000/jour). */
    /** Mode d'affichage courant de la liste réutilisée (listYoutubeSearch) : recherche ou
     *  favoris. Sert à savoir quoi rafraîchir après un ajout/retrait de favori, et ce que le
     *  bouton "fermer" doit faire. Null quand le contenu par défaut (historique) est affiché. */
    private var youtubeListMode: String? = null

    private fun setupYoutubeTab() {
        binding.editYoutubeSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = v.text.toString().trim()
                if (query.isEmpty()) {
                    showYoutubeDefaultContent()
                } else {
                    performYoutubeSearch(query)
                }
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
        binding.btnCloseYoutubeSearch.setOnClickListener {
            binding.editYoutubeSearch.setText("")
            showYoutubeDefaultContent()
        }
        binding.btnYoutubeFavorites.setOnClickListener { showYoutubeFavorites() }
        binding.btnCloseYoutubeFavorites.setOnClickListener { showYoutubeDefaultContent() }
        setupYoutubePlaylistButtons()
    }

    private fun showYoutubeDefaultContent() {
        youtubeListMode = null
        binding.listYoutubeSearch.visibility = View.GONE
        binding.youtubeDefaultContent.visibility = View.VISIBLE
        binding.tvYoutubeError.visibility = View.GONE
        binding.btnCloseYoutubeSearch.visibility = View.GONE
        binding.youtubeSearchBarRow.visibility = View.VISIBLE
        binding.youtubeFavoritesHeaderRow.visibility = View.GONE
        refreshYoutubeHistory()
    }

    private fun performYoutubeSearch(query: String) {
        youtubeListMode = "search"
        binding.youtubeDefaultContent.visibility = View.GONE
        binding.listYoutubeSearch.visibility = View.VISIBLE
        binding.tvYoutubeError.visibility = View.GONE
        binding.btnCloseYoutubeSearch.visibility = View.VISIBLE
        binding.youtubeSearchBarRow.visibility = View.VISIBLE
        binding.youtubeFavoritesHeaderRow.visibility = View.GONE
        binding.listYoutubeSearch.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.listYoutubeSearch.adapter = fr.retrospare.blazeplayer.youtube.YouTubeVideoAdapter(
            requireContext(), emptyList(), compact = false,
            onClick = { openYoutubeVideo(it) },
            onFavoriteToggle = { item, holder -> toggleYoutubeFavorite(item) },
            onMoreClick = { item, anchor -> showYoutubeItemMenu(item, anchor) }
        )
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = fr.retrospare.blazeplayer.youtube.YouTubeSearchApi.search(requireContext(), query)) {
                is fr.retrospare.blazeplayer.youtube.YouTubeSearchApi.Result.Success -> {
                    (binding.listYoutubeSearch.adapter as? fr.retrospare.blazeplayer.youtube.YouTubeVideoAdapter)
                        ?.updateItems(result.items)
                    if (result.items.isEmpty()) {
                        binding.tvYoutubeError.text = "Aucun résultat pour \"$query\""
                        binding.tvYoutubeError.visibility = View.VISIBLE
                    }
                }
                is fr.retrospare.blazeplayer.youtube.YouTubeSearchApi.Result.Error -> {
                    binding.tvYoutubeError.text = result.message
                    binding.tvYoutubeError.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Affiche les favoris dans la même liste que la recherche — un bouton dédié positionné
     *  comme "Fichiers réseau/local" dans les autres onglets, plutôt qu'une bande de miniatures
     *  toujours visible qui prenait trop de place. Écran dédié : pas de barre de recherche,
     *  juste un en-tête "Favoris" avec un bouton pour fermer. */
    private fun showYoutubeFavorites() {
        youtubeListMode = "favorites"
        binding.editYoutubeSearch.setText("")
        binding.youtubeDefaultContent.visibility = View.GONE
        binding.listYoutubeSearch.visibility = View.VISIBLE
        binding.tvYoutubeError.visibility = View.GONE
        binding.youtubeSearchBarRow.visibility = View.GONE
        binding.youtubeFavoritesHeaderRow.visibility = View.VISIBLE
        val favorites = fr.retrospare.blazeplayer.youtube.YouTubeLibrary.getFavorites(requireContext())
        binding.listYoutubeSearch.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.listYoutubeSearch.adapter = fr.retrospare.blazeplayer.youtube.YouTubeVideoAdapter(
            requireContext(), favorites, compact = false,
            onClick = { openYoutubeVideo(it) },
            onFavoriteToggle = { item, holder -> toggleYoutubeFavorite(item) },
            onMoreClick = { item, anchor -> showYoutubeItemMenu(item, anchor) }
        )
        if (favorites.isEmpty()) {
            binding.tvYoutubeError.text = "Aucun favori pour le moment"
            binding.tvYoutubeError.visibility = View.VISIBLE
        }
    }

    private fun refreshYoutubeHistory() {
        if (!isAdded) return
        val history = fr.retrospare.blazeplayer.youtube.YouTubeLibrary.getHistory(requireContext())
        binding.listYoutubeHistory.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.listYoutubeHistory.adapter = fr.retrospare.blazeplayer.youtube.YouTubeVideoAdapter(
            requireContext(), history, compact = false,
            onClick = { openYoutubeVideo(it) },
            onFavoriteToggle = { item, holder -> toggleYoutubeFavorite(item) },
            onMoreClick = { item, anchor -> showYoutubeItemMenu(item, anchor) }
        )
    }

    /** Ajoute une vidéo YouTube à une playlist (1/2/3) — réutilise le système de playlists déjà
     *  existant pour Local/Réseau/Audio (PlaylistManager/PlaylistDialogs), avec l'id de la vidéo
     *  comme "chemin" et son titre comme nom. */
    /** Menu "..." en bout de ligne : ajouter à une playlist, ou retirer de l'historique (utile
     *  uniquement si la ligne vient de l'historique — le retrait est silencieux/sans effet sinon,
     *  puisque removeFromHistory ne fait rien si l'entrée n'y est pas). */
    private fun showYoutubeItemMenu(item: fr.retrospare.blazeplayer.youtube.YouTubeVideoItem, anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Ajouter à la playlist")
        popup.menu.add(0, 2, 1, "Effacer de l'historique")
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { showYoutubeAddToPlaylist(item); true }
                2 -> {
                    fr.retrospare.blazeplayer.youtube.YouTubeLibrary.removeFromHistory(requireContext(), item.videoId)
                    if (youtubeListMode == null) refreshYoutubeHistory()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showYoutubeAddToPlaylist(item: fr.retrospare.blazeplayer.youtube.YouTubeVideoItem) {
        fr.retrospare.blazeplayer.youtube.YouTubeLibrary.cacheMetadata(requireContext(), item)
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
            requireContext(),
            fr.retrospare.blazeplayer.playlist.PlaylistCategory.YOUTUBE,
            listOf(fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(item.videoId, item.title)),
            onAdded = { setupYoutubePlaylistButtons() }
        )
    }

    private fun setupYoutubePlaylistButtons() {
        val buttons = listOf(binding.btnPlaylistYoutube1, binding.btnPlaylistYoutube2, binding.btnPlaylistYoutube3)
        val lastPlayed = fr.retrospare.blazeplayer.playlist.PlaylistManager
            .getLastPlayed(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.YOUTUBE)
        buttons.forEachIndexed { i, btn ->
            val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager
                .getPlaylist(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.YOUTUBE, i + 1).isNotEmpty()
            btn.isSelected = (lastPlayed == i + 1) && hasItems
            btn.setOnClickListener { openYoutubeSavedPlaylist(i + 1) }
        }
    }

    private fun openYoutubeSavedPlaylist(slot: Int) {
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showPlaylistViewer(
            requireContext(),
            fr.retrospare.blazeplayer.playlist.PlaylistCategory.YOUTUBE,
            slot,
            onPlayAll = { tracks ->
                fr.retrospare.blazeplayer.playlist.PlaylistManager.setLastPlayed(
                    requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.YOUTUBE, slot
                )
                setupYoutubePlaylistButtons()
                tracks.firstOrNull()?.let { first ->
                    // Récupère les vraies métadonnées (titre/chaîne/miniature) de TOUTE la
                    // playlist avant de lancer la lecture — un simple cache local ne suffit pas,
                    // certaines vidéos de la playlist n'ont peut-être jamais été vues
                    // individuellement (recherche/favoris) et n'auraient donc rien en cache.
                    viewLifecycleOwner.lifecycleScope.launch {
                        val metadata = fr.retrospare.blazeplayer.youtube.YouTubeSearchApi.fetchVideosMetadata(
                            requireContext(), tracks.map { it.path }
                        )
                        metadata.values.forEach { fr.retrospare.blazeplayer.youtube.YouTubeLibrary.cacheMetadata(requireContext(), it) }
                        openYoutubeVideo(
                            metadata[first.path] ?: fr.retrospare.blazeplayer.youtube.YouTubeVideoItem(videoId = first.path, title = first.name, channelTitle = "", thumbnailUrl = ""),
                            playlistIds = tracks.map { t -> t.path },
                            playlistTitles = tracks.map { t -> t.name },
                            playlistIndex = 0
                        )
                    }
                }
            },
            onPlayOne = { track ->
                fr.retrospare.blazeplayer.playlist.PlaylistManager.setLastPlayed(
                    requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.YOUTUBE, slot
                )
                setupYoutubePlaylistButtons()
                // onPlayOne ne reçoit que l'élément tapé, pas son index : on récupère la
                // playlist complète pour connaître sa position exacte, nécessaire pour le
                // suivant/précédent dans le lecteur.
                val allTracks = fr.retrospare.blazeplayer.playlist.PlaylistManager.getPlaylist(
                    requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.YOUTUBE, slot
                )
                val index = allTracks.indexOfFirst { it.path == track.path }.coerceAtLeast(0)
                viewLifecycleOwner.lifecycleScope.launch {
                    val metadata = fr.retrospare.blazeplayer.youtube.YouTubeSearchApi.fetchVideosMetadata(
                        requireContext(), allTracks.map { it.path }
                    )
                    metadata.values.forEach { fr.retrospare.blazeplayer.youtube.YouTubeLibrary.cacheMetadata(requireContext(), it) }
                    openYoutubeVideo(
                        metadata[track.path] ?: fr.retrospare.blazeplayer.youtube.YouTubeVideoItem(videoId = track.path, title = track.name, channelTitle = "", thumbnailUrl = ""),
                        playlistIds = allTracks.map { it.path },
                        playlistTitles = allTracks.map { it.name },
                        playlistIndex = index
                    )
                }
            }
        )
    }

    /** Ancien nom conservé pour l'appel depuis updateSectionTitles/onResume. */
    private fun refreshYoutubeDefaultContent() = refreshYoutubeHistory()

    private fun toggleYoutubeFavorite(item: fr.retrospare.blazeplayer.youtube.YouTubeVideoItem) {
        fr.retrospare.blazeplayer.youtube.YouTubeLibrary.toggleFavorite(requireContext(), item)
        // La couleur de l'étoile se met déjà à jour instantanément dans l'adapter lui-même ; si on
        // est justement en train de regarder la liste des favoris, il faut par contre bien
        // retirer/ajouter l'élément à la liste elle-même.
        if (youtubeListMode == "favorites") {
            showYoutubeFavorites()
        }
    }

    private fun openYoutubeVideo(
        item: fr.retrospare.blazeplayer.youtube.YouTubeVideoItem,
        playlistIds: List<String>? = null,
        playlistTitles: List<String>? = null,
        playlistIndex: Int = -1
    ) {
        fr.retrospare.blazeplayer.youtube.YouTubeLibrary.cacheMetadata(requireContext(), item)
        val enriched = fr.retrospare.blazeplayer.youtube.YouTubeLibrary.enrichFromCache(requireContext(), item)
        val intent = android.content.Intent(requireContext(), fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity::class.java).apply {
            putExtra(fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity.EXTRA_VIDEO_ID, enriched.videoId)
            putExtra(fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity.EXTRA_TITLE, enriched.title)
            putExtra(fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity.EXTRA_CHANNEL, enriched.channelTitle)
            putExtra(fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity.EXTRA_THUMBNAIL, enriched.thumbnailUrl)
            if (playlistIds != null && playlistTitles != null && playlistIndex >= 0) {
                putExtra(fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity.EXTRA_PLAYLIST_IDS, playlistIds.toTypedArray())
                putExtra(fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity.EXTRA_PLAYLIST_TITLES, playlistTitles.toTypedArray())
                putExtra(fr.retrospare.blazeplayer.youtube.YouTubePlayerActivity.EXTRA_PLAYLIST_INDEX, playlistIndex)
            }
        }
        startActivity(intent)
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
        val tabs = listOf(binding.tabAll as? android.widget.TextView, binding.tabLocal, binding.tabNetwork, binding.tabYoutube, binding.tabAudio)
        currentTabIndex = 4
        updateTabStyles(4)
        showAudioTab()
    }

    fun switchToTab(index: Int) {
        currentTabIndex = index
        viewModel.onTabSelected(index)
        updateTabStyles(index)
        if (index == 4) {
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
        // Résout le fragment via son tag en plus de la variable locale : après une recréation de
        // la vue de HomeFragment (retour de Réglages, d'une vidéo locale...), audioPlayerFragment
        // repart à null alors que l'instance restaurée existe toujours dans childFragmentManager.
        // Sans ce lookup, elle n'était jamais re-cachée/re-plafonnée, et son onResume() (qui appelle
        // setInAudioPlayer(true) sans vérifier s'il est caché) pouvait re-masquer le mini player
        // juste après, sans qu'aucun événement ne le corrige avant un clic manuel sur un onglet.
        val frag = audioPlayerFragment
            ?: (childFragmentManager.findFragmentByTag("blaze_audio") as? fr.retrospare.blazeplayer.player.AudioPlayerFragment)
                ?.also { audioPlayerFragment = it }
        frag?.let {
            val tx = childFragmentManager.beginTransaction()
                .setMaxLifecycle(it, androidx.lifecycle.Lifecycle.State.STARTED)
            if (!it.isHidden) tx.hide(it)
            tx.commitAllowingStateLoss()
        }
    }

    fun openAudioPlayer(path: String, name: String) {
        updateTabStyles(4)
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
                binding.sectionYoutube.visibility = View.GONE
                viewModel.onTabSelected(1)
            }
            2 -> {
                binding.sectionNetwork.visibility = View.VISIBLE
                binding.sectionLocal.visibility = View.GONE
                binding.sectionYoutube.visibility = View.GONE
                viewModel.onTabSelected(2)
            }
            3 -> {
                binding.sectionLocal.visibility = View.GONE
                binding.sectionNetwork.visibility = View.GONE
                binding.sectionYoutube.visibility = View.VISIBLE
                refreshYoutubeDefaultContent()
            }
            else -> {
                binding.sectionLocal.visibility = View.VISIBLE
                binding.sectionNetwork.visibility = View.GONE
                binding.sectionYoutube.visibility = View.GONE
                viewModel.onTabSelected(1)
            }
        }
    }

    private fun updateTabStyles(selectedIndex: Int) {
        // selectedIndex: 1=Local, 2=Réseau, 3=Blaze Tube, 4=Audio
        val tabViews = listOf(binding.tabLocal, binding.tabNetwork, binding.tabYoutube, binding.tabAudio)
        val tabIcons = listOf(binding.tabLocalIcon, binding.tabNetworkIcon, binding.tabYoutubeIcon, binding.tabAudioIcon)
        val tabTexts = listOf(binding.tabLocalText, binding.tabNetworkText, binding.tabYoutubeText, binding.tabAudioText)

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
        binding.btnFavoritesLocal.setOnClickListener {
            fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showFavoritesList(
                requireContext(), fr.retrospare.blazeplayer.favorites.FavoriteCategory.LOCAL
            ) { favorite ->
                audioPlayerFragment?.savePlaylistFromController() ?: Unit
                findNavController().navigate(
                    R.id.action_home_to_browser,
                    androidx.core.os.bundleOf("path" to favorite.path)
                )
            }
        }
        binding.btnFavoritesNetwork.setOnClickListener {
            fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showFavoritesList(
                requireContext(), fr.retrospare.blazeplayer.favorites.FavoriteCategory.NETWORK
            ) { favorite ->
                val shareId = favorite.shareId
                if (shareId.isNullOrEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "Partage introuvable pour ce favori", android.widget.Toast.LENGTH_SHORT).show()
                    return@showFavoritesList
                }
                val intent = android.content.Intent(requireContext(), fr.retrospare.blazeplayer.player.NetworkVideoBrowserActivity::class.java)
                intent.putExtra("shareId", shareId)
                intent.putExtra("initialPath", favorite.path)
                startActivity(intent)
            }
        }
        setupPlaylistButtons()
    }

    private fun setupPlaylistButtons() {
        val localButtons = listOf(
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal1),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal2),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal3)
        )
        val networkButtons = listOf(
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork1),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork2),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork3)
        )
        val lastPlayedLocal = fr.retrospare.blazeplayer.playlist.PlaylistManager
            .getLastPlayed(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO)
        val lastPlayedNetwork = fr.retrospare.blazeplayer.playlist.PlaylistManager
            .getLastPlayed(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO)
        localButtons.forEachIndexed { i, btn ->
            val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager
                .getPlaylist(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO, i + 1).isNotEmpty()
            btn?.isSelected = (lastPlayedLocal == i + 1) && hasItems
            btn?.setOnClickListener { openSavedPlaylist(fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO, i + 1) }
        }
        networkButtons.forEachIndexed { i, btn ->
            val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager
                .getPlaylist(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO, i + 1).isNotEmpty()
            btn?.isSelected = (lastPlayedNetwork == i + 1) && hasItems
            btn?.setOnClickListener { openSavedPlaylist(fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO, i + 1) }
        }
    }

    private fun openSavedPlaylist(category: fr.retrospare.blazeplayer.playlist.PlaylistCategory, slot: Int) {
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showPlaylistViewer(
            requireContext(), category, slot,
            onPlayAll = { tracks ->
                fr.retrospare.blazeplayer.player.PlayerRouter.openPlaylist(requireContext(), tracks)
                fr.retrospare.blazeplayer.playlist.PlaylistManager.setLastPlayed(requireContext(), category, slot)
                setupPlaylistButtons()
            },
            onPlayOne = { track -> fr.retrospare.blazeplayer.player.PlayerRouter.open(requireContext(), track.path, track.name) }
        )
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
                    popup.menu.add(0, 3, 2, "Ajouter à la playlist")
                    popup.menu.add(0, 4, 3, "Retirer de l'historique")
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> { PlayerRouter.open(requireContext(), item.path, item.name); true }
                            2 -> {
                                val sz = if (item.size > 0) android.text.format.Formatter.formatShortFileSize(requireContext(), item.size) else "Inconnue"
                                val dur = item.duration
                                val ds = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else "N/A"
                                val msg = "Chemin : ${item.path}\n\n" +
                                    "Conteneur : ${item.extension.uppercase()}\n" +
                                    "Durée : $ds\n" +
                                    "Taille : $sz"
                                android.app.AlertDialog.Builder(requireContext())
                                    .setTitle(item.name)
                                    .setMessage(msg)
                                    .setPositiveButton("OK", null)
                                    .show()
                                true
                            }
                            3 -> {
                                val category = if (item.isNetwork) fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO
                                    else fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO
                                fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
                                    requireContext(), category,
                                    listOf(fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(item.path, item.name))
                                )
                                true
                            }
                            4 -> { viewModel.removeFromHistory(item); true }
                            else -> false
                        }
                    }
                    popup.show()
                }
            }
        }
    }

}
