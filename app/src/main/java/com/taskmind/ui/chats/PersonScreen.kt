package com.taskmind.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.data.memory.MemoryStore
import com.taskmind.ui.components.DateFormats

/**
 * One person (v1.32): what is open between you, and your chat with them from
 * the memory. Opened from the People view. Read on the phone; nothing is sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    viewModel: ChatsViewModel,
    name: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onSearchChat: (String) -> Unit,
    onAskTwin: (String) -> Unit,
) {
    val ui by viewModel.person.collectAsStateWithLifecycle()
    LaunchedEffect(name) { viewModel.loadPerson(name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { onAskTwin("What is going on with $name? What do I owe them and what do they owe me?") }) {
                        Text("Ask the Twin")
                    }
                    ui.chat?.let { c ->
                        OutlinedButton(onClick = { onSearchChat(c.chat) }) { Text("Search this chat") }
                    }
                }
            }

            section("You owe $name", ui.youOwe.size)
            if (ui.youOwe.isEmpty()) item { Quiet("Nothing open.") }
            items(ui.youOwe, key = { "o-" + it.id }) { TaskLine(it, onOpenTask) }

            section("$name owes you", ui.theyOwe.size)
            if (ui.theyOwe.isEmpty()) item { Quiet("Nothing you are waiting on.") }
            items(ui.theyOwe, key = { "w-" + it.id }) { TaskLine(it, onOpenTask) }

            section("Your chat", null)
            when {
                ui.loading -> item { Quiet("Looking in your chats...") }
                ui.chats.isEmpty() -> item {
                    Quiet(
                        "No chat named like \"$name\" in your memory. Import your WhatsApp history, " +
                            "or the name in the chat may be spelled differently.",
                    )
                }
                else -> {
                    if (ui.chats.size > 1) item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ui.chats.forEach { c ->
                                FilterChip(selected = c == ui.chat, onClick = { viewModel.pickChat(c) }, label = { Text(c.chat) })
                            }
                        }
                    }
                    ui.chat?.let { c -> item { ChatFacts(c) } }
                    items(ui.recent.reversed()) { m ->
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) { Bubble(m) }
                    }
                    ui.chat?.let { c ->
                        if (ui.recent.isNotEmpty()) item {
                            TextButton(onClick = { onSearchChat(c.chat) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Search the whole chat")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, count: Int?) {
    item(key = "h-$title") {
        Text(
            (title + (count?.let { " ($it)" } ?: "")).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun TaskLine(task: TaskEntity, onOpenTask: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onOpenTask(task.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        task.dueAt?.let {
            Text(DateFormats.due(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatFacts(c: MemoryStore.ChatInfo) {
    val years = ((c.last - c.first) / (365.25 * 24 * 60 * 60 * 1000)).toInt()
    Text(
        "${c.messages} messages (${c.mine} from you), since ${dateOf(c.first)}" +
            (if (years >= 1) " - $years year${if (years == 1) "" else "s"}" else "") +
            ". Last on ${dateOf(c.last)}. The newest are below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
