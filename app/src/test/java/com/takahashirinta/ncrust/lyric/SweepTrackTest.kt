/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2 · 逐字扫过轨道（[SweepTrack]）的单测。
 *
 * 这里锁死的是 v1.5.1 在真机上被用户看出来的那个问题：**光标按词跳变而不是连续推进**。
 * 见 [词内必须连续推进_而不是只在词边界跳一格] —— 那条用例在 v1.5.1 的实机上会读到
 * 「词起始位置」，因为绘制阶段拿到的位置被冻在词边界时刻，词内插值那段代码从来没被喂到中间值。
 */
class SweepTrackTest {

    /**
     * 假字形几何：等宽排版，第 [charsPerLine] 个字符换行，每字符 [charWidth] 像素。
     * 行间距用 [lineAdvance] 表达（轨道只关心行号，上下沿由渲染层直接问 TextLayoutResult）。
     */
    private class FakeGeometry(
        private val charCount: Int,
        private val charsPerLine: Int,
        private val charWidth: Float = 10f,
        override val rtl: Boolean = false,
    ) : SweepGeometry {

        override fun lineForChar(charIndex: Int): Int =
            (charIndex.coerceIn(0, (charCount - 1).coerceAtLeast(0))) / charsPerLine

        override fun lineStart(lineIndex: Int): Float =
            if (rtl) lineEndRaw(lineIndex) else 0f

        override fun lineEnd(lineIndex: Int): Float =
            if (rtl) 0f else lineEndRaw(lineIndex)

        override fun charStart(charIndex: Int): Float {
            val i = charIndex.coerceIn(0, (charCount - 1).coerceAtLeast(0))
            val slot = i % charsPerLine
            val base = (slot * charWidth)
            return if (rtl) lineEndRaw(i / charsPerLine) - base else base
        }

        override fun charEnd(charIndex: Int): Float {
            val i = charIndex.coerceIn(0, (charCount - 1).coerceAtLeast(0))
            return if (rtl) {
                charStart(i) - charWidth
            } else {
                charStart(i) + charWidth
            }
        }

        private fun lineEndRaw(lineIndex: Int): Float {
            val first = lineIndex * charsPerLine
            val count = (charCount - first).coerceIn(0, charsPerLine)
            return count * charWidth
        }
    }

    private fun word(startMs: Long, durationMs: Long, from: Int, toExclusive: Int) =
        LrcWord(startMs, durationMs, "x", from, toExclusive)

    // ---------------------------------------------------------------- 核心回归

    /**
     * **v1.5.1 的病灶**：一个词从 t=1000 唱到 t=1400，字符区间 [0,4)，x 从 0 到 40。
     * 词内任意时刻都必须给出**中间位置**，而不是「词起始位置 / 词结束位置」二选一。
     *
     * 旧实现（playedCutCharCount 的调用方只在词边界写位置）在 t=1200 会得到 x=0；
     * 这条用例要求它是 20 —— 也就是「光标与播放进度精确同步」。
     */
    @Test
    fun 词内必须连续推进_而不是只在词边界跳一格() {
        val geo = FakeGeometry(charCount = 10, charsPerLine = 10)
        val track = SweepTrack.build(
            words = listOf(word(1000, 400, 0, 4)),
            textLength = 10,
            endMs = 1400,
            geo = geo,
            fadePx = 0f,
        )
        assertNotNull(track)

        assertEquals(0f, track!!.sample(1000)!!.x, 0.01f)
        assertEquals(10f, track.sample(1100)!!.x, 0.01f)
        assertEquals(20f, track.sample(1200)!!.x, 0.01f)
        assertEquals(30f, track.sample(1300)!!.x, 0.01f)
        assertEquals(40f, track.sample(1400)!!.x, 0.01f)

        // 采样点必须逐个递增（单调），否则画面上就是「跳一下、停一下」。
        var prev = -1f
        for (t in 1000L..1400L step 25L) {
            val x = track.sample(t)!!.x
            assertTrue("t=$t 处光标回退：$prev -> $x", x >= prev - 0.001f)
            prev = x
        }
    }

