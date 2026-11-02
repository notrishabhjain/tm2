package com.taskmind.core

/**
 * Turns an email into the few lines worth reading: the new words in it, not
 * the twenty earlier messages quoted beneath them, the signature or the legal
 * footer.
 *
 * A reply quotes its whole thread. Sending that to the model would cost a
 * long prompt per message and, worse, make every old request in the thread
 * look new again. So the quoted history is cut at the marker the mail client
 * left ("On Mon, Rahul wrote:", "-----Original Message-----", an Outlook
 * "From: ... Sent: ..." block). A forward is the exception: there the quoted
 * message IS the content, and it is kept.
 *
 * Pure, and tested on the shapes Gmail, Outlook and Apple Mail produce.
 */
object EmailText {

    /** Most of an email body sent on. A real ask is in the first screen, not the tenth. */
    const val MAX_BODY = 3000

    private val SUBJECT_PREFIX = Regex(
        "^\\s*((re|fw|fwd|aw|wg|sv|vs|tr|rif|r)\\s*(\\[\\d+])?\\s*:\\s*|\\[(external|ext|ext\\.?)]\\s*)+",
        RegexOption.IGNORE_CASE,
    )
    private val FORWARD_PREFIX = Regex("^\\s*(fw|fwd|tr|wg)\\s*:", RegexOption.IGNORE_CASE)

    /** "RE: Fwd: [EXTERNAL] Quote for racks" -> "Quote for racks". */
    fun cleanSubject(subject: String): String = subject.replace(SUBJECT_PREFIX, "").trim()

    /** The key that groups one thread's messages: the clean subject, lowercased. */
    fun threadKey(subject: String): String =
        cleanSubject(subject).lowercase().replace(Regex("\\s+"), " ").take(120)

