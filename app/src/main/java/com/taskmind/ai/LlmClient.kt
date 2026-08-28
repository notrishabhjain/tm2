package com.taskmind.ai

import com.taskmind.core.LlmJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Spec 8.1: any OpenAI-compatible `/v1/chat/completions` endpoint, configured
 * by the user as base URL + key + model.
 *
 * Every call is handed to the [InferenceRecorder] whether it succeeds or fails,
 * with the exact prompts sent and the unedited reply. Nothing the app sends to
 * a model is invisible to the person whose messages are in it.
 */
class LlmClient(
    private val http: OkHttpClient,
    private val recorder: InferenceRecorder = InferenceRecorder.None,
) {

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    data class Completion(
        val content: String,
        val totalTokens: Int?,
        /** Whether JSON mode was actually used, for the activity log. */
        val jsonMode: Boolean,
        val latencyMillis: Long,
    )

    /** Everything one HTTP attempt produced, for the trace. */
    private data class Attempt(
        val result: AiResult<Completion>,
        val httpStatus: Int?,
        val rawBody: String?,
        val durationMillis: Long,
    )

    /**
     * Sends the prompt with `response_format: json_object` and `temperature: 0`.
     * If the provider rejects `response_format`, retries once without it - many
     * OpenAI-compatible endpoints do not implement it.
     */
    suspend fun complete(
        config: Config,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 1200,
        trace: TraceContext = TraceContext(RecordedCall.KIND_MESSAGE),
    ): AiResult<Completion> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var attempt = post(config, systemPrompt, userPrompt, maxTokens, jsonMode = true)

        val first = attempt.result
        if (first is AiResult.HttpError && first.code == 400 && mentionsResponseFormat(first.message)) {
            attempt = post(config, systemPrompt, userPrompt, maxTokens, jsonMode = false)
        }

        recorder.record(
            RecordedCall(
                kind = trace.kind,
                baseUrl = config.baseUrl,
                model = config.model,
                startedAt = startedAt,
                durationMillis = attempt.durationMillis,
                systemPrompt = RecordedCall.clip(systemPrompt),
                userPrompt = RecordedCall.clip(userPrompt),
                httpStatus = attempt.httpStatus,
                ok = attempt.result is AiResult.Ok,
                responseBody = RecordedCall.clip(attempt.rawBody),
                totalTokens = (attempt.result as? AiResult.Ok)?.value?.totalTokens,
                errorText = attempt.result.errorText,
                rawCaptureId = trace.rawCaptureId,
                sourceLabel = trace.sourceLabel,
            ),
        )

        attempt.result
    }

    private fun mentionsResponseFormat(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("response_format") || m.contains("json_object") || m.contains("json mode")
    }

    private fun post(
        config: Config,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        jsonMode: Boolean,
    ): Attempt {
        val payload: JsonObject = buildJsonObject {
            put("model", config.model)
            put("temperature", 0)
            put("max_tokens", maxTokens)
            if (jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", userPrompt)
                        },
                    )
                },
            )
        }

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(LlmJson.json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON))
            .build()

        val began = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - began

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = LlmJson.errorMessage(body) ?: body.take(300).ifBlank { response.message }
                    return Attempt(
                        AiResult.HttpError(response.code, message, response.header("Retry-After")),
                        response.code,
                        body,
                        elapsed(),
                    )
                }
                val content = LlmJson.chatContent(body)
                    ?: return Attempt(
                        AiResult.BadResponse("no message content in reply: ${body.take(200)}"),
                        response.code,
                        body,
                        elapsed(),
                    )
                Attempt(
                    AiResult.Ok(Completion(content, LlmJson.usageTokens(body), jsonMode, elapsed())),
                    response.code,
                    body,
                    elapsed(),
                )
            }
        } catch (e: IOException) {
            Attempt(AiResult.NetworkError(e.message ?: e.javaClass.simpleName), null, null, elapsed())
        } catch (e: IllegalStateException) {
            Attempt(AiResult.BadResponse(e.message ?: "illegal state"), null, null, elapsed())
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