    /** 相邻两个词之间位置必须连续：跨过词边界时的位移不能超过一个词间距的量级。 */
    @Test
    fun 跨词边界位置连续_没有瞬时跳变() {
        val geo = FakeGeometry(charCount = 12, charsPerLine = 12)
        // 词 1：char[0,3) t=0..300；停顿 300..900；词 2：char[6,9) t=900..1200。
        val track = SweepTrack.build(
            words = listOf(word(0, 300, 0, 3), word(900, 300, 6, 9)),
            textLength = 12,
            endMs = 1200,
            geo = geo,
            fadePx = 0f,
        )!!
        val before = track.sample(899)!!.x
        val after = track.sample(901)!!.x
        assertTrue("跨词边界跳了 ${after - before} px", after - before < 1.0f)
        // 停顿区间内光标是在慢慢走的（AMLL 是冻住再跳），所以 899 已经贴近第二个词的起点。
        assertEquals(60f, track.sample(900)!!.x, 0.01f)
    }

    /** 早于第一个词 → null（这一行还没开口，高亮层整层不画，底层就是未唱色）。 */
    @Test
    fun 早于首词返回null() {
        val geo = FakeGeometry(charCount = 4, charsPerLine = 4)
        val track = SweepTrack.build(listOf(word(5000, 400, 0, 4)), 4, 5400, geo, 0f)!!
        assertNull(track.sample(0))
        assertNull(track.sample(4999))
        assertNotNull(track.sample(5000))
    }

    /** 晚于最后一个节点 → 停在末位置，不再移动（唱完的行不该继续飘）。 */
    @Test
    fun 唱完后停在末位置() {
        val geo = FakeGeometry(charCount = 4, charsPerLine = 4)
        val track = SweepTrack.build(listOf(word(0, 400, 0, 4)), 4, 400, geo, 0f)!!
        val atEnd = track.sample(400)!!.x
        assertEquals(atEnd, track.sample(60_000)!!.x, 0.001f)
    }

    /**
     * **v1.5.2 真机 bug 回归 ①**：相邻词之间绝不能出现「倒扫」。
     *
     * 初版把「首尾各外扩半个渐变带」错用到了**每一个**词：w_i 的末节点落在 charEnd_i + 半带、
     * w_{i+1} 的首节点落在 charStart_{i+1} - 半带，于是每个词间隙光标都要**后退一整个渐变带宽度**，
     * 画面上就是高亮唱完一个词又倒着缩回去一点。折行处词间隙更长，倒扫更明显。
     */
    @Test
    fun 相邻词之间不得倒扫() {
        val geo = FakeGeometry(charCount = 12, charsPerLine = 12)
        val track = SweepTrack.build(
            words = listOf(word(0, 300, 0, 3), word(300, 300, 3, 6), word(600, 300, 6, 9)),
            textLength = 12,
            endMs = 900,
            geo = geo,
            fadePx = 24f,
        )!!
        assertEquals("整行首词要外扩半个渐变带", -12f, track.sample(0)!!.x, 0.01f)
        assertEquals("整行末词要外扩半个渐变带", 102f, track.sample(900)!!.x, 0.01f)
        var prev = Float.NEGATIVE_INFINITY
        for (t in 0L..900L step 5L) {
            val x = track.sample(t)!!.x
            assertTrue("t=$t 光标从 $prev 倒退到 $x（词间隙倒扫）", x >= prev - 0.001f)
            prev = x
        }
        // 中间词的边界正好落在字符边界上：不能带外扩。
        assertEquals(30f, track.sample(300)!!.x, 0.01f)
        assertEquals(60f, track.sample(600)!!.x, 0.01f)
    }

