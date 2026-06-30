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
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.util.EnumSet

/**
 * DataSource Media3 permettant de streamer un fichier via SMB (protocole smb://).
 * Format URI attendu: smb://[user[:pass]@]host[:port]/share/path/to/file.ext
 * Utilise un SMBClient mutualise (SmbClientPool) et une lecture positionnelle reelle (pas de skip()).
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {

    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    private var smbFile: SmbFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var currentPosition: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val parsed = parseSmbUri(dataSpec.uri)

        val smbClient = SmbClientPool.getClient()
        val conn = smbClient.connect(parsed.host, parsed.port)
        connection = conn

        val authContext = if (!parsed.username.isNullOrEmpty()) {
            com.hierynomus.smbj.auth.AuthenticationContext(parsed.username, (parsed.password ?: "").toCharArray(), "")
        } else {
            com.hierynomus.smbj.auth.AuthenticationContext.anonymous()
        }
        val sess = conn.authenticate(authContext)
        session = sess

        val share = sess.connectShare(parsed.shareName) as DiskShare
        diskShare = share

        val file = share.openFile(
            parsed.filePath,
            EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        smbFile = file

        val fileSize = file.getFileInformation(FileStandardInformation::class.java).endOfFile
        val position = dataSpec.position
        currentPosition = position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else fileSize - position

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        // Lecture positionnelle reelle SMB - pas de skip() fragile
        val read = smbFile?.read(buffer, currentPosition, offset, bytesToRead) ?: -1
        if (read <= 0) {
            return C.RESULT_END_OF_INPUT
        }
        currentPosition += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { smbFile?.close() } catch (_: Exception) {}
        try { diskShare?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        smbFile = null
        diskShare = null
        session = null
        connection = null
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
            val filePath = pathSegments.drop(1).joinToString("\\")
            return ParsedSmbUri(username, password, host, port, shareName, filePath)
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }
}
