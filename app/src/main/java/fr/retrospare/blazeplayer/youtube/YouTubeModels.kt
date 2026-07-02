package fr.retrospare.blazeplayer.youtube

/** Résultat de recherche YouTube, ou entrée de favoris/historique. Un seul modèle pour les trois
 *  usages : les champs sont les mêmes, seule la provenance (recherche live vs local) diffère. */
data class YouTubeVideoItem(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String,
    /** Timestamp de dernière lecture (historique) ou d'ajout (favoris), 0 si non applicable
     *  (résultat de recherche pas encore regardé/ajouté). */
    val timestamp: Long = 0L
)
