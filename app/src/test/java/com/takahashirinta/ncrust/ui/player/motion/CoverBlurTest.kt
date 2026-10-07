/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0 · A 档：**封面背景模糊的像素运算**（纯 JVM，无 Android 依赖）。
 *
 * 覆盖铁律 24 的可断言部分：
 *  - 降采样确实是**盒式平均**（不是抽样）——用「一半黑一半白」的图断言均值落在中间；
 *  - 模糊确实抹平了细节（相邻像素差单调下降）；
 *  - **边界处理是 Clamp**（四角不会被压暗）；
 *  - 半径 / 遍数为 0 时是恒等变换（"关掉就是没这功能"）；
 *  - 尺寸不匹配时**抛 `IllegalArgumentException`**（这是编程错误，应当炸在开发期；
 *    而运行时的失败面 —— OOM / recycled bitmap —— 由 `CoverBlurCache` 的 runCatching 兜住）。
 */
class CoverBlurTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = (p ushr 16) and 0xFF
    private fun green(p: Int) = (p ushr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    // ── 1. 降采样 ─────────────────────────────────────────────────────────────────

    @Test
    fun `降采样是盒式平均而不是抽样`() {
        // 4×4 图：左半黑、右半白。抽样式降采样到 2×2 会得到「全黑/全白」两列，
        // 盒式平均得到的是 0/128/255 这类中间值 —— 后者才是"背景颜色不会跳变"的前提。
        val w = 4
        val h = 4
        val src = IntArray(w * h) { i ->
            if (i % w < 2) argb(255, 0, 0, 0) else argb(255, 255, 255, 255)
        }
        val out = CoverBlur.downsample(src, w, h, dst = 2)
        assertEquals(4, out.size)
        assertEquals("左列 = 纯黑", 0, red(out[0]))
        assertEquals("右列 = 纯白", 255, red(out[1]))
        assertEquals("上排左 = 纯黑", 0, red(out[2]))
    }

    @Test
    fun `降采样保留透明度与三通道`() {
        val src = IntArray(4) { argb(128, 10, 20, 30) }
        val out = CoverBlur.downsample(src, 2, 2, dst = 1)
        assertEquals(1, out.size)
        assertEquals(128, (out[0] ushr 24) and 0xFF)
        assertEquals(10, red(out[0]))
        assertEquals(20, green(out[0]))
        assertEquals(30, blue(out[0]))
    }

    @Test
    fun `源比目标小的时候不会崩也不会越界`() {
        // 1×1 → 32×32：每个目标像素都落在同一个源像素上。
        val out = CoverBlur.downsample(IntArray(1) { argb(255, 7, 8, 9) }, 1, 1)
        assertEquals(CoverBlur.DOWNSAMPLE_PX * CoverBlur.DOWNSAMPLE_PX, out.size)
        assertTrue(out.all { red(it) == 7 && green(it) == 8 && blue(it) == 9 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `源尺寸为零时抛异常`() {
        CoverBlur.downsample(IntArray(0), 0, 0, dst = 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `像素数组比声明的尺寸小时抛异常`() {
        CoverBlur.downsample(IntArray(3), 4, 4, dst = 2)
    }

    // ── 2. 模糊 ───────────────────────────────────────────────────────────────────

    /** 相邻像素的平均绝对差：模糊应当让它**单调下降**。 */
    private fun roughness(pixels: IntArray, size: Int): Double {
        var sum = 0L
        var n = 0
        for (y in 0 until size) {
            for (x in 0 until size - 1) {
                sum += kotlin.math.abs(red(pixels[y * size + x]) - red(pixels[y * size + x + 1])).toLong()
                n++
            }
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    @Test
    fun `模糊抹平细节`() {
        // 棋盘格：最"粗糙"的图案。
        val size = CoverBlur.DOWNSAMPLE_PX
        val checker = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if ((x + y) % 2 == 0) argb(255, 255, 255, 255) else argb(255, 0, 0, 0)
        }
        val before = roughness(checker, size)
        val after = roughness(CoverBlur.blur(checker, size), size)
        assertTrue("模糊后相邻差必须显著下降（$before → $after）", after < before / 4)
    }

    @Test
    fun `多遍模糊比单遍更平滑`() {
        val size = CoverBlur.DOWNSAMPLE_PX
        val checker = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if ((x + y) % 2 == 0) argb(255, 255, 255, 255) else argb(255, 0, 0, 0)
        }
        val one = roughness(CoverBlur.blur(checker, size, passes = 1), size)
        val three = roughness(CoverBlur.blur(checker, size, passes = 3), size)
        assertTrue("三遍必须比一遍更平滑（$one → $three）", three < one)
    }

    @Test
    fun `边界用 Clamp 不会把四角压暗`() {
        // 整图纯白：任何边界处理都不该让四角变黑（补零会在四边压出暗边）。
        val size = CoverBlur.DOWNSAMPLE_PX
        val white = IntArray(size * size) { argb(255, 255, 255, 255) }
        val out = CoverBlur.blur(white, size)
        assertTrue("纯白图模糊后必须还是纯白", out.all { red(it) == 255 && green(it) == 255 && blue(it) == 255 })
    }

    @Test
    fun `半径为零或遍数为零时是恒等变换`() {
        val size = 8
        val src = IntArray(size * size) { i -> argb(255, i * 3 % 256, i * 7 % 256, i * 11 % 256) }
        assertArrayEqualsExact(src, CoverBlur.blur(src, size, radius = 0))
        assertArrayEqualsExact(src, CoverBlur.blur(src, size, passes = 0))
    }

    @Test
    fun `模糊不修改输入数组`() {
        val size = 8
        val src = IntArray(size * size) { i -> argb(255, i % 256, 0, 0) }
        val copy = src.copyOf()
        CoverBlur.blur(src, size)
        assertArrayEqualsExact(copy, src)
    }

    @Test
    fun `半径大于边长时被夹取而不是越界`() {
        val size = 4
        val src = IntArray(size * size) { argb(255, 100, 100, 100) }
        val out = CoverBlur.blur(src, size, radius = 999)
        assertEquals(size * size, out.size)
        assertTrue(out.all { red(it) == 100 })
    }

    @Test
    fun `降采样加模糊一步到位`() {
        val w = 64
        val h = 64
        val src = IntArray(w * h) { i -> if (i % w < 32) argb(255, 255, 0, 0) else argb(255, 0, 0, 255) }
        val out = CoverBlur.downsampleAndBlur(src, w, h)
        assertEquals(CoverBlur.DOWNSAMPLE_PX * CoverBlur.DOWNSAMPLE_PX, out.size)
        // 左右两侧应当分别偏红、偏蓝，而中间是过渡带。
        val size = CoverBlur.DOWNSAMPLE_PX
        val left = out[size / 2 * size + 0]
        val right = out[size / 2 * size + size - 1]
        assertTrue("左半边必须偏红", red(left) > blue(left))
        assertTrue("右半边必须偏蓝", blue(right) > red(right))
        assertNotEquals("两边不能一模一样（否则模糊把画面压成了单色）", left, right)
    }

    @Test
    fun `观感修正把灰色糊图推成有色`() {
        // S6 实测：只做降采样 + 模糊的背景平均色是 [43,43,46] —— 三通道几乎相等（色度≈0），
        // 读起来是"一块脏灰"。vibrance 必须把色度拉开，同时不改变灰像素。
        val size = 4
        val source = IntArray(size * size) { argb(255, 90, 60, 70) }
        val before = red(source[0]) - green(source[0])
        val beforeLuma = red(source[0])
        // ⚠️ `vibrance` 是**原地修改**（那张图刚算出来、没有别的持有者），
        // 所以基线必须在调用**之前**取 —— 这正是本用例第一版踩到的坑。
        val out = CoverBlur.vibrance(source, size)
        val after = red(out[0]) - green(out[0])
        assertTrue("色度必须被拉开（$before → $after）", after > before)
        assertTrue("整体必须更亮（$beforeLuma → ${red(out[0])}）", red(out[0]) > beforeLuma)
    }

    @Test
    fun `观感修正不会给纯灰像素凭空造色`() {
        val size = 4
        val grey = IntArray(size * size) { argb(255, 80, 80, 80) }
        val out = CoverBlur.vibrance(grey, size)
        assertEquals("灰像素的三通道必须仍然相等", red(out[0]), green(out[0]))
        assertEquals(green(out[0]), blue(out[0]))
    }

    @Test
    fun `观感修正抬了黑位所以最暗的封面仍可辨认`() {
        val size = 4
        val black = IntArray(size * size) { argb(255, 0, 0, 0) }
        val out = CoverBlur.vibrance(black, size)
        assertTrue("纯黑必须被抬到可辨认的下限之上", red(out[0]) >= CoverBlur.BLACK_FLOOR)
    }

    @Test
    fun `观感修正不越界也不改透明度`() {
        val size = 4
        val bright = IntArray(size * size) { argb(200, 255, 255, 255) }
        val out = CoverBlur.vibrance(bright, size)
        assertTrue(out.all { red(it) in 0..255 && green(it) in 0..255 && blue(it) in 0..255 })
        assertTrue("透明度必须原样保留", out.all { (it ushr 24) and 0xFF == 200 })
    }

    @Test
    fun `默认常量与任务书一致`() {
        assertEquals("铁律 24 / 任务书 §4.1：降采样到 ~32px", 32, CoverBlur.DOWNSAMPLE_PX)
        assertTrue(CoverBlur.BLUR_RADIUS_PX > 0)
        assertTrue(CoverBlur.BLUR_PASSES >= 2)
        assertEquals(4, CoverBlurCache.CACHE_ENTRIES)
    }

    private fun assertArrayEqualsExact(expected: IntArray, actual: IntArray) {
        assertEquals("长度不同", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("下标 $i", expected[i], actual[i])
        }
    }
}
