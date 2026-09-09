package com.taskmind.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.taskmind.R
import com.taskmind.Routes
import com.taskmind.MainActivity
import com.taskmind.data.db.entity.TaskEntity

/** Serves the widget's list. One factory per placed widget. */
class TasksWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TasksWidgetFactory(applicationContext)
}

private class TasksWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<TaskEntity> = emptyList()

    override fun onCreate() = Unit

    /**
     * Called by the launcher before it asks for any row, on a thread it
     * expects to block. Loading here - rather than per row - means the list
     * is drawn from one consistent snapshot.
     */
    override fun onDataSetChanged() {
        rows = WidgetData.pendingTasks(context)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        // A blank row rather than a crash if the list shifted under us between
        // onDataSetChanged and this call - the launcher does not hold a lock.
        val task = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_task_item)
        val views = RemoteViews(context.packageName, R.layout.widget_task_item)
        val now = System.currentTimeMillis()
        val overdue = task.dueAt != null && task.dueAt!! < now

        views.setTextViewText(R.id.item_title, task.title)
        views.setInt(
            R.id.item_accent,
            "setBackgroundColor",
            context.getColor(if (overdue) R.color.widget_overdue else R.color.widget_accent),
        )

        val meta = buildList {
            task.dueAt?.let { add(shortDate(it, now)) }
            task.sourceLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")

        if (meta.isBlank()) {
            views.setViewVisibility(R.id.item_meta, android.view.View.GONE)
        } else {
            views.setTextViewText(R.id.item_meta, meta)
            views.setViewVisibility(R.id.item_meta, android.view.View.VISIBLE)
            views.setTextColor(
                R.id.item_meta,
                context.getColor(if (overdue) R.color.widget_overdue else R.color.widget_on_surface_variant),
            )
        }

        // Completes the template set on the list, so this row opens this task.
        views.setOnClickFillInIntent(
            R.id.item_root,
            Intent().putExtra(MainActivity.EXTRA_ROUTE, Routes.taskDetail(task.id)),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

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
}
