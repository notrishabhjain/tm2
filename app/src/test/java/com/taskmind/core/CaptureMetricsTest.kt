package com.taskmind.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The numbers that say whether capture is worth having. */
class CaptureMetricsTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun task(status: TaskStatus, daysAgo: Int, captured: Boolean = true) =
        CaptureMetrics.Task(captured, status, now - daysAgo * day)

    @Test fun `kept rate counts only captured tasks old enough to judge`() {
        val tasks = listOf(
            task(TaskStatus.COMPLETED, 10),
            task(TaskStatus.ACTIVE, 12),
            task(TaskStatus.DELETED, 9),
            task(TaskStatus.DELETED, 8),
            task(TaskStatus.COMPLETED, 20),
            // Too new to judge, and typed by hand: both ignored.
            task(TaskStatus.DELETED, 2),
            task(TaskStatus.DELETED, 10, captured = false),
        )
        val r = CaptureMetrics.compute(tasks, emptyList(), now)
        assertEquals(60, r.keptPercent)
        assertEquals(5, r.judged)
    }

    @Test fun `too few tasks says so rather than showing a misleading percentage`() {
        val r = CaptureMetrics.compute(listOf(task(TaskStatus.DELETED, 10)), emptyList(), now)
        assertNull(r.keptPercent)
    }

    @Test fun `new per day is over the whole window`() {
        val tasks = (1..15).map { task(TaskStatus.ACTIVE, it) }
        assertEquals(0.5, CaptureMetrics.compute(tasks, emptyList(), now).newPerDay, 0.001)
    }

    @Test fun `digest drop rate ignores maybes still waiting`() {
        val maybes = listOf(ReviewState.DISMISSED, ReviewState.DISMISSED, ReviewState.DISMISSED, ReviewState.ACCEPTED,
            ReviewState.DISMISSED, ReviewState.PENDING).map { CaptureMetrics.Maybe(it, now - day) }
        assertEquals(80, CaptureMetrics.compute(emptyList(), maybes, now).droppedPercent)
    }
}
