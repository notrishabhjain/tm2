package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group policy, which is the one rule in this filter that rejects things
 * that might genuinely be tasks. It gets its own tests for that reason.
 */
class PreFilterGroupTest {

    private val me = listOf("Rishabh", "RJ")

    private fun input(
        text: String,
        isGroup: Boolean = true,
        policy: PreFilter.GroupPolicy = PreFilter.GroupPolicy.ADDRESSED_TO_ME,
    ) = PreFilter.Input(
        packageName = "com.whatsapp",
        senderKey = "Amit",
        text = text,
        hasResolvedMessage = true,
        isGroup = isGroup,
        groupPolicy = policy,
        mention = Mention.scan(text, me),
    )

    private fun rejectRule(verdict: PreFilter.Verdict): String? =
        (verdict as? PreFilter.Verdict.Reject)?.rule

    @Test
    fun `a colleague asking another colleague is rejected`() {
        val verdict = PreFilter.evaluate(input("@Amit please share the TCP dump from your side"))
        assertEquals("group message not addressed to you", rejectRule(verdict))
    }

    @Test
    fun `the same message passes when it names me`() {
        assertTrue(
            PreFilter.evaluate(input("@Rishabh please share the TCP dump")) is PreFilter.Verdict.Pass,
        )
    }

    @Test
    fun `a broadcast to the group passes`() {
        assertTrue(
            PreFilter.evaluate(input("everyone please submit your timesheets by Friday")) is PreFilter.Verdict.Pass,
        )
    }

    @Test
    fun `a one-to-one message is never touched by the group rule`() {
        assertTrue(
            PreFilter.evaluate(
                input("woh payment kal tak kar dena please", isGroup = false),
            ) is PreFilter.Verdict.Pass,
        )
    }

    @Test
    fun `the old behaviour is still available`() {
        assertTrue(
            PreFilter.evaluate(
                input("@Amit please share the dump", policy = PreFilter.GroupPolicy.EVERYTHING),
            ) is PreFilter.Verdict.Pass,
        )
    }

    @Test
    fun `never ignores even a message naming me`() {
        val verdict = PreFilter.evaluate(
            input("@Rishabh please share the dump", policy = PreFilter.GroupPolicy.NEVER),
        )
        assertEquals("group chat ignored by policy", rejectRule(verdict))
    }

    @Test
    fun `the rejection says which kind it was`() {
        val verdict = PreFilter.evaluate(input("@Amit please share the dump"))
        assertEquals("names someone else", (verdict as PreFilter.Verdict.Reject).detail)
    }

    @Test
    fun `an unaddressed group message says so`() {
        val verdict = PreFilter.evaluate(input("chalo phir kal dekh lenge iska kya karna hai"))
        assertEquals("no mention of you", (verdict as PreFilter.Verdict.Reject).detail)
    }

    @Test
    fun `the group rule runs last so an OTP still reports as an OTP`() {
        val verdict = PreFilter.evaluate(input("Your OTP is 493021, do not share it with anyone"))
        assertEquals("otp/verification pattern", rejectRule(verdict))
    }

    @Test
    fun `a stored policy name that no longer exists falls back`() {
        assertEquals(null, PreFilter.GroupPolicy.byName("SOMETHING_OLD"))
        assertEquals(PreFilter.GroupPolicy.NEVER, PreFilter.GroupPolicy.byName("NEVER"))
    }
}
