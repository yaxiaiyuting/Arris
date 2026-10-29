/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource

/** Offline is a terminal wait state when no playable cache exists, never an online timeout. */
internal suspend fun resolveOfflineFirst(
    online: Boolean,
    source: MusicSource,
    offline: suspend () -> SongUrlResult?,
    network: suspend () -> ResolveOutcome,
): ResolveOutcome {
    if (!online) return offline()?.let(ResolveOutcome::ok)
        ?: ResolveOutcome.failed(ResolveFailureKind.NETWORK, source)
    val result = network()
    if (result.result != null) return result
    return offline()?.let(ResolveOutcome::ok) ?: result
}
