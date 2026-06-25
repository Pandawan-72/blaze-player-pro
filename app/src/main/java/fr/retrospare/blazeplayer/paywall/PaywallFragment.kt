package fr.retrospare.blazeplayer.paywall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.databinding.FragmentPaywallBinding

@AndroidEntryPoint
class PaywallFragment : Fragment() {

    private val viewModel: PaywallViewModel by viewModels()
    private var _binding: FragmentPaywallBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPaywallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.checkProStatus()
        binding.btnPurchasePro.setOnClickListener {
            // TODO: Lancer le flux d'achat RevenueCat
        }
        binding.btnRestorePurchases.setOnClickListener {
            // TODO: Restaurer les achats
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
