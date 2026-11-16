package com.taskmind.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.data.memory.MemoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search my chats (v1.32): the imported history and kept captures, by word
 * and by meaning, on the phone. Tapping a result shows the conversation
 * around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSearchScreen(
    viewModel: ChatsViewModel,
    chat: String?,
    initialQuery: String?,
    onBack: () -> Unit,
) {
    val ui by viewModel.search.collectAsStateWithLifecycle()
    LaunchedEffect(chat) {
        viewModel.startSearch(chat)
        if (!initialQuery.isNullOrBlank() && ui.query.isBlank()) viewModel.setQuery(initialQuery)
    }
    BackHandler(enabled = ui.open != null) { viewModel.closeHit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val open = ui.open
                    Text(
                        when {
                            open != null -> open.chat
                            chat != null -> "Search $chat"
                            else -> "Search my chats"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (ui.open != null) viewModel.closeHit() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val open = ui.open
            if (open != null) {
                Conversation(open, ui.context)
            } else {
                Results(ui, viewModel)
            }
        }
    }
}

@Composable
private fun Results(ui: ChatsViewModel.SearchState, viewModel: ChatsViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = ui.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text(if (ui.chat == null) "A word, a name, or a question" else "A word or a name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (ui.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            ui.memoryEmpty -> Hint(
                "Nothing to search yet. Import your WhatsApp history in Settings, You and your chats, " +
                    "or wait for captured messages to be kept.",
            )
            !ui.searched -> Hint(
                buildString {
                    append("Searches your imported chats and kept messages, on the phone. Nothing is sent anywhere.")
                    if (ui.chat == null) {
                        append(
                            if (ui.meaningReady) " Questions of a few words also find chats close in meaning."
                            else " Turn on Search by meaning in Settings to also find chats by what they are about.",
                        )
                    }
                },
            )
            ui.hits.isEmpty() && !ui.searching -> Hint("No messages match. Try fewer or different words.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(ui.hits) { hit ->
                    HitRow(hit) { viewModel.openHit(hit) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: ChatsViewModel.Hit, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (hit.isGroup) "Group: " else "") + hit.chat,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                (if (hit.byMeaning) "close in meaning - " else "") + dateOf(hit.at),
                style = MaterialTheme.typography.labelSmall,
                color = if (hit.byMeaning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        val prefix = if (!hit.byMeaning && hit.sender != null && (hit.isGroup || hit.sender == "You")) "${hit.sender}: " else ""
        Text(
            prefix + hit.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (hit.byMeaning) 4 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Conversation(hit: ChatsViewModel.Hit, messages: List<MemoryStore.Message>) {
    if (messages.isEmpty()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    // The hit itself: the first message at or after its time.
    val focus = messages.indexOfFirst { it.at >= hit.at }.coerceAtLeast(0)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        itemsIndexed(messages) { i, m -> Bubble(m, highlighted = i == focus) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun Bubble(m: MemoryStore.Message, highlighted: Boolean = false) {
    val mine = m.fromMe
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    when {
                        highlighted -> MaterialTheme.colorScheme.tertiaryContainer
                        mine -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (!mine && m.isGroup) {
                Text(m.sender, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(m.body, style = MaterialTheme.typography.bodyMedium)
            Text(
                timeOf(m.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

internal fun dateOf(at: Long): String = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(at))
internal fun timeOf(at: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(Date(at))
