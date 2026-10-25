package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** What a follow-up message does to the task it is about. */
class FollowUpTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val now = ist("2026-10-25T10:00")

    private val task = FollowUp.Current(
        notes = "PO number 4471",
        dueAt = ist("2026-10-27T18:00"),
        priority = Priority.MEDIUM,
        tags = listOf("payment", FollowUp.MAYBE_DONE_TAG),
        reminderAt = ist("2026-10-27T09:00"),
    )

    @Test fun `a nudge adds a dated quote, keeps the date, and clears maybe-done`() {
        val p = FollowUp.update(task, null, Priority.LOW, "Priya", "any update on the PO?", now, now)
        assertEquals(task.dueAt, p.dueAt)
        assertEquals(task.reminderAt, p.reminderAt)
        assertEquals(Priority.MEDIUM, p.priority)
        assertEquals("PO number 4471\n[Sun 25 Oct] Priya: \"any update on the PO?\"", p.notes)
        assertEquals(listOf("payment"), p.tags)
    }

    @Test fun `a new deadline moves the date and the reminder, and urgency only rises`() {
        val p = FollowUp.update(task, ist("2026-10-26T18:00"), Priority.URGENT, "Priya", "need it by tomorrow", now, now)
        assertEquals(ist("2026-10-26T18:00"), p.dueAt)
        assertEquals(ist("2026-10-26T09:00"), p.reminderAt)
        assertEquals(Priority.URGENT, p.priority)
        assertTrue(p.changes.containsAll(listOf("due date", "priority", "note")))
    }

    @Test fun `the same message twice is noted once`() {
        val once = FollowUp.update(task, null, Priority.LOW, "Priya", "any update?", now, now)
        val twice = FollowUp.update(task.copy(notes = once.notes), null, Priority.LOW, "Priya", "any update?", now, now)
        assertEquals(once.notes, twice.notes)
        assertFalse("note" in twice.changes)
    }

    @Test fun `maybe done tags it and says why`() {
        val p = FollowUp.maybeDone(task.copy(tags = emptyList()), "Priya", "received the PO, thanks", now)
        assertEquals(listOf(FollowUp.MAYBE_DONE_TAG), p.tags)
        assertTrue(p.notes!!.endsWith("[Sun 25 Oct] Priya: \"received the PO, thanks\" - maybe done?"))
    }

    @Test fun `long histories drop the oldest follow-up, not the original notes`() {
        var notes: String? = "Original context"
        repeat(40) { i ->
            notes = FollowUp.appendLine(notes, "[Sun 25 Oct] Priya: \"nudge number $i " + "x".repeat(60) + "\"")
        }
        assertTrue(notes!!.length <= FollowUp.MAX_NOTES)
        assertTrue(notes!!.startsWith("Original context"))
        assertTrue(notes!!.contains("nudge number 39"))
        assertFalse(notes!!.contains("nudge number 0 "))
    }
}
