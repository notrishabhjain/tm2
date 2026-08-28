package com.taskmind.diagnostics

import android.content.Context
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.NotificationResolver
import com.taskmind.core.SourceRef
import com.taskmind.core.Stage
import com.taskmind.capture.CaptureCoordinator
import com.taskmind.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Does task creation actually work, on this phone, with this model?
 *
 * The self-test proves the pipeline runs. It does not answer the question the
 * user actually has, which is whether their real messages turn into tasks - and
 * on the device that question stayed open for days because the only messages
 * that reached the model happened to be adverts and status updates, all
 * correctly rejected. "No tasks created" and "no tasks were warranted" look
 * identical from the outside.
 *
 * So this pushes a fixed set of messages through the REAL production path and
 * says, per message, what came out and whether that was right. Every case is
 * taken from the kind of traffic the app is meant to read, and the expected
 * answers are the ones a person would give.
 */
class TaskCreationTest(private val context: Context, private val container: AppContainer) {

    /**
     * [shouldCreateTask] is what a reasonable person would say about the
     * message, not what any particular model happens to do with it.
     */
    data class Case(
        val label: String,
        val message: String,
        val sender: String,
        val shouldCreateTask: Boolean,
        val why: String,
    )

    data class Result(
        val case: Case,
        val createdTitle: String?,
        val sentToReview: Boolean,
        val rejectedBy: String?,
        val millis: Long,
    ) {
        val gotTask: Boolean get() = createdTitle != null
        /** Review counts as finding it: the app noticed, it just wants a look. */
        val correct: Boolean
            get() = if (case.shouldCreateTask) gotTask || sentToReview else !gotTask
    }

    data class Report(val results: List<Result>) {
        val passed: Int get() = results.count { it.correct }
        val total: Int get() = results.size
        val summary: String get() = "$passed of $total handled correctly"

        /** Missing a real commitment is the expensive error; call it out. */
        val missed: List<Result> get() = results.filter { it.case.shouldCreateTask && !it.correct }
        val invented: List<Result> get() = results.filter { !it.case.shouldCreateTask && !it.correct }
    }

    suspend fun run(cases: List<Case> = DEFAULT_CASES): Report = withContext(Dispatchers.IO) {
        container.logger.write(Stage.SELFTEST, LogLevel.INFO, "task creation test started", "${cases.size} cases")
        val results = cases.map { runCase(it) }
        val report = Report(results)
        container.logger.write(
            Stage.SELFTEST,
            if (report.missed.isEmpty()) LogLevel.INFO else LogLevel.WARN,
            "task creation test finished: ${report.summary}",
            results.joinToString("\n") {
                "${if (it.correct) "OK  " else "BAD "} ${it.case.label}: " +
                    (it.createdTitle ?: it.rejectedBy ?: "no task")
            },
        )
        report
    }

    /** One message, all the way through, then cleaned up. */
    private suspend fun runCase(case: Case): Result {
        val started = System.currentTimeMillis()
        val settings = container.settingsRepository.current()
        val taskDao = container.database.taskDao()
        val rawDao = container.database.rawCaptureDao()

        val outcome = runCatching {
            container.captureCoordinator.handleNotification(
                fields = NotificationResolver.Fields(
                    packageName = TEST_PACKAGE,
                    title = case.sender,
                    text = case.message,
                    postTime = System.currentTimeMillis(),
                ),
                appLabel = "TaskMind test",
                // Only the allow-list is overridden. Every other rule - the OTP
                // pattern, the advert markers, the length floor - still applies,
                // so this really is the production path.
                settings = settings.copy(allowedPackages = settings.allowedPackages + TEST_PACKAGE),
                ownPackageName = "com.taskmind.tests.not.us",
            )
        }.getOrNull()

        val captureId = when (outcome) {
            is CaptureCoordinator.Outcome.Captured -> outcome.rawCaptureId
            is CaptureCoordinator.Outcome.Duplicate -> outcome.rawCaptureId
            is CaptureCoordinator.Outcome.Rejected ->
                return Result(case, null, false, "pre-filter: ${outcome.rule}", elapsed(started))
            else -> return Result(case, null, false, "no usable text", elapsed(started))
        }

        val capture = rawDao.byId(captureId)
            ?: return Result(case, null, false, "capture row missing", elapsed(started))

        // The dedup fingerprint outlives its capture by seven days, so without
        // clearing it this suite would only ever be runnable once.
        runCatching {
            container.database.fingerprintDao().deleteByHash(
                SourceRef.fingerprint(
                    packageName = capture.sourceApp.orEmpty(),
                    senderKey = capture.contextLabel.orEmpty(),
                    messageText = capture.rawText.orEmpty(),
                ),
            )
        }

        runCatching {
            container.extractionPipeline.process(capture.copy(state = CaptureState.PENDING_EXTRACTION))
        }

        val tasks = runCatching { taskDao.byRawCapture(captureId) }.getOrDefault(emptyList())
        val reviewed = runCatching {
            container.database.reviewItemDao().byRawCapture(captureId).isNotEmpty()
        }.getOrDefault(false)

        val result = Result(
            case = case,
            createdTitle = tasks.firstOrNull()?.title,
            sentToReview = reviewed,
            rejectedBy = if (tasks.isEmpty() && !reviewed) "the model found no commitment" else null,
            millis = elapsed(started),
        )

        cleanUp(captureId)
        return result
    }

