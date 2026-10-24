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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskmind.core.Priority
import com.taskmind.core.QuickAdd
import com.taskmind.core.Recurrence
import com.taskmind.core.SmartReminder
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.theme.PriorityStyle

/**
 * Manual task entry (spec 16). Everything created here goes through the intake
 * funnel, exactly like a captured commitment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        notes: String?,
        dueAt: Long?,
        priority: Priority,
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
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var reminder by remember { mutableStateOf(SmartReminder.Choice.SMART) }
    var recurrence by remember { mutableStateOf<String?>(null) }
    // Dates typed into the title ("call Rahul tomorrow 5pm") are read as the
    // due date unless a date has been picked by hand or the reading refused.
    var ignoreTyped by remember { mutableStateOf(false) }
    val typed = remember(title) { QuickAdd.parse(title, System.currentTimeMillis()) }
    val useTyped = !ignoreTyped && dueAt == null && typed.dueAt != null

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
                placeholder = { Text("e.g. Call Rahul tomorrow 5pm") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            if (useTyped) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Due ${DateFormats.dueLong(typed.dueAt!!)} - read from \"${typed.matched}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ignoreTyped = true }) { Text("Keep as text") }
                }
            }
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

            WhenFields(
                dueAt = dueAt,
                onDueAt = { dueAt = it },
                reminder = reminder,
                onReminder = { reminder = it },
            )
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
                        val finalTitle = if (useTyped) typed.title.ifBlank { title.trim() } else title.trim()
                        val finalDue = if (useTyped) typed.dueAt else dueAt
                        onSave(
                            finalTitle,
                            notes.trim().ifBlank { null },
                            finalDue,
                            priority,
                            tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            SmartReminder.resolve(reminder, finalDue, System.currentTimeMillis()),
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
}
