package fr.retrospare.blazeplayer.player

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.text.Normalizer
import java.util.Locale

/**
 * Source locale persistante de la bibliothèque audio.
 *
 * La UI ne doit plus reconstruire Mes albums / Artistes / Titres depuis le NAS, la file
 * d'attente ou des snapshots JSON historiques. Le NAS sert uniquement à alimenter cette table :
 * - pass 1 : upsert de squelettes path/name/size/mtime/folderKey ;
 * - pass 2 : update progressif des tags et covers, fichier par fichier.
 */
@Entity(
    tableName = "audio_library_tracks",
    indices = [
        Index(value = ["folderKey"]),
        Index(value = ["albumSortKey"]),
        Index(value = ["artistSortKey"]),
        Index(value = ["seenGeneration"]),
        Index(value = ["deleted"]),
        // Index composite couvrant exactement la clause ORDER BY de observeActive()/activeOnce().
        // Sans lui, SQLite ne peut utiliser aucun des index mono-colonne ci-dessus pour le tri :
        // il fait un scan complet de la table puis un tri en mémoire (temp b-tree) à CHAQUE lecture,
        // y compris à l'ouverture de l'app et à chaque petite mise à jour (une pochette, un tag).
        // Avec cet index, la requête devient un simple parcours déjà trié — le gain grandit avec
        // la taille de la bibliothèque (net dès quelques centaines de titres, flagrant au-delà).
        Index(value = ["deleted", "artistSortKey", "albumSortKey", "trackNumber", "titleSortKey"])
    ]
)
data class AudioLibraryTrackEntity(
    @PrimaryKey val path: String,
    val name: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int,
    val addedAt: Long,
    val extension: String,
    val isNetwork: Boolean,
    val shareId: String,
    val sourceLabel: String,
    val artworkPath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val folderKey: String,
    val seenGeneration: Long,
    val titleFromTag: Boolean,
    val artistFromTag: Boolean,
    val albumFromTag: Boolean,
    val metadataVersion: Int,
    val artworkVersion: Int,
    val deleted: Boolean,
    val albumSortKey: String,
    val artistSortKey: String,
    val titleSortKey: String
)

@Dao
interface AudioLibraryTrackDao {
    // Les colonnes *SortKey sont déjà normalisées (minuscules, sans accents) à l'écriture via
    // normalizeForStore(). Ne PAS ajouter COLLATE NOCASE ici : une collation différente de celle
    // de l'index composite (deleted, artistSortKey, albumSortKey, trackNumber, titleSortKey)
    // empêcherait SQLite d'utiliser cet index pour satisfaire l'ORDER BY, et forcerait un tri en
    // mémoire à chaque lecture malgré l'index — exactement le coût qu'on cherche à éliminer.
    @Query("""
        SELECT * FROM audio_library_tracks
        WHERE deleted = 0
        ORDER BY artistSortKey, albumSortKey, trackNumber, titleSortKey
    """)
    fun observeActive(): Flow<List<AudioLibraryTrackEntity>>

    @Query("""
        SELECT * FROM audio_library_tracks
        WHERE deleted = 0
        ORDER BY artistSortKey, albumSortKey, trackNumber, titleSortKey
        LIMIT :limit
    """)
    suspend fun activeOnce(limit: Int): List<AudioLibraryTrackEntity>

    @Query("SELECT * FROM audio_library_tracks WHERE path IN (:paths)")
    suspend fun byPaths(paths: List<String>): List<AudioLibraryTrackEntity>

    @Query("SELECT * FROM audio_library_tracks WHERE path = :path LIMIT 1")
    suspend fun byPath(path: String): AudioLibraryTrackEntity?

    @Query("UPDATE audio_library_tracks SET artworkPath = :artworkPath, artworkVersion = :artworkVersion WHERE path = :path AND deleted = 0")
    suspend fun updateArtwork(path: String, artworkPath: String, artworkVersion: Int): Int

    @Query("UPDATE audio_library_tracks SET artworkPath = :persistedPath, artworkVersion = :artworkVersion WHERE artworkPath = :sourcePath AND deleted = 0")
    suspend fun updateArtworkForSource(sourcePath: String, persistedPath: String, artworkVersion: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AudioLibraryTrackEntity>): List<Long>

