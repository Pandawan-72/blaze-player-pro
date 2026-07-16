package fr.retrospare.blazeplayer.player

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/** Paroles locales + métadonnées utilisateur non destructives.
 *  Le tag editor Pro+ sauvegarde des overrides locaux pour l'affichage et la bibliothèque sans
 *  réécrire physiquement les fichiers audio (plus sûr avec Android/SAF/SMB). */
object AudioLocalEnhancements {
    private const val PREFS_META = "blaze_audio_metadata_overrides"

    // Cache positif partagé entre le service de lecture et l'overlay du player. Lors d'une
    // transition, le service commence la lecture du .LRC sur son dispatcher IO ; si le Fragment
    // demande le même fichier en parallèle, il rejoint le même verrou au lieu d'ouvrir deux fois
    // le NAS ou le stockage local.
    private val lyricsCache = ConcurrentHashMap<String, LocalLyrics>()
    private val lyricsLookupLocks = Array(32) { Any() }

    data class MetadataOverride(
        val title: String = "",
        val artist: String = "",
        val album: String = ""
    )

    data class LyricSegment(
        val timeMs: Long,
        val start: Int,
        val endExclusive: Int
    )

    data class LyricLine(
        val timeMs: Long,
        val text: String,
        val segments: List<LyricSegment> = emptyList()
    )

    data class LocalLyrics(
        val fileName: String,
        val isLrc: Boolean,
        val isSynced: Boolean,
        val lines: List<LyricLine>,
        val displayText: String
    ) {
        val hasContent: Boolean get() = displayText.isNotBlank() || lines.isNotEmpty()
    }

    fun key(path: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getOverride(context: Context, path: String): MetadataOverride? {
        if (path.isBlank()) return null
        val raw = context.applicationContext.getSharedPreferences(PREFS_META, Context.MODE_PRIVATE)
            .getString(key(path), null) ?: return null
        val parts = raw.split("\u0001", limit = 3)
        return MetadataOverride(
            title = parts.getOrNull(0).orEmpty(),
            artist = parts.getOrNull(1).orEmpty(),
            album = parts.getOrNull(2).orEmpty()
        ).takeIf { it.title.isNotBlank() || it.artist.isNotBlank() || it.album.isNotBlank() }
    }

    fun saveOverride(context: Context, path: String, override: MetadataOverride) {
        if (path.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_META, Context.MODE_PRIVATE)
        val clean = MetadataOverride(override.title.trim(), override.artist.trim(), override.album.trim())
        val edit = prefs.edit()
        if (clean.title.isBlank() && clean.artist.isBlank() && clean.album.isBlank()) {
            edit.remove(key(path))
        } else {
            edit.putString(key(path), listOf(clean.title, clean.artist, clean.album).joinToString("\u0001"))
        }
        edit.apply()
    }

    fun applyOverride(context: Context, path: String, title: String, artist: String, album: String): MetadataOverride {
        val override = getOverride(context, path)
        return MetadataOverride(
            title = override?.title?.takeIf { it.isNotBlank() } ?: title,
            artist = override?.artist?.takeIf { it.isNotBlank() } ?: artist,
            album = override?.album?.takeIf { it.isNotBlank() } ?: album
        )
    }

    fun findLocalLyrics(context: Context, path: String): List<LyricLine> =
        findLocalLyricsData(context, path)?.lines.orEmpty()

    fun findLocalLyricsData(context: Context, path: String): LocalLyrics? {
        if (path.isBlank()) return null
        val settings = AudioProSettings.read(context)
        if (!settings.syncedLyrics) return null
        lyricsCache[path]?.let { return it }

        val lookupIndex = (path.hashCode() and Int.MAX_VALUE) % lyricsLookupLocks.size
        return synchronized(lyricsLookupLocks[lookupIndex]) {
            lyricsCache[path]?.let { return@synchronized it }
            val loaded = findLocalLyricsDataUncached(context, path, settings)
            if (loaded != null) lyricsCache[path] = loaded
            loaded
        }
    }

    private fun findLocalLyricsDataUncached(
        context: Context,
        path: String,
        settings: AudioProSettings.Values
    ): LocalLyrics? {
        // NAS / SMB : la bibliothèque peut stocker les pistes en smb://. Dans ce cas File() et
        // MediaStore ne voient jamais le .lrc voisin. On ouvre donc directement le fichier paroles
        // dans le même dossier SMB avec la même infrastructure réseau que le lecteur.
        if (path.startsWith("smb://", true)) {
            findSmbLyrics(path, settings)?.let { return it }
        }
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) {
            findHttpLyrics(path, settings)?.let { return it }
        }

        val candidates = resolveLocalAudioFiles(context, path)

        for (file in candidates) {
            val dir = file.parentFile?.takeIf { it.exists() && it.isDirectory } ?: continue
            val lyricsFile = findBestLyricsFile(dir, file) ?: continue
            val text = readLyricsText(lyricsFile) ?: continue
            val built = buildLocalLyrics(lyricsFile.name, lyricsFile.extension.lowercase(Locale.ROOT) == "lrc", text, settings)
            if (built != null) return built
        }

        // Android 13+ peut autoriser l'audio mais refuser la lecture directe d'un fichier .lrc/.txt
        // voisin via File(). On tente donc aussi MediaStore Files dans le même dossier logique.
        val mediaStoreLyrics = findBestLyricsFromMediaStore(context, path, candidates)
        if (mediaStoreLyrics != null) {
            val text = readLyricsText(context, mediaStoreLyrics.uri)
            if (text != null) {
                return buildLocalLyrics(mediaStoreLyrics.name, mediaStoreLyrics.name.substringAfterLast('.', "").equals("lrc", ignoreCase = true), text, settings)
            }
        }
        return null
    }

