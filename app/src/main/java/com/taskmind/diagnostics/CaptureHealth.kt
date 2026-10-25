package com.taskmind.diagnostics

import android.content.Context
import com.taskmind.core.CaptureMetrics
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.first

/**
 * The capture metrics, from the phone's own tables, plus the baseline they
 * are judged against.
 *
 * The baseline is the same three numbers taken once, the first time v1.21
 * runs - before the stricter prompt has created a single task - so "kept rate
 * went from 34% to 72%" is a statement about the change, not about a week
 * that happened to be quiet.
 */
object CaptureHealth {

    private const val PREFS = "taskmind_once"
    private const val KEY_BASELINE = "capture_baseline_v121"
    private const val KEY_BASELINE_AT = "capture_baseline_v121_at"
    private const val DAY = 24L * 60 * 60 * 1000

    suspend fun compute(container: AppContainer, now: Long = System.currentTimeMillis()): CaptureMetrics.Result {
        val tasks = container.taskRepository.observeTasks().first().map {
            CaptureMetrics.Task(CaptureMetrics.isCaptured(it.sourceType), it.status, it.createdAt)
        }
        val maybes = container.database.reviewItemDao()
            .since(now - CaptureMetrics.WINDOW_DAYS * DAY)
            .map { CaptureMetrics.Maybe(it.state, it.createdAt) }
        return CaptureMetrics.compute(tasks, maybes, now)
    }

    /** Takes the baseline if it has never been taken. */
    suspend fun recordBaselineOnce(context: Context, container: AppContainer) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_BASELINE)) return
        val result = compute(container)
        prefs.edit()
            .putString(KEY_BASELINE, result.describe())
            .putLong(KEY_BASELINE_AT, System.currentTimeMillis())
            .apply()
    }

    /** The baseline as text and when it was taken, or null if not yet. */
    fun baseline(context: Context): Pair<String, Long>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_BASELINE, null) ?: return null
        return text to prefs.getLong(KEY_BASELINE_AT, 0L)
    }
}
