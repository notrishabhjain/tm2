package com.taskmind.core

import com.taskmind.core.FirstReadMatch.Found
import com.taskmind.core.FirstReadMatch.Read
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstReadMatchTest {

    @Test
    fun `the model agreeing on the same words confirms the first read`() {
        val plan = FirstReadMatch.plan(
            listOf(Read("t1", "can you send me the signed PO by 5pm today?")),
            listOf(Found(0, "send me the signed PO by 5pm today", confident = true)),
        )
        assertEquals(mapOf("t1" to 0), plan.confirm)
        assertTrue(plan.withdraw.isEmpty())
    }

    @Test
    fun `the model finding nothing withdraws the first read`() {
        val plan = FirstReadMatch.plan(listOf(Read("t1", "please share the deck")), emptyList())
        assertTrue(plan.confirm.isEmpty())
        assertEquals(listOf("t1"), plan.withdraw)
    }

    @Test
    fun `an unsure model answer does not confirm`() {
        val plan = FirstReadMatch.plan(
            listOf(Read("t1", "please share the deck")),
            listOf(Found(0, "please share the deck", confident = false)),
        )
        assertEquals(listOf("t1"), plan.withdraw)
    }

    @Test
    fun `one first read and one task in other words still pair up`() {
        val plan = FirstReadMatch.plan(
            listOf(Read("t1", "can you sign it today?")),
            listOf(Found(0, "Rishabh, the PO ... can you sign it\ntoday?", confident = true)),
        )
        assertEquals(mapOf("t1" to 0), plan.confirm)
    }

    @Test
    fun `two asks pair by their words, not by order`() {
        val plan = FirstReadMatch.plan(
            listOf(Read("a", "please pay the vendor today"), Read("b", "send me the deck")),
            listOf(
                Found(0, "send me the deck", confident = true),
                Found(1, "pay the vendor today", confident = true),
            ),
        )
        assertEquals(mapOf("b" to 0, "a" to 1), plan.confirm)
    }

    @Test
    fun `an unrelated task does not swallow a first read when there are two`() {
        val plan = FirstReadMatch.plan(
            listOf(Read("a", "please pay the vendor today"), Read("b", "send me the deck")),
            listOf(Found(0, "book the cab for 7", confident = true)),
        )
        assertTrue(plan.confirm.isEmpty())
        assertEquals(listOf("a", "b"), plan.withdraw)
    }

    @Test
    fun `overlap works across scripts and ignores punctuation`() {
        assertEquals(1.0, FirstReadMatch.overlap("रिपोर्ट भेज दो!", "रिपोर्ट भेज दो"), 0.001)
        assertEquals(0.0, FirstReadMatch.overlap(null, "x"), 0.001)
    }
}
