package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** Where a matter stands, and who owes what. */
class StandingTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val now = ist("2026-10-26T10:00") // a Monday

    @Test fun `a matter says what is next and who you are waiting on`() {
        val line = Standing.matter(
            listOf(
                Standing.Task("Send the signed PO", ist("2026-10-30T18:00"), false, "Priya", now),
                Standing.Task("Share rack quote", null, false, "Priya", now),
                Standing.Task("Rahul to confirm delivery", null, true, "Rahul Mehta", ist("2026-10-23T12:00")),
            ),
            now,
        )
        assertEquals("You owe 2 - next: Send the signed PO, due Fri. Waiting on Rahul since Fri", line)
    }

    @Test fun `one task, due today`() {
        assertEquals(
            "Next: Pay rent, due today",
            Standing.matter(listOf(Standing.Task("Pay rent", ist("2026-10-26T18:00"), false, null, now)), now),
        )
    }

    @Test fun `nothing open says nothing`() {
        assertNull(Standing.matter(emptyList(), now))
    }

    @Test fun `people are grouped case-insensitively, busiest first`() {
        val people = Standing.people(
            listOf(
                Standing.Task("Send PO", ist("2026-10-27T18:00"), false, "Priya", now),
                Standing.Task("Pay invoice", ist("2026-10-26T18:00"), false, "priya", now),
                Standing.Task("Confirm FARPS", null, true, "Priya", now),
                Standing.Task("Share deck", null, false, "Amit", now),
                Standing.Task("Typed by hand", null, false, null, now),
            ),
        )
        assertEquals(2, people.size)
        assertEquals(Standing.Person("Priya", youOwe = 2, theyOwe = 1, next = "Pay invoice"), people[0])
        assertEquals("Amit", people[1].name)
    }
}
