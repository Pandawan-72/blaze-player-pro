package fr.retrospare.blazeplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
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

    private var miniController: androidx.media3.session.MediaController? = null

    private fun connectMiniPlayer() {
        val token = androidx.media3.session.SessionToken(
            this,
            android.content.ComponentName(this, fr.retrospare.blazeplayer.player.BlazePlayerService::class.java)
        )
        val future = androidx.media3.session.MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                miniController = future.get()
                miniController?.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { refreshMiniPlayer() }
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { refreshMiniPlayer() }
                })
                refreshMiniPlayer()
            } catch (e: Exception) {
                binding.miniPlayerBar.visibility = android.view.View.GONE
            }
        }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
    }

    fun refreshMiniPlayer() {
        val ctrl = miniController ?: return
        val meta = ctrl.currentMediaItem?.mediaMetadata
        if (meta != null && ctrl.mediaItemCount > 0) {
            binding.miniPlayerBar.visibility = android.view.View.VISIBLE
            binding.tvMiniTitle.text = meta.title ?: "Titre inconnu"
            binding.tvMiniArtist.text = meta.artist?.toString() ?: ""
            val art = meta.artworkData
            if (art != null) binding.ivMiniArtwork.setImageBitmap(
                android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size))
            binding.btnMiniPlayPause.setImageResource(
                if (ctrl.isPlaying) fr.retrospare.blazeplayer.R.drawable.ic_pause
                else fr.retrospare.blazeplayer.R.drawable.ic_play
            )
            binding.btnMiniPlayPause.setOnClickListener {
                if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
            }
            binding.btnMiniPrev.setOnClickListener { ctrl.seekToPreviousMediaItem() }
            binding.btnMiniNext.setOnClickListener { ctrl.seekToNextMediaItem() }
            binding.miniPlayerBar.setOnClickListener { openBlazeAudio() }
        } else {
            binding.miniPlayerBar.visibility = android.view.View.GONE
        }
    }

    fun hideMiniPlayer() {
        binding.miniPlayerBar.visibility = android.view.View.GONE
    }

    fun showMiniPlayer() {
        refreshMiniPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (miniController == null) connectMiniPlayer()
        else refreshMiniPlayer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("openBlazeAudio", false)) {
            handler.postDelayed({ openBlazeAudio() }, 300)
        }
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
        connectMiniPlayer()
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
