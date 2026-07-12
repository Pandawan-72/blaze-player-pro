package fr.retrospare.blazeplayer.home

import fr.retrospare.blazeplayer.ui.showPremium
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.OnBackPressedCallback
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ImageButton
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
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
import javax.inject.Inject
import androidx.core.content.FileProvider
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.io.FileOutputStream
import java.io.File

@AndroidEntryPoint
class HomeFragment : Fragment() {

    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository

    private val viewModel: HomeViewModel by viewModels()
    private var currentTabIndex = 0
    private var audioPlayerFragment: AudioPlayerFragment? = null
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private enum class GallerySort { FOLDER_NAME, DATE_DESC, DATE_ASC, PHOTO_NAME, FILE_SIZE }
    private enum class GalleryMediaType { PHOTO, VIDEO }

    private var gallerySort: GallerySort = GallerySort.DATE_DESC
    private var currentGalleryMediaType: GalleryMediaType = GalleryMediaType.PHOTO
    private var currentGalleryBucketId: String? = null
    private var currentGalleryBucketName: String? = null
    private var galleryTrashMode: Boolean = false
    private var gallerySelectionMode: Boolean = false
    private val selectedGalleryPhotos = linkedSetOf<String>()
    private var currentGalleryPhotos: List<MediaItem> = emptyList()
    private var galleryFoldersScrollPosition: Int = 0
    private var galleryFoldersScrollOffset: Int = 0

    private var allVideoHistoryItems: List<MediaItem> = emptyList()
    private var latestLocalHistoryItems: List<MediaItem> = emptyList()
    private var latestNetworkHistoryItems: List<MediaItem> = emptyList()
    private var historySelectionTab: Int? = null
    private val selectedHistoryPaths = linkedSetOf<String>()
    private var pendingAudioTabAfterPermission = false
    private var pendingNetworkScanAfterPermission = false
    private var pendingGallerySystemActionRefresh: (() -> Unit)? = null
    private var pendingPermanentDeleteHistoryCleanup: List<MediaItem> = emptyList()
    private var galleryCustomThumbnailMode: Boolean = false
    private var pendingCustomThumbnailVideo: MediaItem? = null
    private var returnTabAfterCustomThumbnail: Int = 1

    /** Empêche onResume() de ramener l'utilisateur sur la liste des dossiers Blaze Gallery au
     *  retour d'un écran qu'on vient nous-mêmes de lancer (éditeur photo, découpe vidéo) — ce
     *  reset était à l'origine pensé uniquement pour le retour d’un écran externe. Positionné
     *  juste avant de lancer ces écrans, consommé (remis à false) dès la première utilisation. */
    private var suppressGalleryResetOnResume = false

    /** Incrémente à chaque nouvel écran Gallery demandé. Les chargements MediaStore étant
     *  asynchrones, une ancienne requête peut terminer après une plus récente et réécrire la
     *  RecyclerView avec l'ancien layout. C'est ce qui pouvait laisser la corbeille avec la grille
     *  des dossiers (2 colonnes) et le bouton "+ dossier" après une suppression définitive. */
    private var galleryRenderGeneration: Long = 0L

    private fun nextGalleryRenderGeneration(): Long = ++galleryRenderGeneration

    private fun isCurrentGalleryRender(generation: Long): Boolean {
        return _binding != null && isAdded && generation == galleryRenderGeneration
    }

