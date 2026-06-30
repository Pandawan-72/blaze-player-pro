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
    // On lit par blocs de 256 Ko depuis le reseau et on sert les petites lectures de Media3 depuis ce buffer.
    private val readBuffer = ByteArray(256 * 1024)
    private var readBufferStart: Long = -1
    private var readBufferLength: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val parsed = parseSmbUri(dataSpec.uri)
        parsedUri = parsed

        val share = try {
            SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
        } catch (e: Exception) {
            // Ressource potentiellement cassee (timeout, NAS redemarre...) -> on invalide et on reessaie une fois
            SmbSessionPool.invalidate(parsed.host, parsed.port, parsed.username, parsed.shareName)
            SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
        }
        diskShare = share

        val file = share.openFile(
            parsed.filePath,
            EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_SEQUENTIAL_ONLY)
        )
        smbFile = file

        val fileSize = file.getFileInformation(FileStandardInformation::class.java).endOfFile
        val position = dataSpec.position
        if (position > fileSize) {
            throw androidx.media3.datasource.DataSourceException(androidx.media3.datasource.DataSourceException.POSITION_OUT_OF_RANGE)
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

        // Recharge le buffer interne par gros bloc depuis le reseau
        var read = -2
        var attempts = 0
        while (attempts < 5) {
            read = try {
                smbFile?.read(readBuffer, currentPosition, 0, readBuffer.size) ?: -1
            } catch (e: Exception) {
                android.util.Log.e("SmbDataSource", "Read failed at position $currentPosition", e)
                parsedUri?.let { SmbSessionPool.invalidate(it.host, it.port, it.username, it.shareName) }
                -1
            }
            if (read != 0) break
            attempts++
        }

        if (read < 0) {
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
        // On ne ferme PAS diskShare/session/connection ici : ils sont mutualises via SmbSessionPool
        // et reutilises pour le fichier suivant. Seul le handle de fichier est ferme.
        try { smbFile?.close() } catch (e: Exception) {
            android.util.Log.e("SmbDataSource", "Failed to close smbFile", e)
        }
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
        fun parseSmbUri(uri: Uri): ParsedSmbUri {
            // smb://[user[:pass]@]host[:port]/share/path/to/file
            val userInfo = uri.userInfo
            var username: String? = null
            var password: String? = null
            if (!userInfo.isNullOrEmpty()) {
                val parts = userInfo.split(":", limit = 2)
                username = java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")
                password = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else null
            }
            val host = uri.host ?: ""
            val port = if (uri.port != -1) uri.port else 445
            val pathSegments = uri.path?.trim('/')?.split("/") ?: emptyList()
            val shareName = pathSegments.getOrNull(0) ?: ""
            // Decode l'URL-encoding (espaces, accents, caracteres speciaux) avant de construire le chemin SMB
            val filePath = pathSegments.drop(1).joinToString("\\") { java.net.URLDecoder.decode(it, "UTF-8") }
            return ParsedSmbUri(username, password, host, port, shareName, filePath)
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
