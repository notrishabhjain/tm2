package com.taskmind.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.core.ChatSearch
import com.taskmind.core.TaskStatus
import com.taskmind.core.WaitingOn
import com.taskmind.data.db.entity.TaskEntity
import com.taskmind.data.memory.MemoryStore
import com.taskmind.di.AppContainer
import com.taskmind.ui.tasks.TaskFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search my chats and a person's page (v1.32). Everything here reads the
 * memory on the phone; nothing is sent anywhere.
 */
class ChatsViewModel(private val container: AppContainer) : ViewModel() {

    data class Hit(
        val chat: String,
        val isGroup: Boolean,
        val at: Long,
        /** For a word match, the message; for a meaning match, the stretch. */
        val text: String,
        val byMeaning: Boolean,
        val sender: String? = null,
    )

    data class SearchState(
        val query: String = "",
        /** Only this chat, when opened from a person's page. */
        val chat: String? = null,
        val searching: Boolean = false,
        val hits: List<Hit> = emptyList(),
        val searched: Boolean = false,
        val memoryEmpty: Boolean = false,
        val meaningReady: Boolean = false,
        /** The conversation around the hit the user opened. */
        val open: Hit? = null,
        val context: List<MemoryStore.Message> = emptyList(),
    )

    data class PersonState(
        val name: String = "",
        val loading: Boolean = true,
        val youOwe: List<TaskEntity> = emptyList(),
        val theyOwe: List<TaskEntity> = emptyList(),
        val chats: List<MemoryStore.ChatInfo> = emptyList(),
        val chat: MemoryStore.ChatInfo? = null,
        val recent: List<MemoryStore.Message> = emptyList(),
    )

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _person = MutableStateFlow(PersonState())
    val person: StateFlow<PersonState> = _person.asStateFlow()

    private var searchJob: Job? = null

    /** The priority people and groups (v1.37), for the person page's switch. */
    val priorityChats: StateFlow<Set<String>> = container.settingsRepository.settings
        .map { it.priorityChats }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setPriority(name: String, on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setPriorityChat(name, on) }
    }

    // ----------------------------------------------------------- search

    fun startSearch(chat: String?) {
        if (_search.value.chat == chat && _search.value.searched) return
        _search.update { it.copy(chat = chat) }
        viewModelScope.launch {
            val empty = withContext(Dispatchers.IO) { container.memoryStore.stats().messages == 0 }
            _search.update { it.copy(memoryEmpty = empty, meaningReady = container.semanticIndex.modelReady()) }
        }
    }

    fun setQuery(q: String) {
        _search.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            run(q)
        }
    }

    fun submit() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { run(_search.value.query) }
    }

    private suspend fun run(q: String) {
        val terms = ChatSearch.terms(q)
        if (terms.isEmpty()) {
            _search.update { it.copy(hits = emptyList(), searched = false, searching = false) }
            return
        }
        _search.update { it.copy(searching = true) }
        val chat = _search.value.chat
        val words = withContext(Dispatchers.IO) {
            val found = runCatching { container.memoryStore.search(terms, 400, chat = chat) }.getOrDefault(emptyList())
            ChatSearch.rank(found, terms, { it.body }, { it.at }, limit = 60)
                .map { Hit(it.chat, it.isGroup, it.at, it.body, byMeaning = false, sender = it.sender) }
        }
        // Meaning matches only for the whole memory, and only for a question
        // of a few words: one word is better served by the word search.
        val meaning = if (chat == null && terms.size >= 2 && container.semanticIndex.modelReady()) {
            runCatching { container.semanticIndex.search(q, 12) }.getOrDefault(emptyList())
                .map { Hit(it.chat, it.isGroup, it.startAt, it.text, byMeaning = true) }
        } else {
            emptyList()
        }
        _search.update { it.copy(hits = meaning + words, searching = false, searched = true) }
    }

    fun openHit(hit: Hit) {
        _search.update { it.copy(open = hit, context = emptyList()) }
        viewModelScope.launch {
            val around = withContext(Dispatchers.IO) { container.memoryStore.around(hit.chat, hit.at, before = 10, after = 14) }
            _search.update { if (it.open == hit) it.copy(context = around) else it }
        }
    }

    fun closeHit() = _search.update { it.copy(open = null, context = emptyList()) }

    // ----------------------------------------------------------- person

    fun loadPerson(name: String) {
        if (_person.value.name == name && !_person.value.loading) return
        _person.value = PersonState(name = name)
        viewModelScope.launch {
            container.taskRepository.observeTasks().collect { all ->
                val theirs = all.filter { t ->
                    t.status == TaskStatus.ACTIVE && TaskFilters.hasTag(listOf(t), name)
                }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
                val waiting = theirs.filter { WaitingOn.matches(it.title, it.tags) }
                _person.update { it.copy(youOwe = theirs - waiting.toSet(), theyOwe = waiting) }
            }
        }
        viewModelScope.launch {
            val chats = withContext(Dispatchers.IO) {
                runCatching { container.memoryStore.chatsNamed(name, groups = false) }.getOrDefault(emptyList())
            }
            _person.update { it.copy(chats = chats, loading = false) }
            chats.firstOrNull()?.let { pickChat(it) }
        }
    }

    fun pickChat(chat: MemoryStore.ChatInfo) {
        _person.update { it.copy(chat = chat, recent = emptyList()) }
        viewModelScope.launch {
            val recent = withContext(Dispatchers.IO) { container.memoryStore.recent(chat.chat, 25) }
            _person.update { if (it.chat == chat) it.copy(recent = recent) else it }
        }
    }
}
