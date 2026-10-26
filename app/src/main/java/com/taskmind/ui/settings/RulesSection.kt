package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.Rules
import com.taskmind.ui.components.SectionCard

/**
 * What TaskMind has learned, as rules the user can see and change.
 *
 * Ideas come from what was kept and dropped and wait here for a yes or no;
 * nothing is applied without one. Approved rules are sent with every
 * message the AI reads, and each can be removed. The user can also write a
 * rule in their own words.
 */
@Composable
fun RulesSection(viewModel: SettingsViewModel) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val ideas by viewModel.ideas.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refreshIdeas() }

    SectionCard(
        title = "Rules",
        subtitle = "Learned from what you keep and drop. Nothing applies until you say yes.",
    ) {
        if (ideas.isNotEmpty()) {
            Text("Ideas", style = MaterialTheme.typography.labelLarge)
            ideas.forEach { idea ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(Rules.label(idea.kind, idea.subject), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        idea.why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { viewModel.decideIdea(idea, approve = true) }) { Text("Use this rule") }
                        TextButton(onClick = { viewModel.decideIdea(idea, approve = false) }) { Text("No") }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        val approved = rules.filter { it.state == Rules.State.APPROVED }
        if (approved.isEmpty()) {
            Text(
                "No rules yet. Ideas appear here after a few weeks of keeping and dropping tasks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            approved.forEach { rule ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Rules.label(rule.kind, rule.subject),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.removeRule(rule.id) }) { Text("Remove") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= Rules.MAX_CUSTOM) draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a rule, e.g. Stock alerts are never tasks") },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    viewModel.addRule(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("Add") }
        }

        val rejected = rules.count { it.state == Rules.State.REJECTED }
        if (rejected > 0) {
            Text(
                "$rejected idea${if (rejected == 1) "" else "s"} you said no to will not be suggested again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
