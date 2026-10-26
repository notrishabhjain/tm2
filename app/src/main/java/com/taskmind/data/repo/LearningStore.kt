package com.taskmind.data.repo

import android.content.Context
import com.taskmind.core.Feedback
import com.taskmind.core.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where what TaskMind learns from the user is kept: the feedback log and the
 * rules they decided on. Two small text files in the app's private storage,
 * one record per line. Never synced, never sent anywhere; only an approved
 * rule's one sentence goes out, with the prompt.
 *
 * Files rather than tables because neither needs querying - both are read
 * whole, a few hundred lines at most - and a file needs no migration.
 */
class LearningStore(context: Context) {

    private val feedbackFile = File(context.filesDir, "feedback.log")
    private val rulesFile = File(context.filesDir, "rules.txt")
    private val lock = Mutex()

    private val _rules = MutableStateFlow(readRules())
    val rules: StateFlow<List<Rules.Rule>> = _rules.asStateFlow()

    suspend fun record(event: Feedback.Event) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                feedbackFile.appendText(Feedback.encode(event) + "\n")
                // Trim now and then rather than on every write.
                if (feedbackFile.length() > TRIM_AT_BYTES) {
                    val kept = feedbackFile.readLines().takeLast(Feedback.MAX_EVENTS)
                    feedbackFile.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    suspend fun events(): List<Feedback.Event> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching { if (feedbackFile.exists()) feedbackFile.readLines().mapNotNull(Feedback::decode) else emptyList() }
                .getOrDefault(emptyList())
        }
    }

    /** Ideas the user has not decided on yet. */
    suspend fun ideas(now: Long = System.currentTimeMillis()): List<Rules.Idea> =
        Rules.ideas(events(), _rules.value, now)

    suspend fun decide(rule: Rules.Rule) = write(Rules.decide(_rules.value, rule))

    suspend fun remove(id: String) = write(Rules.remove(_rules.value, id))

    private suspend fun write(rules: List<Rules.Rule>) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching { rulesFile.writeText(rules.joinToString("\n", transform = Rules::encode)) }
            _rules.value = rules
        }
    }

    private fun readRules(): List<Rules.Rule> =
        runCatching { if (rulesFile.exists()) rulesFile.readLines().mapNotNull(Rules::decode) else emptyList() }
            .getOrDefault(emptyList())

    private companion object {
        /** About twice [Feedback.MAX_EVENTS] lines. */
        const val TRIM_AT_BYTES = 120_000L
    }
}
