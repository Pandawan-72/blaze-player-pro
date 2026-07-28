package fr.retrospare.blazeplayer.gallery.edit

import fr.retrospare.blazeplayer.ui.showPremium
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject

/**
 * Éditeur photo façon Google Photos : filtres (aperçu temps réel via ColorMatrix), recadrage
 * (overlay dédié avec ratios verrouillables), rotation 90°/redressement fin, et un panneau
 * "Détails" (EXIF). Le résultat est toujours enregistré comme un **nouveau** fichier — jamais
 * d'écrasement de l'original — via [MediaSaveUtils].
 *
 * Principe pour ne pas perdre en qualité : l'édition à l'écran se fait sur un bitmap d'aperçu
 * sous-échantillonné (fluide, réactif), mais le recadrage est mémorisé en fractions (0..1) et la
 * rotation en degrés — tout est ré-appliqué sur le fichier source rechargé en pleine résolution
 * au moment d'enregistrer, jamais sur l'aperçu lui-même.
 */
@AndroidEntryPoint
class PhotoEditorActivity : AppCompatActivity() {
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository


    companion object {
        const val EXTRA_PHOTO_PATH = "photo_path"
        const val EXTRA_PHOTO_NAME = "photo_name"
        /** Mode utilisé par Diapo : l'éditeur renvoie une copie temporaire au lieu de publier
         *  une nouvelle photo dans MediaStore. Cela permet de réutiliser exactement les filtres,
         *  crop, rotation et flou existants sans polluer la galerie pendant la préparation. */
        const val EXTRA_RETURN_TEMP = "return_temp"
        const val EXTRA_OUTPUT_PATH = "output_path"
    }

    private lateinit var ivPhoto: BeforeAfterImageView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var blurOverlay: BlurSelectionView
    private lateinit var filterStrip: android.widget.LinearLayout
    private lateinit var filterPanel: View
    private lateinit var cropPanel: View
    private lateinit var rotatePanel: View
    private lateinit var blurPanel: View
    private lateinit var editorProgress: View
    private lateinit var seekStraighten: SeekBar
    private lateinit var btnEffectBlur: com.google.android.material.button.MaterialButton
    private lateinit var btnEffectMosaic: com.google.android.material.button.MaterialButton
    private lateinit var btnShapeCircle: com.google.android.material.button.MaterialButton
    private lateinit var btnShapeSquare: com.google.android.material.button.MaterialButton
    private lateinit var seekBlurIntensity: SeekBar

