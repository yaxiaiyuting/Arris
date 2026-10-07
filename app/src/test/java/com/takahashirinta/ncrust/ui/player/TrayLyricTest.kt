/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · E：竖屏托盘第一行（实时歌词）更新判据的守卫单测。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.lyric.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「托盘第一行显示哪一句」+「什么时候**不**更新」。
 *
 * ## 这组用例在防什么
 *
 * 任务书的红线是「**行级即可，不许逐字高频更新**」。这条要求有两半：
 *
 * 1. **取哪一行**必须与歌词面板同源（同一个二分查找），否则托盘的歌词会比面板
 *    慢/快一行 —— 这是用户一眼能看见的错；
 * 2. **什么时候产出新值**必须是行边界。第 2 半在这里用
 *    [TrayLyric.sameLineAcrossPositionSamples] 那段循环钉住：
 *    同一行内连打 20 个采样点，结果串必须恒等 ⇒ 上层的 `distinctUntilChanged()`
 *    会把 20 次产出压成 1 次 ⇒ 组件在这段时间里零重组。
 */
class TrayLyricTest {

    private fun lines(vararg pairs: Pair<Long, String>) =
        pairs.map { LrcLine(timeMs = it.first, text = it.second) }

    private val sample = lines(
        0L to "作词 : 某人",
        5_000L to "第一句",
        10_000L to "第二句",
        15_000L to "  ",
        20_000L to "第四句",
    )

    private fun at(positionMs: Long) = TrayLyric.lineAt(sample, TrayLyric.timestampsOf(sample), positionMs)

    // ------------------------------------------------------------ 取行 ----

    @Test
    fun `前奏阶段（早于第一行）没有可显示的行`() {
        val later = lines(5_000L to "第一句")
        assertNull(TrayLyric.lineAt(later, TrayLyric.timestampsOf(later), 0L))
        assertNull(TrayLyric.lineAt(later, TrayLyric.timestampsOf(later), 4_999L))
    }

    @Test
    fun `恰好落在行首时立刻切到该行`() {
        assertEquals("第一句", at(5_000L))
        assertEquals("第二句", at(10_000L))
    }

    @Test
    fun `跨行前一刻仍是上一行`() {
        assertEquals("第一句", at(9_999L))
        assertEquals("第二句", at(14_999L))
    }

    @Test
    fun `晚于最后一行时停在最后一行`() {
        assertEquals("第四句", at(20_000L))
        assertEquals("第四句", at(999_999L))
    }

    @Test
    fun `纯空白的当前行返回 null（LRC 里确实有这种占位行）`() {
        assertNull(at(15_000L))
    }

    @Test
    fun `行文本做 trim 后返回`() {
        val padded = lines(0L to "  前后有空格  ")
        assertEquals("前后有空格", TrayLyric.lineAt(padded, TrayLyric.timestampsOf(padded), 0L))
    }

    // ---------------------------------------------------------- 边界 ----

    @Test
    fun `空歌词列表返回 null`() {
        assertNull(TrayLyric.lineAt(emptyList(), LongArray(0), 1_000L))
    }

    @Test
    fun `负位置返回 null（异常输入不猜）`() {
        assertNull(at(-1L))
    }

    @Test
    fun `时间戳数组与行数不等时返回 null（两个上游短暂错位）`() {
        // combine(歌词, 位置) 的两个上游在切歌瞬间可能错位 —— 宁可这一帧不显示，
        // 也不要拿旧的 timestamps 去索引新的 lines（那会显示错行的歌词）。
        assertNull(TrayLyric.lineAt(sample, LongArray(2), 6_000L))
        assertNull(TrayLyric.lineAt(sample, LongArray(0), 6_000L))
    }

    // ------------------------------------------------ 行级节流（核心） ----

    @Test
    fun `同一行内连打 20 个采样点结果串恒等（行级节流可证明）`() {
        val timestamps = TrayLyric.timestampsOf(sample)
        val first = TrayLyric.lineAt(sample, timestamps, 5_000L)
        for (p in 5_000L..9_900L step 250L) {
            assertEquals(
                "位置 $p 仍在第一句里，结果不该变化 —— 变了说明上游在逐帧/逐字刷新",
                first,
                TrayLyric.lineAt(sample, timestamps, p),
            )
        }
    }

    @Test
    fun `整首歌走一遍，产出序列恰好等于歌词行序列（不多不少）`() {
        val timestamps = TrayLyric.timestampsOf(sample)
        val produced = ArrayList<String?>()
        var last: String? = null
        var started = false
        // 模拟 2Hz 采样走完整首歌（0..25000ms，步长 500ms）。
        var p = 0L
        while (p <= 25_000L) {
            val v = TrayLyric.lineAt(sample, timestamps, p)
            if (!started || v != last) {
                produced.add(v)
                last = v
                started = true
            }
            p += 500L
        }
        // 5 个不同取值：作词 / 第一句 / 第二句 / null(空行) / 第四句
        // （首行时间戳是 0，所以没有"前奏 null"这一段 —— 这条断言同时锁住了这一点。）
        assertEquals(5, produced.size)
        assertEquals(listOf("作词 : 某人", "第一句", "第二句", null, "第四句"), produced)
    }

    @Test
    fun `50 次采样只产出 5 次新值（节流比 10 比 1）`() {
        val timestamps = TrayLyric.timestampsOf(sample)
        var changes = 0
        var last: String? = null
        var started = false
        var p = 0L
        while (p <= 25_000L) {
            val v = TrayLyric.lineAt(sample, timestamps, p)
            if (!started || v != last) {
                changes++
                last = v
                started = true
            }
            p += 500L
        }
        // 51 个采样点（0..25000 步长 500）只该产出 5 次新值 ——
        // 这就是「行级节流」的可执行版本：10 次采样换 1 次重组。
        assertEquals(5, changes)
    }

    // ------------------------------------------------------ 时间戳数组 ----

    @Test
    fun `timestampsOf 与行一一对应且保持升序`() {
        val ts = TrayLyric.timestampsOf(sample)
        assertEquals(sample.size, ts.size)
        assertEquals(0L, ts[0])
        assertEquals(20_000L, ts[ts.size - 1])
        for (i in 1 until ts.size) {
            assertEquals(true, ts[i] >= ts[i - 1])
        }
    }

    @Test
    fun `托盘与歌词面板用的是同一个二分查找`() {
        // 直接对着 `currentLineIndex`（面板与逐字窗口共用的那一份）比对，
        // 防止有人给托盘另写一份线性扫描。
        val ts = TrayLyric.timestampsOf(sample)
        for (p in listOf(0L, 4_999L, 5_000L, 9_999L, 10_000L, 19_999L, 25_000L)) {
            val idx = currentLineIndex(p, ts)
            val expected = if (idx in sample.indices) sample[idx].text.trim().takeIf { it.isNotEmpty() } else null
            assertEquals("位置 $p 与面板口径不一致", expected, TrayLyric.lineAt(sample, ts, p))
        }
    }
}
