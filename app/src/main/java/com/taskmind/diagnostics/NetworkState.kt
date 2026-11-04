package com.taskmind.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.core.content.getSystemService

/**
 * What the network looks like to this app right now.
 *
 * Asked by the instant lane before it sends anything (an offline attempt
 * would only spend a retry), and by Health and the diagnostics, because a
 * queue waiting on the network looks identical to a broken one unless
 * something says which it is.
 */
object NetworkState {

    /**
     * True when Data Saver is on and TaskMind is not exempt from it: Android
     * then blocks mobile data whenever the app is not in the foreground.
     */
    fun backgroundDataRestricted(context: Context): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        return runCatching {
            manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)
    }

    /** True when on Wi-Fi or another network the system considers unmetered. */
    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Whether a request sent now can get out: connected, and not blocked for
     * this app (Data Saver, or the phone's own per-app data rules). Sending
     * while blocked only fails and spends a retry, so the instant lane asks
     * first and leaves the work to WorkManager, whose network constraint
     * waits for exactly this.
     */
    @Suppress("DEPRECATION")
    fun isUsable(context: Context): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val info = runCatching { manager.activeNetworkInfo }.getOrNull() ?: return false
        if (info.detailedState == NetworkInfo.DetailedState.BLOCKED) return false
        return info.isConnected
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
