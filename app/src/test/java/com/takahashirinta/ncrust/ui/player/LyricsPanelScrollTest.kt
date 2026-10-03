/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.1 hotfix：大屏歌首「歌词被拉到最顶端、第一句看不见」的滚动定位纯逻辑。
 *
 * 所有视口数值都来自 PCL110（2800×1272px / density 560 = 3.5）实测：
 *  - 竖屏全屏播放器的歌词面板约 700dp 高（2450px）；
 *  - 大屏模式右栏的歌词面板约 280dp 高（980px）—— 200dp 留白在里面占 71%，这就是 bug 的几何来源。
 */
class LyricsPanelScrollTest {

    /** PCL110 的 density。 */
    private val d = 3.5f
    private fun px(dp: Float) = dp * d

    // ---------- ① 顶部留白：竖屏逐像素不变 ----------

    @Test
    fun `portrait tall panel keeps the original 200dp spacer`() {
        // 700dp 视口：36% = 252dp > 200dp ⇒ 保持 200dp，与 v2.0.0 完全一致
        val vh = px(700f).toInt()          // 2450
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(vh, px(200f)), 0.01f)
    }

    @Test
    fun `boundary is 200dp divided by 036 dp`() {
        // 200 / 0.36 ≈ 555.6dp：之上的视口都不动，之下才开始收窄
        val above = px(556f).toInt()
        val below = px(555f).toInt()
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(above, px(200f)), 0.01f)
        assertTrue(LyricsPanelScroll.topSpacerHeightPx(below, px(200f)) < px(200f))
    }

    // ---------- ② 大屏短面板：留白必须收窄，否则第一句被推出可视区 ----------

    @Test
    fun `big screen short panel shrinks the spacer to the lead slot`() {
        // 280dp 面板（大屏右栏实测量级）：留白从 200dp 收到 100.8dp
        val vh = px(280f).toInt()          // 980
        val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, px(200f))
        assertEquals(vh * LyricsPanelScroll.LEAD_FRACTION, spacer, 0.5f)
        // 第一句落在 36% 处 ⇒ 在渐隐带（min(100dp, 30%×视口) = 84dp）下方，肉眼可见
        assertTrue(spacer > vh * 0.30f)
    }

    @Test
    fun `spacer never exceeds the lead fraction of the viewport`() {
        // 不变量 1：任何视口下「列表在最顶端」都必须让第一句出现在 36% 以内。
        // 这条一旦破掉，「回顶」就会把第一句推出可视区 —— 正是本 bug。
        for (vhDp in intArrayOf(120, 200, 240, 279, 363, 420, 555, 556, 700, 900)) {
            val vh = px(vhDp.toFloat()).toInt()
            val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, px(200f))
            assertTrue(
                "视口 ${vhDp}dp 时留白 $spacer 超过了 36% 视口",
                spacer <= vh * LyricsPanelScroll.LEAD_FRACTION + 0.01f,
            )
        }
    }

    @Test
    fun `unknown viewport falls back to the base spacer`() {
        // 首帧 / 无界约束：回落 200dp（回落 0 会让第一句贴顶）
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(0, px(200f)), 0.01f)
        assertEquals(px(200f), LyricsPanelScroll.topSpacerHeightPx(-1, px(200f)), 0.01f)
    }

    // ---------- ③ 定位 offset：与 v1.5.0 起的语义逐值一致 ----------

    @Test
    fun `lead offset keeps the original rounding`() {
        assertEquals(-(700 * 0.36f).toInt(), LyricsPanelScroll.leadOffsetPx(700))
        assertEquals(-252, LyricsPanelScroll.leadOffsetPx(700))
        assertEquals(-100, LyricsPanelScroll.leadOffsetPx(280))
        assertEquals(0, LyricsPanelScroll.leadOffsetPx(0))
    }

    // ---------- ④ 「回顶」与「首句落在 36%」必须是同一个位置 ----------

    @Test
    fun `scroll to top equals positioning line zero at the lead slot`() {
        // LazyColumn 的 scrollToItem(item, offset) 语义：目标 item 出现在 -offset 处；
        // item 1 之上只有 topSpacer，所以它要落到 36% 视口处需要的滚动量是 spacer - 36%×vh。
        // 不变量：这个量 <= 0（即被夹到 0）⇒ 两种定位方式给出同一个位置，不会来回跳。
        // 容差 1px：[leadOffsetPx] 用 .toInt() 向下取整（与 v1.5.0 起逐值一致），
        // 所以"回顶"与"定位"最多差不到 1px，肉眼与 LazyColumn 的整数滚动量都吃不出来。
        for (vhDp in intArrayOf(120, 240, 279, 363, 700)) {
            val vh = px(vhDp.toFloat()).toInt()
            val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, px(200f))
            val neededScroll = spacer - (-LyricsPanelScroll.leadOffsetPx(vh)).toFloat()
            assertTrue(
                "视口 ${vhDp}dp：定位首句需要滚动 $neededScroll px（应为 <1px，即与回顶等价）",
                neededScroll < 1f,
            )
        }
    }

    // ---------- ⑤ 行下标 → item 下标：-1（还没到第一句）按第一句算 ----------

    @Test
    fun `before the first line targets line zero`() {
        // currentLineIndex 在第一句之前返回 -1；面板这时要摆的是"第一句"，不是"列表顶端"
        assertEquals(1, LyricsPanelScroll.targetItemIndex(-1, 66))
    }

    @Test
    fun `normal indices shift by the top spacer`() {
        assertEquals(1, LyricsPanelScroll.targetItemIndex(0, 66))
        assertEquals(6, LyricsPanelScroll.targetItemIndex(5, 66))
        assertEquals(66, LyricsPanelScroll.targetItemIndex(65, 66))
    }

    @Test
    fun `out of range indices are clamped into the list`() {
        // 切歌瞬间可能还带着上一首的行号：钳到列表内，绝不越界
        assertEquals(66, LyricsPanelScroll.targetItemIndex(999, 66))
        assertEquals(1, LyricsPanelScroll.targetItemIndex(-99, 66))
    }

    @Test
    fun `empty line list does not throw`() {
        assertEquals(0, LyricsPanelScroll.targetItemIndex(-1, 0))
        assertEquals(0, LyricsPanelScroll.targetItemIndex(3, 0))
    }

    // ================================================================ v2.3.0 · E
    // 横屏 / 大屏右栏的定位目标：视口**正中**（0.5），而不是竖屏的 0.36。
    // 探针（docs/verification/v2.3.0/probe-lyric-landscape.md）给的是几何事实：
    // 大屏右栏面板约 280dp 高 ⇒ 0.36 × 280 ≈ 100dp，当前行几乎贴着顶部渐隐带。

    @Test
    fun `横屏目标比例是视口正中`() {
        assertEquals(0.5f, LyricsPanelScroll.CENTER_FRACTION, 1e-6f)
    }

    @Test
    fun `竖屏目标比例仍是 0_36（一个像素都不动）`() {
        assertEquals(0.36f, LyricsPanelScroll.LEAD_FRACTION, 1e-6f)
    }

    @Test
    fun `横屏定位 offset 落在视口一半处`() {
        val vh = (280 * d).toInt()
        assertEquals(-(vh * 0.5f).toInt(), LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.CENTER_FRACTION))
    }

    @Test
    fun `横屏顶部留白与竖屏一样被夹取 且等于定位点`() {
        val vh = (280 * d).toInt()
        val spacer = LyricsPanelScroll.topSpacerHeightPx(vh, 200f * d, LyricsPanelScroll.CENTER_FRACTION)
        // 不变量 1：留白 <= CENTER_FRACTION × 视口 ⇒「回顶」与「首句落在正中」是同一个位置
        assertTrue(spacer <= vh * LyricsPanelScroll.CENTER_FRACTION + 1f)
        assertEquals(vh * LyricsPanelScroll.CENTER_FRACTION, spacer, 1f)
    }

    @Test
    fun `横屏留白仍在顶部渐隐带之下（0_30 小于 0_5）`() {
        // LyricsView 的渐隐高 = min(100dp, 30% 视口)，所以首句必须落在 30% 以下。
        assertTrue(LyricsPanelScroll.CENTER_FRACTION > 0.30f)
        assertTrue(LyricsPanelScroll.LEAD_FRACTION > 0.30f)
    }

    @Test
    fun `默认参数等价于竖屏行为（既有调用点零改动）`() {
        val vh = (700 * d).toInt()
        assertEquals(
            LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.LEAD_FRACTION),
            LyricsPanelScroll.leadOffsetPx(vh),
        )
        assertEquals(
            LyricsPanelScroll.topSpacerHeightPx(vh, 200f * d, LyricsPanelScroll.LEAD_FRACTION),
            LyricsPanelScroll.topSpacerHeightPx(vh, 200f * d),
        )
    }

    @Test
    fun `横屏短视口下正中比 36 百分比更靠下（这就是修的位置）`() {
        val vh = (280 * d).toInt()
        val center = -LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.CENTER_FRACTION)
        val lead = -LyricsPanelScroll.leadOffsetPx(vh, LyricsPanelScroll.LEAD_FRACTION)
        assertTrue(center > lead)
        assertEquals((center - lead).toFloat(), vh * 0.14f, 2f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v2.5.2：**整条歌词**居中（用户报「自动居中只是把第一行居中」）
    //
    // 一个 LazyColumn 条目不是「一行」，而是 `Box(padding 10dp) { Column { 原句、译文、音译 } }`。
    // 大屏模式右栏视口只有约 232dp，三行条目却接近 112dp —— 把**顶边**摆在正中的旧语义
    // 会让整条一直铺到视口底部（视觉中点在 74% 处），行数越多偏得越狠。
    // ═══════════════════════════════════════════════════════════════════════

    /** 大屏模式右栏的实测视口（PCL110：约 232dp 高）。 */
    private val bigScreenVh = px(232f).toInt()

    /** 条目高度（dp → px）：单行 / 原句+译文 / 原句+译文+音译。 */
    private fun itemH(rows: Int) = px(
        when (rows) {
            1 -> 62f
            2 -> 88f
            else -> 112f
        }
    ).toInt()

    private fun fade(vh: Int) = LyricsPanelScroll.fadeHeightPx(vh, px(100f), px(24f))

    @Test
    fun `回归本体——大屏三行条目：整条中点接近视口正中，而旧语义在 74 百分比处`() {
        val vh = bigScreenVh
        val h = itemH(3)
        val top = LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.CENTER_FRACTION, h, fade(vh))
        val blockCenter = top + h / 2f
        // 新：整条中点 ≈ 54%（被顶部渐隐带夹住了一点点，见下一条用例）
        assertTrue("整条中点应在 50%~56%，实测 ${blockCenter / vh}", blockCenter / vh in 0.50f..0.56f)
        // 旧：顶边落在正中 ⇒ 整条中点 = 0.5 + h/2/vh ≈ 74%
        val legacyCenter = vh * LyricsPanelScroll.CENTER_FRACTION + h / 2f
        assertTrue("旧语义确实在 73% 之外（这就是用户看到的偏）", legacyCenter / vh > 0.73f)
        // 改善幅度：至少往上挪 40dp
        assertTrue((legacyCenter - blockCenter) > px(40f))
    }

    @Test
    fun `单行条目在横屏下严格落在正中`() {
        val vh = bigScreenVh
        val h = itemH(1)
        val top = LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.CENTER_FRACTION, h, fade(vh))
        assertEquals(vh * 0.5f, top + h / 2f, 1f)
    }

    @Test
    fun `竖屏 36 百分比语义不变——只是从顶边改成整条中点`() {
        val vh = px(700f).toInt()
        val h = itemH(3)
        val top = LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.LEAD_FRACTION, h, fade(vh))
        assertEquals(vh * LyricsPanelScroll.LEAD_FRACTION, top + h / 2f, 1f)
        // 竖屏视口高，渐隐带（100dp）夹不到它
        assertTrue(top > fade(vh))
    }

    @Test
    fun `顶边永远不进顶部渐隐带——宁可不完美居中，也不能让当前行发灰`() {
        val vh = bigScreenVh
        for (rows in 1..3) {
            val h = itemH(rows)
            val top = LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.CENTER_FRACTION, h, fade(vh))
            assertTrue("$rows 行的顶边 $top 落进了渐隐带 ${fade(vh)}", top >= fade(vh))
        }
    }

    @Test
    fun `条目比视口还高时退化成顶对齐（滚动容器通用行为）`() {
        val vh = bigScreenVh
        val h = vh * 2
        val top = LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.CENTER_FRACTION, h, fade(vh))
        assertEquals(fade(vh), top, 1f)
        assertTrue("顶边不得为负", top >= 0f)
    }

    @Test
    fun `高度未知时逐字节回落 v2_5_1 的顶边语义（这是「还没量到」的中间态）`() {
        val vh = bigScreenVh
        for (f in listOf(LyricsPanelScroll.LEAD_FRACTION, LyricsPanelScroll.CENTER_FRACTION)) {
            assertEquals(
                LyricsPanelScroll.leadOffsetPx(vh, f),
                LyricsPanelScroll.blockOffsetPx(vh, f, -1, fade(vh)),
            )
            assertEquals(
                LyricsPanelScroll.leadOffsetPx(vh, f),
                LyricsPanelScroll.blockOffsetPx(vh, f, 0, fade(vh)),
            )
        }
    }

    @Test
    fun `视口未知时 offset 仍是 0（首帧兜底不回归）`() {
        assertEquals(0, LyricsPanelScroll.blockOffsetPx(0, LyricsPanelScroll.CENTER_FRACTION, itemH(3), 0f))
    }

    @Test
    fun `offset 与 blockTopPx 同号相反（负数=内容上移）`() {
        val vh = px(700f).toInt()
        val h = itemH(2)
        val off = LyricsPanelScroll.blockOffsetPx(vh, LyricsPanelScroll.LEAD_FRACTION, h, fade(vh))
        assertTrue(off < 0)
        assertEquals(-LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.LEAD_FRACTION, h, fade(vh)).toInt(), off)
    }

    @Test
    fun `纠正量：方向正确，且已经摆正时严格为 0（不许做无意义滚动）`() {
        val vh = bigScreenVh
        val h = itemH(3)
        val desired = LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.CENTER_FRACTION, h, fade(vh)).toInt()
        // 旧语义把它摆在了正中（顶边），要往上纠正 ⇒ 正数
        val legacyTop = (vh * LyricsPanelScroll.CENTER_FRACTION).toInt()
        val corr = LyricsPanelScroll.blockCorrectionPx(
            vh, LyricsPanelScroll.CENTER_FRACTION, h, fade(vh), legacyTop,
        )
        assertEquals(legacyTop - desired, corr)
        assertTrue("旧位置在新位置下方 ⇒ 需上移（正数）", corr > 0)
        // 已经摆正 ⇒ 0：无意义滚动会把 isScrollInProgress 翻起来，误触发「用户滚动了」
        assertEquals(
            0,
            LyricsPanelScroll.blockCorrectionPx(vh, LyricsPanelScroll.CENTER_FRACTION, h, fade(vh), desired),
        )
        // 高度/视口未知 ⇒ 0
        assertEquals(0, LyricsPanelScroll.blockCorrectionPx(vh, LyricsPanelScroll.CENTER_FRACTION, -1, fade(vh), 0))
        assertEquals(0, LyricsPanelScroll.blockCorrectionPx(0, LyricsPanelScroll.CENTER_FRACTION, h, 0f, 0))
    }

    @Test
    fun `条目放得下时整条不会越过视口底边`() {
        val vh = px(700f).toInt()
        for (rows in 1..3) {
            val h = itemH(rows)
            val top = LyricsPanelScroll.blockTopPx(vh, LyricsPanelScroll.LEAD_FRACTION, h, fade(vh))
            assertTrue("$rows 行的底边越界", top + h <= vh)
        }
    }

    // ---------- 渐隐带：v2.5.2 起抽成唯一落点 ----------

    @Test
    fun `渐隐带高度逐值与 v1_7_0 的内联公式一致（数值不许漂）`() {
        for (vhDp in listOf(60f, 120f, 210f, 232f, 280f, 400f, 556f, 700f, 900f)) {
            val vh = px(vhDp).toInt()
            val legacy = minOf(px(100f), vh * 0.3f).coerceAtLeast(px(24f))
            assertEquals("视口 ${vhDp}dp 的渐隐高度漂了", legacy, fade(vh), 0.01f)
        }
    }

    @Test
    fun `渐隐带：竖屏仍是 100dp、短视口按 30 百分比收窄、极短视口吃下限`() {
        assertEquals(px(100f), fade(px(700f).toInt()), 0.01f)
        assertEquals(px(232f) * 0.3f, fade(px(232f).toInt()), 0.01f)
        assertEquals(px(24f), fade(px(50f).toInt()), 0.01f)   // 15dp < 24dp 下限
    }

    @Test
    fun `渐隐带：视口未知时回落基准值（首帧不闪烁）`() {
        assertEquals(px(100f), fade(0), 0.01f)
    }
}
