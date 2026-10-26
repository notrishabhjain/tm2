package com.taskmind.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.DateResolver
import com.taskmind.core.Priority
import com.taskmind.core.SmartReminder
import com.taskmind.core.Cleanup
import com.taskmind.core.Rules
import com.taskmind.core.SourceType
import com.taskmind.data.repo.MatterFiler
import com.taskmind.core.Standing
import com.taskmind.data.repo.TaskRanking
import com.taskmind.core.WeeklyReview
import com.taskmind.reminders.toWeeklyItem
import java.time.Instant
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskListUiState(
    val view: TaskView = TaskView.FOCUS,
    val sort: SortMode = SortMode.DUE_DATE,
    val group: GroupMode = GroupMode.MATTER,
    val query: String = "",
    val projectId: String? = null,
    val tag: String? = null,
    val tasks: List<TaskEntity> = emptyList(),
    val counts: Map<TaskView, Int> = emptyMap(),
    val projects: List<ProjectEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val pendingReviewCount: Int = 0,
    /** Whether the user has told the app what they are called. Defaults to
     *  true so the banner never flashes up while settings are loading. */
    val ownNamesSet: Boolean = true,
    /** Whether "About you" has been written. Same loading default as above. */
    val aboutMeSet: Boolean = true,
    /** The last seven days, shown at the top of the Done view. */
    val week: WeeklyReview.Summary? = null,
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
    /** Tasks by matter; empty unless [group] is MATTER and some task has one. */
    val matters: List<MatterGroup> = emptyList(),
    /** What is in no matter. Equals [tasks] when there are no matters to show. */
    val unmattered: List<TaskEntity> = emptyList(),
    /** Why each Focus task is there, by task id. */
    val reasons: Map<String, String> = emptyMap(),
    /** Open tasks Focus left for later; shown as a pointer to Agenda. */
    val laterCount: Int = 0,
    /** Who owes what; filled in the People view only. */
    val people: List<Standing.Person> = emptyList(),
    /** The user's About me, for picking out key people. */
    val aboutMe: String = "",
    /** People the user approved as key in Rules. */
    val approvedKeyPeople: Set<String> = emptySet(),
    /** Open tasks with no matter, and open tasks gone stale: what the tidy-up offers. */
    val unfiled: List<TaskEntity> = emptyList(),
    val stale: List<TaskEntity> = emptyList(),
    /** Until when the tidy-up banner is hidden. */
    val cleanupSnoozedUntil: Long = 0L,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

/** The tidy-up sheet: matter suggestions being fetched, shown, or applied. */
data class CleanupState(
    val busy: Boolean = false,
    /** Null until asked for; empty when the model filed nothing. */
    val suggestions: List<Cleanup.Assignment>? = null,
    val error: String? = null,
    val message: String? = null,
)

/** Enough unfiled or stale tasks to be worth offering a tidy-up. */
fun TaskListUiState.cleanupWorthOffering(now: Long): Boolean =
    now >= cleanupSnoozedUntil && (unfiled.size >= CLEANUP_MIN_UNFILED || stale.size >= CLEANUP_MIN_STALE)

const val CLEANUP_MIN_UNFILED = 5
const val CLEANUP_MIN_STALE = 3

/** One matter's open work, in the list's own order, and where it stands. */
data class MatterGroup(val id: String, val name: String, val tasks: List<TaskEntity>, val summary: String? = null)

/**
 * The visible tasks by matter. Matters with the soonest due work come first;
 * a matter with nothing visible is not shown.
 */
internal fun groupByMatter(
    tasks: List<TaskEntity>,
    projects: List<ProjectEntity>,
): Pair<List<MatterGroup>, List<TaskEntity>> {
    val names = projects.associate { it.id to it.name }
    val (filed, loose) = tasks.partition { it.projectId != null && it.projectId in names }
    val groups = filed.groupBy { it.projectId!! }
        .map { (id, rows) ->
            val open = rows.filter { it.status == TaskStatus.ACTIVE }.map(TaskRanking::standing)
            MatterGroup(id, names.getValue(id), rows, Standing.matter(open, System.currentTimeMillis()))
        }
        .sortedWith(compareBy<MatterGroup> { g -> g.tasks.mapNotNull { it.dueAt }.minOrNull() ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
    return groups to loose
}

/** A destructive action the user can take back (spec 16: undo for every one). */
data class UndoAction(val message: String, val undo: suspend () -> Unit)

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
private const val KEY_CLEANUP_SNOOZE = "cleanup_snoozed_until"

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
        viewModelScope.launch {
            container.settingsRepository.settings
                .map { s -> s.ownNames.any { it.isNotBlank() } to s.aboutMe }
                .distinctUntilChanged()
                .collect { (names, about) ->
                    filters.update { it.copy(ownNamesSet = names, aboutMeSet = about.isNotBlank(), aboutMe = about) }
                }
        }
    }

    private val once = container.context.getSharedPreferences("taskmind_once", android.content.Context.MODE_PRIVATE)

    private val _cleanup = MutableStateFlow(CleanupState())
    val cleanup: StateFlow<CleanupState> = _cleanup.asStateFlow()

    private val filer = MatterFiler(container)

    /** Asks the model to suggest a matter for every open task without one. */
    fun suggestMatters() {
        if (_cleanup.value.busy) return
        val unfiled = state.value.unfiled
        if (unfiled.isEmpty()) return
        _cleanup.value = CleanupState(busy = true)
        viewModelScope.launch {
            _cleanup.value = when (val r = runCatching { filer.suggest(unfiled) }.getOrElse { MatterFiler.Result.Failed(it.message ?: "$it") }) {
                is MatterFiler.Result.Suggested -> CleanupState(suggestions = r.assignments)
                is MatterFiler.Result.Failed -> CleanupState(error = r.message)
            }
        }
    }

    fun applyMatters(accepted: List<Cleanup.Assignment>) {
        viewModelScope.launch {
            val filed = filer.apply(accepted)
            _cleanup.value = CleanupState(message = "Filed $filed task${if (filed == 1) "" else "s"} under matters.")
        }
    }

    /** Archives stale tasks: off the list, still under Done, reopenable. */
    fun dropStale(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.taskRepository.bulkSetStatus(ids, TaskStatus.ARCHIVED)
            _cleanup.value = _cleanup.value.copy(message = "Dropped ${ids.size}. They are under Done if you need one back.")
            _undo.value = UndoAction("Dropped ${ids.size} stale task${if (ids.size == 1) "" else "s"}") {
                container.taskRepository.bulkSetStatus(ids, TaskStatus.ACTIVE)
            }
        }
    }

    fun resetCleanup() {
        _cleanup.value = CleanupState()
    }

    /** Hides the tidy-up banner for a week. */
    fun snoozeCleanup() {
        val until = System.currentTimeMillis() + 7L * DAY_MILLIS
        runCatching { once.edit().putLong(KEY_CLEANUP_SNOOZE, until).apply() }
        filters.update { it.copy(cleanupSnoozedUntil = until) }
    }

    init {
        filters.update { it.copy(cleanupSnoozedUntil = runCatching { once.getLong(KEY_CLEANUP_SNOOZE, 0L) }.getOrDefault(0L)) }
        viewModelScope.launch {
            container.learningStore.rules.collect { rules ->
                filters.update { it.copy(approvedKeyPeople = Rules.keyPeople(rules)) }
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

        val filtered = TaskFilters.apply(
            tasks = tasks,
            view = current.view,
            query = current.query,
            projectId = current.projectId,
            tag = liveTag,
            sort = current.sort,
            now = now,
        )

        // Focus ranks what the filters left and keeps the top few; the rest
        // is one tap away under Agenda. Grouping does not apply: the order
        // is the point.
        val focus = TaskRanking.focus(filtered, current.aboutMe, now, approvedKeyPeople = current.approvedKeyPeople)
        val visible = when (current.view) {
            TaskView.FOCUS -> focus.map { it.first }
            else -> filtered
        }
        val reasons = if (current.view == TaskView.FOCUS) {
            focus.mapNotNull { (t, why) -> why?.let { t.id to it } }.toMap()
        } else {
            emptyMap()
        }
        val people = if (current.view == TaskView.PEOPLE) {
            Standing.people(filtered.map(TaskRanking::standing))
        } else {
            emptyList()
        }

        // Only when the mode is on: relating every task to every other is
        // quadratic, and paying for it while nobody is looking at the result
        // would slow down every edit in the app.
        val grouping = current.view != TaskView.FOCUS && current.view != TaskView.PEOPLE
        val (bundles, loose) = if (grouping && current.group == GroupMode.RELATED) {
            TaskBundles.grouped(visible)
        } else {
            emptyList<TaskBundles.Group>() to visible
        }
        val (matters, unmattered) = if (grouping && current.group == GroupMode.MATTER) {
            groupByMatter(visible, projects)
        } else {
            emptyList<MatterGroup>() to visible
        }

        current.copy(
            tag = liveTag,
            // The People view draws people; with nobody to draw, the empty
            // state says so rather than an empty list.
            tasks = if (current.view == TaskView.PEOPLE && people.isEmpty()) emptyList() else visible,
            reasons = reasons,
            laterCount = if (current.view == TaskView.FOCUS) (filtered.size - visible.size).coerceAtLeast(0) else 0,
            people = people,
            unfiled = TaskRanking.rankable(tasks).filter { it.projectId == null && it.sourceType != SourceType.MANUAL },
            stale = TaskRanking.rankable(tasks).let { open ->
                val byId = open.associateBy { it.id }
                Cleanup.stale(open.map(TaskRanking::input), now).mapNotNull { byId[it.id] }
            },
            bundles = bundles,
            unbundled = loose,
            matters = matters,
            unmattered = unmattered,
            counts = TaskFilters.counts(tasks, now) +
                mapOf(
                    TaskView.FOCUS to TaskRanking.focus(tasks, current.aboutMe, now, approvedKeyPeople = current.approvedKeyPeople).size,
                    TaskView.PEOPLE to 0,
                ),
            tagCloud = TaskFilters.tagCloud(scope, liveTag),
            allSubjects = TaskFilters.tagCloud(allTasks, limit = Int.MAX_VALUE)
                .filter { it.kind == AutoTagger.Kind.PERSON || it.kind == AutoTagger.Kind.GROUP }
                .map { it.value }
                .distinctBy { it.lowercase() },
            projects = projects,
            tags = tags,
            pendingReviewCount = reviewCount,
            week = if (current.view == TaskView.COMPLETED) {
                WeeklyReview.summarise(
                    allTasks.filter { it.parentTaskId == null && it.status != TaskStatus.DELETED }.map { it.toWeeklyItem() },
                    now,
                )
            } else {
                null
            },
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

    /**
     * Moves overdue tasks to today, keeping each one's time of day, with a
     * fresh automatic reminder. One write per task, one undo for all.
     *
     * An overdue pile is a list of decisions nobody has made; this makes the
     * commonest one ("still doing it, just today") a single tap.
     */
    fun moveToToday(tasks: List<TaskEntity>) {
        if (tasks.isEmpty()) return
        val now = System.currentTimeMillis()
        val todayStart = DateResolver.startOfDay(now)
        viewModelScope.launch {
            tasks.forEach { task ->
                val due = task.dueAt ?: return@forEach
                val moved = if (SmartReminder.isDateOnly(due)) {
                    SmartReminder.withoutTime(todayStart)
                } else {
                    val local = Instant.ofEpochMilli(due).atZone(DateResolver.IST)
                    SmartReminder.timed(todayStart, local.hour, local.minute)
                }
                container.taskRepository.update(task.copy(dueAt = moved, reminderAt = SmartReminder.forDue(moved, now)))
            }
            offerUndo("Moved ${tasks.size} to today") {
                tasks.forEach { container.taskRepository.update(it) }
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
