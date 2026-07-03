package fr.retrospare.blazeplayer.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Fournisseur de métadonnées film gratuit, sans clé API.
 *
 * Il utilise uniquement les APIs publiques Wikimedia :
 * - Wikidata pour trouver le film, l'affiche, la durée, les acteurs, genres et studios ;
 * - Wikipedia REST Summary pour le résumé dans la langue de l'application ;
 * - cache local JSON + JPG dans Android/data/<package>/files/metadata/.
 *
 * Le nom TmdbMovieRepository est gardé pour ne pas casser les appels UI existants.
 */
object TmdbMovieRepository {
    data class MovieInfo(
        val title: String = "",
        val overview: String = "",
        val posterPath: String = "",
        val releaseDate: String = "",
        val tmdbId: Int = 0,
        val language: String = "",
        val runtime: String = "",
        val actors: String = "",
        val genres: String = "",
        val studio: String = "",
        val sourceUrl: String = ""
    ) {
        val hasContent: Boolean
            get() = title.isNotBlank() || overview.isNotBlank() || posterPath.isNotBlank() ||
                runtime.isNotBlank() || actors.isNotBlank() || genres.isNotBlank() || studio.isNotBlank()
    }

    fun configured(context: Context): Boolean = true

    fun currentTmdbLanguage(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val tag = if (!appLocales.isEmpty) appLocales[0]?.toLanguageTag().orEmpty() else Locale.getDefault().toLanguageTag()
        val normalized = tag.ifBlank { "en-US" }.replace('_', '-')
        val lang = normalized.substringBefore('-').lowercase(Locale.US)
        val country = normalized.substringAfter('-', "").uppercase(Locale.US)
        return if (country.isNotBlank()) "$lang-$country" else when (lang) {
            "fr" -> "fr-FR"; "en" -> "en-US"; "es" -> "es-ES"; "de" -> "de-DE"
            "it" -> "it-IT"; "pt" -> "pt-PT"; "nl" -> "nl-NL"; "ru" -> "ru-RU"
            else -> lang
        }
    }

    fun cleanMovieTitle(fileNameOrPath: String): String = titleCandidates(fileNameOrPath).firstOrNull().orEmpty()

    fun getCached(context: Context, mediaPath: String): MovieInfo? {
        val jsonFile = jsonFile(context, mediaPath)
        if (!jsonFile.exists()) return null
        return runCatching { parseInfo(JSONObject(jsonFile.readText())) }.getOrNull()
            ?.takeIf { it.language == currentTmdbLanguage() && it.hasContent }
    }

    fun searchAndCache(context: Context, mediaPath: String, displayName: String): MovieInfo {
        val language = currentTmdbLanguage()
        val source = displayName.ifBlank { mediaPath }
        val candidates = (titleCandidates(source) + titleCandidates(mediaPath)).distinctBy { normalizeForCompare(it) }.take(5)
        val year = extractYear(source).ifBlank { extractYear(mediaPath) }
        val languages = listOf(wikiLang(language), "en").distinct()

        var info: MovieInfo? = null
        for (lang in languages) {
            for (candidate in candidates) {
                info = runCatching { fetchFromWikimedia(lang, candidate, year) }.getOrNull()
                if (info?.hasContent == true) break
            }
            if (info?.hasContent == true) break
        }

        val normalized = (info ?: MovieInfo(language = language)).copy(language = language)
        if (normalized.hasContent) {
            saveInfo(context, mediaPath, normalized)
            if (normalized.posterPath.isNotBlank()) runCatching { downloadPoster(context, mediaPath, normalized.posterPath) }
        }
        return normalized
    }

    fun posterFile(context: Context, mediaPath: String): File = File(cacheDir(context), "${cacheKey(mediaPath)}.jpg")

