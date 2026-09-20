package com.taskmind.relate

import com.taskmind.core.DateResolver

/**
 * Which tasks belong together, and which are the same task twice.
 *
 * WHY THIS IS RULES AND NOT A MODEL
 *
 * The same argument as the auto-tagger. Rules are free, instant, explainable
 * when a grouping looks wrong, and - the deciding one - they work backwards
 * over every task already in the database. They also run with no network, no
 * key and no consent prompt, which matters for a feature that would otherwise
 * have to send the whole task list somewhere to answer "are these two the
 * same thing".
 *
 * WHAT COUNTS AS RELATED
 *
 * Four signals, weighted so that any one strong one is enough and two weak
 * ones together are also enough:
 *
 *   the same person asked                       0.45
 *   the same group chat                         0.30
 *   the same project                            0.20
 *   similar wording                       0 to 0.35
 *   a topic in common                           0.15
 *   due on the same day                         0.10
 *
 * Anything at or above [RELATED] is a link. "The same person asked" clears the
 * bar on its own, on purpose: three things Sharma Ji wants are worth seeing
 * together even when they have nothing else in common, because they are one
 * phone call to deal with.
 *
 * Links are then followed transitively, so a bundle is a connected group
 * rather than only pairs. That can chain - A links B, B links C, A and C
 * barely touch - which is the accepted cost of not asking the user to build
 * groups by hand.
 *
 * Pure Kotlin, so it is testable on a machine with no Android toolchain. The
 * caller converts its tasks into [Relatable] and back.
 */
object TaskRelations {

    /** A task, reduced to the parts that decide what it is related to. */
    data class Relatable(
        val id: String,
        val title: String,
        val dueAt: Long?,
        val projectId: String? = null,
        /** From the auto-tagger: who asked. */
        val person: String? = null,
        /** From the auto-tagger: which group chat. */
        val group: String? = null,
        /** From the auto-tagger: payment, document, meeting and so on. */
        val topics: List<String> = emptyList(),
    )

    /**
     * A group of related tasks.
     *
     * [reason] is shown to the user. A grouping nobody can explain is a
     * grouping nobody will trust enough to act on.
     */
    data class Bundle(
        val key: String,
        val label: String,
        val reason: String,
        val ids: List<String>,
    ) {
        val size: Int get() = ids.size
    }

    /** Two tasks that look like one task captured twice. */
    data class Duplicate(val keepId: String, val otherId: String, val overlap: Double)

    /** At or above this, two tasks are linked. */
    const val RELATED = 0.45

    private const val RELATED_POINTS = 45

    /** At or above this wording overlap, two tasks are the same task. */
    const val DUPLICATE = 0.8

    // -- scoring -------------------------------------------------------------

    /** How strongly two tasks belong together, from 0 to 1. */
    fun score(a: Relatable, b: Relatable): Double = points(a, b) / 100.0

    /**
     * The same thing in whole points, which is what the threshold is actually
     * compared against.
     *
     * Integers on purpose. Adding 0.30 and 0.15 as doubles gives
     * 0.44999999999999996, so a group and a topic together - a combination
     * chosen precisely because it should just clear 0.45 - would have missed
     * the bar by a rounding error and quietly stopped bundling anything.
     */
    private fun points(a: Relatable, b: Relatable): Int {
        if (a.id == b.id) return 0
        var points = 0

        if (!a.person.isNullOrBlank() && a.person.equals(b.person, ignoreCase = true)) points += 45
        if (!a.group.isNullOrBlank() && a.group.equals(b.group, ignoreCase = true)) points += 30
        if (!a.projectId.isNullOrBlank() && a.projectId == b.projectId) points += 20

        points += Math.round(35 * overlap(a.title, b.title)).toInt()

        if (a.topics.any { t -> b.topics.any { it.equals(t, ignoreCase = true) } }) points += 15
        if (sameDay(a.dueAt, b.dueAt)) points += 10

        return if (points > 100) 100 else points
    }

