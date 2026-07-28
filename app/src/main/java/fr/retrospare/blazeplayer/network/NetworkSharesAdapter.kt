package fr.retrospare.blazeplayer.network

import fr.retrospare.blazeplayer.ui.showPremium
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.ui.ButtonTextFitter
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType

class NetworkSharesAdapter(
    private val onTestConnection: (NetworkShare) -> Unit,
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
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        private val btnBrowse = view.findViewById<View>(R.id.btnBrowse)
        private val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)

        fun bind(share: NetworkShare) {
            tvName.text = share.name
            tvUrl.text = if (share.type == ShareType.UPNP) share.host else "${share.host}/${share.shareName}"
            val isUpnp = share.type == ShareType.UPNP
            tvBadgeType.text = if (isUpnp) "UPnP" else share.type.name
            tvBadgeType.setTextColor(
                ContextCompat.getColor(itemView.context, if (isUpnp) R.color.green_accent else R.color.blue_accent)
            )
            tvStatus.text = ""


            ButtonTextFitter.fit(btnBrowse as android.widget.TextView, minSp = 8, maxSp = 11)
            btnBrowse.setOnClickListener { onTestConnection(share) }
            btnEdit.setOnClickListener {
                android.app.AlertDialog.Builder(itemView.context)
                    .setItems(arrayOf(itemView.context.getString(fr.retrospare.blazeplayer.R.string.action_edit), itemView.context.getString(fr.retrospare.blazeplayer.R.string.action_delete))) { _, which ->
                        when (which) {
                            0 -> onEdit(share)
                            1 -> onDelete(share)
                        }
                    }.showPremium()
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<NetworkShare>() {
        override fun areItemsTheSame(old: NetworkShare, new: NetworkShare) = old.id == new.id
        override fun areContentsTheSame(old: NetworkShare, new: NetworkShare) = old == new
    }
}
