/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.Player
import com.takahashirinta.ncrust.player.MediaDisplayLines.Lines
import com.takahashirinta.ncrust.player.MediaSessionMerge.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.1.6 · 会话合并的两处承重纯逻辑。
 *
 * 这些断言的用处不是「证明代码等于自己」，而是**把契约钉住**：
 * 少接一条 media button 命令、或把回退开关接反，都不会崩、只会静默串台 / 静默丢歌词，
 * 只能靠这里的用例拦住。
 */
class MediaSessionMergeTest {

    // ---------- 1. 媒体按键路由：这张表就是契约 ----------

    @Test
    fun `下一首的两条命令都必须交给应用队列`() {
        // 平台对「下一首」可能发其中任意一条（取决于 ROM 与会话播放列表长度）。
        assertEquals(Route.APP_NEXT, MediaSessionMerge.route(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertEquals(Route.APP_NEXT, MediaSessionMerge.route(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `上一首的两条命令都必须交给应用队列`() {
        assertEquals(
            Route.APP_PREVIOUS,
            MediaSessionMerge.route(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        )
        assertEquals(Route.APP_PREVIOUS, MediaSessionMerge.route(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `播放暂停与 seek 仍然走 media3 默认实现`() {
        // 这两条**不能**被应用截走：截走就等于把音频焦点 / 播放状态机从 media3 手里抢回来，
        // 合并的意义（只有一条会话、只有一个状态源）就没了。
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(Player.COMMAND_PLAY_PAUSE))
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(Player.COMMAND_PLAY_PAUSE))
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(Player.COMMAND_STOP))
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(Player.COMMAND_SET_MEDIA_ITEM))
    }

    @Test
    fun `未知命令一律回落 media3，不猜`() {
        // 平台/车机将来加了新命令，默认行为必须是「交给 media3」而不是被我们误当成切歌。
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(-1))
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(Int.MAX_VALUE))
        assertEquals(Route.MEDIA3_DEFAULT, MediaSessionMerge.route(Int.MIN_VALUE))
    }

    @Test
    fun `下一首与上一首不会互相串（成对断言）`() {
        // 只断言「等于 APP_NEXT」在两条命令被写成同一个常量时仍然会通过 ——
        // 这里再钉一次方向，防止有人把 previous 的常量抄成 next。
        val next = MediaSessionMerge.route(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val prev = MediaSessionMerge.route(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        assertEquals(true, next != prev)
    }

    // ---------- 2. 会话 metadata 两行：开关两侧 + 通知那一份不受影响 ----------

    @Test
    fun `开关打开——会话跟随歌词（与 v1_5_1 起的行为逐字节一致）`() {
        assertEquals(
            Lines("You have a mental problems", "67 · DJ R4"),
            MediaSessionMerge.sessionLines("67", "DJ R4", "You have a mental problems", true)
        )
    }

    @Test
    fun `开关关闭——会话只讲歌的身份，歌词不上会话`() {
        assertEquals(
            Lines("67", "DJ R4"),
            MediaSessionMerge.sessionLines("67", "DJ R4", "You have a mental problems", false)
        )
    }

    @Test
    fun `开关关闭时，没有歌词的歌与开关打开完全等价`() {
        // 回退开关不能改变「本来就没歌词」那些歌的行为 —— 否则它就不是回退，是第二个变体。
        for (line in listOf(null, "", "   ")) {
            assertEquals(
                MediaSessionMerge.sessionLines("67", "DJ R4", line, true),
                MediaSessionMerge.sessionLines("67", "DJ R4", line, false),
            )
        }
    }

    @Test
    fun `通知正文那一份永远带歌词——不受回退开关影响`() {
        // PlaybackService.buildNotification 用的是 MediaDisplayLines.of(...)，不是
        // sessionLines(..., false)。这条用例把「两者不是同一个东西」钉住：
        // 回退开关只允许影响会话，不允许把通知里的歌词也一起关掉。
        val notify = MediaDisplayLines.of("67", "DJ R4", "You have a mental problems")
        val sessionFallback = MediaSessionMerge.sessionLines("67", "DJ R4", "You have a mental problems", false)
        assertEquals("You have a mental problems", notify.title)
        assertEquals("67", sessionFallback.title)
    }
}
