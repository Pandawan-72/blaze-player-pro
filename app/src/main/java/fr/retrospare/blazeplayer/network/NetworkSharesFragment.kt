package fr.retrospare.blazeplayer.network

import android.app.AlertDialog
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
            viewModel.scanNetwork()
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
                dialogBinding.btnTypeDlna to ShareType.DLNA,
                dialogBinding.btnTypeFtp to ShareType.FTP
            ).forEach { (btn, t) ->
                btn.setTextColor(
                    if (t == type) resources.getColor(R.color.blue_accent, null)
                    else resources.getColor(R.color.on_surface_variant, null)
                )
            }
        }
        updateTypeButtons(selectedType)
        dialogBinding.btnTypeSmb.setOnClickListener { updateTypeButtons(ShareType.SMB) }
        dialogBinding.btnTypeDlna.setOnClickListener { updateTypeButtons(ShareType.DLNA) }
        dialogBinding.btnTypeFtp.setOnClickListener { updateTypeButtons(ShareType.FTP) }

        AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Ajouter un chemin réseau" else "Modifier")
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
            .show()
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
}
