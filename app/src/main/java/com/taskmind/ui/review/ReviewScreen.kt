package com.taskmind.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.EmptyState
import com.taskmind.ui.components.StatusPill

/**
 * Spec 14.2 / 16 - the review inbox.
 *
 * Everything here scored between the two thresholds: real enough to keep, not
 * certain enough to assert. Principle 2 says when uncertain, ask rather than
 * assert, and this screen is the asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(viewModel: ReviewViewModel, onBack: () -> Unit) {
    val pending by viewModel.items.collectAsStateWithLifecycle()
    val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showingDismissed by remember { mutableStateOf(false) }
    val items = if (showingDismissed) dismissed else pending

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Review inbox") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !showingDismissed,
                    onClick = { showingDismissed = false },
                    label = { Text(if (pending.isEmpty()) "Waiting" else "Waiting (${pending.size})") },
                )
                FilterChip(
                    selected = showingDismissed,
                    onClick = { showingDismissed = true },
                    label = { Text("Dismissed") },
                )
            }

            if (items.isEmpty()) {
                EmptyState(
                    title = if (showingDismissed) "Nothing dismissed" else "Nothing to review",
                    body = if (showingDismissed) {
                        "Candidates you said no to appear here, and so do ones TaskMind dismissed " +
                            "for you after nobody answered them for a week. Either can be put back."
                    } else {
                        "When TaskMind is fairly sure it found a commitment but not sure enough to " +
                            "add it outright, it waits for you here rather than guessing."
                    },
                    icon = Icons.Outlined.Inbox,
                )
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    ReviewCard(
                        item = item,
                        showingDismissed = showingDismissed,
                        onAccept = { viewModel.accept(item) },
                        onDismiss = { viewModel.dismiss(item) },
                        onRestore = { viewModel.restore(item) },
                    )
                }
            }
        }
    }
}

/**
 * One candidate, with everything that justifies it.
 *
 * The source text and the model's reasoning are both here on purpose: this
 * screen is where the app admits it is not sure, and a decision asked for
 * without showing the working is not a decision the user can actually make.
 */
@Composable
private fun ReviewCard(
    item: ReviewItemEntity,
    showingDismissed: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                item.confidence?.let {
                    StatusPill(text = "%.0f%% sure".format(it * 100), ok = it >= 0.6)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${item.sourceLabel ?: item.sourceType.name} - ${DateFormats.full(item.occurredAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.dueAt?.let {
                Text(
                    "Due ${DateFormats.due(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item.evidence?.let { evidence ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "\u201C$evidence\u201D",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
            }

            if (item.sourceText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("Source", style = MaterialTheme.typography.labelMedium)
                Text(
                    item.sourceText.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item.reasoning?.let { reasoning ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "Why: $reasoning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Text("Add task")
                }
                OutlinedButton(
                    onClick = if (showingDismissed) onRestore else onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (showingDismissed) "Put back" else "Dismiss")
                }
            }
        }
    }
}
