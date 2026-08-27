package com.taskmind.capture

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer
import com.taskmind.notify.Notifier
import com.taskmind.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Spec 17.1 - one of exactly two foreground services.
 *
 * Type `specialUse`, which is exempt from the daily foreground-service budget.
 * It hosts the telephony callback (trigger 2 of 3 for call end, spec 11.2) and
 * nothing that does bounded work - bounded work belongs in [WorkerService],
 * whose `dataSync` budget is finite.
 *
 * Its notification is deferred and minimum-importance: a foreground-service
 * notification is a legal requirement, not a message to the user (spec 17.3).
 */
class ResidencyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var container: AppContainer
    private var telephonyCallback: TelephonyCallback? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var callStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                Notifier.ID_FOREGROUND_RESIDENCY,
                container.notifier.foregroundNotification("Watching for calls"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            registerTelephonyCallback()
            Scheduler.ensureWatchdog(this)
        } catch (t: Throwable) {
            container.logger.post(Stage.SYSTEM, LogLevel.ERROR, "ResidencyService start failed", t.toString())
            stopSelf()
        }
        // Restart if the system kills us: residency is the whole point.
        return START_STICKY
    }

    private fun registerTelephonyCallback() {
        if (telephonyCallback != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            container.logger.post(Stage.CALL, LogLevel.WARN, "telephony callback not registered - READ_PHONE_STATE missing")
            return
        }
        val telephony = getSystemService(TelephonyManager::class.java) ?: return

        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                try {
                    handleCallState(state)
                } catch (t: Throwable) {
                    container.logger.post(Stage.CALL, LogLevel.ERROR, "call state handling failed", t.toString())
                }
            }
        }
        runCatching {
            telephony.registerTelephonyCallback(mainExecutor, callback)
            telephonyCallback = callback
            container.logger.post(Stage.CALL, LogLevel.INFO, "telephony callback registered")
        }
    }

    private fun handleCallState(state: Int) {
        val previous = lastState
        lastState = state
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> callStartedAt = System.currentTimeMillis()
            TelephonyManager.CALL_STATE_IDLE -> {
                if (previous == TelephonyManager.CALL_STATE_OFFHOOK) {
                    container.logger.post(Stage.CALL, LogLevel.INFO, "call ended (telephony callback)")
                    scope.launch {
                        // The call-log row is written by the dialer a moment
                        // after the call ends; the sweep is idempotent, so
                        // asking twice costs nothing.
                        val count = container.callPipeline.sweepCallLog("telephony callback")
                        // Failure mode 7: do NOT pre-check for a recording here.
                        // Right after a call the file is still being written and
                        // the answer is always no. Start the worker with its own
                        // retry loop and let it do the looking.
                        Scheduler.enqueueCallDiscovery(this@ResidencyService)
                        if (count == 0) {
                            Scheduler.enqueueDelayedCallSweep(this@ResidencyService)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        telephonyCallback?.let { cb ->
            runCatching { getSystemService(TelephonyManager::class.java)?.unregisterTelephonyCallback(cb) }
        }
        telephonyCallback = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ResidencyService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ResidencyService::class.java)) }
        }
    }
}
