package com.taskmind.ui.twin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.DateResolver
import com.taskmind.core.FollowUp
import com.taskmind.core.Journal
import com.taskmind.core.TaskStatus
import com.taskmind.core.Twin
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.data.repo.TwinBrain
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One change the twin proposed, shown with a tick box. */
data class Proposal(val action: Twin.Action, val label: String, val checked: Boolean = true)

data class TwinUiState(
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** What was asked, shown above the answer. */
    val asked: String? = null,
    val answer: String? = null,
    val note: String? = null,
    val answerTasks: List<TaskEntity> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    /** One line for the snackbar, with [undoEntry] when it can be undone. */
    val message: String? = null,
    val undoEntry: String? = null,
    val overview: TwinBrain.Overview? = null,
    val draft: TwinBrain.Draft? = null,
    val drafting: Boolean = false,
    val recall: Boolean = true,
)

/**
 * The twin's screen: ask anything, plan the day or the week, apply what it
 * proposes, chase the people who owe you, and undo anything it did.
 */
class TwinViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(TwinUiState())
    val state: StateFlow<TwinUiState> = _state.asStateFlow()

    /** The journal, newest first. */
    val history: StateFlow<List<Journal.Entry>> = container.twinJournal.entries
        .map { it.take(HISTORY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
        viewModelScope.launch { _state.value = _state.value.copy(recall = container.twinStore.current().recall) }
    }

    fun refresh() {
        viewModelScope.launch {
            val overview = runCatching { container.twinBrain.overview() }.getOrNull()
            _state.value = _state.value.copy(overview = overview)
        }
    }

    fun setInput(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    /** Spoken words are added to what is typed, not put in place of it. */
    fun appendSpoken(text: String) {
        val current = _state.value.input.trim()
        setInput(if (current.isEmpty()) text else "$current $text")
    }

    fun ask(text: String = _state.value.input) = submit(text.trim(), null)
    fun planDay() = submit(Twin.PLAN_DAY, TwinBrain.Plan.DAY)
    fun planWeek() = submit(Twin.PLAN_WEEK, TwinBrain.Plan.WEEK)

    private fun submit(request: String, plan: TwinBrain.Plan?) {
        if (request.isEmpty() || _state.value.busy) return
        _state.value = _state.value.copy(
            busy = true, error = null, asked = request, answer = null, note = null,
            answerTasks = emptyList(), proposals = emptyList(),
        )
        viewModelScope.launch {
            val next = try {
                when (val answer = container.twinBrain.ask(request, plan)) {
                    is TwinBrain.Answer.Failed -> _state.value.copy(busy = false, error = answer.message)
                    is TwinBrain.Answer.Ok -> {
                        val byId = container.taskRepository.observeTasks().first().associateBy { it.id }
                        val now = System.currentTimeMillis()
                        val title = { id: String -> byId[id]?.title }
                        _state.value.copy(
                            busy = false,
                            input = if (plan == null) "" else _state.value.input,
                            answer = answer.reply.answer,
                            note = answer.note,
                            answerTasks = answer.reply.taskIds.mapNotNull { byId[it] },
                            proposals = answer.reply.actions.map { Proposal(it, Twin.describe(it, title, now)) },
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.value.copy(busy = false, error = "Something went wrong: ${t.message ?: t}")
            }
            _state.value = next
        }
    }

    fun toggle(index: Int) {
        val list = _state.value.proposals.toMutableList()
        val p = list.getOrNull(index) ?: return
        list[index] = p.copy(checked = !p.checked)
        _state.value = _state.value.copy(proposals = list)
    }

    /** Applies the ticked changes as one undoable step. A ticked nudge opens its draft. */
    fun applyChosen() {
        val chosen = _state.value.proposals.filter { it.checked }
        if (chosen.isEmpty()) return
        val nudge = chosen.map { it.action }.filterIsInstance<Twin.Action.Nudge>().firstOrNull()
        val changes = chosen.map { it.action }.filter { it !is Twin.Action.Nudge }
        viewModelScope.launch {
            if (changes.isNotEmpty()) {
                val summary = summary(changes.size)
                val applied = container.twinActions.apply(changes, summary)
                _state.value = _state.value.copy(
                    proposals = emptyList(),
                    message = when {
                        applied.failed > 0 -> "Applied ${applied.count}; ${applied.failed} could not be (the task changed or is gone)."
                        else -> if (applied.count == 1) "Done - 1 change." else "Done - ${applied.count} changes."
                    },
                    undoEntry = applied.entryId,
                )
            } else {
                _state.value = _state.value.copy(proposals = emptyList())
            }
            nudge?.let { openNudge(it.taskId, it.text) }
            refresh()
        }
    }

    private fun summary(count: Int): String {
        val asked = _state.value.asked?.take(60)?.let { "\"$it\"" } ?: "the twin"
        return (if (count == 1) "1 change" else "$count changes") + " from $asked"
    }

    fun undo(entryId: String) {
        viewModelScope.launch {
            val text = runCatching { container.twinActions.undo(entryId) }.getOrElse { "Could not undo: ${it.message}" }
            _state.value = _state.value.copy(message = text, undoEntry = null)
            refresh()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, undoEntry = null)
    }

    fun clearAnswer() {
        _state.value = _state.value.copy(asked = null, answer = null, note = null, answerTasks = emptyList(), proposals = emptyList(), error = null)
    }

    fun setRecall(on: Boolean) {
        _state.value = _state.value.copy(recall = on)
        viewModelScope.launch { container.twinStore.setRecall(on) }
    }

    // --------------------------------------------------------------- nudges

    fun openNudge(taskId: String, proposed: String? = null) {
        if (_state.value.drafting) return
        _state.value = _state.value.copy(drafting = true)
        viewModelScope.launch {
            val draft = runCatching { container.twinBrain.draft(taskId, proposed) }.getOrNull()
            _state.value = _state.value.copy(
                drafting = false,
                draft = draft,
                message = if (draft == null) "That task is gone." else _state.value.message,
            )
        }
    }

    fun setDraftText(text: String) {
        val d = _state.value.draft ?: return
        _state.value = _state.value.copy(draft = d.copy(text = text))
    }

    fun closeDraft() {
        _state.value = _state.value.copy(draft = null)
    }

    /**
     * The user sent it (or at least opened it to send). Remembered so the
     * same person is not suggested again for a few days, and noted on the task.
     */
    fun nudged(via: String) {
        val d = _state.value.draft ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            container.twinStore.markNudged(d.taskId, now)
            container.taskRepository.byId(d.taskId)?.takeIf { it.status == TaskStatus.ACTIVE }?.let { task ->
                val line = "Nudged ${d.person} ${Instant.ofEpochMilli(now).atZone(DateResolver.IST).format(DAY)} ($via)"
                container.taskRepository.update(task.copy(notes = FollowUp.appendLine(task.notes, line)))
            }
            _state.value = _state.value.copy(draft = null, message = "Noted. ${d.person} will not be suggested again for a few days.")
            refresh()
        }
    }

    companion object {
        private const val HISTORY = 12
        private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    }
}
