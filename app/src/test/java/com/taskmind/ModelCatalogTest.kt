package com.taskmind

import com.taskmind.core.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list in `enabledOnTheAffectedAccount` is what the device's provider
 * account actually had switched on. Not one of those four models can extract a
 * task, which is the fact the app previously had no way to notice.
 */
class ModelCatalogTest {

    private val enabledOnTheAffectedAccount = listOf(
        "groq/compound",
        "meta-llama/llama-prompt-guard-2-22m",
        "meta-llama/llama-prompt-guard-2-86m",
        "whisper-large-v3-turbo",
    )

    @Test
    fun `a prompt guard is a classifier, not a chat model`() {
        assertEquals(ModelCatalog.Kind.GUARD, ModelCatalog.classify("meta-llama/llama-prompt-guard-2-22m"))
        assertEquals(ModelCatalog.Kind.GUARD, ModelCatalog.classify("llama-guard-3-8b"))
        assertEquals(ModelCatalog.Kind.GUARD, ModelCatalog.classify("omni-moderation-latest"))
    }

    @Test
    fun `whisper is transcription`() {
        assertEquals(ModelCatalog.Kind.TRANSCRIPTION, ModelCatalog.classify("whisper-large-v3-turbo"))
        assertEquals(ModelCatalog.Kind.TRANSCRIPTION, ModelCatalog.classify("saarika:v2.5"))
    }

    @Test
    fun `compound is a router`() {
        assertEquals(ModelCatalog.Kind.ROUTER, ModelCatalog.classify("groq/compound"))
        assertEquals(ModelCatalog.Kind.ROUTER, ModelCatalog.classify("openrouter/auto"))
    }

    @Test
    fun `ordinary instruction models are chat models`() {
        for (id in listOf("llama-3.3-70b-versatile", "gpt-4o-mini", "qwen3-32b", "gemma2-9b-it")) {
            assertEquals(id, ModelCatalog.Kind.CHAT, ModelCatalog.classify(id))
        }
    }

    @Test
    fun `embeddings and image models are neither`() {
        assertEquals(ModelCatalog.Kind.OTHER, ModelCatalog.classify("text-embedding-3-small"))
        assertEquals(ModelCatalog.Kind.OTHER, ModelCatalog.classify("dall-e-3"))
    }

    @Test
    fun `none of the models enabled on the affected account can extract`() {
        val usable = enabledOnTheAffectedAccount
            .map { ModelCatalog.Model(it, ModelCatalog.classify(it)) }
            .filter { it.usableForExtraction }
        assertTrue(
            "the account had no model capable of extraction, which is the whole diagnosis",
            usable.isEmpty(),
        )
    }

    @Test
    fun `every unusable kind explains itself and chat does not`() {
        assertNull(ModelCatalog.whyUnusable(ModelCatalog.Kind.CHAT))
        for (kind in ModelCatalog.Kind.entries.filter { it != ModelCatalog.Kind.CHAT }) {
            assertNotNull("$kind must say why it cannot be used", ModelCatalog.whyUnusable(kind))
        }
    }

    @Test
    fun `usable models sort above unusable ones`() {
        val models = enabledOnTheAffectedAccount.plus("llama-3.3-70b-versatile")
            .map { ModelCatalog.Model(it, ModelCatalog.classify(it)) }
        val sorted = ModelCatalog.forExtraction(models)
        assertEquals("llama-3.3-70b-versatile", sorted.first().id)
        assertTrue(sorted.first().usableForExtraction)
        assertFalse(sorted.last().usableForExtraction)
    }

    @Test
    fun `transcription sorting surfaces the speech model`() {
        val models = enabledOnTheAffectedAccount.map { ModelCatalog.Model(it, ModelCatalog.classify(it)) }
        assertEquals("whisper-large-v3-turbo", ModelCatalog.forTranscription(models).first().id)
    }
}
