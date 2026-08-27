package com.taskmind

import android.app.Application
import com.taskmind.capture.ResidencyService
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.work.Scheduler
import kotlinx.coroutines.launch

class TaskMindApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        container.start()
        container.logger.post(
            Stage.SYSTEM,
            LogLevel.INFO,
            "TaskMind ${BuildConfig.APP_VERSION_NAME} (${BuildConfig.APP_VERSION_CODE}) started",
        )

        // Spec 17.4: re-establish everything on every start. A revoked
        // permission or a killed service must recover without the user having
        // to know that it happened.
        Scheduler.ensurePeriodicWork(this)
        Scheduler.ensureWatchdog(this)

        // cachedSettings is still at its defaults this early - the collector
        // that fills it has not run yet - so read the store directly.
        container.applicationScope.launch {
            val settings = container.settingsRepository.current()
            if (settings.cloudConsent && settings.captureCalls) {
                ResidencyService.start(this@TaskMindApp)
            }
        }
    }
}
