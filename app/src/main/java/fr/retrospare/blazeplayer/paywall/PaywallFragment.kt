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
import fr.retrospare.blazeplayer.ui.InfoDialog
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
        observeEvents()
        viewModel.checkProStatus()

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnBuyPro.setOnClickListener { viewModel.purchasePro(requireActivity()) }
        binding.btnBuyProPlus.setOnClickListener { viewModel.purchaseProPlus(requireActivity()) }
        binding.btnRestore.setOnClickListener { viewModel.restorePurchases() }
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

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event -> handleEvent(event) }
            }
        }
    }

    private fun handleEvent(event: PaywallViewModel.PaywallEvent) {
        val context = context ?: return
        when (event) {
            PaywallViewModel.PaywallEvent.ProPurchaseSuccess -> InfoDialog.show(
                context, getString(R.string.info_dialog_title_info), getString(R.string.paywall_purchase_success_pro)
            )
            PaywallViewModel.PaywallEvent.ProPlusPurchaseSuccess -> InfoDialog.show(
                context, getString(R.string.info_dialog_title_info), getString(R.string.paywall_purchase_success_pro_plus)
            )
            PaywallViewModel.PaywallEvent.PurchaseCancelled -> Unit
            is PaywallViewModel.PaywallEvent.PurchaseError -> InfoDialog.show(
                context, getString(R.string.info_dialog_title_info), getString(R.string.paywall_purchase_error, event.message)
            )
            PaywallViewModel.PaywallEvent.RestoreSuccess -> InfoDialog.show(
                context, getString(R.string.info_dialog_title_info), getString(R.string.paywall_restore_success)
            )
            PaywallViewModel.PaywallEvent.RestoreNothingFound -> InfoDialog.show(
                context, getString(R.string.info_dialog_title_info), getString(R.string.paywall_restore_nothing_found)
            )
            is PaywallViewModel.PaywallEvent.RestoreError -> InfoDialog.show(
                context, getString(R.string.info_dialog_title_info), getString(R.string.paywall_restore_error, event.message)
            )
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

        state.proPriceFormatted?.let { binding.tvProPrice.text = it }
        state.proPlusPriceFormatted?.let { binding.tvProPlusPrice.text = it }
        binding.btnBuyPro.text = state.proPriceFormatted?.let { getString(R.string.paywall_buy_pro_dynamic, it) }
            ?: getString(R.string.paywall_buy_pro)
        binding.btnBuyProPlus.text = state.proPlusPriceFormatted?.let { getString(R.string.paywall_buy_pro_plus_dynamic, it) }
            ?: getString(R.string.paywall_buy_pro_plus)

        val canBuyPro = !state.isProPurchased && !state.isProPlusPurchased && !state.isPurchaseInProgress
        val canBuyProPlus = !state.isProPlusPurchased && !state.isPurchaseInProgress
        binding.btnBuyPro.isEnabled = canBuyPro
        binding.btnBuyPro.alpha = if (canBuyPro) 1f else 0.55f
        binding.btnBuyProPlus.isEnabled = canBuyProPlus
        binding.btnBuyProPlus.alpha = if (canBuyProPlus) 1f else 0.55f
        binding.btnRestore.isEnabled = !state.isPurchaseInProgress
        binding.btnRestore.alpha = if (state.isPurchaseInProgress) 0.55f else 1f
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
