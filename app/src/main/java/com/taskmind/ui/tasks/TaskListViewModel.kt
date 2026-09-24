package com.taskmind.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.Priority
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.data.db.entity.TagEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.prefs.UiPreferences
import com.taskmind.tagging.AutoTagger
import com.taskmind.profiles.Profile
import com.taskmind.profiles.ProfileRules
import com.taskmind.profiles.ProfileStore
import com.taskmind.intake.IntakeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskListUiState(
    val view: TaskView = TaskView.AGENDA,
    val sort: SortMode = SortMode.DUE_DATE,
    val group: GroupMode = GroupMode.NONE,
    val query: String = "",
    val projectId: String? = null,
    val tag: String? = null,
    val tasks: List<TaskEntity> = emptyList(),
    val counts: Map<TaskView, Int> = emptyMap(),
    val projects: List<ProjectEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val pendingReviewCount: Int = 0,
    val selection: Set<String> = emptySet(),
    val searching: Boolean = false,
    /** Tags the app worked out for itself, commonest first. */
    val tagCloud: List<com.taskmind.tagging.AutoTagger.Tag> = emptyList(),
    /**
     * Every person and chat that has ever produced a task, commonest first.
     *
     * Deliberately built BEFORE the profile filter and without the tag row's
     * cap: this is what the classification screen lists, and a page for
     * sorting names that only showed names already sorted into the profile you
     * are looking at would be unusable.
     */
    val allSubjects: List<String> = emptyList(),
    /** Which half of your life you are looking at. Null is everything. */
    val profile: Profile? = null,
    /** Who is work and who is personal. Derived filtering needs it on hand. */
    val profileBook: ProfileRules.Book = ProfileRules.Book(),
    /** Groups of related tasks; empty unless [group] is RELATED. */
    val bundles: List<TaskBundles.Group> = emptyList(),
    /** What bundling left over. Equals [tasks] when bundling is off. */
    val unbundled: List<TaskEntity> = emptyList(),
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

/** A destructive action the user can take back (spec 16: undo for every one). */
data class UndoAction(val message: String, val undo: suspend () -> Unit)

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

class TaskListViewModel(private val container: AppContainer) : ViewModel() {

    private val filters = MutableStateFlow(TaskListUiState())

    private val profiles = ProfileStore(container.context)

    init {
        // Folded into the filter state rather than combined separately, so
        // there is still exactly one place where the visible list is decided.
        // Counts, the tag row and the bundles all have to agree with it, and
        // they only can if they are computed from the same list.
        viewModelScope.launch {
            profiles.state.collect { p ->
                filters.update { it.copy(profile = p.viewing, profileBook = p.book) }
            }
        }
    }

    fun setProfile(profile: Profile?) {
        viewModelScope.launch { profiles.setViewing(profile) }
    }

    /** Files everything from this person or chat under one side of your life. */
    fun classify(subject: String, profile: Profile?) {
        viewModelScope.launch { profiles.classify(subject, profile) }
    }

    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo.asStateFlow()

    val state: StateFlow<TaskListUiState> = combine(
        container.taskRepository.observeTasks(),
        container.taskRepository.observeProjects(),
        container.taskRepository.observeTags(),
        container.taskRepository.observePendingReviewCount(),
        filters,
    ) { allTasks, projects, tags, reviewCount, current ->
        val now = System.currentTimeMillis()

        // The profile filter comes first, before everything else, because a
        // task outside the profile you are looking at should not be counted,
        // should not put a tag on the filter row, and should not pull another
        // task into a bundle. An unclassified task is in every profile - see
        // ProfileRules.matches for why that matters.
        val tasks = if (current.profile == null) {
            allTasks
        } else {
            allTasks.filter {
                ProfileRules.matches(TaskFilters.autoTags(it), current.profileBook, current.profile)
            }
        }

        // The tasks this view can show, before search, project or tag. The
        // filter row is built from these rather than from every task in the
        // database, because a derived tag has no life of its own: "Sharma Ji"
        // exists precisely as long as one of his tasks does, and a chip that
        // outlives its last task is a filter that returns nothing.
        val scope = TaskFilters.scope(tasks, current.view, now)

        // A tag whose last task has just been ticked off stops filtering, and
        // is cleared at the source so it does not come back when the view
        // changes to one that still has it. Writing to `filters` from inside
        // the transform is safe here: it settles in one extra emission, since
        // the very next pass finds a null tag and does nothing.
        val liveTag = current.tag?.takeIf { TaskFilters.hasTag(scope, it) }
        if (current.tag != null && liveTag == null) filters.update { it.copy(tag = null) }

        val visible = TaskFilters.apply(
            tasks = tasks,
            view = current.view,
            query = current.query,
            projectId = current.projectId,
            tag = liveTag,
            sort = current.sort,
            now = now,
        )

        // Only when the mode is on: relating every task to every other is
        // quadratic, and paying for it while nobody is looking at the result
        // would slow down every edit in the app.
        val (bundles, loose) = if (current.group == GroupMode.RELATED) {
            TaskBundles.grouped(visible)
        } else {
            emptyList<TaskBundles.Group>() to visible
        }

        current.copy(
            tag = liveTag,
            tasks = visible,
            bundles = bundles,
            unbundled = loose,
            counts = TaskFilters.counts(tasks, now),
            tagCloud = TaskFilters.tagCloud(scope, liveTag),
            allSubjects = TaskFilters.tagCloud(allTasks, limit = Int.MAX_VALUE)
                .filter { it.kind == AutoTagger.Kind.PERSON || it.kind == AutoTagger.Kind.GROUP }
                .map { it.value }
                .distinctBy { it.lowercase() },
            projects = projects,
            tags = tags,
            pendingReviewCount = reviewCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListUiState())

    fun setView(view: TaskView) = filters.update { it.copy(view = view, selection = emptySet()) }
    fun setSort(sort: SortMode) = filters.update { it.copy(sort = sort) }
    fun setGroup(group: GroupMode) = filters.update { it.copy(group = group) }
    fun setQuery(query: String) = filters.update { it.copy(query = query) }
    fun setSearching(searching: Boolean) =
        filters.update { it.copy(searching = searching, query = if (searching) it.query else "") }

    fun setProject(projectId: String?) = filters.update { it.copy(projectId = projectId) }
    fun setTag(tag: String?) = filters.update { it.copy(tag = tag) }

    fun toggleSelection(id: String) = filters.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    fun clearSelection() = filters.update { it.copy(selection = emptySet()) }

    fun consumeUndo() {
        _undo.value = null
    }

    // ------------------------------------------------------------- actions

    fun createTask(
        title: String,
        notes: String?,
        dueAt: Long?,
        priority: Priority,
        projectId: String?,
        tags: List<String>,
        reminderAt: Long?,
        recurrenceRule: String?,
        parentTaskId: String? = null,
        onResult: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = container.taskRepository.createManualTask(
                title = title,
                notes = notes,
                dueAt = dueAt,
                priority = priority,
                projectId = projectId,
                tags = tags,
                reminderAt = reminderAt,
                recurrenceRule = recurrenceRule,
                parentTaskId = parentTaskId,
            )
            if (result is IntakeResult.Created && reminderAt != null) {
                com.taskmind.work.Scheduler.scheduleNextReminder(container.context, container)
            }
            onResult((result as? IntakeResult.Created)?.taskId)
        }
    }

    fun complete(task: TaskEntity) {
        viewModelScope.launch {
            container.taskRepository.complete(task.id)
            offerUndo("Completed \"${task.title.take(40)}\"") {
                container.taskRepository.reopen(task.id)
            }
        }
    }

    fun reopen(task: TaskEntity) {
        viewModelScope.launch { container.taskRepository.reopen(task.id) }
    }

    /**
     * Carries out whatever the swipe was configured to do.
     *
     * A single entry point so the gesture layer does not have to know which
     * actions exist, and so every one of them goes through the methods that
     * already record an undo.
     */
    fun runSwipe(task: TaskEntity, action: UiPreferences.SwipeAction) {
        when (action) {
            UiPreferences.SwipeAction.COMPLETE ->
                if (task.status == TaskStatus.COMPLETED) reopen(task) else complete(task)
            UiPreferences.SwipeAction.ARCHIVE -> archive(task)
            UiPreferences.SwipeAction.DELETE -> delete(task)
            UiPreferences.SwipeAction.SNOOZE -> snooze(task)
            UiPreferences.SwipeAction.NONE -> Unit
        }
    }

    /**
     * Pushes a task a day out, with undo.
     *
     * A day rather than an hour: this is the swipe gesture, used while
     * scanning a list, and "not today" is what that gesture means.
     */
    fun snooze(task: TaskEntity) {
        val previous = task.dueAt
        val target = (task.dueAt ?: System.currentTimeMillis()) + DAY_MILLIS
        viewModelScope.launch {
            container.taskRepository.setDueAt(task.id, target)
            _undo.value = UndoAction("Snoozed \"${task.title.take(30)}\"") {
                container.taskRepository.setDueAt(task.id, previous)
            }
        }
    }

    fun archive(task: TaskEntity) {
        viewModelScope.launch {
            container.taskRepository.archive(task.id)
            offerUndo("Archived \"${task.title.take(40)}\"") {
                container.taskRepository.unarchive(task.id)
            }
        }
    }

    fun delete(task: TaskEntity) {
        viewModelScope.launch {
            container.taskRepository.delete(task.id)
            offerUndo("Deleted \"${task.title.take(40)}\"") {
                container.taskRepository.restore(task.id)
            }
        }
    }

    /**
     * Ticks off everything in one bundle.
     *
     * The whole reason for grouping: five things one person asked for are one
     * phone call, and afterwards they are all done. One write, one undo.
     */
    fun completeBundle(group: TaskBundles.Group) {
        val ids = group.tasks.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.taskRepository.bulkSetStatus(ids, TaskStatus.COMPLETED)
            offerUndo("Completed ${ids.size} in \"${group.bundle.label.take(24)}\"") {
                container.taskRepository.bulkSetStatus(ids, TaskStatus.ACTIVE)
            }
        }
    }

    fun bulkComplete() {
        val ids = filters.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.taskRepository.bulkSetStatus(ids, TaskStatus.COMPLETED)
            clearSelection()
            offerUndo("Completed ${ids.size} tasks") {
                container.taskRepository.bulkSetStatus(ids, TaskStatus.ACTIVE)
            }
        }
    }

    fun bulkArchive() {
        val ids = filters.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.taskRepository.bulkSetStatus(ids, TaskStatus.ARCHIVED)
            clearSelection()
            offerUndo("Archived ${ids.size} tasks") {
                container.taskRepository.bulkSetStatus(ids, TaskStatus.ACTIVE)
            }
        }
    }

    fun bulkDelete() {
        val ids = filters.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.taskRepository.bulkSetStatus(ids, TaskStatus.DELETED)
            clearSelection()
            offerUndo("Deleted ${ids.size} tasks") {
                container.taskRepository.bulkSetStatus(ids, TaskStatus.ACTIVE)
            }
        }
    }

    fun bulkPriority(priority: Priority) {
        val ids = filters.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.taskRepository.bulkSetPriority(ids, priority)
            clearSelection()
        }
    }

    fun createProject(name: String) {
        viewModelScope.launch { container.taskRepository.createProject(name) }
    }

    private fun offerUndo(message: String, action: suspend () -> Unit) {
        _undo.value = UndoAction(message) {
            action()
            _undo.value = null
        }
    }

    fun runUndo(action: UndoAction) {
        viewModelScope.launch { action.undo() }
    }
}
