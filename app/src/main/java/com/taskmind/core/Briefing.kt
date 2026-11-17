package com.taskmind.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The few lines that brief the user on one person (v1.33): before a meeting,
 * or while they are calling. Built on the phone from the task list and the
 * memory; nothing is sent anywhere.
 */
object Briefing {

    data class Open(val title: String, val dueAt: Long?)

    data class LastSaid(val at: Long, val fromMe: Boolean, val body: String)

    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /**
     * "You owe 2: Send the signed PO (due today); +1 more", "They owe you: the
     * revised quote", "Last, 3 Nov - Priya: can you send it by 5?". Empty when
     * there is nothing worth a notification.
     */
    fun lines(person: String, youOwe: List<Open>, theyOwe: List<Open>, last: LastSaid?, now: Long, maxPerLine: Int = 2): List<String> {
        val out = mutableListOf<String>()
        if (youOwe.isNotEmpty()) out += "You owe ${youOwe.size}: " + list(youOwe, now, maxPerLine)
        if (theyOwe.isNotEmpty()) out += "They owe you ${theyOwe.size}: " + list(theyOwe, now, maxPerLine)
        last?.let {
            val who = if (it.fromMe) "You" else person.substringBefore(' ')
            val day = Instant.ofEpochMilli(it.at).atZone(DateResolver.IST).format(DATE)
            out += "Last, $day - $who: " + clip(ProfileSample.mask(it.body.replace('\n', ' ')), 110)
        }
        return out
    }

    private fun list(items: List<Open>, now: Long, max: Int): String {
        val sorted = items.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        val shown = sorted.take(max).joinToString("; ") { o ->
            clip(o.title, 60) + (o.dueAt?.let { " (${due(it, now)})" } ?: "")
        }
        return if (sorted.size > max) "$shown; +${sorted.size - max} more" else shown
    }

    private fun due(at: Long, now: Long): String = when {
        at < DateResolver.startOfDay(now) -> "overdue"
        at < DateResolver.nextMidnight(now) -> "due today"
        else -> "due " + Instant.ofEpochMilli(at).atZone(DateResolver.IST).format(DATE)
    }

    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1).trimEnd() + "…"
}

/**
 * How the user writes to one person (v1.33): a handful of their own past
 * messages to them, given to the model as examples of tone and language when
 * it drafts a reply or a nudge. Only what the user wrote, masked.
 */
object Voice {

    fun examples(mine: List<String>, max: Int = 10, maxChars: Int = 1200): List<String> {
        val out = mutableListOf<String>()
        var used = 0
        for (raw in mine) {
            val m = ProfileSample.mask(raw.replace('\n', ' ').trim())
            if (m.length < 12 || m.length > 220 || m.count(Char::isLetter) < 6) continue
            if (out.any { it.equals(m, ignoreCase = true) }) continue
            if (used + m.length > maxChars) break
            out += m
            used += m.length
            if (out.size >= max) break
        }
        return out
    }

    fun block(examples: List<String>): String = if (examples.isEmpty()) "" else buildString {
        append("How I write to them (my own recent messages; match this tone, language and length):\n")
        examples.forEach { append("- ").append(it).append('\n') }
    }
}
