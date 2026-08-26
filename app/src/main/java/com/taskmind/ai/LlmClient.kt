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
 */
class LlmClient(private val http: OkHttpClient) {

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
    ): AiResult<Completion> = withContext(Dispatchers.IO) {
        val first = post(config, systemPrompt, userPrompt, maxTokens, jsonMode = true)
        if (first is AiResult.HttpError && first.code == 400 && mentionsResponseFormat(first.message)) {
            val second = post(config, systemPrompt, userPrompt, maxTokens, jsonMode = false)
            return@withContext second
        }
        first
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
    ): AiResult<Completion> {
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

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = LlmJson.errorMessage(body) ?: body.take(300).ifBlank { response.message }
                    return AiResult.HttpError(response.code, message)
                }
                val content = LlmJson.chatContent(body)
                    ?: return AiResult.BadResponse("no message content in reply: ${body.take(200)}")
                AiResult.Ok(Completion(content, LlmJson.usageTokens(body), jsonMode))
            }
        } catch (e: IOException) {
            AiResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalStateException) {
            AiResult.BadResponse(e.message ?: "illegal state")
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
