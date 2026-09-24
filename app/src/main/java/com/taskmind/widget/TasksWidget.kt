package com.taskmind.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * TWO MODES, AND WHY
 *
 * The widget can draw its rows in either of two ways, chosen by
 * [WidgetPaging.scrolling]:
 *
 *  - SCROLLING (the default): a real ListView fed by [TasksWidgetService].
 *    Every task is reachable by dragging, which is what a task list on a home
 *    screen is for.
 *  - PAGING: eight fixed row blocks declared in `widget_tasks.xml`, with
 *    prev/next arrows. No service, no adapter, no binder in the middle.
 *
 * Scrolling was tried first and the launcher drew "Couldn't add widget." in
 * every row, which is why the paging mode exists at all. The cause was found
 * much later and it was never the service: the row layout used a bare `View`
 * for its accent bar, and `View` is not a class RemoteViews supports. That
 * survives being applied in-process - which is why the paging rows worked -
 * but a collection item is serialised across a binder into the launcher and
 * validated strictly on arrival. The bar is a TextView now.
 *
 * The paging mode stays, reachable from Settings, because it costs nothing to
 * keep and the user has no way to build a debug APK if the list fails again.
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
                runCatching { manager.updateAppWidget(id, placeholder(app, id)) }
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
    internal fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))
        if (ids.isEmpty()) return
        val snapshot = WidgetData.snapshot(context)
        for (id in ids) manager.updateAppWidget(id, render(context, snapshot, id))
        // The adapter holds its own copy of the rows and will not re-query on
        // its own; without this the list keeps showing what it loaded first.
        runCatching { manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list) }
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
        runCatching { manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list) }
    }

    /** Forgets a removed widget's page offset rather than leaking it forever. */
    override fun onDeleted(context: Context, ids: IntArray) {
        super.onDeleted(context, ids)
        WidgetPaging.forget(context, ids)
    }

    /**
     * A complete, valid frame that touches nothing but strings.
     *
     * It binds the list too, even though it has read no data. The adapter is
     * bound by intent, not by content, so doing it here means the rows are
     * already on screen by the time the header stops saying "Loading" - rather
     * than the list vanishing for the length of a database read every time the
     * system sends an update.
     */
    private fun placeholder(context: Context, widgetId: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_tasks).apply {
            setTextViewText(R.id.widget_summary, "Loading…")
            setViewVisibility(R.id.widget_empty, View.GONE)
            setViewVisibility(R.id.widget_footer, View.GONE)
            setViewVisibility(R.id.widget_review_badge, View.GONE)
            for (slot in 0 until SLOTS) setViewVisibility(ROWS[slot], View.GONE)
            if (WidgetPaging.scrolling(context)) bindList(context, this, widgetId)
            setOnClickPendingIntent(R.id.widget_title, openApp(context, Routes.TASKS))
        }

    private fun render(context: Context, snapshot: WidgetData.Snapshot, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)
        val counts = snapshot.counts
        val all = snapshot.tasks

        views.setTextViewText(R.id.widget_summary, counts.summary)

        if (counts.awaitingApproval > 0) {
            views.setTextViewText(R.id.widget_review_badge, "${counts.awaitingApproval} to approve")
            views.setViewVisibility(R.id.widget_review_badge, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_review_badge, openApp(context, Routes.REVIEW))
        } else {
            views.setViewVisibility(R.id.widget_review_badge, View.GONE)
        }

        if (WidgetPaging.scrolling(context)) {
            bindList(context, views, widgetId)
        } else {
            renderPages(context, views, all, widgetId)
        }

        views.setOnClickPendingIntent(R.id.widget_title, openApp(context, Routes.TASKS))
        views.setOnClickPendingIntent(R.id.widget_summary, openApp(context, Routes.TASKS))
        views.setOnClickPendingIntent(R.id.widget_empty, openApp(context, Routes.TASKS))

        return views
    }

    /**
     * Points the ListView at [TasksWidgetService] and hides everything the
     * paging mode owns.
     *
     * The adapter intent needs a per-widget `data` URI: the system caches
     * adapters keyed on intent equality, and extras are not part of that, so
     * without it two placed widgets would share one factory.
     */
    private fun bindList(context: Context, views: RemoteViews, widgetId: Int) {
        val service = Intent(context, TasksWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("taskmind://widget/$widgetId"))

        for (slot in 0 until SLOTS) views.setViewVisibility(ROWS[slot], View.GONE)
        views.setViewVisibility(R.id.widget_footer, View.GONE)
        views.setViewVisibility(R.id.widget_list, View.VISIBLE)
        // Left to the AdapterView from here - it swaps the two around itself
        // whenever the adapter reports nothing to show.
        views.setViewVisibility(R.id.widget_empty, View.GONE)
        views.setRemoteAdapter(R.id.widget_list, service)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setPendingIntentTemplate(R.id.widget_list, rowTemplate(context, widgetId))
    }

    /** The eight-fixed-rows fallback, unchanged from the version that worked. */
    private fun renderPages(
        context: Context,
        views: RemoteViews,
        all: List<TaskEntity>,
        widgetId: Int,
    ) {
        val now = System.currentTimeMillis()
        val offset = WidgetPaging.offset(context, widgetId, all.size, SLOTS)
        val tasks = all.drop(offset).take(SLOTS)

        views.setViewVisibility(R.id.widget_list, View.GONE)

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
            views.setOnClickPendingIntent(R.id.widget_more, openApp(context, Routes.TASKS))
        } else {
            views.setViewVisibility(R.id.widget_footer, View.GONE)
        }
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
            due?.let { add(WidgetFormat.shortDate(it, now)) }
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
     * The one template every scrolling row fills in.
     *
     * MUTABLE, and it has to be: filling in a template is precisely mutating
     * it, and an immutable one arrives at this receiver with no extras at all.
     * It is safe here because the intent names this app's own component, so
     * the only thing a filled-in copy can ever reach is [onReceive] - and the
     * fill-in itself comes from this app's own RemoteViewsFactory.
     */
    private fun rowTemplate(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, TasksWidgetActions::class.java)
            .setAction(TasksWidgetActions.ACTION_ROW)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("taskmind://row/$widgetId"))
        return PendingIntent.getBroadcast(
            context,
            ("row$widgetId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
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
        val intent = Intent(context, TasksWidgetActions::class.java)
            .setAction(TasksWidgetActions.ACTION_COMPLETE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("taskmind://complete/$widgetId/$taskId"))
        return PendingIntent.getBroadcast(
            context,
            (taskId + widgetId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pageIntent(context: Context, widgetId: Int, delta: Int): PendingIntent {
        val intent = Intent(context, TasksWidgetActions::class.java)
            .setAction(TasksWidgetActions.ACTION_PAGE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(TasksWidgetActions.EXTRA_PAGE_DELTA, delta)
            .setData(Uri.parse("taskmind://page/$widgetId/$delta"))
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

        const val EXTRA_TASK_ID = "task_id"

        /**
         * This receiver is exported - a launcher cannot bind an app widget
         * otherwise - so it deliberately answers to nothing that changes data.
         * Everything the widget's buttons do lives on [TasksWidgetActions],
         * which is not exported. The worst another app can do by shouting at
         * this one is make it redraw.
         */
        private val HANDLED = setOf(
            ACTION_REFRESH,
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
        )

        /**
         * Redraws every placed widget.
         *
         * Instantiating the provider is unusual but correct: an
         * AppWidgetProvider is a BroadcastReceiver, and rendering reads only
         * the context it is handed - no receiver state, no goAsync. It gives
         * [TasksWidgetActions] the same drawing code rather than a second copy
         * of it.
         */
        internal fun redrawAll(context: Context) {
            TasksWidget().renderAll(context)
        }

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
