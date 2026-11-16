package com.taskmind.vector

/**
 * Cuts the memory's messages into stretches of conversation, the unit that is
 * given a vector.
 *
 * One message alone is often too short to mean anything ("ok", "done?"), and
 * a vector per message would also be ten times the work on a phone. A stretch
 * - the same chat, no pause longer than [GAP_MILLIS], at most [MAX_CHARS]
 * characters - keeps the ask with its answer, which is what a question later
 * is about.
 *
 * Pure: the caller hands in messages in any order and stores what comes back.
 */
object Chunker {

    data class Message(
        val id: Long,
        val chat: String,
        val isGroup: Boolean,
        val sender: String,
        val at: Long,
        val body: String,
    )

    data class Chunk(
        val chat: String,
        val isGroup: Boolean,
        val startAt: Long,
        val endAt: Long,
        val firstId: Long,
        val lastId: Long,
        /** "Sender: words" a line each, as the vector is made from and the Twin is shown. */
        val text: String,
    )

    const val GAP_MILLIS = 30 * 60 * 1000L
    const val MAX_CHARS = 700
    const val MAX_MESSAGES = 15
    private const val MAX_LINE = 300

    fun chunk(messages: List<Message>): List<Chunk> {
        val out = ArrayList<Chunk>()
        messages.groupBy { it.chat to it.isGroup }.forEach { (key, inChat) ->
            val sorted = inChat.sortedWith(compareBy<Message> { it.at }.thenBy { it.id })
            var lines = ArrayList<String>()
            var group = ArrayList<Message>()
            var chars = 0

            fun flush() {
                if (group.isEmpty()) return
                out += Chunk(
                    chat = key.first,
                    isGroup = key.second,
                    startAt = group.first().at,
                    endAt = group.last().at,
                    firstId = group.minOf { it.id },
                    lastId = group.maxOf { it.id },
                    text = lines.joinToString("\n"),
                )
                lines = ArrayList()
                group = ArrayList()
                chars = 0
            }

            for (m in sorted) {
                val words = m.body.replace(Regex("\\s+"), " ").trim()
                if (words.isEmpty()) continue
                val line = "${m.sender}: ${if (words.length > MAX_LINE) words.take(MAX_LINE) + "…" else words}"
                val pause = group.isNotEmpty() && m.at - group.last().at > GAP_MILLIS
                val full = group.size >= MAX_MESSAGES || (group.isNotEmpty() && chars + line.length > MAX_CHARS)
                if (pause || full) flush()
                group += m
                lines += line
                chars += line.length + 1
            }
            flush()
        }
        return out
    }
}
