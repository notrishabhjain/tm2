package com.taskmind.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where things stand: one line per matter, and who owes what per person.
 *
 * Written by rules from the tasks themselves rather than by the model. A
 * summary is only worth reading if it is right, and these are always right:
 * they say exactly what the list says, shorter. They also cost nothing and
 * change the moment a task does.
 */
object Standing {

    data class Task(
        val title: String,
        val dueAt: Long?,
        val waitingOn: Boolean,
        val person: String?,
        val updatedAt: Long,
    )

    private const val DAY = 24L * 60 * 60 * 1000
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /**
     * "You owe 2 - next: Send the signed PO, due Fri. Waiting on Rahul."
     *
     * [open] is the matter's active tasks. Null when there is nothing open.
     */
    fun matter(open: List<Task>, now: Long): String? {
        if (open.isEmpty()) return null
        val mine = open.filter { !it.waitingOn }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
        val theirs = open.filter { it.waitingOn }
        val parts = mutableListOf<String>()
        if (mine.isNotEmpty()) {
            val next = mine.first()
            val due = next.dueAt?.let { ", ${when_(it, now)}" }.orEmpty()
            parts += if (mine.size == 1) "Next: ${next.title}$due" else "You owe ${mine.size} - next: ${next.title}$due"
        }
        if (theirs.isNotEmpty()) {
            val names = theirs.mapNotNull { it.person?.substringBefore(' ') }.distinct()
            val since = theirs.minOf { it.updatedAt }
            parts += buildString {
                append("Waiting on ")
                append(if (names.isEmpty()) "${theirs.size}" else names.take(2).joinToString(" and "))
                if (now - since >= DAY) append(" since ").append(format(WEEKDAY, since, now))
            }
        }
        return parts.joinToString(". ")
    }

    /** "due today", "due Fri", "due 12 Nov", "overdue". */
    private fun when_(due: Long, now: Long): String {
        val today = DateResolver.startOfDay(now)
        return when {
            due < today -> "overdue"
            due < DateResolver.nextMidnight(now) -> "due today"
            else -> "due ${format(WEEKDAY, due, now)}"
        }
    }

    private fun format(weekday: DateTimeFormatter, millis: Long, now: Long): String {
        val zoned = Instant.ofEpochMilli(millis).atZone(DateResolver.IST)
        // A weekday is only unambiguous within a week either side.
        return if (kotlin.math.abs(millis - now) < 6 * DAY) zoned.format(weekday) else zoned.format(DATE)
    }

    // ------------------------------------------------------------- people

    data class Person(
        val name: String,
        /** Open tasks this person asked of the user. */
        val youOwe: Int,
        /** Open promises this person made to the user. */
        val theyOwe: Int,
        /** The soonest thing between you, for the row's second line. */
        val next: String?,
    )

    /** Everyone with something open either way, busiest first. */
    fun people(open: List<Task>): List<Person> =
        open.filter { !it.person.isNullOrBlank() }
            .groupBy { it.person!!.trim().lowercase() }
            .map { (_, rows) ->
                val sorted = rows.sortedBy { it.dueAt ?: Long.MAX_VALUE }
                Person(
                    name = rows.first().person!!.trim(),
                    youOwe = rows.count { !it.waitingOn },
                    theyOwe = rows.count { it.waitingOn },
                    next = sorted.first().title,
                )
            }
            .sortedWith(compareByDescending<Person> { it.youOwe + it.theyOwe }.thenBy { it.name.lowercase() })
}
