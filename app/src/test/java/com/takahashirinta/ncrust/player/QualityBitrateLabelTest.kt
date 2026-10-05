package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放界面「音质旁边的码率」文案（`QualityAssessment.bitrateLabel`）的回归测试。
 *
 * 这个函数只有一件事要做对：**有值就写成 `1411kbps`，拿不到就什么都不给**。
 * 两条都容易写错 —— 前者容易带上小数点或单位错位，后者容易退化成 `0kbps`
 * （那是一个会被读成测量结果的数字，而不是「没有信息」）。
 *
 * 锚点值全部取自仓库内已有的实测记录（`QualityAssessment` 的档位阈值注释、
 * `docs/` 下的取链实测）：无损 920,600 / Hi-Res 1,685,762 / 母带 4.7 Mbps。
 */
class QualityBitrateLabelTest {

    // ── 有值：格式 ────────────────────────────────────────────────────────

    /** CD 级无损的常见取值：1,411,200 bit/s = 1411 kbps（需求里点名的那个形状）。 */
    @Test
    fun `1411200 写成 1411kbps`() {
        assertEquals("1411kbps", QualityAssessment.bitrateLabel(1_411_200))
    }

    /** 实测锚点：无损 920,600、Hi-Res 1,685,762、母带 4,700,000。 */
    @Test
    fun `实测的三种码率各自写成整数 kbps`() {
        assertEquals("920kbps", QualityAssessment.bitrateLabel(920_600))
        assertEquals("1685kbps", QualityAssessment.bitrateLabel(1_685_762))
        assertEquals("4700kbps", QualityAssessment.bitrateLabel(4_700_000))
        // ncm exhigh 的 320k mp3 —— 整千值不能被写成 320.0kbps 之类。
        assertEquals("320kbps", QualityAssessment.bitrateLabel(320_000))
    }

    /** 文案形状被钉死：纯数字 + `kbps`，没有小数点、没有空格、没有千分位。 */
    @Test
    fun `文案形状是数字直接接 kbps`() {
        val label = QualityAssessment.bitrateLabel(1_411_200)!!
        assertTrue("actual=$label", Regex("^\\d+kbps$").matches(label))
    }

    // ── 有值：取整方向 ────────────────────────────────────────────────────

    /**
     * 取整是整除（向下），不是四舍五入。这条不是口味问题：档位标签与码率并排显示，
     * 两者必须同向。判据里 `br >= 1,400,000` 才算高解析，而 1,399,600 四舍五入会写成
     * `1400kbps` —— 数字看着已经够到高解析，档位标签却写「无损」，同一行自相矛盾。
     */
    @Test
    fun `取整方向与档位阈值同向`() {
        assertEquals("lossless", QualityAssessment.measuredLevel(1_399_600, "flac"))
        assertEquals("1399kbps", QualityAssessment.bitrateLabel(1_399_600))

        assertEquals("hires", QualityAssessment.measuredLevel(1_400_000, "flac"))
        assertEquals("1400kbps", QualityAssessment.bitrateLabel(1_400_000))
    }

    /** 向下取整的极端形状：1999 bit/s 是 1kbps，不是 2kbps。 */
    @Test
    fun `1999 向下取整为 1kbps`() {
        assertEquals("1kbps", QualityAssessment.bitrateLabel(1_999))
    }

    // ── 拿不到：一律 null，绝不写 0kbps ───────────────────────────────────

    /**
     * 0 是「未知」而不是「零码率」：离线缓存兜底、B 站音频区（响应里只有字节数 `size`）、
     * 取链失败三条路都会给出 `SongUrlResult.br = 0`。显示 `0kbps` 比不显示更糟 ——
     * 它看起来像一个测量结果。
     */
    @Test
    fun `0 与负数都不显示`() {
        assertNull(QualityAssessment.bitrateLabel(0L))
        assertNull(QualityAssessment.bitrateLabel(-1L))
        assertNull(QualityAssessment.bitrateLabel(Long.MIN_VALUE))
    }

    /** 小于 1 kbps 的畸形值同样按「拿不到」处理，边界就是 1000。 */
    @Test
    fun `不足 1kbps 的畸形值不显示 而 1000 正好显示`() {
        assertNull(QualityAssessment.bitrateLabel(1L))
        assertNull(QualityAssessment.bitrateLabel(999L))
        assertEquals("1kbps", QualityAssessment.bitrateLabel(1_000L))
    }

    /** 极大值不溢出、不抛异常（服务端字段是 Long，脏数据也付得起一个字符串）。 */
    @Test
    fun `Long 极大值不溢出`() {
        assertEquals("${Long.MAX_VALUE / 1000}kbps", QualityAssessment.bitrateLabel(Long.MAX_VALUE))
    }
}
