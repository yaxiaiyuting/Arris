/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.player.MediaDisplayLines.Lines
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.6.0：媒体面板/通知两行文案（用户反馈「歌词与歌名位置反了」的修正）。 */
class MediaDisplayLinesTest {

    @Test
    fun `有歌词——第一行歌词，第二行歌名 · 艺人`() {
        assertEquals(
            Lines("You have a mental problems", "67 · DJ R4"),
            MediaDisplayLines.of("67", "DJ R4", "You have a mental problems")
        )
    }

    @Test
    fun `没歌词——回到歌名 与 艺人（与 v1_5_0 逐字节一致）`() {
        assertEquals(Lines("67", "DJ R4"), MediaDisplayLines.of("67", "DJ R4", null))
    }

    @Test
    fun `空白歌词行当作没有歌词`() {
        assertEquals(Lines("67", "DJ R4"), MediaDisplayLines.of("67", "DJ R4", "   "))
        assertEquals(Lines("67", "DJ R4"), MediaDisplayLines.of("67", "DJ R4", ""))
    }

    @Test
    fun `艺人缺失时第二行不留「歌名 · 」的尾巴`() {
        // 第一行仍是歌词，第二行退化成「只有歌名」
        assertEquals(Lines("啦啦啦", "纯音乐"), MediaDisplayLines.of("纯音乐", "", "啦啦啦"))
        assertEquals(Lines("啦啦啦", "纯音乐"), MediaDisplayLines.of("纯音乐", "   ", "啦啦啦"))
    }

    @Test
    fun `歌名缺失时第二行只写艺人`() {
        assertEquals(Lines("啦啦啦", "DJ R4"), MediaDisplayLines.of("", "DJ R4", "啦啦啦"))
    }

    @Test
    fun `歌名与艺人都缺失时第二行为空串（面板自己隐藏空行）`() {
        assertEquals(Lines("啦啦啦", ""), MediaDisplayLines.of("", "", "啦啦啦"))
    }

    @Test
    fun `歌词里的首尾空格要保留原样（不 trim 内容，只判空）`() {
        assertEquals(" 唱 词 ", MediaDisplayLines.of("a", "b", " 唱 词 ").title)
    }

    @Test
    fun `歌词行很长时不截断——截断交给系统面板`() {
        val long = "x".repeat(300)
        assertEquals(long, MediaDisplayLines.of("a", "b", long).title)
    }
}
