package fr.retrospare.blazeplayer.youtube

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import fr.retrospare.blazeplayer.R

/** Lecteur YouTube — utilise android-youtube-player (MIT, PierfrancescoSoffritti), une
 *  bibliothèque éprouvée qui embarque le lecteur officiel IFrame de Google et gère en interne
 *  toute la configuration WebView/referrer nécessaire. Trois tentatives de WebView maison ont
 *  toutes échoué avec des erreurs 150/152/153 (validation d'intégration YouTube), un problème
 *  bien connu que cette bibliothèque résout spécifiquement. On n'extrait toujours JAMAIS l'URL du
 *  flux vidéo brut pour la lire dans Media3/ExoPlayer : cela violerait les CGU de YouTube — cette
 *  activité affiche uniquement le lecteur officiel. En quittant, on revient naturellement sur
 *  l'onglet Blaze Tube déjà actif dans HomeFragment.
 *
 *  Support de la navigation suivant/précédent quand la vidéo fait partie d'une playlist Blaze
 *  Tube (1/2/3) : la liste complète des ids/titres est transmise via Intent, et un seul
 *  YouTubePlayer est réutilisé pour charger la vidéo suivante/précédente sans réinitialiser toute
 *  l'activité (recharge juste la vidéo affichée, garde le lecteur natif ouvert).
 *
 *  Support du mode PiP (image dans l'image) : en quittant l'app pendant la lecture (bouton
 *  Accueil, changement d'app), la vidéo continue dans une petite fenêtre flottante par-dessus le
 *  reste du système, plutôt que de s'arrêter. Nos propres boutons (fermer, précédent, suivant)
 *  sont masqués dans ce mode — trop petit pour être utilisables, et Android fournit déjà ses
 *  propres contrôles de fenêtre PiP par-dessus. */
class YouTubePlayerActivity : AppCompatActivity() {

    private lateinit var youTubePlayerView: YouTubePlayerView
    private var youTubePlayer: YouTubePlayer? = null
    private var isCurrentlyPlaying = false

    private var playlistIds: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var currentIndex: Int = -1

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_THUMBNAIL = "thumbnail"
        const val EXTRA_PLAYLIST_IDS = "playlistIds"
        const val EXTRA_PLAYLIST_TITLES = "playlistTitles"
        const val EXTRA_PLAYLIST_INDEX = "playlistIndex"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mode immersif complet : l'ancien FLAG_FULLSCREEN ne masquait que la barre de statut,
        // pas la barre de navigation système, qui rognait les contrôles du lecteur YouTube en
        // bas de l'écran. WindowInsetsControllerCompat masque les deux, avec réapparition
        // temporaire au balayage (comportement standard des lecteurs vidéo plein écran).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_youtube_player)
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (videoId.isNullOrBlank()) {
            finish()
            return
        }

        playlistIds = intent.getStringArrayExtra(EXTRA_PLAYLIST_IDS)?.toList().orEmpty()
        playlistTitles = intent.getStringArrayExtra(EXTRA_PLAYLIST_TITLES)?.toList().orEmpty()
        currentIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, -1)

        youTubePlayerView = findViewById(R.id.youtubePlayerView)
        lifecycle.addObserver(youTubePlayerView)

        youTubePlayerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(player: YouTubePlayer) {
                youTubePlayer = player
                player.loadVideo(videoId, 0f)
                addCurrentToHistory(videoId, intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_CHANNEL).orEmpty(), intent.getStringExtra(EXTRA_THUMBNAIL).orEmpty())
            }

            override fun onStateChange(player: YouTubePlayer, state: PlayerConstants.PlayerState) {
                isCurrentlyPlaying = state == PlayerConstants.PlayerState.PLAYING
                if (state == PlayerConstants.PlayerState.ENDED) {
                    // Enchaîne automatiquement sur la suivante si on est dans une playlist,
                    // sinon ferme comme avant.
                    if (hasNext()) goToRelative(1) else finish()
                }
            }

            override fun onError(player: YouTubePlayer, error: PlayerConstants.PlayerError) {
                android.util.Log.e("YouTubePlayerActivity", "Erreur lecteur YouTube : $error")
            }
        })

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        setupPlaylistNavigation()
    }

    private fun hasPrevious(): Boolean = playlistIds.size > 1 && currentIndex > 0
    private fun hasNext(): Boolean = playlistIds.size > 1 && currentIndex < playlistIds.size - 1

    private fun setupPlaylistNavigation() {
        val btnPrevious = findViewById<View>(R.id.btnYoutubePrevious)
        val btnNext = findViewById<View>(R.id.btnYoutubeNext)
        if (playlistIds.size <= 1 || currentIndex < 0) {
            btnPrevious.visibility = View.GONE
            btnNext.visibility = View.GONE
            return
        }
        btnPrevious.visibility = View.VISIBLE
        btnNext.visibility = View.VISIBLE
        updateNavigationButtonsState()
        btnPrevious.setOnClickListener { if (hasPrevious()) goToRelative(-1) }
        btnNext.setOnClickListener { if (hasNext()) goToRelative(1) }
    }

    private fun updateNavigationButtonsState() {
        findViewById<View>(R.id.btnYoutubePrevious).apply {
            isEnabled = hasPrevious()
            alpha = if (hasPrevious()) 1f else 0.35f
        }
        findViewById<View>(R.id.btnYoutubeNext).apply {
            isEnabled = hasNext()
            alpha = if (hasNext()) 1f else 0.35f
        }
    }

    private fun goToRelative(delta: Int) {
        val newIndex = currentIndex + delta
        if (newIndex !in playlistIds.indices) return
        currentIndex = newIndex
        val videoId = playlistIds[newIndex]
        val title = playlistTitles.getOrElse(newIndex) { "" }
        val enriched = YouTubeLibrary.enrichFromCache(this, YouTubeVideoItem(videoId = videoId, title = title, channelTitle = "", thumbnailUrl = ""))
        youTubePlayer?.loadVideo(enriched.videoId, 0f)
        addCurrentToHistory(enriched.videoId, enriched.title, enriched.channelTitle, enriched.thumbnailUrl)
        updateNavigationButtonsState()
    }

    private fun addCurrentToHistory(videoId: String, title: String, channel: String, thumbnail: String) {
        // Historique : enregistré à l'ouverture de chaque vidéo (y compris lors d'un
        // suivant/précédent), même logique que l'historique local/réseau existant, qui trace
        // l'ouverture, pas le visionnage complet.
        YouTubeLibrary.addToHistory(
            this,
            YouTubeVideoItem(videoId = videoId, title = title, channelTitle = channel, thumbnailUrl = thumbnail)
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Appelé quand l'utilisateur quitte l'app (bouton Accueil, changement d'app récente) — PAS
     *  au bouton retour, qui doit continuer à fermer normalement. Bascule en PiP si une vidéo est
     *  activement en cours de lecture. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isCurrentlyPlaying && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(buildPipParams())
            } catch (e: Exception) {
                android.util.Log.w("YouTubePlayerActivity", "Échec entrée en PiP", e)
            }
        }
    }

    private fun buildPipParams(): android.app.PictureInPictureParams =
        android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .build()

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Nos boutons (fermer, précédent, suivant) n'ont pas leur place dans la petite fenêtre
        // PiP : Android affiche déjà ses propres contrôles de fenêtre par-dessus.
        val visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnClose).visibility = visibility
        if (playlistIds.size > 1 && currentIndex >= 0) {
            findViewById<View>(R.id.btnYoutubePrevious).visibility = visibility
            findViewById<View>(R.id.btnYoutubeNext).visibility = visibility
        }
    }
}
