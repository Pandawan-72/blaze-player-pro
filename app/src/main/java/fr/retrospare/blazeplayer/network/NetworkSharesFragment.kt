package fr.retrospare.blazeplayer.network

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
    private lateinit var adapter: NetworkSharesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworkSharesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = NetworkSharesAdapter(
            onBrowse = { share ->
                val bundle = Bundle().apply {
                    putString("shareId", share.id)
                    putString("path", "")
                    putBoolean("isNetwork", true)
                }
                findNavController().navigate(R.id.action_network_to_browser, bundle)
            },
            onSetDefault = { share -> viewModel.setDefault(share) },
            onEdit = { share -> showAddEditDialog(share) },
            onDelete = { share -> confirmDelete(share) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnAdd.setOnClickListener { showAddEditDialog(null) }
        binding.btnScan.setOnClickListener {
            Toast.makeText(requireContext(), "Scan réseau en cours...", Toast.LENGTH_SHORT).show()
            showScanDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.shares.collect { shares ->
                        adapter.submitList(shares)
                        binding.tvEmpty.visibility =
                            if (shares.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.message.collect { msg ->
                        msg?.let {
                            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                            viewModel.clearMessage()
                        }
                    }
                }
            }
        }
    }

    private fun showAddEditDialog(existing: NetworkShare?) {
        val dialogBinding = DialogAddNetworkShareBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = if (existing == null) "Ajouter un chemin réseau" else "Modifier le chemin"
        var selectedType = ShareType.SMB

        // Pré-remplir si édition
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

        // Sélecteur de type
        fun updateTypeButtons(type: ShareType) {
            selectedType = type
            listOf(
                dialogBinding.btnTypeSmb to ShareType.SMB,
                dialogBinding.btnTypeDlna to ShareType.DLNA
            ).forEach { (btn, t) ->
                val selected = t == type
                btn.setTextColor(
                    if (selected) resources.getColor(R.color.blue_accent, null)
                    else resources.getColor(R.color.on_surface_variant, null)
                )
                btn.setBackgroundResource(
                    if (selected) R.drawable.bg_tab_selected
                    else android.R.color.transparent
                )
            }
        }
        updateTypeButtons(selectedType)
        dialogBinding.btnTypeSmb.setOnClickListener { updateTypeButtons(ShareType.SMB) }
        dialogBinding.btnTypeDlna.setOnClickListener { updateTypeButtons(ShareType.DLNA) }

        AlertDialog.Builder(requireContext())
            .setTitle(null)
            .setView(dialogBinding.root)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val name = dialogBinding.etName.text.toString().trim()
                val host = dialogBinding.etHost.text.toString().trim()
                val shareName = dialogBinding.etShareName.text.toString().trim()
                val port = dialogBinding.etPort.text.toString().toIntOrNull()
                val username = dialogBinding.etUsername.text.toString().trim().ifEmpty { null }
                val password = dialogBinding.etPassword.text.toString().ifEmpty { null }
                val isDefault = dialogBinding.switchDefault.isChecked

                if (name.isEmpty() || host.isEmpty() || shareName.isEmpty()) {
                    Toast.makeText(requireContext(), "Nom, hôte et dossier sont requis", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val share = existing?.copy(
                    name = name, host = host, port = port,
                    shareName = shareName, username = username,
                    password = password, type = selectedType,
                    isDefault = isDefault
                ) ?: viewModel.createShare(
                    name, host, port, shareName, username, password, selectedType, isDefault
                )
                viewModel.saveShare(share)
            }
            .setNegativeButton("Annuler", null)
            .create()
            .also { d ->
                d.show()
                d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            }
    }

    private fun confirmDelete(share: NetworkShare) {
        AlertDialog.Builder(requireContext())
            .setTitle("Supprimer")
            .setMessage("Supprimer \"${share.name}\" ?")
            .setPositiveButton("Supprimer") { _, _ -> viewModel.deleteShare(share.id) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    private fun showScanDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Recherche sur le réseau local...")
            .setView(android.widget.ProgressBar(requireContext()).apply { isIndeterminate = true })
            .setNegativeButton("Annuler", null)
            .create()
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanNetwork()
            viewModel.scannedShares.collect { devices ->
                if (devices.isEmpty()) return@collect
                dialog.dismiss()

                val names = devices.map { "${it.type.name}  ${it.name}  (${it.host})" }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Appareils détectés")
                    .setItems(names) { _, i ->
                        val device = devices[i]
                        showShareConfig(device)
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
    }

    private fun showShareConfig(device: fr.retrospare.blazeplayer.data.model.NetworkShare) {
        val view = layoutInflater.inflate(fr.retrospare.blazeplayer.R.layout.dialog_add_network_share, null)
        val binding = fr.retrospare.blazeplayer.databinding.DialogAddNetworkShareBinding.bind(view)

        // Pré-remplit les champs connus
        binding.etName.setText(device.name)
        binding.etHost.setText(device.host)
        binding.etHost.isEnabled = false
        binding.etPort.setText((device.port ?: 445).toString())

        // Si DLNA, masque les champs SMB
        val isSmb = device.type == fr.retrospare.blazeplayer.data.model.ShareType.SMB

        // Charge les shares disponibles si SMB
        if (isSmb) {
            viewLifecycleOwner.lifecycleScope.launch {
                val shares = viewModel.listShares(device.host, null, null)
                if (shares.isNotEmpty()) {
                    binding.etShareName.setText(shares.first())
                    // Propose le choix si plusieurs
                    if (shares.size > 1) {
                        binding.etShareName.setOnClickListener {
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Choisir un partage")
                                .setItems(shares.toTypedArray()) { _: android.content.DialogInterface, j: Int ->
                                    binding.etShareName.setText(shares[j])
                                }.show()
                        }
                    }
                }
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Configurer ${device.name}")
            .setView(view)
            .setPositiveButton("Enregistrer") { _, _ ->
                val share = device.copy(
                    name = binding.etName.text.toString(),
                    shareName = binding.etShareName.text.toString(),
                    username = binding.etUsername.text.toString().takeIf { it.isNotEmpty() },
                    password = binding.etPassword.text.toString().takeIf { it.isNotEmpty() }
                )
                viewModel.saveShare(share)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

}
