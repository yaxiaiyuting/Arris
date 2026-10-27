/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * v3.2.2：曲线平滑的**硬性质单测**（铁律 31：不过冲、不出现负高度）。
 *
 * 探针 §2.5 已经在 19055 帧真实波形 / 8.25M 采样点上量过一遍；这里再用**构造出来的
 * 对抗性输入**扫一遍 —— 真实音乐不一定覆盖到"尖峰 + 平台 + 大幅跳变"的每一种组合，
 * 而插值算法最容易在这三种形状上破功。
 *
 * 判据是 [WaveformCurve.staysWithinEndpoints]：曲线在每一段上的取值必须被**该段两个端点**
 * 夹住。它同时蕴含「不过冲」与「不出现负高度」（所有 y ≥ 0 ⇒ 曲线 ≥ 0）。
 */
class WaveformCurveTest {

    private fun tangents(y: FloatArray): FloatArray {
        val out = FloatArray(y.size)
        WaveformCurve.computeTangents(y, y.size, 1f, out)
        return out
    }

    private fun assertNoOvershoot(y: FloatArray, label: String) {
        val m = tangents(y)
        for (i in 0 until y.size - 1) {
            assertTrue(
                "$label: 第 $i 段越界（y=${y[i]}, ${y[i + 1]}, m=${m[i]}, ${m[i + 1]}）",
                WaveformCurve.staysWithinEndpoints(y[i], y[i + 1], m[i], m[i + 1], 1f, steps = 64),
            )
        }
    }

    @Test
    fun `常数序列 —— 切线全零 曲线退化成直线`() {
        val y = FloatArray(28) { 0.4f }
        val m = tangents(y)
        for (v in m) assertEquals(0f, v, 0f)
        assertEquals(0.4f, WaveformCurve.sampleAt(0.4f, 0.4f, 0f, 0f, 1f, 0.5f), 1e-6f)
    }

    @Test
    fun `单调上升 —— 不过冲`() {
        assertNoOvershoot(FloatArray(28) { it / 27f }, "单调上升")
    }

    @Test
    fun `单调下降 —— 不过冲`() {
        assertNoOvershoot(FloatArray(28) { 1f - it / 27f }, "单调下降")
    }

    @Test
    fun `尖峰 —— 鼓点形状 不过冲也不出负值`() {
        val y = FloatArray(28) { if (it == 14) 1f else 0f }
        assertNoOvershoot(y, "单点尖峰")
        val m = tangents(y)
        for (i in 0 until y.size - 1) {
            for (s in 0..64) {
                val v = WaveformCurve.sampleAt(y[i], y[i + 1], m[i], m[i + 1], 1f, s / 64f)
                assertTrue("尖峰处出现负高度: $v", v >= 0f)
            }
        }
    }

    @Test
    fun `快速起音后立刻掉到接近静音 —— 这是 Catmull-Rom 会负高度的形状`() {
        val y = floatArrayOf(0.02f, 0.02f, 0.9f, 0.05f, 0.02f)
        assertNoOvershoot(y, "起音后静音")
    }

    @Test
    fun `平台 + 台阶 —— 不过冲`() {
        val y = floatArrayOf(0.1f, 0.1f, 0.1f, 0.8f, 0.8f, 0.8f, 0.2f, 0.2f)
        assertNoOvershoot(y, "平台台阶")
    }

    @Test
    fun `对抗性随机输入 —— 1000 组 每组都不得越界`() {
        val random = Random(20260928)
        var checked = 0
        repeat(1000) {
            val n = 2 + random.nextInt(26)
            val y = FloatArray(n) {
                // 故意制造大量"接近 0 的静音 + 偶发大跳" —— 插值最容易破功的形状。
                if (random.nextInt(3) == 0) random.nextFloat() * 0.05f else random.nextFloat()
            }
            assertNoOvershoot(y, "随机#$it")
            checked++
        }
        assertEquals(1000, checked)
    }

