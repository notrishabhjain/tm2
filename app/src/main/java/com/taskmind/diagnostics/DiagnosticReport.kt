package com.taskmind.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.taskmind.BuildConfig
import com.taskmind.core.CaptureState
import com.taskmind.core.ModelCatalog
import com.taskmind.core.PromptKind
import com.taskmind.core.ProviderDiagnosis
import com.taskmind.data.settings.Settings
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything needed to diagnose this install, in one shareable file.
 *
 * There is no `adb logcat` on a phone that has never been plugged into a
 * computer, so the alternative to this is the user reading numbers off a screen
 * into a chat window - which is how a router-model permission error spent two
 * rounds being mistaken for a bug in the app.
 *
 * Two rules govern what goes in:
 *
 *  - API keys never appear. Not masked-but-recoverable, not the first
 *    characters: the key's *presence* and length are diagnostic, its value
 *    never is.
 *  - Message text does appear, because a capture that produced no task cannot
 *    be diagnosed without the words that failed. The export screen says this
 *    plainly before sharing, since this file usually leaves the device.
 */
class DiagnosticReport(private val context: Context, private val container: AppContainer) {

    /**
     * [existingReport] is a self-test the user has already run on this screen.
     * Including it costs nothing, where re-running one costs a minute and two
     * paid model calls - so the export reuses a result rather than repeating
     * the work behind a button that only says "Building...".
     */
    suspend fun build(
        includeSelfTest: Boolean = false,
        existingReport: SelfTest.Report? = null,
    ): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val now = System.currentTimeMillis()