    private var photoPath: String = ""
    private var photoName: String = "photo"
    private var returnTempMode: Boolean = false
    private var basePreviewBitmap: Bitmap? = null
    // Bitmap d'aperçu après rotation mais AVANT flou/mosaïque : sert de source à chaque
    // régénération de l'aperçu en direct de l'outil Flou (on ne veut jamais réappliquer le
    // flou sur un aperçu déjà flouté au tour précédent).
    private var rotatedPreviewBitmap: Bitmap? = null
    private var selectedFilter: PhotoFilter = PhotoFilters.buildAll().first()
    private var quarterRotation = 0
    private var straightenDelta = 0f
    private var blurEnabled = false
    private var blurIsMosaic = false
    private var blurIntensity = 50
    private val blurPreviewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingBlurPreview: Runnable? = null
    private val filterThumbViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!fr.retrospare.blazeplayer.paywall.AccessGateUi.enforceNow(
                this,
                userRepository,
                fr.retrospare.blazeplayer.paywall.AccessLevel.PRO
            )) return
        fr.retrospare.blazeplayer.paywall.AccessGateUi.monitor(
            this,
            userRepository,
            fr.retrospare.blazeplayer.paywall.AccessLevel.PRO
        )
        setContentView(R.layout.activity_photo_editor)

        photoPath = intent.getStringExtra(EXTRA_PHOTO_PATH) ?: run { finish(); return }
        photoName = intent.getStringExtra(EXTRA_PHOTO_NAME) ?: "photo"
        returnTempMode = intent.getBooleanExtra(EXTRA_RETURN_TEMP, false)

        ivPhoto = findViewById(R.id.ivPhoto)
        cropOverlay = findViewById(R.id.cropOverlay)
        blurOverlay = findViewById(R.id.blurOverlay)
        filterStrip = findViewById(R.id.filterStrip)
        filterPanel = findViewById(R.id.filterPanel)
        cropPanel = findViewById(R.id.cropPanel)
        rotatePanel = findViewById(R.id.rotatePanel)
        blurPanel = findViewById(R.id.blurPanel)
        editorProgress = findViewById(R.id.editorProgress)
        seekStraighten = findViewById(R.id.seekStraighten)
        btnEffectBlur = findViewById(R.id.btnEffectBlur)
        btnEffectMosaic = findViewById(R.id.btnEffectMosaic)
        btnShapeCircle = findViewById(R.id.btnShapeCircle)
        btnShapeSquare = findViewById(R.id.btnShapeSquare)
        seekBlurIntensity = findViewById(R.id.seekBlurIntensity)

        findViewById<View>(R.id.btnEditorClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnEditorSave).setOnClickListener { performSave() }

        buildFilterStrip()
        setupCropRatioButtons()
        setupRotateControls()
        setupBlurControls()
        setupToolTabs()
        setupBeforeAfterComparison()
        showPanel(filterPanel)

        loadPreview()
    }

    private fun openStream(path: String): InputStream =
        if (path.startsWith("content://")) contentResolver.openInputStream(Uri.parse(path))!!
        else FileInputStream(path)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun loadPreview() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = try { decodeSampled(photoPath, 1400) } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (bmp == null) {
                    Toast.makeText(this@PhotoEditorActivity, getString(R.string.toast_photo_save_failed), Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                basePreviewBitmap = bmp
                rotatedPreviewBitmap = bmp
                ivPhoto.setImageBitmap(bmp)
                ivPhoto.post {
                    val rect = computeImageDisplayRect()
                    cropOverlay.setImageRect(rect)
                    blurOverlay.setImageRect(rect)
                }
            }
        }
    }

    private fun decodeSampled(path: String, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return openStream(path).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Décodage impossible")
    }

    private fun computeImageDisplayRect(): RectF {
        val d = ivPhoto.drawable
        val viewW = ivPhoto.width.toFloat(); val viewH = ivPhoto.height.toFloat()
        if (d == null || viewW <= 0f || viewH <= 0f) return RectF(0f, 0f, viewW, viewH)
        val dW = d.intrinsicWidth.toFloat(); val dH = d.intrinsicHeight.toFloat()
        if (dW <= 0f || dH <= 0f) return RectF(0f, 0f, viewW, viewH)
        val scale = minOf(viewW / dW, viewH / dH)
        val actualW = dW * scale; val actualH = dH * scale
        val left = (viewW - actualW) / 2f; val top = (viewH - actualH) / 2f
        return RectF(left, top, left + actualW, top + actualH)
    }

    // ── Filtres ──────────────────────────────────────────────────────────────

    private fun buildFilterStrip() {
        val inflater = layoutInflater
        PhotoFilters.buildAll().forEach { filter ->
            val item = inflater.inflate(R.layout.item_photo_filter, filterStrip, false)
            val thumb = item.findViewById<ImageView>(R.id.ivFilterThumb)
            val label = item.findViewById<TextView>(R.id.tvFilterLabel)
            label.text = getString(filter.labelRes)
            // Photo d'exemple embarquée (pas la photo réellement éditée) : chaque vignette montre
            // le même aperçu de référence pour comparer les filtres d'un coup d'œil, sans avoir à
            // regénérer une miniature de la photo en cours d'édition à chaque ouverture.
            thumb.setImageResource(R.drawable.sample_filter_preview)
            thumb.colorFilter = ColorMatrixColorFilter(filter.matrix)
            item.isSelected = filter.id == selectedFilter.id
            item.setOnClickListener {
                selectedFilter = filter
                filterThumbViews.forEach { v -> v.isSelected = false }
                item.isSelected = true
                ivPhoto.setAfterFilter(ColorMatrixColorFilter(filter.matrix))
                ivPhoto.setComparisonEnabled(filter.id != "normal" && filterPanel.visibility == View.VISIBLE)
                if (filter.id != "normal") ivPhoto.resetComparisonPosition()
            }
            filterThumbViews.add(item)
            filterStrip.addView(item)
        }
    }

    private fun setupBeforeAfterComparison() {
        ivPhoto.setAfterFilter(ColorMatrixColorFilter(selectedFilter.matrix))
        ivPhoto.setComparisonEnabled(false)
        ivPhoto.resetComparisonPosition()
    }

    // ── Recadrage ────────────────────────────────────────────────────────────

    private val ratioButtons by lazy {
        listOf(
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRatioFree),
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRatioSquare),
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRatio43),
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRatio169)
        )
    }

    private fun setupCropRatioButtons() {
        val ratios = listOf(null, 1f, 4f / 3f, 16f / 9f)
        ratioButtons.forEachIndexed { i, button ->
            button.setOnClickListener {
                cropOverlay.setAspectRatio(ratios[i])
                selectRatioButton(button)
            }
        }
        selectRatioButton(ratioButtons[0]) // "Libre" sélectionné par défaut à l'ouverture.
    }

    /** Entoure en vert le bouton de ratio actif, remet les autres à leur liseret neutre — le
     *  bouton actif n'était auparavant visuellement pas distinguable des autres. */
    private fun selectRatioButton(selected: com.google.android.material.button.MaterialButton) {
        val green = androidx.core.content.ContextCompat.getColor(this, R.color.green_accent)
        val neutral = android.graphics.Color.parseColor("#33FFFFFF")
        ratioButtons.forEach { button ->
            val isSelected = button === selected
            button.strokeColor = android.content.res.ColorStateList.valueOf(if (isSelected) green else neutral)
            button.strokeWidth = if (isSelected) dp(2f).toInt() else 0
        }
    }

    // ── Rotation / redressement ──────────────────────────────────────────────

    private fun setupRotateControls() {
        findViewById<View>(R.id.btnRotateLeft).setOnClickListener {
            quarterRotation = (quarterRotation - 90 + 360) % 360
            regenerateAfterRotation()
        }
        findViewById<View>(R.id.btnRotateRight).setOnClickListener {
            quarterRotation = (quarterRotation + 90) % 360
            regenerateAfterRotation()
        }
        // La SeekBar va de 0 à 90 ; le centre (45) correspond à 0° (pas de redressement).
        seekStraighten.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Aperçu cosmétique instantané (simple rotation de la vue, gratuit) pendant le
                // glisser ; le bitmap réel n'est régénéré qu'au relâché pour rester fluide.
                ivPhoto.rotation = (progress - 45).toFloat()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                straightenDelta = (seekBar.progress - 45).toFloat()
                ivPhoto.rotation = 0f
                regenerateAfterRotation()
            }
        })
    }

    /** Régénère le bitmap d'aperçu tourné (rotation "en dur", pas juste cosmétique) et remet le
     *  recadrage à zéro : préserver un recadrage à travers une rotation demanderait de
     *  reprojeter un rectangle dans un repère qui vient de changer de dimensions — pas justifié
     *  ici, la plupart des éditeurs grand public réinitialisent aussi le cadrage après rotation. */
    private fun regenerateAfterRotation() {
        val base = basePreviewBitmap ?: return
        val angle = (quarterRotation + straightenDelta)
        val rotated = if (angle == 0f) base else rotateBitmap(base, angle)
        rotatedPreviewBitmap = rotated
        ivPhoto.setImageBitmap(rotated)
        ivPhoto.post {
            val rect = computeImageDisplayRect()
            cropOverlay.setImageRect(rect)
            blurOverlay.setImageRect(rect)
            if (blurEnabled) refreshBlurPreview()
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // ── Flou ─────────────────────────────────────────────────────────────────

    private fun setupBlurControls() {
        blurOverlay.onSelectionChanged = { if (blurEnabled) refreshBlurPreview() }

        val green = androidx.core.content.ContextCompat.getColor(this, R.color.green_accent)
        val neutral = android.graphics.Color.parseColor("#33FFFFFF")

        fun selectEffectButton(selected: com.google.android.material.button.MaterialButton) {
            listOf(btnEffectBlur, btnEffectMosaic).forEach { button ->
                val isSelected = button === selected
                button.strokeColor = android.content.res.ColorStateList.valueOf(if (isSelected) green else neutral)
                button.strokeWidth = if (isSelected) dp(2f).toInt() else 0
            }
        }
        btnEffectBlur.setOnClickListener { blurIsMosaic = false; selectEffectButton(btnEffectBlur); if (blurEnabled) refreshBlurPreview() }
        btnEffectMosaic.setOnClickListener { blurIsMosaic = true; selectEffectButton(btnEffectMosaic); if (blurEnabled) refreshBlurPreview() }
        selectEffectButton(btnEffectBlur) // "Flou" sélectionné par défaut.

        fun selectShapeButton(selected: com.google.android.material.button.MaterialButton) {
            listOf(btnShapeCircle, btnShapeSquare).forEach { button ->
                val isSelected = button === selected
                button.strokeColor = android.content.res.ColorStateList.valueOf(if (isSelected) green else neutral)
                button.strokeWidth = if (isSelected) dp(2f).toInt() else 0
            }
        }
        // Le rond est la sélection par défaut à l'écran (BlurSelectionView.isCircle = true) ; les
        // deux boutons ci-dessous permettent juste de choisir explicitement l'une ou l'autre forme
        // — changer la forme déclenche déjà onSelectionChanged (voir le setter isCircle).
        btnShapeCircle.setOnClickListener { blurOverlay.isCircle = true; selectShapeButton(btnShapeCircle) }
        btnShapeSquare.setOnClickListener { blurOverlay.isCircle = false; selectShapeButton(btnShapeSquare) }
        selectShapeButton(btnShapeCircle)

        seekBlurIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                blurIntensity = progress
                if (blurEnabled) refreshBlurPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }
    private fun selectDefaultBlurControls() {
        val green = androidx.core.content.ContextCompat.getColor(this, R.color.green_accent)
        val neutral = android.graphics.Color.parseColor("#33FFFFFF")
        listOf(btnEffectBlur, btnEffectMosaic).forEach { button ->
            val isSelected = button === btnEffectBlur
            button.strokeColor = android.content.res.ColorStateList.valueOf(if (isSelected) green else neutral)
            button.strokeWidth = if (isSelected) dp(2f).toInt() else 0
        }
        listOf(btnShapeCircle, btnShapeSquare).forEach { button ->
            val isSelected = button === btnShapeCircle
            button.strokeColor = android.content.res.ColorStateList.valueOf(if (isSelected) green else neutral)
            button.strokeWidth = if (isSelected) dp(2f).toInt() else 0
        }
    }


    /** Aperçu en direct de l'effet flou/mosaïque : sans ça, la barre d'intensité (et le
     *  déplacement/redimensionnement de la sélection) n'auraient aucun retour visuel avant
     *  l'enregistrement final, ce qui les rendrait peu utiles. Légèrement anti-rebond (~30ms)
     *  pour rester fluide même en cas de glisser rapide ou de changements très rapprochés,
     *  sans pour autant être perceptible comme un délai par l'utilisateur. */
    private fun refreshBlurPreview() {
        pendingBlurPreview?.let { blurPreviewHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val base = rotatedPreviewBitmap ?: return@Runnable
            val frac = blurOverlay.selectionRectFraction()
            val isCircle = blurOverlay.isCircle
            val isMosaic = blurIsMosaic
            val intensity = blurIntensity
            val preview = applyBlurRegion(base, frac, isCircle, isMosaic, intensity)
            ivPhoto.setImageBitmap(preview)
        }
        pendingBlurPreview = runnable
        blurPreviewHandler.postDelayed(runnable, 30L)
    }

    /** Revient à l'aperçu sans effet (désactivation du flou) — sans repasser par
     *  [refreshBlurPreview] puisqu'il n'y a alors plus rien à calculer. */
    // ── Onglets outils ───────────────────────────────────────────────────────

    private val toolTabs by lazy {
        listOf(
            findViewById<View>(R.id.tabFilters),
            findViewById<View>(R.id.tabCrop),
            findViewById<View>(R.id.tabRotate),
            findViewById<View>(R.id.tabBlur)
        )
    }

    private fun setupToolTabs() {
        findViewById<View>(R.id.tabFilters).setOnClickListener { showPanel(filterPanel) }
        findViewById<View>(R.id.tabCrop).setOnClickListener { showPanel(cropPanel) }
        findViewById<View>(R.id.tabRotate).setOnClickListener { showPanel(rotatePanel) }
        findViewById<View>(R.id.tabBlur).setOnClickListener { showPanel(blurPanel) }
    }

    private fun showPanel(panel: View) {
        filterPanel.visibility = if (panel === filterPanel) View.VISIBLE else View.GONE
        cropPanel.visibility = if (panel === cropPanel) View.VISIBLE else View.GONE
        rotatePanel.visibility = if (panel === rotatePanel) View.VISIBLE else View.GONE
        blurPanel.visibility = if (panel === blurPanel) View.VISIBLE else View.GONE
        cropOverlay.visibility = if (panel === cropPanel) View.VISIBLE else View.GONE
        // Plus de toggle : dès qu'on ouvre l'outil Flou/Mosaïque, l'effet est actif par défaut,
        // avec "Flou" + "Rond" sélectionnés. Tant que l'utilisateur n'a pas ouvert cet outil,
        // l'enregistrement ne modifie pas la photo avec un flou implicite.
        if (panel === blurPanel && !blurEnabled) {
            blurEnabled = true
            blurIsMosaic = false
            blurOverlay.isCircle = true
            selectDefaultBlurControls()
        }
        blurOverlay.visibility = if (panel === blurPanel) View.VISIBLE else View.GONE
        ivPhoto.setComparisonEnabled(
            panel === filterPanel && selectedFilter.id != "normal"
        )
        updateTabTint(panel)
        // Les panneaux n'ont pas tous la même hauteur (celui du flou est plus grand, pour loger
        // interrupteur + 2 rangées de boutons + intensité) : la zone image, qui partage l'espace
        // restant avec le panneau actif, change donc de taille à chaque bascule d'onglet. Sans ce
        // recalcul, les overlays gardaient les anciennes coordonnées et paraissaient décalés par
        // rapport à l'image dès qu'on ouvrait un panneau d'une autre hauteur.
        ivPhoto.post {
            val rect = computeImageDisplayRect()
            cropOverlay.setImageRect(rect, preserveSelection = true)
            blurOverlay.setImageRect(rect, preserveSelection = true)
            if (blurEnabled) refreshBlurPreview()
        }
    }

    /** Colore en vert l'icône + le libellé de l'onglet actif, remet les autres en gris neutre —
     *  auparavant seul l'onglet "Filtres" (actif par défaut à l'ouverture) avait cette couleur
     *  posée en dur dans le XML ; changer d'onglet ne mettait rien à jour. */
    private fun updateTabTint(activePanel: View) {
        val green = androidx.core.content.ContextCompat.getColor(this, R.color.green_accent)
        val neutral = androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant)
        val activeTab = when (activePanel) {
            filterPanel -> R.id.tabFilters
            cropPanel -> R.id.tabCrop
            rotatePanel -> R.id.tabRotate
            blurPanel -> R.id.tabBlur
            else -> R.id.tabFilters
        }
        toolTabs.forEach { tab ->
            val color = if (tab.id == activeTab) green else neutral
            val group = tab as? android.view.ViewGroup ?: return@forEach
            (group.getChildAt(0) as? ImageView)?.setColorFilter(color)
            (group.getChildAt(1) as? TextView)?.setTextColor(color)
        }
    }

    // ── Enregistrement ───────────────────────────────────────────────────────

    private fun performSave() {
        editorProgress.visibility = View.VISIBLE
        val cropFrac = cropOverlay.cropRectFraction()
        val blurFrac = blurOverlay.selectionRectFraction()
        val blurCircle = blurOverlay.isCircle
        val shouldBlur = blurEnabled
        val isMosaic = blurIsMosaic
        val intensity = blurIntensity
        val angle = (quarterRotation + straightenDelta)
        val filterMatrix = selectedFilter.matrix
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                var bmp = decodeSampled(photoPath, 4000)
                if (angle != 0f) bmp = rotateBitmap(bmp, angle)
                // Le flou/mosaïque s'applique avant le recadrage : sa sélection a été dessinée
                // dans le même repère que celle du recadrage (l'image tournée, avant découpe),
                // donc appliquer l'effet d'abord garde les deux cohérents, y compris si le
                // recadrage final exclut une partie de la zone traitée.
                if (shouldBlur) bmp = applyBlurRegion(bmp, blurFrac, blurCircle, isMosaic, intensity)
                val left = (cropFrac.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                val top = (cropFrac.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                val right = (cropFrac.right * bmp.width).toInt().coerceIn(left + 1, bmp.width)
                val bottom = (cropFrac.bottom * bmp.height).toInt().coerceIn(top + 1, bmp.height)
                val cropped = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
                val filtered = applyFilter(cropped, filterMatrix)
                if (returnTempMode) {
                    val dir = File(cacheDir, "slideshow_edits").apply { mkdirs() }
                    val out = File(dir, "slide_edit_${System.currentTimeMillis()}.jpg")
                    val saved = out.outputStream().use { stream ->
                        filtered.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    }
                    if (saved && out.exists() && out.length() > 0L) out.absolutePath else null
                } else {
                    if (MediaSaveUtils.saveEditedBitmap(this@PhotoEditorActivity, filtered, photoName)) "published" else null
                }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                editorProgress.visibility = View.GONE
                if (ok != null) {
                    if (returnTempMode) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_PATH, ok))
                        finish()
                    } else {
                        setResult(RESULT_OK, Intent())
                        showAfterSaveDialog()
                    }
                } else {
                    Toast.makeText(this@PhotoEditorActivity, getString(R.string.toast_photo_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** À chaque enregistrement réussi (filtre, recadrage, rotation ou flou/mosaïque confondus),
     *  demande explicitement si l'utilisateur veut continuer à modifier cette même photo ou
     *  retourner à la galerie — plutôt que de décider à sa place. Se reproduit à chaque
     *  validation, pas seulement la première fois. */
    private fun showAfterSaveDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_after_save_title))
            .setMessage(getString(R.string.dialog_after_save_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.action_stay_editing)) { _, _ ->
                showPanel(filterPanel) // Retour à l'onglet par défaut, comme demandé.
            }
            .setNegativeButton(getString(R.string.action_back_to_gallery)) { _, _ -> finish() }
            .showPremium()
    }

    /** Applique un flou (boîte glissante, [SimpleBlur.blur]) ou une mosaïque ([SimpleBlur.pixelate])
     *  selon [isMosaic], uniquement dans la zone sélectionnée par l'outil, en forme de carré ou de
     *  rond selon [isCircle]. [intensity] (0..100, réglé par la barre du panneau) module un rayon/
     *  une taille de bloc eux-mêmes proportionnels à la taille de la zone sélectionnée — pour
     *  rester efficace aussi bien sur une petite zone que sur une grande, à intensité égale. */
    private fun applyBlurRegion(src: Bitmap, frac: RectF, isCircle: Boolean, isMosaic: Boolean, intensity: Int): Bitmap {
        val left = (frac.left * src.width).toInt().coerceIn(0, src.width - 1)
        val top = (frac.top * src.height).toInt().coerceIn(0, src.height - 1)
        val right = (frac.right * src.width).toInt().coerceIn(left + 1, src.width)
        val bottom = (frac.bottom * src.height).toInt().coerceIn(top + 1, src.height)
        val regionW = right - left
        val regionH = bottom - top
        if (regionW <= 0 || regionH <= 0) return src

        val region = Bitmap.createBitmap(src, left, top, regionW, regionH)
        val intensityFactor = 0.3f + (intensity / 100f) * 1.7f
        val processedRegion = if (isMosaic) {
            val blockSize = (minOf(regionW, regionH) * 0.06f * intensityFactor).toInt().coerceIn(2, 60)
            SimpleBlur.pixelate(region, blockSize)
        } else {
            val radius = (minOf(regionW, regionH) * 0.08f * intensityFactor).toInt().coerceIn(2, 60)
            SimpleBlur.blur(region, radius)
        }

        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        if (isCircle) {
            // Masque la zone traitée en cercle : on ne garde que le disque inscrit dans le
            // rectangle de sélection (SRC_IN sur une ellipse pleine).
            val masked = Bitmap.createBitmap(regionW, regionH, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(masked)
            val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            maskCanvas.drawOval(RectF(0f, 0f, regionW.toFloat(), regionH.toFloat()), ovalPaint)
            ovalPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            maskCanvas.drawBitmap(processedRegion, 0f, 0f, ovalPaint)
            canvas.drawBitmap(masked, left.toFloat(), top.toFloat(), null)
        } else {
            canvas.drawBitmap(processedRegion, left.toFloat(), top.toFloat(), null)
        }
        return result
    }

    private fun applyFilter(src: Bitmap, matrix: android.graphics.ColorMatrix): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }
}
