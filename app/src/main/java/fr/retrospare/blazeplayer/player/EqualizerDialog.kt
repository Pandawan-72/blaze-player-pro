package fr.retrospare.blazeplayer.player

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.databinding.DialogEqualizerBinding

class EqualizerDialog(
    private val eqManager: EqualizerManager
) : BottomSheetDialogFragment() {

    private var _binding: DialogEqualizerBinding? = null
    private val binding get() = _binding!!
    private var selectedPreset = "Flat"
    private val bandViews = mutableListOf<EqBandView>()
    private var isApplyingPreset = false
    private val bandLabels = mutableListOf<TextView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            BottomSheetBehavior.from(it).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedPreset = eqManager.getSavedPreset()
        setupHeader()
        setupBands()
        setupPresets()
        setupEffects()
        binding.seekBassBoost.progress = eqManager.getSavedBassBoost()
        binding.tvBassValue.text = "${eqManager.getSavedBassBoost() / 10}%"
        binding.seekVirtualizer.progress = eqManager.getSavedVirtualizer()
        binding.tvVirtValue.text = "${eqManager.getSavedVirtualizer() / 10}%"
    }

    private fun setupHeader() {
        binding.btnCloseEq.setOnClickListener { dismiss() }
        binding.switchEq.isChecked = eqManager.equalizer?.enabled ?: false
        binding.tvEqStatus.text = if (eqManager.equalizer?.enabled == true) "ON" else "OFF"
        binding.switchEq.setOnCheckedChangeListener { _, checked ->
            eqManager.equalizer?.enabled = checked
            eqManager.bassBoost?.enabled = checked
            eqManager.virtualizer?.enabled = checked
            binding.tvEqStatus.text = if (checked) "ON" else "OFF"
            binding.tvEqStatus.setTextColor(
                ContextCompat.getColor(requireContext(),
                    if (checked) R.color.green_accent else R.color.on_surface_variant)
            )
        }
    }

    private fun setupBands() {
        val numBands = eqManager.numBands

        for (band in 0 until numBands) {
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val tvDb = TextView(requireContext()).apply {
                val db = eqManager.getBandLevel(band) / 100
                text = "${if (db >= 0) "+" else ""}$db"
                textSize = 9f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.green_accent))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 20.dpToPx()
                )
            }
            bandLabels.add(tvDb)

            val bandView = EqBandView(requireContext()).apply {
                minLevel = eqManager.minLevel
                maxLevel = eqManager.maxLevel
                currentLevel = eqManager.getBandLevel(band)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                onLevelChanged = { level ->
                    eqManager.setBandLevel(band, level)
                    val db = level / 100
                    tvDb.text = "${if (db >= 0) "+" else ""}$db"
                    if (!isApplyingPreset) {
                        selectedPreset = "Custom"
                        eqManager.savePreset("Custom")
                        refreshChips()
                    }
                }
            }
            bandViews.add(bandView)

            val freq = eqManager.getBandFreq(band)
            val freqLabel = if (freq >= 1000) "${freq/1000}k" else "$freq"
            val tvFreq = TextView(requireContext()).apply {
                text = freqLabel
                textSize = 9f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 20.dpToPx()
                )
            }

            container.addView(tvDb)
            container.addView(bandView)
            container.addView(tvFreq)
            binding.bandsContainer.addView(container)
        }
    }

    private fun setupPresets() {
        eqManager.presets.keys.forEach { presetName ->
            val chip = Chip(requireContext()).apply {
                text = presetName
                isCheckable = true
                isChecked = presetName == selectedPreset
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(requireContext(), R.color.green_accent),
                        ContextCompat.getColor(requireContext(), R.color.surface_variant)
                    )
                )
                setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.BLACK,
                        ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
                ))
                setOnClickListener {
                    selectedPreset = presetName
                    isApplyingPreset = true
                    eqManager.applyPreset(presetName)
                    eqManager.savePreset(presetName)
                    refreshChips()
                    refreshBands()
                    isApplyingPreset = false
                }
            }
            binding.presetContainer.addView(chip)
        }
    }

    private fun refreshChips() {
        for (i in 0 until binding.presetContainer.childCount) {
            val chip = binding.presetContainer.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip.text == selectedPreset
        }
    }

    private fun refreshBands() {
        for (band in 0 until eqManager.numBands) {
            if (band < bandViews.size) {
                val level = eqManager.getBandLevel(band)
                bandViews[band].silent = true
                bandViews[band].currentLevel = level
                bandViews[band].silent = false
                val db = level / 100
                bandLabels[band].text = "${if (db >= 0) "+" else ""}$db"
            }
        }
    }

    private fun setupEffects() {
        binding.seekBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    eqManager.setBassBoost(progress)
                    binding.tvBassValue.text = "${progress / 10}%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    eqManager.setVirtualizer(progress)
                    binding.tvVirtValue.text = "${progress / 10}%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
