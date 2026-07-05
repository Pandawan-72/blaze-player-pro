package fr.retrospare.blazeplayer.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Construit l'état réseau (file + votes) d'une session Blaze Party à partir des seules données
 * persistées ([BlazePartyVoteManager] + [fr.retrospare.blazeplayer.playlist.PlaylistManager]),
 * sans dépendre d'un état en mémoire côté UI (contrairement à
 * [AudioPlayerFragment.sortedBlazePartyTracks], qui tient aussi compte des morceaux déjà joués
 * dans la session d'écran courante).
 *
 * C'est ce qui permet à [BlazePlayerService] — qui continue de tourner en arrière-plan même quand
 * AudioPlayerFragment n'est pas affiché — d'exposer un état de party fiable à [PartyHostServer]
 * indépendamment du cycle de vie de l'écran.
 */
object BlazePartyQueue {

    fun buildState(context: Context, player: Player? = null): PartyState {
        val tracks = fr.retrospare.blazeplayer.playlist.PlaylistManager.getBlazePartyPlaylist(context)
            .map { track ->
                PartyTrack(
                    path = track.path,
                    name = track.name,
                    votes = BlazePartyVoteManager.voteCount(context, track.path),
                    voters = BlazePartyVoteManager.votersFor(context, track.path)
                )
            }
            .sortedByDescending { it.votes }
        val currentPath = player?.currentMediaItem
            ?.let { originalPathOf(it) }
            ?.takeIf { it.isNotBlank() }
        return PartyState(tracks, currentPath, BlazePartyVoteManager.getNickname(context))
    }

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
}
