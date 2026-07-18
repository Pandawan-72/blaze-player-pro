package fr.retrospare.blazeplayer.player

import android.net.Uri
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Client robuste de l'API publique LRCLIB, sans dépendance réseau supplémentaire. */
object LrclibClient {
    private const val SEARCH_URL = "https://lrclib.net/api/search"
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_REQUESTS = 7

    data class LyricsResult(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Double,
        val instrumental: Boolean,
        val plainLyrics: String,
        val syncedLyrics: String,
        internal var matchScore: Double = 0.0
    ) {
        val hasSyncedLyrics: Boolean get() = syncedLyrics.isNotBlank()
        val hasLyrics: Boolean get() = syncedLyrics.isNotBlank() || plainLyrics.isNotBlank()
        val bestLyrics: String get() = syncedLyrics.ifBlank { plainLyrics }
    }

    private data class SearchRequest(
        val trackName: String = "",
        val artistName: String = "",
        val albumName: String = "",
        val query: String = ""
    )

    /**
     * Recherche progressive et tolérante aux métadonnées imparfaites.
     *
     * LRCLIB accepte soit une recherche structurée, soit un paramètre libre `q`. On combine donc :
     * - les tags originaux ;
     * - des tags nettoyés (numéro de piste, extension, mentions "official", "remaster", etc.) ;
     * - une requête libre artiste + titre ;
     * - une recherche au titre seul en dernier recours.
     *
     * Les réponses sont fusionnées puis classées localement selon le titre, l'artiste, l'album et
     * surtout la durée réelle du morceau, ce qui évite qu'une reprise ou une version live remonte
     * avant la bonne piste.
     */
    @Throws(IOException::class)
    fun searchSmart(
        trackName: String,
        artistName: String = "",
        albumName: String = "",
        durationMs: Long = 0L
    ): List<LyricsResult> {
        val originalTitle = cleanupWhitespace(trackName)
        if (originalTitle.isBlank()) return emptyList()

        val originalArtist = cleanupWhitespace(artistName)
        val originalAlbum = cleanupWhitespace(albumName)
        val inferred = inferArtistAndTitle(originalTitle, originalArtist)
        val effectiveArtist = inferred.first
        val effectiveTitle = inferred.second
        val simplifiedTitle = simplifyTitle(effectiveTitle)
        val simplifiedArtist = simplifyArtist(effectiveArtist)

        val requests = LinkedHashSet<SearchRequest>()
        fun structured(title: String, artist: String = "", album: String = "") {
            val cleanTitle = cleanupWhitespace(title)
            if (cleanTitle.isBlank()) return
            requests += SearchRequest(
                trackName = cleanTitle,
                artistName = cleanupWhitespace(artist),
                albumName = cleanupWhitespace(album)
            )
        }
        fun freeQuery(value: String) {
            cleanupWhitespace(value).takeIf { it.isNotBlank() }?.let {
                requests += SearchRequest(query = it)
            }
        }

        // Ne jamais commencer par l'album : c'est le tag le plus souvent absent ou divergent.
        structured(effectiveTitle, effectiveArtist)
        freeQuery(listOf(effectiveArtist, effectiveTitle).filter { it.isNotBlank() }.joinToString(" "))

        // Si le nom de fichier ressemblait à « Artiste - Titre », conserver aussi la chaîne
        // complète : certains morceaux utilisent réellement un tiret dans leur titre.
        if (!sameSearchText(originalTitle, effectiveTitle)) {
            structured(stripTrackNumber(originalTitle))
            freeQuery(stripTrackNumber(originalTitle))
        }

        if (!sameSearchText(simplifiedTitle, effectiveTitle) || !sameSearchText(simplifiedArtist, effectiveArtist)) {
            structured(simplifiedTitle, simplifiedArtist)
            freeQuery(listOf(simplifiedArtist, simplifiedTitle).filter { it.isNotBlank() }.joinToString(" "))
        }

        // L'album reste utile comme variante secondaire quand les tags semblent fiables.
        if (originalAlbum.isNotBlank()) structured(simplifiedTitle, simplifiedArtist, originalAlbum)
        freeQuery(simplifiedTitle)

        val merged = LinkedHashMap<String, LyricsResult>()
        val targetDurationSeconds = durationMs.takeIf { it > 0L }?.div(1000.0) ?: 0.0
        var successfulRequests = 0
        var consecutiveFailures = 0
        var firstFailure: IOException? = null

        for (request in requests.take(MAX_REQUESTS)) {
            try {
                val response = executeSearch(request)
                successfulRequests++
                consecutiveFailures = 0
                response.forEach { result ->
                    val key = if (result.id >= 0L) {
                        "id:${result.id}"
                    } else {
                        listOf(result.trackName, result.artistName, result.albumName, result.durationSeconds.toString())
                            .joinToString("|") { normalizeForMatch(it) }
                    }
                    val previous = merged[key]
                    if (previous == null || (!previous.hasSyncedLyrics && result.hasSyncedLyrics)) {
                        merged[key] = result
                    }
                }
                if (successfulRequests >= 2 && merged.values.any {
                        isConfidentSyncedMatch(
                            result = it,
                            title = simplifiedTitle.ifBlank { effectiveTitle },
                            artist = simplifiedArtist.ifBlank { effectiveArtist },
                            targetDurationSeconds = targetDurationSeconds
                        )
                    }
                ) {
                    break
                }
            } catch (error: IOException) {
                consecutiveFailures++
                if (firstFailure == null) firstFailure = error
                if (successfulRequests == 0 && consecutiveFailures >= 2) break
            }
        }

        if (successfulRequests == 0 && firstFailure != null) throw firstFailure

        return merged.values
            .filter { it.hasLyrics && !it.instrumental }
            .onEach {
                it.matchScore = scoreResult(
                    result = it,
                    title = effectiveTitle,
                    simplifiedTitle = simplifiedTitle,
                    artist = effectiveArtist,
                    simplifiedArtist = simplifiedArtist,
                    album = originalAlbum,
                    targetDurationSeconds = targetDurationSeconds
                )
            }
            .sortedWith(
                compareByDescending<LyricsResult> { it.matchScore }
                    .thenByDescending { it.hasSyncedLyrics }
                    .thenBy { if (targetDurationSeconds > 0.0 && it.durationSeconds > 0.0) abs(it.durationSeconds - targetDurationSeconds) else 0.0 }
                    .thenBy { it.artistName.lowercase(Locale.ROOT) }
            )
    }

