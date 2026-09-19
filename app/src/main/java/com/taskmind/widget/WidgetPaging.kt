package com.taskmind.widget

import android.content.Context

/**
 * Which slice of the task list each placed widget is showing.
 *
 * Plain SharedPreferences rather than DataStore: this is read on the binder
 * thread while a widget is being drawn, it holds one integer per widget, and
 * DataStore's API is suspending - which would mean blocking on it here for no
 * benefit at all.
 *
 * Offsets are clamped on read rather than on write, because the list they
 * index into changes underneath them: ticking tasks off can leave an offset
 * past the end, and the correct response is to show the last page, not an
 * empty one.
 */
object WidgetPaging {

    fun offset(context: Context, widgetId: Int, total: Int, pageSize: Int): Int {
        if (total <= pageSize) return 0
        val stored = prefs(context).getInt(key(widgetId), 0)
        val last = ((total - 1) / pageSize) * pageSize
        return stored.coerceIn(0, last)
    }

    fun move(context: Context, widgetId: Int, delta: Int) {
        val current = prefs(context).getInt(key(widgetId), 0)
        // Clamped to zero here; the upper bound is applied on read, where the
        // total is actually known.
        prefs(context).edit().putInt(key(widgetId), (current + delta).coerceAtLeast(0)).apply()
    }

    /** Back to the top - used whenever the widget is refreshed from the app. */
    fun reset(context: Context, widgetId: Int) {
        prefs(context).edit().remove(key(widgetId)).apply()
    }

    fun forget(context: Context, widgetIds: IntArray) {
        val editor = prefs(context).edit()
        for (id in widgetIds) editor.remove(key(id))
        editor.apply()
    }

    private fun key(widgetId: Int) = "offset_$widgetId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("taskmind_widget", Context.MODE_PRIVATE)
}
