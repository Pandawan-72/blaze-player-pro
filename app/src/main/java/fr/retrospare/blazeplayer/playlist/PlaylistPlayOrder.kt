package fr.retrospare.blazeplayer.playlist

import java.text.Normalizer
import java.util.Locale

/** Ordre de lecture canonique des playlists sauvegardées.
 *
 * Vidéo : ordre alphabétique par nom affiché de la vidéo.
 * Audio : artiste alphabétique, album alphabétique, puis ordre de piste dans l'album.
 * Les anciennes playlists qui n'ont pas encore de numéro de piste utilisent un repli sur
 * les préfixes de fichier du type "01 - Titre", "1/12 Titre", etc.
 */
object PlaylistPlayOrder {
    fun sortedForPlayback(category: PlaylistCategory?, tracks: List<PlaylistTrackRef>): List<PlaylistTrackRef> {
        if (tracks.size <= 1) return tracks
        return when (category) {
            PlaylistCategory.AUDIO -> tracks.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<PlaylistTrackRef>> { normalized(audioArtistKey(it.value)) }
                        .thenBy { normalized(it.value.album) }
                        .thenBy { effectiveTrackNumber(it.value) }
                        .thenBy { normalized(audioTitleKey(it.value)) }
                        .thenBy { it.index }
                )
                .map { it.value }
            PlaylistCategory.LOCAL_VIDEO, PlaylistCategory.NETWORK_VIDEO -> tracks.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<PlaylistTrackRef>> { normalized(videoTitleKey(it.value)) }
                        .thenBy { it.index }
                )
                .map { it.value }
            else -> tracks
        }
    }

    private fun audioArtistKey(track: PlaylistTrackRef): String =
        track.artist.ifBlank { "zzzzzz_${audioTitleKey(track)}" }

    private fun audioTitleKey(track: PlaylistTrackRef): String =
        track.title.ifBlank { track.name }.ifBlank { track.path.substringAfterLast('/') }

    private fun videoTitleKey(track: PlaylistTrackRef): String =
        track.title.ifBlank { track.name }.ifBlank { track.path.substringAfterLast('/') }
            .substringBeforeLast('.')

    private fun effectiveTrackNumber(track: PlaylistTrackRef): Int {
        if (track.trackNumber > 0) return track.trackNumber
        return parseTrackNumber(track.title)
            ?: parseTrackNumber(track.name)
            ?: parseTrackNumber(track.path.substringAfterLast('/'))
            ?: Int.MAX_VALUE
    }

    private fun parseTrackNumber(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // "01 - Title", "1. Title", "1_ Title", "01 Title"
        Regex("""^\D*(\d{1,3})(?:\s*/\s*\d{1,3})?(?:\D|$)""")
            .find(s)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 1..999 }
            ?.let { return it }
        return null
    }

    private fun normalized(value: String): String {
        val noExt = value.substringBeforeLast('.', value)
        val n = Normalizer.normalize(noExt, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .trim()
        return n.removePrefix("the ").removePrefix("le ").removePrefix("la ").removePrefix("les ")
            .removePrefix("l'").removePrefix("un ").removePrefix("une ")
    }
}
