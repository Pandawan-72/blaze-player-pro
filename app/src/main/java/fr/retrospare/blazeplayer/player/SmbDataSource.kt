package fr.retrospare.blazeplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.util.EnumSet

/**
 * DataSource Media3 permettant de streamer un fichier via SMB (protocole smb://).
 * Format URI attendu: smb://[user[:pass]@]host[:port]/share/path/to/file.ext
 * Utilise SmbSessionPool pour reutiliser connexion/session/share entre fichiers
 * (evite reconnexion + reauth a chaque vidéo/piste suivante).
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {

    private var diskShare: DiskShare? = null
    private var smbFile: SmbFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var currentPosition: Long = 0
    private var parsedUri: ParsedSmbUri? = null

    // Buffer interne pour eviter des centaines de micro-requetes SMB (1-4 octets) lors du parsing MKV/EBML.
    // On lit par blocs de 2 Mo depuis le reseau et on sert les petites lectures de Media3 depuis ce buffer.
    // 2 Mo est un bon compromis débit confortable en 4K / empreinte mémoire (aligné sur le buffer
    // SMB2 négocié dans SmbClientPool ; un buffer plus gros ici a provoqué un OutOfMemoryError en
    // combinaison avec les lectures parallèles de miniatures/métadonnées, cf. SmbClientPool).
    private val readBuffer = ByteArray(DEFAULT_READ_BUFFER_BYTES)
    private var readBufferStart: Long = -1
    private var readBufferLength: Int = 0

    private fun computeNetworkReadSize(bytesWanted: Int): Int {
        // Media3 demande souvent de très petits blocs au démarrage pour parser MP4/MKV.
        // Lire systématiquement 8 Mo bloquait le premier frame des longues vidéos sur SMB.
        // On précharge quand même assez pour lisser le débit, mais sans imposer une énorme
        // lecture réseau avant que le player puisse commencer à analyser le conteneur.
        return when {
            bytesWanted <= 64 * 1024 -> 512 * 1024
            bytesWanted <= 512 * 1024 -> 1024 * 1024
            else -> minOf(DEFAULT_READ_BUFFER_BYTES, bytesWanted * 2)
        }
    }

    private fun openSmbFile(parsed: ParsedSmbUri): Triple<DiskShare, SmbFile, Long> {
        val share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
        var file: SmbFile? = null
        return try {
            val openedFile = share.openFile(
                parsed.filePath,
                EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java)
            )
            file = openedFile
            val size = openedFile.getFileInformation(FileStandardInformation::class.java).endOfFile
            Triple(share, openedFile, size)
        } catch (error: Exception) {
            try { file?.close() } catch (_: Exception) {}
            try { share.close() } catch (_: Exception) {}
            throw error
        }
    }

    private fun reopenAfterReadFailure(error: Exception): Boolean {
        val parsed = parsedUri ?: return false
        android.util.Log.w("SmbDataSource", "SMB read failed at $currentPosition, reconnecting once", error)
        try { smbFile?.close() } catch (_: Exception) {}
        try { diskShare?.close() } catch (_: Exception) {}
        smbFile = null
        diskShare = null
        SmbSessionPool.invalidate(parsed.host, parsed.port, parsed.username, parsed.shareName)
        return try {
            val (share, file, _) = openSmbFile(parsed)
            diskShare = share
            smbFile = file
            readBufferStart = -1
            readBufferLength = 0
            true
        } catch (e: Exception) {
            android.util.Log.e("SmbDataSource", "SMB reconnect failed after read error", e)
            false
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val parsed = parseSmbUri(dataSpec.uri)
        parsedUri = parsed

        // Un seul bloc de tentative recouvrant TOUTE la séquence d'ouverture (pas seulement
        // getShare()) : le DiskShare renvoyé peut devenir obsolète/fermé entre son obtention et
        // son utilisation, si un autre consommateur concurrent (le relais HTTP de cast, une
        // extraction de sous-titres...) l'invalide entre-temps.
        fun attemptOpen(): Triple<DiskShare, SmbFile, Long> = openSmbFile(parsed)

        val (share, file, fileSize) = try {
            attemptOpen()
        } catch (e: Exception) {
            if (isMissingPathError(e)) throw e
            // Ressource potentiellement cassee (timeout, NAS redemarre, invalidée par un autre
            // consommateur concurrent...) -> on invalide et on reessaie une fois
            SmbSessionPool.invalidate(parsed.host, parsed.port, parsed.username, parsed.shareName)
            attemptOpen()
        }
        diskShare = share
        smbFile = file

        val position = dataSpec.position
        if (position > fileSize) {
            throw androidx.media3.datasource.DataSourceException(androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        currentPosition = position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else fileSize - position
        readBufferStart = -1
        readBufferLength = 0

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesWanted = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()

        // Sert depuis le buffer interne si la position demandee y est deja presente
        val bufStart = readBufferStart
        if (bufStart >= 0 && currentPosition >= bufStart && currentPosition < bufStart + readBufferLength) {
            val offsetInBuffer = (currentPosition - bufStart).toInt()
            val available = readBufferLength - offsetInBuffer
            val toCopy = minOf(available, bytesWanted)
            System.arraycopy(readBuffer, offsetInBuffer, buffer, offset, toCopy)
            currentPosition += toCopy
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
            bytesTransferred(toCopy)
            return toCopy
        }

        // Recharge le buffer interne par gros bloc depuis le reseau.
        // IMPORTANT : une exception SMB n'est PAS une fin de fichier. Avant ce correctif, on
        // renvoyait END_OF_INPUT sur timeout/connexion NAS perdue, ce qui arrêtait l'audio sans
        // erreur visible. On tente une reconnexion au même offset, puis on laisse Media3 gérer une
        // vraie IOException si la lecture reste impossible.
        var read = -2
        var attempts = 0
        var lastError: Exception? = null
        while (attempts < 3) {
            read = try {
                smbFile?.read(readBuffer, currentPosition, 0, computeNetworkReadSize(bytesWanted)) ?: -1
            } catch (e: Exception) {
                lastError = e
                if (attempts == 0 && reopenAfterReadFailure(e)) {
                    attempts++
                    continue
                }
                throw java.io.IOException("SMB read failed at position $currentPosition", e)
            }
            if (read != 0) break
            attempts++
        }

        if (read < 0) {
            if (lastError != null) throw java.io.IOException("SMB read failed at position $currentPosition", lastError)
            return C.RESULT_END_OF_INPUT
        }
        if (read == 0) {
            return C.RESULT_END_OF_INPUT
        }

        readBufferStart = currentPosition
        readBufferLength = read

        val toCopy = minOf(read, bytesWanted)
        System.arraycopy(readBuffer, 0, buffer, offset, toCopy)
        currentPosition += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        // DiskShare n'est plus partagé : on ferme le handle puis le share privé de CE DataSource,
        // sans toucher à la session/connexion mutualisée.
        try { smbFile?.close() } catch (_: Exception) {}
        try { diskShare?.close() } catch (_: Exception) {}
        smbFile = null
        diskShare = null
        transferEnded()
    }

    data class ParsedSmbUri(
        val username: String?,
        val password: String?,
        val host: String,
        val port: Int,
        val shareName: String,
        val filePath: String
    )

    companion object {
        private const val DEFAULT_READ_BUFFER_BYTES = 2 * 1024 * 1024

        fun parseSmbUri(uri: Uri): ParsedSmbUri {
            // smb://[user[:pass]@]host[:port]/share/path/to/file
            val userInfo = uri.encodedUserInfo
            var username: String? = null
            var password: String? = null
            if (!userInfo.isNullOrEmpty()) {
                val parts = userInfo.split(":", limit = 2)
                username = Uri.decode(parts.getOrNull(0).orEmpty())
                password = if (parts.size > 1) Uri.decode(parts[1]) else null
            }
            val host = uri.host ?: ""
            val port = if (uri.port != -1) uri.port else 445
            // pathSegments est déjà décodé par android.net.Uri. Le redécoder avec URLDecoder
            // transformait notamment les '+' en espaces et cassait certains noms accentués.
            val pathSegments = uri.pathSegments
            val shareName = pathSegments.getOrNull(0) ?: ""
            val filePath = pathSegments.drop(1).joinToString("\\")
            return ParsedSmbUri(username, password, host, port, shareName, filePath)
        }

        fun buildSmbUri(
            host: String,
            port: Int,
            shareName: String,
            filePath: String,
            username: String?,
            password: String?
        ): String {
            val auth = if (username.isNullOrEmpty()) "" else {
                val encodedPassword = password?.let { ":${Uri.encode(it)}" }.orEmpty()
                "${Uri.encode(username)}$encodedPassword@"
            }
            val safeHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val portPart = if (port != 445) ":$port" else ""
            val segments = buildList {
                add(shareName)
                addAll(filePath.replace('\\', '/').split('/').filter { it.isNotEmpty() })
            }.joinToString("/") { Uri.encode(it) }
            return "smb://$auth$safeHost$portPart/$segments"
        }

        fun isMissingPathError(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                val message = current.message.orEmpty().uppercase()
                if (message.contains("STATUS_OBJECT_PATH_NOT_FOUND") ||
                    message.contains("STATUS_OBJECT_NAME_NOT_FOUND") ||
                    message.contains("STATUS_NO_SUCH_FILE") ||
                    message.contains("STATUS_NOT_A_DIRECTORY")
                ) return true
                current = current.cause
            }
            return false
        }

        /** Retire user/password des URI SMB avant écriture dans logcat. */
        fun redactForLog(value: String): String {
            if (!value.startsWith("smb://", ignoreCase = true)) return value
            return runCatching {
                val parsed = Uri.parse(value)
                val safeHost = parsed.host.orEmpty().ifBlank { "unknown-host" }
                val port = if (parsed.port != -1 && parsed.port != 445) ":${parsed.port}" else ""
                "smb://$safeHost$port${parsed.encodedPath.orEmpty()}"
            }.getOrDefault("smb://<redacted>")
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
