package fr.retrospare.blazeplayer.player

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import fr.retrospare.blazeplayer.R

class PlaylistManagerDialog(
    private val currentItems: List<PlaylistItem>,
    private val onLoad: (List<PlaylistItem>) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_playlist_manager, container, false)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerSavedPlaylists)
        val btnSaveCurrent = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSaveCurrentPlaylist)

        recycler.layoutManager = LinearLayoutManager(requireContext())

        fun refresh() {
            val playlists = PlaylistManager.getAll(requireContext())
            recycler.adapter = SavedPlaylistAdapter(
                playlists,
                onLoad = { pl ->
                    onLoad(pl.items)
                    dismiss()
                },
                onRename = { pl ->
                    val et = EditText(requireContext()).apply { setText(pl.name) }
                    AlertDialog.Builder(requireContext())
                        .setTitle("Renommer")
                        .setView(et)
                        .setPositiveButton("OK") { _, _ ->
                            val newName = et.text.toString().trim()
                            if (newName.isNotEmpty()) {
                                PlaylistManager.rename(requireContext(), pl.id, newName)
                                refresh()
                            }
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                },
                onDelete = { pl ->
                    AlertDialog.Builder(requireContext())
                        .setTitle("Supprimer \"${pl.name}\" ?")
                        .setPositiveButton("Supprimer") { _, _ ->
                            PlaylistManager.delete(requireContext(), pl.id)
                            refresh()
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            )
        }

        refresh()

        btnSaveCurrent.setOnClickListener {
            val localItems = currentItems.filter {
                !it.path.startsWith("smb://") && !it.path.startsWith("ftp://")
            }
            if (localItems.isEmpty()) {
                Toast.makeText(requireContext(), "Aucun fichier local à sauvegarder", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val et = EditText(requireContext()).apply { hint = "Nom de la playlist" }
            AlertDialog.Builder(requireContext())
                .setTitle("Nouvelle playlist")
                .setView(et)
                .setPositiveButton("Sauvegarder") { _, _ ->
                    val name = et.text.toString().trim()
                    if (name.isNotEmpty()) {
                        PlaylistManager.createNew(requireContext(), name, localItems)
                        Toast.makeText(requireContext(), "Playlist \"$name\" sauvegardée", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        return view
    }
}

class SavedPlaylistAdapter(
    private val playlists: List<SavedPlaylist>,
    private val onLoad: (SavedPlaylist) -> Unit,
    private val onRename: (SavedPlaylist) -> Unit,
    private val onDelete: (SavedPlaylist) -> Unit
) : RecyclerView.Adapter<SavedPlaylistAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPlaylistName)
        val tvCount: TextView = view.findViewById(R.id.tvPlaylistCount)
        val btnRename: ImageButton = view.findViewById(R.id.btnRenamePlaylist)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeletePlaylist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_saved_playlist, parent, false))

    override fun getItemCount() = playlists.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pl = playlists[position]
        holder.tvName.text = pl.name
        holder.tvCount.text = "${pl.items.size} piste(s)"
        holder.itemView.setOnClickListener { onLoad(pl) }
        holder.btnRename.setOnClickListener { onRename(pl) }
        holder.btnDelete.setOnClickListener { onDelete(pl) }
    }
}
