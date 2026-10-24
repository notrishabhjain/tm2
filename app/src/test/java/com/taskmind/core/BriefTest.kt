package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** What the morning brief and the evening wrap-up say. */
class BriefTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val nine = ist("2026-10-23T09:00")
    private val seven = ist("2026-10-23T19:00")

    private val tasks = listOf(
        Brief.Item("Pay rent", ist("2026-10-21T18:00"), waitingOn = false),
        Brief.Item("Call bank", ist("2026-10-23T16:00"), waitingOn = false),
        Brief.Item("Send deck", ist("2026-10-23T18:00"), waitingOn = false),
        Brief.Item("Check Abhishek confirmed", ist("2026-10-23T18:00"), waitingOn = true),
        Brief.Item("Plan offsite", ist("2026-10-30T18:00"), waitingOn = false),
        Brief.Item("Read book", null, waitingOn = false),
    )

    @Test fun `the morning counts today, the backlog and who you are waiting on`() {
        val m = Brief.compose(Brief.Kind.MORNING, tasks, nine)!!
        assertEquals("3 due today, 1 overdue, waiting on 1", m.title)
        assertEquals("Late: Pay rent", m.lines.first())
        assertTrue(m.lines.contains("16:00  Call bank"))
        // A task with only a day shows no made-up time.
        assertTrue(m.lines.contains("Send deck"))
    }

    @Test fun `the evening lists what slipped, leaving out other people's moves`() {
        val e = Brief.compose(Brief.Kind.EVENING, tasks, seven)!!
        assertEquals(3, e.slipped)
        assertEquals(listOf("Pay rent", "Call bank", "Send deck"), e.lines)
    }

    @Test fun `meetings lead the morning and count on their own`() {
        val m = Brief.compose(
            Brief.Kind.MORNING,
            emptyList(),
            nine,
            listOf(Brief.Meeting(ist("2026-10-23T11:00"), "Vendor sync"), Brief.Meeting(null, "Diwali leave")),
        )!!
        assertEquals("2 meetings", m.title)
        assertEquals(listOf("All day  Meeting: Diwali leave", "11:00  Meeting: Vendor sync"), m.lines)
    }

    @Test fun `nothing to say means no message`() {
        val clear = listOf(Brief.Item("Plan offsite", ist("2026-10-30T18:00"), waitingOn = false))
        assertNull(Brief.compose(Brief.Kind.MORNING, clear, nine))
        assertNull(Brief.compose(Brief.Kind.EVENING, clear, seven))
    }

    @Test fun `the next brief is later today, or tomorrow once the hour has passed`() {
        assertEquals(ist("2026-10-23T19:00"), Brief.nextAt(19, nine))
        assertEquals(ist("2026-10-24T09:00"), Brief.nextAt(9, nine))
    }
}
