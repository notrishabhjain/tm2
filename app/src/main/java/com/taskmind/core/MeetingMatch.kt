package com.taskmind.core

/**
 * Which open tasks a meeting is about: the ones with a person who is in it,
 * or a matter its title names.
 *
 * Before a meeting the prep notification lists these, so you walk in knowing
 * what you owe the people in the room and what they owe you. Matching is on
 * whole words, by first name or matter name, case-insensitively; a meeting
 * that matches nothing gets no notification at all.
 */
object MeetingMatch {

    data class Meeting(val title: String, val attendees: List<String>)

    data class Task(val id: String, val title: String, val person: String?, val matter: String?, val waitingOn: Boolean)

    /** Most tasks a prep notification lists. */
    const val MAX = 5

    fun related(meeting: Meeting, open: List<Task>): List<Task> {
        val haystack = (listOf(meeting.title) + meeting.attendees).joinToString(" ").lowercase()
        if (haystack.isBlank()) return emptyList()
        fun mentions(name: String?): Boolean {
            val word = name?.trim()?.lowercase()?.takeIf { it.length >= 3 } ?: return false
            return Regex("(^|[^\\p{L}])${Regex.escape(word)}([^\\p{L}]|$)").containsMatchIn(haystack)
        }
        return open.filter { t ->
            mentions(t.person?.substringBefore(' ')) || mentions(t.matter)
        }.take(MAX)
    }

    /** "You owe: Send the signed PO" / "Waiting on: Rahul's quote" lines for the notification. */
    fun lines(tasks: List<Task>): List<String> =
        tasks.map { (if (it.waitingOn) "Waiting on: " else "You owe: ") + it.title }
}
