package fr.retrospare.blazeplayer.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Cache disque partage pour le streaming reseau (SMB/HTTP), permettant des seeks
 * quasi instantanes sur les portions deja lues et reduisant le trafic NAS.
 */
@UnstableApi
object VideoCacheManager {

    private const val CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 Mo

    @Volatile
    private var cache: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        var c = cache
        if (c == null) {
            val cacheDir = File(context.cacheDir, "video_network_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context)
            c = SimpleCache(cacheDir, evictor, databaseProvider)
            cache = c
        }
        return c
    }
}
