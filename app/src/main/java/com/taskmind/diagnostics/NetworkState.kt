package com.taskmind.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * Whether the phone is somewhere call audio is allowed to be uploaded.
 *
 * "Upload call audio on Wi-Fi only" is on by default and is enforced by
 * WorkManager, which simply does not run the work until the constraint is met.
 * That is correct - a call recording is megabytes - but it is completely
 * silent: on the device this left 59 transcriptions queued with nothing
 * anywhere saying why. Asking the same question the scheduler asks is what
 * turns that into an explanation.
 */
object NetworkState {

    /** True when on Wi-Fi or another network the system considers unmetered. */
    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isConnected(context: Context): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** One phrase for the diagnostic report and the status screen. */
    fun describe(context: Context): String = when {
        !isConnected(context) -> "no network"
        isUnmetered(context) -> "unmetered (Wi-Fi)"
        else -> "metered (mobile data)"
    }
}
