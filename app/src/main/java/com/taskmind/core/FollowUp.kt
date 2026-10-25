package com.taskmind.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a follow-up message does to a task that already exists.
 *
 * "Any update on the PO?", "can you make it Friday instead" and "got it,
 * thanks" used to arrive as three new tasks, or as nothing. With the chat
 * context the model can say which open task a message is about; this decides
 * what that means for the task: a new due date if one was stated, the higher
 * of the two priorities, and a dated line in the notes quoting what was said,
 * so the history of a task reads in one place.
 *
 * Pure - the caller reads the task, applies the patch and writes it back.
 */
object FollowUp {

    /** The tag a task gets when someone's message suggests it is already done. */
    const val MAYBE_DONE_TAG = "maybe-done"

    /** Longest quote kept in a follow-up note. The evidence span is short anyway. */
    private const val MAX_QUOTE = 160

    /** Notes past this are trimmed from the oldest follow-up line. */
    const val MAX_NOTES = 2000

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    data class Current(
        val notes: String?,
        val dueAt: Long?,
        val priority: Priority,
        val tags: List<String>,
        val reminderAt: Long?,
    )

    data class Patch(
        val notes: String?,
        val dueAt: Long?,
        val priority: Priority,
        val tags: List<String>,
        val reminderAt: Long?,
        /** What changed, for the activity log. Empty when nothing did. */
        val changes: List<String>,
    )

    /**
     * A message that adds to an open task. [newDueAt] is the resolved due date
     * the message stated, or null when it stated none - in which case the
     * existing one stands.
     */
    fun update(
        current: Current,
        newDueAt: Long?,
        priority: Priority,
        who: String,
        quote: String?,
        at: Long,
        now: Long,
    ): Patch {
        val changes = mutableListOf<String>()
        var dueAt = current.dueAt
        var reminderAt = current.reminderAt
        if (newDueAt != null && newDueAt != current.dueAt) {
            dueAt = newDueAt
            // The reminder follows the due date. One the user set by hand to
            // something unrelated is lost; a stale reminder for a date that
            // no longer applies would be worse.
            reminderAt = SmartReminder.forDue(newDueAt, now)
            changes += "due date"
        }
        val higher = if (priority.ordinal < current.priority.ordinal) priority else current.priority
        if (higher != current.priority) changes += "priority"
        val line = noteLine(at, who, quote)
        val notes = appendLine(current.notes, line)
        if (notes != current.notes) changes += "note"
        // A fresh nudge means it is not done, whatever an earlier message hinted.
        val tags = current.tags.filterNot { it == MAYBE_DONE_TAG }
        if (tags.size != current.tags.size) changes += "cleared maybe-done"
        return Patch(notes, dueAt, higher, tags, reminderAt, changes)
    }

    /** A message that suggests an open task has been taken care of. */
    fun maybeDone(current: Current, who: String, quote: String?, at: Long): Patch {
        val tags = if (MAYBE_DONE_TAG in current.tags) current.tags else current.tags + MAYBE_DONE_TAG
        val notes = appendLine(current.notes, noteLine(at, who, quote) + " - maybe done?")
        val changes = if (tags.size != current.tags.size) listOf("maybe done") else emptyList()
        return Patch(notes, current.dueAt, current.priority, tags, current.reminderAt, changes)
    }

    /** "[Sun 25 Oct] Priya: "any update on the PO"" */
    fun noteLine(at: Long, who: String, quote: String?): String = buildString {
        append('[').append(Instant.ofEpochMilli(at).atZone(DateResolver.IST).format(DAY)).append("] ")
        append(who.ifBlank { "Someone" })
        val q = quote?.replace('\n', ' ')?.trim()?.take(MAX_QUOTE)
        if (!q.isNullOrEmpty()) append(": \"").append(q).append('"') else append(" followed up")
    }

    /**
     * Adds [line] on its own line. The same line twice (a notification
     * re-delivered) is added once. Past [MAX_NOTES] the oldest follow-up lines
     * go first; whatever was written before them stays.
     */
    fun appendLine(notes: String?, line: String): String {
        val existing = notes?.trimEnd().orEmpty()
        if (existing.lines().any { it.trim() == line.trim() }) return existing
        var result = if (existing.isEmpty()) line else "$existing\n$line"
        while (result.length > MAX_NOTES) {
            val lines = result.lines()
            val firstFollowUp = lines.indexOfFirst { it.startsWith("[") }
            if (firstFollowUp < 0 || firstFollowUp == lines.lastIndex) break
            result = (lines.take(firstFollowUp) + lines.drop(firstFollowUp + 1)).joinToString("\n")
        }
        return result
    }
}
