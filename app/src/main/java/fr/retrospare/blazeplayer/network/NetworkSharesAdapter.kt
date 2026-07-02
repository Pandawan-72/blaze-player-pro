package fr.retrospare.blazeplayer.network

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType

class NetworkSharesAdapter(
    private val onBrowse: (NetworkShare) -> Unit,
    private val onSetDefault: (NetworkShare) -> Unit,
    private val onEdit: (NetworkShare) -> Unit,
    private val onDelete: (NetworkShare) -> Unit
) : ListAdapter<NetworkShare, NetworkSharesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_share, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvShareName)
        private val tvUrl: TextView = view.findViewById(R.id.tvShareUrl)
        private val tvBadgeType: TextView = view.findViewById(R.id.tvBadgeType)
        private val tvBadgeDefault: TextView = view.findViewById(R.id.tvBadgeDefault)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        private val switchDefault: SwitchMaterial = view.findViewById(R.id.switchDefault)
        private val btnBrowse = view.findViewById<View>(R.id.btnBrowse)
        private val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        private val ivStar: ImageView = view.findViewById(R.id.ivStar)

        fun bind(share: NetworkShare) {
            tvName.text = share.name
            tvUrl.text = "${share.host}/${share.shareName}"
            tvBadgeType.text = share.type?.name ?: "SMB"
            tvBadgeDefault.visibility = if (share.isDefault) View.VISIBLE else View.GONE
            tvStatus.text = ""

            switchDefault.setOnCheckedChangeListener(null)
            switchDefault.isChecked = share.isDefault
            switchDefault.setOnCheckedChangeListener { _, checked ->
                if (checked) onSetDefault(share)
            }

            val starColor = if (share.isDefault) R.color.yellow_accent else R.color.on_surface_variant
            ivStar.setColorFilter(itemView.context.getColor(starColor))

            btnBrowse.setOnClickListener { onBrowse(share) }
            btnEdit.setOnClickListener {
                android.app.AlertDialog.Builder(itemView.context)
                    .setItems(arrayOf(itemView.context.getString(fr.retrospare.blazeplayer.R.string.action_edit), itemView.context.getString(fr.retrospare.blazeplayer.R.string.action_delete))) { _, which ->
                        when (which) {
                            0 -> onEdit(share)
                            1 -> onDelete(share)
                        }
                    }.show()
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NetworkShare>() {
        override fun areItemsTheSame(old: NetworkShare, new: NetworkShare) = old.id == new.id
        override fun areContentsTheSame(old: NetworkShare, new: NetworkShare) = old == new
    }
}
