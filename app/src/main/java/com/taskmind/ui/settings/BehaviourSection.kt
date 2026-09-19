package com.taskmind.ui.settings

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.prefs.UiPreferences
import com.taskmind.reminders.OngoingReminder
import com.taskmind.ui.components.LabeledSwitch
import com.taskmind.ui.components.SectionCard
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
        title = "Standing reminder",
        subtitle = "A notification that stays in your shade listing what is overdue or due today.",
    ) {
        LabeledSwitch(
            label = "Keep today's tasks in the notification shade",
            checked = state.ongoingReminder,
            onCheckedChange = { on ->
                scope.launch {
                    prefs.setOngoingReminder(on)
                    if (on) Scheduler.enqueueOngoingReminder(context) else OngoingReminder.cancel(context)
                }
            },
            description = "Silent, and refreshed every hour and whenever you leave the app.",
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "It disappears on its own when nothing is due. On Android 14 and later the system " +
                "allows any notification to be swiped away whatever the app asks - if you dismiss " +
                "it, it returns at the next refresh rather than staying gone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
