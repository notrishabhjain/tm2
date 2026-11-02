package com.taskmind.core

import com.taskmind.core.LlmJson.str
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Chasing the people who owe you something - drafted, never sent.
 *
 * A waiting-on task ("Check Rahul sent the quote") that has gone past its
 * date, or sat for a few days with none, is worth a nudge. This finds them,
 * and drafts the message: with the model when it can (in the person's own
 * language and tone), from a template when it cannot. The user reads it,
 * edits it if they like, and sends it themselves from WhatsApp or mail.
 *
 * Pure.
 */
object Nudge {

    data class Candidate(
        val taskId: String,
        val person: String,
        val title: String,
        val evidence: String?,
        /** When the promise was made: the capture's time. */
        val since: Long,
        val dueAt: Long?,
        /** "WhatsApp", "Email"... where it was said, when known. */
        val channel: String?,
    ) {
        fun overdue(now: Long): Boolean = dueAt != null && dueAt < now
    }

    /** A promise with no date is worth chasing after this long. */
    const val WAIT_DAYS = 2L
    /** After a nudge, leave it this long before suggesting another. */
    const val QUIET_DAYS = 3L
    private const val DAY = 24L * 60 * 60 * 1000

    /** Waiting-on tasks worth a nudge now, the longest-waiting first. */
    fun candidates(tasks: List<Twin.Task>, nudgedAt: Map<String, Long>, now: Long, max: Int = 6): List<Candidate> =
        tasks.asSequence()
            .filter { it.state == Twin.State.OPEN && it.waiting }
            .filter { t -> (t.dueAt != null && t.dueAt < now) || (t.dueAt == null && t.createdAt < now - WAIT_DAYS * DAY) }
            .filter { t -> (nudgedAt[t.id] ?: 0L) < now - QUIET_DAYS * DAY }
            .mapNotNull { t ->
                val person = person(t.from) ?: return@mapNotNull null
                Candidate(t.id, person, t.title, t.evidence, t.createdAt, t.dueAt, channel(t.from))
            }
            .sortedBy { it.dueAt ?: it.since }
            .take(max)
            .toList()

    /** "Priya (WhatsApp)" -> "Priya"; "Call with Rahul" -> "Rahul"; null when there is nobody. */
    fun person(from: String?): String? {
        val text = from?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = text.removePrefix("Call with ").substringBefore(" (").substringBefore(" in ").trim()
        return name.takeIf { it.length >= 2 && !it.equals("unknown", true) && !it.all { c -> c.isDigit() || c in "+- ()" } }
    }

    private fun channel(from: String?): String? = from?.substringAfter(" (", "")?.substringBefore(')')?.takeIf { it.isNotBlank() }

    private val HINGLISH = Regex(
        "\\b(hai|hain|tha|thi|kar|karna|karunga|karungi|dunga|dungi|denge|bhej|bhejna|kal|aaj|parso|abhi|" +
            "haan|nahi|nhi|bhai|ji|kya|kab|mein|main|hum|aap|tum|wala|wali|dena|lena|ho jayega|bata|batana)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun looksHinglish(text: String?): Boolean = text != null && HINGLISH.findAll(text).count() >= 2

    /** The template draft, for when the model is not available. */
    fun draft(c: Candidate, now: Long): String {
        val first = c.person.substringBefore(' ').ifBlank { c.person }
        val what = c.evidence?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
        val ago = ago(c.since, now)
        return if (looksHinglish(c.evidence)) {
            buildString {
                append("Hi $first, ")
                if (what != null) append("$ago aapne bola tha: \"$what\". ") else append("${c.title} - ")
                append("Koi update? Thanks!")
            }
        } else {
            buildString {
                append("Hi $first, just following up")
                if (what != null) append(" on this from $ago: \"$what\".") else append(": ${c.title}.")
                append(" Any update?")
            }
        }
    }

    /** "today", "yesterday", "3 days ago", "on Mon 26 Oct". */
    fun ago(at: Long, now: Long): String {
        val days = (DateResolver.startOfDay(now) - DateResolver.startOfDay(at)) / DAY
        return when {
            days <= 0 -> "today"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            else -> "on " + Instant.ofEpochMilli(at).atZone(DateResolver.IST).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        }
    }

    val SYSTEM: String = """
You write ONE short follow-up message that the user will send, themselves, to someone who
promised them something and has not delivered yet.

Two or three short lines. Warm, polite and specific: say what was promised and when. Never
pushy, never passive-aggressive, no guilt. Write it AS the user, in the language and tone of
the person's own words in the quote - Hinglish if they wrote Hinglish, plain English if they
wrote English. No placeholders, no signature, no subject line.

Return ONLY this JSON, no markdown: {"text":"<the message>"}
""".trim()

    fun userPrompt(c: Candidate, now: Long, aboutMe: String = ""): String = buildString {
        append(Prompts.aboutBlock(aboutMe))
        append("Now: ").append(DateResolver.formatForPrompt(now)).append(" IST\n")
        append("To: ").append(c.person).append('\n')
        c.channel?.let { append("Where they said it: ").append(it).append('\n') }
        append("The task: ").append(c.title).append('\n')
        c.evidence?.takeIf { it.isNotBlank() }?.let { append("Their words: \"").append(it).append("\" (").append(ago(c.since, now)).append(")\n") }
        c.dueAt?.let { append("It was due: ").append(DateResolver.formatForPrompt(it)).append(" IST\n") }
    }

    fun parse(raw: String): String? =
        LlmJson.parseObject(raw)?.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.take(700)

    /**
     * A number WhatsApp's link accepts: digits only, with the country code. A
     * ten-digit Indian mobile gets 91 in front; a leading 0 is dropped first.
     */
    fun whatsappNumber(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() } ?: return null
        val trimmed = digits.trimStart('0')
        return when {
            trimmed.length == 10 -> "91$trimmed"
            trimmed.length in 11..15 -> trimmed
            else -> null
        }
    }
}
