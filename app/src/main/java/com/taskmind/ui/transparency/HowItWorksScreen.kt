package com.taskmind.ui.transparency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ui.components.KeyValueRow
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.components.StatusPill

/**
 * Settings -> How TaskMind decides.
 *
 * An app that reads your messages and creates tasks from them is asking for a
 * lot of trust. This screen is the argument for it: every rule the app applies,
 * in plain language, with the value it is currently using — read from live
 * settings, so it cannot go stale the way a written explanation does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksScreen(
    viewModel: HowItWorksViewModel,
    onBack: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenModelCalls: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How TaskMind decides") },
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
                .padding(bottom = 40.dp),
        ) {
            SectionCard(title = "Nothing here is hidden") {
                Text(
                    "TaskMind reads your messages and decides what becomes a task. That is a lot to take " +
                        "on trust, so this screen lists every rule it applies and the value it is using " +
                        "right now.\n\n" +
                        "The numbers below are read from your live settings, not written into this text. " +
                        "If you change a threshold, this page changes with it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onOpenPrompts) { Text("Read the prompts") }
                    OutlinedButton(onClick = onOpenModelCalls) { Text("See the calls") }
                }
            }

            SectionCard(title = "Right now") {
                KeyValueRow("Version", state.version)
                KeyValueRow("Extraction model", state.llmModel)
                KeyValueRow("Transcription model", state.asrModel)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(if (state.hasLlmKey) "Extraction key set" else "No extraction key", state.hasLlmKey)
                    StatusPill(if (state.hasAsrKey) "Transcription key set" else "No transcription key", state.hasAsrKey)
                }
                Spacer(Modifier.height(12.dp))
                KeyValueRow("Tasks stored", state.storedTasks.toString())
                KeyValueRow("Captures stored", state.storedCaptures.toString())
                KeyValueRow("Model calls kept", state.storedModelCalls.toString())
            }

            state.stages.forEach { stage ->
                SectionCard(title = stage.name, subtitle = stage.summary) {
                    stage.rules.forEachIndexed { index, rule ->
                        if (index > 0) Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                rule.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            rule.value?.let { value ->
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (rule.explanation.isNotBlank()) {
                            Text(
                                rule.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionCard(
                title = "When something does not work",
                subtitle = "In the order that usually finds it fastest.",
            ) {
                Text(
                    "1. Model calls — did the request go out, and what came back? A failure here almost " +
                        "always explains itself.\n\n" +
                        "2. Activity log — set it to DEBUG to see every decision, including which " +
                        "pre-filter rule rejected a message and what score the quote check got.\n\n" +
                        "3. Self-test, on the Status screen — pushes a made-up Hinglish message through " +
                        "the real pipeline end to end and names the stage that failed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onOpenModelCalls) { Text("Model calls") }
                    OutlinedButton(onClick = onOpenLog) { Text("Activity log") }
                }
            }
        }
    }
}
