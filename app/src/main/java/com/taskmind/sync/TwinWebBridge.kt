package com.taskmind.sync

import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.core.TwinWeb
import com.taskmind.data.repo.TwinBrain
import com.taskmind.di.AppContainer
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/**
 * The Twin on the web page (v1.36).
 *
 * The page writes a question into `twin_questions`. The phone - the only
 * place the memory and the AI key live - picks it up, answers it exactly as
 * the Twin tab would, and writes the answer back with the snippets it was
 * given. Proposed changes are counted but not applied: those wait for the
 * user on the phone. Rows older than 30 days are deleted.
 */
class TwinWebBridge(private val container: AppContainer) {

    private val api = SupabaseApi(container.httpClient)
    private val running = Mutex()
    @Volatile private var lastCleanup = 0L
    @Volatile private var missingTable = false

    /** One look for new questions. Safe to call often; does nothing when sync is off. */
    suspend fun poll(): Int {
        if (missingTable || !running.tryLock()) return 0
        try {
            val store = SyncStore(container.context)
            val state = store.current()
            if (!state.enabled || !state.configured) return 0
            val secrets = SyncSecrets(container.context)
            if (!secrets.signedIn()) return 0
            val url = state.projectUrl
            val token = SyncAuth.token(api, secrets, url) ?: return 0

            val now = System.currentTimeMillis()
            if (now - lastCleanup > CLEANUP_EVERY) {
                val out = api.deleteWhere(url, secrets.anonKey, token, TwinWeb.TABLE, TwinWeb.expiredFilter(now))
                if (out is SupabaseApi.Outcome.Failed && out.message.startsWith("HTTP 404")) {
                    missingTable = true
                    return 0
                }
                lastCleanup = now
            }

            val rows = when (val out = api.select(url, secrets.anonKey, token, TwinWeb.TABLE, "status=eq.asked&order=asked_at.asc", 3)) {
                is SupabaseApi.Outcome.Ok -> out.value
                is SupabaseApi.Outcome.Failed -> {
                    if (out.message.startsWith("HTTP 404")) missingTable = true
                    return 0
                }
            }
            var answered = 0
            for (row in rows) {
                val obj = row as? JsonObject ?: continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val question = obj["question"]?.jsonPrimitive?.contentOrNull?.take(TwinWeb.MAX_QUESTION)?.trim().orEmpty()
                // Claim it, so a second phone or a second poll does not answer it too.
                val claimed = api.patch(url, secrets.anonKey, token, TwinWeb.TABLE, id, buildJsonObject { put("status", "answering") }, guard = "status=eq.asked")
                if (claimed !is SupabaseApi.Outcome.Ok || claimed.value == 0) continue
                val fields = if (question.isEmpty()) failed("The question was empty.") else answer(question)
                api.patch(url, secrets.anonKey, token, TwinWeb.TABLE, id, fields)
                answered++
            }
            if (answered > 0) container.logger.write(Stage.SYSTEM, LogLevel.INFO, "Twin on the web: answered $answered question(s)")
            return answered
        } catch (t: Throwable) {
            container.logger.write(Stage.SYSTEM, LogLevel.WARN, "Twin on the web: poll failed", t.toString())
            return 0
        } finally {
            running.unlock()
        }
    }

    private suspend fun answer(question: String): JsonObject = when (val a = container.twinBrain.ask(question)) {
        is TwinBrain.Answer.Ok -> {
            val titles = a.reply.taskIds.mapNotNull { id -> runCatching { container.taskRepository.byId(id)?.title }.getOrNull() }
            buildJsonObject {
                put("status", "answered")
                put("answer", a.reply.answer)
                put("note", a.note ?: if (a.usedModel) null else "Made on the phone without the AI.")
                put("proposed_changes", a.reply.actions.size)
                putJsonArray("task_titles") { titles.take(20).forEach { add(it) } }
                put(
                    "snippets",
                    buildJsonArray {
                        TwinWeb.snippets(a.memory).forEach { s ->
                            add(
                                buildJsonObject {
                                    put("at", Instant.ofEpochMilli(s.at).toString())
                                    put("from", s.from)
                                    put("text", s.text)
                                },
                            )
                        }
                    },
                )
                put("answered_at", Instant.now().toString())
            }
        }
        is TwinBrain.Answer.Failed -> failed(a.message)
    }

    private fun failed(message: String) = buildJsonObject {
        put("status", "failed")
        put("note", message)
        put("answered_at", Instant.now().toString())
    }

    companion object {
        private const val CLEANUP_EVERY = 6L * 60 * 60 * 1000
    }
}
