package com.taskmind.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.taskmind.core.LogLevel
import com.taskmind.core.ReplyTracker
import com.taskmind.core.Stage
import com.taskmind.data.memory.MemoryStore
import com.taskmind.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps the replies the user sends in WhatsApp (v1.35), so the memory holds
 * both sides of a chat and not only what notifications showed.
 *
 * Listens to WhatsApp and WhatsApp Business only (see the service's config).
 * It records a message only when the send button is pressed, with the chat's
 * name from the header. What it keeps goes into the memory on the phone and
 * nowhere else; it reads no other app and sends nothing anywhere.
 */
class ReplyCaptureService : AccessibilityService() {

    private val tracker = ReplyTracker()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in ReplyTracker.PACKAGES) return
        runCatching {
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> tracker.onChat(header(pkg))
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (e.isPassword) return
                    val id = ReplyTracker.idTail(e.source?.viewIdResourceName)
                    val isInput = id in ReplyTracker.INPUT_IDS ||
                        (id == null && e.className?.toString() == "android.widget.EditText")
                    if (isInput) tracker.onTyped(e.text.joinToString(""))
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val source = e.source
                    val id = ReplyTracker.idTail(source?.viewIdResourceName)
                    if (id in ReplyTracker.SEND_IDS || ReplyTracker.looksLikeSend(source?.contentDescription ?: e.contentDescription)) {
                        tracker.onChat(header(pkg))
                        tracker.onSend()?.let(::keep)
                    }
                }
            }
        }
    }

    /** The open chat's name, or null when WhatsApp is not showing a chat. */
    private fun header(pkg: String): String? {
        val root = rootInActiveWindow ?: return null
        for (id in ReplyTracker.HEADER_IDS) {
            val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull()
            val text = node?.text?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) return text
        }
        return null
    }

    private fun keep(reply: ReplyTracker.Reply) {
        val container = AppContainer.get(applicationContext)
        container.applicationScope.launch(Dispatchers.IO) {
            runCatching {
                val memory = container.memoryStore
                val isGroup = memory.chatsNamed(reply.chat, groups = true, limit = 1)
                    .firstOrNull { it.chat.equals(reply.chat, ignoreCase = true) }?.isGroup ?: false
                memory.insertAll(
                    listOf(
                        MemoryStore.Message(
                            source = MemoryStore.SOURCE_REPLY,
                            ext = "${reply.at}:${reply.chat.hashCode()}:${reply.text.hashCode()}",
                            chat = reply.chat,
                            isGroup = isGroup,
                            sender = "You",
                            fromMe = true,
                            at = reply.at,
                            body = reply.text,
                        ),
                    ),
                )
            }.onFailure {
                container.logger.write(Stage.CAPTURE, LogLevel.WARN, "could not keep a sent reply", it.toString())
            }
        }
    }

    override fun onInterrupt() = Unit
}
