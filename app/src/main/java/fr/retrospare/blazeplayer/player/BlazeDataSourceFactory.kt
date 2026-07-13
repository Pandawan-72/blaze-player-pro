package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * DataSource Media3 unique pour Blaze :
 * - smb:// est lu via SmbDataSource ;
 * - http(s)://, content://, file:// et chemins locaux restent gérés par DefaultDataSource.
 *
 * Avant ce correctif, le service audio utilisait DefaultDataSource.Factory(context, SmbDataSource.Factory()).
 * Selon Media3, l'upstream fourni est utilisé pour les flux réseau HTTP(S) ; une piste UPnP/DLNA
 * (URL http://...) était donc envoyée au lecteur SMB, ce qui empêchait le lancement. Cette factory
 * choisit explicitement la bonne source en fonction du schéma de l'URI.
 */
@UnstableApi
class BlazeDataSourceFactory(
    context: Context,
    private val smbOwnerTag: String = SmbDataSource.OWNER_GENERIC
) : DataSource.Factory {

    private val appContext = context.applicationContext

    override fun createDataSource(): DataSource = BlazeDispatchingDataSource(appContext, smbOwnerTag)

    private class BlazeDispatchingDataSource(
        private val context: Context,
        private val smbOwnerTag: String
    ) : DataSource {
        @Volatile private var delegate: DataSource? = null
        private val pendingListeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            pendingListeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        @Synchronized
        override fun open(dataSpec: DataSpec): Long {
            // Une instance de dispatch ne doit posséder qu'un seul delegate actif. Détacher et
            // fermer l'ancien avant d'en créer un autre rend également close() idempotent.
            val previous = delegate
            delegate = null
            runCatching { previous?.close() }

            val scheme = dataSpec.uri.scheme?.lowercase()
            val ds: DataSource = if (scheme == "smb") {
                SmbDataSource(smbOwnerTag)
            } else {
                DefaultDataSource.Factory(context).createDataSource()
            }
            pendingListeners.forEach { ds.addTransferListener(it) }
            delegate = ds
            return try {
                ds.open(dataSpec)
            } catch (error: Throwable) {
                if (delegate === ds) delegate = null
                runCatching { ds.close() }
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return delegate?.read(buffer, offset, length) ?: androidx.media3.common.C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            // Détacher avant de fermer : deux close() concurrents ne peuvent plus atteindre le même
            // SmbDataSource et déclencher deux transferEnded() successifs.
            val current = synchronized(this) {
                delegate.also { delegate = null }
            }
            current?.close()
        }
    }
}
