package com.taskmind.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taskmind.core.TaskStatus
import com.taskmind.data.db.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /**
     * The ONLY insert path for tasks.
     *
     * Called by exactly one class, `RoomIntakePorts`, which is reachable only
     * from `IntakeFunnel`. `ArchitectureTest` fails the build if a second
     * caller appears - that is failure mode 1, where the call path wrote tasks
     * to a different table and produced zero visible tasks for weeks.
     *
     * IGNORE + a zero row-count return is the dedup mechanism: the unique index
     * on (sourceType, sourceRef, titleKey) decides, not the caller.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE status != 'DELETED' ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId AND status != 'DELETED' ORDER BY sortOrder ASC")
    fun observeSubTasks(parentId: String): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE status = :status")
    fun countByStatus(status: TaskStatus): Flow<Int>

    @Query("SELECT * FROM tasks WHERE rawCaptureId = :rawCaptureId")
    suspend fun byRawCapture(rawCaptureId: String): List<TaskEntity>

    @Query("UPDATE tasks SET status = :status, completedAt = :completedAt, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: TaskStatus, completedAt: Long?, now: Long)

    @Query("UPDATE tasks SET status = :status, completedAt = :completedAt, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setStatusBulk(ids: List<String>, status: TaskStatus, completedAt: Long?, now: Long)

    @Query("UPDATE tasks SET priority = :priority, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setPriorityBulk(ids: List<String>, priority: String, now: Long)

    @Query("UPDATE tasks SET reminderAt = :reminderAt, updatedAt = :now WHERE id = :id")
    suspend fun setReminder(id: String, reminderAt: Long?, now: Long)

    @Query("UPDATE tasks SET dueAt = :dueAt, updatedAt = :now WHERE id = :id")
    suspend fun setDueAt(id: String, dueAt: Long?, now: Long)

    @Query("SELECT * FROM tasks WHERE reminderAt IS NOT NULL AND reminderAt > :from AND status = 'ACTIVE' ORDER BY reminderAt ASC LIMIT 1")
    suspend fun nextReminder(from: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE reminderAt IS NOT NULL AND reminderAt <= :now AND status = 'ACTIVE'")
    suspend fun dueReminders(now: Long): List<TaskEntity>

    /** Hard delete. Only reachable from an explicit user action or an import. */
    @Query("DELETE FROM tasks WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<String>)

    @Query("SELECT * FROM tasks WHERE status != 'DELETED'")
    suspend fun allForExport(): List<TaskEntity>

    @Query("SELECT id FROM tasks WHERE status = 'DELETED'")
    suspend fun deletedIds(): List<String>

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun total(): Int

    /**
     * Spec 6.3: a retention purge clears raw text but must never delete the
     * task derived from it. The FK is ON DELETE SET NULL; this makes the
     * intent explicit for the manual "erase captured content" action too.
     */
    @Query("UPDATE tasks SET rawCaptureId = NULL WHERE rawCaptureId IN (:rawCaptureIds)")
    suspend fun detachRawCaptures(rawCaptureIds: List<String>)

    @Query("UPDATE tasks SET rawCaptureId = NULL")
    suspend fun detachAllRawCaptures()
}
