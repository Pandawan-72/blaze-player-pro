package fr.retrospare.blazeplayer.network

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.data.model.NetworkShare
import fr.retrospare.blazeplayer.data.model.ShareType
import fr.retrospare.blazeplayer.databinding.DialogAddNetworkShareBinding
import fr.retrospare.blazeplayer.databinding.FragmentNetworkSharesBinding
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NetworkSharesFragment : Fragment() {

    private val viewModel: NetworkSharesViewModel by viewModels()
    private var _binding: FragmentNetworkSharesBinding? = null
    private val binding get() = _binding!!
    private lateinit var savedAdapter: NetworkSharesAdapter
    private lateinit var discoveredAdapter: DiscoveredDeviceAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetworkSharesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Applique les insets système
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, bars.top, 0, 0)
            insets
        }
        setupRecyclerViews()
        setupButtons()
        observeViewModel()
        viewModel.scanNetwork() // Scan automatique à l'ouverture
    }

    private fun setupRecyclerViews() {
        // Adapteur des appareils découverts
        discoveredAdapter = DiscoveredDeviceAdapter { device ->
            showDeviceConfig(device)
        }
        binding.recyclerDiscovered.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDiscovered.adapter = discoveredAdapter

        // Adapteur des chemins sauvegardés
        savedAdapter = NetworkSharesAdapter(
            onBrowse = { share ->
                val intent = android.content.Intent(requireContext(), fr.retrospare.blazeplayer.player.NetworkVideoBrowserActivity::class.java)
                intent.putExtra("shareId", share.id)
                startActivity(intent)
            },
            onSetDefault = { share -> viewModel.setDefault(share) },
            onEdit = { share -> showAddEditDialog(share) },
            onDelete = { share -> confirmDelete(share) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = savedAdapter
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnAdd.setOnClickListener { showAddEditDialog(null) }
        binding.btnScan.setOnClickListener { viewModel.scanNetwork() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Chemins sauvegardés
                launch {
                    viewModel.shares.collect { shares ->
                        savedAdapter.submitList(shares)
                        binding.tvEmpty.visibility = if (shares.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                // Appareils découverts
                launch {
                    viewModel.discoveredDevices.collect { devices ->
                        discoveredAdapter.submitList(devices)
                        val visible = devices.isNotEmpty()
                        binding.tvSectionDiscovered.visibility = if (visible) View.VISIBLE else View.GONE
                        binding.recyclerDiscovered.visibility = if (visible) View.VISIBLE else View.GONE
                        if (devices.isNotEmpty()) {
                            binding.tvSubtitle.text = resources.getQuantityString(R.plurals.devices_found, devices.size, devices.size)
                        }
                        // Message bas dynamique
                        if (!viewModel.isScanning.value) {
                            binding.tvEmpty.text = if (devices.isEmpty()) getString(R.string.toast_no_device_detected) else getString(R.string.toast_click_device_to_explore)
                            binding.tvEmpty.visibility = View.VISIBLE
                        }
                    }
                }

                // Scan en cours
                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
                        binding.btnScan.isEnabled = !scanning
                        binding.btnScan.alpha = if (scanning) 0.5f else 1f
                        binding.tvSubtitle.text = if (scanning) getString(R.string.scan_in_progress) else getString(R.string.scan_complete)
                    }
                }

                // Messages
                launch {
                    viewModel.message.collect { msg ->
                        msg?.let {
                            val text = when (it) {
                                fr.retrospare.blazeplayer.network.NetworkSharesViewModel.NetworkMessage.PATH_SAVED -> getString(R.string.toast_path_saved)
                                fr.retrospare.blazeplayer.network.NetworkSharesViewModel.NetworkMessage.PATH_DELETED -> getString(R.string.toast_path_deleted)
                                fr.retrospare.blazeplayer.network.NetworkSharesViewModel.NetworkMessage.SCAN_UNAVAILABLE_EMULATOR -> getString(R.string.toast_scan_unavailable_emulator)
                            }
                            android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.clearMessage()
                        }
                    }
                }
            }
        }
    }

    private fun showDeviceConfig(device: NetworkScanner.DiscoveredDevice) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_network_connect, null)
        val tvName = dialogView.findViewById<android.widget.TextView>(R.id.tvDeviceName)
        val tvIp = dialogView.findViewById<android.widget.TextView>(R.id.tvDeviceIp)
        val tvBadge = dialogView.findViewById<android.widget.TextView>(R.id.tvTypeBadge)
        val layoutShare = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutShare)
        val etShare = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etShare)
        val etUsername = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)

        tvName.text = device.name
        tvIp.text = device.ip
        tvBadge.text = device.type.name
        tvBadge.setBackgroundResource(R.drawable.bg_badge_blue)

        // Charge les partages SMB disponibles
        viewLifecycleOwner.lifecycleScope.launch {
            val shares = viewModel.listShares(device.ip, null, null)
            if (shares.isNotEmpty()) {
                etShare.setText(shares.first())
                if (shares.size > 1) {
                    etShare.setOnClickListener {
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.dialog_choose_share))
                            .setItems(shares.toTypedArray()) { _: DialogInterface, j: Int ->
                                etShare.setText(shares[j])
                            }.show()
                    }
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(null)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.action_add_to_favorites)) { _, _ ->
                val share = NetworkShare(
                    id = "${device.type.name.lowercase()}_${device.ip}",
                    name = device.name,
                    host = device.ip,
                    port = 445,
                    shareName = etShare.text?.toString() ?: "",
                    username = etUsername.text?.toString()?.takeIf { it.isNotEmpty() },
                    password = etPassword.text?.toString()?.takeIf { it.isNotEmpty() },
                    type = device.type
                )
                viewModel.saveShare(share)
                android.widget.Toast.makeText(requireContext(), getString(R.string.toast_added_to_favorites, device.name), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create().also { d ->
                d.show()
                d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
    }

    private fun showAddEditDialog(existing: NetworkShare?) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_network_share, null)
        val dialogBinding = DialogAddNetworkShareBinding.bind(dialogView)
        var selectedType = ShareType.SMB

        dialogBinding.tvDialogTitle.text = if (existing == null) getString(R.string.add_network_path) else getString(R.string.dialog_edit_path)

        existing?.let {
            dialogBinding.etName.setText(it.name)
            dialogBinding.etHost.setText(it.host)
            dialogBinding.etPort.setText(it.port?.toString() ?: "")
            dialogBinding.etShareName.setText(it.shareName)
            dialogBinding.etUsername.setText(it.username ?: "")
            dialogBinding.etPassword.setText(it.password ?: "")
            dialogBinding.switchDefault.isChecked = it.isDefault
            selectedType = it.type
        }

        fun updateTypeButtons(type: ShareType) {
            selectedType = type
            listOf(dialogBinding.btnTypeSmb to ShareType.SMB)
                .forEach { (btn, t) ->
                    val selected = t == type
                    btn.setTextColor(if (selected) resources.getColor(R.color.blue_accent, null) else resources.getColor(R.color.on_surface_variant, null))
                    btn.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else android.R.color.transparent)
                }
        }
        updateTypeButtons(selectedType)
        dialogBinding.btnTypeSmb.setOnClickListener { updateTypeButtons(ShareType.SMB) }

        AlertDialog.Builder(requireContext())
            .setTitle(null)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                val name = dialogBinding.etName.text.toString().trim()
                val host = dialogBinding.etHost.text.toString().trim()
                val shareName = dialogBinding.etShareName.text.toString().trim()
                if (name.isEmpty() || host.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_name_host_required), android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val share = existing?.copy(
                    name = name, host = host,
                    port = dialogBinding.etPort.text.toString().toIntOrNull(),
                    shareName = shareName,
                    username = dialogBinding.etUsername.text.toString().takeIf { it.isNotEmpty() },
                    password = dialogBinding.etPassword.text.toString().takeIf { it.isNotEmpty() },
                    type = selectedType,
                    isDefault = dialogBinding.switchDefault.isChecked
                ) ?: viewModel.createShare(name, host, dialogBinding.etPort.text.toString().toIntOrNull(), shareName,
                    dialogBinding.etUsername.text.toString().takeIf { it.isNotEmpty() },
                    dialogBinding.etPassword.text.toString().takeIf { it.isNotEmpty() },
                    selectedType, dialogBinding.switchDefault.isChecked)
                viewModel.saveShare(share)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create().also { d ->
                d.show()
                d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
    }

    private fun confirmDelete(share: NetworkShare) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.action_delete))
            .setMessage(getString(R.string.dialog_delete_message, share.name))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ -> viewModel.deleteShare(share.id) }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
