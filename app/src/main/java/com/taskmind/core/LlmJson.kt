package com.taskmind.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsing model output.
 *
 * Two rules from the spec shape this file:
 *
 *  - Spec 8.1: if the provider rejects `response_format`, we retry once without
 *    it and fall back to extracting the first balanced `{...}` block. But a
 *    response that then fails schema validation is DISCARDED, not rescued
 *    field-by-field. Half a hallucination is still a hallucination.
 *  - Principle 2, precision beats recall: a missing or unreadable confidence is
 *    treated as uncertain, never as certain.
 *
 * Scalar type coercion (a number arriving as `"0.9"`, a boolean as `"true"`) is
 * not leniency - providers genuinely differ on this and the value is unambiguous.
 */
object LlmJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Scans for the first balanced `{...}` block, respecting string literals and
     * escapes so a brace inside a quote does not end the object.
     */
    fun extractFirstJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** Strips ```json fences some providers add despite being told not to. */
    fun stripFences(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    fun parseObject(raw: String): JsonObject? {
        val cleaned = stripFences(raw)
        runCatching { return json.parseToJsonElement(cleaned).jsonObject }
        val block = extractFirstJsonObject(cleaned) ?: return null
        runCatching { return json.parseToJsonElement(block).jsonObject }
        return null
    }

    // ------------------------------------------------------- tolerant getters

    private fun JsonObject.el(key: String): JsonElement? =
        this[key]?.takeIf { it !is JsonNull }

    fun JsonObject.str(key: String): String? {
        val e = el(key) as? JsonPrimitive ?: return null
        val v = e.content
        return if (v.isBlank() || v.equals("null", ignoreCase = true)) null else v
    }

    fun JsonObject.bool(key: String): Boolean? {
        val e = el(key) as? JsonPrimitive ?: return null
        e.booleanOrNull?.let { return it }
        return when (e.content.trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }
    }

    fun JsonObject.dbl(key: String): Double? {
        val e = el(key) as? JsonPrimitive ?: return null
        e.doubleOrNull?.let { return it }
        return e.content.trim().toDoubleOrNull()
    }

    fun JsonObject.int(key: String): Int? = dbl(key)?.toInt()

    fun JsonObject.arr(key: String): JsonArray? = el(key) as? JsonArray

    fun JsonObject.strList(key: String): List<String> =
        arr(key)?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } } ?: emptyList()

    // --------------------------------------------------------- response shape

    /** Pulls `choices[0].message.content` out of an OpenAI-compatible reply. */
    fun chatContent(body: String): String? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val choices = obj["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        val message = first["message"]?.jsonObject
        val content = message?.get("content")
        if (content is JsonPrimitive) return content.content
        // Some providers return content as an array of parts.
        if (content is JsonArray) {
            return content.joinToString("") { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty()
            }
        }
        return (first["text"] as? JsonPrimitive)?.content
    }

    fun usageTokens(body: String): Int? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val usage = obj["usage"]?.jsonObject ?: return null
        return (usage["total_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
    }

    /** Pulls `text` out of a transcription reply, OpenAI or Sarvam shaped. */
    fun transcriptText(body: String): String? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return body.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
        (obj["text"] as? JsonPrimitive)?.content?.let { if (it.isNotBlank()) return it }
        (obj["transcript"] as? JsonPrimitive)?.content?.let { if (it.isNotBlank()) return it }
        // Sarvam batch shape.
        (obj["transcripts"] as? JsonArray)?.let { arr ->
            val joined = arr.joinToString(" ") { (it as? JsonPrimitive)?.content.orEmpty() }.trim()
            if (joined.isNotEmpty()) return joined
        }
        return null
    }

    /** Best-effort extraction of a provider error message for the activity log. */
    fun errorMessage(body: String): String? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val err = obj["error"]
        if (err is JsonObject) return (err["message"] as? JsonPrimitive)?.content
        if (err is JsonPrimitive) return err.content
        return (obj["message"] as? JsonPrimitive)?.content
            ?: (obj["detail"] as? JsonPrimitive)?.content
    }
}