    /**
     * **v1.5.2 真机 bug 回归 ②**：软换行（一句话占两行）处不得把光标插值回下一行行首。
     *
     * 折行时上一行末词的 x 接近行尾、下一行首词的 x 接近行首，直接插值等于让整行高亮
     * 从行尾倒着扫回行首 —— 用户描述为「这句话的特效又从头播了一遍、歌词对不上」。
     * 正确行为是：**停在上一行行尾**，等下一行的词真的开始时再瞬移过去。
     */
    @Test
    fun 折行处停在上一行行尾不得倒扫回行首() {
        val geo = FakeGeometry(charCount = 20, charsPerLine = 10)
        // 词 1 在第 0 行末尾 char[7,10)，词 2 在第 1 行开头 char[10,13)，中间有 600ms 停顿。
        val track = SweepTrack.build(
            words = listOf(word(0, 300, 7, 10), word(900, 300, 10, 13)),
            textLength = 20,
            endMs = 1200,
            geo = geo,
            fadePx = 0f,
        )!!
        assertEquals(0, track.sample(300)!!.lineIndex)
        assertEquals(100f, track.sample(300)!!.x, 0.01f)
        // 停顿区间：停在**第 0 行的行尾 100px**，而不是往第 1 行的行首 0px 倒着插值。
        assertEquals(0, track.sample(600)!!.lineIndex)
        assertEquals(100f, track.sample(600)!!.x, 0.01f)
        assertEquals(100f, track.sample(899)!!.x, 0.01f)
        // 下一个词真正开始的时刻才瞬移到第 1 行。
        assertEquals(1, track.sample(900)!!.lineIndex)
        assertEquals(0f, track.sample(900)!!.x, 0.01f)
        assertEquals(30f, track.sample(1200)!!.x, 0.01f)
        // 同一视觉行内光标必须单调不减 —— 这条不变量同时覆盖上面两条回归。
        var prevLine = -1
        var prevX = Float.NEGATIVE_INFINITY
        for (t in 0L..1200L step 5L) {
            val s = track.sample(t)!!
            if (s.lineIndex == prevLine) {
                assertTrue("第 $prevLine 行内 t=$t 倒退：$prevX -> ${s.x}", s.x >= prevX - 0.001f)
            }
            prevLine = s.lineIndex
            prevX = s.x
        }
    }

    // ---------------------------------------------------------------- 行尾收束

    /** yrc 的词没铺满展示文本（行尾标点 / 尾音留白）时，光标要匀速走到行尾，而不是停在半路。 */
    @Test
    fun 行尾收束_光标走到行尾且封顶700ms() {
        val geo = FakeGeometry(charCount = 12, charsPerLine = 12)
        // 词只覆盖 char[0,4)，行结束时刻在 5000ms（离末词 4600ms，远超封顶）。
        val track = SweepTrack.build(listOf(word(0, 400, 0, 4)), 12, 5000, geo, 0f)!!
        val tail = track.sample(60_000)!!
        assertEquals("必须贴到行尾 120px", 120f, tail.x, 0.01f)
        // 封顶 700ms：到 1100ms 就已经在行尾了，不会 4.6 秒才慢慢蹭过去。
        assertEquals(120f, track.sample(400 + SweepTrack.TERMINAL_MAX_MS)!!.x, 0.01f)
        assertTrue(track.sample(700)!!.x < 120f)
    }

    /** 词的结束时刻已越过行结束时刻时，不加终止节点（不能凭空多出一段位移）。 */
    @Test
    fun 行结束早于末词结束时不加终止节点() {
        val geo = FakeGeometry(charCount = 12, charsPerLine = 12)
        val track = SweepTrack.build(listOf(word(0, 900, 0, 4)), 12, 500, geo, 0f)!!
        assertEquals(40f, track.sample(900)!!.x, 0.01f)
        assertEquals(40f, track.sample(99_999)!!.x, 0.01f)
    }

    @Test
    fun 无行结束时刻时末词唱完即停() {
        val geo = FakeGeometry(charCount = 12, charsPerLine = 12)
        val track = SweepTrack.build(listOf(word(0, 400, 0, 4)), 12, null, geo, 0f)!!
        assertEquals(40f, track.sample(99_999)!!.x, 0.01f)
    }

    // ---------------------------------------------------------------- 首尾外扩

