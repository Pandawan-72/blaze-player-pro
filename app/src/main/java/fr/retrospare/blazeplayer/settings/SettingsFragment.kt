package fr.retrospare.blazeplayer.settings

import android.os.Bundle
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

        setupChoice(
            binding.settingOrientation.root,
            R.drawable.ic_settings,
            "Orientation par défaut",
            listOf("Automatique", "Portrait", "Paysage"),
            viewModel.getOrientationIndex(),
            "Orientation par défaut"
        ) { viewModel.setOrientationIndex(it) }

        setupToggle(
            binding.settingPip.root,
            R.drawable.ic_settings,
            "PiP automatique",
            "Passer en Picture-in-Picture lors du changement d'app",
            viewModel.getPip()
        ) { viewModel.setPip(it) }

        setupToggle(
            binding.settingGestures.root,
            R.drawable.ic_settings,
            "Contrôles gestuels",
            "Luminosité, volume et avance par glissement",
            viewModel.getGestures()
        ) { viewModel.setGestures(it) }

        // AUDIO
        setupChoice(
            binding.settingAudioLang.root,
            R.drawable.ic_language,
            "Langue audio préférée",
            listOf("Pas de préférence", "Français", "Anglais", "Espagnol", "Allemand", "Italien", "Japonais"),
            viewModel.getAudioLangIndex(),
            "Langue audio préférée"
        ) { viewModel.setAudioLangIndex(it) }

        setupToggle(
            binding.settingRememberVolume.root,
            R.drawable.ic_settings,
            "Mémoriser le volume",
            "Retenir le niveau de volume entre les lectures",
            viewModel.getRememberVolume()
        ) { viewModel.setRememberVolume(it) }

        // SOUS-TITRES
        setupToggle(
            binding.settingSubtitleDefault.root,
            R.drawable.ic_subtitles,
            "Afficher les sous-titres",
            "Activer les sous-titres par défaut",
            viewModel.getSubtitlesDefault()
        ) { viewModel.setSubtitlesDefault(it) }

        setupChoice(
            binding.settingSubtitleLang.root,
            R.drawable.ic_language,
            "Langue des sous-titres",
            listOf("Pas de préférence", "Français", "Anglais", "Espagnol", "Allemand", "Italien", "Japonais"),
            viewModel.getSubtitleLangIndex(),
            "Langue des sous-titres préférée"
        ) { viewModel.setSubtitleLangIndex(it) }

        // RÉSEAU
        setupToggle(
            binding.settingChromecast.root,
            R.drawable.ic_cast,
            "Chromecast",
            "Activer le support Chromecast",
            viewModel.getChromecast()
        ) { viewModel.toggleChromecast() }

        // INTERFACE
        setupToggle(
            binding.settingShowHidden.root,
            R.drawable.ic_settings,
            "Afficher les fichiers cachés",
            "Fichiers commençant par un point (.)",
            viewModel.getShowHidden()
        ) { viewModel.setShowHidden(it) }

        setupToggle(
            binding.settingShowAudio.root,
            R.drawable.ic_audio,
            "Afficher les fichiers audio",
            "Inclure la musique dans le navigateur",
            viewModel.getShowAudio()
        ) { viewModel.setShowAudio(it) }

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
        setupAction(
            binding.settingAbout.root,
            R.drawable.ic_settings,
            "À propos",
            "Blaze Player v0.0.2 — Retro-Spare"
        ) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Blaze Player")
                .setMessage("Version 0.0.2\n\nDéveloppé par Retro-Spare\n\nCe lecteur utilise la bibliothèque libVLC sous licence LGPL v2.1")
                .setPositiveButton("OK", null)
                .show()
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
        view.setOnClickListener {
            SettingsDialog.showChoice(requireContext(), dialogTitle, choices, selectedIndex) { idx ->
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
        binding.btnLogout.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Se déconnecter")
                .setMessage("Voulez-vous vraiment vous déconnecter ?")
                .setPositiveButton("Déconnecter") { _, _ ->
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                    requireActivity().finish()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
