package com.taskmind.ai

import com.taskmind.core.LlmJson
import com.taskmind.core.RateLimit
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
 *
 * A [Config] may carry a backup provider (v1.31.1). When the main one fails -
 * a rate limit, a bad key, a server error, no network - the same request goes
 * to the backup at once and its answer is used. After a rate limit the main
 * provider is left to rest until its reset, so the following calls go straight
 * to the backup instead of spending a request to hear "429" again.
 */
class LlmClient(
    private val http: OkHttpClient,
    private val recorder: InferenceRecorder = InferenceRecorder.None,
) {

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        /** Tried when this provider fails. Null or keyless means no backup. */
        val backup: Config? = null,
    ) {
        val usableBackup: Config?
            get() = backup?.takeIf { it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() && it.model.isNotBlank() }
                ?.copy(backup = null)
    }

    data class Completion(
        val content: String,
        val totalTokens: Int?,
        /** Whether JSON mode was actually used, for the activity log. */
        val jsonMode: Boolean,
        val latencyMillis: Long,
        /** True when the backup provider produced this answer. */
        val fromBackup: Boolean = false,
    )

    /** Everything one HTTP attempt produced, for the trace. */
    private data class Attempt(
        val result: AiResult<Completion>,
        val httpStatus: Int?,
        val rawBody: String?,
        val durationMillis: Long,
    )

    /**
     * When the main provider may next be asked, after it rate limited us.
     * Kept in memory only: after a restart the first call simply finds out
     * again. Keyed by base URL + model so changing the main provider clears it.
     */
    @Volatile private var primaryRest: Pair<String, Long>? = null

    /**
     * Sends the prompt with `response_format: json_object` and `temperature: 0`.
     * If the provider rejects `response_format`, retries once without it - many
     * OpenAI-compatible endpoints do not implement it.
     *
     * With a backup in [config] (and [allowBackup]), a failed call is sent
     * again to the backup. If both fail, the main provider's error is
     * returned, so a rate limit still parks the queue and a bad main key still
     * shows as a configuration problem.
     */
    suspend fun complete(
        config: Config,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 1200,
        trace: TraceContext = TraceContext(RecordedCall.KIND_MESSAGE),
        allowBackup: Boolean = true,
    ): AiResult<Completion> = withContext(Dispatchers.IO) {
        val backup = if (allowBackup) config.usableBackup else null
        val now = System.currentTimeMillis()
        val restingUntil = primaryRest?.takeIf { it.first == keyOf(config) }?.second ?: 0L

        // Only a backup key saved: there is nothing to ask first.
        if (backup != null && config.apiKey.isBlank()) {
            val only = once(backup, systemPrompt, userPrompt, maxTokens, trace)
            return@withContext if (only is AiResult.Ok) AiResult.Ok(only.value.copy(fromBackup = true)) else only
        }

        if (backup != null && restingUntil > now) {
            val viaBackup = once(backup, systemPrompt, userPrompt, maxTokens, trace)
            if (viaBackup is AiResult.Ok) return@withContext AiResult.Ok(viaBackup.value.copy(fromBackup = true))
            // The main provider is still resting, so this is a rate limit as far
            // as the queue is concerned: park until the main one is back rather
            // than marking the capture broken over the backup's error.
            val seconds = ((restingUntil - now) / 1000).coerceAtLeast(1)
            return@withContext AiResult.HttpError(
                429,
                "main provider rate limited for another ${seconds}s; backup failed too: ${viaBackup.errorText}",
                seconds.toString(),
            )
        }

        val first = once(config, systemPrompt, userPrompt, maxTokens, trace)
        if (first is AiResult.Ok || backup == null) return@withContext first

        if (first is AiResult.HttpError && first.rateLimited) {
            val wait = RateLimit.cooldownMillis(first.retryAfter, first.message)
            primaryRest = keyOf(config) to System.currentTimeMillis() + wait
        }
        val second = once(backup, systemPrompt, userPrompt, maxTokens, trace)
        if (second is AiResult.Ok) AiResult.Ok(second.value.copy(fromBackup = true)) else first
    }

    private fun keyOf(config: Config) = config.baseUrl.trimEnd('/') + "|" + config.model

    /** When the main provider in [config] may next be asked; 0 when it is not resting (v1.36.1). */
    fun restingUntil(config: Config): Long =
        primaryRest?.takeIf { it.first == keyOf(config) }?.second?.takeIf { it > System.currentTimeMillis() } ?: 0L

    /** The same, for whichever main provider last rested; for callers without a config at hand. */
    fun restingUntilAny(): Long = primaryRest?.second?.takeIf { it > System.currentTimeMillis() } ?: 0L

    /** One provider: the JSON-mode attempt, the plain retry if needed, and the record. */
    private suspend fun once(
        config: Config,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        trace: TraceContext,
    ): AiResult<Completion> {
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
        return attempt.result
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
            .apply {
                // OpenRouter asks apps to name themselves; it is optional and harmless.
                if (url.contains("openrouter.ai")) addHeader("X-Title", "TaskMind")
            }
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
