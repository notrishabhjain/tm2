package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Areas, the Today plan, brain dumps and routines (v1.26). */
class ReleaseOneTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val now = ist("2026-10-31T09:00")
    private val areas = Areas.DEFAULT

    // -- areas -------------------------------------------------------------

    @Test fun `areas parse, de-duplicate and match the user's spelling`() {
        assertEquals(listOf("Work", "Home", "money"), Areas.parse("Work, Home ,, money\nwork"))
        assertEquals("Money", Areas.normalize(" money ", areas))
        assertNull(Areas.normalize("Errands", areas))
        assertNull(Areas.normalize(null, areas))
        assertEquals("The user's areas: Work, Home, Money, Health, Family\n", Areas.promptLine(areas))
    }

    // -- today ---------------------------------------------------------------

    private fun task(id: String, plannedFor: String? = null, inbox: Boolean = false, priority: Priority = Priority.MEDIUM, dueAt: Long? = null) =
        TodayPlan.Task(id, plannedFor, inbox, Importance.Input(id, id, priority, dueAt, now, now, emptyList(), null, null, false))

    @Test fun `today holds what was chosen, carries yesterday, and suggests the rest`() {
        val plan = TodayPlan.plan(
            listOf(
                task("chosen", plannedFor = "2026-10-31"),
                task("yesterday", plannedFor = "2026-10-30"),
                task("urgent", priority = Priority.URGENT, dueAt = ist("2026-10-31T18:00")),
                task("late", dueAt = ist("2026-10-29T18:00")),
                task("in-inbox", inbox = true, priority = Priority.URGENT, dueAt = ist("2026-10-31T18:00")),
                task("someday", priority = Priority.LOW),
            ),
            "2026-10-31",
            now,
        )
        assertEquals(listOf("chosen"), plan.today.map { it.id })
        assertEquals(listOf("yesterday"), plan.carried.map { it.id })
        val suggested = plan.suggested.map { it.first.id }
        assertTrue("urgent" in suggested && "late" in suggested)
        assertFalse("in-inbox" in suggested)
        assertFalse("someday" in suggested)
    }

    // -- brain dump ------------------------------------------------------------

    @Test fun `the model's split is read back with dates and areas`() {
        val reply = """{"items":[
            {"title":"Pay electricity bill","dueDate":"2026-11-06T18:00:00+05:30","area":"money","priority":"HIGH","today":false,"notes":null},
            {"title":"Call Rahul about the rack quote","dueDate":null,"area":"Errands","priority":"MEDIUM","today":true,"notes":"ask about delivery"},
            {"title":"pay electricity bill","dueDate":null,"area":null,"priority":"LOW","today":false,"notes":null}]}"""
        val items = BrainDump.parse(reply, now, areas)!!
        assertEquals(2, items.size)
        assertEquals(ist("2026-11-06T18:00"), items[0].dueAt)
        assertEquals("Money", items[0].area)
        assertNull(items[1].area)
        assertTrue(items[1].today)
        assertNull(BrainDump.parse("not json", now, areas))
    }

    @Test fun `without a model a pasted list still becomes tasks`() {
        val items = BrainDump.fallback("- buy milk\n2. Call Rahul tomorrow\n• renew passport, book cab, send invoice\nok", now)
        assertEquals(listOf("Buy milk", "Call Rahul", "Renew passport", "Book cab", "Send invoice"), items.map { it.title })
        assertEquals(ist("2026-11-01T18:00"), items[1].dueAt)
    }

    @Test fun `the prompt carries the moment and the areas`() {
        val p = BrainDump.userPrompt("pay rent", now, areas)
        assertTrue(p.contains("The user's areas: Work"))
        assertTrue(p.endsWith("pay rent"))
    }

    // -- routines ----------------------------------------------------------------

    @Test fun `yearly routines roll a year and remind ahead`() {
        assertTrue(Recurrence.isValid("YEARLY"))
        assertTrue(Recurrence.isValid("CUSTOM:2y"))
        assertEquals("Repeats yearly", Recurrence.describe("YEARLY"))
        assertEquals(ist("2027-03-15T18:00"), Recurrence.next("YEARLY", ist("2026-03-15T18:00")))
        assertEquals(ist("2027-02-13T09:00"), Routines.reminderFor(ist("2027-03-15T18:00"), 30))
        assertEquals(ist("2026-11-01T09:00"), Routines.reminderFor(ist("2026-11-01T18:00"), 0))
    }

    // -- brief -----------------------------------------------------------------

    @Test fun `the morning mentions the inbox, even on an otherwise empty day`() {
        val m = Brief.compose(Brief.Kind.MORNING, emptyList(), now, inbox = 4)!!
        assertEquals("4 new in Inbox", m.title)
        assertEquals("Inbox: 4 new - send each to Today or Later", m.lines.last())
        assertNull(Brief.compose(Brief.Kind.MORNING, emptyList(), now))
    }
}
