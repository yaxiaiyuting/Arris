/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**推送节流**的 JVM 单测（本版 P0）。
 *
 * 为什么这一层是全版最该测的：节流写错的两种后果都是真机才能看见的 ——
 *   · 推得太勤：Binder 事务 + 宿主反射 + 封面位图每秒拷贝，掉帧与耗电；
 *   · 推得太少：暂停/切歌后卡片停在旧画面上，用户以为应用卡死了。
 * 而两者在单测里都只是「给一串输入、看返回值」。
 */

package com.takahashirinta.ncrust.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPushGateTest {

    private fun key(
        songId: Long = 1L,
        isPlaying: Boolean = true,
        positionSec: Int = 0,
        size: WidgetSize = WidgetSize.MEDIUM,
        hasContent: Boolean = true,
        hasArtwork: Boolean = true,
    ) = WidgetPushKey(
        hasContent = hasContent,
        songId = songId,
        isPlaying = isPlaying,
        positionSec = positionSec,
        size = size,
        hasArtwork = hasArtwork,
    )

    // ---------------------------------------------------------------- 四种该推的时机

    @Test
    fun `本进程第一次推送——没有上一次指纹，必推`() {
        assertTrue(WidgetPushGate.shouldPush(previous = null, next = key()))
    }

    @Test
    fun `整数秒变化——推`() {
        assertTrue(WidgetPushGate.shouldPush(key(positionSec = 3), key(positionSec = 4)))
    }

    @Test
    fun `切歌——推`() {
        assertTrue(WidgetPushGate.shouldPush(key(songId = 1L), key(songId = 2L)))
    }

    @Test
    fun `播放切到暂停——推（用户按了暂停，图标必须立刻变成播放键）`() {
        assertTrue(
            WidgetPushGate.shouldPush(key(isPlaying = true, positionSec = 9), key(isPlaying = false, positionSec = 9)),
        )
    }

    @Test
    fun `暂停切到播放——推`() {
        assertTrue(
            WidgetPushGate.shouldPush(key(isPlaying = false, positionSec = 9), key(isPlaying = true, positionSec = 9)),
        )
    }

    @Test
    fun `尺寸变化——推（哪怕正在暂停）`() {
        assertTrue(
            WidgetPushGate.shouldPush(
                key(isPlaying = false, size = WidgetSize.MEDIUM),
                key(isPlaying = false, size = WidgetSize.LARGE),
            ),
        )
    }

    @Test
    fun `空态与有内容之间切换——两个方向都推`() {
        assertTrue(WidgetPushGate.shouldPush(key(hasContent = true), key(hasContent = false, songId = -1L)))
        assertTrue(WidgetPushGate.shouldPush(key(hasContent = false, songId = -1L), key(hasContent = true)))
    }

    // ---------------------------------------------------------------- 不该推的（P0 的另一半）

    @Test
    fun `同一秒内、状态没变——不推`() {
        assertFalse(WidgetPushGate.shouldPush(key(positionSec = 7), key(positionSec = 7)))
    }

    @Test
    fun `暂停时秒数变化——不推（暂停后进度不会自己走）`() {
        assertFalse(
            WidgetPushGate.shouldPush(
                key(isPlaying = false, positionSec = 7),
                key(isPlaying = false, positionSec = 8),
            ),
        )
    }

    @Test
    fun `暂停且一切没变——不推`() {
        assertFalse(WidgetPushGate.shouldPush(key(isPlaying = false), key(isPlaying = false)))
    }

    @Test
    fun `空态反复渲染——不推`() {
        assertFalse(
            WidgetPushGate.shouldPush(
                key(hasContent = false, songId = -1L),
                key(hasContent = false, songId = -1L),
            ),
        )
    }

    @Test
    fun `暂停态下切歌——推（暂停不等于冻结）`() {
        assertTrue(
            WidgetPushGate.shouldPush(
                key(songId = 1L, isPlaying = false),
                key(songId = 2L, isPlaying = false),
            ),
        )
    }

    @Test
    fun `封面来迟或从无到有——暂停时也要推`() {
        // 服务在暂停 N 秒后会主动释放封面位图，恢复播放时再从 URL 拉回来；
        // 这一对转换如果被闸门吃掉，卡片会永远停在音符占位上。
        assertTrue(
            WidgetPushGate.shouldPush(
                key(isPlaying = false, hasArtwork = false),
                key(isPlaying = false, hasArtwork = true),
            ),
        )
        assertTrue(
            WidgetPushGate.shouldPush(
                key(isPlaying = false, hasArtwork = true),
                key(isPlaying = false, hasArtwork = false),
            ),
        )
        // 播放中也一样（暂停→播放那一刻封面还没加载完的窗口）。
        assertTrue(WidgetPushGate.shouldPush(key(hasArtwork = false), key(hasArtwork = true)))
    }

    // ---------------------------------------------------------------- 模型级的 2Hz 验证

    @Test
    fun `2Hz 的 tick 每分钟只放行 60 次推送（而不是 120 次）`() {
        var previous: WidgetPushKey? = null
        var pushes = 0
        // 模拟 60 秒：ticker 每 500ms 一次，位置每次 +500ms。
        for (tick in 0 until 120) {
            val positionMs = tick * 500L
            val next = key(positionSec = (positionMs / 1000L).toInt())
            if (WidgetPushGate.shouldPush(previous, next)) {
                pushes++
                previous = next
            }
        }
        // tick 0 是「第一次推送」（previous == null）且位置是第 0 秒；
        // 之后 tick 2/4/…/118 各跨一个整秒（1..59）⇒ 1 + 59 = 60。
        // 也就是「用户感知到的刷新率仍是每秒一次」，而 Binder 压力减半。
        assertEquals(60, pushes)
        assertTrue("推送次数必须显著少于 tick 次数", pushes < 120)
    }

    @Test
    fun `暂停后的 120 次 tick 一次都不推`() {
        val paused = key(isPlaying = false, positionSec = 42)
        var previous: WidgetPushKey? = paused
        var pushes = 0
        for (tick in 0 until 120) {
            val next = key(isPlaying = false, positionSec = 42 + tick / 2)
            if (WidgetPushGate.shouldPush(previous, next)) {
                pushes++
                previous = next
            }
        }
        assertEquals("暂停期间必须完全停止推送", 0, pushes)
    }

    // ---------------------------------------------------------------- 封面发送策略

    @Test
    fun `换歌必须带封面`() {
        assertTrue(
            WidgetArtworkPolicy.shouldShip(
                sameSong = false,
                sameTarget = true,
                secSinceLastShip = 1,
            ),
        )
    }

    @Test
    fun `目标像素变化必须带封面（用户把卡片拖大了）`() {
        assertTrue(
            WidgetArtworkPolicy.shouldShip(
                sameSong = true,
                sameTarget = false,
                secSinceLastShip = 1,
            ),
        )
    }

    @Test
    fun `同一首、同一尺寸、刚发过——不带（省一次位图跨进程拷贝）`() {
        assertFalse(
            WidgetArtworkPolicy.shouldShip(
                sameSong = true,
                sameTarget = true,
                secSinceLastShip = 0,
            ),
        )
        assertFalse(
            WidgetArtworkPolicy.shouldShip(
                sameSong = true,
                sameTarget = true,
                secSinceLastShip = WidgetArtworkPolicy.RESHIP_INTERVAL_SEC - 1,
            ),
        )
    }

    @Test
    fun `到补发间隔必须带一次（宿主进程可能重启过）`() {
        assertTrue(
            WidgetArtworkPolicy.shouldShip(
                sameSong = true,
                sameTarget = true,
                secSinceLastShip = WidgetArtworkPolicy.RESHIP_INTERVAL_SEC,
            ),
        )
    }

    // ---------------------------------------------------------------- 位图目标像素

    @Test
    fun `目标像素等于卡片 dp 乘以屏幕密度`() {
        assertEquals(120, WidgetArtworkPolicy.targetPx(coverDp = 40, density = 3.0f))
        assertEquals(112, WidgetArtworkPolicy.targetPx(coverDp = 56, density = 2.0f))
        assertEquals(56, WidgetArtworkPolicy.targetPx(coverDp = 56, density = 1.0f))
    }

    @Test
    fun `目标像素夹在上限（Binder 事务安全）`() {
        // 56dp @ 4.0x = 224 → 夹到 192px = 147KB（ARGB_8888），远低于 1MB 事务硬限。
        assertEquals(WidgetArtworkPolicy.MAX_TARGET_PX, WidgetArtworkPolicy.targetPx(56, 4.0f))
        assertEquals(WidgetArtworkPolicy.MAX_TARGET_PX, WidgetArtworkPolicy.targetPx(56, 100f))
        assertEquals(192 * 192 * 4, 147456)
    }

    @Test
    fun `目标像素夹在下限（不出现马赛克）`() {
        assertEquals(WidgetArtworkPolicy.MIN_TARGET_PX, WidgetArtworkPolicy.targetPx(40, 1.0f))
        assertEquals(WidgetArtworkPolicy.MIN_TARGET_PX, WidgetArtworkPolicy.targetPx(0, 3.0f))
    }

    @Test
    fun `密度是 0 或 NaN 时不崩（回落下限）`() {
        assertEquals(WidgetArtworkPolicy.MIN_TARGET_PX, WidgetArtworkPolicy.targetPx(40, 0f))
        assertEquals(WidgetArtworkPolicy.MIN_TARGET_PX, WidgetArtworkPolicy.targetPx(40, -1f))
        assertEquals(WidgetArtworkPolicy.MIN_TARGET_PX, WidgetArtworkPolicy.targetPx(40, Float.NaN))
    }

    // ---------------------------------------------------------------- 卡片存在性查询节流

    @Test
    fun `从没查询过就必须查一次`() {
        assertTrue(WidgetPresencePolicy.shouldRefresh(nowSec = 1_000L, lastRefreshSec = -1L))
    }

    @Test
    fun `刚查过就不查（没人用桌面卡片时连 Binder 都不发）`() {
        assertFalse(WidgetPresencePolicy.shouldRefresh(nowSec = 1_000L, lastRefreshSec = 1_000L))
        assertFalse(
            WidgetPresencePolicy.shouldRefresh(
                nowSec = 1_000L + WidgetPresencePolicy.REFRESH_INTERVAL_SEC - 1,
                lastRefreshSec = 1_000L,
            ),
        )
    }

    @Test
    fun `超过间隔重新查询`() {
        assertTrue(
            WidgetPresencePolicy.shouldRefresh(
                nowSec = 1_000L + WidgetPresencePolicy.REFRESH_INTERVAL_SEC,
                lastRefreshSec = 1_000L,
            ),
        )
    }
}
