package fr.retrospare.blazeplayer.player

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lecteur MPEG audio minimal et borné utilisé lorsque MediaMetadataRetriever ne fournit ni durée
 * ni bitrate pour un MP3. Il ne décode aucun échantillon : il lit l'en-tête MPEG, puis Xing/Info ou
 * VBRI lorsqu'ils existent. En dernier recours, il échantillonne des trames consécutives afin de
 * calculer un débit moyen et d'estimer la durée, ce qui couvre aussi les anciens MP3 VBR dépourvus
 * d'en-tête Xing.
 */
internal object Mp3TechnicalMetadataReader {

    data class Result(
        val durationMs: Long,
        val bitrate: Long
    )

    private const val MAX_SYNC_SEARCH_BYTES = 512 * 1024
    private const val MAX_FRAME_SAMPLE_BYTES = 4 * 1024 * 1024
    private const val MIN_VALIDATED_FRAMES = 3

    fun read(context: Context, path: String): Result? {
        val source = openSource(context.applicationContext, path) ?: return null
        return try {
            read(source)
        } catch (error: Exception) {
            android.util.Log.w(
                "Mp3TechnicalReader",
                "MP3 technical fallback failed for ${SmbDataSource.redactForLog(path)}",
                error
            )
            null
        } finally {
            runCatching { source.close() }
        }
    }

    private fun read(source: RandomAccessSource): Result? {
        if (source.size <= 4L) return null
        val audioStart = id3v2End(source)
        val firstFrame = findFirstFrame(source, audioStart) ?: return null

        readXingOrInfo(source, firstFrame)?.let { frameCount ->
            val durationMs = durationFromFrameCount(frameCount, firstFrame.header)
            if (durationMs > 0L) {
                return Result(
                    durationMs = durationMs,
                    bitrate = averageBitrate(source, firstFrame.position, durationMs)
                        .takeIf { it > 0L }
                        ?: firstFrame.header.bitrate
                )
            }
        }

        readVbri(source, firstFrame)?.let { frameCount ->
            val durationMs = durationFromFrameCount(frameCount, firstFrame.header)
            if (durationMs > 0L) {
                return Result(
                    durationMs = durationMs,
                    bitrate = averageBitrate(source, firstFrame.position, durationMs)
                        .takeIf { it > 0L }
                        ?: firstFrame.header.bitrate
                )
            }
        }

        val sampled = sampleConsecutiveFrames(source, firstFrame)
        if (sampled.frames <= 0 || sampled.durationUs <= 0L || sampled.bytes <= 0L) return null

        val sampledBitrate = (sampled.bytes * 8_000_000L / sampled.durationUs)
            .coerceAtLeast(1L)
        val audioBytes = effectiveAudioBytes(source, firstFrame.position)
        val durationMs = if (audioBytes > 0L) {
            (audioBytes * 8_000L / sampledBitrate).coerceAtLeast(1L)
        } else {
            (sampled.durationUs / 1_000L).coerceAtLeast(1L)
        }
        return Result(durationMs = durationMs, bitrate = sampledBitrate)
    }

    private data class LocatedFrame(
        val position: Long,
        val header: FrameHeader
    )

    private data class FrameHeader(
        val version: Int,
        val layer: Int,
        val bitrate: Long,
        val sampleRate: Int,
        val frameLength: Int,
        val samplesPerFrame: Int,
        val channelMode: Int,
        val hasCrc: Boolean
    )

    private data class FrameSample(
        val frames: Int,
        val bytes: Long,
        val durationUs: Long
    )

    private fun id3v2End(source: RandomAccessSource): Long {
        val header = readBytes(source, 0L, 10) ?: return 0L
        if (
            header.size < 10 ||
            header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return 0L
        }
        val bodySize = synchsafeInt(header, 6).toLong().coerceAtLeast(0L)
        val footerSize = if ((header[5].toInt() and 0x10) != 0) 10L else 0L
        return (10L + bodySize + footerSize).coerceAtMost(source.size)
    }

    private fun findFirstFrame(source: RandomAccessSource, start: Long): LocatedFrame? {
        val available = (source.size - start).coerceAtLeast(0L)
        val length = minOf(available, MAX_SYNC_SEARCH_BYTES.toLong()).toInt()
        val bytes = readBytes(source, start, length) ?: return null
        var index = 0
        while (index + 4 <= bytes.size) {
            val header = parseHeader(bytes, index)
            if (header != null) {
                val secondOffset = index + header.frameLength
                val second = if (secondOffset + 4 <= bytes.size) {
                    parseHeader(bytes, secondOffset)
                } else {
                    readBytes(source, start + secondOffset, 4)?.let { parseHeader(it, 0) }
                }
                if (second != null && compatible(header, second)) {
                    return LocatedFrame(start + index, header)
                }
            }
            index++
        }
        return null
    }

