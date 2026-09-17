package com.taskmind.tagging

import com.taskmind.core.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tagger is pure, so unlike most of this app it can actually be tested
 * without a device. These cases are the label shapes `CaptureCoordinator`
 * really produces, not invented ones.
 */
class AutoTaggerTest {

    private fun keysOf(
        type: SourceType,
        app: String? = null,
        label: String? = null,
        title: String = "",
        evidence: String? = null,
    ) = AutoTagger.keys(type, app, label, title, evidence)

    // -- labels --------------------------------------------------------------

    @Test
    fun `a one-to-one message yields the sender and the app`() {
        val keys = keysOf(
            SourceType.NOTIFICATION,
            app = "com.whatsapp",
            label = "Sharma Ji - WhatsApp",
            title = "Send the revised quote",
        )
        assertTrue("sharma ji" in keys)
        assertTrue("whatsapp" in keys)
        assertTrue("message" in keys)
    }

    @Test
    fun `a group message yields both the sender and the group`() {
        val parsed = AutoTagger.parseLabel(
            SourceType.NOTIFICATION,
            "Sharma Ji in Project Alpha - WhatsApp",
        )
        assertEquals("Sharma Ji", parsed.person)
        assertEquals("Project Alpha", parsed.group)
        assertEquals("WhatsApp", parsed.app)
    }

    @Test
    fun `the app suffix is split on the last separator, not the first`() {
        // A real group name with a dash in it must not be mistaken for the app.
        val parsed = AutoTagger.parseLabel(
            SourceType.NOTIFICATION,
            "Amit in Sales - North Region - WhatsApp",
        )
        assertEquals("Amit", parsed.person)
        assertEquals("Sales - North Region", parsed.group)
        assertEquals("WhatsApp", parsed.app)
    }

    @Test
    fun `a call yields the contact and no app`() {
        val parsed = AutoTagger.parseLabel(SourceType.CALL, "Call with Amit Kumar - 14:32")
        assertEquals("Amit Kumar", parsed.person)
        assertEquals(null, parsed.group)
        assertEquals(null, parsed.app)
    }

    @Test
    fun `a call label without a time still yields the contact`() {
        // The manual-import path writes this shorter form.
        val parsed = AutoTagger.parseLabel(SourceType.CALL, "Call with Amit Kumar")
        assertEquals("Amit Kumar", parsed.person)
    }

    @Test
    fun `an unresolved sender produces no person tag`() {
        val keys = keysOf(SourceType.NOTIFICATION, label = "Unknown - WhatsApp", title = "x")
        assertFalse("unknown" in keys)
    }

    @Test
    fun `a bare phone number is not a person tag`() {
        // Grouping by "+91 98765 43210" helps nobody.
        val parsed = AutoTagger.parseLabel(SourceType.CALL, "Call with +91 98765 43210 - 09:15")
        assertEquals(null, parsed.person)
    }

    @Test
    fun `a label in an unexpected shape yields no wrong tags`() {
        val parsed = AutoTagger.parseLabel(SourceType.NOTIFICATION, "something odd")
        assertEquals("something odd", parsed.person)
        assertEquals(null, parsed.group)
        assertEquals(null, parsed.app)
    }

    @Test
    fun `an empty label is handled`() {
        val parsed = AutoTagger.parseLabel(SourceType.NOTIFICATION, null)
        assertEquals(null, parsed.person)
        assertEquals(null, parsed.group)
        assertEquals(null, parsed.app)
    }

    // -- apps ----------------------------------------------------------------

    @Test
    fun `the business build of WhatsApp is named separately`() {
        val keys = keysOf(SourceType.NOTIFICATION, app = "com.whatsapp.w4b", label = "A - X", title = "t")
        assertTrue("whatsapp business" in keys)
    }

    @Test
    fun `an unmapped package falls back to the label's own app name`() {
        val keys = keysOf(SourceType.NOTIFICATION, app = "com.some.obscure.chat", label = "A B - Chatty", title = "t")
        assertTrue("chatty" in keys)
    }