    /** 首词向左外扩半个渐变带、末词向右外扩半个 —— 让渐变带完整地滑入 / 滑出整行。 */
    @Test
    fun 首尾各外扩半个渐变带() {
        val geo = FakeGeometry(charCount = 8, charsPerLine = 8)
        val track = SweepTrack.build(listOf(word(0, 400, 0, 8)), 8, 400, geo, 20f)!!
        assertEquals(-10f, track.sample(0)!!.x, 0.01f)
        assertEquals(90f, track.sample(400)!!.x, 0.01f)
    }

    // ---------------------------------------------------------------- 折行

    /** 词被软换行劈成两截：前半截把渐变带送出上一行行尾，后半截从下一行行首外滑入。 */
    @Test
    fun 跨行词在换行处折返() {
        val geo = FakeGeometry(charCount = 20, charsPerLine = 10)
        // char[8,12) 横跨第 0 行（0..9）与第 1 行（10..19）。
        val track = SweepTrack.build(listOf(word(0, 400, 8, 12)), 20, 400, geo, 0f)!!
        assertEquals(0, track.sample(0)!!.lineIndex)
        assertEquals(80f, track.sample(0)!!.x, 0.01f)
        assertEquals(0, track.sample(199)!!.lineIndex)
        assertEquals(100f, track.sample(200)!!.x, 0.01f)
        assertEquals(1, track.sample(201)!!.lineIndex)
        assertEquals(0f, track.sample(201)!!.x, 0.01f)
        assertEquals(1, track.sample(400)!!.lineIndex)
        assertEquals(20f, track.sample(400)!!.x, 0.01f)
    }

    // ---------------------------------------------------------------- 方向 / 缓动 / 渐变带

    /** RTL：位置随时间**递减**，且「已唱实心区」在渐变带的右侧。 */
    @Test
    fun RTL下位置递减且实心区在右() {
        val geo = FakeGeometry(charCount = 8, charsPerLine = 8, rtl = true)
        val track = SweepTrack.build(listOf(word(0, 400, 0, 4)), 8, 400, geo, 0f)!!
        val a = track.sample(0)!!
        val b = track.sample(400)!!
        assertTrue("RTL 应该从右往左推进", b.x < a.x)
        assertEquals(80f, a.x, 0.01f)
        assertEquals(40f, b.x, 0.01f)
        val band = b.band(20f)
        assertEquals(false, band.litOnLeft)
    }

    @Test
    fun 渐变带以光标为中心() {
        val sample = SweepSample(lineIndex = 0, x = 100f, rtl = false)
        val band = sample.band(30f)
        assertEquals(85f, band.fadeStart, 0.001f)
        assertEquals(115f, band.fadeEnd, 0.001f)
        assertEquals(30f, band.width, 0.001f)
        // 光标正好落在 50% 处 —— 这是「与播放进度精确同步」的判据。
        assertEquals(sample.x, (band.fadeStart + band.fadeEnd) / 2f, 0.001f)
    }

    @Test
    fun 缓动函数端点与中点() {
        assertEquals(0f, SweepEasing.LINEAR.apply(0f), 0.001f)
        assertEquals(1f, SweepEasing.LINEAR.apply(1f), 0.001f)
        assertEquals(0.5f, SweepEasing.LINEAR.apply(0.5f), 0.001f)
        assertEquals(0.5f, SweepEasing.SMOOTH.apply(0.5f), 0.001f)
        assertEquals(0.875f, SweepEasing.EASE_OUT.apply(0.5f), 0.001f)
        // 越界要夹住，绝不外推（否则光标会飞出屏幕）。
        assertEquals(0f, SweepEasing.SMOOTH.apply(-3f), 0.001f)
        assertEquals(1f, SweepEasing.EASE_OUT.apply(9f), 0.001f)
    }

    @Test
    fun 缓动会改变词内位置但不改端点() {
        val geo = FakeGeometry(charCount = 10, charsPerLine = 10)
        val smooth = SweepTrack.build(
            listOf(word(0, 400, 0, 4)), 10, 400, geo, 0f, SweepEasing.SMOOTH,
        )!!
        assertEquals(0f, smooth.sample(0)!!.x, 0.01f)
        assertEquals(20f, smooth.sample(200)!!.x, 0.01f)
        assertEquals(40f, smooth.sample(400)!!.x, 0.01f)
        // smoothstep(0.25) = 0.25² × (3 - 0.5) = 0.15625，与线性 0.125 明显不同 —— 缓动确实生效了。
        assertEquals(6.25f, smooth.sample(100)!!.x, 0.01f)
    }

