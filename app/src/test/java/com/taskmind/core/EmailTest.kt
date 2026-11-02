package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailTest {

    private fun msg(text: String): Mime.Message = Mime.parse(text.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8))

    // ------------------------------------------------------------------ MIME

    @Test
    fun `plain mail with encoded subject and names`() {
        val m = msg(
            """
            From: "Sharma, Rahul" <rahul@acme.com>
            To: Rishabh <rishabh@gmail.com>, priya@acme.com
            Cc: Team: a@acme.com, b@acme.com;
            Subject: =?UTF-8?B?UXVvdGUgZm9yIHJhY2tzIOKCuQ==?=
            Date: Mon, 2 Nov 2026 10:15:30 +0530 (IST)
            Message-ID: <abc123@mail.acme.com>
            Content-Type: text/plain; charset=utf-8

            Hi Rishabh, can you send the PO by Friday?
            """,
        )
        assertEquals("Sharma, Rahul", m.from?.name)
        assertEquals("rahul@acme.com", m.from?.email)
        assertEquals(listOf("rishabh@gmail.com", "priya@acme.com"), m.to.map { it.email })
        assertEquals(listOf("a@acme.com", "b@acme.com"), m.cc.map { it.email })
        assertEquals("Quote for racks ₹", m.subject)
        assertEquals("abc123@mail.acme.com", m.messageId)
        assertEquals(1793594730000L, m.date)
        assertEquals("Hi Rishabh, can you send the PO by Friday?", m.text.trim())
    }

    @Test
    fun `q-encoded words join and underscores are spaces`() {
        assertEquals("Re: Bill due", Mime.decodeHeader("=?iso-8859-1?Q?Re:_Bill?= =?iso-8859-1?Q?_due?="))
        assertEquals("plain", Mime.decodeHeader("plain"))
    }

    @Test
    fun `folded headers are unfolded`() {
        val m = msg(
            """
            From: rahul@acme.com
            Subject: A long subject
             that wraps
            Content-Type: text/plain

            body
            """,
        )
        assertEquals("A long subject that wraps", m.subject)
    }

    @Test
    fun `alternative prefers plain and decodes quoted-printable`() {
        val m = msg(
            """
            From: rahul@acme.com
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8
            Content-Transfer-Encoding: quoted-printable

            Please pay =E2=82=B925,000 by to=
            morrow.
            --b1
            Content-Type: text/html; charset=UTF-8

            <p>Please pay &#8377;25,000 by tomorrow.</p>
            --b1--
            """,
        )
        assertEquals("Please pay ₹25,000 by tomorrow.", m.text.trim())
        assertNotNull(m.html)
    }

    @Test
    fun `html only base64 mail becomes text and loses the quoted thread`() {
        val html = "<html><head><style>p{}</style></head><body><div>Can you review the deck?<br>Thanks</div>" +
            "<div class=\"gmail_quote\">On Mon, Priya wrote: old stuff</div></body></html>"
        val b64 = java.util.Base64.getEncoder().encodeToString(html.toByteArray())
        val m = msg(
            """
            From: priya@acme.com
            Content-Type: text/html; charset=utf-8
            Content-Transfer-Encoding: base64

            $b64
            """,
        )
        val body = EmailText.body(m)
        assertTrue(body, body.startsWith("Can you review the deck?"))
        assertFalse(body, body.contains("old stuff"))
    }

    @Test
    fun `nested parts, attachments and calendar invites`() {
        val m = msg(
            """
            From: rahul@acme.com
            Content-Type: multipart/mixed; boundary="abc"

            --abc
            Content-Type: multipart/alternative; boundary="abc123"

            --abc123
            Content-Type: text/plain

            Agenda attached.
            --abc123--
            --abc
            Content-Type: application/pdf; name="agenda.pdf"
            Content-Disposition: attachment; filename="agenda.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0=
            --abc
            Content-Type: text/calendar; method=REQUEST

            BEGIN:VCALENDAR
            --abc--
            """,
        )
        assertEquals("Agenda attached.", m.text.trim())
        assertEquals(listOf("agenda.pdf"), m.attachments)
        assertTrue(m.hasCalendar)
    }

    @Test
    fun `dates in common shapes`() {
        assertNotNull(Mime.parseDate("2 Nov 2026 10:15 GMT"))
        assertNotNull(Mime.parseDate("Mon, 02 Nov 2026 04:45:30 -0000"))
        assertNull(Mime.parseDate("yesterday"))
    }

    @Test
    fun `html entities and lists`() {
        val text = Mime.htmlToText("<ul><li>Send PO</li><li>Call&nbsp;Rahul &amp; Priya</li></ul>")
        assertEquals("- Send PO\n- Call Rahul & Priya", text)
    }

    // ------------------------------------------------------------ cleaning

    @Test
    fun `gmail reply quote is cut`() {
        val text = "Sure, will send by Friday.\n\nOn Mon, 2 Nov 2026 at 10:15, Rahul <r@x.com> wrote:\n> can you send it?"
        assertEquals("Sure, will send by Friday.", EmailText.stripQuoted(text))
    }

    @Test
    fun `outlook reply block is cut`() {
        val text = "Approved, go ahead.\n\n________________________________\nFrom: Priya Nair\nSent: Monday\nSubject: PO"
        assertEquals("Approved, go ahead.", EmailText.stripQuoted(text))
        val noRule = "Please share the deck.\n\nFrom: Priya Nair <p@x.com>\nSent: Monday, 2 November\nTo: Rishabh"
        assertEquals("Please share the deck.", EmailText.stripQuoted(noRule))
    }

    @Test
    fun `signature and disclaimer are cut`() {
        val text = "Can you call me today?\n\nSent from my iPhone"
        assertEquals("Can you call me today?", EmailText.stripSignature(text))
        val legal = "Please sign the NDA.\n\nRegards,\nRahul\n\nThis email and any attachments are confidential and intended solely for the addressee."
        assertEquals("Please sign the NDA.\n\nRegards,\nRahul", EmailText.stripSignature(legal))
    }

    @Test
    fun `forwards keep the forwarded words and name their author`() {
        val body = "FYI\n\n---------- Forwarded message ---------\nFrom: Rahul Sharma <rahul@acme.com>\nDate: Mon\nSubject: PO\n\nPlease sign the PO today."
        assertTrue(EmailText.isForward("Fwd: PO", body))
        assertEquals("rahul@acme.com", EmailText.forwardedFrom(body)?.email)
        assertEquals("Rahul Sharma", EmailText.forwardedFrom(body)?.name)
    }

    @Test
    fun `subjects lose reply and forward prefixes`() {
        assertEquals("Quote for racks", EmailText.cleanSubject("RE: Fwd: [EXTERNAL] Quote for racks"))
        assertEquals("quote for racks", EmailText.threadKey("Re: Quote  for racks"))
    }

    @Test
    fun `capture text carries the subject so it can be quoted`() {
        val text = EmailText.forCapture(
            Mime.Address("Rahul", "r@x.com"), listOf(Mime.Address(null, "me@x.com")), emptyList(),
            "PO", "Send it by Friday", sent = false,
        )
        assertTrue(text.contains("Subject: PO"))
        assertTrue(text.endsWith("Send it by Friday"))
    }

    // -------------------------------------------------------------- filter

    private fun input(
        from: String = "rahul@acme.com",
        to: List<String> = listOf("me@gmail.com"),
        subject: String = "PO",
        body: String = "Can you send the PO by Friday?",
        headers: Map<String, String> = emptyMap(),
        folder: EmailFilter.Folder = EmailFilter.Folder.INBOX,
        onlyToMe: Boolean = true,
        calendar: Boolean = false,
    ) = EmailFilter.Input(
        folder = folder,
        from = Mime.Address(null, from),
        to = to.map { Mime.Address(null, it) },
        cc = emptyList(),
        subject = subject,
        body = body,
        headers = Mime.Headers(headers.toList()),
        hasCalendar = calendar,
        mine = setOf("me@gmail.com"),
        onlyToMe = onlyToMe,
    )

    @Test
    fun `a person's mail to you is read`() {
        assertTrue(EmailFilter.evaluate(input()) is EmailFilter.Verdict.Read)
    }

    @Test
    fun `newsletters and robots are skipped unless they ask for action`() {
        val news = EmailFilter.evaluate(input(headers = mapOf("List-Unsubscribe" to "<mailto:x>")))
        assertEquals("mailing list or newsletter", (news as EmailFilter.Verdict.Skip).rule)
        assertTrue(EmailFilter.evaluate(input(from = "no-reply@bank.com", body = "Your statement is ready")) is EmailFilter.Verdict.Skip)
        assertTrue(EmailFilter.evaluate(input(from = "no_reply@email.apple.com", body = "Receipt")) is EmailFilter.Verdict.Skip)
        val sign = EmailFilter.evaluate(input(from = "dse@docusign.net", subject = "Please sign: NDA", headers = mapOf("Auto-Submitted" to "auto-generated")))
        assertTrue(sign is EmailFilter.Verdict.Read)
    }

    @Test
    fun `invitations are left to the calendar`() {
        assertTrue(EmailFilter.evaluate(input(subject = "Invitation: Weekly sync @ Tue")) is EmailFilter.Verdict.Skip)
        assertTrue(EmailFilter.evaluate(input(calendar = true)) is EmailFilter.Verdict.Skip)
    }

    @Test
    fun `notes to self are read, your other mail in the inbox is not`() {
        assertTrue(EmailFilter.evaluate(input(from = "me@gmail.com", to = listOf("m.e+todo@gmail.com"))) is EmailFilter.Verdict.Read)
        assertTrue(EmailFilter.evaluate(input(from = "me@gmail.com", to = listOf("rahul@acme.com"))) is EmailFilter.Verdict.Skip)
    }

    @Test
    fun `sent mail is read only for promises`() {
        val promise = input(from = "me@gmail.com", to = listOf("rahul@acme.com"), body = "I'll send the revised quote by tomorrow.", folder = EmailFilter.Folder.SENT)
        assertTrue(EmailFilter.evaluate(promise) is EmailFilter.Verdict.Read)
        val thanks = promise.copy(body = "Thanks, received.")
        assertTrue(EmailFilter.evaluate(thanks) is EmailFilter.Verdict.Skip)
        assertTrue(EmailFilter.looksLikePromise("kal tak bhej dunga"))
    }

    @Test
    fun `mail not addressed to you is skipped when asked`() {
        val list = input(to = listOf("all-staff@acme.com"))
        assertTrue(EmailFilter.evaluate(list) is EmailFilter.Verdict.Skip)
        assertTrue(EmailFilter.evaluate(list.copy(onlyToMe = false)) is EmailFilter.Verdict.Read)
        assertTrue(EmailFilter.evaluate(input(to = listOf("M.E@gmail.com"))) is EmailFilter.Verdict.Read)
    }

    @Test
    fun `labelled mail is always read`() {
        val labelled = input(headers = mapOf("List-Id" to "x"), folder = EmailFilter.Folder.LABEL)
        assertTrue(EmailFilter.evaluate(labelled) is EmailFilter.Verdict.Read)
    }

    @Test
    fun `the capture label round-trips and keeps the thread`() {
        val label = EmailText.contextLabel("Rahul | Sharma", "r@x.com", "RE: Quote for racks", sent = true)
        val meta = EmailText.parseContext(label)
        assertEquals("Rahul / Sharma", meta.sender)
        assertEquals("r@x.com", meta.address)
        assertEquals("Quote for racks", meta.subject)
        assertEquals("quote for racks", meta.thread)
        assertTrue(meta.sent)
        assertEquals("Rahul / Sharma", ChatContext.parseLabel(label).sender)
        assertNull(ChatContext.parseLabel(label).group)
        assertEquals("Body here", EmailText.bodyOf("From: a\nSubject: b\n\nBody here"))
    }

    @Test
    fun `the email prompt asks for what the parser reads and grounds its quotes`() {
        val prompt = Prompts.DEFAULT_EMAIL_SYSTEM
        listOf("\"tasks\"", "\"evidence\"", "assignedToMe", "confidence", "GROUNDING", "\"done\"").forEach {
            assertTrue("email prompt must mention $it", prompt.contains(it))
        }
        assertEquals(Prompts.DEFAULT_EMAIL_SYSTEM, PromptKind.EMAIL.defaultText())
        val user = Prompts.emailUser(0L, "Gmail", "From: Rahul\nSubject: PO\n\nSign it", setOf("Rishabh"), setOf("me@gmail.com"))
        assertTrue(user.contains("The user's addresses: me@gmail.com"))
        assertTrue(user.contains("Email via Gmail:"))
        assertTrue(user.trimEnd().endsWith("Sign it"))
    }

    // --------------------------------------------------------- notifications

    @Test
    fun `a mail notification becomes one email`() {
        val n = EmailApps.read("Rahul Sharma", "Quote for racks", "Quote for racks\nHi, can you send the PO by Friday?", "me@gmail.com")
        assertEquals("Rahul Sharma", n?.sender)
        assertEquals("Quote for racks", n?.subject)
        assertEquals("Hi, can you send the PO by Friday?", n?.body)
        assertEquals("me@gmail.com", n?.account)
        assertTrue(EmailApps.captureText(n!!).contains("Subject: Quote for racks"))
    }

    @Test
    fun `roll-ups are not emails`() {
        assertNull(EmailApps.read("3 new messages", "Rahul, Priya", null, null))
        assertNull(EmailApps.read("Rahul", null, null, null))
        assertTrue(EmailApps.isEmailApp("com.google.android.gm"))
        assertFalse(EmailApps.isEmailApp("com.whatsapp"))
    }
}
