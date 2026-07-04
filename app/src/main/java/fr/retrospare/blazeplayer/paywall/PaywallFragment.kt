package fr.retrospare.blazeplayer.paywall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.retrospare.blazeplayer.R
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
        applySystemBarPadding()
        viewModel.checkProStatus()
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnBuyPro.setOnClickListener {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_info), getString(R.string.toast_billing_soon))
        }
        binding.btnBuyProPlus.setOnClickListener {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_info), getString(R.string.toast_billing_soon))
        }
        binding.btnRestore.setOnClickListener {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_info), getString(R.string.toast_restore_purchases_soon))
        }
    }

    private fun applySystemBarPadding() {
        val initialLeft = binding.paywallRoot.paddingLeft
        val initialTop = binding.paywallRoot.paddingTop
        val initialRight = binding.paywallRoot.paddingRight
        val initialBottom = binding.paywallRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.paywallRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.paywallRoot)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
