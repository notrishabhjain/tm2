package com.taskmind

import com.taskmind.core.PromptKind
import com.taskmind.core.PromptSet
import com.taskmind.core.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompts are editable, so the parts the rest of the app depends on need to
 * be pinned. If someone rewrites the extraction prompt and drops the JSON shape
 * or the grounding rule, the failure shows up as "no tasks appear" - these tests
 * at least keep the SHIPPED defaults honest.
 */
class PromptSetTest {

    @Test
    fun `every kind maps to its default text`() {
        assertEquals(Prompts.DEFAULT_NOTIFICATION_SYSTEM, PromptKind.NOTIFICATION.defaultText())
        assertEquals(Prompts.DEFAULT_CALL_SYSTEM, PromptKind.CALL.defaultText())
        assertEquals(Prompts.DEFAULT_VERIFY_SYSTEM, PromptKind.VERIFY.defaultText())
    }

    @Test
    fun `the default set is the default text`() {
        PromptKind.entries.forEach { kind ->
            assertEquals(kind.defaultText(), kind.textIn(PromptSet.DEFAULT))
        }
    }

    @Test
    fun `extraction prompts demand the evidence field the matcher checks`() {
        // Spec 13 drops any task whose evidence cannot be found in the source.
        // If the prompt stops asking for it, every task gets dropped.
        listOf(Prompts.DEFAULT_NOTIFICATION_SYSTEM, Prompts.DEFAULT_CALL_SYSTEM).forEach { prompt ->
            assertTrue("prompt must ask for evidence", prompt.contains("\"evidence\""))
            assertTrue("prompt must demand a verbatim quote", prompt.contains("GROUNDING"))
        }
    }

    @Test
    fun `extraction prompts ask for the fields the parser reads`() {
        val notification = Prompts.DEFAULT_NOTIFICATION_SYSTEM
        listOf("isTask", "title", "priority", "dueDate", "confidence").forEach {
            assertTrue("notification prompt must ask for $it", notification.contains(it))
        }
        val call = Prompts.DEFAULT_CALL_SYSTEM
        listOf("tasks", "title", "evidence", "assignedToMe", "confidence").forEach {
            assertTrue("call prompt must ask for $it", call.contains(it))
        }
    }

    @Test
    fun `the verify prompt asks for the verdict shape the parser reads`() {
        val verify = Prompts.DEFAULT_VERIFY_SYSTEM
        assertTrue(verify.contains("verdicts"))
        listOf("keep", "fix", "drop").forEach { assertTrue(verify.contains(it)) }
    }

    @Test
    fun `every prompt kind explains itself and its risk to the user`() {
        PromptKind.entries.forEach { kind ->
            assertTrue(kind.title.isNotBlank())
            assertTrue("${kind.name} needs a purpose", kind.purpose.length > 40)
            assertTrue("${kind.name} needs a caution", kind.caution.length > 40)
        }
    }
}
