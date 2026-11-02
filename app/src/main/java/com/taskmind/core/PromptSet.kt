package com.taskmind.core

/**
 * The three system prompts actually sent to the model, and where each one came
 * from.
 *
 * TaskMind's whole precision story rests on these prompts, so they are not
 * hidden constants: the user can read them, edit them, and see which ones they
 * have changed. [PromptSet] is what the extractor sends; [PromptSource] is what
 * the UI shows next to each one.
 */
data class PromptSet(
    val notificationSystem: String,
    val callSystem: String,
    val verifySystem: String,
    /** Last, with a default, so a set built before email existed still compiles and reads. */
    val emailSystem: String = Prompts.DEFAULT_EMAIL_SYSTEM,
) {
    companion object {
        val DEFAULT = PromptSet(
            notificationSystem = Prompts.DEFAULT_NOTIFICATION_SYSTEM,
            callSystem = Prompts.DEFAULT_CALL_SYSTEM,
            verifySystem = Prompts.DEFAULT_VERIFY_SYSTEM,
            emailSystem = Prompts.DEFAULT_EMAIL_SYSTEM,
        )
    }
}

enum class PromptKind(
    val key: String,
    val title: String,
    val purpose: String,
    /** What breaks if this prompt is edited carelessly. Shown in the editor. */
    val caution: String,
) {
    NOTIFICATION(
        key = "notification_system",
        title = "Message extraction",
        purpose = "Decides whether one incoming message is a task for you, and what that task says. " +
            "This is the prompt that runs on every WhatsApp or SMS message that gets past the pre-filter.",
        caution = "The GROUNDING paragraph is load-bearing. TaskMind checks the model's \"evidence\" " +
            "quote against the original message and drops the task if it cannot find it. Remove or weaken " +
            "that instruction and the model stops quoting accurately, so tasks start being discarded.",
    ),
    CALL(
        key = "call_system",
        title = "Call transcript extraction",
        purpose = "Pulls commitments out of a call transcript. Runs once per transcribed call, and once " +
            "per transcript you paste in by hand.",
        caution = "Same grounding rule as above. Also note the transcript will contain speech-recognition " +
            "errors — the instruction to skip garbled sections rather than guess is what stops invented tasks.",
    ),
    EMAIL(
        key = "email_system",
        title = "Email extraction",
        purpose = "Pulls the asks and promises out of one email - read from your mailbox, or from a mail " +
            "app's notification. Runs once per email that gets past the newsletter and robot filter.",
        caution = "Same grounding rule as the others: the quote must be copied from the email. The " +
            "\"WHOSE TASK IS IT\" section is what keeps a Cc or a request to a colleague off your list.",
    ),
    VERIFY(
        key = "verify_system",
        title = "Second-opinion review",
        purpose = "A second model call that reviews each candidate task before it is created. Costs one " +
            "extra call per batch and can be turned off entirely in Settings -> Accuracy.",
        caution = "This prompt is deliberately harsher than the extraction prompts. Softening it raises " +
            "recall and lowers precision, which is the opposite of the trade-off the rest of the app makes.",
    );

    fun defaultText(): String = when (this) {
        NOTIFICATION -> Prompts.DEFAULT_NOTIFICATION_SYSTEM
        CALL -> Prompts.DEFAULT_CALL_SYSTEM
        EMAIL -> Prompts.DEFAULT_EMAIL_SYSTEM
        VERIFY -> Prompts.DEFAULT_VERIFY_SYSTEM
    }

    fun textIn(set: PromptSet): String = when (this) {
        NOTIFICATION -> set.notificationSystem
        CALL -> set.callSystem
        EMAIL -> set.emailSystem
        VERIFY -> set.verifySystem
    }
}
