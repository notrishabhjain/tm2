package com.taskmind.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.taskmind.MainActivity
import com.taskmind.R
import com.taskmind.Routes
import com.taskmind.data.db.entity.TaskEntity

/** Serves the scrolling list. One factory per placed widget. */
class TasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TasksWidgetFactory(applicationContext)
}

/**
 * The rows, built in this app's process and handed to the launcher.
 *
 * This existed once before and the launcher drew "Couldn't add widget." in
 * every row. The cause was in the item layout, not here: it used a bare
 * `View` for the accent bar, and `View` is not a class RemoteViews supports.
 * A directly-applied RemoteViews tolerates one; a collection item, serialised
 * across a binder and validated on the other side, does not. The bar is a
 * TextView now.
 *
 * `runBlocking` is correct in this one place: a RemoteViewsFactory is called
 * on a binder thread the launcher expects to block, and both `onDataSetChanged`
 * and `getViewAt` must have their answer before returning. There is no
 * suspending version of that contract.
 */
private class TasksWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<TaskEntity> = emptyList()

    override fun onCreate() = Unit

    /**
     * Called before any row is asked for, on a thread the launcher expects to
     * block. Loading here rather than per row means the list is drawn from one
     * consistent snapshot.
     */
    override fun onDataSetChanged() {
        rows = WidgetData.pendingTasks(context)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_task_item)
        // A blank row rather than a crash if the list shifted between
        // onDataSetChanged and this call - the launcher holds no lock.
        val task = rows.getOrNull(position) ?: return views

        val now = System.currentTimeMillis()
        val due = task.dueAt
        val overdue = due != null && due < now

        views.setTextViewText(R.id.item_title, task.title)
        views.setInt(
            R.id.item_accent,
            "setBackgroundColor",
            context.getColor(if (overdue) R.color.widget_overdue else R.color.widget_accent),
        )

        val meta = buildList {
            due?.let { add(WidgetFormat.shortDate(it, now)) }
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

        // A collection view takes exactly ONE pending-intent template, so both
        // targets complete the same one and say which they are in an extra.
        // The action cannot carry that: Intent.fillIn leaves the template's
        // own action alone unless FILL_IN_ACTION is set, which is a subtlety
        // worth not depending on.
        views.setOnClickFillInIntent(
            R.id.item_root,
            Intent()
                .putExtra(TasksWidgetActions.EXTRA_ROW_OP, TasksWidgetActions.OP_OPEN)
                .putExtra(MainActivity.EXTRA_ROUTE, Routes.taskDetail(task.id)),
        )
        views.setOnClickFillInIntent(
            R.id.item_done,
            Intent()
                .putExtra(TasksWidgetActions.EXTRA_ROW_OP, TasksWidgetActions.OP_DONE)
                .putExtra(TasksWidget.EXTRA_TASK_ID, task.id),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
