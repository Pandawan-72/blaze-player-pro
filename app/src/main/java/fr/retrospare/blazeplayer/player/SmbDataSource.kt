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
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * DataSource Media3 permettant de streamer un fichier via SMB (protocole smb://).
 * Format URI attendu: smb://[user[:pass]@]host[:port]/share/path/to/file.ext
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {

    private var client: SMBClient? = null
    private var diskShare: DiskShare? = null
    private var smbFile: SmbFile? = null
    private var inputStream: java.io.InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val parsed = parseSmbUri(dataSpec.uri)

        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(60, TimeUnit.SECONDS)
            .build()
        val smbClient = SMBClient(config)
        client = smbClient

        val connection = smbClient.connect(parsed.host, parsed.port)
        val authContext = if (!parsed.username.isNullOrEmpty()) {
            AuthenticationContext(parsed.username, (parsed.password ?: "").toCharArray(), "")
        } else {
            AuthenticationContext.anonymous()
        }
        val session = connection.authenticate(authContext)
        val share = session.connectShare(parsed.shareName) as DiskShare
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
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else fileSize - position

        inputStream = file.getInputStream()
        if (position > 0) {
            inputStream?.skip(position)
        }

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val read = inputStream?.read(buffer, offset, bytesToRead) ?: -1
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { smbFile?.close() } catch (_: Exception) {}
        try { diskShare?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        inputStream = null
        smbFile = null
        diskShare = null
        client = null
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