    private fun buildLocalLyrics(fileName: String, isLrc: Boolean, text: String, settings: AudioProSettings.Values): LocalLyrics? {
        val parsed = if (isLrc && settings.syncedLyrics) parseLrc(text) else emptyList()
        val display = when {
            parsed.isNotEmpty() -> parsed.joinToString("\n") { it.text }
            isLrc -> lrcToPlainText(text)
            else -> plainLyricsText(text)
        }
        if (display.isBlank() && parsed.isEmpty()) return null
        return LocalLyrics(
            fileName = fileName,
            isLrc = isLrc,
            isSynced = isLrc && settings.syncedLyrics && parsed.isNotEmpty(),
            lines = if (parsed.isNotEmpty()) parsed else staticLines(display),
            displayText = display
        )
    }

    private data class CandidateLyricsName(val fileName: String, val isLrc: Boolean)

    private fun candidateLyricsNames(audioName: String): List<CandidateLyricsName> {
        val decodedName = runCatching { URLDecoder.decode(audioName.substringBefore('?'), "UTF-8") }
            .getOrDefault(audioName.substringBefore('?'))
        val audioBase = decodedName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', decodedName)
        val names = linkedMapOf<String, CandidateLyricsName>()

        fun add(name: String) {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext == "lrc" || ext == "txt") names.putIfAbsent(name.lowercase(Locale.ROOT), CandidateLyricsName(name, ext == "lrc"))
        }

