package fr.retrospare.blazeplayer.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import fr.retrospare.blazeplayer.R
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * Fenêtre TMDb sans clé API :
 * - nettoie le nom du fichier ;
 * - interroge uniquement la page de recherche publique TMDb en arrière-plan ;
 * - charge la page mobile TMDb du premier film trouvé dans un WebView ;
 * - affiche "Titre non trouvé" si aucun lien /movie n'est trouvé.
 */
object TmdbWebDialog {
    fun show(context: Context, fileNameOrTitle: String) {
        val density = context.resources.displayMetrics.density
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (560 * density).toInt())
        }
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = ProgressBar.VISIBLE
        }
        val message = TextView(context).apply {
            text = context.getString(R.string.loading)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            visibility = TextView.VISIBLE
        }
        val webView = WebView(context).apply {
            visibility = WebView.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = ProgressBar.GONE
                    message.visibility = TextView.GONE
                    visibility = WebView.VISIBLE
                }
            }
        }
        container.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(progress, FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt(), android.view.Gravity.CENTER))

        val dialog = AlertDialog.Builder(context)
            .setTitle("TMDb")
            .setView(container)
            .setPositiveButton(context.getString(R.string.action_close), null)
            .create()
        dialog.setOnDismissListener { runCatching { webView.destroy() } }
        dialog.show()

        val main = Handler(Looper.getMainLooper())
        Thread {
            val url = runCatching { findTmdbMovieUrl(fileNameOrTitle) }.getOrNull()
            main.post {
                if (!dialog.isShowing) return@post
                progress.visibility = ProgressBar.GONE
                if (url.isNullOrBlank()) {
                    webView.visibility = WebView.GONE
                    message.visibility = TextView.VISIBLE
                    message.text = context.getString(R.string.tmdb_title_not_found)
                } else {
                    progress.visibility = ProgressBar.VISIBLE
                    message.visibility = TextView.VISIBLE
                    message.text = context.getString(R.string.loading)
                    webView.loadUrl(url)
                }
            }
        }.apply { name = "BlazeTmdbWebSearch"; isDaemon = true; start() }
    }

    private fun findTmdbMovieUrl(fileNameOrTitle: String): String? {
        val title = cleanTitle(fileNameOrTitle)
        if (title.isBlank()) return null
        val query = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "https://www.themoviedb.org/search/movie?query=$query"
        val html = httpGet(searchUrl)
        val patterns = listOf(
            Pattern.compile("href=\\\"(/movie/[0-9]+[^\\\"]*)\\\""),
            Pattern.compile("href='(/movie/[0-9]+[^']*)'")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val path = matcher.group(1) ?: return null
                return "https://www.themoviedb.org" + path.replace("&amp;", "&")
            }
        }
        return null
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun cleanTitle(fileNameOrTitle: String): String {
        var s = fileNameOrTitle.substringAfterLast('/').substringAfterLast('\\')
        s = s.substringBeforeLast('.', s)
        val year = Regex("\\b(19|20)\\d{2}\\b").find(s)?.value
        s = s.replace('.', ' ').replace('_', ' ').replace('-', ' ')
        s = s.replace(Regex("\\b(2160p|1080p|720p|480p|bluray|brrip|web[- ]?dl|webrip|hdrip|dvdrip|x264|x265|h264|h265|hevc|aac|dts|truehd|atmos|multi|vostfr|vf|french|english|extended|remux|proper|repack)\\b", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("[\\[\\](){},]+"), " ").replace(Regex("\\s+"), " ").trim()
        return listOf(s, year).filter { !it.isNullOrBlank() }.distinct().joinToString(" ")
    }
}
