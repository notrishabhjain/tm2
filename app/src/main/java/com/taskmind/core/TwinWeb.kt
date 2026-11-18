package com.taskmind.core

import java.time.Instant

/**
 * What a Twin answer looks like on the web page (v1.36). Pure, so what goes
 * online is tested rather than assumed.
 *
 * Online: the question, the answer, the titles of the tasks it names, how
 * many changes it proposed (those are applied on the phone), and the message
 * snippets it was given, each cut to [SNIPPET_CHARS] with numbers, emails and
 * links masked. Nothing else from the memory leaves the phone.
 */
object TwinWeb {

    const val TABLE = "twin_questions"
    const val SNIPPET_CHARS = 280
    const val MAX_SNIPPETS = 8
    const val KEEP_DAYS = 30L
    const val MAX_QUESTION = 2000

    data class Snippet(val at: Long, val from: String, val text: String)

    fun snippets(memory: List<Twin.Memory>): List<Snippet> = memory.take(MAX_SNIPPETS).map {
        val text = ProfileSample.mask(it.text.replace('\n', ' ').trim())
        Snippet(it.at, it.from.take(80), if (text.length <= SNIPPET_CHARS) text else text.take(SNIPPET_CHARS - 1).trimEnd() + "…")
    }

    /** The PostgREST filter for rows past the keeping period. */
    fun expiredFilter(now: Long): String =
        "asked_at=lt." + Instant.ofEpochMilli(now - KEEP_DAYS * 24 * 60 * 60 * 1000).toString()
}
