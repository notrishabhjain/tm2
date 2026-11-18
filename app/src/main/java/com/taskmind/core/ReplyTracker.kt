package com.taskmind.core

/**
 * Turns what an accessibility service sees in WhatsApp into "the user sent
 * this message to that chat" (v1.35). Pure, so the rules are tested here
 * rather than discovered on a phone.
 *
 * WhatsApp shows the chat's name in its header and the message being typed
 * in its input box. When the send button is pressed, the last text the box
 * held is the message that went. Nothing is recorded without a send press, so
 * a draft that is typed and deleted, or text in the search bar, is never kept.
 */
class ReplyTracker(private val now: () -> Long = System::currentTimeMillis) {

    data class Reply(val chat: String, val text: String, val at: Long)

    private var chat: String? = null
    private var typed: String = ""
    /** What the box held just before it was emptied: WhatsApp clears it before the press is reported. */
    private var cleared: String? = null
    private var clearedAt = 0L
    private var last: Reply? = null

    /** The open chat's name, from the header; null when no chat is open. */
    fun onChat(name: String?) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
        if (clean != chat) {
            typed = ""
            cleared = null
        }
        chat = clean
    }

    /** The input box changed. */
    fun onTyped(text: String?) {
        val t = text.orEmpty()
        if (t.isBlank() && typed.isNotBlank()) {
            cleared = typed
            clearedAt = now()
        } else if (t.isNotBlank()) {
            cleared = null
        }
        typed = t
    }

    /** The send button was pressed. Returns the reply to keep, or null. */
    fun onSend(): Reply? {
        val c = chat ?: return null
        val at = now()
        val text = typed.trim().ifEmpty {
            cleared?.takeIf { at - clearedAt <= CLEAR_WINDOW_MILLIS }?.trim().orEmpty()
        }
        typed = ""
        cleared = null
        if (text.isEmpty() || text.length > MAX_TEXT) return null
        // One press can be reported twice.
        last?.let { if (it.chat == c && it.text == text && at - it.at < DUPLICATE_MILLIS) return null }
        return Reply(c, text, at).also { last = it }
    }

    companion object {
        const val MAX_NAME = 80
        const val MAX_TEXT = 4000
        const val DUPLICATE_MILLIS = 3_000L
        const val CLEAR_WINDOW_MILLIS = 2_000L

        /** The packages the service listens to, and nothing else. */
        val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        /** View ids WhatsApp uses, by their last part, across recent versions. */
        val INPUT_IDS = setOf("entry")
        val SEND_IDS = setOf("send", "send_container")
        val HEADER_IDS = setOf("conversation_contact_name", "conversation_title")

        /** A send button found by what it says aloud, when its id changes. */
        fun looksLikeSend(description: CharSequence?): Boolean {
            val d = description?.toString()?.trim()?.lowercase() ?: return false
            return d == "send" || d == "भेजें" || d == "भेजा" || d == "send message"
        }

        fun idTail(viewId: String?): String? = viewId?.substringAfterLast('/')
    }
}
