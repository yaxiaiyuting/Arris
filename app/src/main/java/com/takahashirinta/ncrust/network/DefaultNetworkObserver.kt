/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class PlaybackNetworkRoute(val id: Long?, val online: Boolean, val capabilities: Int = 0)

/** Observe the default route only. An old Wi-Fi onLost must not erase the new mobile route. */
internal class DefaultNetworkObserver(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    fun snapshot(): PlaybackNetworkRoute = try {
        val network = cm?.activeNetwork
        val caps = network?.let { cm?.getNetworkCapabilities(it) }
        // Validation is a change signal, never a hard online gate (some ROM probes fail).
        val signature = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN)
            .fold(0) { mask, transport -> if (caps?.hasTransport(transport) == true) mask or (1 shl transport) else mask } or
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 1 shl 16 else 0)
        PlaybackNetworkRoute(network?.networkHandle,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true, signature)
    } catch (_: Exception) { PlaybackNetworkRoute(null, true) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun changes() = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(snapshot()) }
            override fun onLost(network: Network) { trySend(snapshot()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(snapshot()) }
        }
        val registered = try { cm?.registerDefaultNetworkCallback(callback); cm != null }
            catch (_: Exception) { false }
        trySend(snapshot())
        awaitClose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
