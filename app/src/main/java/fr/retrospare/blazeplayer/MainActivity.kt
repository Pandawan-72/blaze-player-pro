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
        }

        binding.btnMiniPlayPause.setOnClickListener {
            val c = miniPlayerVm.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        binding.btnMiniPrev.setOnClickListener { miniPlayerVm.controller?.seekToPreviousMediaItem() }
        binding.btnMiniNext.setOnClickListener { miniPlayerVm.controller?.seekToNextMediaItem() }
        binding.miniPlayerBar.setOnClickListener { openBlazeAudio() }
    }

    fun setInAudioPlayer(inPlayer: Boolean) {
        miniPlayerVm.setInAudioPlayer(inPlayer)
    }

    fun getMiniPlayerViewModel() = miniPlayerVm

    override fun onResume() {
        super.onResume()
        miniPlayerVm.refresh()
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
        // Initialise explicitement le CastContext en tout premier (avant tout autre code) - sans cet
        // appel precoce, la decouverte des appareils Chromecast peut ne jamais demarrer.
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "CastContext init failed", e)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
                Manifest.permission.READ_MEDIA_AUDIO
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