        out.section("TaskMind diagnostic report")
        out.kv("Generated", stamp(now))
        out.kv("App version", "${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE})")
        out.kv("Build type", if (BuildConfig.DEBUG) "debug" else "release")
        out.kv("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        out.kv("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        out.appendLine()
        out.appendLine(
            "This file contains the text of captured messages and transcripts. It does NOT " +
                "contain API keys. Read it before sharing it.",
        )

        val settings = runCatching { container.settingsRepository.current() }.getOrNull()

        out.section("Permissions")
        for (item in PermissionState.all(context)) {
            out.kv(
                item.label,
                (if (item.granted) "granted" else "NOT GRANTED") + if (item.required) " (required)" else "",
            )
        }

        out.section("Providers")
        if (settings == null) {
            out.appendLine("settings could not be read")
        } else {
            val model = settings.llmModel
            out.kv("Extraction base URL", settings.llmBaseUrl)
            out.kv("Extraction model", model)
            out.kv("Model looks like", ModelCatalog.classify(model).name)
            ModelCatalog.whyUnusable(ModelCatalog.classify(model))?.let { out.kv("PROBLEM", it) }
            ProviderDiagnosis.settingsWarningFor(model)?.let { out.kv("WARNING", it) }
            out.kv("Extraction key", describeKey(runCatching { container.secretStore.llmApiKey }.getOrNull()))
            out.kv("Transcription provider", settings.asrProvider.name)
            out.kv("Transcription base URL", settings.asrBaseUrl)
            out.kv("Transcription model", settings.asrModel)
            out.kv("Transcription language", settings.asrLanguage)
            out.kv("Transcription key", describeKey(runCatching { container.secretStore.asrApiKey }.getOrNull()))
        }

        val runtime = runCatching { container.runtimeStateStore.current() }.getOrNull()
        if (runtime != null) {
            out.section("Provider cooldowns")
            out.kv(
                "Extraction",
                if (runtime.llmCooldown.activeAt(now)) {
                    "paused for ${runtime.llmCooldown.remainingMillis(now) / 60_000}m " +
                        "(${runtime.llmCooldown.reason})"
                } else {
                    "none"
                },
            )
            out.kv(
                "Transcription",
                if (runtime.asrCooldown.activeAt(now)) {
                    "paused for ${runtime.asrCooldown.remainingMillis(now) / 60_000}m"
                } else {
                    "none"
                },
            )
        }

        if (settings != null) out.settingsSection(settings)

        out.section("Notifications")
        val notifications = runCatching { container.notifier.diagnose() }.getOrNull()
        if (notifications == null) {
            out.appendLine("could not be checked")
        } else {
            out.kv("Permission granted", notifications.permissionGranted.toString())
            out.kv("Notifications enabled", notifications.notificationsEnabled.toString())
            for ((channel, importance) in notifications.channelImportance) {
                out.kv("Channel '$channel' importance", importanceLabel(importance))
            }
            out.kv("Verdict", notifications.explain())
        }

        out.section("Network")
        out.kv("Current network", NetworkState.describe(context))
        out.kv("Call audio on Wi-Fi only", (settings?.wifiOnlyAsr ?: false).toString())
        if (settings?.wifiOnlyAsr == true && !NetworkState.isUnmetered(context)) {
            out.kv(
                "NOTE",
                "Transcription work is scheduled with an unmetered-network constraint, so " +
                    "anything awaiting transcription waits for Wi-Fi. This is why that queue " +
                    "may not be draining.",
            )
        }

        out.section("Background work")
        val scheduled = runCatching { Scheduler.scheduledWorkNames(context) }.getOrDefault(emptyList())
        out.kv("Registered", if (scheduled.isEmpty()) "NONE - nothing will run in the background" else scheduled.joinToString())

        out.section("Capture queue")
        val rawDao = container.database.rawCaptureDao()
        for (state in CaptureState.entries) {
            out.kv(state.name, runCatching { rawDao.countByState(state) }.getOrDefault(-1).toString())
        }
        out.kv("Total captures", runCatching { rawDao.total() }.getOrDefault(-1).toString())
        out.kv("Total tasks", runCatching { container.taskRepository.totalTasks() }.getOrDefault(-1).toString())

        out.section("Call recordings")
        val survey = runCatching {
            container.recordingFinder.survey(settings?.callRecordingDirUri)
        }.getOrNull()
        if (survey == null) {
            out.appendLine("could not be surveyed")
        } else {
            out.kv("All Files Access", survey.allFilesAccess.toString())
            out.kv("Folder chosen", survey.userDirConfigured.toString())
            out.kv("Folder readable", survey.userDirReadable.toString())
            out.kv("Found in chosen folder", survey.userDirCount.toString())
            out.kv("Found in known paths", survey.knownPathCount.toString())
            out.kv("Found in media store", survey.mediaStoreCount.toString())
            out.kv("Known paths that exist", survey.existingKnownPaths.joinToString().ifBlank { "none" })
            survey.newest?.let {
                out.kv("Newest recording", "${it.name} (${it.sizeBytes} bytes, ${stamp(it.lastModified)})")
            }
        }

        out.section("Recent calls")
        val calls = runCatching { container.database.callRecordDao().recent(15) }.getOrDefault(emptyList())
        if (calls.isEmpty()) out.appendLine("none recorded")
        for (call in calls) {
            out.appendLine(
                "${stamp(call.startTime)}  ${call.state}  ${call.contactName ?: call.phoneNumber ?: "unknown"}  " +
                    "duration=${call.durationSeconds ?: "?"}s  recording=${call.recordingPath ?: "none"}" +
                    (call.lastError?.let { "  error=$it" } ?: ""),
            )
        }

        callPipelineSection(out, settings)

        out.section("Prompts")
        val prompts = runCatching { container.promptStore.current() }.getOrNull()
        if (prompts == null) {
            out.appendLine("could not be read")
        } else {
            for (kind in PromptKind.entries) {
                val text = kind.textIn(prompts)
                val edited = text != kind.defaultText()
                out.kv(kind.title, if (edited) "EDITED BY USER (${text.length} chars)" else "default")
            }
        }

        out.section("Recent model calls")
        val modelCalls = runCatching { container.database.inferenceCallDao().recent(25) }.getOrDefault(emptyList())
        if (modelCalls.isEmpty()) out.appendLine("none recorded - no request has been sent yet")
        for (call in modelCalls) {
            out.appendLine("--- ${stamp(call.startedAt)}  ${call.kind}  ${call.model}")
            out.appendLine("    status=${call.httpStatus ?: "-"} ok=${call.ok} ${call.durationMillis}ms tokens=${call.totalTokens ?: "-"}")
            call.errorText?.let { out.appendLine("    error: $it") }
            call.diagnosis?.let { out.appendLine("    diagnosis: $it") }
            call.sourceLabel?.let { out.appendLine("    source: $it") }
            out.appendLine("    user prompt: ${call.userPrompt?.take(600) ?: "-"}")
            out.appendLine("    reply: ${call.responseBody?.take(600) ?: "-"}")
        }

        out.section("Recent captures")
        val captures = runCatching { rawDao.recent(25) }.getOrDefault(emptyList())
        if (captures.isEmpty()) out.appendLine("none")
        for (capture in captures) {
            out.appendLine(
                "${stamp(capture.capturedAt)}  ${capture.state}  ${capture.sourceType}  " +
                    "attempts=${capture.attemptCount}  app=${capture.sourceApp ?: "-"}",
            )
            out.appendLine("    from: ${capture.sourceLabel ?: "-"}")
            out.appendLine("    text: ${capture.rawText?.take(400) ?: "-"}")
            capture.lastError?.let { out.appendLine("    error: $it") }
        }

        out.section("Activity log (newest first)")
        val log = runCatching { container.database.activityLogDao().recent(250) }.getOrDefault(emptyList())
        if (log.isEmpty()) out.appendLine("empty")
        for (entry in log) {
            out.appendLine("${stamp(entry.timestamp)}  ${entry.level}  ${entry.stage}  ${entry.message}")
            entry.detail?.let { out.appendLine("    $it") }
        }

        if (includeSelfTest || existingReport != null) {
            out.section("Self-test")
            val report = existingReport
                ?: runCatching { SelfTest(context, container).run() }.getOrNull()
            if (report == null) {
                out.appendLine("the self-test could not be run")
            } else {
                out.appendLine(report.summary)
                for (step in report.steps) {
                    out.appendLine("${if (step.passed) "PASS" else "FAIL"}  ${step.name} (${step.millis}ms)")
                    out.appendLine("    ${step.detail}")
                }
            }
        }

        out.appendLine()
        out.appendLine("--- end of report ---")
        out.toString()
    }

