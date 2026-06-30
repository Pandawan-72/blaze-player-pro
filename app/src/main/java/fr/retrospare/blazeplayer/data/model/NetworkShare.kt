package fr.retrospare.blazeplayer.data.model

data class NetworkShare(
    val id: String = "",
    val name: String = "",
    val host: String = "",
    val port: Int? = null,
    val shareName: String = "",
    val username: String? = null,
    val password: String? = null,
    val type: ShareType = ShareType.SMB,
    val isDefault: Boolean = false,
    val isOnline: Boolean = false
)

enum class ShareType {
    SMB, FTP
}
