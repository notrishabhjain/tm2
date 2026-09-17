package com.taskmind.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a short spoken note.
 *
 * AAC in an MP4 container at 16 kHz mono - the same rate the decoder
 * downsamples everything to anyway, so recording at it means one less
 * resample and a smaller file to upload. Speech at this rate is what every
 * speech model is trained on; recording at 44.1 kHz would cost bandwidth and
 * buy nothing.
 *
 * Nothing here touches the network or the database. It produces a file and
 * hands it over; [VoiceNoteCapture] does the rest.
 */
class VoiceNoteRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /**
     * Begins recording into a fresh file in the cache directory.
     *
     * Returns false rather than throwing when the microphone cannot be opened
     * - another app holding it, or the permission having been revoked between
     * the check and the press - because the caller's only sensible response is
     * to tell the user, not to crash.
     */
    fun start(): Boolean {
        if (recorder != null) return false

        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "note-${System.currentTimeMillis()}.m4a")

        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = created
            target = file
            startedAt = System.currentTimeMillis()
            true
        } catch (t: Throwable) {
            runCatching { created.release() }
            runCatching { file.delete() }
            recorder = null
            target = null
            false
        }
    }

    /**
     * Stops and returns the file, or null if there is nothing usable.
     *
     * A press that is released almost immediately produces a file with no
     * audio in it, and MediaRecorder.stop() throws outright if it is called
     * before any frame was written. Both end up here as null, which the caller
     * reads as "too short" rather than as a failure.
     */
    fun stop(): File? {
        val active = recorder ?: return null
        val file = target
        val heldFor = System.currentTimeMillis() - startedAt

        recorder = null
        target = null

        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }

        if (!stopped || file == null || heldFor < MIN_MILLIS || !file.exists() || file.length() < MIN_BYTES) {
            runCatching { file?.delete() }
            return null
        }
        return file
    }

    /** Throws the recording away. Used when the press is cancelled. */
    fun cancel() {
        val active = recorder ?: return
        val file = target
        recorder = null
        target = null
        runCatching { active.stop() }
        runCatching { active.release() }
        runCatching { file?.delete() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 64_000

        /** Below this a press is a mis-tap, not a note. */
        const val MIN_MILLIS = 700L
        const val MIN_BYTES = 1024L
    }
}
