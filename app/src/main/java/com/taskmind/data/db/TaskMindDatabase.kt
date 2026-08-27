package com.taskmind.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskmind.data.db.dao.ActivityLogDao
import com.taskmind.data.db.dao.CallRecordDao
import com.taskmind.data.db.dao.FingerprintDao
import com.taskmind.data.db.dao.InferenceCallDao
import com.taskmind.data.db.dao.ProjectDao
import com.taskmind.data.db.dao.RawCaptureDao
import com.taskmind.data.db.dao.ReviewItemDao
import com.taskmind.data.db.dao.SeenPackageDao
import com.taskmind.data.db.dao.TagDao
import com.taskmind.data.db.dao.TaskDao
import com.taskmind.data.db.entity.ActivityLogEntity
import com.taskmind.data.db.entity.CallRecordEntity
import com.taskmind.data.db.entity.FingerprintEntity
import com.taskmind.data.db.entity.InferenceCallEntity
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
        InferenceCallEntity::class,
        ActivityLogEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        SeenPackageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TaskMindDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun callRecordDao(): CallRecordDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun inferenceCallDao(): InferenceCallDao
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

        /**
         * Every schema change gets a real migration. Failure mode 6 was a
         * recovery path that deleted the user's tasks because the local
         * database was assumed disposable; it is not, and
         * fallbackToDestructiveMigration is absent by design.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Adds the model-call trace. Purely additive: no existing table
                // is touched, so there is nothing to lose here.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `inference_calls` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `durationMillis` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `baseUrl` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `systemPrompt` TEXT,
                        `userPrompt` TEXT,
                        `httpStatus` INTEGER,
                        `ok` INTEGER NOT NULL,
                        `responseBody` TEXT,
                        `totalTokens` INTEGER,
                        `errorText` TEXT,
                        `diagnosis` TEXT,
                        `rawCaptureId` TEXT,
                        `sourceLabel` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inference_calls_startedAt` ON `inference_calls` (`startedAt`)")
            }
        }

        private val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

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
