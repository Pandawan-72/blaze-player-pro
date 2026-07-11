package fr.retrospare.blazeplayer.player

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.SharedPreferences
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.net.wifi.WifiManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service de lecture AUDIO basé sur [MediaSessionService] : c'est l'unique source de vérité pour
 * la lecture audio de l'application. Toute UI (AudioPlayerFragment, MiniPlayer, notification
 * système, Android Auto...) doit communiquer avec le player exclusivement via un
 * [androidx.media3.session.MediaController] connecté à ce service.
 *
 * On évite délibérément toute référence statique directe au player ou au service (pattern
 * `companion object { var instance }`) : c'est un anti-pattern Media3 qui casse l'encapsulation
 * de la session et ne fonctionne pas si le contrôleur tourne dans un autre process. Le seul état
 * interne qui n'est pas exposé par l'API [androidx.media3.common.Player] standard (l'audioSessionId,
 * nécessaire pour brancher l'égaliseur système) est exposé via une commande de session personnalisée.
 *
 * La notification média (lockscreen, barre de notif, contrôles Bluetooth/casque) est entièrement
 * gérée automatiquement par [MediaSessionService] via son [androidx.media3.session.MediaNotification.Provider]
 * par défaut — il ne faut surtout pas la dupliquer manuellement avec une notification "maison".
 */
@UnstableApi
class BlazePlayerService : MediaSessionService() {

    companion object {
        /** Commande de session permettant à un [androidx.media3.session.MediaController] de
         *  récupérer l'audioSessionId courant du player, utilisé pour brancher l'égaliseur système
         *  (android.media.audiofx). Non exposé par l'API Player standard. */
        const val COMMAND_GET_AUDIO_SESSION_ID = "fr.retrospare.blazeplayer.GET_AUDIO_SESSION_ID"
        const val COMMAND_PLAY_EXTERNAL_AUDIO = "fr.retrospare.blazeplayer.PLAY_EXTERNAL_AUDIO"
        const val ACTION_PLAY_EXTERNAL_AUDIO = "fr.retrospare.blazeplayer.action.PLAY_EXTERNAL_AUDIO"
        const val ACTION_PLAY_AUDIO_QUEUE = "fr.retrospare.blazeplayer.action.PLAY_AUDIO_QUEUE"
        const val ACTION_APPEND_AUDIO_QUEUE = "fr.retrospare.blazeplayer.action.APPEND_AUDIO_QUEUE"
        const val ACTION_APPEND_AUDIO_QUEUE_AND_PLAY = "fr.retrospare.blazeplayer.action.APPEND_AUDIO_QUEUE_AND_PLAY"
        const val EXTRA_AUDIO_SESSION_ID = "audioSessionId"
        const val EXTRA_EXTERNAL_AUDIO_PATH = "path"
        const val EXTRA_EXTERNAL_AUDIO_NAME = "name"
        const val EXTRA_AUDIO_QUEUE_PATHS = "paths"
        const val EXTRA_AUDIO_QUEUE_NAMES = "names"
        const val EXTRA_AUDIO_QUEUE_INDEX = "index"

        /** Commandes de session pour piloter le serveur réseau Blaze Party hébergé par CE service
         *  (et non par AudioPlayerFragment), afin qu'une session hôte survive à la fermeture de
         *  l'écran audio tant que la lecture — donc ce service — reste active en arrière-plan. */
        const val COMMAND_PARTY_START_HOST = "fr.retrospare.blazeplayer.PARTY_START_HOST"
        const val COMMAND_PARTY_STOP_HOST = "fr.retrospare.blazeplayer.PARTY_STOP_HOST"
        // Actions de service en secours : elles garantissent que NanoHTTPD démarre même si le
        // MediaController n'est pas encore prêt au moment où l'hôte affiche le QR.
        const val ACTION_PARTY_START_HOST = "fr.retrospare.blazeplayer.action.PARTY_START_HOST"
        const val ACTION_PARTY_STOP_HOST = "fr.retrospare.blazeplayer.action.PARTY_STOP_HOST"
        const val EXTRA_PARTY_TOKEN = "partyToken"

        // ID de notification et channel dédiés : Media3 utilise le même ID par défaut pour tous
        // les MediaSessionService de l'app si non personnalisé, ce qui faisait que la notification
        // vidéo écrasait purement et simplement la notification audio (même slot de notification).
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "blaze_audio_channel"

        /** Signal léger lu par la bibliothèque audio : quand une piste joue, les scans
         *  métadonnées/covers doivent rester strictement non prioritaires pour éviter
         *  toute coupure, surtout avec des fichiers sur NAS. */
        @Volatile var isAudioPlaybackActive: Boolean = false
            private set
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var sessionPlayer: Player? = null
    private var eqManager: EqualizerManager? = null
    private var equalizerSessionId: Int = 0
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = 0
    private var pcmAudioProcessor: BlazePcmAudioProcessor? = null
    private lateinit var eqPrefs: SharedPreferences
    private lateinit var audioProPrefs: SharedPreferences
    private var audioProValues: AudioProSettings.Values = AudioProSettings.Values()
    private var currentReplayGainDb: Float = 0f
    private var playerVolumeAnimator: ValueAnimator? = null
    private var crossfadeInProgress = false
    private var crossfadeIndex = -1
    private val eqApplyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val partyStateHandler = Handler(Looper.getMainLooper())
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private val audioProListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        audioProValues = AudioProSettings.read(applicationContext)
        refreshReplayGainForCurrentItem()
        applyAudioProSettings()
    }
    private val eqPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == EqualizerManager.KEY_LOUDNESS || key == EqualizerManager.KEY_EQ_ENABLED) {
            applyLoudnessEnhancer(player?.audioSessionId ?: 0)
        }
        if (key == EqualizerManager.KEY_EQ_ENABLED) {
            player?.let { tryApplyHighQualityOutput(it) }
        }
    }
    private val crossfadeRunnable = object : Runnable {
        override fun run() {
            checkCrossfadeWindow()
            scheduleCrossfadeCheck()
        }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serveur HTTP local Blaze Party : vit ici (et non dans AudioPlayerFragment) précisément pour
    // continuer à répondre aux invités même quand l'utilisateur quitte l'écran audio, tant que la
    // lecture — donc ce service — tourne toujours en arrière-plan.
    private var partyHostServer: PartyHostServer? = null
    private var partyWifiLock: WifiManager.WifiLock? = null
    @Volatile private var partyStateSnapshot: PartyState? = null
    private val partyStateRefreshRunnable = object : Runnable {
        override fun run() {
            refreshPartyStateSnapshotNow()
            if (partyHostServer != null) {
                partyStateHandler.postDelayed(this, 1000L)
            }
        }
    }


    override fun onCreate() {
        super.onCreate()

        audioProPrefs = AudioProSettings.prefs(this)
        audioProValues = AudioProSettings.read(this)
        audioProPrefs.registerOnSharedPreferenceChangeListener(audioProListener)
        eqPrefs = getSharedPreferences(EqualizerManager.PREFS_NAME, Context.MODE_PRIVATE)
        eqPrefs.registerOnSharedPreferenceChangeListener(eqPreferenceListener)

        // Media3 recommande de garder le Player et la MediaSession dans le MediaSessionService.
        // On ne détruit/recrée plus le service pour chaque fichier externe : on remplace simplement
        // la playlist du Player dans cette session stable. Cela évite les blocages de bind/release
        // et les ANR au démarrage.

        // Notification/channel dédiés à l'audio, distincts de ceux de la vidéo (cf. constantes
        // ci-dessus) pour que les deux notifications coexistent sans se remplacer l'une l'autre.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(fr.retrospare.blazeplayer.R.string.notif_channel_audio)
                .build()
        )

        // Niveau 1 anti-ANR : ne pas initialiser SimpleCache dans onCreate(). SimpleCache peut
        // scanner/verrouiller le cache disque sur le thread principal au démarrage du service.
        // Pour la fiabilité debug, on privilégie un DataSource direct ; le cache pourra revenir plus
        // tard avec une initialisation lazy hors thread principal.
        val dataSourceFactory = BlazeDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        val centralPcmProcessor = BlazePcmAudioProcessor(this).also { pcmAudioProcessor = it }
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    // Un AudioProcessor PCM doit rester actif pour la balance, le mono, la largeur
                    // stéréo, le limiteur et la réverbération. La sortie float directe contournerait
                    // cette chaîne sur certains appareils, donc on garde ici la sortie entière.
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(centralPcmProcessor))
                    .build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        // Lecture audio réseau : on privilégie la stabilité sur SMB/Wi-Fi plutôt qu'un démarrage
        // ultra agressif. Les valeurs ci-dessous gardent un démarrage raisonnable, mais exigent
        // un tampon plus confortable après rebuffer afin d'éviter les micro-coupures et les arrêts
        // silencieux sur NAS/Wi-Fi instables.
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(
                /* minBufferMs = */ 45_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Garde le Wi-Fi actif pendant la lecture réseau (SMB) en arrière-plan/écran éteint.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        restoreEqualizerForPlayer(exoPlayer)
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                fr.retrospare.blazeplayer.debug.CrashReporter.log(
                    applicationContext,
                    "Audio player error: code=${error.errorCodeName}",
                    error
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    android.util.Log.i("BlazePlayerService", "Audio buffering…")
                }
                isAudioPlaybackActive = exoPlayer.playWhenReady &&
                    playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    playbackState != androidx.media3.common.Player.STATE_ENDED
            }

            override fun onEvents(
                player: androidx.media3.common.Player,
                events: androidx.media3.common.Player.Events
            ) {
                // Un changement de route ou une recréation de l'AudioTrack peut attribuer une
                // nouvelle session. Les AudioEffect natifs doivent alors être libérés puis
                // rattachés immédiatement à la session réellement active.
                if (events.contains(androidx.media3.common.Player.EVENT_AUDIO_SESSION_ID)) {
                    restoreEqualizerForPlayer(exoPlayer)
                }
            }
        })
        player = exoPlayer
        applyAudioProSettings(exoPlayer)

        // Isolation stricte audio/vidéo : la MediaSession audio expose UNIQUEMENT ExoPlayer local.
        // Aucun composant Chromecast côté audio : le lecteur audio et le
        // mini-player ne peuvent plus récupérer ni piloter un média vidéo casté.
        val audioSessionPlayer: Player = exoPlayer
        sessionPlayer = audioSessionPlayer

        val openIntent = PendingIntent.getActivity(
            this, 2001,
            Intent(this, fr.retrospare.blazeplayer.BlazeAudioLauncherActivity::class.java).apply {
                action = "fr.retrospare.blazeplayer.action.OPEN_BLAZE_AUDIO_FROM_NOTIFICATION"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("openBlazeAudio", true)
                putExtra("requestedTab", 4)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        audioSessionPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                persistAudioQueue()
                prepareReplayGainForItem(mediaItem)

                // Précharge le .LRC correspondant en parallèle de la préparation/lecture audio.
                // Le cache/verrou dans AudioLocalEnhancements évite une seconde ouverture lorsque
                // l'overlay du Fragment réclame les mêmes paroles quelques millisecondes après.
                val lyricsPath = mediaItem?.let { BlazePartyQueue.originalPathOf(it) }.orEmpty()
                if (lyricsPath.isNotBlank() && audioProValues.syncedLyrics) {
                    serviceScope.launch {
                        AudioLocalEnhancements.findLocalLyricsData(applicationContext, lyricsPath)
                    }
                }

                if (!crossfadeInProgress && audioProValues.crossfade) {
                    fadePlayerTo(AudioProSettings.playerVolume(audioProValues, currentReplayGainDb), (audioProValues.crossfadeDurationSec * 450L).coerceIn(180L, 1500L))
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isAudioPlaybackActive = isPlaying
                scheduleCrossfadeCheck()
            }

            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                if (events.contains(androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(androidx.media3.common.Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED) ||
                    events.contains(androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    persistAudioQueue()
                    scheduleCrossfadeCheck()
                }
            }
        })

        mediaSession = MediaSession.Builder(this, audioSessionPlayer)
            .setId("BlazeAudio")
            .setSessionActivity(openIntent)
            .setCallback(SessionCallback())
            .build()

        // Si une party était hébergée avant que le service ne soit recréé (process tué puis
        // relancé par Android, par exemple), on redémarre le serveur avec le même jeton que celui
        // déjà distribué via le QR, pour que les invités connectés n'aient rien à rescanner.
        if (BlazePartyVoteManager.isActive(applicationContext) && BlazePartyVoteManager.isHost(applicationContext)) {
            BlazePartyVoteManager.getHostToken(applicationContext)?.let { startPartyHostServer(it) }
        }
    }

    /** Démarre (ou redémarre) le serveur HTTP local Blaze Party. Vit dans ce service — et non dans
     *  AudioPlayerFragment — précisément pour continuer à répondre aux invités quand l'utilisateur
     *  quitte l'écran audio, tant que la lecture (donc ce service) reste active.
     *
     *  Point important : NanoHTTPD traite les requêtes sur ses propres threads, alors que Media3
     *  impose de lire ExoPlayer depuis son thread applicatif principal. Les versions précédentes
     *  construisaient l'état Party directement dans le callback HTTP, ce qui pouvait générer des
     *  erreurs intermittentes sur /state après un join pourtant réussi. On maintient désormais un
     *  snapshot rafraîchi côté main thread et le serveur ne fait que le sérialiser. */
    private fun startPartyHostServer(token: String) {
        stopPartyHostServer()
        // Démarre avec un snapshot sûr (sans accès au Player hors thread), puis le runnable main
        // ci-dessous injecte l'état complet dès que le serveur est lancé.
        partyStateSnapshot = BlazePartyQueue.buildState(applicationContext, null)
        try {
            partyHostServer = PartyHostServer(
                token = token,
                stateProvider = { currentPartyStateSnapshot() },
                onVoteReceived = { path, nickname, add ->
                    if (add) {
                        BlazePartyVoteManager.addVote(applicationContext, path, nickname)
                    } else {
                        BlazePartyVoteManager.removeVote(applicationContext, path, nickname)
                    }
                    refreshPartyStateSnapshotAsync()
                },
                onGuestJoined = {
                    BlazePartyVoteManager.markGuestConnected(applicationContext)
                    refreshPartyStateSnapshotAsync()
                }
            ).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            acquirePartyWifiLock()
            partyStateHandler.removeCallbacks(partyStateRefreshRunnable)
            partyStateHandler.post(partyStateRefreshRunnable)
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Impossible de démarrer PartyHostServer", e)
            partyHostServer = null
        }
    }

    private fun stopPartyHostServer() {
        partyStateHandler.removeCallbacks(partyStateRefreshRunnable)
        releasePartyWifiLock()
        try { partyHostServer?.stop() } catch (_: Exception) {}
        partyHostServer = null
        partyStateSnapshot = null
    }

    @Suppress("DEPRECATION")
    private fun compatWifiLockMode(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }

    private fun acquirePartyWifiLock() {
        try {
            if (partyWifiLock?.isHeld == true) return
            val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
            partyWifiLock = wifi.createWifiLock(compatWifiLockMode(), "BlazePartyHostLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Blaze Party Wi-Fi lock failed", e)
            partyWifiLock = null
        }
    }

    private fun releasePartyWifiLock() {
        try { if (partyWifiLock?.isHeld == true) partyWifiLock?.release() } catch (_: Exception) {}
        partyWifiLock = null
    }

    private fun refreshPartyStateSnapshotAsync() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshPartyStateSnapshotNow()
        } else {
            partyStateHandler.post { refreshPartyStateSnapshotNow() }
        }
    }

    private fun refreshPartyStateSnapshotNow() {
        try {
            partyStateSnapshot = BlazePartyQueue.buildState(applicationContext, sessionPlayer)
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Blaze Party state snapshot failed", e)
            // On garde le dernier snapshot valide plutôt que de faire échouer /state : côté client,
            // une réponse vide/invalide était interprétée comme une perte définitive de l'hôte.
            if (partyStateSnapshot == null) {
                partyStateSnapshot = BlazePartyQueue.buildState(applicationContext, null)
            }
        }
    }

    private fun currentPartyStateSnapshot(): PartyState {
        // Si la playlist Party dédiée est disponible, elle peut être reconstruite sans toucher
        // ExoPlayer et reflète immédiatement les votes écrits par /vote.
        val safeState = BlazePartyQueue.buildState(applicationContext, null)
        if (safeState.tracks.isNotEmpty()) {
            // buildState(..., null) est sûr depuis le thread HTTP mais ne peut pas lire le morceau
            // courant du Player. On réinjecte donc le currentPath du dernier snapshot pris sur le
            // thread principal, indispensable côté client pour afficher le contour et le mini-EQ.
            // On fusionne aussi les métadonnées éventuellement présentes dans ce snapshot main
            // thread, pour ne pas perdre artiste/conteneur/durée si le cache persistant n'était
            // pas encore complet au moment du /state.
            val snapshotByPath = partyStateSnapshot?.tracks?.associateBy { it.path }.orEmpty()
            val mergedTracks = safeState.tracks.map { track ->
                val snap = snapshotByPath[track.path]
                if (snap == null) track else track.copy(
                    artist = track.artist.ifBlank { snap.artist },
                    title = track.title.ifBlank { snap.title },
                    extension = track.extension.ifBlank { snap.extension },
                    bitrate = if (track.bitrate > 0L) track.bitrate else snap.bitrate,
                    isLossless = track.isLossless || snap.isLossless,
                    durationMs = if (track.durationMs > 0L) track.durationMs else snap.durationMs
                )
            }
            val liveSnapshot = partyStateSnapshot
            return safeState.copy(
                tracks = mergedTracks,
                currentPath = safeState.currentPath ?: liveSnapshot?.currentPath,
                currentPositionMs = liveSnapshot?.currentPositionMs ?: safeState.currentPositionMs,
                currentDurationMs = liveSnapshot?.currentDurationMs ?: safeState.currentDurationMs,
                isPlaying = liveSnapshot?.isPlaying ?: safeState.isPlaying
            )
        }

        // Sinon, on sert le dernier snapshot pris sur le thread principal du player, en recalculant
        // seulement les votes depuis les SharedPreferences pour ne pas exposer une liste figée.
        return partyStateSnapshot?.let { state ->
            state.copy(
                tracks = state.tracks.map { track ->
                    track.copy(
                        votes = BlazePartyVoteManager.voteCount(applicationContext, track.path),
                        voters = BlazePartyVoteManager.votersFor(applicationContext, track.path)
                    )
                }.sortedWith(
                    compareBy<PartyTrack> { if (it.playedOrder > 0) 1 else 0 }
                        .thenByDescending { if (it.playedOrder == 0) it.votes else 0 }
                        .thenBy { it.playedOrder }
                ),
                hostNickname = BlazePartyVoteManager.getNickname(applicationContext)
            )
        } ?: PartyState(emptyList(), null, BlazePartyVoteManager.getNickname(applicationContext))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_EXTERNAL_AUDIO -> {
                val path = intent.getStringExtra(EXTRA_EXTERNAL_AUDIO_PATH).orEmpty()
                val name = intent.getStringExtra(EXTRA_EXTERNAL_AUDIO_NAME).orEmpty().ifBlank {
                    android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: "Audio"
                }
                replaceWithExternalAudio(path, name)
                return START_STICKY
            }
            ACTION_PLAY_AUDIO_QUEUE -> {
                val paths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_PATHS).orEmpty()
                val names = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_NAMES).orEmpty()
                val index = intent.getIntExtra(EXTRA_AUDIO_QUEUE_INDEX, 0)
                playAudioQueue(paths, names, index)
                return START_STICKY
            }
            ACTION_APPEND_AUDIO_QUEUE -> {
                val paths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_PATHS).orEmpty()
                val names = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_NAMES).orEmpty()
                appendAudioQueue(paths, names)
                return START_STICKY
            }
            ACTION_APPEND_AUDIO_QUEUE_AND_PLAY -> {
                val paths = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_PATHS).orEmpty()
                val names = intent.getStringArrayListExtra(EXTRA_AUDIO_QUEUE_NAMES).orEmpty()
                val index = intent.getIntExtra(EXTRA_AUDIO_QUEUE_INDEX, 0)
                appendAudioQueueAndPlay(paths, names, index)
                return START_STICKY
            }
            ACTION_PARTY_START_HOST -> {
                val token = intent.getStringExtra(EXTRA_PARTY_TOKEN).orEmpty()
                if (token.isNotBlank()) startPartyHostServer(token)
                return START_STICKY
            }
            ACTION_PARTY_STOP_HOST -> {
                stopPartyHostServer()
                return START_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun replaceWithExternalAudio(path: String, name: String) {
        if (path.isBlank()) return
        try {
            val p = sessionPlayer ?: player ?: return
            val item = AudioRepository.buildSimpleMediaItem(applicationContext, path, name)

            // ACTION_VIEW audio externe = lecture immédiate et prioritaire.
            // Conforme Media3 : on conserve la même MediaSessionService et on remplace la queue
            // du Player, au lieu de tuer/rebinder le service ou de passer par le Fragment.
            p.setMediaItem(item, /* startPositionMs = */ 0L)
            p.playWhenReady = true
            p.prepare()
            p.play()
            persistAudioQueue()
            enrichExternalAudioMetadataAsync(path, name)
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Replace external audio failed for $path", e)
        }
    }


    private fun playAudioQueue(paths: List<String>, names: List<String>, startIndex: Int) {
        val clean = paths.mapIndexedNotNull { index, rawPath ->
            val path = rawPath.trim()
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapIndexedNotNull null
            val name = names.getOrNull(index).orEmpty().ifBlank {
                android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
                    ?: path.substringBefore('?').substringAfterLast('/').ifBlank { "Audio" }
            }
            PlaylistItem(path, name)
        }.distinctBy { it.path }
        if (clean.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, clean.size - 1)
        AudioRepository.save(applicationContext, clean, safeIndex, 0L, Player.REPEAT_MODE_OFF, false)

        try {
            val p = sessionPlayer ?: player ?: return
            val mediaItems = clean.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name) }
            p.setMediaItems(mediaItems, safeIndex, 0L)
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.shuffleModeEnabled = false
            p.playWhenReady = true
            p.prepare()
            p.play()
            persistAudioQueue()
            // Chargement non bloquant de la pochette du morceau courant pour la notification/lockscreen.
            clean.getOrNull(safeIndex)?.let { enrichExternalAudioMetadataAsync(it.path, it.name) }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Play audio queue failed", e)
        }
    }


    private fun appendAudioQueueAndPlay(paths: List<String>, names: List<String>, startIndex: Int) {
        val clean = paths.mapIndexedNotNull { index, rawPath ->
            val path = rawPath.trim()
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapIndexedNotNull null
            val name = names.getOrNull(index).orEmpty().ifBlank {
                android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
                    ?: path.substringBefore('?').substringAfterLast('/').ifBlank { "Audio" }
            }
            PlaylistItem(path, name)
        }.distinctBy { it.path }
        if (clean.isEmpty()) return
        val targetItem = clean[startIndex.coerceIn(0, clean.size - 1)]

        try {
            val p = sessionPlayer ?: player
            if (p != null && p.mediaItemCount > 0) {
                val existingPaths = (0 until p.mediaItemCount).map { originalPathFromItem(p.getMediaItemAt(it)) }
                val existingSet = existingPaths.toMutableSet()
                val additions = clean.filter { existingSet.add(it.path) }
                val insertionStart = p.mediaItemCount
                if (additions.isNotEmpty()) {
                    p.addMediaItems(additions.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name) })
                }
                val targetIndex = existingPaths.indexOf(targetItem.path).takeIf { it >= 0 }
                    ?: additions.indexOfFirst { it.path == targetItem.path }.takeIf { it >= 0 }?.let { insertionStart + it }
                    ?: p.currentMediaItemIndex.coerceAtLeast(0)
                p.seekTo(targetIndex, 0L)
                p.playWhenReady = true
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
                persistAudioQueue()
                enrichExternalAudioMetadataAsync(targetItem.path, targetItem.name)
            } else {
                val savedState = AudioRepository.loadState(applicationContext)
                val existing = savedState.items.map { it.path }.toMutableSet()
                val additions = clean.filter { existing.add(it.path) }
                val merged = savedState.items + additions
                if (merged.isEmpty()) return
                val targetIndex = savedState.items.indexOfFirst { it.path == targetItem.path }.takeIf { it >= 0 }
                    ?: additions.indexOfFirst { it.path == targetItem.path }.takeIf { it >= 0 }?.let { savedState.items.size + it }
                    ?: savedState.index.coerceIn(0, (merged.size - 1).coerceAtLeast(0))
                AudioRepository.save(applicationContext, merged, targetIndex, 0L, savedState.repeatMode, savedState.shuffle)
                val target = sessionPlayer ?: player
                if (target != null) {
                    val mediaItems = merged.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name) }
                    target.setMediaItems(mediaItems, targetIndex, 0L)
                    target.repeatMode = savedState.repeatMode
                    target.shuffleModeEnabled = savedState.shuffle
                    target.playWhenReady = true
                    target.prepare()
                    target.play()
                    persistAudioQueue()
                }
                enrichExternalAudioMetadataAsync(targetItem.path, targetItem.name)
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Append and play audio queue failed", e)
        }
    }

    private fun appendAudioQueue(paths: List<String>, names: List<String>) {
        val clean = paths.mapIndexedNotNull { index, rawPath ->
            val path = rawPath.trim()
            if (path.isBlank() || !AudioRepository.isAudioExtension(path)) return@mapIndexedNotNull null
            val name = names.getOrNull(index).orEmpty().ifBlank {
                android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
                    ?: path.substringBefore('?').substringAfterLast('/').ifBlank { "Audio" }
            }
            PlaylistItem(path, name)
        }.distinctBy { it.path }
        if (clean.isEmpty()) return

        try {
            val p = sessionPlayer ?: player
            if (p != null && p.mediaItemCount > 0) {
                val existingPaths = (0 until p.mediaItemCount)
                    .map { originalPathFromItem(p.getMediaItemAt(it)) }
                    .toMutableSet()
                val mediaItems = clean
                    .filter { existingPaths.add(it.path) }
                    .map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name) }
                if (mediaItems.isNotEmpty()) {
                    p.addMediaItems(mediaItems)
                    persistAudioQueue()
                    mediaItems.forEach { item ->
                        val itemPath = originalPathFromItem(item)
                        enrichExternalAudioMetadataAsync(itemPath, AudioLibraryHeuristics.fileNameFromPath(itemPath))
                    }
                }
            } else {
                val savedState = AudioRepository.loadState(applicationContext)
                val existing = savedState.items.map { it.path }.toMutableSet()
                val merged = savedState.items + clean.filter { existing.add(it.path) }
                if (merged.isNotEmpty()) {
                    AudioRepository.save(
                        applicationContext,
                        merged,
                        savedState.index.coerceIn(0, (merged.size - 1).coerceAtLeast(0)),
                        savedState.positionMs,
                        savedState.repeatMode,
                        savedState.shuffle
                    )
                    val target = sessionPlayer ?: player
                    if (target != null && target.mediaItemCount == 0) {
                        val mediaItems = merged.map { AudioRepository.buildSimpleMediaItem(applicationContext, it.path, it.name) }
                        target.setMediaItems(mediaItems, savedState.index.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)), savedState.positionMs)
                        target.repeatMode = savedState.repeatMode
                        target.shuffleModeEnabled = savedState.shuffle
                        target.prepare()
                    }
                }
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Append audio queue failed", e)
            val savedState = AudioRepository.loadState(applicationContext)
            val existing = savedState.items.map { it.path }.toMutableSet()
            val merged = savedState.items + clean.filter { existing.add(it.path) }
            if (merged.isNotEmpty()) {
                AudioRepository.save(applicationContext, merged, savedState.index, savedState.positionMs, savedState.repeatMode, savedState.shuffle)
            }
        }
    }

    private fun enrichExternalAudioMetadataAsync(path: String, fallbackName: String) {
        if (path.isBlank()) return
        serviceScope.launch {
            val enriched = try {
                // Seule la pochette est extraite hors thread principal. Les textes de la
                // notification restent dérivés du nom de fichier et des dossiers.
                AudioRepository.buildMediaItemWithMetadata(applicationContext, path, fallbackName)
            } catch (e: Exception) {
                fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Audio notification metadata enrichment failed for $path", e)
                null
            } ?: return@launch

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val p = sessionPlayer ?: player ?: return@post
                    val current = p.currentMediaItem ?: return@post
                    if (current.mediaId != path) return@post
                    p.replaceMediaItem(p.currentMediaItemIndex, enriched)
                    persistAudioQueue()
                } catch (e: Exception) {
                    fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Apply audio notification metadata failed for $path", e)
                }
            }
        }
    }

    private fun restoreEqualizerForPlayer(exoPlayer: ExoPlayer, attempt: Int = 0) {
        val sessionId = exoPlayer.audioSessionId
        if (sessionId <= 0 && attempt < 10) {
            eqApplyHandler.postDelayed({ restoreEqualizerForPlayer(exoPlayer, attempt + 1) }, 250L)
            return
        }
        if (sessionId <= 0) return
        if (eqManager != null && equalizerSessionId == sessionId) {
            applyLoudnessEnhancer(sessionId)
            return
        }
        try { eqManager?.release() } catch (_: Exception) {}
        eqManager = null
        equalizerSessionId = 0
        eqManager = try {
            EqualizerManager(sessionId, applicationContext).also {
                it.restoreLastSession()
                equalizerSessionId = sessionId
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Restore equalizer in audio service failed", e)
            null
        }
        applyLoudnessEnhancer(sessionId)
    }



    private fun applyAudioProSettings(target: ExoPlayer? = player) {
        val p = target ?: return
        try {
            val volume = AudioProSettings.playerVolume(audioProValues, currentReplayGainDb)
            if (!crossfadeInProgress) p.volume = volume
            trySetSkipSilence(p, !audioProValues.gapless)
            tryApplyHighQualityOutput(p)
            // Le préampli de l'égaliseur possède sa propre valeur persistante. Il ne doit pas
            // être écrasé à chaque changement d'un réglage Pro+ sans rapport avec l'EQ.
            applyLoudnessEnhancer(p.audioSessionId)
            scheduleCrossfadeCheck()
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Apply audio Pro+ settings failed", e)
        }
    }

    private fun trySetSkipSilence(exoPlayer: ExoPlayer, enabled: Boolean) {
        // Media3 expose setSkipSilenceEnabled selon les versions. Reflection = compat compilation.
        runCatching {
            exoPlayer.javaClass.methods.firstOrNull { it.name == "setSkipSilenceEnabled" && it.parameterTypes.size == 1 }
                ?.invoke(exoPlayer, enabled)
        }
    }

    private fun tryApplyHighQualityOutput(exoPlayer: ExoPlayer) {
        // Les capacités Hi-Res/offload varient selon Android, le DAC et la version Media3. On active
        // les préférences d'offload quand l'API est présente, sans casser les builds qui n'exposent
        // pas encore les mêmes signatures. Les effets logiciels restent prioritaires si l'utilisateur
        // active préampli, ReplayGain ou normalisation.
        runCatching {
            val prefsClass = Class.forName("androidx.media3.exoplayer.audio.AudioOffloadPreferences")
            val builderClass = Class.forName("androidx.media3.exoplayer.audio.AudioOffloadPreferences\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            fun call(name: String, value: Any) {
                builderClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == 1 }
                    ?.invoke(builder, value)
            }
            call("setIsGaplessSupportRequired", audioProValues.gapless)
            call("setIsSpeedChangeSupportRequired", false)
            // Le passthrough/offload court-circuite la chaîne PCM. Tant que le DSP Blaze est actif,
            // on privilégie donc les réglages audibles (balance, mono, limiteur, réverbération) à
            // l'offload matériel. Désactiver l'égaliseur réautorise le mode Hi-Res/offload demandé.
            val blazeDspEnabled = if (::eqPrefs.isInitialized) {
                eqPrefs.getBoolean(EqualizerManager.KEY_EQ_ENABLED, true)
            } else {
                true
            }
            call("setAudioOffloadMode", if (audioProValues.hiRes && !blazeDspEnabled) 1 else 0)
            val prefs = builderClass.methods.firstOrNull { it.name == "build" && it.parameterTypes.isEmpty() }
                ?.invoke(builder)
                ?: return@runCatching
            exoPlayer.javaClass.methods.firstOrNull { method ->
                method.name == "setAudioOffloadPreferences" && method.parameterTypes.size == 1 && method.parameterTypes[0].isAssignableFrom(prefsClass)
            }?.invoke(exoPlayer, prefs)
        }
    }

    private fun refreshReplayGainForCurrentItem() {
        prepareReplayGainForItem((sessionPlayer ?: player)?.currentMediaItem)
    }

    private fun prepareReplayGainForItem(mediaItem: androidx.media3.common.MediaItem?) {
        if (mediaItem == null || audioProValues.replayGain == AudioProSettings.REPLAYGAIN_OFF) {
            currentReplayGainDb = 0f
            applyAudioProSettings()
            return
        }
        val path = originalPathFromItem(mediaItem)
        if (path.isBlank()) {
            currentReplayGainDb = 0f
            return
        }
        val albumKey = albumKeyFor(mediaItem)
        currentReplayGainDb = AudioLoudnessAnalyzer.cachedReplayGainDb(applicationContext, path, albumKey, audioProValues.replayGain)
        applyAudioProSettings()
        serviceScope.launch {
            val analyzed = AudioLoudnessAnalyzer.getOrAnalyze(applicationContext, path, albumKey)
            val gain = AudioLoudnessAnalyzer.replayGainDb(applicationContext, path, albumKey, audioProValues.replayGain, analyzed)
            Handler(Looper.getMainLooper()).post {
                val current = (sessionPlayer ?: player)?.currentMediaItem
                if (current != null && originalPathFromItem(current) == path) {
                    currentReplayGainDb = gain
                    applyAudioProSettings()
                }
            }
        }
    }

    private fun albumKeyFor(mediaItem: androidx.media3.common.MediaItem): String {
        val metadata = mediaItem.mediaMetadata
        val album = metadata.albumTitle?.toString().orEmpty().trim().lowercase()
        val artist = metadata.artist?.toString().orEmpty().trim().lowercase()
        return listOf(artist, album).filter { it.isNotBlank() }.joinToString("|")
    }

    private fun applyLoudnessEnhancer(sessionId: Int) {
        if (sessionId <= 0) return
        try {
            val eqLoudnessGain = eqManager
                ?.takeIf { it.isEnabled() && it.isLoudnessAvailable() }
                ?.getSavedLoudnessMillibels()
                ?: 0
            val targetGain = (
                AudioProSettings.loudnessTargetMillibels(audioProValues, currentReplayGainDb) +
                    eqLoudnessGain
                ).coerceIn(0, 1800)

            if (targetGain <= 0) {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null
                loudnessEnhancerSessionId = 0
                return
            }

            if (loudnessEnhancerSessionId != sessionId) {
                try { loudnessEnhancer?.release() } catch (_: Exception) {}
                loudnessEnhancer = null
                loudnessEnhancerSessionId = 0
            }
            val enhancer = loudnessEnhancer ?: LoudnessEnhancer(sessionId).also {
                loudnessEnhancer = it
                loudnessEnhancerSessionId = sessionId
            }
            enhancer.setTargetGain(targetGain)
            enhancer.enabled = true
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Loudness enhancer unavailable", e)
            try { loudnessEnhancer?.release() } catch (_: Exception) {}
            loudnessEnhancer = null
            loudnessEnhancerSessionId = 0
        }
    }

    private fun scheduleCrossfadeCheck() {
        crossfadeHandler.removeCallbacks(crossfadeRunnable)
        val p = player ?: return
        if (audioProValues.crossfade && audioProValues.crossfadeDurationSec > 0 && p.isPlaying) {
            crossfadeHandler.postDelayed(crossfadeRunnable, 350L)
        }
    }

    private fun checkCrossfadeWindow() {
        val p = player ?: return
        if (crossfadeInProgress || !audioProValues.crossfade || !p.isPlaying) return
        val duration = p.duration
        if (duration <= 0L || duration == androidx.media3.common.C.TIME_UNSET) return
        if (!runCatching { p.hasNextMediaItem() }.getOrDefault(false)) return
        val fadeMs = (audioProValues.crossfadeDurationSec * 1000L).coerceIn(800L, 12_000L)
        val remaining = duration - p.currentPosition
        if (remaining in 1L..(fadeMs + 280L) && crossfadeIndex != p.currentMediaItemIndex) {
            startCrossfadeToNext(p, fadeMs)
        }
    }

    private fun startCrossfadeToNext(p: ExoPlayer, fadeMs: Long) {
        crossfadeInProgress = true
        crossfadeIndex = p.currentMediaItemIndex
        val half = (fadeMs / 2L).coerceAtLeast(320L)
        fadePlayerTo(0.03f, half)
        crossfadeHandler.postDelayed({
            try {
                if (player !== p) return@postDelayed
                if (runCatching { p.hasNextMediaItem() }.getOrDefault(false)) {
                    p.seekToNextMediaItem()
                    p.play()
                    fadePlayerTo(AudioProSettings.playerVolume(audioProValues, currentReplayGainDb), half)
                }
            } finally {
                crossfadeHandler.postDelayed({
                    crossfadeInProgress = false
                    scheduleCrossfadeCheck()
                }, half + 60L)
            }
        }, half)
    }

    private fun fadePlayerTo(targetVolume: Float, durationMs: Long) {
        val p = player ?: return
        try { playerVolumeAnimator?.cancel() } catch (_: Exception) {}
        val start = p.volume
        playerVolumeAnimator = ValueAnimator.ofFloat(start, targetVolume.coerceIn(0f, 1f)).apply {
            duration = durationMs.coerceAtLeast(120L)
            addUpdateListener { animator ->
                try { p.volume = animator.animatedValue as Float } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun isObsoleteLocalRelayUrl(value: String): Boolean =
        value.startsWith("http://") && value.contains(":8928/")

    private fun originalPathFromItem(mi: androidx.media3.common.MediaItem): String {
        val extras = mi.mediaMetadata.extras
        val fromExtras = extras?.getString("blaze_original_path")
            ?.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
        if (fromExtras != null) return fromExtras
        val fromMediaId = mi.mediaId.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
        if (fromMediaId != null) return fromMediaId
        return mi.localConfiguration?.uri?.toString()
            ?.takeIf { it.isNotBlank() && !isObsoleteLocalRelayUrl(it) && AudioRepository.isAudioExtension(it) }
            .orEmpty()
    }

    private fun persistAudioQueue() {
        val p = sessionPlayer ?: player ?: return
        try {
            if (p.mediaItemCount <= 0) return
            val items = (0 until p.mediaItemCount).map { i ->
                val mi = p.getMediaItemAt(i)
                val path = originalPathFromItem(mi)
                val name = mi.mediaMetadata.extras?.getString("blaze_original_name").orEmpty().ifBlank {
                    AudioLibraryHeuristics.fileNameFromPath(path).ifBlank {
                        mi.localConfiguration?.uri?.lastPathSegment ?: path.substringAfterLast('/')
                    }
                }
                PlaylistItem(path, name)
            }.filter { it.path.isNotBlank() && !isObsoleteLocalRelayUrl(it.path) && AudioRepository.isAudioExtension(it.path) }
            if (items.isNotEmpty()) {
                AudioRepository.save(
                    applicationContext,
                    items,
                    p.currentMediaItemIndex.coerceAtLeast(0),
                    p.currentPosition.coerceAtLeast(0L),
                    p.repeatMode,
                    p.shuffleModeEnabled
                )
            }
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Persist audio queue failed", e)
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        // Balayer la notification déclenche automatiquement COMMAND_STOP côté Media3 (géré en
        // interne par DefaultMediaNotificationProvider), mais ça n'arrête PAS le service lui-même
        // (limitation connue de Media3) : la lecture pouvait donc continuer en arrière-plan alors
        // que la notification avait disparu. On intercepte précisément COMMAND_STOP ici plutôt que
        // de deviner via les transitions d'état du player (IDLE arrive aussi normalement pendant
        // le chargement, ce qui coupait le service à tort).
        @Deprecated("Deprecated by Media3, still needed to intercept COMMAND_STOP from the media notification.")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            if (playerCommand == androidx.media3.common.Player.COMMAND_STOP) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(COMMAND_GET_AUDIO_SESSION_ID, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PLAY_EXTERNAL_AUDIO, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PARTY_START_HOST, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PARTY_STOP_HOST, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_GET_AUDIO_SESSION_ID) {
                val resultExtras = Bundle().apply {
                    putInt(EXTRA_AUDIO_SESSION_ID, player?.audioSessionId ?: 0)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultExtras))
            }
            if (customCommand.customAction == COMMAND_PLAY_EXTERNAL_AUDIO) {
                val path = args.getString(EXTRA_EXTERNAL_AUDIO_PATH).orEmpty()
                val name = args.getString(EXTRA_EXTERNAL_AUDIO_NAME).orEmpty().ifBlank {
                    android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: "Audio"
                }
                if (path.isBlank()) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))

                // Même chemin que l'intent direct du service : remplacement strict et lecture immédiate.
                replaceWithExternalAudio(path, name)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == COMMAND_PARTY_START_HOST) {
                val token = args.getString(EXTRA_PARTY_TOKEN).orEmpty()
                if (token.isBlank()) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                startPartyHostServer(token)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == COMMAND_PARTY_STOP_HOST) {
                stopPartyHostServer()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Comportement attendu : bouton Home = lecture audio en arrière-plan avec notification ;
     * swipe de l'app dans les tâches récentes = fermeture explicite, donc arrêt immédiat de la
     * lecture et suppression de la notification Media3.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistAudioQueue()
        try {
            isAudioPlaybackActive = false
            sessionPlayer?.stop()
            sessionPlayer?.clearMediaItems()
        } catch (e: Exception) {
            fr.retrospare.blazeplayer.debug.CrashReporter.log(applicationContext, "Audio onTaskRemoved stop failed", e)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isAudioPlaybackActive = false
        try { eqApplyHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { crossfadeHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { playerVolumeAnimator?.cancel() } catch (_: Exception) {}
        try { if (::audioProPrefs.isInitialized) audioProPrefs.unregisterOnSharedPreferenceChangeListener(audioProListener) } catch (_: Exception) {}
        try { if (::eqPrefs.isInitialized) eqPrefs.unregisterOnSharedPreferenceChangeListener(eqPreferenceListener) } catch (_: Exception) {}
        try { pcmAudioProcessor?.releaseSettings() } catch (_: Exception) {}
        pcmAudioProcessor = null
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        loudnessEnhancerSessionId = 0
        try { serviceScope.cancel() } catch (_: Exception) {}
        stopPartyHostServer()
        val sessionToRelease = mediaSession
        val eqToRelease = eqManager
        val playerToRelease = player
        mediaSession = null
        eqManager = null
        equalizerSessionId = 0
        sessionPlayer = null
        player = null
        try { sessionToRelease?.release() } catch (_: Exception) {}
        try { eqToRelease?.release() } catch (_: Exception) {}
        try { playerToRelease?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

}
