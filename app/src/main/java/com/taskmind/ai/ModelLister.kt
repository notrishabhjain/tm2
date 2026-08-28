package com.taskmind.ai

import com.taskmind.core.LlmJson
import com.taskmind.core.ModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Asks the provider which models this key may actually use.
 *
 * `GET /models` is part of the OpenAI-compatible contract that every preset
 * here implements, and it answers per key, which is the only authority on the
 * question. Everything else - the provider's public docs, a name someone
 * remembered, a suggestion compiled into this app - can be out of date or
 * simply not apply to a particular account.
 */
class ModelLister(private val http: OkHttpClient) {

    sealed interface Result {
        data class Ok(val models: List<ModelCatalog.Model>) : Result
        data class Failed(val message: String) : Result

        /** 404 on /models: a real provider that just does not implement it. */
        data object Unsupported : Result
    }

    suspend fun list(baseUrl: String, apiKey: String): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Failed("No API key set.")
        val url = baseUrl.trim().trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 404 || response.code == 405) return@use Result.Unsupported
                if (!response.isSuccessful) {
                    val message = LlmJson.errorMessage(body)
                        ?: body.take(300).ifBlank { response.message }
                    return@use Result.Failed("HTTP ${response.code}: $message")
                }
                val models = parse(body)
                if (models.isEmpty()) {
                    Result.Failed("The provider returned an empty model list for this key.")
                } else {
                    Result.Ok(models)
                }
            }
        } catch (e: IOException) {
            Result.Failed("network: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun parse(body: String): List<ModelCatalog.Model> {
        val root = runCatching { LlmJson.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val data = runCatching { root["data"]?.jsonArray }.getOrNull() ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj.stringOr("id") ?: return@mapNotNull null
            ModelCatalog.Model(
                id = id,
                kind = ModelCatalog.classify(id),
                ownedBy = obj.stringOr("owned_by"),
                contextWindow = obj.intOr("context_window") ?: obj.intOr("max_context_length"),
            )
        }
    }

    private fun JsonObject.stringOr(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.intOr(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toInt() }.getOrNull()
}
