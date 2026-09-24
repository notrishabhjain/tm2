package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskmind.core.PreFilter
import com.taskmind.data.settings.Settings
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.design.Space

/**
 * Who you are, and what to do about group chats.
 *
 * These two belong on one page because the first is what makes the second
 * work. "Only when I'm involved" with no names to look for would reject every
 * group message on the phone, so the app stands the rule down until a name is
 * entered - and a setting that quietly does nothing is worse than no setting,
 * so this page says so at the top rather than leaving it to be discovered.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GroupSection(settings: Settings, viewModel: SettingsViewModel) {
    var newName by remember { mutableStateOf("") }
    var newGroup by remember { mutableStateOf("") }

    SectionCard(
        title = "What people call you",
        subtitle = "Your first name, a nickname, your work handle, your number — whatever appears " +
            "when somebody addresses you in a chat.",
    ) {
        if (settings.ownNames.isEmpty()) {
            Text(
                "Nothing set yet, so group filtering is switched off and every group message is " +
                    "read, exactly as before. Add at least one name to turn it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(Space.step))
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
            settings.ownNames.sorted().forEach { name ->
                AssistChip(
                    onClick = { viewModel.setOwnNames(settings.ownNames - name) },
                    label = { Text(name) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Remove $name",
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(Space.snug))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Add a name") },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(Space.snug))
            TextButton(
                onClick = {
                    viewModel.setOwnNames(settings.ownNames + newName.trim())
                    newName = ""
                },
                enabled = newName.trim().length >= 2,
            ) {
                Text("Add")
            }
        }

        Spacer(Modifier.height(Space.snug))
        Text(
            "Also used by the model. It could never apply its own \"is this aimed at somebody " +
                "else\" rule before, because nothing told it what you are called — which is why a " +
                "colleague's request to another colleague arrived as a task, and arrived confident.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard(
        title = "Group chats",
        subtitle = "One-to-one messages and calls are unaffected by this.",
    ) {
        PreFilter.GroupPolicy.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.tight),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = settings.groupPolicy == option,
                    onClick = { viewModel.setGroupPolicy(option) },
                )
                Spacer(Modifier.width(Space.snug))
                Column(Modifier.weight(1f)) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (settings.groupPolicy == option) FontWeight.SemiBold else null,
                    )
                    Text(
                        option.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.snug))
        Text(
            "Android gives no reliable \"you were mentioned\" signal — WhatsApp writes the mention " +
                "into the message text and that is all there is to read. So this will occasionally " +
                "miss one. The two lists below are the way to say \"never mind the rule here\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard(
        title = "Groups with their own rule",
        subtitle = "Type the group's name exactly as WhatsApp shows it.",
    ) {
        GroupList(
            label = "Always read everything",
            names = settings.groupsAlwaysWatch,
            onRemove = { viewModel.setGroupOverride(it, null) },
        )
        Spacer(Modifier.height(Space.step))
        GroupList(
            label = "Never read at all",
            names = settings.groupsNeverWatch,
            onRemove = { viewModel.setGroupOverride(it, null) },
        )

        Spacer(Modifier.height(Space.step))
        OutlinedTextField(
            value = newGroup,
            onValueChange = { newGroup = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Group name") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(Space.snug))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
            TextButton(
                onClick = {
                    viewModel.setGroupOverride(newGroup.trim(), PreFilter.GroupPolicy.EVERYTHING)
                    newGroup = ""
                },
                enabled = newGroup.isNotBlank(),
            ) {
                Text("Always read")
            }
            TextButton(
                onClick = {
                    viewModel.setGroupOverride(newGroup.trim(), PreFilter.GroupPolicy.NEVER)
                    newGroup = ""
                },
                enabled = newGroup.isNotBlank(),
            ) {
                Text("Never read")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GroupList(label: String, names: Set<String>, onRemove: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(Space.tight))
    if (names.isEmpty()) {
        Text(
            "None",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
        names.sorted().forEach { name ->
            AssistChip(
                onClick = { onRemove(name) },
                label = { Text(name) },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove $name",
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}
