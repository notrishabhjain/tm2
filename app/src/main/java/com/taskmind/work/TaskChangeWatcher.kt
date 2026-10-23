package com.taskmind.work

import android.content.Context
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.widget.TasksWidget
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything that has to follow the task list, refreshed when the list changes.
 *
 * Before this, the widget, the web mirror and the standing notification were
 * refreshed when you LEFT the app - so a task captured from WhatsApp while the
 * app was closed did not reach the widget or the laptop until you next opened
 * TaskMind and closed it again, and a task ticked off in the browser stayed on
 * the home screen. One observer of the table fixes all three, wherever the
 * change came from: a capture, a notification button, the widget, the web.
 *
 * Debounced, because a burst (a bundle completed, a pull from the web applying
 * ten edits) should cost one refresh, not ten.
 */
object TaskChangeWatcher {

    private const val SETTLE_MILLIS = 2_000L

    @OptIn(FlowPreview::class)
    fun start(context: Context, container: AppContainer) {
        val app = context.applicationContext
        container.applicationScope.launch {
            container.taskRepository.observeTasks()
                // A fingerprint of what the dependants draw from. updatedAt
                // moves on every write, so this changes whenever anything does.
                .map { tasks -> tasks.sumOf { it.updatedAt xor it.id.hashCode().toLong() } to tasks.size }
                .distinctUntilChanged()
                // The first value is the list as it already stands at start-up,
                // which is not a change.
                .drop(1)
                .debounce(SETTLE_MILLIS)
                .collect {
                    try {
                        TasksWidget.redrawAll(app)
                        Scheduler.enqueueOngoingReminder(app)
                        // A no-op when web access is off.
                        Scheduler.enqueueSync(app)
                        Scheduler.scheduleNextReminder(app, container)
                    } catch (t: Throwable) {
                        container.logger.write(Stage.SYSTEM, LogLevel.WARN, "refresh after a task change failed", t.toString())
                    }
                }
        }
    }
}
