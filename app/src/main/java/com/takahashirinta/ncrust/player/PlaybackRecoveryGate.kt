/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.PlaybackNetworkRoute

/** Main-thread state. A usable new route can recover only the last unpaused request. */
internal class PlaybackRecoveryGate(initialRoute: PlaybackNetworkRoute) {
    private var route = initialRoute
    var wanted = false; private set
    var resolving = false; private set
    var waiting = false; private set
    fun begin() { wanted = true; resolving = true; waiting = false }
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
}
