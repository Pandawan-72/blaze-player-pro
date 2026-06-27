package fr.retrospare.blazeplayer.network

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import fr.retrospare.blazeplayer.data.model.MediaItem
import fr.retrospare.blazeplayer.data.model.NetworkShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbBrowser @Inject constructor() {

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts",
        "m4v", "webm", "mpg", "mpeg", "3gp", "divx"
    )

    private fun createClient(): SMBClient {
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withReadTimeout(60, TimeUnit.SECONDS)
            .build()
        return SMBClient(config)
    }

    suspend fun listFiles(
        share: NetworkShare,
        path: String = ""
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = createClient()
            val authContext = buildAuthContext(share)
            val host = share.host
            val port = share.port ?: 445
            val items = mutableListOf<MediaItem>()

            client.connect(host, port).use { connection ->
                connection.authenticate(authContext).use { session ->
                    (session.connectShare(share.shareName) as? DiskShare)?.use { diskShare ->
                        val searchPath = if (path.isEmpty()) "" else path
                        val entries = diskShare.list(searchPath)

                        entries.forEach { info ->
                            val name = info.fileName
                            if (name == "." || name == "..") return@forEach
                            if (name.startsWith(".")) return@forEach

                            val fullPath = if (path.isEmpty()) name else "$path\\$name"
                            val isDir = info.fileAttributes and 0x10L != 0L
                            val ext = name.substringAfterLast('.', "").lowercase()

                            if (isDir) {
                                items.add(
                                    MediaItem(
                                        id = "smb://$host/${share.shareName}/$fullPath",
                                        name = name,
                                        path = fullPath,
                                        mimeType = "folder",
                                        extension = "",
                                        isNetwork = true,
                                        networkShareId = share.id
                                    )
                                )
                            } else if (ext in VIDEO_EXTENSIONS) {
                                val smbUri = buildSmbUri(share, fullPath)
                                items.add(
                                    MediaItem(
                                        id = smbUri,
                                        name = name,
                                        path = smbUri,
                                        size = info.endOfFile,
                                        mimeType = getMimeType(ext),
                                        extension = ext,
                                        isNetwork = true,
                                        networkShareId = share.id
                                    )
                                )
                            }
                        }
                    }
                }
            }
            items.sortWith(compareBy({ it.mimeType != "folder" }, { it.name.lowercase() }))
            items
        }
    }

    suspend fun checkConnection(share: NetworkShare): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = createClient()
            val authContext = buildAuthContext(share)
            client.connect(share.host, share.port ?: 445).use { connection ->
                connection.authenticate(authContext).use { session ->
                    session.connectShare(share.shareName) != null
                }
            }
        }.getOrDefault(false)
    }

    private fun buildAuthContext(share: NetworkShare): AuthenticationContext {
        return if (!share.username.isNullOrEmpty()) {
            AuthenticationContext(
                share.username,
                (share.password ?: "").toCharArray(),
                ""
            )
        } else {
            AuthenticationContext.anonymous()
        }
    }

    private fun buildSmbUri(share: NetworkShare, path: String): String {
        val cleanPath = path.replace("\\", "/")
        val auth = if (!share.username.isNullOrEmpty()) {
            val pass = share.password?.let { ":${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
            "${java.net.URLEncoder.encode(share.username, "UTF-8")}$pass@"
        } else ""
        val port = if (share.port != null && share.port != 445) ":${share.port}" else ""
        return "smb://$auth${share.host}$port/${share.shareName}/$cleanPath"
    }

    private fun getMimeType(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2ts"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "webm" -> "video/webm"
        else -> "video/*"
    }
}
