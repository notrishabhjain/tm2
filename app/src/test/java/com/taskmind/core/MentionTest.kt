package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a group message is the user's problem.
 *
 * Written against the traffic this app sees: Hindi, English and both in one
 * sentence, WhatsApp's "@Name" and "@919812345678" forms, and the specific
 * case that started this - one colleague asking another for an update.
 */
class MentionTest {

    private val me = listOf("Rishabh", "RJ", "919812345678")

    private fun scan(text: String) = Mention.scan(text, me)

    // -- addressed to me -----------------------------------------------------

    @Test
    fun `an at-mention of me counts`() {
        assertTrue(scan("@Rishabh please share the deck").mentionsMe)
    }

    @Test
    fun `my bare name counts`() {
        assertTrue(scan("Rishabh bhai woh update de dena").mentionsMe)
    }

    @Test
    fun `my number as a handle counts`() {
        assertTrue(scan("@919812345678 kal tak bhej dena").mentionsMe)
    }

    @Test
    fun `case does not matter`() {
        assertTrue(scan("RISHABH can you check this").mentionsMe)
    }

    @Test
    fun `a short alias still matches`() {
        assertTrue(scan("RJ please confirm the timing").mentionsMe)
    }

    // -- not addressed to me -------------------------------------------------

    @Test
    fun `one colleague asking another is not for me`() {
        val r = scan("@Amit please share the TCP dump from your side")
        assertFalse(r.mentionsMe)
        assertFalse(r.addressedToAll)
        assertTrue(r.namesSomeoneElse)
        assertFalse(r.forMe)
    }

    @Test
    fun `a bare other name is not enough to rule me out`() {
        // "Tell Rahul I called" names Rahul and is still the user's job, so a
        // bare name must never set namesSomeoneElse.
        val r = scan("Rahul ko bata dena ki maine call kiya tha")
        assertFalse(r.namesSomeoneElse)
        assertFalse(r.forMe)
    }

    @Test
    fun `a name inside another word does not match`() {
        // The boundary check: "rjm" must not match the alias "rj".
        assertFalse(Mention.scan("check the rjmatrix export", listOf("rj")).mentionsMe)
    }

    @Test
    fun `an unrelated message mentions nobody`() {
        val r = scan("chalo phir kal milte hain")
        assertFalse(r.mentionsMe)
        assertFalse(r.addressedToAll)
        assertFalse(r.namesSomeoneElse)
    }

    // -- broadcasts ----------------------------------------------------------

    @Test
    fun `at-everyone is a broadcast`() {
        assertTrue(scan("@everyone standup at 10").addressedToAll)
    }

    @Test
    fun `hindi broadcasts count`() {
        assertTrue(scan("aap sab apna status bhej dena").addressedToAll)
        assertTrue(scan("सभी लोग कल समय पर आना").addressedToAll)
    }

    @Test
    fun `a broadcast is for me even without my name`() {
        assertTrue(scan("everyone please submit your timesheets").forMe)
    }

    @Test
    fun `send all the files is not a broadcast`() {
        // Bare "all" is deliberately absent from the list.
        assertFalse(scan("send all the files to the client").addressedToAll)
    }

    @Test
    fun `the team said yes is not a broadcast`() {
        assertFalse(scan("the team said yes to the proposal").addressedToAll)
    }

    // -- edges ---------------------------------------------------------------

    @Test
    fun `no configured names means nothing is addressed to me`() {
        val r = Mention.scan("@Rishabh please share the deck", emptyList())
        assertFalse(r.mentionsMe)
    }

    @Test
    fun `a one-character name is ignored as too loose`() {
        assertFalse(Mention.scan("a quick note about the invoice", listOf("a")).mentionsMe)
    }

    @Test
    fun `my own mention beats another handle in the same message`() {
        val r = scan("@Amit and @Rishabh please sort this out today")
        assertTrue(r.mentionsMe)
        assertFalse("mentioning me must win", r.namesSomeoneElse)
    }

    @Test
    fun `devanagari text does not match a latin alias by accident`() {
        assertFalse(Mention.scan("कल शाम तक भेज देना", listOf("rj")).mentionsMe)
    }

    @Test
    fun `blank text mentions nobody`() {
        assertEquals(Mention.Result(false, false, false), scan(""))
    }
}
