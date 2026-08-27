package com.taskmind.capture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Spec 12.1: decode to 16 kHz mono PCM WAV before sending. MediaCodec +
 * MediaExtractor; do not ship FFmpeg.
 *
 * The dialer writes m4a/amr/3gp/opus depending on the device. Every provider
 * wants something predictable, and a 16 kHz mono stream is also six times
 * smaller than the source on a stereo 48 kHz recording, which matters because
 * call audio is the expensive upload.
 *
 * PCM is written to a raw scratch file, never held in memory: a 25 minute call
 * is ~48 MB of samples.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Pcm(
        /** Raw little-endian 16-bit mono PCM at [TARGET_SAMPLE_RATE]. */
        val file: File,
        val sampleCount: Long,
    ) {
        val durationSeconds: Double get() = sampleCount.toDouble() / TARGET_SAMPLE_RATE
    }

    /**
     * @throws DecodeException when the file has no decodable audio track. The
     *   caller keeps the recording and reports the failure; it never deletes it.
     */
    fun decodeToMonoPcm(source: File, scratch: File): Pcm {
        if (!source.exists() || source.length() == 0L) {
            throw DecodeException("recording missing or empty: ${source.absolutePath}")
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var written = 0L

        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("no audio track in ${source.name}")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw DecodeException("audio track has no mime type")

            val sourceRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            val sourceChannels = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            FileOutputStream(scratch).use { out ->
                val resampler = Resampler(sourceRate, TARGET_SAMPLE_RATE)
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var outChannels = sourceChannels

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex)
                            val size = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            outChannels = f.optInt(MediaFormat.KEY_CHANNEL_COUNT, sourceChannels)
                            resampler.setSourceRate(f.optInt(MediaFormat.KEY_SAMPLE_RATE, sourceRate))
                        }
                        outIndex >= 0 -> {
                            val outBuffer = codec.getOutputBuffer(outIndex)
                            if (outBuffer != null && bufferInfo.size > 0) {
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val shorts = outBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val mono = downmix(shorts, outChannels)
                                val resampled = resampler.process(mono)
                                written += writeShorts(out, resampled)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("could not decode ${source.name}: ${e.message}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        if (written == 0L) throw DecodeException("decoded 0 samples from ${source.name}")
        return Pcm(scratch, written)
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun downmix(buffer: java.nio.ShortBuffer, channels: Int): ShortArray {
        val total = buffer.remaining()
        if (channels <= 1) {
            val out = ShortArray(total)
            buffer.get(out)
            return out
        }
        val frames = total / channels
        val out = ShortArray(frames)
        val frame = ShortArray(channels)
        for (i in 0 until frames) {
            buffer.get(frame)
            var sum = 0
            for (c in frame) sum += c.toInt()
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    private fun writeShorts(out: FileOutputStream, samples: ShortArray): Long {
        if (samples.isEmpty()) return 0
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bytes.putShort(s)
        out.write(bytes.array())
        return samples.size.toLong()
    }

    private const val TIMEOUT_US = 10_000L

    /**
     * Linear-interpolation resampler. Good enough for speech heading to a cloud
     * ASR, and it carries no native dependency.
     */
    private class Resampler(private var sourceRate: Int, private val targetRate: Int) {
        private var position = 0.0
        private var lastSample: Short = 0

        fun setSourceRate(rate: Int) {
            if (rate > 0) sourceRate = rate
        }

        fun process(input: ShortArray): ShortArray {
            if (input.isEmpty()) return input
            if (sourceRate == targetRate) {
                lastSample = input.last()
                return input
            }
            val step = sourceRate.toDouble() / targetRate
            val out = ArrayList<Short>(((input.size / step) + 2).toInt())
            var p = position
            while (p < input.size) {
                val idx = p.toInt()
                val frac = p - idx
                val a = if (idx == 0) lastSample.toInt() else input[idx - 1].toInt()
                val b = input[idx].toInt()
                out.add((a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort())
                p += step
            }
            position = p - input.size
            lastSample = input.last()
            return out.toShortArray()
        }
    }
}
