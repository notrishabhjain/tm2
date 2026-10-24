package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** What Sunday evening's review says. */
class WeeklyReviewTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    // A Sunday evening.
    private val now = ist("2026-10-25T18:00")

    private fun item(title: String, open: Boolean, created: String, completed: String? = null, from: String? = null) =
        WeeklyReview.Item(title, open, ist(created), completed?.let(::ist), from)

    @Test fun `counts done, new and open, and names the oldest and the busiest asker`() {
        val s = WeeklyReview.summarise(
            listOf(
                item("Send deck", open = false, created = "2026-10-20T10:00", completed = "2026-10-21T10:00"),
                item("Pay vendor", open = false, created = "2026-10-01T10:00", completed = "2026-10-24T10:00"),
                item("Old quote", open = true, created = "2026-10-05T10:00", from = "Sharma Ji"),
                item("Fix invoice", open = true, created = "2026-10-22T10:00", from = "Sharma Ji"),
                item("New thing", open = true, created = "2026-10-25T09:00", from = "Priya"),
                item("Ancient", open = false, created = "2026-09-01T10:00", completed = "2026-09-02T10:00"),
            ),
            now,
        )!!
        assertEquals("2 done, 3 new, 3 still open", s.headline)
        assertEquals(listOf("Old quote" to 20, "Fix invoice" to 3), s.oldest)
        assertEquals(listOf("Sharma Ji" to 2), s.topAskers)
        assertEquals("Most asks: Sharma Ji (2)", s.lines().last())
    }

    @Test fun `an empty week says nothing`() {
        assertNull(
            WeeklyReview.summarise(
                listOf(item("Ancient", open = false, created = "2026-09-01T10:00", completed = "2026-09-02T10:00")),
                now,
            ),
        )
    }

    @Test fun `the next review is the coming Sunday evening`() {
        assertEquals(ist("2026-11-01T18:00"), WeeklyReview.nextSunday(18, now))
        assertEquals(ist("2026-10-25T18:00"), WeeklyReview.nextSunday(18, ist("2026-10-23T10:00")))
    }
}
