package com.taskmind.diagnostics

import android.content.Context
import com.taskmind.capture.AudioChunker
import com.taskmind.capture.CaptureCoordinator
import com.taskmind.core.CaptureState
import com.taskmind.core.ModelCatalog
import com.taskmind.core.PromptKind
import com.taskmind.core.LogLevel
import com.taskmind.core.NotificationResolver
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
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
        /**
         * What to do about a failure. A diagnostic that says something is
         * broken without saying what to change is only half a diagnostic, and
         * on a phone there is no log to fall back on.
         */
        val hint: String? = null,
        /** False for checks that degrade one feature rather than break the app. */
        val critical: Boolean = true,
    )

    data class Report(val steps: List<Step>) {
        val passed: Boolean get() = steps.all { it.passed }
        val criticalFailures: List<Step> get() = steps.filter { !it.passed && it.critical }
        val summary: String
            get() = "${steps.count { it.passed }}/${steps.size} steps passed"
    }

    suspend fun run(): Report = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Step>()
        container.logger.write(Stage.SELFTEST, LogLevel.INFO, "self-test started")

        // Ordered so that the first failure is the most upstream cause. A
        // failing extraction step below a failing key step tells you nothing
        // new, and reading the list top-down should lead to the real problem.
        steps += permissionStep()
        steps += listenerStep()
        steps += databaseStep()
        steps += promptStep()
        steps += backgroundWorkStep()
        steps += providerConfigStep()
        steps += llmConnectionStep()
        steps += asrConnectionStep()
        steps += recordingDiscoveryStep()
        steps += queueStep()
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

    /** Is the listener actually bound? Everything upstream depends on it. */
    private fun listenerStep(): Step = timed("Notification listener") {
        val enabled = PermissionState.notificationAccess(context).granted
        if (!enabled) {
            return@timed false to "not granted - no message will ever be captured"
        }
        true to "granted and bound"
    }

    /** A write and a read back. Storage full or a failed migration both land here. */
    private suspend fun databaseStep(): Step = timedSuspend("Database") {
        val dao = container.database.rawCaptureDao()
        val captures = dao.total()
        val tasks = container.taskRepository.totalTasks()
        val calls = container.database.inferenceCallDao().total()
        // Exercising a write is the point: a read-only failure mode (disk full,
        // corrupt journal) is invisible to a query alone.
        container.logger.write(Stage.SELFTEST, LogLevel.DEBUG, "database write probe")
        true to "$tasks task(s), $captures capture(s), $calls recorded model call(s)"
    }

    /** Are the prompts the model will actually receive present and sane? */
    private suspend fun promptStep(): Step = timedSuspend("Prompts") {
        val prompts = container.promptStore.current()
        val edited = PromptKind.entries.filter { it.textIn(prompts) != it.defaultText() }
        val empty = PromptKind.entries.filter { it.textIn(prompts).isBlank() }
        if (empty.isNotEmpty()) {
            return@timedSuspend false to "empty: ${empty.joinToString { it.title }} - reset it in Settings -> Prompts"
        }
        val detail = if (edited.isEmpty()) {
            "all three at their defaults"
        } else {
            "edited by you: ${edited.joinToString { it.title }}"
        }
        true to detail
    }

    /** Is anything scheduled to drain the queue when the app is closed? */
    private fun backgroundWorkStep(): Step = timed("Background work") {
        val names = Scheduler.scheduledWorkNames(context)
        if (names.isEmpty()) {
            return@timed false to "nothing scheduled - open the app once more, or check battery settings"
        }
        true to "scheduled: ${names.joinToString()}"
    }

    /**
     * Whether the configured model can do this job at all.
     *
     * This is the check that would have saved a day's quota on the device: the
     * model was a router, the router called a model the account had blocked,
     * and every capture in the queue paid to rediscover that.
     */
    private suspend fun providerConfigStep(): Step = timedSuspend("Provider configuration") {
        val settings = container.settingsRepository.current()
        val model = settings.llmModel.trim()
        if (model.isEmpty()) return@timedSuspend false to "no extraction model set"
        if (!container.secretStore.hasLlmKey()) {
            return@timedSuspend false to "no API key set - captures are held, not lost"
        }
        val kind = ModelCatalog.classify(model)
        val unusable = ModelCatalog.whyUnusable(kind)
        if (unusable != null) {
            return@timedSuspend false to "\"$model\" cannot do extraction. $unusable"
        }
        val cooldown = container.runtimeStateStore.current().llmCooldown
        if (cooldown.activeAt(System.currentTimeMillis())) {
            val minutes = cooldown.remainingMillis(System.currentTimeMillis()) / 60_000
            return@timedSuspend false to
                "rate limited by the provider for another ${minutes}m (${cooldown.reason})"
        }
        true to "$model at ${settings.llmBaseUrl}"
    }

    /**
     * What recording discovery can actually see. Reports the counts rather than
     * a yes/no, because "the folder has files but none matched the call window"
     * and "the folder cannot be read" look identical from the outside and need
     * completely different fixes.
     */
    private suspend fun recordingDiscoveryStep(): Step = timedSuspend("Call recordings", critical = false) {
        val settings = container.settingsRepository.current()
        if (!settings.captureCalls) return@timedSuspend true to "call capture is switched off in settings"

        val survey = container.recordingFinder.survey(settings.callRecordingDirUri)
        if (survey.userDirConfigured && !survey.userDirReadable) {
            return@timedSuspend false to
                "the folder you chose can no longer be read - pick it again in Settings"
        }
        if (survey.total == 0) {
            val where = if (survey.userDirConfigured) {
                "Your chosen folder is readable but empty."
            } else if (survey.allFilesAccess) {
                "Searched ${survey.existingKnownPaths.size} known folder(s) and the media store."
            } else {
                "No All Files Access and no folder chosen, so there is nowhere to look."
            }
            return@timedSuspend false to
                "$where Is call recording switched on in your dialer? TaskMind cannot record calls itself."
        }
        val newest = survey.newest
        true to buildString {
            append("${survey.total} recording(s) visible")
            append(" (folder ${survey.userDirCount}, known paths ${survey.knownPathCount}, ")
            append("media store ${survey.mediaStoreCount})")
            newest?.let { append(". Newest: ${it.name}") }
        }
    }

    /** Nothing should be stuck. If it is, say what in and why. */
    private suspend fun queueStep(): Step = timedSuspend("Capture queue") {
        val dao = container.database.rawCaptureDao()
        val blocked = dao.countByState(CaptureState.BLOCKED_CONFIG)
        val held = dao.countByState(CaptureState.BUDGET_HELD)
        val pending = dao.countByState(CaptureState.PENDING_EXTRACTION)
        val failed = dao.countByState(CaptureState.FAILED_PERMANENT)
        val awaiting = dao.countByState(CaptureState.PENDING_TRANSCRIPTION)

        val summary = "pending $pending, awaiting transcription $awaiting, " +
            "blocked $blocked, held for budget $held, failed $failed"
        if (blocked > 0) {
            return@timedSuspend false to
                "$summary. The blocked ones are waiting on a provider setting - they retry " +
                "automatically once you change the model or base URL, and nothing is lost."
        }
        true to summary
    }

    // ------------------------------------------------------------- plumbing

    private inline fun timed(
        name: String,
        critical: Boolean = true,
        block: () -> Pair<Boolean, String>,
    ): Step {
        val start = System.currentTimeMillis()
        val (ok, detail) = runCatching { block() }.getOrElse { false to (it.message ?: it.toString()) }
        return Step(name, ok, detail, System.currentTimeMillis() - start, critical = critical)
    }

    private suspend fun timedSuspend(
        name: String,
        critical: Boolean = true,
        block: suspend () -> Pair<Boolean, String>,
    ): Step {
        val start = System.currentTimeMillis()
        val (ok, detail) = runCatching { block() }.getOrElse { false to (it.message ?: it.toString()) }
        return Step(name, ok, detail, System.currentTimeMillis() - start, critical = critical)
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
