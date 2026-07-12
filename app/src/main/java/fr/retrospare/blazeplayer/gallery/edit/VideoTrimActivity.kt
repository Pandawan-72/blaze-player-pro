package fr.retrospare.blazeplayer.gallery.edit

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.AndroidEntryPoint
import fr.retrospare.blazeplayer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Découpe vidéo façon Google Photos : barre de sélection à deux poignées + prévisualisation en
 * boucle sur le segment choisi, puis export soit "rapide" (recopie de flux ffmpeg `-c copy` —
 * quasi instantané mais la coupe se cale sur le photogramme clé le plus proche), soit "précis"
 * (ré-encodage, plus lent mais une coupe exacte à la milliseconde). Un export GIF du même segment
 * est aussi proposé, via la technique classique palette ffmpeg (bien meilleure qualité qu'un GIF
 * généré sans palette dédiée).
 */
@AndroidEntryPoint
class VideoTrimActivity : AppCompatActivity() {
    @Inject lateinit var userRepository: fr.retrospare.blazeplayer.data.repository.UserRepository


    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_VIDEO_NAME = "video_name"
        /** Extras du résultat renvoyé à HomeFragment, pour qu'il ouvre directement le dossier de
         *  la Galerie où le résultat de la découpe/export GIF vient d'être enregistré. */
        const val EXTRA_RESULT_BUCKET_ID = "result_bucket_id"
        const val EXTRA_RESULT_BUCKET_NAME = "result_bucket_name"
        const val EXTRA_RESULT_IS_GIF = "result_is_gif"
        private const val MP3_EXPORT_ARTIST = "Blaze Video to MP3"
    }

    private lateinit var playerView: PlayerView
    private lateinit var rangeSeekBar: RangeSeekBarView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var progress: View

    private var player: ExoPlayer? = null
    private var videoPath: String = ""
    private var videoName: String = "video"
    private val handler = Handler(Looper.getMainLooper())
    private var loopChecker: Runnable? = null

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
        setContentView(R.layout.activity_video_trim)

        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: run { finish(); return }
        videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "video"

        playerView = findViewById(R.id.trimPlayerView)
        rangeSeekBar = findViewById(R.id.rangeSeekBar)
        tvDuration = findViewById(R.id.tvTrimDuration)
        btnPlayPause = findViewById(R.id.btnTrimPlayPause)
        progress = findViewById(R.id.trimProgress)

        findViewById<View>(R.id.btnTrimClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExportFast).setOnClickListener { startExport(precise = false) }
        findViewById<View>(R.id.btnExportPrecise).setOnClickListener { startExport(precise = true) }
        findViewById<View>(R.id.btnExportGif).setOnClickListener { startGifExport() }
        findViewById<View>(R.id.btnExportMp3).setOnClickListener { startMp3Export() }

        setupPlayer()
        setupRangeBar()
        setupPlayPause()
    }

    private fun setupPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(videoPath)))
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && rangeSeekBar.durationMs == 0L) {
                    val duration = exo.duration.coerceAtLeast(1L)
                    rangeSeekBar.durationMs = duration
                    rangeSeekBar.setRange(0L, duration)
                    updateDurationLabel()
                    loadFrameStrip(duration)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_small)
                if (isPlaying) startLoopChecker() else stopLoopChecker()
            }
        })
        exo.prepare()
    }

    /** Extrait une dizaine de vignettes réparties sur toute la durée de la vidéo (comme les
     *  galeries premium) et les transmet à [RangeSeekBarView], qui les affiche en fond de piste
     *  à la place d'une simple barre de couleur unie. Extraction en arrière-plan : décoder des
     *  frames est trop lent pour le thread principal. */
    private fun loadFrameStrip(durationMs: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            val frames = try {
                retriever.setDataSource(this@VideoTrimActivity, Uri.parse(videoPath))
                val frameCount = 12
                (0 until frameCount).mapNotNull { i ->
                    val timeUs = (durationMs * i / frameCount) * 1000L
                    try {
                        retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            if (frames.isNotEmpty()) {
                withContext(Dispatchers.Main) { rangeSeekBar.setFrames(frames) }
            }
        }
    }

    private fun setupRangeBar() {
        rangeSeekBar.onRangeChanged = { start, _ ->
            updateDurationLabel()
            player?.seekTo(start)
        }
    }

    private fun updateDurationLabel() {
        val seconds = (rangeSeekBar.endMs - rangeSeekBar.startMs) / 1000f
        tvDuration.text = String.format(Locale.getDefault(), "%.1fs", seconds)
    }

    private fun setupPlayPause() {
        btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
            } else {
                if (p.currentPosition < rangeSeekBar.startMs || p.currentPosition >= rangeSeekBar.endMs) {
                    p.seekTo(rangeSeekBar.startMs)
                }
                p.play()
            }
        }
    }

    /** Boucle de prévisualisation : ramène la lecture au début du segment dès qu'elle en dépasse
     *  la fin, pour que l'utilisateur puisse écouter/regarder en boucle uniquement la partie
     *  qu'il est en train de sélectionner — comme le fait Google Photos. */
    private fun startLoopChecker() {
        stopLoopChecker()
        val runnable = object : Runnable {
            override fun run() {
                val p = player ?: return
                rangeSeekBar.playheadMs = p.currentPosition
                if (p.currentPosition >= rangeSeekBar.endMs) {
                    p.seekTo(rangeSeekBar.startMs)
                }
                handler.postDelayed(this, 120L)
            }
        }
        loopChecker = runnable
        handler.post(runnable)
    }

    private fun stopLoopChecker() {
        loopChecker?.let { handler.removeCallbacks(it) }
        loopChecker = null
    }

    // ── Export ───────────────────────────────────────────────────────────────

    private fun startExport(precise: Boolean) {
        val startSec = rangeSeekBar.startMs / 1000f
        val durationSec = (rangeSeekBar.endMs - rangeSeekBar.startMs) / 1000f
        if (durationSec <= 0f) return
        player?.pause()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val input = copyToCacheIfNeeded(videoPath)
            val outFile = File(cacheDir, "trim_${System.currentTimeMillis()}.mp4")
            val command = if (precise) {
                // mpeg4 plutôt que libx264 : ce fork de ffmpeg-kit n'embarque que les composants
                // LGPL (utilisé à l'origine uniquement pour l'extraction de sous-titres) — libx264
                // est un encodeur GPL généralement absent de ce genre de build minimal, ce qui
                // provoquait un échec systématique ("Unknown encoder") sur l'export précis alors
                // que le rapide (-c copy, sans encodeur) fonctionnait. mpeg4 est toujours présent,
                // quelle que soit la variante de build ffmpeg.
                "-y -i \"${input.absolutePath}\" -ss $startSec -t $durationSec -c:v mpeg4 -q:v 3 -c:a aac \"${outFile.absolutePath}\""
            } else {
                "-y -ss $startSec -i \"${input.absolutePath}\" -t $durationSec -c copy \"${outFile.absolutePath}\""
            }
            val session = FFmpegKit.execute(command)
            val success = ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0L
            if (!success) {
                android.util.Log.e("VideoTrimActivity", "Échec export vidéo (précis=$precise) : ${session.allLogsAsString}")
            }
            val publishedUri = if (success) MediaSaveUtils.publishProcessedFile(
                this@VideoTrimActivity, outFile, "${sanitizeName(videoName)}_cut_${System.currentTimeMillis()}.mp4", "video/mp4"
            ) else null
            outFile.delete()
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                Toast.makeText(
                    this@VideoTrimActivity,
                    getString(if (publishedUri != null) R.string.toast_video_saved else R.string.toast_video_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (publishedUri != null) finishWithResult(publishedUri, isGif = false)
            }
        }
    }


    private fun startMp3Export() {
        val startSec = formatSeconds(rangeSeekBar.startMs)
        val durationSec = formatSeconds(rangeSeekBar.endMs - rangeSeekBar.startMs)
        if (rangeSeekBar.endMs <= rangeSeekBar.startMs) return
        player?.pause()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val input = copyToCacheIfNeeded(videoPath)
            val outFile = File(cacheDir, "mp3_cut_${System.currentTimeMillis()}.mp3")
            val coverFile = File(cacheDir, "mp3_cover_${System.currentTimeMillis()}.jpg")
            val exportedCover = extractMp3CoverFrame(
                input = input,
                output = coverFile,
                startMs = rangeSeekBar.startMs
            )
            if (!exportedCover) {
                android.util.Log.w("VideoTrimActivity", "Cover MP3 non extraite, export audio sans jaquette")
            }

            // Étape 1 : produire un MP3 audio pur et fiable. L'intégration de cover via FFmpeg
            // sous forme de flux vidéo `attached_pic` n'est pas toujours relue correctement par
            // Android/MediaStore/lecteurs audio. On garde donc FFmpeg uniquement pour l'audio.
            val lameSession = executeMp3Transcode(
                input = input,
                output = outFile,
                startSec = startSec,
                durationSec = durationSec,
                encoder = "libmp3lame"
            )
            var success = ReturnCode.isSuccess(lameSession.returnCode) && outFile.exists() && outFile.length() > 0L

            if (!success) {
                android.util.Log.w("VideoTrimActivity", "Export MP3 libmp3lame échoué, tentative libshine : ${lameSession.allLogsAsString}")
                outFile.delete()
                val shineSession = executeMp3Transcode(
                    input = input,
                    output = outFile,
                    startSec = startSec,
                    durationSec = durationSec,
                    encoder = "libshine"
                )
                success = ReturnCode.isSuccess(shineSession.returnCode) && outFile.exists() && outFile.length() > 0L
                if (!success) {
                    android.util.Log.w("VideoTrimActivity", "Export MP3 libshine échoué, tentative encodeur MP3 par défaut : ${shineSession.allLogsAsString}")
                    outFile.delete()
                    val defaultMp3Session = executeMp3Transcode(
                        input = input,
                        output = outFile,
                        startSec = startSec,
                        durationSec = durationSec,
                        encoder = "mp3"
                    )
                    success = ReturnCode.isSuccess(defaultMp3Session.returnCode) && outFile.exists() && outFile.length() > 0L
                    if (!success) {
                        android.util.Log.e("VideoTrimActivity", "Échec export MP3 complet : ${defaultMp3Session.allLogsAsString}")
                    }
                }
            }

            val exportDate = SimpleDateFormat("yyyy_MM_dd", Locale.US).format(Date())
            val requestedDisplayName = "Blaze_Extractor_${exportDate}.mp3"

            // Étape 2 : MediaSaveUtils choisit d'abord le nom final réellement disponible
            // (avec _001, _002, etc. si nécessaire), puis ce nom exact est écrit dans le champ
            // titre TIT2 du tag ID3v2.3 avant la copie vers Documents/Blaze Audio Extractor.
            // Le tag artiste TPE1 et la jaquette APIC restent également intégrés.
            val publishedUri = if (success) MediaSaveUtils.publishMp3CutFile(
                context = this@VideoTrimActivity,
                sourceFile = outFile,
                displayName = requestedDisplayName,
                prepareSourceForDisplayName = { finalDisplayName ->
                    embedMp3Id3v23Metadata(
                        mp3File = outFile,
                        coverFile = coverFile.takeIf { exportedCover },
                        title = finalDisplayName
                    )
                }
            ) else null
            outFile.delete()
            coverFile.delete()
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                Toast.makeText(
                    this@VideoTrimActivity,
                    getString(if (publishedUri != null) R.string.toast_mp3_saved else R.string.toast_mp3_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (publishedUri != null) finish()
            }
        }
    }

    private fun executeMp3Transcode(
        input: File,
        output: File,
        startSec: String,
        durationSec: String,
        encoder: String
    ) = FFmpegKit.executeWithArguments(
        mutableListOf<String>().apply {
            add("-y")
            add("-hide_banner")
            add("-loglevel")
            add("info")
            add("-i")
            add(input.absolutePath)
            add("-ss")
            add(startSec)
            add("-t")
            add(durationSec)
            add("-map")
            add("0:a:0")
            add("-vn")
            add("-sn")
            add("-dn")
            add("-c:a")
            add(encoder)
            add("-b:a")
            add("128k")
            add("-minrate")
            add("128k")
            add("-ar")
            add("44100")
            add("-ac")
            add("2")
            add("-af")
            add("aresample=async=1:first_pts=0")
            add("-map_metadata")
            add("-1")
            add("-id3v2_version")
            add("3")
            add("-f")
            add("mp3")
            add(output.absolutePath)
        }.toTypedArray()
    )

    private fun extractMp3CoverFrame(input: File, output: File, startMs: Long): Boolean {
        // Méthode principale : MediaMetadataRetriever. Elle écrit directement un JPEG unique,
        // sans passer par le muxer image2 de FFmpeg, donc sans ambiguïté sequence/pattern.
        if (extractMp3CoverFrameWithRetriever(input, output, startMs)) return true

        // Fallback : FFmpeg si le retriever Android ne parvient pas à décoder la frame.
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-y",
                "-hide_banner",
                "-loglevel", "warning",
                "-ss", formatSeconds(startMs),
                "-i", input.absolutePath,
                "-map", "0:v:0",
                "-an",
                "-sn",
                "-dn",
                "-frames:v", "1",
                "-vf", "scale=640:-2,format=yuvj420p",
                "-q:v", "4",
                "-update", "1",
                "-f", "image2",
                output.absolutePath
            )
        )
        val success = ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L
        if (!success) {
            android.util.Log.w("VideoTrimActivity", "Échec extraction cover MP3 : ${session.allLogsAsString}")
            output.delete()
        }
        return success
    }

    private fun extractMp3CoverFrameWithRetriever(input: File, output: File, startMs: Long): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(input.absolutePath)
            val frame = retriever.getFrameAtTime(startMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            val cover = scaleBitmapForMp3Cover(frame)
            output.outputStream().use { out ->
                cover.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (cover !== frame) cover.recycle()
            frame.recycle()
            output.exists() && output.length() > 0L
        } catch (e: Exception) {
            android.util.Log.w("VideoTrimActivity", "Échec extraction cover MP3 via MediaMetadataRetriever", e)
            output.delete()
            false
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun scaleBitmapForMp3Cover(bitmap: Bitmap): Bitmap {
        val maxSide = 640
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxSide || width <= 0 || height <= 0) return bitmap
        val ratio = maxSide.toFloat() / longest.toFloat()
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun embedMp3Id3v23Metadata(mp3File: File, coverFile: File?, title: String): Boolean {
        return try {
            if (!mp3File.exists() || mp3File.length() <= 0L) return false
            val coverBytes = coverFile
                ?.takeIf { it.exists() && it.length() > 0L }
                ?.readBytes()
                ?.takeIf { looksLikeJpeg(it) }
            if (coverFile != null && coverBytes == null) {
                android.util.Log.w("VideoTrimActivity", "Cover MP3 ignorée : le fichier extrait n'est pas un JPEG valide")
            }

            val original = mp3File.readBytes()
            val audioBytes = stripExistingId3v2Tag(original)
            val id3Tag = buildId3v23Tag(
                title = title,
                artist = MP3_EXPORT_ARTIST,
                jpegBytes = coverBytes
            )
            val taggedFile = File(mp3File.parentFile, "${mp3File.nameWithoutExtension}_tagged_${System.currentTimeMillis()}.mp3")
            taggedFile.outputStream().use { out ->
                out.write(id3Tag)
                out.write(audioBytes)
            }
            if (!hasTextFrame(taggedFile, "TIT2") || !hasTextFrame(taggedFile, "TPE1")) {
                taggedFile.delete()
                return false
            }
            if (coverBytes != null && !hasApicFrame(taggedFile)) {
                taggedFile.delete()
                return false
            }
            taggedFile.copyTo(mp3File, overwrite = true)
            taggedFile.delete()
            android.util.Log.i(
                "VideoTrimActivity",
                "Métadonnées MP3 intégrées en ID3v2.3 : titre=$title, artiste=$MP3_EXPORT_ARTIST, cover=${coverBytes?.size ?: 0} octets"
            )
            true
        } catch (e: Exception) {
            android.util.Log.w("VideoTrimActivity", "Échec injection métadonnées MP3 ID3", e)
            false
        }
    }

    private fun buildId3v23Tag(title: String, artist: String, jpegBytes: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream().apply {
            write(buildId3v23TextFrame("TIT2", title))
            write(buildId3v23TextFrame("TPE1", artist))
            if (jpegBytes != null) write(buildId3v23ApicFrame(jpegBytes))
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(0x03, 0x00, 0x00)) // ID3v2.3.0, sans flags
            write(encodeSynchsafe(frames.size))
            write(frames)
        }.toByteArray()
    }

    private fun buildId3v23TextFrame(frameId: String, value: String): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(0x00) // ISO-8859-1 : compatible ID3v2.3 pour les noms Blaze générés
            write(value.toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(frameId.toByteArray(Charsets.ISO_8859_1))
            writeInt32(payload.size)
            write(byteArrayOf(0x00, 0x00))
            write(payload)
        }.toByteArray()
    }

    private fun buildId3v23ApicFrame(jpegBytes: ByteArray): ByteArray {
        val apicPayload = ByteArrayOutputStream().apply {
            write(0x00) // ISO-8859-1 pour compatibilité ID3v2.3 Android
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            write(0x00)
            write(0x03) // Cover front
            write(0x00) // description vide terminée par zéro
            write(jpegBytes)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("APIC".toByteArray(Charsets.ISO_8859_1))
            writeInt32(apicPayload.size)
            write(byteArrayOf(0x00, 0x00))
            write(apicPayload)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun encodeSynchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun decodeSynchsafe(bytes: ByteArray, offset: Int): Int {
        if (bytes.size < offset + 4) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun stripExistingId3v2Tag(bytes: ByteArray): ByteArray {
        if (bytes.size < 10) return bytes
        val hasId3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        if (!hasId3) return bytes
        val tagSize = decodeSynchsafe(bytes, 6)
        val totalSize = (10 + tagSize).coerceIn(10, bytes.size)
        return bytes.copyOfRange(totalSize, bytes.size)
    }

    private fun hasApicFrame(file: File): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 20) return false
        val hasId3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        if (!hasId3) return false
        val tagEnd = (10 + decodeSynchsafe(bytes, 6)).coerceAtMost(bytes.size)
        val apic = "APIC".toByteArray(Charsets.ISO_8859_1)
        for (i in 10..(tagEnd - apic.size)) {
            if (bytes[i] == apic[0] && bytes[i + 1] == apic[1] && bytes[i + 2] == apic[2] && bytes[i + 3] == apic[3]) return true
        }
        return false
    }

    private fun hasTextFrame(file: File, frameId: String): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 20 || frameId.length != 4) return false
        val hasId3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        if (!hasId3) return false
        val tagEnd = (10 + decodeSynchsafe(bytes, 6)).coerceAtMost(bytes.size)
        val needle = frameId.toByteArray(Charsets.ISO_8859_1)
        for (i in 10..(tagEnd - needle.size)) {
            if (bytes[i] == needle[0] && bytes[i + 1] == needle[1] && bytes[i + 2] == needle[2] && bytes[i + 3] == needle[3]) return true
        }
        return false
    }

    private fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size > 4 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()

    private fun startGifExport() {
        val startSec = rangeSeekBar.startMs / 1000f
        val durationSec = ((rangeSeekBar.endMs - rangeSeekBar.startMs) / 1000f).coerceAtMost(15f) // GIF: segment volontairement plafonné pour garder un poids de fichier raisonnable
        if (durationSec <= 0f) return
        player?.pause()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val input = copyToCacheIfNeeded(videoPath)
            val outFile = File(cacheDir, "gif_${System.currentTimeMillis()}.gif")
            // Technique palette ffmpeg classique : bien meilleure qualité de couleurs qu'une
            // conversion GIF directe sans palette dédiée. Produit un vrai GIF animé (pas une image
            // figée) : chaque frame de la palette est réutilisée pour tout le segment sélectionné.
            val command = "-y -ss $startSec -i \"${input.absolutePath}\" -t $durationSec " +
                "-vf \"fps=6,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse\" " +
                "\"${outFile.absolutePath}\""
            val session = FFmpegKit.execute(command)
            val success = ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0L
            if (!success) {
                android.util.Log.e("VideoTrimActivity", "Échec export GIF : ${session.allLogsAsString}")
            }
            val publishedUri = if (success) MediaSaveUtils.publishProcessedFile(
                this@VideoTrimActivity, outFile, "${sanitizeName(videoName)}_${System.currentTimeMillis()}.gif", "image/gif"
            ) else null
            outFile.delete()
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                Toast.makeText(
                    this@VideoTrimActivity,
                    getString(if (publishedUri != null) R.string.toast_gif_saved else R.string.toast_video_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (publishedUri != null) finishWithResult(publishedUri, isGif = true)
            }
        }
    }

    /** Termine l'écran en renvoyant le dossier (bucket MediaStore) où le résultat vient d'être
     *  enregistré, pour que HomeFragment y navigue directement plutôt que de laisser l'utilisateur
     *  sur l'ancien dossier d'origine de la vidéo découpée. */
    private fun finishWithResult(uri: Uri, isGif: Boolean) {
        val bucket = MediaSaveUtils.bucketInfoForUri(this, uri, isGif)
        val data = Intent().apply {
            if (bucket != null) {
                putExtra(EXTRA_RESULT_BUCKET_ID, bucket.first)
                putExtra(EXTRA_RESULT_BUCKET_NAME, bucket.second)
            }
            putExtra(EXTRA_RESULT_IS_GIF, isGif)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    /** ffmpeg-kit a besoin d'un chemin de fichier réel ; une source content:// (cas courant sur
     *  Android 10+ pour les vidéos de la Galerie) est d'abord recopiée dans le cache de l'app. */
    private fun copyToCacheIfNeeded(path: String): File {
        val uri = Uri.parse(path)
        if (uri.scheme == "file") return File(uri.path.orEmpty())
        if (uri.scheme != "content") return File(path)
        val safeExt = videoName.substringAfterLast('.', "mp4").lowercase(Locale.US).ifBlank { "mp4" }
        val temp = File(cacheDir, "src_${System.currentTimeMillis()}.$safeExt")
        contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return temp
    }

    private fun formatSeconds(milliseconds: Long): String =
        String.format(Locale.US, "%.3f", milliseconds / 1000.0)

    private fun sanitizeName(name: String): String =
        name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40).ifBlank { "Blaze" }

    override fun onDestroy() {
        stopLoopChecker()
        player?.release()
        player = null
        super.onDestroy()
    }
}
