package fr.retrospare.blazeplayer.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.model.MediaSource
import fr.retrospare.blazeplayer.data.model.NetworkShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbBrowser @Inject constructor() {

    private val client = SMBClient()

    suspend fun listFiles(share: NetworkShare, path: String = ""): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<MediaItem>()
            try {
                val authContext = if (share.username.isNotEmpty()) {
                    AuthenticationContext(share.username, share.password.toCharArray(), "")
                } else {
                    AuthenticationContext.anonymous()
                }
                client.connect(share.host).use { connection ->
                    connection.authenticate(authContext).use { session ->
                        (session.connectShare(share.shareName) as? DiskShare)?.use { diskShare ->
                            diskShare.list(path).forEach { info ->
                                if (!info.fileName.startsWith(".")) {
                                    items += MediaItem(
                                        id = "${share.host}/${share.shareName}/$path/${info.fileName}",
                                        title = info.fileName,
                                        path = "smb://${share.host}/${share.shareName}/$path/${info.fileName}",
                                        mimeType = getMimeType(info.fileName),
                                        size = info.endOfFile,
                                        source = MediaSource.SMB
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Log error
            }
            items
        }

    private fun getMimeType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2ts"
            else -> "video/*"
        }
    }
}
