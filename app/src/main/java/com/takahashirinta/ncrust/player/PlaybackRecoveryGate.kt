/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.PlaybackNetworkRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Main-thread state. A usable new route can recover only the last unpaused request. */
internal class PlaybackRecoveryGate(initialRoute: PlaybackNetworkRoute) {
    private var route = initialRoute
    val online: Boolean get() = route.online
    private val routeRefreshPending = MutableStateFlow(false)
    var wanted = false; private set
    var resolving = false; private set
    var waiting = false; private set
    fun begin(wanted: Boolean = true) {
        this.wanted = wanted
        resolving = true
        waiting = false
    }
    fun resolved() { resolving = false; waiting = false }
    fun failed(network: Boolean) { resolving = false; waiting = network; if (!network) wanted = false }
    fun cancel() { wanted = false; resolving = false; waiting = false }
    fun shouldResolveBeforePlay(hasPlayableItem: Boolean, playerFailed: Boolean) =
        resolving || waiting || !hasPlayableItem || playerFailed
    fun needsRecovery(buffering: Boolean) = wanted && (resolving || waiting || buffering)
    fun networkChanged(next: PlaybackNetworkRoute): Boolean {
        val changed = next != route
        route = next
        return changed && next.online
    }

    suspend fun awaitRouteRefresh() {
        if (routeRefreshPending.value) routeRefreshPending.first { !it }
    }

    /** Called from collectLatest so a newer route takes over a pending refresh. */
    suspend fun updateRoute(
        next: PlaybackNetworkRoute,
        onChange: (refreshing: Boolean) -> Unit,
        refresh: suspend () -> Unit,
        onReady: () -> Unit,
    ) {
        val recover = networkChanged(next)
        routeRefreshPending.value = recover
        onChange(recover)
        if (!recover) return
        // Keep preloads waiting through coalesced online callbacks and cancelled refreshes.
        delay(500L)
        refresh()
        onReady()
        routeRefreshPending.value = false
    }
}
