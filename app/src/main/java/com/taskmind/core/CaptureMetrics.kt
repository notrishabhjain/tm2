package com.taskmind.core

import kotlin.math.roundToInt

/**
 * Is capture worth it? Three numbers, from data the phone already keeps.
 *
 *  - Kept rate: of the tasks TaskMind created for you a week or more ago, how
 *    many you kept (finished, or still open) rather than deleted or marked
 *    "Not mine". The single best measure of whether captured tasks are worth
 *    having. Only tasks at least [SETTLE_DAYS] old count, so a task created
 *    this morning is not counted as "kept" before you have had a chance to
 *    delete it.
 *  - New per day: how many captured tasks reach the list, over the last
 *    [WINDOW_DAYS] days.
 *  - Digest drop rate: of the maybes you decided on, how many you dropped.
 *    Mostly dropping means the bar is about right; mostly keeping means it is
 *    too high and real tasks are waiting in the digest.
 *
 * Pure and tested. The Health screen shows these next to the same numbers
 * taken the first time v1.21 ran, which is how a change is judged.
 */
object CaptureMetrics {

    const val SETTLE_DAYS = 7
    const val WINDOW_DAYS = 30
    private const val DAY = 24L * 60 * 60 * 1000

    data class Task(val captured: Boolean, val status: TaskStatus, val createdAt: Long)
    data class Maybe(val state: ReviewState, val createdAt: Long)

    data class Result(
        /** 0-100, or null when fewer than [MIN_SAMPLE] tasks are old enough to judge. */
        val keptPercent: Int?,
        val newPerDay: Double,
        /** 0-100, or null when too few maybes have been decided. */
        val droppedPercent: Int?,
        val judged: Int,
    ) {
        fun describe(): String = listOf(
            keptPercent?.let { "$it% kept" } ?: "kept: not enough yet",
            "%.1f new a day".format(newPerDay),
            droppedPercent?.let { "$it% of maybes dropped" } ?: "maybes: not enough yet",
        ).joinToString(" · ")
    }

    const val MIN_SAMPLE = 5

    fun compute(tasks: List<Task>, maybes: List<Maybe>, now: Long): Result {
        val windowStart = now - WINDOW_DAYS * DAY
        val settled = tasks.filter {
            it.captured && it.createdAt >= windowStart && it.createdAt <= now - SETTLE_DAYS * DAY
        }
        val kept = settled.count { it.status != TaskStatus.DELETED }
        val keptPercent = if (settled.size >= MIN_SAMPLE) (100.0 * kept / settled.size).roundToInt() else null

        val recent = tasks.count { it.captured && it.createdAt >= windowStart }
        val newPerDay = recent.toDouble() / WINDOW_DAYS

        val decided = maybes.filter { it.createdAt >= windowStart && it.state != ReviewState.PENDING }
        val dropped = decided.count { it.state == ReviewState.DISMISSED }
        val droppedPercent = if (decided.size >= MIN_SAMPLE) (100.0 * dropped / decided.size).roundToInt() else null

        return Result(keptPercent, newPerDay, droppedPercent, settled.size)
    }

    /** What counts as captured: anything the app created, not something you typed. */
    fun isCaptured(source: SourceType): Boolean = source != SourceType.MANUAL
}