    private val galleryPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (_binding == null || !isAdded) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(3)) {
                returnToHome()
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            if (hasGalleryPermission()) {
                showGalleryFolders()
            } else {
                showGalleryPermissionPlaceholder()
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (_binding == null || !isAdded) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(4)) {
                pendingAudioTabAfterPermission = false
                returnToHome()
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            if (pendingAudioTabAfterPermission) {
                pendingAudioTabAfterPermission = false
                showAudioTab()
            }
        }
    }

    private val networkPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingNetworkScanAfterPermission) {
            pendingNetworkScanAfterPermission = false
            requestEmbeddedNetworkScan()
        }
        if (_binding != null && currentTabIndex == 2 && missingRuntimePermissions(networkPermissions()).isEmpty()) {
            binding.root.post { showNetworkHelpOnce() }
        }
    }

    private val gallerySystemActionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Les boîtes système MediaStore (mise à la corbeille / restauration / suppression définitive)
        // font passer l'Activity par onResume(). On neutralise donc le reset de l'onglet Gallery,
        // puis on rafraîchit explicitement la vue qui était active avant l'action système.
        suppressGalleryResetOnResume = false
        if (result.resultCode == android.app.Activity.RESULT_OK && pendingPermanentDeleteHistoryCleanup.isNotEmpty()) {
            viewModel.removeFromHistory(pendingPermanentDeleteHistoryCleanup)
        }
        pendingPermanentDeleteHistoryCleanup = emptyList()
        val refresh = pendingGallerySystemActionRefresh
        pendingGallerySystemActionRefresh = null
        refresh?.invoke() ?: refreshCurrentGalleryView()
    }

    /** Onglet Gallery : au retour de l'éditeur photo (via la croix, choisie par l'utilisateur —
     *  jamais de fermeture automatique après un enregistrement), on ne fait rien de spécial :
     *  c'est justement le but de [suppressGalleryResetOnResume], qui empêche onResume() de nous
     *  ramener sur la liste des dossiers alors qu'on était dans un dossier précis. */
    private val photoEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    /** Au retour de la découpe vidéo/export GIF, on navigue explicitement vers le dossier "Blaze
     *  Gallery" où le résultat vient d'être enregistré — contrairement à l'éditeur photo, ici
     *  l'utilisateur doit voir immédiatement ce qu'il vient de produire. */
    private val videoTrimLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val bucketId = data?.getStringExtra(fr.retrospare.blazeplayer.gallery.edit.VideoTrimActivity.EXTRA_RESULT_BUCKET_ID)
            val bucketName = data?.getStringExtra(fr.retrospare.blazeplayer.gallery.edit.VideoTrimActivity.EXTRA_RESULT_BUCKET_NAME)
            val isGif = data?.getBooleanExtra(fr.retrospare.blazeplayer.gallery.edit.VideoTrimActivity.EXTRA_RESULT_IS_GIF, false) ?: false
            if (bucketId != null && bucketName != null) {
                currentGalleryMediaType = if (isGif) GalleryMediaType.PHOTO else GalleryMediaType.VIDEO
                refreshGalleryTypeToggleColors()
                galleryTrashMode = false
                showGalleryPhotos(bucketId, bucketName)
            }
        }
    }

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
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (historySelectionTab != null) {
                    clearHistorySelection()
                    return
                }
                if (currentTabIndex == 3 && handleBlazeGalleryBack()) return
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        // Différé : setUpMediaRouteButton() déclenche en interne CastContext.getSharedInstance(),
        // un appel synchrone connu pour provoquer des ANR sur le thread principal (même cause que
        // le correctif appliqué dans PlayerActivity). On évite qu'il bloque la mise en place
        // initiale de la vue, qui se répète à chaque recréation de HomeFragment.
        view.post {
            if (!isAdded) return@post
            try {
                // Même dialog custom que dans le lecteur : quand une session Cast est active,
                // le second appui sur l'icône de l'accueil ouvre le panneau Blaze au lieu du
                // vieux contrôleur MediaRouter gris/vert. Le sélecteur d'appareils initial
                // reste celui du SDK Google Cast.
                binding.btnCast.setDialogFactory(fr.retrospare.blazeplayer.cast.BlazeMediaRouteDialogFactory())
                com.google.android.gms.cast.framework.CastButtonFactory
                    .setUpMediaRouteButton(requireContext(), binding.btnCast)
            } catch (e: Exception) {
                binding.btnCast.visibility = android.view.View.GONE
            }
        }

        // "Caster l'écran du téléphone" : ouvre directement les paramètres système Android de
        // diffusion d'écran, plutôt qu'un pipeline maison (capture MediaProjection + encodage
        // vidéo temps réel + serveur de streaming live) — l'API officielle de Google pour ça
        // (CastRemoteDisplay) est abandonnée depuis plusieurs années, et reconstruire l'équivalent
        // soi-même serait un chantier bien plus lourd que tout le reste de l'app, avec un risque
        // élevé de ne pas fonctionner de façon fiable. Android sait déjà le faire nativement.
        binding.btnCastRemote.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), fr.retrospare.blazeplayer.player.ChromecastRemoteActivity::class.java))
        }

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
                fr.retrospare.blazeplayer.ui.InfoDialog.show(activity, getString(R.string.info_dialog_title_error), getString(R.string.toast_cannot_open_screen_cast_settings))
            }
        }

        binding.btnNetworkHelp.setOnClickListener { showNetworkHelpDialog() }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_settings)
        }
        setupTabs()
        monitorTrialExpiryWhileVisible()
        setupButtons()
        setupGalleryTab()
        observeViewModel()
        updateVersionBadge()
        // Force la réapparition du mini player si nécessaire : recréer cette vue (retour de
        // Réglages, d'une vidéo locale...) ne déclenche pas onResume() de l'Activity, donc rien
        // d'autre ne le refaisait apparaître automatiquement dans ces cas-là.
        (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.refreshMiniPlayer()

        // Lancement depuis l'icône indépendante "Blaze Gallery".
        // MainActivity peut recevoir l'intent avant que HomeFragment soit réellement attaché ;
        // on le retraitera donc ici, au moment où les onglets existent vraiment.
        requireActivity().intent?.let { launchIntent ->
            val fromGalleryAlias = launchIntent.component?.className == "${requireContext().packageName}.BlazeGalleryLauncherActivity"
            val requestedGallery = launchIntent.getBooleanExtra("openBlazeGallery", false)
            val alreadyConsumed = launchIntent.getBooleanExtra("blazeGalleryLaunchConsumed", false)
            if ((fromGalleryAlias || requestedGallery) && !alreadyConsumed) {
                launchIntent.putExtra("blazeGalleryLaunchConsumed", true)
                launchIntent.removeExtra("openBlazeGallery")
                binding.root.post { requestBlazeGalleryTab() }
            }
        }

        consumePendingBlazeGalleryLaunchInHome()
        consumePendingBlazeAudioLaunchInHome()
        if (_binding != null) {
            setupPlaylistButtons()
            setupVideoQueueButtons()
        }

        // Switche vers Blaze Audio quand un fichier audio est ajouté depuis le navigateur
        val sharedAudioVm = androidx.lifecycle.ViewModelProvider(requireActivity())[fr.retrospare.blazeplayer.home.SharedAudioViewModel::class.java]
        viewLifecycleOwner.lifecycleScope.launch {
            sharedAudioVm.pendingTracks.collect { tracks ->
                if (tracks.isNotEmpty()) {
                    if (canOpenTab(4)) {
                        currentTabIndex = 4
                        updateTabStyles(4)
                        showAudioTab()
                    } else {
                        sharedAudioVm.consumePendingTracks()
                        findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enforceCurrentTabAccess()
        refreshAccessibleVideoHistory()
        updateVersionBadge()
        consumePendingBlazeGalleryLaunchInHome()
        consumePendingBlazeAudioLaunchInHome()
        if (_binding != null) {
            setupPlaylistButtons()
            setupVideoQueueButtons()
        }
        if (suppressGalleryResetOnResume) {
            // Retour d'un écran qu'on vient nous-mêmes de lancer ou d'une boîte système MediaStore :
            // ne surtout pas forcer l'accueil des dossiers, sinon la corbeille peut se transformer
            // visuellement en vue dossiers (2 colonnes + bouton "+ dossier") avant que le callback
            // de suppression/restauration n'ait le temps de rafraîchir la bonne vue.
            suppressGalleryResetOnResume = false
        } else if (currentTabIndex == 3) {
            refreshCurrentGalleryView()
        }
    }

    private fun monitorTrialExpiryWhileVisible() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val state = userRepository.currentAccessState()
                if (state.isTrialActive) {
                    kotlinx.coroutines.delay(
                        (state.trialEndMillis - state.evaluatedAtMillis).coerceAtLeast(1L)
                    )
                }
                if (_binding != null && isAdded) {
                    enforceCurrentTabAccess()
                    refreshAccessibleVideoHistory()
                    updateVersionBadge()
                }
            }
        }
    }

    private fun enforceCurrentTabAccess() {
        if (_binding == null || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (currentTabIndex in 2..4 && !canOpenTab(currentTabIndex)) {
                if (currentTabIndex == 4) {
                    requireContext().stopService(
                        android.content.Intent(requireContext(), fr.retrospare.blazeplayer.player.BlazePlayerService::class.java)
                    )
                }
                returnToHome()
            }
        }
    }

    /** Point d'entrée unique et sûr pour forcer l'onglet Blaze Gallery, appelable depuis
     *  MainActivity sans risquer de planter ou de ne rien faire silencieusement si la vue de ce
     *  fragment n'existe pas encore (écran d'accueil pas encore affiché) ou plus (une autre
     *  destination du NavHost est actuellement affichée). Dans ce dernier cas, la demande reste
     *  persistée dans les SharedPreferences et sera reconsommée dès que HomeFragment sera recréé
     *  (onViewCreated) ou repasse au premier plan (onResume) — voir ces deux méthodes. */
    fun requestBlazeGalleryTab() {
        requireContext().getSharedPreferences("launcher_requests", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pendingOpenBlazeGallery", true)
            .putLong("pendingOpenBlazeGalleryAt", System.currentTimeMillis())
            .apply()
        // N'applique tout de suite que si la vue existe réellement ; sinon onViewCreated/onResume
        // s'en chargeront eux-mêmes une fois la vue disponible.
        if (_binding != null && isAdded) {
            consumePendingBlazeGalleryLaunchInHome()
        }
    }

    private fun consumePendingBlazeGalleryLaunchInHome(): Boolean {
        if (_binding == null || !isAdded) return false
        val prefs = requireContext().getSharedPreferences("launcher_requests", android.content.Context.MODE_PRIVATE)
        val pending = prefs.getBoolean("pendingOpenBlazeGallery", false) ||
            prefs.getLong("pendingOpenBlazeGalleryAt", 0L) > 0L
        if (!pending) return false

        fun applyGalleryTab() {
            if (_binding == null) return
            if (currentTabIndex == 3) {
                // Les rappels retardés du launcher ne doivent pas réinitialiser la navigation interne
                // de Blaze Gallery si l'utilisateur est déjà entré dans la corbeille ou un dossier.
                updateTabStyles(3)
                viewModel.onTabSelected(3)
            } else {
                switchToTab(3)
            }
        }

        applyGalleryTab()
        binding.root.postDelayed({ applyGalleryTab() }, 120L)
        binding.root.postDelayed({ applyGalleryTab() }, 300L)
        binding.root.postDelayed({ applyGalleryTab() }, 700L)

        // On efface seulement après plusieurs applications, pour éviter que l'ancien
        // état restauré de l'écran d'accueil repasse sur Local quand l'application
        // était déjà en tâche de fond.
        binding.root.postDelayed({
            prefs.edit()
                .putBoolean("pendingOpenBlazeGallery", false)
                .remove("pendingOpenBlazeGalleryAt")
                .apply()
        }, 1000L)
        return true
    }

    fun requestBlazeAudioTab() {
        requireContext().getSharedPreferences("launcher_requests", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pendingOpenBlazeAudio", true)
            .putLong("pendingOpenBlazeAudioAt", System.currentTimeMillis())
            .apply()
        if (_binding != null && isAdded) {
            consumePendingBlazeAudioLaunchInHome()
        }
    }

    private fun consumePendingBlazeAudioLaunchInHome(): Boolean {
        if (_binding == null || !isAdded) return false
        val prefs = requireContext().getSharedPreferences("launcher_requests", android.content.Context.MODE_PRIVATE)
        val pending = prefs.getBoolean("pendingOpenBlazeAudio", false) ||
            prefs.getLong("pendingOpenBlazeAudioAt", 0L) > 0L
        if (!pending) return false

        fun applyAudioTab() {
            if (_binding == null) return
            switchToTab(4)
        }

        applyAudioTab()
        binding.root.postDelayed({ applyAudioTab() }, 120L)
        binding.root.postDelayed({ applyAudioTab() }, 300L)
        binding.root.postDelayed({ applyAudioTab() }, 700L)

        binding.root.postDelayed({
            prefs.edit()
                .putBoolean("pendingOpenBlazeAudio", false)
                .remove("pendingOpenBlazeAudioAt")
                .apply()
        }, 1000L)
        return true
    }

    private fun selectTab(index: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(index)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            currentTabIndex = index
            updateTabStyles(index)
            if (index == 4) {
                viewModel.onTabSelected(index)
                if (requestAudioPermissionsIfNeeded()) showAudioTab()
            } else {
                hideAudioTab()
                viewModel.onTabSelected(index)
                if (index == 2) requestNetworkPermissionsIfNeeded()
                updateSectionTitles(index)
                if (index == 2) showNetworkHelpOnce()
            }
        }
    }

    private fun setupTabs() {
        binding.tabLocal.setOnClickListener { selectTab(1) }
        binding.tabNetwork.setOnClickListener { selectTab(2) }
        binding.tabGallery.setOnClickListener { selectTab(3) }
        binding.tabAudio.setOnClickListener { selectTab(4) }

        val requestedTab = when (viewModel.currentTabIndex.value) {
            2, 3, 4 -> viewModel.currentTabIndex.value
            else -> 1
        }

        // Toujours construire l'accueil sur Blaze Video (gratuit). Un onglet mémorisé Pro/Pro+
        // n'est restauré qu'après vérification des droits, ce qui évite d'afficher ou d'initialiser
        // brièvement une fonctionnalité premium après l'expiration de l'essai.
        currentTabIndex = 1
        updateTabStyles(1)
        hideAudioTab()
        updateSectionTitles(1)
        viewModel.onTabSelected(1)

        if (requestedTab != 1) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (!canOpenTab(requestedTab) || _binding == null) return@launch
                currentTabIndex = requestedTab
                updateTabStyles(requestedTab)
                viewModel.onTabSelected(requestedTab)
                if (requestedTab == 4) {
                    if (requestAudioPermissionsIfNeeded()) showAudioTab()
                } else {
                    hideAudioTab()
                    if (requestedTab == 2) requestNetworkPermissionsIfNeeded()
                    updateSectionTitles(requestedTab)
                    if (requestedTab == 2) showNetworkHelpOnce()
                }
            }
        }
    }

    private fun openNetworkSourcesFromTab() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(2)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            currentTabIndex = 2
            audioPlayerFragment?.savePlaylistFromController() ?: Unit
            updateTabStyles(2)
            hideAudioTab()
            requestNetworkPermissionsIfNeeded()
            updateSectionTitles(2)
            showNetworkHelpOnce()
        }
    }

    private fun ensureEmbeddedNetworkSources() {
        if (_binding == null || !isAdded) return
        val existing = childFragmentManager.findFragmentByTag("home_network_sources")
        if (existing == null) {
            childFragmentManager.beginTransaction()
                .replace(
                    fr.retrospare.blazeplayer.R.id.networkConfigContainer,
                    fr.retrospare.blazeplayer.network.NetworkSharesFragment().apply {
                        arguments = Bundle().apply { putBoolean("embeddedInHome", true) }
                    },
                    "home_network_sources"
                )
                .commitAllowingStateLoss()
        } else if (existing.isHidden) {
            childFragmentManager.beginTransaction()
                .show(existing)
                .commitAllowingStateLoss()
        }
    }

    private fun requestEmbeddedNetworkScan() {
        runWithProAccess {
            if (_binding == null || !isAdded) return@runWithProAccess
            if (!requestNetworkPermissionsIfNeeded(scanAfterGrant = true)) return@runWithProAccess
            updateEmbeddedNetworkScanState(scanning = true)
            ensureEmbeddedNetworkSources()
            binding.root.post {
                (childFragmentManager.findFragmentByTag("home_network_sources") as? fr.retrospare.blazeplayer.network.NetworkSharesFragment)
                    ?.requestScanFromParent()
                    ?: updateEmbeddedNetworkScanState(scanning = false)
            }
        }
    }

    private fun runWithProAccess(action: () -> Unit) {
        if (_binding == null || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (!fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            if (_binding != null && isAdded) action()
        }
    }

    private fun updateHeaderCastButtons(tabIndex: Int) {
        val showCastButtons = tabIndex == 1
        val visibility = if (showCastButtons) View.VISIBLE else View.GONE
        binding.btnCast.visibility = visibility
        binding.btnScreenCast.visibility = visibility
        binding.btnCastRemote.visibility = visibility
        binding.btnNetworkHelp.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
    }

    private fun showNetworkHelpOnce() {
        if (_binding == null || !isAdded || currentTabIndex != 2) return
        if (missingRuntimePermissions(networkPermissions()).isNotEmpty()) return
        val prefs = requireContext().getSharedPreferences(PREFS_HELP_MODALS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NETWORK_HELP_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_NETWORK_HELP_SHOWN, true).apply()
        binding.root.post { showNetworkHelpDialog() }
    }

    private fun showNetworkHelpDialog() {
        if (!isAdded) return
        fr.retrospare.blazeplayer.ui.InfoDialog.show(
            requireContext(),
            getString(R.string.network_help_title),
            getString(R.string.network_help_message),
            iconRes = R.drawable.ic_help_circle
        )
    }

    fun updateEmbeddedNetworkScanState(scanning: Boolean) {
        if (_binding == null || !isAdded) return
        binding.tvNetworkHeaderSubtitle.text = getString(if (scanning) R.string.scan_in_progress else R.string.scan_complete)
        binding.btnNetworkHeaderScan.isEnabled = !scanning
        binding.btnNetworkHeaderScan.alpha = if (scanning) 0.55f else 1f
    }



    private suspend fun canOpenTab(index: Int): Boolean {
        // 1 = Blaze Video (gratuit), 2 = Réseau / sources SMB-UPnP (Pro), 3 = Blaze Gallery (Pro), 4 = Blaze Audio (Pro+).
        return when (index) {
            2, 3 -> fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            4 -> fr.retrospare.blazeplayer.paywall.FeatureAccess.isProPlus(userRepository)
            else -> true
        }
    }

    /** Affiche à droite du logo la mention Free / Pro / Pro+ correspondant aux droits effectifs
     *  (achat persistant ou essai Pro+ actif). */
    private fun updateVersionBadge() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val proPlus = fr.retrospare.blazeplayer.paywall.FeatureAccess.isProPlus(userRepository)
            val pro = proPlus || fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            if (_binding == null) return@launch
            when {
                proPlus -> {
                    binding.tvVersionBadge.text = getString(R.string.version_badge_pro_plus)
                    binding.tvVersionBadge.setBackgroundResource(R.drawable.bg_pro_badge)
                    binding.tvVersionBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_accent))
                }
                pro -> {
                    binding.tvVersionBadge.text = getString(R.string.version_badge_pro)
                    binding.tvVersionBadge.setBackgroundResource(R.drawable.bg_pro_badge)
                    binding.tvVersionBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_accent))
                }
                else -> {
                    binding.tvVersionBadge.text = getString(R.string.version_badge_free)
                    binding.tvVersionBadge.setBackgroundResource(R.drawable.bg_badge_gray)
                    binding.tvVersionBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_background))
                }
            }
            binding.tvVersionBadge.visibility = View.VISIBLE
        }
    }

    /** Configure l'onglet Blaze Gallery : il affiche uniquement la galerie locale MediaStore. */
    private fun setupGalleryTab() {
        configureGalleryPrimaryAsTrashIcon()
        binding.btnGalleryPrimary.setOnClickListener { showGalleryTrash() }
        binding.btnGallerySecondary.text = getString(R.string.action_back)
        binding.btnGallerySecondary.setOnClickListener { showGalleryFolders() }
        binding.btnGallerySecondary.visibility = View.GONE
        setupGalleryTypeToggle()
    }

    /** Câble une seule fois les 2 icônes de bascule Photo/Vidéo de l'accueil Blaze Gallery et
     *  applique leur couleur initiale (vert = actif, gris = inactif). */
    private fun setupGalleryTypeToggle() {
        binding.btnGalleryTypePhoto.setOnClickListener { switchGalleryMediaType(GalleryMediaType.PHOTO) }
        binding.btnGalleryTypeVideo.setOnClickListener { switchGalleryMediaType(GalleryMediaType.VIDEO) }
        refreshGalleryTypeToggleColors()
    }

    private fun switchGalleryMediaType(type: GalleryMediaType) {
        if (currentGalleryMediaType == type || galleryCustomThumbnailMode) return
        currentGalleryMediaType = type
        galleryFoldersScrollPosition = 0
        galleryFoldersScrollOffset = 0
        refreshGalleryTypeToggleColors()
        showGalleryFolders()
    }

    private fun refreshGalleryTypeToggleColors() {
        val activeColor = ContextCompat.getColor(requireContext(), R.color.green_accent)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
        binding.btnGalleryTypePhoto.setColorFilter(if (currentGalleryMediaType == GalleryMediaType.PHOTO) activeColor else inactiveColor)
        binding.btnGalleryTypeVideo.setColorFilter(if (currentGalleryMediaType == GalleryMediaType.VIDEO) activeColor else inactiveColor)
    }

    /** Le sélecteur Photo/Vidéo n'est visible qu'à l'accueil de Blaze Gallery (liste des
     *  dossiers) : il disparaît dès qu'on entre dans un dossier, la corbeille, ou un mode de
     *  sélection multiple, exactement comme le reste de la barre d'action de cet écran. */
    private fun updateGalleryTypeToggle(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.galleryTypeHeaderToggle.visibility = visibility
        binding.btnGalleryTypePhoto.visibility = visibility
        binding.btnGalleryTypeVideo.visibility = visibility
    }

    private fun handleBlazeGalleryBack(): Boolean {
        return when {
            gallerySelectionMode -> {
                clearGallerySelection()
                currentGalleryBucketId?.let { showGalleryPhotos(it, currentGalleryBucketName.orEmpty()) } ?: showGalleryFolders()
                true
            }
            galleryTrashMode -> {
                showGalleryFolders()
                true
            }
            galleryCustomThumbnailMode -> {
                cancelCustomThumbnailSelection()
                true
            }
            currentGalleryBucketId != null -> {
                showGalleryFolders(restoreScroll = true)
                true
            }
            else -> false
        }
    }

    private fun refreshCurrentGalleryView() {
        if (_binding == null || !isAdded || currentTabIndex != 3) return
        // Ne pas détruire une sélection multiple juste parce qu'un chooser/une boîte système a
        // temporairement mis l'app en pause. Les actions destructives nettoient déjà la sélection
        // avant de lancer MediaStore.
        if (gallerySelectionMode) return
        when {
            galleryTrashMode -> showGalleryTrash()
            currentGalleryBucketId != null -> showGalleryPhotos(currentGalleryBucketId.orEmpty(), currentGalleryBucketName.orEmpty())
            else -> showGalleryFolders()
        }
    }

    /** URI MediaStore correspondant au type de média actuellement affiché dans Blaze Gallery. */
    private val galleryContentUri: android.net.Uri
        get() = if (currentGalleryMediaType == GalleryMediaType.VIDEO)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** Dossier public de base (Pictures ou Movies) selon le type de média affiché. */
    private val galleryBaseDirectory: String
        get() = if (currentGalleryMediaType == GalleryMediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

    private val galleryDefaultMimeType: String
        get() = if (currentGalleryMediaType == GalleryMediaType.VIDEO) "video/*" else "image/*"

    /** Clé SharedPreferences des dossiers vides créés manuellement : séparée par type de média
     *  pour qu'un dossier photo créé n'apparaisse pas comme dossier vidéo, et inversement. */
    private fun createdGalleryFoldersPrefKey(): String =
        if (currentGalleryMediaType == GalleryMediaType.VIDEO) "created_folders_video" else "created_folders_photo"

    private fun runtimePermissionPrefs() =
        requireContext().getSharedPreferences(PREFS_RUNTIME_PERMISSIONS, android.content.Context.MODE_PRIVATE)

    private fun missingRuntimePermissions(permissions: Array<String>): Array<String> =
        permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    private fun galleryPermissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun audioPermissions(): Array<String> {
        val result = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            result += android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            result += android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // Requis par android.media.audiofx.Visualizer pour l'égaliseur visuel dynamique.
            // L'app ne s'en sert pas pour enregistrer du son.
            result += android.Manifest.permission.RECORD_AUDIO
        }
        return result.toTypedArray()
    }

    private fun networkPermissions(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        emptyArray()
    }

    private fun hasGalleryPermission(): Boolean = missingRuntimePermissions(galleryPermissions()).isEmpty()

    private fun requestGalleryPermissionIfNeeded(): Boolean {
        if (hasGalleryPermission()) return true
        val prefs = runtimePermissionPrefs()
        val permissions = missingRuntimePermissions(galleryPermissions())
        if (permissions.isEmpty()) return true
        if (prefs.getBoolean(KEY_GALLERY_PERMISSIONS_PROMPTED, false)) {
            showGalleryPermissionPlaceholder()
            return false
        }
        prefs.edit().putBoolean(KEY_GALLERY_PERMISSIONS_PROMPTED, true).apply()
        galleryPermissionLauncher.launch(permissions)
        return false
    }

    private fun requestAudioPermissionsIfNeeded(): Boolean {
        val permissions = missingRuntimePermissions(audioPermissions())
        if (permissions.isEmpty()) return true
        val prefs = runtimePermissionPrefs()
        if (prefs.getBoolean(KEY_AUDIO_PERMISSIONS_PROMPTED, false)) return true
        prefs.edit().putBoolean(KEY_AUDIO_PERMISSIONS_PROMPTED, true).apply()
        pendingAudioTabAfterPermission = true
        audioPermissionLauncher.launch(permissions)
        return false
    }

    private fun requestNetworkPermissionsIfNeeded(scanAfterGrant: Boolean = false): Boolean {
        val permissions = missingRuntimePermissions(networkPermissions())
        if (permissions.isEmpty()) return true
        val prefs = runtimePermissionPrefs()
        if (prefs.getBoolean(KEY_NETWORK_PERMISSIONS_PROMPTED, false)) return true
        prefs.edit().putBoolean(KEY_NETWORK_PERMISSIONS_PROMPTED, true).apply()
        pendingNetworkScanAfterPermission = scanAfterGrant
        networkPermissionLauncher.launch(permissions)
        return false
    }

    private fun showGalleryPermissionPlaceholder() {
        if (_binding == null || !isAdded) return
        binding.listGallery.visibility = View.GONE
        binding.emptyStateGallery.visibility = View.VISIBLE
        binding.tvEmptyStateGallery.text = getString(R.string.permission_gallery_required)
        binding.btnGallerySecondary.visibility = View.GONE
        binding.btnGalleryPrimary.visibility = View.GONE
        updateGalleryTypeToggle(false)
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setGalleryPrimaryCompact(compact: Boolean) {
        val params = binding.btnGalleryPrimary.layoutParams
        params.width = if (compact) dp(40) else ViewGroup.LayoutParams.WRAP_CONTENT
        binding.btnGalleryPrimary.layoutParams = params
        binding.btnGalleryPrimary.minWidth = 0
        binding.btnGalleryPrimary.minimumWidth = 0
        binding.btnGalleryPrimary.iconPadding = if (compact) 0 else dp(6)
        binding.btnGalleryPrimary.iconSize = if (compact) dp(22) else dp(15)
        if (compact) {
            binding.btnGalleryPrimary.setPadding(0, 0, 0, 0)
        } else {
            binding.btnGalleryPrimary.setPadding(dp(14), 0, dp(14), 0)
        }
    }

    private fun setGallerySecondaryCompact(compact: Boolean) {
        val params = binding.btnGallerySecondary.layoutParams
        params.width = if (compact) dp(40) else ViewGroup.LayoutParams.WRAP_CONTENT
        binding.btnGallerySecondary.layoutParams = params
        binding.btnGallerySecondary.minWidth = 0
        binding.btnGallerySecondary.minimumWidth = 0
        binding.btnGallerySecondary.iconPadding = if (compact) 0 else dp(6)
        binding.btnGallerySecondary.iconSize = if (compact) dp(20) else dp(15)
        if (compact) {
            binding.btnGallerySecondary.setPadding(0, 0, 0, 0)
        } else {
            binding.btnGallerySecondary.setPadding(dp(12), 0, dp(12), 0)
        }
    }

    private fun configureGalleryPrimaryAsTrashIcon() {
        setGalleryPrimaryCompact(true)
        binding.btnGalleryPrimary.text = ""
        binding.btnGalleryPrimary.setIconResource(R.drawable.ic_trash)
        binding.btnGalleryPrimary.contentDescription = getString(R.string.gallery_trash)
        binding.btnGalleryPrimary.setTextColor(android.graphics.Color.TRANSPARENT)
        binding.btnGalleryPrimary.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF5C5C"))
        binding.btnGalleryPrimary.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_header_action_button)
        binding.btnGalleryPrimary.backgroundTintList = null
        binding.btnGalleryPrimary.strokeWidth = 0
        binding.btnGalleryPrimary.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    }

    private fun configureGalleryPrimaryAsTextAction() {
        setGalleryPrimaryCompact(false)
        binding.btnGalleryPrimary.contentDescription = null
    }

    private fun styleGalleryBackButton() {
        setGallerySecondaryCompact(true)
        binding.btnGallerySecondary.text = ""
        binding.btnGallerySecondary.contentDescription = getString(R.string.action_back)
        binding.btnGallerySecondary.setIconResource(R.drawable.ic_arrow_back)
        binding.btnGallerySecondary.setTextColor(android.graphics.Color.TRANSPARENT)
        binding.btnGallerySecondary.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.on_surface))
        binding.btnGallerySecondary.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_header_action_button)
        binding.btnGallerySecondary.backgroundTintList = null
        binding.btnGallerySecondary.strokeWidth = 0
        binding.btnGallerySecondary.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
    }

    private fun styleGallerySecondaryNeutralButton() {
        setGallerySecondaryCompact(false)
        binding.btnGallerySecondary.contentDescription = null
        binding.btnGallerySecondary.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
        binding.btnGallerySecondary.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.on_surface))
        binding.btnGallerySecondary.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_variant))
        binding.btnGallerySecondary.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#B8C0CC"))
        binding.btnGallerySecondary.strokeWidth = (1 * resources.displayMetrics.density).toInt()
    }

    private fun styleGalleryCreateFolderButton() {
        setGallerySecondaryCompact(false)
        binding.btnGallerySecondary.contentDescription = null
        binding.btnGallerySecondary.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
        binding.btnGallerySecondary.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.green_accent))
        binding.btnGallerySecondary.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_variant))
        binding.btnGallerySecondary.strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.green_accent_stroke))
        binding.btnGallerySecondary.strokeWidth = dp(1)
    }

    private fun styleGalleryTrashButton(emptyAction: Boolean = false) {
        configureGalleryPrimaryAsTextAction()
        if (emptyAction) {
            binding.btnGalleryPrimary.setTextColor(android.graphics.Color.WHITE)
            binding.btnGalleryPrimary.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.btnGalleryPrimary.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2A2F3A"))
            binding.btnGalleryPrimary.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E05151"))
            binding.btnGalleryPrimary.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        } else {
            binding.btnGalleryPrimary.setTextColor(android.graphics.Color.WHITE)
            binding.btnGalleryPrimary.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.btnGalleryPrimary.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_variant))
            binding.btnGalleryPrimary.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#B8C0CC"))
            binding.btnGalleryPrimary.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        }
    }

    private fun confirmGalleryDeletion(message: String, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(message)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.action_confirm_delete)) { _, _ -> onConfirm() }
            .showPremium()
    }

    private fun showGallerySortMenu() {
        val popup = android.widget.PopupMenu(requireContext(), binding.btnGalleryPrimary)
        popup.menu.add(0, 1, 0, getString(R.string.gallery_sort_date_desc))
        popup.menu.add(0, 2, 1, getString(R.string.gallery_sort_date_asc))
        popup.menu.add(0, 3, 2, getString(R.string.gallery_sort_name))
        popup.menu.add(0, 4, 3, getString(R.string.gallery_sort_size))
        popup.setOnMenuItemClickListener { item ->
            gallerySort = when (item.itemId) {
                2 -> GallerySort.DATE_ASC
                3 -> GallerySort.PHOTO_NAME
                4 -> GallerySort.FILE_SIZE
                else -> GallerySort.DATE_DESC
            }
            currentGalleryBucketId?.let { showGalleryPhotos(it, currentGalleryBucketName.orEmpty()) }
            true
        }
        popup.show()
    }

    private fun saveGalleryFoldersScrollPosition() {
        val recycler = _binding?.listGallery ?: return
        val layoutManager = recycler.layoutManager as? androidx.recyclerview.widget.GridLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        if (firstVisible == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
        val firstView = layoutManager.findViewByPosition(firstVisible)
        galleryFoldersScrollPosition = firstVisible
        galleryFoldersScrollOffset = (firstView?.top ?: recycler.paddingTop) - recycler.paddingTop
    }

    private fun restoreGalleryFoldersScrollPosition(itemCount: Int) {
        if (itemCount <= 0) return
        val position = galleryFoldersScrollPosition.coerceIn(0, itemCount - 1)
        val offset = galleryFoldersScrollOffset
        val recycler = _binding?.listGallery ?: return
        recycler.post {
            val currentRecycler = _binding?.listGallery ?: return@post
            val layoutManager = currentRecycler.layoutManager as? androidx.recyclerview.widget.GridLayoutManager ?: return@post
            layoutManager.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun openGalleryFolder(folder: MediaItem) {
        saveGalleryFoldersScrollPosition()
        showGalleryPhotos(folder.path, folder.name)
    }

    private fun showGalleryFolders(restoreScroll: Boolean = false) {
        if (!isAdded || !requestGalleryPermissionIfNeeded()) return
        val renderGeneration = nextGalleryRenderGeneration()
        currentGalleryBucketId = null
        currentGalleryBucketName = null
        galleryTrashMode = false
        clearGallerySelection()
        binding.btnGallerySecondary.visibility = View.VISIBLE
        binding.btnGallerySecondary.text = getString(R.string.gallery_folder_button)
        binding.btnGallerySecondary.setIconResource(R.drawable.ic_add_circle)
        binding.btnGallerySecondary.setOnClickListener { showCreateGalleryFolderDialog() }
        styleGalleryCreateFolderButton()
        binding.btnGalleryPrimary.visibility = View.VISIBLE
        configureGalleryPrimaryAsTrashIcon()
        binding.btnGalleryPrimary.setOnClickListener { showGalleryTrash() }
        binding.tvSectionGallery.text = if (galleryCustomThumbnailMode) {
            getString(R.string.custom_thumbnail_pick_folder)
        } else {
            getString(R.string.gallery_folders)
        }
        // Le sélecteur Photo/Vidéo n'a de sens qu'à l'accueil de la galerie ; en mode sélection
        // de miniature personnalisée, on reste forcé sur Photo (voir startCustomThumbnailSelection).
        updateGalleryTypeToggle(visible = !galleryCustomThumbnailMode)
        binding.listGallery.visibility = View.VISIBLE
        binding.listGallery.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(12)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
            itemAnimator = null
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGalleryFoldersFromMediaStore() }
            if (!isCurrentGalleryRender(renderGeneration) || galleryTrashMode || currentGalleryBucketId != null) return@launch
            binding.listGallery.adapter = GalleryAdapter(
                items = folders,
                grid = false,
                trashMode = false,
                onClick = { folder -> openGalleryFolder(folder) },
                onLongClick = {},
                onMore = { folder, anchor -> showGalleryFolderMenu(folder, anchor) }
            )
            if (restoreScroll) restoreGalleryFoldersScrollPosition(folders.size)
            if (currentGalleryMediaType == GalleryMediaType.PHOTO) {
                // Pré-cache utile à la réouverture, mais volontairement lancé doucement pour ne
                // pas concurrencer le scroll de l'accueil Galerie où chaque dossier affiche 4 aperçus.
                precacheGalleryPhotoThumbnails(folders.flatMap { it.previewUris }, renderGeneration, maxSize = 220, initialDelayMs = 450L)
            }
            updateGalleryEmptyState(folders.isEmpty(), getString(R.string.gallery_empty_folders))
        }
    }

    /** Bascule l'état vide générique de Blaze Gallery (dossiers/photos/corbeille partagent la même
     *  grille [listGallery]) — mêmes principes que pour l'historique Local/Réseau : ne pas laisser
     *  une grille vide sans explication. Pas de bouton d'action ici (contrairement à
     *  Local/Réseau) car l'action pertinente change trop selon le contexte (créer un dossier,
     *  ajouter une photo, corbeille naturellement vide...). */
    private fun updateGalleryEmptyState(isEmpty: Boolean, message: String) {
        binding.listGallery.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyStateGallery.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) binding.tvEmptyStateGallery.text = message
    }

    private fun showGalleryPhotos(bucketId: String, bucketName: String) {
        if (!isAdded || !requestGalleryPermissionIfNeeded()) return
        val renderGeneration = nextGalleryRenderGeneration()
        currentGalleryBucketId = bucketId
        currentGalleryBucketName = bucketName
        galleryTrashMode = false
        clearGallerySelection()
        updateGalleryTypeToggle(visible = false)
        binding.btnGallerySecondary.visibility = View.VISIBLE
        binding.btnGallerySecondary.text = getString(R.string.action_back)
        binding.btnGallerySecondary.setIconResource(R.drawable.ic_arrow_back)
        binding.btnGallerySecondary.setOnClickListener { showGalleryFolders(restoreScroll = true) }
        styleGalleryBackButton()
        binding.btnGalleryPrimary.visibility = View.VISIBLE
        binding.btnGalleryPrimary.text = getString(R.string.gallery_sort)
        binding.btnGalleryPrimary.setIconResource(R.drawable.ic_sort)
        binding.btnGalleryPrimary.setOnClickListener { showGallerySortMenu() }
        styleGalleryTrashButton(false)
        binding.tvSectionGallery.text = if (galleryCustomThumbnailMode) {
            getString(R.string.custom_thumbnail_pick_image)
        } else {
            getString(R.string.gallery_folder_title, bucketName)
        }
        binding.listGallery.visibility = View.VISIBLE
        binding.listGallery.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
            itemAnimator = null
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val photos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGalleryPhotosFromMediaStore(bucketId) }
            if (!isCurrentGalleryRender(renderGeneration) || galleryTrashMode || currentGalleryBucketId != bucketId) return@launch
            currentGalleryPhotos = photos
            binding.listGallery.adapter = GalleryAdapter(
                items = photos,
                grid = true,
                trashMode = false,
                onClick = { photo ->
                    when {
                        galleryCustomThumbnailMode -> applyCustomThumbnailFromGallery(photo)
                        gallerySelectionMode -> toggleGallerySelection(photo)
                        else -> openGalleryPhoto(photo)
                    }
                },
                onLongClick = { photo -> if (!galleryCustomThumbnailMode) enterGallerySelection(photo) },
                onMore = { photo, anchor -> showGalleryPhotoMenu(photo, anchor) }
            )
            if (currentGalleryMediaType == GalleryMediaType.PHOTO) {
                precacheGalleryPhotoThumbnails(photos.map { it.path }, renderGeneration, maxSize = 360)
            }
            updateGalleryEmptyState(photos.isEmpty(), getString(R.string.gallery_empty_photos))
        }
    }

    private fun loadGalleryFoldersFromMediaStore(): List<MediaItem> {
        val resolver = requireContext().contentResolver
        val contentUri = galleryContentUri
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media._ID,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        )
        val folders = linkedMapOf<String, MutableGalleryFolder>()
        val queryArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_MODIFIED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
        } else null
        val cursorResult = if (queryArgs != null) {
            resolver.query(contentUri, projection, queryArgs, null)
        } else {
            resolver.query(contentUri, projection, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")
        }
        cursorResult?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val folderPathCol = cursor.getColumnIndexOrThrow(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdCol) ?: continue
                val bucketName = cursor.getString(bucketNameCol)?.takeIf { it.isNotBlank() } ?: getString(R.string.gallery_unknown_folder)
                val date = cursor.getLong(dateCol)
                val imageId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val imageUri = android.content.ContentUris.withAppendedId(contentUri, imageId).toString()
                val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getString(folderPathCol)?.takeIf { it.isNotBlank() } ?: "${galleryBaseDirectory}/$bucketName/"
                } else {
                    File(cursor.getString(folderPathCol).orEmpty()).parentFile?.absolutePath.orEmpty()
                }
                val folder = folders.getOrPut(bucketId) { MutableGalleryFolder(bucketId, bucketName, relativePath = relativePath) }
                folder.count += 1
                if (folder.previewUris.size < 4) folder.previewUris += imageUri
                if (date > folder.lastModified) folder.lastModified = date
            }
        }
        getCreatedGalleryFolders().forEach { relativePath ->
            val name = relativePath.trimEnd('/').substringAfterLast('/').ifBlank { getString(R.string.gallery_unknown_folder) }
            if (folders.values.none { it.relativePath == relativePath }) {
                folders["created_$relativePath"] = MutableGalleryFolder(
                    bucketId = "relative:$relativePath",
                    name = name,
                    relativePath = relativePath
                )
            }
        }
        val result = folders.values.map {
            MediaItem(
                id = "gallery_folder_${it.bucketId}",
                name = it.name,
                path = it.bucketId,
                size = it.count.toLong(),
                lastPlayedAt = it.lastModified,
                mimeType = "folder",
                resolution = it.relativePath,
                previewUris = it.previewUris
            )
        }
        // Accueil Blaze Gallery : les dossiers restent toujours classés par nom.
        // Le tri date/nom/taille est uniquement appliqué à l'intérieur d'un dossier photo.
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun loadGalleryPhotosFromMediaStore(bucketId: String): List<MediaItem> {
        val resolver = requireContext().contentResolver
        val contentUri = galleryContentUri
        val relativePathFilter = bucketId.removePrefix("relative:").takeIf { bucketId.startsWith("relative:") }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        )
        val photos = mutableListOf<MediaItem>()
        val cursorResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = android.os.Bundle().apply {
                val selectionColumn = if (relativePathFilter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.BUCKET_ID
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "$selectionColumn = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePathFilter ?: bucketId))
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_MODIFIED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            resolver.query(contentUri, projection, args, null)
        } else {
            resolver.query(
                contentUri,
                projection,
                if (relativePathFilter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${MediaStore.Images.Media.RELATIVE_PATH} = ?" else "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(relativePathFilter ?: bucketId),
                null
            )
        }
        cursorResult?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val photoPathCol = cursor.getColumnIndexOrThrow(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(contentUri, id)
                val name = cursor.getString(nameCol) ?: "$id"
                val mime = cursor.getString(mimeCol) ?: galleryDefaultMimeType
                photos += MediaItem(
                    id = "gallery_photo_$id",
                    name = name,
                    path = uri.toString(),
                    size = cursor.getLong(sizeCol),
                    lastPlayedAt = cursor.getLong(dateCol),
                    extension = name.substringAfterLast('.', "").uppercase(),
                    mimeType = mime,
                    resolution = cursor.getString(photoPathCol).orEmpty()
                )
            }
        }
        return when (gallerySort) {
            GallerySort.DATE_ASC -> photos.sortedBy { it.lastPlayedAt }
            GallerySort.PHOTO_NAME, GallerySort.FOLDER_NAME -> photos.sortedBy { it.name.lowercase() }
            GallerySort.FILE_SIZE -> photos.sortedByDescending { it.size }
            else -> photos.sortedByDescending { it.lastPlayedAt }
        }
    }

    private data class MutableGalleryFolder(
        val bucketId: String,
        val name: String,
        var count: Int = 0,
        var lastModified: Long = 0L,
        var previewUris: List<String> = emptyList(),
        var relativePath: String = ""
    )

    private fun openGalleryPhoto(photo: MediaItem) {
        if (currentGalleryMediaType == GalleryMediaType.VIDEO) {
            fr.retrospare.blazeplayer.player.PlayerRouter.open(requireContext(), photo.path, photo.name)
        } else {
            showInAppPhotoViewer(photo)
        }
    }

    private fun showInAppPhotoViewer(photo: MediaItem) {
        val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = android.widget.FrameLayout(requireContext()).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            fitsSystemWindows = true
        }
        val previousOrientation = requireActivity().requestedOrientation
        requireActivity().requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        val image = fr.retrospare.blazeplayer.ui.ZoomableImageView(requireContext()).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            contentDescription = photo.name
        }
        val close = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ContextCompat.getColor(requireContext(), R.color.on_surface))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_icon_gray)
            contentDescription = getString(R.string.action_close)
            setOnClickListener { dialog.dismiss() }
            val size = (48 * resources.displayMetrics.density).toInt()
            val margin = (18 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                setMargins(margin, margin, margin, margin)
            }
        }
        root.addView(image)
        root.addView(close)
        // Bouton "Modifier" en overlay, accès direct à l'éditeur sans repasser par le menu "...".
        // Masqué pour les GIF : l'éditeur travaille sur une image statique et aplatirait
        // silencieusement l'animation sur sa première frame, ce qui surprendrait l'utilisateur.
        if (!isGifUri(photo.path)) {
            val editButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = getString(R.string.gallery_menu_edit)
                textSize = 13f
                isAllCaps = false
                includeFontPadding = false
                setTextColor(android.graphics.Color.WHITE)
                iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_tune)
                iconSize = (16 * resources.displayMetrics.density).toInt()
                iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#66000000"))
                cornerRadius = (18 * resources.displayMetrics.density).toInt()
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(), 0,
                    (14 * resources.displayMetrics.density).toInt(), 0
                )
                setOnClickListener { dialog.dismiss(); openPhotoEditor(photo) }
                val margin = (18 * resources.displayMetrics.density).toInt()
                val height = (36 * resources.displayMetrics.density).toInt()
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, height, android.view.Gravity.TOP or android.view.Gravity.START
                ).apply {
                    setMargins(margin, margin, margin, margin)
                }
            }
            root.addView(editButton)
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (isAdded) requireActivity().requestedOrientation = previousOrientation
        }
        dialog.setOnShowListener {
            loadGalleryImage(image, photo.path, fullScreenPhotoSize(), isFullScreen = true)
        }
        dialog.show()
    }

    /** Taille sûre pour l'affichage plein écran d'une photo : plafonnée à la résolution réelle de
     *  l'écran (carrée, en prenant le plus grand des deux côtés, pour rester valable après une
     *  rotation puisque le visionneur autorise le FULL_SENSOR). Remplace Size.ORIGINAL, qui
     *  demandait à Coil de décoder la photo à sa résolution native — avec les capteurs 48/108MP
     *  courants aujourd'hui, ça produisait un bitmap plus grand que la taille de texture max du
     *  GPU, provoquant un crash immédiat ("Canvas: trying to draw too large bitmap") à l'ouverture
     *  de la moindre photo un peu grande. L'écran ne peut de toute façon jamais afficher plus de
     *  détail que son propre nombre de pixels, donc ce plafond ne coûte aucune netteté visible.
     */
    private fun fullScreenPhotoSize(): Size {
        val metrics = resources.displayMetrics
        val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1080)
        return Size(maxDim, maxDim)
    }

    private fun loadGalleryImage(imageView: ImageView, uriString: String, requestSize: Size = Size(360, 360), isFullScreen: Boolean = false, thumbnailMaxSize: Int = 360) {
        imageView.setTag(R.id.ivThumbnail, uriString)
        imageView.setImageDrawable(null)
        if (currentGalleryMediaType == GalleryMediaType.VIDEO) {
            // Les vignettes vidéo passent par le même extracteur de frame (+ cache mémoire/disque)
            // que le reste de l'app (navigateur local/réseau), plutôt que par Coil qui ne décode
            // pas les conteneurs vidéo.
            viewLifecycleOwner.lifecycleScope.launch {
                ThumbnailUtils.loadThumbnail(requireContext(), uriString, imageView)
            }
            return
        }
        if (isFullScreen) {
            // Plein écran uniquement (pas les vignettes de grille, où animer N GIF à la fois en
            // RecyclerView coûterait cher) : si c'est un GIF, on le décode nous-mêmes en
            // AnimatedImageDrawable pour une vraie lecture animée — Coil n'affiche que la première
            // frame sans la dépendance coil-gif, absente du projet. ImageDecoder est natif Android
            // (API 28+, exactement le minSdk de l'app), aucune dépendance supplémentaire requise.
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val isGif = isGifUri(uriString)
                if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val source = android.graphics.ImageDecoder.createSource(requireContext().contentResolver, android.net.Uri.parse(uriString))
                        val maxDim = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                        val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                            // Même précaution que pour les photos statiques : un GIF plus grand
                            // que l'écran (rare mais possible) pourrait sinon dépasser la taille de
                            // texture max du GPU au rendu.
                            val srcW = info.size.width; val srcH = info.size.height
                            if (srcW > maxDim || srcH > maxDim) {
                                val sample = maxOf(srcW / maxDim, srcH / maxDim).coerceAtLeast(1)
                                decoder.setTargetSampleSize(sample)
                            }
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            imageView.setImageDrawable(drawable)
                            (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                        }
                        return@launch
                    } catch (_: Exception) {
                        // Décodage animé impossible (format non supporté, etc.) : repli statique.
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isAdded) loadStaticGalleryImage(imageView, uriString, requestSize, allowHardware = true)
                }
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = ThumbnailUtils.loadImageThumbnail(requireContext(), uriString, imageView, thumbnailMaxSize)
            if (!loaded && isAdded && imageView.getTag(R.id.ivThumbnail) == uriString) {
                // Filet de sécurité : si un fournisseur MediaStore/SAF refuse le décodage direct,
                // Coil garde l'ancien comportement d'affichage au lieu de laisser une case vide.
                loadStaticGalleryImage(imageView, uriString, requestSize)
            }
        }
    }

    private fun precacheGalleryPhotoThumbnails(
        paths: List<String>,
        renderGeneration: Long,
        maxSize: Int,
        initialDelayMs: Long = 250L
    ) {
        if (paths.isEmpty()) return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            if (initialDelayMs > 0L) kotlinx.coroutines.delay(initialDelayMs)
            paths.distinct().forEachIndexed { index, path ->
                if (renderGeneration != galleryRenderGeneration) return@launch
                ThumbnailUtils.getImageThumbnailBitmap(appContext, path, maxSize)
                // Laisse respirer le thread UI/GPU pendant un scroll rapide : le pré-cache doit
                // améliorer la prochaine ouverture, pas rendre l'ouverture actuelle saccadée.
                if (index % 4 == 3) kotlinx.coroutines.delay(24L)
            }
        }
    }

    private fun isGifUri(uriString: String): Boolean {
        if (uriString.endsWith(".gif", ignoreCase = true)) return true
        return try {
            if (uriString.startsWith("content://")) {
                requireContext().contentResolver.getType(android.net.Uri.parse(uriString)) == "image/gif"
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /** [allowHardware] doit rester à false pour les vignettes de grille (nécessaire ailleurs dans
     *  le code pour la génération de palette/traitement pixel), mais peut passer à true pour la
     *  visionneuse plein écran : là, l'image ne sert qu'à être affichée/zoomée, jamais lue pixel
     *  par pixel. Un bitmap matériel (GPU) évite le ré-upload CPU->GPU à chaque frame pendant un
     *  pincement de zoom sur une grande image, ce qui est précisément ce qui causait les saccades. */
    private fun loadStaticGalleryImage(imageView: ImageView, uriString: String, requestSize: Size, allowHardware: Boolean = false) {
        val request = ImageRequest.Builder(requireContext())
            .data(android.net.Uri.parse(uriString))
            .target(imageView)
            .size(requestSize)
            .scale(Scale.FILL)
            .allowHardware(allowHardware)
            .crossfade(false)
            .build()
        requireContext().imageLoader.enqueue(request)
    }

    private fun showGalleryFolderMenu(folder: MediaItem, anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.gallery_delete_folder))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    confirmGalleryDeletion(getString(R.string.confirm_delete_folder_message)) { moveGalleryFolderToTrash(folder) }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }


    private fun getCreatedGalleryFolders(): MutableSet<String> {
        return requireContext()
            .getSharedPreferences("blaze_gallery", android.content.Context.MODE_PRIVATE)
            .getStringSet(createdGalleryFoldersPrefKey(), emptySet())
            ?.toMutableSet() ?: mutableSetOf()
    }

    private fun rememberCreatedGalleryFolder(relativePath: String) {
        val folders = getCreatedGalleryFolders()
        folders += relativePath
        requireContext()
            .getSharedPreferences("blaze_gallery", android.content.Context.MODE_PRIVATE)
            .edit()
            .putStringSet(createdGalleryFoldersPrefKey(), folders)
            .apply()
    }

    private fun showCreateGalleryFolderDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.gallery_create_folder_hint)
            isSingleLine = true
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gallery_create_folder_title))
            .setView(input)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.gallery_create_folder)) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) createGalleryFolder(name)
            }
            .showPremium()
    }

    private fun createGalleryFolder(folderName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val safeName = folderName.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { return@withContext false }
                    val baseDir = galleryBaseDirectory
                    val relativePath = "${baseDir}/$safeName/"
                    val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), safeName)
                    val created = dir.mkdirs() || dir.isDirectory
                    rememberCreatedGalleryFolder(relativePath)
                    created || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                } catch (_: Exception) {
                    false
                }
            }
            android.widget.Toast.makeText(
                requireContext(),
                getString(if (ok) R.string.gallery_folder_created else R.string.gallery_folder_create_error),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            showGalleryFolders()
        }
    }

    private fun showMoveGalleryPhotoDialog(photo: MediaItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGalleryFoldersFromMediaStore() }
                .filter { it.path != currentGalleryBucketId && !it.resolution.isNullOrBlank() }
            if (folders.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), getString(R.string.gallery_photo_move_error), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = folders.map { it.name }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.gallery_move_photo_title))
                .setItems(names) { _, which -> moveGalleryPhotoToFolder(photo, folders[which]) }
                .showPremium()
        }
    }

    private fun moveGalleryPhotoToFolder(photo: MediaItem, folder: MediaItem) {
        val photoUri = photo.path.toUriOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingGallerySystemActionRefresh = {
                val ok = updateGalleryPhotoFolder(photo, folder)
                android.widget.Toast.makeText(requireContext(), getString(if (ok) R.string.gallery_photo_moved else R.string.gallery_photo_move_error), android.widget.Toast.LENGTH_SHORT).show()
                currentGalleryBucketId?.let { showGalleryPhotos(it, currentGalleryBucketName.orEmpty()) } ?: showGalleryFolders()
            }
            val request = MediaStore.createWriteRequest(requireContext().contentResolver, listOf(photoUri))
            gallerySystemActionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            val ok = updateGalleryPhotoFolder(photo, folder)
            android.widget.Toast.makeText(requireContext(), getString(if (ok) R.string.gallery_photo_moved else R.string.gallery_photo_move_error), android.widget.Toast.LENGTH_SHORT).show()
            currentGalleryBucketId?.let { showGalleryPhotos(it, currentGalleryBucketName.orEmpty()) } ?: showGalleryFolders()
        }
    }

    private fun updateGalleryPhotoFolder(photo: MediaItem, folder: MediaItem): Boolean {
        return try {
            val resolver = requireContext().contentResolver
            val photoUri = photo.path.toUriOrNull() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val targetRelativePath = folder.resolution?.takeIf { it.isNotBlank() } ?: return false
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.RELATIVE_PATH, targetRelativePath)
                }
                resolver.update(photoUri, values, null, null) > 0
            } else {
                val sourcePath = photo.resolution?.takeIf { it.isNotBlank() } ?: return false
                val targetDir = File(folder.resolution.orEmpty())
                if (!targetDir.exists()) targetDir.mkdirs()
                val source = File(sourcePath)
                val target = File(targetDir, source.name)
                val moved = source.renameTo(target)
                if (moved) {
                    resolver.delete(photoUri, null, null)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DATA, target.absolutePath)
                        put(MediaStore.Images.Media.DISPLAY_NAME, target.name)
                        put(MediaStore.Images.Media.MIME_TYPE, photo.mimeType.ifBlank { galleryDefaultMimeType })
                    }
                    resolver.insert(galleryContentUri, values)
                }
                moved
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun startCustomThumbnailSelection(item: MediaItem, sourceTab: Int = currentTabIndex) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(3)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            pendingCustomThumbnailVideo = item
            returnTabAfterCustomThumbnail = sourceTab.takeIf { it == 1 || it == 2 } ?: 1
            galleryCustomThumbnailMode = true
            // La miniature personnalisée doit toujours venir d'une photo, quel que soit le mode
            // affiché précédemment dans Blaze Gallery.
            currentGalleryMediaType = GalleryMediaType.PHOTO
            refreshGalleryTypeToggleColors()
            clearGallerySelection()
            currentTabIndex = 3
            updateTabStyles(3)
            hideAudioTab()
            // Affiche réellement l'onglet Blaze Gallery avant de charger les dossiers : sans
            // ce passage par updateSectionTitles(3), la liste de galerie pouvait être préparée
            // alors que la section Local/Réseau restait visible, ce qui donnait l'impression que
            // l'action ne menait pas directement à Blaze Gallery.
            updateSectionTitles(3)
            android.widget.Toast.makeText(requireContext(), getString(R.string.custom_thumbnail_pick_toast), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun applyCustomThumbnailFromGallery(photo: MediaItem) {
        val target = pendingCustomThumbnailVideo ?: return
        val ok = fr.retrospare.blazeplayer.ui.ThumbnailUtils.setCustomVideoThumbnail(requireContext(), target.path, Uri.parse(photo.path))
        android.widget.Toast.makeText(
            requireContext(),
            getString(if (ok) R.string.custom_thumbnail_saved else R.string.custom_thumbnail_error),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        finishCustomThumbnailSelection(refreshTarget = true)
    }

    private fun cancelCustomThumbnailSelection() {
        finishCustomThumbnailSelection(refreshTarget = false)
    }

    private fun finishCustomThumbnailSelection(refreshTarget: Boolean) {
        val targetTab = returnTabAfterCustomThumbnail
        galleryCustomThumbnailMode = false
        pendingCustomThumbnailVideo = null
        currentGalleryBucketId = null
        currentGalleryBucketName = null
        currentTabIndex = targetTab
        updateTabStyles(targetTab)
        hideAudioTab()
        viewModel.onTabSelected(targetTab)
        updateSectionTitles(targetTab)
        if (refreshTarget) {
            binding.listLocal.adapter?.notifyDataSetChanged()
            binding.listNetwork.adapter?.notifyDataSetChanged()
        }
    }

    private fun deleteCustomThumbnail(item: MediaItem) {
        fr.retrospare.blazeplayer.ui.ThumbnailUtils.deleteCustomVideoThumbnail(requireContext(), item.path)
        android.widget.Toast.makeText(requireContext(), getString(R.string.custom_thumbnail_deleted), android.widget.Toast.LENGTH_SHORT).show()
        binding.listLocal.adapter?.notifyDataSetChanged()
        binding.listNetwork.adapter?.notifyDataSetChanged()
        viewModel.onTabSelected(currentTabIndex)
    }

    private fun showGalleryPhotoMenu(photo: MediaItem, anchor: View) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        if (galleryTrashMode) {
            popup.menu.add(0, 1, 0, getString(R.string.gallery_restore))
            popup.menu.add(0, 2, 1, getString(R.string.gallery_delete_permanently_from_trash))
        } else {
            popup.menu.add(0, 1, 0, getString(R.string.action_open))
            if (currentGalleryMediaType == GalleryMediaType.VIDEO) {
                popup.menu.add(0, 5, 1, getString(R.string.gallery_menu_cut))
            } else {
                popup.menu.add(0, 5, 1, getString(R.string.gallery_menu_edit))
                popup.menu.add(0, 6, 2, getString(R.string.action_information))
            }
            popup.menu.add(0, 2, 3, getString(R.string.action_share))
            popup.menu.add(0, 4, 4, getString(R.string.gallery_move_to_folder))
            popup.menu.add(0, 3, 5, getString(R.string.gallery_delete))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (galleryTrashMode) restoreGalleryPhoto(photo) else openGalleryPhoto(photo)
                    true
                }
                2 -> {
                    if (galleryTrashMode) confirmGalleryDeletion(getString(R.string.confirm_permanent_delete_message)) { permanentlyDeleteGalleryPhoto(photo) } else shareGalleryPhoto(photo)
                    true
                }
                3 -> { confirmGalleryDeletion(getString(R.string.confirm_delete_photo_message)) { moveGalleryPhotoToTrash(photo) }; true }
                4 -> { showMoveGalleryPhotoDialog(photo); true }
                5 -> {
                    if (currentGalleryMediaType == GalleryMediaType.VIDEO) openVideoTrimEditor(photo) else openPhotoEditor(photo)
                    true
                }
                6 -> { fr.retrospare.blazeplayer.gallery.PhotoDetailsDialog.show(requireContext(), photo.path); true }
                else -> false
            }
        }
        popup.show()
    }

    /** Ouvre l'éditeur photo (filtres/recadrage/rotation) façon Google Photos sur la photo
     *  sélectionnée. Le résultat est toujours enregistré comme un nouveau fichier — jamais
     *  d'écrasement de l'original. L'utilisateur choisit lui-même quand quitter (croix), et reste
     *  ensuite dans le même dossier qu'avant l'édition — d'où [suppressGalleryResetOnResume]. */
    private fun openPhotoEditor(photo: MediaItem) {
        val intent = android.content.Intent(requireContext(), fr.retrospare.blazeplayer.gallery.edit.PhotoEditorActivity::class.java).apply {
            putExtra(fr.retrospare.blazeplayer.gallery.edit.PhotoEditorActivity.EXTRA_PHOTO_PATH, photo.path)
            putExtra(fr.retrospare.blazeplayer.gallery.edit.PhotoEditorActivity.EXTRA_PHOTO_NAME, photo.name)
        }
        suppressGalleryResetOnResume = true
        photoEditorLauncher.launch(intent)
    }

    /** Ouvre l'écran de découpe vidéo (barre de sélection + export rapide/précis/GIF). Au retour,
     *  [videoTrimLauncher] navigue explicitement vers le dossier où le résultat a été enregistré. */
    private fun openVideoTrimEditor(video: MediaItem) {
        val intent = android.content.Intent(requireContext(), fr.retrospare.blazeplayer.gallery.edit.VideoTrimActivity::class.java).apply {
            putExtra(fr.retrospare.blazeplayer.gallery.edit.VideoTrimActivity.EXTRA_VIDEO_PATH, video.path)
            putExtra(fr.retrospare.blazeplayer.gallery.edit.VideoTrimActivity.EXTRA_VIDEO_NAME, video.name)
        }
        suppressGalleryResetOnResume = true
        videoTrimLauncher.launch(intent)
    }

    private fun shareGalleryPhoto(photo: MediaItem) {
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = photo.mimeType.ifBlank { galleryDefaultMimeType }
            putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(photo.path))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, getString(R.string.action_share)))
    }

    private fun getGalleryTrashSet(): MutableSet<String> {
        return requireContext()
            .getSharedPreferences("blaze_gallery", android.content.Context.MODE_PRIVATE)
            .getStringSet("trash_uris", emptySet())
            ?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveGalleryTrashSet(values: Set<String>) {
        requireContext()
            .getSharedPreferences("blaze_gallery", android.content.Context.MODE_PRIVATE)
            .edit()
            .putStringSet("trash_uris", values)
            .apply()
    }

    private fun moveGalleryFolderToTrash(folder: MediaItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val photos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGalleryPhotosFromMediaStore(folder.path) }
            moveGalleryPhotosToSystemTrash(photos) { showGalleryFolders() }
        }
    }

    private fun moveGalleryPhotoToTrash(photo: MediaItem) {
        moveGalleryPhotosToSystemTrash(listOf(photo)) {
            currentGalleryBucketId?.let { showGalleryPhotos(it, currentGalleryBucketName.orEmpty()) } ?: showGalleryFolders()
        }
    }

    private fun restoreGalleryPhoto(photo: MediaItem) {
        restoreGalleryPhotosFromSystemTrash(listOf(photo)) { showGalleryTrash() }
    }

    private fun permanentlyDeleteGalleryPhoto(photo: MediaItem) {
        deleteGalleryPhotosPermanently(listOf(photo)) { showGalleryTrash() }
    }

    private fun emptyGalleryTrashPermanently() {
        deleteGalleryPhotosPermanently(currentGalleryPhotos) { showGalleryTrash() }
    }

    private fun moveGalleryPhotosToSystemTrash(photos: List<MediaItem>, refresh: () -> Unit) {
        val uris = photos.mapNotNull { it.path.toUriOrNull() }
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suppressGalleryResetOnResume = true
            pendingGallerySystemActionRefresh = refresh
            val request = MediaStore.createTrashRequest(requireContext().contentResolver, uris, true)
            gallerySystemActionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            // Fallback pré-Android 11 : pas de corbeille système MediaStore disponible.
            val trash = getGalleryTrashSet()
            trash.addAll(uris.map { it.toString() })
            saveGalleryTrashSet(trash)
            refresh()
        }
    }

    private fun restoreGalleryPhotosFromSystemTrash(photos: List<MediaItem>, refresh: () -> Unit) {
        val uris = photos.mapNotNull { it.path.toUriOrNull() }
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suppressGalleryResetOnResume = true
            pendingGallerySystemActionRefresh = refresh
            val request = MediaStore.createTrashRequest(requireContext().contentResolver, uris, false)
            gallerySystemActionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            val trash = getGalleryTrashSet()
            uris.forEach { trash.remove(it.toString()) }
            saveGalleryTrashSet(trash)
            refresh()
        }
    }

    private fun deleteGalleryPhotosPermanently(photos: List<MediaItem>, refresh: () -> Unit) {
        val uris = photos.mapNotNull { it.path.toUriOrNull() }
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suppressGalleryResetOnResume = true
            pendingPermanentDeleteHistoryCleanup = photos
            pendingGallerySystemActionRefresh = refresh
            val request = MediaStore.createDeleteRequest(requireContext().contentResolver, uris)
            gallerySystemActionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            val deletedItems = photos.filter { item ->
                item.path.toUriOrNull()?.let { uri ->
                    try { requireContext().contentResolver.delete(uri, null, null) > 0 } catch (_: Exception) { false }
                } ?: false
            }
            if (deletedItems.isNotEmpty()) viewModel.removeFromHistory(deletedItems)
            val trash = getGalleryTrashSet()
            deletedItems.forEach { trash.remove(it.path) }
            saveGalleryTrashSet(trash)
            refresh()
        }
    }

    private fun String.toUriOrNull(): Uri? = try { Uri.parse(this) } catch (_: Exception) { null }

    private fun showGalleryTrash() {
        if (!isAdded || !requestGalleryPermissionIfNeeded()) return
        val renderGeneration = nextGalleryRenderGeneration()
        galleryTrashMode = true
        clearGallerySelection()
        currentGalleryBucketId = null
        currentGalleryBucketName = null
        updateGalleryTypeToggle(visible = false)
        binding.tvSectionGallery.text = getString(R.string.gallery_trash)
        binding.btnGallerySecondary.visibility = View.VISIBLE
        binding.btnGallerySecondary.text = getString(R.string.action_back)
        binding.btnGallerySecondary.setIconResource(R.drawable.ic_arrow_back)
        binding.btnGallerySecondary.setOnClickListener { showGalleryFolders() }
        binding.btnGalleryPrimary.visibility = View.VISIBLE
        binding.btnGalleryPrimary.text = getString(R.string.gallery_empty_trash)
        binding.btnGalleryPrimary.setIconResource(R.drawable.ic_trash)
        binding.btnGalleryPrimary.setOnClickListener { confirmGalleryDeletion(getString(R.string.confirm_empty_trash_message)) { emptyGalleryTrashPermanently() } }
        styleGalleryBackButton()
        styleGalleryTrashButton(true)
        binding.listGallery.visibility = View.VISIBLE
        binding.listGallery.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
            itemAnimator = null
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val photos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadTrashedGalleryPhotosFromMediaStore() }
            if (!isCurrentGalleryRender(renderGeneration) || !galleryTrashMode) return@launch
            currentGalleryPhotos = photos
            binding.listGallery.adapter = GalleryAdapter(
                items = photos,
                grid = true,
                trashMode = true,
                onClick = { photo -> openGalleryPhoto(photo) },
                onLongClick = {},
                onMore = { photo, anchor -> showGalleryPhotoMenu(photo, anchor) }
            )
            if (currentGalleryMediaType == GalleryMediaType.PHOTO) {
                precacheGalleryPhotoThumbnails(photos.map { it.path }, renderGeneration, maxSize = 360)
            }
            updateGalleryEmptyState(photos.isEmpty(), getString(R.string.gallery_trash_empty_message))
        }
    }

    private fun loadTrashedGalleryPhotosFromMediaStore(): List<MediaItem> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            loadAllGalleryPhotosFromMediaStore(includeOnlyTrashed = true)
        } else {
            val trash = getGalleryTrashSet()
            if (trash.isEmpty()) emptyList() else loadAllGalleryPhotosFromMediaStore(includeOnlyTrashed = false).filter { trash.contains(it.path) }
        }
    }

    private fun loadAllGalleryPhotosFromMediaStore(includeOnlyTrashed: Boolean = false): List<MediaItem> {
        val all = mutableListOf<MediaItem>()
        val resolver = requireContext().contentResolver
        val contentUri = galleryContentUri
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE
        )
        val cursorResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = android.os.Bundle().apply {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED,
                    if (includeOnlyTrashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
                )
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_MODIFIED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            resolver.query(contentUri, projection, args, null)
        } else {
            resolver.query(contentUri, projection, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")
        }
        cursorResult?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(contentUri, id)
                val name = cursor.getString(nameCol) ?: "$id"
                all += MediaItem(
                    id = "gallery_photo_$id",
                    name = name,
                    path = uri.toString(),
                    size = cursor.getLong(sizeCol),
                    lastPlayedAt = cursor.getLong(dateCol),
                    extension = name.substringAfterLast('.', "").uppercase(),
                    mimeType = cursor.getString(mimeCol) ?: galleryDefaultMimeType
                )
            }
        }
        return all
    }

    private fun enterGallerySelection(photo: MediaItem) {
        if (galleryTrashMode) return
        gallerySelectionMode = true
        selectedGalleryPhotos.clear()
        selectedGalleryPhotos.add(photo.path)
        updateGallerySelectionToolbar()
        binding.listGallery.adapter?.notifyDataSetChanged()
    }

    private fun toggleGallerySelection(photo: MediaItem) {
        if (!gallerySelectionMode) return
        if (!selectedGalleryPhotos.add(photo.path)) selectedGalleryPhotos.remove(photo.path)
        if (selectedGalleryPhotos.isEmpty()) clearGallerySelection() else updateGallerySelectionToolbar()
        binding.listGallery.adapter?.notifyDataSetChanged()
    }

    private fun clearGallerySelection() {
        gallerySelectionMode = false
        selectedGalleryPhotos.clear()
    }

    private fun updateGallerySelectionToolbar() {
        binding.btnGallerySecondary.visibility = View.VISIBLE
        binding.btnGallerySecondary.text = getString(R.string.gallery_delete_all)
        binding.btnGallerySecondary.setIconResource(R.drawable.ic_trash)
        binding.btnGallerySecondary.setOnClickListener { confirmGalleryDeletion(getString(R.string.confirm_delete_selected_message)) { deleteSelectedGalleryPhotos() } }
        binding.btnGalleryPrimary.visibility = View.VISIBLE
        binding.btnGalleryPrimary.text = getString(R.string.gallery_share_all)
        binding.btnGalleryPrimary.setIconResource(R.drawable.ic_share)
        binding.btnGalleryPrimary.setOnClickListener { shareSelectedGalleryPhotosAsZip() }
        styleGallerySecondaryNeutralButton()
        styleGalleryTrashButton(false)
        binding.tvSectionGallery.text = getString(R.string.gallery_selected_count, selectedGalleryPhotos.size)
    }

    private fun deleteSelectedGalleryPhotos() {
        val selected = currentGalleryPhotos.filter { selectedGalleryPhotos.contains(it.path) }
        clearGallerySelection()
        moveGalleryPhotosToSystemTrash(selected) {
            currentGalleryBucketId?.let { showGalleryPhotos(it, currentGalleryBucketName.orEmpty()) } ?: showGalleryFolders()
        }
    }

    private fun shareSelectedGalleryPhotosAsZip() {
        val selected = currentGalleryPhotos.filter { selectedGalleryPhotos.contains(it.path) }
            .ifEmpty { currentGalleryPhotos }
        if (selected.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val zipFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { createGalleryZip(selected) }
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", zipFile)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, getString(R.string.action_share)))
        }
    }

    private fun createGalleryZip(photos: List<MediaItem>): File {
        val dir = File(requireContext().cacheDir, "gallery_share").apply { mkdirs() }
        val zipFile = File(dir, "blaze_gallery_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            photos.forEachIndexed { index, photo ->
                val fallbackExt = if (currentGalleryMediaType == GalleryMediaType.VIDEO) "mp4" else "jpg"
                val name = photo.name.ifBlank { "media_${index + 1}.$fallbackExt" }
                requireContext().contentResolver.openInputStream(android.net.Uri.parse(photo.path))?.use { input ->
                    zip.putNextEntry(ZipEntry(name))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
        return zipFile
    }

    private inner class PhotoViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val cbSelected: android.widget.CheckBox = view.findViewById(R.id.cbSelected)
        val btnMore: View = view.findViewById(R.id.btnMore)
    }

    private inner class FolderViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        val tvFolderCount: TextView = view.findViewById(R.id.tvFolderCount)
        val previews: List<ImageView> = listOf(
            view.findViewById(R.id.ivPreview1),
            view.findViewById(R.id.ivPreview2),
            view.findViewById(R.id.ivPreview3),
            view.findViewById(R.id.ivPreview4)
        )
        val btnFolderMore: View = view.findViewById(R.id.btnFolderMore)
    }

    /** Adapter des grilles Blaze Gallery (dossiers et photos).
     *
     *  Les vues enfants sont mises en cache dans un vrai ViewHolder (PhotoViewHolder /
     *  FolderViewHolder) au lieu d'être recherchées via findViewById() à chaque bind : avant ce
     *  correctif, chaque recyclage de case pendant le scroll refaisait 5 à 7 parcours de l'arbre
     *  de vues, ce qui est la cause la plus commune de saccades dans une grille RecyclerView.
     *  Le redimensionnement carré des tuiles de dossier ne passe plus non plus par un
     *  `view.post { ... }` exécuté à chaque bind (cf. SquareRoundedFrameLayout), qui provoquait un
     *  saut visible de la tuile juste après son apparition à l'écran pendant le scroll. */
    private inner class GalleryAdapter(
        private val items: List<MediaItem>,
        private val grid: Boolean,
        private val trashMode: Boolean,
        private val onClick: (MediaItem) -> Unit,
        private val onLongClick: (MediaItem) -> Unit,
        private val onMore: (MediaItem, View) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemCount() = items.size

        override fun getItemId(position: Int): Long = items[position].path.hashCode().toLong()

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            return if (grid) {
                PhotoViewHolder(layoutInflater.inflate(R.layout.item_gallery_photo, parent, false))
            } else {
                FolderViewHolder(layoutInflater.inflate(R.layout.item_gallery_folder_tile, parent, false))
            }
        }

        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val view = holder.itemView
            if (holder is PhotoViewHolder) {
                holder.tvFileName.text = item.name
                holder.tvDuration.text = android.text.format.Formatter.formatShortFileSize(requireContext(), item.size)
                loadGalleryImage(holder.ivThumbnail, item.path)
                holder.cbSelected.visibility = if (gallerySelectionMode && !trashMode) View.VISIBLE else View.GONE
                holder.cbSelected.isChecked = selectedGalleryPhotos.contains(item.path)
                holder.cbSelected.setOnClickListener { toggleGallerySelection(item) }
                holder.btnMore.visibility = if (gallerySelectionMode && !trashMode) View.GONE else View.VISIBLE
                holder.btnMore.setOnClickListener { onMore(item, it) }
            } else if (holder is FolderViewHolder) {
                holder.tvFolderName.text = item.name
                holder.tvFolderCount.text = resources.getQuantityString(
                    if (currentGalleryMediaType == GalleryMediaType.VIDEO) R.plurals.gallery_video_count else R.plurals.gallery_photo_count,
                    item.size.toInt(),
                    item.size.toInt()
                )
                holder.previews.forEachIndexed { index, imageView ->
                    val uri = item.previewUris.getOrNull(index)
                    if (uri != null) {
                        imageView.visibility = View.VISIBLE
                        loadGalleryImage(imageView, uri, Size(220, 220), thumbnailMaxSize = 220)
                    } else {
                        imageView.setTag(R.id.ivThumbnail, null)
                        imageView.visibility = View.INVISIBLE
                        imageView.setImageDrawable(null)
                    }
                }
                holder.btnFolderMore.setOnClickListener { onMore(item, it) }
            }
            view.setOnClickListener { onClick(item) }
            view.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun onViewRecycled(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            when (holder) {
                is PhotoViewHolder -> {
                    holder.ivThumbnail.setTag(R.id.ivThumbnail, null)
                    holder.ivThumbnail.setImageDrawable(null)
                }
                is FolderViewHolder -> holder.previews.forEach { imageView ->
                    imageView.setTag(R.id.ivThumbnail, null)
                    imageView.setImageDrawable(null)
                }
            }
        }
    }

    private fun refreshGalleryDefaultContent() {
        showGalleryFolders()
    }

    private fun showAudioTab() {
        updateGalleryTypeToggle(false)
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
        switchToTab(4)
    }

    fun switchToTab(index: Int) {
        // Garde-fou : si la vue n'existe pas (fragment présent dans le back stack mais pas
        // affiché, ex. Réglages/Réseau au premier plan), viewLifecycleOwner planterait.
        if (_binding == null || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(index)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            currentTabIndex = index
            viewModel.onTabSelected(index)
            updateTabStyles(index)
            if (index == 4) {
                if (requestAudioPermissionsIfNeeded()) showAudioTab()
            } else {
                hideAudioTab()
                if (index == 2) requestNetworkPermissionsIfNeeded()
                updateSectionTitles(index)
                if (index == 2) showNetworkHelpOnce()
            }
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
        if (_binding == null || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(4)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            openAudioPlayerAfterAccess(path, name)
        }
    }

    private fun openAudioPlayerAfterAccess(path: String, name: String) {
        currentTabIndex = 4
        viewModel.onTabSelected(4)
        updateTabStyles(4)
        showAudioTab()

        val existing = audioPlayerFragment
            ?: (childFragmentManager.findFragmentByTag("blaze_audio") as? AudioPlayerFragment)
                ?.also { audioPlayerFragment = it }
        if (existing != null && existing.isAdded && existing.context != null) {
            existing.addTrack(path, name)
            return
        } else if (existing != null) {
            // Protection anti-crash : après certains retours depuis un intent Android externe,
            // FragmentManager peut encore retourner une ancienne instance taggée "blaze_audio"
            // alors qu'elle n'est plus attachée. Appeler addTrack() dessus déclenche
            // activityViewModels()/requireActivity(). On jette cette instance et on recrée un
            // lecteur propre avec le morceau en attente.
            audioPlayerFragment = null
            childFragmentManager.beginTransaction().remove(existing).commitAllowingStateLoss()
        }

        // Le fragment audio n'existe pas encore : on place le morceau en attente dans le
        // ViewModel partagé, puis AudioPlayerFragment le consommera dès que son MediaController
        // sera prêt. Cela évite de bloquer l'UI pendant l'ouverture depuis un gestionnaire de
        // fichiers et évite aussi de perdre le morceau.
        androidx.lifecycle.ViewModelProvider(requireActivity())[fr.retrospare.blazeplayer.home.SharedAudioViewModel::class.java]
            .addToPlaylist(path, name)
        audioPlayerFragment = AudioPlayerFragment()
        childFragmentManager.beginTransaction()
            .replace(fr.retrospare.blazeplayer.R.id.audioContainer, audioPlayerFragment!!, "blaze_audio")
            .commitAllowingStateLoss()
    }

    private fun updateSectionTitles(tabIndex: Int) {
        updateHeaderCastButtons(tabIndex)
        when (tabIndex) {
            1 -> {
                binding.sectionLocal.visibility = View.VISIBLE
                binding.sectionNetwork.visibility = View.GONE
                binding.sectionGallery.visibility = View.GONE
                binding.headerControlsLocal.visibility = View.VISIBLE
                binding.headerControlsNetwork.visibility = View.GONE
                binding.headerControlsGallery.visibility = View.GONE
                updateGalleryTypeToggle(false)
                viewModel.onTabSelected(1)
            }
            2 -> {
                // L'onglet Réseau est maintenant une vraie page intégrée à Home :
                // la configuration SMB/UPnP reste affichée sous la barre d'onglets,
                // sans repasser par une destination plein écran qui masquerait les tabs.
                binding.sectionLocal.visibility = View.GONE
                binding.sectionNetwork.visibility = View.VISIBLE
                binding.sectionGallery.visibility = View.GONE
                binding.headerControlsLocal.visibility = View.GONE
                binding.headerControlsNetwork.visibility = View.VISIBLE
                binding.headerControlsGallery.visibility = View.GONE
                updateGalleryTypeToggle(false)
                updateEmbeddedNetworkScanState(scanning = false)
                ensureEmbeddedNetworkSources()
            }
            3 -> {
                binding.sectionLocal.visibility = View.GONE
                binding.sectionNetwork.visibility = View.GONE
                binding.sectionGallery.visibility = View.VISIBLE
                binding.headerControlsLocal.visibility = View.GONE
                binding.headerControlsNetwork.visibility = View.GONE
                binding.headerControlsGallery.visibility = View.VISIBLE
                viewModel.onTabSelected(3)
                refreshGalleryDefaultContent()
            }
            else -> {
                binding.sectionLocal.visibility = View.VISIBLE
                binding.sectionNetwork.visibility = View.GONE
                binding.sectionGallery.visibility = View.GONE
                binding.headerControlsLocal.visibility = View.VISIBLE
                binding.headerControlsNetwork.visibility = View.GONE
                binding.headerControlsGallery.visibility = View.GONE
                updateGalleryTypeToggle(false)
                viewModel.onTabSelected(1)
            }
        }
        updateHistoryDeleteOverlay()
    }

    private fun updateTabStyles(selectedIndex: Int) {
        // selectedIndex: 1=Blaze Video, 2=Réseau raccourci sources, 3=Blaze Gallery, 4=Audio
        val tabViews = listOf(binding.tabLocal, binding.tabNetwork, binding.tabGallery, binding.tabAudio)
        val tabIcons = listOf(binding.tabLocalIcon, binding.tabNetworkIcon, binding.tabGalleryIcon, binding.tabAudioIcon)
        val tabTexts = listOf(binding.tabLocalText, binding.tabNetworkText, binding.tabGalleryText, binding.tabAudioText)

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
        binding.btnNetworkHeaderScan.setOnClickListener { requestEmbeddedNetworkScan() }
        binding.btnBrowseNetwork.setOnClickListener { openNetworkSourcesFromTab() }
        binding.btnEmptyStateBrowseNetwork.setOnClickListener { binding.btnBrowseNetwork.performClick() }
        binding.btnBrowseLocal.setOnClickListener {
            audioPlayerFragment?.savePlaylistFromController() ?: Unit
            findNavController().navigate(R.id.action_home_to_browser)
        }
        binding.btnEmptyStateBrowseLocal.setOnClickListener { binding.btnBrowseLocal.performClick() }
        binding.btnHistoryLocal.setOnClickListener { showHistoryActionsDialog(1) }
        binding.btnHistoryNetwork.setOnClickListener { openNetworkSourcesFromTab() }
        binding.btnFavoritesLocal.setOnClickListener {
            showBlazeVideoFavorites()
        }
        binding.btnFavoritesNetwork.setOnClickListener {
            runWithProAccess {
                fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showFavoritesList(
                    requireContext(), fr.retrospare.blazeplayer.favorites.FavoriteCategory.NETWORK
                ) { favorite ->
                    val shareId = favorite.shareId
                    if (shareId.isNullOrEmpty()) {
                        fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_error), getString(R.string.toast_share_not_found))
                        return@showFavoritesList
                    }
                    audioPlayerFragment?.savePlaylistFromController() ?: Unit
                    findNavController().navigate(
                        R.id.action_home_to_browser,
                        android.os.Bundle().apply {
                            putBoolean("isNetwork", true)
                            putString("shareId", shareId)
                            putString("path", favorite.path)
                        }
                    )
                }
            }
        }
        binding.root.findViewById<android.view.View>(fr.retrospare.blazeplayer.R.id.btnVideoQueueLocal)?.setOnClickListener {
            fr.retrospare.blazeplayer.player.VideoQueueSheet.show(
                requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO
            ) {
                setupPlaylistButtons()
                setupVideoQueueButtons()
            }
        }
        binding.root.findViewById<android.view.View>(fr.retrospare.blazeplayer.R.id.btnVideoQueueNetwork)?.setOnClickListener {
            runWithProAccess {
                fr.retrospare.blazeplayer.player.VideoQueueSheet.show(
                    requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO
                ) {
                    setupPlaylistButtons()
                    setupVideoQueueButtons()
                }
            }
        }
        setupPlaylistButtons()
        setupVideoQueueButtons()
    }

    private fun showBlazeVideoFavorites() {
        viewLifecycleOwner.lifecycleScope.launch {
            val hasPro = fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            val categories = if (hasPro) {
                listOf(
                    fr.retrospare.blazeplayer.favorites.FavoriteCategory.LOCAL,
                    fr.retrospare.blazeplayer.favorites.FavoriteCategory.NETWORK
                )
            } else {
                listOf(fr.retrospare.blazeplayer.favorites.FavoriteCategory.LOCAL)
            }
            fr.retrospare.blazeplayer.favorites.FavoriteDialogs.showFavoritesList(
                requireContext(), categories
            ) { category, favorite ->
                audioPlayerFragment?.savePlaylistFromController() ?: Unit
                if (category == fr.retrospare.blazeplayer.favorites.FavoriteCategory.NETWORK) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (!fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)) {
                            findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                            return@launch
                        }
                        val shareId = favorite.shareId
                        if (shareId.isNullOrEmpty()) {
                            fr.retrospare.blazeplayer.ui.InfoDialog.show(
                                requireContext(),
                                getString(R.string.info_dialog_title_error),
                                getString(R.string.toast_share_not_found)
                            )
                            return@launch
                        }
                        findNavController().navigate(
                            R.id.action_home_to_browser,
                            android.os.Bundle().apply {
                                putBoolean("isNetwork", true)
                                putString("shareId", shareId)
                                putString("path", favorite.path)
                            }
                        )
                    }
                } else {
                    findNavController().navigate(
                        R.id.action_home_to_browser,
                        android.os.Bundle().apply { putString("path", favorite.path) }
                    )
                }
            }
        }
    }

    /** Câble une puce de playlist numérotée : état visuel "non vide" indépendant de "dernière
     *  lue" (auparavant confondus : seule la dernière lue avait l'air "remplie", les autres
     *  playlists non vides étaient visuellement identiques à des slots vides), tap pour ouvrir,
     *  appui long pour un accès rapide à "Vider" sans repasser par le dialogue complet. */
    private fun bindPlaylistChip(btn: android.widget.TextView?, category: fr.retrospare.blazeplayer.playlist.PlaylistCategory, slot: Int, lastPlayed: Int) {
        btn ?: return
        val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager.getPlaylist(requireContext(), category, slot).isNotEmpty()
        btn.isSelected = hasItems
        btn.isActivated = hasItems && lastPlayed == slot
        btn.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    hasItems -> R.color.black
                    else -> R.color.on_surface_variant
                }
            )
        )
        btn.setOnClickListener {
            if (category == fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO) {
                runWithProAccess { openSavedPlaylist(category, slot) }
            } else {
                openSavedPlaylist(category, slot)
            }
        }
        btn.setOnLongClickListener {
            if (category == fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO) {
                runWithProAccess { showPlaylistQuickMenu(category, slot, btn) }
            } else {
                showPlaylistQuickMenu(category, slot, btn)
            }
            true
        }
    }

    /** Menu rapide d'une puce de playlist (appui long) : évite d'ouvrir le dialogue complet rien
     *  que pour vider une playlist, et donne un accès direct à "Ouvrir" même sur un slot vide. */
    private fun showPlaylistQuickMenu(category: fr.retrospare.blazeplayer.playlist.PlaylistCategory, slot: Int, anchor: View) {
        val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager.getPlaylist(requireContext(), category, slot).isNotEmpty()
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, getString(R.string.action_open))
        if (hasItems) popup.menu.add(0, 2, 1, getString(R.string.action_empty_playlist))
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { openSavedPlaylist(category, slot); true }
                2 -> {
                    fr.retrospare.blazeplayer.playlist.PlaylistManager.clearPlaylist(requireContext(), category, slot)
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_playlist_emptied, slot), android.widget.Toast.LENGTH_SHORT).show()
                    setupPlaylistButtons()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setupVideoQueueButtons() {
        val localBtn = binding.root.findViewById<android.view.View>(fr.retrospare.blazeplayer.R.id.btnVideoQueueLocal)
        val networkBtn = binding.root.findViewById<android.view.View>(fr.retrospare.blazeplayer.R.id.btnVideoQueueNetwork)
        val localHasItems = fr.retrospare.blazeplayer.player.VideoQueueManager
            .getQueue(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO).isNotEmpty()
        val networkHasItems = fr.retrospare.blazeplayer.player.VideoQueueManager
            .getQueue(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO).isNotEmpty()
        localBtn?.isSelected = localHasItems
        networkBtn?.isSelected = networkHasItems
        localBtn?.alpha = 1f
        networkBtn?.alpha = 1f
    }

    private fun setupPlaylistButtons() {
        val localButtons = listOf(
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal1),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal2),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal3),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal4),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistLocal5)
        )
        val networkButtons = listOf(
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork1),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork2),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork3),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork4),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistNetwork5)
        )
        val lastPlayedLocal = fr.retrospare.blazeplayer.playlist.PlaylistManager
            .getLastPlayed(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO)
        val lastPlayedNetwork = fr.retrospare.blazeplayer.playlist.PlaylistManager
            .getLastPlayed(requireContext(), fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO)
        localButtons.forEachIndexed { i, btn ->
            bindPlaylistChip(btn, fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO, i + 1, lastPlayedLocal)
        }
        networkButtons.forEachIndexed { i, btn ->
            bindPlaylistChip(btn, fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO, i + 1, lastPlayedNetwork)
        }
    }

    private fun openSavedPlaylist(category: fr.retrospare.blazeplayer.playlist.PlaylistCategory, slot: Int) {
        if (category == fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO &&
            !kotlinx.coroutines.runBlocking {
                fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            }
        ) {
            findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
            return
        }
        fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showPlaylistViewer(
            requireContext(), category, slot,
            onPlayAll = { tracks ->
                val ctx = requireContext()
                val orderedTracks = fr.retrospare.blazeplayer.playlist.PlaylistPlayOrder.sortedForPlayback(category, tracks)
                val added = fr.retrospare.blazeplayer.player.VideoQueueManager.addToQueue(ctx, category, orderedTracks)
                val already = (orderedTracks.size - added).coerceAtLeast(0)
                val msg = if (already == 0) {
                    getString(fr.retrospare.blazeplayer.R.string.toast_video_queue_added, added)
                } else {
                    getString(fr.retrospare.blazeplayer.R.string.toast_video_queue_added_partial, added, already)
                }
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                fr.retrospare.blazeplayer.playlist.PlaylistManager.setLastPlayed(ctx, category, slot)
                setupPlaylistButtons()
                setupVideoQueueButtons()
                fr.retrospare.blazeplayer.player.VideoQueueSheet.show(ctx, category)
            },
            onPlayOne = { track -> fr.retrospare.blazeplayer.player.PlayerRouter.open(requireContext(), track.path, track.name) },
            onChanged = {
                setupPlaylistButtons()
                setupVideoQueueButtons()
            }
        )
    }


    private fun containerBadgeFrom(item: MediaItem): String {
        fun clean(value: String): String {
            if (value.isBlank()) return ""
            val noQuery = value.substringBefore('?').substringBefore('#')
            val ext = noQuery.substringAfterLast('.', "")
                .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
                ?: ""
            return ext.uppercase()
        }
        val fromStored = clean(item.extension)
        if (fromStored.isNotEmpty()) return fromStored
        val fromName = clean(item.name)
        if (fromName.isNotEmpty()) return fromName
        val fromPath = clean(item.path)
        if (fromPath.isNotEmpty()) return fromPath
        return when {
            item.mimeType.contains("mp4", true) -> "MP4"
            item.mimeType.contains("matroska", true) || item.mimeType.contains("mkv", true) -> "MKV"
            item.mimeType.contains("avi", true) -> "AVI"
            item.mimeType.contains("webm", true) -> "WEBM"
            else -> ""
        }
    }

    private fun isNetworkHistorySource(item: MediaItem): Boolean = when {
        item.path.startsWith("smb://", ignoreCase = true) -> true
        item.networkShareId?.startsWith("upnp_", ignoreCase = true) == true -> true
        item.path.startsWith("http://", ignoreCase = true) -> item.isNetwork
        item.path.startsWith("https://", ignoreCase = true) -> item.isNetwork
        else -> item.isNetwork
    }

    private fun bindHistorySourceIcon(view: ImageView, item: MediaItem) {
        val isNetwork = isNetworkHistorySource(item)
        view.setImageResource(if (isNetwork) R.drawable.ic_network else R.drawable.ic_sd_card)
        view.contentDescription = getString(if (isNetwork) R.string.tab_network else R.string.local_storage)
    }

    private fun historyItemsForTab(tab: Int): List<MediaItem> = latestLocalHistoryItems

    private fun historyTitleForTab(tab: Int): String = getString(R.string.history_blaze_video)

    private fun showHistoryActionsDialog(tab: Int) {
        val items = historyItemsForTab(tab)
        if (items.isEmpty()) {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(
                requireContext(),
                historyTitleForTab(tab),
                getString(R.string.history_empty_blaze_video_subtitle),
                R.drawable.ic_history
            )
            return
        }

        fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
        val dialog = android.app.Dialog(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            setBackgroundResource(R.drawable.bg_cast_status_card)
        }

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val iconWrap = android.widget.FrameLayout(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_cast_icon_circle)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        iconWrap.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_history)
            setColorFilter(android.graphics.Color.WHITE)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(24), dp(24), android.view.Gravity.CENTER)
        })
        header.addView(iconWrap)
        header.addView(TextView(requireContext()).apply {
            text = historyTitleForTab(tab)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
        })
        header.addView(ImageButton(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_top_icon_btn)
            setImageResource(R.drawable.ic_close)
            setColorFilter(android.graphics.Color.WHITE)
            contentDescription = getString(R.string.action_close)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        root.addView(View(requireContext()).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#1FFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
        })

        val selectedCount = if (historySelectionTab == tab) selectedHistoryPaths.size else 0
        root.addView(TextView(requireContext()).apply {
            text = if (historySelectionTab == tab) {
                getString(R.string.history_selection_active, selectedCount)
            } else {
                getString(R.string.history_toast_select_thumbnails)
            }
            setTextColor(android.graphics.Color.parseColor("#CCFFFFFF"))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        })

        fun addActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
            root.addView(TextView(requireContext()).apply {
                text = label
                setTextColor(ContextCompat.getColor(requireContext(), R.color.green_accent))
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                setPadding(dp(18), 0, dp(18), 0)
                alpha = if (enabled) 1f else 0.45f
                isEnabled = enabled
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
                    topMargin = dp(8)
                }
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            })
        }

        // Le dialogue ne supprime plus rien : il sert uniquement à passer en mode sélection.
        // La validation destructive se fait ensuite via le bouton rouge en overlay sur la grille,
        // ce qui évite les suppressions trop rapides depuis un modal fermé.
        addActionButton(getString(R.string.history_select_thumbnails)) { enterHistorySelection(tab) }
        if (historySelectionTab == tab) {
            addActionButton(getString(R.string.history_finish_selection)) { clearHistorySelection() }
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun confirmHistoryDeletion(message: String, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(message)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.action_confirm_delete)) { _, _ -> onConfirm() }
            .showPremium()
    }

    private fun ensureHistoryDeleteOverlayButton(): com.google.android.material.button.MaterialButton? {
        if (_binding == null || !isAdded) return null
        return binding.btnHistoryDeleteOverlay.apply {
            setOnClickListener { deleteSelectedHistoryFromOverlay() }
        }
    }

    private fun updateHistoryDeleteOverlay() {
        if (_binding == null || !isAdded) return
        val activeOnBlazeVideo = currentTabIndex == 1 && historySelectionTab == 1
        val targetBottomPadding = dp(if (activeOnBlazeVideo) 88 else 24)
        if (binding.listLocal.paddingBottom != targetBottomPadding) {
            binding.listLocal.setPadding(
                binding.listLocal.paddingLeft,
                binding.listLocal.paddingTop,
                binding.listLocal.paddingRight,
                targetBottomPadding
            )
        }
        val button = ensureHistoryDeleteOverlayButton() ?: return
        if (!activeOnBlazeVideo) {
            button.visibility = View.GONE
            return
        }
        val selectedCount = selectedHistoryPaths.size
        button.text = if (selectedCount > 0) {
            getString(R.string.history_delete_selection, selectedCount)
        } else {
            getString(R.string.action_delete)
        }
        button.isEnabled = selectedCount > 0
        button.alpha = if (selectedCount > 0) 1f else 0.55f
        button.visibility = View.VISIBLE
        button.bringToFront()
    }

    private fun deleteSelectedHistoryFromOverlay() {
        val selected = historyItemsForTab(1).filter { selectedHistoryPaths.contains(it.path) }
        if (selected.isEmpty()) return
        confirmHistoryDeletion(
            getString(R.string.history_confirm_delete_items, selected.size),
            onConfirm = {
                viewModel.removeFromHistory(selected)
                clearHistorySelection()
            }
        )
    }

    private fun enterHistorySelection(tab: Int) {
        historySelectionTab = tab
        selectedHistoryPaths.clear()
        refreshHistorySelectionAdapters()
        updateHistoryDeleteOverlay()
        android.widget.Toast.makeText(requireContext(), getString(R.string.history_toast_select_thumbnails), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toggleHistorySelection(item: MediaItem) {
        if (historySelectionTab == null) return
        if (!selectedHistoryPaths.add(item.path)) selectedHistoryPaths.remove(item.path)
        refreshHistorySelectionAdapters()
        updateHistoryDeleteOverlay()
    }

    private fun clearHistorySelection() {
        historySelectionTab = null
        selectedHistoryPaths.clear()
        refreshHistorySelectionAdapters()
        updateHistoryDeleteOverlay()
    }

    private fun refreshHistorySelectionAdapters() {
        if (_binding == null) return
        binding.listLocal.adapter?.notifyDataSetChanged()
        binding.listNetwork.adapter?.notifyDataSetChanged()
    }

    private fun refreshAccessibleVideoHistory(items: List<MediaItem> = allVideoHistoryItems) {
        if (_binding == null || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val hasPro = fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            val visible = if (hasPro) items else items.filterNot { it.isNetwork ||
                fr.retrospare.blazeplayer.paywall.FeatureAccess.isNetworkMediaPath(it.path) }
            latestLocalHistoryItems = visible
            if (_binding != null) updateRecycler(binding.listLocal, visible)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recentNetworkItems.collect { items ->
                        latestNetworkHistoryItems = items
                    }
                }
                launch {
                    viewModel.recentLocalItems.collect { items ->
                        allVideoHistoryItems = items
                        refreshAccessibleVideoHistory(items)
                    }
                }
            }
        }
    }

    private fun openHistoryItem(item: MediaItem) {
        PlayerRouter.open(requireContext(), item.path, item.name)
    }

    private fun updateRecycler(recycler: androidx.recyclerview.widget.RecyclerView, items: List<MediaItem>) {
        val historyTabForRecycler = 1
        val emptyState = binding.emptyStateLocal
        if (items.isEmpty() && historySelectionTab == historyTabForRecycler) clearHistorySelection()
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) return
        // Même design que Blaze Gallery : tuiles portrait, 3 par rangée, overlay bas avec
        // titre/durée/"..." plutôt que des lignes empilées avec tous les badges à côté.
        recycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    layoutInflater.inflate(R.layout.item_history_tile, parent, false)
                ) {}
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val item = items[position]
                val v = holder.itemView
                val lastPlayedPath = items.maxByOrNull { it.lastPlayedAt }?.path
                v.findViewById<View>(R.id.lastPlayedBorder).visibility =
                    if (item.path == lastPlayedPath) View.VISIBLE else View.GONE

                // Titre + durée, dans l'overlay bas comme sur les tuiles photo de Blaze Gallery.
                v.findViewById<TextView>(R.id.tvFileName).text = item.name
                v.findViewById<TextView>(R.id.tvDuration).text = item.formattedDuration
                v.findViewById<ImageView>(R.id.ivSourceBadge)?.let { bindHistorySourceIcon(it, item) }

                // Badges conteneur + qualité en haut à gauche de la tuile (résolution seule sert
                // désormais de badge "qualité" : le champ MediaItem.resolution est déjà normalisé
                // en SD/HD/FHD/4K ailleurs dans l'app, pas une valeur brute en pixels).
                val tvFmt = v.findViewById<TextView>(R.id.tvFormat)
                val tvRes = v.findViewById<TextView>(R.id.tvResolution)
                val ext = containerBadgeFrom(item)
                val isAudioItem = item.mimeType.startsWith("audio/") ||
                    item.extension.lowercase() in setOf("mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "ape", "wv", "aiff", "alac")
                if (isAudioItem) {
                    fr.retrospare.blazeplayer.player.AudioQualityBadgeBinder.bind(
                        tvFmt,
                        tvRes,
                        item.path,
                        item.name,
                        ext,
                        knownDurationMs = item.duration.takeIf { it > 0L }?.times(1000L) ?: 0L,
                        knownSizeBytes = item.size
                    )
                } else {
                    if (ext.isNotEmpty()) {
                        fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvFmt, ext)
                        tvFmt.visibility = View.VISIBLE
                    } else {
                        tvFmt.visibility = View.GONE
                    }
                    if (!item.resolution.isNullOrEmpty()) {
                        tvRes.text = item.resolution
                        fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvRes)
                        tvRes.visibility = View.VISIBLE
                    } else {
                        tvRes.visibility = View.GONE
                    }
                }

                // Miniatures vidéo locales/réseau avec cache disque persistant, comme avant.
                val ivThumb = v.findViewById<ImageView>(R.id.ivThumbnail)
                ivThumb.setImageDrawable(null)
                v.findViewById<ImageView>(R.id.ivPlayOverlay)?.visibility = View.VISIBLE
                viewLifecycleOwner.lifecycleScope.launch {
                    fr.retrospare.blazeplayer.ui.ThumbnailUtils.loadThumbnail(requireContext(), item.path, ivThumb)
                }

                val inSelectionMode = historySelectionTab == historyTabForRecycler
                val cbSelect = v.findViewById<android.widget.CheckBox>(R.id.cbHistorySelect)
                cbSelect?.visibility = if (inSelectionMode) View.VISIBLE else View.GONE
                cbSelect?.isChecked = selectedHistoryPaths.contains(item.path)
                cbSelect?.setOnClickListener { toggleHistorySelection(item) }

                // Click
                v.setOnClickListener {
                    if (inSelectionMode) toggleHistorySelection(item) else openHistoryItem(item)
                }

                // Bouton 3 points, désormais ancré dans l'overlay bas de la tuile
                val btnMore = v.findViewById<android.view.View>(R.id.btnMore)
                btnMore?.visibility = if (inSelectionMode) View.GONE else View.VISIBLE
                btnMore?.setOnClickListener { anchor ->
                    val popup = android.widget.PopupMenu(requireContext(), anchor)
                    popup.menu.add(0, 1, 0, getString(R.string.action_play))
                    popup.menu.add(0, 2, 1, getString(R.string.action_information))
                    popup.menu.add(0, 3, 2, getString(R.string.add_to_playlist_short))
                    popup.menu.add(0, 5, 3, getString(R.string.action_custom_thumbnail))
                    popup.menu.add(0, 6, 4, getString(R.string.action_delete_thumbnail))
                    popup.menu.add(0, 4, 5, getString(R.string.action_remove_from_history))
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> { openHistoryItem(item); true }
                            2 -> {
                                // Codec audio/vidéo : retirés de la tuile elle-même (surchargeait
                                // l'overlay), déplacés ici dans le détail "Informations".
                                fr.retrospare.blazeplayer.ui.VideoInfoDialog.show(
                                    context = requireContext(),
                                    scope = viewLifecycleOwner.lifecycleScope,
                                    title = item.name,
                                    mediaPath = item.path,
                                    displayName = item.name,
                                    extension = item.extension.uppercase(),
                                    itemSizeBytes = item.size,
                                    itemDurationSeconds = item.duration,
                                    resolution = item.resolution,
                                    videoCodec = item.videoCodec,
                                    audioCodec = item.audioCodec,
                                    fullExtract = false
                                )
                                true
                            }
                            3 -> {
                                val category = if (item.isNetwork) {
                                    fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO
                                } else {
                                    fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO
                                }
                                fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
                                    requireContext(), category,
                                    listOf(fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(item.path, item.name))
                                )
                                true
                            }
                            5 -> { startCustomThumbnailSelection(item, historyTabForRecycler); true }
                            6 -> { deleteCustomThumbnail(item); true }
                            4 -> { viewModel.removeFromHistory(item); true }
                            else -> false
                        }
                    }
                    popup.show()
                }
            }
        }
    }

    companion object {
        private const val PREFS_RUNTIME_PERMISSIONS = "blaze_runtime_permissions"
        private const val PREFS_HELP_MODALS = "blaze_help_modals"
        private const val KEY_NETWORK_HELP_SHOWN = "network_help_shown"
        private const val KEY_GALLERY_PERMISSIONS_PROMPTED = "gallery_permissions_prompted"
        private const val KEY_AUDIO_PERMISSIONS_PROMPTED = "audio_permissions_prompted"
        private const val KEY_NETWORK_PERMISSIONS_PROMPTED = "network_permissions_prompted"
    }

}
