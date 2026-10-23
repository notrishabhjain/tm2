package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Dates typed into a new task, the way people actually type them. */
class QuickAddTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    // A Friday morning.
    private val now = ist("2026-10-23T10:00")

    private fun parse(text: String) = QuickAdd.parse(text, now)

    @Test fun `tomorrow with a time`() {
        val r = parse("Call Rahul tomorrow 5pm")
        assertEquals("Call Rahul", r.title)
        assertEquals(ist("2026-10-24T17:00"), r.dueAt)
    }

    @Test fun `a weekday with no time is due that day with no time`() {
        val r = parse("Send invoice by monday")
        assertEquals("Send invoice", r.title)
        assertTrue(SmartReminder.isDateOnly(r.dueAt!!))
        assertEquals(ist("2026-10-26T18:00"), r.dueAt)
    }

    @Test fun `today's weekday means today, next means a week on`() {
        assertEquals(ist("2026-10-23T18:00"), parse("Pay rent friday").dueAt)
        assertEquals(ist("2026-10-30T18:00"), parse("Pay rent next friday").dueAt)
    }

    @Test fun `hinglish day words`() {
        assertEquals(ist("2026-10-24T18:00"), parse("Sharma ji ko paise bhejna kal").dueAt)
        assertEquals(ist("2026-10-25T18:00"), parse("Report parso").dueAt)
        assertEquals("Report", parse("Report parso").title)
    }

    @Test fun `a bare hour after at is read in working hours`() {
        val r = parse("Standup prep tomorrow at 9")
        assertEquals(ist("2026-10-24T09:00"), r.dueAt)
        assertEquals("Standup prep", r.title)
        assertEquals(ist("2026-10-24T16:00"), parse("Review deck tomorrow at 4").dueAt)
    }

    @Test fun `minutes and twenty-four hour times`() {
        assertEquals(ist("2026-10-24T17:30"), parse("Ping vendor tomorrow 5:30pm").dueAt)
        assertEquals(ist("2026-10-24T14:15"), parse("Ping vendor tomorrow 14:15").dueAt)
    }

    @Test fun `a time alone is today if still ahead, otherwise tomorrow`() {
        assertEquals(ist("2026-10-23T16:00"), parse("Call bank at 4pm").dueAt)
        assertEquals(ist("2026-10-24T08:00"), parse("Gym 8am").dueAt)
    }

    @Test fun `six in the evening is a real time, not "any time"`() {
        val due = parse("Dinner reservation tomorrow 6pm").dueAt!!
        assertTrue(!SmartReminder.isDateOnly(due))
    }

    @Test fun `in two hours`() {
        val r = parse("Call back the client in 2 hours")
        assertEquals("Call back the client", r.title)
        assertEquals(now + 2 * 3_600_000L, r.dueAt)
    }

    @Test fun `parts of the day`() {
        assertEquals(ist("2026-10-24T09:00"), parse("Gym tomorrow morning").dueAt)
        assertEquals(ist("2026-10-23T21:00"), parse("Pack bags tonight").dueAt)
    }

    @Test fun `no date leaves the title alone`() {
        val r = parse("Buy milk")
        assertEquals("Buy milk", r.title)
        assertNull(r.dueAt)
    }

    @Test fun `words that only look like days are left alone`() {
        assertNull(parse("Sat with Rahul about the budget").dueAt)
    }

    @Test fun `numbers that are not times are left alone`() {
        val r = parse("Pay 25000 to Sharma ji")
        assertNull(r.dueAt)
        assertEquals("Pay 25000 to Sharma ji", r.title)
    }
}
