package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态字号：**相对中位数**的判据（4.0.0）。
 *
 * 回归目标：旧实现是绝对三档，中文歌词"每句都恰好一行半"时整首都不动 —— 用户报的
 * 「动态字号根本没用」。这里钉住"同一首歌里一定出现至少两档"。
 */
class DynamicLyricFontRelativeTest {

    private val width = 900f      // 可用宽度（px）
    private val font = 75f        // 基准字号（px）⇒ 每行约 12 字

    @Test
    fun `长短句混排时一定出现层次`() {
        val lines = listOf(
            "晚安",                            // 极短
            "把今天的疲惫交给风声",            // 中等
            "我在人群里听见你的名字然后回头",  // 长
            "一遍又一遍地数着回家的路灯",      // 中等
        )
        val scales = DynamicLyricFont.scalesFor(lines, width, font)
        assertEquals(lines.size, scales.size)
        assertTrue("短句应被放大，实际=${scales[0]}", scales[0] > 1f)
        assertTrue("长句应被缩小，实际=${scales[2]}", scales[2] < 1f)
        assertTrue("必须出现多个档位，实际=$scales", scales.distinct().size >= 2)
    }

    @Test
    fun `整首都是短句但长短有差时也会拉开层次`() {
        val lines = listOf("晚安", "今天也辛苦了", "在吗", "把外套挂好再睡", "想你")
        val scales = DynamicLyricFont.scalesFor(lines, width, font)
        assertTrue("短句集合里应出现放大档，实际=$scales", scales.any { it > 1f })
    }

    @Test
    fun `每句都一样长时不制造假的层次`() {
        val lines = List(6) { "同样的句子" }
        val scales = DynamicLyricFont.scalesFor(lines, width, font)
        assertTrue("等长句子应保持一致，实际=$scales", scales.distinct().size <= 1)
    }

    @Test
    fun `宽度或字号不可用时一律不缩放`() {
        val lines = listOf("晚安", "把今天的疲惫交给风声")
        assertEquals(listOf(1f, 1f), DynamicLyricFont.scalesFor(lines, 0f, font))
        assertEquals(listOf(1f, 1f), DynamicLyricFont.scalesFor(lines, width, 0f))
        assertEquals(emptyList<Float>(), DynamicLyricFont.scalesFor(emptyList(), width, font))
    }

    @Test
    fun `超长句永远缩小`() {
        val long = "这一句特别特别长".repeat(6)
        val scales = DynamicLyricFont.scalesFor(listOf("短", long), width, font)
        assertTrue("超长句应缩小，实际=${scales[1]}", scales[1] <= DynamicLyricFont.SCALE_SMALL)
    }
}
