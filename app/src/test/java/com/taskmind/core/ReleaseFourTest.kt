package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** The twin: planning, proposed changes, undo, nudges and recall (v1.28). */
class ReleaseFourTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val now = ist("2026-11-02T10:00")
    private val areas = Areas.DEFAULT

    private val sent = listOf(
        Twin.Task("a", "Send the signed PO to Priya", Twin.State.OPEN, dueAt = ist("2026-11-02T18:00"), from = "Priya (WhatsApp)"),
        Twin.Task("b", "Check Rahul sent the quote", Twin.State.OPEN, waiting = true, from = "Rahul (WhatsApp)", evidence = "main kal tak quote bhej dunga"),
        Twin.Task("c", "Renew car insurance", Twin.State.DONE, completedAt = ist("2026-11-01T12:00")),
        Twin.Task("d", "Review hiring plan", Twin.State.INBOX),
    )

    // -- reading the twin's answer ------------------------------------------

    @Test fun `actions are read back against the numbered list`() {
        val reply = Twin.parse(
            """{"answer":"Here is today.","tasks":[1,2,9],"actions":[
                {"do":"plan","task":1,"day":"today","at":"11:30"},
                {"do":"plan","task":1,"day":"tomorrow"},
                {"do":"plan","task":2,"day":"2026-11-02","at":"09:00"},
                {"do":"add","title":"Book the venue","due":"2026-11-05T18:00:00+05:30","area":"work","priority":"HIGH"},
                {"do":"edit","task":2,"due":"none"},
                {"do":"done","task":3},
                {"do":"nudge","task":2,"to":"Rahul"},
                {"do":"nudge","task":2,"to":"Rahul","text":"Hi Rahul, quote ka kya hua?"},
                {"do":"fly","task":1}
            ]}""",
            sent, now, areas,
        )
        assertNotNull(reply)
        reply!!
        assertEquals("Here is today.", reply.answer)
        assertEquals(listOf("a", "b"), reply.taskIds)
        val plans = reply.actions.filterIsInstance<Twin.Action.Plan>()
        // One plan per task: the second for task 1 is dropped.
        assertEquals(listOf("a", "b"), plans.map { it.taskId })
        assertEquals("2026-11-02", plans[0].day)
        assertEquals(ist("2026-11-02T11:30"), plans[0].at)
        // 09:00 has already gone: planned for today, no time.
        assertNull(plans[1].at)
        val add = reply.actions.filterIsInstance<Twin.Action.Add>().single()
        assertEquals("Work", add.area)
        assertEquals(Priority.HIGH, add.priority)
        assertEquals(ist("2026-11-05T18:00"), add.dueAt)
        assertTrue(reply.actions.filterIsInstance<Twin.Action.Edit>().single().clearDue)
        // A finished task cannot be finished again; a nudge needs its words.
        assertTrue(reply.actions.none { it is Twin.Action.Done })
        assertEquals("Hi Rahul, quote ka kya hua?", reply.actions.filterIsInstance<Twin.Action.Nudge>().single().text)
    }

    @Test fun `a plain answer with no actions is fine, an empty one is not`() {
        assertEquals(0, Twin.parse("""{"answer":"Two things for Priya.","tasks":[1]}""", sent, now, areas)!!.actions.size)
        assertNull(Twin.parse("""{"answer":"","actions":[]}""", sent, now, areas))
        assertNull(Twin.parse("not json", sent, now, areas))
    }

    @Test fun `days are read in plain words and kept within two months`() {
        assertEquals("2026-11-03", Twin.day("tomorrow", now))
        assertEquals("2026-11-06", Twin.day("Friday", now))
        assertEquals("2026-11-20", Twin.day("2026-11-20", now))
        assertNull(Twin.day("2026-10-01", now))
        assertNull(Twin.day("2027-06-01", now))
        assertNull(Twin.day("null", now))
    }

    @Test fun `the prompt carries the calendar, free time, states and the request`() {
        val prompt = Twin.userPrompt(
            Twin.PLAN_DAY,
            Twin.Context(
                now = now,
                tasks = sent,
                meetings = listOf(Twin.Meeting(ist("2026-11-02T11:00"), ist("2026-11-02T11:30"), "Vendor sync", listOf("Rahul"))),
                matters = listOf(Twin.MatterLine("Vendor PO", "You owe 1")),
                memory = listOf(Twin.Memory(ist("2026-10-30T14:00"), "Priya", "please share the PO copy")),
                areas = areas,
                userNames = setOf("Rishabh"),
            ),
        )
        assertTrue(prompt.contains("11:00-11:30 Vendor sync (Rahul)"))
        assertTrue(prompt.contains("FREE TODAY: 10:00-11:00, 11:30-19:00"))
        assertTrue(prompt.contains("[2] OPEN WAITING | Check Rahul sent the quote"))
        assertTrue(prompt.contains("[4] INBOX | Review hiring plan"))
        assertTrue(prompt.contains("- Vendor PO: You owe 1"))
        assertTrue(prompt.contains("please share the PO copy"))
        assertTrue(prompt.trimEnd().endsWith("REQUEST: Plan my day."))
    }

    @Test fun `what is sent puts the pressing first and leaves old history out`() {
        val tasks = listOf(
            Twin.Task("low", "low", Twin.State.OPEN, points = 5),
            Twin.Task("high", "high", Twin.State.OPEN, points = 60),
            Twin.Task("old", "old", Twin.State.DONE, completedAt = now - 40L * 24 * 60 * 60 * 1000),
            Twin.Task("new", "new", Twin.State.DONE, completedAt = now - 1000),
            Twin.Task("in", "in", Twin.State.INBOX),
        )
        assertEquals(listOf("high", "low", "in", "new"), Twin.select(tasks, now).map { it.id })
    }

    @Test fun `actions read as plain lines`() {
        val title = { id: String -> sent.firstOrNull { it.id == id }?.title }
        assertEquals(
            "Plan for today at 11:30: Send the signed PO to Priya",
            Twin.describe(Twin.Action.Plan("a", "2026-11-02", ist("2026-11-02T11:30")), title, now),
        )
        assertEquals("Mark done: Send the signed PO to Priya", Twin.describe(Twin.Action.Done("a"), title, now))
        assertEquals(
            "Remove the date: Check Rahul sent the quote",
            Twin.describe(Twin.Action.Edit("b", null, null, true, null, null), title, now),
        )
    }

    // -- planning without a model ---------------------------------------------

    @Test fun `free time is what the meetings leave between nine and seven`() {
        val gaps = DayPlanner.gaps(
            listOf(
                Twin.Meeting(ist("2026-11-02T11:00"), ist("2026-11-02T11:30"), "Sync"),
                Twin.Meeting(ist("2026-11-02T14:00"), ist("2026-11-02T15:00"), "Review"),
                Twin.Meeting(ist("2026-11-02T14:30"), ist("2026-11-02T15:10"), "Overlap"),
                Twin.Meeting(null, null, "Holiday"),
            ),
            now,
        )
        assertEquals(
            listOf("10:00-11:00", "11:30-14:00", "15:10-19:00"),
            gaps.map { "${hm(it.start)}-${hm(it.end)}" },
        )
        assertTrue(DayPlanner.gaps(emptyList(), ist("2026-11-02T18:45")).isEmpty())
    }

    private fun hm(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(DateResolver.IST).toLocalTime().toString()

    private fun open(id: String, plannedFor: String? = null, priority: Priority = Priority.MEDIUM, dueAt: Long? = null, waiting: Boolean = false) =
        TodayPlan.Task(id, plannedFor, false, Importance.Input(id, id, priority, dueAt, now, now, emptyList(), null, null, waiting))

    @Test fun `a day plan keeps today's choices and fits the rest between meetings`() {
        val picks = DayPlanner.planDay(
            listOf(
                open("chosen", plannedFor = "2026-11-02"),
                open("due", priority = Priority.HIGH, dueAt = ist("2026-11-02T18:00")),
                open("someday", priority = Priority.LOW),
            ),
            listOf(Twin.Meeting(ist("2026-11-02T10:30"), ist("2026-11-02T12:00"), "Board")),
            now,
        )
        assertEquals(listOf("chosen", "due"), picks.map { it.taskId })
        // 10:00-10:30 is too short for 45 minutes, so the first goes after the meeting.
        assertEquals(ist("2026-11-02T12:00"), picks[0].at)
        assertEquals(ist("2026-11-02T12:45"), picks[1].at)
    }

    @Test fun `a week plan puts dated work before its day and leaves the unimportant`() {
        val plan = DayPlanner.planWeek(
            listOf(
                open("friday", priority = Priority.HIGH, dueAt = ist("2026-11-06T18:00")),
                open("urgent", priority = Priority.URGENT),
                open("someday", priority = Priority.LOW),
                open("waiting", priority = Priority.HIGH, dueAt = ist("2026-11-03T18:00"), waiting = true),
                open("already", plannedFor = "2026-11-04"),
            ),
            now,
        ).toMap()
        assertEquals("2026-11-05", plan["friday"])
        assertEquals("2026-11-02", plan["urgent"])
        assertFalse("someday" in plan)
        assertFalse("waiting" in plan)
        assertFalse("already" in plan)
    }

    // -- undo -------------------------------------------------------------------

    private fun snap(due: Long? = null, day: String? = null, status: String = "ACTIVE", title: String = "Send PO") =
        Journal.Snapshot(title, due, null, "MEDIUM", status, null, day, false, null, emptyList(), null)

    @Test fun `entries survive the trip through JSON`() {
        val entry = Journal.Entry(
            "e1", now, "twin", "Planned 1 task",
            listOf(Journal.Change("a", Journal.Kind.CHANGED, snap(), snap(day = "2026-11-02")), Journal.Change("n", Journal.Kind.CREATED, null, snap())),
        )
        assertEquals(entry, Journal.decode(Journal.encode(entry)))
        assertNull(Journal.decode("{oops"))
    }

    @Test fun `undo restores what the change set and keeps what the user changed since`() {
        val entry = Journal.Entry(
            "e1", now, "twin", "x",
            listOf(
                Journal.Change("a", Journal.Kind.CHANGED, snap(day = null, due = 1L), snap(day = "2026-11-02", due = 2L)),
                Journal.Change("b", Journal.Kind.CHANGED, snap(day = null), snap(day = "2026-11-02")),
                Journal.Change("new", Journal.Kind.CREATED, null, snap()),
                Journal.Change("gone", Journal.Kind.CHANGED, snap(), snap(status = "COMPLETED")),
            ),
        )
        val current = mapOf(
            // The day is still as the twin set it; the date was edited since.
            "a" to snap(day = "2026-11-02", due = 3L),
            // Already moved back by hand.
            "b" to snap(day = null),
            "new" to snap(),
        )
        val reverts = Journal.undo(entry) { current[it] }
        val a = reverts[0] as Journal.Revert.Restore
        assertNull(a.to.plannedFor)
        assertEquals(3L, a.to.dueAt)
        assertEquals(1, a.kept)
        assertTrue(reverts[1] is Journal.Revert.Nothing)
        assertTrue(reverts[2] is Journal.Revert.Remove)
        assertTrue(reverts[3] is Journal.Revert.Nothing)
        assertEquals(listOf("due", "day"), Journal.changedFields(snap(due = 1L), snap(due = 2L, day = "x")))
    }

    // -- nudges -------------------------------------------------------------------

    @Test fun `people who owe you are found once their promise has aged`() {
        val day = 24L * 60 * 60 * 1000
        val tasks = listOf(
            Twin.Task("late", "Check Rahul sent the quote", Twin.State.OPEN, waiting = true, from = "Rahul (WhatsApp)", dueAt = now - day, createdAt = now - 3 * day),
            Twin.Task("fresh", "Check Neha shared the deck", Twin.State.OPEN, waiting = true, from = "Neha (WhatsApp)", createdAt = now - day / 2),
            Twin.Task("nudged", "Check Amit paid", Twin.State.OPEN, waiting = true, from = "Amit (WhatsApp)", createdAt = now - 5 * day),
            Twin.Task("mine", "Send PO", Twin.State.OPEN, from = "Priya (WhatsApp)", createdAt = now - 5 * day),
            Twin.Task("nobody", "Check the vendor replied", Twin.State.OPEN, waiting = true, createdAt = now - 5 * day),
        )
        val found = Nudge.candidates(tasks, mapOf("nudged" to now - day), now)
        assertEquals(listOf("late"), found.map { it.taskId })
        assertEquals("Rahul", found.single().person)
        assertEquals("WhatsApp", found.single().channel)
    }

    @Test fun `the template answers in the person's language`() {
        val day = 24L * 60 * 60 * 1000
        val hinglish = Nudge.Candidate("x", "Rahul Sharma", "Check Rahul sent the quote", "main kal tak quote bhej dunga", now - 3 * day, null, "WhatsApp")
        assertEquals("Hi Rahul, 3 days ago aapne bola tha: \"main kal tak quote bhej dunga\". Koi update? Thanks!", Nudge.draft(hinglish, now))
        val english = hinglish.copy(evidence = "I will send the quote by Monday")
        assertEquals("Hi Rahul, just following up on this from 3 days ago: \"I will send the quote by Monday\". Any update?", Nudge.draft(english, now))
        assertEquals("Hi Rahul, quote?", Nudge.parse("""{"text":"Hi Rahul, quote?"}"""))
    }

    @Test fun `numbers become whatsapp numbers`() {
        assertEquals("919876543210", Nudge.whatsappNumber("+91 98765 43210"))
        assertEquals("919876543210", Nudge.whatsappNumber("098765 43210"))
        assertEquals("447700900123", Nudge.whatsappNumber("+44 7700 900123"))
        assertNull(Nudge.whatsappNumber("12345"))
        assertNull(Nudge.whatsappNumber(null))
    }

    // -- recall --------------------------------------------------------------------

    @Test fun `recall finds the messages a question is about`() {
        val items = listOf(
            Recall.Item(ist("2026-10-20T10:00"), "Priya", "Can you send the invoice for the October batch by Friday?"),
            Recall.Item(ist("2026-10-21T10:00"), "Rahul", "The invoice is ready"),
            Recall.Item(ist("2026-10-22T10:00"), "Neha", "lunch?"),
            Recall.Item(ist("2026-09-10T10:00"), "Priya", "Please share the deck"),
        )
        val found = Recall.search("What did Priya ask about the invoice?", items, now)
        assertEquals("Priya", found.first().from)
        assertTrue(found.first().text.contains("invoice"))
        assertFalse(found.any { it.from == "Neha" })
        val october = Recall.search("What did Priya ask for in October?", items, now)
        assertEquals(listOf(ist("2026-10-20T10:00")), october.map { it.at })
        assertTrue(Recall.terms("kya Rahul ne quote bheja?").containsAll(listOf("rahul", "quote")))
        assertEquals(java.time.Month.OCTOBER, Recall.monthIn("in oct"))
        assertNull(Recall.monthIn("may I ask"))
    }
}
