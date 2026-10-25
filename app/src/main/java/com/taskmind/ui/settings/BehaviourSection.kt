package com.taskmind.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.prefs.UiPreferences
import com.taskmind.reminders.CalendarToday
import com.taskmind.reminders.DailyBrief
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
import com.taskmind.widget.TasksWidget
import com.taskmind.widget.WidgetPaging
import com.taskmind.work.Scheduler
import kotlinx.coroutines.launch

/**
 * Gestures and the standing reminder.
 *
 * Self-contained, like the web-access page: it owns its store and does not go
 * through the settings view model, because none of this is capture
 * configuration and threading it through there would tangle the two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviourSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { UiPreferences(context) }
    val state by prefs.state.collectAsStateWithLifecycle(initialValue = UiPreferences.State())
    // Asked for only when the switch is turned on, and the switch only turns
    // on if it is granted.
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { prefs.setCalendarInBrief(true) }
    }

    SectionCard(
        title = "Swipe on a task",
        subtitle = "What happens when you swipe a row left or right.",
    ) {
        Text("Swipe right", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ActionChips(state.swipeRight) { scope.launch { prefs.setSwipeRight(it) } }

        Spacer(Modifier.height(14.dp))
        Text("Swipe left", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ActionChips(state.swipeLeft) { scope.launch { prefs.setSwipeLeft(it) } }

        Spacer(Modifier.height(12.dp))
        Text(
            "Every one of these can be undone from the bar that appears afterwards, " +
                "including delete.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard(
        title = "Daily brief",
        subtitle = "TaskMind speaks first, twice a day - and stays quiet when there is nothing to say. " +
            "This replaces the standing \"due today\" notification.",
    ) {
        LabeledSwitch(
            label = "Morning brief at 9:00",
            checked = state.morningBrief,
            onCheckedChange = { on ->
                scope.launch {
                    prefs.setMorningBrief(on)
                    DailyBrief.schedule(context)
                }
            },
            description = "What is due today, what is already late, and who you are waiting on.",
        )
        LabeledSwitch(
            label = "Evening wrap-up and digest at 18:30",
            checked = state.eveningBrief,
            onCheckedChange = { on ->
                scope.launch {
                    prefs.setEveningBrief(on)
                    DailyBrief.schedule(context)
                }
            },
            description = "What is still open today, with one button to move all of it to tomorrow, " +
                "and how many maybe-tasks are waiting for a keep or drop. Maybes no longer notify one by one.",
        )
        LabeledSwitch(
            label = "Weekly review on Sunday at 18:00",
            checked = state.weeklyReview,
            onCheckedChange = { on ->
                scope.launch {
                    prefs.setWeeklyReview(on)
                    DailyBrief.schedule(context)
                }
            },
            description = "What got done, what has been open longest, and who asks the most.",
        )
        LabeledSwitch(
            label = "Today's meetings in the morning brief",
            checked = state.calendarInBrief && CalendarToday.granted(context),
            onCheckedChange = { on ->
                if (on && !CalendarToday.granted(context)) {
                    calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                } else {
                    scope.launch { prefs.setCalendarInBrief(on) }
                }
            },
            description = "Reads your calendar on the phone for that one notification. Nothing is stored or sent.",
        )
    }

    WidgetSection()

    AppearanceSection()
}

/**
 * The widget's row mechanism.
 *
 * This is here rather than hidden because the scrolling list is served across
 * a binder to the launcher, and that path has failed on a real device before.
 * If it ever fails again there is no way to build a debug APK to fix it from,
 * so the way back has to be a switch the user can reach.
 */
@Composable
private fun WidgetSection() {
    val context = LocalContext.current
    var scrolling by remember(context) { mutableStateOf(WidgetPaging.scrolling(context)) }

    SectionCard(
        title = "Home screen widget",
        subtitle = "How the widget shows more tasks than fit on it.",
    ) {
        LabeledSwitch(
            label = "Scroll the widget",
            checked = scrolling,
            onCheckedChange = { on ->
                scrolling = on
                WidgetPaging.setScrolling(context, on)
                TasksWidget.refresh(context)
            },
            description = "Off, the widget shows eight tasks at a time with arrows to page " +
                "through them. Turn it off if the widget ever stops loading.",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionChips(
    selected: UiPreferences.SwipeAction,
    onSelect: (UiPreferences.SwipeAction) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UiPreferences.SwipeAction.entries.forEach { action ->
            FilterChip(
                selected = selected == action,
                onClick = { onSelect(action) },
                label = { Text(action.label) },
            )
        }
    }
}