    fun loadPosterInto(imageView: ImageView, context: Context, mediaPath: String) {
        val f = posterFile(context, mediaPath)
        if (f.exists()) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) imageView.setImageDrawable(BitmapDrawable(context.resources, bmp))
        }
    }

    private fun fetchFromWikimedia(wikiLang: String, title: String, year: String): MovieInfo? {
        val searchIds = wikidataSearch(wikiLang, title, year).take(8)
        if (searchIds.isEmpty()) return null
        val entities = wikidataEntities(searchIds, wikiLang)
        val selected = entities.maxByOrNull { scoreEntity(it, title, year, wikiLang) } ?: return null
        if (scoreEntity(selected, title, year, wikiLang) < 20) return null

        val id = selected.optString("id")
        val labels = selected.optJSONObject("labels")
        val titleLabel = labels?.optJSONObject(wikiLang)?.optString("value")
            ?: labels?.optJSONObject("en")?.optString("value")
            ?: title
        val sitelinks = selected.optJSONObject("sitelinks")
        val localTitle = sitelinks?.optJSONObject("${wikiLang}wiki")?.optString("title").orEmpty()
        val enTitle = sitelinks?.optJSONObject("enwiki")?.optString("title").orEmpty()

        val summary = wikipediaSummary(wikiLang, localTitle.ifBlank { enTitle }, fallbackLang = "en")
        val claims = selected.optJSONObject("claims") ?: JSONObject()
        val imageName = claimCommonsFile(claims, "P18")
        val posterUrl = if (imageName.isNotBlank()) commonsFileUrl(imageName) else summary.posterUrl
        val runtime = claimQuantity(claims, "P2047").takeIf { it > 0 }?.let { formatRuntimeMinutes(it) }.orEmpty()
        val releaseYear = claimTime(claims, "P577").let { extractYear(it) }.ifBlank { year }

        val linkedIds = linkedEntityIds(claims, listOf("P161", "P136", "P272", "P750"))
        val linkedLabels = if (linkedIds.isNotEmpty()) wikidataLabels(linkedIds, wikiLang) else emptyMap()

        val actors = claimEntityIds(claims, "P161").take(5).mapNotNull { linkedLabels[it] }.joinToString(", ")
        val genres = claimEntityIds(claims, "P136").take(4).mapNotNull { linkedLabels[it] }.joinToString(", ")
        val studio = (claimEntityIds(claims, "P272") + claimEntityIds(claims, "P750")).distinct().take(3).mapNotNull { linkedLabels[it] }.joinToString(", ")
        val pageUrl = summary.pageUrl.ifBlank {
            if (localTitle.isNotBlank()) "https://$wikiLang.wikipedia.org/wiki/" + URLEncoder.encode(localTitle.replace(' ', '_'), "UTF-8")
            else "https://www.wikidata.org/wiki/$id"
        }

        return MovieInfo(
            title = summary.title.ifBlank { titleLabel },
            overview = summary.extract,
            posterPath = posterUrl,
            releaseDate = releaseYear,
            language = currentTmdbLanguage(),
            runtime = runtime,
            actors = actors,
            genres = genres,
            studio = studio,
            sourceUrl = pageUrl
        ).takeIf { it.hasContent }
    }

    private data class WikiSummary(val title: String = "", val extract: String = "", val posterUrl: String = "", val pageUrl: String = "")

    private fun wikipediaSummary(wikiLang: String, pageTitle: String, fallbackLang: String): WikiSummary {
        if (pageTitle.isBlank()) return WikiSummary()
        val summary = runCatching { wikipediaSummaryOnce(wikiLang, pageTitle) }.getOrNull()
        if (summary?.extract?.isNotBlank() == true || summary?.posterUrl?.isNotBlank() == true) return summary
        if (wikiLang != fallbackLang) return runCatching { wikipediaSummaryOnce(fallbackLang, pageTitle) }.getOrDefault(WikiSummary())
        return WikiSummary()
    }

    private fun wikipediaSummaryOnce(wikiLang: String, pageTitle: String): WikiSummary {
        val url = "https://$wikiLang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(pageTitle.replace(' ', '_'), "UTF-8")
        val obj = JSONObject(httpGet(url))
        val poster = obj.optJSONObject("thumbnail")?.optString("source").orEmpty()
            .replace("/320px-", "/640px-")
        val contentUrls = obj.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
        return WikiSummary(
            title = obj.optString("title"),
            extract = obj.optString("extract"),
            posterUrl = poster,
            pageUrl = contentUrls
        )
    }

    private fun wikidataSearch(wikiLang: String, title: String, year: String): List<String> {
        val queries = listOf(
            listOf(title, year).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, year, localizedFilmKeyword(wikiLang)).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, year, "movie").filter { it.isNotBlank() }.joinToString(" "),
            title
        ).distinct().filter { it.isNotBlank() }
        val ids = mutableListOf<String>()
        for (query in queries) {
            val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=$wikiLang&uselang=$wikiLang&limit=8&type=item&search=" + URLEncoder.encode(query, "UTF-8")
            val arr = JSONObject(httpGet(url)).optJSONArray("search") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                if (id.startsWith("Q") && !ids.contains(id)) ids.add(id)
            }
            if (ids.size >= 8) break
        }
        return ids
    }

    private fun wikidataEntities(ids: List<String>, wikiLang: String): List<JSONObject> {
        if (ids.isEmpty()) return emptyList()
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels|descriptions|claims|sitelinks&languages=$wikiLang|en&sitefilter=${wikiLang}wiki|enwiki&ids=" + ids.joinToString("|")
        val entities = JSONObject(httpGet(url)).optJSONObject("entities") ?: return emptyList()
        return ids.mapNotNull { entities.optJSONObject(it) }
    }

    private fun wikidataLabels(ids: List<String>, wikiLang: String): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        ids.chunked(40).forEach { chunk ->
            runCatching {
                val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=$wikiLang|en&ids=" + chunk.joinToString("|")
                val entities = JSONObject(httpGet(url)).optJSONObject("entities") ?: JSONObject()
                chunk.forEach { id ->
                    val labels = entities.optJSONObject(id)?.optJSONObject("labels")
                    val label = labels?.optJSONObject(wikiLang)?.optString("value")
                        ?: labels?.optJSONObject("en")?.optString("value")
                        ?: ""
                    if (label.isNotBlank()) result[id] = label
                }
            }
        }
        return result
    }

    private fun scoreEntity(entity: JSONObject, wantedTitle: String, year: String, wikiLang: String): Int {
        val labels = entity.optJSONObject("labels")
        val label = labels?.optJSONObject(wikiLang)?.optString("value") ?: labels?.optJSONObject("en")?.optString("value") ?: ""
        val desc = entity.optJSONObject("descriptions")?.optJSONObject(wikiLang)?.optString("value")
            ?: entity.optJSONObject("descriptions")?.optJSONObject("en")?.optString("value")
            ?: ""
        val claims = entity.optJSONObject("claims") ?: JSONObject()
        val candidate = normalizeForCompare(label)
        val wanted = normalizeForCompare(wantedTitle)
        var score = 0
        if (candidate == wanted) score += 80
        if (candidate.contains(wanted) || wanted.contains(candidate)) score += 40
        wanted.split(' ').filter { it.length > 2 }.forEach { if (candidate.contains(it)) score += 8 }
        if (year.isNotBlank() && claimTime(claims, "P577").contains(year)) score += 35
        if (isProbablyMovie(claims, desc)) score += 50
        if (desc.contains("film", true) || desc.contains("movie", true) || desc.contains("película", true)) score += 12
        if (desc.contains("album", true) || desc.contains("song", true) || desc.contains("soundtrack", true)) score -= 80
        if (entity.optJSONObject("sitelinks")?.has("${wikiLang}wiki") == true) score += 8
        if (entity.optJSONObject("sitelinks")?.has("enwiki") == true) score += 5
        return score
    }

    private fun isProbablyMovie(claims: JSONObject, description: String): Boolean {
        val instanceIds = claimEntityIds(claims, "P31").toSet()
        val filmLike = setOf("Q11424", "Q24869", "Q506240", "Q93204", "Q202866")
        return instanceIds.any { it in filmLike } || description.contains("film", true) || description.contains("movie", true)
    }

    private fun claimEntityIds(claims: JSONObject, property: String): List<String> {
        val arr = claims.optJSONArray(property) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)
                ?.optJSONObject("mainsnak")
                ?.optJSONObject("datavalue")
                ?.optJSONObject("value")
                ?.optString("id")
                .orEmpty()
            if (id.startsWith("Q") && !out.contains(id)) out.add(id)
        }
        return out
    }

    private fun linkedEntityIds(claims: JSONObject, properties: List<String>): List<String> = properties.flatMap { claimEntityIds(claims, it) }.distinct()

    private fun claimCommonsFile(claims: JSONObject, property: String): String {
        val arr = claims.optJSONArray(property) ?: return ""
        return arr.optJSONObject(0)
            ?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optString("value")
            .orEmpty()
    }

    private fun claimQuantity(claims: JSONObject, property: String): Int {
        val arr = claims.optJSONArray(property) ?: return 0
        val raw = arr.optJSONObject(0)
            ?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optJSONObject("value")
            ?.optString("amount")
            .orEmpty()
        return raw.replace("+", "").substringBefore('.').toIntOrNull() ?: 0
    }

    private fun claimTime(claims: JSONObject, property: String): String {
        val arr = claims.optJSONArray(property) ?: return ""
        return arr.optJSONObject(0)
            ?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optJSONObject("value")
            ?.optString("time")
            .orEmpty()
    }

    private fun commonsFileUrl(fileName: String): String = "https://commons.wikimedia.org/wiki/Special:FilePath/" + URLEncoder.encode(fileName, "UTF-8") + "?width=640"

    private fun formatRuntimeMinutes(minutes: Int): String = if (minutes >= 60) "%dh%02d".format(minutes / 60, minutes % 60) else "$minutes min"

    private fun titleCandidates(fileNameOrPath: String): List<String> {
        val raw = fileNameOrPath.substringAfterLast('/').substringAfterLast('\\')
        val noQuery = raw.substringBefore('?')
        val noExt = noQuery.substringBeforeLast('.', noQuery)
        val decoded = runCatching { URLDecoder.decode(noExt, "UTF-8") }.getOrDefault(noExt)
        val year = extractYear(decoded)
        var normalized = decoded
            .replace(Regex("\\[[^\\]]*]"), " ")
            .replace(Regex("\\{[^}]*}"), " ")
            .replace(Regex("\\((?!\\s*(?:19|20)\\d{2}\\s*\\))[^)]*\\)"), " ")
            .replace(Regex("[._+]+"), " ")
            .replace(Regex("[-–—]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val noise = Regex(
            "\\b(2160p|1080p|720p|576p|480p|4320p|8k|4k|uhd|hdr10\\+?|hdr|dv|dolby\\s*vision|" +
                "web\\s*dl|webdl|web\\s*rip|webrip|blu\\s*ray|bluray|bdrip|br\\s*rip|brrip|dvdrip|remux|hdtv|" +
                "x264|x265|h264|h265|avc|hevc|aac|ac3|eac3|dts|truehd|atmos|flac|mp3|opus|10bit|8bit|" +
                "multi|vostfr|subfrench|french|truefrench|vf2|vff|vfq|vo|stfr|subbed|dubbed|proper|repack|rerip|" +
                "extended|unrated|directors?\\s*cut|theatrical|final\\s*cut|imax|open\\s*matte|criterion|" +
                "yts|yify|rarbg|eztv|ettv|galaxyrg|tgx|amzn|nf|dsnp|hmax|itunes)\\b",
            RegexOption.IGNORE_CASE
        )
        normalized = normalized
            .replace(noise, " ")
            .replace(Regex("\\b[0-9]+\\s*(?:ch|mb|gb|fps|hz|kbps|mbps)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val beforeYear = if (year.isNotBlank()) normalized.substringBefore(year).trim() else normalized
        val withoutYear = normalized.replace(Regex("\\b(19|20)\\d{2}\\b"), " ").replace(Regex("\\s+"), " ").trim()
        return listOf(beforeYear, withoutYear, normalized)
            .map { it.replace(Regex("^[^A-Za-zÀ-ÿ0-9]+|[^A-Za-zÀ-ÿ0-9]+$"), "").trim() }
            .filter { it.length >= 2 }
            .distinctBy { normalizeForCompare(it) }
            .take(6)
            .ifEmpty { listOf(decoded) }
    }

    private fun normalizeForCompare(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9à-ÿ]+"), " ")
        .replace(Regex("\\b(the|a|an|le|la|les|un|une|des|de|du|el|los|las|il|lo|gli)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun saveInfo(context: Context, mediaPath: String, info: MovieInfo) {
        val obj = JSONObject()
            .put("title", info.title)
            .put("overview", info.overview)
            .put("posterPath", info.posterPath)
            .put("releaseDate", info.releaseDate)
            .put("tmdbId", info.tmdbId)
            .put("language", info.language)
            .put("runtime", info.runtime)
            .put("actors", info.actors)
            .put("genres", info.genres)
            .put("studio", info.studio)
            .put("sourceUrl", info.sourceUrl)
        jsonFile(context, mediaPath).writeText(obj.toString())
    }

    private fun parseInfo(obj: JSONObject): MovieInfo = MovieInfo(
        title = obj.optString("title"),
        overview = obj.optString("overview"),
        posterPath = obj.optString("posterPath"),
        releaseDate = obj.optString("releaseDate"),
        tmdbId = obj.optInt("tmdbId"),
        language = obj.optString("language"),
        runtime = obj.optString("runtime"),
        actors = obj.optString("actors"),
        genres = obj.optString("genres"),
        studio = obj.optString("studio"),
        sourceUrl = obj.optString("sourceUrl")
    )

    private fun downloadPoster(context: Context, mediaPath: String, posterUrl: String) {
        val conn = (URL(posterUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_500
            readTimeout = 3_500
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BlazePlayer/1.0 (Wikimedia metadata cache)")
        }
        try {
            conn.inputStream.use { input -> posterFile(context, mediaPath).outputStream().use { output -> input.copyTo(output) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_500
            readTimeout = 3_500
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BlazePlayer/1.0 (Wikimedia metadata cache)")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cacheDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "metadata").apply { mkdirs() }

    private fun jsonFile(context: Context, mediaPath: String): File = File(cacheDir(context), "${cacheKey(mediaPath)}.json")

    private fun cacheKey(mediaPath: String): String {
        val clean = cleanMovieTitle(mediaPath)
        val year = extractYear(mediaPath)
        val base = listOf(clean, year).filter { it.isNotBlank() }.joinToString("_")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return base.ifBlank { hash(mediaPath).take(16) }
    }

    private fun extractYear(value: String): String = Regex("\\b(19|20)\\d{2}\\b").find(value)?.value.orEmpty()

    private fun wikiLang(language: String): String = when (language.substringBefore('-').lowercase(Locale.US)) {
        "fr", "en", "es", "de", "it", "pt", "nl", "ru" -> language.substringBefore('-').lowercase(Locale.US)
        else -> "en"
    }

    private fun localizedFilmKeyword(wikiLang: String): String = when (wikiLang) {
        "fr" -> "film"
        "es" -> "película"
        "de" -> "Film"
        "it" -> "film"
        "pt" -> "filme"
        "nl" -> "film"
        "ru" -> "фильм"
        else -> "film"
    }

    private fun hash(value: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }
}
