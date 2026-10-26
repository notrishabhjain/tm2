package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rule ideas from what was kept and dropped, and how approved rules reach the prompt. */
class RulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun e(kind: Feedback.Kind, person: String?, group: String? = null, daysAgo: Int = 1) =
        Feedback.Event(now - daysAgo * day, kind, person, group)

    @Test fun `a group you always drop becomes an idea, with the numbers`() {
        val events = List(5) { e(Feedback.Kind.NOT_MINE, "Bot$it", "Ops Alerts") }
        val ideas = Rules.ideas(events, emptyList(), now)
        assertEquals(1, ideas.size)
        assertEquals(Rules.Kind.IGNORE_GROUP, ideas[0].kind)
        assertEquals("Ops Alerts", ideas[0].subject)
        assertTrue(ideas[0].why.contains("5"))
    }

    @Test fun `one kept task is enough to stop an ignore idea`() {
        val events = List(5) { e(Feedback.Kind.DISMISSED, "Amit", "Ops Alerts") } +
            e(Feedback.Kind.COMPLETED, "Amit", "Ops Alerts")
        assertTrue(Rules.ideas(events, emptyList(), now).none { it.kind == Rules.Kind.IGNORE_GROUP })
    }

    @Test fun `old feedback does not count`() {
        val events = List(5) { e(Feedback.Kind.NOT_MINE, "X", "Old Group", daysAgo = 40) }
        assertTrue(Rules.ideas(events, emptyList(), now).isEmpty())
    }

    @Test fun `someone whose asks you always do is suggested as key`() {
        val events = List(4) { e(Feedback.Kind.COMPLETED, "Priya", if (it % 2 == 0) "Vendors" else null) }
        val ideas = Rules.ideas(events, emptyList(), now)
        assertEquals(listOf(Rules.Kind.KEY_PERSON to "Priya"), ideas.map { it.kind to it.subject })
    }

    @Test fun `a one-to-one sender you always drop is an idea, a rejected idea is not repeated`() {
        val events = List(3) { e(Feedback.Kind.DELETED_QUICKLY, "Promo Desk") }
        assertEquals(Rules.Kind.IGNORE_SENDER, Rules.ideas(events, emptyList(), now).single().kind)
        val rejected = Rules.Rule(Rules.Kind.IGNORE_SENDER, "promo desk", Rules.State.REJECTED)
        assertTrue(Rules.ideas(events, listOf(rejected), now).isEmpty())
    }

    @Test fun `only approved rules reach the prompt`() {
        val rules = listOf(
            Rules.Rule(Rules.Kind.IGNORE_GROUP, "Ops Alerts", Rules.State.APPROVED),
            Rules.Rule(Rules.Kind.KEY_PERSON, "Priya", Rules.State.REJECTED),
            Rules.Rule(Rules.Kind.CUSTOM, "Stock-level messages are never tasks.", Rules.State.APPROVED),
        )
        val block = Rules.promptBlock(rules)
        assertTrue(block.startsWith("Rules the user approved:\n"))
        assertTrue(block.contains("- Messages in the group \"Ops Alerts\" are not the user's tasks unless they name the user."))
        assertTrue(block.contains("- Stock-level messages are never tasks."))
        assertTrue(!block.contains("Priya"))
        assertEquals("", Rules.promptBlock(emptyList()))
        assertEquals(emptySet<String>(), Rules.keyPeople(rules))
    }

    @Test fun `rules and events survive storage`() {
        val r = Rules.Rule(Rules.Kind.CUSTOM, "Anything from HR about leave is low priority", Rules.State.APPROVED)
        assertEquals(r, Rules.decode(Rules.encode(r)))
        val ev = Feedback.Event(123L, Feedback.Kind.KEPT, "Sharma Ji", null)
        assertEquals(ev, Feedback.decode(Feedback.encode(ev)))
        assertNull(Feedback.decode("garbage"))
    }

    @Test fun `a new decision replaces the old one`() {
        val a = Rules.Rule(Rules.Kind.KEY_PERSON, "Priya", Rules.State.REJECTED)
        val b = a.copy(subject = "PRIYA", state = Rules.State.APPROVED)
        assertEquals(listOf(b), Rules.decide(listOf(a), b))
    }
}
