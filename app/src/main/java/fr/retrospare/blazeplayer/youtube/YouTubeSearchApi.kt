package fr.retrospare.blazeplayer.youtube

import fr.retrospare.blazeplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Client minimal pour l'API YouTube Data v3, recherche uniquement (endpoint /search). Ne gère
 *  jamais la lecture vidéo elle-même : ça reste la responsabilité du lecteur officiel YouTube
 *  (IFrame Player embarqué dans une WebView), jamais l'extraction d'un flux brut, qui violerait
 *  les conditions d'utilisation de YouTube. */
object YouTubeSearchApi {

    private const val BASE_URL = "https://www.googleapis.com/youtube/v3/search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Success(val items: List<YouTubeVideoItem>) : Result()
        data class Error(val message: String) : Result()
    }

    /** Vrai si aucune clé n'a été configurée dans local.properties — permet à l'UI d'afficher un
     *  message clair plutôt qu'un échec réseau opaque. */
    fun isApiKeyConfigured(): Boolean = BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    /** Calcule l'empreinte SHA-1 du certificat de signature réel de l'app à l'exécution — doit
     *  correspondre EXACTEMENT à celle enregistrée dans les restrictions Android de la clé API
     *  sur Google Cloud Console. Sans l'en-tête X-Android-Cert correspondant, Google rejette la
     *  requête avec "Requests from this Android client application <empty> are blocked", même si
     *  la clé elle-même est valide — OkHttp ne l'envoie jamais automatiquement, contrairement aux
     *  bibliothèques officielles Google. */
    private fun getSigningCertSha1(context: android.content.Context): String? {
        return try {
            val signature = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()
            } ?: return null
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            android.util.Log.w("YouTubeSearchApi", "Impossible de calculer le SHA-1 de signature", e)
            null
        }
    }

    private fun buildAuthenticatedRequest(context: android.content.Context, url: String): Request {
        val requestBuilder = Request.Builder().url(url).get()
        requestBuilder.addHeader("X-Android-Package", context.packageName)
        getSigningCertSha1(context)?.let { requestBuilder.addHeader("X-Android-Cert", it) }
        return requestBuilder.build()
    }

    suspend fun search(context: android.content.Context, query: String, maxResults: Int = 25): Result = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext Result.Error("Aucune clé API YouTube configurée (local.properties)")
        }
        if (query.isBlank()) return@withContext Result.Success(emptyList())

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?part=snippet&type=video&maxResults=$maxResults" +
                "&q=$encodedQuery&key=${BuildConfig.YOUTUBE_API_KEY}"
            val request = buildAuthenticatedRequest(context, url)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    android.util.Log.e("YouTubeSearchApi", "Échec recherche (${response.code}) : $body")
                    val reason = try {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    } catch (e: Exception) { null }
                    return@withContext Result.Error(reason ?: "Erreur ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext Result.Error("Réponse vide")
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: return@withContext Result.Success(emptyList())
                val results = mutableListOf<YouTubeVideoItem>()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val videoId = item.optJSONObject("id")?.optString("videoId") ?: continue
                    val snippet = item.optJSONObject("snippet") ?: continue
                    val thumb = snippet.optJSONObject("thumbnails")
                        ?.optJSONObject("medium")
                        ?.optString("url")
                        ?: snippet.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url")
                        ?: ""
                    results.add(
                        YouTubeVideoItem(
                            videoId = videoId,
                            title = snippet.optString("title"),
                            channelTitle = snippet.optString("channelTitle"),
                            thumbnailUrl = thumb
                        )
                    )
                }
                Result.Success(results)
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeSearchApi", "Échec recherche pour \"$query\"", e)
            Result.Error(e.message ?: "Erreur réseau")
        }
    }

    /** Récupère les VRAIES métadonnées (titre, chaîne, miniature) pour un ensemble de videoId,
     *  via l'endpoint /videos (accepte jusqu'à 50 id séparés par des virgules en un seul appel).
     *  Plus fiable qu'un cache local qui dépend d'avoir déjà vu la vidéo ailleurs avec ses
     *  données complètes — utilisé pour s'assurer que TOUTES les vidéos d'une playlist affichent
     *  bien leur miniature/description, même celles jamais vues individuellement avant. */
    suspend fun fetchVideosMetadata(context: android.content.Context, videoIds: List<String>): Map<String, YouTubeVideoItem> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured() || videoIds.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<String, YouTubeVideoItem>()
        try {
            // L'API limite à 50 id par appel : on découpe si besoin.
            videoIds.distinct().chunked(50).forEach { chunk ->
                val ids = chunk.joinToString(",")
                val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet&id=$ids&key=${BuildConfig.YOUTUBE_API_KEY}"
                val request = buildAuthenticatedRequest(context, url)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.w("YouTubeSearchApi", "Échec fetchVideosMetadata (${response.code})")
                        return@use
                    }
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items") ?: return@use
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val videoId = item.optString("id")
                        val snippet = item.optJSONObject("snippet") ?: continue
                        val thumb = snippet.optJSONObject("thumbnails")
                            ?.optJSONObject("medium")
                            ?.optString("url")
                            ?: snippet.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url")
                            ?: ""
                        result[videoId] = YouTubeVideoItem(
                            videoId = videoId,
                            title = snippet.optString("title"),
                            channelTitle = snippet.optString("channelTitle"),
                            thumbnailUrl = thumb
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTubeSearchApi", "Échec fetchVideosMetadata", e)
        }
        result
    }
}
