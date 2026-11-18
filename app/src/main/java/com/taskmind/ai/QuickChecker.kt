package com.taskmind.ai

import com.taskmind.core.QuickCheck

/**
 * Runs the [QuickCheck] against a small model (v1.34). Returns null when the
 * check is off, has no model, or fails in any way: the caller then does the
 * full read, exactly as it would without the check.
 */
class QuickChecker(
    private val llm: LlmClient,
    /** The small model's config, or null when the check is off. */
    private val configProvider: suspend () -> LlmClient.Config?,
) {
    suspend fun check(
        sender: String,
        group: String?,
        userNames: Set<String>,
        openTasks: List<String>,
        text: String,
        rawCaptureId: String?,
        sourceLabel: String?,
    ): QuickCheck.Decision? {
        val config = configProvider() ?: return null
        val result = llm.complete(
            config = config,
            systemPrompt = QuickCheck.SYSTEM,
            userPrompt = QuickCheck.userPrompt(sender, group, userNames, openTasks, text),
            maxTokens = 60,
            trace = TraceContext(RecordedCall.KIND_QUICK_CHECK, rawCaptureId, sourceLabel),
            // A failed check costs nothing: the full read follows, with its own backup.
            allowBackup = false,
        )
        return (result as? AiResult.Ok)?.value?.let { QuickCheck.parse(it.content, it.totalTokens) }
    }
}