    private fun executeSearch(request: SearchRequest): List<LyricsResult> {
        val uri = Uri.parse(SEARCH_URL).buildUpon().apply {
            if (request.query.isNotBlank()) {
                appendQueryParameter("q", request.query)
            } else {
                appendQueryParameter("track_name", request.trackName)
                request.artistName.takeIf { it.isNotBlank() }?.let { appendQueryParameter("artist_name", it) }
                request.albumName.takeIf { it.isNotBlank() }?.let { appendQueryParameter("album_name", it) }
            }
        }.build()

        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            setRequestProperty("User-Agent", "BlazeAudio/1.0 (Android; LRCLIB integration)")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("LRCLIB HTTP $status")
            val announcedLength = connection.contentLength
            if (announcedLength > MAX_RESPONSE_BYTES) throw IOException("LRCLIB response too large")

            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IOException("LRCLIB response too large")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }

            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            return buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    fun nullableString(name: String): String =
                        if (item.isNull(name)) "" else item.optString(name, "").trim()
                    add(
                        LyricsResult(
                            id = item.optLong("id", -1L),
                            trackName = nullableString("trackName"),
                            artistName = nullableString("artistName"),
                            albumName = nullableString("albumName"),
                            durationSeconds = item.optDouble("duration", 0.0),
                            instrumental = item.optBoolean("instrumental", false),
                            plainLyrics = nullableString("plainLyrics"),
                            syncedLyrics = nullableString("syncedLyrics")
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }


    private fun isConfidentSyncedMatch(
        result: LyricsResult,
        title: String,
        artist: String,
        targetDurationSeconds: Double
    ): Boolean {
        if (!result.hasSyncedLyrics || result.instrumental) return false
        val titleSimilarity = textSimilarity(normalizeForMatch(result.trackName), normalizeForMatch(title))
        if (titleSimilarity < 0.94) return false
        if (artist.isNotBlank()) {
            val artistSimilarity = textSimilarity(normalizeForMatch(result.artistName), normalizeForMatch(artist))
            if (artistSimilarity < 0.82) return false
        }
        if (targetDurationSeconds > 0.0 && result.durationSeconds > 0.0 &&
            abs(result.durationSeconds - targetDurationSeconds) > 12.0
        ) return false
        return true
    }

    private fun scoreResult(
        result: LyricsResult,
        title: String,
        simplifiedTitle: String,
        artist: String,
        simplifiedArtist: String,
        album: String,
        targetDurationSeconds: Double
    ): Double {
        val resultTitle = normalizeForMatch(result.trackName)
        val targetTitle = normalizeForMatch(title)
        val targetSimpleTitle = normalizeForMatch(simplifiedTitle)
        val resultArtist = normalizeForMatch(result.artistName)
        val targetArtist = normalizeForMatch(artist)
        val targetSimpleArtist = normalizeForMatch(simplifiedArtist)

        var score = 0.0
        score += when {
            resultTitle == targetTitle && targetTitle.isNotBlank() -> 130.0
            resultTitle == targetSimpleTitle && targetSimpleTitle.isNotBlank() -> 120.0
            else -> 92.0 * textSimilarity(resultTitle, targetSimpleTitle.ifBlank { targetTitle })
        }

        if (targetArtist.isNotBlank() || targetSimpleArtist.isNotBlank()) {
            score += when {
                resultArtist == targetArtist && targetArtist.isNotBlank() -> 72.0
                resultArtist == targetSimpleArtist && targetSimpleArtist.isNotBlank() -> 68.0
                else -> 50.0 * textSimilarity(resultArtist, targetSimpleArtist.ifBlank { targetArtist })
            }
        }

        if (album.isNotBlank() && result.albumName.isNotBlank()) {
            score += 12.0 * textSimilarity(normalizeForMatch(result.albumName), normalizeForMatch(album))
        }

        if (targetDurationSeconds > 0.0 && result.durationSeconds > 0.0) {
            val delta = abs(result.durationSeconds - targetDurationSeconds)
            score += when {
                delta <= 2.0 -> 48.0
                delta <= 5.0 -> 38.0
                delta <= 10.0 -> 25.0
                delta <= 20.0 -> 12.0
                delta <= 45.0 -> 3.0
                else -> -minOf(30.0, delta / 8.0)
            }
        }

        if (result.hasSyncedLyrics) score += 18.0
        return score
    }

    private fun textSimilarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
        val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val intersection = leftTokens.intersect(rightTokens).size.toDouble()
        val union = leftTokens.union(rightTokens).size.toDouble()
        val jaccard = if (union > 0.0) intersection / union else 0.0
        val containment = intersection / max(1, minOf(leftTokens.size, rightTokens.size)).toDouble()
        return (jaccard * 0.55 + containment * 0.45).coerceIn(0.0, 1.0)
    }

