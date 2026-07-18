package fr.retrospare.blazeplayer.player

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recherche LRCLIB et enregistre automatiquement le résultat sélectionné à côté du morceau. */
object LrclibLyricsDialog {
    fun show(
        fragment: Fragment,
        audioPath: String,
        trackName: String,
        artistName: String,
        albumName: String,
        durationMs: Long,
        accentColor: Int,
        onSaved: (LyricsFileStorage.SaveResult) -> Unit
    ) {
        val context = fragment.context ?: return
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        // Dimensions calculées avant l'ajout de la fenêtre : le modal apparaît directement à sa
        // taille finale, sans première frame en WRAP_CONTENT suivie d'un redimensionnement visible.
        val dialogWidth = (context.resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost(dp(560))
        val dialogHeight = (context.resources.displayMetrics.heightPixels * 0.86f).toInt().coerceAtMost(dp(720))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            attributes = attributes.apply {
                width = dialogWidth
                height = dialogHeight
                gravity = Gravity.CENTER
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_rounded)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(TextView(context).apply {
            setText(R.string.lrclib_search_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(context).apply {
            setText(R.string.lrclib_search_subtitle)
            setTextColor(context.getColor(R.color.on_surface_variant))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        })

        fun searchField(value: String, hintRes: Int): EditText = EditText(context).apply {
            setText(value)
            setHint(hintRes)
            setTextColor(context.getColor(R.color.on_surface))
            setHintTextColor(context.getColor(R.color.text_hint))
            textSize = 15f
            setSingleLine(true)
            background = ContextCompat.getDrawable(context, R.drawable.bg_setting_item)
            setPadding(dp(14), 0, dp(14), 0)
        }

        val titleField = searchField(trackName, R.string.lrclib_track_hint)
        val artistField = searchField(artistName, R.string.lrclib_artist_hint)
        val albumField = searchField(albumName, R.string.lrclib_album_hint)
        root.addView(titleField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })
        root.addView(artistField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })
        root.addView(albumField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })

        val searchButton = MaterialButton(context).apply {
            setText(R.string.lrclib_search_action)
            isAllCaps = false
        }
        root.addView(searchButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        // INVISIBLE réserve l'espace dès la première frame. Passer de GONE à VISIBLE était la
        // seconde cause du petit saut vertical au lancement de la recherche automatique.
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })

        val status = TextView(context).apply {
            setTextColor(context.getColor(R.color.on_surface_variant))
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        val resultsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val resultsScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(resultsContainer)
        }
        root.addView(resultsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        var activeJob: Job? = null

        fun setBusy(busy: Boolean) {
            progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
            searchButton.isEnabled = !busy
            titleField.isEnabled = !busy
            artistField.isEnabled = !busy
            albumField.isEnabled = !busy
        }

        fun saveResult(result: LrclibClient.LyricsResult) {
            activeJob?.cancel()
            setBusy(true)
            status.setText(R.string.lrclib_downloading)
            activeJob = fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val saved = runCatching {
                    LyricsFileStorage.saveBesideTrack(context.applicationContext, audioPath, result.bestLyrics)
                }
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    setBusy(false)
                    saved.onSuccess { saveResult ->
                        AudioLocalEnhancements.invalidateLyrics(audioPath)
                        Toast.makeText(
                            context,
                            context.getString(R.string.lrclib_saved, saveResult.fileName),
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                        onSaved(saveResult)
                    }.onFailure { error ->
                        status.setTextColor(context.getColor(R.color.red_accent))
                        status.text = context.getString(
                            R.string.lrclib_save_failed,
                            error.message ?: context.getString(R.string.unknown_generic)
                        )
                    }
                }
            }
        }

        fun renderResults(results: List<LrclibClient.LyricsResult>) {
            resultsContainer.removeAllViews()
            val available = results.filter { it.hasLyrics && !it.instrumental }

            if (available.isEmpty()) {
                status.setTextColor(context.getColor(R.color.on_surface_variant))
                status.setText(R.string.lrclib_no_synced_results)
                return
            }
            status.setTextColor(accentColor)
            status.text = context.resources.getQuantityString(R.plurals.lrclib_results_count, available.size, available.size)

            available.forEach { item ->
                val duration = if (item.durationSeconds > 0.0) formatDuration(item.durationSeconds.toLong()) else "--:--"
                val subtitle = buildString {
                    append(item.artistName.ifBlank { context.getString(R.string.unknown_generic) })
                    item.albumName.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    append(" · ").append(duration)
                }
                val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    text = buildString {
                        append(context.getString(R.string.lrclib_download_action))
                        append('\n')
                        append(item.trackName.ifBlank { titleField.text.toString() })
                        append('\n')
                        append(subtitle)
                    }
                    setTextColor(context.getColor(R.color.on_surface))
                    setOnClickListener { saveResult(item) }
                    contentDescription = context.getString(R.string.lrclib_download_result, item.trackName, item.artistName)
                }
                resultsContainer.addView(
                    button,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    }
                )
            }
        }

        fun runSearch() {
            val queryTitle = titleField.text?.toString()?.trim().orEmpty()
            if (queryTitle.isBlank()) {
                titleField.error = context.getString(R.string.lrclib_track_required)
                return
            }
            activeJob?.cancel()
            root.requestFocus()
            setBusy(true)
            resultsContainer.removeAllViews()
            status.setTextColor(context.getColor(R.color.on_surface_variant))
            status.setText(R.string.lrclib_searching)
            activeJob = fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    LrclibClient.searchSmart(
                        trackName = queryTitle,
                        artistName = artistField.text?.toString().orEmpty(),
                        albumName = albumField.text?.toString().orEmpty(),
                        durationMs = durationMs
                    )
                }
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    setBusy(false)
                    result.onSuccess(::renderResults).onFailure { error ->
                        status.setTextColor(context.getColor(R.color.red_accent))
                        status.text = context.getString(
                            R.string.lrclib_search_failed,
                            error.message ?: context.getString(R.string.unknown_generic)
                        )
                    }
                }
            }
        }

        searchButton.setOnClickListener { runSearch() }
        dialog.setOnDismissListener { activeJob?.cancel() }
        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(dialogWidth, dialogHeight)
        )
        root.requestFocus()
        dialog.show()
        root.post { if (dialog.isShowing) runSearch() }
        fr.retrospare.blazeplayer.ui.HapticFeedbackManager.attachToWindow(dialog.window)
    }

    private fun formatDuration(seconds: Long): String =
        "%d:%02d".format(seconds / 60L, seconds % 60L)
}
