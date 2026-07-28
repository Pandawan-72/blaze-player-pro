package fr.retrospare.blazeplayer.player

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class AudioProSettingsActivity : AppCompatActivity() {
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository


    @Inject lateinit var dataStore: DataStore<Preferences>

    private lateinit var prefs: SharedPreferences
    private var refreshAfterWatchedBrowser = false
    private val accentColor by lazy { resolveAccentColor() }
    private val heroAccentColor by lazy { ContextCompat.getColor(this, R.color.green_accent) }
    private val textMain by lazy { ContextCompat.getColor(this, R.color.on_background) }
    private val textMuted by lazy { ContextCompat.getColor(this, R.color.on_surface_variant) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!fr.retrospare.blazeplayer.paywall.AccessGateUi.enforceNow(
                this,
                userRepository,
                fr.retrospare.blazeplayer.paywall.AccessLevel.PRO_PLUS
            )) return
        fr.retrospare.blazeplayer.paywall.AccessGateUi.monitor(
            this,
            userRepository,
            fr.retrospare.blazeplayer.paywall.AccessLevel.PRO_PLUS
        )
        setContentView(R.layout.activity_blaze_audio_settings)
        applySystemBarColors(ContextCompat.getColor(this, R.color.background))
        AudioProSettings.migrateOutputPreferences(this)
        prefs = AudioProSettings.prefs(this)
        AudioPremiumUi.applyDynamicHero(findViewById<View>(R.id.audioSettingsHero), heroAccentColor)
        findViewById<TextView>(R.id.badgeSettingsPro)?.let {
            it.setTextColor(heroAccentColor)
            it.setBackgroundResource(R.drawable.bg_pro_badge)
            it.setTypeface(it.typeface, android.graphics.Typeface.BOLD)
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        val container = findViewById<LinearLayout>(R.id.settingsContainer)
        container.addView(section(R.string.audio_section_playback).apply {
            addView(switchRow(AudioProSettings.KEY_GAPLESS, R.drawable.ic_repeat, R.string.audio_gapless_title, R.string.audio_gapless_subtitle, true))
            addView(separator())
            addView(switchRow(AudioProSettings.KEY_CROSSFADE, R.drawable.ic_equalizer, R.string.audio_crossfade_title, R.string.audio_crossfade_subtitle, true))
            addView(separator())
            addView(sliderRow(AudioProSettings.KEY_CROSSFADE_DURATION, R.drawable.ic_timer, R.string.audio_crossfade_duration, null, 0, 12, 3, " s"))
        })
        container.addView(section(R.string.audio_section_quality).apply {
            addView(switchRow(AudioProSettings.KEY_HI_RES, R.drawable.ic_equalizer, R.string.audio_high_quality_output, R.string.audio_high_quality_subtitle, false))
            addView(separator())
            addView(outputPrecisionRow())
            addView(separator())
            addView(
                infoRow(
                    R.drawable.ic_volume,
                    R.string.audio_local_output_title,
                    R.string.audio_local_output_subtitle,
                    ""
                ) { LocalAudioOutputDialog.show(this@AudioProSettingsActivity) }
            )
        })
        container.addView(section(R.string.audio_section_library).apply {
            addView(switchRow(AudioProSettings.KEY_AUTO_SCAN, R.drawable.ic_refresh, R.string.audio_auto_scan, R.string.audio_auto_scan_subtitle, true))
            addView(separator())
            addView(infoRow(R.drawable.ic_folder, R.string.audio_watched_folders, R.string.audio_watched_folders_subtitle, watchedFoldersValue()) { openWatchedFoldersBrowser() })
            addView(separator())
            addView(switchRow(AudioProSettings.KEY_TRACK_ORDER, R.drawable.ic_music_note_large, R.string.audio_sort_album_track, R.string.audio_sort_album_track_subtitle, true))
            addView(separator())
            addView(switchRow(AudioProSettings.KEY_IGNORE_SHORT, R.drawable.ic_delete_sweep, R.string.audio_ignore_short_files, R.string.audio_ignore_short_files_subtitle, false))
        })
        container.addView(section(R.string.settings_section_blaze_audio).apply {
            addView(dataStoreSwitchRow(SettingsViewModel.KEY_MINI_PLAYER, R.drawable.ic_layout_list, R.string.settings_mini_player, R.string.settings_mini_player_desc, false))
            addView(separator())
            addView(dataStoreSwitchRow(SettingsViewModel.KEY_AUDIO_SPECTRUM, R.drawable.ic_equalizer, R.string.settings_audio_spectrum, R.string.settings_audio_spectrum_desc, true))
        })
        container.addView(section(R.string.audio_section_interface).apply {
            addView(switchRow(AudioProSettings.KEY_DYNAMIC_THEME, R.drawable.ic_flame, R.string.audio_dynamic_theme, R.string.audio_dynamic_theme_subtitle, true))
        })
        container.addView(section(R.string.audio_section_lyrics_metadata).apply {
            addView(switchRow(AudioProSettings.KEY_SYNCED_LYRICS, R.drawable.ic_lyrics, R.string.audio_synced_lyrics, R.string.audio_synced_lyrics_subtitle, true))
            addView(separator())
            addView(infoRow(R.drawable.ic_edit, R.string.audio_tag_editor, R.string.audio_tag_editor_subtitle, "") { showTagEditorDialog() })
        })
    }

    override fun onResume() {
        super.onResume()
        if (refreshAfterWatchedBrowser) {
            refreshAfterWatchedBrowser = false
            recreate()
        }
    }

    private fun section(titleRes: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@AudioProSettingsActivity, R.drawable.bg_audio_pro_card)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        addView(TextView(this@AudioProSettingsActivity).apply {
            text = getString(titleRes).uppercase()
            setTextColor(accentColor)
            textSize = 14f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(16), dp(14), dp(16), dp(10))
        })
        addView(separator())
    }

    private fun switchRow(key: String, iconRes: Int, titleRes: Int, subRes: Int?, defaultValue: Boolean): View {
        val root = baseRow(iconRes)
        val texts = textColumn(getString(titleRes), subRes?.let { getString(it) })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        val sw = MaterialSwitch(this).apply {
            applyAudioStyleToggle(this)
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, checked ->
                when (key) {
                    AudioProSettings.KEY_HI_RES -> {
                        AudioProSettings.setHighQualityEnabled(this@AudioProSettingsActivity, checked)
                    }
                    AudioProSettings.KEY_AUTO_SCAN,
                    AudioProSettings.KEY_TRACK_ORDER,
                    AudioProSettings.KEY_IGNORE_SHORT -> {
                        AudioProSettings.setLibraryBoolean(this@AudioProSettingsActivity, key, checked)
                    }
                    else -> {
                        val edit = prefs.edit().putBoolean(key, checked)
                        if (key == AudioProSettings.KEY_SYNCED_LYRICS) {
                            edit.putBoolean(AudioProSettings.KEY_LYRICS_PLAYER, checked)
                        }
                        edit.apply()
                    }
                }
                if (key == AudioProSettings.KEY_DYNAMIC_THEME) recreate()
            }
        }
        root.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun dataStoreSwitchRow(key: Preferences.Key<Boolean>, iconRes: Int, titleRes: Int, subRes: Int?, defaultValue: Boolean): View {
        val root = baseRow(iconRes)
        val texts = textColumn(getString(titleRes), subRes?.let { getString(it) })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        val sw = MaterialSwitch(this).apply {
            applyAudioStyleToggle(this)
            isEnabled = false
            isChecked = defaultValue
        }
        root.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        lifecycleScope.launch {
            var ready = false
            val current = dataStore.data.first()[key] ?: defaultValue
            sw.isChecked = current
            sw.isEnabled = true
            ready = true
            sw.setOnCheckedChangeListener { _, checked ->
                if (!ready) return@setOnCheckedChangeListener
                lifecycleScope.launch {
                    dataStore.edit { prefs -> prefs[key] = checked }
                }
            }
        }
        return root
    }

    private fun applyAudioStyleToggle(sw: MaterialSwitch) {
        val trackStates = arrayOf(
            intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled)
        )
        sw.trackTintList = ColorStateList(
            trackStates,
            intArrayOf(
                AudioDynamicColor.mix(0xFF101827.toInt(), accentColor, 0.36f),
                ContextCompat.getColor(this, R.color.surface_variant),
                ContextCompat.getColor(this, R.color.surface)
            )
        )
        sw.thumbTintList = ColorStateList(
            trackStates,
            intArrayOf(
                accentColor,
                ContextCompat.getColor(this, R.color.on_surface_variant),
                ContextCompat.getColor(this, R.color.text_hint)
            )
        )
    }


    private fun sliderRow(key: String, iconRes: Int, titleRes: Int, subRes: Int?, min: Int, max: Int, defaultValue: Int, suffix: String): View {
        val root = baseRow(iconRes)
        val texts = textColumn(getString(titleRes), subRes?.let { getString(it) })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.86f).apply { marginStart = dp(14) })
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = prefs.getInt(key, defaultValue) - min
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        val value = TextView(this).apply {
            text = "${prefs.getInt(key, defaultValue)}$suffix"
            setTextColor(textMuted)
            textSize = 13f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                value.text = "$v$suffix"
                if (fromUser) prefs.edit().putInt(key, v).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        root.addView(seek, LinearLayout.LayoutParams(0, dp(38), 1.1f).apply { marginStart = dp(8) })
        root.addView(value, LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun outputPrecisionRow(): View {
        val root = baseRow(R.drawable.ic_audio)
        root.addView(
            textColumn(
                getString(R.string.audio_output_mode_title),
                getString(R.string.audio_output_mode_subtitle)
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
        )
        val modes = listOf(
            AudioProSettings.OUTPUT_MODE_AUTO to getString(R.string.audio_output_mode_auto),
            AudioProSettings.OUTPUT_MODE_COMPATIBILITY to getString(R.string.audio_output_mode_compatibility),
            AudioProSettings.OUTPUT_MODE_HIGH_PRECISION to getString(R.string.audio_output_mode_high_precision)
        )
        val current = AudioProSettings.normalizeOutputMode(
            prefs.getString(AudioProSettings.KEY_OUTPUT_MODE, AudioProSettings.OUTPUT_MODE_AUTO)
        )
        val chip = TextView(this).apply {
            text = modes.firstOrNull { it.first == current }?.second ?: modes.first().second
            setTextColor(if (current == AudioProSettings.OUTPUT_MODE_AUTO) accentColor else textMuted)
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
            maxLines = 1
            background = ContextCompat.getDrawable(this@AudioProSettingsActivity, R.drawable.bg_audio_pro_chip)
            setOnClickListener {
                val active = AudioProSettings.normalizeOutputMode(
                    prefs.getString(AudioProSettings.KEY_OUTPUT_MODE, AudioProSettings.OUTPUT_MODE_AUTO)
                )
                val index = modes.indexOfFirst { it.first == active }.coerceAtLeast(0)
                val next = modes[(index + 1) % modes.size]
                AudioProSettings.setOutputMode(this@AudioProSettingsActivity, next.first)
                text = next.second
                setTextColor(if (next.first == AudioProSettings.OUTPUT_MODE_AUTO) accentColor else textMuted)
            }
        }
        root.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
            marginStart = dp(8)
        })
        return root
    }

    private fun infoRow(iconRes: Int, titleRes: Int, subRes: Int, value: String, showChevron: Boolean = true, onClick: (() -> Unit)? = null): View {
        val root = baseRow(iconRes)
        root.addView(textColumn(getString(titleRes), getString(subRes)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        if (value.isNotBlank()) root.addView(TextView(this).apply {
            text = value
            setTextColor(textMuted)
            textSize = 13f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        })
        if (showChevron) {
            root.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(textMuted)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
        }
        if (onClick != null) {
            root.isClickable = true
            root.isFocusable = true
            root.setOnClickListener { onClick() }
        }
        return root
    }

    private fun baseRow(iconRes: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
        setPadding(dp(16), dp(8), dp(14), dp(8))
        val icon = ImageView(this@AudioProSettingsActivity).apply {
            setImageResource(iconRes)
            setColorFilter(textMuted)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
    }

    private fun textColumn(title: String, subtitle: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@AudioProSettingsActivity).apply {
            text = title
            setTextColor(textMain)
            textSize = 15f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(this@AudioProSettingsActivity).apply {
                text = subtitle
                setTextColor(textMuted)
                textSize = 12f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
    }


    private fun resolveAccentColor(): Int = AudioPremiumUi.resolveAccentColor(this)

    private fun openWatchedFoldersBrowser() {
        refreshAfterWatchedBrowser = true
        startActivity(Intent(this, AudioBrowserActivity::class.java).apply {
            putExtra(AudioBrowserActivity.EXTRA_WATCHED_FOLDERS_MODE, true)
        })
    }

    private fun watchedFoldersValue(): String {
        val count = AudioProSettings.watchedFolderCount(this)
        return resources.getQuantityString(R.plurals.audio_folder_count_compact, count, count)
    }

    private fun showTagEditorDialog() {
        val state = AudioRepository.loadState(this)
        val item = state.items.getOrNull(state.index)
        if (item == null) {
            android.widget.Toast.makeText(this, R.string.audio_no_current_track, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val cached = AudioMediaCache.getCachedMetadata(this, item.path)
        val override = AudioLocalEnhancements.getOverride(this, item.path)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
        }
        fun input(label: Int, value: String, number: Boolean = false): android.widget.EditText = android.widget.EditText(this).apply {
            hint = getString(label)
            setText(value)
            setSingleLine(true)
            setTextColor(textMain)
            setHintTextColor(textMuted)
            if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val currentTitle = override?.title ?: cached?.title ?: item.name.substringBeforeLast('.')
        val currentArtist = override?.artist ?: cached?.artist.orEmpty()
        val currentAlbum = override?.album ?: cached?.album.orEmpty()
        val title = input(R.string.audio_tag_title, currentTitle)
        val artist = input(R.string.audio_tag_artist, currentArtist)
        val album = input(R.string.audio_tag_album, currentAlbum)
        val genre = input(R.string.audio_tag_genre, "")
        val year = input(R.string.audio_tag_year, "", number = true)
        val track = input(R.string.audio_tag_track, (cached?.trackNumber ?: 0).takeIf { it > 0 }?.toString().orEmpty(), number = true)
        val disc = input(R.string.audio_tag_disc, "", number = true)
        listOf(title, artist, album, genre, year, track, disc).forEach { root.addView(it) }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.audio_tag_editor))
            .setView(root)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setNeutralButton(getString(R.string.audio_tag_reset), null)
            .setPositiveButton(getString(R.string.action_save), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                        android.widget.Toast.makeText(this, R.string.audio_tag_writing, android.widget.Toast.LENGTH_SHORT).show()
                        val tags = AudioTagWriter.EditableTags(
                            title = title.text.toString(),
                            artist = artist.text.toString(),
                            album = album.text.toString(),
                            genre = genre.text.toString(),
                            year = year.text.toString(),
                            track = track.text.toString(),
                            disc = disc.text.toString()
                        )
                        lifecycleScope.launch {
                            val result = withContext(AudioLibraryBackgroundDispatchers.io) { AudioTagWriter.write(this@AudioProSettingsActivity, item.path, tags) }
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                            result.onSuccess {
                                android.widget.Toast.makeText(this@AudioProSettingsActivity, R.string.audio_tag_saved_hard, android.widget.Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }.onFailure { error ->
                                android.widget.Toast.makeText(this@AudioProSettingsActivity, error.message ?: getString(R.string.audio_tag_write_failed), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        AudioLocalEnhancements.saveOverride(this, item.path, AudioLocalEnhancements.MetadataOverride())
                        android.widget.Toast.makeText(this, R.string.audio_tag_reset_done, android.widget.Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    fr.retrospare.blazeplayer.ui.DialogButtonStyler.style(dialog)
                }
                dialog.show()
                fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
            }
    }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(0x18FFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun applySystemBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
    }

}
