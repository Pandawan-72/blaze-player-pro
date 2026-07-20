package fr.retrospare.blazeplayer.gallery.slideshow

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.button.MaterialButton
import fr.retrospare.blazeplayer.R
import fr.retrospare.blazeplayer.gallery.edit.MediaSaveUtils
import fr.retrospare.blazeplayer.gallery.edit.PhotoEditorActivity
import fr.retrospare.blazeplayer.ui.ThumbnailUtils
import fr.retrospare.blazeplayer.ui.premiumButtons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Créateur de diaporamas Blaze Gallery.
 *
 * - Les photos restent dans l'ordre choisi (réordonnables par glisser-déposer).
 * - Chaque photo peut être renvoyée dans l'éditeur existant en mode temporaire.
 * - Les annotations sont rendues par Canvas avant l'encodage : aucune dépendance à drawtext/font
 *   FFmpeg et rendu identique sur tous les appareils.
 * - La durée se cale sur le MP3/WAV choisi. L'utilisateur peut la raccourcir ; le son est alors
 *   fondu en sortie.
 * - Export MP4 1080p/30 fps. L'audio est encodé en MP3 128 kb/s comme demandé.
 */
class SlideshowActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_PATHS = "slideshow_photo_paths"
        const val EXTRA_PHOTO_NAMES = "slideshow_photo_names"
        private const val OUTPUT_WIDTH = 1920
        private const val OUTPUT_HEIGHT = 1080
        private const val DEFAULT_MS_PER_SLIDE = 4_000L
        private const val MIN_MS_PER_SLIDE = 1_500L
        @Volatile private var cachedVideoEncoderName: String? = null
    }

    private data class Slide(
        var sourcePath: String,
        val originalPath: String,
        var name: String,
        var annotation: String = "",
        var annotationColor: Int = 0xD91A1D24.toInt(),
        var annotationShape: Int = AnnotationEditorView.SHAPE_PILL,
        var annotationFont: Int = 0,
        var annotationSizeFraction: Float = 0.060f,
        var annotationX: Float = 0.50f,
        var annotationY: Float = 0.78f,
        var gifUri: String? = null,
        var gifX: Float = 0.50f,
        var gifY: Float = 0.45f,
        var gifWidthFraction: Float = 0.28f
    )

    private data class PreparedSlide(
        val frame: File,
        val gif: File?,
        val slide: Slide,
        val index: Int
    )

    private data class VideoEncoderSpec(
        val name: String,
        val arguments: List<String>
    )

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: SlideAdapter
    private lateinit var tvMusic: TextView
    private lateinit var tvDuration: TextView
    private lateinit var seekDuration: SeekBar
    private lateinit var progress: View
    private lateinit var btnExport: MaterialButton

    private val slides = mutableListOf<Slide>()
    private val slideshowTypefaceCache = mutableMapOf<Int, android.graphics.Typeface>()
    private var musicUri: Uri? = null
    private var musicName: String? = null
    private var musicDurationMs: Long = 0L
    private var chosenDurationMs: Long = 0L
    private var editingIndex: Int = RecyclerView.NO_POSITION
    private var annotationEditingIndex: Int = RecyclerView.NO_POSITION

    private val musicPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        val duration = readMediaDuration(uri)
        if (duration <= 0L) {
            Toast.makeText(this, R.string.slideshow_music_invalid, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        musicUri = uri
        musicDurationMs = duration
        chosenDurationMs = duration
        musicName = queryDisplayName(uri) ?: uri.lastPathSegment ?: getString(R.string.slideshow_music_selected)
        updateDurationUi(resetSeek = true)
    }

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val index = editingIndex
        editingIndex = RecyclerView.NO_POSITION
        if (result.resultCode != RESULT_OK || index !in slides.indices) return@registerForActivityResult
        val output = result.data?.getStringExtra(PhotoEditorActivity.EXTRA_OUTPUT_PATH).orEmpty()
        if (output.isNotBlank() && File(output).isFile) {
            val previous = slides[index].sourcePath
            slides[index].sourcePath = output
            if (previous != slides[index].originalPath) deleteTempEdit(previous)
            adapter.notifyItemChanged(index)
        }
    }

    private val photoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val paths = data.getStringArrayListExtra(SlideshowPhotoPickerActivity.EXTRA_RESULT_PATHS).orEmpty()
        val names = data.getStringArrayListExtra(SlideshowPhotoPickerActivity.EXTRA_RESULT_NAMES).orEmpty()
        val existing = slides.map { it.originalPath }.toHashSet()
        var added = 0
        paths.forEachIndexed { index, path ->
            if (path.isBlank() || path in existing) return@forEachIndexed
            slides += Slide(
                sourcePath = path,
                originalPath = path,
                name = names.getOrNull(index).orEmpty().ifBlank { getString(R.string.slideshow_photo_number, slides.size + 1) }
            )
            existing += path
            added++
        }
        if (added > 0) {
            adapter.notifyItemRangeInserted(slides.size - added, added)
            if (musicDurationMs <= 0L) chosenDurationMs = slides.size * DEFAULT_MS_PER_SLIDE
            updateDurationUi(resetSeek = true)
            Toast.makeText(this, getString(R.string.slideshow_added_count, added), Toast.LENGTH_SHORT).show()
        }
    }

    private val annotationEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val index = annotationEditingIndex
        annotationEditingIndex = RecyclerView.NO_POSITION
        if (result.resultCode != RESULT_OK || index !in slides.indices) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        slides[index].apply {
            annotation = data.getStringExtra(AnnotationEditorActivity.EXTRA_TEXT).orEmpty()
            annotationColor = data.getIntExtra(AnnotationEditorActivity.EXTRA_COLOR, annotationColor)
            annotationShape = data.getIntExtra(AnnotationEditorActivity.EXTRA_SHAPE, annotationShape)
            annotationFont = data.getIntExtra(AnnotationEditorActivity.EXTRA_FONT, annotationFont)
            annotationSizeFraction = data.getFloatExtra(AnnotationEditorActivity.EXTRA_SIZE, annotationSizeFraction)
            annotationX = data.getFloatExtra(AnnotationEditorActivity.EXTRA_X, annotationX)
            annotationY = data.getFloatExtra(AnnotationEditorActivity.EXTRA_Y, annotationY)
            gifUri = data.getStringExtra(AnnotationEditorActivity.EXTRA_GIF_URI)?.takeIf { it.isNotBlank() }
            gifX = data.getFloatExtra(AnnotationEditorActivity.EXTRA_GIF_X, gifX)
            gifY = data.getFloatExtra(AnnotationEditorActivity.EXTRA_GIF_Y, gifY)
            gifWidthFraction = data.getFloatExtra(AnnotationEditorActivity.EXTRA_GIF_WIDTH, gifWidthFraction)
        }
        adapter.notifyItemChanged(index)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slideshow)

        SystemBarsInsets.apply(this, R.id.rootSlideshow)

        val paths = intent.getStringArrayListExtra(EXTRA_PHOTO_PATHS).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_PHOTO_NAMES).orEmpty()
        paths.forEachIndexed { index, path ->
            slides += Slide(path, path, names.getOrNull(index).orEmpty().ifBlank { getString(R.string.slideshow_photo_number, index + 1) })
        }
        if (slides.size < 2) {
            Toast.makeText(this, R.string.slideshow_need_two_photos, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        recycler = findViewById(R.id.listSlideshowPhotos)
        tvMusic = findViewById(R.id.tvSlideshowMusic)
        tvDuration = findViewById(R.id.tvSlideshowDuration)
        seekDuration = findViewById(R.id.seekSlideshowDuration)
        progress = findViewById(R.id.slideshowProgress)
        btnExport = findViewById(R.id.btnSlideshowExport)

        findViewById<View>(R.id.btnSlideshowClose).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnSlideshowMusic).setOnClickListener {
            musicPicker.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/wave"))
        }
        findViewById<MaterialButton>(R.id.btnSlideshowRemoveMusic).setOnClickListener {
            musicUri = null
            musicName = null
            musicDurationMs = 0L
            chosenDurationMs = slides.size * DEFAULT_MS_PER_SLIDE
            updateDurationUi(resetSeek = true)
        }
        findViewById<MaterialButton>(R.id.btnSlideshowAddPhotos).setOnClickListener {
            photoPickerLauncher.launch(Intent(this, SlideshowPhotoPickerActivity::class.java).apply {
                putStringArrayListExtra(
                    SlideshowPhotoPickerActivity.EXTRA_EXISTING_PATHS,
                    ArrayList(slides.map { it.originalPath })
                )
                putStringArrayListExtra(
                    SlideshowPhotoPickerActivity.EXTRA_EXISTING_NAMES,
                    ArrayList(slides.map { it.name })
                )
            })
        }
        btnExport.setOnClickListener { showExportNameDialog() }

        adapter = SlideAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.itemAnimator = null

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in slides.indices || to !in slides.indices) return false
                Collections.swap(slides, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(recycler)

        chosenDurationMs = slides.size * DEFAULT_MS_PER_SLIDE
        seekDuration.max = 1000
        seekDuration.progress = 1000
        seekDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val maximum = if (musicDurationMs > 0) musicDurationMs else slides.size * 10_000L
                val minimum = min(maximum, max(5_000L, slides.size * MIN_MS_PER_SLIDE))
                val fraction = progress / 1000f
                chosenDurationMs = (minimum + ((maximum - minimum) * fraction)).toLong().coerceIn(minimum, maximum)
                updateDurationLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateDurationUi(resetSeek = true)
    }

    override fun onDestroy() {
        if (isFinishing) {
            slides.forEach { slide ->
                if (slide.sourcePath != slide.originalPath) deleteTempEdit(slide.sourcePath)
            }
        }
        super.onDestroy()
    }

    private fun deleteTempEdit(path: String) {
        runCatching {
            val file = File(path)
            val root = File(cacheDir, "slideshow_edits").canonicalFile
            if (file.canonicalFile.path.startsWith(root.path + File.separator)) file.delete()
        }
    }

    private fun updateDurationUi(resetSeek: Boolean) {
        tvMusic.text = musicName ?: getString(R.string.slideshow_no_music)
        if (resetSeek) {
            val maximum = if (musicDurationMs > 0) musicDurationMs else slides.size * 10_000L
            val minimum = min(maximum, max(5_000L, slides.size * MIN_MS_PER_SLIDE))
            seekDuration.progress = if (maximum <= minimum) 1000 else
                (((chosenDurationMs - minimum).coerceIn(0L, maximum - minimum)).toDouble() / (maximum - minimum) * 1000.0).toInt()
        }
        updateDurationLabels()
        findViewById<MaterialButton>(R.id.btnSlideshowRemoveMusic).visibility = if (musicUri != null) View.VISIBLE else View.GONE
    }

    private fun updateDurationLabels() {
        val perPhoto = chosenDurationMs.toDouble() / slides.size / 1000.0
        tvDuration.text = getString(
            R.string.slideshow_duration_summary,
            formatDuration(chosenDurationMs),
            String.format(Locale.getDefault(), "%.1f", perPhoto)
        )
    }

    private fun showAnnotationEditor(position: Int) {
        if (position !in slides.indices) return
        annotationEditingIndex = position
        val slide = slides[position]
        annotationEditorLauncher.launch(Intent(this, AnnotationEditorActivity::class.java).apply {
            putExtra(AnnotationEditorActivity.EXTRA_SOURCE_PATH, slide.sourcePath)
            putExtra(AnnotationEditorActivity.EXTRA_TEXT, slide.annotation)
            putExtra(AnnotationEditorActivity.EXTRA_COLOR, slide.annotationColor)
            putExtra(AnnotationEditorActivity.EXTRA_SHAPE, slide.annotationShape)
            putExtra(AnnotationEditorActivity.EXTRA_FONT, slide.annotationFont)
            putExtra(AnnotationEditorActivity.EXTRA_SIZE, slide.annotationSizeFraction)
            putExtra(AnnotationEditorActivity.EXTRA_X, slide.annotationX)
            putExtra(AnnotationEditorActivity.EXTRA_Y, slide.annotationY)
            putExtra(AnnotationEditorActivity.EXTRA_GIF_URI, slide.gifUri.orEmpty())
            putExtra(AnnotationEditorActivity.EXTRA_GIF_X, slide.gifX)
            putExtra(AnnotationEditorActivity.EXTRA_GIF_Y, slide.gifY)
            putExtra(AnnotationEditorActivity.EXTRA_GIF_WIDTH, slide.gifWidthFraction)
        })
    }

    private fun editPhoto(position: Int) {
        if (position !in slides.indices) return
        editingIndex = position
        val slide = slides[position]
        editorLauncher.launch(Intent(this, PhotoEditorActivity::class.java).apply {
            putExtra(PhotoEditorActivity.EXTRA_PHOTO_PATH, slide.sourcePath)
            putExtra(PhotoEditorActivity.EXTRA_PHOTO_NAME, slide.name)
            putExtra(PhotoEditorActivity.EXTRA_RETURN_TEMP, true)
        })
    }

    private fun showExportNameDialog() {
        val defaultName = "Blaze_Diapo_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val input = EditText(this).apply {
            setText(defaultName)
            setSelection(text.length)
            hint = getString(R.string.slideshow_export_name_hint)
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.slideshow_export_name_title)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.slideshow_export, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sanitized = sanitizeExportName(input.text?.toString().orEmpty())
                if (sanitized.isBlank()) {
                    input.error = getString(R.string.slideshow_export_name_invalid)
                } else {
                    dialog.dismiss()
                    exportSlideshow(sanitized)
                }
            }
        }
        dialog.show()
        dialog.premiumButtons()
    }

    private fun sanitizeExportName(raw: String): String = raw
        .trim()
        .removeSuffix(".mp4")
        .removeSuffix(".MP4")
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim(' ', '.')
        .take(100)

    private fun exportSlideshow(exportName: String) {
        if (slides.size < 2 || chosenDurationMs <= 0L) return
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { buildSlideshow(exportName) }.getOrNull()
            withContext(Dispatchers.Main) {
                setBusy(false)
                if (result != null) {
                    Toast.makeText(this@SlideshowActivity, R.string.slideshow_export_success, Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK, Intent().setData(result))
                    finish()
                } else {
                    Toast.makeText(this@SlideshowActivity, R.string.slideshow_export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildSlideshow(exportName: String): Uri? {
        val work = File(cacheDir, "slideshow_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val durationPerSlideMs = max(1L, chosenDurationMs / slides.size)

            // Prépare les images une seule fois. L'ancienne version alternait rendu JPEG puis
            // lancement d'une session FFmpeg pour chaque slide ; on conserve les frames prêtes
            // afin de pouvoir basculer d'un encodeur matériel vers le fallback logiciel sans
            // redécoder ni retraiter les photos.
            val prepared = slides.mapIndexed { index, slide ->
                val frame = File(work, "slide_%03d.jpg".format(Locale.US, index))
                renderSlide(slide, frame)
                val gif = slide.gifUri?.let { uriString ->
                    copyUriToCache(Uri.parse(uriString), work, "gif_%03d".format(Locale.US, index))
                }
                PreparedSlide(frame, gif, slide, index)
            }

            val preferredEncoder = selectFastVideoEncoder(prepared.first().frame, work)
            var segments = encodeAllSegments(prepared, durationPerSlideMs, work, preferredEncoder)

            // Certains firmwares annoncent un encodeur MediaCodec mais échouent avec une chaîne
            // de filtres (notamment lorsqu'un GIF est superposé). Dans ce cas on repart proprement
            // avec le MPEG-4 natif, sans jamais produire un fichier composé de codecs différents.
            if (segments == null && preferredEncoder.name != "mpeg4") {
                android.util.Log.w("BlazeDiapo", "Encodeur ${preferredEncoder.name} indisponible pour ce projet, fallback mpeg4")
                segments = encodeAllSegments(prepared, durationPerSlideMs, work, softwareMpeg4Encoder())
                cachedVideoEncoderName = "mpeg4"
            }
            val finalSegments = segments ?: return null
            if (finalSegments.any { !it.isFile || it.length() == 0L }) return null

            val concat = File(work, "segments.txt")
            concat.bufferedWriter().use { writer ->
                finalSegments.forEach { file -> writer.append("file '").append(file.absolutePath).append("'\n") }
            }
            val silentVideo = File(work, "video_silent.mp4")
            val concatSession = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y", "-hide_banner", "-loglevel", "warning",
                    "-f", "concat", "-safe", "0", "-i", concat.absolutePath,
                    "-c", "copy", "-movflags", "+faststart", silentVideo.absolutePath
                )
            )
            if (!ReturnCode.isSuccess(concatSession.returnCode) || !silentVideo.isFile || silentVideo.length() == 0L) {
                android.util.Log.w("BlazeDiapo", "Concat segments échoué: ${concatSession.allLogsAsString}")
                return null
            }

            val musicFile = musicUri?.let { copyUriToCache(it, work, "music_source") }
            val out = File(work, "export.mp4")
            val success = if (musicFile != null) {
                encodeFinalWithMusic(silentVideo, musicFile, out, "libmp3lame") ||
                    encodeFinalWithMusic(silentVideo, musicFile, out, "libshine") ||
                    encodeFinalWithMusic(silentVideo, musicFile, out, "mp3")
            } else {
                silentVideo.copyTo(out, overwrite = true)
                out.isFile && out.length() > 0L
            }
            if (!success) return null
            return MediaSaveUtils.publishProcessedFile(this, out, "$exportName.mp4", "video/mp4")
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    private fun encodeAllSegments(
        prepared: List<PreparedSlide>,
        durationMs: Long,
        work: File,
        encoder: VideoEncoderSpec
    ): List<File>? {
        val output = ArrayList<File>(prepared.size)
        prepared.forEach { item ->
            val segment = File(work, "segment_%03d.mp4".format(Locale.US, item.index))
            if (!encodeSlideSegment(
                    frame = item.frame,
                    gif = item.gif,
                    slide = item.slide,
                    durationMs = durationMs,
                    out = segment,
                    encoder = encoder,
                    fadeIn = item.index > 0,
                    fadeOut = item.index < prepared.lastIndex
                )) {
                output.forEach { runCatching { it.delete() } }
                runCatching { segment.delete() }
                return null
            }
            output += segment
        }
        return output
    }

    /**
     * Choisit le chemin d'encodage le plus rapide réellement utilisable sur l'appareil :
     * 1) H.264 MediaCodec matériel si le build FFmpeg et le téléphone l'exposent ;
     * 2) libx264 ultrafast si exceptionnellement présent ;
     * 3) MPEG-4 Part 2 natif multithread, disponible dans notre build FFmpegKit.
     *
     * Un mini-probe de 0,25 s évite de faire échouer un export complet sur les appareils qui
     * annoncent un encodeur matériel mais refusent le format de surface/filtre demandé.
     */
    private fun selectFastVideoEncoder(frame: File, work: File): VideoEncoderSpec {
        val cached = cachedVideoEncoderName
        if (cached != null) return encoderByName(cached)

        val encoderList = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-encoders")).allLogsAsString.orEmpty()
        val candidates = buildList {
            if (encoderList.contains("h264_mediacodec")) add(hardwareH264Encoder())
            if (encoderList.contains("libx264")) add(x264UltraFastEncoder())
            add(softwareMpeg4Encoder())
        }
        for (candidate in candidates) {
            val probe = File(work, "encoder_probe_${candidate.name}.mp4")
            if (probeVideoEncoder(frame, probe, candidate)) {
                runCatching { probe.delete() }
                cachedVideoEncoderName = candidate.name
                android.util.Log.i("BlazeDiapo", "Encodeur vidéo sélectionné: ${candidate.name}")
                return candidate
            }
            runCatching { probe.delete() }
        }
        return softwareMpeg4Encoder()
    }

    private fun probeVideoEncoder(frame: File, out: File, encoder: VideoEncoderSpec): Boolean {
        val args = mutableListOf(
            "-y", "-hide_banner", "-loglevel", "error",
            "-loop", "1", "-framerate", "1", "-i", frame.absolutePath,
            "-t", "0.25", "-vf", "fps=30,format=yuv420p", "-an"
        )
        args += encoder.arguments
        args += listOf("-r", "30", "-movflags", "+faststart", out.absolutePath)
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        return ReturnCode.isSuccess(session.returnCode) && out.isFile && out.length() > 0L
    }

    private fun hardwareH264Encoder() = VideoEncoderSpec(
        "h264_mediacodec",
        // 7 Mb/s reste très propre en 1080p30 pour un diaporama majoritairement statique et
        // réduit encore légèrement la charge d'encodage et d'écriture.
        listOf("-c:v", "h264_mediacodec", "-b:v", "7M")
    )

    private fun x264UltraFastEncoder() = VideoEncoderSpec(
        "libx264",
        // Preset ultrafast conservé ; CRF légèrement relâché pour accélérer et alléger la sortie.
        listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "23", "-pix_fmt", "yuv420p", "-threads", "0")
    )

    private fun softwareMpeg4Encoder() = VideoEncoderSpec(
        "mpeg4",
        // q=6 accélère encore légèrement le fallback logiciel tout en restant propre
        // sur des photos affichées en 1080p.
        listOf("-c:v", "mpeg4", "-q:v", "6", "-pix_fmt", "yuv420p", "-threads", "0")
    )

    private fun encoderByName(name: String): VideoEncoderSpec = when (name) {
        "h264_mediacodec" -> hardwareH264Encoder()
        "libx264" -> x264UltraFastEncoder()
        else -> softwareMpeg4Encoder()
    }

    private fun encodeSlideSegment(
        frame: File,
        gif: File?,
        slide: Slide,
        durationMs: Long,
        out: File,
        encoder: VideoEncoderSpec,
        fadeIn: Boolean,
        fadeOut: Boolean
    ): Boolean {
        if (out.exists()) out.delete()
        val durationSeconds = durationMs / 1000.0
        // Transition courte via noir. Elle est intégrée à chaque segment avant la concaténation,
        // donc aucun second réencodage global du diaporama n'est nécessaire.
        val transitionSeconds = min(0.22, max(0.08, durationSeconds / 6.0))
        val fadeFilters = buildList {
            if (fadeIn) add("fade=t=in:st=0:d=${String.format(Locale.US, "%.3f", transitionSeconds)}")
            if (fadeOut) {
                val start = max(0.0, durationSeconds - transitionSeconds)
                add("fade=t=out:st=${String.format(Locale.US, "%.3f", start)}:d=${String.format(Locale.US, "%.3f", transitionSeconds)}")
            }
        }
        val fadeSuffix = if (fadeFilters.isEmpty()) "" else "," + fadeFilters.joinToString(",")

        val args = mutableListOf(
            "-y", "-hide_banner", "-loglevel", "warning",
            // La photo n'est décodée qu'à 1 fps ; pour une slide avec GIF, le filtre fps ci-dessous
            // la duplique ensuite en une vraie base 30 fps. Chaque frame du GIF dispose donc d'une
            // frame de fond correspondante sans redécoder le JPEG 30 fois par seconde.
            "-loop", "1", "-framerate", "1", "-i", frame.absolutePath
        )
        if (gif != null) {
            // Le GIF est bouclé pendant toute la durée de la slide. Ses timestamps sont conservés,
            // puis normalisés à 30 fps : toutes les images de l'animation sont compositées dans
            // l'ordre et à leur durée correcte, tandis que la photo fixe est multipliée à 30 fps.
            args += listOf("-stream_loop", "-1", "-i", gif.absolutePath)
            val gifWidth = (OUTPUT_WIDTH * slide.gifWidthFraction.coerceIn(0.12f, 0.55f)).toInt().coerceAtLeast(80)
            val cx = String.format(Locale.US, "%.6f", slide.gifX.coerceIn(0f, 1f))
            val cy = String.format(Locale.US, "%.6f", slide.gifY.coerceIn(0f, 1f))
            val filter = "[0:v]fps=30,setpts=N/(30*TB)[base];" +
                "[1:v]scale=$gifWidth:-1:flags=fast_bilinear,fps=30,setpts=PTS-STARTPTS[gif];" +
                "[base][gif]overlay=" +
                "x='max(0,min(main_w-overlay_w,main_w*$cx-overlay_w/2))':" +
                "y='max(0,min(main_h-overlay_h,main_h*$cy-overlay_h/2))':" +
                "shortest=1,format=yuv420p$fadeSuffix[v]"
            args += listOf("-filter_complex", filter, "-map", "[v]")
        } else {
            args += listOf("-vf", "fps=30,format=yuv420p$fadeSuffix")
        }
        args += listOf("-t", seconds(durationMs), "-an")
        args += encoder.arguments
        args += listOf("-r", "30", "-movflags", "+faststart", out.absolutePath)
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val ok = ReturnCode.isSuccess(session.returnCode) && out.isFile && out.length() > 0L
        if (!ok) android.util.Log.w("BlazeDiapo", "Segment échoué (${encoder.name}): ${session.allLogsAsString}")
        return ok
    }

    private fun encodeFinalWithMusic(video: File, music: File, out: File, encoder: String): Boolean {
        if (out.exists()) out.delete()
        val args = mutableListOf(
            "-y", "-hide_banner", "-loglevel", "warning",
            "-i", video.absolutePath,
            "-i", music.absolutePath,
            "-t", seconds(chosenDurationMs),
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy",
            "-c:a", encoder, "-b:a", "128k", "-ar", "44100", "-ac", "2"
        )
        if (musicDurationMs > 0L && chosenDurationMs + 250L < musicDurationMs) {
            val fadeMs = min(3_000L, max(800L, chosenDurationMs / 5))
            val start = max(0L, chosenDurationMs - fadeMs)
            args += listOf("-af", "afade=t=out:st=${seconds(start)}:d=${seconds(fadeMs)}")
        }
        args += listOf("-shortest", "-movflags", "+faststart", out.absolutePath)
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val ok = ReturnCode.isSuccess(session.returnCode) && out.isFile && out.length() > 0L
        if (!ok) android.util.Log.w("BlazeDiapo", "Encodeur audio $encoder échoué: ${session.allLogsAsString}")
        return ok
    }

    private fun renderSlide(slide: Slide, target: File) {
        val src = decodeSampled(slide.sourcePath, 2600)
        val canvasBitmap = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.BLACK)
        // Respecte intégralement le cadrage et l'orientation d'origine. Une photo portrait reste
        // portrait, une photo paysage reste paysage : aucune déformation ni center-crop automatique.
        // Le cadre vidéo reste 1920x1080 ; les zones non couvertes forment un matte noir propre.
        val scale = min(OUTPUT_WIDTH.toFloat() / src.width, OUTPUT_HEIGHT.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val dst = RectF(
            (OUTPUT_WIDTH - w) / 2f,
            (OUTPUT_HEIGHT - h) / 2f,
            (OUTPUT_WIDTH + w) / 2f,
            (OUTPUT_HEIGHT + h) / 2f
        )
        canvas.drawBitmap(src, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (slide.annotation.isNotBlank()) drawAnnotation(canvas, slide)
        target.outputStream().use { canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 87, it) }
        if (src !== canvasBitmap && !src.isRecycled) src.recycle()
        canvasBitmap.recycle()
    }

    private fun drawAnnotation(canvas: Canvas, slide: Slide) {
        val text = slide.annotation
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = OUTPUT_HEIGHT * slide.annotationSizeFraction.coerceIn(0.030f, 0.115f)
            typeface = slideshowTypefaceCache.getOrPut(slide.annotationFont) {
                SlideshowFontCatalog.getBlocking(this@SlideshowActivity, slide.annotationFont)
            }
            color = Color.WHITE
            setShadowLayer(max(2f, textSize * 0.06f), 0f, textSize * 0.035f, 0xCC000000.toInt())
        }
        val maxTextWidth = (OUTPUT_WIDTH * 0.72f).toInt()
        val first = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        var actualWidth = 1f
        for (i in 0 until first.lineCount) actualWidth = max(actualWidth, first.getLineWidth(i))
        val adaptiveWidth = min(maxTextWidth.toFloat(), actualWidth + 2f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, adaptiveWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(4)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        val paddingX = max(28f, textPaint.textSize * 0.48f)
        val paddingY = max(18f, textPaint.textSize * 0.28f)
        val skewPad = if (slide.annotationShape == AnnotationEditorView.SHAPE_PARALLELOGRAM) max(24f, textPaint.textSize * 0.34f) else 0f
        val boxW = layout.width + paddingX * 2f + skewPad * 2f
        val boxH = layout.height + paddingY * 2f
        var left = OUTPUT_WIDTH * slide.annotationX.coerceIn(0f, 1f) - boxW / 2f
        var top = OUTPUT_HEIGHT * slide.annotationY.coerceIn(0f, 1f) - boxH / 2f
        left = left.coerceIn(10f, max(10f, OUTPUT_WIDTH - boxW - 10f))
        top = top.coerceIn(10f, max(10f, OUTPUT_HEIGHT - boxH - 10f))
        val rect = RectF(left, top, left + boxW, top + boxH)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slide.annotationColor }
        when (slide.annotationShape) {
            AnnotationEditorView.SHAPE_PARALLELOGRAM -> {
                val skew = min(boxH * 0.42f, boxW * 0.10f)
                val path = Path().apply {
                    moveTo(left + skew, top)
                    lineTo(left + boxW, top)
                    lineTo(left + boxW - skew, top + boxH)
                    lineTo(left, top + boxH)
                    close()
                }
                canvas.drawPath(path, bg)
            }
            AnnotationEditorView.SHAPE_RECTANGLE -> canvas.drawRect(rect, bg)
            else -> canvas.drawRoundRect(rect, boxH / 2f, boxH / 2f, bg)
        }
        canvas.save()
        canvas.translate(left + paddingX + skewPad, top + paddingY)
        layout.draw(canvas)
        canvas.restore()
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
        val matrix = android.graphics.Matrix()
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

    private fun copyUriToCache(uri: Uri, dir: File, base: String): File {
        val ext = queryDisplayName(uri)?.substringAfterLast('.', "bin")?.take(8) ?: "bin"
        val out = File(dir, "$base.$ext")
        contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: error("Unable to read music")
        return out
    }

    private fun readMediaDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { runCatching { retriever.release() } }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d".format(Locale.getDefault(), total / 60, total % 60)
    }

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnExport.isEnabled = !busy
        recycler.isEnabled = !busy
    }

    private inner class SlideAdapter : RecyclerView.Adapter<SlideViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder =
            SlideViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_slideshow_photo, parent, false))

        override fun getItemCount(): Int = slides.size

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            val slide = slides[position]
            holder.title.text = slide.name
            holder.annotation.text = when {
                slide.annotation.isNotBlank() && slide.gifUri != null -> getString(R.string.slideshow_annotation_and_gif_summary, slide.annotation)
                slide.annotation.isNotBlank() -> slide.annotation
                slide.gifUri != null -> getString(R.string.slideshow_gif_selected)
                else -> getString(R.string.slideshow_no_annotation)
            }
            holder.image.setTag(R.id.ivThumbnail, slide.sourcePath)
            holder.image.setImageDrawable(ContextCompat.getDrawable(this@SlideshowActivity, R.drawable.bg_gallery_photo_bottom_overlay))
            lifecycleScope.launch {
                ThumbnailUtils.loadImageThumbnail(this@SlideshowActivity, slide.sourcePath, holder.image, 300)
            }
            holder.btnAnnotation.setOnClickListener { showAnnotationEditor(holder.bindingAdapterPosition) }
            holder.btnEdit.setOnClickListener { editPhoto(holder.bindingAdapterPosition) }
            holder.btnRemove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index !in slides.indices) return@setOnClickListener
                if (slides.size <= 2) {
                    Toast.makeText(this@SlideshowActivity, R.string.slideshow_need_two_photos, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                slides.removeAt(index)
                notifyItemRemoved(index)
                val maxDuration = if (musicDurationMs > 0) musicDurationMs else slides.size * 10_000L
                chosenDurationMs = min(chosenDurationMs, maxDuration).coerceAtLeast(slides.size * MIN_MS_PER_SLIDE)
                updateDurationUi(resetSeek = true)
            }
        }
    }

    private class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.ivSlideshowPhoto)
        val title: TextView = view.findViewById(R.id.tvSlideshowPhotoName)
        val annotation: TextView = view.findViewById(R.id.tvSlideshowAnnotation)
        val btnAnnotation: MaterialButton = view.findViewById(R.id.btnSlideshowAnnotation)
        val btnEdit: MaterialButton = view.findViewById(R.id.btnSlideshowEdit)
        val btnRemove: MaterialButton = view.findViewById(R.id.btnSlideshowRemove)
    }
}
