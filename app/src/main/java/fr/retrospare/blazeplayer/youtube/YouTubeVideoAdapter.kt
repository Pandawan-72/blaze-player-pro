package fr.retrospare.blazeplayer.youtube

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adapter unique pour les trois listes (recherche, favoris, historique) : mode compact pour la
 *  bande horizontale des favoris, mode normal sinon. [onFavoriteToggle] est null en mode compact
 *  (pas de bouton favori sur les vignettes déjà favorites, il suffit de les retirer autrement). */
class YouTubeVideoAdapter(
    private val context: Context,
    private var items: List<YouTubeVideoItem>,
    private val compact: Boolean,
    private val onClick: (YouTubeVideoItem) -> Unit,
    private val onFavoriteToggle: ((YouTubeVideoItem, RecyclerView.ViewHolder) -> Unit)? = null,
    private val onMoreClick: ((YouTubeVideoItem, View) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val scope = CoroutineScope(Dispatchers.Main)

    fun updateItems(newItems: List<YouTubeVideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    private class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var loadJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (compact) R.layout.item_youtube_favorite else R.layout.item_youtube_video
        val view = LayoutInflater.from(context).inflate(layout, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val view = holder.itemView
        val vh = holder as VideoViewHolder

        val ivThumb = view.findViewById<ImageView>(R.id.ivYoutubeThumbnail)
        ivThumb.setImageDrawable(null)
        vh.loadJob?.cancel()
        vh.loadJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadThumbnailBitmap(item.thumbnailUrl) }
            if (bitmap != null) ivThumb.setImageBitmap(bitmap)
        }

        view.findViewById<TextView>(R.id.tvYoutubeTitle).text = item.title
        view.findViewById<TextView>(R.id.tvYoutubeChannel)?.text = item.channelTitle

        view.setOnClickListener { onClick(item) }

        val btnFavorite = view.findViewById<View>(R.id.btnYoutubeFavorite)
        if (btnFavorite != null && onFavoriteToggle != null) {
            val imgBtn = btnFavorite as? android.widget.ImageButton
            fun applyFavoriteColor(isFav: Boolean) {
                imgBtn?.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(
                        context,
                        if (isFav) R.color.green_accent else R.color.on_surface_variant
                    )
                )
            }
            applyFavoriteColor(YouTubeLibrary.isFavorite(context, item.videoId))
            btnFavorite.setOnClickListener {
                // Retour visuel immédiat : ne pas attendre un rafraîchissement externe de la
                // liste (qui peut ne jamais toucher CETTE liste précise, ex: résultats de
                // recherche) pour que l'étoile change de couleur.
                applyFavoriteColor(!YouTubeLibrary.isFavorite(context, item.videoId))
                onFavoriteToggle.invoke(item, holder)
            }
        }

        view.findViewById<View>(R.id.btnYoutubeMore)?.let { btnMore ->
            btnMore.setOnClickListener { onMoreClick?.invoke(item, btnMore) }
        }
    }

    private fun loadThumbnailBitmap(url: String): android.graphics.Bitmap? {
        if (url.isBlank()) return null
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
}
