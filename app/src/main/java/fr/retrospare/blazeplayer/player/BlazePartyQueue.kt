package fr.retrospare.blazeplayer.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Construit l'état réseau (file + votes + métadonnées) d'une session Blaze Party à partir des
 * données persistées côté hôte et, quand c'est possible, du Player audio. Le client ne peut pas
 * relire les fichiers locaux/SMB de l'hôte : tout ce qui est nécessaire à l'affichage de la file
 * partagée doit donc voyager dans [PartyState].
 */
object BlazePartyQueue {

    fun buildState(context: Context, player: Player? = null): PartyState {
        val dedicatedPartyTracks = fr.retrospare.blazeplayer.playlist.PlaylistManager.getBlazePartyPlaylist(context)
        val sourceTracks = dedicatedPartyTracks.ifEmpty {
            // Garde-fou : si l'hôte démarre/partage Blaze Party alors que la playlist Party dédiée
            // n'a pas encore été alimentée, on expose au moins la file audio actuellement chargée.
            playerQueueAsTrackRefs(player)
        }
        val playerMeta = playerMetadataByPath(player)

        val indexedTracks = sourceTracks
            .distinctBy { it.path }
            .withIndex()
            .map { indexed ->
                val track = indexed.value
                val path = track.path
                val cached = AudioMetadataExtractor.getCached(context, path)
                val fromPlayer = playerMeta[path]
                val ext = cached?.extension?.takeIf { it.isNotBlank() }
                    ?: fromPlayer?.extension?.takeIf { it.isNotBlank() }
                    ?: track.extension.takeIf { it.isNotBlank() }
                    ?: fallbackExt(track.name, path)
                val title = cached?.title?.takeIf { it.isNotBlank() }
                    ?: fromPlayer?.title?.takeIf { it.isNotBlank() }
                    ?: track.title.takeIf { it.isNotBlank() }
                    ?: track.name.substringBeforeLast('.')
                val artist = cached?.artist?.takeIf { it.isNotBlank() }
                    ?: fromPlayer?.artist?.takeIf { it.isNotBlank() }
                    ?: track.artist
                val bitrate = cached?.bitrate?.takeIf { it > 0L }
                    ?: fromPlayer?.bitrate?.takeIf { it > 0L }
                    ?: track.bitrate
                val durationMs = cached?.duration?.takeIf { it > 0L }?.times(1000L)
                    ?: fromPlayer?.duration?.takeIf { it > 0L }?.times(1000L)
                    ?: track.durationMs
                val lossless = cached?.isLossless == true || fromPlayer?.isLossless == true || track.isLossless || ext.uppercase() in LOSSLESS_EXTENSIONS

                IndexedValue(
                    index = indexed.index,
                    value = PartyTrack(
                        path = path,
                        name = track.name,
                        votes = BlazePartyVoteManager.voteCount(context, path),
                        voters = BlazePartyVoteManager.votersFor(context, path),
                        artist = artist,
                        title = title,
                        extension = ext.uppercase(),
                        bitrate = bitrate,
                        isLossless = lossless,
                        durationMs = durationMs,
                        playedOrder = BlazePartyVoteManager.playedRank(context, path)
                    )
                )
            }
            .sortedWith(
                compareBy<IndexedValue<PartyTrack>> { if (it.value.playedOrder > 0) 1 else 0 }
                    .thenByDescending { if (it.value.playedOrder == 0) it.value.votes else 0 }
                    .thenBy { if (it.value.playedOrder > 0) it.value.playedOrder else it.index }
            )

        val currentPath = player?.currentMediaItem
            ?.let { originalPathOf(it) }
            ?.takeIf { it.isNotBlank() }
        val currentDurationMs = player?.duration
            ?.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
            ?: indexedTracks.firstOrNull { it.value.path == currentPath }?.value?.durationMs
            ?: 0L
        val currentPositionMs = if (currentPath != null) {
            player?.currentPosition?.coerceAtLeast(0L)?.coerceAtMost(currentDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE) ?: 0L
        } else 0L
        return PartyState(
            tracks = indexedTracks.map { it.value },
            currentPath = currentPath,
            hostNickname = BlazePartyVoteManager.getNickname(context),
            currentPositionMs = currentPositionMs,
            currentDurationMs = currentDurationMs,
            isPlaying = player?.isPlaying == true
        )
    }

    private fun playerQueueAsTrackRefs(player: Player?): List<fr.retrospare.blazeplayer.playlist.PlaylistTrackRef> {
        if (player == null || player.mediaItemCount <= 0) return emptyList()
        return (0 until player.mediaItemCount).mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val path = originalPathOf(item).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!AudioRepository.isSupportedAudioPath(path)) return@mapNotNull null
            val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('/')
                ?: path.substringAfterLast('/')
            fr.retrospare.blazeplayer.playlist.PlaylistTrackRef(path, title)
        }
    }

    private fun playerMetadataByPath(player: Player?): Map<String, AudioTechnicalInfo> {
        if (player == null || player.mediaItemCount <= 0) return emptyMap()
        return (0 until player.mediaItemCount).mapNotNull { index ->
            val item = player.getMediaItemAt(index)
            val path = originalPathOf(item).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val meta = item.mediaMetadata
            val ext = meta.extras?.getString(AudioRepository.EXTRA_CONTAINER_EXTENSION).orEmpty()
            path to AudioTechnicalInfo(
                artist = meta.artist?.toString().orEmpty(),
                title = meta.title?.toString().orEmpty(),
                album = meta.albumTitle?.toString().orEmpty(),
                extension = ext,
                isLossless = ext.uppercase() in LOSSLESS_EXTENSIONS
            )
        }.toMap()
    }

    private fun fallbackExt(name: String, path: String): String =
        name.substringAfterLast('.', "").ifBlank { path.substringBefore('?').substringAfterLast('.', "") }.uppercase()

    /** Même logique que la fonction privée équivalente de AudioPlayerFragment, dupliquée ici
     *  volontairement pour rester utilisable depuis le service sans dépendance vers le Fragment. */
    fun originalPathOf(item: MediaItem): String {
        val fromExtras = item.mediaMetadata.extras?.getString("blaze_original_path")
            ?.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
        if (fromExtras != null) return fromExtras
        return item.mediaId.takeIf { it.isNotBlank() && AudioRepository.isAudioExtension(it) }
            ?: item.localConfiguration?.uri?.toString()?.takeIf { AudioRepository.isAudioExtension(it) }
            ?: ""
    }

    private val LOSSLESS_EXTENSIONS = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF")
}
