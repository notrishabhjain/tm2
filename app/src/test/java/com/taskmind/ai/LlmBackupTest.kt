package com.taskmind.ai

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmBackupTest {

    /** Answers by host: a status code per host, or 200 with a JSON reply. */
    private class Fake(val codes: MutableMap<String, Int>) : Interceptor {
        val hosts = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            hosts += host
            val code = codes[host] ?: 200
            val body = if (code == 200) {
                """{"choices":[{"message":{"content":"{\"from\":\"$host\"}"}}],"usage":{"total_tokens":7}}"""
            } else {
                """{"error":{"message":"Rate limit reached, tokens per day (TPD)"}}"""
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .apply { if (code == 429) header("Retry-After", "600") }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val backup = LlmClient.Config("https://openrouter.ai/api/v1", "or-key", "openai/gpt-oss-120b:free")
    private val main = LlmClient.Config("https://api.groq.com/openai/v1", "groq-key", "openai/gpt-oss-120b", backup)

    private fun client(fake: Fake) = LlmClient(OkHttpClient.Builder().addInterceptor(fake).build())

    private fun LlmClient.ask(config: LlmClient.Config = main, allowBackup: Boolean = true) =
        runBlocking { complete(config, "s", "u", allowBackup = allowBackup) }

    @Test
    fun `a working main provider never touches the backup`() {
        val fake = Fake(mutableMapOf())
        val r = client(fake).ask()
        assertTrue(r is AiResult.Ok && !r.value.fromBackup)
        assertEquals(listOf("api.groq.com"), fake.hosts)
    }

    @Test
    fun `a rate limit goes to the backup, and the main one rests after it`() {
        val fake = Fake(mutableMapOf("api.groq.com" to 429))
        val c = client(fake)
        val first = c.ask()
        assertTrue(first is AiResult.Ok && first.value.fromBackup)
        assertTrue((first as AiResult.Ok).value.content.contains("openrouter.ai"))
        assertEquals(listOf("api.groq.com", "openrouter.ai"), fake.hosts)

        // Resting: the next call skips the main provider entirely.
        fake.hosts.clear()
        assertTrue(c.ask() is AiResult.Ok)
        assertEquals(listOf("openrouter.ai"), fake.hosts)
    }

    @Test
    fun `when both fail the main provider's error is returned`() {
        val fake = Fake(mutableMapOf("api.groq.com" to 401, "openrouter.ai" to 500))
        val r = client(fake).ask()
        assertTrue(r is AiResult.HttpError && r.code == 401)
    }

    @Test
    fun `a failing backup while the main one rests still reads as a rate limit`() {
        val fake = Fake(mutableMapOf("api.groq.com" to 429, "openrouter.ai" to 500))
        val c = client(fake)
        assertTrue((c.ask() as AiResult.HttpError).rateLimited)
        fake.hosts.clear()
        val r = c.ask()
        assertTrue(r is AiResult.HttpError && r.rateLimited)
        assertEquals(listOf("openrouter.ai"), fake.hosts)
    }

    @Test
    fun `testing a provider never falls back, and no backup key means no backup`() {
        val fake = Fake(mutableMapOf("api.groq.com" to 429))
        assertFalse(client(fake).ask(allowBackup = false) is AiResult.Ok)
        assertEquals(listOf("api.groq.com"), fake.hosts)

        val keyless = Fake(mutableMapOf("api.groq.com" to 503))
        client(keyless).ask(main.copy(backup = backup.copy(apiKey = "")))
        assertEquals(listOf("api.groq.com"), keyless.hosts)
    }

    @Test
    fun `only a backup key saved goes straight to the backup`() {
        val fake = Fake(mutableMapOf())
        val r = client(fake).ask(main.copy(apiKey = ""))
        assertTrue(r is AiResult.Ok && r.value.fromBackup)
        assertEquals(listOf("openrouter.ai"), fake.hosts)
    }
}
