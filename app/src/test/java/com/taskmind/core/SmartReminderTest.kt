package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * When a task reminds you without being asked.
 *
 * Every time here is IST, because that is what the user lives in and what
 * every date the app stores was resolved against.
 */
class SmartReminderTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val morning = ist("2026-10-23T08:00")

    @Test fun `a timed task is reminded half an hour before`() {
        assertEquals(ist("2026-10-23T15:30"), SmartReminder.forDue(ist("2026-10-23T16:00"), morning))
    }

    @Test fun `a timed task too close for the lead is reminded at the time`() {
        val now = ist("2026-10-23T15:45")
        assertEquals(ist("2026-10-23T16:00"), SmartReminder.forDue(ist("2026-10-23T16:00"), now))
    }

    @Test fun `a date-only task is reminded at nine that morning`() {
        val due = ist("2026-10-24T18:00")
        assertTrue(SmartReminder.isDateOnly(due))
        assertEquals(ist("2026-10-24T09:00"), SmartReminder.forDue(due, morning))
    }

    @Test fun `a date-only task for today, after nine, falls back to before the end of day`() {
        val now = ist("2026-10-23T11:00")
        assertEquals(ist("2026-10-23T17:30"), SmartReminder.forDue(ist("2026-10-23T18:00"), now))
    }

    @Test fun `a past deadline gets no reminder`() {
        assertNull(SmartReminder.forDue(ist("2026-10-22T16:00"), morning))
    }

    @Test fun `no due date means no reminder`() {
        assertNull(SmartReminder.forDue(null, morning))
    }

    @Test fun `six in the evening with minutes is a real time`() {
        assertFalse(SmartReminder.isDateOnly(ist("2026-10-23T18:30")))
    }

    @Test fun `each explicit choice resolves to its own instant`() {
        val due = ist("2026-10-24T16:00")
        assertEquals(due, SmartReminder.resolve(SmartReminder.Choice.AT_TIME, due, morning))
        assertEquals(ist("2026-10-24T15:00"), SmartReminder.resolve(SmartReminder.Choice.HOUR_BEFORE, due, morning))
        assertEquals(ist("2026-10-24T09:00"), SmartReminder.resolve(SmartReminder.Choice.MORNING, due, morning))
        assertNull(SmartReminder.resolve(SmartReminder.Choice.NONE, due, morning))
    }

    @Test fun `a choice that lands in the past resolves to nothing`() {
        val due = ist("2026-10-23T08:30")
        assertNull(SmartReminder.resolve(SmartReminder.Choice.HOUR_BEFORE, due, morning))
    }

    @Test fun `an existing reminder is described by the choice that made it`() {
        val due = ist("2026-10-24T16:00")
        assertEquals(SmartReminder.Choice.HOUR_BEFORE, SmartReminder.describe(ist("2026-10-24T15:00"), due, morning))
        assertEquals(SmartReminder.Choice.AT_TIME, SmartReminder.describe(due, due, morning))
        assertEquals(SmartReminder.Choice.NONE, SmartReminder.describe(null, due, morning))
    }

    @Test fun `setting and clearing a time keeps the day`() {
        val due = ist("2026-10-24T18:00")
        val timed = SmartReminder.withTime(due, 10, 15)
        assertEquals(ist("2026-10-24T10:15"), timed)
        assertEquals(due, SmartReminder.withoutTime(timed))
    }
}
