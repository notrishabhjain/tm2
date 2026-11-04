package com.taskmind.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A chat's messages, read together once the chat goes quiet.
 *
 * People rarely say a thing in one message: "Rishabh", "the PO", "can you
 * sign it today?" arrive as three notifications a few seconds apart. Read
 * one at a time, the first two are nothing and the third is half a task.
 * So a new message waits until its chat has been quiet for [QUIET_MILLIS],
 * then the whole burst goes to the model as one message - one call instead
 * of several, and a task that makes sense.
 *
 * Eight seconds, down from a minute in v1.29. Lines typed in a row arrive a
 * few seconds apart, so eight still gathers them; a minute made every task
 * a minute late. A line that comes later is read on its own, with the chat
 * so far as context, and the model updates the task rather than adding one.
 */
object Burst {

    /** How long a chat must be quiet before its messages are read. */
    const val QUIET_MILLIS = 8_000L

    /** How far back pending messages of the same chat are gathered. */
    const val WINDOW_MILLIS = 10L * 60 * 1000

    /** Most messages read as one. */
    const val MAX_LINES = 12

    data class Line(val at: Long, val sender: String, val text: String)

    /** When the burst may be read: [QUIET_MILLIS] after its newest message, or now if that has passed. */
    fun readyAt(lines: List<Line>): Long = (lines.maxOfOrNull { it.at } ?: 0L) + QUIET_MILLIS

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /**
     * One message made of several, oldest first, each line with its time and
     * sender. A single message is returned exactly as it came, so a chat that
     * sends one line at a time reads as before.
     */
    fun combine(lines: List<Line>): String {
        val sorted = lines.sortedBy { it.at }.takeLast(MAX_LINES)
        if (sorted.size == 1) return sorted.single().text
        return sorted.joinToString("\n") { line ->
            "[" + Instant.ofEpochMilli(line.at).atZone(DateResolver.IST).format(TIME) + "] " +
                line.sender + ": " + line.text.replace('\n', ' ')
        }
    }
}
