package com.taskmind.widget

import android.content.Context
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * What the widget shows, read straight from the database.
 *
 * `runBlocking` is correct here and nowhere else in the app: a
 * RemoteViewsFactory is called on a binder thread that the launcher expects to
 * block, and both `onDataSetChanged` and `getViewAt` must have their answer
 * before they return. There is no suspending version of that contract.
 *
 * Read-only throughout - this takes the first value of flows the app already
 * exposes and writes nothing back.
 */
object WidgetData {

    /** How many rows the widget will hold before it needs scrolling anyway. */
    private const val MAX_ROWS = 25

    data class Counts(
        val pending: Int,
        val overdue: Int,
        val dueToday: Int,
        val awaitingApproval: Int,
    ) {
        /**
         * One line, most urgent fact first.
         *
         * "12 pending" alone is not actionable; "3 overdue · 2 today" is the
         * thing worth glancing at a home screen for.
         */
        val summary: String
            get() = buildList {
                if (overdue > 0) add("$overdue overdue")
                if (dueToday > 0) add("$dueToday today")
                if (isEmpty()) add(if (pending == 1) "1 task" else "$pending tasks")
            }.joinToString("  ·  ")
    }

    fun counts(context: Context): Counts {
        val tasks = pendingTasks(context)
        val now = System.currentTimeMillis()
        val endOfToday = endOfDay(now)
        return Counts(
            pending = tasks.size,
            overdue = tasks.count { it.dueAt != null && it.dueAt!! < now },
            dueToday = tasks.count { it.dueAt != null && it.dueAt!! in now..endOfToday },
            awaitingApproval = runCatching {
                runBlocking {
                    AppContainer.get(context).database.reviewItemDao().observePendingCount().first()
                }
            }.getOrDefault(0),
        )
    }

    /**
     * Active tasks, soonest first, undated last.
     *
     * Sorted here rather than in a query so the widget stays a pure consumer
     * of the existing DAO surface and adds nothing to it.
     */
    fun pendingTasks(context: Context): List<TaskEntity> = runCatching {
        runBlocking {
            AppContainer.get(context).database.taskDao().observeAll().first()
        }
            .filter { it.status == TaskStatus.ACTIVE }
            .sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.title }))
            .take(MAX_ROWS)
    }.getOrDefault(emptyList())

    private fun endOfDay(now: Long): Long {
        val zone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val cal = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }
        return cal.timeInMillis
    }
}
