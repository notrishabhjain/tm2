package com.taskmind.voice

import com.taskmind.ai.AiResult
import com.taskmind.ai.errorText
import com.taskmind.capture.AudioChunker
import com.taskmind.capture.AudioDecoder
import com.taskmind.capture.CaptureCoordinator
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.tagging.AutoTagger
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a spoken note into tasks.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO
 *
 * It does not extract tasks. It transcribes, hands the text to the capture
 * coordinator as a clipboard capture, and asks the extraction worker to run.
 * Everything after that - the pre-filter, the model call, evidence grounding,
 * the confidence gate, the review queue, dedup, the intake funnel - is the
 * same code that handles a WhatsApp message or a call. A voice note is not a
 * special kind of task, it is a different way of saying one.
 *
 * That is also why this needed no changes to the capture engine: every piece
 * it calls was already public and already tested.
 *
 * It reuses [AudioDecoder] and [AudioChunker] rather than recording WAV
 * directly, so a long note is split on silence exactly as a call recording is,
 * and the transcriber receives the 16 kHz mono WAV its contract asks for.
 */
object VoiceNoteCapture {

    /**
     * The source label a voice note carries.
     *
     * Defined by the tagger, not here. The tagger has to recognise this exact
     * string to tag a note "voice" instead of treating it as a sender's name,
     * and two constants that must match are two constants that eventually will
     * not.
     */
    const val LABEL = AutoTagger.VOICE_LABEL

    sealed interface Result {
        /** Transcribed and queued. [text] is shown back so you can see it heard you. */
        data class Queued(val text: String) : Result

        /** Recorded fine, but there were no words in it. */
        object NoSpeech : Result

        /** Something went wrong; [message] is fit to show on screen. */
        data class Failed(val message: String) : Result
    }

    suspend fun process(container: AppContainer, recording: File): Result = withContext(Dispatchers.IO) {
        val work = File(container.context.cacheDir, "voice/work-${recording.nameWithoutExtension}")
        work.mkdirs()

        try {
            val settings = container.settingsRepository.current()

            // Consent gates the microphone the same way it gates everything
            // else that leaves the device. Checked here rather than at the
            // button so the message can say why.
            if (!settings.cloudConsent) {
                return@withContext Result.Failed(
                    "Turn on cloud processing in Settings to transcribe voice notes.",
                )
            }

            val pcm = runCatching {
                AudioDecoder.decodeToMonoPcm(recording, File(work, "audio.pcm"))
            }.getOrElse {
                return@withContext Result.Failed("That recording could not be read.")
            }

            val chunks = AudioChunker.chunk(pcm, CHUNK_SECONDS, work, "note")
            if (chunks.isEmpty()) return@withContext Result.NoSpeech

            val (transcriber, _) = container.transcriber()
            val parts = mutableListOf<String>()
            for (chunk in chunks) {
                when (val out = transcriber.transcribe(chunk, settings.asrLanguage)) {
                    is AiResult.Ok -> out.value.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                    else -> return@withContext Result.Failed(
                        out.errorText ?: "The transcriber did not answer.",
                    )
                }
            }

            val text = parts.joinToString(" ").trim()
            if (text.isEmpty()) return@withContext Result.NoSpeech

            when (container.captureCoordinator.captureClipboardTranscript(text, LABEL, System.currentTimeMillis())) {
                is CaptureCoordinator.Outcome.Captured -> {
                    Scheduler.enqueueExtraction(container.context)
                    container.logger.write(
                        Stage.CAPTURE,
                        LogLevel.INFO,
                        "voice note captured",
                        text.take(120),
                    )
                    Result.Queued(text)
                }
                // The same words twice. The capture already exists and is
                // already queued, so saying "captured" would be a lie and
                // saying "failed" would be worse.
                is CaptureCoordinator.Outcome.Duplicate ->
                    Result.Failed("You already recorded that one.")

                else -> Result.Failed("There was nothing usable in that note.")
            }
        } catch (t: Throwable) {
            container.logger.write(Stage.CAPTURE, LogLevel.WARN, "voice note failed", t.toString())
            Result.Failed(t.message ?: "That did not work.")
        } finally {
            // The audio is the sensitive part and it has served its purpose
            // the moment the text exists. Deleted whatever the outcome.
            runCatching { work.deleteRecursively() }
            runCatching { recording.delete() }
        }
    }

    /** Matches the call pipeline's chunking, so a long note behaves the same. */
    private const val CHUNK_SECONDS = 120.0
}
