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
        if (intent.action != ACTION_REFRESH &&
            intent.action != AppWidgetManager.ACTION_APPWIDGET_UPDATE
        ) {
            super.onReceive(context, intent)
            return
        }

        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = runCatching {
            manager.getAppWidgetIds(ComponentName(app, TasksWidget::class.java))
        }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return

        for (id in ids) {
            runCatching { manager.updateAppWidget(id, placeholder(app)) }
        }

        val pending = goAsync()
        Thread {
            try {
                val snapshot = WidgetData.snapshot(app)
                val views = render(app, snapshot)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (t: Throwable) {
                // The placeholder is already on screen, so this degrades to a
                // widget showing its header and nothing else - visible, and
                // explained in the log rather than nowhere.
                runCatching {
                    val container = AppContainer.get(app)
                    container.applicationScope.launch {
                        container.logger.write(
                            Stage.SYSTEM,
                            LogLevel.WARN,
                            "widget could not read tasks",
                            t.toString(),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * Unreachable while [onReceive] intercepts the update broadcast; kept as
     * the correct behaviour for any direct caller.
     */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = render(context, WidgetData.snapshot(context))
        for (id in ids) manager.updateAppWidget(id, views)
    }

    /** A complete, valid frame that touches nothing but strings. */
    private fun placeholder(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_tasks).apply {
            setTextViewText(R.id.widget_summary, "Loading…")
            setViewVisibility(R.id.widget_empty, View.GONE)
            setViewVisibility(R.id.widget_more, View.GONE)
            setViewVisibility(R.id.widget_review_badge, View.GONE)
            for (slot in 0 until SLOTS) setViewVisibility(ROWS[slot], View.GONE)
            setOnClickPendingIntent(R.id.widget_title, openApp(context, Routes.TASKS))
        }

    private fun render(context: Context, snapshot: WidgetData.Snapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)
        val counts = snapshot.counts
        val tasks = snapshot.tasks
        val now = System.currentTimeMillis()

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
                fillRow(context, views, slot, task, now)
            }
        }

        views.setViewVisibility(R.id.widget_empty, if (tasks.isEmpty()) View.VISIBLE else View.GONE)

        val hidden = tasks.size - minOf(tasks.size, SLOTS)
        if (hidden > 0) {
            views.setTextViewText(R.id.widget_more, "and $hidden more")
            views.setViewVisibility(R.id.widget_more, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_more, View.GONE)
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

        /** Nudges every placed widget to re-read. Safe to call when none exist. */
        fun refresh(context: Context) {
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
