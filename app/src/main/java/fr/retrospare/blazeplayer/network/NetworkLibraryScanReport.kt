package fr.retrospare.blazeplayer.network

/**
 * Résultat détaillé d'une découverte réseau.
 *
 * foundCount peut être positif même lorsque complete=false : les éléments découverts sont alors
 * conservés, mais le dossier n'est jamais purgé puisque certains sous-dossiers n'ont pas été
 * confirmés. C'est le point clé pour rendre les scans SMB/UPnP tolérants aux coupures temporaires.
 */
data class NetworkLibraryScanReport(
    val foundCount: Int,
    val visitedDirectoryCount: Int,
    val failedDirectories: List<String>,
    val limitReached: Boolean,
    val cancelled: Boolean,
    val complete: Boolean
)
