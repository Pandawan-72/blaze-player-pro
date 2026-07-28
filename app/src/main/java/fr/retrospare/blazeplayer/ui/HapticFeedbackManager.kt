package fr.retrospare.blazeplayer.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Gestionnaire global du retour haptique des interactions.
 *
 * Le réglage est volontairement stocké dans des SharedPreferences légères afin d'être disponible
 * immédiatement au démarrage, sans lecture bloquante de DataStore. Il est désactivé par défaut.
 * Toutes les fenêtres d'Activity sont interceptées au niveau de Window.Callback : cela couvre les
 * boutons, cartes, lignes RecyclerView, albums, titres, files d'attente, onglets et contrôles du
 * lecteur sans remplacer les OnClickListener existants.
 */
object HapticFeedbackManager {
    private const val PREFS_NAME = "blaze_haptic_preferences"
    private const val KEY_ENABLED = "haptic_feedback_enabled"

    private val enabled = AtomicBoolean(false)
    private val attachedWindows = WeakHashMap<Window, Unit>()
    private var initialized = false

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        enabled.set(
            application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        )

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
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
                synchronized(attachedWindows) { attachedWindows.remove(activity.window) }
            }
        })
    }

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(context: Context, value: Boolean) {
        // La valeur mémoire est modifiée avant l'écriture disque : le toggle prend effet dès le clic.
        enabled.set(value)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }

    /** Attache aussi les Dialog/BottomSheet créés hors de la fenêtre principale de l'Activity. */
    fun attachToWindow(window: Window?) {
        window ?: return
        // Le même point d'entrée couvre déjà toutes les Activity, Dialog et BottomSheet Blaze.
        AdaptiveButtonTextManager.attachToWindow(window)
        synchronized(attachedWindows) {
            if (attachedWindows.containsKey(window)) return
            val callback = window.callback ?: return
            if (callback is HapticWindowCallback) {
                attachedWindows[window] = Unit
                return
            }
            val wrapper = HapticWindowCallback(callback, window)
            window.callback = wrapper
            attachedWindows[window] = Unit
        }
    }

    fun perform(view: View?, feedbackConstant: Int = HapticFeedbackConstants.VIRTUAL_KEY): Boolean {
        if (!enabled.get()) return false
        val target = view ?: return false
        if (!target.isEnabled || !target.isShown) return false
        return target.performHapticFeedback(
            feedbackConstant,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    private class HapticWindowCallback(
        private val wrapped: Window.Callback,
        private val window: Window
    ) : Window.Callback by wrapped {
        private val touchSlop = ViewConfiguration.get(window.context).scaledTouchSlop.toFloat()
        private var downTarget: WeakReference<View>? = null
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    downTarget = findClickableAt(window.decorView, event.rawX, event.rawY)?.let { WeakReference(it) }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop) {
                        moved = true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val original = downTarget?.get()
                    val released = findClickableAt(window.decorView, event.rawX, event.rawY)
                    if (!moved && original != null && original === released && original.isEnabled) {
                        // Déclenché sur ACTION_UP, juste avant l'action réelle : aucun retour pendant
                        // un scroll et aucune latence ajoutée aux OnClickListener.
                        perform(original)
                    }
                    clearTouchTracking()
                }

                MotionEvent.ACTION_CANCEL -> clearTouchTracking()
            }
            return wrapped.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_UP &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            ) {
                val focused = window.currentFocus
                if (focused != null && isClickable(focused)) perform(focused)
            }
            return wrapped.dispatchKeyEvent(event)
        }

        private fun clearTouchTracking() {
            downTarget?.clear()
            downTarget = null
            moved = false
        }
    }

    private fun findClickableAt(view: View, rawX: Float, rawY: Float): View? {
        if (!view.isShown || !view.isEnabled || view.alpha <= 0.01f) return null
        val visibleRect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(visibleRect) ||
            !visibleRect.contains(rawX.toInt(), rawY.toInt())
        ) return null

        if (view is ViewGroup) {
            // Les derniers enfants sont généralement dessinés au-dessus des précédents.
            for (index in view.childCount - 1 downTo 0) {
                findClickableAt(view.getChildAt(index), rawX, rawY)?.let { return it }
            }
        }
        return view.takeIf(::isClickable)
    }

    private fun isClickable(view: View): Boolean =
        view.isEnabled && (
            view.isClickable ||
                view.isLongClickable ||
                view.hasOnClickListeners() ||
                view is android.widget.AdapterView<*>
            )
}
