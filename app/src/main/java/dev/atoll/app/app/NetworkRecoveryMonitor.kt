package dev.atoll.app.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** One monitor for the application-owned graph, independent of Activity recreation. */
internal class NetworkRecoveryMonitor(context: Context, recovered: () -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val changes = DefaultNetworkChanges(connectivity.activeNetwork)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (changes.available(network)) recovered()
        }
        override fun onLost(network: Network) { changes.lost(network) }
    }
    init { connectivity.registerDefaultNetworkCallback(callback) }
}
