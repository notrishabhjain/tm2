package com.taskmind.ui.braindump

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.BrainDump
import com.taskmind.data.repo.BrainDumper
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrainDumpState(
    val text: String = "",
    val busy: Boolean = false,
    /** Null while writing; the split items once sorted. */
    val items: List<BrainDump.Item>? = null,
    /** Indexes the user unticked. */
    val skipped: Set<Int> = emptySet(),
    val note: String? = null,
    /** Set once the items are added; the sheet closes on it. */
    val added: Int? = null,
    val areas: List<String> = emptyList(),
)

/** Say or paste everything, see it split, add what you want. */
class BrainDumpViewModel(private val container: AppContainer) : ViewModel() {

    private val dumper = BrainDumper(container)

    private val _state = MutableStateFlow(BrainDumpState())
    val state: StateFlow<BrainDumpState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { s -> _state.value = _state.value.copy(areas = s.areas) }
        }
    }

    /** Starts fresh, optionally with text handed in (a share, a paste). */
    fun reset(text: String = "") {
        _state.value = BrainDumpState(text = text, areas = _state.value.areas)
    }

    fun setText(text: String) {
        _state.value = _state.value.copy(text = text.take(BrainDump.MAX_CHARS))
    }

    /** Dictation appends, so several goes build one dump. */
    fun appendSpoken(spoken: String) {
        val current = _state.value.text.trimEnd()
        setText(if (current.isEmpty()) spoken else "$current\n$spoken")
    }

    fun sortItOut() {
        val text = _state.value.text
        if (text.isBlank() || _state.value.busy) return
        _state.value = _state.value.copy(busy = true, note = null)
        viewModelScope.launch {
            val split = runCatching { dumper.split(text) }.getOrElse {
                BrainDumper.Split(BrainDump.fallback(text, System.currentTimeMillis()), false, "Something went wrong, so the list was split line by line.")
            }
            _state.value = _state.value.copy(busy = false, items = split.items, skipped = emptySet(), note = split.note)
        }
    }

    fun toggle(index: Int) {
        val s = _state.value.skipped
        _state.value = _state.value.copy(skipped = if (index in s) s - index else s + index)
    }

    /** Changes one item's area, or clears it. */
    fun setArea(index: Int, area: String?) {
        val items = _state.value.items ?: return
        _state.value = _state.value.copy(items = items.mapIndexed { i, it -> if (i == index) it.copy(area = area) else it })
    }

    fun toggleToday(index: Int) {
        val items = _state.value.items ?: return
        _state.value = _state.value.copy(items = items.mapIndexed { i, it -> if (i == index) it.copy(today = !it.today) else it })
    }

    fun backToText() {
        _state.value = _state.value.copy(items = null, skipped = emptySet())
    }

    fun add() {
        val s = _state.value
        val chosen = s.items.orEmpty().filterIndexed { i, _ -> i !in s.skipped }
        if (chosen.isEmpty() || s.busy) return
        _state.value = s.copy(busy = true)
        viewModelScope.launch {
            val n = runCatching { dumper.add(chosen) }.getOrDefault(0)
            _state.value = _state.value.copy(busy = false, added = n)
        }
    }
}
