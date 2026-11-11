/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player.motion

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class MotionPlayback { Playing, Buffering, Paused }

/** 暂停和缓冲只推进到画面收尾，随后等待下一次播放状态变化。 */
internal suspend fun Flow<MotionPlayback>.runMotionFrames(
    play: suspend (resuming: Boolean) -> Unit,
    settle: suspend (holdWaveform: Boolean) -> Boolean,
) {
    var wasInactive = false
    distinctUntilChanged().collectLatest { state ->
        if (state == MotionPlayback.Playing) {
            play(wasInactive)
        } else {
            wasInactive = true
            while (settle(state == MotionPlayback.Buffering)) Unit
        }
    }
}
