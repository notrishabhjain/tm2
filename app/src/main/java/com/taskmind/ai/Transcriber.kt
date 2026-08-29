package com.taskmind.ai

import com.taskmind.core.AsrProvider
import com.taskmind.core.LlmJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/**
 * Spec 24: transcription sits behind an interface so an on-device engine can be
 * added in v2 without touching the funnel.
 */
interface Transcriber {

    val label: String

    /** @param audio a 16 kHz mono PCM WAV file, already chunked if needed. */
    suspend fun transcribe(audio: File, languageCode: String): AiResult<String>
}

data class AsrConfig(
    val provider: AsrProvider,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val language: String,
)

/**
 * Spec 8.2: `POST {base}/audio/transcriptions`, multipart with file, model,
 * language. Presets: OpenAI (whisper-1), Groq (whisper-large-v3).
 */
class OpenAiCompatibleTranscriber(
    private val http: OkHttpClient,
    private val configProvider: suspend () -> AsrConfig,
    private val recorder: InferenceRecorder = InferenceRecorder.None,
) : Transcriber {

    override val label: String get() = "openai-compatible"

    override suspend fun transcribe(audio: File, languageCode: String): AiResult<String> =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audio.name, audio.asRequestBody(WAV))
                .addFormDataPart("model", config.model)
                .addFormDataPart("language", languageCode.ifBlank { config.language })
                .addFormDataPart("response_format", "json")
                // Deterministic, and no creative reconstruction of unclear
                // audio. The default lets Whisper sample, which on noisy phone
                // audio is how you get confident nonsense.
                .addFormDataPart("temperature", "0")
                // Whisper conditions on this text. On the device it returned
                // "गुद मॉर्निंग" for "good morning" and "विप्टेण" for VPN -
                // Hinglish office speech, where English technical words sit
                // inside Hindi sentences, is exactly the case a prompt fixes.
                .addFormDataPart("prompt", promptHintFor(languageCode.ifBlank { config.language }))
                .build()

            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()

            execute(http, request, recorder, config, audio.name)
        }

    private companion object {
        val WAV = "audio/wav".toMediaType()
    }
}

/**
 * A vocabulary hint for the recogniser.
 *
 * Whisper uses the prompt as preceding context, so a sample of the register it
 * is about to hear measurably improves it. Indian office calls are Hinglish:
 * Hindi grammar carrying English nouns, which a Hindi-only model transliterates
 * into nonsense and an English-only model drops entirely.
 */
internal fun promptHintFor(languageCode: String): String = when {
    languageCode.startsWith("hi") ->
        "यह एक ऑफिस कॉल है। बातचीत हिंदी और अंग्रेज़ी दोनों में है (Hinglish). " +
            "आम शब्द: meeting, report, email, invoice, payment, deadline, follow up, " +
            "confirm, update, share, send, call back, VPN, IP, server, firewall, portal, " +
            "ticket, request, approval, team, sir, ji, kal, aaj, subah, shaam, please."
    else ->
        "This is an office phone call. Speakers mix English with Hindi words. " +
            "Common terms: meeting, report, email, invoice, payment, deadline, follow up, " +
            "confirm, update, share, send, call back, VPN, IP, server, firewall, portal, " +
            "ticket, request, approval."
}

/**
 * Spec 8.2: Sarvam is purpose-built for Indian languages and materially better
 * than Whisper on Hindi phone audio. It is not OpenAI-shaped, so it gets its
 * own adapter: a different auth header, different field names, and a sync
 * endpoint that caps around 30 s per request (hence the always-chunk rule in
 * AudioChunker).
 */
class SarvamTranscriber(
    private val http: OkHttpClient,
    private val configProvider: suspend () -> AsrConfig,
    private val recorder: InferenceRecorder = InferenceRecorder.None,
) : Transcriber {

    override val label: String get() = "sarvam"

    override suspend fun transcribe(audio: File, languageCode: String): AiResult<String> =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            val language = toSarvamLanguage(languageCode.ifBlank { config.language })
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audio.name, audio.asRequestBody(WAV))
                .addFormDataPart("model", config.model.ifBlank { "saarika:v2.5" })
                .addFormDataPart("language_code", language)
                .build()

            val base = config.baseUrl.trimEnd('/').ifBlank { "https://api.sarvam.ai" }
            val request = Request.Builder()
                .url("$base/speech-to-text")
                .addHeader("api-subscription-key", config.apiKey)
                .post(body)
                .build()

            execute(http, request, recorder, config, audio.name)
        }

    /** Sarvam wants BCP-47-ish codes: "hi" -> "hi-IN". */
    private fun toSarvamLanguage(code: String): String {
        val c = code.trim()
        if (c.contains('-')) return c
        return when (c.lowercase()) {
            "", "hi" -> "hi-IN"
            "en" -> "en-IN"
            else -> "$c-IN"
        }
    }

    private companion object {
        val WAV = "audio/wav".toMediaType()
    }
}

/**
 * Runs the multipart request and records it. Audio is not stored in the trace -
 * only which file was sent, how long it took, and what came back - because the
 * recording itself already lives on disk and copying it into the database
 * would double the space it takes.
 */
private suspend fun execute(
    http: OkHttpClient,
    request: Request,
    recorder: InferenceRecorder,
    config: AsrConfig,
    audioName: String,
): AiResult<String> {
    val startedAt = System.currentTimeMillis()
    var status: Int? = null
    var raw: String? = null

    val result = try {
        http.newCall(request).execute().use { response ->
            status = response.code
            val body = response.body?.string().orEmpty()
            raw = body
            if (!response.isSuccessful) {
                val message = LlmJson.errorMessage(body) ?: body.take(300).ifBlank { response.message }
                AiResult.HttpError(response.code, message)
            } else {
                val text = LlmJson.transcriptText(body)
                if (text.isNullOrBlank()) {
                    // A 2xx with no text is a real outcome for silent audio.
                    // Treat it as an empty transcript, not an error: the capture
                    // still completes and is not retried forever.
                    AiResult.Ok("")
                } else {
                    AiResult.Ok(text.trim())
                }
            }
        }
    } catch (e: IOException) {
        AiResult.NetworkError(e.message ?: e.javaClass.simpleName)
    }

    recorder.record(
        RecordedCall(
            kind = RecordedCall.KIND_ASR,
            baseUrl = config.baseUrl,
            model = config.model,
            startedAt = startedAt,
            durationMillis = System.currentTimeMillis() - startedAt,
            userPrompt = "audio chunk: $audioName (language ${config.language})",
            httpStatus = status,
            ok = result is AiResult.Ok,
            responseBody = RecordedCall.clip(raw),
            errorText = result.errorText,
            sourceLabel = audioName,
        ),
    )
    return result
}

/** Picks the adapter the user configured. */
object TranscriberFactory {
    fun create(
        http: OkHttpClient,
        configProvider: suspend () -> AsrConfig,
        provider: AsrProvider,
        recorder: InferenceRecorder = InferenceRecorder.None,
    ): Transcriber = when (provider) {
        AsrProvider.SARVAM -> SarvamTranscriber(http, configProvider, recorder)
        AsrProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleTranscriber(http, configProvider, recorder)
    }
}
