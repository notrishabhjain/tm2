package com.taskmind.core

/**
 * Email that arrives as a notification from a mail app.
 *
 * The zero-setup path: no password, no connector, and it works for every
 * provider the mail app does - Gmail, Workspace, Outlook, Microsoft 365. The
 * cost is that a notification carries only the start of the mail. For most
 * asks ("Can you send the revised deck by Thursday?") the start is enough.
 *
 * A mail notification is shaped differently from a chat one: the title is the
 * sender, the text is the subject, and the expanded text is the subject
 * followed by the opening lines. [read] turns that into one email.
 */
object EmailApps {

    /** Mail apps by package, with the name shown in labels. */
    val PACKAGES: Map<String, String> = linkedMapOf(
        "com.google.android.gm" to "Gmail",
        "com.microsoft.office.outlook" to "Outlook",
        "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
        "com.samsung.android.email.provider" to "Samsung Email",
        "ch.protonmail.android" to "Proton Mail",
        "me.proton.android.mail" to "Proton Mail",
        "com.readdle.spark" to "Spark",
        "me.bluemail.mail" to "BlueMail",
        "com.zoho.mail" to "Zoho Mail",
        "com.easilydo.mail" to "Edison Mail",
        "com.fastmail.app" to "Fastmail",
        "com.basecamp.hey" to "HEY",
        "net.thunderbird.android" to "Thunderbird",
        "com.fsck.k9" to "K-9 Mail",
        "org.kman.AquaMail" to "Aqua Mail",
        "ru.mail.mailapp" to "myMail",
        "com.superhuman.mail" to "Superhuman",
        "com.google.android.gm.lite" to "Gmail Go",
    )

    fun isEmailApp(packageName: String?): Boolean = packageName != null && packageName in PACKAGES

    fun label(packageName: String?): String? = packageName?.let { PACKAGES[it] }

    /** One mail as its notification shows it. */
    data class Notified(val sender: String, val subject: String, val body: String, val account: String?)

    private val COUNT_SUFFIX = Regex("\\s*\\(\\d+\\)$")
    private val SUMMARY_TITLE = Regex("^\\d+\\s+(new\\s+)?(messages?|emails?|mails?|conversations?)\\b", RegexOption.IGNORE_CASE)

    /**
     * Reads a mail notification. Null for a roll-up ("3 new messages") or one
     * with nothing to read.
     */
    fun read(title: String?, text: String?, bigText: String?, subText: String?): Notified? {
        val sender = title?.replace(COUNT_SUFFIX, "")?.trim().orEmpty()
        if (sender.isEmpty() || SUMMARY_TITLE.containsMatchIn(sender)) return null
        val short = text?.trim().orEmpty()
        val long = bigText?.trim().orEmpty()
        if (short.isEmpty() && long.isEmpty()) return null

        // Usually: text = subject, bigText = subject + newline + snippet.
        val subject: String
        val body: String
        when {
            long.isNotEmpty() && short.isNotEmpty() && long.startsWith(short) -> {
                subject = short.lineSequence().first().trim()
                body = long.removePrefix(short).trim()
            }
            long.isNotEmpty() -> {
                val lines = long.lines()
                subject = if (short.isNotEmpty()) short.lineSequence().first().trim() else lines.first().trim()
                body = if (short.isNotEmpty()) long else lines.drop(1).joinToString("\n").trim()
            }
            else -> {
                val lines = short.lines()
                subject = lines.first().trim()
                body = lines.drop(1).joinToString("\n").trim()
            }
        }
        return Notified(
            sender = sender,
            subject = subject,
            body = body,
            account = subText?.trim()?.takeIf { it.contains('@') },
        )
    }

    /** The capture text: the same shape as an email read over IMAP, so one prompt reads both. */
    fun captureText(n: Notified): String = buildString {
        append("From: ").append(n.sender).append('\n')
        n.account?.let { append("To: ").append(it).append('\n') }
        append("Subject: ").append(n.subject.ifBlank { "(no subject)" }).append('\n')
        append('\n').append(n.body.ifBlank { n.subject })
    }
}
