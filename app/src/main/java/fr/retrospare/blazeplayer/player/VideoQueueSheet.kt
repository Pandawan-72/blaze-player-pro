package fr.retrospare.blazeplayer.player

import fr.retrospare.blazeplayer.ui.showPremium
import fr.retrospare.blazeplayer.ui.ButtonTextFitter
import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.playlist.PlaylistCategory
import fr.retrospare.blazeplayer.playlist.displayLabel

object VideoQueueSheet {
    fun show(
        context: Context,
        category: PlaylistCategory,
        currentPath: String? = null,
        onItemSelected: ((List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef>, Int) -> Unit)? = null,
        onChanged: (() -> Unit)? = null
    ) {
        var tracks = VideoQueueManager.getQueue(context, category)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_video_queue, null)
        dialog.setContentView(view)
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val title = view.findViewById<TextView>(R.id.tvVideoQueueTitle)
        val empty = view.findViewById<TextView>(R.id.tvVideoQueueEmpty)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerVideoQueue)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseVideoQueue)
        val btnToPlaylist = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVideoQueueToPlaylist)
        val btnClear = view.findViewById<TextView>(R.id.btnClearVideoQueue)
        ButtonTextFitter.fitRecursively(view, minSp = 9, maxSp = 13)

        recycler.layoutManager = GridLayoutManager(context, 2)
        lateinit var adapter: VideoQueueAdapter

        fun refreshHeader(adapter: VideoQueueAdapter? = null) {
            tracks = VideoQueueManager.getQueue(context, category)
            title.text = context.getString(R.string.video_queue_title_with_category, category.displayLabel(context), tracks.size)
            recycler.visibility = if (tracks.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
            btnClear.isEnabled = tracks.isNotEmpty()
            btnClear.alpha = if (tracks.isNotEmpty()) 1f else 0.4f
            btnToPlaylist.isEnabled = tracks.isNotEmpty()
            btnToPlaylist.alpha = if (tracks.isNotEmpty()) 1f else 0.4f
            adapter?.submit(tracks)
            onChanged?.invoke()
        }

        fun showRemoveDialog() {
            val snapshot = VideoQueueManager.getQueue(context, category)
            if (snapshot.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.toast_list_already_empty), Toast.LENGTH_SHORT).show()
                refreshHeader(adapter)
                return
            }
            val labels = snapshot.mapIndexed { index, track ->
                val cleanName = track.name.substringBeforeLast('.').ifBlank { track.name.ifBlank { track.path.substringAfterLast('/') } }
                "${index + 1}. $cleanName"
            }
            val checked = BooleanArray(snapshot.size) { false }
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.dialog_clean_list))
                .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, isChecked -> checked[i] = isChecked }
                .setPositiveButton(context.getString(R.string.action_remove_selection)) { _, _ ->
                    val current = VideoQueueManager.getQueue(context, category).toMutableList()
                    checked.indices.reversed().forEach { i ->
                        val pathToRemove = snapshot.getOrNull(i)?.path
                        if (checked[i] && pathToRemove != null) {
                            val index = current.indexOfFirst { it.path == pathToRemove }
                            if (index >= 0) current.removeAt(index)
                        }
                    }
                    VideoQueueManager.saveQueue(context, category, current)
                    Toast.makeText(context, context.getString(R.string.toast_video_queue_item_removed), Toast.LENGTH_SHORT).show()
                    refreshHeader(adapter)
                }
                .setNeutralButton(context.getString(R.string.action_clear_all)) { _, _ ->
                    VideoQueueManager.clearQueue(context, category)
                    Toast.makeText(context, context.getString(R.string.toast_video_queue_cleared), Toast.LENGTH_SHORT).show()
                    refreshHeader(adapter)
                }
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .showPremium()
        }
        adapter = VideoQueueAdapter(
            tracks = tracks,
            currentPath = currentPath,
            onItemClick = { index ->
                val queue = VideoQueueManager.getQueue(context, category)
                if (queue.isNotEmpty() && index in queue.indices) {
                    if (onItemSelected != null) {
                        onItemSelected.invoke(queue, index)
                    } else {
                        PlayerRouter.openPlaylist(context, queue, index)
                    }
                    dialog.dismiss()
                }
            },
            onRemove = { track ->
                VideoQueueManager.removeFromQueue(context, category, track.path)
                Toast.makeText(context, context.getString(R.string.toast_video_queue_item_removed), Toast.LENGTH_SHORT).show()
                refreshHeader(adapter)
            }
        )
        recycler.adapter = adapter
        var movedDuringDrag = false
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true
            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false
                val moved = adapter.moveItem(from, to)
                if (moved) movedDuringDrag = true
                return moved
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    movedDuringDrag = false
                    viewHolder?.itemView?.alpha = 0.92f
                    viewHolder?.itemView?.elevation = 10f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.elevation = 0f
                if (movedDuringDrag) {
                    tracks = adapter.currentItems()
                    VideoQueueManager.saveQueue(context, category, tracks)
                    adapter.notifyDataSetChanged()
                    onChanged?.invoke()
                    movedDuringDrag = false
                }
            }
        })
        touchHelper.attachToRecyclerView(recycler)
        refreshHeader(adapter)

        btnClose.setOnClickListener { dialog.dismiss() }
        btnToPlaylist.setOnClickListener {
            val queue = VideoQueueManager.getQueue(context, category)
            if (queue.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.toast_list_already_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fr.retrospare.blazeplayer.playlist.PlaylistDialogs.showAddToPlaylistPicker(
                context = context,
                category = category,
                tracks = queue,
                onAdded = { onChanged?.invoke() }
            )
        }
        btnClear.setOnClickListener { showRemoveDialog() }
        dialog.show()
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
