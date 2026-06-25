package fr.retrospare.blazeplayer.network

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.model.NetworkShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbBrowser @Inject constructor() {

    private val client = SMBClient()

    suspend fun listFiles(share: NetworkShare, path: String = ""): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<MediaItem>()
            try {
                val authContext = if (!share.username.isNullOrEmpty()) {
                    AuthenticationContext(share.username, (share.password ?: "").toCharArray(), "")
                } else {
                    AuthenticationContext.anonymous()
                }
                client.connect(share.host).use { connection ->
                    connection.authenticate(authContext).use { session ->
                        (session.connectShare(share.shareName) as? DiskShare)?.use { diskShare ->
                            diskShare.list(path).forEach { info ->
                                if (!info.fileName.startsWith(".")) {
                                    val ext = info.fileName.substringAfterLast('.', "").lowercase()
                                    items += MediaItem(
                                        id = "${share.host}/${share.shareName}/$path/${info.fileName}",
                                        name = info.fileName,
                                        path = "smb://${share.host}/${share.shareName}/$path/${info.fileName}",
                                        mimeType = getMimeType(ext),
                                        extension = ext,
                                        size = info.endOfFile,
                                        isNetwork = true,
                                        networkShareId = share.id
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Log error silently
            }
            items
        }

    private fun getMimeType(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2ts"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        else -> "video/*"
    }
}
