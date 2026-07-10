package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.EnumSet
import kotlin.math.max

/** Analyse de loudness locale pour rendre ReplayGain réellement exploitable même si le fichier
 *  ne contient pas de tags ReplayGain. Le résultat est mis en cache par chemin, pour éviter de
 *  ré-analyser les morceaux à chaque lecture.
 *
 *  On utilise volumedetect FFmpeg en copie locale/cache : pas de réencodage, pas de modification du
 *  fichier. Pour limiter l'impact CPU, seule la première fenêtre de lecture est analysée ; c'est
 *  suffisant pour lisser les écarts de volume perçus dans la majorité des bibliothèques locales. */
object AudioLoudnessAnalyzer {
    private const val PREFS = "blaze_audio_loudness_cache"
    private const val ALBUM_PREFS = "blaze_audio_album_loudness_cache"
    private const val ANALYSIS_SECONDS = 120
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<LoudnessInfo>>()

    data class LoudnessInfo(
        val meanDb: Float,
        val maxDb: Float,
        val trackGainDb: Float
    )

    suspend fun getOrAnalyze(context: Context, path: String, albumKey: String = ""): LoudnessInfo? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        load(context, path)?.let { return@withContext it }
        runCatching {
            val localInput = prepareLocalInput(context, path)
            try {
                analyzeLocalFile(localInput).also { info ->
                    save(context, path, info)
                    if (albumKey.isNotBlank()) updateAlbumAggregate(context, albumKey, info)
                }
            } finally {
                if (localInput.parentFile == context.cacheDir && localInput.name.startsWith("loudness_")) {
                    runCatching { localInput.delete() }
                }
            }
        }.onFailure { Log.w("AudioLoudnessAnalyzer", "ReplayGain analysis failed for $path", it) }
            .getOrNull()
    }

    fun cachedReplayGainDb(context: Context, path: String, albumKey: String, mode: Int): Float {
        return when (mode) {
            AudioProSettings.REPLAYGAIN_TRACK -> load(context, path)?.trackGainDb ?: 0f
            AudioProSettings.REPLAYGAIN_ALBUM -> loadAlbumGain(context, albumKey) ?: load(context, path)?.trackGainDb ?: 0f
            else -> 0f
        }
    }

    fun replayGainDb(context: Context, path: String, albumKey: String, mode: Int, info: LoudnessInfo?): Float {
        return when (mode) {
            AudioProSettings.REPLAYGAIN_TRACK -> info?.trackGainDb ?: cachedReplayGainDb(context, path, albumKey, mode)
            AudioProSettings.REPLAYGAIN_ALBUM -> loadAlbumGain(context, albumKey) ?: info?.trackGainDb ?: 0f
            else -> 0f
        }
    }

    private fun analyzeLocalFile(input: File): LoudnessInfo {
        val session = FFmpegKit.executeWithArguments(arrayOf(
            "-hide_banner", "-nostats", "-t", ANALYSIS_SECONDS.toString(),
            "-i", input.absolutePath,
            "-vn", "-af", "volumedetect", "-f", "null", "-"
        ))
        val logs = session.allLogsAsString.orEmpty()
        val mean = Regex("mean_volume:\\s*(-?\\d+(?:\\.\\d+)?)\\s*dB").find(logs)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val maxPeak = Regex("max_volume:\\s*(-?\\d+(?:\\.\\d+)?)\\s*dB").find(logs)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        if (mean == null || maxPeak == null) error("Loudness unavailable")
        // Objectif proche ReplayGain classique : -18 dBFS moyen, avec protection de crête.
        val wantedGain = TARGET_MEAN_DB - mean
        val peakSafeGain = -0.7f - maxPeak
        val gain = minOf(wantedGain, peakSafeGain).coerceIn(-12f, 8f)
        return LoudnessInfo(meanDb = mean, maxDb = maxPeak, trackGainDb = gain)
    }

    private fun prepareLocalInput(context: Context, path: String): File {
        return when {
            path.startsWith("smb://", true) -> copySmbToCache(context, path)
            path.startsWith("content://", true) -> copyContentToCache(context, Uri.parse(path), guessExtension(path, "m4a"))
            path.startsWith("file://", true) -> File(Uri.parse(path).path.orEmpty())
            path.startsWith("http://", true) || path.startsWith("https://", true) -> error("Remote stream analysis unavailable")
            else -> File(path)
        }
    }

    private fun copyContentToCache(context: Context, uri: Uri, ext: String): File {
        val temp = File(context.cacheDir, "loudness_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 512 * 1024) }
        } ?: error("Content unreadable")
        return temp
    }

    private fun copySmbToCache(context: Context, path: String): File {
        val parsed = SmbDataSource.parseSmbUri(Uri.parse(path))
        val temp = File(context.cacheDir, "loudness_${System.currentTimeMillis()}.${guessExtension(path, "m4a")}")
        var share: com.hierynomus.smbj.share.DiskShare? = null
        var file: com.hierynomus.smbj.share.File? = null
        try {
            share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
            file = share.openFile(
                parsed.filePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
            val size = file.getFileInformation(FileStandardInformation::class.java).endOfFile
            val buffer = ByteArray(512 * 1024)
            var offset = 0L
            temp.outputStream().use { output ->
                while (offset < size) {
                    val wanted = minOf(buffer.size.toLong(), size - offset).toInt()
                    val read = file.read(buffer, offset, 0, wanted)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    offset += read.toLong()
                }
            }
            return temp
        } catch (e: Exception) {
            runCatching { temp.delete() }
            throw e
        } finally {
            runCatching { file?.close() }
            runCatching { share?.close() }
        }
    }

    private fun save(context: Context, path: String, info: LoudnessInfo) {
        val raw = JSONObject()
            .put("mean", info.meanDb.toDouble())
            .put("max", info.maxDb.toDouble())
            .put("gain", info.trackGainDb.toDouble())
            .toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(path), enc(raw)).apply()
    }

    private fun load(context: Context, path: String): LoudnessInfo? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(path), null) ?: return null
        return runCatching {
            val obj = JSONObject(dec(raw))
            LoudnessInfo(
                meanDb = obj.optDouble("mean").toFloat(),
                maxDb = obj.optDouble("max").toFloat(),
                trackGainDb = obj.optDouble("gain").toFloat()
            )
        }.getOrNull()
    }

    private fun updateAlbumAggregate(context: Context, albumKey: String, info: LoudnessInfo) {
        val p = context.getSharedPreferences(ALBUM_PREFS, Context.MODE_PRIVATE)
        val key = key(albumKey)
        val previous = p.getString(key, null)?.let { runCatching { JSONObject(dec(it)) }.getOrNull() }
        val count = (previous?.optInt("count") ?: 0) + 1
        val previousGain = previous?.optDouble("gain")?.toFloat() ?: 0f
        val gain = if (count <= 1) info.trackGainDb else ((previousGain * (count - 1)) + info.trackGainDb) / count
        val raw = JSONObject().put("count", count).put("gain", gain.toDouble()).toString()
        p.edit().putString(key, enc(raw)).apply()
    }

    private fun loadAlbumGain(context: Context, albumKey: String): Float? {
        if (albumKey.isBlank()) return null
        val raw = context.getSharedPreferences(ALBUM_PREFS, Context.MODE_PRIVATE).getString(key(albumKey), null) ?: return null
        return runCatching { JSONObject(dec(raw)).optDouble("gain").toFloat() }.getOrNull()
    }

    private fun key(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun enc(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun dec(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
    private fun guessExtension(path: String, fallback: String): String = path.substringBefore('?').substringAfterLast('.', fallback).takeIf { it.length in 2..5 } ?: fallback

    private const val TARGET_MEAN_DB = -18f
}
