package com.taskmind.core

/**
 * The fixed parts of a life that tasks are filed under: Work, Home, Money...
 *
 * Matters are ongoing pieces of work and come and go; areas stay. The user
 * can rename, add and remove them; everything that reads an area - the
 * model's answer, a brain dump, a picker - goes through [normalize], so a
 * task is only ever filed under an area that exists.
 */
object Areas {

    val DEFAULT: List<String> = listOf("Work", "Home", "Money", "Health", "Family")

    const val MAX = 10
    const val MAX_NAME = 24

    /** "Work, Home ,, money" -> [Work, Home, money]: trimmed, de-duplicated, capped. */
    fun parse(raw: String): List<String> =
        raw.split(',', '\n')
            .map { it.trim().take(MAX_NAME) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX)

    fun format(areas: List<String>): String = areas.joinToString(", ")

    /** The area as the user spells it, or null when [raw] is none of theirs. */
    fun normalize(raw: String?, areas: List<String>): String? {
        val wanted = raw?.trim()?.trim('"', '.')?.takeIf { it.isNotEmpty() } ?: return null
        return areas.firstOrNull { it.equals(wanted, ignoreCase = true) }
    }

    /** The line the extraction prompts carry, so the model picks from the user's own list. */
    fun promptLine(areas: List<String>): String =
        if (areas.isEmpty()) "" else "The user's areas: ${areas.joinToString(", ")}\n"
}
