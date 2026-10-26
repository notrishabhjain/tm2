package com.taskmind.core

/**
 * What the user did with what TaskMind gave them, and who it came from.
 *
 * Every "Not mine", every digest item dropped or kept, every captured task
 * deleted or finished, with the sender and the group. Nothing else: no
 * titles, no text. This is the raw material for the rule ideas in [Rules] -
 * "you dropped everything from Ops Alerts" is a pattern only a record like
 * this can show.
 *
 * Pure: the events, how they are written to a line and read back, and which
 * ones count for and against a sender.
 */
object Feedback {

    enum class Kind(val positive: Boolean) {
        /** Told the app a task was somebody else's. The strongest "no". */
        NOT_MINE(false),
        /** Deleted a captured task within a day of getting it. */
        DELETED_QUICKLY(false),
        /** Dropped a maybe from the digest. */
        DISMISSED(false),
        /** Kept a maybe from the digest. */
        KEPT(true),
        /** Finished a captured task. */
        COMPLETED(true),
    }

    data class Event(val at: Long, val kind: Kind, val person: String?, val group: String?)

    /** Most events kept; the oldest go first. Rules only look back [Rules.WINDOW_DAYS]. */
    const val MAX_EVENTS = 1000

    /** Within this of being created, a delete says "this should not have been a task". */
    const val QUICK_DELETE_MILLIS = 24L * 60 * 60 * 1000

    private const val SEP = '\u001F'

    fun encode(e: Event): String =
        listOf(e.at.toString(), e.kind.name, clean(e.person), clean(e.group)).joinToString(SEP.toString())

    fun decode(line: String): Event? {
        val parts = line.split(SEP)
        if (parts.size != 4) return null
        val at = parts[0].toLongOrNull() ?: return null
        val kind = Kind.entries.firstOrNull { it.name == parts[1] } ?: return null
        return Event(at, kind, parts[2].ifBlank { null }, parts[3].ifBlank { null })
    }

    private fun clean(s: String?): String = s?.replace(SEP, ' ')?.replace('\n', ' ')?.trim().orEmpty()

    /** A delete counts against the sender only if it came quickly. */
    fun forDelete(createdAt: Long, now: Long): Kind? =
        if (now - createdAt <= QUICK_DELETE_MILLIS) Kind.DELETED_QUICKLY else null
}
