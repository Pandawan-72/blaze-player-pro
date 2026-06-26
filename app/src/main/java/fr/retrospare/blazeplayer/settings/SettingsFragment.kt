package fr.retrospare.blazeplayer.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.databinding.FragmentSettingsBinding

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnLogout.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Se déconnecter")
                .setMessage("Voulez-vous vraiment vous déconnecter ?")
                .setPositiveButton("Déconnecter") { _, _ ->
                    com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
                    findNavController().navigate(R.id.loginFragment)
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        binding.btnBecomePro.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_paywall)
        }

        binding.btnSectionPlayback.setOnClickListener {
            showPlaybackSettings()
        }

        binding.btnSectionDecoding.setOnClickListener {
            showDecodingSettings()
        }

        binding.btnSectionSubtitles.setOnClickListener {
            showSubtitlesSettings()
        }

        binding.btnSectionNetwork.setOnClickListener {
            showNetworkSettings()
        }

        binding.btnSectionInterface.setOnClickListener {
            showInterfaceSettings()
        }

        binding.btnSectionAbout.setOnClickListener {
            showAboutSettings()
        }
    }

    private fun showPlaybackSettings() {
        val entries = arrayOf("Ajuster", "Remplir", "Étirer", "Original")
        val speeds = arrayOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×")

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Lecture")
            .setItems(arrayOf(
                "Ratio par défaut",
                "Vitesse de lecture",
                "Reprendre la lecture",
                "Durée saut court",
                "Durée saut long",
                "Rotation automatique"
            )) { _, which ->
                when (which) {
                    0 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Ratio par défaut")
                        .setSingleChoiceItems(entries, viewModel.getDefaultRatio()) { d, i ->
                            viewModel.setDefaultRatio(i)
                            d.dismiss()
                        }.show()
                    1 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Vitesse de lecture")
                        .setSingleChoiceItems(speeds, viewModel.getDefaultSpeed()) { d, i ->
                            viewModel.setDefaultSpeed(i)
                            d.dismiss()
                        }.show()
                    2 -> viewModel.toggleResumePlayback()
                    3 -> showDurationPicker("Saut court (secondes)", viewModel.getShortSkip()) {
                        viewModel.setShortSkip(it)
                    }
                    4 -> showDurationPicker("Saut long (secondes)", viewModel.getLongSkip()) {
                        viewModel.setLongSkip(it)
                    }
                    5 -> viewModel.toggleAutoRotate()
                }
            }
            .show()
    }

    private fun showDecodingSettings() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Décodage")
            .setItems(arrayOf(
                "Décodage matériel",
                "HDR (Pro)",
                "Mode décodage"
            )) { _, which ->
                when (which) {
                    0 -> viewModel.toggleHardwareDecode()
                    1 -> viewModel.toggleHdr()
                    2 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Mode décodage")
                        .setSingleChoiceItems(
                            arrayOf("Auto", "Logiciel", "Matériel forcé"),
                            viewModel.getDecodeMode()
                        ) { d, i ->
                            viewModel.setDecodeMode(i)
                            d.dismiss()
                        }.show()
                }
            }
            .show()
    }

    private fun showSubtitlesSettings() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Sous-titres")
            .setItems(arrayOf(
                "Taille du texte",
                "Fond sous-titres",
                "Décalage par défaut",
                "Support ASS/SSA (Pro)"
            )) { _, which ->
                when (which) {
                    0 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Taille du texte")
                        .setSingleChoiceItems(
                            arrayOf("Petite", "Moyenne", "Grande", "Très grande"),
                            viewModel.getSubtitleSize()
                        ) { d, i ->
                            viewModel.setSubtitleSize(i)
                            d.dismiss()
                        }.show()
                    1 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Fond sous-titres")
                        .setSingleChoiceItems(
                            arrayOf("Ombre", "Fond semi-transparent", "Aucun"),
                            viewModel.getSubtitleBackground()
                        ) { d, i ->
                            viewModel.setSubtitleBackground(i)
                            d.dismiss()
                        }.show()
                    2 -> showDurationPicker("Décalage (ms)", viewModel.getSubtitleDelay()) {
                        viewModel.setSubtitleDelay(it)
                    }
                    3 -> viewModel.toggleAssSupport()
                }
            }
            .show()
    }

    private fun showNetworkSettings() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Réseau")
            .setItems(arrayOf(
                "Wi-Fi uniquement",
                "Taille du cache réseau",
                "Chromecast (Pro)"
            )) { _, which ->
                when (which) {
                    0 -> viewModel.toggleWifiOnly()
                    1 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Cache réseau")
                        .setSingleChoiceItems(
                            arrayOf("8 Mo", "16 Mo", "32 Mo", "64 Mo", "128 Mo"),
                            viewModel.getCacheSize()
                        ) { d, i ->
                            viewModel.setCacheSize(i)
                            d.dismiss()
                        }.show()
                    2 -> viewModel.toggleChromecast()
                }
            }
            .show()
    }

    private fun showInterfaceSettings() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Interface")
            .setItems(arrayOf(
                "Thème",
                "Langue de l'interface",
                "Vue navigateur par défaut",
                "Tri par défaut"
            )) { _, which ->
                when (which) {
                    0 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Thème")
                        .setSingleChoiceItems(
                            arrayOf("Sombre", "Clair", "Système"),
                            viewModel.getTheme()
                        ) { d, i ->
                            viewModel.setTheme(i)
                            d.dismiss()
                        }.show()
                    1 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Langue")
                        .setSingleChoiceItems(
                            arrayOf("Français", "English", "Español", "Deutsch",
                                "Italiano", "Português", "日本語", "中文"),
                            viewModel.getLanguage()
                        ) { d, i ->
                            viewModel.setLanguage(i)
                            d.dismiss()
                        }.show()
                    2 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Vue navigateur")
                        .setSingleChoiceItems(
                            arrayOf("Liste", "Grille"),
                            viewModel.getBrowserView()
                        ) { d, i ->
                            viewModel.setBrowserView(i)
                            d.dismiss()
                        }.show()
                    3 -> android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Tri par défaut")
                        .setSingleChoiceItems(
                            arrayOf("Nom A–Z", "Nom Z–A", "Date récente", "Taille"),
                            viewModel.getSortMode()
                        ) { d, i ->
                            viewModel.setSortMode(i)
                            d.dismiss()
                        }.show()
                }
            }
            .show()
    }

    private fun showAboutSettings() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("À propos")
            .setItems(arrayOf(
                "Version : 1.0.0",
                "Noter l'application",
                "Contacter le support",
                "Mentions légales",
                "Effacer les données locales"
            )) { _, which ->
                when (which) {
                    1 -> openPlayStore()
                    2 -> sendSupportEmail()
                    3 -> showLegalNotice()
                    4 -> confirmClearData()
                }
            }
            .show()
    }

    private fun showDurationPicker(title: String, current: Int, onResult: (Int) -> Unit) {
        val values = (5..120 step 5).map { it.toString() + "s" }.toTypedArray()
        val currentIdx = ((current / 5) - 1).coerceIn(0, values.lastIndex)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(values, currentIdx) { d, i ->
                onResult((i + 1) * 5)
                d.dismiss()
            }
            .show()
    }

    private fun openPlayStore() {
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=${requireContext().packageName}")
                )
            )
        } catch (e: Exception) {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=${requireContext().packageName}")
                )
            )
        }
    }

    private fun sendSupportEmail() {
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:support@retro-spare.fr")
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Blaze Player - Support")
        }
        startActivity(intent)
    }

    private fun showLegalNotice() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Mentions légales")
            .setMessage("Blaze Player\nVersion 1.0.0\n\n© 2024 Retro-Spare\nTous droits réservés.\n\nDéveloppé par Retro-Spare, Le Mans, France.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmClearData() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Effacer les données")
            .setMessage("Cela supprimera l'historique de lecture, le cache et les identifiants réseau. Cette action est irréversible.")
            .setPositiveButton("Effacer") { _, _ ->
                viewModel.clearAllData()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
