package com.taskmind.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taskmind.MainActivity
import com.taskmind.Routes
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Everything the widget's buttons do, on a receiver nothing outside this app
 * can reach.
 *
 * WHY THIS IS A SEPARATE RECEIVER
 *
 * [TasksWidget] has to be `exported="true"` - that is the only way a launcher
 * can bind an app widget. Exporting it means any app on the phone can send it
 * an explicit intent, and while the intent filter only advertises
 * APPWIDGET_UPDATE, an explicit intent reaches the component whatever action
 * it carries. So while the complete, page and row actions lived on that
 * receiver, another app could complete a task or make TaskMind open itself.
 *
 * A PendingIntent runs with this app's identity, so it can target a component
 * that is not exported at all. Moving the actions here costs nothing and
 * closes it completely.
 */
class TasksWidgetActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        when (intent.action) {
            ACTION_COMPLETE -> {
                val taskId = intent.getStringExtra(TasksWidget.EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) complete(app, taskId)
            }

            ACTION_ROW -> {
                // One template serves the whole scrolling list, so the row says
                // in an extra which half of itself was tapped.
                when (intent.getStringExtra(EXTRA_ROW_OP)) {
                    OP_DONE -> {
                        val taskId = intent.getStringExtra(TasksWidget.EXTRA_TASK_ID)
                        if (!taskId.isNullOrBlank()) complete(app, taskId)
                    }

                    OP_OPEN -> open(app, intent.getStringExtra(MainActivity.EXTRA_ROUTE))
                }
            }

            ACTION_PAGE -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
                val delta = intent.getIntExtra(EXTRA_PAGE_DELTA, 0)
                if (widgetId != 0 && delta != 0) {
                    WidgetPaging.move(app, widgetId, delta * TasksWidget.SLOTS)
                    redraw(app)
                }
            }
        }
    }

    /** Ticks a task off the database, then redraws, without blocking this thread. */
    private fun complete(app: Context, taskId: String) {
        val pending = goAsync()
        Thread {
            try {
                // The same repository call the app's own checkbox makes, so a
                // recurring task still spawns its next instance when ticked off
                // from the home screen.
                runBlocking { AppContainer.get(app).taskRepository.complete(taskId) }
                TasksWidget.redrawAll(app)
            } catch (t: Throwable) {
                log(app, "widget could not complete a task", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun redraw(app: Context) {
        val pending = goAsync()
        Thread {
            try {
                TasksWidget.redrawAll(app)
            } catch (t: Throwable) {
                log(app, "widget could not read tasks", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * Opens a task from a scrolling row.
     *
     * A collection view carries exactly one pending-intent template and the
     * tick needs a broadcast, so the tap comes back through here and starts the
     * activity by hand. Starting an activity from a receiver is normally
     * blocked in the background; it is allowed here because the launcher - a
     * visible app - is what sent the PendingIntent, which grants this app a
     * short window to do it. If a launcher ever declines, the paging mode in
     * Settings uses a direct activity PendingIntent instead.
     */
    private fun open(app: Context, route: String?) {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_ROUTE, route ?: Routes.TASKS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { app.startActivity(intent) }
            .onFailure { log(app, "widget could not open a task", it) }
    }

    private fun log(context: Context, message: String, t: Throwable) {
        runCatching {
            val container = AppContainer.get(context)
            container.applicationScope.launch {
                container.logger.write(Stage.SYSTEM, LogLevel.WARN, message, t.toString())
            }
        }
    }

    companion object {
        const val ACTION_COMPLETE = "com.taskmind.action.WIDGET_COMPLETE"
        const val ACTION_PAGE = "com.taskmind.action.WIDGET_PAGE"
        const val ACTION_ROW = "com.taskmind.action.WIDGET_ROW"

        const val EXTRA_PAGE_DELTA = "page_delta"
        const val EXTRA_ROW_OP = "row_op"

        const val OP_OPEN = "open"
        const val OP_DONE = "done"
    }
}
