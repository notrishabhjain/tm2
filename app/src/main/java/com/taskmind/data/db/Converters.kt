package com.taskmind.data.db

import androidx.room.TypeConverter
import com.taskmind.core.CallDirection
import com.taskmind.core.CallState
import com.taskmind.core.CaptureState
import com.taskmind.core.LogLevel
import com.taskmind.core.Priority
import com.taskmind.core.ReviewState
import com.taskmind.core.SourceType
import com.taskmind.core.TaskStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Enums are stored as their names, not their ordinals: reordering an enum must
 * not silently reinterpret every row already written.
 */
class Converters {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())

    @TypeConverter
    fun tagsToJson(value: List<String>?): String = json.encodeToString(stringList, value ?: emptyList())

    @TypeConverter
    fun jsonToTags(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(stringList, value) }.getOrDefault(emptyList())
    }

    @TypeConverter fun priorityToString(v: Priority): String = v.name

    @TypeConverter
    fun stringToPriority(v: String): Priority =
        runCatching { Priority.valueOf(v) }.getOrDefault(Priority.MEDIUM)

    @TypeConverter fun statusToString(v: TaskStatus): String = v.name

    @TypeConverter
    fun stringToStatus(v: String): TaskStatus =
        runCatching { TaskStatus.valueOf(v) }.getOrDefault(TaskStatus.ACTIVE)

    @TypeConverter fun sourceTypeToString(v: SourceType): String = v.name

    @TypeConverter
    fun stringToSourceType(v: String): SourceType =
        runCatching { SourceType.valueOf(v) }.getOrDefault(SourceType.MANUAL)

    @TypeConverter fun captureStateToString(v: CaptureState): String = v.name

    @TypeConverter
    fun stringToCaptureState(v: String): CaptureState =
        runCatching { CaptureState.valueOf(v) }.getOrDefault(CaptureState.PENDING_EXTRACTION)

    @TypeConverter fun reviewStateToString(v: ReviewState): String = v.name

    @TypeConverter
    fun stringToReviewState(v: String): ReviewState =
        runCatching { ReviewState.valueOf(v) }.getOrDefault(ReviewState.PENDING)

    @TypeConverter fun callDirectionToString(v: CallDirection): String = v.name

    @TypeConverter
    fun stringToCallDirection(v: String): CallDirection =
        runCatching { CallDirection.valueOf(v) }.getOrDefault(CallDirection.UNKNOWN)

    @TypeConverter fun callStateToString(v: CallState): String = v.name

    @TypeConverter
    fun stringToCallState(v: String): CallState =
        runCatching { CallState.valueOf(v) }.getOrDefault(CallState.PENDING_RECORDING)

    @TypeConverter fun logLevelToString(v: LogLevel): String = v.name

    @TypeConverter
    fun stringToLogLevel(v: String): LogLevel =
        runCatching { LogLevel.valueOf(v) }.getOrDefault(LogLevel.INFO)
}
