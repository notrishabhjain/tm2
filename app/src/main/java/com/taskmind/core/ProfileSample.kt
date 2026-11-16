package com.taskmind.core

/**
 * The small sample of the user's own messages that goes to the AI when they
 * ask TaskMind to draft "About you" from their chats (v1.32).
 *
 * Only what the user wrote, never what others wrote to them. Spread across
 * their busiest chats so one long thread does not crowd out the rest. Short
 * replies ("ok", "haan") say nothing about a person and are skipped. Numbers
 * that look like phone, account or card numbers, and email addresses, are
 * masked before anything is shown or sent.
 *
 * Pure Kotlin, so what is sent is unit-tested rather than taken on trust.
 */
object ProfileSample {

    data class Line(val chat: String, val isGroup: Boolean, val at: Long, val body: String)

    data class Sample(val text: String, val messages: Int, val chats: Int)

    const val MAX_CHARS = 9_000
    const val MAX_LINE = 220
    private const val MIN_LINE = 18

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val LONG_NUMBER = Regex("(?<![\\w])\\+?\\d[\\d -]{5,}\\d(?![\\w])")
    private val URL = Regex("https?://\\S+")

    fun mask(text: String): String = text
        .replace(URL, "[link]")
        .replace(EMAIL, "[email]")
        .replace(LONG_NUMBER, "[number]")

    /**
     * [byChat] is each chat's lines, newest first, in the order the chats
     * should be visited (busiest first). Takes lines round-robin until
     * [maxChars] is reached.
     */
    fun build(byChat: List<List<Line>>, maxChars: Int = MAX_CHARS): Sample {
        val queues = byChat.map { lines ->
            lines.asSequence()
                .map { it.copy(body = mask(it.body.replace('\n', ' ').trim())) }
                .filter { it.body.length >= MIN_LINE && it.body.count(Char::isLetter) >= 10 }
                .distinctBy { it.body.lowercase() }
                .toMutableList()
        }.filter { it.isNotEmpty() }

        val taken = LinkedHashMap<String, MutableList<Line>>()
        var used = 0
        var count = 0
        var progress = true
        while (progress && used < maxChars) {
            progress = false
            for (q in queues) {
                if (q.isEmpty()) continue
                val line = q.removeAt(0)
                val body = if (line.body.length > MAX_LINE) line.body.take(MAX_LINE - 3) + "..." else line.body
                val cost = body.length + 3
                if (used + cost > maxChars) continue
                taken.getOrPut(header(line)) { mutableListOf() } += line.copy(body = body)
                used += cost
                count++
                progress = true
            }
        }
        val text = taken.entries.joinToString("\n\n") { (head, lines) ->
            head + "\n" + lines.joinToString("\n") { "- " + it.body }
        }
        return Sample(text, count, taken.size)
    }

    private fun header(line: Line) = if (line.isGroup) "[group: ${line.chat}]" else "[with ${line.chat}]"

    val SYSTEM_PROMPT = """
        You help a person write a short note about themselves that a task assistant will read
        before deciding which of their messages contain tasks. You are given a sample of messages
        THEY wrote, grouped by chat. Infer only what the messages support; never guess at health,
        religion, politics, finances or relationships beyond what is plainly stated.

        Reply with one JSON object: {"about": "..."}. The "about" text is at most 1200 characters,
        in plain lines, using these headings when there is something to say:
        Role: (their work, as far as the messages show)
        Key people: (the people they deal with most, and how: client, manager, family...)
        Current work: (ongoing matters and projects)
        Personal: (recurring personal responsibilities)
        Ignore: (kinds of chats or messages that never hold tasks for them)
        Languages: (the languages and scripts they write in)
        Write in English. Use names as they appear in the chats.
    """.trimIndent()
}
