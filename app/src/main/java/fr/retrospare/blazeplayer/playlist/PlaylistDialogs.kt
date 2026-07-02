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
            Toast.makeText(context, context.getString(fr.retrospare.blazeplayer.R.string.toast_no_file_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val counts = PlaylistManager.getAllSlotCounts(context, category)
        val labels = (1..PlaylistManager.SLOT_COUNT).map { slot ->
            val count = counts[slot - 1]
            context.getString(fr.retrospare.blazeplayer.R.string.playlist_slot_name, slot) + " " +
                (if (count > 0) "(" + context.resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.playlist_item_count, count, count) + ")"
                 else context.getString(fr.retrospare.blazeplayer.R.string.playlist_empty))
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.dialog_which_playlist))
            .setItems(labels) { _, which ->
                val slot = which + 1
                val added = PlaylistManager.addToPlaylist(context, category, slot, tracks)
                val addedText = context.resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.playlist_items_added, added, added)
                val msg = if (added == tracks.size) {
                    context.getString(fr.retrospare.blazeplayer.R.string.playlist_added_to_slot, addedText, slot)
                } else {
                    val remaining = tracks.size - added
                    val remainingText = context.resources.getQuantityString(fr.retrospare.blazeplayer.R.plurals.playlist_items_already_present, remaining, remaining)
                    context.getString(fr.retrospare.blazeplayer.R.string.playlist_added_partial, addedText, remainingText)
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                onAdded?.invoke(slot)
            }
            .setNegativeButton(context.getString(fr.retrospare.blazeplayer.R.string.action_cancel), null)
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
                .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.playlist_slot_name, slot) + " — " + category.displayLabel(context))
                .setMessage(context.getString(fr.retrospare.blazeplayer.R.string.dialog_playlist_empty_message))
                .setPositiveButton(context.getString(fr.retrospare.blazeplayer.R.string.action_ok), null)
                .show()
            return
        }
        val names = tracks.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(context.getString(fr.retrospare.blazeplayer.R.string.playlist_slot_name, slot) + " — " + category.displayLabel(context) + " (${tracks.size})")
            .setItems(names) { _, which -> onPlayOne(tracks[which]) }
            .setPositiveButton(context.getString(fr.retrospare.blazeplayer.R.string.action_play_playlist)) { _, _ -> onPlayAll(tracks) }
            .setNeutralButton(context.getString(fr.retrospare.blazeplayer.R.string.action_empty_playlist)) { _, _ ->
                PlaylistManager.clearPlaylist(context, category, slot)
                Toast.makeText(context, context.getString(fr.retrospare.blazeplayer.R.string.toast_playlist_emptied, slot), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(context.getString(fr.retrospare.blazeplayer.R.string.action_close), null)
            .show()
    }
}
