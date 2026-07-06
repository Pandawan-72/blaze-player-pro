package fr.retrospare.blazeplayer.playlist

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import fr.retrospare.blazeplayer.R

object PlaylistDialogs {

    /** Affiche un choix parmi les playlists sauvegardées, avec le nombre d'éléments déjà présents,
     *  et ajoute les éléments sélectionnés à la playlist choisie. */
    fun showAddToPlaylistPicker(
        context: Context,
        category: PlaylistCategory,
        tracks: List<PlaylistTrackRef>,
        onAdded: ((slot: Int) -> Unit)? = null
    ) {
        if (tracks.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_no_file_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val counts = PlaylistManager.getAllSlotCounts(context, category)
        val labels = (1..PlaylistManager.SLOT_COUNT).map { slot ->
            val count = counts[slot - 1]
            context.getString(R.string.playlist_slot_name, slot) + " " +
                (if (count > 0) "(" + context.resources.getQuantityString(R.plurals.playlist_item_count, count, count) + ")"
                 else context.getString(R.string.playlist_empty))
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_which_playlist))
            .setItems(labels) { _, which ->
                val slot = which + 1
                val added = PlaylistManager.addToPlaylist(context, category, slot, tracks)
                val addedText = context.resources.getQuantityString(R.plurals.playlist_items_added, added, added)
                val msg = if (added == tracks.size) {
                    context.getString(R.string.playlist_added_to_slot, addedText, slot)
                } else {
                    val remaining = tracks.size - added
                    val remainingText = context.resources.getQuantityString(R.plurals.playlist_items_already_present, remaining, remaining)
                    context.getString(R.string.playlist_added_partial, addedText, remainingText)
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                onAdded?.invoke(slot)
            }
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .show()
    }

    /** Affiche le contenu d'une playlist sauvegardée dans une feuille de bas d'écran habillée
     *  (au lieu d'une AlertDialog.setItems générique) : lecture d'un morceau précis, lecture de
     *  toute la playlist, retrait individuel d'un morceau sans fermer la feuille, et un bouton
     *  secondaire "Vider" (ou "Ajouter à Blaze Party" si applicable). */
    fun showPlaylistViewer(
        context: Context,
        category: PlaylistCategory,
        slot: Int,
        onPlayAll: (List<PlaylistTrackRef>) -> Unit,
        onPlayOne: (PlaylistTrackRef) -> Unit,
        onAddToParty: ((List<PlaylistTrackRef>) -> Unit)? = null
    ) {
        showTrackListSheet(
            context = context,
            title = context.getString(R.string.playlist_slot_name, slot) + " — " + category.displayLabel(context),
            loadTracks = { PlaylistManager.getPlaylist(context, category, slot) },
            onPlayAll = onPlayAll,
            onPlayOne = onPlayOne,
            onRemoveTrack = { track -> PlaylistManager.removeFromPlaylist(context, category, slot, track.path) },
            clearLabel = if (onAddToParty != null) context.getString(R.string.add_to_blaze_party) else context.getString(R.string.action_empty_playlist),
            onClear = {
                if (onAddToParty != null) {
                    onAddToParty.invoke(PlaylistManager.getPlaylist(context, category, slot))
                } else {
                    PlaylistManager.clearPlaylist(context, category, slot)
                    Toast.makeText(context, context.getString(R.string.toast_playlist_emptied, slot), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun showBlazePartyPlaylistViewer(
        context: Context,
        onPlayAll: (List<PlaylistTrackRef>) -> Unit,
        onPlayOne: (PlaylistTrackRef) -> Unit
    ) {
        showTrackListSheet(
            context = context,
            title = context.getString(R.string.blaze_party_playlist_title),
            loadTracks = { PlaylistManager.getBlazePartyPlaylist(context) },
            onPlayAll = onPlayAll,
            onPlayOne = onPlayOne,
            onRemoveTrack = { track -> PlaylistManager.removeFromBlazePartyPlaylist(context, track.path) },
            clearLabel = context.getString(R.string.action_empty_playlist),
            onClear = {
                PlaylistManager.clearBlazePartyPlaylist(context)
                Toast.makeText(context, context.getString(R.string.toast_blaze_party_playlist_emptied), Toast.LENGTH_SHORT).show()
            }
        )
    }

    /** Feuille de bas d'écran générique listant les morceaux d'une playlist. Remplace l'ancien
     *  rendu en `AlertDialog.setItems` — purement textuel, sans retrait individuel possible, et
     *  visuellement en rupture avec le reste de l'app qui a déjà des bottom sheets soignées
     *  (sélecteur de piste, Blaze Party...). Chaque suppression de morceau met juste à jour la
     *  liste affichée, sans fermer la feuille — évite d'avoir à rouvrir le dialogue à chaque fois
     *  pour retirer plusieurs morceaux d'affilée. */
    private fun showTrackListSheet(
        context: Context,
        title: String,
        loadTracks: () -> List<PlaylistTrackRef>,
        onPlayAll: (List<PlaylistTrackRef>) -> Unit,
        onPlayOne: (PlaylistTrackRef) -> Unit,
        onRemoveTrack: (PlaylistTrackRef) -> Unit,
        clearLabel: String,
        onClear: () -> Unit
    ) {
        var tracks = loadTracks()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_playlist_viewer, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvPlaylistSheetTitle)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerPlaylistTracks)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPlaylistSheetEmpty)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClosePlaylistSheet)
        val btnClear = view.findViewById<MaterialButton>(R.id.btnPlaylistSheetClear)
        val btnPlayAll = view.findViewById<MaterialButton>(R.id.btnPlaylistSheetPlayAll)

        val dialog = BottomSheetDialog(context)
        dialog.setContentView(view)

        fun refreshHeader() {
            tvTitle.text = "$title (${tracks.size})"
            val empty = tracks.isEmpty()
            recycler.visibility = if (empty) View.GONE else View.VISIBLE
            tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            btnPlayAll.isEnabled = !empty
            btnPlayAll.alpha = if (empty) 0.5f else 1f
            btnClear.isEnabled = !empty
            btnClear.alpha = if (empty) 0.5f else 1f
        }

        recycler.layoutManager = LinearLayoutManager(context)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = tracks.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_track_row, parent, false)
                ) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val track = tracks[position]
                val v = holder.itemView
                v.findViewById<TextView>(R.id.tvTrackName).text = track.name
                v.setOnClickListener { onPlayOne(track); dialog.dismiss() }
                v.findViewById<View>(R.id.btnRemoveTrack).setOnClickListener {
                    onRemoveTrack(track)
                    tracks = loadTracks()
                    notifyDataSetChanged()
                    refreshHeader()
                }
            }
        }
        recycler.adapter = adapter
        refreshHeader()

        btnClose.setOnClickListener { dialog.dismiss() }
        btnPlayAll.setOnClickListener { if (tracks.isNotEmpty()) { onPlayAll(tracks); dialog.dismiss() } }
        btnClear.text = clearLabel
        btnClear.setOnClickListener {
            onClear()
            dialog.dismiss()
        }

        dialog.show()
    }
}
