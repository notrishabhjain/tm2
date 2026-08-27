package com.taskmind.capture

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Spec 12.2 - chunking.
 *
 * Sarvam's sync endpoint caps around 30 s per request, so we always chunk for
 * it; OpenAI-compatible endpoints accept ~25 MB / ~25 minutes but chunking is
 * still what makes a killed process resumable (spec 12.3).
 *
 * Boundaries are cut at the QUIETEST 100 ms window within +/- 3 s of each
 * nominal boundary, so words are not sliced in half. Cutting mid-word costs a
 * commitment at both ends of the cut.
 */
object AudioChunker {

    private const val BYTES_PER_SAMPLE = 2
    private const val SEARCH_SECONDS = 3.0
    private const val QUIET_WINDOW_MILLIS = 100

    /**
     * @param maxChunkSeconds at most this much audio per chunk.
     * @return WAV files in order. Never empty for non-empty input.
     */
    fun chunk(pcm: AudioDecoder.Pcm, maxChunkSeconds: Double, outDir: File, namePrefix: String): List<File> {
        outDir.mkdirs()
        val total = pcm.sampleCount
        if (total <= 0) return emptyList()

        val chunkSamples = (maxChunkSeconds * AudioDecoder.TARGET_SAMPLE_RATE).toLong().coerceAtLeast(1)
        val boundaries = mutableListOf(0L)

        RandomAccessFile(pcm.file, "r").use { raf ->
            var cursor = 0L
            while (total - cursor > chunkSamples) {
                val nominal = cursor + chunkSamples
                val cut = quietestCut(raf, nominal, total)
                    // A quiet window may not exist in continuous speech; the
                    // nominal boundary is then the honest answer.
                    .coerceIn(cursor + chunkSamples / 2, total - 1)
                boundaries.add(cut)
                cursor = cut
            }
        }
        boundaries.add(total)

        val files = mutableListOf<File>()
        for (i in 0 until boundaries.size - 1) {
            val start = boundaries[i]
            val end = boundaries[i + 1]
            if (end <= start) continue
            val out = File(outDir, "$namePrefix-%03d.wav".format(i))
            writeWav(pcm.file, start, end, out)
            files.add(out)
        }
        return files
    }

    /**
     * Finds the start of the quietest [QUIET_WINDOW_MILLIS] window within
     * +/- [SEARCH_SECONDS] of the nominal boundary, by mean absolute amplitude.
     */
    private fun quietestCut(raf: RandomAccessFile, nominalSample: Long, totalSamples: Long): Long {
        val searchSamples = (SEARCH_SECONDS * AudioDecoder.TARGET_SAMPLE_RATE).toLong()
        val windowSamples = (QUIET_WINDOW_MILLIS * AudioDecoder.TARGET_SAMPLE_RATE / 1000).toInt()

        val from = (nominalSample - searchSamples).coerceAtLeast(0)
        val to = (nominalSample + searchSamples).coerceAtMost(totalSamples)
        val span = (to - from).toInt()
        if (span <= windowSamples) return nominalSample

        val bytes = ByteArray(span * BYTES_PER_SAMPLE)
        raf.seek(from * BYTES_PER_SAMPLE)
        raf.readFully(bytes)
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        // Rolling sum of absolute amplitude over the window.
        var sum = 0L
        for (i in 0 until windowSamples) sum += kotlin.math.abs(samples.get(i).toInt())
        var best = sum
        var bestStart = 0
        for (i in windowSamples until span) {
            sum += kotlin.math.abs(samples.get(i).toInt())
            sum -= kotlin.math.abs(samples.get(i - windowSamples).toInt())
            if (sum < best) {
                best = sum
                bestStart = i - windowSamples + 1
            }
        }
        // Cut in the middle of the quiet window.
        return from + bestStart + windowSamples / 2
    }

    private fun writeWav(pcmFile: File, startSample: Long, endSample: Long, out: File) {
        val sampleCount = endSample - startSample
        val dataBytes = sampleCount * BYTES_PER_SAMPLE
        FileOutputStream(out).use { os ->
            os.write(header(dataBytes.toInt()))
            RandomAccessFile(pcmFile, "r").use { raf ->
                raf.seek(startSample * BYTES_PER_SAMPLE)
                val buffer = ByteArray(64 * 1024)
                var remaining = dataBytes
                while (remaining > 0) {
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    os.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    /** Canonical 44-byte RIFF/WAVE header for 16-bit mono PCM. */
    fun header(dataBytes: Int, sampleRate: Int = AudioDecoder.TARGET_SAMPLE_RATE): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    /**
     * Spec 8.3: the ASR connection test sends a bundled 2 second silent WAV.
     * Generating it costs 64 kB of zeros and removes an asset from the build.
     */
    fun writeSilentWav(target: File, seconds: Int = 2): File {
        val samples = seconds * AudioDecoder.TARGET_SAMPLE_RATE
        val dataBytes = samples * BYTES_PER_SAMPLE
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { os ->
            os.write(header(dataBytes))
            val silence = ByteArray(8192)
            var remaining = dataBytes
            while (remaining > 0) {
                val n = minOf(remaining, silence.size)
                os.write(silence, 0, n)
                remaining -= n
            }
        }
        return target
    }
}
