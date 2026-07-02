package fr.retrospare.blazeplayer.settings


import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
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
import com.google.android.material.switchmaterial.SwitchMaterial
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
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
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnBecomePro.setOnClickListener { }
        setupSettings()
        setupLogout()
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

        setupToggle(
            binding.settingRememberVolume.root,
            R.drawable.ic_settings,
            getString(R.string.settings_remember_volume),
            getString(R.string.settings_remember_volume_desc),
            viewModel.getRememberVolume()
        ) { viewModel.setRememberVolume(it) }

        // RÉSEAU


        // INTERFACE
        viewLifecycleOwner.lifecycleScope.launch {
            val miniVm = (requireActivity() as? fr.retrospare.blazeplayer.MainActivity)?.getMiniPlayerViewModel()
            val enabled = miniVm?.getMiniPlayerEnabled()?.first() ?: false
            setupToggle(
                binding.settingMiniPlayer.root,
                fr.retrospare.blazeplayer.R.drawable.ic_layout_list,
                getString(R.string.settings_mini_player),
                getString(R.string.settings_mini_player_desc),
                enabled
            ) { isEnabled ->
                miniVm?.setMiniPlayerEnabled(isEnabled)
            }
        }
        setupToggle(
            binding.settingShowHidden.root,
            R.drawable.ic_settings,
            getString(R.string.settings_show_hidden_files),
            getString(R.string.settings_show_hidden_files_desc),
            viewModel.getShowHidden()
        ) { viewModel.setShowHidden(it) }



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
                .show()
        }

        // À PROPOS
        val appVersion = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) { "?" }
        setupAction(
            binding.settingAbout.root,
            R.drawable.ic_settings,
            getString(R.string.settings_about),
            getString(R.string.settings_about_desc)
        ) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Blaze Player")
                .setMessage(getString(R.string.about_dialog_message, appVersion))
                .setPositiveButton(getString(R.string.action_ok), null)
                .show()
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
            "ru" to "Русский"
        )

    private fun currentLanguageLabel(): String {
        val currentTags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (currentTags.isEmpty) return getString(R.string.language_system_auto)
        val currentTag = currentTags[0]?.language
        return supportedLanguages.firstOrNull { it.first == currentTag }?.second ?: getString(R.string.language_system_auto)
    }

    private fun showLanguagePicker() {
        val currentTags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentTags.isEmpty) null else currentTags[0]?.language
        val languages = supportedLanguages
        val selectedIndex = languages.indexOfFirst { it.first == currentTag }.let { if (it < 0) 0 else it }
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
            .show()
    }

    private fun setupToggle(view: View, icon: Int, title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(icon)
        view.findViewById<TextView>(R.id.tvTitle).text = title
        view.findViewById<TextView>(R.id.tvSubtitle).apply { text = subtitle; visibility = View.VISIBLE }
        val sw = view.findViewById<SwitchMaterial>(R.id.switchToggle)
        sw.visibility = View.VISIBLE
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        view.setOnClickListener { sw.isChecked = !sw.isChecked }
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
            android.widget.Toast.makeText(requireContext(), getString(R.string.toast_restore_purchases_soon), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
