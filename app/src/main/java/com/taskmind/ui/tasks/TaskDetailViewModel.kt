package com.taskmind.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.Priority
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaskDetailUiState(
    val task: TaskEntity? = null,
    val subTasks: List<TaskEntity> = emptyList(),
    val rawCapture: RawCaptureEntity? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
)

class TaskDetailViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(TaskDetailUiState())
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()

    private var currentId: String? = null

    fun load(taskId: String) {
        if (currentId == taskId) return
        currentId = taskId
        viewModelScope.launch {
            container.taskRepository.observeTask(taskId).collect { task ->
                if (task == null) {
                    _state.value = TaskDetailUiState(loading = false, notFound = true)
                    return@collect
                }
                _state.value = _state.value.copy(
                    task = task,
                    loading = false,
                    notFound = false,
                    // Spec 6.3: after a retention purge this is null and the
                    // tap-through goes away, but the task keeps its evidence
                    // and its source label.
                    rawCapture = container.taskRepository.rawCaptureFor(task),
                )
            }
        }
        viewModelScope.launch {
            container.taskRepository.observeSubTasks(taskId).collect { subs ->
                _state.value = _state.value.copy(subTasks = subs)
            }
        }
    }

    fun save(update: TaskEntity) {
        viewModelScope.launch { container.taskRepository.update(update) }
    }

    fun setPriority(priority: Priority) {
        val task = _state.value.task ?: return
        save(task.copy(priority = priority))
    }

    fun setDueAt(dueAt: Long?) {
        val task = _state.value.task ?: return
        viewModelScope.launch { container.taskRepository.setDueAt(task.id, dueAt) }
    }

    fun setReminder(at: Long?) {
        val task = _state.value.task ?: return
        viewModelScope.launch {
            container.taskRepository.setReminder(task.id, at)
            Scheduler.scheduleNextReminder(container.context, container)
        }
    }

    /** Spec 16: snooze (+1h, +3h, tomorrow 9am, custom). */
    fun snooze(millisFromNow: Long) {
        val task = _state.value.task ?: return
        setReminder(System.currentTimeMillis() + millisFromNow)
        if (task.dueAt != null && task.dueAt < System.currentTimeMillis()) {
            setDueAt(System.currentTimeMillis() + millisFromNow)
        }
    }

    fun complete() {
        val task = _state.value.task ?: return
        viewModelScope.launch { container.taskRepository.complete(task.id) }
    }

    fun reopen() {
        val task = _state.value.task ?: return
        viewModelScope.launch { container.taskRepository.reopen(task.id) }
    }

    fun archive() {
        val task = _state.value.task ?: return
        viewModelScope.launch { container.taskRepository.archive(task.id) }
    }

    fun delete() {
        val task = _state.value.task ?: return
        viewModelScope.launch { container.taskRepository.delete(task.id) }
    }

    fun addSubTask(title: String) {
        val task = _state.value.task ?: return
        viewModelScope.launch {
            container.taskRepository.createManualTask(
                title = title,
                projectId = task.projectId,
                parentTaskId = task.id,
            )
        }
    }

    fun completeSubTask(sub: TaskEntity) {
        viewModelScope.launch { container.taskRepository.complete(sub.id) }
    }
}
