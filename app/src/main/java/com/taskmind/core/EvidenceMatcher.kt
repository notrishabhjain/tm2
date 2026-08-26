package com.taskmind.core

/**
 * Spec 13 - grounding.
 *
 * Every extracted task carries an `evidence` field containing the source words
 * that justify it. A task whose evidence cannot be located in the source is
 * dropped in code, not by the model: the model cannot invent a task without
 * also inventing a quote, and the quote is mechanically checkable.
 *
 * A literal `contains` check is a trap. Models normalise whitespace, swap
 * Devanagari for Latin, drop a matra, or tidy punctuation. A strict matcher
 * turns a precision-first design into a recall-zero one, so the fallback is a
 * containment score over a sliding token window.
 */
object EvidenceMatcher {

    /** Shortest evidence we will accept. Below this, a match means nothing. */
    const val MIN_EVIDENCE_CHARS = 8

    data class Result(
        val matched: Boolean,
        val bestScore: Double,
        val reason: String,
    )

    fun match(evidence: String?, source: String, tolerance: Double): Result {
        if (evidence == null) return Result(false, 0.0, "evidence is null")

        val e = Normalize.forMatch(evidence)
        val s = Normalize.forMatch(source)

        if (e.isEmpty()) return Result(false, 0.0, "evidence empty after normalisation")
        if (e.length < MIN_EVIDENCE_CHARS) {
            return Result(false, 0.0, "evidence shorter than $MIN_EVIDENCE_CHARS chars")
        }
        if (s.contains(e)) return Result(true, 1.0, "exact containment")

        val eTokens = e.split(" ").filter { it.isNotEmpty() }
        val sTokens = s.split(" ").filter { it.isNotEmpty() }
        if (eTokens.size < 2) return Result(false, 0.0, "single-token evidence, no exact match")
        if (sTokens.isEmpty()) return Result(false, 0.0, "source empty after normalisation")

        val window = (eTokens.size * 1.6).toInt().coerceAtLeast(eTokens.size).coerceAtMost(sTokens.size)
        var best = 0.0
        val lastStart = (sTokens.size - window).coerceAtLeast(0)
        for (i in 0..lastStart) {
            val slice = sTokens.subList(i, (i + window).coerceAtMost(sTokens.size)).toSet()
            val hit = eTokens.count { it in slice }.toDouble() / eTokens.size
            if (hit > best) best = hit
            if (best >= 1.0) break
        }
        val ok = best >= tolerance
        return Result(ok, best, if (ok) "window containment $best" else "best window containment $best < $tolerance")
    }
}
