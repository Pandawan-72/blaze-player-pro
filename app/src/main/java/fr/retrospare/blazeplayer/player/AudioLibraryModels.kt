package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Origine d'un [LibraryTrack]. Renommé depuis l'ancien `Source` (privé à AudioLibraryActivity)
 * pour éviter toute collision de nom une fois partagé entre Repository/ViewModel/Activity.
 */
enum class LibraryTrackSource { LOCAL, NETWORK, QUEUE, SNAPSHOT }

/**
 * Modèle de domaine partagé de la bibliothèque audio (Repository -> ViewModel -> UI).
 * Équivalent exact de l'ancien `AudioLibraryActivity.Track` (mêmes champs, même sémantique),
 * simplement rendu public et indépendant de l'Activity pour pouvoir vivre dans le Repository.
 */
data class LibraryTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Débit moyen en bits/s, hydraté sans modifier les noms issus des dossiers. */
    val bitrate: Long = 0L,
    val trackNo: Int,
    val path: String,
    val addedAt: Long,
    val artworkPath: String = "",
    val source: LibraryTrackSource = LibraryTrackSource.LOCAL,
    val sourceLabel: String = "",
    val titleFromTag: Boolean = false,
    val albumFromTag: Boolean = false,
    val artistFromTag: Boolean = false,
    val container: String = "",
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
    /** Arborescence Artiste/Album/Titre utilisée pour le regroupement, distincte de l'URL. */
    val libraryPath: String = ""
)

data class LibraryAlbum(
    val key: String,
    val title: String,
    val artist: String,
    val tracks: List<LibraryTrack>,
    val artworkPath: String,
    val addedAt: Long
)

data class LibraryArtist(
    val name: String,
    val tracks: List<LibraryTrack>,
    val albums: Int
)

/**
 * Toute la logique d'inférence titre/artiste/album depuis un nom de fichier ou un chemin, la
 * normalisation de tri, et le regroupement en albums/artistes. Extrait verbatim de
 * AudioLibraryActivity : aucune de ces fonctions n'a de raison de changer de comportement, elles
 * sont donc copiées telles quelles plutôt que réécrites, pour ne prendre aucun risque de
 * régression sur des heuristiques déjà éprouvées.
 *
 * Seule différence avec l'original : les quelques fonctions qui utilisaient `getString()` (donc
 * implicitement l'Activity) prennent maintenant un [Context] explicite.
 */
object AudioLibraryHeuristics {

    /** Métadonnées d’affichage audio dérivées uniquement du chemin. Aucune lecture du fichier
     *  audio n’est nécessaire : titre = nom du fichier, album = dossier contenant le titre,
     *  artiste = dossier parent de l’album. */
    data class FolderMetadata(
        val title: String,
        val artist: String,
        val album: String
    )

    fun folderMetadata(path: String, displayName: String = ""): FolderMetadata {
        // Pour un chemin de fichier normal, le nom réel du fichier reste la source de vérité :
        // `displayName` peut déjà contenir un ancien titre ID3 fourni par Media3. Pour content://,
        // où le dernier segment de l'URI est souvent opaque, le nom d'affichage reste nécessaire.
        val pathName = fileNameFromPath(path)
        val fileName = when {
            path.startsWith("content://", ignoreCase = true) && displayName.isNotBlank() -> displayName
            pathName.isNotBlank() -> pathName
            else -> displayName
        }
        val canReadFolders = !path.startsWith("content://", ignoreCase = true)
        return FolderMetadata(
            title = inferTitleFromName(fileName).ifBlank { cleanName(fileName.substringBeforeLast('.', fileName)) },
            artist = if (canReadFolders) artistFolderNameFromPath(path) else "",
            album = if (canReadFolders) albumFolderNameFromPath(path) else ""
        )
    }

    fun structuralPath(track: LibraryTrack): String =
        track.libraryPath.ifBlank { track.path }

