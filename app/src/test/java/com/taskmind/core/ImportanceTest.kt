package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** One order for everything, and the reasons behind it. */
class ImportanceTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val now = ist("2026-10-26T10:00")

    private fun task(
        id: String,
        priority: Priority = Priority.MEDIUM,
        dueAt: Long? = null,
        createdAt: Long = now,
        notes: String? = null,
        person: String? = null,
        waitingOn: Boolean = false,
        tags: List<String> = emptyList(),
    ) = Importance.Input(id, "Task $id", priority, dueAt, createdAt, createdAt, tags, notes, person, waitingOn)

    @Test fun `overdue and urgent outranks medium with no date`() {
        val late = Importance.score(task("a", Priority.URGENT, ist("2026-10-25T18:00")), now)
        val idle = Importance.score(task("b"), now)
        assertTrue(late.points > idle.points)
        assertEquals("Marked urgent", late.why)
        assertTrue("Overdue" in late.reasons)
    }

    @Test fun `a date-only due today is due today, not overdue, all day`() {
        val s = Importance.score(task("a", dueAt = ist("2026-10-26T18:00")), ist("2026-10-26T19:30"))
        assertEquals("Due today", s.why)
    }

    @Test fun `follow-ups count as nudges, maybe-done lines do not`() {
        val notes = "PO 4471\n[Sun 25 Oct] Priya: \"any update?\"\n[Mon 26 Oct] Priya: \"need it today\"\n" +
            "[Mon 26 Oct] Priya: \"got it\" - maybe done?"
        assertEquals(2, Importance.nudges(notes))
        val s = Importance.score(task("a", notes = notes, person = "Priya Shah"), now)
        assertEquals("Priya followed up 2 times", s.why)
    }

    @Test fun `key people get a boost and say so`() {
        val keys = Importance.keyPeople("I head infra. Priya (finance) approves POs. Ignore Ops Alerts.", listOf("Priya Shah", "Ram", "Amit"))
        assertEquals(setOf("Priya Shah"), keys)
        val s = Importance.score(task("a", person = "Priya Shah"), now, keys)
        assertTrue("Priya Shah is a key person" in s.reasons)
    }

    @Test fun `key people match whole words only`() {
        assertTrue(Importance.keyPeople("the programme review", listOf("Ram")).isEmpty())
    }

    @Test fun `stale undated tasks sink`() {
        val fresh = Importance.score(task("a"), now)
        val stale = Importance.score(task("b", createdAt = now - 40L * 24 * 60 * 60 * 1000), now)
        assertTrue(stale.points < fresh.points)
    }

    @Test fun `focus is short, mine only, and best first`() {
        val tasks = listOf(
            task("low", Priority.LOW),
            task("urgent", Priority.URGENT, ist("2026-10-26T18:00")),
            task("high", Priority.HIGH, ist("2026-10-27T18:00")),
            task("theirs", Priority.URGENT, ist("2026-10-26T18:00"), waitingOn = true),
            task("done?", Priority.URGENT, ist("2026-10-26T18:00"), tags = listOf(FollowUp.MAYBE_DONE_TAG)),
        ) + (1..10).map { task("h$it", Priority.HIGH, ist("2026-10-26T18:00")) }
        val focus = Importance.focus(tasks, now)
        assertEquals(Importance.FOCUS_SIZE, focus.size)
        assertEquals("urgent", focus.first().first.id)
        val ids = focus.map { it.first.id }
        assertFalse("theirs" in ids)
        assertFalse("done?" in ids)
        assertFalse("low" in ids)
    }

    @Test fun `nothing pressing means an empty focus, not filler`() {
        assertTrue(Importance.focus(listOf(task("a", Priority.LOW), task("b")), now).isEmpty())
    }
}
