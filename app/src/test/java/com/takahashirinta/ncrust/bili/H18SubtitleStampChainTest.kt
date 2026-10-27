/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 2026-10-27 审查 H18：100 分钟后的字幕时间戳与解析器不兼容。
 */

package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.lyric.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H18：**长时长（≥100 分钟）字幕的时间戳必须在整条链上活下来**。
 *
 * ## 缺陷形状
 *
 * `BiliSubtitle.formatStamp` 的 `"%02d:%02d.%02d"` 对分钟只规定**最小宽度**，
 * 100 分钟生成 `[100:00.00]`（合法 LRC），而 `LrcParser` 的两个正则当时写死 `\d{2}` ——
 * 于是 100 分钟之后的字幕行被**整行丢弃**：B 站长视频/长音频在该边界之后没有歌词，
 * 且不报错。
 *
 * ## 这条用例钉的是什么
 *
 * 生产方（本文件所在的 `bili/`）输出正确形状 + 消费方（`lyric/LrcParser.kt`）按多位分钟解析。
 * 消费方的修复不在本次改动的文件范围内（`lyric/` 不归本轮），**但它已经在工作树里**；
 * 这条用例是两边一起的端到端判据：任一侧回退，它会立刻变红。
 */
class H18SubtitleStampChainTest {

    private fun cue(fromMs: Long, text: String) =
        BiliSubtitleCue(fromMs = fromMs, toMs = fromMs + 3_000L, text = text, music = 1.0)

    /** 生产方的边界形状：99 分钟是两位，100 分钟如实给三位（不折行、不截断）。 */
    @Test
    fun `时间戳在 100 分钟边界上不截断`() {
        assertEquals("[99:59.00]", BiliSubtitle.formatStamp((99 * 60 + 59) * 1_000L))
        assertEquals("[100:00.00]", BiliSubtitle.formatStamp(100 * 60 * 1_000L))
        assertEquals("[100:02.50]", BiliSubtitle.formatStamp((100 * 60 + 2) * 1_000L + 500L))
        assertEquals("[00:00.00]", BiliSubtitle.formatStamp(0L))
    }

    /** 端到端：跨过 100 分钟的三条音乐字幕，经 LRC 生成 + 解析之后**一条都不许丢**。 */
    @Test
    fun `跨过100分钟的字幕经LRC解析之后一条都不丢`() {
        val cues = listOf(
            cue((99 * 60 + 59) * 1_000L, "♪ 九十九分钟 ♪"),
            cue(100 * 60 * 1_000L, "♪ 一百分整 ♪"),
            cue((100 * 60 + 2) * 1_000L, "♪ 一百分零二秒 ♪"),
        )
        val lrc = BiliSubtitle.lyricLrc(cues)
        val lines = LrcParser.parse(lrc)
        assertEquals("三条字幕都必须解析出来（旧解析器只留第一条）", 3, lines.size)
        assertEquals(
            listOf(
                (99 * 60 + 59) * 1_000L,
                100 * 60 * 1_000L,
                (100 * 60 + 2) * 1_000L,
            ),
            lines.map { it.timeMs },
        )
        assertTrue("演唱标记必须被剥掉", lines.none { it.text.contains("♪") })
    }

    /** 两小时的量级也要成立（长音频/演唱会）。 */
    @Test
    fun `两小时处的字幕同样保留`() {
        val lrc = BiliSubtitle.lyricLrc(listOf(cue((150 * 60 + 30) * 1_000L, "两小时半")))
        val lines = LrcParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals((150 * 60 + 30) * 1_000L, lines.single().timeMs)
    }
}
