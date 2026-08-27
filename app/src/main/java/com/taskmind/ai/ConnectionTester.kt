package com.taskmind.ai

import com.taskmind.core.LlmJson
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import java.io.File

/**
 * Spec 8.3 - Test connection.
 *
 * Without this, a wrong base URL presents identically to a broken pipeline, and
 * diagnosing it costs a CI cycle. Both tests write their result to the activity
 * log so the outcome survives leaving the screen.
 */
class ConnectionTester(
    private val llm: LlmClient,
    private val log: suspend (String, LogLevel, String, String?) -> Unit,
) {

    data class TestResult(val ok: Boolean, val message: String, val millis: Long)

    /** Sends a two-token prompt and asserts a JSON reply. */
    suspend fun testLlm(config: LlmClient.Config): TestResult {
        if (config.apiKey.isBlank()) {
            return fail("No API key set for the LLM provider.", 0)
        }
        val started = System.currentTimeMillis()
        val result = llm.complete(
            config = config,
            systemPrompt = "Reply with only this JSON and nothing else: {\"ok\":true}",
            userPrompt = "ping",
            maxTokens = 32,
        )
        val elapsed = System.currentTimeMillis() - started
        return when (result) {
            is AiResult.Ok -> {
                val parsed = LlmJson.parseObject(result.value.content)
                if (parsed == null) {
                    fail("Connected, but the reply was not JSON: ${result.value.content.take(120)}", elapsed)
                } else {
                    ok("LLM reachable (${config.model}), JSON reply in ${elapsed}ms", elapsed)
                }
            }
            else -> fail(explain(result), elapsed)
        }
    }

    /**
     * Sends a bundled 2-second silent WAV and asserts a 2xx. An empty
     * transcript is the correct answer for silence.
     */
    suspend fun testAsr(transcriber: Transcriber, sample: File, language: String): TestResult {
        val started = System.currentTimeMillis()
        val result = transcriber.transcribe(sample, language)
        val elapsed = System.currentTimeMillis() - started
        return when (result) {
            is AiResult.Ok -> ok("ASR reachable (${transcriber.label}) in ${elapsed}ms", elapsed)
            else -> fail(explain(result), elapsed)
        }
    }

    private fun explain(result: AiResult<*>): String = when (result) {
        is AiResult.Ok -> "ok"
        is AiResult.HttpError -> when {
            result.code == 401 -> "Rejected: the API key is wrong or expired (HTTP 401)."
            result.code == 403 -> "Rejected: this key is not allowed to use that model (HTTP 403)."
            result.code == 404 -> "Not found: check the base URL and the model name (HTTP 404)."
            result.code == 429 -> "Rate limited (HTTP 429). The key works; try again shortly."
            result.code in 500..599 -> "Provider error (HTTP ${result.code}). Not your configuration."
            else -> "HTTP ${result.code}: ${result.message.take(200)}"
        }
        is AiResult.NetworkError -> "Could not reach the provider: ${result.message}. Check the base URL and the network."
        is AiResult.BadResponse -> "Reached the provider but could not read the reply: ${result.message.take(200)}"
    }

    private suspend fun ok(message: String, millis: Long): TestResult {
        log(Stage.SYSTEM, LogLevel.INFO, "connection test passed", message)
        return TestResult(true, message, millis)
    }

    private suspend fun fail(message: String, millis: Long): TestResult {
        log(Stage.SYSTEM, LogLevel.ERROR, "connection test failed", message)
        return TestResult(false, message, millis)
    }
}
