package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 峰值短横与小球（圆点）的**纵向错开**回归测试（用户实测：「短横感觉错开比较合理」）。
 *
 * ## 背景
 *
 * v3.4.5 把圆点改成跟着小球走后，圆点终于会离开色带顶边（要的效果），
 * 但短横与圆点**共用同一个 `ballHalf`** ⇒ 短横矩形落在圆点半径内、被完全遮住，
 * 于是 `peaks` + `dots` 都开的档位上，原来那条粉色虚线变成"一串小球"。
 *
 * ## 判据纪律
 *
 * 量的是**渲染层真正消费的量**：`peakHalfPx(...)` 的返回值（调用方直接把它当
 * 短横的 `peakHalf` 用）。不要在这里另建一套"可见位置"的定义 ——
 * 本仓库在波形上已经因为口径不一致失败过多次。
 *
 * 另外：判据不能只断言"不重叠"，要断言**有正的空隙** ——
 * 刚好贴住（空隙 = 0）在低分辨率屏上仍会读成一坨。
 */
class BandMarkerGeometryTest {

    /** 与 `AudioVisualizer` 的实际取值一致：圆点半径 1.2dp、短横厚度 1.5dp（mdpi 下 = px）。 */
    private val r = 1.2f
    private val cap = 1.5f

    private fun sep(half: Float) = BandMarkerGeometry.peakHalfPx(half, r, cap, separate = true)
    private fun gap(half: Float) = BandMarkerGeometry.gapPx(half, r, cap, separate = true)

    // ------------------------------------------------------------ 核心回归 ----

    @Test
    fun `错开后短横与圆点在纵向上不重叠`() {
        // 覆盖整条值域，包括两个极值（用户最可能看到重叠的地方）
        for (half in listOf(0f, 0.5f, 2f, 5f, 10f, 20f, 40f, 80f, 120f, 1000f)) {
            assertTrue(
                "半高 $half 处短横仍与圆点重叠（gap=${gap(half)}）",
                BandMarkerGeometry.isSeparated(half, r, cap, separate = true),
            )
        }
    }

    @Test
    fun `极值高度下仍然有空隙 —— 不是刚好贴住`() {
        // 「刚好不重叠」不够：低分辨率屏上贴着会读成一坨。要求空隙 ≥ 短横厚度
        // （错开量刻意多给了一个 cap，见 peakLiftPx 的推导）。
        val minGap = cap
        for (half in listOf(0f, 1f, 25f, 60f, 200f, 5000f)) {
            assertTrue(
                "半高 $half 处空隙过小（gap=${gap(half)}，要求 ≥ $minGap）",
                gap(half) >= minGap - 1e-4f,
            )
        }
    }

    @Test
    fun `错开量与柱高无关 —— 否则高柱上又会重叠`() {
        // 这是修复的关键不变式：抬升量必须恒定。
        // ⚠️ 容差用 1e-2 而不是 1e-5：`sep(half) − half` 在 `half` 很大时是
        // **两个大浮点数相减**（1000 量级 + 3.9），有效位被吃掉 ⇒ 结果只能保证 ~1e-3 的相对精度。
        // 这是测法的精度上限，不是实现的缺陷 —— 用 1e-5 断言会假红（这条我踩过一次）。
        // 「恒定」这个语义本身仍然被严格检验：变异必须远小于 0.01。
        val tol = 1e-2f
        val lifts = listOf(0f, 1f, 10f, 100f, 1000f).map { sep(it) - it }
        val first = lifts.first()
        for (l in lifts) {
            assertEquals("抬升量必须恒定（随柱高缩放会让高柱重新重叠）", first, l, tol)
        }
        assertEquals("抬升量 = 2×圆点半径 + 短横厚度", 2f * r + cap, first, tol)
    }

    @Test
    fun `关闭错开时退化成改前行为 —— 供 A B 对照`() {
        // `separate = false` 必须**逐值**等于旧实现（peakHalf = ballHalf），
        // 这样"改前"仍可测，也让回归测试有对照物。
        for (half in listOf(0f, 0.5f, 3f, 50f, 999f)) {
            assertEquals("未错开时应等于 ballHalf", half, sep(half).let {
                BandMarkerGeometry.peakHalfPx(half, r, cap, separate = false)
            }, 1e-6f)
        }
    }

    @Test
    fun `改前确实会重叠 —— 旧行为的缺陷被复现`() {
        // 这条不是"测旧代码"，而是记录"为什么不能改回去"：
        // 未错开时短横下沿落在圆点内部。
        assertFalse(
            "未错开时应当判定为重叠（gap=${BandMarkerGeometry.gapPx(10f, r, cap, separate = false)}）",
            BandMarkerGeometry.isSeparated(10f, r, cap, separate = false),
        )
    }

    // ------------------------------------------------------------ 边界 ----

    @Test
    fun `圆点半径或厚度为 0 时不产生 NaN 也不抛异常`() {
        val v = BandMarkerGeometry.peakHalfPx(5f, 0f, 0f)
        assertTrue("必须有限", v.isFinite())
        assertEquals("两者都为 0 ⇒ 抬升 0", 5f, v, 1e-6f)
    }

