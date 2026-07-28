package fr.retrospare.blazeplayer.player

import android.content.Context
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshots binaires rapides de la bibliothèque audio.
 *
 * Deux niveaux sont persistés :
 * - une projection légère contenant une piste représentative par album, afin de rendre la grille
 *   immédiatement sans attendre tous les titres ;
 * - le snapshot complet, restauré ensuite sur un thread d'arrière-plan.
 *
 * Room reste la source durable et vérifiable, mais n'est jamais nécessaire au premier rendu quand
 * ces fichiers existent.
 */
object AudioLibraryBootstrapStore {
    private const val FULL_MAGIC = 0x424C5A41 // "BLZA"
    private const val ALBUM_MAGIC = 0x424C5A42 // "BLZB"
    private const val VERSION = 3

    private const val FULL_FILE_NAME = "audio_library_bootstrap_v3.bin"
    private const val FULL_TEMP_FILE_NAME = "audio_library_bootstrap_v3.tmp"
    private const val ALBUM_FILE_NAME = "audio_library_albums_bootstrap_v3.bin"
    private const val ALBUM_TEMP_FILE_NAME = "audio_library_albums_bootstrap_v3.tmp"
    private const val STATE_PREFS = "audio_library_bootstrap_state"
    private const val KEY_DIRTY = "dirty"

    private const val MAX_TRACKS = 500_000
    private const val MAX_ALBUMS = 50_000
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024
    private const val MAX_FILE_BYTES = 256L * 1024L * 1024L

    private val albumRestoreLock = Any()
    private val fullRestoreLock = Any()
    @Volatile private var albumRestoreAttempted = false
    @Volatile private var fullRestoreAttempted = false
    private val saveMutex = Mutex()
    private val dirtyGeneration = AtomicLong(0L)
    private val activeDirtySessions = AtomicInteger(0)

    /**
     * Restaure uniquement les modèles nécessaires à la grille Albums. Cette méthode peut être
     * appelée par plusieurs chemins : une seule lecture réelle sera effectuée par processus.
     */
    fun restoreAlbumBootstrapBlocking(context: Context): Boolean {
        val current = AudioLibraryMemoryStore.current()
        if (current.tracks.isNotEmpty()) return true
        synchronized(albumRestoreLock) {
            val afterLock = AudioLibraryMemoryStore.current()
            if (afterLock.tracks.isNotEmpty()) return true
            if (albumRestoreAttempted) return false
            albumRestoreAttempted = true

            val appContext = context.applicationContext
            // Même règle que pour le snapshot complet : si Room contient des métadonnées ou des
            // covers plus récentes, ne pas afficher brièvement une projection album obsolète.
            if (isDirty(appContext)) return false
            val file = albumSnapshotFile(appContext)
            if (!isValidSnapshotFile(file)) return false
            val tracks = runCatching { readTracks(file, ALBUM_MAGIC, MAX_ALBUMS) }
                .getOrNull()
                .orEmpty()
            if (tracks.isEmpty()) return false
            // Un scan peut avoir publié la nouvelle bibliothèque pendant la lecture du fichier.
            // Dans ce cas l'ancien bootstrap ne doit jamais effacer les dossiers fraîchement ajoutés.
            if (AudioLibraryMemoryStore.current().tracks.isNotEmpty()) return true
            AudioLibraryMemoryStore.replace(
                tracks = tracks,
                origin = AudioLibrarySnapshotOrigin.DISK_BOOTSTRAP,
                ready = false
            )
            return true
        }
    }

    /**
     * Restaure le snapshot complet. Le travail est bloquant pour l'appelant, mais il est désormais
     * invoqué uniquement depuis les dispatchers d'arrière-plan du warmup/repository.
     */
    fun restoreBlocking(context: Context): Boolean {
        val current = AudioLibraryMemoryStore.current()
        if (current.ready && current.tracks.isNotEmpty()) return true
        synchronized(fullRestoreLock) {
            val afterLock = AudioLibraryMemoryStore.current()
            if (afterLock.ready && afterLock.tracks.isNotEmpty()) return true
            if (fullRestoreAttempted) return false
            fullRestoreAttempted = true

            val appContext = context.applicationContext
            if (isDirty(appContext)) return false
            val file = fullSnapshotFile(appContext)
            if (!isValidSnapshotFile(file)) return false
            val tracks = runCatching { readTracks(file, FULL_MAGIC, MAX_TRACKS) }
                .getOrNull()
                .orEmpty()
            if (tracks.isEmpty()) return false
            val latest = AudioLibraryMemoryStore.current()
            val latestComesFromLiveScan = latest.origin in setOf(
                AudioLibrarySnapshotOrigin.MEDIASTORE,
                AudioLibrarySnapshotOrigin.LOCAL_SCAN,
                AudioLibrarySnapshotOrigin.NETWORK_SCAN,
                AudioLibrarySnapshotOrigin.FULL_SCAN,
                AudioLibrarySnapshotOrigin.FOLDER_CHANGE
            )
            if (latestComesFromLiveScan) return true
            if (latest.ready && latest.tracks.isNotEmpty()) return true
            AudioLibraryMemoryStore.replace(
                tracks = tracks,
                origin = AudioLibrarySnapshotOrigin.DISK_BOOTSTRAP,
                ready = true
            )
            return true
        }
    }

