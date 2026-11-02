package com.taskmind.core

import com.taskmind.core.LlmJson.str
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The twin: one assistant that knows the list, the calendar and the recent
 * messages, answers questions about them, and proposes changes.
 *
 * It replaces a set of single-purpose prompts with one that has clear powers:
 * plan a task onto a day, add, edit, finish, drop, remind, or draft a nudge.
 * It never applies anything. Every action it proposes is shown to the user,
 * who applies the ones they want with a tap; applied changes are logged in
 * [Journal] and can be undone.
 *
 * Tasks are numbered rather than sent with their ids, as in [AskPrompt]: the
 * model points back at them cheaply, and the ids never leave the phone.
 *
 * Pure: the prompt, and reading the answer back, are tested without a model.
 */
object Twin {

    data class Task(
        val id: String,
        val title: String,
        val state: State,
        val dueAt: Long? = null,
        /** The day it is on the Today list for, "2026-11-02", or null. */
        val plannedFor: String? = null,
        val area: String? = null,
        val matter: String? = null,
        /** "Priya (WhatsApp)", "Call with Rahul", or null for typed tasks. */
        val from: String? = null,
        val evidence: String? = null,
        /** Someone else owes the user this. */
        val waiting: Boolean = false,
        val priority: Priority = Priority.MEDIUM,
        val createdAt: Long = 0L,
        val completedAt: Long? = null,
        /** Importance points, for ordering what is sent. */
        val points: Int = 0,
    )

    enum class State { OPEN, INBOX, DONE }

    data class Meeting(val start: Long?, val end: Long?, val title: String, val people: List<String> = emptyList())

    data class MatterLine(val name: String, val standing: String?)

    /** A message or email snippet found for the question, from the phone's own memory. */
    data class Memory(val at: Long, val from: String, val text: String)

    data class Context(
        val now: Long,
        val tasks: List<Task>,
        val meetings: List<Meeting> = emptyList(),
        /** Meetings on the coming days, for planning a week. */
        val upcoming: List<Meeting> = emptyList(),
        val matters: List<MatterLine> = emptyList(),
        val memory: List<Memory> = emptyList(),
        val areas: List<String> = emptyList(),
        val aboutMe: String = "",
        val userNames: Set<String> = emptySet(),
    )

    sealed interface Action {
        val taskId: String?

        data class Plan(override val taskId: String, val day: String?, val at: Long?) : Action
        data class Add(
            val title: String,
            val dueAt: Long?,
            val day: String?,
            val area: String?,
            val priority: Priority,
            val notes: String?,
        ) : Action {
            override val taskId: String? get() = null
        }
        data class Edit(
            override val taskId: String,
            val title: String?,
            val dueAt: Long?,
            val clearDue: Boolean,
            val priority: Priority?,
            val area: String?,
        ) : Action
        data class Done(override val taskId: String) : Action
        data class Drop(override val taskId: String) : Action
        data class Remind(override val taskId: String, val at: Long) : Action
        data class Nudge(override val taskId: String, val to: String?, val text: String) : Action
    }

    data class Reply(val answer: String, val taskIds: List<String>, val actions: List<Action>)

