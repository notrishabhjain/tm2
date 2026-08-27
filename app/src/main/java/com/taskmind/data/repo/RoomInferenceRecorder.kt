package com.taskmind.data.repo

import com.taskmind.ai.InferenceRecorder
import com.taskmind.ai.RecordedCall
import com.taskmind.core.ProviderDiagnosis
import com.taskmind.data.db.dao.InferenceCallDao
import com.taskmind.data.db.entity.InferenceCallEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists the model-call trace, and attaches a plain-language diagnosis to
 * failures at the moment they happen.
 *
 * Diagnosing here rather than in the UI means the explanation is stored with
 * the failure: the reason a call failed is still there tomorrow, and it travels
 * with the exported log.
 */
class RoomInferenceRecorder(private val dao: InferenceCallDao) : InferenceRecorder {

    private val trimLock = Mutex()
    private var writesSinceTrim = 0

    override suspend fun record(call: RecordedCall) {
        val diagnosis = if (!call.ok && call.httpStatus != null && call.errorText != null) {
            ProviderDiagnosis.diagnose(call.model, call.httpStatus, call.errorText)
        } else {
            null
        }

        runCatching {
            dao.insert(
                InferenceCallEntity(
                    startedAt = call.startedAt,
                    durationMillis = call.durationMillis,
                    kind = call.kind,
                    baseUrl = call.baseUrl,
                    model = call.model,
                    systemPrompt = call.systemPrompt,
                    userPrompt = call.userPrompt,
                    httpStatus = call.httpStatus,
                    ok = call.ok,
                    responseBody = call.responseBody,
                    totalTokens = call.totalTokens,
                    errorText = call.errorText,
                    diagnosis = diagnosis,
                    rawCaptureId = call.rawCaptureId,
                    sourceLabel = call.sourceLabel,
                ),
            )
            trimIfNeeded()
        }
    }

    private suspend fun trimIfNeeded() {
        trimLock.withLock {
            writesSinceTrim++
            if (writesSinceTrim < TRIM_EVERY) return
            writesSinceTrim = 0
        }
        runCatching { dao.trimTo(KEEP) }
    }

    companion object {
        /** Each row holds several kilobytes of prompt and reply. */
        const val KEEP = 100
        private const val TRIM_EVERY = 10
    }
}