    /** Nothing this suite creates is left in the user's task list. */
    private suspend fun cleanUp(captureId: String) {
        runCatching {
            val taskDao = container.database.taskDao()
            val tasks = taskDao.byRawCapture(captureId)
            if (tasks.isNotEmpty()) taskDao.hardDelete(tasks.map { it.id })
            container.database.reviewItemDao().deleteByRawCapture(captureId)
            taskDao.detachRawCaptures(listOf(captureId))
            container.database.rawCaptureDao().delete(listOf(captureId))
        }
    }

    private fun elapsed(from: Long) = System.currentTimeMillis() - from

    companion object {
        const val TEST_PACKAGE = "com.taskmind.tests"

        /**
         * Every message here is modelled on real traffic from the device this
         * was debugged against: work WhatsApp in Hinglish, a family request, and
         * the noise that should never become a task.
         */
        val DEFAULT_CASES = listOf(
            Case(
                label = "Hinglish deadline with an amount",
                message = "bhai kal tak woh 4500 ka invoice Sharma Traders ko bhej dena, warna payment atak jayega",
                sender = "Abhishek",
                shouldCreateTask = true,
                why = "A direct request with a deadline and an amount. The clearest possible case.",
            ),
            Case(
                label = "Plain English request with a time",
                message = "Please call Rahul tomorrow at 4 PM about the renewal",
                sender = "Kashish",
                shouldCreateTask = true,
                why = "An explicit instruction with a date and time.",
            ),
            Case(
                label = "Casual Hinglish request",
                message = "Rishabh ji pics send kr do jo kal khiche the",
                sender = "Manu Jain",
                shouldCreateTask = true,
                why = "Informal, no deadline, still a request addressed to you.",
            ),
            Case(
                label = "Polite work request",
                message = "Kindly connect with Shri Varun Gupta ji from the NIC network team asap",
                sender = "Rohit Grover",
                shouldCreateTask = true,
                why = "Formal Indian office register. 'Kindly' and 'asap' are the ask.",
            ),
            Case(
                label = "Hedged request",
                message = "Can you share the TCP dump collected earlier from the application side here, if possible",
                sender = "Kashish",
                shouldCreateTask = true,
                why = "Softened by 'if possible', but still something you have been asked to do.",
            ),
            Case(
                label = "Devanagari request",
                message = "कल शाम तक रिपोर्ट भेज देना, क्लाइंट को दिखानी है",
                sender = "Sameer",
                shouldCreateTask = true,
                why = "Hindi script must work as well as Latin.",
            ),
            Case(
                label = "Status update, not a request",
                message = "SSL offloading is working now and it is configured on the WAF",
                sender = "Deepak Jha",
                shouldCreateTask = false,
                why = "Reports what happened. Nobody is being asked to do anything.",
            ),
            Case(
                label = "Acknowledgement",
                message = "Haan theek hai, main dekh leta hoon abhi",
                sender = "Abhishek",
                shouldCreateTask = false,
                why = "Someone else accepting work. Not yours.",
            ),
            Case(
                label = "One-time code",
                message = "Your OTP is 448213. Do not share it with anyone.",
                sender = "VM-HDFCBK",
                shouldCreateTask = false,
                why = "Should never reach the model at all - the pre-filter catches it for free.",
            ),
            Case(
                label = "Marketing",
                message = "Biggest sale of the season, flat discounts across the store. T&C apply, shop now!",
                sender = "Flipkart",
                shouldCreateTask = false,
                why = "Also a pre-filter case, and also free.",
            ),
        )
    }
}
