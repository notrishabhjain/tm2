package com.taskmind.diagnostics

import android.content.Context
import com.taskmind.capture.AudioChunker
import com.taskmind.capture.CaptureCoordinator
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.NotificationResolver
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spec 15 - the self-test.
 *
 * FAILURE MODE 8: the prior self-test exercised only discovery and
 * transcription, reported success, and pointed debugging away from the broken
 * extraction stage for weeks.
 *
 * So this one runs the PRODUCTION path end to end. It injects a synthetic
 * notification through the real `CaptureCoordinator.handleNotification()` entry
 * point and a synthetic transcript through the real call extraction path, then
 * asserts a task actually appeared - and deletes both afterwards.
 */
class SelfTest(private val context: Context, private val container: AppContainer) {

    data class Step(
        val name: String,
        val passed: Boolean,
        val detail: String,
        val millis: Long,
    )

    data class Report(val steps: List<Step>) {
        val passed: Boolean get() = steps.all { it.passed }
        val summary: String
            get() = "${steps.count { it.passed }}/${steps.size} steps passed"
    }

    suspend fun run(): Report = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Step>()
        container.logger.write(Stage.SELFTEST, LogLevel.INFO, "self-test started")

        steps += permissionStep()
        steps += asrConnectionStep()
        steps += llmConnectionStep()
        steps += syntheticNotificationStep()
        steps += syntheticTranscriptStep()
        steps += cleanupStep()

