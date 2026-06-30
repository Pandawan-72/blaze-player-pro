package fr.retrospare.blazeplayer.player

import android.media.MediaDataSource
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.util.EnumSet

/**
 * MediaDataSource permettant a MediaMetadataRetriever de lire un fichier via SMB
 * pour generer des miniatures / extraire des metadonnees sans avoir a streamer via ExoPlayer.
 * Utilise SmbSessionPool (meme pool que SmbDataSource) pour reutiliser connexion/session/share.
 */
class SmbMediaDataSource(smbUri: String) : MediaDataSource() {

    private val parsed = SmbDataSource.parseSmbUri(android.net.Uri.parse(smbUri))
    private var diskShare: DiskShare? = null
    private var smbFile: SmbFile? = null
    private var fileSize: Long = -1L

    init {
        val share = try {
            SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
        } catch (e: Exception) {
            android.util.Log.e("SmbMediaDataSource", "getShare failed, retrying after invalidate", e)
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
        fileSize = file.getFileInformation(FileStandardInformation::class.java).endOfFile
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val file = smbFile ?: return -1
        return try {
            file.read(buffer, position, offset, size)
        } catch (e: Exception) {
            android.util.Log.e("SmbMediaDataSource", "readAt failed at position $position", e)
            SmbSessionPool.invalidate(parsed.host, parsed.port, parsed.username, parsed.shareName)
            -1
        }
    }

    override fun getSize(): Long = fileSize

    override fun close() {
        // Ne ferme pas le DiskShare (mutualise via SmbSessionPool), seulement le handle de fichier
        try { smbFile?.close() } catch (e: Exception) {
            android.util.Log.e("SmbMediaDataSource", "Failed to close smbFile", e)
        }
        smbFile = null
        diskShare = null
    }
}
