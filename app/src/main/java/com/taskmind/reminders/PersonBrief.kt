package com.taskmind.reminders

import com.taskmind.contacts.ContactLookup
import com.taskmind.core.Briefing
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.core.TaskStatus
import com.taskmind.core.WaitingOn
import com.taskmind.di.AppContainer
import com.taskmind.ui.tasks.TaskFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A briefing on one person (v1.33), from the task list and the memory. Used
 * while they call and before a meeting with them. Nothing is sent anywhere.
 */
object PersonBrief {

    suspend fun lines(container: AppContainer, person: String, now: Long = System.currentTimeMillis()): List<String> {
        val tasks = container.taskRepository.observeTasks().first()
            .filter { it.status == TaskStatus.ACTIVE && it.parentTaskId == null && TaskFilters.hasTag(listOf(it), person) }
        val (theirs, mine) = tasks.partition { WaitingOn.matches(it.title, it.tags) }
        val last = withContext(Dispatchers.IO) {
            runCatching {
                val memory = container.memoryStore
                val chat = memory.chatsNamed(person, groups = false, limit = 1).firstOrNull()
                chat?.let { memory.recent(it.chat, 1).firstOrNull() }
            }.getOrNull()
        }
        return Briefing.lines(
            person = person,
            youOwe = mine.map { Briefing.Open(it.title, it.dueAt) },
            theyOwe = theirs.map { Briefing.Open(it.title, it.dueAt) },
            last = last?.let { Briefing.LastSaid(it.at, it.fromMe, it.body) },
            now = now,
        )
    }

    // ------------------------------------------------------------- calls

    @Volatile private var lastRing: Pair<String, Long>? = null

    /** An incoming call from [number]: brief the user if they know the caller. */
    suspend fun onRinging(container: AppContainer, number: String) {
        if (!container.settingsRepository.current().callBriefing) return
        val now = System.currentTimeMillis()
        // The system announces a ringing call more than once.
        lastRing?.let { (n, at) -> if (n == number && now - at < 60_000) return }
        lastRing = number to now
        val name = ContactLookup.nameFor(container.context, number) ?: return
        val lines = runCatching { lines(container, name, now) }.getOrElse {
            container.logger.write(Stage.CALL, LogLevel.WARN, "call briefing failed", it.toString())
            emptyList()
        }
        if (lines.isNotEmpty()) container.notifier.postCallBrief(name, lines)
    }
}
