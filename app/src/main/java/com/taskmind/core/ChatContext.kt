package com.taskmind.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model sees besides the message itself: the conversation so far,
 * the user's open tasks with this person or group, and the matters they track.
 *
 * Before this, every message was judged alone. "Ok, will do", "any update?"
 * and "send it by today" each arrived with no idea what they referred to, so
 * a nudge about an existing task became a second task, and nothing ever
 * belonged to anything. With this context the model can say "this updates
 * T2" or "this belongs to the Vendor PO matter" instead.
 *
 * Tasks and matters are numbered (T1, M1) rather than sent with their ids:
 * cheaper, and the ids never leave the phone. Pure - building the block and
 * reading the model's references back are both tested.
 */
object ChatContext {

    data class Line(val at: Long, val sender: String, val text: String)

    data class OpenTask(val id: String, val title: String, val dueAt: Long?, val matter: String?)

    data class Matter(val id: String, val name: String)

    /** What one prompt carries, numbered in the order given. */
    data class Block(
        val recent: List<Line>,
        val tasks: List<OpenTask>,
        val matters: List<Matter>,
    ) {
        val isEmpty: Boolean get() = recent.isEmpty() && tasks.isEmpty() && matters.isEmpty()

        companion object {
            val EMPTY = Block(emptyList(), emptyList(), emptyList())
        }
    }

    /** Where a task should go, as the model chose it. */
    sealed interface MatterChoice {
        data class Existing(val id: String) : MatterChoice
        data class New(val name: String) : MatterChoice
        data object None : MatterChoice
    }

    const val MAX_RECENT = 10
    const val MAX_TASKS = 10
    const val MAX_MATTERS = 15
    const val RECENT_WINDOW_MILLIS = 48L * 60 * 60 * 1000
    private const val MAX_LINE = 280

    private val TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val DUE = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    /** Caps each list, keeping the most relevant: the newest chat lines. */
    fun block(recent: List<Line>, tasks: List<OpenTask>, matters: List<Matter>): Block = Block(
        recent = recent.sortedBy { it.at }.takeLast(MAX_RECENT),
        tasks = tasks.take(MAX_TASKS),
        matters = matters.distinctBy { it.name.lowercase() }.take(MAX_MATTERS),
    )

    /** The context as text, ahead of the message. Empty when there is none. */
    fun render(block: Block): String {
        if (block.isEmpty) return ""
        return buildString {
            if (block.recent.isNotEmpty()) {
                append("Earlier in this chat (oldest first):\n")
                block.recent.forEach { line ->
                    append('[').append(format(TIME, line.at)).append("] ")
                    append(line.sender).append(": ")
                    append(line.text.replace('\n', ' ').take(MAX_LINE)).append('\n')
                }
                append('\n')
            }
            if (block.tasks.isNotEmpty()) {
                append("The user's open tasks with this person or group:\n")
                block.tasks.forEachIndexed { i, t ->
                    append('T').append(i + 1).append(". ").append(t.title.replace('\n', ' '))
                    t.dueAt?.let { append(" (due ").append(format(DUE, it)).append(')') }
                    t.matter?.let { append(" [matter: ").append(it).append(']') }
                    append('\n')
                }
                append('\n')
            }
            if (block.matters.isNotEmpty()) {
                append("Matters the user tracks:\n")
                block.matters.forEachIndexed { i, m -> append('M').append(i + 1).append(". ").append(m.name).append('\n') }
                append('\n')
            }
        }
    }

    /** "T2", "t2", "#2" or "2" -> the id of the second open task, or null. */
    fun resolveTask(ref: String?, block: Block): String? {
        val n = ref?.trim()?.removePrefix("#")?.removePrefix("T")?.removePrefix("t")?.toIntOrNull() ?: return null
        return block.tasks.getOrNull(n - 1)?.id
    }

    /**
     * "M3" -> that matter; an existing matter's name -> that matter; any other
     * short phrase -> a new matter by that name; nothing -> none.
     */
    fun resolveMatter(ref: String?, block: Block): MatterChoice {
        val raw = ref?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("none", ignoreCase = true)) {
            return MatterChoice.None
        }
        Regex("^[Mm]\\s?(\\d{1,3})$").find(raw)?.let { m ->
            val matter = block.matters.getOrNull(m.groupValues[1].toInt() - 1) ?: return MatterChoice.None
            return MatterChoice.Existing(matter.id)
        }
        block.matters.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.let { return MatterChoice.Existing(it.id) }
        val name = raw.trim('"', '\'', '.', ' ').take(MAX_MATTER_NAME)
        return if (name.length < 3) MatterChoice.None else MatterChoice.New(name)
    }

    const val MAX_MATTER_NAME = 48

    /** The sender and group a notification capture came from, from its context label. */
    data class Chat(val sender: String, val group: String?)

    fun parseLabel(raw: String?): Chat {
        if (raw.isNullOrBlank()) return Chat("Unknown", null)
        var sender = ""
        var group: String? = null
        for (part in raw.split('|')) {
            when {
                part.startsWith("sender=") -> sender = part.removePrefix("sender=")
                part.startsWith("group=") -> group = part.removePrefix("group=").takeIf { it.isNotBlank() }
            }
        }
        if (sender.isBlank() && !raw.contains('=')) sender = raw
        return Chat(sender.ifBlank { "Unknown" }, group)
    }

    /** Whether two captures are the same conversation: the same group, or the same one-to-one sender. */
    fun sameChat(a: Chat, b: Chat): Boolean = when {
        a.group != null || b.group != null -> a.group != null && a.group.equals(b.group, ignoreCase = true)
        else -> a.sender.equals(b.sender, ignoreCase = true)
    }

    private fun format(pattern: DateTimeFormatter, millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(DateResolver.IST).format(pattern)
}
