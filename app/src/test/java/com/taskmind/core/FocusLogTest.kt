package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** Were the morning's picks the things that got done? */
class FocusLogTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val mon = DateResolver.startOfDay(ist("2026-10-26T09:00"))
    private val tue = DateResolver.startOfDay(ist("2026-10-27T09:00"))

    @Test fun `entries survive a round trip`() {
        val day = FocusLog.Day(mon, listOf("a", "b"))
        assertEquals(day, FocusLog.decode(FocusLog.encode(day)))
        assertEquals(FocusLog.Day(mon, emptyList()), FocusLog.decode(FocusLog.encode(FocusLog.Day(mon, emptyList()))))
    }

    @Test fun `the same day is replaced, and old days roll off`() {
        var log = emptyList<FocusLog.Day>()
        repeat(20) { i -> log = FocusLog.record(log, FocusLog.Day(mon + i * 86_400_000L, listOf("x"))) }
        log = FocusLog.record(log, FocusLog.Day(log.last().startOfDay, listOf("y")))
        assertEquals(FocusLog.KEEP_DAYS, log.size)
        assertEquals(listOf("y"), log.last().ids)
    }

    @Test fun `only same-day completions count, and today waits`() {
        val days = listOf(FocusLog.Day(mon, listOf("a", "b", "c")), FocusLog.Day(tue, listOf("d", "e", "f")))
        val done = mapOf(
            "a" to ist("2026-10-26T15:00"),
            "b" to ist("2026-10-27T10:00"), // a day late
            "d" to ist("2026-10-27T11:00"),
            "e" to ist("2026-10-27T20:00"),
        )
        assertEquals(50, FocusLog.completion(days, done::get, ist("2026-10-28T09:00")))
        // On Tuesday evening only Monday is judged, and three picks are too few.
        assertNull(FocusLog.completion(days, done::get, ist("2026-10-27T21:00")))
    }
}
