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
        // Un seul bloc de tentative recouvrant TOUTE la séquence d'ouverture (pas seulement
        // getShare()) : le DiskShare renvoyé peut devenir obsolète/fermé entre le moment où on
        // l'obtient et le moment où on l'utilise, si un autre consommateur concurrent (le lecteur
        // local, un autre relais de cast...) l'invalide entre-temps. Observé en pratique via
        // "DiskShare has already been closed" au niveau de getFileInformation(), alors que
        // getShare() lui-même avait réussi juste avant.
        fun attemptOpen(): Triple<DiskShare, SmbFile, Long> {
            val share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
            val file = share.openFile(
                parsed.filePath,
                EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java)
            )
            val size = file.getFileInformation(FileStandardInformation::class.java).endOfFile
            return Triple(share, file, size)
        }

        val (share, file, size) = try {
            attemptOpen()
        } catch (e: Exception) {
            android.util.Log.w("SmbMediaDataSource", "Ouverture échouée (share invalidé entre-temps ?), nouvelle tentative", e)
            SmbSessionPool.invalidate(parsed.host, parsed.port, parsed.username, parsed.shareName)
            attemptOpen()
        }
        diskShare = share
        smbFile = file
        fileSize = size
    }

    private var retryBudget = 25 // limite cumulée sur la durée de vie de cette instance

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val file = smbFile ?: return -1
        return try {
            file.read(buffer, position, offset, size)
        } catch (e: Exception) {
            if (retryBudget <= 0) {
                android.util.Log.e("SmbMediaDataSource", "Budget de nouvelles tentatives épuisé à la position $position, abandon")
                return -1
            }
            retryBudget--
            // Retente avec un handle frais plutôt que de renvoyer -1 immédiatement : un -1 est
            // interprété par l'appelant (MediaDataSource, utilisé par MediaExtractor) comme "fin
            // de fichier atteinte", ce qui arrêtait silencieusement la découverte des pistes
            // suivantes (audio, sous-titres) dès la moindre erreur de lecture transitoire sur un
            // accès aléatoire à un offset donné — exactement le schéma d'accès utilisé par
            // MediaExtractor/le parseur EBML pour sonder la structure d'un conteneur MKV/MP4.
            // Budget limité : observé en pratique jusqu'à 46s+ de tentatives en rafale sans lui.
            android.util.Log.w("SmbMediaDataSource", "readAt failed at position $position, nouvelle tentative avec un handle/share frais (budget restant: $retryBudget)", e)
            try {
                val share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
                val freshFile = share.openFile(
                    parsed.filePath,
                    EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java)
                )
                try { smbFile?.close() } catch (_: Exception) {}
                try { diskShare?.close() } catch (_: Exception) {}
                smbFile = freshFile
                diskShare = share
                freshFile.read(buffer, position, offset, size)
            } catch (e2: Exception) {
                android.util.Log.e("SmbMediaDataSource", "readAt a échoué même après nouvelle tentative à la position $position", e2)
                -1
            }
        }
    }

    override fun getSize(): Long = fileSize

    override fun close() {
        // DiskShare n'est plus partagé : on ferme le handle puis le share privé de cette source.
        try { smbFile?.close() } catch (e: Exception) {
            android.util.Log.e("SmbMediaDataSource", "Failed to close smbFile", e)
        }
        try { diskShare?.close() } catch (e: Exception) {
            android.util.Log.e("SmbMediaDataSource", "Failed to close diskShare", e)
        }
        smbFile = null
        diskShare = null
    }
}
