package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskmind.core.Prompts
import com.taskmind.data.settings.Settings
import com.taskmind.ui.components.SectionCard

/**
 * The user's own description of their work, sent with every capture prompt.
 *
 * The model judges "is this worth a task?" for someone it otherwise knows
 * nothing about. Ten lines on role, key people, current work and what to
 * ignore change that more than any rule in the prompt.
 */
@Composable
fun AboutMeSection(settings: Settings, viewModel: SettingsViewModel) {
    var draft by remember(settings.aboutMe) { mutableStateOf(settings.aboutMe) }
    val changed = draft.trim() != settings.aboutMe.trim()

    SectionCard(
        title = "About you",
        subtitle = "In your own words. TaskMind reads this before deciding whether a message is worth a task.",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= Prompts.MAX_ABOUT_ME) draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            placeholder = { Text(EXAMPLE) },
        )
        Text(
            "${draft.length} / ${Prompts.MAX_ABOUT_ME}. Sent to your AI provider with each message it reads; " +
                "never to the web app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (changed) {
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { viewModel.setAboutMe(draft) }) { Text("Save") }
                TextButton(onClick = { draft = settings.aboutMe }) { Text("Discard") }
            }
        }
    }
}

private const val EXAMPLE =
    "Role: infra lead at Acme, Bengaluru.\n" +
        "Key people: Sharma ji (finance), Priya (client PM), my manager Anil.\n" +
        "Current work: data-centre migration, vendor PO for racks, hiring two SREs.\n" +
        "Personal: rent, kids' school, parents' medical.\n" +
        "Ignore: stock and ops alerts groups, society group, birthday wishes, FYIs."
