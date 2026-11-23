package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSampleTest {

    private fun l(chat: String, body: String, group: Boolean = false) = ProfileSample.Line(chat, group, 0, body)

    @Test
    fun `numbers, emails and links are masked`() {
        val m = ProfileSample.mask("call me on +91 98765 43210 or mail a.b@x.com, see https://x.co/a; PO 4471")
        assertFalse(m.contains("98765"))
        assertFalse(m.contains("a.b@x.com"))
        assertFalse(m.contains("https"))
        assertTrue(m.contains("[number]") && m.contains("[email]") && m.contains("[link]"))
        // A short number like a PO reference is kept: it says what the work is.
        assertTrue(m.contains("4471"))
    }

    @Test
    fun `short replies are skipped and chats take turns`() {
        val sample = ProfileSample.build(
            listOf(
                listOf(l("Priya", "ok"), l("Priya", "sending the signed PO by five today"), l("Priya", "the vendor call moved to Monday morning")),
                listOf(l("Mom", "haan"), l("Mom", "I will pick up your medicines on the way back"), l("Mom", "")),
                listOf(l("Society", "who parked in my slot again this morning", group = true)),
            ),
        )
        assertEquals(4, sample.messages)
        assertEquals(3, sample.chats)
        assertTrue(sample.text.contains("[with Priya]\n- sending the signed PO"))
        assertTrue(sample.text.contains("[group: Society]"))
        assertFalse(sample.text.contains("- ok"))
    }

    @Test
    fun `the sample stops at the size limit`() {
        val many = (1..500).map { l("Big", "message number $it about the data centre migration plan") }
        val sample = ProfileSample.build(listOf(many), maxChars = 1000)
        assertTrue(sample.text.length <= 1000 + 20)
        assertTrue(sample.messages in 10..25)
    }
}

class ChatSearchTest {
    @Test
    fun `messages with more of the words come first, then newer`() {
        val items = listOf(
            "invoice sent yesterday" to 3L,
            "Sharma invoice pending payment" to 1L,
            "payment received" to 5L,
            "lunch?" to 9L,
            "sharmaji ka invoice" to 2L,
        )
        val ranked = ChatSearch.rank(items, ChatSearch.terms("Sharma invoice"), { it.first }, { it.second })
        assertEquals(listOf("sharmaji ka invoice", "Sharma invoice pending payment", "invoice sent yesterday"), ranked.map { it.first })
        assertTrue(ChatSearch.terms("a, बिजली bill!").containsAll(listOf("बिजली", "bill")))
    }
}

class BriefingTest {
    private val now = java.time.ZonedDateTime.of(2026, 11, 17, 10, 0, 0, 0, DateResolver.IST).toInstant().toEpochMilli()
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a brief lists what is owed both ways and the last thing said`() {
        val lines = Briefing.lines(
            "Priya Sharma",
            youOwe = listOf(Briefing.Open("Send the signed PO", now + 2 * 3600_000), Briefing.Open("Fix slide 4", null), Briefing.Open("Book venue", now + 3 * day)),
            theyOwe = listOf(Briefing.Open("Revised quote", null)),
            last = Briefing.LastSaid(now - day, fromMe = false, body = "call me on 9876543210 about the PO"),
            now = now,
        )
        assertEquals("You owe 3: Send the signed PO (due today); Book venue (due 20 Nov); +1 more", lines[0])
        assertEquals("They owe you 1: Revised quote", lines[1])
        assertTrue(lines[2].startsWith("Last, 16 Nov - Priya: call me on [number]"))
        assertTrue(Briefing.lines("X", emptyList(), emptyList(), null, now).isEmpty())
    }

    @Test
    fun `voice examples are the user's own, masked, distinct and bounded`() {
        val ex = Voice.examples(listOf("ok", "Haan bhai, kal tak bhej dunga PO", "haan bhai, kal tak bhej dunga po", "mail me at a@b.com about it please", "x".repeat(300)))
        assertEquals(listOf("Haan bhai, kal tak bhej dunga PO", "mail me at [email] about it please"), ex)
        assertTrue(Voice.block(ex).contains("- Haan bhai"))
        assertEquals("", Voice.block(emptyList()))
    }
}

class TwinWebTest {
    @Test
    fun `snippets going online are cut, masked and capped`() {
        val memory = (1..12).map { Twin.Memory(it.toLong(), "Priya", "call 9876543210 " + "x".repeat(400)) }
        val s = TwinWeb.snippets(memory)
        assertEquals(TwinWeb.MAX_SNIPPETS, s.size)
        assertTrue(s.all { it.text.length <= TwinWeb.SNIPPET_CHARS })
        assertFalse(s[0].text.contains("9876543210"))
        assertEquals("asked_at=lt.1970-01-01T00:00:00Z", TwinWeb.expiredFilter(30L * 24 * 60 * 60 * 1000))
    }
}


class PriorityChatsTest {
    @Test
    fun `a priority person matches their own chat, a priority group its messages`() {
        val p = setOf("Priya Sharma", " Vendors ")
        assertTrue(PriorityChats.matches(p, "priya sharma", null))
        assertFalse(PriorityChats.matches(p, "Priya Sharma", "Family")) // her message in another group
        assertTrue(PriorityChats.matches(p, "Rahul", "VENDORS"))
        assertFalse(PriorityChats.matches(p, "Rahul", null))
        assertFalse(PriorityChats.matches(emptySet(), "Priya Sharma", null))
        assertEquals(Priority.HIGH, PriorityChats.bump(Priority.LOW))
        assertEquals(Priority.URGENT, PriorityChats.bump(Priority.URGENT))
        assertTrue(PriorityChats.withRule("rule A").startsWith("PRIORITY CHAT"))
    }
}