    @Test
    fun `a package name is never used as a display tag`() {
        // The fallback rejects anything with a dot: "com.some.app" is not a name.
        val keys = keysOf(SourceType.NOTIFICATION, app = null, label = "A B - com.some.app", title = "t")
        assertFalse("com.some.app" in keys)
    }

    // -- topics --------------------------------------------------------------

    @Test
    fun `a payment is tagged from English`() {
        assertTrue("payment" in keysOf(SourceType.CALL, title = "Send the invoice for 50000"))
    }

    @Test
    fun `a payment is tagged from romanised Hindi`() {
        assertTrue("payment" in keysOf(SourceType.CALL, title = "Paise bhej dena kal tak"))
    }

    @Test
    fun `a payment is tagged from Devanagari`() {
        // Spoken Hindi comes back from the transcriber in this script.
        assertTrue("payment" in keysOf(SourceType.CALL, title = "कल तक भुगतान करना है"))
    }

    @Test
    fun `a meeting is tagged`() {
        assertTrue("meeting" in keysOf(SourceType.NOTIFICATION, title = "Meeting on Tuesday at 4"))
    }

    @Test
    fun `evidence counts towards the topic, not just the title`() {
        val keys = keysOf(
            SourceType.CALL,
            title = "Sort this out",
            evidence = "aap mujhe agreement bhej dijiye",
        )
        assertTrue("document" in keys)
    }

    @Test
    fun `at most two topics are given`() {
        // Deliberately matches payment, document, meeting and send at once.
        val keys = keysOf(
            SourceType.CALL,
            title = "Send the invoice and the agreement before the meeting",
        )
        val topics = keys.filter { it in listOf("payment", "document", "meeting", "send", "follow-up", "call-back") }
        assertEquals(2, topics.size)
    }

    @Test
    fun `the more specific topic wins when several match`() {
        val keys = keysOf(SourceType.CALL, title = "Send the invoice and the agreement before the meeting")
        assertTrue("payment" in keys)
    }

    @Test
    fun `a task with no recognisable topic simply gets none`() {
        val keys = keysOf(SourceType.MANUAL, title = "Think about it")
        assertEquals(listOf("manual"), keys)
    }

    // -- shape ---------------------------------------------------------------

    @Test
    fun `every source type produces a source tag`() {
        for (type in SourceType.entries) {
            val keys = keysOf(type, title = "x")
            assertTrue("no source tag for $type", keys.isNotEmpty())
        }
    }

    @Test
    fun `tags do not repeat`() {
        val keys = keysOf(
            SourceType.NOTIFICATION,
            app = "com.whatsapp",
            label = "WhatsApp - WhatsApp",
            title = "pay the bill, payment pending",
        )
        assertEquals(keys.size, keys.distinct().size)
    }

    // -- voice notes ---------------------------------------------------------

    @Test
    fun `a voice note is tagged voice, not as a person called Voice note`() {
        val keys = keysOf(
            SourceType.CLIPBOARD,
            label = AutoTagger.VOICE_LABEL,
            title = "Call the accountant about the invoice",
        )
        assertTrue("voice" in keys)
        assertFalse("voice note" in keys)
        // Still topic-tagged like anything else.
        assertTrue("payment" in keys)
    }

    @Test
    fun `an ordinary pasted transcript is not treated as a voice note`() {
        val keys = keysOf(SourceType.CLIPBOARD, label = "Amit - WhatsApp", title = "x")
        assertFalse("voice" in keys)
        assertTrue("pasted" in keys)
    }

    @Test
    fun `the person leads so it reads as the most useful tag first`() {
        val tags = AutoTagger.tags(
            SourceType.NOTIFICATION,
            "com.whatsapp",
            "Sharma Ji in Project Alpha - WhatsApp",
            "Send the invoice",
        )
        assertEquals(AutoTagger.Kind.PERSON, tags.first().kind)
        assertEquals(AutoTagger.Kind.GROUP, tags[1].kind)
    }
}
