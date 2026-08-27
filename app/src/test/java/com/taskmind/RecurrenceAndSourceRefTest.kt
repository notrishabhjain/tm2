package com.taskmind

import com.taskmind.core.DateResolver
import com.taskmind.core.Recurrence
import com.taskmind.core.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class RecurrenceAndSourceRefTest {

    private val anchor = ZonedDateTime.parse("2025-07-07T18:00:00+05:30").toInstant().toEpochMilli()

    @Test
    fun `standard rules advance correctly in IST`() {
        assertEquals(
            ZonedDateTime.parse("2025-07-08T18:00:00+05:30").toInstant().toEpochMilli(),
            Recurrence.next("DAILY", anchor),
        )
        assertEquals(
            ZonedDateTime.parse("2025-07-14T18:00:00+05:30").toInstant().toEpochMilli(),
            Recurrence.next("WEEKLY", anchor),
        )
        assertEquals(
            ZonedDateTime.parse("2025-08-07T18:00:00+05:30").toInstant().toEpochMilli(),
            Recurrence.next("MONTHLY", anchor),
        )
    }

    @Test
    fun `custom rules advance correctly`() {
        assertEquals(
            ZonedDateTime.parse("2025-07-10T18:00:00+05:30").toInstant().toEpochMilli(),
            Recurrence.next("CUSTOM:3d", anchor),
        )
        assertEquals(
            ZonedDateTime.parse("2025-07-21T18:00:00+05:30").toInstant().toEpochMilli(),
            Recurrence.next("CUSTOM:2w", anchor),
        )
        assertEquals(
            ZonedDateTime.parse("2025-10-07T18:00:00+05:30").toInstant().toEpochMilli(),
            Recurrence.next("CUSTOM:3m", anchor),
        )
    }

    @Test
    fun `a month rule at the end of the month clamps rather than overflowing`() {
        val jan31 = ZonedDateTime.parse("2025-01-31T18:00:00+05:30").toInstant().toEpochMilli()
        val next = Recurrence.next("MONTHLY", jan31)!!
        assertEquals("2025-02-28", DateResolver.dayKey(next))
    }

    @Test
    fun `invalid rules yield null`() {
        assertNull(Recurrence.next(null, anchor))
        assertNull(Recurrence.next("", anchor))
        assertNull(Recurrence.next("EVERY_OTHER_TUESDAY", anchor))
        assertNull(Recurrence.next("CUSTOM:xd", anchor))
        assertFalse(Recurrence.isValid("CUSTOM:3y"))
        assertTrue(Recurrence.isValid("CUSTOM:14d"))
    }

    @Test
    fun `rules describe themselves for the UI`() {
        assertEquals("Repeats daily", Recurrence.describe("DAILY"))
        assertEquals("Repeats every 3 days", Recurrence.describe("CUSTOM:3d"))
        assertEquals("Repeats every 1 week", Recurrence.describe("CUSTOM:1w"))
        assertNull(Recurrence.describe("nonsense"))
    }

    @Test
    fun `notification source refs are stable under cosmetic text differences`() {
        val a = SourceRef.forNotification("com.whatsapp", "Sharma Ji", "Beta, woh 25,000 ka payment KAL tak!")
        val b = SourceRef.forNotification("com.whatsapp", "sharma ji", "beta woh 25000 ka payment kal tak")
        assertEquals(a, b)
        assertTrue(a.startsWith("n:"))
        assertEquals(42, a.length)
    }

    @Test
    fun `notification source refs differ by sender package and content`() {
        val base = SourceRef.forNotification("com.whatsapp", "Sharma Ji", "kal tak payment kar dena")
        assertNotEquals(base, SourceRef.forNotification("org.telegram.messenger", "Sharma Ji", "kal tak payment kar dena"))
        assertNotEquals(base, SourceRef.forNotification("com.whatsapp", "Verma Ji", "kal tak payment kar dena"))
        assertNotEquals(base, SourceRef.forNotification("com.whatsapp", "Sharma Ji", "parso payment kar dena"))
    }

    @Test
    fun `call source refs prefer the call log id and fall back deterministically`() {
        assertEquals("c:4242", SourceRef.forCall(4242L, 1_700_000_000_000L, "+91 98123 45678"))
        assertEquals(
            "c:1700000000000:919812345678",
            SourceRef.forCall(null, 1_700_000_000_000L, "+91 98123 45678"),
        )
        assertEquals(
            "c:1700000000000:",
            SourceRef.forCall(null, 1_700_000_000_000L, null),
        )
    }

    @Test
    fun `clipboard source refs hash the whole transcript`() {
        val a = SourceRef.forClipboard("Speaker 1: haan ji\nSpeaker 2: kal tak bhej dena")
        val b = SourceRef.forClipboard("Speaker 1: haan ji\nSpeaker 2: kal tak bhej dena")
        assertEquals(a, b)
        assertTrue(a.startsWith("p:"))
        assertNotEquals(a, SourceRef.forClipboard("Speaker 1: haan ji"))
    }

    @Test
    fun `fingerprints match the notification identity`() {
        val a = SourceRef.fingerprint("com.whatsapp", "Sharma Ji", "kal tak payment kar dena")
        val b = SourceRef.fingerprint("com.whatsapp", "Sharma Ji", "kal tak   payment kar dena.")
        assertEquals(a, b)
        assertEquals(64, a.length)
    }
}
