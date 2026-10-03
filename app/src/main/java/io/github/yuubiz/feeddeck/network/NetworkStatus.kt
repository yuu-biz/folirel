package io.github.yuubiz.feeddeck.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkState(val connected: Boolean, val metered: Boolean)

/** Current connectivity; replaceable in tests. */
fun interface NetworkStatus {
    fun current(): NetworkState
}

class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Emits the current state and every change of the default network. */
    fun observe(): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(current())
            }
        }
        trySend(current())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override fun current(): NetworkState {
        val network = connectivity.activeNetwork ?: return NetworkState(connected = false, metered = true)
        val caps = connectivity.getNetworkCapabilities(network) ?: return NetworkState(connected = false, metered = true)
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // Same notion as WorkManager's UNMETERED constraint; not a strict Wi-Fi/SSID check.
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkState(connected, metered)
    }
}
