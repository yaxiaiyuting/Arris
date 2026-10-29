/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource
import kotlinx.coroutines.withTimeoutOrNull

/** Offline is a terminal wait state when no playable cache exists, never an online timeout. */
internal suspend fun resolveOfflineFirst(
    online: Boolean,
    source: MusicSource,
    offline: suspend () -> SongUrlResult?,
    network: suspend () -> ResolveOutcome,
    networkTimeoutMs: Long = 15_000L,
): ResolveOutcome {
    if (!online) return offline()?.let(ResolveOutcome::ok)
        ?: ResolveOutcome.failed(ResolveFailureKind.NETWORK, source)
    // Bound only the remote leg: timeout must not cancel the complete-cache fallback.
    val result = withTimeoutOrNull(networkTimeoutMs) { network() }
        ?: ResolveOutcome.failed(ResolveFailureKind.NETWORK, source)
    if (result.result != null) return result
    return offline()?.let(ResolveOutcome::ok) ?: result
}
