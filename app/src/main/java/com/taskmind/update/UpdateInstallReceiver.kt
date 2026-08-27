package com.taskmind.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.taskmind.core.LogLevel
import com.taskmind.core.Stage
import com.taskmind.di.AppContainer

/**
 * Receives the outcome of a PackageInstaller session (spec 19).
 *
 * The downloaded APK is only removed once the session reports a terminal
 * result; deleting it earlier is how an install that asks for user
 * confirmation ends up with nothing to install.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val container = AppContainer.get(context)
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmation != null) {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmation)
                    }
                    container.logger.post(Stage.UPDATE, LogLevel.INFO, "install awaiting user confirmation")
                }

                PackageInstaller.STATUS_SUCCESS ->
                    container.logger.post(Stage.UPDATE, LogLevel.INFO, "update installed")

                else ->
                    container.logger.post(
                        Stage.UPDATE,
                        LogLevel.ERROR,
                        "update install failed (status $status)",
                        message,
                    )
            }
        } catch (t: Throwable) {
            runCatching {
                AppContainer.get(context).logger.post(
                    Stage.UPDATE,
                    LogLevel.ERROR,
                    "UpdateInstallReceiver failed",
                    t.toString(),
                )
            }
        }
    }
}