        if (audioBase.isNotBlank()) {
            add("$audioBase.lrc")
            add("$audioBase.txt")
        }
        // Replis volontaires, sans supposer le nom exact du fichier. LRC reste prioritaire sur TXT.
        listOf("lyrics.lrc", "paroles.lrc", "lyric.lrc", "parole.lrc", "lyrics.txt", "paroles.txt", "lyric.txt", "parole.txt")
            .forEach { add(it) }
        return names.values.toList()
    }

    private fun findSmbLyrics(path: String, settings: AudioProSettings.Values): LocalLyrics? = runCatching {
        val uri = Uri.parse(path)
        val parsed = SmbDataSource.parseSmbUri(uri)
        if (parsed.host.isBlank() || parsed.shareName.isBlank() || parsed.filePath.isBlank()) return@runCatching null
        val folderPath = parsed.filePath.substringBeforeLast("\\", missingDelimiterValue = "")
        val audioName = parsed.filePath.substringAfterLast("\\")
        val candidates = candidateLyricsNames(audioName)
        if (candidates.isEmpty()) return@runCatching null

        var share: com.hierynomus.smbj.share.DiskShare? = null
        try {
            share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
            // Essai ultra-rapide : même nom exact que l'audio + replis standard.
            for (candidate in candidates) {
                val smbPath = if (folderPath.isBlank()) candidate.fileName else "$folderPath\\${candidate.fileName}"
                val text = readSmbTextFile(share, smbPath) ?: continue
                val built = buildLocalLyrics(candidate.fileName, candidate.isLrc, text, settings)
                if (built != null) return@runCatching built
            }

            // Si le NAS est sensible à la casse, ou si le fichier contient des accents/encodages
            // légèrement différents, on liste le dossier et on choisit le meilleur .lrc/.txt.
            for (candidate in smbLyricsNamesFromDirectory(share, folderPath, audioName)) {
                val smbPath = if (folderPath.isBlank()) candidate.fileName else "$folderPath\\${candidate.fileName}"
                val text = readSmbTextFile(share, smbPath) ?: continue
                val built = buildLocalLyrics(candidate.fileName, candidate.isLrc, text, settings)
                if (built != null) return@runCatching built
            }
        } finally {
            try { share?.close() } catch (_: Exception) {}
        }
        null
    }.getOrNull()

    private fun smbLyricsNamesFromDirectory(
        share: com.hierynomus.smbj.share.DiskShare,
        folderPath: String,
        audioName: String
    ): List<CandidateLyricsName> = runCatching {
        val normalizedBase = normalizeName(audioName.substringBeforeLast('.', audioName))
        share.list(folderPath).asSequence()
            .filter { info ->
                val name = info.fileName.orEmpty()
                val isDir = info.fileAttributes and 0x10L != 0L
                !isDir && name != "." && name != ".." && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("lrc", "txt") && info.endOfFile in 1L..512_000L
            }
            .map { info ->
                val name = info.fileName
                Triple(lyricsPriorityByBase(name, normalizedBase), name.lowercase(Locale.ROOT), CandidateLyricsName(name, name.substringAfterLast('.', "").equals("lrc", ignoreCase = true)))
            }
            .filter { it.first < 100 }
            .sortedWith(compareBy<Triple<Int, String, CandidateLyricsName>> { it.first }.thenBy { it.second })
            .map { it.third }
            .toList()
    }.getOrDefault(emptyList())

    private fun findHttpLyrics(path: String, settings: AudioProSettings.Values): LocalLyrics? = runCatching {
        val cleanUrl = path.substringBefore('#').substringBefore('?')
        val folderUrl = cleanUrl.substringBeforeLast('/', missingDelimiterValue = "")
        val audioName = cleanUrl.substringAfterLast('/')
        if (folderUrl.isBlank() || audioName.isBlank()) return@runCatching null
        for (candidate in candidateLyricsNames(audioName)) {
            val encodedName = URLEncoder.encode(candidate.fileName, "UTF-8").replace("+", "%20")
            val text = readHttpTextFile("$folderUrl/$encodedName") ?: continue
            val built = buildLocalLyrics(candidate.fileName, candidate.isLrc, text, settings)
            if (built != null) return@runCatching built
        }
        null
    }.getOrNull()

    private fun readHttpTextFile(url: String): String? {
        val connection = runCatching { URL(url).openConnection() as? java.net.HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "BlazePlayer/AudioLyrics")
            val code = connection.responseCode
            if (code !in 200..299) return null
            val length = connection.contentLengthLong
            if (length > 512_000L) return null
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > 512_000) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            decodeLyricsBytes(bytes)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readSmbTextFile(share: com.hierynomus.smbj.share.DiskShare, path: String): String? {
        val smbFile = runCatching {
            share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java)
            )
        }.getOrNull() ?: return null
        return try {
            val size = runCatching { smbFile.getFileInformation(FileStandardInformation::class.java).endOfFile }.getOrDefault(0L)
            if (size !in 1L..512_000L) return null
            val bytes = ByteArray(size.toInt())
            var total = 0
            while (total < bytes.size) {
                val read = smbFile.read(bytes, total.toLong(), total, bytes.size - total)
                if (read <= 0) break
                total += read
            }
            if (total <= 0) null else decodeLyricsBytes(bytes.copyOf(total))
        } catch (_: Exception) {
            null
        } finally {
            try { smbFile.close() } catch (_: Exception) {}
        }
    }

    private fun decodeLyricsBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return runCatching {
            when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
                else -> bytes.toString(Charsets.UTF_8)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: runCatching {
            bytes.toString(Charsets.ISO_8859_1)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }


    private fun resolveLocalAudioFiles(context: Context, path: String): List<File> {
        val result = linkedSetOf<File>()

        fun addRaw(raw: String) {
            val clean = raw.trim().removePrefix("file://")
            if (clean.isBlank()) return
            val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
            result += File(decoded)
        }

        when {
            path.startsWith("file://", true) -> Uri.parse(path).path?.let { addRaw(it) }
            path.startsWith("content://", true) -> {
                val uri = runCatching { Uri.parse(path) }.getOrNull()
                if (uri != null) {
                    queryContentDataPath(context, uri)?.let { addRaw(it) }
                    documentRawPath(uri)?.let { addRaw(it) }
                    documentPrimaryPath(uri)?.let { addRaw(it) }
                    // Dernier recours : certains providers exposent le chemin dans le dernier segment.
                    uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.contains('/') }?.let { addRaw(it) }
                }
            }
            else -> addRaw(path)
        }

        return result.filter { it.path.isNotBlank() }.distinctBy { it.absolutePath }
    }

    private fun queryContentDataPath(context: Context, uri: Uri): String? = runCatching {
        val projection = arrayOf(MediaStore.MediaColumns.DATA, OpenableColumns.DISPLAY_NAME)
        context.applicationContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val data = if (dataIndex >= 0) cursor.getString(dataIndex).orEmpty() else ""
            data.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun documentRawPath(uri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getDocumentId(uri)
        documentId.removePrefix("raw:").takeIf { it != documentId && it.startsWith("/") }
    }.getOrNull()

    private fun documentPrimaryPath(uri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getDocumentId(uri)
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2 || !parts[0].equals("primary", ignoreCase = true)) return@runCatching null
        File(android.os.Environment.getExternalStorageDirectory(), parts[1]).absolutePath
    }.getOrNull()


    private data class LyricsContentFile(val name: String, val uri: Uri)
    private data class LyricsContentMatch(val priority: Int, val sortName: String, val displayName: String, val uri: Uri)

    private fun findBestLyricsFromMediaStore(context: Context, path: String, audioFiles: List<File>): LyricsContentFile? {
        val relativePath = resolveAudioRelativePath(context, path, audioFiles) ?: return null
        val normalizedBases = audioFiles
            .map { normalizeName(it.name.substringBeforeLast('.', it.name)) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOfNotNull(resolveContentDisplayName(context, path)?.substringBeforeLast('.')?.let { normalizeName(it) }) }
        if (normalizedBases.isEmpty()) return null

        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(relativePath)
        val best = mutableListOf<LyricsContentMatch>()
        runCatching {
            context.applicationContext.contentResolver.query(collection, projection, selection, args, MediaStore.MediaColumns.DISPLAY_NAME + " ASC")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in setOf("lrc", "txt")) continue
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 1L
                    if (size !in 1L..512_000L) continue
                    val priority = normalizedBases.minOf { lyricsPriorityByBase(name, it) }
                    if (priority >= 100) continue
                    best += LyricsContentMatch(priority, name.lowercase(Locale.ROOT), name, ContentUris.withAppendedId(collection, cursor.getLong(idIndex)))
                }
            }
        }
        return best.sortedWith(compareBy<LyricsContentMatch> { it.priority }.thenBy { it.sortName })
            .firstOrNull()
            ?.let { LyricsContentFile(it.displayName, it.uri) }
    }

    private fun resolveAudioRelativePath(context: Context, path: String, audioFiles: List<File>): String? {
        if (path.startsWith("content://", true)) {
            val uri = runCatching { Uri.parse(path) }.getOrNull()
            if (uri != null) {
                queryContentRelativePath(context, uri)?.let { return it }
            }
        }
        val file = audioFiles.firstOrNull()
        if (file != null) {
            relativePathFromExternal(file)?.let { return it }
        }
        return null
    }

    private fun queryContentRelativePath(context: Context, uri: Uri): String? = runCatching {
        val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH)
        context.applicationContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            if (idx >= 0) cursor.getString(idx).orEmpty().takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun resolveContentDisplayName(context: Context, path: String): String? = runCatching {
        val uri = Uri.parse(path)
        context.applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx).orEmpty().takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun relativePathFromExternal(file: File): String? {
        val root = android.os.Environment.getExternalStorageDirectory().absoluteFile
        val parent = file.parentFile?.absoluteFile ?: return null
        val rootPath = root.path.trimEnd('/') + "/"
        val parentPath = parent.path.trimEnd('/') + "/"
        if (!parentPath.startsWith(rootPath, ignoreCase = true)) return null
        return parentPath.removePrefix(rootPath).takeIf { it.isNotBlank() }
    }

    private fun readLyricsText(context: Context, uri: Uri): String? = runCatching {
        val bytes = context.applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        decodeLyricsBytes(bytes)
    }.getOrNull()

    private fun findBestLyricsFile(dir: File, audioFile: File): File? {
        val base = audioFile.name.substringBeforeLast('.', audioFile.name)
        val normalizedBase = normalizeName(base)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.length() in 1L..512_000L && it.extension.lowercase(Locale.ROOT) in setOf("lrc", "txt") }
            ?.sortedWith(compareBy<File> { lyricsPriority(it, normalizedBase) }.thenBy { it.name.lowercase(Locale.ROOT) })
            .orEmpty()
        return files.firstOrNull { lyricsPriority(it, normalizedBase) < 100 }
    }

    private fun lyricsPriority(file: File, normalizedAudioBase: String): Int =
        lyricsPriorityByBase(file.name, normalizedAudioBase)

    private fun lyricsPriorityByBase(fileName: String, normalizedAudioBase: String): Int {
        val base = normalizeName(fileName.substringBeforeLast('.', fileName))
        val extBonus = if (fileName.substringAfterLast('.', "").equals("lrc", ignoreCase = true)) 0 else 10
        return when {
            base == normalizedAudioBase -> extBonus
            base in setOf("lyrics", "lyric", "paroles", "parole") -> 20 + extBonus
            base in setOf("folder", "cover") -> 100
            base.contains(normalizedAudioBase) && normalizedAudioBase.length >= 4 -> 30 + extBonus
            normalizedAudioBase.contains(base) && base.length >= 4 -> 40 + extBonus
            else -> 100
        }
    }

    private fun readLyricsText(file: File): String? =
        runCatching { decodeLyricsBytes(file.readBytes()) }.getOrNull()

    private fun normalizeName(value: String): String {
        val noAccent = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccent.lowercase(Locale.ROOT)
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun plainLyricsText(text: String): String = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(600)
        .joinToString("\n")

    private fun lrcToPlainText(text: String): String {
        val lineTimeRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
        val enhancedTimeRegex = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
        return text.lineSequence()
            .map { it.replace(lineTimeRegex, "").replace(enhancedTimeRegex, "").trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") }
            .take(600)
            .joinToString("\n")
    }

    private fun staticLines(display: String): List<LyricLine> {
        val preview = display.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .firstOrNull()
            .orEmpty()
        return if (preview.isBlank()) emptyList() else listOf(LyricLine(0L, preview.take(240)))
    }

    private fun parseLrc(text: String): List<LyricLine> {
        val lineTimeRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
        val enhancedTimeRegex = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
        val result = mutableListOf<LyricLine>()

        text.lineSequence().forEach { raw ->
            val lineMatches = lineTimeRegex.findAll(raw).toList()
            if (lineMatches.isEmpty()) return@forEach

            val payload = raw.replace(lineTimeRegex, "").trim()
            if (payload.isBlank()) return@forEach
            val firstLineTime = timeTagToMs(lineMatches.first())
            val parsedPayload = parseEnhancedLrcPayload(
                payload = payload,
                enhancedTimeRegex = enhancedTimeRegex,
                lineTimeMs = firstLineTime
            )
            if (parsedPayload.text.isBlank()) return@forEach

            lineMatches.forEach { match ->
                val lineTime = timeTagToMs(match)
                val offset = lineTime - firstLineTime
                result += LyricLine(
                    timeMs = lineTime,
                    text = parsedPayload.text,
                    segments = parsedPayload.segments.map { segment ->
                        segment.copy(timeMs = (segment.timeMs + offset).coerceAtLeast(lineTime))
                    }
                )
            }
        }
        return result.sortedBy { it.timeMs }.take(10_000)
    }

    private data class ParsedEnhancedPayload(
        val text: String,
        val segments: List<LyricSegment>
    )

    /**
     * Enhanced LRC utilise des balises <mm:ss.xx> devant chaque mot ou syllabe. On conserve les
     * espaces réels du texte afin de supporter aussi bien les mots séparés que les syllabes accolées.
     */
    private fun parseEnhancedLrcPayload(
        payload: String,
        enhancedTimeRegex: Regex,
        lineTimeMs: Long
    ): ParsedEnhancedPayload {
        val matches = enhancedTimeRegex.findAll(payload).toList()
        if (matches.isEmpty()) {
            return ParsedEnhancedPayload(payload.trim(), emptyList())
        }

        val textBuilder = StringBuilder()
        val rawSegments = mutableListOf<LyricSegment>()

        fun appendTimedChunk(rawChunk: String, rawTimeMs: Long, trimStart: Boolean) {
            var chunk = rawChunk.replace(Regex("[\\r\\n\\t]+"), " ")
            if (trimStart) chunk = chunk.trimStart()
            if (chunk.isBlank()) return
            if (textBuilder.isNotEmpty() && textBuilder.last().isWhitespace() && chunk.first().isWhitespace()) {
                chunk = chunk.trimStart()
            }
            val start = textBuilder.length
            textBuilder.append(chunk)
            val end = textBuilder.length
            if (end > start) {
                rawSegments += LyricSegment(
                    timeMs = rawTimeMs,
                    start = start,
                    endExclusive = end
                )
            }
        }

        // Dans les Enhanced LRC courants, le texte situé avant la première balise <...>
        // commence au time code de ligne [mm:ss.xx]. Exemple :
        // [00:05.44]Your <00:05.82>blades ...
        appendTimedChunk(
            rawChunk = payload.substring(0, matches.first().range.first),
            rawTimeMs = lineTimeMs,
            trimStart = true
        )

        matches.forEachIndexed { index, match ->
            val chunkStart = match.range.last + 1
            val chunkEndExclusive = matches.getOrNull(index + 1)?.range?.first ?: payload.length
            if (chunkStart >= chunkEndExclusive) return@forEachIndexed
            appendTimedChunk(
                rawChunk = payload.substring(chunkStart, chunkEndExclusive),
                rawTimeMs = timeTagToMs(match),
                trimStart = textBuilder.isEmpty()
            )
        }

        while (textBuilder.isNotEmpty() && textBuilder.last().isWhitespace()) {
            textBuilder.deleteCharAt(textBuilder.lastIndex)
        }
        val finalLength = textBuilder.length

        // Certains fichiers trouvés en ligne contiennent quelques time codes internes inversés.
        // Le rendu suit l'ordre du texte : on rend donc les temps monotones pour éviter qu'une
        // recherche binaire saute directement à la fin de la phrase.
        var previousTimeMs = lineTimeMs
        val normalizedSegments = rawSegments.mapNotNull { segment ->
            val start = segment.start.coerceAtMost(finalLength)
            val end = segment.endExclusive.coerceAtMost(finalLength)
            if (end <= start) return@mapNotNull null
            val normalizedTime = segment.timeMs.coerceAtLeast(previousTimeMs)
            previousTimeMs = normalizedTime
            segment.copy(
                timeMs = normalizedTime,
                start = start,
                endExclusive = end
            )
        }

        return ParsedEnhancedPayload(
            text = textBuilder.toString(),
            segments = normalizedSegments
        )
    }

    private fun timeTagToMs(match: MatchResult): Long {
        val min = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val sec = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val fracRaw = match.groupValues.getOrNull(3).orEmpty()
        val ms = when (fracRaw.length) {
            1 -> fracRaw.toLongOrNull()?.times(100) ?: 0L
            2 -> fracRaw.toLongOrNull()?.times(10) ?: 0L
            else -> fracRaw.take(3).toLongOrNull() ?: 0L
        }
        return (min * 60_000L) + (sec * 1000L) + ms
    }

    fun lineForPosition(lines: List<LyricLine>, positionMs: Long): String? {
        if (lines.isEmpty()) return null
        var best: LyricLine? = null
        for (line in lines) {
            if (line.timeMs <= positionMs + 250L) best = line else break
        }
        return best?.text ?: lines.firstOrNull()?.text
    }
}
