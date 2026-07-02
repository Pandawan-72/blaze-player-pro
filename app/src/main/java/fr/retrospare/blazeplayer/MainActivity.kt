package fr.retrospare.blazeplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import androidx.activity.viewModels
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            showPermissionRationale()
        }
    }

    private val miniPlayerVm: fr.retrospare.blazeplayer.player.MiniPlayerViewModel by viewModels()

    private fun setupMiniPlayer() {
        miniPlayerVm.connect()

        // Observe le state — collecte sur toute la durée de vie de l'Activity
        lifecycleScope.launch {
            miniPlayerVm.state.collect { state ->
                applyMiniPlayerState(state)
            }
        }

        binding.btnMiniPlayPause.setOnClickListener {
            val c = miniPlayerVm.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.btnMiniPrev.setOnClickListener { miniPlayerVm.controller?.seekToPreviousMediaItem() }
        binding.btnMiniNext.setOnClickListener { miniPlayerVm.controller?.seekToNextMediaItem() }
        binding.miniPlayerBar.setOnClickListener { openBlazeAudio() }
        setupMiniPlayerDrag()
    }

    /** Applique l'état courant du mini player à la vue. Extrait de setupMiniPlayer() pour pouvoir
     *  être rappelé depuis onResume() : un StateFlow ne redéclenche pas le collector si la valeur
     *  n'a pas changé, ce qui pouvait laisser le mini player caché (retour d'une vidéo locale,
     *  réveil de l'écran...) tant qu'aucun changement d'onglet ne forçait une nouvelle valeur. */
    private fun applyMiniPlayerState(state: fr.retrospare.blazeplayer.player.MiniPlayerState) {
        binding.miniPlayerBar.visibility =
            if (state.isVisible) android.view.View.VISIBLE else android.view.View.GONE
        if (state.isVisible) {
            binding.tvMiniTitle.text = state.title.ifEmpty { "Titre inconnu" }
            binding.tvMiniArtist.text = state.artist
            val art = state.artworkData
            if (art != null) binding.ivMiniArtwork.setImageBitmap(
                android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size))
            binding.btnMiniPlayPause.setImageResource(
                if (state.isPlaying) fr.retrospare.blazeplayer.R.drawable.ic_pause
                else fr.retrospare.blazeplayer.R.drawable.ic_play
            )
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
        miniPlayerVm.refresh()
        if (::binding.isInitialized) {
            applyMiniPlayerState(miniPlayerVm.state.value)
            binding.miniPlayerBar.bringToFront()
            binding.miniPlayerBar.requestLayout()
        }
    }

    override fun onResume() {
        super.onResume()
        miniPlayerVm.refresh()
        if (::binding.isInitialized) {
            applyMiniPlayerState(miniPlayerVm.state.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAudioIntent(intent)
    }

    /** Traite les intents demandant l'ouverture d'un fichier audio (depuis PlayerRouter) ou le
     *  retour à l'écran audio (depuis la notification/sessionActivity de BlazePlayerService). */
    private fun handleAudioIntent(intent: Intent) {
        val audioPath = intent.getStringExtra("openAudioPath")
        if (audioPath != null) {
            val audioName = intent.getStringExtra("openAudioName") ?: ""
            handler.postDelayed({ openAudioPlayer(audioPath, audioName) }, 300)
            // Consomme l'extra pour ne pas la rejouer si l'activity est recréée plus tard.
            intent.removeExtra("openAudioPath")
            intent.removeExtra("openAudioName")
            return
        }
        if (intent.getBooleanExtra("openBlazeAudio", false)) {
            handler.postDelayed({ openBlazeAudio() }, 300)
        }
        val requestedTab = intent.getIntExtra("requestedTab", -1)
        if (requestedTab in 1..3) {
            handler.postDelayed({ switchToTab(requestedTab) }, 300)
        }
    }

    private fun openAudioPlayer(path: String, name: String) {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        val homeFragment = navHost?.childFragmentManager?.fragments
            ?.filterIsInstance<fr.retrospare.blazeplayer.home.HomeFragment>()
            ?.firstOrNull()
        homeFragment?.openAudioPlayer(path, name)
    }

    private fun switchToTab(index: Int) {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        val homeFragment = navHost?.childFragmentManager?.fragments
            ?.filterIsInstance<fr.retrospare.blazeplayer.home.HomeFragment>()
            ?.firstOrNull()
        homeFragment?.switchToTab(index)
    }

    private fun openBlazeAudio() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        val homeFragment = navHost?.childFragmentManager?.fragments
            ?.filterIsInstance<fr.retrospare.blazeplayer.home.HomeFragment>()
            ?.firstOrNull()
        homeFragment?.switchToAudioTab()
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
        requestStoragePermissions()
        // Connecte le mini player seulement si activé dans les préférences
        setupMiniPlayer()
        handleAudioIntent(intent)
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

    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun showPermissionRationale() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permission nécessaire")
            .setMessage("Blaze Player a besoin d'accéder à vos fichiers vidéo pour fonctionner.")
            .setPositiveButton("Autoriser") { _, _ -> requestStoragePermissions() }
            .setNegativeButton("Ignorer", null)
            .show()
    }
}
