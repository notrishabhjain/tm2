package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tidy-up: what counts as stale, and how filing suggestions are read back. */
class CleanupTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun task(
        id: String,
        daysIdle: Int,
        priority: Priority = Priority.MEDIUM,
        dueAt: Long? = null,
    ) = Importance.Input(id, id, priority, dueAt, now - daysIdle * day, now - daysIdle * day, emptyList(), null, null, false)

    @Test fun `stale means untouched for weeks with nothing pressing`() {
        val stale = Cleanup.stale(
            listOf(
                task("old", 30),
                task("older", 40, Priority.LOW),
                task("fresh", 5),
                task("urgent", 30, Priority.URGENT),
                task("due-soon", 30, dueAt = now + 2 * day),
                task("long-overdue", 30, dueAt = now - 20 * day),
            ),
            now,
        )
        assertEquals(listOf("older", "old", "long-overdue"), stale.map { it.id })
    }

    private val tasks = listOf(
        Cleanup.Task("a", "Send signed PO", "Priya"),
        Cleanup.Task("b", "Share rack quote", "Priya"),
        Cleanup.Task("c", "Book movers", null),
        Cleanup.Task("d", "Get office keys", null),
        Cleanup.Task("e", "Buy milk", null),
    )
    private val matters = listOf(ChatContext.Matter("m1", "Vendor PO"))

    @Test fun `the prompt numbers tasks and matters`() {
        val p = Cleanup.userPrompt(tasks, matters)
        assertTrue(p.contains("M1. Vendor PO"))
        assertTrue(p.contains("T1. Send signed PO (from Priya)"))
        assertTrue(p.contains("T5. Buy milk\n"))
    }

    @Test fun `suggestions keep existing matters, shared new ones, and drop matters of one`() {
        val reply = """{"assign":[
            {"task":"T1","matter":"M1"},{"task":"T2","matter":"M1"},
            {"task":"T3","matter":"Office move"},{"task":"T4","matter":"office move"},
            {"task":"T5","matter":"Groceries"},{"task":"T9","matter":"M1"}]}"""
        val out = Cleanup.parse(reply, tasks, matters)!!
        assertEquals(
            listOf(
                Cleanup.Assignment("a", "Send signed PO", "Vendor PO", "m1"),
                Cleanup.Assignment("b", "Share rack quote", "Vendor PO", "m1"),
                Cleanup.Assignment("c", "Book movers", "Office move", null),
                Cleanup.Assignment("d", "Get office keys", "Office move", null),
            ),
            out,
        )
    }

    @Test fun `an unreadable answer is null, not an empty list`() {
        assertNull(Cleanup.parse("sorry", tasks, matters))
    }
}
