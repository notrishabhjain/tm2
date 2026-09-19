package com.taskmind.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.taskmind.MainActivity
import com.taskmind.R
import com.taskmind.Routes
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The home-screen widget: what is pending, without opening anything.
 *
 * THIRD ATTEMPT, AND DELIBERATELY THE DULLEST ONE
 *
 * The first version used a ListView fed by a RemoteViewsService and the
 * launcher drew "Couldn't add widget." in every row. The second built rows
 * with RemoteViews.addView and the launcher rejected the whole thing with
 * "Can't load widget". Both were guesses at which link in a chain had broken,
 * and both were wrong.
 *
 * So this one has no chain. Every row is declared in `widget_tasks.xml`, and
 * the only calls made here are setTextViewText, setViewVisibility,
 * setTextColor, setInt and setOnClickPendingIntent against ids from that file.
 * No service, no adapter, no nested RemoteViews, no addView. The cost is a
 * fixed ceiling of [SLOTS] rows; the benefit is that there is no longer any
 * mechanism left to fail.
 *
 * It also renders twice on purpose - see [onReceive].
 *
 * It reads through the same DAO flows the app uses and writes nothing.
 */
class TasksWidget : AppWidgetProvider() {

    /**
     * Renders a valid frame synchronously, then fills it in from a background
     * thread.
     *
     * The synchronous pass is the important one. Reading the database takes a
     * moment and must not happen on this thread, but a widget whose provider
     * returns without ever calling `updateAppWidget` is exactly what makes a
     * launcher give up and show its own error. Handing it a complete, valid
     * RemoteViews first means the worst case is a widget that says "Loading"
     * for a moment, rather than one that says nothing can be loaded at all.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) {
            super.onReceive(context, intent)
            return
        }

        val app = context.applicationContext

        // Acted on before the redraw below, so the redraw already reflects it
        // and the row disappears in one step rather than two.
        when (intent.action) {
            ACTION_COMPLETE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    val pending = goAsync()
                    Thread {
                        try {
                            val container = AppContainer.get(app)
                            // The same repository call the app's own checkbox
                            // makes, so a recurring task still spawns its next
                            // instance when ticked off from the home screen.
                            runBlocking { container.taskRepository.complete(taskId) }
                            renderAll(app)
                        } catch (t: Throwable) {
                            log(app, "widget could not complete a task", t)
                        } finally {
                            pending.finish()
                        }
                    }.start()
                    return
                }
            }

            ACTION_PAGE -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
                val delta = intent.getIntExtra(EXTRA_PAGE_DELTA, 0)
                if (widgetId != 0 && delta != 0) WidgetPaging.move(app, widgetId, delta * SLOTS)
            }
        }
        val manager = AppWidgetManager.getInstance(app)
        val ids = runCatching {
            manager.getAppWidgetIds(ComponentName(app, TasksWidget::class.java))
        }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return

        // Only on the system's own update, which is the case where the widget
        // may still be showing nothing. Painting it on a page tap or a refresh
        // would blank the rows and flash "Loading" over content that is
        // already correct.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            for (id in ids) {
                runCatching { manager.updateAppWidget(id, placeholder(app)) }
            }
        }

        val pending = goAsync()
        Thread {
            try {
                renderAll(app)
            } catch (t: Throwable) {
                // The placeholder is already on screen, so this degrades to a
                // widget showing its header and nothing else - visible, and
                // explained in the log rather than nowhere.
                log(app, "widget could not read tasks", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * Redraws every placed widget from one read of the database.
     *
     * Each widget gets its own RemoteViews because each has its own page
     * offset; the snapshot behind them is shared.
     */
    private fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))
        if (ids.isEmpty()) return
        val snapshot = WidgetData.snapshot(context)
        for (id in ids) manager.updateAppWidget(id, render(context, snapshot, id))
    }

    private fun log(context: Context, message: String, t: Throwable) {
        runCatching {
            val container = AppContainer.get(context)
            container.applicationScope.launch {
                container.logger.write(Stage.SYSTEM, LogLevel.WARN, message, t.toString())
            }
        }
    }

    /**
     * Unreachable while [onReceive] intercepts the update broadcast; kept as
     * the correct behaviour for any direct caller.
     */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snapshot = WidgetData.snapshot(context)
        for (id in ids) manager.updateAppWidget(id, render(context, snapshot, id))
    }

    /** Forgets a removed widget's page offset rather than leaking it forever. */
    override fun onDeleted(context: Context, ids: IntArray) {
        super.onDeleted(context, ids)
        WidgetPaging.forget(context, ids)
    }

    /** A complete, valid frame that touches nothing but strings. */
    private fun placeholder(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_tasks).apply {
            setTextViewText(R.id.widget_summary, "Loading…")
            setViewVisibility(R.id.widget_empty, View.GONE)
            setViewVisibility(R.id.widget_footer, View.GONE)
            setViewVisibility(R.id.widget_review_badge, View.GONE)
            for (slot in 0 until SLOTS) setViewVisibility(ROWS[slot], View.GONE)
            setOnClickPendingIntent(R.id.widget_title, openApp(context, Routes.TASKS))
        }

    private fun render(context: Context, snapshot: WidgetData.Snapshot, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)
        val counts = snapshot.counts
        val all = snapshot.tasks
        val now = System.currentTimeMillis()

        val offset = WidgetPaging.offset(context, widgetId, all.size, SLOTS)
        val tasks = all.drop(offset).take(SLOTS)

        views.setTextViewText(R.id.widget_summary, counts.summary)

        if (counts.awaitingApproval > 0) {
            views.setTextViewText(R.id.widget_review_badge, "${counts.awaitingApproval} to approve")
            views.setViewVisibility(R.id.widget_review_badge, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_review_badge, openApp(context, Routes.REVIEW))
        } else {
            views.setViewVisibility(R.id.widget_review_badge, View.GONE)
        }

        for (slot in 0 until SLOTS) {
            val task = tasks.getOrNull(slot)
            if (task == null) {
                views.setViewVisibility(ROWS[slot], View.GONE)
            } else {
                fillRow(context, views, slot, task, now, widgetId)
            }
        }

        views.setViewVisibility(R.id.widget_empty, if (all.isEmpty()) View.VISIBLE else View.GONE)

        if (all.size > SLOTS) {
            val from = offset + 1
            val to = offset + tasks.size
            views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
            views.setTextViewText(R.id.widget_more, "$from-$to of ${all.size}")
            // Both arrows stay visible at the ends and simply do nothing, so
            // the row does not reflow as you page through it.
            views.setOnClickPendingIntent(R.id.widget_prev, pageIntent(context, widgetId, -1))
            views.setOnClickPendingIntent(R.id.widget_next, pageIntent(context, widgetId, 1))
        } else {
            views.setViewVisibility(R.id.widget_footer, View.GONE)
        }

        views.setOnClickPendingIntent(R.id.widget_title, openApp(context, Routes.TASKS))
        views.setOnClickPendingIntent(R.id.widget_summary, openApp(context, Routes.TASKS))
        views.setOnClickPendingIntent(R.id.widget_empty, openApp(context, Routes.TASKS))
        views.setOnClickPendingIntent(R.id.widget_more, openApp(context, Routes.TASKS))

        return views
    }

    private fun fillRow(
        context: Context,
        views: RemoteViews,
        slot: Int,
        task: TaskEntity,
        now: Long,
        widgetId: Int,
    ) {
        val due = task.dueAt
        val overdue = due != null && due < now
        val accent = context.getColor(if (overdue) R.color.widget_overdue else R.color.widget_accent)

        views.setViewVisibility(ROWS[slot], View.VISIBLE)
        views.setTextViewText(TITLES[slot], task.title)
        views.setInt(BARS[slot], "setBackgroundColor", accent)

        val meta = buildList {
            due?.let { add(shortDate(it, now)) }
            task.sourceLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")

        if (meta.isBlank()) {
            views.setViewVisibility(METAS[slot], View.GONE)
        } else {
            views.setTextViewText(METAS[slot], meta)
            views.setViewVisibility(METAS[slot], View.VISIBLE)
            views.setTextColor(
                METAS[slot],
                context.getColor(
                    if (overdue) R.color.widget_overdue else R.color.widget_on_surface_variant,
                ),
            )
        }

        views.setOnClickPendingIntent(ROWS[slot], openApp(context, Routes.taskDetail(task.id)))
        views.setOnClickPendingIntent(DONES[slot], completeIntent(context, task.id, widgetId))
    }

    /**
     * Ticks a task off without opening the app.
     *
     * The request code mixes the task id and the widget id so two widgets
     * showing the same task do not share one pending intent - the system keys
     * these on request code and intent equality, and extras do not count
     * towards that.
     */
    private fun completeIntent(context: Context, taskId: String, widgetId: Int): PendingIntent {
        val intent = Intent(context, TasksWidget::class.java)
            .setAction(ACTION_COMPLETE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(android.net.Uri.parse("taskmind://complete/$widgetId/$taskId"))
        return PendingIntent.getBroadcast(
            context,
            (taskId + widgetId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pageIntent(context: Context, widgetId: Int, delta: Int): PendingIntent {
        val intent = Intent(context, TasksWidget::class.java)
            .setAction(ACTION_PAGE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(EXTRA_PAGE_DELTA, delta)
            .setData(android.net.Uri.parse("taskmind://page/$widgetId/$delta"))
        return PendingIntent.getBroadcast(
            context,
            ("page$widgetId:$delta").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Every pending intent here is IMMUTABLE - nothing outside this app can
     * alter where one points. The request code has to differ per route or the
     * system hands back the first intent for every row.
     */
    private fun openApp(context: Context, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.taskmind.action.WIDGET_REFRESH"
        const val ACTION_COMPLETE = "com.taskmind.action.WIDGET_COMPLETE"
        const val ACTION_PAGE = "com.taskmind.action.WIDGET_PAGE"

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PAGE_DELTA = "page_delta"

        private val HANDLED = setOf(
            ACTION_REFRESH,
            ACTION_COMPLETE,
            ACTION_PAGE,
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
        )

        /** Must match the number of row blocks in `widget_tasks.xml`. */
        const val SLOTS = 8

        private val ROWS = intArrayOf(
            R.id.row0, R.id.row1, R.id.row2, R.id.row3,
            R.id.row4, R.id.row5, R.id.row6, R.id.row7,
        )
        private val BARS = intArrayOf(
            R.id.row0_bar, R.id.row1_bar, R.id.row2_bar, R.id.row3_bar,
            R.id.row4_bar, R.id.row5_bar, R.id.row6_bar, R.id.row7_bar,
        )
        private val TITLES = intArrayOf(
            R.id.row0_title, R.id.row1_title, R.id.row2_title, R.id.row3_title,
            R.id.row4_title, R.id.row5_title, R.id.row6_title, R.id.row7_title,
        )
        private val METAS = intArrayOf(
            R.id.row0_meta, R.id.row1_meta, R.id.row2_meta, R.id.row3_meta,
            R.id.row4_meta, R.id.row5_meta, R.id.row6_meta, R.id.row7_meta,
        )
        private val DONES = intArrayOf(
            R.id.row0_done, R.id.row1_done, R.id.row2_done, R.id.row3_done,
            R.id.row4_done, R.id.row5_done, R.id.row6_done, R.id.row7_done,
        )

        /**
         * Nudges every placed widget to re-read, back at the first page.
         *
         * Called when the app goes to the background. Coming back to a home
         * screen showing page three of a list you have just been editing is
         * disorienting; the top is where the urgent things are.
         */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))
                for (id in ids) WidgetPaging.reset(context, id)
            }
            val intent = Intent(context, TasksWidget::class.java).setAction(ACTION_REFRESH)
            runCatching { context.sendBroadcast(intent) }
        }
    }
}

/** "Overdue", "Today", "Tue" or "3 Oct" - whichever is shortest and clearest. */
private fun shortDate(millis: Long, now: Long): String {
    val zone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    val day = 24L * 60 * 60 * 1000
    val today = java.util.Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        millis < today -> "Overdue"
        millis < today + day -> "Today"
        millis < today + 2 * day -> "Tomorrow"
        millis < today + 7 * day ->
            java.text.SimpleDateFormat("EEE", java.util.Locale.ENGLISH)
                .apply { timeZone = zone }.format(java.util.Date(millis))
        else ->
            java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH)
                .apply { timeZone = zone }.format(java.util.Date(millis))
    }
}
