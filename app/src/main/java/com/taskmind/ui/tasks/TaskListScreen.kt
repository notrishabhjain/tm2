package com.taskmind.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.core.TaskStatus
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.components.EmptyState
import com.taskmind.ui.components.PriorityDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    viewModel: TaskListViewModel,
    onOpenTask: (String) -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenCalls: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditor by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Spec 16: undo for every destructive action, via snackbar.
    LaunchedEffect(undo) {
        val action = undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = action.message,
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.runUndo(action)
        } else {
            viewModel.consumeUndo()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = { Text("${state.selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::bulkComplete) {
                            Icon(Icons.Outlined.DoneAll, contentDescription = "Complete selected")
                        }
                        IconButton(onClick = viewModel::bulkArchive) {
                            Icon(Icons.Outlined.Archive, contentDescription = "Archive selected")
                        }
                        IconButton(onClick = viewModel::bulkDelete) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete selected")
                        }
                    },
                )
            } else if (state.searching) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("Search titles and notes") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setSearching(false) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close search")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("TaskMind") },
                    actions = {
                        IconButton(onClick = { viewModel.setSearching(true) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        if (state.pendingReviewCount > 0) {
                            BadgedBox(badge = { Badge { Text("${state.pendingReviewCount}") } }) {
                                IconButton(onClick = onOpenReview) {
                                    Icon(Icons.Outlined.Inbox, contentDescription = "Review inbox")
                                }
                            }
                        } else {
                            IconButton(onClick = onOpenReview) {
                                Icon(Icons.Outlined.Inbox, contentDescription = "Review inbox")
                            }
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Outlined.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = {
                                            viewModel.setSort(mode)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Status and diagnostics") },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                    onClick = { showMenu = false; onOpenStatus() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Calls") },
                                    leadingIcon = { Icon(Icons.Outlined.Phone, null) },
                                    onClick = { showMenu = false; onOpenCalls() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Import transcript") },
                                    onClick = { showMenu = false; onOpenImport() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { showMenu = false; onOpenSettings() },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showEditor = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New task") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ViewChips(state = state, onSelect = viewModel::setView)

            if (state.projects.isNotEmpty() || state.tags.isNotEmpty()) {
                FilterChips(state = state, onProject = viewModel::setProject, onTag = viewModel::setTag)
            }

            if (state.tasks.isEmpty()) {
                EmptyState(
                    title = state.view.emptyTitle,
                    body = state.view.emptyBody,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            selected = task.id in state.selection,
                            selectionMode = state.selectionMode,
                            onClick = {
                                if (state.selectionMode) viewModel.toggleSelection(task.id) else onOpenTask(task.id)
                            },
                            onLongClick = { viewModel.toggleSelection(task.id) },
                            onToggleComplete = {
                                if (task.status == TaskStatus.COMPLETED) viewModel.reopen(task) else viewModel.complete(task)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        TaskEditorSheet(
            projects = state.projects,
            onDismiss = { showEditor = false },
            onSave = { title, notes, dueAt, priority, projectId, tags, reminderAt, rule ->
                viewModel.createTask(title, notes, dueAt, priority, projectId, tags, reminderAt, rule)
                showEditor = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewChips(state: TaskListUiState, onSelect: (TaskView) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TaskView.entries.forEach { view ->
            val count = state.counts[view] ?: 0
            FilterChip(
                selected = state.view == view,
                onClick = { onSelect(view) },
                label = { Text(if (count > 0) "${view.label} $count" else view.label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChips(
    state: TaskListUiState,
    onProject: (String?) -> Unit,
    onTag: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.projects.forEach { project ->
            FilterChip(
                selected = state.projectId == project.id,
                onClick = { onProject(if (state.projectId == project.id) null else project.id) },
                label = { Text(project.name) },
            )
        }
        state.tags.forEach { tag ->
            FilterChip(
                selected = state.tag == tag.name,
                onClick = { onTag(if (state.tag == tag.name) null else tag.name) },
                label = { Text("#${tag.name}") },
            )
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleComplete: () -> Unit,
) {
    val completed = task.status == TaskStatus.COMPLETED
    val overdue = !completed && DateFormats.isOverdue(task.dueAt)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = completed || selected,
                onCheckedChange = { if (selectionMode) onLongClick() else onToggleComplete() },
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityDot(task.priority)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (completed) TextDecoration.LineThrough else null,
                        color = if (completed) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = DateFormats.due(task.dueAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    // Spec 16: provenance is the differentiator. Every
                    // auto-created task says where it came from, right here in
                    // the list, not buried on a detail screen.
                    task.sourceLabel?.let { label ->
                        Text(
                            text = "  -  $label",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (task.tags.isNotEmpty()) {
                    Text(
                        text = task.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun TaskListActionsHint(onOpenStatus: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onOpenStatus)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Capture is not set up yet - tap to see what is missing",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        TextButton(onClick = onOpenStatus, modifier = Modifier.align(Alignment.CenterEnd)) {
            Text("Fix")
        }
    }
}
