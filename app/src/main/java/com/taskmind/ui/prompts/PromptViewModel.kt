package com.taskmind.ui.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.PromptKind
import com.taskmind.core.PromptSet
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PromptUiState(
    val prompts: PromptSet = PromptSet.DEFAULT,
    val overridden: Set<PromptKind> = emptySet(),
    val editing: PromptKind? = null,
    val draft: String = "",
    val message: String? = null,
) {
    val draftDiffersFromSaved: Boolean
        get() {
            val kind = editing ?: return false
            return draft.trim() != kind.textIn(prompts).trim()
        }

    val draftIsDefault: Boolean
        get() {
            val kind = editing ?: return true
            return draft.trim() == kind.defaultText().trim()
        }
}

/**
 * Spec 14 makes the prompts the quality-critical stage of the whole app, which
 * is exactly why they should not be a secret. This screen shows what is really
 * being sent, lets it be rewritten, and can put any of them back.
 */
class PromptViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PromptUiState())
    val state: StateFlow<PromptUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(container.promptStore.prompts, container.promptStore.overridden) { prompts, overridden ->
                prompts to overridden
            }.collect { (prompts, overridden) ->
                _state.value = _state.value.copy(prompts = prompts, overridden = overridden)
            }
        }
    }

    fun edit(kind: PromptKind) {
        _state.value = _state.value.copy(editing = kind, draft = kind.textIn(_state.value.prompts))
    }

    fun updateDraft(text: String) {
        _state.value = _state.value.copy(draft = text)
    }

    fun closeEditor() {
        _state.value = _state.value.copy(editing = null, draft = "")
    }

    fun save() {
        val kind = _state.value.editing ?: return
        val text = _state.value.draft
        viewModelScope.launch {
            container.promptStore.set(kind, text)
            _state.value = _state.value.copy(
                editing = null,
                draft = "",
                message = if (text.trim() == kind.defaultText().trim()) {
                    "Back to the default — the override was removed, so future updates will apply."
                } else {
                    "Saved. The next capture uses it."
                },
            )
        }
    }

    /** Puts the draft back to the shipped text without saving it yet. */
    fun revertDraftToDefault() {
        val kind = _state.value.editing ?: return
        _state.value = _state.value.copy(draft = kind.defaultText())
    }

    fun reset(kind: PromptKind) {
        viewModelScope.launch {
            container.promptStore.reset(kind)
            _state.value = _state.value.copy(message = "${kind.title} is back to the default.")
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            container.promptStore.resetAll()
            _state.value = _state.value.copy(message = "All prompts are back to their defaults.")
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
