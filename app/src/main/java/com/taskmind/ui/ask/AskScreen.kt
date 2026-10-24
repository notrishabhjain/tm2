package com.taskmind.ui.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.design.Radius
import com.taskmind.ui.design.Space

/**
 * Ask the task list a question, in your own words.
 *
 * Reached from search: a query that reads like a question gets an "Ask
 * TaskMind" button, so this is not one more icon on the task screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AskScreen(
    viewModel: AskViewModel,
    initialQuestion: String?,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A question carried over from search is asked straight away - once.
    // Coming back here from a task must not ask (and pay) again.
    LaunchedEffect(initialQuestion) {
        if (!initialQuestion.isNullOrBlank() && viewModel.state.value.question.isEmpty()) {
            viewModel.setQuestion(initialQuestion)
            viewModel.ask()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask TaskMind") },
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.edge)
                .padding(bottom = Space.section),
        ) {
            OutlinedTextField(
                value = state.question,
                onValueChange = viewModel::setQuestion,
                placeholder = { Text("What do I owe Rahul?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(Space.snug))
            if (state.answer == null && !state.busy) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
                    listOf(
                        "What is due this week?",
                        "Who am I waiting on?",
                        "What did the client ask for?",
                    ).forEach { example ->
                        SuggestionChip(
                            onClick = {
                                viewModel.setQuestion(example)
                                viewModel.ask()
                            },
                            label = { Text(example) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.snug))
            }
            Button(onClick = viewModel::ask, enabled = state.question.isNotBlank() && !state.busy) {
                Text("Ask")
            }

            if (state.busy) {
                Spacer(Modifier.height(Space.step))
                CircularProgressIndicator()
            }

            state.error?.let { error ->
                Spacer(Modifier.height(Space.step))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            state.answer?.let { answer ->
                Spacer(Modifier.height(Space.step))
                Surface(
                    shape = RoundedCornerShape(Radius.card),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(answer, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Space.edge))
                }
                state.tasks.forEach { task ->
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

            Spacer(Modifier.height(Space.section))
            Text(
                "Your question goes to your AI provider with each task's title, date, who it came from " +
                    "and its short quote - never the full messages or call transcripts. One question is " +
                    "one model call against your daily limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