    private fun compatible(first: FrameHeader, second: FrameHeader): Boolean =
        first.version == second.version &&
            first.layer == second.layer &&
            first.sampleRate == second.sampleRate

    private fun readXingOrInfo(source: RandomAccessSource, frame: LocatedFrame): Long? {
        if (frame.header.layer != 3) return null
        val mono = frame.header.channelMode == 3
        val sideInfo = when (frame.header.version) {
            1 -> if (mono) 17 else 32
            else -> if (mono) 9 else 17
        }
        val offset = frame.position + 4L + (if (frame.header.hasCrc) 2L else 0L) + sideInfo
        val bytes = readBytes(source, offset, 16) ?: return null
        if (bytes.size < 12) return null
        val marker = String(bytes, 0, 4, Charsets.ISO_8859_1)
        if (marker != "Xing" && marker != "Info") return null
        val flags = beInt(bytes, 4)
        if ((flags and 0x1) == 0) return null
        return beUInt(bytes, 8).takeIf { it > 0L }
    }

    private fun readVbri(source: RandomAccessSource, frame: LocatedFrame): Long? {
        val offsets = longArrayOf(
            frame.position + 4L + 32L,
            frame.position + 4L + (if (frame.header.hasCrc) 2L else 0L) + 32L
        )
        offsets.forEach { offset ->
            val bytes = readBytes(source, offset, 18) ?: return@forEach
            if (bytes.size >= 18 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "VBRI") {
                return beUInt(bytes, 14).takeIf { it > 0L }
            }
        }
        return null
    }

    private fun durationFromFrameCount(frameCount: Long, header: FrameHeader): Long {
        if (frameCount <= 0L || header.sampleRate <= 0 || header.samplesPerFrame <= 0) return 0L
        return (frameCount * header.samplesPerFrame * 1_000L / header.sampleRate)
            .coerceAtLeast(1L)
    }

    private fun sampleConsecutiveFrames(
        source: RandomAccessSource,
        firstFrame: LocatedFrame
    ): FrameSample {
        val maxLength = minOf(
            (source.size - firstFrame.position).coerceAtLeast(0L),
            MAX_FRAME_SAMPLE_BYTES.toLong()
        ).toInt()
        val bytes = readBytes(source, firstFrame.position, maxLength) ?: return FrameSample(0, 0L, 0L)
        var offset = 0
        var frames = 0
        var totalBytes = 0L
        var durationUs = 0L
        var reference = firstFrame.header

        while (offset + 4 <= bytes.size) {
            val header = parseHeader(bytes, offset) ?: break
            if (!compatible(reference, header)) break
            if (offset + header.frameLength > bytes.size) break
            frames++
            totalBytes += header.frameLength
            durationUs += header.samplesPerFrame * 1_000_000L / header.sampleRate
            offset += header.frameLength
            reference = header
        }

        return if (frames >= MIN_VALIDATED_FRAMES) {
            FrameSample(frames, totalBytes, durationUs)
        } else {
            FrameSample(0, 0L, 0L)
        }
    }

    private fun effectiveAudioBytes(source: RandomAccessSource, firstFramePosition: Long): Long {
        var end = source.size
        if (end >= 128L) {
            val id3v1 = readBytes(source, end - 128L, 3)
            if (id3v1 != null && id3v1.size == 3 && String(id3v1, Charsets.ISO_8859_1) == "TAG") {
                end -= 128L
            }
        }
        return (end - firstFramePosition).coerceAtLeast(0L)
    }

    private fun averageBitrate(source: RandomAccessSource, firstFramePosition: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val bytes = effectiveAudioBytes(source, firstFramePosition)
        return if (bytes > 0L) (bytes * 8_000L / durationMs).coerceAtLeast(1L) else 0L
    }

