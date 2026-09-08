package com.taskmind.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.taskmind.MainActivity
import com.taskmind.R
import com.taskmind.Routes

/**
 * The home-screen widget: what is pending, without opening anything.
 *
 * Built on RemoteViews rather than Glance deliberately. Glance is less code,
 * but it pulls in a new dependency whose Compose-compiler compatibility can
 * only be discovered in CI, and a widget that fails to inflate shows an empty
 * grey box with no error anywhere. RemoteViews has been stable since API 3.
 *
 * It reads through the same DAO flows the app uses and writes nothing.
 */
class TasksWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidget::class.java))
            // Tell the list to re-query, then redraw the frame. Both are
            // needed: the frame holds the counts, the list holds the rows.
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            for (id in ids) render(context, manager, id)
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)

        val counts = WidgetData.counts(context)
        views.setTextViewText(R.id.widget_summary, counts.summary)

        if (counts.awaitingApproval > 0) {
            views.setTextViewText(R.id.widget_review_badge, "${counts.awaitingApproval} to approve")
            views.setViewVisibility(R.id.widget_review_badge, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_review_badge, android.view.View.GONE)
        }

        views.setViewVisibility(
            R.id.widget_empty,
            if (counts.pending == 0) android.view.View.VISIBLE else android.view.View.GONE,
        )

        // The list is served by RemoteViewsService; the adapter intent must be
        // unique per widget or the launcher reuses one factory for all of them.
        val serviceIntent = Intent(context, TasksWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        // The two-argument overload is API 31+; this app supports 29, so the
        // older one stays. It is deprecated, not removed.
        @Suppress("DEPRECATION")
        views.setRemoteAdapter(widgetId, R.id.widget_list, serviceIntent)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        // Tapping anywhere opens the app. Tapping a row opens that task, via
        // the template plus the per-row fill-in intent set by the factory.
        views.setOnClickPendingIntent(R.id.widget_title, openApp(context, Routes.TASKS))
        views.setOnClickPendingIntent(R.id.widget_review_badge, openApp(context, Routes.REVIEW))
        views.setPendingIntentTemplate(R.id.widget_list, openAppTemplate(context))

        manager.updateAppWidget(widgetId, views)
    }

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

    /**
     * MUTABLE on purpose, and the one place in the app that is.
     *
     * A pending-intent template is completed by the per-row fill-in intent the
     * list factory supplies; an immutable template cannot be filled in, so
     * every row would open the same task. The template targets only this app's
     * own activity, so nothing outside can redirect it.
     */
    private fun openAppTemplate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.taskmind.action.WIDGET_REFRESH"

        /** Nudges every placed widget to re-read. Safe to call when none exist. */
        fun refresh(context: Context) {
            val intent = Intent(context, TasksWidget::class.java).setAction(ACTION_REFRESH)
            runCatching { context.sendBroadcast(intent) }
        }
    }
}
