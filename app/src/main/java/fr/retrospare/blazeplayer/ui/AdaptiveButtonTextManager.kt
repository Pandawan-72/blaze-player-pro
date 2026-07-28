package fr.retrospare.blazeplayer.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import java.util.WeakHashMap

/**
 * Applique ButtonTextFitter à chaque fenêtre Blaze, y compris les Dialog et BottomSheet.
 *
 * Un OnGlobalLayoutListener rescane uniquement après une modification de structure ou de taille.
 * Les TextView déjà configurés sont mémorisés par ButtonTextFitter, donc le coût des rescans reste
 * faible et les boutons ajoutés dynamiquement sont couverts automatiquement.
 */
object AdaptiveButtonTextManager : Application.ActivityLifecycleCallbacks {

    private const val SCAN_DELAY_MS = 48L

    private class WindowBinding(val root: View) {
        var scanPosted = false

        val scanRunnable = Runnable {
            scanPosted = false
            ButtonTextFitter.fitRecursively(root, minSp = 7, maxSp = 16)
        }

        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!scanPosted) {
                scanPosted = true
                root.removeCallbacks(scanRunnable)
                root.postDelayed(scanRunnable, SCAN_DELAY_MS)
            }
        }
    }

    private val windows = WeakHashMap<Window, WindowBinding>()
    private var initialized = false

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun attachToWindow(window: Window?) {
        window ?: return
        val root = window.decorView
        val existing = synchronized(windows) { windows[window] }
        if (existing?.root === root) {
            root.post { ButtonTextFitter.fitRecursively(root, 7, 16) }
            return
        }

        existing?.let { removeBinding(it) }

        val binding = WindowBinding(root)
        synchronized(windows) { windows[window] = binding }
        root.viewTreeObserver.addOnGlobalLayoutListener(binding.layoutListener)
        root.post { ButtonTextFitter.fitRecursively(root, 7, 16) }
    }

    fun applyTo(root: View?) {
        root ?: return
        root.post { ButtonTextFitter.fitRecursively(root, 7, 16) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        attachToWindow(activity.window)
    }

    override fun onActivityStarted(activity: Activity) {
        attachToWindow(activity.window)
    }

    override fun onActivityResumed(activity: Activity) {
        attachToWindow(activity.window)
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        val binding = synchronized(windows) { windows.remove(activity.window) } ?: return
        removeBinding(binding)
    }

    private fun removeBinding(binding: WindowBinding) {
        binding.root.removeCallbacks(binding.scanRunnable)
        val observer = binding.root.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnGlobalLayoutListener(binding.layoutListener)
        }
    }
}
