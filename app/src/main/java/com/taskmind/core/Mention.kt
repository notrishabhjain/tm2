package com.taskmind.core

/**
 * Was this message aimed at the user, or at somebody else in the room?
 *
 * THE PROBLEM THIS EXISTS FOR
 *
 * In a work group, most messages are one colleague asking another for an
 * update. The extraction prompt already has a rule for it - "in a group chat
 * naming a specific OTHER person as the doer, return isTask=false" - and it
 * could not apply it, because nowhere did the app tell the model what the user
 * is called. Asked to judge whether "Rishabh bhai update de dena" is aimed at
 * the reader, with no idea who the reader is, a model tuned to prefer recall
 * does the only sensible thing and says yes.
 *
 * So this answers the question cheaply and deterministically, before any
 * inference, and the answer is also handed to the model.
 *
 * WHAT IT CANNOT DO
 *
 * Android exposes no "you were mentioned" flag. WhatsApp renders a mention as
 * "@Name" or "@919812345678" inside the notification text and that is all
 * there is to go on, so this is text matching against names the user
 * configures. It will occasionally miss one - which is exactly why the policy
 * that uses it is a setting, with an "always watch this group" escape hatch,
 * rather than a silent default.
 *
 * Pure Kotlin, so every rule here is unit-tested rather than discovered on a
 * phone.
 */
object Mention {

    data class Result(
        /** The user's own name or number appears, with or without an @. */
        val mentionsMe: Boolean,
        /** @everyone, @all, "sabhi", and friends. */
        val addressedToAll: Boolean,
        /** An @handle that is not one of the user's - so somebody else is the doer. */
        val namesSomeoneElse: Boolean,
    ) {
        /** True when there is positive reason to think this concerns the user. */
        val forMe: Boolean get() = mentionsMe || addressedToAll
    }

    fun scan(text: String, myNames: Collection<String>): Result {
        val names = myNames.map { it.trim().lowercase() }.filter { it.length >= 2 }
        val lower = text.lowercase()

        val mine = names.any { name ->
            // "@Name" first: it is the unambiguous form, and it survives a name
            // that would otherwise be too common to match on its own.
            lower.contains("@$name") || containsWord(lower, name)
        }

        val toAll = BROADCAST.any { lower.contains(it) }

        // Only handles, not bare names: a bare name that is not the user's is
        // far too weak a signal to act on. "Tell Rahul I called" names Rahul
        // and is still the user's job.
        val others = handles(lower).any { handle -> names.none { handle == it || handle.startsWith(it) } }

        return Result(mentionsMe = mine, addressedToAll = toAll, namesSomeoneElse = others && !mine)
    }

    /**
     * The @handles in a message.
     *
     * A handle runs to the first space, which is right for "@919812345678" and
     * cuts "@Sharma Ji" short at "@sharma". That is deliberate: the prefix is
     * matched with startsWith above, so a multi-word name still matches its
     * owner, and a truncated stranger's handle is still a stranger's handle.
     */
    private fun handles(lowerText: String): List<String> =
        HANDLE.findAll(lowerText).map { it.groupValues[1] }.toList()

    private val HANDLE = Regex("@([^\\s@,.!?;:]{2,40})")

    /**
     * Word-boundary matching that works in Devanagari too.
     *
     * Java's \\b is defined over [A-Za-z0-9_], so a regex boundary would treat
     * every Hindi character as a boundary and match a name inside any word.
     * Char.isLetterOrDigit is Unicode-aware.
     */
    private fun containsWord(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val before = haystack.getOrNull(at - 1)
            val after = haystack.getOrNull(at + needle.length)
            val boundedLeft = before == null || !before.isLetterOrDigit()
            val boundedRight = after == null || !after.isLetterOrDigit()
            if (boundedLeft && boundedRight) return true
            from = at + 1
        }
    }

    /**
     * Addressed to the whole group.
     *
     * Explicit markers and a short list of phrases that mean "all of you" in
     * the two languages this app actually sees. Deliberately short: a loose
     * entry here readmits exactly the noise this is meant to remove. Bare
     * "all" and bare "team" are absent on purpose - "send all the files" and
     * "the team said yes" are not broadcasts.
     */
    private val BROADCAST: List<String> = listOf(
        "@everyone", "@all", "@channel", "@here", "@team",
        "everyone", "every one", "all of you", "you all", "anyone",
        "aap sab", "aap sabhi", "sab log", "sabhi log", "sabhi", "sab please",
        "आप सब", "सभी", "सब लोग",
    )
}
