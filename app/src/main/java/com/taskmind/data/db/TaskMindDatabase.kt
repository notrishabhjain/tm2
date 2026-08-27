package com.taskmind.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.taskmind.data.db.dao.ActivityLogDao
import com.taskmind.data.db.dao.CallRecordDao
import com.taskmind.data.db.dao.FingerprintDao
import com.taskmind.data.db.dao.ProjectDao
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.dao.ReviewItemDao
import com.taskmind.data.db.dao.SeenPackageDao
import com.taskmind.data.db.dao.TagDao
import com.taskmind.data.db.dao.TaskDao
import com.taskmind.data.db.entity.ActivityLogEntity
import com.taskmind.data.db.entity.CallRecordEntity
import com.taskmind.data.db.entity.FingerprintEntity
import com.taskmind.data.db.entity.ProjectEntity
import com.taskmind.data.db.entity.RawCaptureEntity
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.data.db.entity.SeenPackageEntity
import com.taskmind.data.db.entity.TagEntity
import com.taskmind.data.db.entity.TaskEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Database(
    entities = [
        TaskEntity::class,
        RawCaptureEntity::class,
        ReviewItemEntity::class,
        CallRecordEntity::class,
        FingerprintEntity::class,
        ActivityLogEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        SeenPackageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TaskMindDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun callRecordDao(): CallRecordDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun projectDao(): ProjectDao
    abstract fun tagDao(): TagDao
    abstract fun seenPackageDao(): SeenPackageDao

    companion object {
        const val NAME = "taskmind.db"

        /**
         * Spec 17.4 / failure mode 6: corruption recovery must NEVER delete user
         * data. `fallbackToDestructiveMigration()` is deliberately absent and
         * `ArchitectureTest` fails the build if it reappears. Real migrations
         * are written for every schema change; the exported schemas in
         * app/schemas are what makes that possible without a local machine.
         *
         * If the file is unreadable we salvage it to a timestamped copy first,
         * so the raw bytes survive even when Room cannot open them.
         */
        fun build(context: Context): TaskMindDatabase =
            Room.databaseBuilder(context.applicationContext, TaskMindDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .enableMultiInstanceInvalidation()
                .build()

        /** No migrations yet - version 1. Every future bump appends here. */
        private val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        /**
         * Copies the database file aside before any recovery attempt. Returns
         * the salvage path, or null when there was nothing to salvage.
         */
        fun salvage(context: Context): String? {
            val dbFile = context.getDatabasePath(NAME)
            if (!dbFile.exists()) return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(context.filesDir, "salvage").apply { mkdirs() }
            val target = File(dir, "taskmind-$stamp.db")
            return runCatching {
                dbFile.copyTo(target, overwrite = true)
                target.absolutePath
            }.getOrNull()
        }
    }
}
