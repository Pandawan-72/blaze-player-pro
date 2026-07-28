package fr.retrospare.blazeplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistance durable des pochettes audio déjà résolues.
 *
 * Contrairement au cache de miniatures général, la clé ne dépend ni d'un epoch réseau ni du nom
 * réellement trouvé sur le NAS. Une fois une pochette extraite, bibliothèque, player et mini-player
 * relisent donc le même JPEG local, même après un redémarrage ou un rafraîchissement de la bibliothèque.
 */
object AudioArtworkPersistence {

    private const val DIRECTORY_NAME = "audio_artwork_persist_v2"
    private const val LEGACY_DIRECTORY_NAME = "audio_artwork_persist"
    private const val FILE_NAME = "cover.jpg"
    private const val JPEG_QUALITY = 93
    private const val MAX_DIMENSION = 512
    private const val MAX_DIRECTORY_BYTES = 300L * 1024L * 1024L
    private val legacyCleanupDone = AtomicBoolean(false)

    fun existingPath(context: Context, audioPath: String): String? {
        if (audioPath.isBlank()) return null
        val appContext = context.applicationContext
        cleanupLegacyOnce(appContext)
        val file = fileFor(appContext, audioPath)
        return file.takeIf { it.isFile && it.length() > 4L && hasCompleteJpegEnvelope(it) }
            ?.absolutePath
            ?: run {
                if (file.exists()) runCatching { file.delete() }
                null
            }
    }

    fun isPersistedPath(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            val root = rootDir(context.applicationContext).canonicalFile
            val candidate = File(path).canonicalFile
            candidate.path.startsWith(root.path + File.separator) &&
                candidate.name.equals(FILE_NAME, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun isLegacyPersistedPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val normalized = path.replace('\\', '/')
        return normalized.contains("/$LEGACY_DIRECTORY_NAME/") &&
            !normalized.contains("/$DIRECTORY_NAME/")
    }

    fun loadBitmap(context: Context, audioPath: String): Bitmap? {
        val file = existingPath(context, audioPath)?.let(::File) ?: return null
        return decodeFile(file).also { bitmap ->
            if (bitmap == null) runCatching { file.delete() }
            else runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
    }

    fun loadBitmapFromPersistedPath(context: Context, persistedPath: String): Bitmap? {
        if (!isPersistedPath(context, persistedPath)) return null
        val file = File(persistedPath)
        if (!file.isFile || file.length() <= 0L) return null
        return decodeFile(file).also { bitmap ->
            if (bitmap == null) runCatching { file.delete() }
            else runCatching { file.setLastModified(System.currentTimeMillis()) }
        }
    }

    /** Écrit atomiquement le JPEG et renvoie son chemin stable. */
    fun persist(context: Context, audioPath: String, bitmap: Bitmap): String? {
        if (audioPath.isBlank() || bitmap.width <= 0 || bitmap.height <= 0) return null
        val appContext = context.applicationContext
        val target = fileFor(appContext, audioPath)
        val parent = target.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) return null
        cleanupLegacyOnce(appContext)
        val tmp = File(parent, ".$FILE_NAME.${Thread.currentThread().id}.${System.nanoTime()}.tmp")

        var prepared: Bitmap? = null
        return try {
            val source = prepareForPersistence(bitmap)
                ?: throw IllegalStateException("Unable to create a software artwork bitmap")
            prepared = source
            FileOutputStream(tmp).use { output ->
                if (!source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IllegalStateException("Bitmap compression failed")
                }
                output.fd.sync()
            }
            replaceFileAtomically(tmp, target)
            target.setLastModified(System.currentTimeMillis())
            pruneIfNeeded(appContext)
            target.absolutePath
        } catch (error: Exception) {
            runCatching { tmp.delete() }
            Log.w("AudioArtworkPersist", "Unable to persist artwork", error)
            null
        } finally {
            prepared?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        }
    }

    private fun decodeFile(file: File): Bitmap? {
        return try {
            if (!hasCompleteJpegEnvelope(file)) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / sample > MAX_DIMENSION * 2 || bounds.outHeight / sample > MAX_DIMENSION * 2) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample.coerceAtLeast(1)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inDither = true
                    }
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun replaceFileAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hasCompleteJpegEnvelope(file: File): Boolean = runCatching {
        if (file.length() < 4L) return@runCatching false
        java.io.RandomAccessFile(file, "r").use { raf ->
            val first = raf.readUnsignedByte()
            val second = raf.readUnsignedByte()
            raf.seek(file.length() - 2L)
            val beforeLast = raf.readUnsignedByte()
            val last = raf.readUnsignedByte()
            first == 0xFF && second == 0xD8 && beforeLast == 0xFF && last == 0xD9
        }
    }.getOrDefault(false)

    private fun cleanupLegacyOnce(context: Context) {
        if (!legacyCleanupDone.compareAndSet(false, true)) return
        runCatching { File(context.filesDir, LEGACY_DIRECTORY_NAME).deleteRecursively() }
    }

    private fun prepareForPersistence(bitmap: Bitmap): Bitmap? {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
            runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
        } else {
            bitmap
        }
        val largest = maxOf(software.width, software.height)
        if (largest <= MAX_DIMENSION) return software
        val ratio = MAX_DIMENSION.toFloat() / largest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            software,
            (software.width * ratio).toInt().coerceAtLeast(1),
            (software.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (software !== bitmap && scaled !== software && !software.isRecycled) software.recycle()
        return scaled
    }

    private fun fileFor(context: Context, audioPath: String): File =
        File(File(rootDir(context), sha256(canonicalAudioPath(audioPath))), FILE_NAME)

    private fun rootDir(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

    private fun canonicalAudioPath(path: String): String = path
        .substringBefore('#')
        .trim()
        .replace('\\', '/')

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun pruneIfNeeded(context: Context) {
        runCatching {
            val root = rootDir(context)
            val files = root.listFiles()
                ?.asSequence()
                ?.flatMap { folder -> folder.listFiles()?.asSequence() ?: emptySequence() }
                ?.filter { it.isFile }
                ?.sortedBy { it.lastModified() }
                ?.toList()
                .orEmpty()
            var total = files.sumOf { it.length() }
            if (total <= MAX_DIRECTORY_BYTES) return
            for (file in files) {
                if (total <= MAX_DIRECTORY_BYTES) break
                val length = file.length()
                if (file.delete()) {
                    total -= length
                    runCatching { file.parentFile?.delete() }
                }
            }
        }
    }
}
