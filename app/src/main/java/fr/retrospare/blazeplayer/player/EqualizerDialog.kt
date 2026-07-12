package fr.retrospare.blazeplayer.player

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.databinding.DialogEqualizerBinding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Égaliseur 10 bandes et réglages DSP avancés.
 *
 * Cette feuille ne possède aucun AudioEffect : elle ne fait qu'écrire les préférences. Le service
 * audio est l'unique propriétaire de l'égaliseur natif, du LoudnessEnhancer et du processeur PCM.
 */
class EqualizerDialog(
    private val eqManager: EqualizerManager
) : BottomSheetDialogFragment() {

    private var _binding: DialogEqualizerBinding? = null
    private val binding get() = _binding!!
    private var selectedPreset = "Flat"
    private val bandViews = mutableListOf<EqBandView>()
    private val bandLabels = mutableListOf<TextView>()
    private var isApplyingPreset = false
    private var dynamicColorPrefs: SharedPreferences? = null
    private var currentDynamicAccent = AudioDynamicColor.DEFAULT_ACCENT
    private var themeAnimator: ValueAnimator? = null

    private val dynamicColorListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_DYNAMIC_ACCENT && isAdded && _binding != null) {
            val accent = resolveDynamicAccent(prefs)
            binding.root.post { animateEqualizerTheme(accent) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            // Le conteneur Material ne doit pas masquer le dégradé dynamique de la feuille.
            it.background = ColorDrawable(Color.TRANSPARENT)
            BottomSheetBehavior.from(it).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
        dynamicColorPrefs?.registerOnSharedPreferenceChangeListener(dynamicColorListener)
    }

    override fun onStop() {
        dynamicColorPrefs?.unregisterOnSharedPreferenceChangeListener(dynamicColorListener)
        super.onStop()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDynamicBackground()
        selectedPreset = eqManager.getSavedPreset()
        setupHeader()
        setupBands()
        setupPresets()
        setupAdvancedTabs()
        setupToneControls()
        setupDynamicsControls()
        setupAmbienceControls()
        setupReplayGainControls()
        applyCapabilityVisibility()
    }


    private fun setupDynamicBackground() {
        val prefs = requireContext().getSharedPreferences(DYNAMIC_AUDIO_PREFS, Context.MODE_PRIVATE)
        dynamicColorPrefs = prefs
        currentDynamicAccent = resolveDynamicAccent(prefs)
        applyEqualizerTheme(currentDynamicAccent)
    }

    private fun resolveDynamicAccent(prefs: SharedPreferences): Int {
        return if (AudioProSettings.read(requireContext()).dynamicTheme) {
            prefs.getInt(KEY_DYNAMIC_ACCENT, AudioDynamicColor.DEFAULT_ACCENT)
        } else {
            AudioDynamicColor.DEFAULT_ACCENT
        }
    }

    /**
     * Reprend le même dégradé que le player. Les cartes restent sombres mais reçoivent une légère
     * teinte et un contour issus de la pochette pour que la couleur reste visible sur tout l'écran.
     */
    private fun applyEqualizerTheme(accent: Int) {
        if (_binding == null) return
        val background = AudioDynamicColor.backgroundFromAccent(accent)
        binding.root.background = buildEqualizerBackground(background)
        listOf(
            binding.eqBandsCard,
            binding.panelTone,
            binding.panelDynamics,
            binding.panelAmbience,
            binding.replayGainCard
        ).forEach { it.background = buildEqualizerCardBackground(accent) }
        currentDynamicAccent = accent
    }

    private fun animateEqualizerTheme(targetAccent: Int) {
        if (_binding == null || targetAccent == currentDynamicAccent) {
            applyEqualizerTheme(targetAccent)
            return
        }
        themeAnimator?.cancel()
        themeAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentDynamicAccent, targetAccent).apply {
            duration = 380L
            addUpdateListener { animator ->
                applyEqualizerTheme(animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun buildEqualizerBackground(color: Int): GradientDrawable {
        val radius = 28.dpToPx().toFloat()
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                AudioDynamicColor.mix(Color.rgb(2, 7, 9), color, 0.56f),
                AudioDynamicColor.mix(color, Color.rgb(18, 24, 30), 0.08f),
                AudioDynamicColor.mix(Color.rgb(2, 7, 9), color, 0.34f)
            )
        ).apply {
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
    }

    private fun buildEqualizerCardBackground(accent: Int): GradientDrawable {
        val base = AudioDynamicColor.mix(Color.rgb(10, 16, 26), accent, 0.11f)
        val glacierStroke = ContextCompat.getColor(requireContext(), R.color.audio_library_accent_stroke)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                AudioDynamicColor.mix(base, Color.WHITE, 0.025f),
                AudioDynamicColor.mix(base, Color.BLACK, 0.13f)
            )
        ).apply {
            cornerRadius = 14.dpToPx().toFloat()
            // Liseré glacier commun à l'égaliseur 10 bandes et aux cartes de potentiomètres.
            setStroke(1.dpToPx().coerceAtLeast(1), glacierStroke)
        }
    }

    private fun setupHeader() {
        AudioProSettings.migrateOutputPreferences(requireContext())
        binding.btnCloseEq.setOnClickListener { dismiss() }
        applyAudioStyleToggle(binding.switchEq)
        binding.switchEq.isChecked = eqManager.isEnabled()
        refreshEqStatus(eqManager.isEnabled())
        binding.switchEq.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // Les effets PCM et la sortie directe/float sont exclusifs. Réactiver Réglages son
                // remet donc la qualité en mode automatique et coupe la sortie directe.
                AudioProSettings.prepareForSoundSettings(requireContext())
            }
            eqManager.setEnabled(checked)
            refreshEqStatus(checked)
        }
    }

    private fun refreshEqStatus(enabled: Boolean) {
        binding.tvEqStatus.setText(if (enabled) R.string.eq_status_on else R.string.eq_status_off)
        binding.tvEqStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.green_accent else R.color.on_surface_variant
            )
        )
    }

    private fun setupBands() {
        binding.bandsContainer.removeAllViews()
        bandViews.clear()
        bandLabels.clear()

        for (band in 0 until eqManager.numBands) {
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val tvDb = TextView(requireContext()).apply {
                text = compactDb(eqManager.getBandLevel(band))
                textSize = 9f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.green_accent))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    20.dpToPx()
                )
            }
            bandLabels += tvDb

            val bandView = EqBandView(requireContext()).apply {
                minLevel = eqManager.minLevel
                maxLevel = eqManager.maxLevel
                currentLevel = eqManager.getBandLevel(band)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                onLevelChanged = { level ->
                    eqManager.setBandLevel(band, level)
                    tvDb.text = compactDb(level)
                    if (!isApplyingPreset) {
                        selectedPreset = "Custom"
                        refreshChips()
                    }
                }
            }
            bandViews += bandView

            val frequency = eqManager.getBandFreq(band)
            val frequencyLabel = if (frequency >= 1000) "${frequency / 1000}k" else "$frequency"
            val tvFrequency = TextView(requireContext()).apply {
                text = frequencyLabel
                textSize = 9f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    20.dpToPx()
                )
            }

            container.addView(tvDb)
            container.addView(bandView)
            container.addView(tvFrequency)
            binding.bandsContainer.addView(container)
        }
    }

    private fun setupPresets() {
        binding.presetContainer.removeAllViews()
        eqManager.presets.keys.forEach { presetName ->
            val chip = Chip(requireContext()).apply {
                text = eqManager.getPresetDisplayName(requireContext(), presetName)
                tag = presetName
                isCheckable = true
                isChecked = presetName == selectedPreset
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(requireContext(), R.color.green_accent),
                        ContextCompat.getColor(requireContext(), R.color.surface_variant)
                    )
                )
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(
                            Color.BLACK,
                            ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
                        )
                    )
                )
                setOnClickListener {
                    selectedPreset = presetName
                    isApplyingPreset = true
                    eqManager.applyPreset(presetName)
                    refreshChips()
                    refreshBands()
                    isApplyingPreset = false
                }
            }
            binding.presetContainer.addView(chip)
        }
    }

    private fun setupAdvancedTabs() {
        binding.advancedTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            binding.panelTone.visibility = if (checkedId == binding.btnTabTone.id) View.VISIBLE else View.GONE
            binding.panelDynamics.visibility = if (checkedId == binding.btnTabDynamics.id) View.VISIBLE else View.GONE
            binding.panelAmbience.visibility = if (checkedId == binding.btnTabAmbience.id) View.VISIBLE else View.GONE
        }
        if (binding.advancedTabs.checkedButtonId == View.NO_ID) {
            binding.advancedTabs.check(binding.btnTabTone.id)
        } else {
            binding.panelTone.visibility = View.VISIBLE
            binding.panelDynamics.visibility = View.GONE
            binding.panelAmbience.visibility = View.GONE
        }
    }

    private fun setupToneControls() {
        configureKnob(
            binding.knobBass,
            -1000,
            1000,
            50,
            0,
            eqManager.getSavedToneBass(),
            getString(R.string.eq_bass)
        ) { value ->
            eqManager.setToneBass(value)
            binding.tvBassToneValue.text = formatDb(value)
        }
        binding.tvBassToneValue.text = formatDb(eqManager.getSavedToneBass())
        binding.knobBass.setAccentColor(ContextCompat.getColor(requireContext(), R.color.blue_accent))

        configureKnob(
            binding.knobTreble,
            -1000,
            1000,
            50,
            0,
            eqManager.getSavedToneTreble(),
            getString(R.string.eq_treble)
        ) { value ->
            eqManager.setToneTreble(value)
            binding.tvTrebleToneValue.text = formatDb(value)
        }
        binding.tvTrebleToneValue.text = formatDb(eqManager.getSavedToneTreble())
        binding.knobTreble.setAccentColor(ContextCompat.getColor(requireContext(), R.color.yellow_accent))

        configureKnob(
            binding.knobBalance,
            -100,
            100,
            1,
            0,
            eqManager.getSavedBalance(),
            getString(R.string.eq_balance)
        ) { value ->
            eqManager.setBalance(value)
            binding.tvBalanceValue.text = formatBalance(value)
        }
        binding.tvBalanceValue.text = formatBalance(eqManager.getSavedBalance())
        binding.knobBalance.setAccentColor(ContextCompat.getColor(requireContext(), R.color.green_accent))

        configureKnob(
            binding.knobStereo,
            0,
            150,
            1,
            100,
            eqManager.getSavedStereoWidth(),
            getString(R.string.eq_stereo)
        ) { value ->
            eqManager.setStereoWidth(value)
            binding.tvStereoValue.text = "$value%"
        }
        binding.tvStereoValue.text = "${eqManager.getSavedStereoWidth()}%"
        binding.knobStereo.setAccentColor(ContextCompat.getColor(requireContext(), R.color.blue_accent))

        binding.btnMono.isChecked = eqManager.isMonoEnabled()
        refreshMonoButton(eqManager.isMonoEnabled())
        binding.btnMono.addOnCheckedChangeListener { _, checked ->
            eqManager.setMonoEnabled(checked)
            refreshMonoButton(checked)
        }
    }

    private fun setupDynamicsControls() {
        configureKnob(
            binding.knobPreamp,
            EqualizerManager.PREAMP_MIN,
            EqualizerManager.PREAMP_MAX,
            50,
            0,
            eqManager.getSavedPreamp(),
            getString(R.string.eq_preamp_label)
        ) { value ->
            eqManager.setPreamp(value)
            binding.tvPreampKnobValue.text = formatDb(value)
        }
        binding.tvPreampKnobValue.text = formatDb(eqManager.getSavedPreamp())
        binding.knobPreamp.setAccentColor(ContextCompat.getColor(requireContext(), R.color.green_accent))

        configureKnob(
            binding.knobCompressor,
            EqualizerManager.COMPRESSOR_MIN,
            EqualizerManager.COMPRESSOR_MAX,
            1,
            0,
            eqManager.getSavedCompressor(),
            getString(R.string.eq_compressor_label)
        ) { value ->
            eqManager.setCompressor(value)
            binding.tvCompressorKnobValue.text = "$value%"
        }
        binding.tvCompressorKnobValue.text = "${eqManager.getSavedCompressor()}%"
        binding.knobCompressor.setAccentColor(ContextCompat.getColor(requireContext(), R.color.red_accent))

        configureKnob(
            binding.knobLoudness,
            0,
            100,
            1,
            0,
            eqManager.getSavedLoudness(),
            getString(R.string.eq_loudness)
        ) { value ->
            eqManager.setLoudness(value)
            binding.tvLoudnessValue.text = "$value%"
        }
        binding.tvLoudnessValue.text = "${eqManager.getSavedLoudness()}%"
        binding.knobLoudness.setAccentColor(ContextCompat.getColor(requireContext(), R.color.yellow_accent))

        applyAudioStyleToggle(binding.switchAutoHeadroom)
        binding.switchAutoHeadroom.isChecked = eqManager.isAutoHeadroomEnabled()
        binding.switchAutoHeadroom.setOnCheckedChangeListener { _, checked ->
            eqManager.setAutoHeadroom(checked)
        }

        applyAudioStyleToggle(binding.switchLimiter)
        binding.switchLimiter.isChecked = eqManager.isLimiterEnabled()
        binding.switchLimiter.setOnCheckedChangeListener { _, checked ->
            eqManager.setLimiterEnabled(checked)
        }
    }

    private fun setupAmbienceControls() {
        applyAudioStyleToggle(binding.switchReverb)
        binding.switchReverb.isChecked = eqManager.isReverbEnabled()
        binding.switchReverb.setOnCheckedChangeListener { _, checked ->
            eqManager.setReverbEnabled(checked)
            refreshReverbEnabledState(checked)
        }

        val roomNames = listOf(
            R.string.eq_room_studio,
            R.string.eq_room_small,
            R.string.eq_room_hall,
            R.string.eq_room_plate
        )
        binding.reverbPresetGroup.removeAllViews()
        roomNames.forEachIndexed { index, stringRes ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = getString(stringRes)
                tag = index
                isCheckable = true
                isChecked = index == eqManager.getSavedReverbPreset()
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        ContextCompat.getColor(requireContext(), R.color.purple_accent),
                        ContextCompat.getColor(requireContext(), R.color.surface_variant)
                    )
                )
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(
                            Color.BLACK,
                            ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
                        )
                    )
                )
                setOnClickListener { eqManager.setReverbPreset(index) }
            }
            binding.reverbPresetGroup.addView(chip)
        }

        configureKnob(
            binding.knobReverbMix,
            0,
            EqualizerManager.REVERB_MIX_MAX,
            1,
            0,
            eqManager.getSavedReverbMix(),
            getString(R.string.eq_reverb_mix)
        ) { value ->
            eqManager.setReverbMix(value)
            binding.tvReverbMixValue.text = "$value%"
        }
        binding.tvReverbMixValue.text = "${eqManager.getSavedReverbMix()}%"
        binding.knobReverbMix.setAccentColor(ContextCompat.getColor(requireContext(), R.color.purple_accent))
        refreshReverbEnabledState(eqManager.isReverbEnabled())
    }

    private fun setupReplayGainControls() {
        val prefs = AudioProSettings.prefs(requireContext())
        val selectedId = when (
            prefs.getInt(AudioProSettings.KEY_REPLAYGAIN, AudioProSettings.REPLAYGAIN_TRACK)
                .coerceIn(AudioProSettings.REPLAYGAIN_OFF, AudioProSettings.REPLAYGAIN_ALBUM)
        ) {
            AudioProSettings.REPLAYGAIN_OFF -> binding.btnReplayGainOff.id
            AudioProSettings.REPLAYGAIN_ALBUM -> binding.btnReplayGainAlbum.id
            else -> binding.btnReplayGainTrack.id
        }
        binding.replayGainGroup.check(selectedId)
        binding.replayGainGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                binding.btnReplayGainOff.id -> AudioProSettings.REPLAYGAIN_OFF
                binding.btnReplayGainAlbum.id -> AudioProSettings.REPLAYGAIN_ALBUM
                else -> AudioProSettings.REPLAYGAIN_TRACK
            }
            prefs.edit().putInt(AudioProSettings.KEY_REPLAYGAIN, mode).apply()
        }
    }

    private fun applyCapabilityVisibility() {
        val nativeEqAvailable = eqManager.isEqualizerAvailable()
        val eqVisibility = if (nativeEqAvailable) View.VISIBLE else View.GONE
        binding.eqBandsCard.visibility = eqVisibility
        binding.tvPresetTitle.visibility = eqVisibility
        binding.presetContainer.visibility = eqVisibility
        binding.bassContainer.visibility = eqVisibility
        binding.trebleContainer.visibility = eqVisibility
        binding.preampContainer.visibility = eqVisibility
        binding.compressorContainer.visibility = eqVisibility
        binding.switchAutoHeadroom.visibility = eqVisibility
        binding.tvAutoHeadroomSummary.visibility = eqVisibility
        binding.tvToneUnavailable.visibility = if (nativeEqAvailable) View.GONE else View.VISIBLE

        val loudnessAvailable = eqManager.isLoudnessAvailable()
        binding.loudnessContainer.visibility = if (loudnessAvailable) View.VISIBLE else View.GONE
    }

    private fun refreshReverbEnabledState(enabled: Boolean) {
        for (index in 0 until binding.reverbPresetGroup.childCount) {
            binding.reverbPresetGroup.getChildAt(index).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.38f
            }
        }
        setControlEnabled(binding.knobReverbMix, enabled)
    }

    private fun refreshMonoButton(enabled: Boolean) {
        binding.btnMono.text = if (enabled) {
            getString(R.string.eq_mono_active)
        } else {
            getString(R.string.eq_mono)
        }
        binding.btnMono.iconTint = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.green_accent else R.color.on_surface_variant
            )
        )
    }

    private fun configureKnob(
        knob: RotaryKnobView,
        minimum: Int,
        maximum: Int,
        step: Int,
        defaultValue: Int,
        initialValue: Int,
        description: String,
        onChanged: (Int) -> Unit
    ) {
        knob.minValue = minimum.toFloat()
        knob.maxValue = maximum.toFloat()
        knob.step = step.toFloat()
        knob.setDefaultValue(defaultValue.toFloat())
        knob.setValue(initialValue.toFloat())
        knob.contentDescription = description
        knob.onValueChanged = { value, fromUser ->
            if (fromUser) onChanged(value.roundToInt())
        }
    }

    private fun applyAudioStyleToggle(sw: MaterialSwitch) {
        val accent = ContextCompat.getColor(requireContext(), R.color.green_accent)
        val trackStates = arrayOf(
            intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled)
        )
        sw.trackTintList = ColorStateList(
            trackStates,
            intArrayOf(
                AudioDynamicColor.mix(0xFF101827.toInt(), accent, 0.36f),
                ContextCompat.getColor(requireContext(), R.color.surface_variant),
                ContextCompat.getColor(requireContext(), R.color.surface)
            )
        )
        sw.thumbTintList = ColorStateList(
            trackStates,
            intArrayOf(
                accent,
                ContextCompat.getColor(requireContext(), R.color.on_surface_variant),
                ContextCompat.getColor(requireContext(), R.color.text_hint)
            )
        )
    }

    private fun setControlEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    private fun refreshChips() {
        for (index in 0 until binding.presetContainer.childCount) {
            val chip = binding.presetContainer.getChildAt(index) as? Chip ?: continue
            chip.isChecked = chip.tag == selectedPreset
        }
    }

    private fun refreshBands() {
        for (band in 0 until eqManager.numBands) {
            if (band >= bandViews.size || band >= bandLabels.size) continue
            val level = eqManager.getBandLevel(band)
            bandViews[band].silent = true
            bandViews[band].currentLevel = level
            bandViews[band].silent = false
            bandLabels[band].text = compactDb(level)
        }
    }

    private fun compactDb(level: Int): String {
        val db = level / 100
        return "${if (db >= 0) "+" else ""}$db"
    }

    private fun formatDb(level: Int): String =
        String.format(Locale.getDefault(), "%+.1f dB", level / 100f)

    private fun formatBalance(value: Int): String = when {
        abs(value) <= 1 -> getString(R.string.eq_center)
        value < 0 -> getString(R.string.eq_balance_left, abs(value))
        else -> getString(R.string.eq_balance_right, value)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        themeAnimator?.cancel()
        themeAnimator = null
        dynamicColorPrefs?.unregisterOnSharedPreferenceChangeListener(dynamicColorListener)
        dynamicColorPrefs = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val DYNAMIC_AUDIO_PREFS = "blaze_audio_dynamic_colors"
        private const val KEY_DYNAMIC_ACCENT = "dynamic_accent"
    }
}
