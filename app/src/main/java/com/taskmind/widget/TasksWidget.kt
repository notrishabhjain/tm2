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
import com.taskmind.data.db.entity.TaskEntity

/**
 * The home-screen widget: what is pending, without opening anything.
 *
 * Built on RemoteViews rather than Glance deliberately. Glance is less code,
 * but it pulls in a new dependency whose Compose-compiler compatibility can
 * only be discovered in CI, and a widget that fails to inflate shows an empty
 * grey box with no error anywhere. RemoteViews has been stable since API 3.
 *
 * The rows are built here and added straight into the frame. The earlier
 * version used a ListView fed by a RemoteViewsService, which the launcher has
 * to bind to across processes; when any link in that chain fails the launcher
 * draws "Couldn't add widget." in every row and records the reason nowhere.
 * Static rows have no service, no binder and no adapter, so that whole failure
 * class is gone. The cost is a fixed row count with no scrolling, which is the
 * right trade for something meant to be glanced at.
 *
 * It reads through the same DAO flows the app uses and writes nothing.
 */
class TasksWidget : AppWidgetProvider() {

    /**
     * Both the system's update and our own refresh are handled here rather
     * than in `onUpdate`, because rendering has to read the database and a
     * broadcast receiver's `onReceive` runs on the main thread. `goAsync`
     * holds the broadcast open while a background thread does the read, so
     * the launcher is never waiting on disk to draw a home screen.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH &&
            intent.action != AppWidgetManager.ACTION_APPWIDGET_UPDATE
        ) {
            super.onReceive(context, intent)
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                renderAll(app)
            } catch (t: Throwable) {
                // A widget that throws here is removed by the launcher and the
                // user has to place it again. Better a stale widget than none.
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * `onUpdate` is unreachable while `onReceive` above intercepts the update
     * broadcast; it stays as the correct behaviour for any direct caller.
     */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    private fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))
        for (id in ids) render(context, manager, id)
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)

        val snapshot = WidgetData.snapshot(context)
        val counts = snapshot.counts
        views.setTextViewText(R.id.widget_summary, counts.summary)

        if (counts.awaitingApproval > 0) {
            views.setTextViewText(R.id.widget_review_badge, "${counts.awaitingApproval} to approve")
            views.setViewVisibility(R.id.widget_review_badge, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_review_badge, View.GONE)
        }

        val tasks = snapshot.tasks
        val shown = tasks.take(VISIBLE_ROWS)
        val now = System.currentTimeMillis()

        // Freshly inflated each render, so the container starts empty; clearing
        // it first is still cheap insurance against a launcher that recycles.
        views.removeAllViews(R.id.widget_rows)
        for (task in shown) {
            views.addView(R.id.widget_rows, row(context, task, now))
        }

        views.setViewVisibility(R.id.widget_empty, if (tasks.isEmpty()) View.VISIBLE else View.GONE)

        val hidden = tasks.size - shown.size
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
        views.setOnClickPendingIntent(R.id.widget_review_badge, openApp(context, Routes.REVIEW))

        manager.updateAppWidget(widgetId, views)
    }

    /** One task row, with its own pending intent - no template to fill in. */
    private fun row(context: Context, task: TaskEntity, now: Long): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_task_item)
        val due = task.dueAt
        val overdue = due != null && due < now

        views.setTextViewText(R.id.item_title, task.title)
        views.setInt(
            R.id.item_accent,
            "setBackgroundColor",
            context.getColor(if (overdue) R.color.widget_overdue else R.color.widget_accent),
        )

        val meta = buildList {
            due?.let { add(shortDate(it, now)) }
            task.sourceLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")

        if (meta.isBlank()) {
            views.setViewVisibility(R.id.item_meta, View.GONE)
        } else {
            views.setTextViewText(R.id.item_meta, meta)
            views.setViewVisibility(R.id.item_meta, View.VISIBLE)
            views.setTextColor(
                R.id.item_meta,
                context.getColor(
                    if (overdue) R.color.widget_overdue else R.color.widget_on_surface_variant,
                ),
            )
        }

        views.setOnClickPendingIntent(R.id.item_root, openApp(context, Routes.taskDetail(task.id)))
        return views
    }

    /**
     * Every pending intent here is IMMUTABLE. Nothing outside this app can
     * alter where one points, and with per-row intents there is no template
     * left that would have needed to be mutable.
     *
     * The request code has to differ per route or the system hands back the
     * first intent for every row.
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

        /**
         * How many rows are drawn. Without a scrolling list this is bounded by
         * what fits a tall home-screen widget; the rest is summarised.
         */
        private const val VISIBLE_ROWS = 6

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