    suspend fun save(context: Context, snapshot: AudioLibrarySnapshot) =
        withContext(AudioLibraryBackgroundDispatchers.io) {
        if (!snapshot.ready || snapshot.tracks.isEmpty()) return@withContext
        val appContext = context.applicationContext

        saveMutex.withLock {
            val generationAtStart = dirtyGeneration.get()
            val latest = AudioLibraryMemoryStore.current().takeIf {
                it.ready && it.tracks.isNotEmpty()
            } ?: snapshot

            // Le snapshot complet est prioritaire. Son écriture reste atomique. Le marqueur dirty
            // n'est effacé qu'après le remplacement réussi du fichier complet et uniquement si
            // aucune nouvelle métadonnée n'a été publiée pendant l'écriture.
            val fullSaved = runCatching {
                writeSnapshotAtomic(
                    target = fullSnapshotFile(appContext),
                    temp = File(appContext.filesDir, FULL_TEMP_FILE_NAME),
                    magic = FULL_MAGIC,
                    tracks = latest.tracks.asSequence().take(MAX_TRACKS).toList()
                )
            }.isSuccess
            if (
                fullSaved &&
                activeDirtySessions.get() == 0 &&
                dirtyGeneration.get() == generationAtStart
            ) {
                appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DIRTY, false)
                    .apply()
            }

            // Une piste représentative par album suffit pour construire immédiatement les tuiles,
            // compteurs et artistes. Cette projection est beaucoup plus petite que le fichier complet.
            val albumTracks = latest.albumKeysSorted.asSequence()
                .mapNotNull { key -> latest.albumsByKey[key]?.tracks?.firstOrNull() }
                .take(MAX_ALBUMS)
                .toList()
            if (albumTracks.isNotEmpty()) {
                runCatching {
                    writeSnapshotAtomic(
                        target = albumSnapshotFile(appContext),
                        temp = File(appContext.filesDir, ALBUM_TEMP_FILE_NAME),
                        magic = ALBUM_MAGIC,
                        tracks = albumTracks
                    )
                }
            }
        }
    }

    /**
     * Signale qu'une écriture Room plus récente que le snapshot binaire existe. Tant qu'un nouveau
     * snapshot complet n'a pas été écrit, le prochain démarrage contournera le fichier obsolète et
     * restaurera les durées/bitrates directement depuis Room.
     */
    fun beginDirtySession(context: Context) {
        activeDirtySessions.incrementAndGet()
        dirtyGeneration.incrementAndGet()
        context.applicationContext
            .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DIRTY, true)
            .commit()
    }

    fun endDirtySession() {
        if (activeDirtySessions.decrementAndGet() < 0) {
            activeDirtySessions.set(0)
        }
    }

    private fun isDirty(context: Context): Boolean =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIRTY, false)

    private fun writeSnapshotAtomic(
        target: File,
        temp: File,
        magic: Int,
        tracks: List<LibraryTrack>
    ) {
        runCatching { temp.delete() }
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp), 128 * 1024)).use { output ->
                output.writeInt(magic)
                output.writeInt(VERSION)
                output.writeInt(tracks.size)
                tracks.forEach { track -> output.writeTrack(track) }
                output.flush()
            }
            FileOutputStream(temp, true).use { it.fd.sync() }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Unable to replace audio bootstrap snapshot")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    private fun readTracks(file: File, expectedMagic: Int, maxCount: Int): List<LibraryTrack> {
        DataInputStream(BufferedInputStream(FileInputStream(file), 128 * 1024)).use { input ->
            if (input.readInt() != expectedMagic) throw IllegalStateException("Invalid audio bootstrap magic")
            if (input.readInt() != VERSION) throw IllegalStateException("Unsupported audio bootstrap version")
            val count = input.readInt()
            if (count !in 1..maxCount) throw IllegalStateException("Invalid audio bootstrap size")
            val sources = LibraryTrackSource.values()
            return ArrayList<LibraryTrack>(count).apply {
                repeat(count) { add(input.readTrack(sources)) }
            }
        }
    }

    private fun DataOutputStream.writeTrack(track: LibraryTrack) {
        writeLong(track.id)
        writeString(track.title)
        writeString(track.artist)
        writeString(track.album)
        writeLong(track.durationMs)
        writeLong(track.bitrate)
        writeInt(track.trackNo)
        writeString(track.path)
        writeString(track.libraryPath)
        writeLong(track.addedAt)
        writeString(track.artworkPath)
        writeInt(track.source.ordinal)
        writeString(track.sourceLabel)
        writeBoolean(track.titleFromTag)
        writeBoolean(track.albumFromTag)
        writeBoolean(track.artistFromTag)
        writeString(track.container)
        writeLong(track.sizeBytes)
        writeLong(track.modifiedAt)
    }

    private fun DataInputStream.readTrack(sources: Array<LibraryTrackSource>): LibraryTrack =
        LibraryTrack(
            id = readLong(),
            title = readString(),
            artist = readString(),
            album = readString(),
            durationMs = readLong(),
            bitrate = readLong(),
            trackNo = readInt(),
            path = readString(),
            libraryPath = readString(),
            addedAt = readLong(),
            artworkPath = readString(),
            source = sources.getOrElse(readInt()) { LibraryTrackSource.LOCAL },
            sourceLabel = readString(),
            titleFromTag = readBoolean(),
            albumFromTag = readBoolean(),
            artistFromTag = readBoolean(),
            container = readString(),
            sizeBytes = readLong(),
            modifiedAt = readLong()
        )

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        if (size !in 0..MAX_STRING_BYTES) throw EOFException("Invalid string length")
        if (size == 0) return ""
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun isValidSnapshotFile(file: File): Boolean =
        file.isFile && file.length() in 16L..MAX_FILE_BYTES

    private fun fullSnapshotFile(context: Context): File = File(context.filesDir, FULL_FILE_NAME)
    private fun albumSnapshotFile(context: Context): File = File(context.filesDir, ALBUM_FILE_NAME)
}
