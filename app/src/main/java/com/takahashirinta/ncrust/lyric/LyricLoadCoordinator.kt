/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import com.takahashirinta.ncrust.source.TrackKey

/** Accepts only the latest request for the current track. Used on the playback model's main thread. */
internal class LyricLoadCoordinator {
    data class Load(val generation: Long, val track: TrackKey)

    private var currentTrack: TrackKey? = null
    private var generation = 0L

    fun onTrackChanged(track: TrackKey) {
        generation++
        currentTrack = track
    }

    fun begin(track: TrackKey): Load {
        onTrackChanged(track)
        return Load(generation, track)
    }

    fun isCurrent(load: Load): Boolean =
        load.generation == generation && load.track == currentTrack

    fun invalidate() {
        generation++
        currentTrack = null
    }
}