    /** Enough for a busy month; past this the least pressing drop off. */
    const val MAX_TASKS = 150
    private const val MAX_INBOX = 30
    private const val MAX_DONE = 40
    private const val MAX_EVIDENCE = 140
    private const val DAY = 24L * 60 * 60 * 1000

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    private val DAY_ONLY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    val SYSTEM: String = """
You are the user's twin: a sharp, discreet chief of staff who knows their to-do list, their
calendar and their recent messages, and changes the list when asked.

You are given NOW, the user's calendar, their TASKS numbered [1], [2]..., the MATTERS they
track, sometimes MESSAGES THAT MAY BE RELEVANT from their own phone, and the REQUEST.
Each task line shows its state - OPEN, INBOX (captured, not yet looked at) or DONE - then
its title, due date, the day it is planned for, area, matter, who it came from, WAITING
when someone else owes the user something, and the quote it came from.

Answer only from what you were given. If it does not say, say you cannot tell. Never invent
a task, a date, a person or a promise.

You may PROPOSE changes. The user sees every one and applies it with a tap; nothing happens
without them. Propose changes only when the request asks for them - plan, move, add, finish,
drop, remind, chase - never for a plain question. Actions:
- {"do":"plan","task":N,"day":"YYYY-MM-DD" or null,"at":"HH:mm" or null} - put an open task
  on a day's list; null day takes it off. "at" is a start time today, between meetings.
- {"do":"add","title":"...","due":"<ISO 8601 +05:30 or null>","day":"YYYY-MM-DD or null",
  "area":"<one of the user's areas or null>","priority":"URGENT|HIGH|MEDIUM|LOW","notes":null}
  - only for something the user asked to add.
- {"do":"edit","task":N,"title":"...","due":"<ISO 8601 +05:30, or none>","priority":"...",
  "area":"..."} - give only the fields that change.
- {"do":"done","task":N} - only when the user says it is done.
- {"do":"drop","task":N} - only when the user says to drop it.
- {"do":"remind","task":N,"at":"<ISO 8601 +05:30>"}
- {"do":"nudge","task":N,"to":"<name>","text":"<the message, ready to send>"} - a polite
  follow-up to someone who owes the user something (a WAITING task). Two or three short
  lines, warm and specific: what was promised and when. Write it in the language that person
  used in the quote - Hinglish if they wrote Hinglish. The user sends it themselves.

PLANNING
- "Plan my day": choose 3 to 5 open tasks for today. First what is overdue or due today,
  then what is already planned for today, then what matters most - key people, deadlines
  this week. Give each an "at" time inside the free time between meetings, hardest first.
  Leave the rest: a short list that gets done beats a long one that does not.
- "Plan my week": spread open tasks over the next 7 days, at most 4 a day, each on or before
  its due date, lighter on days full of meetings. Leave tasks with no reason to be this week.
- INBOX tasks are planned only when the user asks; they have not decided on them yet.

Be brief: a few sentences or short lines. Lay a plan out as "HH:mm  Task" lines. Answer in
the language of the request - English, Hindi or Hinglish.

Return ONLY this JSON, no markdown:
{"answer":"<text, may contain line breaks>","tasks":[<numbers the answer is about>],"actions":[...]}
""".trim()

    /** Asked for by the "Plan my day" button; the same words a user would type. */
    const val PLAN_DAY = "Plan my day."
    const val PLAN_WEEK = "Plan my week: spread my open tasks over the next 7 days."

    /**
     * What to send: open tasks most pressing first, then the newest Inbox
     * items, then recently finished ones - enough to answer "what did I do
     * for Priya", not the whole history.
     */
    fun select(tasks: List<Task>, now: Long): List<Task> {
        val open = tasks.filter { it.state == State.OPEN }
            .sortedWith(compareByDescending<Task> { it.points }.thenBy { it.dueAt ?: Long.MAX_VALUE })
        val inbox = tasks.filter { it.state == State.INBOX }.sortedByDescending { it.createdAt }.take(MAX_INBOX)
        val done = tasks.filter { it.state == State.DONE && (it.completedAt ?: 0L) > now - 30 * DAY }
            .sortedByDescending { it.completedAt ?: 0L }.take(MAX_DONE)
        return (open + inbox + done).take(MAX_TASKS)
    }

    fun userPrompt(request: String, ctx: Context): String = buildString {
        append("NOW: ").append(DateResolver.formatForPrompt(ctx.now)).append(" IST\n")
        Prompts.aboutBlock(ctx.aboutMe).takeIf { it.isNotEmpty() }?.let { append(it) }
        val names = ctx.userNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isNotEmpty()) append("You are known as: ").append(names.joinToString(", ")).append('\n')
        append(Areas.promptLine(ctx.areas))
        append('\n')

        append("TODAY'S CALENDAR:\n")
        if (ctx.meetings.isEmpty()) append("- nothing\n")
        ctx.meetings.sortedBy { it.start ?: 0L }.forEach { m -> append("- ").append(meetingLine(m)).append('\n') }
        val free = DayPlanner.gaps(ctx.meetings, ctx.now)
        if (free.isNotEmpty()) {
            append("FREE TODAY: ").append(free.joinToString(", ") { "${hm(it.start)}-${hm(it.end)}" }).append('\n')
        }
        if (ctx.upcoming.isNotEmpty()) {
            append("COMING DAYS:\n")
            ctx.upcoming.sortedBy { it.start ?: 0L }.take(30).forEach { m ->
                append("- ").append(m.start?.let { day(it) + " " } ?: "").append(meetingLine(m)).append('\n')
            }
        }
        append('\n')

