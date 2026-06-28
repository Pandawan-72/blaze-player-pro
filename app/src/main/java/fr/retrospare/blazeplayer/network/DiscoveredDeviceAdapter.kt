package fr.retrospare.blazeplayer.network

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.databinding.ItemNetworkDeviceBinding

class DiscoveredDeviceAdapter(
    private val onClick: (NetworkScanner.DiscoveredDevice) -> Unit
) : ListAdapter<NetworkScanner.DiscoveredDevice, DiscoveredDeviceAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemNetworkDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: NetworkScanner.DiscoveredDevice) {
            binding.tvName.text = device.name
            binding.tvIp.text = device.ip

            when (device.type) {
                ShareType.SMB -> {
                    binding.tvBadge.text = "SMB"
                    binding.tvBadge.setBackgroundResource(R.drawable.bg_badge_blue)
                    binding.ivIcon.setImageResource(R.drawable.ic_folder)
                }
                ShareType.DLNA -> {
                    binding.tvBadge.text = "DLNA"
                    binding.tvBadge.setBackgroundResource(R.drawable.bg_badge_green)
                    binding.ivIcon.setImageResource(R.drawable.ic_wifi)
                }
                else -> {
                    binding.tvBadge.text = "NET"
                    binding.tvBadge.setBackgroundResource(R.drawable.bg_badge_gray)
                }
            }

            binding.root.setOnClickListener { onClick(device) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemNetworkDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<NetworkScanner.DiscoveredDevice>() {
        override fun areItemsTheSame(a: NetworkScanner.DiscoveredDevice, b: NetworkScanner.DiscoveredDevice) = a.ip == b.ip
        override fun areContentsTheSame(a: NetworkScanner.DiscoveredDevice, b: NetworkScanner.DiscoveredDevice) = a == b
    }
}
