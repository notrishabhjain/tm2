package com.taskmind.capture

import android.content.Context
import com.taskmind.core.LogLevel
import com.taskmind.core.QuickCheck
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer

/**
 * The quick check's audit (v1.36.1), remembered on the phone.
 *
 * One in [QuickCheck.AUDIT_EVERY] confident "none"s is read in full anyway.
 * When the full read finds a task the check would have skipped, that is a
 * miss; [QuickCheck.AUDIT_MISSES_TO_STOP] misses among the recent audits
 * switch the check off, and the user is told.
 */
class QuickAuditStore(context: Context, private val container: AppContainer) : ExtractionPipeline.QuickAudit {

    private val prefs = context.applicationContext.getSharedPreferences("quick_check_audit", Context.MODE_PRIVATE)

    @Synchronized
    override fun sampleThis(): Boolean {
        val n = prefs.getInt(K_SEEN, 0) + 1
        prefs.edit().putInt(K_SEEN, n).apply()
        return n % QuickCheck.AUDIT_EVERY == 0
    }

    override fun history(): String = prefs.getString(K_HISTORY, "").orEmpty()

    override fun save(history: String) {
        prefs.edit().putString(K_HISTORY, history).apply()
    }

    override suspend fun stop(misses: Int) {
        container.settingsRepository.setQuickCheck(false)
        container.logger.write(
            Stage.EXTRACT,
            LogLevel.ERROR,
            "quick check turned off: it would have skipped $misses real task(s) in recent checks",
            "Every chat message gets the full read again. Turn it back on in Settings, Advanced, Accuracy.",
        )
        container.notifier.postQuickCheckOff(misses)
    }

    private companion object {
        const val K_SEEN = "seen"
        const val K_HISTORY = "history"
    }
}
