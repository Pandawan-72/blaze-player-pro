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
            "Reprendre la lecture",
            listOf("Toujours", "Demander", "Jamais"),
            viewModel.getResumeMode(),
            "Reprendre la lecture"
        ) { viewModel.setResumeMode(it) }

        setupToggle(
            binding.settingAutoPlay.root,
            R.drawable.ic_skip_next,
            "Lecture automatique suivante",
            "Lire automatiquement le fichier suivant",
            viewModel.getAutoPlay()
        ) { viewModel.setAutoPlay(it) }

        setupChoice(
            binding.settingSpeed.root,
            R.drawable.ic_settings,
            "Vitesse de lecture",
            listOf("0.25x", "0.5x", "0.75x", "1x (normal)", "1.25x", "1.5x", "2x"),
            viewModel.getSpeedIndex(),
            "Vitesse de lecture"
        ) { viewModel.setSpeedIndex(it) }

        setupChoice(
            binding.settingSeekTime.root,
            R.drawable.ic_forward_10,
            "Durée de l'avance rapide",
            listOf("5 secondes", "10 secondes", "15 secondes", "30 secondes", "60 secondes"),
            viewModel.getSeekTimeIndex(),
            "Durée de l'avance rapide"
        ) { viewModel.setSeekTimeIndex(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            val orientIdx = viewModel.getOrientationIndexAsync()
            setupChoice(
                binding.settingOrientation.root,
                R.drawable.ic_settings,
                "Orientation par défaut",
                listOf("Automatique", "Portrait", "Paysage"),
                orientIdx,
                "Orientation par défaut"
            ) { viewModel.setOrientationIndex(it) }
        }

        setupToggle(
            binding.settingPip.root,
            R.drawable.ic_settings,
            "PiP automatique",
            "Passer en Picture-in-Picture lors du changement d'app",
            viewModel.getPip()
        ) { viewModel.setPip(it) }



        // AUDIO
        val audioLangs = listOf("Pas de préférence", "Français", "Anglais", "Espagnol", "Allemand", "Italien", "Japonais", "Portugais", "Néerlandais", "Russe", "Chinois")
        viewLifecycleOwner.lifecycleScope.launch {
            val idx = viewModel.getAudioLangIndexAsync()
            setupChoice(
                binding.settingAudioLang.root,
                R.drawable.ic_language,
                "Langue audio préférée",
                audioLangs,
                idx,
                "Langue audio préférée"
            ) { viewModel.setAudioLangIndex(it) }
        }

        setupToggle(
            binding.settingRememberVolume.root,
            R.drawable.ic_settings,
            "Mémoriser le volume",
            "Retenir le niveau de volume entre les lectures",
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
                "Mini player",
                "Afficher le mini player dans l'app",
                enabled
            ) { isEnabled ->
                miniVm?.setMiniPlayerEnabled(isEnabled)
            }
        }
        setupToggle(
            binding.settingShowHidden.root,
            R.drawable.ic_settings,
            "Afficher les fichiers cachés",
            "Fichiers commençant par un point (.)",
            viewModel.getShowHidden()
        ) { viewModel.setShowHidden(it) }



        // DONNÉES
        setupAction(
            binding.settingClearHistory.root,
            R.drawable.ic_history,
            "Effacer l'historique",
            "Supprimer tous les fichiers récemment lus"
        ) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Effacer l'historique")
                .setMessage("Supprimer tous les fichiers récemment lus ?")
                .setPositiveButton("Effacer") { _, _ ->
                    viewModel.clearAllData()
                    Toast.makeText(requireContext(), "Historique effacé", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // À PROPOS
        val appVersion = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) { "?" }
        setupAction(
            binding.settingAbout.root,
            R.drawable.ic_settings,
            "À propos",
            "Ce qu'il faut savoir sur Blaze Player"
        ) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Blaze Player")
                .setMessage("Blaze Player\n\nVersion $appVersion\n\nDéveloppé par Retro-Spare\n\nVotre lecteur multimédia pour Android")
                .setPositiveButton("OK", null)
                .show()
        }
        }
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
            android.widget.Toast.makeText(requireContext(), "Restauration des achats... (bientôt disponible)", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
