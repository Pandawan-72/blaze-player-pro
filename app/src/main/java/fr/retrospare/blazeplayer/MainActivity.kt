package fr.retrospare.blazeplayer

import fr.retrospare.blazeplayer.ui.showPremium
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import androidx.activity.viewModels
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.databinding.ActivityMainBinding
import fr.retrospare.blazeplayer.player.AudioLibraryHeuristics
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository

    private val handler = Handler(Looper.getMainLooper())
    private val miniTimeTicker = object : Runnable {
        override fun run() {
            updateMiniPlayerTime()
            handler.postDelayed(this, 1000L)
        }
    }

    private lateinit var binding: ActivityMainBinding
    private var miniBgAnimator: android.animation.ValueAnimator? = null
    private var currentMiniBgColor: Int = fr.retrospare.blazeplayer.player.AudioDynamicColor.DEFAULT_BACKGROUND
    private var miniPlayingAnimator: android.animation.ObjectAnimator? = null


    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        val videoAccessDenied = denied.any {
            it == Manifest.permission.READ_MEDIA_VIDEO || it == Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (videoAccessDenied) {
            showPermissionRationale()
        }
    }

    private val miniPlayerVm: fr.retrospare.blazeplayer.player.MiniPlayerViewModel by viewModels()
    private var miniPlayerUiInitialized = false
    private var miniPlayerAccessJob: kotlinx.coroutines.Job? = null
    private var accessObserverStarted = false

    /** Initialise uniquement les vues et observateurs du mini player.
     * La connexion MediaController est volontairement différée jusqu'à ce que les droits Pro+
     * aient été résolus de façon asynchrone et qu'une vraie session audio existe déjà. */
    private fun setupMiniPlayer() {
        if (miniPlayerUiInitialized) return
        miniPlayerUiInitialized = true

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                miniPlayerVm.state.collect { state ->
                    applyMiniPlayerState(state)
                }
            }
        }

        binding.btnMiniPlayPause.setOnClickListener {
            val c = miniPlayerVm.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.btnMiniPrev.setOnClickListener { miniPlayerVm.controller?.seekToPreviousMediaItem() }
        binding.btnMiniNext.setOnClickListener { miniPlayerVm.controller?.seekToNextMediaItem() }
        binding.btnMiniClose.setOnClickListener {
            miniPlayerVm.dismiss()
            binding.miniPlayerBar.visibility = android.view.View.GONE
            stopMiniPlayingAnimation(keepVisible = false)
        }
        binding.miniPlayerBar.setOnClickListener { openBlazeAudio() }
        setupMiniPlayerDrag()
        handler.removeCallbacks(miniTimeTicker)
        handler.post(miniTimeTicker)
    }

    private fun updateMiniPlayerConnection(
        knownState: fr.retrospare.blazeplayer.data.repository.SubscriptionAccessState? = null
    ) {
        miniPlayerAccessJob?.cancel()
        miniPlayerAccessJob = lifecycleScope.launch {
            val state = knownState ?: userRepository.currentAccessState()
            val enabled = userRepository.miniPlayerEnabledFlow.first()
            val shouldConnect = state.hasProPlusAccess && enabled &&
                fr.retrospare.blazeplayer.player.BlazePlayerService.isAudioSessionActive

            if (shouldConnect) {
                miniPlayerVm.connect()
                miniPlayerVm.refresh()
            } else {
                miniPlayerVm.disconnect()
                if (::binding.isInitialized) {
                    binding.miniPlayerBar.visibility = android.view.View.GONE
                    stopMiniPlayingAnimation(keepVisible = false)
                }
            }
        }
    }

    private fun formatMiniTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun updateMiniPlayerTime() {
        if (!::binding.isInitialized || binding.miniPlayerBar.visibility != android.view.View.VISIBLE) return
        val c = miniPlayerVm.controller ?: return
        val position = c.currentPosition.coerceAtLeast(0L)
        val duration = c.duration.takeIf { it > 0 } ?: 0L
        binding.tvMiniTime.text = if (duration > 0L) {
            "${formatMiniTime(position)} / ${formatMiniTime(duration)}"
        } else {
            formatMiniTime(position)
        }
    }

    /** Applique l'état courant du mini player à la vue. Extrait de setupMiniPlayer() pour pouvoir
     *  être rappelé depuis onResume() : un StateFlow ne redéclenche pas le collector si la valeur
     *  n'a pas changé, ce qui pouvait laisser le mini player caché (retour d'une vidéo locale,
     *  réveil de l'écran...) tant qu'aucun changement d'onglet ne forçait une nouvelle valeur. */
    private fun applyMiniPlayerState(state: fr.retrospare.blazeplayer.player.MiniPlayerState) {
        binding.miniPlayerBar.visibility =
            if (state.isVisible) android.view.View.VISIBLE else android.view.View.GONE
        if (state.isVisible) {
            binding.tvMiniTitle.text = state.title.ifEmpty { getString(R.string.unknown_title) }
            val miniTags = buildMiniArtistAlbumText(state.artist, state.album, state.accentColor)
            binding.tvMiniArtist.text = miniTags
            binding.tvMiniArtist.visibility = if (miniTags.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            val art = state.artworkData
            if (art != null) {
                binding.ivMiniArtwork.setImageBitmap(
                    android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size))
            } else {
                binding.ivMiniArtwork.setImageResource(fr.retrospare.blazeplayer.R.drawable.ic_music_note_large)
            }
            updateMiniPlayerTime()
            binding.btnMiniPlayPause.setImageResource(
                if (state.isPlaying) fr.retrospare.blazeplayer.R.drawable.ic_pause
                else fr.retrospare.blazeplayer.R.drawable.ic_play
            )
            binding.miniPlayingIndicator.imageTintList = android.content.res.ColorStateList.valueOf(state.accentColor)
            binding.miniEqOverlay.visibility = android.view.View.VISIBLE
            updateMiniPlayingIndicator(state.isPlaying)
            applyMiniPlayerColor(state.backgroundColor, state.accentColor)
        } else {
            binding.miniEqOverlay.visibility = android.view.View.GONE
            stopMiniPlayingAnimation(keepVisible = false)
        }
    }

    private fun updateMiniPlayingIndicator(isPlaying: Boolean) {
        binding.miniEqOverlay.visibility = android.view.View.VISIBLE
        binding.miniPlayingIndicator.visibility = android.view.View.VISIBLE
        binding.miniPlayingIndicator.alpha = if (isPlaying) 1f else 0.72f
        if (isPlaying) startMiniPlayingAnimation() else stopMiniPlayingAnimation(keepVisible = true)
    }

    private fun startMiniPlayingAnimation() {
        val indicator = binding.miniPlayingIndicator
        if (miniPlayingAnimator?.isStarted == true) return
        miniPlayingAnimator?.cancel()
        miniPlayingAnimator = android.animation.ObjectAnimator.ofFloat(
            indicator,
            android.view.View.ROTATION,
            indicator.rotation,
            indicator.rotation + 360f
        ).apply {
            duration = 1500L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    private fun stopMiniPlayingAnimation(keepVisible: Boolean) {
        miniPlayingAnimator?.cancel()
        miniPlayingAnimator = null
        if (!::binding.isInitialized) return
        binding.miniPlayingIndicator.visibility = if (keepVisible) android.view.View.VISIBLE else android.view.View.GONE
        binding.miniEqOverlay.visibility = if (keepVisible) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Artiste et album affichés tels qu'ils sont dérivés de l'arborescence Artiste/Album/Titre.
     *  Aucun filtre de "tag faible" ne doit masquer un nom de dossier choisi par l'utilisateur. */
    private fun buildMiniArtistAlbumText(artist: String, album: String, accentColor: Int): CharSequence {
        val safeArtist = artist.trim()
        val safeAlbum = album.trim()
        val builder = android.text.SpannableStringBuilder()
        if (safeArtist.isNotBlank()) {
            val start = builder.length
            builder.append(safeArtist)
            builder.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, builder.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                android.text.style.ForegroundColorSpan(accentColor),
                start, builder.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (safeAlbum.isNotBlank()) {
            if (builder.isNotEmpty()) builder.append("  •  ")
            builder.append(safeAlbum)
        }
        return builder
    }

    /** Anime le fond du mini player vers la couleur dynamique du morceau en cours (même couleur
     *  que l'écran Blaze Audio, cf. AudioDynamicColor). */
    private fun applyMiniPlayerColor(backgroundColor: Int, accentColor: Int) {
        if (currentMiniBgColor == backgroundColor) return
        miniBgAnimator?.cancel()
        miniBgAnimator = android.animation.ValueAnimator.ofObject(
            android.animation.ArgbEvaluator(), currentMiniBgColor, backgroundColor
        ).apply {
            duration = 320L
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                currentMiniBgColor = color
                binding.miniPlayerBar.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * resources.displayMetrics.density
                    setColor(color)
                    setStroke((1f * resources.displayMetrics.density).toInt(), 0x33FFFFFF)
                }
            }
            start()
        }
    }

    /** Permet de glisser le mini player au doigt n'importe où dans la page. Un simple tap
     *  (sans déplacement notable) ouvre toujours le lecteur Blaze Audio comme avant. */
    private fun setupMiniPlayerDrag() {
        val miniPlayer = binding.miniPlayerBar
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        var downRawX = 0f
        var downRawY = 0f
        var startTranslationX = 0f
        var startTranslationY = 0f
        var isDragging = false

        miniPlayer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startTranslationX = view.translationX
                    startTranslationY = view.translationY
                    isDragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val parent = view.parent as android.view.View

                        // Bornes calculées à partir de la position posée par le layout, pour
                        // que le mini player ne puisse jamais sortir de l'écran.
                        val minTx = -view.left.toFloat()
                        val maxTx = (parent.width - view.right).toFloat()
                        val minTy = -view.top.toFloat()
                        val maxTy = (parent.height - view.bottom).toFloat()

                        view.translationX = (startTranslationX + dx).coerceIn(minTx, maxTx)
                        view.translationY = (startTranslationY + dy).coerceIn(minTy, maxTy)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    isDragging = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    fun setInAudioPlayer(inPlayer: Boolean) {
        miniPlayerVm.setInAudioPlayer(inPlayer)
    }

    fun getMiniPlayerViewModel() = miniPlayerVm

    /** Force la resynchronisation du mini player : à appeler depuis n'importe quel fragment qui
     *  vient d'(re)créer sa vue (ex: HomeFragment de retour de Réglages ou d'une vidéo locale),
     *  car la simple recréation d'un Fragment ne déclenche PAS onResume() de l'Activity (elle
     *  reste résumée tout du long), donc le mini player pouvait rester caché/masqué sans qu'aucun
     *  évènement ne force sa réapparition. bringToFront() re-garantit aussi qu'il reste au-dessus
     *  du contenu qui vient d'être réinflaté, au cas où l'ordre de dessin aurait été perturbé. */
    fun refreshMiniPlayer() {
        updateMiniPlayerConnection()
        if (::binding.isInitialized) {
            applyMiniPlayerState(miniPlayerVm.state.value)
            binding.miniPlayerBar.bringToFront()
            binding.miniPlayerBar.requestLayout()
        }
    }

    override fun onPause() {
        stopMiniPlayingAnimation(keepVisible = true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshTrialAccess()
        if (::binding.isInitialized) {
            applyMiniPlayerState(miniPlayerVm.state.value)
        }
        consumePendingBlazeGalleryLaunch()
        // Important pour la notification audio : selon le launcher/ROM et l'état de la tâche,
        // le PendingIntent peut simplement ramener MainActivity au premier plan sans repasser par
        // onNewIntent(). On consomme donc aussi ici la demande persistante d'ouverture Blaze Audio.
        consumePendingBlazeAudioLaunch()
    }

    private fun findHomeFragment(): fr.retrospare.blazeplayer.home.HomeFragment? {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        return navHost?.childFragmentManager?.fragments
            ?.filterIsInstance<fr.retrospare.blazeplayer.home.HomeFragment>()
            ?.firstOrNull()
    }

    /** Point d'entrée unique pour forcer l'ouverture sur l'onglet Blaze Gallery, quelle que soit
     *  la situation dans laquelle se trouvait l'application :
     *  - app pas lancée / tâche froide : consommé plus tard par HomeFragment.onViewCreated().
     *  - app déjà ouverte sur l'accueil : appliqué immédiatement via HomeFragment.
     *  - app déjà ouverte sur un autre écran (Réglages, Réseau, Recherche...) : on revient
     *    d'abord explicitement à l'accueil via le NavController avant d'appliquer l'onglet,
     *    sinon HomeFragment n'a pas de vue et la demande était auparavant silencieusement
     *    ignorée (c'était la cause du bug : l'icône "Blaze Gallery" retombait sur "Local"). */
    private fun consumePendingBlazeGalleryLaunch(): Boolean {
        val prefs = getSharedPreferences("launcher_requests", MODE_PRIVATE)
        val pending = prefs.getBoolean("pendingOpenBlazeGallery", false) ||
            prefs.getLong("pendingOpenBlazeGalleryAt", 0L) > 0L
        if (!pending) return false

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                as? androidx.navigation.fragment.NavHostFragment
        // Si une autre destination que l'accueil est affichée, on y revient d'abord : sans ça,
        // HomeFragment n'a pas de vue et ne peut pas appliquer l'onglet demandé.
        navHost?.navController?.let { nav ->
            if (nav.currentDestination?.id != fr.retrospare.blazeplayer.R.id.homeFragment) {
                nav.popBackStack(fr.retrospare.blazeplayer.R.id.homeFragment, false)
            }
        }

        // Ne PAS effacer le flag ici : HomeFragment est seul responsable de l'effacer, une
        // fois l'onglet Blaze Gallery réellement appliqué (voir requestBlazeGalleryTab()).
        findHomeFragment()?.requestBlazeGalleryTab()
        // HomeFragment peut ne pas encore exister (vue en cours de (re)création après le
        // popBackStack ci-dessus) : on retente sur quelques frames pour couvrir ce cas, sans
        // dépendre uniquement d'un délai fixe.
        handler.postDelayed({ findHomeFragment()?.requestBlazeGalleryTab() }, 120L)
        handler.postDelayed({ findHomeFragment()?.requestBlazeGalleryTab() }, 300L)
        return true
    }

    private fun consumePendingBlazeAudioLaunch(): Boolean {
        val prefs = getSharedPreferences("launcher_requests", MODE_PRIVATE)
        val pending = prefs.getBoolean("pendingOpenBlazeAudio", false) ||
            prefs.getLong("pendingOpenBlazeAudioAt", 0L) > 0L
        if (!pending) return false

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                as? androidx.navigation.fragment.NavHostFragment
        navHost?.navController?.let { nav ->
            if (nav.currentDestination?.id != fr.retrospare.blazeplayer.R.id.homeFragment) {
                nav.popBackStack(fr.retrospare.blazeplayer.R.id.homeFragment, false)
            }
        }

        findHomeFragment()?.requestBlazeAudioTab()
        handler.postDelayed({ findHomeFragment()?.requestBlazeAudioTab() }, 120L)
        handler.postDelayed({ findHomeFragment()?.requestBlazeAudioTab() }, 300L)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handlePaywallIntent(intent)) handleAudioIntent(intent)
    }

    /** Traite les intents demandant l'ouverture d'un fichier audio (depuis PlayerRouter) ou le
     *  retour à l'écran audio (depuis la notification/sessionActivity de BlazePlayerService). */
    private fun handleAudioIntent(intent: Intent) {
        if (handleBlazePartyInvite(intent)) return
        if (handleExternalAudioLaunchIntent(intent)) return
        handleExternalViewIntent(intent)?.let { return }

        val audioPath = intent.getStringExtra("openAudioPath")
        if (audioPath != null) {
            val audioName = intent.getStringExtra("openAudioName") ?: ""
            handler.postDelayed({ openAudioPlayer(audioPath, audioName) }, 300)
            // Consomme l'extra pour ne pas la rejouer si l'activity est recréée plus tard.
            intent.removeExtra("openAudioPath")
            intent.removeExtra("openAudioName")
            return
        }
        if (intent.getBooleanExtra("openBlazeAudio", false) ||
            intent.component?.className == "$packageName.BlazeAudioLauncherActivity") {
            getSharedPreferences("launcher_requests", MODE_PRIVATE)
                .edit()
                .putBoolean("pendingOpenBlazeAudio", true)
                .putLong("pendingOpenBlazeAudioAt", System.currentTimeMillis())
                .apply()
            consumePendingBlazeAudioLaunch()
            intent.removeExtra("openBlazeAudio")
            return
        }
        if (intent.getBooleanExtra("openBlazeGallery", false) ||
            intent.component?.className == "$packageName.BlazeGalleryLauncherActivity") {
            getSharedPreferences("launcher_requests", MODE_PRIVATE)
                .edit()
                .putBoolean("pendingOpenBlazeGallery", true)
                .putLong("pendingOpenBlazeGalleryAt", System.currentTimeMillis())
                .apply()
            consumePendingBlazeGalleryLaunch()
            intent.removeExtra("openBlazeGallery")
            return
        }
        val requestedTab = intent.getIntExtra("requestedTab", -1)
        if (requestedTab in 1..4) {
            // Navigation explicite depuis les navigateurs : revenir à l'accueil et activer
            // l'onglet demandé (1=Local, 2=Réseau, 3=Blaze Gallery, 4=Audio). Le délai laisse le
            // NavHost recréer HomeFragment si l'Activity vient d'être remise au premier plan.
            handler.postDelayed({ switchToTab(requestedTab) }, 300)
            intent.removeExtra("requestedTab")
        }
    }


    private fun handleBlazePartyInvite(intent: Intent): Boolean {
        val data = intent.data ?: return false
        val connection = fr.retrospare.blazeplayer.player.PartyProtocol.parse(data.toString())
        if (connection == null) {
            val scheme = data.scheme.orEmpty()
            val looksLikePartyLink = scheme == "bp" || scheme == "blazeparty" || scheme == "intent" ||
                data.host == "blazeparty.local"
            if (!looksLikePartyLink) return false
            android.util.Log.w("MainActivity", "Lien Blaze Party invalide/incomplet : $data")
            android.widget.Toast.makeText(this, getString(fr.retrospare.blazeplayer.R.string.blaze_party_scan_unavailable), android.widget.Toast.LENGTH_LONG).show()
            intent.data = null
            intent.removeExtra("blazePartyInvite")
            return true
        }
        val partyPayload = data.toString()
        intent.data = null
        intent.removeExtra("blazePartyInvite")

        // Le contrôle DataStore est asynchrone : aucun runBlocking ne doit immobiliser le thread
        // principal lors d'un démarrage à froid ou de l'ouverture d'un lien Blaze Party.
        lifecycleScope.launch {
            val hasAudioAccess = fr.retrospare.blazeplayer.paywall.FeatureAccess.isProPlus(userRepository)
            if (!hasAudioAccess) {
                openPaywall()
                return@launch
            }

            fr.retrospare.blazeplayer.player.BlazePartyVoteManager.setHost(this@MainActivity, false)
            fr.retrospare.blazeplayer.player.BlazePartyVoteManager.saveConnection(this@MainActivity, connection)
            fr.retrospare.blazeplayer.player.BlazePartyVoteManager.saveSessionPayload(this@MainActivity, partyPayload)
            getSharedPreferences("launcher_requests", MODE_PRIVATE)
                .edit()
                .putBoolean("pendingOpenBlazeAudio", true)
                .putLong("pendingOpenBlazeAudioAt", System.currentTimeMillis())
                .putBoolean("pendingOpenBlazePartySheet", true)
                .putLong("pendingOpenBlazePartySheetAt", System.currentTimeMillis())
                .apply()
            // Ne pas afficher "Blaze Party rejoint" ici : à ce stade le QR est seulement parsé et
            // mémorisé. La vraie confirmation arrive depuis AudioPlayerFragment uniquement après un
            // /join + snapshot /state valides.
            handler.postDelayed({
                openBlazeAudio()
                showBlazePartyNicknameDialogFromInvite()
            }, 120L)
        }
        return true
    }


    private fun showBlazePartyNicknameDialogFromInvite() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setSingleLine(true)
            hint = getString(fr.retrospare.blazeplayer.R.string.blaze_party_nickname_hint)
            setText(fr.retrospare.blazeplayer.player.BlazePartyVoteManager.getNickname(this@MainActivity).takeIf { it != getString(fr.retrospare.blazeplayer.R.string.blaze_party_default_host) && it != "Hôte" }.orEmpty())
        }
        val density = resources.displayMetrics.density
        val box = com.google.android.material.textfield.TextInputLayout(this).apply {
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), 0)
            hint = getString(fr.retrospare.blazeplayer.R.string.blaze_party_nickname)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(fr.retrospare.blazeplayer.R.string.blaze_party_nickname_title))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                fr.retrospare.blazeplayer.player.BlazePartyVoteManager.saveNickname(this, input.text?.toString().orEmpty())
            }
            .setCancelable(false)
            .showPremium()
    }

    private fun handleExternalAudioLaunchIntent(intent: Intent): Boolean {
        val path = intent.getStringExtra("externalAudioPath") ?: return false
        val name = intent.getStringExtra("externalAudioName")
            ?: intent.data?.lastPathSegment?.substringAfterLast('/')
            ?: "Audio"
        startExternalAudioInMain(path, name, intent.data)
        intent.removeExtra("externalAudioPath")
        intent.removeExtra("externalAudioName")
        consumeExternalIntent(intent)
        return true
    }

    private fun startExternalAudioInMain(path: String, name: String, grantUri: android.net.Uri?) {
        lifecycleScope.launch {
            if (!fr.retrospare.blazeplayer.paywall.FeatureAccess.isProPlus(userRepository)) {
                openPaywall()
                return@launch
            }
            try {
                if (grantUri?.scheme == android.content.ContentResolver.SCHEME_CONTENT) {
                    grantUriPermission(packageName, grantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) {}

            startService(Intent(this@MainActivity, fr.retrospare.blazeplayer.player.BlazePlayerService::class.java).apply {
                action = fr.retrospare.blazeplayer.player.BlazePlayerService.ACTION_PLAY_EXTERNAL_AUDIO
                putExtra(fr.retrospare.blazeplayer.player.BlazePlayerService.EXTRA_EXTERNAL_AUDIO_PATH, path)
                putExtra(fr.retrospare.blazeplayer.player.BlazePlayerService.EXTRA_EXTERNAL_AUDIO_NAME, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (grantUri != null) {
                    data = grantUri
                    clipData = android.content.ClipData.newUri(contentResolver, name, grantUri)
                }
            })

            handler.postDelayed({
                openBlazeAudio()
                miniPlayerVm.refresh()
            }, 120L)
        }
    }

    /** Ouvre les fichiers envoyés par Android depuis Galerie / explorateur de fichiers
     *  (ACTION_VIEW “Ouvrir avec”, ACTION_SEND/SEND_MULTIPLE “Partager”). */
    private fun handleExternalViewIntent(intent: Intent): Boolean {
        val external = fr.retrospare.blazeplayer.player.ExternalMediaIntentUtils.fromExternalIntent(this, intent)
            ?: return false
        try {
            if (external.uri.scheme == android.content.ContentResolver.SCHEME_CONTENT) {
                grantUriPermission(packageName, external.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {}

        when (external.kind) {
            fr.retrospare.blazeplayer.player.ExternalMediaIntentUtils.ExternalMedia.Kind.AUDIO -> {
                // Les fichiers audio externes doivent s'ouvrir dans l'interface principale afin de
                // conserver la barre d'onglets (Local / Réseau / Blaze Gallery / Blaze Audio). On ne
                // passe plus par AudioPlayerActivity plein écran : MainActivity démarre le
                // MediaSessionService stable, qui remplace strictement la file par le fichier cliqué,
                // puis affiche l'onglet Blaze Audio.
                startExternalAudioInMain(external.path, external.name, external.uri)
                consumeExternalIntent(intent)
                return true
            }
            fr.retrospare.blazeplayer.player.ExternalMediaIntentUtils.ExternalMedia.Kind.VIDEO -> {
                val videoIntent = Intent(this, fr.retrospare.blazeplayer.player.PlayerActivity::class.java).apply {
                    putExtra("mediaPath", external.path)
                    putExtra("mediaName", external.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    data = external.uri
                    clipData = android.content.ClipData.newUri(contentResolver, external.name, external.uri)
                }
                startActivity(videoIntent)
                consumeExternalIntent(intent)
                return true
            }
            fr.retrospare.blazeplayer.player.ExternalMediaIntentUtils.ExternalMedia.Kind.UNKNOWN -> {
                // Fallback : on garde le comportement le plus permissif possible.
                fr.retrospare.blazeplayer.player.PlayerRouter.open(this, external.path, external.name)
                consumeExternalIntent(intent)
                return true
            }
        }
    }

    private fun consumeExternalIntent(intent: Intent) {
        intent.data = null
        intent.clipData = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.action = null
        intent.type = null
    }

    private fun openAudioPlayerWithRetry(path: String, name: String, attempt: Int = 0) {
        val opened = openAudioPlayer(path, name)
        if (!opened && attempt < 10) {
            handler.postDelayed({ openAudioPlayerWithRetry(path, name, attempt + 1) }, 200L)
        }
    }

    private fun openAudioPlayer(path: String, name: String): Boolean {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        val homeFragment = navHost?.childFragmentManager?.fragments
            ?.filterIsInstance<fr.retrospare.blazeplayer.home.HomeFragment>()
            ?.firstOrNull()
        return if (homeFragment != null) {
            homeFragment.openAudioPlayer(path, name)
            true
        } else {
            false
        }
    }

    private fun switchToTab(index: Int): Boolean {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        val homeFragment = navHost?.childFragmentManager?.fragments
            ?.filterIsInstance<fr.retrospare.blazeplayer.home.HomeFragment>()
            ?.firstOrNull()
        return if (homeFragment != null) {
            homeFragment.switchToTab(index)
            true
        } else {
            false
        }
    }

    private fun openBlazeAudio() {
        getSharedPreferences("launcher_requests", MODE_PRIVATE)
            .edit()
            .putBoolean("pendingOpenBlazeAudio", true)
            .putLong("pendingOpenBlazeAudioAt", System.currentTimeMillis())
            .apply()
        consumePendingBlazeAudioLaunch()
    }

    override fun onDestroy() {
        miniPlayerAccessJob?.cancel()
        handler.removeCallbacks(miniTimeTicker)
        stopMiniPlayingAnimation(keepVisible = false)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialise le CastContext juste après avoir posté le premier frame de la fenêtre (et non
        // plus AVANT setContentView) : cet appel peut être lent (vérif Play Services, découverte
        // des routes Cast) et le faire de façon bloquante avant même la création de la fenêtre
        // retardait son tout premier rendu — symptôme classique d'appli qu'il faut lancer deux fois
        // depuis l'icône (le 1er tap démarre le process sans rien afficher à temps). On le garde
        // suffisamment tôt pour ne pas casser la découverte Chromecast (cf. contexte plus bas).
        window.decorView.post {
            try {
                com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "CastContext init failed", e)
            }
        }
        // Edge-to-edge : le contenu gère lui-même les insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Applique le padding top/bottom pour éviter les barres système
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Applique seulement le bas pour la barre nav, le haut est géré par chaque fragment
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }
        setupNavigation()
        setupMiniPlayer()
        initializeTrialAccess()
        maybeRequestBlazeVideoPermissionsOnFirstLaunch()
        if (!handlePaywallIntent(intent)) handleAudioIntent(intent)
    }

    private fun initializeTrialAccess() {
        lifecycleScope.launch {
            val state = userRepository.ensureTrialStarted()
            applyResolvedAccessState(state)
            observePurchasedAccessChanges()
        }
    }

    /** Observe en continu les écritures atomiques faites après un achat, une restauration ou la
     * synchronisation silencieuse RevenueCat. Le déblocage n'attend donc ni onResume(), ni un
     * changement d'onglet, ni une nouvelle ouverture du paywall. */
    private fun observePurchasedAccessChanges() {
        if (accessObserverStarted) return
        accessObserverStarted = true
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                userRepository.accessStateFlow.collect { state ->
                    applyResolvedAccessState(state)
                }
            }
        }
    }

    private fun applyResolvedAccessState(
        state: fr.retrospare.blazeplayer.data.repository.SubscriptionAccessState
    ) {
        if (!state.hasProPlusAccess) {
            stopService(Intent(this, fr.retrospare.blazeplayer.player.BlazePlayerService::class.java))
        }
        fr.retrospare.blazeplayer.paywall.TrialReminderScheduler.sync(this, state)
        updateMiniPlayerConnection(state)
    }

    private fun refreshTrialAccess() {
        lifecycleScope.launch {
            applyResolvedAccessState(userRepository.currentAccessState())
        }
    }

    private fun handlePaywallIntent(intent: Intent): Boolean {
        if (!intent.getBooleanExtra(fr.retrospare.blazeplayer.paywall.TrialReminderScheduler.EXTRA_OPEN_PAYWALL, false)) return false
        intent.removeExtra(fr.retrospare.blazeplayer.paywall.TrialReminderScheduler.EXTRA_OPEN_PAYWALL)
        binding.root.post { openPaywall() }
        return true
    }

    private fun openPaywall() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val nav = navHost.navController
        if (nav.currentDestination?.id == R.id.paywallFragment) return
        runCatching { nav.navigate(R.id.paywallFragment) }
            .onFailure {
                nav.popBackStack(R.id.homeFragment, false)
                runCatching { nav.navigate(R.id.action_home_to_paywall) }
            }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        graph.setStartDestination(
            R.id.homeFragment
        )
        navController.setGraph(graph, null)
    }

    /** Au lancement de l'app, on ne demande plus toutes les permissions de toute l'app.
     *  L'ouverture par défaut est Blaze Video : seules les permissions utiles à la vidéo locale
     *  et aux contrôles de lecture sont donc demandées ici. Blaze Gallery et Blaze Audio gèrent
     *  leurs permissions au premier accès à leur onglet respectif depuis HomeFragment. */
    private fun blazeVideoPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun missingPermissions(permissions: Array<String>): Array<String> =
        permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()

    private var lastPermissionRequest: Array<String> = emptyArray()

    private fun launchRuntimePermissions(permissions: Array<String>) {
        val notGranted = missingPermissions(permissions)
        if (notGranted.isEmpty()) return
        lastPermissionRequest = notGranted
        permissionLauncher.launch(notGranted)
    }

    private fun launchTargetsAnotherBlazeModule(): Boolean {
        val requestedTab = intent?.getIntExtra("requestedTab", -1) ?: -1
        return intent?.getBooleanExtra("openBlazeGallery", false) == true ||
            intent?.getBooleanExtra("openBlazeAudio", false) == true ||
            requestedTab == 3 || requestedTab == 4
    }

    private fun maybeRequestBlazeVideoPermissionsOnFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_ONBOARDING, android.content.Context.MODE_PRIVATE)
        val requested = mutableListOf<String>()
        val editor = prefs.edit()

        // Depuis les icônes Blaze Gallery / Blaze Audio, ne pas demander la permission vidéo.
        // La permission de notification reste toutefois indépendante : elle est nécessaire au
        // rappel de fin d'essai Pro+ programmé 48 h avant l'échéance.
        if (!launchTargetsAnotherBlazeModule() &&
            !prefs.getBoolean(KEY_VIDEO_PERMISSIONS_PROMPTED, false)
        ) {
            requested += blazeVideoPermissions()
            editor.putBoolean(KEY_VIDEO_PERMISSIONS_PROMPTED, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, false)
        ) {
            requested += Manifest.permission.POST_NOTIFICATIONS
            editor.putBoolean(KEY_NOTIFICATION_PERMISSION_PROMPTED, true)
        }

        val missing = missingPermissions(requested.distinct().toTypedArray())
        if (requested.isNotEmpty()) editor.apply()
        if (missing.isNotEmpty()) launchRuntimePermissions(missing)
    }

    private fun showPermissionRationale() {
        val retryPermissions = lastPermissionRequest.takeIf { it.isNotEmpty() } ?: blazeVideoPermissions()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_permission_needed))
            .setMessage(getString(R.string.dialog_permission_message))
            .setPositiveButton(getString(R.string.action_allow)) { _, _ -> launchRuntimePermissions(retryPermissions) }
            .setNegativeButton(getString(R.string.action_ignore), null)
            .showPremium()
    }

    companion object {
        private const val PREFS_ONBOARDING = "blaze_onboarding"
        private const val KEY_VIDEO_PERMISSIONS_PROMPTED = "video_permissions_prompted"
        private const val KEY_NOTIFICATION_PERMISSION_PROMPTED = "notification_permission_prompted"
    }
}
