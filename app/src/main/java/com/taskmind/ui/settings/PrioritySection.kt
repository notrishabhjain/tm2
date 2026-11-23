package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taskmind.data.memory.MemoryStore
import com.taskmind.data.settings.Settings
import com.taskmind.di.AppContainer
import com.taskmind.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Priority people and groups (v1.37). Typing a name offers the matching chats
 * from the WhatsApp history, so the name is spelled exactly as the chat shows
 * it - which is what the matching needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrioritySection(settings: Settings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<MemoryStore.ChatInfo>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        matches = withContext(Dispatchers.IO) {
            runCatching { container.memoryStore.chatsNamed(query, groups = true, limit = 6) }.getOrDefault(emptyList())
        }
    }

    SectionCard(
        title = "Priority people and groups",
        subtitle = "Tasks from these go straight onto your list, at High priority, and alert you at once - " +
            "no Inbox, no waiting as a maybe. Priority groups are read in full. Each morning, after WhatsApp's " +
            "nightly backup, TaskMind also checks these chats for messages no notification showed you.",
    ) {
        if (settings.priorityChats.isEmpty()) {
            Text(
                "None yet. Add the people and groups whose asks you must never miss.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.priorityChats.sortedBy { it.lowercase() }.forEach { name ->
                    AssistChip(
                        onClick = { viewModel.setPriorityChat(name, false) },
                        label = { Text(name) },
                        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove $name", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Add a person or group") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (matches.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.filter { m -> settings.priorityChats.none { it.equals(m.chat, ignoreCase = true) } }.forEach { m ->
                    SuggestionChip(
                        onClick = {
                            viewModel.setPriorityChat(m.chat, true)
                            query = ""
                        },
                        label = { Text((if (m.isGroup) "Group: " else "") + m.chat) },
                    )
                }
            }
        }
        if (query.trim().length >= 2) {
            TextButton(onClick = {
                viewModel.setPriorityChat(query.trim(), true)
                query = ""
            }) { Text("Add \"${query.trim()}\" as typed") }
        }
        Text(
            "Tip: a chat you have muted in WhatsApp shows no notification, so TaskMind only sees it the next morning. " +
                "To keep a priority chat quiet but visible, unmute it and set its notification tone to None in WhatsApp " +
                "(chat, the name at the top, Notifications).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
