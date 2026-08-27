package com.taskmind.ai

import com.taskmind.core.LlmJson
import com.taskmind.core.LlmJson.bool
import com.taskmind.core.LlmJson.dbl
import com.taskmind.core.LlmJson.str
import com.taskmind.core.LlmJson.strList
import com.taskmind.core.Priority
import com.taskmind.core.PromptSet
import com.taskmind.core.Prompts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Spec 24: extraction sits behind an interface so a local (on-device) engine
 * can be added in v2 without touching the intake funnel.
 */
interface TaskExtractor {

    val originLabel: String

    suspend fun extractFromMessage(input: MessageInput): AiResult<MessageExtraction>

    suspend fun extractFromTranscript(input: TranscriptInput): AiResult<TranscriptExtraction>

    /** Spec 14.5. A no-op implementation is a legitimate one. */
    suspend fun verify(source: String, candidates: List<ExtractedTask>): AiResult<List<Verdict>>
}

data class MessageInput(
    val appLabel: String,
    val senderKey: String,
    val groupName: String?,
    val text: String,
    val occurredAt: Long,
    /** Carried so the model call can be traced back to the capture it came from. */
    val rawCaptureId: String? = null,
    val sourceLabel: String? = null,
)

data class TranscriptInput(
    val contactLabel: String,
    val transcript: String,
    val occurredAt: Long,
    val rawCaptureId: String? = null,
    val sourceLabel: String? = null,
)

data class ExtractedTask(
    val title: String,
    val evidence: String?,
    val priority: Priority,
    val dueDateRaw: String?,
    val notes: String?,
    val confidence: Double?,
    val assignedToMe: Boolean = true,
)

data class MessageExtraction(
    val isTask: Boolean,
    val reasoning: String?,
    val task: ExtractedTask?,
    val tokens: Int?,
)

data class TranscriptExtraction(
    val reasoning: String?,
    val summary: String?,
    val topics: List<String>,
    val tasks: List<ExtractedTask>,
    val tokens: Int?,
)

data class Verdict(
    val index: Int,
    val verdict: String,
    val title: String?,
    val dueDate: String?,
    val reason: String?,
) {
    val isDrop get() = verdict.equals("drop", ignoreCase = true)
    val isFix get() = verdict.equals("fix", ignoreCase = true)
}

/**
 * The cloud implementation. v1 is cloud-only by decision (spec 2): local models
 * were the single largest source of complexity and are deferred behind this
 * interface.
 */
