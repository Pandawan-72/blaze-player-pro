package fr.retrospare.blazeplayer.data.model

data class NetworkShare(
    val id: String,
    val name: String,
    val host: String,
    val shareName: String = "",
    val username: String = "",
    val password: String = "",
    val type: ShareType = ShareType.SMB
)

enum class ShareType {
    SMB,
    DLNA
}
