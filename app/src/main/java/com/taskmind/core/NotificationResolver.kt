package com.taskmind.core

/**
 * Spec 10.1 and 10.2 - deciding WHICH text and WHICH sender a notification
 * actually carries. Pure, so it is unit-tested rather than debugged on-device.
 *
 * The trap (spec 10.1): Android bundles unread messages. The expanded text
 * field accumulates ALL unread messages in a conversation and changes every
 * time a new one arrives. Fingerprinting that produces a fresh task per bundled
 * message, and feeds the model a growing wall of text.
 */
object NotificationResolver {

    /** One entry of a MessagingStyle bundle. */
    data class Message(val sender: String?, val text: String?, val timestamp: Long = 0)

    /** Everything read out of the notification extras, with no Android types. */
    data class Fields(
        val packageName: String,
        val title: String? = null,
        val text: String? = null,
        val bigText: String? = null,
        val textLines: List<String> = emptyList(),
        val conversationTitle: String? = null,
        val isGroupConversation: Boolean = false,
        val messages: List<Message> = emptyList(),
        val postTime: Long = 0,
        val isGroupSummary: Boolean = false,
        val isOngoing: Boolean = false,
        val isMediaStyle: Boolean = false,
    )

    data class Resolved(
        val messageText: String,
        val senderKey: String,
        val groupName: String?,
        val isGroup: Boolean,
        /** Which rule produced the text, for the activity log. */
        val textSource: String,
    )

    private val TITLE_WITH_GROUP = Regex("^(.+?)\\s*[@:]\\s*(.+)$")

    fun resolve(fields: Fields): Resolved? {
        val (text, textSource, messageSender) = resolveText(fields) ?: return null
        val (senderKey, groupName, isGroup) = resolveSender(fields, messageSender)
        if (text.isBlank()) return null
        return Resolved(
            messageText = text.trim(),
            senderKey = senderKey.trim(),
            groupName = groupName?.trim()?.takeIf { it.isNotEmpty() },
            isGroup = isGroup,
            textSource = textSource,
        )
    }

    /**
     * Resolution order (spec 10.1):
     *  1. the LAST entry of EXTRA_MESSAGES - a single message with its own
     *     sender, which is what WhatsApp provides
     *  2. EXTRA_TEXT_LINES, last line only
     *  3. EXTRA_TEXT
     *  4. EXTRA_BIG_TEXT, last resort, final line only
     *
     * That single latest message is used for BOTH the fingerprint and the model
     * input.
     */
    private fun resolveText(fields: Fields): Triple<String, String, String?>? {
        fields.messages.lastOrNull { !it.text.isNullOrBlank() }?.let { m ->
            return Triple(m.text!!, "EXTRA_MESSAGES.last", m.sender)
        }
        fields.textLines.lastOrNull { it.isNotBlank() }?.let { line ->
            return Triple(stripSenderPrefix(line), "EXTRA_TEXT_LINES.last", null)
        }
        fields.text?.takeIf { it.isNotBlank() }?.let { return Triple(it, "EXTRA_TEXT", null) }
        fields.bigText?.takeIf { it.isNotBlank() }?.let { big ->
            val lastLine = big.split('\n').lastOrNull { it.isNotBlank() } ?: return null
            return Triple(stripSenderPrefix(lastLine), "EXTRA_BIG_TEXT.lastLine", null)
        }
        return null
    }

    /**
     * Inbox-style lines are often "Sender: message". Keeping the prefix would
     * poison both the fingerprint and the evidence check.
     */
    private fun stripSenderPrefix(line: String): String {
        val idx = line.indexOf(": ")
        if (idx in 1..40) {
            val candidate = line.substring(idx + 2)
            if (candidate.isNotBlank()) return candidate
        }
        return line
    }

    /**
     * Rules, in order (spec 10.2).
     */
    private fun resolveSender(fields: Fields, messageSender: String?): Triple<String, String?, Boolean> {
        if (fields.messages.isNotEmpty()) {
            val sender = messageSender?.takeIf { it.isNotBlank() }
                ?: fields.title.orEmpty()
            val group = if (fields.isGroupConversation) {
                fields.conversationTitle ?: fields.title
            } else {
                null
            }
            return Triple(sender, group, fields.isGroupConversation)
        }

        val conversationTitle = fields.conversationTitle?.takeIf { it.isNotBlank() }
        val title = fields.title?.takeIf { it.isNotBlank() }

        if (conversationTitle != null && conversationTitle != title) {
            return Triple(title.orEmpty(), conversationTitle, true)
        }

        if (title != null) {
            val match = TITLE_WITH_GROUP.find(title)
            if (match != null) {
                return Triple(match.groupValues[1], match.groupValues[2], true)
            }
            return Triple(title, null, false)
        }

        return Triple("", null, false)
    }
}
