package com.taskmind.diagnostics

import android.content.Context
import android.os.Build
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

        out.section("Network")
        out.kv("Current network", NetworkState.describe(context))
        out.kv("Call audio on Wi-Fi only", (settings?.wifiOnlyAsr ?: true).toString())
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
        kv("Retention", "${s.retentionDays} days")
        kv("Delete recordings after ASR", s.deleteRecordingsAfterTranscription.toString())
        kv("Update manifest URL", s.updateManifestUrl.ifBlank { "not set" })
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("=".repeat(72))
        appendLine(title.uppercase())
        appendLine("=".repeat(72))
    }

    private fun StringBuilder.kv(key: String, value: String) {
        appendLine("$key: $value")
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    companion object {
        fun fileName(): String =
            "taskmind-diagnostics-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                ".txt"
    }
}