class CloudTaskExtractor(
    private val llm: LlmClient,
    private val configProvider: suspend () -> LlmClient.Config,
    /**
     * The system prompts to send. Reads through to the user's overrides, so an
     * edit in Settings -> Prompts takes effect on the very next capture with no
     * restart.
     */
    private val promptProvider: suspend () -> PromptSet = { PromptSet.DEFAULT },
) : TaskExtractor {

    override val originLabel: String get() = cachedOrigin

    private var cachedOrigin: String = "cloud"

    override suspend fun extractFromMessage(input: MessageInput): AiResult<MessageExtraction> {
        val config = configProvider()
        cachedOrigin = "cloud:${config.model}"
        val user = Prompts.notificationUser(
            occurredAtMillis = input.occurredAt,
            appLabel = input.appLabel,
            senderKey = input.senderKey,
            groupName = input.groupName,
            messageText = input.text,
        )
        val result = llm.complete(
            config = config,
            systemPrompt = promptProvider().notificationSystem,
            userPrompt = user,
            maxTokens = 600,
            trace = TraceContext(
                kind = RecordedCall.KIND_MESSAGE,
                rawCaptureId = input.rawCaptureId,
                sourceLabel = input.sourceLabel ?: input.senderKey,
            ),
        )
        return when (result) {
            is AiResult.Ok -> parseMessage(result.value)
            is AiResult.HttpError -> result
            is AiResult.NetworkError -> result
            is AiResult.BadResponse -> result
        }
    }

    private fun parseMessage(completion: LlmClient.Completion): AiResult<MessageExtraction> {
        val obj = LlmJson.parseObject(completion.content)
            ?: return AiResult.BadResponse("model output was not JSON: ${completion.content.take(200)}")

        val isTask = obj.bool("isTask") ?: false
        val reasoning = obj.str("reasoning")
        if (!isTask) {
            return AiResult.Ok(MessageExtraction(false, reasoning, null, completion.totalTokens))
        }

        val title = obj.str("title")
            // A "task" with no title fails schema validation. Discard it -
            // do not try to salvage a title from the reasoning (spec 8.1).
            ?: return AiResult.Ok(MessageExtraction(false, reasoning ?: "no title returned", null, completion.totalTokens))

        val task = ExtractedTask(
            title = title,
            evidence = obj.str("evidence"),
            priority = Priority.parse(obj.str("priority")),
            dueDateRaw = obj.str("dueDate"),
            notes = obj.str("notes"),
            confidence = obj.dbl("confidence"),
        )
        return AiResult.Ok(MessageExtraction(true, reasoning, task, completion.totalTokens))
    }

    override suspend fun extractFromTranscript(input: TranscriptInput): AiResult<TranscriptExtraction> {
        val config = configProvider()
        cachedOrigin = "cloud:${config.model}"
        val user = Prompts.callUser(input.occurredAt, input.contactLabel, input.transcript)
        val result = llm.complete(
            config = config,
            systemPrompt = promptProvider().callSystem,
            userPrompt = user,
            maxTokens = 2000,
            trace = TraceContext(
                kind = RecordedCall.KIND_TRANSCRIPT,
                rawCaptureId = input.rawCaptureId,
                sourceLabel = input.sourceLabel ?: input.contactLabel,
            ),
        )
        return when (result) {
            is AiResult.Ok -> parseTranscript(result.value)
            is AiResult.HttpError -> result
            is AiResult.NetworkError -> result
            is AiResult.BadResponse -> result
        }
    }

    private fun parseTranscript(completion: LlmClient.Completion): AiResult<TranscriptExtraction> {
        val obj = LlmJson.parseObject(completion.content)
            ?: return AiResult.BadResponse("model output was not JSON: ${completion.content.take(200)}")

        val tasks = ((obj["tasks"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { element ->
            val t = element as? JsonObject ?: return@mapNotNull null
            val title = t.str("title") ?: return@mapNotNull null
            ExtractedTask(
                title = title,
                evidence = t.str("evidence"),
                priority = Priority.parse(t.str("priority")),
                dueDateRaw = t.str("dueDate"),
                notes = t.str("notes"),
                confidence = t.dbl("confidence"),
                assignedToMe = t.bool("assignedToMe") ?: true,
            )
        }

        return AiResult.Ok(
            TranscriptExtraction(
                reasoning = obj.str("reasoning"),
                summary = obj.str("summary"),
                topics = obj.strList("topics"),
                tasks = tasks,
                tokens = completion.totalTokens,
            ),
        )
    }

    override suspend fun verify(source: String, candidates: List<ExtractedTask>): AiResult<List<Verdict>> {
        if (candidates.isEmpty()) return AiResult.Ok(emptyList())
        val config = configProvider()
        val rendered = candidates.map { c ->
            buildString {
                append("title=").append(c.title)
                append(" | evidence=").append(c.evidence ?: "null")
                append(" | dueDate=").append(c.dueDateRaw ?: "null")
            }
        }
        val user = Prompts.verifyUser(source, rendered)
        val result = llm.complete(
            config = config,
            systemPrompt = promptProvider().verifySystem,
            userPrompt = user,
            maxTokens = 900,
            trace = TraceContext(kind = RecordedCall.KIND_VERIFY),
        )
        return when (result) {
            is AiResult.Ok -> {
                val obj = LlmJson.parseObject(result.value.content)
                    ?: return AiResult.BadResponse("verify output was not JSON")
                val verdicts = ((obj["verdicts"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { element ->
                    val v = element.jsonObject
                    val idx = v.dbl("index")?.toInt() ?: return@mapNotNull null
                    Verdict(
                        index = idx,
                        verdict = v.str("verdict") ?: "keep",
                        title = v.str("title"),
                        dueDate = v.str("dueDate"),
                        reason = v.str("reason"),
                    )
                }
                AiResult.Ok(verdicts)
            }
            is AiResult.HttpError -> result
            is AiResult.NetworkError -> result
            is AiResult.BadResponse -> result
        }
    }
}
