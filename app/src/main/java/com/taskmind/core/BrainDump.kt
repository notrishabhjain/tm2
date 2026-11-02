package com.taskmind.core

import com.taskmind.core.LlmJson.bool
import com.taskmind.core.LlmJson.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Everything on your mind, said or pasted at once, turned into tasks.
 *
 * "pay electricity bill by friday, call Rahul about the rack quote, and buy
 * Diwali gifts for the team" becomes three tasks with dates and areas. The
 * same path imports a list from anywhere: paste or share it in.
 *
 * With a model: one call splits, titles, dates and files the lot. Without one
 * - no key, no consent, no network - [fallback] splits on lines and commas
 * and reads dates with [QuickAdd], so nothing said is lost.
 *
 * The user sees every item before anything is added.
 */
object BrainDump {

    /** Longest text sent; a pasted list past this is cut, not refused. */
    const val MAX_CHARS = 6000

    /** One task, ready to add. [dueRaw] is ISO with offset, or null. */
    data class Item(
        val title: String,
        val dueAt: Long?,
        val area: String?,
        val priority: Priority,
        val notes: String?,
        /** The user said it is for today ("today", "aaj", "right now"). */
        val today: Boolean,
    )

    val SYSTEM: String = """
You turn what a person said or pasted into their own to-do items. It may be a spoken
brain-dump in Hindi, English or Hinglish (with speech-recognition errors), or a list pasted
from another app. Every item is the person's own task: they said it, so do not judge whether it
is worth doing.

- One item per distinct action. Split "call Rahul and email Priya" into two. Merge exact repeats.
- Title: imperative, in English, at most 60 characters, keeping names, amounts and specifics.
- Resolve dates and times against the moment given. A date with no time means 18:00. All
  times are India Standard Time. No date stated means null - never invent one.
- area: one of the person's areas listed, or null when none clearly fits.
- priority: URGENT only for explicit urgency, HIGH for a deadline within 3 days or "important",
  LOW for "someday", "jab time mile", "maybe"; otherwise MEDIUM.
- today: true when they said it is for today ("today", "aaj", "right now", "first thing").
- notes: details worth keeping that are not in the title; null if none.
- Ignore filler ("um", "okay so", "and also") and anything that is not an action.

Return ONLY this JSON, no markdown:
{"items":[{"title":"...","dueDate":"<ISO 8601 with +05:30, or null>","area":"<area or null>","priority":"URGENT|HIGH|MEDIUM|LOW","today":true|false,"notes":null}]}
""".trim()

    fun userPrompt(text: String, now: Long, areas: List<String>): String = buildString {
        append("Now: ").append(DateResolver.formatForPrompt(now)).append(" IST\n")
        append(Areas.promptLine(areas))
        append("\nWHAT THEY SAID OR PASTED:\n")
        append(text.trim().take(MAX_CHARS))
    }

    /** The model's answer as items, or null when it could not be read. */
    fun parse(content: String, now: Long, areas: List<String>): List<Item>? {
        val obj = LlmJson.parseObject(content) ?: return null
        val rows = obj["items"] as? JsonArray ?: return null
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val title = row.str("title")?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Item(
                title = title,
                dueAt = DateResolver.resolve(row.str("dueDate"), now).dueAt,
                area = Areas.normalize(row.str("area"), areas),
                priority = Priority.parse(row.str("priority")),
                notes = row.str("notes")?.trim()?.takeIf { it.isNotEmpty() },
                today = row.bool("today") ?: false,
            )
        }.distinctBy { it.title.lowercase() }
    }

    /** Splits a list without a model: one item per line or bullet, dates read locally. */
    fun fallback(text: String, now: Long): List<Item> =
        text.take(MAX_CHARS)
            .split('\n', ';')
            .flatMap { line -> if (line.count { it == ',' } >= 2) line.split(',') else listOf(line) }
            .map { it.trim().trimStart('-', '*', '•', '[', ']', ' ').trim() }
            .map { it.replace(Regex("^\\d{1,2}[.)]\\s*"), "").removePrefix("x ").trim() }
            .filter { it.length >= 3 }
            .map { line ->
                val q = QuickAdd.parse(line, now)
                val title = q.title.ifBlank { line }
                Item(
                    title = title.replaceFirstChar { it.uppercase() }.take(120),
                    dueAt = q.dueAt,
                    area = null,
                    priority = Priority.MEDIUM,
                    notes = null,
                    today = Regex("\\b(today|aaj|tonight)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line),
                )
            }
            .distinctBy { it.title.lowercase() }
}
