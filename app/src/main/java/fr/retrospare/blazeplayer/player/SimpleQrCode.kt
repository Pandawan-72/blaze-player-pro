package fr.retrospare.blazeplayer.player

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Génération du QR code d'invitation Blaze Party, désormais basée sur ZXing (`com.google.zxing:core`)
 * plutôt que sur un encodeur QR fait main.
 *
 * L'implémentation précédente réimplémentait à la main l'intégralité de l'algorithme QR (motifs de
 * repérage, masquage, correction Reed-Solomon, zone d'information de format...). Vérification faite
 * avec un vrai décodeur (zbar) : plusieurs bits de la zone "informations de format" — celle qui dit
 * au lecteur quel masque et quel niveau de correction d'erreur ont été utilisés — étaient placés aux
 * mauvaises coordonnées. Le code généré ressemblait à un QR valide visuellement (mêmes repères,
 * même structure générale) mais n'importe quel scanner conforme à la norme ISO/IEC 18004 le rejetait
 * en bloc, d'où le "mon scanner n'arrive pas à le lire" — ce n'était pas un souci de scanner
 * particulier, TOUS les scanners corrects auraient échoué de la même façon.
 *
 * Un encodeur QR maison reste un terrain miné pour ce genre d'erreur (la spec ISO/IEC 18004 est
 * dense et peu tolérante au moindre décalage de coordonnée). ZXing est la bibliothèque de référence
 * utilisée ou testée contre la quasi-totalité des scanners du marché (appareils photo Android,
 * Google Lens, scanners tiers, lecteurs de caisse...), ce qui garantit ici la compatibilité maximale
 * demandée, sans reproduire un bug de ce type.
 *
 * Ajouter au build.gradle du module app : implementation("com.google.zxing:core:3.5.3")
 */
object SimpleQrCode {

    /** Conserve exactement la même signature que l'ancienne implémentation : aucun appelant
     *  (AudioPlayerFragment) n'a besoin d'être modifié. */
    fun bitmap(text: String, scale: Int = 8, quiet: Int = 4): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quiet,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1"
        )
        val matrix: BitMatrix = try {
            // width=0, height=0 : on ne demande à ZXing que la matrice "brute" (un pixel par
            // module, zone de silence déjà incluse via MARGIN), pour garder nous-mêmes le contrôle
            // total de la mise à l'échelle finale via [scale] — comportement identique à l'ancien
            // code, qui dessinait aussi un carré de `scale` pixels par module.
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (e: WriterException) {
            // Même contrat d'erreur que l'ancienne implémentation (payload trop long), pour ne pas
            // avoir à toucher le bloc try/catch(IllegalArgumentException) déjà présent côté appelant.
            throw IllegalArgumentException("Blaze Party QR payload invalide ou trop long", e)
        }

        val moduleCount = matrix.width // == matrix.height : la matrice est toujours carrée
        val px = moduleCount * scale
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        for (y in 0 until moduleCount) {
            for (x in 0 until moduleCount) {
                val color = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        bmp.setPixel(x * scale + dx, y * scale + dy, color)
                    }
                }
            }
        }
        return bmp
    }
}