    private fun inferArtistAndTitle(trackName: String, artistName: String): Pair<String, String> {
        if (artistName.isNotBlank()) return artistName to stripTrackNumber(trackName)
        val cleaned = stripTrackNumber(trackName)
        val separators = listOf(" - ", " – ", " — ", " | ")
        for (separator in separators) {
            val pieces = cleaned.split(separator, limit = 2).map(::cleanupWhitespace)
            if (pieces.size == 2 && pieces[0].length >= 2 && pieces[1].length >= 2) {
                return pieces[0] to pieces[1]
            }
        }
        return "" to cleaned
    }

    private fun simplifyTitle(value: String): String {
        var title = stripTrackNumber(
            value.replace(
                Regex("(?i)\\.(?:mp3|flac|m4a|aac|ogg|opus|wav|wma|alac|aiff?)$"),
                ""
            )
        )
        title = title.replace(
            Regex("(?i)\\s*[\\[(](?:official(?:\\s+(?:music|lyric|lyrics))?\\s+video|official\\s+audio|lyrics?|audio|video|visuali[sz]er|hd|4k|(?:\\d{4}\\s+)?remaster(?:ed)?(?:\\s+\\d{4})?|radio\\s+edit)[^\\])]*[\\])]") ,
            " "
        )
        title = title.replace(Regex("(?i)\\s*[\\[(](?:feat\\.?|ft\\.?|featuring)\\s+[^\\])]+[\\])]") , " ")
        title = title.replace(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.+$"), " ")
        return cleanupWhitespace(title).ifBlank { cleanupWhitespace(value) }
    }

    private fun simplifyArtist(value: String): String = cleanupWhitespace(
        value.replace(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|&|x)\\s+.+$"), " ")
    ).ifBlank { cleanupWhitespace(value) }

    private fun stripTrackNumber(value: String): String =
        cleanupWhitespace(value.replace(Regex("^\\s*(?:cd\\s*\\d+[._ -]*)?\\d{1,3}[._ -]+", RegexOption.IGNORE_CASE), ""))

    private fun sameSearchText(left: String, right: String): Boolean =
        normalizeForMatch(left) == normalizeForMatch(right)

    private fun cleanupWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun normalizeForMatch(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace('&', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
