package fr.retrospare.blazeplayer.paywall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.databinding.FragmentPaywallBinding
import kotlinx.coroutines.launch

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
        observeState()
        viewModel.checkProStatus()

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnBuyPro.setOnClickListener {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(
                requireContext(),
                getString(R.string.info_dialog_title_info),
                getString(R.string.toast_billing_soon)
            )
        }
        binding.btnBuyProPlus.setOnClickListener {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(
                requireContext(),
                getString(R.string.info_dialog_title_info),
                getString(R.string.toast_billing_soon)
            )
        }
        binding.btnRestore.setOnClickListener {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(
                requireContext(),
                getString(R.string.info_dialog_title_info),
                getString(R.string.toast_restore_purchases_soon)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkProStatus()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        PaywallViewModel.PaywallState.Loading -> {
                            binding.tvTrialStatus.text = getString(R.string.loading)
                        }
                        is PaywallViewModel.PaywallState.Error -> {
                            binding.tvTrialStatus.text = getString(R.string.paywall_status_unavailable)
                        }
                        is PaywallViewModel.PaywallState.Ready -> renderState(state)
                    }
                }
            }
        }
    }

    private fun renderState(state: PaywallViewModel.PaywallState.Ready) {
        binding.tvTrialStatus.text = when {
            state.isProPlusPurchased -> getString(R.string.paywall_active_pro_plus)
            state.isTrialActive -> resources.getQuantityString(
                R.plurals.paywall_trial_days_left,
                state.trialDaysLeft,
                state.trialDaysLeft
            )
            state.isProPurchased -> getString(R.string.paywall_active_pro)
            else -> getString(R.string.paywall_trial_expired)
        }

        binding.btnBuyPro.isEnabled = !state.isProPurchased && !state.isProPlusPurchased
        binding.btnBuyPro.alpha = if (binding.btnBuyPro.isEnabled) 1f else 0.55f
        binding.btnBuyProPlus.isEnabled = !state.isProPlusPurchased
        binding.btnBuyProPlus.alpha = if (binding.btnBuyProPlus.isEnabled) 1f else 0.55f
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
