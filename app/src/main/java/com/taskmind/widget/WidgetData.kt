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
 * `runBlocking` is correct here and nowhere else in the app: a widget render
 * has to hand a finished RemoteViews to the launcher, so it needs its answer
 * before it returns. The caller keeps that block off the main thread - see
 * `TasksWidget.onReceive`.
 *
 * Read-only throughout - this takes the first value of flows the app already
 * exposes and writes nothing back.
 */
object WidgetData {

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

    /** Everything one render needs, from a single read of the task table. */
    data class Snapshot(val tasks: List<TaskEntity>, val counts: Counts)

    /**
     * One read, not two. The counts are derived from the same list the rows
     * are drawn from, so the header can never disagree with what is below it.
     */
    fun snapshot(context: Context): Snapshot {
        val tasks = pendingTasks(context)
        return Snapshot(tasks, counts(context, tasks))
    }

    private fun counts(context: Context, tasks: List<TaskEntity>): Counts {
        val now = System.currentTimeMillis()
        val endOfToday = endOfDay(now)
        return Counts(
            pending = tasks.size,
            overdue = tasks.count { it.dueAt != null && it.dueAt!! < now },
            dueToday = tasks.count { it.dueAt != null && it.dueAt!! in now..endOfToday },
            // The Inbox badge: maybes waiting, plus captured tasks not yet decided on.
            awaitingApproval = runCatching {
                runBlocking {
                    val db = AppContainer.get(context).database
                    db.reviewItemDao().observePendingCount().first() + db.taskDao().observeInboxCount().first()
                }
            }.getOrDefault(0),
        )
    }

    /**
     * Active tasks, soonest first, undated last.
     *
     * Sorted here rather than in a query so the widget stays a pure consumer
     * of the existing DAO surface and adds nothing to it. Uncapped on purpose:
     * the counts above are drawn from this list, so trimming it here would
     * quietly cap "12 tasks" at whatever the row limit happened to be. The
     * widget slices it for display.
     */
    fun pendingTasks(context: Context): List<TaskEntity> = runCatching {
        val today = com.taskmind.core.DateResolver.dayKey(System.currentTimeMillis())
        runBlocking {
            AppContainer.get(context).database.taskDao().observeAll().first()
        }
            // Your list, not the Inbox: captured items wait there until you
            // decide on them, and a task's steps live inside the task.
            .filter { it.status == TaskStatus.ACTIVE && !it.inbox && it.parentTaskId == null }
            // What you put on today first, then by date.
            .sortedWith(compareBy({ if (it.plannedFor == today) 0 else 1 }, { it.dueAt ?: Long.MAX_VALUE }, { it.title }))
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
