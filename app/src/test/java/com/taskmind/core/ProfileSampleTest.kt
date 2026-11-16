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
