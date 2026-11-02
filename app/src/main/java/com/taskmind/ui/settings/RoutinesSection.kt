package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.DateResolver
import com.taskmind.core.Recurrence
import com.taskmind.core.Routines
import com.taskmind.core.SmartReminder
import com.taskmind.data.settings.Settings
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.tasks.WhenFields

/**
 * Things that come back on their own: rent, bills, renewals, refills.
 *
 * Each is a repeating task with a lead time: due on the day, reminded the
 * chosen number of days before at 09:00. Ticking it off rolls it to the next
 * date by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutinesSection(settings: Settings, viewModel: SettingsViewModel) {
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var every by remember { mutableStateOf(Routines.Every.MONTH) }
    var due by remember { mutableStateOf<Long?>(null) }
    var lead by remember { mutableStateOf(3) }
    var area by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "Routines",
        subtitle = "Bills, renewals and refills that repeat. Tick one off and the next one appears.",
    ) {
        if (routines.isEmpty()) {
            Text(
                "No routines yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        routines.forEach { task ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            Recurrence.describe(task.recurrenceRule),
                            task.dueAt?.let { "next ${DateFormats.due(it).replaceFirstChar { c -> c.lowercase() }}" },
                            task.area,
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.stopRoutine(task) }) { Text("Stop") }
            }
        }

        if (!adding) {
            TextButton(onClick = { adding = true }) { Text("Add a routine") }
            return@SectionCard
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Start from", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Routines.PRESETS.forEach { p ->
                FilterChip(
                    selected = title == p.title,
                    onClick = {
                        title = p.title
                        every = p.every
                        lead = p.leadDays
                        area = settings.areas.firstOrNull { it.equals(p.area, ignoreCase = true) }
                    },
                    label = { Text(p.title) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(80) },
            label = { Text("What") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Text("How often", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Routines.Every.entries.forEach { e ->
                FilterChip(selected = every == e, onClick = { every = e }, label = { Text(e.label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        WhenFields(
            dueAt = due,
            onDueAt = { due = it },
            reminder = SmartReminder.Choice.SMART,
            onReminder = {},
            showReminder = false,
        )
        Spacer(Modifier.height(8.dp))
        Text("Remind me", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Routines.LEAD_CHOICES.forEach { d ->
                FilterChip(selected = lead == d, onClick = { lead = d }, label = { Text(Routines.describeLead(d)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Area", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            settings.areas.forEach { a ->
                FilterChip(selected = area == a, onClick = { area = if (area == a) null else a }, label = { Text(a) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { adding = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = {
                    val at = due ?: return@Button
                    viewModel.addRoutine(title, every, at, lead, area)
                    adding = false
                    title = ""
                    due = null
                },
                enabled = title.isNotBlank() && due != null,
                modifier = Modifier.weight(1f),
            ) { Text("Save routine") }
        }
        if (due == null) {
            Text(
                "Pick the next date it is due.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The fixed parts of life tasks are filed under. */
@Composable
fun AreasSection(settings: Settings, viewModel: SettingsViewModel) {
    var draft by remember(settings.areas) { mutableStateOf(settings.areas.joinToString(", ")) }
    SectionCard(
        title = "Areas",
        subtitle = "Every task can sit in one area. TaskMind files captured tasks for you; you can change any.",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(240) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Areas, separated by commas") },
        )
        if (draft.trim() != settings.areas.joinToString(", ")) {
            Row {
                Button(onClick = { viewModel.setAreas(draft) }) { Text("Save") }
                TextButton(onClick = { draft = settings.areas.joinToString(", ") }) { Text("Discard") }
            }
        }
    }
}
