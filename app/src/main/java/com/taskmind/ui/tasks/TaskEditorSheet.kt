package com.taskmind.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskmind.core.DateResolver
import com.taskmind.core.Priority
import com.taskmind.core.Recurrence
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.theme.PriorityStyle
import java.time.Instant

/**
 * Manual task entry (spec 16). Everything created here goes through the intake
 * funnel, exactly like a captured commitment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    projects: List<ProjectEntity>,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        notes: String?,
        dueAt: Long?,
        priority: Priority,
        projectId: String?,
        tags: List<String>,
        reminderAt: Long?,
        recurrenceRule: String?,
    ) -> Unit,
    initialTitle: String = "",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf(initialTitle) }
    var notes by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var reminder by remember { mutableStateOf<Long?>(null) }
    var recurrence by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("New task", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What needs doing?") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(16.dp))

            Text("Priority", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { option ->
                    FilterChip(
                        selected = priority == option,
                        onClick = { priority = option },
                        label = { Text(PriorityStyle.label(option)) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Due", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = dueAt == null,
                    onClick = { dueAt = null },
                    label = { Text("No date") },
                )
                FilterChip(
                    selected = dueAt != null && DateFormats.due(dueAt).startsWith("Today"),
                    onClick = { dueAt = defaultTimeOn(0) },
                    label = { Text("Today") },
                )
                FilterChip(
                    selected = dueAt != null && DateFormats.due(dueAt).startsWith("Tomorrow"),
                    onClick = { dueAt = defaultTimeOn(1) },
                    label = { Text("Tomorrow") },
                )
                FilterChip(
                    selected = false,
                    onClick = { showDatePicker = true },
                    label = { Text("Pick a date") },
                )
            }
            if (dueAt != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Due ${DateFormats.full(dueAt!!)} IST",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            Text("Repeats", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(null, "DAILY", "WEEKLY", "MONTHLY", "CUSTOM:14d").forEach { rule ->
                    FilterChip(
                        selected = recurrence == rule,
                        onClick = { recurrence = rule },
                        label = { Text(rule?.let { Recurrence.describe(it) ?: it } ?: "Never") },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Remind me", style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = reminder == null, onClick = { reminder = null }, label = { Text("No reminder") })
                FilterChip(
                    selected = reminder != null,
                    onClick = { reminder = (dueAt ?: defaultTimeOn(0)) - 60 * 60 * 1000 },
                    label = { Text("1 hour before") },
                )
            }
            Spacer(Modifier.height(16.dp))

            if (projects.isNotEmpty()) {
                Text("Project", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = projectId == null, onClick = { projectId = null }, label = { Text("None") })
                    projects.forEach { project ->
                        FilterChip(
                            selected = projectId == project.id,
                            onClick = { projectId = project.id },
                            label = { Text(project.name) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags, comma separated") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        onSave(
                            title.trim(),
                            notes.trim().ifBlank { null },
                            dueAt,
                            priority,
                            projectId,
                            tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            reminder,
                            recurrence,
                        )
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Add task")
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueAt ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { selected ->
                        // The picker returns UTC midnight; a deadline with no
                        // time means 18:00 IST (spec 14.1), so convert rather
                        // than storing midnight UTC and drifting a day.
                        val date = Instant.ofEpochMilli(selected).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        dueAt = date.atTime(DateResolver.DEFAULT_HOUR, 0)
                            .atZone(DateResolver.IST).toInstant().toEpochMilli()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Today or N days out, at the default 18:00 IST. */
private fun defaultTimeOn(daysFromNow: Long): Long =
    Instant.ofEpochMilli(System.currentTimeMillis())
        .atZone(DateResolver.IST)
        .toLocalDate()
        .plusDays(daysFromNow)
        .atTime(DateResolver.DEFAULT_HOUR, 0)
        .atZone(DateResolver.IST)
        .toInstant()
        .toEpochMilli()
