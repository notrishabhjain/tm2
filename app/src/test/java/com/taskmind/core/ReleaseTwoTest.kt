package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Chat bursts and meeting matching (v1.27). */
class ReleaseTwoTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    @Test fun `a burst reads as one message, oldest first, with times and senders`() {
        val text = Burst.combine(
            listOf(
                Burst.Line(ist("2026-11-02T10:01:20"), "Priya", "can you sign it\ntoday?"),
                Burst.Line(ist("2026-11-02T10:01:05"), "Priya", "Rishabh, the PO"),
            ),
        )
        assertEquals("[10:01] Priya: Rishabh, the PO\n[10:01] Priya: can you sign it today?", text)
    }

    @Test fun `a single message passes through untouched`() {
        assertEquals("ok\nthanks", Burst.combine(listOf(Burst.Line(1L, "A", "ok\nthanks"))))
    }

    @Test fun `the burst waits a quiet minute after its newest message`() {
        val lines = listOf(Burst.Line(1_000L, "A", "x"), Burst.Line(5_000L, "A", "y"))
        assertEquals(5_000L + Burst.QUIET_MILLIS, Burst.readyAt(lines))
    }

    private val open = listOf(
        MeetingMatch.Task("1", "Send the signed PO", "Priya Shah", "Vendor PO", false),
        MeetingMatch.Task("2", "Rack quote", "Rahul Mehta", null, true),
        MeetingMatch.Task("3", "Book cab", null, null, false),
        MeetingMatch.Task("4", "Interview loop", "Ram", "SRE hiring", false),
    )

    @Test fun `a meeting matches people in it and matters it names`() {
        val r = MeetingMatch.related(MeetingMatch.Meeting("Vendor PO sync", listOf("rahul.mehta@acme.com Rahul Mehta")), open)
        assertEquals(listOf("1", "2"), r.map { it.id })
        assertEquals(listOf("You owe: Send the signed PO", "Waiting on: Rack quote"), MeetingMatch.lines(r))
    }

    @Test fun `names match whole words only`() {
        assertTrue(MeetingMatch.related(MeetingMatch.Meeting("Programme review", emptyList()), open).isEmpty())
    }
}
