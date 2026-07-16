package fr.retrospare.blazeplayer.cast

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter

/**
 * Observe au mieux l'état d'une projection vidéo système.
 *
 * Le mirroring lancé depuis les réglages Android n'appartient pas à une session Cast créée par
 * Blaze Player. On combine donc les deux signaux publics disponibles : la route vidéo sélectionnée
 * dans AndroidX MediaRouter et la présence d'un écran de présentation fourni par DisplayManager.
 */
class SystemScreenMirrorStateMonitor(
    context: Context,
    private val onStateChanged: (Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaRouter = MediaRouter.getInstance(appContext)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    private val liveVideoSelector = MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
        .build()

    private var started = false
    private var lastState: Boolean? = null

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteSelected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int
        ) = dispatch()

        override fun onRouteUnselected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int
        ) = dispatch()

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = dispatch()

        override fun onRoutePresentationDisplayChanged(
            router: MediaRouter,
            route: MediaRouter.RouteInfo
        ) = dispatch()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = dispatch()
        override fun onDisplayRemoved(displayId: Int) = dispatch()
        override fun onDisplayChanged(displayId: Int) = dispatch()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            dispatch()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        mediaRouter.addCallback(
            MediaRouteSelector.EMPTY,
            mediaRouterCallback,
            MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS
        )
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        dispatch(force = true)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        mediaRouter.removeCallback(mediaRouterCallback)
        displayManager?.unregisterDisplayListener(displayListener)
        mainHandler.removeCallbacks(pollRunnable)
    }

    fun refresh() = dispatch(force = true)

    private fun dispatch(force: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dispatch(force) }
            return
        }
        val active = isScreenMirrorActive()
        if (force || lastState != active) {
            lastState = active
            onStateChanged(active)
        }
    }

    private fun isScreenMirrorActive(): Boolean {
        val selectedRoute = mediaRouter.selectedRoute
        val selectedVideoRoute = runCatching {
            selectedRoute.isEnabled &&
                !selectedRoute.isDefault &&
                !selectedRoute.isBluetooth &&
                (
                    selectedRoute.presentationDisplay != null ||
                        selectedRoute.matchesSelector(liveVideoSelector)
                    )
        }.getOrDefault(false)

        val presentationDisplay = runCatching {
            displayManager
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .orEmpty()
                .any { display ->
                    display.displayId != Display.DEFAULT_DISPLAY && display.isValid
                }
        }.getOrDefault(false)

        return selectedVideoRoute || presentationDisplay
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
    }
}
