package com.taskmind.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.Priority
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.design.CountPill
import com.taskmind.ui.design.Empty
import com.taskmind.ui.design.MetaChip
import com.taskmind.ui.design.Radius
import com.taskmind.ui.design.Space
import com.taskmind.ui.design.Touch
import com.taskmind.ui.design.listContentPadding

/**
 * The screen the app is for.
 *
 * Rebuilt around one idea: the answer to "what do I have to do" should be
 * legible without reading. That means the agenda's own headings carry the
 * counts, overdue work is coloured rather than merely listed, and everything
 * that is not a task title is quieter than the task title.
 *
 * The view model is untouched - this is presentation only, over exactly the
 * same state and the same calls.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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
    var showSortMenu by remember { mutableStateOf(false) }

    // Spec 16: undo for every destructive action, via snackbar.
    LaunchedEffect(undo) {
        val action = undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = action.message,
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.runUndo(action) else viewModel.consumeUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                SelectionBar(
                    count = state.selection.size,
                    onClear = viewModel::clearSelection,
                    onComplete = viewModel::bulkComplete,
                    onArchive = viewModel::bulkArchive,
                    onDelete = viewModel::bulkDelete,
                )
            } else {
                TopAppBar(
                    title = { Text("Tasks", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.setSearching(!state.searching) }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search tasks")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Outlined.Sort, contentDescription = "Sort and group")
                            }
                            DropdownMenu(showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = {
                                            viewModel.setSort(mode)
                                            showSortMenu = false
                                        },
                                        trailingIcon = {
                                            if (state.sort == mode) {
                                                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(onClick = { showEditor = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New task")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.searching) {
                SearchField(
                    query = state.query,
                    onQuery = viewModel::setQuery,
                    onClose = {
                        viewModel.setQuery("")
                        viewModel.setSearching(false)
                    },
                )
            }

            // The review inbox earns a permanent place at the top of the list
            // rather than a badge somewhere in a menu: an item waiting for a
            // decision is the most time-sensitive thing the app holds.
            if (state.pendingReviewCount > 0) {
                ReviewBanner(count = state.pendingReviewCount, onClick = onOpenReview)
            }

            ViewChips(state = state, onSelect = viewModel::setView)

            if (state.tasks.isEmpty()) {
                Empty(
                    title = state.view.emptyTitle,
                    body = state.view.emptyBody,
                    icon = Icons.Outlined.Inbox,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    contentPadding = listContentPadding(),
                    verticalArrangement = Arrangement.spacedBy(Space.hair),
                ) {
                    // One row-builder, used flat or under section headers, so
                    // the two paths cannot drift apart.
                    fun LazyListScope.taskRows(rows: List<TaskEntity>) {
                        items(rows, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                selected = task.id in state.selection,
                                selectionMode = state.selectionMode,
                                overdue = TaskFilters.sectionFor(task, System.currentTimeMillis()) ==
                                    TaskFilters.AgendaSection.OVERDUE,
                                onClick = {
                                    if (state.selectionMode) {
                                        viewModel.toggleSelection(task.id)
                                    } else {
                                        onOpenTask(task.id)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(task.id) },
                                onToggleComplete = {
                                    if (task.status == TaskStatus.COMPLETED) {
                                        viewModel.reopen(task)
                                    } else {
                                        viewModel.complete(task)
                                    }
                                },
                            )
                        }
                    }

                    if (state.view == TaskView.AGENDA) {
                        // Overdue first: that is what someone opens the app to
                        // find out, and it is the thing a flat list buries.
                        for ((section, rows) in TaskFilters.agenda(state.tasks, System.currentTimeMillis())) {
                            item(key = "section-${section.name}") {
                                SectionHeading(
                                    label = section.label,
                                    count = rows.size,
                                    urgent = section == TaskFilters.AgendaSection.OVERDUE,
                                )
                            }
                            taskRows(rows)
                        }
                    } else {
                        taskRows(state.tasks)
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

/**
 * One task.
 *
 * The old row gave the title, the date, the priority dot and the source equal
 * visual weight, so none of them stood out. Here the title is the only thing
 * at full contrast; everything else is support, and the single loud element -
 * the red edge on an overdue row - is reserved for the one state that needs
 * to interrupt someone scrolling past.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    task: TaskEntity,
    selected: Boolean,
    selectionMode: Boolean,
    overdue: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleComplete: () -> Unit,
) {
    val done = task.status == TaskStatus.COMPLETED
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else -> Color.Transparent
    }

    Surface(
        color = container,
        shape = RoundedCornerShape(Radius.row),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.snug)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier
                .heightIn(min = Touch.min)
                .padding(vertical = Space.snug, horizontal = Space.snug),
            verticalAlignment = Alignment.Top,
        ) {
            // A bar, not a dot. At a glance down the list the eye follows a
            // vertical edge far more easily than it picks out small circles.
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(3.dp)
                    .height(28.dp)
                    .background(
                        color = priorityColor(task.priority, overdue && !done),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.width(Space.step))

            Checkbox(
                checked = done,
                onCheckedChange = { onToggleComplete() },
                modifier = Modifier.size(Touch.min - 12.dp),
            )
            Spacer(Modifier.width(Space.tight))

            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (overdue && !done) FontWeight.Medium else FontWeight.Normal,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val source = task.sourceLabel?.takeIf { it.isNotBlank() }
                if (task.dueAt != null || source != null) {
                    Spacer(Modifier.height(Space.tight))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.snug),
                    ) {
                        task.dueAt?.let { due ->
                            Text(
                                text = DateFormats.due(due),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (overdue && !done) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        source?.let { MetaChip(text = it.take(28)) }
                    }
                }
            }
        }
    }
}

/** Overdue overrides priority: the deadline is the more urgent fact. */
@Composable
private fun priorityColor(priority: Priority, overdue: Boolean): Color = when {
    overdue -> MaterialTheme.colorScheme.error
    priority == Priority.HIGH -> MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
    priority == Priority.MEDIUM -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
}

@Composable
private fun SectionHeading(label: String, count: Int, urgent: Boolean) {
    val color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.edge, end = Space.edge, top = Space.step, bottom = Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Spacer(Modifier.width(Space.snug))
        CountPill(count, color)
    }
}

/**
 * The one thing on this screen allowed to look like a call to action.
 *
 * Items awaiting approval used to sit behind a badge in an overflow menu,
 * which is where things go to be forgotten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewBanner(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge, vertical = Space.snug),
    ) {
        Row(
            Modifier.padding(Space.edge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(Space.step))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) "1 item needs your approval" else "$count items need your approval",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Tap to accept or dismiss",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onComplete: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = onComplete) {
                Icon(Icons.Outlined.DoneAll, contentDescription = "Complete selected")
            }
            IconButton(onClick = onArchive) {
                Icon(Icons.Outlined.Archive, contentDescription = "Archive selected")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete selected")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search titles and notes") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close search")
            }
        },
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = RoundedCornerShape(Radius.card),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge, vertical = Space.snug),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewChips(state: TaskListUiState, onSelect: (TaskView) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.step, vertical = Space.tight),
        horizontalArrangement = Arrangement.spacedBy(Space.snug),
    ) {
        TaskView.entries.forEach { view ->
            val count = state.counts[view] ?: 0
            FilterChip(
                selected = state.view == view,
                onClick = { onSelect(view) },
                label = { Text(if (count > 0) "${view.label}  $count" else view.label) },
            )
        }
    }
}
