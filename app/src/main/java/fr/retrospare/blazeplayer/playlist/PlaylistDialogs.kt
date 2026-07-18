package fr.retrospare.blazeplayer.playlist

import fr.retrospare.blazeplayer.ui.showPremium
import fr.retrospare.blazeplayer.ui.ButtonTextFitter
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import fr.retrospare.blazeplayer.R

object PlaylistDialogs {

    fun showCreatePlaylistDialog(
        context: Context,
        category: PlaylistCategory,
        onCreated: ((NamedPlaylist) -> Unit)? = null
    ) {
        val density = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            hint = context.getString(R.string.hint_playlist_name)
            setSingleLine(true)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (22 * density).toInt()
            val vertical = (8 * density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.action_new_playlist))
            .setView(container)
            .setPositiveButton(context.getString(R.string.action_save), null)
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .showPremium()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text?.toString().orEmpty().trim()
            if (name.isBlank()) {
                input.error = context.getString(R.string.toast_playlist_name_required)
                return@setOnClickListener
            }
            val created = PlaylistManager.createNamedPlaylist(context, category, name)
            if (created == null) {
                input.error = context.getString(R.string.toast_playlist_name_exists)
                return@setOnClickListener
            }
            Toast.makeText(context, context.getString(R.string.toast_playlist_created, created.name), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onCreated?.invoke(created)
        }
        input.requestFocus()
    }

    fun showAddToPlaylistPicker(
        context: Context,
        category: PlaylistCategory,
        tracks: List<PlaylistTrackRef>,
        onAdded: ((playlistId: String) -> Unit)? = null
    ) {
        if (tracks.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_no_file_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val playlists = PlaylistManager.getNamedPlaylists(context, category)
        if (playlists.isEmpty()) {
            showCreatePlaylistDialog(context, category) { created ->
                val added = PlaylistManager.addToNamedPlaylist(context, category, created.id, tracks)
                showNamedAddResult(context, created, tracks.size, added)
                onAdded?.invoke(created.id)
            }
            return
        }
        val labels = playlists.map { playlist ->
            val count = PlaylistManager.getNamedPlaylistTracks(context, category, playlist.id).size
            val countText = context.resources.getQuantityString(R.plurals.playlist_item_count, count, count)
            context.getString(R.string.playlist_named_with_count, playlist.name, countText)
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_which_playlist))
            .setItems(labels) { _, which ->
                val playlist = playlists[which]
                val added = PlaylistManager.addToNamedPlaylist(context, category, playlist.id, tracks)
                showNamedAddResult(context, playlist, tracks.size, added)
                onAdded?.invoke(playlist.id)
            }
            .setNeutralButton(context.getString(R.string.action_new_playlist)) { _, _ ->
                showCreatePlaylistDialog(context, category) { created ->
                    val added = PlaylistManager.addToNamedPlaylist(context, category, created.id, tracks)
                    showNamedAddResult(context, created, tracks.size, added)
                    onAdded?.invoke(created.id)
                }
            }
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .showPremium()
    }

    private fun showNamedAddResult(context: Context, playlist: NamedPlaylist, requested: Int, added: Int) {
        val addedText = context.resources.getQuantityString(R.plurals.playlist_items_added, added, added)
        val message = if (added == requested) {
            context.getString(R.string.playlist_added_to_named, addedText, playlist.name)
        } else {
            val remaining = (requested - added).coerceAtLeast(0)
            val remainingText = context.resources.getQuantityString(R.plurals.playlist_items_already_present, remaining, remaining)
            context.getString(R.string.playlist_added_partial_named, addedText, remainingText, playlist.name)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun showChoosePlaylistForQueue(
        context: Context,
        category: PlaylistCategory,
        onPlaylistsChanged: (() -> Unit)? = null,
        onChosen: (NamedPlaylist, List<PlaylistTrackRef>) -> Unit
    ) {
        val playlists = PlaylistManager.getNamedPlaylists(context, category)
        if (playlists.isEmpty()) return
        val labels = playlists.map { playlist ->
            val count = PlaylistManager.getNamedPlaylistTracks(context, category, playlist.id).size
            val countText = context.resources.getQuantityString(R.plurals.playlist_item_count, count, count)
            context.getString(R.string.playlist_named_with_count, playlist.name, countText)
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.action_choose_playlist))
            .setItems(labels) { _, which ->
                val playlist = playlists[which]
                val tracks = PlaylistManager.getNamedPlaylistTracks(context, category, playlist.id)
                if (tracks.isEmpty()) Toast.makeText(context, context.getString(R.string.playlist_empty), Toast.LENGTH_SHORT).show()
                else onChosen(playlist, tracks)
            }
            .setNeutralButton(context.getString(R.string.action_delete_playlist)) { _, _ ->
                showDeletePlaylistDialog(context, category) {
                    onPlaylistsChanged?.invoke()
                }
            }
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .showPremium()
    }

    private fun showDeletePlaylistDialog(
        context: Context,
        category: PlaylistCategory,
        onDeleted: (() -> Unit)? = null
    ) {
        val playlists = PlaylistManager.getNamedPlaylists(context, category)
        if (playlists.isEmpty()) return
        val labels = playlists.map { playlist ->
            val count = PlaylistManager.getNamedPlaylistTracks(context, category, playlist.id).size
            val countText = context.resources.getQuantityString(R.plurals.playlist_item_count, count, count)
            context.getString(R.string.playlist_named_with_count, playlist.name, countText)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.action_delete_playlist))
            .setItems(labels) { _, which ->
                val playlist = playlists[which]
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.confirm_delete_title))
                    .setMessage(context.getString(R.string.confirm_delete_named_playlist_message, playlist.name))
                    .setPositiveButton(context.getString(R.string.action_delete)) { _, _ ->
                        PlaylistManager.deleteNamedPlaylist(context, category, playlist.id)
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_named_playlist_deleted, playlist.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        onDeleted?.invoke()
                    }
                    .setNegativeButton(context.getString(R.string.action_cancel), null)
                    .showPremium()
            }
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .showPremium()
    }

    /** Affiche le contenu d'une playlist sauvegardée dans une feuille de bas d'écran habillée
     *  (au lieu d'une AlertDialog.setItems générique) : lecture d'un morceau précis, lecture de
     *  toute la playlist, retrait individuel d'un morceau sans fermer la feuille, et un bouton
     *  secondaire "Vider" (ou "Ajouter à Blaze Party" si applicable). */
    fun showNamedPlaylistViewer(
        context: Context,
        category: PlaylistCategory,
        playlist: NamedPlaylist,
        onPlayAll: (List<PlaylistTrackRef>) -> Unit,
        onPlayOne: (PlaylistTrackRef) -> Unit,
        onAddToParty: ((List<PlaylistTrackRef>) -> Unit)? = null,
        onChanged: (() -> Unit)? = null
    ) {
        showTrackListSheet(
            context = context,
            title = playlist.name,
            category = category,
            loadTracks = { PlaylistManager.getNamedPlaylistTracks(context, category, playlist.id) },
            onPlayAll = onPlayAll,
            onPlayOne = onPlayOne,
            onRemoveTrack = { track ->
                PlaylistManager.removeFromNamedPlaylist(context, category, playlist.id, track.path)
                onChanged?.invoke()
            },
            clearLabel = context.getString(R.string.action_empty_playlist),
            onClear = {
                PlaylistManager.clearNamedPlaylist(context, category, playlist.id)
                Toast.makeText(context, context.getString(R.string.toast_named_playlist_emptied, playlist.name), Toast.LENGTH_SHORT).show()
                onChanged?.invoke()
            },
            secondaryLabel = onAddToParty?.let { context.getString(R.string.add_to_blaze_party) },
            onSecondary = onAddToParty?.let { addToParty ->
                { addToParty.invoke(PlaylistManager.getNamedPlaylistTracks(context, category, playlist.id)) }
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
            category = PlaylistCategory.AUDIO,
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
        category: PlaylistCategory?,
        loadTracks: () -> List<PlaylistTrackRef>,
        onPlayAll: (List<PlaylistTrackRef>) -> Unit,
        onPlayOne: (PlaylistTrackRef) -> Unit,
        onRemoveTrack: (PlaylistTrackRef) -> Unit,
        clearLabel: String,
        onClear: () -> Unit,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null
    ) {
        var tracks = loadTracks()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_playlist_viewer, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvPlaylistSheetTitle)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerPlaylistTracks)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPlaylistSheetEmpty)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClosePlaylistSheet)
        val btnClear = view.findViewById<MaterialButton>(R.id.btnPlaylistSheetClear)
        val btnSecondary = view.findViewById<MaterialButton>(R.id.btnPlaylistSheetSecondary)
        val btnPlayAll = view.findViewById<MaterialButton>(R.id.btnPlaylistSheetPlayAll)
        ButtonTextFitter.fitRecursively(view, minSp = 9, maxSp = 13)

        val dialog = BottomSheetDialog(context)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = maxHeight }
            BottomSheetBehavior.from(bottomSheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        fun refreshHeader() {
            tvTitle.text = "$title (${tracks.size})"
            val empty = tracks.isEmpty()
            recycler.visibility = if (empty) View.GONE else View.VISIBLE
            tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            btnPlayAll.isEnabled = !empty
            btnPlayAll.alpha = if (empty) 0.5f else 1f
            btnClear.isEnabled = !empty
            btnClear.alpha = if (empty) 0.5f else 1f
            btnSecondary.isEnabled = !empty
            btnSecondary.alpha = if (empty) 0.5f else 1f
        }

        fun displayTrackName(track: PlaylistTrackRef): String {
            if (category != PlaylistCategory.AUDIO) return track.name
            val artist = track.artist.trim()
            val title = track.title.trim()
                .ifBlank { track.name.substringBeforeLast('.', track.name).trim() }
                .ifBlank { track.name }
            return if (artist.isNotBlank()) "$artist - $title" else title
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
                v.findViewById<TextView>(R.id.tvTrackName).text = displayTrackName(track)
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
        btnPlayAll.setOnClickListener { if (tracks.isNotEmpty()) { onPlayAll(PlaylistPlayOrder.sortedForPlayback(category, tracks)); dialog.dismiss() } }
        btnClear.text = clearLabel
        btnClear.setOnClickListener {
            onClear()
            dialog.dismiss()
        }
        if (secondaryLabel != null && onSecondary != null) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = secondaryLabel
            btnSecondary.setOnClickListener {
                onSecondary.invoke()
                dialog.dismiss()
            }
        } else {
            btnSecondary.visibility = View.GONE
        }

        dialog.show()
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
    }
}
