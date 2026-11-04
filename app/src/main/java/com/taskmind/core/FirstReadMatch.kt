package com.taskmind.core

/**
 * Pairs the tasks the phone made on its first read of a message (see
 * [QuickRead]) with what the model found when it read the same message.
 *
 * The model has the last word. A first read the model agrees with - a
 * confident task quoting the same words - is kept and given the model's
 * better title and date, so the notification the user already has stays the
 * right one. A first read the model does not back is withdrawn. Whatever the
 * model found that no first read covered goes through the funnel as usual.
 *
 * Pure, so the pairing is tested rather than discovered on a phone.
 */
object FirstReadMatch {

    /** A task the phone made, and the words it quoted. */
    data class Read(val taskId: String, val evidence: String?)

    /** One task the model found, by its place in the model's answer. */
    data class Found(val index: Int, val evidence: String?, val confident: Boolean)

    data class Plan(
        /** First read task id -> the index of the model's task that confirms it. */
        val confirm: Map<String, Int>,
        /** First reads the model did not back. */
        val withdraw: List<String>,
    )

    /** How much two quotes must share to be the same ask. */
    const val MIN_OVERLAP = 0.4

    fun plan(reads: List<Read>, found: List<Found>): Plan {
        val confirm = linkedMapOf<String, Int>()
        val taken = mutableSetOf<Int>()
        val candidates = found.filter { it.confident }

        // Best quote overlap first, so two asks in one burst pair up right.
        val pairs = reads.flatMap { r -> candidates.map { f -> Triple(r, f, overlap(r.evidence, f.evidence)) } }
            .filter { it.third >= MIN_OVERLAP }
            .sortedByDescending { it.third }
        for ((read, f, _) in pairs) {
            if (read.taskId in confirm || f.index in taken) continue
            confirm[read.taskId] = f.index
            taken += f.index
        }

        // One first read and one confident task left: the same ask in other
        // words ("can you sign it?" read alone, "sign the PO" read with the
        // lines before it). Anything less clear-cut is not guessed at.
        val leftReads = reads.filter { it.taskId !in confirm }
        val leftFound = candidates.filter { it.index !in taken }
        if (leftReads.size == 1 && leftFound.size == 1) {
            confirm[leftReads.single().taskId] = leftFound.single().index
        }

        return Plan(confirm, reads.map { it.taskId }.filter { it !in confirm })
    }

    /**
     * Shared words over the shorter quote's words: 1.0 when one quote
     * contains the other. Letters and digits only, in any script.
     */
    fun overlap(a: String?, b: String?): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / minOf(x.size, y.size)
    }

    private fun words(s: String?): Set<String> =
        s.orEmpty().lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()
}
