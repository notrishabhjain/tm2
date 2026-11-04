package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class QuickReadTest {

    private fun ist(s: String): Long = LocalDateTime.parse(s).atZone(DateResolver.IST).toInstant().toEpochMilli()

    // Wednesday 4 Nov 2026, 10:00 IST.
    private val now = ist("2026-11-04T10:00:00")
    private val me = listOf("Rishabh")

    private fun read(text: String, sender: String = "Priya Sharma", group: Boolean = false) =
        QuickRead.read(text, sender, me, group, now)

    // ------------------------------------------------------------------ asks

    @Test
    fun `a polite ask with a deadline is read at once`() {
        val r = read("Rishabh, can you send me the signed PO by 5pm today?")
        assertNotNull(r)
        r!!
        assertEquals(QuickRead.Kind.ASK, r.kind)
        assertEquals("Send Priya the signed PO", r.title)
        assertEquals(ist("2026-11-04T17:00:00"), r.dueAt)
        assertTrue(r.confidence >= 0.85)
        assertEquals("Rishabh, can you send me the signed PO by 5pm today?", r.evidence)
    }

    @Test
    fun `please plus a verb is an ask, and the sender is named`() {
        val r = read("Please share the deck")!!
        assertEquals(QuickRead.Kind.ASK, r.kind)
        assertEquals("Share the deck (Priya)", r.title)
        assertNull(r.dueAt)
    }

    @Test
    fun `hinglish asks are read, date words and tak removed`() {
        val r = QuickRead.read("Rishabh bhai kal tak invoice bhej dena", "Amit", me, false, now)!!
        assertEquals(QuickRead.Kind.ASK, r.kind)
        assertEquals("Invoice bhej dena (Amit)", r.title)
        assertEquals(DateResolver.startOfDay(now) + DAY, DateResolver.startOfDay(r.dueAt!!))
        assertTrue(r.confidence >= 0.88)
    }

    @Test
    fun `hindi asks are read`() {
        val r = QuickRead.read("रिपोर्ट आज भेज दो", "Amit", me, false, now)
        assertNotNull(r)
        assertEquals(QuickRead.Kind.ASK, r!!.kind)
    }

    @Test
    fun `a bare imperative in a one-to-one chat is an ask`() {
        val r = read("Call the vendor about the rack delivery")!!
        assertEquals(QuickRead.Kind.ASK, r.kind)
        assertEquals("Call the vendor about the rack delivery (Priya)", r.title)
        assertEquals(0.82, r.confidence, 0.001)
    }

    @Test
    fun `the ask is found in a longer message and quoted exactly`() {
        val r = read("Hi Rishabh. Hope you are well. Can you sign the PO today?")!!
        assertEquals("Can you sign the PO today?", r.evidence)
        assertEquals("Sign the PO (Priya)", r.title)
    }

    @Test
    fun `urgency is noticed without a date`() {
        val r = read("Please transfer the advance asap")!!
        assertTrue(r.urgent)
        assertNull(r.dueAt)
        assertTrue(r.confidence >= 0.88)
    }

    // -------------------------------------------------------------- promises

    @Test
    fun `an english promise is something to wait on`() {
        val r = read("I'll send the revised quote tomorrow")!!
        assertEquals(QuickRead.Kind.PROMISE, r.kind)
        assertEquals("Priya to send the revised quote", r.title)
        assertEquals(DateResolver.startOfDay(now) + DAY, DateResolver.startOfDay(r.dueAt!!))
    }

    @Test
    fun `a hinglish promise is something to wait on`() {
        val r = QuickRead.read("kal tak bhej dunga", "Amit", me, false, now)!!
        assertEquals(QuickRead.Kind.PROMISE, r.kind)
        assertEquals("Waiting on Amit: bhej dunga", r.title)
    }

    @Test
    fun `a question that starts with will is an ask, not a promise`() {
        val r = read("Will you send the report today?")!!
        assertEquals(QuickRead.Kind.ASK, r.kind)
        assertEquals("Send the report (Priya)", r.title)
    }

    // ------------------------------------------------------- left to the model

    @Test
    fun `thanks, greetings and chit-chat are not tasks`() {
        listOf(
            "Thanks, got it",
            "Good morning!",
            "ok sure",
            "Happy birthday bro, have a great one",
            "I will be late today",
            "Ok will do",
        ).forEach { assertNull(it, read(it)) }
    }

    @Test
    fun `chasers, notices, offers and refusals are left to the model`() {
        listOf(
            "Did you send the deck?",
            "Please find attached the invoice",
            "Let me know if you need anything",
            "No need to send it now",
            "Rehne do, mat bhejo",
            "Have you paid the vendor yet?",
        ).forEach { assertNull(it, read(it)) }
    }

    @Test
    fun `links and promotions are left to the model`() {
        assertNull(read("Please check this https://example.com/doc"))
        assertNull(read("Pay now and get 20% off on your next order"))
    }

    @Test
    fun `in a group only an ask that names the user counts`() {
        assertNull(read("Can you send the report?", group = true))
        val r = read("@Rishabh can you send the report?", group = true)
        assertNotNull(r)
        assertEquals("Send the report (Priya)", r!!.title)
    }

    @Test
    fun `an ask naming someone else is not the user's`() {
        assertNull(read("@Priya please send the report", sender = "Neha"))
    }

    @Test
    fun `a promise in a group needs the user named`() {
        assertNull(read("I'll send the minutes tomorrow", group = true))
    }

    @Test
    fun `a sender that is only a number is not put in the title`() {
        val r = QuickRead.read("Please share the deck", "+91 98765 43210", me, false, now)!!
        assertEquals("Share the deck", r.title)
    }

    @Test
    fun `long titles are clipped at a word`() {
        val r = read("Please send " + "the quarterly vendor reconciliation workbook ".repeat(4))!!
        assertTrue(r.title.length <= 92)
        assertTrue(r.title.endsWith("…") || r.title.endsWith(")"))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