    // ---------------------------------------------------------------- 坏数据容错

    @Test
    fun 空词表或空文本返回null() {
        val geo = FakeGeometry(charCount = 4, charsPerLine = 4)
        assertNull(SweepTrack.build(emptyList(), 4, null, geo, 0f))
        assertNull(SweepTrack.build(listOf(word(0, 100, 0, 4)), 0, null, geo, 0f))
    }

    /** 字符区间全越界的词直接跳过；一个有效词都没有 → null（调用方退回整行渲染）。 */
    @Test
    fun 字符区间越界全部跳过() {
        val geo = FakeGeometry(charCount = 4, charsPerLine = 4)
        assertNull(SweepTrack.build(listOf(word(0, 100, 99, 120)), 4, null, geo, 0f))
    }

    /** 词时长非正 = 瞬时点亮，不能崩、不能产生 NaN，也不能让后续采样错位。 */
    @Test
    fun 零时长与负时长不会崩() {
        val geo = FakeGeometry(charCount = 8, charsPerLine = 8)
        val track = SweepTrack.build(
            listOf(word(0, 0, 0, 2), word(0, -5, 2, 4), word(500, 200, 4, 6)),
            8, 700, geo, 0f,
        )!!
        for (t in -100L..1200L step 37L) {
            val s = track.sample(t)
            if (s != null) assertTrue("出现 NaN: t=$t", s.x.isFinite())
        }
        assertEquals(60f, track.sample(700)!!.x, 0.01f)
    }

    /** 词时间乱序也要能用：内部排序后折线在时间轴上严格递增（否则二分查找会给出错位置）。 */
    @Test
    fun 词时间乱序被排序() {
        val geo = FakeGeometry(charCount = 12, charsPerLine = 12)
        val track = SweepTrack.build(
            listOf(word(1000, 200, 4, 6), word(0, 200, 0, 2), word(2000, 200, 8, 10)),
            12, 2200, geo, 0f,
        )!!
        assertEquals(0f, track.sample(0)!!.x, 0.01f)
        assertEquals(20f, track.sample(200)!!.x, 0.01f)
        assertEquals(60f, track.sample(1200)!!.x, 0.01f)
        assertEquals(100f, track.sample(2200)!!.x, 0.01f)
        var prevT = Long.MIN_VALUE
        for (t in 0L..2200L step 10L) {
            val s = track.sample(t) ?: continue
            assertTrue(s.x.isFinite())
            prevT = t
        }
        assertTrue(prevT > 0)
    }

    /** 空词（charStart == charEndExclusive）不产生节点。 */
    @Test
    fun 空字符区间不产生节点() {
        val geo = FakeGeometry(charCount = 8, charsPerLine = 8)
        val track = SweepTrack.build(
            listOf(word(0, 100, 3, 3), word(200, 200, 0, 2)),
            8, 400, geo, 0f,
        )!!
        // 第一个词被跳过，第二个词因此成为「首词」，同样享受首部外扩（fadePx=0 时看不出）。
        assertEquals(0f, track.sample(200)!!.x, 0.01f)
        assertEquals(20f, track.sample(400)!!.x, 0.01f)
        assertNull(track.sample(199))
    }

    // ---------------------------------------------------------------- 配置

    @Test
    fun 默认配置与低端档() {
        val d = LyricsSweepConfig.DEFAULT
        assertEquals(0.65f, d.fadeEm, 0.0001f)
        assertEquals(0.4f, d.inactiveAlpha, 0.0001f)
        assertEquals(SweepEasing.LINEAR, d.easing)
        assertTrue(d.softEdge)
        assertEquals(false, LyricsSweepConfig.LOW_END.softEdge)
    }
}
