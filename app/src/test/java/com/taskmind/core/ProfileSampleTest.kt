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

class QuickCheckTest {
    @Test
    fun `only a confident none skips the full read`() {
        assertTrue(QuickCheck.skip(QuickCheck.parse("""{"verdict":"none","confidence":0.93}""")))
        assertFalse(QuickCheck.skip(QuickCheck.parse("""{"verdict":"none","confidence":0.6}""")))
        assertFalse(QuickCheck.skip(QuickCheck.parse("""{"verdict":"maybe","confidence":0.99}""")))
        assertFalse(QuickCheck.skip(QuickCheck.parse("""{"verdict":"task","confidence":0.99}""")))
        // Anything unreadable goes on to the full read.
        assertFalse(QuickCheck.skip(QuickCheck.parse("not json")))
        assertFalse(QuickCheck.skip(QuickCheck.parse("""{"verdict":"none"}""")))
        assertFalse(QuickCheck.skip(null))
        assertEquals(QuickCheck.Verdict.NONE, QuickCheck.parse("```json\n{\"verdict\":\"None\",\"confidence\":\"0.9\"}\n```")?.verdict)
    }

    @Test
    fun `the model is the user's, or a small one on Groq, or none`() {
        assertEquals("my-model", QuickCheck.modelFor(" my-model ", "https://openrouter.ai/api/v1"))
        assertEquals(QuickCheck.DEFAULT_GROQ_MODEL, QuickCheck.modelFor("", "https://api.groq.com/openai/v1"))
        assertEquals(null, QuickCheck.modelFor("", "https://api.openai.com/v1"))
    }

    @Test
    fun `the prompt stays small`() {
        val p = QuickCheck.userPrompt("Priya", "Vendors", setOf("RJ"), (1..9).map { "task $it" }, "x".repeat(5000))
        assertTrue(p.length < 1800)
        assertTrue(p.contains("- task 5") && !p.contains("- task 6"))
    }
}

class ReplyTrackerTest {
    @Test
    fun `a reply is kept only when sent, once, in the open chat`() {
        var t = 1_000L
        val r = ReplyTracker { t }
        assertEquals(null, r.onSend()) // no chat open
        r.onChat("Priya")
        r.onTyped("will send the PO by 5")
        val sent = r.onSend()
        assertEquals(ReplyTracker.Reply("Priya", "will send the PO by 5", 1_000L), sent)
        // The box is empty after sending; a second report of the press keeps nothing.
        assertEquals(null, r.onSend())
        r.onTyped("will send the PO by 5")
        t += 500
        assertEquals(null, r.onSend()) // same text within 3s: the same press
        // A draft left behind when the chat changes is not carried over.
        r.onTyped("half typed")
        r.onChat("Rahul")
        assertEquals(null, r.onSend())
        r.onTyped("   ")
        assertEquals(null, r.onSend())
        // WhatsApp empties the box before the press is reported.
        r.onTyped("haan bhai, kal")
        r.onTyped("")
        t += 300
        assertEquals("haan bhai, kal", r.onSend()?.text)
        // Text deleted long before a press is not a reply.
        r.onTyped("never mind")
        r.onTyped("")
        t += 5_000
        assertEquals(null, r.onSend())
        assertTrue(ReplyTracker.looksLikeSend("Send"))
        assertFalse(ReplyTracker.looksLikeSend("Attach"))
        assertEquals("entry", ReplyTracker.idTail("com.whatsapp:id/entry"))
    }
}
