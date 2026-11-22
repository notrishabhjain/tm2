package com.taskmind.capture

import android.content.Context
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.Normalize
import com.taskmind.core.SourceRef
import com.taskmind.core.SourceType
import com.taskmind.core.Stage
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.memory.MemoryStore
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The morning catch-up for priority chats (v1.37).
 *
 * Notifications are the only way to see WhatsApp as it happens, and they miss
 * things: a muted chat shows none, a busy group rolls messages into one, a
 * phone that killed the listener sees nothing for a while. WhatsApp's nightly
 * backup has everything. After each refresh, the messages other people wrote
 * in a priority chat since the last catch-up (at most three days back) that
 * no notification already brought in are read exactly as if they had arrived
 * now - through the same pipeline, with the same checks.
 */
class PriorityCatchUp(private val container: AppContainer) {

    private val prefs get() = container.context.getSharedPreferences("priority_catch_up", Context.MODE_PRIVATE)

    /** Returns how many messages were queued to be read. */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        val settings = container.settingsRepository.current()
        if (settings.priorityChats.isEmpty() || !settings.cloudConsent || !settings.captureNotifications) return@withContext 0
        val now = System.currentTimeMillis()
        val through = prefs.getLong(K_THROUGH, 0L)
        val since = maxOf(through, now - MAX_AGE_MILLIS)
        val dao = container.database.rawCaptureDao()
        var queued = 0
        var newest = since
        var capped = false

        for (name in settings.priorityChats) {
            val messages = runCatching { container.memoryStore.othersSince(name, since, PER_CHAT) }.getOrDefault(emptyList())
            for (m in messages) {
                if (queued >= MAX_PER_RUN) {
                    capped = true
                    break
                }
                newest = maxOf(newest, m.at)
                val body = m.body.trim()
                if (body.length < 2 || body.startsWith("<") && body.endsWith(">")) continue
                if (seenLive(m, body)) continue
                val senderKey = if (m.isGroup) m.sender else m.chat
                val ref = SourceRef.forNotification(PACKAGE, senderKey, body)
                if (dao.bySourceRef(SourceType.NOTIFICATION.name, ref) != null) continue
                val inserted = dao.insert(
                    RawCaptureEntity(
                        id = UUID.randomUUID().toString(),
                        sourceType = SourceType.NOTIFICATION,
                        sourceRef = ref,
                        sourceApp = PACKAGE,
                        sourceLabel = buildString {
                            append(senderKey)
                            if (m.isGroup) append(" in ").append(m.chat)
                            append(" - WhatsApp (from the backup)")
                        },
                        rawText = body,
                        capturedAt = now,
                        occurredAt = m.at,
                        state = CaptureState.PENDING_EXTRACTION,
                        contextLabel = buildString {
                            append("sender=").append(senderKey)
                            if (m.isGroup) append("|group=").append(m.chat)
                            append("|isGroup=").append(m.isGroup)
                        },
                    ),
                )
                if (inserted != -1L) queued++
            }
        }

        // When capped, keep the start where it was: the rest is picked up next
        // time, and what was queued now is skipped then by its source ref.
        if (!capped) prefs.edit().putLong(K_THROUGH, maxOf(newest, through)).apply()
        if (queued > 0) {
            container.logger.write(
                Stage.CAPTURE,
                LogLevel.INFO,
                "priority catch-up: $queued message(s) from the backup that no notification showed",
                settings.priorityChats.joinToString(),
            )
            Scheduler.enqueueExtraction(container.context)
        }
        queued
    }

    /** Whether a notification already brought this message in, around the time it was sent. */
    private suspend fun seenLive(m: MemoryStore.Message, body: String): Boolean {
        val want = Normalize.forHash(body)
        return LIVE_PACKAGES.any { pkg ->
            container.database.rawCaptureDao()
                .recentFromApp(pkg, m.at - WINDOW_MILLIS, m.at + WINDOW_MILLIS, excludeId = "", limit = 300)
                .any { it.rawText?.let(Normalize::forHash) == want }
        }
    }

    private companion object {
        const val K_THROUGH = "through"
        const val PACKAGE = "com.whatsapp"
        val LIVE_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        const val MAX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1000
        const val WINDOW_MILLIS = 20L * 60 * 1000
        const val PER_CHAT = 150
        const val MAX_PER_RUN = 120
    }
}
