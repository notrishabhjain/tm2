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
                .build()

            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()

            execute(http, request)
        }

    private companion object {
        val WAV = "audio/wav".toMediaType()
    }
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

            execute(http, request)
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

private fun execute(http: OkHttpClient, request: Request): AiResult<String> = try {
    http.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = LlmJson.errorMessage(raw) ?: raw.take(300).ifBlank { response.message }
            AiResult.HttpError(response.code, message)
        } else {
            val text = LlmJson.transcriptText(raw)
            if (text.isNullOrBlank()) {
                // A 2xx with no text is a real outcome for silent audio. Treat
                // it as an empty transcript, not an error: the capture still
                // completes and is not retried forever.
                AiResult.Ok("")
            } else {
                AiResult.Ok(text.trim())
            }
        }
    }
} catch (e: IOException) {
    AiResult.NetworkError(e.message ?: e.javaClass.simpleName)
}

/** Picks the adapter the user configured. */
object TranscriberFactory {
    fun create(http: OkHttpClient, configProvider: suspend () -> AsrConfig, provider: AsrProvider): Transcriber =
        when (provider) {
            AsrProvider.SARVAM -> SarvamTranscriber(http, configProvider)
            AsrProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleTranscriber(http, configProvider)
        }
}