    @Test
    fun `负值与 NaN 输入被夹成安全值`() {
        val v1 = BandMarkerGeometry.peakHalfPx(-10f, r, cap)
        assertTrue("负半高应夹到 0 以上", v1 >= 0f && v1.isFinite())
        val v2 = BandMarkerGeometry.peakHalfPx(Float.NaN, r, cap)
        assertTrue("NaN 半高不得传播", v2.isFinite())
        val v3 = BandMarkerGeometry.peakHalfPx(5f, Float.NaN, Float.NaN)
        assertTrue("NaN 半径/厚度不得传播", v3.isFinite())
    }

    @Test
    fun `空隙随圆点半径增大而增大 —— 单调性`() {
        // 语义检查：圆点越大，短横要抬得越高才不被遮住。
        val g1 = BandMarkerGeometry.gapPx(10f, 1f, cap)
        val g2 = BandMarkerGeometry.gapPx(10f, 3f, cap)
        val g3 = BandMarkerGeometry.gapPx(10f, 6f, cap)
        assertTrue("半径增大时空隙不应变小", g1 <= g2 + 1e-5f && g2 <= g3 + 1e-5f)
    }

    @Test
    fun `高 DPI 下（半径与厚度都放大）依然不重叠`() {
        // xhdpi ≈ 2x、xxhdpi ≈ 3x —— 与 AudioVisualizer 一致地一起缩放。
        for (scale in listOf(1f, 2f, 3f, 4f)) {
            val rr = r * scale
            val cc = cap * scale
            for (half in listOf(0f, 20f, 100f, 400f)) {
                assertTrue(
                    "scale=$scale half=$half 处重叠",
                    BandMarkerGeometry.isSeparated(half, rr, cc, separate = true),
                )
            }
        }
    }

    // ─────────────── v3.4.6：短横取「峰值保持」与「不许压住小球」的较大值 ───────────────

    /**
     * 保持值高于小球时，短横画在**保持值**处（不再跟着小球下来）。
     *
     * 旧实现里短横 = `小球 + 常量`，所以这条用例在旧实现下必然红 —— 那正是
     * 用户说的「它和小短线是一样的」。
     */
    @Test
    fun `峰值保持高于小球时短横用保持值`() {
        val r = 1.2f
        val cap = 1.5f
        val ball = 10f
        val hold = 40f
        val half = BandMarkerGeometry.dashHalfPx(ball, hold, r, cap)
        assertEquals("保持值更高时必须用它（而不是小球 + 常量）", hold, half, 1e-4f)
        assertTrue("必须明显高于「只跟小球」的结果", half > BandMarkerGeometry.peakHalfPx(ball, r, cap) + 10f)
    }

    /** 小球弹回接近历史最高点时，由既有错开量兜住 —— 两者不许重叠。 */
    @Test
    fun `小球追平保持值时短横仍不与小球重叠`() {
        val r = 1.2f
        val cap = 1.5f
        val ball = 30f
        val half = BandMarkerGeometry.dashHalfPx(ball, 30f, r, cap)
        assertEquals(
            "小球与保持值同高时，退化成既有的错开几何",
            BandMarkerGeometry.peakHalfPx(ball, r, cap), half, 1e-4f,
        )
        assertTrue(
            "必须仍然分开",
            BandMarkerGeometry.isSeparated(ball, r, cap),
        )
        assertTrue("空隙必须为正", BandMarkerGeometry.gapPx(ball, r, cap) > 0f)
    }

    /** 短横永远不低于小球（保持值被传成更小也不许画到小球下面）。 */
    @Test
    fun `保持值更小时短横仍不低于小球`() {
        val r = 1.2f
        val cap = 1.5f
        for (ball in listOf(0f, 5f, 20f, 60f)) {
            for (hold in listOf(0f, 1f, ball, ball - 5f)) {
                val half = BandMarkerGeometry.dashHalfPx(ball, hold, r, cap)
                assertTrue(
                    "ball=$ball hold=$hold ⇒ 短横($half) 绝不低于小球($ball)",
                    half >= ball - 1e-4f,
                )
            }
        }
    }

    /**
     * 非有限输入不许传染到画布。
     *
     * 契约是「结果有限」，不是「结果等于 0」：`ballHalf` 为 NaN 时会被净化成 0，
     * 但**错开量仍然要加**（否则短横会压在圆点上）。所以那条断言的是有限性与下界，
     * 不是精确值 —— 我第一版写成 `assertEquals(0f, ...)` 是错的。
     */
    @Test
    fun `峰值几何 非有限输入不传染`() {
        val a = BandMarkerGeometry.dashHalfPx(Float.NaN, Float.NaN, 1.2f, 1.5f)
        assertTrue("结果必须有限，实测 $a", a.isFinite())
        assertEquals("NaN 净化成 0 之后只剩错开量", 2f * 1.2f + 1.5f, a, 1e-4f)
        val b = BandMarkerGeometry.dashHalfPx(0f, 0f, Float.NaN, Float.NaN)
        assertTrue("半径/厚度非有限时结果必须有限，实测 $b", b.isFinite())
        assertEquals(0f, b, 1e-4f)
    }

    /** `separate = false` 时退化成"只跟小球"（= v3.4.6 之前的逐像素行为，供 A/B 对照）。 */
    @Test
    fun `关闭错开时退化回只跟小球`() {
        val ball = 25f
        assertEquals(
            BandMarkerGeometry.peakHalfPx(ball, 1.2f, 1.5f, separate = false),
            BandMarkerGeometry.dashHalfPx(ball, 99f, 1.2f, 1.5f, separate = false),
            1e-4f,
        )
    }
}
