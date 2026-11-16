package com.taskmind.core

/**
 * Ranking word matches for the Search my chats screen (v1.32).
 *
 * The memory's full-text index finds messages holding ANY of the words; a
 * person searching expects the ones holding most of them first. Messages are
 * scored by how many distinct words they contain (as a word or the start of
 * one), then by recency.
 */
object ChatSearch {

    fun terms(query: String): List<String> =
        query.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 2 }.distinct().take(8)

    fun score(body: String, terms: List<String>): Int {
        val words = body.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
        return terms.count { t -> words.any { it.startsWith(t) } }
    }

    /** [items] with their body and time; best first, at most [limit]. */
    fun <T> rank(items: List<T>, terms: List<String>, body: (T) -> String, at: (T) -> Long, limit: Int = 60): List<T> {
        if (terms.isEmpty()) return emptyList()
        return items.map { it to score(body(it), terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenByDescending { at(it.first) })
            .take(limit)
            .map { it.first }
    }
}
