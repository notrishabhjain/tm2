package com.taskmind.core

/**
 * People and groups the user marked as a priority (v1.37).
 *
 * What it changes, everywhere at once:
 *  - a group marked priority is read in full, whatever the group rule says;
 *  - the model is told the chat matters, so a borderline ask becomes a task;
 *  - a task from it skips the Inbox and the maybes, goes straight on the
 *    list at High priority or above, and alerts at once, heads-up;
 *  - the nightly WhatsApp backup is checked for its messages that no
 *    notification showed (a muted chat, a missed notification).
 *
 * Names are matched as the chat shows them, ignoring case and outer spaces.
 */
object PriorityChats {

    fun key(name: String): String = name.trim().lowercase()

    /** A one-to-one chat is matched by the person; a group message by the group. */
    fun matches(priority: Set<String>, sender: String?, group: String?): Boolean {
        if (priority.isEmpty()) return false
        val keys = priority.map(::key).toSet()
        val chat = group?.takeIf { it.isNotBlank() } ?: sender
        return chat != null && key(chat) in keys
    }

    fun bump(p: Priority): Priority = when (p) {
        Priority.LOW, Priority.MEDIUM -> Priority.HIGH
        else -> p
    }

    /** Added to the rules the model reads for a message from a priority chat. */
    const val RULE: String =
        "PRIORITY CHAT: the user marked this person or group as a priority. Anything in this message that " +
            "asks, expects or reminds the user to do something, or that the user promised, is a task - do not " +
            "drop a real ask as too vague or too small. Plain chatter is still not a task."

    fun withRule(rules: String): String = if (rules.isBlank()) RULE else "$RULE\n$rules"
}
