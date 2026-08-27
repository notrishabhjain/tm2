package com.taskmind

import com.taskmind.core.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 7.4 requires titleKey to be unit-tested with at least 15 Hinglish pairs
 * that must and must not collapse. This is the dedup key: if it is wrong, the
 * user either sees the same commitment twice or loses a real second one.
 */
class NormalizeTest {

    private fun collapses(a: String, b: String) {
        assertEquals(
            "expected '$a' and '$b' to share a titleKey",
            Normalize.titleKey(a),
            Normalize.titleKey(b),
        )
    }

    private fun distinct(a: String, b: String) {
        assertNotEquals(
            "expected '$a' and '$b' to have different titleKeys",
            Normalize.titleKey(a),
            Normalize.titleKey(b),
        )
    }

    @Test
    fun `pairs that must collapse`() {
        collapses("Pay 25000 to Sharma Ji", "pay  25000   to sharma ji")
        collapses("bhai payment kar dena", "payment kar dena")
        collapses("beta woh payment kar dena", "woh payment kar dena")
        collapses("Please send the invoice", "send the invoice")
        collapses("pls send the invoice", "Send the invoice.")
        collapses("plz send the invoice", "  send the invoice  ")
        collapses("Kindly share the report", "share the report")
        collapses("zara file bhej dena", "file bhej dena")
        collapses("thoda file bhej dena", "file bhej dena")
        collapses("ek baar call kar lena", "call kar lena")
        collapses("sir approve the PO", "approve the PO")
        collapses("ji approve the PO", "approve the po")
        collapses("bhai please ek baar dekh lena", "dekh lena")
        collapses("Send report!!!", "send report")
        collapses("Call Sharma Ji - urgent", "call sharma ji urgent")
        collapses("Book tickets  ", "book tickets.")
    }

    @Test
    fun `pairs that must not collapse`() {
        distinct("Pay 25000 to Sharma Ji", "Pay 35000 to Sharma Ji")
        distinct("send the invoice", "send the quotation")
        distinct("call sharma ji", "call verma ji")
        distinct("book tickets for Delhi", "book tickets for Mumbai")
        distinct("payment kar dena", "payment mat karna")
        distinct("share the report", "share the reports today")
        distinct("bhej dena file", "file bhej dena")
        distinct("Approve PO 1234", "Approve PO 1235")
        distinct("beta ka admission form bharna", "admission form bharna")
    }

    @Test
    fun `politeness-only title falls back rather than collapsing to empty`() {
        val key = Normalize.titleKey("bhai please ji")
        assertTrue("politeness-only title must not yield an empty key", key.isNotEmpty())
    }

    @Test
    fun `devanagari survives normalisation`() {
        val key = Normalize.titleKey("कल पेमेंट करना")
        assertTrue("Devanagari letters must survive", key.any { it.code in 0x0900..0x097F })
    }

    @Test
    fun `forHash strips punctuation and case but keeps letters and digits`() {
        assertEquals(
            Normalize.forHash("Beta, woh 25,000 ka payment KAL tak!"),
            Normalize.forHash("beta woh 25000 ka payment kal tak"),
        )
    }

    @Test
    fun `forHash of different text differs`() {
        assertNotEquals(Normalize.forHash("pay 25000"), Normalize.forHash("pay 35000"))
    }

    @Test
    fun `tidyTitle strips trailing full stops and collapses whitespace`() {
        assertEquals("Send the invoice", Normalize.tidyTitle("  Send   the invoice.  "))
        assertEquals("Send the invoice", Normalize.tidyTitle("Send the invoice..."))
        assertEquals(
            "काम करो",
            Normalize.tidyTitle("काम करो।"),
        )
    }

    @Test
    fun `sha256 is stable and 64 hex chars`() {
        val h = Normalize.sha256Hex("taskmind")
        assertEquals(64, h.length)
        assertTrue(h.all { it in "0123456789abcdef" })
        assertEquals(h, Normalize.sha256Hex("taskmind"))
        assertFalse(h == Normalize.sha256Hex("taskmind "))
    }
}
