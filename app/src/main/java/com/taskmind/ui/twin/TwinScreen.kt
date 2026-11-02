package com.taskmind.ui.twin

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.Journal
import com.taskmind.core.Nudge
import com.taskmind.data.repo.TwinJournal
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.design.Radius
import com.taskmind.ui.design.Space
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The twin: one place to ask, to plan, to chase, and to take back anything
 * it did. Nothing here changes the list until the user taps Apply.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TwinScreen(
    viewModel: TwinViewModel,
    initialQuestion: String?,
    /** "plan", "week" or "nudge:<task id>", from a notification. */
    startWith: String?,
    onStartHandled: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(initialQuestion) {
        if (!initialQuestion.isNullOrBlank() && viewModel.state.value.asked == null) {
            viewModel.setInput(initialQuestion)
            viewModel.ask(initialQuestion)
        }
    }
    LaunchedEffect(startWith) {
        val start = startWith ?: return@LaunchedEffect
        when {
            start == START_PLAN -> viewModel.planDay()
            start == START_WEEK -> viewModel.planWeek()
            start.startsWith(START_NUDGE) -> viewModel.openNudge(start.removePrefix(START_NUDGE))
        }
        onStartHandled()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val entry = state.undoEntry
        val result = snackbar.showSnackbar(
            message,
            actionLabel = if (entry != null) "Undo" else null,
            duration = if (entry != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && entry != null) viewModel.undo(entry) else viewModel.clearMessage()
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(viewModel::appendSpoken)
        }
    }
    val dictation = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your twin")
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Twin") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.edge)
                .padding(bottom = Space.section),
        ) {
            state.overview?.let { o ->
                val facts = listOfNotNull(
                    o.meetings.takeIf { it > 0 }?.let { if (it == 1) "1 meeting today" else "$it meetings today" },
                    (o.today + o.todayDone).takeIf { it > 0 }?.let { "${o.todayDone} of $it done on Today" },
                    o.inbox.takeIf { it > 0 }?.let { "$it in the Inbox" },
                    o.owed.takeIf { it > 0 }?.let { if (it == 1) "1 thing owed to you" else "$it things owed to you" },
                )
                Text(
                    if (facts.isEmpty()) "A clear day so far." else facts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.snug))
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
                AssistChip(onClick = viewModel::planDay, label = { Text("Plan my day") }, enabled = !state.busy)
                AssistChip(onClick = viewModel::planWeek, label = { Text("Plan my week") }, enabled = !state.busy)
                listOf("Who owes me what?", "What slipped this week?", "What is due this week?").forEach { q ->
                    AssistChip(onClick = { viewModel.ask(q) }, label = { Text(q) }, enabled = !state.busy)
                }
            }
            Spacer(Modifier.height(Space.snug))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::setInput,
                    placeholder = { Text("Ask or tell me anything - \"move the bank visit to Friday\"") },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                )
                IconButton(onClick = { runCatching { dictate.launch(dictation) } }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Speak")
                }
                IconButton(onClick = { viewModel.ask() }, enabled = state.input.isNotBlank() && !state.busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                }
            }

            if (state.busy || state.drafting) {
                Spacer(Modifier.height(Space.snug))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.error?.let {
                Spacer(Modifier.height(Space.snug))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.answer != null) {
                Spacer(Modifier.height(Space.step))
                AnswerCard(state, onOpenTask, onClear = viewModel::clearAnswer)
            }

            if (state.proposals.isNotEmpty()) {
                Spacer(Modifier.height(Space.step))
                Card("Proposed changes") {
                    state.proposals.forEachIndexed { i, p ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { viewModel.toggle(i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = p.checked, onCheckedChange = { viewModel.toggle(i) })
                            Text(p.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val count = state.proposals.count { it.checked }
                    Spacer(Modifier.height(Space.snug))
                    Button(onClick = viewModel::applyChosen, enabled = count > 0) {
                        Text(if (count == 1) "Apply 1 change" else "Apply $count changes")
                    }
                    Text(
                        "Every change can be undone from the history below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val chase = state.overview?.chase.orEmpty()
            if (chase.isNotEmpty()) {
                Spacer(Modifier.height(Space.step))
                Card("Waiting on people") {
                    val now = System.currentTimeMillis()
                    chase.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = Space.tight), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    c.person + "  ·  " + if (c.overdue(now)) "was due ${DateFormats.due(c.dueAt)}" else "promised ${Nudge.ago(c.since, now)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { viewModel.openNudge(c.taskId) }, enabled = !state.drafting) { Text("Nudge") }
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                Spacer(Modifier.height(Space.step))
                Card("What TaskMind changed") {
                    history.forEach { entry -> HistoryRow(entry, onUndo = { viewModel.undo(entry.id) }) }
                }
            }

            Spacer(Modifier.height(Space.section))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Search my messages when I ask", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "A few short snippets of your own messages and mail, found on the phone, go with a question " +
                            "that needs them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.recall, onCheckedChange = viewModel::setRecall)
            }
            Spacer(Modifier.height(Space.snug))
            Text(
                "Questions and plans go to your AI provider with your tasks (title, date, who, the short quote), " +
                    "today's calendar and your matters. Nothing goes to the web page. One question is one model " +
                    "call against your daily limit; Plan my day works without one when it has to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.draft?.let { draft ->
        NudgeSheet(
            draft = draft,
            onTextChange = viewModel::setDraftText,
            onSent = viewModel::nudged,
            onDismiss = viewModel::closeDraft,
        )
    }
}

@Composable
private fun AnswerCard(state: TwinUiState, onOpenTask: (String) -> Unit, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.edge)) {
            state.asked?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.tight))
            }
            Text(state.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            state.note?.let {
                Spacer(Modifier.height(Space.tight))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.answerTasks.isNotEmpty()) {
                Spacer(Modifier.height(Space.snug))
                HorizontalDivider()
                state.answerTasks.forEach { task ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTask(task.id) }
                            .padding(vertical = Space.snug),
                    ) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(task.dueAt?.let { DateFormats.due(it) }, task.sourceLabel).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.edge)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Space.tight))
            content()
        }
    }
}

@Composable
private fun HistoryRow(entry: Journal.Entry, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.tight), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                Instant.ofEpochMilli(entry.at).atZone(com.taskmind.core.DateResolver.IST).format(WHEN) + "  ·  " + when (entry.source) {
                    TwinJournal.SOURCE_TWIN -> "you, with the twin"
                    TwinJournal.SOURCE_CAPTURE -> "from a message"
                    TwinJournal.SOURCE_EVENING -> "evening wrap-up"
                    else -> entry.source
                } + if (entry.undone) "  ·  undone" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!entry.undone) TextButton(onClick = onUndo) { Text("Undo") }
    }
}

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

const val START_PLAN = "plan"
const val START_WEEK = "week"
const val START_NUDGE = "nudge:"
