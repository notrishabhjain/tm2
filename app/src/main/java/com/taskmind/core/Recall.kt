package com.taskmind.core

import java.time.Instant
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * "What did Priya ask for in October?" - finding the messages a question is
 * about, on the phone, before anything is sent.
 *
 * Tasks only hold the one line that made them a task. The messages around
 * them hold the rest, and they never leave the phone except to the AI
 * provider, as they did when they arrived. So the search runs here: the
 * question's words and names against the captured messages and mails, and
 * only the few best snippets - a few hundred characters each - go with the
 * question.
 *
 * Pure: plain word matching, tested on fixed text.
 */
object Recall {

    data class Item(val at: Long, val from: String, val text: String)

    const val MAX_SNIPPET = 280
    const val MAX_ITEMS = 8

    private val STOP = setOf(
        // English
        "the", "and", "for", "with", "from", "about", "what", "when", "where", "which", "who", "whom", "whose",
        "why", "how", "did", "does", "doing", "done", "have", "has", "had", "any", "are", "was", "were", "this",
        "that", "these", "those", "there", "their", "they", "them", "you", "your", "yours", "mine", "our",
        "owe", "owes", "ask", "asked", "asking", "tell", "told", "said", "say", "says", "need", "needs",
        "should", "could", "would", "will", "can", "all", "last", "next", "week", "month", "today", "tomorrow",
        "yesterday", "please", "plan", "day", "days", "much", "many", "pending", "still", "get", "got", "give",
        "want", "wanted", "something", "anything", "everything", "thing", "things", "task", "tasks", "list",
        "message", "messages", "email", "emails", "mail", "chat", "call", "calls", "send", "sent", "him",
        "her", "his", "she", "not", "yet", "since", "ago", "into", "out", "over", "promised", "promise",
        // Hinglish
        "kya", "kab", "kaun", "kisne", "kitna", "kitne", "mujhe", "maine", "mera", "meri", "mere", "hai",
        "hain", "tha", "thi", "the", "ke", "ki", "ka", "ko", "se", "aur", "bhi", "nahi", "kuch", "koi", "ne",
        "baat", "bola", "boli", "kaha", "liye", "wala", "wali", "abhi", "kal", "aaj",
    )

    /** The words a question is about: names, nouns, amounts. Lowercase, no stop words. */
    fun terms(question: String): List<String> =
        Regex("[\\p{L}\\p{N}₹][\\p{L}\\p{N}.'₹-]*").findAll(question.lowercase())
            .map { it.value.trim('.', '\'', '-') }
            .filter { it.length >= 3 && it !in STOP && month(it) == null }
            .distinct()
            .toList()

    /** A month the question names ("October", "oct"), if any. */
    fun monthIn(question: String): Month? =
        Regex("\\p{L}+").findAll(question.lowercase()).mapNotNull { month(it.value) }.firstOrNull()

    /**
     * Month names and the unambiguous short forms. "May" is left out - it is
     * far more often the verb - and so is "mar", which is a word in Hindi.
     */
    private val MONTH_WORDS: Map<String, Month> = buildMap {
        Month.values().forEach { m -> put(m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(), m) }
        remove("may")
        listOf("jan", "feb", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec").forEach { short ->
            Month.values().firstOrNull { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().startsWith(short.take(3)) }
                ?.let { put(short, it) }
        }
    }

    private fun month(word: String): Month? = MONTH_WORDS[word]

    /**
     * The best [max] items for [question]: most matching words first, then
     * newest. A single shared word is enough only when the question has one
     * word, or the word is in the sender's name.
     */
    fun search(question: String, items: List<Item>, now: Long, max: Int = MAX_ITEMS): List<Item> {
        val terms = terms(question)
        if (terms.isEmpty()) return emptyList()
        val month = monthIn(question)
        val zone = DateResolver.IST
        val thisYear = Instant.ofEpochMilli(now).atZone(zone).year
        val thisMonth = Instant.ofEpochMilli(now).atZone(zone).month
        val year = if (month != null && month > thisMonth) thisYear - 1 else thisYear

        return items.asSequence()
            .filter { item ->
                month == null || Instant.ofEpochMilli(item.at).atZone(zone).let { it.month == month && it.year == year }
            }
            .mapNotNull { item ->
                val text = item.text.lowercase()
                val from = item.from.lowercase()
                val inText = terms.filter { text.contains(it) }
                val inFrom = terms.filter { from.contains(it) }
                val hits = (inText + inFrom).distinct().size
                val enough = hits >= 2 || inFrom.isNotEmpty() || (terms.size == 1 && hits == 1)
                if (!enough) null else Triple(item, hits, inText.firstOrNull())
            }
            .sortedWith(compareByDescending<Triple<Item, Int, String?>> { it.second }.thenByDescending { it.first.at })
            .take(max)
            .map { (item, _, term) -> item.copy(text = snippet(item.text, term)) }
            .toList()
    }

    /** A window of the text around [term], at most [MAX_SNIPPET] characters. */
    fun snippet(text: String, term: String?): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= MAX_SNIPPET) return flat
        val at = term?.let { flat.lowercase().indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val start = (at - 100).coerceAtLeast(0)
        val end = (start + MAX_SNIPPET).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }
}
