package com.taskmind

import com.taskmind.core.PreFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 10.3. The filter is aimed at HIGH RECALL of real tasks: it may only
 * reject on a matched rule, never on general uncertainty. A false reject costs
 * a commitment, which principle 1 calls the one unforgivable bug.
 */
class PreFilterTest {

    private fun input(
        text: String,
        pkg: String = "com.whatsapp",
        sender: String = "Sharma Ji",
        allowListed: Boolean = true,
        seen: Boolean = false,
        groupSummary: Boolean = false,
        ongoing: Boolean = false,
        media: Boolean = false,
    ) = PreFilter.Input(
        packageName = pkg,
        senderKey = sender,
        text = text,
        isGroupSummary = groupSummary,
        isOngoing = ongoing,
        isMediaStyle = media,
        isAllowListed = allowListed,
        fingerprintSeen = seen,
    )

    private fun rejectRule(v: PreFilter.Verdict): String {
        assertTrue("expected a reject, got $v", v is PreFilter.Verdict.Reject)
        return (v as PreFilter.Verdict.Reject).rule
    }

    @Test
    fun `a real hinglish request passes`() {
        val v = PreFilter.evaluate(input("beta woh 25000 ka payment kal tak kar dena"))
        assertEquals(PreFilter.Verdict.Pass, v)
    }

    @Test
    fun `a real english request passes`() {
        val v = PreFilter.evaluate(input("Can you send me the signed contract before Friday?"))
        assertEquals(PreFilter.Verdict.Pass, v)
    }

    @Test
    fun `a devanagari request passes`() {
        val v = PreFilter.evaluate(input("कल तक रिपोर्ट भेज देना please"))
        assertEquals(PreFilter.Verdict.Pass, v)
    }

    @Test
    fun `otp is rejected`() {
        val rule = rejectRule(
            PreFilter.evaluate(input("123456 is your OTP for login. Do not share it with anyone.")),
        )
        assertTrue(rule.contains("otp"))
    }

    @Test
    fun `hinglish otp warning is rejected`() {
        val rule = rejectRule(
            PreFilter.evaluate(input("aapka verification code 4821 hai, kisi ko na bataye")),
        )
        assertTrue(rule.contains("otp"))
    }

    @Test
    fun `bank debit alert is rejected`() {
        val rule = rejectRule(
            PreFilter.evaluate(
                input("Rs.4,500 debited from a/c XX1234 for UPI txn. Avl bal: Rs.52,310", sender = "HDFCBK"),
            ),
        )
        assertTrue(rule.contains("transactional"))
    }

    @Test
    fun `delivery update is rejected`() {
        val rule = rejectRule(PreFilter.evaluate(input("Your order has been shipped and will arrive today")))
        assertTrue(rule.contains("transactional"))
    }

    @Test
    fun `DLT header sender is rejected`() {
        val rule = rejectRule(PreFilter.evaluate(input("Kindly complete your KYC today", sender = "VM-ABCD12")))
        assertTrue(rule.contains("DLT"))
    }

    @Test
    fun `noreply sender is rejected`() {
        val rule = rejectRule(PreFilter.evaluate(input("Kindly review the attached document", sender = "noreply")))
        assertTrue(rule.contains("automated"))
    }

    @Test
    fun `package not on the allow-list is rejected`() {
        val rule = rejectRule(
            PreFilter.evaluate(input("send me the file kal tak", pkg = "com.example.game", allowListed = false)),
        )
        assertTrue(rule.contains("allow-list"))
    }

    @Test
    fun `own package is rejected`() {
        val rule = rejectRule(PreFilter.evaluate(input("Task created: pay Sharma Ji", pkg = "com.taskmind")))
        assertTrue(rule.contains("own package"))
    }

    @Test
    fun `system packages are rejected`() {
        assertTrue(rejectRule(PreFilter.evaluate(input("USB charging this device", pkg = "android"))).isNotEmpty())
        assertTrue(
            rejectRule(PreFilter.evaluate(input("Screenshot captured", pkg = "com.android.systemui"))).isNotEmpty(),
        )
    }

    @Test
    fun `group summary ongoing and media notifications are rejected`() {
        assertEquals("group summary", rejectRule(PreFilter.evaluate(input("3 new messages", groupSummary = true))))
        assertEquals("ongoing event", rejectRule(PreFilter.evaluate(input("Navigating home", ongoing = true))))
        assertEquals("media notification", rejectRule(PreFilter.evaluate(input("Now playing something", media = true))))
    }

    @Test
    fun `short and empty text is rejected`() {
        assertTrue(rejectRule(PreFilter.evaluate(input("ok"))).contains("shorter"))
        assertEquals("empty text", rejectRule(PreFilter.evaluate(input("   "))))
    }

    @Test
    fun `an already seen fingerprint is rejected`() {
        val rule = rejectRule(PreFilter.evaluate(input("beta payment kal tak kar dena", seen = true)))
        assertTrue(rule.contains("fingerprint"))
    }

    @Test
    fun `a message with a number but no otp words is not rejected`() {
        // The OTP rule needs a number AND an OTP word. "25000" alone is money.
        assertEquals(PreFilter.Verdict.Pass, PreFilter.evaluate(input("25000 bhej dena aaj shaam tak")))
    }

    @Test
    fun `default allow-list covers whatsapp and sms`() {
        assertTrue(PreFilter.DEFAULT_ALLOWED_PACKAGES.contains("com.whatsapp"))
        assertTrue(PreFilter.DEFAULT_ALLOWED_PACKAGES.contains("com.google.android.apps.messaging"))
    }
}
