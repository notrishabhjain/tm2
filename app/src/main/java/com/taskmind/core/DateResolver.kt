package com.taskmind.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Spec 14.1 - date and time handling.
 *
 * The timezone is Asia/Kolkata, always. Not the device default: a user who
 * travels must not have last week's "kal tak" silently reinterpreted.
 *
 * Relative expressions are resolved by the model against `occurredAt` (when the
 * message arrived or the call happened), never against "now" - a notification
 * processed the next morning after a backlog drain must still treat "kal" as
 * the day after the message arrived. This object's job is to police what comes
 * back.
 */
object DateResolver {

    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** A date with no time means 18:00 IST (spec 14.1). */
    const val DEFAULT_HOUR = 18

    private const val TWELVE_HOURS_MILLIS = 12L * 60 * 60 * 1000
    private const val TWO_YEARS_MILLIS = 2L * 365 * 24 * 60 * 60 * 1000

    private val PROMPT_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    /**
     * @param dueAt resolved deadline, or null when the model gave none or gave
     *   one we refuse to trust.
     * @param noteSuffix text to append to the task's notes when a stated
     *   deadline had to be dropped. Spec 14.1: keep the task, keep the words,
     *   never silently shift the date.
     * @param reason for the activity log.
     */
    data class Resolved(
        val dueAt: Long?,
        val noteSuffix: String?,
        val reason: String,
    )

    /** Formats `occurredAt` the way the extraction prompts expect it. */
    fun formatForPrompt(occurredAtMillis: Long): String =
        Instant.ofEpochMilli(occurredAtMillis).atZone(IST).format(PROMPT_FORMAT)

    /**
     * Parses a model-returned ISO-8601 due date and sanity-checks it against the
     * moment the source text was produced.
     */
    fun resolve(raw: String?, occurredAtMillis: Long): Resolved {
        if (raw.isNullOrBlank() || raw.trim().equals("null", ignoreCase = true)) {
            return Resolved(null, null, "no date stated")
        }
        val parsed = parseIso(raw.trim())
            ?: return Resolved(null, null, "unparseable date '$raw' - treated as null, not guessed")

        // Resolved wrong by the model. Keep the task and the words; never shift
        // the date forward a year to make it look sane.
        if (parsed < occurredAtMillis - TWELVE_HOURS_MILLIS) {
            return Resolved(
                dueAt = null,
                noteSuffix = "stated deadline: ${raw.trim()}",
                reason = "date more than 12h before the source moment - dropped, kept in notes",
            )
        }
        if (parsed > occurredAtMillis + TWO_YEARS_MILLIS) {
            return Resolved(
                dueAt = null,
                noteSuffix = "stated deadline: ${raw.trim()}",
                reason = "date more than 2 years out - dropped, kept in notes",
            )
        }
        return Resolved(parsed, null, "ok")
    }

    /**
     * Accepts, in order: a full offset date-time, a local date-time (assumed
     * IST), and a bare date (assumed 18:00 IST).
     */
    fun parseIso(raw: String): Long? {
        val s = raw.trim().removeSurrounding("\"")
        if (s.isEmpty()) return null

        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return ZonedDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return LocalDateTime.parse(s).atZone(IST).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        try {
            return LocalDate.parse(s).atTime(DEFAULT_HOUR, 0).atZone(IST).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        return null
    }

    /** Start of today in IST - the boundary every "Today" view uses. */
    fun startOfDay(nowMillis: Long): Long =
        Instant.ofEpochMilli(nowMillis).atZone(IST).toLocalDate().atStartOfDay(IST).toInstant().toEpochMilli()

    fun endOfDay(nowMillis: Long): Long = startOfDay(nowMillis) + 24L * 60 * 60 * 1000 - 1

    /** Next local midnight in IST - when a spent daily budget is released. */
    fun nextMidnight(nowMillis: Long): Long = startOfDay(nowMillis) + 24L * 60 * 60 * 1000

    /** IST calendar day key, e.g. "2026-08-26". Used for daily budget buckets. */
    fun dayKey(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(IST).toLocalDate().toString()
}
