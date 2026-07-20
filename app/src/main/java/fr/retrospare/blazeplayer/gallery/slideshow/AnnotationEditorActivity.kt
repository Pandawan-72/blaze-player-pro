package fr.retrospare.blazeplayer.gallery.slideshow

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream

class AnnotationEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_PATH = "annotation_source_path"
        const val EXTRA_TEXT = "annotation_text"
        const val EXTRA_COLOR = "annotation_color"
        const val EXTRA_SHAPE = "annotation_shape"
        const val EXTRA_FONT = "annotation_font"
        const val EXTRA_SIZE = "annotation_size"
        const val EXTRA_X = "annotation_x"
        const val EXTRA_Y = "annotation_y"
        const val EXTRA_GIF_URI = "annotation_gif_uri"
        const val EXTRA_GIF_X = "annotation_gif_x"
        const val EXTRA_GIF_Y = "annotation_gif_y"
        const val EXTRA_GIF_WIDTH = "annotation_gif_width"
    }

    private lateinit var canvasView: AnnotationEditorView
    private lateinit var textInput: EditText
    private lateinit var sizeLabel: TextView
    private lateinit var gifRemove: MaterialButton
    private var selectedFont = 0
    private var selectedShape = AnnotationEditorView.SHAPE_PILL
    private var selectedColor = 0xD91A1D24.toInt()

    private val gifPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        val type = contentResolver.getType(uri).orEmpty().lowercase()
        val name = queryDisplayName(uri).orEmpty().lowercase()
        if (type != "image/gif" && !name.endsWith(".gif")) {
            Toast.makeText(this, R.string.slideshow_gif_invalid, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        canvasView.setGifUri(uri)
        gifRemove.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slideshow_annotation_editor)
        canvasView = findViewById(R.id.annotationEditorCanvas)
        textInput = findViewById(R.id.etAnnotationEditorText)
        sizeLabel = findViewById(R.id.tvAnnotationSize)
        gifRemove = findViewById(R.id.btnAnnotationGifRemove)
        canvasView.post {
            val targetHeight = (canvasView.width * 9f / 16f).toInt().coerceAtLeast(1)
            if (canvasView.layoutParams.height != targetHeight) {
                canvasView.layoutParams = canvasView.layoutParams.apply { height = targetHeight }
            }
        }

        selectedFont = intent.getIntExtra(EXTRA_FONT, 0).coerceIn(0, SlideshowFontCatalog.options.lastIndex)
        selectedShape = intent.getIntExtra(EXTRA_SHAPE, AnnotationEditorView.SHAPE_PILL)
        selectedColor = intent.getIntExtra(EXTRA_COLOR, 0xD91A1D24.toInt())
        canvasView.annotationText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        canvasView.annotationColor = selectedColor
        canvasView.annotationShape = selectedShape
        canvasView.annotationSizeFraction = intent.getFloatExtra(EXTRA_SIZE, 0.060f)
        canvasView.setAnnotationPosition(
            intent.getFloatExtra(EXTRA_X, 0.50f),
            intent.getFloatExtra(EXTRA_Y, 0.78f)
        )
        canvasView.setGifPosition(
            intent.getFloatExtra(EXTRA_GIF_X, 0.50f),
            intent.getFloatExtra(EXTRA_GIF_Y, 0.45f)
        )
        canvasView.setGifWidthFraction(intent.getFloatExtra(EXTRA_GIF_WIDTH, 0.28f))
        intent.getStringExtra(EXTRA_GIF_URI)?.takeIf { it.isNotBlank() }?.let {
            canvasView.setGifUri(Uri.parse(it))
            gifRemove.visibility = View.VISIBLE
        }

        textInput.setText(canvasView.annotationText)
        textInput.setSelection(textInput.text?.length ?: 0)
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                canvasView.annotationText = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        SystemBarsInsets.apply(this, R.id.rootAnnotationEditor)

        setupFonts()
        setupColors()
        setupShapes()
        setupSize()

        findViewById<View>(R.id.btnAnnotationEditorClose).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnAnnotationEditorSave).setOnClickListener { saveAndClose() }
        findViewById<MaterialButton>(R.id.btnAnnotationGifChoose).setOnClickListener {
            gifPicker.launch(arrayOf("image/gif"))
        }
        gifRemove.setOnClickListener {
            canvasView.setGifUri(null)
            it.visibility = View.GONE
        }

        val source = intent.getStringExtra(EXTRA_SOURCE_PATH).orEmpty()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decodeSampled(source, 2400) }.getOrNull() }
            if (bitmap == null) {
                Toast.makeText(this@AnnotationEditorActivity, R.string.slideshow_export_failed, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                canvasView.setBitmap(bitmap)
            }
        }
    }

    private fun setupFonts() {
        val container = findViewById<LinearLayout>(R.id.annotationFontContainer)
        SlideshowFontCatalog.options.forEachIndexed { index, option ->
            val button = MaterialButton(this).apply {
                text = getString(option.nameRes)
                minWidth = 0
                isAllCaps = false
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    selectedFont = index
                    updateFontSelection(container)
                    SlideshowFontCatalog.request(this@AnnotationEditorActivity, index) { tf ->
                        if (selectedFont == index) canvasView.annotationTypeface = tf
                    }
                }
            }
            container.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginEnd = dp(6)
            })
        }
        updateFontSelection(container)
        canvasView.annotationTypeface = SlideshowFontCatalog.fallback(selectedFont)
        SlideshowFontCatalog.request(this, selectedFont) { tf -> canvasView.annotationTypeface = tf }
    }

    private fun updateFontSelection(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val button = container.getChildAt(i) as? MaterialButton ?: continue
            button.alpha = if (i == selectedFont) 1f else 0.62f
            button.strokeWidth = if (i == selectedFont) dp(2) else dp(1)
        }
    }

    private fun setupColors() {
        val colors = intArrayOf(
            0xDC111319.toInt(), 0xDC5C1733.toInt(), 0xDC6A1B9A.toInt(), 0xDC203A8F.toInt(),
            0xDC006B73.toInt(), 0xDC176A3A.toInt(), 0xDC9A4B00.toInt(), 0xDC8B1E1E.toInt()
        )
        val container = findViewById<LinearLayout>(R.id.annotationColorContainer)
        colors.forEach { color ->
            val swatch = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    setStroke(if (sameRgb(color, selectedColor)) dp(3) else dp(1), Color.WHITE)
                }
                contentDescription = getString(R.string.slideshow_annotation_background_color)
                setOnClickListener {
                    selectedColor = color
                    canvasView.annotationColor = color
                    refreshColorSwatches(container, colors)
                }
            }
            container.addView(swatch, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
        }
    }

    private fun refreshColorSwatches(container: LinearLayout, colors: IntArray) {
        for (i in 0 until minOf(container.childCount, colors.size)) {
            val color = colors[i]
            container.getChildAt(i).background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                setStroke(if (sameRgb(color, selectedColor)) dp(3) else dp(1), Color.WHITE)
            }
        }
    }

    private fun sameRgb(a: Int, b: Int): Boolean = ColorUtils.setAlphaComponent(a, 255) == ColorUtils.setAlphaComponent(b, 255)

    private fun setupShapes() {
        val descriptions = intArrayOf(
            R.string.slideshow_annotation_shape_pill,
            R.string.slideshow_annotation_shape_parallelogram,
            R.string.slideshow_annotation_shape_rectangle
        )
        val container = findViewById<LinearLayout>(R.id.annotationShapeContainer)

        fun refresh() {
            for (i in 0 until container.childCount) {
                val view = container.getChildAt(i)
                view.background = AnnotationShapeChoiceDrawable(i, i == selectedShape)
                view.alpha = if (i == selectedShape) 1f else 0.82f
            }
        }

        descriptions.forEachIndexed { index, description ->
            val button = View(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = getString(description)
                background = AnnotationShapeChoiceDrawable(index, index == selectedShape)
                alpha = if (index == selectedShape) 1f else 0.82f
                setOnClickListener {
                    selectedShape = index
                    canvasView.annotationShape = index
                    refresh()
                }
            }
            container.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                if (index > 0) marginStart = dp(12)
            })
        }
    }

    /**
     * Aperçu purement graphique des trois formes d'annotation. Aucun libellé visible :
     * la forme du bouton est elle-même l'explication du choix. Le texte localisé reste utilisé
     * comme contentDescription pour TalkBack.
     */
    private inner class AnnotationShapeChoiceDrawable(
        private val shapeKind: Int,
        private val selected: Boolean
    ) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (selected) 0xE63A2A56.toInt() else 0xD922252C.toInt()
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(if (selected) 2 else 1).toFloat()
            color = if (selected) 0xFFB89BFF.toInt() else 0x667D8490.toInt()
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val inset = dp(3).toFloat()
            val r = RectF(bounds).apply { inset(inset, inset) }
            when (shapeKind) {
                AnnotationEditorView.SHAPE_PARALLELOGRAM -> {
                    val skew = minOf(r.height() * 0.42f, r.width() * 0.14f)
                    val path = Path().apply {
                        moveTo(r.left + skew, r.top)
                        lineTo(r.right, r.top)
                        lineTo(r.right - skew, r.bottom)
                        lineTo(r.left, r.bottom)
                        close()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, stroke)
                }
                AnnotationEditorView.SHAPE_RECTANGLE -> {
                    canvas.drawRect(r, fill)
                    canvas.drawRect(r, stroke)
                }
                else -> {
                    val radius = r.height() / 2f
                    canvas.drawRoundRect(r, radius, radius, fill)
                    canvas.drawRoundRect(r, radius, radius, stroke)
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            stroke.alpha = alpha
            invalidateSelf()
        }
        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            stroke.colorFilter = colorFilter
            invalidateSelf()
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun setupSize() {
        val seek = findViewById<SeekBar>(R.id.seekAnnotationSize)
        val minSize = 0.030f
        val maxSize = 0.115f
        val initial = ((canvasView.annotationSizeFraction - minSize) / (maxSize - minSize) * 100f).toInt().coerceIn(0, 100)
        seek.progress = initial
        updateSizeLabel()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                canvasView.annotationSizeFraction = minSize + (maxSize - minSize) * (progress / 100f)
                updateSizeLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateSizeLabel() {
        val percent = (canvasView.annotationSizeFraction / 0.060f * 100f).toInt()
        sizeLabel.text = getString(R.string.slideshow_annotation_size_value, percent)
    }

    private fun saveAndClose() {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_TEXT, canvasView.annotationText.trim())
                putExtra(EXTRA_COLOR, selectedColor)
                putExtra(EXTRA_SHAPE, selectedShape)
                putExtra(EXTRA_FONT, selectedFont)
                putExtra(EXTRA_SIZE, canvasView.annotationSizeFraction)
                putExtra(EXTRA_X, canvasView.annotationCenterX)
                putExtra(EXTRA_Y, canvasView.annotationCenterY)
                putExtra(EXTRA_GIF_URI, canvasView.currentGifUri()?.toString().orEmpty())
                putExtra(EXTRA_GIF_X, canvasView.gifCenterX)
                putExtra(EXTRA_GIF_Y, canvasView.gifCenterY)
                putExtra(EXTRA_GIF_WIDTH, canvasView.gifWidthFraction)
            }
        )
        finish()
    }

    private fun decodeSampled(path: String, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openInput(path).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Unable to decode $path")
        return applyExifOrientation(path, decoded)
    }

    private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            openInput(path).use { input ->
                android.media.ExifInterface(input).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { rotated ->
                if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            }
        }.getOrDefault(bitmap)
    }

    private fun openInput(path: String): InputStream = when {
        path.startsWith("content://") -> contentResolver.openInputStream(Uri.parse(path)) ?: error("Unreadable URI")
        path.startsWith("file://") -> FileInputStream(Uri.parse(path).path ?: error("Invalid file URI"))
        else -> FileInputStream(path)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