    fun applyFolderMetadata(track: LibraryTrack): LibraryTrack {
        val structure = structuralPath(track)
        val folder = folderMetadata(
            structure,
            fileNameFromPath(structure).ifBlank { fileNameFromPath(track.path) }
        )
        return track.copy(
            title = folder.title.ifBlank { track.title },
            artist = folder.artist.ifBlank { track.artist },
            album = folder.album.ifBlank { track.album },
            titleFromTag = false,
            artistFromTag = false,
            albumFromTag = false
        )
    }

    val audioExtensions = setOf(
        "mp3", "flac", "aac", "ogg", "oga", "opus", "wav", "m4a", "m4b",
        "wma", "ape", "dts", "ac3", "mka", "wv", "aiff", "alac"
    )
    val coverExtensions = setOf("jpg", "jpeg", "png", "webp")
    val coverBaseNames = listOf("cover")

    fun belongsToLocalFolder(path: String, folder: fr.retrospare.blazeplayer.player.AudioProSettings.WatchedFolder): Boolean {
        val cleanPath = path.removePrefix("file://").trimEnd('/')
        val folderPath = folder.path.removePrefix("file://").trimEnd('/')
        return cleanPath == folderPath || cleanPath.startsWith("$folderPath/")
    }

    fun belongsToNetworkFolder(path: String, folder: fr.retrospare.blazeplayer.player.AudioProSettings.WatchedFolder): Boolean {
        if (!folder.isNetwork) return false
        val cleanPath = normalizeNetworkPath(path)
        val folderPath = normalizeNetworkPath(folder.path)
        if (folderPath.isBlank()) return true
        val candidates = linkedSetOf(folderPath).apply {
            val folderName = normalizeNetworkPath(folder.name)
            if (folderName.isNotBlank()) add(folderName)
            val shareName = normalizeNetworkPath(folder.shareName.ifBlank { folder.name })
            if (shareName.isNotBlank() && folderPath.startsWith("$shareName/")) add(folderPath.removePrefix("$shareName/").trimStart('/'))
            folderPath.substringAfter("://", folderPath).takeIf { it != folderPath }?.let { add(it.trimStart('/')) }
        }.filter { it.isNotBlank() }
        return candidates.any { candidate ->
            cleanPath == candidate ||
                cleanPath.startsWith("$candidate/") ||
                cleanPath.endsWith("/$candidate") ||
                cleanPath.contains("/$candidate/")
        }
    }

    fun normalizeNetworkPath(value: String): String = runCatching { Uri.decode(value) }.getOrDefault(value)
        .substringBefore('?')
        .replace('\\', '/')
        .trim()
        .trimEnd('/')
        .removePrefix("smb://")
        .removePrefix("http://")
        .removePrefix("https://")
        .trimStart('/')

    fun isNetworkPath(path: String): Boolean = path.startsWith("smb://", true) || path.startsWith("http://", true) || path.startsWith("https://", true)

    fun isAudioItem(ext: String, mime: String, path: String): Boolean =
        ext.lowercase(Locale.getDefault()) in audioExtensions || mime.startsWith("audio/", true) || AudioRepository.isAudioExtension(path)

    fun isImagePath(path: String): Boolean =
        path.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase(Locale.getDefault()) in coverExtensions

    fun isAudioPath(path: String): Boolean =
        path.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase(Locale.getDefault()) in audioExtensions

    /**
     * Une albumArtURI UPnP peut être une URL HTTP sans extension. Dans un champ artworkPath,
     * cette référence reste une vraie pochette même si isImagePath() ne peut pas la reconnaître.
     */
    fun isArtworkReference(path: String): Boolean =
        isImagePath(path) ||
            (
                (path.startsWith("http://", true) || path.startsWith("https://", true)) &&
                    !isAudioPath(path)
            )

    fun pickNetworkFolderCover(items: List<fr.retrospare.blazeplayer.data.model.MediaItem>): String = items
        .filter { isImagePath(it.path) && isPreferredCoverName(it.name.ifBlank { fileNameFromPath(it.path) }) }
        .sortedWith(compareBy<fr.retrospare.blazeplayer.data.model.MediaItem> { coverPriority(it.name.ifBlank { fileNameFromPath(it.path) }) }.thenBy { normalize(it.name) })
        .firstOrNull()?.path.orEmpty()

