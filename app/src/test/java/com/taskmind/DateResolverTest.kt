package com.taskmind

import com.taskmind.core.DateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

/** Spec 14.1. Every rule here exists because a wrong date is a wrong task. */
class DateResolverTest {

    /** Mon 7 July 2025, 14:00 IST. */
    private val occurredAt = ZonedDateTime.parse("2025-07-07T14:00:00+05:30").toInstant().toEpochMilli()

    @Test
    fun `offset date-time parses`() {
        val r = DateResolver.resolve("2025-07-08T18:00:00+05:30", occurredAt)
        assertEquals(
            ZonedDateTime.parse("2025-07-08T18:00:00+05:30").toInstant().toEpochMilli(),
            r.dueAt,
        )
        assertNull(r.noteSuffix)
    }

    @Test
    fun `bare date defaults to 18-00 IST`() {
        val r = DateResolver.resolve("2025-07-08", occurredAt)
        val expected = ZonedDateTime.parse("2025-07-08T18:00:00+05:30").toInstant().toEpochMilli()
        assertEquals(expected, r.dueAt)
    }

    @Test
    fun `local date-time is read as IST not device default`() {
        val r = DateResolver.resolve("2025-07-08T09:30:00", occurredAt)
        val expected = ZonedDateTime.parse("2025-07-08T09:30:00+05:30").toInstant().toEpochMilli()
        assertEquals(expected, r.dueAt)
    }

    @Test
    fun `unparseable date becomes null and is not guessed`() {
        val r = DateResolver.resolve("next Tuesdayish", occurredAt)
        assertNull(r.dueAt)
        assertNull(r.noteSuffix)
        assertTrue(r.reason.contains("unparseable"))
    }

    @Test
    fun `null and the literal string null are both no date`() {
        assertNull(DateResolver.resolve(null, occurredAt).dueAt)
        assertNull(DateResolver.resolve("null", occurredAt).dueAt)
        assertNull(DateResolver.resolve("  ", occurredAt).dueAt)
    }

    @Test
    fun `a date well before the source moment is dropped and kept in notes`() {
        // The classic model failure: resolving "kal" against today instead of
        // the day the message arrived, landing a year in the past.
        val r = DateResolver.resolve("2024-07-08T18:00:00+05:30", occurredAt)
        assertNull("must not silently shift the date forward", r.dueAt)
        assertNotNull(r.noteSuffix)
        assertTrue(r.noteSuffix!!.startsWith("stated deadline:"))
    }

    @Test
    fun `a date slightly before the source moment is still accepted`() {
        // "aaj 11 baje" for a message that arrived at 14:00 is within 12h and
        // is a legitimate same-day deadline that has already passed.
        val r = DateResolver.resolve("2025-07-07T11:00:00+05:30", occurredAt)
        assertNotNull(r.dueAt)
        assertNull(r.noteSuffix)
    }

    @Test
    fun `a date more than two years out is dropped`() {
        val r = DateResolver.resolve("2030-01-01T18:00:00+05:30", occurredAt)
        assertNull(r.dueAt)
        assertNotNull(r.noteSuffix)
    }

    @Test
    fun `prompt formatting uses IST regardless of the JVM default zone`() {
        val formatted = DateResolver.formatForPrompt(occurredAt)
        assertEquals("Monday 7 July 2025, 14:00", formatted)
    }

    @Test
    fun `day boundaries are IST day boundaries`() {
        val start = DateResolver.startOfDay(occurredAt)
        assertEquals(
            ZonedDateTime.parse("2025-07-07T00:00:00+05:30").toInstant().toEpochMilli(),
            start,
        )
        assertEquals(start + 24L * 3600 * 1000, DateResolver.nextMidnight(occurredAt))
        assertTrue(DateResolver.endOfDay(occurredAt) < DateResolver.nextMidnight(occurredAt))
    }

    @Test
    fun `day key is the IST calendar day`() {
        // 19:00 UTC on 7 July is already 8 July in IST.
        val utcEvening = Instant.parse("2025-07-07T19:00:00Z").toEpochMilli()
        assertEquals("2025-07-08", DateResolver.dayKey(utcEvening))
    }
}
