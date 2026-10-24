package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking the task list a question. */
class AskPromptTest {

    private fun task(id: String, open: Boolean = true, created: Long = 0, due: Long? = null, evidence: String? = null) =
        AskPrompt.Task(id, "Task $id", open, due, from = "Rahul", evidence = evidence, createdAt = created)

    @Test fun `open tasks go first, then the newest finished ones`() {
        val picked = AskPrompt.select(
            listOf(task("old-done", open = false, created = 1), task("open"), task("new-done", open = false, created = 9)),
        )
        assertEquals(listOf("open", "new-done", "old-done"), picked.map { it.id })
    }

    @Test fun `the prompt numbers tasks and never carries their ids`() {
        val t = task("3f2a9c1e-uuid", evidence = "file bhej do").copy(title = "Send the file")
        val prompt = AskPrompt.userPrompt("What do I owe Rahul?", listOf(t), 0)
        assertTrue(prompt.contains("[1] OPEN | Send the file"))
        assertTrue(prompt.contains("said: \"file bhej do\""))
        assertTrue(prompt.endsWith("QUESTION: What do I owe Rahul?"))
        assertFalse(prompt.contains("3f2a9c1e"))
    }

    @Test fun `the answer points back at tasks by number`() {
        val sent = listOf(task("a"), task("b"), task("c"))
        val answer = AskPrompt.parse("""{"answer":"Two things for Rahul.","tasks":[3,1,7]}""", sent)!!
        assertEquals("Two things for Rahul.", answer.text)
        // 7 is out of range and dropped rather than crashing.
        assertEquals(listOf("c", "a"), answer.taskIds)
    }

    @Test fun `an empty or broken reply is no answer`() {
        assertNull(AskPrompt.parse("""{"answer":""}""", emptyList()))
        assertNull(AskPrompt.parse("not json", emptyList()))
    }
}
