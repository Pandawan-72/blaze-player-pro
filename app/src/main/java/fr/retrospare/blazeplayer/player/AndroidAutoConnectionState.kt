package fr.retrospare.blazeplayer.player

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * État des contrôleurs automobiles et de l'écran de file Android Auto.
 *
 * La connexion automobile reste active pendant toute la session, mais la timeline ne doit être
 * figée que lorsque la file est réellement parcourue. Dès que le navigateur quitte la catégorie
 * [AndroidAutoLibrary.QUEUE_ID], les changements effectués sur le téléphone peuvent être publiés
 * et seront visibles à la prochaine ouverture de la file.
 */
object AndroidAutoConnectionState {
    private val connections = ConcurrentHashMap<String, AtomicInteger>()
    private val queueViewers = ConcurrentHashMap.newKeySet<String>()

    val isConnected: Boolean
        get() = connections.values.any { it.get() > 0 }

    val isQueueVisible: Boolean
        get() = queueViewers.isNotEmpty()

    fun isCarControllerPackage(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        return normalized.contains("projection.gearhead") ||
            normalized.contains("androidauto") ||
            normalized.contains("automotive") ||
            normalized.contains(".car.") ||
            normalized.endsWith(".car")
    }

    private fun controllerKey(packageName: String, uid: Int): String = "$packageName:$uid"

    /** Retourne true uniquement lors du passage de zéro à au moins une connexion automobile. */
    fun onConnected(packageName: String, uid: Int): Boolean {
        if (!isCarControllerPackage(packageName)) return false
        val wasConnected = isConnected
        val key = controllerKey(packageName, uid)
        connections.compute(key) { _, current ->
            (current ?: AtomicInteger(0)).apply { incrementAndGet() }
        }
        return !wasConnected && isConnected
    }

    /** Retourne true uniquement lorsque la dernière connexion automobile vient de disparaître. */
    fun onDisconnected(packageName: String, uid: Int): Boolean {
        if (!isCarControllerPackage(packageName)) return false
        val key = controllerKey(packageName, uid)
        queueViewers.remove(key)
        connections.computeIfPresent(key) { _, current ->
            if (current.decrementAndGet() <= 0) null else current
        }
        return !isConnected
    }

    /**
     * Marque la file comme affichée pour ce contrôleur. Retourne true lors du premier affichage
     * global, ce qui permet de capturer un instantané frais une seule fois.
     */
    fun onQueueShown(packageName: String, uid: Int): Boolean {
        if (!isCarControllerPackage(packageName)) return false
        val wasVisible = isQueueVisible
        queueViewers.add(controllerKey(packageName, uid))
        return !wasVisible && isQueueVisible
    }

    /**
     * Marque la file comme masquée pour ce contrôleur. Retourne true lorsque le dernier écran de
     * file vient d'être quitté et que les mises à jour différées peuvent être publiées.
     */
    fun onQueueHidden(packageName: String, uid: Int): Boolean {
        if (!isCarControllerPackage(packageName)) return false
        val wasVisible = isQueueVisible
        queueViewers.remove(controllerKey(packageName, uid))
        return wasVisible && !isQueueVisible
    }

    fun reset() {
        queueViewers.clear()
        connections.clear()
    }
}
