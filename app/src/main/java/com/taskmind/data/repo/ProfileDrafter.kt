package com.taskmind.data.repo

import com.taskmind.ai.AiResult
import com.taskmind.ai.TraceContext
import com.taskmind.ai.errorText
import com.taskmind.core.LlmJson
import com.taskmind.core.LlmJson.str
import com.taskmind.core.ProfileSample
import com.taskmind.core.Prompts
import com.taskmind.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drafts "About you" from the user's own chats (v1.32).
 *
 * Two steps, on purpose. [prepare] builds the sample on the phone and sends
 * nothing; the user reads exactly what would go. Only [send], after they tap
 * it, makes the one AI call. The draft comes back for them to edit and save;
 * nothing is saved on its own.
 */
class ProfileDrafter(private val container: AppContainer) {

    data class Prepared(val sample: ProfileSample.Sample, val provider: String)

    sealed interface Result {
        data class Draft(val text: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun prepare(months: Int = 6): Prepared? = withContext(Dispatchers.IO) {
        val memory = container.memoryStore
        val since = System.currentTimeMillis() - months * 30L * 24 * 60 * 60 * 1000
        val chats = memory.busiestChats(since, CHATS)
        if (chats.isEmpty()) return@withContext null
        val byChat = chats.map { chat ->
            memory.recent(chat, PER_CHAT, fromMeOnly = true, since = since)
                .map { ProfileSample.Line(it.chat, it.isGroup, it.at, it.body) }
        }
        val sample = ProfileSample.build(byChat)
        if (sample.messages == 0) return@withContext null
        val config = container.llmConfig()
        Prepared(sample, "${config.model} at ${config.baseUrl.removePrefix("https://")}")
    }

    suspend fun send(prepared: Prepared): Result {
        val settings = container.settingsRepository.current()
        if (!settings.cloudConsent) return Result.Failed("Cloud processing is off (Settings, Privacy and security).")
        if (!container.secretStore.hasLlmKey()) return Result.Failed("No AI key is set.")
        val result = container.llmClient.complete(
            config = container.llmConfig(),
            systemPrompt = ProfileSample.SYSTEM_PROMPT,
            userPrompt = "My messages, grouped by chat:\n\n" + prepared.sample.text,
            maxTokens = 1500,
            trace = TraceContext(KIND_PROFILE),
        )
        return when (result) {
            is AiResult.Ok -> {
                val about = LlmJson.parseObject(result.value.content)?.str("about")?.trim()
                if (about.isNullOrBlank()) {
                    Result.Failed("The AI's reply had no draft in it. Try again, or try another model.")
                } else {
                    Result.Draft(about.take(Prompts.MAX_ABOUT_ME))
                }
            }
            else -> Result.Failed(result.errorText ?: "The AI could not be reached.")
        }
    }

    companion object {
        const val KIND_PROFILE = "profile-draft"
        private const val CHATS = 25
        private const val PER_CHAT = 60
    }
}