    /**
     * Presence and shape only. Length and prefix distinguish "no key", "key
     * from the wrong provider" and "key truncated by a bad paste", which are
     * the failures worth telling apart; the value itself is never diagnostic.
     */
    private fun describeKey(key: String?): String = when {
        key.isNullOrBlank() -> "NOT SET"
        else -> "set (${key.length} chars, starts \"${key.take(3)}\")"
    }

    private fun StringBuilder.settingsSection(s: Settings) {
        section("Settings")
        kv("Cloud consent", s.cloudConsent.toString())
        kv("Onboarding complete", s.onboardingComplete.toString())
        kv("Capture notifications", s.captureNotifications.toString())
        kv("Capture calls", s.captureCalls.toString())
        kv("Allowed packages", s.allowedPackages.sorted().joinToString().ifBlank { "none" })
        kv("Min call duration", "${s.minCallDurationSeconds}s")
        kv("Recording folder", s.callRecordingDirUri ?: "not set")
        kv("Auto-create threshold", s.autoCreateThreshold.toString())
        kv("Review threshold", s.reviewThreshold.toString())
        kv("Notification tolerance", s.notificationTolerance.toString())
        kv("Clipboard tolerance", s.clipboardTolerance.toString())
        kv("Call tolerance", s.callTolerance.toString())
        kv("Verify pass", s.verifyPass.toString())
        kv("Max model calls/day", s.maxLlmCallsPerDay.toString())
        kv("Max calls per app/day", s.maxLlmCallsPerPackagePerDay.toString())
        kv("Max ASR minutes/day", s.maxAsrMinutesPerDay.toString())
        kv("Wi-Fi only for audio", s.wifiOnlyAsr.toString())
        kv(
            "Ignoring recordings before",
            if (s.recordingCutoffMillis > 0) {
                stamp(s.recordingCutoffMillis)
            } else {
                "not set yet"
            },
        )
        kv("Retention", "${s.retentionDays} days")
        kv("Delete recordings after ASR", s.deleteRecordingsAfterTranscription.toString())
        kv("Update manifest URL", s.updateManifestUrl.ifBlank { "not set" })
    }

    /** IMPORTANCE_NONE is the one that matters: the category is switched off. */
    private fun importanceLabel(value: Int): String = when (value) {
        android.app.NotificationManager.IMPORTANCE_NONE -> "NONE - blocked"
        android.app.NotificationManager.IMPORTANCE_MIN -> "min"
        android.app.NotificationManager.IMPORTANCE_LOW -> "low"
        android.app.NotificationManager.IMPORTANCE_DEFAULT -> "default"
        android.app.NotificationManager.IMPORTANCE_HIGH -> "high"
        android.app.NotificationManager.IMPORTANCE_UNSPECIFIED -> "channel not created yet"
        else -> value.toString()
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("=".repeat(72))
        appendLine(title.uppercase())
        appendLine("=".repeat(72))
    }