        val today = DateResolver.dayKey(ctx.now)
        append("TASKS:\n")
        if (ctx.tasks.isEmpty()) append("(none)\n")
        ctx.tasks.forEachIndexed { i, t ->
            append('[').append(i + 1).append("] ")
            append(
                when (t.state) {
                    State.OPEN -> if (t.waiting) "OPEN WAITING" else "OPEN"
                    State.INBOX -> "INBOX"
                    State.DONE -> "DONE" + (t.completedAt?.let { " " + day(it) } ?: "")
                },
            )
            append(" | ").append(t.title.replace('\n', ' '))
            t.dueAt?.let { append(" | due ").append(dueText(it, ctx.now)) }
            t.plannedFor?.let { append(" | planned ").append(if (it == today) "today" else it) }
            t.area?.let { append(" | ").append(it) }
            t.matter?.let { append(" | matter: ").append(it) }
            t.from?.takeIf { it.isNotBlank() }?.let { append(" | from ").append(it) }
            if (t.priority == Priority.URGENT || t.priority == Priority.HIGH) append(" | ").append(t.priority.name.lowercase())
            t.evidence?.takeIf { it.isNotBlank() }?.let {
                append(" | said: \"").append(it.replace('\n', ' ').take(MAX_EVIDENCE)).append('"')
            }
            append('\n')
        }

        if (ctx.matters.isNotEmpty()) {
            append("\nMATTERS:\n")
            ctx.matters.take(20).forEach { m ->
                append("- ").append(m.name)
                m.standing?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                append('\n')
            }
        }

        if (ctx.memory.isNotEmpty()) {
            append("\nMESSAGES THAT MAY BE RELEVANT (from the user's phone, newest first):\n")
            ctx.memory.forEach { m ->
                append("- [").append(format(WHEN, m.at)).append("] ").append(m.from).append(": \"")
                append(m.text.replace('\n', ' ').take(Recall.MAX_SNIPPET)).append("\"\n")
            }
        }

