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
class SmbMediaDataSource(val originalUri: String) : MediaDataSource() {

    private val parsed = SmbDataSource.parseSmbUri(android.net.Uri.parse(originalUri))
    private var diskShare: DiskShare? = null
    private var smbFile: SmbFile? = null
    private var fileSize: Long = -1L

    init {
        if (BlazePlayerService.isAudioPlaybackActive) {
            throw java.io.InterruptedIOException("SMB metadata deferred while audio playback is active")
        }
        // Un seul bloc de tentative recouvrant TOUTE la séquence d'ouverture (pas seulement
        // getShare()) : le DiskShare renvoyé peut devenir obsolète/fermé entre le moment où on
        // l'obtient et le moment où on l'utilise, si un autre consommateur concurrent (le lecteur
        // local, un autre relais de cast...) l'invalide entre-temps. Observé en pratique via
        // "DiskShare has already been closed" au niveau de getFileInformation(), alors que
        // getShare() lui-même avait réussi juste avant.
        fun attemptOpen(): Triple<DiskShare, SmbFile, Long> {
            val share = SmbSessionPool.getShare(parsed.host, parsed.port, parsed.username, parsed.password, parsed.shareName)
            var file: SmbFile? = null
            return try {
                val openedFile = share.openFile(
                    parsed.filePath,
                    EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java)
                )
                file = openedFile
                val size = openedFile.getFileInformation(FileStandardInformation::class.java).endOfFile
                Triple(share, openedFile, size)
            } catch (error: Exception) {
                try { file?.close() } catch (_: Exception) {}
                try { share.close() } catch (_: Exception) {}
                throw error
            }
        }

        val (share, file, size) = try {
            attemptOpen()
        } catch (e: Exception) {
            if (SmbDataSource.isMissingPathError(e)) throw e
            // Une extraction de métadonnées/pochette est non critique : elle ne doit jamais
            // invalider la session/connexion partagée et couper le flux audio en cours.
            android.util.Log.w("SmbMediaDataSource", "Ouverture SMB metadata échouée, nouvelle tentative locale: ${e.message}")
            attemptOpen()
        }
        diskShare = share
        smbFile = file
        fileSize = size
    }

    private var retryBudget = 2 // éviter des dizaines de secondes de tentatives sur une cover

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        // Cette source sert uniquement aux covers/métadonnées. Une tâche commencée juste avant le
        // lancement d'un morceau doit céder immédiatement la connexion au Player.
        if (BlazePlayerService.isAudioPlaybackActive) return -1

        // MediaMetadataRetriever/MediaExtractor peuvent demander une lecture unique de plusieurs
        // Mo (sondage de conteneur MKV/MP4). Sans plafond, ça forçait smbj à allouer un paquet
        // SMB2READ de cette taille d'un bloc, ce qui a provoqué un OutOfMemoryError en combinaison
        // avec le tampon de lecture vidéo déjà actif (tas cible 256 Mo, sans largeHeap). On
        // découpe donc toute lecture trop grosse en blocs de taille raisonnable.
        if (size > MAX_SINGLE_READ_BYTES) {
            return readAtChunked(position, buffer, offset, size)
        }
        val file = smbFile ?: return -1
        return try {
            if (BlazePlayerService.isAudioPlaybackActive) return -1
            file.read(buffer, position, offset, size)
        } catch (e: Exception) {
            if (BlazePlayerService.isAudioPlaybackActive) return -1
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
            android.util.Log.w("SmbMediaDataSource", "readAt failed at position $position, tentative locale restante: $retryBudget (${e.message})")
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

    // Découpe une lecture demandée trop grosse en plusieurs appels SMB de taille bornée, pour ne
    // jamais laisser smbj allouer un seul paquet réseau de plusieurs Mo. On s'arrête au premier
    // bloc partiel ou à la fin de fichier, comme le ferait un read() classique.
    private fun readAtChunked(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        var totalRead = 0
        while (totalRead < size) {
            if (BlazePlayerService.isAudioPlaybackActive) return if (totalRead > 0) totalRead else -1
            val chunkSize = minOf(MAX_SINGLE_READ_BYTES, size - totalRead)
            val read = readAt(position + totalRead, buffer, offset + totalRead, chunkSize)
            if (read <= 0) {
                return if (totalRead > 0) totalRead else read
            }
            totalRead += read
            if (read < chunkSize) break // lecture partielle (fin de fichier probable)
        }
        return totalRead
    }

    override fun getSize(): Long = fileSize

    override fun close() {
        // DiskShare n'est plus partagé : on ferme le handle puis le share privé de cette source.
        try { smbFile?.close() } catch (_: Exception) {}
        try { diskShare?.close() } catch (_: Exception) {}
        smbFile = null
        diskShare = null
    }

    companion object {
        // Taille max d'une lecture SMB unique déclenchée par readAt. Volontairement bien en
        // dessous du buffer SMB2 négocié (SmbClientPool, 2 Mo) pour garder une marge de sécurité
        // mémoire même si plusieurs SmbMediaDataSource/SmbDataSource lisent en parallèle.
        private const val MAX_SINGLE_READ_BYTES = 1 * 1024 * 1024
    }
}
