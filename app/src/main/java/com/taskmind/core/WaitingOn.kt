package com.taskmind.core

/**
 * Tasks where the next move is somebody else's.
 *
 * "Abhishek will confirm by tomorrow" is not something you do; it is
 * something you chase if it does not happen. The extraction prompts already
 * write these as "Check Abhishek confirmed the FARPS request", and the call
 * pipeline tags them "waiting-on" when the model says the promise was the
 * other party's. This recognises both, so they can sit in their own list -
 * the people you are waiting on, rather than mixed in with what you owe.
 *
 * Derived, never stored, like every other grouping in the app: it applies to
 * tasks captured before it existed, and needs no change to the database.
 */
object WaitingOn {

    /** The tag the call pipeline puts on other people's promises. */
    const val TAG = "waiting-on"

    private val DONE_WORDS =
        "(confirmed|sent|shared|replied|responded|paid|delivered|called( back)?|done|completed|" +
            "finished|signed|approved|uploaded|submitted|fixed|arranged|booked|forwarded|got back)"

    private val TITLE = listOf(
        // "Follow up with Rahul on the quote", "Follow up on the invoice"
        Regex("^follow[ -]?up\\b", RegexOption.IGNORE_CASE),
        // "Chase Priya for the signed copy"
        Regex("^chase\\b", RegexOption.IGNORE_CASE),
        // "Check if the vendor sent the PO", "Check whether ..."
        Regex("^check (if|whether)\\b", RegexOption.IGNORE_CASE),
        // "Check Abhishek confirmed the FARPS request",
        // "Check the vendor has sent the PO"
        Regex("^check (that )?[\\p{L}.' ]{1,40}? (has |have |had )?$DONE_WORDS\\b", RegexOption.IGNORE_CASE),
        // "Make sure Rahul sends the deck"
        Regex("^make sure [\\p{L}.']+ ", RegexOption.IGNORE_CASE),
        // "Remind Sharma ji to send the bill"
        Regex("^remind [\\p{L}.' ]{1,40}? to\\b", RegexOption.IGNORE_CASE),
        // "Wait for the client's approval", "Waiting on legal"
        Regex("^wait(ing)? (for|on)\\b", RegexOption.IGNORE_CASE),
    )

    fun matches(title: String, tags: List<String> = emptyList()): Boolean {
        if (tags.any { it.equals(TAG, ignoreCase = true) }) return true
        val t = title.trim()
        return TITLE.any { it.containsMatchIn(t) }
    }
}
