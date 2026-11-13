/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLoadCoordinatorTest {
    private val netease = TrackKey(MusicSource.NETEASE, 287035L)
    private val qq = TrackKey(MusicSource.QQMUSIC, 287035L, "mid")

    @Test fun trackChangeInvalidatesBeforeTheNextRequestStarts() {
        val coordinator = LyricLoadCoordinator()
        val previous = coordinator.begin(netease)
        coordinator.onTrackChanged(qq)
        assertFalse(coordinator.isCurrent(previous))
        val next = coordinator.begin(qq)
        assertTrue(coordinator.isCurrent(next))
        assertFalse(coordinator.isCurrent(previous))
    }

    @Test fun evenAtTheCurrentGenerationTheSourceMustMatch() {
        val coordinator = LyricLoadCoordinator()
        val current = coordinator.begin(netease)
        assertFalse(coordinator.isCurrent(current.copy(track = qq)))
    }

    @Test fun retrySupersedesAnOlderRequestForTheSameTrack() {
        val coordinator = LyricLoadCoordinator()
        val previous = coordinator.begin(netease)
        val retry = coordinator.begin(netease)
        assertTrue(retry.generation > previous.generation)
        assertFalse(coordinator.isCurrent(previous))
        assertTrue(coordinator.isCurrent(retry))
    }

    @Test fun replacingIdentityPayloadInvalidatesThePendingRequest() {
        val coordinator = LyricLoadCoordinator()
        val previous = coordinator.begin(TrackKey(MusicSource.QQMUSIC, qq.id))
        coordinator.onTrackChanged(qq)
        assertFalse(coordinator.isCurrent(previous))
        assertTrue(coordinator.isCurrent(coordinator.begin(qq)))
    }

    @Test fun consecutiveSwitchesDoNotReviveEarlierRequests() {
        val coordinator = LyricLoadCoordinator()
        val requests = List(20) { index -> coordinator.begin(if (index % 2 == 0) netease else qq) }
        assertTrue(requests.dropLast(1).none(coordinator::isCurrent))
        assertTrue(coordinator.isCurrent(requests.last()))
    }

    @Test fun stoppingRejectsAllPreviousRequestsAndAllowsRestart() {
        val coordinator = LyricLoadCoordinator()
        val previous = coordinator.begin(qq)
        coordinator.invalidate()
        assertFalse(coordinator.isCurrent(previous))
        val restart = coordinator.begin(qq)
        assertFalse(coordinator.isCurrent(previous))
        assertTrue(coordinator.isCurrent(restart))
    }
}