    /**
     * How much two titles say the same thing, from 0 to 1.
     *
     * Jaccard over meaningful words: shared words over total distinct words.
     * Stop-words are dropped in both English and romanised Hindi, because
     * "please send the invoice" and "invoice bhej dena" have exactly one word
     * in common that matters and four that do not.
     *
     * Two titles with nothing left after filtering score 0 rather than 1 -
     * "ok" and "haan ji" are not the same task, they are both nearly empty.
     */
    fun overlap(a: String, b: String): Double {
        val left = words(a)
        val right = words(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.count { it in right }
        val total = (left + right).size
        return if (total == 0) 0.0 else shared.toDouble() / total
    }

    // -- bundling ------------------------------------------------------------

    /**
     * Groups of two or more related tasks, largest first.
     *
     * Single-link: every task that links to any member of a group joins it.
     * Tasks that link to nothing are not returned at all - a bundle of one is
     * just a task, and showing it as a bundle would turn a useful view into
     * the same list with extra headings.
     */
    fun bundles(tasks: List<Relatable>): List<Bundle> {
        if (tasks.size < 2) return emptyList()

        val parent = IntArray(tasks.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var walk = i
            while (parent[walk] != walk) {
                val next = parent[walk]
                parent[walk] = root
                walk = next
            }
            return root
        }

        for (i in tasks.indices) {
            for (j in i + 1 until tasks.size) {
                if (points(tasks[i], tasks[j]) >= RELATED_POINTS) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<Relatable>>()
        for (i in tasks.indices) groups.getOrPut(find(i)) { mutableListOf() }.add(tasks[i])

        return groups.values
            .filter { it.size >= 2 }
            .map { describe(it) }
            // Biggest first, then stable by label so the order does not shuffle
            // between two renders of the same list.
            .sortedWith(compareByDescending<Bundle> { it.size }.thenBy { it.label })
    }

    /** The bundle one task sits in, if any. */
    fun bundleFor(taskId: String, tasks: List<Relatable>): Bundle? =
        bundles(tasks).firstOrNull { taskId in it.ids }

    /**
     * Names a group after whatever all of its members actually share.
     *
     * Checked in the order a person would find useful: who asked, then where,
     * then what kind of thing, then - failing all of that - the word the
     * titles have in common.
     */
    private fun describe(members: List<Relatable>): Bundle {
        val ids = members.map { it.id }
        val key = ids.sorted().joinToString("|")

        val person = members.first().person
        if (!person.isNullOrBlank() && members.all { it.person.equals(person, ignoreCase = true) }) {
            return Bundle(key, person, "All from ${person}", ids)
        }

        val group = members.first().group
        if (!group.isNullOrBlank() && members.all { it.group.equals(group, ignoreCase = true) }) {
            return Bundle(key, group, "All in ${group}", ids)
        }

        val topic = members.first().topics.firstOrNull { t ->
            members.all { m -> m.topics.any { it.equals(t, ignoreCase = true) } }
        }
        if (topic != null) return Bundle(key, topic.replaceFirstChar { it.uppercase() }, "Same topic", ids)

        val common = members.map { words(it.title) }
            .reduce { acc, next -> acc.intersect(next) }
            .firstOrNull()
        if (common != null) {
            return Bundle(key, common.replaceFirstChar { it.uppercase() }, "Similar wording", ids)
        }

        return Bundle(key, "Related", "Related tasks", ids)
    }

    // -- duplicates ----------------------------------------------------------

    /**
     * Pairs that look like the same task captured twice.
     *
     * Deliberately stricter than [RELATED]: this one suggests throwing
     * something away, and two tasks from the same person on the same day are
     * routinely two different tasks. It needs the wording itself to match.
     *
     * The older task is the one kept, because it is the one already carrying
     * any edits, reminders or sub-tasks.
     */
    fun duplicates(tasks: List<Relatable>): List<Duplicate> {
        val out = mutableListOf<Duplicate>()
        for (i in tasks.indices) {
            for (j in i + 1 until tasks.size) {
                val a = tasks[i]
                val b = tasks[j]
                val shared = overlap(a.title, b.title)
                if (shared < DUPLICATE) continue
                // A date that disagrees means two deadlines, which means two
                // tasks however similar the wording.
                if (a.dueAt != null && b.dueAt != null && !sameDay(a.dueAt, b.dueAt)) continue
                out += Duplicate(keepId = a.id, otherId = b.id, overlap = shared)
            }
        }
        return out.sortedByDescending { it.overlap }
    }

    // -- words ---------------------------------------------------------------

    /**
     * The meaningful words in a title, lower-cased and lightly stemmed.
     *
     * Combining marks are kept alongside letters and digits, and that is not a
     * detail: in Devanagari the vowel signs are marks, not letters, so
     * stripping everything that is not `isLetterOrDigit` turns "पैसे" into
     * "पस" - short enough to be dropped as noise. Hindi titles would then have
     * matched nothing at all.
     */
    fun words(text: String): Set<String> =
        text.lowercase()
            .split(*SEPARATORS)
            .map { part -> part.filter { isWordChar(it) } }
            .filter { it.length >= 3 && it !in STOPWORDS }
            .map { stem(it) }
            .toSet()

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() ||
            c.category == CharCategory.NON_SPACING_MARK ||
            c.category == CharCategory.COMBINING_SPACING_MARK

    /**
     * Trailing plurals only.
     *
     * A real stemmer would be better and is not worth a dependency here:
     * "payments" and "payment" is the case that actually comes up, and
     * anything cleverer risks collapsing two words that mean different things.
     */
    private fun stem(word: String): String =
        if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word

    private fun sameDay(a: Long?, b: Long?): Boolean {
        if (a == null || b == null) return false
        return DateResolver.startOfDay(a) == DateResolver.startOfDay(b)
    }

    private val SEPARATORS = charArrayOf(
        ' ', '\t', '\n', '\r', ',', '.', ';', ':', '!', '?', '-', '–', '—',
        '(', ')', '[', ']', '{', '}', '/', '\\', '"', '\'', '`', '|', '।',
    )

    /**
     * Words that carry no meaning for matching.
     *
     * English and romanised Hindi together, because a title captured from a
     * WhatsApp message is routinely half of each. Devanagari stop-words are
     * included for the same reason transcripts need them: Whisper returns
     * Devanagari for spoken Hindi.
     */
    private val STOPWORDS: Set<String> = setOf(
        // English
        "the", "and", "for", "with", "you", "your", "please", "kindly", "need",
        "needs", "will", "can", "could", "would", "should", "have", "has", "had",
        "are", "was", "were", "this", "that", "them", "they", "from", "about",
        "into", "out", "not", "but", "all", "any", "get", "got", "let", "make",
        "sure", "asap", "today", "tomorrow", "yesterday", "morning", "evening",
        "night", "day", "week", "month", "time", "also", "just", "very", "some",
        // Romanised Hindi
        "kar", "karo", "karna", "kare", "karke", "hai", "hain", "tha", "thi",
        "kya", "koi", "abhi", "aap", "aapka", "aapke", "mera", "mere", "meri",
        "unka", "unke", "iska", "iske", "nahi", "nahin", "haan", "bhi", "aur",
        "par", "wala", "wale", "jaldi", "zaroor", "zarur", "dena", "dijiye",
        "kijiye", "chahiye", "raha", "rahe", "rahi", "liye",
        // Devanagari
        "है", "हैं", "और", "कर", "करो", "करना", "दो", "दें", "लिए", "नहीं",
        "आप", "मेरा", "मेरे", "यह", "कृपया", "जल्दी", "अभी",
    )
}
