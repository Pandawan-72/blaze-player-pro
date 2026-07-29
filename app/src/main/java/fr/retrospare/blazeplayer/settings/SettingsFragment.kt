package fr.retrospare.blazeplayer.settings


import fr.retrospare.blazeplayer.ui.showPremium
import android.os.Bundle
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.BuildConfig
import fr.retrospare.blazeplayer.databinding.FragmentSettingsBinding

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fr.retrospare.blazeplayer.player.AudioPremiumUi.applyDynamicHero(
            binding.settingsHero,
            ContextCompat.getColor(requireContext(), R.color.green_accent)
        )
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnBecomePro.setOnClickListener {
            val navController = findNavController()
            if (navController.currentDestination?.id == R.id.settingsFragment) {
                navController.navigate(R.id.action_settings_to_paywall)
            }
        }
        setupSettings()
        setupLogout()
        setupRateApp()
    }

    private fun setupSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
        // Attend que la vraie valeur sauvegardée soit disponible avant d'afficher quoi que ce
        // soit : sans ça, les lectures synchrones ci-dessous (getAutoPlay, getPip...) pouvaient
        // s'exécuter avant la première lecture du DataStore et retomber sur leur valeur par
        // défaut, donnant l'impression que le réglage se "désactivait" à chaque réouverture.
        viewModel.awaitReady()

        // LECTURE
        setupChoice(
            binding.settingResume.root,
            R.drawable.ic_play,
            getString(R.string.settings_resume_playback),
            listOf(getString(R.string.resume_always), getString(R.string.resume_ask), getString(R.string.resume_never)),
            viewModel.getResumeMode(),
            getString(R.string.settings_resume_playback)
        ) { viewModel.setResumeMode(it) }

        setupToggle(
            binding.settingAutoPlay.root,
            R.drawable.ic_skip_next,
            getString(R.string.settings_autoplay_next),
            getString(R.string.settings_autoplay_next_desc),
            viewModel.getAutoPlay()
        ) { viewModel.setAutoPlay(it) }

        setupChoice(
            binding.settingSpeed.root,
            R.drawable.ic_settings,
            getString(R.string.settings_playback_speed),
            listOf("0.25x", "0.5x", "0.75x", getString(R.string.speed_normal), "1.25x", "1.5x", "2x"),
            viewModel.getSpeedIndex(),
            getString(R.string.settings_playback_speed)
        ) { viewModel.setSpeedIndex(it) }

        setupChoice(
            binding.settingSeekTime.root,
            R.drawable.ic_forward_10,
            getString(R.string.settings_seek_duration),
            listOf(
                getString(R.string.seconds_5), getString(R.string.seconds_10), getString(R.string.seconds_15),
                getString(R.string.seconds_30), getString(R.string.seconds_60)
            ),
            viewModel.getSeekTimeIndex(),
            getString(R.string.settings_seek_duration)
        ) { viewModel.setSeekTimeIndex(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            val orientIdx = viewModel.getOrientationIndexAsync()
            setupChoice(
                binding.settingOrientation.root,
                R.drawable.ic_settings,
                getString(R.string.settings_default_orientation),
                listOf(getString(R.string.orientation_auto), getString(R.string.orientation_portrait), getString(R.string.orientation_landscape)),
                orientIdx,
                getString(R.string.settings_default_orientation)
            ) { viewModel.setOrientationIndex(it) }
        }

        setupToggle(
            binding.settingPip.root,
            R.drawable.ic_settings,
            getString(R.string.settings_auto_pip),
            getString(R.string.settings_auto_pip_desc),
            viewModel.getPip()
        ) { viewModel.setPip(it) }



        // AUDIO
        val audioLangs = listOf(
            getString(R.string.lang_no_preference), getString(R.string.lang_french), getString(R.string.lang_english),
            getString(R.string.lang_spanish), getString(R.string.lang_german), getString(R.string.lang_italian),
            getString(R.string.lang_japanese), getString(R.string.lang_portuguese), getString(R.string.lang_dutch),
            getString(R.string.lang_russian), getString(R.string.lang_chinese)
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val idx = viewModel.getAudioLangIndexAsync()
            setupChoice(
                binding.settingAudioLang.root,
                R.drawable.ic_language,
                getString(R.string.settings_preferred_audio_lang),
                audioLangs,
                idx,
                getString(R.string.settings_preferred_audio_lang)
            ) { viewModel.setAudioLangIndex(it) }
        }

        // RÉSEAU


        // INTERFACE
        setupToggle(
            binding.settingShowHidden.root,
            R.drawable.ic_settings,
            getString(R.string.settings_show_hidden_files),
            getString(R.string.settings_show_hidden_files_desc),
            viewModel.getShowHidden()
        ) { viewModel.setShowHidden(it) }

        setupToggle(
            binding.settingHaptic.root,
            R.drawable.ic_vibration,
            getString(R.string.settings_haptic_feedback),
            getString(R.string.settings_haptic_feedback_desc),
            viewModel.getHapticFeedbackEnabled()
        ) { viewModel.setHapticFeedbackEnabled(it) }



        // DONNÉES
        setupAction(
            binding.settingClearHistory.root,
            R.drawable.ic_history,
            getString(R.string.settings_clear_history),
            getString(R.string.settings_clear_history_desc)
        ) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.settings_clear_history))
                .setMessage(getString(R.string.dialog_clear_history_message))
                .setPositiveButton(getString(R.string.action_clear)) { _, _ ->
                    viewModel.clearAllData()
                    Toast.makeText(requireContext(), getString(R.string.toast_history_cleared), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .showPremium()
        }

        // À PROPOS
        val appVersion = BuildConfig.VERSION_NAME
        setupAction(
            binding.settingAbout.root,
            R.drawable.ic_settings,
            getString(R.string.settings_about),
            getString(R.string.settings_about_desc)
        ) {
            val aboutMessage = SpannableStringBuilder()
                .append(getString(R.string.about_dialog_message, appVersion))
                .append("\n\n")
                .append(getString(R.string.about_developed_in_france))
                .append(" ")

            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_heart_filled)?.let { heart ->
                val tintedHeart = DrawableCompat.wrap(heart.mutate())
                DrawableCompat.setTint(tintedHeart, ContextCompat.getColor(requireContext(), R.color.green_accent))
                val size = (20 * resources.displayMetrics.density).toInt()
                tintedHeart.setBounds(0, 0, size, size)

                val iconStart = aboutMessage.length
                aboutMessage.append("\uFFFC")
                aboutMessage.setSpan(
                    ImageSpan(tintedHeart, ImageSpan.ALIGN_BASELINE),
                    iconStart,
                    iconStart + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Blaze Player")
                .setMessage(aboutMessage)
                .setPositiveButton(getString(R.string.action_ok), null)
                .showPremium()
        }

        // LANGUE
        setupAction(
            binding.settingLanguage.root,
            R.drawable.ic_language,
            getString(R.string.settings_language),
            currentLanguageLabel()
        ) {
            showLanguagePicker()
        }

        // CONTACT
        setupAction(
            binding.settingSuggestions.root,
            R.drawable.ic_mail,
            getString(R.string.settings_suggestions),
            getString(R.string.settings_suggestions_desc)
        ) {
            sendContactEmail("contact@retro-spare.fr")
        }
        setupAction(
            binding.settingReportBug.root,
            R.drawable.ic_bug_report,
            getString(R.string.settings_report_bug),
            getString(R.string.settings_report_bug_desc)
        ) {
            sendContactEmail("dev@retro-spare.fr")
        }
        }
    }

    private fun sendContactEmail(address: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:$address")
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_error), getString(R.string.toast_no_email_app))
        }
    }

    /** Langues supportées : tag BCP-47 -> nom affiché dans SA PROPRE langue (convention standard
     *  des sélecteurs de langue — chacun reconnaît son nom, peu importe la langue actuelle de
     *  l'app). null = suit la langue du système. */
    private val supportedLanguages: List<Pair<String?, String>>
        get() = listOf(
            null to getString(R.string.language_system_auto),
            "fr" to "Français",
            "en" to "English",
            "es" to "Español",
            "it" to "Italiano",
            "pt" to "Português",
            "de" to "Deutsch",
            "nl" to "Nederlands",
            "ru" to "Русский",
            "hi" to "हिन्दी",
            "uk" to "Українська",
            "ar" to "العربية",
            "id" to "Bahasa Indonesia"
        )

    /** Java/Android peut encore renvoyer le code ISO historique "in" pour l'indonésien,
     *  alors que le tag BCP-47 moderne enregistré par l'application est "id". */
    private fun normalizeLanguageCode(code: String?): String? = when (code?.lowercase()) {
        "in" -> "id"
        else -> code?.lowercase()
    }

    private fun currentLanguageLabel(): String {
        val currentTags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (currentTags.isEmpty) return getString(R.string.language_system_auto)
        val currentTag = normalizeLanguageCode(currentTags[0]?.language)
        return supportedLanguages.firstOrNull { normalizeLanguageCode(it.first) == currentTag }?.second
            ?: getString(R.string.language_system_auto)
    }

    private fun showLanguagePicker() {
        val currentTags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentTags.isEmpty) null else normalizeLanguageCode(currentTags[0]?.language)
        val languages = supportedLanguages
        val selectedIndex = languages.indexOfFirst { normalizeLanguageCode(it.first) == currentTag }
            .let { if (it < 0) 0 else it }
        val labels = languages.map { it.second }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_choose_language))
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val tag = languages[which].first
                val locales = if (tag == null) {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                }
                // Applique la nouvelle langue à toute l'app — androidx s'occupe de la persistance
                // (survit au redémarrage de l'app) et recrée automatiquement les écrans affichés.
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .showPremium()
    }

    private fun setupToggle(view: View, icon: Int, title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(icon)
        view.findViewById<TextView>(R.id.tvTitle).text = title
        view.findViewById<TextView>(R.id.tvSubtitle).apply { text = subtitle; visibility = View.VISIBLE }
        val sw = view.findViewById<MaterialSwitch>(R.id.switchToggle)

        // Les lignes de réglage sont incluses plusieurs fois et contiennent toutes un switch avec
        // le même id (`switchToggle`). Lors d'un changement de langue, AppCompat recrée le fragment
        // et Android restaure ensuite l'état des vues par id : avec ces ids dupliqués, un ancien
        // état visuel pouvait écraser la valeur lue depuis DataStore. Résultat visible :
        // "Activer le spectre visuel" revenait sur OFF après changement de langue alors que la
        // préférence ne devait pas changer. Les switches des réglages sont déjà persistés dans
        // DataStore, donc on désactive leur sauvegarde/restauration automatique de vue.
        sw.isSaveEnabled = false
        sw.isSaveFromParentEnabled = false

        sw.visibility = View.VISIBLE
        applyAudioStyleToggle(sw)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        view.setOnClickListener { sw.isChecked = !sw.isChecked }
    }

    private fun applyAudioStyleToggle(sw: MaterialSwitch) {
        val accent = ContextCompat.getColor(requireContext(), R.color.green_accent)
        val checkedTrack = ColorStateList.valueOf(fr.retrospare.blazeplayer.player.AudioDynamicColor.mix(0xFF101827.toInt(), accent, 0.36f))
        val uncheckedTrack = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface_variant))
        val disabledTrack = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.surface))
        val trackStates = arrayOf(
            intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled)
        )
        sw.trackTintList = ColorStateList(
            trackStates,
            intArrayOf(checkedTrack.defaultColor, uncheckedTrack.defaultColor, disabledTrack.defaultColor)
        )
        sw.thumbTintList = ColorStateList(
            trackStates,
            intArrayOf(accent, ContextCompat.getColor(requireContext(), R.color.on_surface_variant), ContextCompat.getColor(requireContext(), R.color.text_hint))
        )
    }


    private fun setupChoice(view: View, icon: Int, title: String, choices: List<String>, selectedIndex: Int, dialogTitle: String, onSelected: (Int) -> Unit) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(icon)
        view.findViewById<TextView>(R.id.tvTitle).text = title
        val tvSub = view.findViewById<TextView>(R.id.tvSubtitle)
        tvSub.text = choices.getOrElse(selectedIndex) { choices[0] }
        tvSub.visibility = View.VISIBLE
        view.findViewById<ImageView>(R.id.ivChevron).visibility = View.VISIBLE
        var currentIndex = selectedIndex
        view.setOnClickListener {
            SettingsDialog.showChoice(requireContext(), dialogTitle, choices, currentIndex) { idx ->
                currentIndex = idx
                tvSub.text = choices[idx]
                onSelected(idx)
            }
        }
    }

    private fun setupAction(view: View, icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(icon)
        view.findViewById<TextView>(R.id.tvTitle).text = title
        view.findViewById<TextView>(R.id.tvSubtitle).apply { text = subtitle; visibility = View.VISIBLE }
        view.findViewById<ImageView>(R.id.ivChevron).visibility = View.VISIBLE
        view.setOnClickListener { onClick() }
    }

    private fun setupLogout() {
        binding.btnRestorePurchases.setOnClickListener {
            fr.retrospare.blazeplayer.ui.InfoDialog.show(requireContext(), getString(R.string.info_dialog_title_info), getString(R.string.toast_restore_purchases_soon))
        }
    }

    private fun setupRateApp() {
        binding.btnRateApp.setOnClickListener {
            openPlayStoreListing()
        }
    }

    private fun openPlayStoreListing() {
        val packageName = requireContext().packageName
        val marketIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=$packageName")
        ).apply {
            setPackage("com.android.vending")
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                    android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        }

        try {
            startActivity(marketIntent)
            return
        } catch (_: Exception) {
            // Si le Play Store n'est pas installé/disponible, on retombe sur la page web.
        }

        val webIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        )
        try {
            startActivity(webIntent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.toast_play_store_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