    private val FORWARD_MARKER = Regex(
        "^\\s*(-{5,}\\s*forwarded message\\s*-{5,}|begin forwarded message:|-{5,}\\s*original message\\s*-{5,})",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    fun isForward(subject: String, body: String): Boolean =
        FORWARD_PREFIX.containsMatchIn(subject) ||
            Regex("^\\s*(-{5,}\\s*forwarded message|begin forwarded message:)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                .containsMatchIn(body)

    /**
     * Who wrote the message being forwarded, from the "From:" line of the
     * forwarded block. The forwarder is usually the user themselves; the
     * person whose words these are is the one that matters.
     */
    fun forwardedFrom(body: String): Mime.Address? {
        val marker = FORWARD_MARKER.find(body) ?: return null
        val after = body.substring(marker.range.last + 1).lines().take(12)
        val line = after.firstOrNull { Regex("^\\s*\\*?from:\\*?\\s", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: return null
        val value = line.substringAfter(':').replace("*", "").trim()
        return Mime.addresses(value.replace("[mailto:", "<").replace("]", ">")).firstOrNull()
            ?: value.takeIf { it.isNotBlank() }?.let { Mime.Address(it.substringBefore('<').trim().ifEmpty { null }, it) }
    }

    // ---------------------------------------------------------- quote cutting

    private val QUOTE_HEADERS = listOf(
        // Gmail, Apple Mail, Thunderbird: "On Mon, 2 Nov 2026 at 10:15, Rahul <r@x.com> wrote:"
        Regex("^\\s*on\\s.{4,200}?\\swrote:\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        // The same, wrapped over two lines by the client.
        Regex("^\\s*on\\s[^\\n]{4,160}\\n[^\\n]{0,160}wrote:\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("^\\s*-{5,}\\s*original message\\s*-{5,}\\s*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        // Outlook: a rule of underscores, then From: ... Sent: ...
        Regex("^\\s*_{10,}\\s*$\\n\\s*from:", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        // Outlook without the rule: a From: line followed within a line or two by Sent: or Date:
        Regex("^\\s*\\*?from:\\*?\\s.+\\n(\\s*\\*?(to|cc):\\*?.*\\n)?\\s*\\*?(sent|date):\\*?\\s", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        // Hindi-language Gmail.
        Regex("^.{4,200}\\sने लिखा:\\s*$", RegexOption.MULTILINE),
    )

    /** Drops the quoted history beneath a reply, and any ">" quoted lines left. */
    fun stripQuoted(text: String): String {
        var cut = text
        val at = QUOTE_HEADERS.mapNotNull { it.find(cut)?.range?.first }.filter { it > 0 }.minOrNull()
        if (at != null) cut = cut.substring(0, at)
        return cut.lines().filterNot { it.trimStart().startsWith(">") }.joinToString("\n").trim()
    }

    // --------------------------------------------------- signatures, footers

    private val SIGNATURE_START = listOf(
        // The standard delimiter.
        Regex("^--\\s*$", RegexOption.MULTILINE),
        Regex("^\\s*sent from my (iphone|ipad|android|samsung|mobile|phone|galaxy|oneplus|xiaomi|redmi)[^\\n]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("^\\s*(get|download) outlook for (ios|android)[^\\n]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("^\\s*sent (from|with|via) (outlook|gmail|yahoo mail|proton mail|spark)[^\\n]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
    )

    private val DISCLAIMER = Regex(
        "(this (e-?mail|message|communication)( and any (attachments|files)[^.]*)? (is|are|may be|contains?) (strictly )?(confidential|privileged|intended))|" +
            "(the information (contained )?in this (e-?mail|message)[^.]* confidential)|" +
            "(if you (are not|have received this)[^.]{0,80}(intended recipient|in error))|" +
            "(disclaimer\\s*:)",
        RegexOption.IGNORE_CASE,
    )

    /** Cuts the signature and any legal footer. Never cuts into the first lines of a short mail. */
    fun stripSignature(text: String): String {
        var cut = text
        val at = SIGNATURE_START.mapNotNull { it.find(cut)?.range?.first }.filter { it > 0 }.minOrNull()
        if (at != null) cut = cut.substring(0, at)
        // A disclaimer paragraph and everything after it.
        val paragraphs = cut.split(Regex("\\n\\s*\\n"))
        val firstLegal = paragraphs.indexOfFirst { DISCLAIMER.containsMatchIn(it) }
        if (firstLegal > 0) cut = paragraphs.take(firstLegal).joinToString("\n\n")
        return cut.trim()
    }

    /**
     * The readable body of [message]: HTML or plain, without the quoted
     * thread unless it is a forward, without signature and footer, capped.
     */
    fun body(message: Mime.Message): String {
        val forward = isForward(message.subject, message.plain ?: message.html.orEmpty())
        val raw = message.plain?.takeIf { it.isNotBlank() }
            ?: message.html?.let { Mime.htmlToText(it, cutQuotes = !forward) }.orEmpty()
        val withoutQuotes = if (forward) raw else stripQuoted(raw)
        val clean = Mime.tidy(stripSignature(withoutQuotes))
        return if (clean.length <= MAX_BODY) clean else clean.take(MAX_BODY).substringBeforeLast(' ') + " …"
    }

    /**
     * What is stored and sent: who, to whom, the subject, then the body.
     * The subject is part of the text so its words can be quoted as evidence.
     */
    fun forCapture(
        from: Mime.Address?,
        to: List<Mime.Address>,
        cc: List<Mime.Address>,
        subject: String,
        body: String,
        sent: Boolean,
        attachments: List<String> = emptyList(),
    ): String = buildString {
        if (sent) append("Email YOU sent\n")
        append("From: ").append(from?.let(::show) ?: "unknown").append('\n')
        if (to.isNotEmpty()) append("To: ").append(to.take(6).joinToString(", ") { show(it) }).append(if (to.size > 6) ", …" else "").append('\n')
        if (cc.isNotEmpty()) append("Cc: ").append(cc.take(6).joinToString(", ") { show(it) }).append(if (cc.size > 6) ", …" else "").append('\n')
        append("Subject: ").append(subject.ifBlank { "(no subject)" }).append('\n')
        if (attachments.isNotEmpty()) append("Attachments: ").append(attachments.take(5).joinToString(", ")).append('\n')
        append('\n').append(body)
    }

    private fun show(a: Mime.Address): String = if (a.name.isNullOrBlank()) a.email else "${a.name} <${a.email}>"

    // ------------------------------------------------- the capture's label

    /**
     * What an email capture remembers about where it came from, in the same
     * "key=value|key=value" shape as a chat's context label, so the chat
     * memory reads [sender] exactly as it reads a WhatsApp sender.
     */
    data class Meta(
        val sender: String,
        val address: String?,
        val subject: String?,
        val thread: String?,
        /** The user wrote it (read from the sent folder). */
        val sent: Boolean,
    )

    fun contextLabel(sender: String, address: String?, subject: String, sent: Boolean): String = buildString {
        append("sender=").append(safe(sender.ifBlank { address ?: "Unknown" }))
        address?.takeIf { it.isNotBlank() }?.let { append("|email=").append(safe(it)) }
        if (subject.isNotBlank()) {
            append("|subject=").append(safe(cleanSubject(subject).take(140)))
            append("|thread=").append(safe(threadKey(subject)))
        }
        append("|sent=").append(sent)
        append("|isGroup=false")
    }

    fun parseContext(raw: String?): Meta {
        val parts = raw.orEmpty().split('|').associate { part ->
            part.substringBefore('=') to part.substringAfter('=', "")
        }
        return Meta(
            sender = parts["sender"]?.takeIf { it.isNotBlank() } ?: "Unknown",
            address = parts["email"]?.takeIf { it.isNotBlank() },
            subject = parts["subject"]?.takeIf { it.isNotBlank() },
            thread = parts["thread"]?.takeIf { it.isNotBlank() },
            sent = parts["sent"] == "true",
        )
    }

    /** The body of a capture made by [forCapture]: everything after the header lines. */
    fun bodyOf(captureText: String): String {
        val blank = captureText.indexOf("\n\n")
        return if (blank < 0) captureText else captureText.substring(blank + 2).trim()
    }

    private fun safe(value: String): String = value.replace('|', '/').replace('\n', ' ').trim()
}