    /**
     * Android's call log, next to what TaskMind did with each row.
     *
     * The question "why did that call not become a task?" has never been
     * answerable from inside the app. The activity log is capped and gets
     * evicted by message traffic; the app's own call table only contains calls
     * it decided to keep, so a call it skipped leaves no trace anywhere. This
     * reads the source of truth - the platform's call log - and says, per call,
     * whether TaskMind registered it and where it got to. A blank column here
     * is the answer.
     */
    private suspend fun callPipelineSection(out: StringBuilder, settings: Settings?) {
        out.section("Call pipeline (what the phone reported vs what TaskMind did)")

        val minDuration: Long = settings?.minCallDurationSeconds ?: 15L
        out.kv("Call capture enabled", (settings?.captureCalls ?: false).toString())
        out.kv("Cloud consent", (settings?.cloudConsent ?: false).toString())
        out.kv("Ignoring calls shorter than", "${minDuration}s")

        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        out.kv("READ_CALL_LOG granted", granted.toString())
        if (!granted) {
            out.appendLine("Without this permission the app cannot see that a call happened at all.")
            return
        }
        val capturing = settings != null && settings.captureCalls && settings.cloudConsent
        if (!capturing) {
            out.appendLine(
                "One of the switches above is off, so the call sweep returns immediately and " +
                    "no call can become a task, whatever the rows below say.",
            )
        }

        val rows = readCallLog(CALL_LOG_ROWS)
        if (rows.isEmpty()) {
            out.appendLine("The phone's call log reported no calls. Nothing has been missed - there is nothing there.")
            return
        }

        out.appendLine("time                 dir       dur   TaskMind")
        for (row in rows) {
            val record = runCatching { container.database.callRecordDao().byCallLogId(row.id) }.getOrNull()
            val verdict = when {
                record != null -> {
                    val capture = record.rawCaptureId?.let { id ->
                        runCatching { container.database.rawCaptureDao().byId(id) }.getOrNull()
                    }
                    buildString {
                        append("registered, ").append(record.state)
                        capture?.let { append(" / capture ").append(it.state) }
                        record.recordingPath?.let { append(" / recording found") }
                        record.lastError?.let { append(" / ").append(it) }
                        capture?.lastError?.let { append(" / ").append(it) }
                    }
                }
                row.direction != CallLog.Calls.INCOMING_TYPE && row.direction != CallLog.Calls.OUTGOING_TYPE ->
                    "skipped - not a connected call"
                row.duration != null && row.duration < minDuration ->
                    "skipped - shorter than ${minDuration}s"
                else ->
                    "NOT REGISTERED - this one should have been picked up"
            }
            out.appendLine(
                stamp(row.date).padEnd(21) +
                    directionLabel(row.direction).padEnd(10) +
                    (row.duration?.let { "${it}s" } ?: "?").padEnd(6) +
                    verdict,
            )
        }
    }

    private data class CallLogRow(val id: Long, val date: Long, val duration: Long?, val direction: Int)

    private fun readCallLog(limit: Int): List<CallLogRow> {
        val out = mutableListOf<CallLogRow>()
        val cursor = runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )
        }.getOrNull() ?: return emptyList()

        cursor.use { c ->
            val idIdx = c.getColumnIndex(CallLog.Calls._ID)
            val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
            while (c.moveToNext() && out.size < limit) {
                if (idIdx < 0 || dateIdx < 0) continue
                out.add(
                    CallLogRow(
                        id = c.getLong(idIdx),
                        date = c.getLong(dateIdx),
                        // Read as nullable on purpose - failure mode 4. A row
                        // still being written reports no duration, and treating
                        // that as zero is what made real calls look too short.
                        duration = if (durationIdx >= 0 && !c.isNull(durationIdx)) c.getLong(durationIdx) else null,
                        direction = if (typeIdx >= 0) c.getInt(typeIdx) else 0,
                    ),
                )
            }
        }
        return out
    }

    private fun directionLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> "other"
    }

    private fun StringBuilder.kv(key: String, value: String) {
        appendLine("$key: $value")
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    companion object {
        /** Enough to cover a normal day of calls without bloating the report. */
        private const val CALL_LOG_ROWS = 25

        fun fileName(): String =
            "taskmind-diagnostics-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                ".txt"
    }
}