    @Test
    fun `负输入不会把曲线推向负值 更远 —— 切线处理仍保持端点夹取`() {
        // 生产路径不会传负值（heights 有 minBar 下限），但纯函数不该因为一次坏输入而发散。
        val y = floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f)
        val m = tangents(y)
        for (i in 0 until y.size - 1) {
            assertTrue(WaveformCurve.staysWithinEndpoints(y[i], y[i + 1], m[i], m[i + 1], 1f, steps = 64))
        }
    }

    @Test
    fun `ControlY 必须落在该段两端之间`() {
        val random = Random(7)
        repeat(500) {
            val a = random.nextFloat()
            val b = random.nextFloat()
            val y = floatArrayOf(a, b)
            val m = tangents(y)
            val c1 = WaveformCurve.controlY1(a, m[0], 1f)
            val c2 = WaveformCurve.controlY2(b, m[1], 1f)
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            assertTrue("c1 出界: $c1 ∉ [$lo, $hi]", c1 >= lo - 1e-5f && c1 <= hi + 1e-5f)
            assertTrue("c2 出界: $c2 ∉ [$lo, $hi]", c2 >= lo - 1e-5f && c2 <= hi + 1e-5f)
        }
    }

    @Test
    fun `非法 dx 退化成折线而不是 NaN`() {
        val y = floatArrayOf(0.1f, 0.9f, 0.2f)
        val out = FloatArray(3)
        WaveformCurve.computeTangents(y, 3, 0f, out)
        for (v in out) assertEquals(0f, v, 0f)
        WaveformCurve.computeTangents(y, 3, Float.NaN, out)
        for (v in out) assertEquals(0f, v, 0f)
        WaveformCurve.computeTangents(y, 3, -1f, out)
        for (v in out) assertEquals(0f, v, 0f)
    }

    @Test
    fun `点数不足两点时不做任何事 —— 只清掉被声明的那几格`() {
        val out = FloatArray(4) { 9f }
        // count = 1：只写 out[0]，其余槽位属于调用方的数据，不该被清。
        WaveformCurve.computeTangents(FloatArray(4), 1, 1f, out)
        assertEquals(0f, out[0], 0f)
        for (i in 1 until out.size) assertEquals(9f, out[i], 0f)

        val out0 = FloatArray(4) { 9f }
        WaveformCurve.computeTangents(FloatArray(4), 0, 1f, out0)
        for (v in out0) assertEquals(9f, v, 0f)
    }

    /**
     * v3.8.0 · J13：被夹掉的部分**必须清零**，而不是「保持调用方原值」。
     *
     * 旧断言是 `-1f 保持不动`，理由是「`out` 里超出 `count` 的槽位属于调用方」。
     * 那条理由对**`count` 之外**成立（见下一条用例），但这里的槽位在 `count` **之内**
     * ——调用方声明了 116 个点、绘制层就会画 116 个点，而 `MAX_POINTS` 只允许算 96 个。
     * 「保持不动」在真实渲染里意味着**用上一帧的旧切线去画这一帧的后 20 段**
     * （生产上就是 85 点被夹到 64 时的那 21 段，探针测到峰值 1.0243 越界）。
     * 所以夹断之后只能是 0（退化成折线，几何上绝不会越界）。
     */
    @Test
    fun `超出 MAX_POINTS 但在 count 之内的槽位必须清零而不是留旧值`() {
        val y = FloatArray(WaveformCurve.MAX_POINTS + 20) { it.toFloat() }
        val out = FloatArray(y.size) { -1f }
        WaveformCurve.computeTangents(y, y.size, 1f, out)
        for (i in WaveformCurve.MAX_POINTS until out.size) {
            assertEquals("被夹断的槽位必须是 0（折线），不能留上一帧的切线", 0f, out[i], 0f)
        }
    }

    @Test
    fun `count 之外的槽位不属于本次调用 —— 保持不动`() {
        val out = FloatArray(8) { -1f }
        WaveformCurve.computeTangents(FloatArray(4) { it.toFloat() }, 4, 1f, out)
        for (i in 4 until out.size) {
            assertEquals("调用方没声明的槽位不该被写", -1f, out[i], 0f)
        }
    }

    @Test
    fun `端点的切线不得与相邻斜率反号`() {
        // 端点单侧差分反向的形状：先降后升。
        val y = floatArrayOf(0.9f, 0.2f, 0.25f, 0.3f)
        val m = tangents(y)
        assertTrue("首点切线不该是正的（斜率是负的）", m[0] <= 0f)
        assertTrue(abs(m[0]) <= 3f * abs(y[1] - y[0]) + 1e-6f)
    }
}
