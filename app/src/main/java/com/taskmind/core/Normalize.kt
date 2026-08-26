package com.taskmind.core

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Text normalisation shared by dedup, evidence matching and fingerprinting.
 *
 * Deliberately free of any `android.*` import so that every rule in here is
 * exercised by plain JVM unit tests in CI. There is no local machine on which
 * to debug these on-device (spec S3).
 */
object Normalize {

    /**
     * Format characters only (ZWJ/ZWNJ, bidi marks). Control characters such as
     * newline and tab are deliberately NOT in here: they must become a space,
     * not vanish, or "25000\nka" collapses into one token.
     */
    private val ZERO_WIDTH = Regex("[\\p{Cf}\\uFEFF]")

    /** "25,000" and "25000" are the same amount. Models tidy digit grouping. */
    private val DIGIT_SEPARATOR = Regex("(?<=\\d)[,\\u00A0](?=\\d)")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Spec 6.1: NFKC -> lowercase -> strip all non-alphanumeric (Unicode aware,
     * so Devanagari letters survive) -> collapse whitespace.
     */
    fun forHash(s: String): String = squash(s)

    /** Spec 13: the comparison basis for the evidence matcher. */
    fun forMatch(s: String): String = squash(s)

    private fun squash(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase()
            .replace(ZERO_WIDTH, "")
            .replace(DIGIT_SEPARATOR, "")
            .replace(NON_ALNUM, " ")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Leading politeness tokens stripped by [titleKey]. Hindi/Hinglish address
     * forms carry no task meaning: "bhai woh payment kar dena" and
     * "woh payment kar dena" are the same commitment and must dedup together.
     */
    private val POLITENESS_SINGLE = setOf(
        "please", "pls", "plz", "kindly", "zara", "zaraa", "thoda", "thodaa",
        "bhai", "beta", "sir", "ji", "madam", "maam",
    )

    /** Multi-word politeness openers, consumed as a unit before single tokens. */
    private val POLITENESS_PHRASES = listOf(
        listOf("ek", "baar"),
    )

    /**
     * Spec 7.4. Lowercase -> NFKC -> strip leading politeness tokens repeatedly
     * until none match -> strip punctuation -> collapse whitespace.
     *
     * This is the dedup key, so it must be stable: any change here changes what
     * counts as a duplicate for every task already stored.
     */
    fun titleKey(title: String): String {
        val base = squash(title)
        if (base.isEmpty()) return ""

        var tokens = base.split(" ").filter { it.isNotEmpty() }
        var changed = true
        while (changed && tokens.isNotEmpty()) {
            changed = false
            // Longest phrase first, so "ek baar" is consumed as a unit.
            for (phrase in POLITENESS_PHRASES.sortedByDescending { it.size }) {
                if (tokens.size >= phrase.size && tokens.subList(0, phrase.size) == phrase) {
                    tokens = tokens.drop(phrase.size)
                    changed = true
                    break
                }
            }
            if (changed) continue
            if (tokens[0] in POLITENESS_SINGLE) {
                tokens = tokens.drop(1)
                changed = true
            }
        }
        // Everything was politeness: fall back to the normalised original rather
        // than returning an empty key that would collapse unrelated tasks.
        return if (tokens.isEmpty()) base else tokens.joinToString(" ")
    }

    fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** Spec 7.3: trim, collapse whitespace, strip a trailing full stop. */
    fun tidyTitle(title: String): String {
        var t = title.replace(WHITESPACE, " ").trim()
        while (t.isNotEmpty() && (t.last() == '.' || t.last() == '।')) {
            t = t.dropLast(1).trimEnd()
        }
        return t
    }
}
