package com.taskmind.capture

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.taskmind.core.DateResolver
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Spec 17.1 - the second and last foreground service.
 *
 * Type `dataSync`, for bounded upload/inference work only. It stops the moment
 * the work completes, never on a fixed timer.
 *
 * FAILURE MODE 2, which actually happened: `dataSync` has a cumulative ~6 h/day
 * budget. Exceeding it throws ForegroundServiceDidNotStopInTimeException and
 * KILLS the app - and that crash killed the notification listener, which killed
 * call detection. Android 15 calls the TWO-argument onTimeout for dataSync;
 * implementing only the one-argument overload means the handler never fires.
 *
 * Both overloads are implemented below, and `ArchitectureTest` fails the build
 * if either disappears.
 */
class WorkerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var container: AppContainer
    private var job: Job? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startedAt = System.currentTimeMillis()
        try {
            startForeground(
                Notifier.ID_FOREGROUND_WORKER,
                container.notifier.foregroundNotification("Processing captures"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (t: Throwable) {
            container.logger.post(Stage.WORKER, LogLevel.ERROR, "could not start dataSync service", t.toString())
            stopSelf(startId)
            return START_NOT_STICKY
        }

        job?.cancel()
        job = scope.launch {
            try {
                container.transcriptionPipeline.let { pipeline ->
                    val pending = container.database.rawCaptureDao()
                        .dueForState(com.taskmind.core.CaptureState.PENDING_TRANSCRIPTION, System.currentTimeMillis(), 3)
                    for (capture in pending) {
                        pipeline.transcribe(capture)
                    }
                }
            } catch (t: Throwable) {
                container.logger.write(Stage.WORKER, LogLevel.ERROR, "worker service failed", t.toString())
            } finally {
                // Stop the moment the work completes. Never on a timer.
                finish(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(startId: Int) {
        recordBudget()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
    }

    /**
     * Spec 17.1: track cumulative dataSync seconds per rolling day so that
     * non-urgent work can be deferred below a 30 minute reserve.
     */
    private fun recordBudget() {
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)
        if (seconds <= 0) return
        container.applicationScope.launch {
            runCatching {
                container.settingsRepository.recordDataSyncSeconds(
                    DateResolver.dayKey(System.currentTimeMillis()),
                    seconds,
                )
            }
        }
    }

    /**
     * API 34 signature. Android 14 calls this one.
     */
    override fun onTimeout(startId: Int) {
        container.logger.post(Stage.WORKER, LogLevel.WARN, "dataSync timeout (API 34 overload) - stopping")
        job?.cancel()
        finish(startId)
    }

    /**
     * API 35 signature. Android 15 calls THIS one for dataSync services.
     * Failure mode 2 was implementing only the overload above.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        container.logger.post(Stage.WORKER, LogLevel.WARN, "dataSync timeout (API 35 overload, type=$fgsType) - stopping")
        job?.cancel()
        finish(startId)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Spec 17.1: keep a 30 minute reserve of the daily dataSync budget. */
        const val DAILY_BUDGET_SECONDS = 6 * 60 * 60
        const val RESERVE_SECONDS = 30 * 60

        /**
         * Starting a foreground service from the background is blocked from
         * ordinary contexts on HyperOS; call this from the notification
         * listener process or from a foreground activity (spec 17.2).
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WorkerService::class.java))
            }
        }

        suspend fun hasBudget(container: AppContainer): Boolean {
            val usage = container.settingsRepository.fgsUsage()
            val today = DateResolver.dayKey(System.currentTimeMillis())
            if (usage.dayKey != today) return true
            return usage.dataSyncSeconds < DAILY_BUDGET_SECONDS - RESERVE_SECONDS
        }
    }
}