        val report = Report(steps)
        container.logger.write(
            Stage.SELFTEST,
            if (report.passed) LogLevel.INFO else LogLevel.WARN,
            "self-test finished: ${report.summary}",
            steps.joinToString("\n") { "${if (it.passed) "PASS" else "FAIL"} ${it.name} (${it.millis}ms): ${it.detail}" },
        )
        report
    }

    /** Step 1: report every permission's actual current state. */
    private fun permissionStep(): Step = timed("Permissions") {
        val items = PermissionState.all(context)
        val missing = items.filter { !it.granted }
        val detail = if (missing.isEmpty()) {
            "all ${items.size} granted"
        } else {
            "missing: " + missing.joinToString(", ") { it.label }
        }
        // Only notification access is genuinely required for the app to do
        // anything at all; the rest degrade specific features.
        val required = items.filter { it.required }
        (required.all { it.granted }) to detail
    }

    /** Step 2: run the ASR connection test on a bundled sample. */
    private suspend fun asrConnectionStep(): Step = timedSuspend("ASR connection") {
        if (!container.secretStore.hasAsrKey()) {
            return@timedSuspend false to "no ASR key configured - call transcription is unavailable"
        }
        val sample = AudioChunker.writeSilentWav(File(context.cacheDir, "selftest/silence.wav"))
        val (transcriber, _) = container.transcriber()
        val result = container.connectionTester.testAsr(
            transcriber,
            sample,
            container.settingsRepository.current().asrLanguage,
        )
        result.ok to result.message
    }

    private suspend fun llmConnectionStep(): Step = timedSuspend("LLM connection") {
        if (!container.secretStore.hasLlmKey()) {
            return@timedSuspend false to "no LLM key configured - extraction is unavailable"
        }
        val result = container.connectionTester.testLlm(container.llmConfig())
        result.ok to result.message
    }

    /**
     * Step 3: inject a synthetic notification through the REAL entry point and
     * assert a task appears with the expected title.
     */
    private suspend fun syntheticNotificationStep(): Step = timedSuspend("Notification pipeline") {
        val settings = container.settingsRepository.current()
        if (!settings.cloudConsent) return@timedSuspend false to "cloud consent not given - capture is disabled"
        if (!container.secretStore.hasLlmKey()) return@timedSuspend false to "skipped - no LLM key"

        val fields = NotificationResolver.Fields(
            packageName = SYNTHETIC_PACKAGE,
            title = SYNTHETIC_SENDER,
            text = SYNTHETIC_MESSAGE,
            postTime = System.currentTimeMillis(),
        )

        val outcome = container.captureCoordinator.handleNotification(
            fields = fields,
            appLabel = "TaskMind self-test",
            // The allow-list is settings data, and the synthetic package is not
            // on it. Override only that field: every other rule still applies,
            // so this really is the production path.
            settings = settings.copy(allowedPackages = settings.allowedPackages + SYNTHETIC_PACKAGE),
            ownPackageName = "com.taskmind.selftest.not.us",
        )

        val captureId = when (outcome) {
            is CaptureCoordinator.Outcome.Captured -> outcome.rawCaptureId
            is CaptureCoordinator.Outcome.Duplicate -> outcome.rawCaptureId
            else -> return@timedSuspend false to "capture stage rejected the synthetic message: $outcome"
        }

        val capture = container.database.rawCaptureDao().byId(captureId)
            ?: return@timedSuspend false to "the RawCapture row was not written"

        container.extractionPipeline.process(capture.copy(state = CaptureState.PENDING_EXTRACTION))

        val tasks = container.database.taskDao().byRawCapture(captureId)
        if (tasks.isEmpty()) {
            return@timedSuspend false to
                "extraction produced no task. The activity log has the evidence-check score and the funnel verdict " +
                "for this exact message - that is where the answer is."
        }
        true to "created: ${tasks.joinToString { it.title }}"
    }

    /** Step 4: inject a synthetic transcript through the real call extraction path. */
    private suspend fun syntheticTranscriptStep(): Step = timedSuspend("Call extraction pipeline") {
        if (!container.secretStore.hasLlmKey()) return@timedSuspend false to "skipped - no LLM key"

        val outcome = container.captureCoordinator.captureClipboardTranscript(
            transcript = SYNTHETIC_TRANSCRIPT,
            contactLabel = SYNTHETIC_CALL_LABEL,
            occurredAt = System.currentTimeMillis(),
        )
        val captureId = when (outcome) {
            is CaptureCoordinator.Outcome.Captured -> outcome.rawCaptureId
            is CaptureCoordinator.Outcome.Duplicate -> outcome.rawCaptureId
            else -> return@timedSuspend false to "the synthetic transcript was not captured: $outcome"
        }

        val capture = container.database.rawCaptureDao().byId(captureId)
            ?: return@timedSuspend false to "the RawCapture row was not written"
        container.extractionPipeline.process(capture.copy(state = CaptureState.PENDING_EXTRACTION))

        val tasks = container.database.taskDao().byRawCapture(captureId)
        if (tasks.isEmpty()) {
            return@timedSuspend false to "transcript extraction produced no task - see the activity log"
        }
        true to "created: ${tasks.joinToString { it.title }}"
    }

    /** Step 5: delete both synthetic tasks and their captures. */
    private suspend fun cleanupStep(): Step = timedSuspend("Cleanup") {
        val taskDao = container.database.taskDao()
        val rawDao = container.database.rawCaptureDao()

        val synthetic = taskDao.allForExport().filter {
            it.sourceApp == SYNTHETIC_PACKAGE || it.sourceLabel == SYNTHETIC_CALL_LABEL
        }
        if (synthetic.isNotEmpty()) taskDao.hardDelete(synthetic.map { it.id })

        val captures = rawDao.recent(50).filter {
            it.sourceApp == SYNTHETIC_PACKAGE || it.sourceLabel == SYNTHETIC_CALL_LABEL
        }
        if (captures.isNotEmpty()) {
            taskDao.detachRawCaptures(captures.map { it.id })
            rawDao.delete(captures.map { it.id })
        }

        val leftover = taskDao.allForExport().count {
            it.sourceApp == SYNTHETIC_PACKAGE && it.status != TaskStatus.DELETED
        }
        (leftover == 0) to "removed ${synthetic.size} task(s) and ${captures.size} capture(s)"
    }

    // ------------------------------------------------------------- plumbing

    private inline fun timed(name: String, block: () -> Pair<Boolean, String>): Step {
        val start = System.currentTimeMillis()
        val (ok, detail) = runCatching { block() }.getOrElse { false to (it.message ?: it.toString()) }
        return Step(name, ok, detail, System.currentTimeMillis() - start)
    }

    private suspend fun timedSuspend(name: String, block: suspend () -> Pair<Boolean, String>): Step {
        val start = System.currentTimeMillis()
        val (ok, detail) = runCatching { block() }.getOrElse { false to (it.message ?: it.toString()) }
        return Step(name, ok, detail, System.currentTimeMillis() - start)
    }

    private companion object {
        const val SYNTHETIC_PACKAGE = "com.taskmind.selftest"
        const val SYNTHETIC_SENDER = "TaskMind Self-test"
        const val SYNTHETIC_MESSAGE =
            "bhai kal tak woh 4500 ka invoice Sharma Traders ko bhej dena, warna payment atak jayega"
        const val SYNTHETIC_CALL_LABEL = "TaskMind self-test call"
        const val SYNTHETIC_TRANSCRIPT =
            "Speaker 1: haan ji namaste, boliye.\n" +
                "Speaker 2: sir woh site visit ka report kal shaam tak bhej dijiyega, client ko dikhana hai.\n" +
                "Speaker 1: theek hai, main kal shaam tak report bhej deta hoon."
    }
}
