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

    /** Below this, even a "none" goes on to the full read. */
    const val SKIP_AT = 0.85

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
