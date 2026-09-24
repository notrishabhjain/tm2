package com.taskmind.profiles

import com.taskmind.tagging.AutoTagger

/** Which half of your life a task belongs to. */
enum class Profile(val label: String) {
    PERSONAL("Personal"),
    WORK("Work"),
    ;

    companion object {
        /** A stored name that no longer matches an entry falls back rather than crashing. */
        fun byName(name: String?): Profile? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Sorting tasks into personal and professional, without a database column.
 *
 * DERIVED, NOT STORED - AGAIN
 *
 * The same decision as the auto-tagger, for the same reasons. A task's profile
 * is computed from who asked and which chat it came from, both of which the
 * tagger already recovers from the source label. What is stored is one small
 * map of person-or-group to profile.
 *
 * The payoff is retroactive: classify Sharma Ji as work and every task he has
 * ever sent you becomes a work task immediately, including the ones captured
 * months ago. A column on the task table would only ever have been filled in
 * for tasks captured after the change, and would have needed a migration to
 * the frozen schema to get there.
 *
 * Pure Kotlin, so the rules are unit-tested rather than discovered on a phone.
 */
object ProfileRules {

    /**
     * The classification itself: two sets of names, lower-cased.
     *
     * Sets rather than a map because that is what a preferences store can hold
     * natively, and because "in neither" is the normal state - most people you
     * message are never classified, and their tasks should simply appear
     * everywhere rather than being guessed at.
     */
    data class Book(
        val personal: Set<String> = emptySet(),
        val work: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = personal.isEmpty() && work.isEmpty()

        fun profileOf(subject: String): Profile? {
            val key = subject.trim().lowercase()
            if (key.isEmpty()) return null
            return when {
                work.contains(key) -> Profile.WORK
                personal.contains(key) -> Profile.PERSONAL
                else -> null
            }
        }
    }

    /**
     * The names in a task that can carry a classification: who said it, and
     * which group it was said in.
     *
     * Not the app, not the topic. "WhatsApp" is not a side of your life, and
     * marking the payment topic as work would put your electricity bill there.
     */
    fun subjects(tags: List<AutoTagger.Tag>): List<String> =
        tags.filter { it.kind == AutoTagger.Kind.PERSON || it.kind == AutoTagger.Kind.GROUP }
            .map { it.value }

    /**
     * A task's profile, or null when nothing about it has been classified.
     *
     * The GROUP wins over the PERSON when they disagree. A colleague messaging
     * you inside a work group is doing so at work, whatever you decided about
     * them personally - and the reverse, a work contact in the family group,
     * is the case where you would be annoyed to see it filed as work.
     */
    fun of(tags: List<AutoTagger.Tag>, book: Book): Profile? {
        if (book.isEmpty) return null
        val group = tags.firstOrNull { it.kind == AutoTagger.Kind.GROUP }?.value
        group?.let { book.profileOf(it)?.let { profile -> return profile } }
        val person = tags.firstOrNull { it.kind == AutoTagger.Kind.PERSON }?.value
        return person?.let { book.profileOf(it) }
    }

    /**
     * Whether a task belongs in a given view.
     *
     * An unclassified task shows in BOTH profiles rather than neither. Hiding
     * a real commitment because a contact was never sorted would make the
     * feature dangerous: the first time you miss something because of it, you
     * stop trusting the list. Classifying is an improvement to the view, never
     * a precondition for seeing your own tasks.
     */
    fun matches(tags: List<AutoTagger.Tag>, book: Book, viewing: Profile?): Boolean {
        if (viewing == null) return true
        val profile = of(tags, book) ?: return true
        return profile == viewing
    }
}
