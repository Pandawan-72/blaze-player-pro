package fr.retrospare.blazeplayer.playlist

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object PlaylistDialogs {

    /** Affiche un choix "Playlist 1 / 2 / 3" (avec le nombre d'éléments déjà présents dans
     *  chacune) et ajoute les éléments sélectionnés à la playlist choisie. */
    fun showAddToPlaylistPicker(
        context: Context,
        category: PlaylistCategory,
        tracks: List<PlaylistTrackRef>,
        onAdded: ((slot: Int) -> Unit)? = null
    ) {
        if (tracks.isEmpty()) {
            Toast.makeText(context, "Aucun fichier sélectionné", Toast.LENGTH_SHORT).show()
            return
        }
        val counts = PlaylistManager.getAllSlotCounts(context, category)
        val labels = (1..PlaylistManager.SLOT_COUNT).map { slot ->
            val count = counts[slot - 1]
            "Playlist $slot" + if (count > 0) " ($count élément${if (count > 1) "s" else ""})" else " (vide)"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Ajouter à quelle playlist ?")
            .setItems(labels) { _, which ->
                val slot = which + 1
                val added = PlaylistManager.addToPlaylist(context, category, slot, tracks)
                val msg = if (added == tracks.size) {
                    "$added élément${if (added > 1) "s" else ""} ajouté${if (added > 1) "s" else ""} à la playlist $slot"
                } else {
                    "$added élément${if (added > 1) "s" else ""} ajouté${if (added > 1) "s" else ""} (${tracks.size - added} déjà présent(s))"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                onAdded?.invoke(slot)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Affiche le contenu d'une playlist sauvegardée avec un bouton "Jouer la playlist" et la
     *  possibilité de lire un élément précis ou de vider la playlist.
     *  [onPlayAll] reçoit la liste complète (pour lancer la lecture, en enchaînement si le
     *  contexte le permet). [onPlayOne] reçoit un élément précis tapé dans la liste. */
    fun showPlaylistViewer(
        context: Context,
        category: PlaylistCategory,
        slot: Int,
        onPlayAll: (List<PlaylistTrackRef>) -> Unit,
        onPlayOne: (PlaylistTrackRef) -> Unit
    ) {
        val tracks = PlaylistManager.getPlaylist(context, category, slot)
        if (tracks.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Playlist $slot — ${category.label}")
                .setMessage("Cette playlist est vide.\n\nDepuis le navigateur, sélectionne des fichiers puis utilise \"Ajouter à la playlist\" pour la remplir.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val names = tracks.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Playlist $slot — ${category.label} (${tracks.size})")
            .setItems(names) { _, which -> onPlayOne(tracks[which]) }
            .setPositiveButton("Jouer la playlist") { _, _ -> onPlayAll(tracks) }
            .setNeutralButton("Vider") { _, _ ->
                PlaylistManager.clearPlaylist(context, category, slot)
                Toast.makeText(context, "Playlist $slot vidée", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }
}
