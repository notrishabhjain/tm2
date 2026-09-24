package com.taskmind.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmind.profiles.Profile
import com.taskmind.ui.AppViewModels
import com.taskmind.ui.components.SectionCard
import com.taskmind.ui.design.Space
import com.taskmind.ui.design.Touch
import com.taskmind.ui.tasks.TaskListViewModel

/**
 * Sorting the people and chats you actually hear from into work and personal.
 *
 * The list is built from your tasks rather than from your contacts. Your
 * address book has hundreds of names and almost none of them have ever sent
 * you a commitment; this shows the ones that have, commonest first, which is a
 * list short enough to finish in a sitting.
 *
 * Nothing is guessed. A name you have not sorted stays unsorted, and its tasks
 * appear under both profiles - see ProfileRules.matches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSection() {
    val vm: TaskListViewModel = viewModel(factory = AppViewModels.factory)
    val state by vm.state.collectAsStateWithLifecycle()

    // Commonest first, uncapped, and computed before the profile filter - see
    // TaskListUiState.allSubjects.
    val subjects = state.allSubjects

    SectionCard(
        title = "People and chats",
        subtitle = "Everything from a name you sort here moves with it — including tasks " +
            "captured months ago, because the profile is worked out from the task rather than " +
            "stored on it.",
    ) {
        if (subjects.isEmpty()) {
            Text(
                "Nothing to sort yet. Names appear here once TaskMind has captured a task from " +
                    "somebody.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        subjects.forEach { subject ->
            val current = state.profileBook.profileOf(subject)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.min)
                    .padding(vertical = Space.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        subject,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current == null) {
                        Text(
                            "Both",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
                    Profile.entries.forEach { profile ->
                        FilterChip(
                            selected = current == profile,
                            // Tapping the selected one clears it, so a mistake
                            // takes one tap to undo rather than a third option.
                            onClick = { vm.classify(subject, if (current == profile) null else profile) },
                            label = { Text(profile.label) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "A group beats a person when they disagree: a work contact writing in the family " +
                "group is personal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
