package fr.retrospare.blazeplayer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R

data class PlaylistItem(val path: String, val name: String)

class PlaylistAdapter(
    private val items: MutableList<PlaylistItem>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    private var currentIndex = 0

    fun setCurrentIndex(index: Int) {
        val old = currentIndex
        currentIndex = index
        notifyItemChanged(old)
        notifyItemChanged(index)
    }

    fun addItem(item: PlaylistItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun getItems() = items.toList()

    fun removeItem(item: PlaylistItem) {
        val idx = items.indexOf(item)
        if (idx >= 0) { items.removeAt(idx); notifyItemRemoved(idx) }
    }

    fun clearAll() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == currentIndex)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvTrackName)
        private val tvIndex: TextView = view.findViewById(R.id.tvTrackIndex)
        private val indicator: View = view.findViewById(R.id.playingIndicator)

        fun bind(item: PlaylistItem, isPlaying: Boolean) {
            tvName.text = item.name.substringBeforeLast(".")
            tvIndex.text = (adapterPosition + 1).toString()
            indicator.visibility = if (isPlaying) View.VISIBLE else View.INVISIBLE
            tvName.setTextColor(
                itemView.context.getColor(
                    if (isPlaying) R.color.green_accent else R.color.on_surface
                )
            )
            itemView.setOnClickListener { onItemClick(adapterPosition) }
        }
    }
}
