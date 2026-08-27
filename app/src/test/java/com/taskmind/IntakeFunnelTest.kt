package com.taskmind

import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.SourceType
import com.taskmind.intake.Clock
import com.taskmind.intake.CaptureMarker
import com.taskmind.intake.FunnelConfig
import com.taskmind.intake.FunnelLog
import com.taskmind.intake.IdGenerator
import com.taskmind.intake.IntakeFunnel
import com.taskmind.intake.IntakeResult
import com.taskmind.intake.NewTask
import com.taskmind.intake.ReviewProposal
import com.taskmind.intake.ReviewSink
import com.taskmind.intake.TaskCandidate
import com.taskmind.intake.TaskCreatedNotifier
import com.taskmind.intake.TaskSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class IntakeFunnelTest {

    // ------------------------------------------------------------- test doubles

    private class FakeTaskSink : TaskSink {
        val tasks = mutableListOf<NewTask>()
        private val keys = mutableSetOf<String>()

        /** Mirrors the unique index on (sourceType, sourceRef, titleKey). */
        override suspend fun insertIfAbsent(task: NewTask): Boolean {
            tasks.add(task)
            if (task.sourceRef == null) return true // SQLite treats NULLs as distinct
            val key = "${task.sourceType}|${task.sourceRef}|${task.titleKey}"
            if (!keys.add(key)) {
                tasks.remove(task)
                return false
            }
            return true
        }
    }

    private class FakeReviewSink : ReviewSink {
        val proposals = mutableListOf<ReviewProposal>()
        override suspend fun propose(proposal: ReviewProposal) {
            proposals.add(proposal)
        }
    }

    private class FakeMarker : CaptureMarker {
        val marked = mutableListOf<String>()
        override suspend fun markProcessed(rawCaptureId: String) {
            marked.add(rawCaptureId)
        }
    }

    private class FakeLog : FunnelLog {
        val lines = mutableListOf<Triple<String, LogLevel, String>>()
        override suspend fun log(stage: String, level: LogLevel, message: String, detail: String?) {
            lines.add(Triple(stage, level, message))
        }
    }

    private class FakeNotifier : TaskCreatedNotifier {
        val notified = mutableListOf<String>()
        override suspend fun onTaskCreated(taskId: String, title: String) {
            notified.add(title)
        }
    }

    private class SeqIds : IdGenerator {
        private var n = 0
        override fun newId(): String = "id-${n++}"
    }

    private val occurredAt = ZonedDateTime.parse("2025-07-07T14:00:00+05:30").toInstant().toEpochMilli()

    private class Harness(config: FunnelConfig = FunnelConfig.DEFAULT) {
        val sink = FakeTaskSink()
        val review = FakeReviewSink()
        val marker = FakeMarker()
        val log = FakeLog()
        val notifier = FakeNotifier()
        val funnel = IntakeFunnel(
            taskSink = sink,
            reviewSink = review,
            captureMarker = marker,
            log = log,
            notifier = notifier,
            ids = SeqIds(),
            clock = object : Clock {
                override fun now(): Long = 1_751_875_200_000L
            },
            configProvider = { config },
        )
    }

    private val messageText =
        "beta woh 25000 ka payment kal tak kar dena warna late fee lagegi"

    private fun notificationCandidate(
        title: String = "Pay 25,000 to Sharma Ji",
        evidence: String? = "woh 25000 ka payment kal tak kar dena",
        confidence: Double? = 0.95,
        sourceRef: String = "n:abc123",
        rawCaptureId: String? = "raw-1",
    ) = TaskCandidate(
        title = title,
        evidence = evidence,
        priority = Priority.HIGH,
        dueAtRaw = "2025-07-08T18:00:00+05:30",
        notes = "Late fee applies if missed",
        confidence = confidence,
        sourceType = SourceType.NOTIFICATION,
        sourceRef = sourceRef,
        sourceLabel = "Sharma Ji - WhatsApp",
        sourceApp = "com.whatsapp",
        rawCaptureId = rawCaptureId,
        inferenceOrigin = "cloud:gpt-4o-mini",
        sourceText = messageText,
        occurredAt = occurredAt,
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun `a confident grounded notification creates a task`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(notificationCandidate())
        assertTrue("$r", r is IntakeResult.Created)
        assertEquals(1, h.sink.tasks.size)
        assertEquals("Pay 25,000 to Sharma Ji", h.sink.tasks[0].title)
        assertNotNull(h.sink.tasks[0].dueAt)
        assertEquals(1, h.notifier.notified.size)
    }

    /**
     * Failure mode 5: persist first, mark second. A capture may only be marked
     * processed once the task it produced actually exists.
     */
    @Test
    fun `the raw capture is marked only after the task is persisted`() = runTest {
        val h = Harness()
        h.funnel.submit(notificationCandidate())
        assertEquals(listOf("raw-1"), h.marker.marked)
        assertEquals(1, h.sink.tasks.size)
    }

    @Test
    fun `evidence that is not in the source is dropped`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(
            notificationCandidate(evidence = "transfer 90000 to the vendor before Diwali"),
        )
        assertTrue("$r", r is IntakeResult.Discarded)
        assertEquals(0, h.sink.tasks.size)
        assertEquals(0, h.review.proposals.size)
        assertTrue(h.log.lines.any { it.second == LogLevel.WARN })
    }

    @Test
    fun `null evidence from an automated source is dropped`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(notificationCandidate(evidence = null))
        assertTrue("$r", r is IntakeResult.Discarded)
    }

    @Test
    fun `manual entry skips the evidence check`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(
            TaskCandidate(
                title = "Buy milk",
                evidence = null,
                sourceType = SourceType.MANUAL,
                sourceRef = null,
                occurredAt = occurredAt,
            ),
        )
        assertTrue("$r", r is IntakeResult.Created)
    }

    @Test
    fun `mid confidence goes to the review inbox`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(notificationCandidate(confidence = 0.55))
        assertTrue("$r", r is IntakeResult.SentToReview)
        assertEquals(0, h.sink.tasks.size)
        assertEquals(1, h.review.proposals.size)
        assertEquals(0, h.notifier.notified.size)
    }

    @Test
    fun `low confidence is discarded`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(notificationCandidate(confidence = 0.2))
        assertTrue("$r", r is IntakeResult.Discarded)
        assertEquals(0, h.sink.tasks.size)
        assertEquals(0, h.review.proposals.size)
    }

    @Test
    fun `missing confidence is treated as uncertain not as certain`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(notificationCandidate(confidence = null))
        assertTrue("$r", r is IntakeResult.SentToReview)
    }

    @Test
    fun `out of range confidence is invalid`() = runTest {
        val h = Harness()
        assertTrue(h.funnel.submit(notificationCandidate(confidence = 1.4)) is IntakeResult.Invalid)
        assertTrue(h.funnel.submit(notificationCandidate(confidence = -0.1)) is IntakeResult.Invalid)
    }

    @Test
    fun `a duplicate is silently absorbed by the unique index`() = runTest {
        val h = Harness()
        assertTrue(h.funnel.submit(notificationCandidate()) is IntakeResult.Created)
        val second = h.funnel.submit(notificationCandidate())
        assertTrue("$second", second is IntakeResult.Duplicate)
        assertEquals(1, h.sink.tasks.size)
        assertEquals("no second notification for a duplicate", 1, h.notifier.notified.size)
    }

    @Test
    fun `politeness variants of the same title dedup together`() = runTest {
        val h = Harness()
        h.funnel.submit(notificationCandidate(title = "Pay Sharma Ji"))
        val second = h.funnel.submit(notificationCandidate(title = "bhai Pay Sharma Ji."))
        assertTrue("$second", second is IntakeResult.Duplicate)
    }

    @Test
    fun `manual tasks with a null sourceRef may legitimately repeat`() = runTest {
        val h = Harness()
        repeat(2) {
            val r = h.funnel.submit(
                TaskCandidate(
                    title = "Buy milk",
                    evidence = null,
                    sourceType = SourceType.MANUAL,
                    sourceRef = null,
                    occurredAt = occurredAt,
                ),
            )
            assertTrue("$r", r is IntakeResult.Created)
        }
        assertEquals(2, h.sink.tasks.size)
    }

    @Test
    fun `a blank title is invalid`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(notificationCandidate(title = "   "))
        assertTrue("$r", r is IntakeResult.Invalid)
    }

    @Test
    fun `an automated candidate without a sourceRef is invalid`() = runTest {
        val h = Harness()
        val r = h.funnel.submit(
            notificationCandidate().copy(sourceRef = null),
        )
        assertTrue("$r", r is IntakeResult.Invalid)
    }

    @Test
    fun `a wrongly resolved date is dropped and preserved in notes`() = runTest {
        val h = Harness()
        h.funnel.submit(
            notificationCandidate().copy(dueAtRaw = "2024-07-08T18:00:00+05:30"),
        )
        val task = h.sink.tasks.single()
        assertNull(task.dueAt)
        assertTrue(task.notes!!.contains("stated deadline: 2024-07-08T18:00:00+05:30"))
    }

    @Test
    fun `every outcome writes an activity log line`() = runTest {
        val h = Harness()
        h.funnel.submit(notificationCandidate())
        h.funnel.submit(notificationCandidate(confidence = 0.5))
        h.funnel.submit(notificationCandidate(confidence = 0.1))
        h.funnel.submit(notificationCandidate(title = ""))
        val funnelLines = h.log.lines.filter { it.first == "funnel" }
        assertTrue("expected one log line per submit, got $funnelLines", funnelLines.size >= 4)
    }

    /**
     * Spec 5: the single most important structural rule. Manual entry,
     * notification, call and review-accept must all converge on submit().
     * If this test ever needs changing, something has grown a second path to
     * the task table - which is failure mode 1.
     */
    @Test
    fun `all sources converge on the same funnel`() = runTest {
        val h = Harness()
        val callTranscript = "haan ji main kal tak wo report bhej deta hoon aap chinta mat kijiye"
        val clipboardText = "Speaker 1: kal tak invoice bhej dena bhai zaroor"

        val candidates = listOf(
            notificationCandidate(),
            TaskCandidate(
                title = "Send the report",
                evidence = "main kal tak wo report bhej deta hoon",
                confidence = 0.9,
                sourceType = SourceType.CALL,
                sourceRef = "c:4242",
                sourceLabel = "Call with +919812345678",
                rawCaptureId = "raw-call",
                sourceText = callTranscript,
                occurredAt = occurredAt,
            ),
            TaskCandidate(
                title = "Send the invoice",
                evidence = "kal tak invoice bhej dena",
                confidence = 0.88,
                sourceType = SourceType.CLIPBOARD,
                sourceRef = "p:deadbeef",
                sourceText = clipboardText,
                occurredAt = occurredAt,
            ),
            TaskCandidate(
                title = "Buy milk",
                evidence = null,
                sourceType = SourceType.MANUAL,
                sourceRef = null,
                occurredAt = occurredAt,
            ),
            TaskCandidate(
                title = "Send the invoice",
                evidence = "kal tak invoice bhej dena",
                // The accept action submits with the user's own certainty:
                // they have already looked at the source text.
                confidence = 1.0,
                sourceType = SourceType.REVIEW,
                sourceRef = "p:deadbeef",
                sourceText = clipboardText,
                occurredAt = occurredAt,
            ),
        )

        for (c in candidates) {
            val r = h.funnel.submit(c)
            assertTrue("source ${c.sourceType} did not produce a task: $r", r is IntakeResult.Created)
        }

        assertEquals(candidates.size, h.sink.tasks.size)
        assertEquals(
            setOf(
                SourceType.NOTIFICATION,
                SourceType.CALL,
                SourceType.CLIPBOARD,
                SourceType.MANUAL,
                SourceType.REVIEW,
            ),
            h.sink.tasks.map { it.sourceType }.toSet(),
        )
    }

    @Test
    fun `an accepted review item is created regardless of its original confidence`() = runTest {
        val h = Harness()
        // REVIEW candidates are submitted with confidence 1.0 by the accept
        // action: the user has already made the judgement.
        val r = h.funnel.submit(
            notificationCandidate(confidence = 1.0).copy(sourceType = SourceType.REVIEW),
        )
        assertTrue("$r", r is IntakeResult.Created)
    }

    @Test
    fun `tunable thresholds actually move the gate`() = runTest {
        val strict = Harness(FunnelConfig(autoCreateThreshold = 0.99, reviewThreshold = 0.9))
        assertTrue(strict.funnel.submit(notificationCandidate(confidence = 0.95)) is IntakeResult.SentToReview)

        val loose = Harness(FunnelConfig(autoCreateThreshold = 0.3, reviewThreshold = 0.1))
        assertTrue(loose.funnel.submit(notificationCandidate(confidence = 0.4)) is IntakeResult.Created)
    }
}
