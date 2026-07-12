package fr.retrospare.blazeplayer.player

import java.lang.ref.WeakReference

/**
 * Relais en mémoire entre la télécommande Cast et PlayerActivity.
 *
 * Quand le lecteur vidéo existe encore, les touches de la télécommande empruntent exactement les
 * mêmes chemins que les boutons déjà présents dans le player (file locale, reprise réseau, stop,
 * etc.). Si le lecteur a été fermé pendant que le Cast continue, la télécommande retombe sur le
 * MediaController du service vidéo et sur l'état de file persisté.
 */
object ChromecastRemoteCommandBridge {

    enum class Command {
        PLAY_PAUSE,
        PREVIOUS,
        NEXT,
        SEEK_BACK,
        SEEK_FORWARD,
        STOP
    }

    interface Target {
        fun onChromecastRemoteCommand(command: Command): Boolean
    }

    @Volatile
    private var targetRef: WeakReference<Target>? = null

    fun attach(target: Target) {
        targetRef = WeakReference(target)
    }

    fun detach(target: Target) {
        if (targetRef?.get() === target) targetRef = null
    }

    fun dispatch(command: Command): Boolean {
        val target = targetRef?.get() ?: return false
        return try {
            target.onChromecastRemoteCommand(command)
        } catch (_: Exception) {
            false
        }
    }
}
