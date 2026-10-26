package com.taskmind.ui.tasks

import com.taskmind.contacts.ContactLookup
import com.taskmind.core.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.taskmind.core.LogLevel
import com.taskmind.core.PreFilter
import com.taskmind.core.Stage
import com.taskmind.tagging.AutoTagger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.Priority
import com.taskmind.core.TaskStatus
import com.taskmind.core.FollowUp
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Who a task came from, and how to get back to them. */
data class TaskContact(
    /** The person, when the task names one. */
    val person: String?,
    /** The group, when it came from one. */
    val group: String?,
    /** A dialable number for [person], when the phone book has one. */
    val number: String?,
    /** The chat app it arrived through, when that is WhatsApp. */
    val chatApp: String?,
)

data class TaskDetailUiState(
    val contact: TaskContact? = null,
    val task: TaskEntity? = null,
    val subTasks: List<TaskEntity> = emptyList(),
    val rawCapture: RawCaptureEntity? = null,
    val loading: Boolean = true,
    val notFound: Boolean = false,
    /** Other active tasks the app thinks belong with this one. */
    val related: List<TaskEntity> = emptyList(),
    /** Why, in the words shown under the heading. */
    val relatedReason: String? = null,
    /** Tasks that look like this one captured twice. */
    val duplicates: List<TaskEntity> = emptyList(),
    /** Every matter, for the picker. */
    val matters: List<ProjectEntity> = emptyList(),
) {
    val matterName: String? get() = task?.projectId?.let { id -> matters.firstOrNull { it.id == id }?.name }
    val maybeDone: Boolean get() = task?.let { FollowUp.MAYBE_DONE_TAG in it.tags && it.status == TaskStatus.ACTIVE } == true
}

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
                    contact = contactFor(task),
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
            container.taskRepository.observeProjects().collect { projects ->
                _state.value = _state.value.copy(matters = projects)
            }
        }
        viewModelScope.launch {
            container.taskRepository.observeSubTasks(taskId).collect { subs ->
                _state.value = _state.value.copy(subTasks = subs)
            }
        }
        viewModelScope.launch {
            container.taskRepository.observeTasks().collect { all ->
                // Only active, top-level tasks. Offering to complete something
                // already completed is not an offer, and a sub-task is already
                // shown under its parent.
                val pool = all.filter { it.status == TaskStatus.ACTIVE && it.parentTaskId == null }
                val dupes = TaskBundles.duplicatesOf(taskId, pool)
                val dupeIds = dupes.map { it.id }.toSet()
                val group = TaskBundles.relatedTo(taskId, pool)
                _state.value = _state.value.copy(
                    // A suspected duplicate is shown on its own, as a
                    // question, so it is kept out of the list you are being
                    // invited to tick off in one go.
                    related = group?.tasks.orEmpty()
                        .filter { it.id != taskId && it.id !in dupeIds },
                    relatedReason = group?.bundle?.reason,
                    duplicates = dupes,
                )
            }
        }
    }

    private suspend fun contactFor(task: TaskEntity): TaskContact? {
        if (task.sourceType == SourceType.MANUAL) return null
        val parsed = AutoTagger.parseLabel(task.sourceType, task.sourceLabel)
        val person = parsed.person?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        val number = withContext(Dispatchers.IO) { ContactLookup.numberFor(container.context, person) }
        val chatApp = task.sourceApp?.takeIf { it.startsWith("com.whatsapp") }
        if (person == null && parsed.group == null) return null
        return TaskContact(person = person, group = parsed.group, number = number, chatApp = chatApp)
    }

    /**
     * Ticks off this task and everything bundled with it.
     *
     * One call rather than a loop of them, so the whole thing is one write and
     * one undo - which is the point of bundling in the first place.
     */
    fun completeBundle() {
        val task = _state.value.task ?: return
        val ids = listOf(task.id) + _state.value.related.map { it.id }
        viewModelScope.launch {
            container.taskRepository.bulkSetStatus(ids, TaskStatus.COMPLETED)
        }
    }

    fun save(update: TaskEntity) {
        viewModelScope.launch { container.taskRepository.update(update) }
    }

    /** Files the task under a matter, or under none. */
    fun setMatter(projectId: String?) {
        val task = _state.value.task ?: return
        save(task.copy(projectId = projectId))
    }

    /** Files the task under a new matter, or under the existing one of that name. */
    fun createMatter(name: String) {
        val task = _state.value.task ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val id = _state.value.matters.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.id
                ?: container.taskRepository.createProject(clean)
            container.taskRepository.update(task.copy(projectId = id))
        }
    }

    /** The user says it is not done after all. */
    fun stillOpen() {
        val task = _state.value.task ?: return
        save(task.copy(tags = task.tags - FollowUp.MAYBE_DONE_TAG))
    }

    fun setPriority(priority: Priority) {
        val task = _state.value.task ?: return
        save(task.copy(priority = priority))
    }

    /**
     * Renames the task. The dedup key stays as it was: it records what the
     * source said, and renaming a captured task must not let the same message
     * create it a second time.
     */
    fun rename(title: String) {
        val task = _state.value.task ?: return
        val clean = title.trim()
        if (clean.isEmpty() || clean == task.title) return
        save(task.copy(title = clean))
    }

    /** Due date and reminder together, in one write. */
    fun setWhen(dueAt: Long?, reminderAt: Long?) {
        val task = _state.value.task ?: return
        viewModelScope.launch {
            container.taskRepository.update(task.copy(dueAt = dueAt, reminderAt = reminderAt))
            Scheduler.scheduleNextReminder(container.context, container)
        }
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

    /**
     * "This is not my task": removes it and reports the group it came from,
     * so the screen can offer to stop reading that group. The offer is a
     * question, never automatic - one misread message does not make a whole
     * group noise.
     */
    fun notMine(onRemoved: (group: String?) -> Unit) {
        val task = _state.value.task ?: return
        viewModelScope.launch {
            container.taskRepository.notMine(task.id)
            container.logger.write(Stage.FUNNEL, LogLevel.INFO, "marked not mine", task.title)
            onRemoved(AutoTagger.parseLabel(task.sourceType, task.sourceLabel).group?.takeIf { it.isNotBlank() })
        }
    }

    fun muteGroup(group: String) {
        viewModelScope.launch {
            container.settingsRepository.setGroupOverride(group, PreFilter.GroupPolicy.NEVER)
            container.logger.write(Stage.PREFILTER, LogLevel.INFO, "stopped watching group", group)
        }
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
