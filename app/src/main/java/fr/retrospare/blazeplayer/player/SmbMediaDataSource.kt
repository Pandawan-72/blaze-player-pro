package fr.retrospare.blazeplayer.player

import android.media.MediaDataSource
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
 * MediaDataSource permettant a MediaMetadataRetriever de lire un fichier via SMB
 * pour generer des miniatures sans avoir a streamer via ExoPlayer.
 */
class SmbMediaDataSource(smbUri: String) : MediaDataSource() {

    private val parsed = SmbDataSource.parseSmbUri(android.net.Uri.parse(smbUri))
    private var client: SMBClient? = null
    private var diskShare: DiskShare? = null
    private var smbFile: SmbFile? = null
    private var inputStream: java.io.InputStream? = null
    private var fileSize: Long = -1L
    private var currentPos: Long = 0L

    init {
        val config = SmbConfig.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .withReadTimeout(15, TimeUnit.SECONDS)
            .build()
        val smbClient = SMBClient(config)
        client = smbClient
        val connection = smbClient.connect(parsed.host, parsed.port)
        val authContext = if (!parsed.username.isNullOrEmpty()) {
            AuthenticationContext(parsed.username, (parsed.password ?: "").toCharArray(), "")
        } else AuthenticationContext.anonymous()
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
        fileSize = file.getFileInformation(FileStandardInformation::class.java).endOfFile
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val file = smbFile ?: return -1
        return try {
            file.read(buffer, position, offset, size)
        } catch (e: Exception) {
            -1
        }
    }

    override fun getSize(): Long = fileSize

    override fun close() {
        try { smbFile?.close() } catch (_: Exception) {}
        try { diskShare?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        smbFile = null
        diskShare = null
        client = null
    }
}
