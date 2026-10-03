package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三泳道横向滚动的**形状保真**回归测试（用户实测：尖端会变平滑再变回去）。
 *
 * ## 判据：峰尖度随相位的波动
 *
 * 「尖度」= 峰值高出两侧邻点的程度。用户看到的「尖端变圆再变尖」就是
 * **同一段音频、不同相位下尖度不同**。
 *
 * 这条判据的特点是**它必须在整段相位上扫**：只量一个相位永远看不出问题
 * （相位 0 时两种写法完全一样 —— 这也是它长期没被发现的原因）。
 *
 * ⚠️ 另一条纪律：判据必须建立在**渲染层真正消费的量**上 —— 这里是
 * `heights[]`（写入 `out`）与 `xShiftPx`（几何平移）。不要在测试里另建
 * 一套「可见位置」的定义（本仓库在波形上为此错过三次）。
 */
class BandScrollTest {

    private val heightPx = 200f
    private val minBar = 2f
    private val step = 40f          // 一格 40px
    private val count = 28

    /**
     * 一段带尖峰的幅度序列（尖峰 + 相邻低值），形状照真实音乐：
     * 有明确的孤立高点，而不是平滑正弦 —— 平滑正弦上尖度差异量不出来。
     */
    private fun window(): FloatArray {
        val w = FloatArray(count + 4)
        for (i in w.indices) w[i] = 0.18f + 0.10f * kotlin.math.sin(i * 0.9f).toFloat()
        for (p in intArrayOf(3, 8, 12, 17, 22, 26)) {
            if (p < w.size) w[p] = 0.92f
        }
        return w
    }

    private fun fill(mode: BandScroll.Mode, phase: Float): Pair<FloatArray, Float> {
        val w = window()
        val out = FloatArray(count)
        val shift = BandScroll.fillLane(
            window = w, out = out, base = 0, count = count,
            phase = phase, lane = 0, heightPx = heightPx, minBar = minBar,
            step = step, mode = mode,
        )
        return out to shift
    }

    /** 尖度：最大「峰高出两侧邻点」的量。 */
    private fun maxTip(h: FloatArray): Float {
        var best = 0f
        for (i in 1 until h.size - 1) {
            val t = h[i] - maxOf(h[i - 1], h[i + 1])
            if (t > best) best = t
        }
        return best
    }

    /** 尖度在 12 个相位上的相对波动。 */
    private fun sharpnessSwing(mode: BandScroll.Mode): Float {
        val tips = (0 until 12).map { maxTip(fill(mode, it / 12f).first) }
        val lo = tips.min()
        val hi = tips.max()
        return if (hi <= 0f) 0f else (hi - lo) / hi * 100f
    }

    // ------------------------------------------------------------ 核心回归 ----

    @Test
    fun `位置平移的峰尖度不随相位变化 —— 尖端不再变圆变尖`() {
        val swing = sharpnessSwing(BandScroll.Mode.POSITION_SHIFT)
        assertEquals("形状必须与相位无关（用户报的就是这条）", 0f, swing, 0.01f)
    }

    @Test
    fun `值插值确实会让峰尖度随相位摆动 —— 旧写法的缺陷被复现`() {
        // 这条不是"测旧代码"，而是**给新写法一个对照**：如果哪天有人把
        // 生产路径改回 VALUE_LERP，上面那条会红；这条则记录"为什么不能改回去"。
        val swing = sharpnessSwing(BandScroll.Mode.VALUE_LERP)
        assertTrue(
            "值插值应当产生明显的尖度摆动（实测约 90%），实际=${swing}%",
            swing > 30f,
        )
    }

    // ------------------------------------------------------------ 平移正确性 ----

    @Test
    fun `亚格相位变成几何位移 而不是取值变化`() {
        // 相位 0 与 0.75：取值数组必须**逐值相同**，只有位移不同。
        val (h0, s0) = fill(BandScroll.Mode.POSITION_SHIFT, 0f)
        val (h1, s1) = fill(BandScroll.Mode.POSITION_SHIFT, 0.75f)
        assertEquals("取值不得随相位变化（形状保真的定义）", h0.toList(), h1.toList())
        assertEquals("相位 0 不平移", 0f, s0, 1e-4f)
        assertEquals("相位 0.75 左移 0.75 格", -0.75f * step, s1, 1e-4f)
    }

