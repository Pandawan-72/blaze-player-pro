package fr.retrospare.blazeplayer.home

import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
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

    private var gallerySort: GallerySort = GallerySort.DATE_DESC
    private var currentGalleryBucketId: String? = null
    private var currentGalleryBucketName: String? = null
    private var galleryTrashMode: Boolean = false
    private var gallerySelectionMode: Boolean = false
    private val selectedGalleryPhotos = linkedSetOf<String>()
    private var currentGalleryPhotos: List<MediaItem> = emptyList()
    private var pendingGallerySystemActionRefresh: (() -> Unit)? = null
    private var galleryCustomThumbnailMode: Boolean = false
    private var pendingCustomThumbnailVideo: MediaItem? = null
    private var returnTabAfterCustomThumbnail: Int = 1

    private val galleryPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        showGalleryFolders()
    }

    private val gallerySystemActionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pendingGallerySystemActionRefresh?.invoke()
        pendingGallerySystemActionRefresh = null
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
                viewLifecycleOwner.lifecycleScope.launch {
                    if (!canOpenTab(index)) {
                        findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                        return@launch
                    }
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
        }
        val activeTab = if (viewModel.currentTabIndex.value == 0) 1 else viewModel.currentTabIndex.value
        updateTabStyles(activeTab)
        updateSectionTitles(activeTab)
        if (activeTab == 4) showAudioTab()
        else { hideAudioTab(); viewModel.onTabSelected(activeTab) }
    }


    private suspend fun canOpenTab(index: Int): Boolean {
        return when (index) {
            2, 4 -> fr.retrospare.blazeplayer.paywall.FeatureAccess.isPro(userRepository)
            3 -> fr.retrospare.blazeplayer.paywall.FeatureAccess.isProPlus(userRepository)
            else -> true
        }
    }

    /** Configure la recherche et les listes de l'onglet Blaze Gallery. Recherche déclenchée par la
     *  touche "Rechercher" du clavier (pas de recherche à chaque frappe, pour ménager le quota
     *  gratuit de l'API — ~100 unités par recherche, 10 000/jour). */
    /** Mode d'affichage courant de la liste réutilisée (listYoutubeSearch) : recherche ou
     *  favoris. Sert à savoir quoi rafraîchir après un ajout/retrait de favori, et ce que le
     *  bouton "fermer" doit faire. Null quand le contenu par défaut (historique) est affiché. */
    private var youtubeListMode: String? = null

    private fun setupYoutubeTab() {
        // Onglet 3 : Blaze Gallery. Plus aucun accès cloud/SAF ici : la galerie lit
        // directement les photos locales exposées par MediaStore, comme la galerie Android.
        binding.youtubeSearchBarRow.visibility = View.GONE
        binding.youtubeFavoritesHeaderRow.visibility = View.GONE
        binding.listYoutubeSearch.visibility = View.GONE
        binding.tvYoutubeError.visibility = View.GONE
        binding.youtubeDefaultContent.visibility = View.GONE
        binding.btnCloseYoutubeSearch.visibility = View.GONE
        binding.btnCloudFiles.text = getString(R.string.gallery_trash)
        binding.btnCloudFiles.setIconResource(R.drawable.ic_trash)
        binding.btnCloudFiles.setOnClickListener { showGalleryTrash() }
        binding.btnFavoritesCloud.text = getString(R.string.action_back)
        binding.btnFavoritesCloud.setOnClickListener { showGalleryFolders() }
        binding.btnFavoritesCloud.visibility = View.GONE
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
                showGalleryFolders()
                true
            }
            else -> false
        }
    }

    private fun hasGalleryPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestGalleryPermissionIfNeeded(): Boolean {
        if (hasGalleryPermission()) return true
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        galleryPermissionLauncher.launch(permissions)
        return false
    }


    private fun styleGalleryBackButton() {
        binding.btnFavoritesCloud.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
        binding.btnFavoritesCloud.iconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.on_surface))
        binding.btnFavoritesCloud.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_variant))
        binding.btnFavoritesCloud.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#B8C0CC"))
        binding.btnFavoritesCloud.strokeWidth = (1 * resources.displayMetrics.density).toInt()
    }

    private fun styleGalleryTrashButton(emptyAction: Boolean = false) {
        if (emptyAction) {
            binding.btnCloudFiles.setTextColor(android.graphics.Color.WHITE)
            binding.btnCloudFiles.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.btnCloudFiles.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2A2F3A"))
            binding.btnCloudFiles.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E05151"))
            binding.btnCloudFiles.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        } else {
            binding.btnCloudFiles.setTextColor(android.graphics.Color.WHITE)
            binding.btnCloudFiles.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.btnCloudFiles.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_variant))
            binding.btnCloudFiles.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#B8C0CC"))
            binding.btnCloudFiles.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        }
    }

    private fun confirmGalleryDeletion(message: String, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(message)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.action_confirm_delete)) { _, _ -> onConfirm() }
            .show()
    }

    private fun showGallerySortMenu() {
        val popup = android.widget.PopupMenu(requireContext(), binding.btnCloudFiles)
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

    private fun showGalleryFolders() {
        if (!isAdded || !requestGalleryPermissionIfNeeded()) return
        currentGalleryBucketId = null
        currentGalleryBucketName = null
        galleryTrashMode = false
        clearGallerySelection()
        binding.btnFavoritesCloud.visibility = View.GONE
        binding.btnCloudFiles.visibility = View.VISIBLE
        binding.btnCloudFiles.text = getString(R.string.gallery_trash)
        binding.btnCloudFiles.setIconResource(R.drawable.ic_trash)
        binding.btnCloudFiles.setOnClickListener { showGalleryTrash() }
        styleGalleryTrashButton(false)
        binding.tvSectionCloud.text = if (galleryCustomThumbnailMode) {
            getString(R.string.custom_thumbnail_pick_folder)
        } else {
            getString(R.string.gallery_folders)
        }
        binding.listCloud.visibility = View.VISIBLE
        binding.listCloud.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(12)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
            itemAnimator = null
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGalleryFoldersFromMediaStore() }
            binding.listCloud.adapter = GalleryAdapter(
                items = folders,
                grid = false,
                trashMode = false,
                onClick = { folder -> showGalleryPhotos(folder.path, folder.name) },
                onLongClick = {},
                onMore = { folder, anchor -> showGalleryFolderMenu(folder, anchor) }
            )
        }
    }

    private fun showGalleryPhotos(bucketId: String, bucketName: String) {
        if (!isAdded || !requestGalleryPermissionIfNeeded()) return
        currentGalleryBucketId = bucketId
        currentGalleryBucketName = bucketName
        galleryTrashMode = false
        clearGallerySelection()
        binding.btnFavoritesCloud.visibility = View.VISIBLE
        binding.btnFavoritesCloud.text = getString(R.string.action_back)
        binding.btnFavoritesCloud.setIconResource(R.drawable.ic_arrow_back)
        binding.btnFavoritesCloud.setOnClickListener { showGalleryFolders() }
        styleGalleryBackButton()
        binding.btnCloudFiles.visibility = View.VISIBLE
        binding.btnCloudFiles.text = getString(R.string.gallery_sort)
        binding.btnCloudFiles.setIconResource(R.drawable.ic_sort)
        binding.btnCloudFiles.setOnClickListener { showGallerySortMenu() }
        styleGalleryTrashButton(false)
        binding.tvSectionCloud.text = if (galleryCustomThumbnailMode) {
            getString(R.string.custom_thumbnail_pick_image)
        } else {
            getString(R.string.gallery_folder_title, bucketName)
        }
        binding.listCloud.visibility = View.VISIBLE
        binding.listCloud.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
            itemAnimator = null
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val photos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGalleryPhotosFromMediaStore(bucketId) }
            currentGalleryPhotos = photos
            binding.listCloud.adapter = GalleryAdapter(
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
        }
    }

    private fun loadGalleryFoldersFromMediaStore(): List<MediaItem> {
        val resolver = requireContext().contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media._ID
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
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, queryArgs, null)
        } else {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")
        }
        cursorResult?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdCol) ?: continue
                val bucketName = cursor.getString(bucketNameCol)?.takeIf { it.isNotBlank() } ?: getString(R.string.gallery_unknown_folder)
                val date = cursor.getLong(dateCol)
                val imageId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val imageUri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId).toString()
                val folder = folders.getOrPut(bucketId) { MutableGalleryFolder(bucketId, bucketName) }
                folder.count += 1
                if (folder.previewUris.size < 4) folder.previewUris += imageUri
                if (date > folder.lastModified) folder.lastModified = date
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
                previewUris = it.previewUris
            )
        }
        // Accueil Blaze Gallery : les dossiers restent toujours classés par nom.
        // Le tri date/nom/taille est uniquement appliqué à l'intérieur d'un dossier photo.
        return result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun loadGalleryPhotosFromMediaStore(bucketId: String): List<MediaItem> {
        val resolver = requireContext().contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE
        )
        val photos = mutableListOf<MediaItem>()
        val cursorResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.BUCKET_ID} = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(bucketId))
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_MODIFIED))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)
        } else {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(bucketId),
                null
            )
        }
        cursorResult?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val name = cursor.getString(nameCol) ?: "Photo $id"
                val mime = cursor.getString(mimeCol) ?: "image/*"
                photos += MediaItem(
                    id = "gallery_photo_$id",
                    name = name,
                    path = uri.toString(),
                    size = cursor.getLong(sizeCol),
                    lastPlayedAt = cursor.getLong(dateCol),
                    extension = name.substringAfterLast('.', "").uppercase(),
                    mimeType = mime
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
        var previewUris: List<String> = emptyList()
    )

    private fun openGalleryPhoto(photo: MediaItem) {
        showInAppPhotoViewer(photo)
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
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (isAdded) requireActivity().requestedOrientation = previousOrientation
        }
        dialog.setOnShowListener {
            loadGalleryImage(image, photo.path, Size.ORIGINAL)
        }
        dialog.show()
    }

    private fun loadGalleryImage(imageView: ImageView, uriString: String, requestSize: Size = Size(360, 360)) {
        imageView.setImageDrawable(null)
        val request = ImageRequest.Builder(requireContext())
            .data(android.net.Uri.parse(uriString))
            .target(imageView)
            .size(requestSize)
            .scale(Scale.FILL)
            .allowHardware(true)
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

    private fun startCustomThumbnailSelection(item: MediaItem, sourceTab: Int = currentTabIndex) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(3)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
            pendingCustomThumbnailVideo = item
            returnTabAfterCustomThumbnail = sourceTab.takeIf { it == 1 || it == 2 } ?: 1
            galleryCustomThumbnailMode = true
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
            popup.menu.add(0, 2, 1, getString(R.string.action_share))
            popup.menu.add(0, 3, 2, getString(R.string.gallery_delete))
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
                else -> false
            }
        }
        popup.show()
    }

    private fun shareGalleryPhoto(photo: MediaItem) {
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = photo.mimeType.ifBlank { "image/*" }
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
            pendingGallerySystemActionRefresh = refresh
            val request = MediaStore.createDeleteRequest(requireContext().contentResolver, uris)
            gallerySystemActionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            uris.forEach { uri -> try { requireContext().contentResolver.delete(uri, null, null) } catch (_: Exception) {} }
            val trash = getGalleryTrashSet()
            uris.forEach { trash.remove(it.toString()) }
            saveGalleryTrashSet(trash)
            refresh()
        }
    }

    private fun String.toUriOrNull(): Uri? = try { Uri.parse(this) } catch (_: Exception) { null }

    private fun showGalleryTrash() {
        if (!isAdded || !requestGalleryPermissionIfNeeded()) return
        galleryTrashMode = true
        clearGallerySelection()
        currentGalleryBucketId = null
        currentGalleryBucketName = null
        binding.tvSectionCloud.text = getString(R.string.gallery_trash)
        binding.btnFavoritesCloud.visibility = View.VISIBLE
        binding.btnFavoritesCloud.text = getString(R.string.action_back)
        binding.btnFavoritesCloud.setIconResource(R.drawable.ic_arrow_back)
        binding.btnFavoritesCloud.setOnClickListener { showGalleryFolders() }
        binding.btnCloudFiles.visibility = View.VISIBLE
        binding.btnCloudFiles.text = getString(R.string.gallery_empty_trash)
        binding.btnCloudFiles.setIconResource(R.drawable.ic_trash)
        binding.btnCloudFiles.setOnClickListener { confirmGalleryDeletion(getString(R.string.confirm_empty_trash_message)) { emptyGalleryTrashPermanently() } }
        styleGalleryBackButton()
        styleGalleryTrashButton(true)
        binding.listCloud.visibility = View.VISIBLE
        binding.listCloud.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
            itemAnimator = null
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val photos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadTrashedGalleryPhotosFromMediaStore() }
            currentGalleryPhotos = photos
            binding.listCloud.adapter = GalleryAdapter(
                items = photos,
                grid = true,
                trashMode = true,
                onClick = { photo -> openGalleryPhoto(photo) },
                onLongClick = {},
                onMore = { photo, anchor -> showGalleryPhotoMenu(photo, anchor) }
            )
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
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)
        } else {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")
        }
        cursorResult?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val name = cursor.getString(nameCol) ?: "Photo $id"
                all += MediaItem(
                    id = "gallery_photo_$id",
                    name = name,
                    path = uri.toString(),
                    size = cursor.getLong(sizeCol),
                    lastPlayedAt = cursor.getLong(dateCol),
                    extension = name.substringAfterLast('.', "").uppercase(),
                    mimeType = cursor.getString(mimeCol) ?: "image/*"
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
        binding.listCloud.adapter?.notifyDataSetChanged()
    }

    private fun toggleGallerySelection(photo: MediaItem) {
        if (!gallerySelectionMode) return
        if (!selectedGalleryPhotos.add(photo.path)) selectedGalleryPhotos.remove(photo.path)
        if (selectedGalleryPhotos.isEmpty()) clearGallerySelection() else updateGallerySelectionToolbar()
        binding.listCloud.adapter?.notifyDataSetChanged()
    }

    private fun clearGallerySelection() {
        gallerySelectionMode = false
        selectedGalleryPhotos.clear()
    }

    private fun updateGallerySelectionToolbar() {
        binding.btnFavoritesCloud.visibility = View.VISIBLE
        binding.btnFavoritesCloud.text = getString(R.string.gallery_delete_all)
        binding.btnFavoritesCloud.setIconResource(R.drawable.ic_trash)
        binding.btnFavoritesCloud.setOnClickListener { confirmGalleryDeletion(getString(R.string.confirm_delete_selected_message)) { deleteSelectedGalleryPhotos() } }
        binding.btnCloudFiles.visibility = View.VISIBLE
        binding.btnCloudFiles.text = getString(R.string.gallery_share_all)
        binding.btnCloudFiles.setIconResource(R.drawable.ic_share)
        binding.btnCloudFiles.setOnClickListener { shareSelectedGalleryPhotosAsZip() }
        styleGalleryBackButton()
        styleGalleryTrashButton(false)
        binding.tvSectionCloud.text = getString(R.string.gallery_selected_count, selectedGalleryPhotos.size)
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
                val name = photo.name.ifBlank { "photo_${index + 1}.jpg" }
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
                holder.tvFolderCount.text = resources.getQuantityString(R.plurals.gallery_photo_count, item.size.toInt(), item.size.toInt())
                holder.previews.forEachIndexed { index, imageView ->
                    val uri = item.previewUris.getOrNull(index)
                    if (uri != null) {
                        imageView.visibility = View.VISIBLE
                        loadGalleryImage(imageView, uri, Size(220, 220))
                    } else {
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
    }

    private fun showYoutubeDefaultContent() {
        youtubeListMode = null
        binding.listYoutubeSearch.visibility = View.GONE
        binding.youtubeDefaultContent.visibility = View.GONE
        binding.tvYoutubeError.visibility = View.GONE
        binding.btnCloseYoutubeSearch.visibility = View.GONE
        binding.youtubeSearchBarRow.visibility = View.GONE
        binding.youtubeFavoritesHeaderRow.visibility = View.GONE
        binding.listYoutubeHistory.visibility = View.GONE
        showGalleryFolders()
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
                        binding.tvYoutubeError.text = getString(R.string.youtube_no_results, query)
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
            binding.tvYoutubeError.text = getString(R.string.youtube_no_favorites)
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
            onMoreClick = { item, anchor -> showYoutubeItemMenu(item, anchor) },
            highlightedVideoId = history.firstOrNull()?.videoId
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
        popup.menu.add(0, 1, 0, getString(R.string.youtube_add_to_playlist))
        popup.menu.add(0, 2, 1, getString(R.string.youtube_remove_from_history))
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
        val buttons = listOf(
            binding.btnPlaylistYoutube1, binding.btnPlaylistYoutube2, binding.btnPlaylistYoutube3,
            binding.btnPlaylistYoutube4, binding.btnPlaylistYoutube5
        )
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
    private fun refreshYoutubeDefaultContent() {
        showYoutubeDefaultContent()
    }

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
        viewLifecycleOwner.lifecycleScope.launch {
            if (!canOpenTab(index)) {
                findNavController().navigate(fr.retrospare.blazeplayer.R.id.action_home_to_paywall)
                return@launch
            }
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
                viewModel.onTabSelected(3)
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
        // selectedIndex: 1=Local, 2=Réseau, 3=Blaze Gallery, 4=Audio
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
                    fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_error), getString(R.string.toast_share_not_found))
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

    private fun setupCloudPlaylistButtons() {
        val cloudButtons = listOf(
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistCloud1),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistCloud2),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistCloud3),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistCloud4),
            binding.root.findViewById<android.widget.TextView>(fr.retrospare.blazeplayer.R.id.btnPlaylistCloud5)
        )
        val category = fr.retrospare.blazeplayer.playlist.PlaylistCategory.CLOUD_VIDEO
        val lastPlayedCloud = fr.retrospare.blazeplayer.playlist.PlaylistManager.getLastPlayed(requireContext(), category)
        cloudButtons.forEachIndexed { i, btn ->
            val hasItems = fr.retrospare.blazeplayer.playlist.PlaylistManager.getPlaylist(requireContext(), category, i + 1).isNotEmpty()
            btn?.isSelected = (lastPlayedCloud == i + 1) && hasItems
            btn?.setOnClickListener { openSavedPlaylist(category, i + 1) }
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

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.recentNetworkItems.collect { updateRecycler(binding.listNetwork, it) } }
                launch { viewModel.recentLocalItems.collect { updateRecycler(binding.listLocal, it) } }
            }
        }
    }

    private fun openHistoryItem(item: MediaItem) {
        if (item.isCloud) {
            val intent = android.content.Intent(requireContext(), fr.retrospare.blazeplayer.player.PlayerActivity::class.java).apply {
                putExtra("mediaPath", item.path)
                putExtra("mediaName", item.name)
                putExtra("isCloudMedia", true)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    val uri = android.net.Uri.parse(item.path)
                    data = uri
                    clipData = android.content.ClipData.newUri(requireContext().contentResolver, item.name, uri)
                } catch (_: Exception) { }
            }
            startActivity(intent)
        } else {
            PlayerRouter.open(requireContext(), item.path, item.name)
        }
    }

    private fun updateRecycler(recycler: androidx.recyclerview.widget.RecyclerView, items: List<MediaItem>) {
        val historyTabForRecycler = if (recycler.id == R.id.listNetwork) 2 else 1
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
                val lastPlayedPath = items.maxByOrNull { it.lastPlayedAt }?.path
                v.setBackgroundResource(
                    if (item.path == lastPlayedPath) R.drawable.bg_media_card_last_played
                    else R.drawable.bg_media_card
                )

                // Nom du fichier
                v.findViewById<TextView>(R.id.tvFileName).text = item.name

                val tvDur = v.findViewById<TextView>(R.id.tvDuration)
                val tvRes = v.findViewById<TextView>(R.id.tvResolution)
                val tvVid = v.findViewById<TextView>(R.id.tvVideoCodec)
                val tvAud = v.findViewById<TextView>(R.id.tvAudioCodec)
                val tvFmt = v.findViewById<TextView>(R.id.tvFormat)
                val ivThumb = v.findViewById<ImageView>(R.id.ivThumbnail)

                // Affichage direct et synchrone — comme pour le navigateur local/réseau
                // (BrowserAdapter) : ces champs sont maintenant préchargés (avec mise en cache
                // pour le réseau) par HomeViewModel avant même l'affichage, plutôt qu'extraits à
                // chaque ligne visible pendant le défilement. Même rendu partout, plus de risque
                // d'écrire sur la mauvaise ligne recyclée pendant un chargement.
                tvDur.text = item.formattedDuration
                tvRes.text = item.resolution ?: ""
                tvRes.visibility = if (!item.resolution.isNullOrEmpty()) View.VISIBLE else View.GONE
                tvVid.text = item.videoCodec ?: ""
                tvVid.visibility = if (!item.videoCodec.isNullOrEmpty()) View.VISIBLE else View.GONE
                tvAud.text = item.audioCodec ?: ""
                tvAud.visibility = if (!item.audioCodec.isNullOrEmpty()) View.VISIBLE else View.GONE

                // Badge conteneur immédiat depuis l'extension persistée, puis le titre, puis
                // l'URL réseau. Les URLs UPnP peuvent avoir un titre sans extension ou une query
                // string; ce fallback garde le même badge MP4/MKV/AVI que les vidéos SMB.
                val ext = containerBadgeFrom(item)
                if (ext.isNotEmpty()) {
                    fr.retrospare.blazeplayer.ui.BadgeStyle.applyContainerBadge(tvFmt, ext)
                    tvFmt.visibility = View.VISIBLE
                } else {
                    tvFmt.visibility = View.GONE
                }
                fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvRes)
                fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvVid)
                fr.retrospare.blazeplayer.ui.BadgeStyle.applyTechnicalBadge(tvAud)

                // Historique accueil : on garde les miniatures vidéo locales/réseau avec cache
                // disque persistant. Seuls les navigateurs sont allégés sans miniatures.
                (ivThumb.parent as? View)?.visibility = View.VISIBLE
                ivThumb.setImageDrawable(null)
                v.findViewById<ImageView>(R.id.ivPlayOverlay)?.visibility = View.VISIBLE
                viewLifecycleOwner.lifecycleScope.launch {
                    fr.retrospare.blazeplayer.ui.ThumbnailUtils.loadThumbnail(requireContext(), item.path, ivThumb)
                }

                // Click
                v.setOnClickListener { openHistoryItem(item) }

                // Bouton 3 points
                val btnMore = v.findViewById<android.view.View>(R.id.btnMore)
                btnMore?.setOnClickListener { anchor ->
                    val popup = android.widget.PopupMenu(requireContext(), anchor)
                    popup.menu.add(0, 1, 0, getString(R.string.action_play))
                    popup.menu.add(0, 2, 1, getString(R.string.action_information))
                    popup.menu.add(0, 3, 2, getString(R.string.youtube_add_to_playlist))
                    popup.menu.add(0, 5, 3, getString(R.string.action_custom_thumbnail))
                    popup.menu.add(0, 6, 4, getString(R.string.action_delete_thumbnail))
                    popup.menu.add(0, 4, 5, getString(R.string.action_remove_from_history))
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> { openHistoryItem(item); true }
                            2 -> {
                                fr.retrospare.blazeplayer.ui.VideoInfoDialog.show(
                                    context = requireContext(),
                                    scope = viewLifecycleOwner.lifecycleScope,
                                    title = item.name,
                                    mediaPath = item.path,
                                    displayName = item.name,
                                    extension = item.extension.uppercase(),
                                    itemSizeBytes = item.size,
                                    itemDurationSeconds = item.duration,
                                    fullExtract = false
                                )
                                true
                            }
                            3 -> {
                                val category = when {
                                    item.isCloud -> fr.retrospare.blazeplayer.playlist.PlaylistCategory.CLOUD_VIDEO
                                    item.isNetwork -> fr.retrospare.blazeplayer.playlist.PlaylistCategory.NETWORK_VIDEO
                                    else -> fr.retrospare.blazeplayer.playlist.PlaylistCategory.LOCAL_VIDEO
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

}
