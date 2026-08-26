package com.taskmind

import com.taskmind.core.EvidenceMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 13. This is the mechanical anti-hallucination device: the model cannot
 * invent a task without also inventing a quote, and the quote is checkable.
 *
 * It has to be tolerant enough that real model output survives (whitespace,
 * punctuation, case) and strict enough that invented text does not.
 */
class EvidenceMatcherTest {

    private val source =
        "beta woh 25000 ka payment kal tak kar dena warna late fee lagegi, aur invoice bhi bhej dena"

    @Test
    fun `exact quote matches`() {
        val r = EvidenceMatcher.match("woh 25000 ka payment kal tak kar dena", source, 0.90)
        assertTrue(r.matched)
        assertEquals(1.0, r.bestScore, 0.0001)
    }

    @Test
    fun `case and punctuation differences still match`() {
        val r = EvidenceMatcher.match("Woh 25,000 ka Payment kal tak kar dena.", source, 0.90)
        assertTrue(r.reason, r.matched)
    }

    @Test
    fun `whitespace normalisation still matches`() {
        val r = EvidenceMatcher.match("woh   25000\nka payment  kal tak kar dena", source, 0.90)
        assertTrue(r.reason, r.matched)
    }

    @Test
    fun `a single dropped word still matches at call tolerance`() {
        val r = EvidenceMatcher.match("woh 25000 ka payment kal kar dena", source, 0.75)
        assertTrue(r.reason, r.matched)
    }

    @Test
    fun `invented evidence does not match`() {
        val r = EvidenceMatcher.match("transfer 50000 to the vendor by Friday", source, 0.90)
        assertFalse(r.reason, r.matched)
    }

    @Test
    fun `plausible but unstated evidence does not match`() {
        val r = EvidenceMatcher.match("please share the bank details tomorrow", source, 0.75)
        assertFalse(r.reason, r.matched)
    }

    @Test
    fun `null evidence never matches`() {
        assertFalse(EvidenceMatcher.match(null, source, 0.5).matched)
    }

    @Test
    fun `evidence shorter than the floor never matches`() {
        // "payment" appears verbatim but is too short to prove anything.
        val r = EvidenceMatcher.match("payment", source, 0.5)
        assertFalse(r.reason, r.matched)
    }

    @Test
    fun `empty source never matches`() {
        assertFalse(EvidenceMatcher.match("woh 25000 ka payment kal tak", "", 0.5).matched)
    }

    @Test
    fun `devanagari evidence matches devanagari source`() {
        val hindi = "हाँ जी कल तक पेमेंट कर दूंगा और इनवॉइस भेज दूंगा"
        val r = EvidenceMatcher.match("कल तक पेमेंट कर दूंगा", hindi, 0.75)
        assertTrue(r.reason, r.matched)
    }

    @Test
    fun `tolerance is actually applied`() {
        // Half the tokens present: passes at 0.5, fails at 0.9.
        val evidence = "25000 ka payment totally different words here"
        val loose = EvidenceMatcher.match(evidence, source, 0.4)
        val strict = EvidenceMatcher.match(evidence, source, 0.9)
        assertTrue(loose.bestScore > 0.0)
        assertFalse(strict.matched)
    }

    @Test
    fun `noisy asr transcript still grounds at call tolerance`() {
        val transcript =
            "haan ji to main kal tak wo report bhej deta hoon aap chinta mat kijiye theek hai"
        val r = EvidenceMatcher.match("main kal tak woh report bhej deta hun", transcript, 0.75)
        assertTrue(r.reason, r.matched)
    }
}
