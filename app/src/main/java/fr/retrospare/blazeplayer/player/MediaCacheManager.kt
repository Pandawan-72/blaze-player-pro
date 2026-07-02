package fr.retrospare.blazeplayer.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Cache disque partagé pour le streaming réseau (SMB), utilisé par [BlazePlayerService] (audio) et
 * [VideoPlaybackService] (vidéo) via [androidx.media3.datasource.cache.CacheDataSource]. Permet des
 * seeks quasi instantanés sur les portions déjà lues et réduit le trafic NAS.
 *
 * Un seul [SimpleCache] doit exister par dossier de cache pour toute la durée de vie du process
 * (c'est une contrainte de Media3) : on le construit donc une seule fois ici en singleton plutôt
 * que de laisser chaque service en créer un séparément.
 */
@UnstableApi
object MediaCacheManager {

    // 500 Mo était trop juste pour du 4K (rempli en quelques minutes sur un flux haut débit),
    // ce qui forçait des re-lectures réseau constantes sur les seeks/reprises au lieu de servir
    // depuis le cache. 2 Go laisse une marge confortable pour un film 4K entier ou plusieurs
    // épisodes, tout en restant raisonnable en usage disque sur un téléphone.
    private const val CACHE_SIZE_BYTES = 2L * 1024 * 1024 * 1024 // 2 Go

    @Volatile
    private var cache: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        var c = cache
        if (c == null) {
            val cacheDir = File(context.applicationContext.cacheDir, "network_media_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            c = SimpleCache(cacheDir, evictor, databaseProvider)
            cache = c
        }
        return c
    }
}