    @Query("DELETE FROM audio_library_tracks WHERE folderKey IN (:folderKeys)")
    suspend fun deleteFolders(folderKeys: List<String>): Int

    @Query("DELETE FROM audio_library_tracks WHERE folderKey IN (:folderKeys) AND seenGeneration != :generation")
    suspend fun deleteMissingInFolders(folderKeys: List<String>, generation: Long): Int

    @Query("DELETE FROM audio_library_tracks")
    suspend fun clear(): Int
}

@Database(
    entities = [AudioLibraryTrackEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AudioLibraryRoomDatabase : RoomDatabase() {
    abstract fun trackDao(): AudioLibraryTrackDao

    companion object {
        @Volatile private var instance: AudioLibraryRoomDatabase? = null

        fun get(context: Context): AudioLibraryRoomDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AudioLibraryRoomDatabase::class.java,
                "blaze_audio_library_room.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

object AudioLibraryRoomStore {
    private const val CURRENT_METADATA_VERSION = 2
    private const val CURRENT_ARTWORK_VERSION = 2

    data class ReplaceResult(
        val changedPaths: Set<String>,
        val upsertedCount: Int,
        val removedCount: Int
    )

    data class TechnicalSnapshot(
        val durationMs: Long,
        val sizeBytes: Long,
        val extension: String
    )

    fun observeActive(context: Context): Flow<List<AudioLibraryTrackEntity>> =
        AudioLibraryRoomDatabase.get(context).trackDao().observeActive()

    suspend fun loadActive(context: Context, limit: Int = Int.MAX_VALUE): List<AudioLibraryTrackEntity> =
        AudioLibraryRoomDatabase.get(context).trackDao().activeOnce(if (limit == Int.MAX_VALUE) Int.MAX_VALUE else limit)

    /** Données déjà indexées permettant de calculer un débit moyen sans rouvrir le fichier NAS. */
    suspend fun loadTechnicalSnapshot(context: Context, path: String): TechnicalSnapshot? {
        if (path.isBlank()) return null
        val entity = AudioLibraryRoomDatabase.get(context).trackDao().byPath(path) ?: return null
        return TechnicalSnapshot(
            durationMs = entity.durationMs.coerceAtLeast(0L),
            sizeBytes = entity.sizeBytes.coerceAtLeast(0L),
            extension = entity.extension
        )
    }

    suspend fun replaceSkeletonPass(
        context: Context,
        skeletons: List<AudioLibraryTrackEntity>,
        confirmedFolderKeys: Set<String>,
        generation: Long
    ): ReplaceResult {
        val partial = upsertSkeletonBatch(context, skeletons, generation)
        val dao = AudioLibraryRoomDatabase.get(context).trackDao()
        var removed = 0
        if (confirmedFolderKeys.isNotEmpty()) {
            confirmedFolderKeys.chunked(300).forEach { removed += dao.deleteMissingInFolders(it, generation) }
        }
        return partial.copy(removedCount = removed)
    }

    /**
     * Écrit immédiatement un lot de squelettes sans purger les autres entrées. Cette variante est
     * utilisée pendant le parcours d'un NAS afin que la bibliothèque commence à apparaître dès
     * les premiers dossiers trouvés, au lieu d'attendre la fin de tout le scan réseau.
     */
    suspend fun upsertSkeletonBatch(
        context: Context,
        skeletons: List<AudioLibraryTrackEntity>,
        generation: Long
    ): ReplaceResult {
        val dao = AudioLibraryRoomDatabase.get(context).trackDao()
        val clean = skeletons
            .asSequence()
            .filter { it.path.isNotBlank() && it.folderKey.isNotBlank() }
            .distinctBy { canonicalPathKey(it.path) }
            .toList()
        val existing = mutableMapOf<String, AudioLibraryTrackEntity>()
        clean.map { it.path }.chunked(400).forEach { chunk ->
            dao.byPaths(chunk).forEach { existing[it.path] = it }
        }

        val changedPaths = linkedSetOf<String>()
        val merged = clean.map { skeleton ->
            val old = existing[skeleton.path]
            val fileChanged = old == null || !sameFileIdentity(old, skeleton)
            val needsMetadata = old == null || old.metadataVersion < CURRENT_METADATA_VERSION || !old.hasRealMetadata()
            if (fileChanged || needsMetadata) changedPaths += skeleton.path

            when {
                old == null -> skeleton.copy(
                    seenGeneration = generation,
                    deleted = false,
                    metadataVersion = 0,
                    artworkVersion = 0,
                    albumSortKey = normalizeForStore(skeleton.album),
                    artistSortKey = normalizeForStore(skeleton.artist),
                    titleSortKey = normalizeForStore(skeleton.title)
                )
                !fileChanged && old.hasRealMetadata() -> old.copy(
                    name = skeleton.name.ifBlank { old.name },
                    addedAt = maxOf(old.addedAt, skeleton.addedAt),
                    sourceLabel = skeleton.sourceLabel.ifBlank { old.sourceLabel },
                    shareId = skeleton.shareId.ifBlank { old.shareId },
                    folderKey = skeleton.folderKey,
                    seenGeneration = generation,
                    deleted = false,
                    sizeBytes = skeleton.sizeBytes.takeIf { it > 0L } ?: old.sizeBytes,
                    modifiedAt = skeleton.modifiedAt.takeIf { it > 0L } ?: old.modifiedAt,
                    extension = skeleton.extension.ifBlank { old.extension },
                    // Une pochette déjà extraite est notre source stable : un simple rescan ne
                    // doit pas la remplacer par une URL NAS ou le chemin audio, sinon les écrans
                    // redeviennent dépendants du réseau et peuvent afficher un placeholder.
                    artworkPath = old.artworkPath.takeIf {
                        AudioArtworkPersistence.isPersistedPath(context, it) && java.io.File(it).isFile
                    } ?: skeleton.artworkPath.ifBlank { skeleton.path },
                    artworkVersion = if (
                        AudioArtworkPersistence.isPersistedPath(context, old.artworkPath) && java.io.File(old.artworkPath).isFile
                    ) CURRENT_ARTWORK_VERSION else if (
                        AudioLibraryHeuristics.isImagePath(skeleton.artworkPath) &&
                        AudioLibraryHeuristics.isPreferredCoverName(AudioLibraryHeuristics.fileNameFromPath(skeleton.artworkPath))
                    ) CURRENT_ARTWORK_VERSION else 0
                )
                else -> skeleton.copy(
                    seenGeneration = generation,
                    deleted = false,
                    metadataVersion = 0,
                    artworkPath = old?.artworkPath?.takeIf {
                        !fileChanged && AudioArtworkPersistence.isPersistedPath(context, it) && java.io.File(it).isFile
                    } ?: skeleton.artworkPath,
                    artworkVersion = if (
                        old != null && !fileChanged && AudioArtworkPersistence.isPersistedPath(context, old.artworkPath) && java.io.File(old.artworkPath).isFile
                    ) CURRENT_ARTWORK_VERSION else 0,
                    albumSortKey = normalizeForStore(skeleton.album),
                    artistSortKey = normalizeForStore(skeleton.artist),
                    titleSortKey = normalizeForStore(skeleton.title)
                )
            }
        }
        merged.chunked(300).forEach { dao.upsertAll(it) }
        return ReplaceResult(changedPaths, merged.size, 0)
    }

    suspend fun upsertMetadata(context: Context, updates: List<AudioLibraryTrackEntity>) {
        if (updates.isEmpty()) return
        val dao = AudioLibraryRoomDatabase.get(context).trackDao()
        val existing = mutableMapOf<String, AudioLibraryTrackEntity>()
        updates.map { it.path }.chunked(400).forEach { chunk ->
            dao.byPaths(chunk).forEach { existing[it.path] = it }
        }
        val merged = updates.mapNotNull { update ->
            val old = existing[update.path] ?: return@mapNotNull null
            if (old.deleted) return@mapNotNull null
            old.copy(
                title = update.title.ifBlank { old.title },
                artist = update.artist.ifBlank { old.artist },
                album = update.album.ifBlank { old.album },
                durationMs = update.durationMs.takeIf { it > 0L } ?: old.durationMs,
                trackNumber = update.trackNumber.takeIf { it > 0 } ?: old.trackNumber,
                extension = update.extension.ifBlank { old.extension },
                artworkPath = old.artworkPath.takeIf {
                    AudioArtworkPersistence.isPersistedPath(context, it) && java.io.File(it).isFile
                } ?: update.artworkPath.ifBlank { old.artworkPath },
                titleFromTag = update.titleFromTag || old.titleFromTag,
                artistFromTag = update.artistFromTag || old.artistFromTag,
                albumFromTag = update.albumFromTag || old.albumFromTag,
                metadataVersion = CURRENT_METADATA_VERSION,
                artworkVersion = if (update.artworkPath.isNotBlank()) CURRENT_ARTWORK_VERSION else old.artworkVersion,
                deleted = false,
                albumSortKey = normalizeForStore(update.album.ifBlank { old.album }),
                artistSortKey = normalizeForStore(update.artist.ifBlank { old.artist }),
                titleSortKey = normalizeForStore(update.title.ifBlank { old.title })
            )
        }
        merged.chunked(300).forEach { dao.upsertAll(it) }
    }

    suspend fun upsertMetadataInfo(context: Context, path: String, info: AudioTechnicalInfo) {
        if (path.isBlank()) return
        val dao = AudioLibraryRoomDatabase.get(context).trackDao()
        val old = dao.byPath(path) ?: return
        if (old.deleted) return
        val nextTitle = info.title.ifBlank { old.title }
        val nextArtist = info.artist.ifBlank { old.artist }
        val nextAlbum = info.album.ifBlank { old.album }
        val next = old.copy(
            title = nextTitle,
            artist = nextArtist,
            album = nextAlbum,
            durationMs = if (info.duration > 0L) info.duration * 1000L else old.durationMs,
            trackNumber = if (info.trackNumber > 0) info.trackNumber else old.trackNumber,
            extension = info.extension.ifBlank { old.extension },
            titleFromTag = old.titleFromTag || info.title.isNotBlank(),
            artistFromTag = old.artistFromTag || info.artist.isNotBlank(),
            albumFromTag = old.albumFromTag || info.album.isNotBlank(),
            metadataVersion = CURRENT_METADATA_VERSION,
            albumSortKey = normalizeForStore(nextAlbum),
            artistSortKey = normalizeForStore(nextArtist),
            titleSortKey = normalizeForStore(nextTitle)
        )
        dao.upsertAll(listOf(next))
    }

    suspend fun updateArtworkPath(context: Context, path: String, artworkPath: String) {
        if (path.isBlank() || artworkPath.isBlank()) return
        AudioLibraryRoomDatabase.get(context).trackDao().updateArtwork(path, artworkPath, CURRENT_ARTWORK_VERSION)
    }

    suspend fun updateArtworkPathForSource(context: Context, sourcePath: String, persistedPath: String) {
        if (sourcePath.isBlank() || persistedPath.isBlank() || sourcePath == persistedPath) return
        AudioLibraryRoomDatabase.get(context).trackDao()
            .updateArtworkForSource(sourcePath, persistedPath, CURRENT_ARTWORK_VERSION)
    }

    suspend fun deleteFolders(context: Context, folderKeys: Set<String>) {
        if (folderKeys.isEmpty()) return
        val dao = AudioLibraryRoomDatabase.get(context).trackDao()
        folderKeys.chunked(300).forEach { dao.deleteFolders(it) }
    }

    suspend fun clear(context: Context) {
        AudioLibraryRoomDatabase.get(context).trackDao().clear()
    }

    private fun AudioLibraryTrackEntity.hasRealMetadata(): Boolean =
        titleFromTag || artistFromTag || albumFromTag || durationMs > 0L || trackNumber > 0 || metadataVersion >= CURRENT_METADATA_VERSION

    private fun sameFileIdentity(old: AudioLibraryTrackEntity, new: AudioLibraryTrackEntity): Boolean {
        val oldSize = old.sizeBytes
        val newSize = new.sizeBytes
        val oldModified = old.modifiedAt
        val newModified = new.modifiedAt
        if (new.isNetwork && newSize <= 0L && newModified <= 0L) return false
        if (oldSize > 0L && newSize > 0L && oldSize != newSize) return false
        if (oldModified > 0L && newModified > 0L && oldModified != newModified) return false
        return true
    }

    private fun canonicalPathKey(path: String): String = path
        .substringBefore('?')
        .substringBefore('#')
        .replace('\\', '/')
        .trim()
        .trimEnd('/')
        .lowercase(Locale.getDefault())
}

private fun normalizeForStore(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.getDefault()), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