    private val preferredCoverBases = listOf(
        "cover", "folder", "front", "poster", "default", "jacket",
        "album", "albumart", "artwork", "jaquette", "pochette"
    )

    fun isPreferredCoverName(name: String): Boolean {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in coverExtensions) return false
        val base = fileName.substringBeforeLast('.', fileName)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .lowercase(Locale.ROOT)
        return preferredCoverBases.any {
            base == it || base.startsWith("$it ")
        }
    }

    fun coverPriority(name: String): Int {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val base = fileName.substringBeforeLast('.', fileName)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .lowercase(Locale.ROOT)
        val index = preferredCoverBases.indexOfFirst {
            base == it || base.startsWith("$it ")
        }
        return if (index >= 0) index else 100
    }

    fun albumDirectoryIdentity(path: String): String {
        val segments = pathSegments(path).dropLast(1).toMutableList()
        while (segments.isNotEmpty() && isDiscFolderName(segments.last())) {
            segments.removeAt(segments.lastIndex)
        }
        return segments.joinToString("/") { normalize(it) }.trim('/')
    }

    /**
     * Identité stable d'un album dans la bibliothèque : le dossier qui contient les fichiers.
     * Les tags ALBUM/ARTIST ne participent plus au regroupement, afin qu'une extraction tardive ne
     * renomme pas, ne fusionne pas et ne déplace pas les cartes déjà affichées.
     */
    fun albumKey(track: LibraryTrack): String {
        val directoryKey = albumDirectoryIdentity(structuralPath(track))
        return "folder:${directoryKey.ifBlank { canonicalPathKey(track.path) }}"
    }

    /** Nom affiché dans « Mes albums » : nom du dossier contenant les titres de l'album. */
    fun bestAlbumTitle(tracks: List<LibraryTrack>): String = dominantPathValue(
        tracks.map { albumFolderNameFromPath(structuralPath(it)) }.filter { it.isNotBlank() }
    )

    /** Nom d'artiste affiché : nom du dossier parent du dossier de l'album. */
    fun bestAlbumArtist(tracks: List<LibraryTrack>): String = dominantPathValue(
        tracks.map { artistFolderNameFromPath(structuralPath(it)) }.filter { it.isNotBlank() }
    )

    private fun dominantPathValue(values: List<String>): String {
        if (values.isEmpty()) return ""
        val grouped = values.groupBy { normalize(it) }
        val dominant = grouped.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenBy { it.key })
            .firstOrNull()
            ?.value
            .orEmpty()
        return dominant.firstOrNull().orEmpty()
    }

    fun bestArtworkPath(tracks: List<LibraryTrack>): String =
        tracks.firstNotNullOfOrNull { track ->
            track.artworkPath.takeIf(::isArtworkReference)
        }
        ?: tracks.firstNotNullOfOrNull { t -> t.artworkPath.takeIf { it.isNotBlank() } }
        ?: tracks.firstOrNull()?.path.orEmpty()

    fun albumFolderNameFromPath(path: String): String = pathSegments(path).dropLast(1).lastOrNull { !isDiscFolderName(it) }.orEmpty().let { cleanName(it) }

    fun artistFolderNameFromPath(path: String): String {
        val segments = pathSegments(path).dropLast(1).filterNot { isDiscFolderName(it) }
        if (segments.size < 2) return ""
        return cleanName(segments.getOrNull(segments.size - 2).orEmpty())
    }

    fun pathSegments(path: String): List<String> = runCatching {
        val raw = Uri.decode(path.substringBefore('?').substringBefore('#'))
        raw.replace('\\', '/').split('/').filter { it.isNotBlank() }
            .filterNot { it.equals("storage", true) || it.equals("emulated", true) || it == "0" || it.startsWith("smb:", true) || it.startsWith("http", true) }
    }.getOrDefault(emptyList())

    fun inferAlbumFromPath(path: String, fallback: String): String = albumFolderNameFromPath(path).takeIf { it.isNotBlank() && !isWeakAlbum(it) } ?: fallback

    fun inferArtistFromPath(path: String, fallback: String): String = artistFolderNameFromPath(path).takeIf { it.isNotBlank() && !isWeakArtist(it) } ?: fallback

    fun inferArtistFromFile(file: File, root: File): String {
        val rel = runCatching { file.relativeTo(root).path.replace('\\', '/') }.getOrDefault(file.name)
        val parts = rel.split('/').filter { it.isNotBlank() }
        return when {
            parts.size >= 3 -> cleanName(parts[parts.size - 3])
            parts.size >= 2 -> cleanName(root.name)
            else -> ""
        }
    }

    fun inferAlbumFromFile(file: File, root: File, fallback: String): String {
        val parent = file.parentFile?.name.orEmpty()
        if (parent.isNotBlank() && !isDiscFolderName(parent)) return cleanName(parent)
        return root.name.ifBlank { fallback }
    }

    fun inferArtistFromName(name: String): String {
        val clean = name.substringBeforeLast('.', name)
        val patterns = listOf(" - ", " – ", " — ")
        patterns.forEach { sep ->
            val idx = clean.indexOf(sep)
            if (idx in 1..80) return clean.substring(0, idx).trim()
        }
        return ""
    }

    fun inferTitleFromName(name: String): String = name.substringBeforeLast('.', name)
        .replace(Regex("^\\s*\\d{1,3}[\\s._-]+"), "")
        .replace('_', ' ')
        .trim()
        .ifBlank { name }

    fun inferTrackNo(name: String): Int = Regex("^\\s*(\\d{1,3})").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    fun normalizedTrackNo(value: Int): Int {
        val raw = if (value > 1000) value % 1000 else value
        return raw.takeIf { it > 0 } ?: Int.MAX_VALUE
    }

    /** Ordre partagé entre le détail d'album, la file créée par « Lire l'album » et les listes.
     *  Désactiver le tri par numéro produit un ordre alphabétique stable au lieu de laisser
     *  l'ordre SQL ou réseau décider de façon imprévisible. */
    fun sortAlbumTracks(input: List<LibraryTrack>, byTrackNumber: Boolean): List<LibraryTrack> {
        val unique = input.distinctBy { canonicalPathKey(it.path) }
        return if (byTrackNumber) {
            unique.sortedWith(
                compareBy<LibraryTrack> { discNumberFromPath(structuralPath(it)) }
                    .thenBy { normalizedTrackNo(it.trackNo) }
                    .thenBy { normalize(it.title) }
                    .thenBy { normalize(fileNameFromPath(it.path)) }
            )
        } else {
            unique.sortedWith(
                compareBy<LibraryTrack> { normalize(it.title) }
                    .thenBy { normalize(fileNameFromPath(it.path)) }
            )
        }
    }

    fun discNumberFromPath(path: String): Int = pathSegments(path).firstNotNullOfOrNull { segment -> Regex("(?:cd|disc|disk|disque)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(segment)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0

    fun isDiscFolderName(value: String): Boolean = Regex("^(?:cd|disc|disk|disque|vol(?:ume)?)\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())

    fun isWeakArtist(value: String): Boolean {
        val n = normalize(value)
        return n.isBlank() || n in setOf(
            "unknown", "<unknown>", "unknown artist", "artiste inconnu", "various artists",
            "various artist", "artistes multiples", "multiple artists", "various", "compilation",
            "music", "musique", "audio", "local", "network", "nas"
        )
    }

    fun isWeakAlbum(value: String): Boolean {
        val n = normalize(value)
        return n.isBlank() || n in setOf(
            "unknown", "<unknown>", "album inconnu", "unknown album", "music", "musique",
            "audio", "local", "network", "nas"
        )
    }

    fun normalizeArtistSort(value: String): String = normalize(value).removePrefix("the ").removePrefix("les ").removePrefix("la ").removePrefix("le ")

    fun normalize(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun cleanName(value: String): String = Uri.decode(value).replace('_', ' ').trim().trim('-', '–', '—', ' ')

    fun fileNameFromPath(path: String): String {
        val decoded = runCatching {
            Uri.decode(path.substringBefore('?').substringBefore('#')).replace('\\', '/')
        }.getOrDefault(path.substringBefore('?').substringBefore('#').replace('\\', '/'))
        return decoded.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: runCatching { Uri.parse(path).lastPathSegment }.getOrNull().orEmpty()
    }

    fun containerFrom(preferred: String?, path: String): String {
        val cleaned = preferred.orEmpty()
            .substringAfterLast('/', preferred.orEmpty())
            .substringAfterLast('.', preferred.orEmpty())
            .trim()
            .trimStart('.')
            .uppercase(Locale.getDefault())
        if (cleaned.isNotBlank() && cleaned.length <= 8) return cleaned
        return path.substringBefore('?')
            .substringAfterLast('.', "")
            .uppercase(Locale.getDefault())
            .takeIf { it.length <= 8 }
            .orEmpty()
    }

    fun containerLabel(track: LibraryTrack): String = containerFrom(track.container, track.path)

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun splitArtists(value: String): List<String> = value
        .split(";", ",", " feat. ", " ft. ", " featuring ", " & ")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    fun metadataArtistNamesForTrack(track: LibraryTrack): List<String> {
        if (!track.artistFromTag || isWeakArtist(track.artist)) return emptyList()
        return splitArtists(track.artist)
            .map { cleanName(it) }
            .filter { it.isNotBlank() && !isWeakArtist(it) }
            .distinctBy { normalize(it) }
    }

    fun primaryArtistSortKey(track: LibraryTrack): String = normalizeArtistSort(metadataArtistNamesForTrack(track).firstOrNull() ?: track.artist)

    fun canonicalPathKey(path: String): String = normalizeNetworkPath(path).ifBlank { path.trim() }.lowercase(Locale.getDefault())

    /** Nécessite un Context pour lire les dossiers surveillés courants. */
    fun belongsToAnyWatchedFolder(context: Context, track: LibraryTrack): Boolean =
        fr.retrospare.blazeplayer.player.AudioProSettings.watchedFolders(context).any { folder ->
            if (folder.isNetwork) {
                belongsToNetworkFolder(track.path, folder) ||
                    belongsToNetworkFolder(structuralPath(track), folder)
            } else {
                belongsToLocalFolder(track.path, folder)
            }
        }

    fun trackCompletenessScore(track: LibraryTrack): Int {
        var score = 0
        if (track.titleFromTag && track.title.isNotBlank()) score += 8
        if (track.artistFromTag && !isWeakArtist(track.artist)) score += 16
        if (track.albumFromTag && !isWeakAlbum(track.album)) score += 16
        if (track.durationMs > 0L) score += 4
        if (track.bitrate > 0L) score += 2
        if (track.trackNo > 0) score += 3
        if (containerLabel(track).isNotBlank()) score += 2
        if (track.artworkPath.isNotBlank()) score += 1
        return score
    }

    /** Ne garde qu'un exemplaire par chemin canonique (le plus complet), et uniquement les
     *  titres qui appartiennent encore à un dossier surveillé actuel. */
    fun canonicalLibraryTracks(context: Context, input: List<LibraryTrack>): List<LibraryTrack> {
        // watchedFolders() décode la configuration persistée. L'ancienne version la rappelait pour
        // chaque titre via belongsToAnyWatchedFolder(), ce qui transformait une restauration de
        // quelques milliers de pistes en milliers de lectures/parsing identiques et expliquait le
        // long écran « Chargement ». La configuration est désormais lue une seule fois par passe.
        val watchedFolders = fr.retrospare.blazeplayer.player.AudioProSettings.watchedFolders(context)
        return input
            .asSequence()
            .filter { it.path.isNotBlank() }
            .map(::applyFolderMetadata)
            .filter { it.source != LibraryTrackSource.QUEUE }
            .groupBy { canonicalPathKey(it.path) }
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<LibraryTrack> { trackCompletenessScore(it) }.thenBy { it.addedAt }
                )
            }
            .filter { track ->
                watchedFolders.any { folder ->
                    if (folder.isNetwork) {
                        belongsToNetworkFolder(track.path, folder) ||
                            belongsToNetworkFolder(structuralPath(track), folder)
                    } else {
                        belongsToLocalFolder(track.path, folder)
                    }
                }
            }
            .toList()
    }

    fun mergeTracks(a: List<LibraryTrack>, b: List<LibraryTrack>, context: Context): List<LibraryTrack> {
        val map = LinkedHashMap<String, LibraryTrack>()
        (a + b).forEach { incoming ->
            if (incoming.path.isBlank() || incoming.source == LibraryTrackSource.QUEUE) return@forEach
            val existing = map[incoming.path]
            map[incoming.path] = when {
                existing == null -> incoming
                trackCompletenessScore(incoming) > trackCompletenessScore(existing) -> incoming.copy(
                    addedAt = maxOf(incoming.addedAt, existing.addedAt),
                    artworkPath = incoming.artworkPath.ifBlank { existing.artworkPath },
                    bitrate = incoming.bitrate.takeIf { it > 0L } ?: existing.bitrate,
                    sizeBytes = incoming.sizeBytes.takeIf { it > 0L } ?: existing.sizeBytes,
                    modifiedAt = incoming.modifiedAt.takeIf { it > 0L } ?: existing.modifiedAt,
                    libraryPath = incoming.libraryPath.ifBlank { existing.libraryPath }
                )
                trackCompletenessScore(incoming) == trackCompletenessScore(existing) -> existing.copy(
                    addedAt = maxOf(incoming.addedAt, existing.addedAt),
                    artworkPath = existing.artworkPath.ifBlank { incoming.artworkPath },
                    bitrate = existing.bitrate.takeIf { it > 0L } ?: incoming.bitrate,
                    container = existing.container.ifBlank { incoming.container },
                    sizeBytes = existing.sizeBytes.takeIf { it > 0L } ?: incoming.sizeBytes,
                    modifiedAt = existing.modifiedAt.takeIf { it > 0L } ?: incoming.modifiedAt,
                    libraryPath = existing.libraryPath.ifBlank { incoming.libraryPath }
                )
                else -> existing.copy(
                    bitrate = existing.bitrate.takeIf { it > 0L } ?: incoming.bitrate,
                    libraryPath = existing.libraryPath.ifBlank { incoming.libraryPath }
                )
            }
        }
        return canonicalLibraryTracks(context, map.values.toList())
    }

    fun pruneMissingLocalTracks(current: List<LibraryTrack>, localFolders: List<fr.retrospare.blazeplayer.player.AudioProSettings.WatchedFolder>): List<LibraryTrack> {
        if (current.isEmpty()) return current
        return current.filter { track ->
            val localPath = track.path.removePrefix("file://")
            val isWatchedLocal = localFolders.any { belongsToLocalFolder(localPath, it) }
            val localLibraryTrack = isWatchedLocal || (track.source == LibraryTrackSource.LOCAL && !isNetworkPath(track.path))
            if (!localLibraryTrack) return@filter true
            File(localPath).exists()
        }
    }

    fun pruneConfirmedNetworkFolders(
        current: List<LibraryTrack>,
        confirmedFolders: List<fr.retrospare.blazeplayer.player.AudioProSettings.WatchedFolder>,
        liveTracks: List<LibraryTrack>
    ): List<LibraryTrack> {
        if (current.isEmpty() || confirmedFolders.isEmpty()) return current
        val livePaths = liveTracks
            .asSequence()
            .map { normalizeNetworkPath(it.path) }
            .filter { it.isNotBlank() }
            .toHashSet()
        return current.filter { track ->
            val networkTrack = track.source == LibraryTrackSource.NETWORK || isNetworkPath(track.path)
            if (!networkTrack) return@filter true
            val inConfirmedFolder = confirmedFolders.any {
                belongsToNetworkFolder(track.path, it) ||
                    belongsToNetworkFolder(structuralPath(track), it)
            }
            if (!inConfirmedFolder) return@filter true
            normalizeNetworkPath(track.path) in livePaths
        }
    }
}
