/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import androidx.media3.common.Player
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.MusicSource

/** A URL replaces the entire identity, including nullable fields. Controls preserve it. */
internal fun playbackRequestIdentity(
    previous: TrackKey?, hasUrl: Boolean, songId: Long,
    source: String?, sourceId: String?, mediaId: String?,
): TrackKey? = if (!hasUrl) previous else {
    if (songId <= 0) null else TrackKey.of(source, songId, sourceId, mediaId).let {
        if (it.source == MusicSource.NETEASE) it.copy(sourceId = null, mediaId = null) else it
    }
}

/** Manual navigation always advances even when automatic completion repeats the current track. */
internal fun manualQueueIndex(size: Int, current: Int, direction: Int): Int? =
    if (size <= 0) null else Math.floorMod(current.coerceIn(0, size - 1) + direction, size)

/** A resolved next URL can only be queued behind the current item it was requested for. */
internal fun admitPreload(generationCurrent: Boolean, owner: TrackKey?, playing: TrackKey?): Boolean =
    generationCurrent && owner != null && playing != null && owner == playing

/** The VM and Service share the same epoch, including commands waiting in Android's queue. */
internal val playRequestGeneration = com.takahashirinta.ncrust.ui.viewmodel.PlayRequestGate()

/** Active audio may outlive the activity task, including a track still buffering. */
internal fun hasActivePlaybackIntent(playWhenReady: Boolean, state: Int, hasItem: Boolean): Boolean =
    playWhenReady && hasItem && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
