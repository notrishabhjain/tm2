package com.taskmind.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.taskmind.core.CallState
import com.taskmind.core.CaptureState
import com.taskmind.core.ReviewState
import com.taskmind.data.db.entity.ActivityLogEntity
import com.taskmind.data.db.entity.CallRecordEntity
import com.taskmind.data.db.entity.FingerprintEntity
import com.taskmind.data.db.entity.InferenceCallEntity
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.data.db.entity.SeenPackageEntity
import com.taskmind.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RawCaptureDao {

    /**
     * IGNORE, not REPLACE.
     *
     * The unique index on (sourceType, sourceRef) is what actually stops two
     * simultaneous call-end triggers from double-capturing a call, and REPLACE
     * would defeat it: it deletes the existing row and inserts a new one with a
     * different id, orphaning every task and review item that pointed at it.
     * IGNORE returns -1 for the loser of the race, which the caller reads as
     * "someone else got there first" and resolves by re-reading.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(capture: RawCaptureEntity): Long

    @Update
    suspend fun update(capture: RawCaptureEntity)

    @Query("SELECT * FROM raw_captures WHERE id = :id")
    suspend fun byId(id: String): RawCaptureEntity?

    @Query("SELECT * FROM raw_captures WHERE sourceType = :sourceType AND sourceRef = :sourceRef LIMIT 1")
    suspend fun bySourceRef(sourceType: String, sourceRef: String): RawCaptureEntity?

    @Query("SELECT * FROM raw_captures WHERE audioPath = :audioPath LIMIT 1")
    suspend fun byAudioPath(audioPath: String): RawCaptureEntity?

    /** One query for a whole screenful, rather than one per listed file. */
    @Query("SELECT * FROM raw_captures WHERE audioPath IN (:audioPaths)")
    suspend fun byAudioPaths(audioPaths: List<String>): List<RawCaptureEntity>

    /**
     * The work queue. Oldest first, so a backlog drain after a key is finally
     * configured produces tasks in the order the commitments were made
     * (spec 8.4).
     */
    @Query(
        """
        SELECT * FROM raw_captures
        WHERE state = :state
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        ORDER BY occurredAt ASC
        LIMIT :limit
        """,
    )
    suspend fun dueForState(state: CaptureState, now: Long, limit: Int): List<RawCaptureEntity>

    @Query("SELECT * FROM raw_captures WHERE state = :state ORDER BY occurredAt ASC LIMIT :limit")
    suspend fun byState(state: CaptureState, limit: Int): List<RawCaptureEntity>

    @Query("SELECT state, COUNT(*) as count FROM raw_captures GROUP BY state")
    fun observeStateCounts(): Flow<List<CaptureStateCount>>

    @Query("UPDATE raw_captures SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: CaptureState)

    @Query(
        """
        UPDATE raw_captures
        SET state = :state, attemptCount = :attemptCount, lastError = :error, nextAttemptAt = :nextAttemptAt
        WHERE id = :id
        """,
    )
    suspend fun setRetry(
        id: String,
        state: CaptureState,
        attemptCount: Int,
        error: String?,
        nextAttemptAt: Long?,
    )

    @Query("UPDATE raw_captures SET rawText = :text, state = :state WHERE id = :id")
    suspend fun setTranscript(id: String, text: String, state: CaptureState)

    @Query("UPDATE raw_captures SET partialText = :partial, chunkIndex = :chunkIndex, chunkTotal = :chunkTotal WHERE id = :id")
    suspend fun setChunkProgress(id: String, partial: String?, chunkIndex: Int, chunkTotal: Int)

    /** Spec 9: everything held for budget is released after the next midnight. */
    @Query("UPDATE raw_captures SET state = :to, nextAttemptAt = NULL WHERE state = :from")
    suspend fun releaseState(from: CaptureState, to: CaptureState)

    @Query("SELECT COUNT(*) FROM raw_captures WHERE state = :state")
    suspend fun countByState(state: CaptureState): Int

    @Query("SELECT COUNT(*) FROM raw_captures")
    suspend fun total(): Int

    // -- retention (spec 6.3) ------------------------------------------------

    @Query("SELECT * FROM raw_captures WHERE capturedAt < :before AND state IN ('DONE', 'REJECTED', 'FAILED_PERMANENT')")
    suspend fun purgeable(before: Long): List<RawCaptureEntity>

    @Query("DELETE FROM raw_captures WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT * FROM raw_captures ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RawCaptureEntity>

    @Query("DELETE FROM raw_captures")
    suspend fun deleteAll()
}

data class CaptureStateCount(val state: CaptureState, val count: Int)

@Dao
interface ReviewItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ReviewItemEntity)

    @Query("SELECT * FROM review_items WHERE state = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<ReviewItemEntity>>

    @Query("SELECT COUNT(*) FROM review_items WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM review_items WHERE id = :id")
    suspend fun byId(id: String): ReviewItemEntity?

    @Query("UPDATE review_items SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: ReviewState)

    @Query("DELETE FROM review_items WHERE state != 'PENDING' AND createdAt < :before")
    suspend fun purgeResolved(before: Long)

    @Query("SELECT * FROM review_items WHERE rawCaptureId = :rawCaptureId")
    suspend fun byRawCapture(rawCaptureId: String): List<ReviewItemEntity>

    /** The test bench must not leave its samples in the review inbox. */
    @Query("DELETE FROM review_items WHERE rawCaptureId = :rawCaptureId")
    suspend fun deleteByRawCapture(rawCaptureId: String)

    @Query("DELETE FROM review_items")
    suspend fun deleteAll()
}

@Dao
interface CallRecordDao {

    @Upsert
    suspend fun upsert(record: CallRecordEntity)

    @Query("SELECT * FROM call_records WHERE id = :id")
    suspend fun byId(id: String): CallRecordEntity?

    @Query("SELECT * FROM call_records WHERE callLogId = :callLogId LIMIT 1")
    suspend fun byCallLogId(callLogId: Long): CallRecordEntity?

    @Query("SELECT * FROM call_records ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_records ORDER BY startTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CallRecordEntity>

    /**
     * Failure mode 4: `durationSeconds >= :minDuration` is FALSE for NULL in
     * SQL, so every call with an unknown duration was silently skipped. The
     * NULL branch is explicit and unit-tested.
     */
    @Query(
        """
        SELECT * FROM call_records
        WHERE state = :state
          AND (durationSeconds IS NULL OR durationSeconds >= :minDuration)
        ORDER BY startTime ASC
        """,
    )
    suspend fun pendingWithMinDuration(state: CallState, minDuration: Long): List<CallRecordEntity>

    @Query("SELECT * FROM call_records WHERE state = :state ORDER BY startTime ASC")
    suspend fun byState(state: CallState): List<CallRecordEntity>

    @Query("SELECT MAX(startTime) FROM call_records")
    suspend fun latestStartTime(): Long?

    @Query("UPDATE call_records SET state = :state, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: String, state: CallState, now: Long)

    @Query("DELETE FROM call_records")
    suspend fun deleteAll()
}

@Dao
interface FingerprintDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(fingerprint: FingerprintEntity): Long

    @Query("SELECT COUNT(*) FROM fingerprints WHERE hash = :hash AND seenAt > :after")
    suspend fun seenSince(hash: String, after: Long): Int

    @Query("DELETE FROM fingerprints WHERE seenAt < :before")
    suspend fun purgeOlderThan(before: Long)

    /**
     * Used by the self-test, which must be repeatable.
     *
     * Without this the synthetic message's fingerprint outlived the test that
     * created it, so the second run was rejected as a duplicate before any
     * model was called - the diagnostic reported the pipeline broken when what
     * was broken was the diagnostic.
     */
    @Query("DELETE FROM fingerprints WHERE hash = :hash")
    suspend fun deleteByHash(hash: String)

    @Query("DELETE FROM fingerprints")
    suspend fun deleteAll()
}

@Dao
interface ActivityLogDao {

    @Insert
    suspend fun insert(entry: ActivityLogEntity)

    /** Spec 6.2: keep the newest 500, trim on insert. */
    @Query("DELETE FROM activity_log WHERE id NOT IN (SELECT id FROM activity_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("SELECT * FROM activity_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_log ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActivityLogEntity>

    @Query("DELETE FROM activity_log")
    suspend fun deleteAll()
}

@Dao
interface ProjectDao {

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY sortOrder ASC, name ASC")
    suspend fun all(): List<ProjectEntity>

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity)

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("DELETE FROM tags WHERE name = :name")
    suspend fun delete(name: String)
}

@Dao
interface SeenPackageDao {

    @Query(
        """
        INSERT INTO seen_packages (packageName, label, firstSeenAt, lastSeenAt, notificationCount)
        VALUES (:packageName, :label, :now, :now, 1)
        ON CONFLICT(packageName) DO UPDATE SET
            lastSeenAt = :now,
            notificationCount = notificationCount + 1,
            label = COALESCE(:label, label)
        """,
    )
    suspend fun record(packageName: String, label: String?, now: Long)

    @Query("SELECT * FROM seen_packages ORDER BY notificationCount DESC, packageName ASC")
    fun observeAll(): Flow<List<SeenPackageEntity>>

    @Query("DELETE FROM seen_packages")
    suspend fun deleteAll()
}

@Dao
interface InferenceCallDao {

    @Insert
    suspend fun insert(call: InferenceCallEntity): Long

    /** Kept small: each row can hold several kilobytes of prompt and reply. */
    @Query("DELETE FROM inference_calls WHERE id NOT IN (SELECT id FROM inference_calls ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("SELECT * FROM inference_calls ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<InferenceCallEntity>>

    @Query("SELECT * FROM inference_calls ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<InferenceCallEntity>

    @Query("SELECT * FROM inference_calls WHERE rawCaptureId = :rawCaptureId ORDER BY id ASC")
    suspend fun forCapture(rawCaptureId: String): List<InferenceCallEntity>

    @Query("SELECT COUNT(*) FROM inference_calls WHERE ok = 0 AND startedAt > :since")
    suspend fun failuresSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM inference_calls")
    suspend fun total(): Int

    @Query("DELETE FROM inference_calls")
    suspend fun deleteAll()
}
