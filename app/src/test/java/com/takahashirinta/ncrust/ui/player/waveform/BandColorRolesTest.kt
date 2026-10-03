/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import com.takahashirinta.ncrust.ui.theme.color.Cam16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.2：HCT 三角色的**颜色映射单测**（铁律 30 的"颜色必须能分辨"，以及降级退路）。
 *
 * 判据与探针 §2.4 一致：任意两色 **CAM16-UCS ΔE ≥ 5**（必需）。
 * 这里只对**预设主题色**断言（探针已对 36 个真实种子扫过；单测要的是"改坏了立刻红"，
 * 而不是把 36 个样本再抄一遍）。
 */
class BandColorRolesTest {

    private val presets = intArrayOf(
        0xFF1DB954.toInt(),
        0xFF3B82F6.toInt(),
        0xFFEF4444.toInt(),
        0xFFF59E0B.toInt(),
        0xFF8B5CF6.toInt(),
        0xFFFFFFFF.toInt(),
    )

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    @Test
    fun `预设主题色 —— 三个频段色两两可分辨`() {
        val out = IntArray(3)
        for (seed in presets) {
            for (isDark in listOf(true, false)) {
                assertTrue("paletteFor 必须成功: ${hex(seed)}/$isDark", BandColorRoles.paletteFor(seed, isDark, out))
                val pairs = listOf(
                    out[BandColorRoles.LOW] to out[BandColorRoles.MID],
                    out[BandColorRoles.MID] to out[BandColorRoles.HIGH],
                    out[BandColorRoles.LOW] to out[BandColorRoles.HIGH],
                )
                for ((a, b) in pairs) {
                    val de = Cam16.fromInt(a).distance(Cam16.fromInt(b))
                    assertTrue(
                        "ΔE 太小: ${hex(seed)}/${if (isDark) "深" else "浅"} ${hex(a)} vs ${hex(b)} = $de",
                        de >= 5.0,
                    )
                }
            }
        }
    }

    @Test
    fun `中频必须是主题色本身 —— 它占 2 分之 3 的时间`() {
        val out = IntArray(3)
        for (seed in presets) {
            BandColorRoles.paletteFor(seed, true, out)
            assertEquals(
                "中频必须是主题色（探针实测中频占比 0.66）",
                seed,
                out[BandColorRoles.MID],
            )
        }
    }

    @Test
    fun `深色与浅色主题给出不同的 tone`() {
        val dark = IntArray(3)
        val light = IntArray(3)
        BandColorRoles.paletteFor(0xFF1DB954.toInt(), true, dark)
        BandColorRoles.paletteFor(0xFF1DB954.toInt(), false, light)
        assertTrue("深色主题的三色应当比浅色更亮", Cam16.fromInt(dark[0]).j > Cam16.fromInt(light[0]).j)
    }

    @Test
    fun `确定性 —— 同输入同输出`() {
        val a = IntArray(3)
        val b = IntArray(3)
        BandColorRoles.paletteFor(0xFF3B82F6.toInt(), true, a)
        BandColorRoles.paletteFor(0xFF3B82F6.toInt(), true, b)
        assertArrayEqualsInt(a, b)
    }

    @Test
    fun `输出数组太短时返回 false 而不是崩溃`() {
        assertFalse(BandColorRoles.paletteFor(0xFF1DB954.toInt(), true, IntArray(2)))
    }

    @Test
    fun `resolve —— 不可信时必须退回单色（降级路径的如实表达）`() {
        val palette = IntArray(3)
        BandColorRoles.paletteFor(0xFF1DB954.toInt(), true, palette)
        val weights = floatArrayOf(1f, 0f, 0f)
        val fallback = 0xFF123456.toInt()
        assertEquals(fallback, BandColorRoles.resolve(palette, weights, reliable = false, fallback = fallback))
        assertEquals(palette[0], BandColorRoles.resolve(palette, weights, reliable = true, fallback = fallback))
    }

    @Test
    fun `resolve —— 数组太短时也退回单色`() {
        val fallback = 0xFFABCDEF.toInt()
        assertEquals(
            fallback,
            BandColorRoles.resolve(IntArray(2), floatArrayOf(1f, 0f, 0f), true, fallback),
        )
        assertEquals(
            fallback,
            BandColorRoles.resolve(IntArray(3), FloatArray(2), true, fallback),
        )
    }

    @Test
    fun `mix —— 单色权重取到该色 全零权重给透明`() {
        val palette = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt())
        assertEquals(palette[0], BandColorRoles.mix(palette, floatArrayOf(1f, 0f, 0f)))
        assertEquals(palette[2], BandColorRoles.mix(palette, floatArrayOf(0f, 0f, 1f)))
        assertEquals(0, BandColorRoles.mix(palette, floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun `mix —— 一半一半落在两色之间 且通道不越界`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val mid = BandColorRoles.mix(palette, floatArrayOf(0.5f, 0.5f, 0f))
        // 0x00..0xFF 的中点按**截断**落在 0x7F（不是四舍五入的 0x80）—— 断言这个具体值，
        // 因为它决定了"过渡中间色"到底长什么样，四舍五入与截断差一个色阶。
        assertEquals(0x7F, (mid shr 16) and 0xFF)
        assertEquals(0x7F, (mid shr 8) and 0xFF)
        assertEquals(0x7F, mid and 0xFF)
    }

    @Test
    fun `mix —— NaN 与负权重按 0 处理 不产生非法颜色`() {
        val palette = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFFCCDDEE.toInt())
        val out = BandColorRoles.mix(palette, floatArrayOf(Float.NaN, -1f, 2f))
        // 只有 high 的 2.0 生效 ⇒ 每通道翻倍后被 clamp 到 255（NaN 与负权重按 0 处理）。
        assertEquals(0xFF, (out ushr 24) and 0xFF)
        assertEquals(0xFF, (out shr 16) and 0xFF)
        assertEquals(0xFF, (out shr 8) and 0xFF)
        assertEquals(0xFF, out and 0xFF)
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("下标 $i", expected[i], actual[i])
    }
}
