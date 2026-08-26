package com.taskmind.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.Priority
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.data.db.entity.TagEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
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
    val view: TaskView = TaskView.TODAY,
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
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

/** A destructive action the user can take back (spec 16: undo for every one). */
data class UndoAction(val message: String, val undo: suspend () -> Unit)

class TaskListViewModel(private val container: AppContainer) : ViewModel() {

    private val filters = MutableStateFlow(TaskListUiState())

    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo.asStateFlow()

    val state: StateFlow<TaskListUiState> = combine(
        container.taskRepository.observeTasks(),
        container.taskRepository.observeProjects(),
        container.taskRepository.observeTags(),
        container.taskRepository.observePendingReviewCount(),
        filters,
    ) { tasks, projects, tags, reviewCount, current ->
        val now = System.currentTimeMillis()
        current.copy(
            tasks = TaskFilters.apply(
                tasks = tasks,
                view = current.view,
                query = current.query,
                projectId = current.projectId,
                tag = current.tag,
                sort = current.sort,
                now = now,
            ),
            counts = TaskFilters.counts(tasks, now),
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
