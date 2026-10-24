package com.taskmind.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskmind.core.DateResolver
import com.taskmind.core.SmartReminder
import com.taskmind.ui.components.DateFormats
import java.time.Instant
import java.time.ZoneOffset

/**
 * Due day, due time and reminder, as one control.
 *
 * Used by the new-task sheet and by a task's own screen, so the two cannot
 * drift. Before this the form could set a day but not a time ("call at 4"
 * could not be written down), offered exactly one reminder ("1 hour before"),
 * and a task's screen could not change its date at all.
 *
 * A task with a day and no time is stored at the 18:00 default, which is how
 * the rest of the app already represents a bare date - so "Any time" here and
 * a date the AI found with no time mean the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhenFields(
    dueAt: Long?,
    onDueAt: (Long?) -> Unit,
    reminder: SmartReminder.Choice,
    onReminder: (SmartReminder.Choice) -> Unit,
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()

    Column {
        Text("Due", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val today = DateResolver.startOfDay(now)
            val tomorrow = DateResolver.nextMidnight(now)
            FilterChip(selected = dueAt == null, onClick = { onDueAt(null) }, label = { Text("No date") })
            FilterChip(
                selected = dueAt != null && DateResolver.startOfDay(dueAt) == today,
                onClick = { onDueAt(onDay(today, dueAt)) },
                label = { Text("Today") },
            )
            FilterChip(
                selected = dueAt != null && DateResolver.startOfDay(dueAt) == tomorrow,
                onClick = { onDueAt(onDay(tomorrow, dueAt)) },
                label = { Text("Tomorrow") },
            )
            FilterChip(selected = false, onClick = { showDate = true }, label = { Text("Pick a date") })
        }

        if (dueAt != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val untimed = SmartReminder.isDateOnly(dueAt)
                FilterChip(
                    selected = untimed,
                    onClick = { onDueAt(SmartReminder.withoutTime(dueAt)) },
                    label = { Text("Any time") },
                )
                FilterChip(
                    selected = !untimed,
                    onClick = { showTime = true },
                    label = { Text(if (untimed) "Set a time" else DateFormats.dueLong(dueAt).substringAfter(", ")) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Due ${DateFormats.dueLong(dueAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("Remind me", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmartReminder.Choice.entries.forEach { choice ->
                    FilterChip(
                        selected = reminder == choice,
                        onClick = { onReminder(choice) },
                        label = { Text(choice.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val at = SmartReminder.resolve(reminder, dueAt, now)
            Text(
                when {
                    reminder == SmartReminder.Choice.NONE -> "No reminder."
                    at == null -> "That time has already passed, so there will be no reminder."
                    else -> "Reminds you ${DateFormats.due(at, now).replaceFirstChar { it.lowercase() }}" +
                        if (SmartReminder.isDateOnly(at)) " at 18:00" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueAt ?: now)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { selected ->
                        // The picker answers in UTC midnight; read the calendar
                        // day it means and place it in IST, keeping any time the
                        // task already had.
                        val day = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(DateResolver.IST).toInstant().toEpochMilli()
                        onDueAt(onDay(day, dueAt))
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTime && dueAt != null) {
        val local = Instant.ofEpochMilli(dueAt).atZone(DateResolver.IST)
        val untimed = SmartReminder.isDateOnly(dueAt)
        val timeState = rememberTimePickerState(
            initialHour = if (untimed) 10 else local.hour,
            initialMinute = if (untimed) 0 else local.minute,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onDueAt(SmartReminder.timed(dueAt, timeState.hour, timeState.minute))
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = timeState) },
        )
    }
}

/**
 * [dayStart] (midnight IST) with the time of day [current] already had, or
 * with no time if it had none.
 */
private fun onDay(dayStart: Long, current: Long?): Long {
    if (current == null || SmartReminder.isDateOnly(current)) return SmartReminder.withoutTime(dayStart)
    val local = Instant.ofEpochMilli(current).atZone(DateResolver.IST)
    return SmartReminder.timed(dayStart, local.hour, local.minute)
}
