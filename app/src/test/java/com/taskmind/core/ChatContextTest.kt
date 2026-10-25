package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** What the model sees around a message, and how its references come back. */
class ChatContextTest {

    private fun ist(text: String): Long =
        LocalDateTime.parse(text).atZone(DateResolver.IST).toInstant().toEpochMilli()

    private val block = ChatContext.block(
        recent = listOf(
            ChatContext.Line(ist("2026-10-25T10:05"), "Priya", "did you get the PO?"),
            ChatContext.Line(ist("2026-10-25T10:00"), "Priya", "sending the PO now"),
        ),
        tasks = listOf(
            ChatContext.OpenTask("uuid-a", "Send signed PO to Priya", ist("2026-10-27T18:00"), "Vendor PO"),
            ChatContext.OpenTask("uuid-b", "Share rack quote", null, null),
        ),
        matters = listOf(ChatContext.Matter("p1", "Vendor PO"), ChatContext.Matter("p2", "Hiring SREs")),
    )

    @Test fun `the block reads oldest first, numbered, with no ids`() {
        val text = ChatContext.render(block)
        assertTrue(text.indexOf("sending the PO now") < text.indexOf("did you get the PO?"))
        assertTrue(text.contains("T1. Send signed PO to Priya (due Tue 27 Oct 18:00) [matter: Vendor PO]"))
        assertTrue(text.contains("T2. Share rack quote\n"))
        assertTrue(text.contains("M2. Hiring SREs"))
        assertFalse(text.contains("uuid-a"))
    }

    @Test fun `no context renders nothing`() {
        assertEquals("", ChatContext.render(ChatContext.Block.EMPTY))
    }

    @Test fun `task references come back as ids`() {
        assertEquals("uuid-b", ChatContext.resolveTask("T2", block))
        assertEquals("uuid-a", ChatContext.resolveTask("1", block))
        assertNull(ChatContext.resolveTask("T9", block))
        assertNull(ChatContext.resolveTask(null, block))
    }

    @Test fun `matters by number, by name, as new, or none`() {
        assertEquals(ChatContext.MatterChoice.Existing("p2"), ChatContext.resolveMatter("M2", block))
        assertEquals(ChatContext.MatterChoice.Existing("p1"), ChatContext.resolveMatter("vendor po", block))
        assertEquals(ChatContext.MatterChoice.New("Flat rent"), ChatContext.resolveMatter("Flat rent", block))
        assertEquals(ChatContext.MatterChoice.None, ChatContext.resolveMatter("null", block))
        assertEquals(ChatContext.MatterChoice.None, ChatContext.resolveMatter("M9", block))
    }

    @Test fun `same chat means same group, or the same one-to-one sender`() {
        val a = ChatContext.parseLabel("sender=Priya|group=Vendors|isGroup=true")
        val b = ChatContext.parseLabel("sender=Amit|group=Vendors|isGroup=true")
        val c = ChatContext.parseLabel("sender=Priya|isGroup=false")
        assertTrue(ChatContext.sameChat(a, b))
        assertFalse(ChatContext.sameChat(a, c))
        assertTrue(ChatContext.sameChat(c, ChatContext.parseLabel("sender=priya|isGroup=false")))
    }

    @Test fun `long chats keep only the newest lines`() {
        val lines = (1..15).map { ChatContext.Line(it * 60_000L, "A", "line $it") }
        val kept = ChatContext.block(lines, emptyList(), emptyList()).recent
        assertEquals(ChatContext.MAX_RECENT, kept.size)
        assertEquals("line 15", kept.last().text)
    }
}