        append("\nREQUEST: ").append(request.trim())
    }

    /** Reads the model's reply. [sent] is the task list in the order it was numbered. */
    fun parse(raw: String, sent: List<Task>, now: Long, areas: List<String>): Reply? {
        val obj = LlmJson.parseObject(raw) ?: return null
        val answer = obj.str("answer")?.trim().orEmpty()
        val ids = numbers(obj["tasks"]).mapNotNull { n -> sent.getOrNull(n - 1)?.id }.distinct()
        val actions = (obj["actions"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { a -> action(a, sent, now, areas) } }
            // One of each kind per task: a second "plan" for the same task is the model hedging.
            .distinctBy { a -> a.javaClass.simpleName + ":" + (a.taskId ?: (a as? Action.Add)?.title?.lowercase()) }
        if (answer.isEmpty() && actions.isEmpty()) return null
        return Reply(answer.ifEmpty { "Here is what I would change:" }, ids, actions)
    }

    private fun numbers(element: kotlinx.serialization.json.JsonElement?): List<Int> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.trim('[', ']')?.toIntOrNull() }

    private fun action(a: JsonObject, sent: List<Task>, now: Long, areas: List<String>): Action? {
        val kind = (a.str("do") ?: a.str("action") ?: a.str("type"))?.trim()?.lowercase() ?: return null
        val task = a.str("task")?.trim()?.trim('[', ']')?.toIntOrNull()?.let { sent.getOrNull(it - 1) }
        val openTask = task?.takeIf { it.state != State.DONE }
        return when (kind) {
            "plan" -> {
                val t = openTask ?: return null
                val day = day(a.str("day"), now)
                val at = day?.let { d -> time(a.str("at"), d) }?.takeIf { it > now }
                Action.Plan(t.id, day, at)
            }
            "add" -> {
                val title = a.str("title")?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: return null
                Action.Add(
                    title = title,
                    dueAt = DateResolver.resolve(a.str("due"), now).dueAt,
                    day = day(a.str("day"), now),
                    area = Areas.normalize(a.str("area"), areas),
                    priority = Priority.parse(a.str("priority")),
                    notes = a.str("notes")?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            "edit" -> {
                val t = openTask ?: return null
                val dueRaw = a.str("due")
                val clear = dueRaw != null && dueRaw.trim().lowercase() in setOf("none", "no date", "remove")
                val edit = Action.Edit(
                    taskId = t.id,
                    title = a.str("title")?.trim()?.take(120)?.takeIf { it.isNotEmpty() && it != t.title },
                    dueAt = if (clear) null else DateResolver.resolve(dueRaw, now).dueAt,
                    clearDue = clear,
                    priority = a.str("priority")?.let { Priority.entries.firstOrNull { p -> p.name.equals(it.trim(), true) } },
                    area = Areas.normalize(a.str("area"), areas),
                )
                if (edit.title == null && edit.dueAt == null && !edit.clearDue && edit.priority == null && edit.area == null) null else edit
            }
            "done", "complete" -> openTask?.let { Action.Done(it.id) }
            "drop", "delete", "archive" -> openTask?.let { Action.Drop(it.id) }
            "remind" -> {
                val t = openTask ?: return null
                val at = DateResolver.resolve(a.str("at"), now).dueAt?.takeIf { it > now } ?: return null
                Action.Remind(t.id, at)
            }
            "nudge", "chase" -> {
                val t = openTask ?: return null
                val text = a.str("text")?.trim()?.take(700)?.takeIf { it.isNotEmpty() } ?: return null
                Action.Nudge(t.id, a.str("to")?.trim()?.takeIf { it.isNotEmpty() }, text)
            }
            else -> null
        }
    }

    /** "2026-11-03", "today", "tomorrow" or a weekday, within the next two months; null otherwise. */
    fun day(raw: String?, now: Long): String? {
        val text = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "null" && it != "none" } ?: return null
        val today = Instant.ofEpochMilli(now).atZone(DateResolver.IST).toLocalDate()
        val date = when (text) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            else -> runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
                ?: java.time.DayOfWeek.values().firstOrNull { it.name.lowercase().startsWith(text.take(3)) && text.length >= 3 }
                    ?.let { dow -> (1..7L).map { today.plusDays(it) }.first { it.dayOfWeek == dow } }
        } ?: return null
        if (date.isBefore(today) || date.isAfter(today.plusDays(62))) return null
        return date.toString()
    }

    /** "10:30" on [day], as epoch millis. */
    fun time(raw: String?, day: String): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: return null
        val t = runCatching { LocalTime.parse(if (text.length == 4 && text[1] == ':') "0$text" else text.take(5)) }.getOrNull() ?: return null
        return runCatching { LocalDate.parse(day).atTime(t).atZone(DateResolver.IST).toInstant().toEpochMilli() }.getOrNull()
    }

    /** One line a person can read: "Plan for today at 10:30: Send the PO". */
    fun describe(action: Action, titleOf: (String) -> String?, now: Long): String {
        val today = DateResolver.dayKey(now)
        val tomorrow = DateResolver.dayKey(now + DAY)
        fun dayName(d: String?) = when (d) {
            null -> "no day"
            today -> "today"
            tomorrow -> "tomorrow"
            else -> runCatching { LocalDate.parse(d).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) }.getOrDefault(d)
        }
        fun title(id: String?) = id?.let(titleOf) ?: "a task"
        return when (action) {
            is Action.Plan ->
                if (action.day == null) "Take off the day's list: ${title(action.taskId)}"
                else "Plan for ${dayName(action.day)}" + (action.at?.let { " at ${hm(it)}" } ?: "") + ": ${title(action.taskId)}"
            is Action.Add -> "Add: ${action.title}" +
                listOfNotNull(
                    action.dueAt?.let { "due " + dueText(it, now) },
                    action.day?.let { "on " + dayName(it) },
                    action.area,
                ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
            is Action.Edit -> buildList {
                action.title?.let { add("rename to \"$it\"") }
                if (action.clearDue) add("remove the date")
                action.dueAt?.let { add("due " + dueText(it, now)) }
                action.priority?.let { add(it.name.lowercase() + " priority") }
                action.area?.let { add("file under $it") }
            }.joinToString(", ").replaceFirstChar { it.uppercase() } + ": ${title(action.taskId)}"
            is Action.Done -> "Mark done: ${title(action.taskId)}"
            is Action.Drop -> "Drop: ${title(action.taskId)}"
            is Action.Remind -> "Remind ${dueText(action.at, now)}: ${title(action.taskId)}"
            is Action.Nudge -> "Draft a nudge" + (action.to?.let { " to $it" } ?: "") + ": ${title(action.taskId)}"
        }
    }

    private fun meetingLine(m: Meeting): String = buildString {
        if (m.start == null) append("All day") else {
            append(hm(m.start))
            m.end?.let { append('-').append(hm(it)) }
        }
        append(' ').append(m.title.replace('\n', ' ').take(80))
        if (m.people.isNotEmpty()) append(" (").append(m.people.take(6).joinToString(", ")).append(')')
    }

    private fun dueText(at: Long, now: Long): String {
        val text = if (SmartReminder.isDateOnly(at)) format(DAY_ONLY, at) else format(WHEN, at)
        return if (at < now) "$text (overdue)" else text
    }

    private fun day(at: Long) = format(DAY_ONLY, at)
    private fun hm(at: Long) = format(HM, at)
    private fun format(f: DateTimeFormatter, at: Long): String = Instant.ofEpochMilli(at).atZone(DateResolver.IST).format(f)
}
