package com.taskmind.capture

import android.content.Context
import com.taskmind.ai.AiResult
import com.taskmind.ai.errorText
import com.taskmind.ai.retryable
import com.taskmind.ai.Transcriber
import com.taskmind.core.AsrProvider
import com.taskmind.core.Backoff
import com.taskmind.core.CaptureState
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.repo.ActivityLogger
import com.taskmind.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spec 12 - transcription.
 *
 * Never discard the call. On failure after backoff the capture stays
 * PENDING_TRANSCRIPTION with the recording path intact, and the status screen
 * shows why.
 */
class TranscriptionPipeline(
    private val context: Context,
    private val rawCaptureDao: RawCaptureDao,
    private val settingsRepository: SettingsRepository,
    private val transcriberProvider: suspend () -> Pair<Transcriber, AsrProvider>,
    private val logger: ActivityLogger,
    private val hasAsrKey: () -> Boolean,
) {

    sealed interface Outcome {
        data class Transcribed(val chars: Int, val seconds: Int) : Outcome
        data class Parked(val reason: String) : Outcome
        data class BudgetHeld(val reason: String) : Outcome
        data class Retry(val reason: String, val at: Long?) : Outcome
        data class Permanent(val reason: String) : Outcome
    }

    suspend fun transcribe(capture: RawCaptureEntity): Outcome = withContext(Dispatchers.IO) {
        val path = capture.audioPath
        if (path.isNullOrBlank()) {
            return@withContext Outcome.Parked("no recording found yet")
        }
        if (!AudioSource.exists(context, path)) {
            return@withContext Outcome.Parked("recording no longer readable at $path")
        }
        if (!hasAsrKey()) {
            // Spec 8.4: nothing is discarded for lack of a key.
            return@withContext Outcome.Parked("no ASR API key configured")
        }

        val settings = settingsRepository.current()
        val now = System.currentTimeMillis()
        val todayKey = DateResolver.dayKey(now)
        settingsRepository.rollBudgetIfNeeded(todayKey)
        val usage = settingsRepository.currentUsage()
        val budgetSeconds = settings.maxAsrMinutesPerDay * 60
        if (usage.asrSeconds >= budgetSeconds) {
            rawCaptureDao.setRetry(
                capture.id,
                CaptureState.BUDGET_HELD,
                capture.attemptCount,
                "daily ASR budget of ${settings.maxAsrMinutesPerDay} minutes reached",
                DateResolver.nextMidnight(now),
            )
            logger.write(Stage.BUDGET, LogLevel.INFO, "call held for ASR budget", capture.sourceRef)
            return@withContext Outcome.BudgetHeld("daily ASR budget reached")
        }

        val workDir = File(context.cacheDir, "asr/${capture.id}").apply { mkdirs() }
        val (transcriber, provider) = transcriberProvider()

        // A recording found in the user's nominated folder is a content:// URI,
        // which the decoder cannot open as a file. Resolving it here is what
        // makes the folder picker work at all.
        val source = AudioSource.materialise(context, path, workDir)
            ?: return@withContext Outcome.Parked("recording could not be read from $path")

        try {
            val pcm = AudioDecoder.decodeToMonoPcm(source, File(workDir, "audio.pcm"))
            val durationSeconds = pcm.durationSeconds.toInt()
            logger.write(
                Stage.TRANSCRIBE,
                LogLevel.INFO,
                "decoded ${source.name}",
                "seconds=$durationSeconds provider=${transcriber.label}",
            )

            // Sarvam's sync endpoint caps around 30 s per request, so always
            // chunk for it (spec 8.2 / 12.2).
            val maxChunkSeconds = if (provider == AsrProvider.SARVAM) SARVAM_CHUNK_SECONDS else OPENAI_CHUNK_SECONDS
            val chunks = AudioChunker.chunk(pcm, maxChunkSeconds, workDir, "chunk")
            if (chunks.isEmpty()) {
                return@withContext fail(capture, "no audio after decoding", now)
            }

            // Spec 12.3: checkpoint per chunk so a killed process resumes
            // rather than restarting - and re-paying for what it already did.
            val startIndex = if (capture.chunkTotal == chunks.size) capture.chunkIndex else 0
            val builder = StringBuilder(if (startIndex > 0) capture.partialText.orEmpty() else "")

            for (index in startIndex until chunks.size) {
                val chunk = chunks[index]
                val result = transcriber.transcribe(chunk, settings.asrLanguage)
                when (result) {
                    is AiResult.Ok -> {
                        if (result.value.isNotBlank()) {
                            if (builder.isNotEmpty()) builder.append(' ')
                            builder.append(result.value)
                        }
                        rawCaptureDao.setChunkProgress(capture.id, builder.toString(), index + 1, chunks.size)
                    }
                    else -> {
                        rawCaptureDao.setChunkProgress(capture.id, builder.toString(), index, chunks.size)
                        return@withContext fail(capture, result.errorText ?: "ASR failed", now, retryable = result.retryable)
                    }
                }
            }

            settingsRepository.recordAsrSeconds(todayKey, durationSeconds)

            val transcript = builder.toString().trim()
            if (transcript.isBlank()) {
                // Silence is a real outcome, not a failure. Close the capture
                // so it is not retried against the same silent audio forever.
                rawCaptureDao.setTranscript(capture.id, "", CaptureState.REJECTED)
                logger.write(Stage.TRANSCRIBE, LogLevel.INFO, "transcript was empty", capture.sourceLabel)
                return@withContext Outcome.Transcribed(0, durationSeconds)
            }

            rawCaptureDao.setTranscript(capture.id, transcript, CaptureState.PENDING_EXTRACTION)
            logger.write(
                Stage.TRANSCRIBE,
                LogLevel.INFO,
                "transcribed ${chunks.size} chunk(s)",
                "chars=${transcript.length} seconds=$durationSeconds provider=${transcriber.label}",
            )

            if (settings.deleteRecordingsAfterTranscription) {
                // Delete the ORIGINAL, not `source` - for a content:// URI
                // `source` is a scratch copy in the cache, and deleting that
                // would leave the user's recording in place while reporting
                // that the setting had been honoured.
                runCatching { AudioSource.delete(context, path) }
            }
            Outcome.Transcribed(transcript.length, durationSeconds)
        } catch (e: AudioDecoder.DecodeException) {
            fail(capture, e.message ?: "could not decode the recording", now, retryable = false)
        } catch (e: Exception) {
            fail(capture, e.message ?: e.javaClass.simpleName, now)
        } finally {
            // Keep nothing but the original recording; the scratch PCM and the
            // chunks are reproducible.
            runCatching { workDir.listFiles()?.forEach { if (it.name != "audio.pcm") it.delete() } }
            runCatching { File(workDir, "audio.pcm").delete() }
        }
    }

    private suspend fun fail(
        capture: RawCaptureEntity,
        error: String,
        now: Long,
        retryable: Boolean = true,
    ): Outcome {
        val attempts = capture.attemptCount + 1
        val nextAt = if (retryable) Backoff.nextAttemptAt(attempts, now) else null
        return if (nextAt == null && Backoff.isExhausted(attempts)) {
            // The recording path stays intact: a permanent failure is still
            // recoverable by hand, and the audio is never deleted here.
            rawCaptureDao.setRetry(capture.id, CaptureState.FAILED_PERMANENT, attempts, error, null)
            logger.write(Stage.TRANSCRIBE, LogLevel.ERROR, "transcription failed permanently", error)
            Outcome.Permanent(error)
        } else if (nextAt == null) {
            rawCaptureDao.setRetry(capture.id, CaptureState.PENDING_TRANSCRIPTION, attempts, error, null)
            logger.write(Stage.TRANSCRIBE, LogLevel.ERROR, "transcription failed, not retryable", error)
            Outcome.Parked(error)
        } else {
            rawCaptureDao.setRetry(capture.id, CaptureState.PENDING_TRANSCRIPTION, attempts, error, nextAt)
            logger.write(Stage.TRANSCRIBE, LogLevel.WARN, "transcription failed, retrying (attempt $attempts)", error)
            Outcome.Retry(error, nextAt)
        }
    }

    private companion object {
        const val SARVAM_CHUNK_SECONDS = 25.0
        const val OPENAI_CHUNK_SECONDS = 25.0 * 24 // ~10 minutes, well inside the 25 MB limit
    }
}
