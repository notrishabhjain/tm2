package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontFamily
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
fun AboutMeSection(settings: Settings, ui: SettingsUiState, viewModel: SettingsViewModel) {
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
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = viewModel::prepareProfileDraft,
            enabled = ui.profileDraft == ProfileDraftState.Idle,
        ) { Text("Draft this from my chats") }
        Text(
            "Uses your imported WhatsApp history. You see exactly what would be sent before anything leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ProfileDraftDialog(
        state = ui.profileDraft,
        onSend = viewModel::sendProfileDraft,
        onUse = { text ->
            draft = text
            viewModel.closeProfileDraft()
        },
        onClose = viewModel::closeProfileDraft,
    )
}

/**
 * The consent step and the result, in one dialog: what goes, to whom, and
 * then the draft to edit. "Use this" only fills the box; Save is still yours.
 */
@Composable
private fun ProfileDraftDialog(
    state: ProfileDraftState,
    onSend: () -> Unit,
    onUse: (String) -> Unit,
    onClose: () -> Unit,
) {
    if (state == ProfileDraftState.Idle) return
    var showSample by remember(state) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (state != ProfileDraftState.Sending) onClose() },
        title = { Text("Draft About you") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when (state) {
                    ProfileDraftState.Preparing -> Text("Picking a sample of your own messages, on the phone...")
                    ProfileDraftState.NothingToSend -> Text(
                        "There are no messages of yours from the last six months to learn from. " +
                            "Import your WhatsApp history first (Settings, You and your chats, WhatsApp history).",
                    )
                    is ProfileDraftState.Preview -> {
                        val s = state.prepared.sample
                        Text(
                            "This sends ${s.messages} of your own messages from ${s.chats} chats " +
                                "(${s.text.length} characters, last six months) to ${state.prepared.provider}, once. " +
                                "Only what you wrote, never what others wrote to you. Phone numbers, emails and links are masked.",
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showSample = !showSample }) {
                            Text(if (showSample) "Hide what is sent" else "Show exactly what is sent")
                        }
                        if (showSample) {
                            Text(s.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    ProfileDraftState.Sending -> Text("Asking the AI for a draft...")
                    is ProfileDraftState.Ready -> {
                        Text("Here is the draft. Use it to fill the box, then edit and Save.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(state.text)
                    }
                    is ProfileDraftState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    ProfileDraftState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is ProfileDraftState.Preview -> Button(onClick = onSend) { Text("Send and draft") }
                is ProfileDraftState.Ready -> Button(onClick = { onUse(state.text) }) { Text("Use this") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state != ProfileDraftState.Sending && state != ProfileDraftState.Preparing) {
                TextButton(onClick = onClose) { Text(if (state is ProfileDraftState.Ready) "Discard" else "Cancel") }
            }
        },
    )
}

private const val EXAMPLE =
    "Role: infra lead at Acme, Bengaluru.\n" +
        "Key people: Sharma ji (finance), Priya (client PM), my manager Anil.\n" +
        "Current work: data-centre migration, vendor PO for racks, hiring two SREs.\n" +
        "Personal: rent, kids' school, parents' medical.\n" +
        "Ignore: stock and ops alerts groups, society group, birthday wishes, FYIs."
