package com.taskmind

import com.taskmind.core.LlmJson
import com.taskmind.core.LlmJson.bool
import com.taskmind.core.LlmJson.dbl
import com.taskmind.core.LlmJson.str
import com.taskmind.core.LlmJson.strList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 8.1 - what we accept from a provider, and what we refuse to rescue. */
class LlmJsonTest {

    @Test
    fun `plain json parses`() {
        val obj = LlmJson.parseObject("""{"isTask":true,"confidence":0.9,"title":"Pay Sharma Ji"}""")!!
        assertEquals(true, obj.bool("isTask"))
        assertEquals(0.9, obj.dbl("confidence")!!, 0.0001)
        assertEquals("Pay Sharma Ji", obj.str("title"))
    }

    @Test
    fun `markdown fences are stripped`() {
        val obj = LlmJson.parseObject("```json\n{\"isTask\":false}\n```")!!
        assertEquals(false, obj.bool("isTask"))
    }

    @Test
    fun `a json object embedded in prose is extracted`() {
        val raw = "Sure! Here is the result:\n{\"isTask\":true,\"title\":\"Send invoice\"}\nHope that helps."
        val obj = LlmJson.parseObject(raw)!!
        assertEquals("Send invoice", obj.str("title"))
    }

    @Test
    fun `braces inside strings do not end the object`() {
        val raw = """prefix {"title":"use the {placeholder} form","isTask":true} suffix"""
        val extracted = LlmJson.extractFirstJsonObject(raw)!!
        assertTrue(extracted.endsWith("}"))
        val obj = LlmJson.parseObject(raw)!!
        assertEquals("use the {placeholder} form", obj.str("title"))
    }

    @Test
    fun `scalars arriving as strings are coerced`() {
        val obj = LlmJson.parseObject("""{"isTask":"true","confidence":"0.82"}""")!!
        assertEquals(true, obj.bool("isTask"))
        assertEquals(0.82, obj.dbl("confidence")!!, 0.0001)
    }

    @Test
    fun `nulls and the literal string null both read as absent`() {
        val obj = LlmJson.parseObject("""{"title":null,"evidence":"null","notes":""}""")!!
        assertNull(obj.str("title"))
        assertNull(obj.str("evidence"))
        assertNull(obj.str("notes"))
    }

    @Test
    fun `unreadable output yields null rather than a half-parsed task`() {
        assertNull(LlmJson.parseObject("I could not determine whether this is a task."))
    }

    @Test
    fun `string arrays are read`() {
        val obj = LlmJson.parseObject("""{"topics":["payment","invoice",""]}""")!!
        assertEquals(listOf("payment", "invoice"), obj.strList("topics"))
    }

    @Test
    fun `chat content is pulled from an openai shaped reply`() {
        val body = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"{\"isTask\":true}"}}],
             "usage":{"total_tokens":412}}
        """.trimIndent()
        assertEquals("""{"isTask":true}""", LlmJson.chatContent(body))
        assertEquals(412, LlmJson.usageTokens(body))
    }

    @Test
    fun `content delivered as parts is joined`() {
        val body = """{"choices":[{"message":{"content":[{"text":"{\"isTask\""},{"text":":false}"}]}}]}"""
        assertEquals("""{"isTask":false}""", LlmJson.chatContent(body))
    }

    @Test
    fun `transcript text is read from both openai and sarvam shapes`() {
        assertEquals("haan ji", LlmJson.transcriptText("""{"text":"haan ji"}"""))
        assertEquals("haan ji", LlmJson.transcriptText("""{"transcript":"haan ji"}"""))
        assertEquals("haan ji theek hai", LlmJson.transcriptText("""{"transcripts":["haan ji","theek hai"]}"""))
    }

    @Test
    fun `provider errors are surfaced for the activity log`() {
        assertEquals(
            "Incorrect API key provided",
            LlmJson.errorMessage("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""),
        )
        assertEquals("rate limited", LlmJson.errorMessage("""{"error":"rate limited"}"""))
    }
}
