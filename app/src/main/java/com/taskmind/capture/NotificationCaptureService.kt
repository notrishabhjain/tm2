package com.taskmind.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.taskmind.core.LogLevel
import com.taskmind.core.NotificationResolver
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spec 10 - notification capture, and spec 11.2 - the host process for call
 * detection.
 *
 * Why the call-log observer lives HERE (spec 11.2): a NotificationListenerService
 * is system-bound and is therefore the only context that can reliably start a
 * foreground service from the background on HyperOS. Hosting the observer and
 * the recovery sweep in this process is what makes call detection work at all.
 *
 * Every entry point is wrapped in a top-level try/catch. An uncaught exception
 * kills the notification listener, which kills call detection with it.
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var container: AppContainer

    private var callLogObserver: ContentObserver? = null
    private var lastSweepAt = 0L

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
    }

    override fun onListenerConnected() {
        try {
            super.onListenerConnected()
            container.logger.post(Stage.SYSTEM, LogLevel.INFO, "notification listener connected")
            registerCallLogObserver()
            Scheduler.ensurePeriodicWork(this)
            Scheduler.ensureWatchdog(this)
            replayActiveNotifications()
            scope.launch { container.callPipeline.sweepCallLog("listener connected") }
        } catch (t: Throwable) {
            container.logger.post(Stage.SYSTEM, LogLevel.ERROR, "onListenerConnected failed", t.toString())
        }
    }

    override fun onListenerDisconnected() {
        try {
            super.onListenerDisconnected()
            container.logger.post(Stage.SYSTEM, LogLevel.WARN, "notification listener disconnected - requesting rebind")
            unregisterCallLogObserver()
            // Spec 10.4: ask the system to bind us again rather than waiting.
            // requestRebind is a static on NotificationListenerService.
            requestRebind(
                ComponentName(this, NotificationCaptureService::class.java),
            )
        } catch (t: Throwable) {
            container.logger.post(Stage.SYSTEM, LogLevel.ERROR, "onListenerDisconnected failed", t.toString())
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            handle(sbn)
            maybeSweepCallLog()
        } catch (t: Throwable) {
            // An uncaught exception here kills the listener and takes call
            // detection down with it. Nothing escapes.
            container.logger.post(Stage.CAPTURE, LogLevel.ERROR, "onNotificationPosted failed", t.toString())
        }
    }

    override fun onDestroy() {
        unregisterCallLogObserver()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- capture

    private fun handle(sbn: StatusBarNotification) {
        val fields = readFields(sbn) ?: return
        val settings = container.cachedSettings
        val appLabel = container.appLabel(sbn.packageName)

        // Spec 10.4: the row must exist before this process can die, so the
        // write is synchronous. It is a single indexed insert; the cost is
        // measured in milliseconds and the alternative is a lost commitment.
        val outcome = runBlocking {
            withTimeoutOrNull(SYNC_WRITE_TIMEOUT_MILLIS) {
                container.captureCoordinator.handleNotification(
                    fields = fields,
                    appLabel = appLabel,
                    settings = settings,
                    ownPackageName = packageName,
                )
            }
        }

        if (outcome is CaptureCoordinator.Outcome.Captured) {
            Scheduler.enqueueExtraction(this)
        }
    }

    private fun readFields(sbn: StatusBarNotification): NotificationResolver.Fields? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val messages = runCatching {
            Notification.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.map { m ->
                    NotificationResolver.Message(
                        sender = m.senderPerson?.name?.toString() ?: m.sender?.toString(),
                        text = m.text?.toString(),
                        timestamp = m.timestamp,
                    )
                }
                .orEmpty()
        }.getOrDefault(emptyList())

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() }
            .orEmpty()

        val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

        return NotificationResolver.Fields(
            packageName = sbn.packageName.orEmpty(),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = textLines,
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            messages = messages,
            postTime = sbn.postTime,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isMediaStyle = isMedia,
        )
    }

    /**
     * Spec 10.4: on connect, replay anything currently posted whose fingerprint
     * we have not seen. This is what recovers messages that arrived while the
     * listener was unbound.
     */
    private fun replayActiveNotifications() {
        scope.launch {
            val active = runCatching { activeNotifications }.getOrNull() ?: return@launch
            var replayed = 0
            for (sbn in active) {
                runCatching {
                    val fields = readFields(sbn) ?: return@runCatching
                    val outcome = container.captureCoordinator.handleNotification(
                        fields = fields,
                        appLabel = container.appLabel(sbn.packageName),
                        settings = container.cachedSettings,
                        ownPackageName = packageName,
                    )
                    if (outcome is CaptureCoordinator.Outcome.Captured) replayed++
                }
            }
            if (replayed > 0) {
                container.logger.write(Stage.CAPTURE, LogLevel.INFO, "replayed $replayed active notification(s)")
                Scheduler.enqueueExtraction(this@NotificationCaptureService)
            }
        }
    }

    // -------------------------------------------------- call-log observer

    private fun registerCallLogObserver() {
        if (callLogObserver != null) return
        if (!container.callPipeline.hasCallLogPermission()) {
            container.logger.post(Stage.CALL, LogLevel.WARN, "call-log observer not registered - READ_CALL_LOG missing")
            return
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scope.launch {
                    runCatching {
                        val count = container.callPipeline.sweepCallLog("call-log observer")
                        if (count > 0) Scheduler.enqueueCallDiscovery(this@NotificationCaptureService)
                    }
                }
            }
        }
        runCatching {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            callLogObserver = observer
            container.logger.post(Stage.CALL, LogLevel.INFO, "call-log observer registered")
        }
    }

    private fun unregisterCallLogObserver() {
        callLogObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        callLogObserver = null
    }

    /**
     * Spec 11.2 recovery path: a sweep triggered by incoming notification
     * traffic. Throttled, because notification traffic is not scarce.
     */
    private fun maybeSweepCallLog() {
        val now = System.currentTimeMillis()
        if (now - lastSweepAt < SWEEP_THROTTLE_MILLIS) return
        lastSweepAt = now
        scope.launch {
            runCatching {
                val count = container.callPipeline.sweepCallLog("notification traffic")
                if (count > 0) Scheduler.enqueueCallDiscovery(this@NotificationCaptureService)
            }
        }
    }

    companion object {
        private const val SYNC_WRITE_TIMEOUT_MILLIS = 2_000L
        private const val SWEEP_THROTTLE_MILLIS = 60_000L

        /** Used by the status screen to report the listener's real state. */
        fun isEnabled(context: android.content.Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return flat.split(':').any { it.contains(context.packageName) }
        }

        fun settingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