    @Test
    fun `值插值模式下位移恒为 0 —— 它只会改形状`() {
        for (p in 0..4) {
            val (_, shift) = fill(BandScroll.Mode.VALUE_LERP, p / 4f)
            assertEquals("VALUE_LERP 不该产生几何位移", 0f, shift, 1e-6f)
        }
    }

    @Test
    fun `每帧位移等于 dt 除以柱间隔 —— 平滑滑动没有被牺牲`() {
        // 这条守住"修形状不能退化成 10Hz 整格跳变"（那条路实测抖动率 166.9%、跳变 100px）。
        val dt = 16.667f
        val interval = 100f
        val (_, a) = fill(BandScroll.Mode.POSITION_SHIFT, 0f)
        val (_, b) = fill(BandScroll.Mode.POSITION_SHIFT, (dt / interval))
        val stepPx = kotlin.math.abs(b - a)
        assertEquals(
            "逐帧位移应为 0.167 格",
            dt / interval * step, stepPx, 1e-3f,
        )
        // 远小于一整格（100px 那条会退化到 40px）
        assertTrue("逐帧位移必须远小于一格（否则就是整格跳变）", stepPx < step * 0.25f)
    }

    @Test
    fun `相位在同一格内单调递进时位移单调不倒退`() {
        var prev = Float.MAX_VALUE
        for (k in 0..20) {
            val (_, s) = fill(BandScroll.Mode.POSITION_SHIFT, k / 20f)
            assertTrue("位移必须单调左移（不得回跳）", s <= prev)
            prev = s
        }
    }

    // ------------------------------------------------------------ 边界 ----

    @Test
    fun `相位超出 0到1 被夹取 —— 不产生越界平移`() {
        val (_, lo) = fill(BandScroll.Mode.POSITION_SHIFT, -0.5f)
        val (_, hi) = fill(BandScroll.Mode.POSITION_SHIFT, 3.7f)
        assertEquals(0f, lo, 1e-4f)
        assertEquals("最多左移一格", -step, hi, 1e-4f)
    }

    @Test
    fun `NaN 相位不产生 NaN 高度也不产生 NaN 位移`() {
        val (h, s) = fill(BandScroll.Mode.POSITION_SHIFT, Float.NaN)
        assertTrue("高度不得为 NaN", h.none { it.isNaN() })
        assertTrue("位移不得为 NaN", !s.isNaN())
    }

    @Test
    fun `窗口短于 count 时不越界且不抛异常`() {
        val out = FloatArray(count)
        val short = FloatArray(4) { 0.5f }
        val shift = BandScroll.fillLane(
            window = short, out = out, base = 0, count = count,
            phase = 0.5f, lane = 1, heightPx = heightPx, minBar = minBar,
            step = step, mode = BandScroll.Mode.POSITION_SHIFT,
        )
        assertTrue(!shift.isNaN())
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `base 非零时只写自己那一段 —— 三泳道互不覆写`() {
        val w = window()
        val out = FloatArray(count * 3)
        val s0 = BandScroll.fillLane(w, out, 0, count, 0.3f, 0, heightPx, minBar, step)
        val s1 = BandScroll.fillLane(w, out, count, count, 0.3f, 1, heightPx, minBar, step)
        val s2 = BandScroll.fillLane(w, out, count * 2, count, 0.3f, 2, heightPx, minBar, step)
        assertEquals("三条泳道的位移一致", s0, s1, 1e-6f)
        assertEquals(s1, s2, 1e-6f)
        // 不同泳道显示增益不同 ⇒ 取值不应逐值相同（否则说明增益没生效）
        val lane0 = out.copyOfRange(0, count)
        val lane1 = out.copyOfRange(count, count * 2)
        assertTrue("泳道显示增益应当生效", lane0.toList() != lane1.toList())
    }

    @Test
    fun `最小可见高度仍然生效`() {
        val zeros = FloatArray(count)
        val out = FloatArray(count)
        BandScroll.fillLane(zeros, out, 0, count, 0.5f, 0, heightPx, minBar, step)
        assertTrue("全零输入也应有最小高度（否则整条带子消失）", out.all { it >= minBar * 0.5f - 1e-6f })
    }

    @Test
    fun `生产模式是位置平移`() {
        assertEquals(
            "生产路径必须是位置平移（值插值已知会让尖端变形）",
            BandScroll.Mode.POSITION_SHIFT,
            BandScroll.PRODUCTION,
        )
    }
}