    private fun parseHeader(bytes: ByteArray, offset: Int): FrameHeader? {
        if (offset < 0 || offset + 3 >= bytes.size) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null

        val versionBits = (b1 ushr 3) and 0x03
        val version = when (versionBits) {
            0 -> 25
            2 -> 2
            3 -> 1
            else -> return null
        }
        val layerBits = (b1 ushr 1) and 0x03
        val layer = when (layerBits) {
            1 -> 3
            2 -> 2
            3 -> 1
            else -> return null
        }
        val bitrateIndex = (b2 ushr 4) and 0x0F
        val sampleRateIndex = (b2 ushr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null

        val bitrateKbps = bitrateKbps(version, layer, bitrateIndex)
        if (bitrateKbps <= 0) return null
        val sampleRate = sampleRate(version, sampleRateIndex)
        if (sampleRate <= 0) return null
        val padding = (b2 ushr 1) and 0x01
        val bitrate = bitrateKbps * 1_000L
        val frameLength = when (layer) {
            1 -> (((12L * bitrate) / sampleRate) + padding) * 4L
            2 -> ((144L * bitrate) / sampleRate) + padding
            else -> (((if (version == 1) 144L else 72L) * bitrate) / sampleRate) + padding
        }.toInt()
        if (frameLength < 24 || frameLength > 32 * 1024) return null

        val samplesPerFrame = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (version == 1) 1152 else 576
        }
        return FrameHeader(
            version = version,
            layer = layer,
            bitrate = bitrate,
            sampleRate = sampleRate,
            frameLength = frameLength,
            samplesPerFrame = samplesPerFrame,
            channelMode = (b3 ushr 6) and 0x03,
            hasCrc = (b1 and 0x01) == 0
        )
    }

    private fun bitrateKbps(version: Int, layer: Int, index: Int): Int {
        val table = if (version == 1) {
            when (layer) {
                1 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
                2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
                else -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
            }
        } else {
            when (layer) {
                1 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
                else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
            }
        }
        return table[index]
    }

    private fun sampleRate(version: Int, index: Int): Int {
        val base = intArrayOf(44_100, 48_000, 32_000)[index]
        return when (version) {
            1 -> base
            2 -> base / 2
            else -> base / 4
        }
    }

    private fun synchsafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun beUInt(bytes: ByteArray, offset: Int): Long =
        beInt(bytes, offset).toLong() and 0xFFFF_FFFFL

    private fun readBytes(source: RandomAccessSource, position: Long, length: Int): ByteArray? {
        if (position < 0L || length <= 0 || position >= source.size) return null
        val bounded = minOf(length.toLong(), source.size - position).toInt()
        if (bounded <= 0) return null
        val buffer = ByteArray(bounded)
        var total = 0
        while (total < bounded) {
            val count = source.readAt(position + total, buffer, total, bounded - total)
            if (count <= 0) break
            total += count
        }
        return if (total > 0) buffer.copyOf(total) else null
    }

    private interface RandomAccessSource : AutoCloseable {
        val size: Long
        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    }

    private class LocalSource(file: File) : RandomAccessSource {
        private val random = RandomAccessFile(file, "r")
        override val size: Long = random.length()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0L || position >= size) return -1
            random.seek(position)
            return random.read(buffer, offset, length)
        }
        override fun close() = random.close()
    }

    private class ContentSource(
        private val descriptor: android.os.ParcelFileDescriptor
    ) : RandomAccessSource {
        private val stream = FileInputStream(descriptor.fileDescriptor)
        private val channel: FileChannel = stream.channel
        override val size: Long = runCatching { channel.size() }
            .getOrElse { descriptor.statSize }
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position < 0L || (size >= 0L && position >= size)) return -1
            val target = ByteBuffer.wrap(buffer, offset, length)
            return channel.read(target, position)
        }
        override fun close() {
            runCatching { stream.close() }
            runCatching { descriptor.close() }
        }
    }

    private class SmbSource(
        private val source: SmbMediaDataSource
    ) : RandomAccessSource {
        override val size: Long = source.getSize()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            source.readAt(position, buffer, offset, length)
        override fun close() = source.close()
    }

    private fun openSource(context: Context, path: String): RandomAccessSource? = try {
        when {
            path.startsWith("smb://", true) -> SmbSource(SmbMediaDataSource(path))
            path.startsWith("content://", true) -> context.contentResolver
                .openFileDescriptor(Uri.parse(path), "r")
                ?.let(::ContentSource)
            path.startsWith("file://", true) -> Uri.parse(path).path
                ?.let(::File)
                ?.takeIf { it.isFile }
                ?.let(::LocalSource)
            path.startsWith("http://", true) || path.startsWith("https://", true) -> null
            else -> File(path).takeIf { it.isFile }?.let(::LocalSource)
        }
    } catch (error: Exception) {
        android.util.Log.w(
            "Mp3TechnicalReader",
            "Cannot open MP3 source ${SmbDataSource.redactForLog(path)}",
            error
        )
        null
    }
}
