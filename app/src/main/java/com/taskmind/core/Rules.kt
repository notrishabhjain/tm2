package com.taskmind.core

/**
 * Rules the user approves, and the ideas for them TaskMind works out from
 * what they kept and dropped.
 *
 * Nothing here is applied on its own. An idea is a suggestion with the
 * numbers behind it ("you dropped 6 of its tasks and kept none"); only a rule
 * the user approved reaches the prompt, every approved rule is listed where
 * they can remove it, and a rejected idea is not suggested again.
 *
 * The ideas come from counting, not from a model: they cost nothing, they
 * can always say exactly why, and they are the same every time. The user can
 * add a rule in their own words for anything counting cannot see.
 */
object Rules {

    enum class Kind {
        /** Messages in this group are not the user's tasks unless they name the user. */
        IGNORE_GROUP,
        /** Messages from this person are not tasks. */
        IGNORE_SENDER,
        /** This person matters: their requests get the benefit of the doubt, and rank higher. */
        KEY_PERSON,
        /** Written by the user. */
        CUSTOM,
    }

    enum class State { APPROVED, REJECTED }

    data class Rule(val kind: Kind, val subject: String, val state: State) {
        val id: String get() = id(kind, subject)
    }

    /** A rule the user has not decided on yet, with why it is suggested. */
    data class Idea(val kind: Kind, val subject: String, val why: String) {
        val id: String get() = id(kind, subject)
    }

    fun id(kind: Kind, subject: String): String = "${kind.name}:${subject.trim().lowercase()}"

    const val WINDOW_DAYS = 30
    private const val DAY = 24L * 60 * 60 * 1000

    /** At least this many "no"s, and no "yes", before ignoring a group. */
    const val IGNORE_GROUP_MIN = 4
    /** The same for one person in a one-to-one chat. */
    const val IGNORE_SENDER_MIN = 3
    /** At least this many "yes"es, and no "no", before calling someone key. */
    const val KEY_PERSON_MIN = 4

    const val MAX_CUSTOM = 200

    /**
     * New ideas from the last [WINDOW_DAYS] of feedback, strongest first.
     * Anything already decided - approved or rejected - is not suggested.
     */
    fun ideas(events: List<Feedback.Event>, decided: List<Rule>, now: Long): List<Idea> {
        val recent = events.filter { it.at >= now - WINDOW_DAYS * DAY }
        val taken = decided.map { it.id }.toSet()
        val out = mutableListOf<Pair<Int, Idea>>()

        recent.filter { !it.group.isNullOrBlank() }
            .groupBy { it.group!!.trim().lowercase() }
            .forEach { (_, rows) ->
                val no = rows.count { !it.kind.positive }
                val yes = rows.count { it.kind.positive }
                if (no >= IGNORE_GROUP_MIN && yes == 0) {
                    val name = rows.first().group!!.trim()
                    out += no to Idea(Kind.IGNORE_GROUP, name, "You dropped $no of its tasks this month and kept none.")
                }
            }

        recent.filter { it.group.isNullOrBlank() && !it.person.isNullOrBlank() }
            .groupBy { it.person!!.trim().lowercase() }
            .forEach { (_, rows) ->
                val no = rows.count { !it.kind.positive }
                val yes = rows.count { it.kind.positive }
                if (no >= IGNORE_SENDER_MIN && yes == 0) {
                    val name = rows.first().person!!.trim()
                    out += no to Idea(Kind.IGNORE_SENDER, name, "You dropped $no of their tasks this month and kept none.")
                }
            }

        // Key people are judged across groups and one-to-one chats alike:
        // someone whose asks you always do is key wherever they ask.
        recent.filter { !it.person.isNullOrBlank() }
            .groupBy { it.person!!.trim().lowercase() }
            .forEach { (_, rows) ->
                val no = rows.count { !it.kind.positive }
                val yes = rows.count { it.kind.positive }
                if (yes >= KEY_PERSON_MIN && no == 0) {
                    val name = rows.first().person!!.trim()
                    out += yes to Idea(Kind.KEY_PERSON, name, "You did or kept all $yes of their tasks this month.")
                }
            }

        return out.filter { it.second.id !in taken }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    /** What the model is told for one approved rule. */
    fun sentence(rule: Rule): String = when (rule.kind) {
        Kind.IGNORE_GROUP -> "Messages in the group \"${rule.subject}\" are not the user's tasks unless they name the user."
        Kind.IGNORE_SENDER -> "Messages from \"${rule.subject}\" are not tasks."
        Kind.KEY_PERSON -> "${rule.subject} is a key person: give their requests the benefit of the doubt."
        Kind.CUSTOM -> rule.subject.trim()
    }

    /** How the same rule reads on the rules page. */
    fun label(kind: Kind, subject: String): String = when (kind) {
        Kind.IGNORE_GROUP -> "Ignore the group \"$subject\" unless it names you"
        Kind.IGNORE_SENDER -> "Ignore messages from $subject"
        Kind.KEY_PERSON -> "Treat $subject as a key person"
        Kind.CUSTOM -> subject.trim()
    }

    /** Most rules sent with a prompt, newest kept. */
    const val MAX_IN_PROMPT = 25

    /**
     * The approved rules, ahead of the message. Empty when there are none,
     * for the same reason About me is: an empty heading invites a guess.
     */
    fun promptBlock(rules: List<Rule>): String {
        val approved = rules.filter { it.state == State.APPROVED }.takeLast(MAX_IN_PROMPT)
        if (approved.isEmpty()) return ""
        return buildString {
            append("Rules the user approved:\n")
            approved.forEach { append("- ").append(sentence(it)).append('\n') }
            append('\n')
        }
    }

    /** Approved key people, for ranking. */
    fun keyPeople(rules: List<Rule>): Set<String> =
        rules.filter { it.kind == Kind.KEY_PERSON && it.state == State.APPROVED }.map { it.subject }.toSet()

    // -- storage, one rule per line ---------------------------------------------

    private const val SEP = '\u001F'

    fun encode(rule: Rule): String = listOf(rule.kind.name, rule.state.name, rule.subject.replace(SEP, ' ').replace('\n', ' '))
        .joinToString(SEP.toString())

    fun decode(line: String): Rule? {
        val parts = line.split(SEP, limit = 3)
        if (parts.size != 3) return null
        val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
        val state = State.entries.firstOrNull { it.name == parts[1] } ?: return null
        return parts[2].trim().takeIf { it.isNotEmpty() }?.let { Rule(kind, it, state) }
    }

    /** Records a decision, replacing any earlier one on the same rule. */
    fun decide(rules: List<Rule>, rule: Rule): List<Rule> = rules.filter { it.id != rule.id } + rule

    fun remove(rules: List<Rule>, id: String): List<Rule> = rules.filter { it.id != id }
}
