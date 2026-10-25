package com.taskmind.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.core.Priority
import com.taskmind.core.WeeklyReview
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.ui.components.DateFormats
import com.taskmind.ui.design.CountPill
import com.taskmind.ui.design.Empty
import com.taskmind.prefs.UiPreferences
import com.taskmind.profiles.Profile
import com.taskmind.tagging.AutoTagger
import com.taskmind.ui.design.MetaChip
import com.taskmind.ui.design.Radius
import com.taskmind.ui.design.Space
import com.taskmind.ui.design.Touch
import com.taskmind.ui.design.listContentPadding
import kotlinx.coroutines.launch

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
    onOpenNameSettings: () -> Unit = onOpenSettings,
    /** Opened from the widget's "+": open the new-task sheet. */
    startWithEditor: Boolean = false,
    onEditorRequestHandled: () -> Unit = {},
    /** Ask the task list a question in words (Ask TaskMind). */
    onAsk: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val uiPrefs = remember(context) { UiPreferences(context) }
    val ui by uiPrefs.state.collectAsStateWithLifecycle(initialValue = UiPreferences.State())
    var showEditor by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startWithEditor) {
        if (startWithEditor) {
            showEditor = true
            onEditorRequestHandled()
        }
    }
    var showFilters by remember { mutableStateOf(false) }

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
                        // Sort, group, work/personal and tags all live in one
                        // sheet behind this button. They used to be three rows
                        // of chips stacked above the first task.
                        IconButton(onClick = { showFilters = true }) {
                            BadgedBox(
                                badge = { if (state.profile != null || state.tag != null) Badge() },
                            ) {
                                Icon(Icons.Outlined.Tune, contentDescription = "Filter, sort and group")
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VoiceNoteButton { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    }
                    Spacer(Modifier.height(Space.step))
                    FloatingActionButton(onClick = { showEditor = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New task")
                    }
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
                // Search matches words; a question needs reading. Once the
                // query looks like a question, offer to ask it instead.
                if (looksLikeQuestion(state.query)) {
                    TextButton(
                        onClick = { onAsk(state.query.trim()) },
                        modifier = Modifier.padding(horizontal = Space.step),
                    ) { Text("Ask TaskMind: \u201c${state.query.trim().take(40)}\u201d") }
                }
            }

            // The one setting that changes what gets captured. Without a name
            // the group filter cannot tell a request to you from one between
            // two colleagues, so it is asked for here, where it will be seen,
            // rather than on a settings page nobody opens. The review inbox
            // used to hold this spot; the Review tab's badge already says it.
            if (!state.ownNamesSet) {
                NameBanner(
                    title = "Tell TaskMind your name",
                    body = "So requests between other people in a group stop becoming your tasks.",
                    onClick = onOpenNameSettings,
                )
            } else if (!state.aboutMeSet) {
                NameBanner(
                    title = "Tell TaskMind about your work",
                    body = "Ten lines on your role, key people and what to ignore - the biggest single " +
                        "improvement to what becomes a task.",
                    onClick = onOpenNameSettings,
                )
            }

            ViewChips(state = state, onSelect = viewModel::setView)

            // Only what is actually narrowing the list, each with its own x.
            ActiveFilters(
                profile = state.profile,
                tag = state.tag,
                onClearProfile = { viewModel.setProfile(null) },
                onClearTag = { viewModel.setTag(null) },
            )

            state.week?.let { WeekCard(it) }

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
                            SwipeableTaskRow(
                                rightAction = ui.swipeRight,
                                leftAction = ui.swipeLeft,
                                enabled = !state.selectionMode,
                                onAction = { action -> viewModel.runSwipe(task, action) },
                            ) {
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
                    }

                    when {
                        // Bundling wins over the agenda when it is on: the two
                        // answer different questions and stacking both would
                        // put a task under two headings at once.
                        state.group == GroupMode.RELATED -> {
                            for (group in state.bundles) {
                                item(key = "bundle-${group.bundle.key}") {
                                    BundleHeading(
                                        group = group,
                                        onCompleteAll = { viewModel.completeBundle(group) },
                                    )
                                }
                                taskRows(group.tasks)
                            }
                            if (state.unbundled.isNotEmpty()) {
                                if (state.bundles.isNotEmpty()) {
                                    item(key = "section-loose") {
                                        SectionHeading(
                                            label = "On their own",
                                            count = state.unbundled.size,
                                            urgent = false,
                                        )
                                    }
                                }
                                taskRows(state.unbundled)
                            }
                        }

                        state.view == TaskView.AGENDA -> {
                            // Overdue first: that is what someone opens the app to
                            // find out, and it is the thing a flat list buries.
                            for ((section, rows) in TaskFilters.agenda(state.tasks, System.currentTimeMillis())) {
                                item(key = "section-${section.name}") {
                                    val overdueSection = section == TaskFilters.AgendaSection.OVERDUE
                                    SectionHeading(
                                        label = section.label,
                                        count = rows.size,
                                        urgent = overdueSection,
                                        action = if (overdueSection) "Move to today" else null,
                                        onAction = { viewModel.moveToToday(rows) },
                                    )
                                }
                                taskRows(rows)
                            }
                        }

                        else -> taskRows(state.tasks)
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            state = state,
            onDismiss = { showFilters = false },
            onProfile = viewModel::setProfile,
            onSort = viewModel::setSort,
            onGroup = viewModel::setGroup,
            onTag = viewModel::setTag,
        )
    }

    if (showEditor) {
        TaskEditorSheet(
            onDismiss = { showEditor = false },
            onSave = { title, notes, dueAt, priority, tags, reminderAt, rule ->
                viewModel.createTask(title, notes, dueAt, priority, null, tags, reminderAt, rule)
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

                // Three chips rather than one truncated "Sharma Ji in Project
                // Alpha - WhatsApp". The same information, but each part is
                // separately readable and separately searchable, which is the
                // point of deriving them at all.
                // One chip: who it is from. The date already says when, and
                // the app, the source and the topic are on the task's own
                // screen - three chips per row was most of the list's noise.
                val autoTags = remember(task.id, task.title, task.sourceLabel) {
                    TaskFilters.autoTags(task)
                        .filter { it.kind == AutoTagger.Kind.PERSON || it.kind == AutoTagger.Kind.GROUP }
                        .take(1)
                }
                if (task.dueAt != null || autoTags.isNotEmpty()) {
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
                        autoTags.forEach { tag ->
                            MetaChip(text = tag.value.take(22), color = tagColor(tag.kind))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Everything that narrows or orders the list, in one place.
 *
 * Profile, sort, grouping and tags were a sort menu plus three rows of chips
 * permanently above the list - on a phone, a third of the screen before the
 * first task. They change rarely, so they live here; the list shows only what
 * is currently switched on (see [ActiveFilters]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    state: TaskListUiState,
    onDismiss: () -> Unit,
    onProfile: (Profile?) -> Unit,
    onSort: (SortMode) -> Unit,
    onGroup: (GroupMode) -> Unit,
    onTag: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.edge)
                .padding(bottom = Space.section),
        ) {
            SheetLabel("Show")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
                FilterChip(selected = state.profile == null, onClick = { onProfile(null) }, label = { Text("Everything") })
                Profile.entries.forEach { profile ->
                    FilterChip(
                        selected = state.profile == profile,
                        onClick = { onProfile(if (state.profile == profile) null else profile) },
                        label = { Text(profile.label) },
                    )
                }
            }

            SheetLabel("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
                SortMode.entries.forEach { mode ->
                    FilterChip(selected = state.sort == mode, onClick = { onSort(mode) }, label = { Text(mode.label) })
                }
            }

            SheetLabel("Group")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.snug)) {
                GroupMode.entries.forEach { mode ->
                    FilterChip(selected = state.group == mode, onClick = { onGroup(mode) }, label = { Text(mode.label) })
                }
            }

            // Only tags the app worked out itself, and only ones that occur in
            // this view - a tag outlives its last task by not a second.
            if (state.tagCloud.isNotEmpty()) {
                SheetLabel("People, groups and topics")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.snug),
                    verticalArrangement = Arrangement.spacedBy(Space.tight),
                ) {
                    state.tagCloud.forEach { tag ->
                        val isOn = state.tag?.equals(tag.value, ignoreCase = true) == true
                        FilterChip(
                            selected = isOn,
                            onClick = { onTag(if (isOn) null else tag.value) },
                            label = { Text(tag.value) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.step, bottom = Space.tight),
    )
}

/** The filters currently narrowing the list, each removable in one tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFilters(
    profile: Profile?,
    tag: String?,
    onClearProfile: () -> Unit,
    onClearTag: () -> Unit,
) {
    if (profile == null && tag == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.step),
        horizontalArrangement = Arrangement.spacedBy(Space.snug),
    ) {
        profile?.let {
            InputChip(
                selected = true,
                onClick = onClearProfile,
                label = { Text(it.label) },
                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Show everything", modifier = Modifier.size(16.dp)) },
            )
        }
        tag?.let {
            InputChip(
                selected = true,
                onClick = onClearTag,
                label = { Text(it) },
                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

/**
 * Tags are coloured by what they tell you, not by which tag they are.
 *
 * Who said it is the thing the eye should land on; how it arrived is
 * background. Same reasoning as the priority bar - the colour carries a rank,
 * so the row can be scanned instead of read.
 */
@Composable
private fun tagColor(kind: AutoTagger.Kind): Color = when (kind) {
    AutoTagger.Kind.PERSON -> MaterialTheme.colorScheme.primary
    AutoTagger.Kind.GROUP -> MaterialTheme.colorScheme.tertiary
    AutoTagger.Kind.TOPIC -> MaterialTheme.colorScheme.secondary
    AutoTagger.Kind.APP, AutoTagger.Kind.SOURCE -> MaterialTheme.colorScheme.onSurfaceVariant
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
private fun SectionHeading(
    label: String,
    count: Int,
    urgent: Boolean,
    action: String? = null,
    onAction: () -> Unit = {},
) {
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
        if (action != null) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * A bundle's heading, with the one action the whole feature exists for.
 *
 * The reason line is not decoration. The app decided these belong together,
 * and a grouping that cannot say why is one nobody will trust enough to hit
 * "Complete all" on.
 */
@Composable
private fun BundleHeading(group: TaskBundles.Group, onCompleteAll: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.edge, end = Space.snug, top = Space.step, bottom = Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Space.snug))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.bundle.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(Space.snug))
                CountPill(group.tasks.size, MaterialTheme.colorScheme.primary)
            }
            Text(
                text = group.bundle.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCompleteAll) { Text("Complete all") }
    }
}

/**
 * Asks for the user's name until it has one.
 *
 * The only banner on this screen: everything about group chats depends on it,
 * and it was previously a card on a Status screen nothing in the app linked to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameBanner(title: String, body: String, onClick: () -> Unit) {
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
                Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(Space.step))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** The last seven days, at the top of the Done view. */
@Composable
private fun WeekCard(week: WeeklyReview.Summary) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.edge, vertical = Space.snug),
    ) {
        Column(Modifier.padding(Space.edge)) {
            Text("This week", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(week.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            week.lines().forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        placeholder = { Text("Search, or ask a question") },
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

/** Three words or more, or a question mark: worth offering to ask. */
private fun looksLikeQuestion(query: String): Boolean {
    val q = query.trim()
    return q.endsWith("?") || q.split(Regex("\\s+")).size >= 3
}
