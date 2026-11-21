package com.taskmind.core

import com.taskmind.core.LlmJson.dbl
import com.taskmind.core.LlmJson.str

/**
 * The quick check (v1.34): a short call to a small, cheap model that decides
 * whether a chat message is worth the full read at all.
 *
 * Most messages that pass the phone's own filter still hold no task - "ok",
 * "haha", a forward, a photo caption. The full read sends about 4,000 tokens
 * of instructions and context for each; this sends a few hundred. Only a
 * confident "nothing here" stops the message. "Task", "maybe", a low
 * confidence, a parse failure or any error all go on to the full read, so the
 * check can save tokens but should not lose tasks.
 */
object QuickCheck {

    enum class Verdict { NONE, TASK, MAYBE }

    data class Decision(val verdict: Verdict, val confidence: Double, val tokens: Int?)

    /** Below this, even a "none" goes on to the full read. Raised from 0.85 in v1.36.1. */
    const val SKIP_AT = 0.9

    /**
     * v1.36.1: messages the small model is never asked about, because the
     * phone can already see they might matter. A question, a number (a time,
     * a date, an amount, an invoice), or a word that usually carries an ask or
     * a plan - in English or Hinglish. These always get the full read; the
     * check is left only the plain chatter it is good at.
     */
    fun mustRead(text: String): Boolean {
        val t = text.lowercase()
        if ('?' in t || t.any { it.isDigit() }) return true
        val words = t.split(Regex("[^\\p{L}\\p{M}]+")).filter { it.isNotEmpty() }.toSet()
        if (words.any { it in CUES }) return true
        return PHRASES.any { it in t }
    }

    private val CUES = setOf(
        // asks and promises
        "please", "pls", "plz", "kindly", "can", "could", "will", "would", "need", "needs", "must", "should",
        "send", "share", "call", "pay", "paid", "transfer", "book", "confirm", "check", "review", "update",
        "remind", "reminder", "follow", "submit", "sign", "approve", "fix", "arrange", "bring", "buy", "order",
        "deliver", "delivery", "meet", "meeting", "deadline", "due", "urgent", "asap", "eod", "tomorrow", "today",
        "tonight", "morning", "evening", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "invoice", "payment", "quote", "quotation", "po", "bill", "rent", "fees", "appointment", "done", "sent",
        "cancel", "cancelled", "postpone", "reschedule", "moved",
        // Hinglish and Hindi
        "kal", "aaj", "parso", "abhi", "jaldi", "bhej", "bhejo", "bhejna", "bhejdo", "karo", "kardo", "karna",
        "kar", "dena", "lena", "dekh", "dekho", "batao", "bata", "bolo", "milte", "milna", "chahiye", "zaroor",
        "yaad", "bhejiye", "kijiye", "dijiye", "bhugtan", "paisa", "paise",
        "कल", "आज", "भेज", "भेजो", "करो", "कर", "देना", "चाहिए", "जल्दी", "बताओ", "याद",
    )

    private val PHRASES = listOf("let me know", "get back", "by end of", "kar dena", "bhej dena", "dekh lena", "yaad dila")

    /** One in this many confident "none"s is read in full anyway, to check the check. */
    const val AUDIT_EVERY = 5

    /** How many audits are remembered, and how many misses among them turn the check off. */
    const val AUDIT_WINDOW = 20
    const val AUDIT_MISSES_TO_STOP = 2

    /**
     * [history] is the recent audits, oldest first: '1' where the full read
     * found something the check would have skipped, '0' where it agreed.
     */
    fun shouldStop(history: String): Boolean =
        history.takeLast(AUDIT_WINDOW).count { it == '1' } >= AUDIT_MISSES_TO_STOP

    fun record(history: String, missed: Boolean): String = (history + if (missed) '1' else '0').takeLast(AUDIT_WINDOW)

    const val DEFAULT_GROQ_MODEL = "llama-3.1-8b-instant"
    private const val MAX_TEXT = 1500

    /** The model to use: the user's choice, else a small Groq model on Groq, else none. */
    fun modelFor(chosen: String, baseUrl: String): String? = when {
        chosen.isNotBlank() -> chosen.trim()
        baseUrl.contains("groq.com") -> DEFAULT_GROQ_MODEL
        else -> null
    }

    val SYSTEM: String = """
You screen ONE chat message for a personal task assistant. Decide whether the full assistant
needs to read it. Say "task" if it might hold any of these for the user: something someone asks
or expects them to do; a promise someone made to them; a meeting, call, date, deadline, payment,
bill or appointment that involves them; news that one of their open tasks is done, changed,
moved or cancelled. Say "none" only for messages that plainly hold none of that: greetings,
thanks, "ok"/"haan", jokes, reactions, forwards, news, ads, offers, OTPs and alerts, chit-chat.
When unsure, say "maybe". Messages may be in English, Hindi or Hinglish.

Return ONLY this JSON: {"verdict":"none|task|maybe","confidence":<0 to 1>}
""".trim()

    fun userPrompt(sender: String, group: String?, userNames: Set<String>, openTasks: List<String>, text: String): String = buildString {
        if (userNames.isNotEmpty()) append("The user is called: ").append(userNames.take(4).joinToString(", ")).append('\n')
        append("From: ").append(sender)
        group?.let { append(" (in the group \"").append(it).append("\")") }
        append('\n')
        if (openTasks.isNotEmpty()) {
            append("The user's open tasks in this chat:\n")
            openTasks.take(5).forEach { append("- ").append(it.take(80)).append('\n') }
        }
        append("Message:\n").append(text.take(MAX_TEXT))
    }

    fun parse(raw: String, tokens: Int? = null): Decision? {
        val obj = LlmJson.parseObject(raw) ?: return null
        val verdict = when (obj.str("verdict")?.trim()?.lowercase()) {
            "none", "no" -> Verdict.NONE
            "task", "yes" -> Verdict.TASK
            "maybe", "unsure" -> Verdict.MAYBE
            else -> return null
        }
        val confidence = (obj.dbl("confidence") ?: return null).coerceIn(0.0, 1.0)
        return Decision(verdict, confidence, tokens)
    }

    /** Whether the full read can be skipped. */
    fun skip(d: Decision?): Boolean = d != null && d.verdict == Verdict.NONE && d.confidence >= SKIP_AT
}
