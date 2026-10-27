/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.7.9 · J07：「实际在播身份」的落定规则。
 *
 * 复审（`docs/reviews/2026-10-27-1150-scheduled-review.md` J07）确认的错法是：
 * 身份只在 `nextTrack != currentTrack` 分支里落定，而手动切歌时 `playSong` 已经把
 * `currentTrack` 提前写成 B ⇒ 真正起播 B 的那一刻 `changed=false` ⇒ 身份停在 A，
 * `StatsRecorder` 把 B 的收听时长一直记到 A 头上。
 *
 * 这里钉的是那条不变量：**过渡发生了就落定身份**，「换没换歌」只由返回值回答。
 * 纯状态，无 Android / 播放器 / 协程。
 */

package com.takahashirinta.ncrust.ui.viewmodel

import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayingIdentityTest {

    private val a = TrackKey.of("netease", 101L)
    private val b = TrackKey.of("netease", 102L)

    @Test
    fun `同一首的过渡（next 等于 current）也必须刷新在播身份`() {
        val identity = PlayingIdentity()
        // 自动过渡到 A：这是真正在播的那一首。
        assertTrue(identity.settle(a, current = null))
        assertEquals(a, identity.value)

        // 手动切歌：playSong 已经把请求身份提前写成了 B，所以过渡时 next == current。
        // 这正是旧实现漏掉的那条路径 —— 身份必须刷新成 B。
        assertFalse(identity.settle(b, current = b))
        assertEquals(
            "next == current 时没有落定身份：StatsRecorder 会继续拿 A 记账（J07）",
            b,
            identity.value,
        )
    }

    @Test
    fun `慢网重复点同一首——每次过渡都落定，返回值告诉调用方没换歌`() {
        val identity = PlayingIdentity()
        identity.settle(a, current = a)
        // 用户又点了一次同一首：URL 重新取链、播放器重新起播同一项。
        assertFalse(identity.settle(a, current = a))
        assertEquals(a, identity.value)
    }

    @Test
    fun `换歌时返回 true（调用方据此清上一首续播记录并重取歌词）`() {
        val identity = PlayingIdentity()
        identity.settle(a, current = a)
        assertTrue(identity.settle(b, current = a))
        assertEquals(b, identity.value)
    }

    @Test
    fun `内容相同但实例不同的身份同样会落定`() {
        // TrackKey 的相等只看音源 + id；过渡上报的是服务端现构的实例，
        // 落定不依赖「与上一次是同一个对象」。
        val identity = PlayingIdentity()
        identity.settle(TrackKey.of("netease", 101L), current = null)
        val reported = TrackKey.of("netease", 101L, sourceId = "fixture-mid")
        identity.settle(reported, current = TrackKey.of("netease", 101L))
        assertSame("落定的必须是这一次上报的那个实例", reported, identity.value)
    }

    @Test
    fun `停止后清空——旧身份不再遮蔽后续新项`() {
        val identity = PlayingIdentity()
        identity.settle(a, current = a)
        identity.clear()
        assertNull("停止后必须没有「实际在播」这回事", identity.value)
    }
}
