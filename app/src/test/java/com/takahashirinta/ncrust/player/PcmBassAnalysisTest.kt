/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0：**低频（鼓 / 贝斯）通道**的纯数学（JVM 直测，无 Android 运行时）。
 *
 * 这一层存在的唯一理由是节拍判据：全带 RMS 分不出「一句高音」与「一下底鼓」，
 * 于是涟漪/脉冲要么乱触发要么不触发。低频通道来自**一阶低通**（每样本一次乘加），
 * 与全带 RMS 共用**同一次**逐样本遍历 —— 不是 FFT、不是第二遍扫描、不画任何频谱。
 *
 * 这里断言的是三件事：
 *  1. 低通确实**只让低频过**（同一个 60Hz 正弦与 6kHz 正弦，前者的低频读数远大于后者）；
 *  2. 全带读数**不受**低通影响（与 `PcmRms.of` 的参考实现逐位一致）；
 *  3. 打包/解包与系数公式的边界（采样率未知时退化成"低频 = 全带"，而不是拿猜的值去滤波）。
 */
class PcmBassAnalysisTest {

    /** 生成一段交错 16bit PCM 的正弦波（振幅 0.8），返回其 ByteBuffer。 */
    private fun sine(freqHz: Double, sampleRateHz: Int, samples: Int, amplitude: Double = 0.8): ByteBuffer {
        val buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until samples) {
            val v = (sin(2.0 * PI * freqHz * i / sampleRateHz) * amplitude * 32767).toInt()
            buf.put((v and 0xFF).toByte())
            buf.put(((v shr 8) and 0xFF).toByte())
        }
        buf.flip()
        return buf
    }

    @Test
    fun `低通只让低频通过`() {
        val sampleRate = 44_100
        val state = DoubleArray(1)
        val bass60 = PcmRms.analyze(sine(60.0, sampleRate, 8192), C.ENCODING_PCM_16BIT, sampleRate, state)
        state[0] = 0.0
        val bass6k = PcmRms.analyze(sine(6000.0, sampleRate, 8192), C.ENCODING_PCM_16BIT, sampleRate, state)

        val low = PcmRms.bassOf(bass60)
        val high = PcmRms.bassOf(bass6k)
        assertTrue("60Hz 的低频读数必须远大于 6kHz（$low vs $high）", low > high * 5f)
        // 全带读数两者接近（同一个振幅的正弦，能量相同）。
        val fullLow = PcmRms.fullOf(bass60)
        val fullHigh = PcmRms.fullOf(bass6k)
        assertTrue("全带读数不该被低通影响（$fullLow vs $fullHigh）", abs(fullLow - fullHigh) < 0.01f)
    }

    @Test
    fun `全带读数与参考实现一致`() {
        // `PcmRms.of` 是 v2.8.0 的参考实现，保留它就是为了这条断言：
        // analyze 多算的低频通道**不得**改变全带值。
        val sampleRate = 44_100
        val buffer = sine(440.0, sampleRate, 4096)
        val reference = PcmRms.of(buffer.duplicate(), C.ENCODING_PCM_16BIT)
        val packed = PcmRms.analyze(buffer.duplicate(), C.ENCODING_PCM_16BIT, sampleRate, DoubleArray(1))
        assertEquals(reference.toFloat(), PcmRms.fullOf(packed), 1e-5f)
    }

    @Test
    fun `低频读数是全带的下界`() {
        // 低通是能量意义上的"取一部分"，所以低频 RMS ≤ 全带 RMS。
        val sampleRate = 44_100
        val packed = PcmRms.analyze(sine(200.0, sampleRate, 4096), C.ENCODING_PCM_16BIT, sampleRate, DoubleArray(1))
        assertTrue(PcmRms.bassOf(packed) <= PcmRms.fullOf(packed) + 1e-4f)
    }

    @Test
    fun `采样率未知时退化成低频等于全带`() {
        // flush 还没被调用过（采样率 0）时**不许**拿猜的截止频率去滤波 ——
        // 系数 1.0 让 lp 等于当前样本，低频通道因此与总响度等价，
        // 行为退回 v2.8.0 的判据口径。
        assertEquals(1.0, PcmRms.lowPassCoefficient(0), 1e-12)
        assertEquals(1.0, PcmRms.lowPassCoefficient(-1), 1e-12)
        val sampleRate = 44_100
        val packed = PcmRms.analyze(sine(440.0, sampleRate, 1024), C.ENCODING_PCM_16BIT, 0, DoubleArray(1))
        assertEquals(PcmRms.fullOf(packed), PcmRms.bassOf(packed), 1e-6f)
    }

    @Test
    fun `低通系数公式与量级`() {
        // a = 1 - exp(-2π fc / fs)；44.1kHz / 150Hz ⇒ 约 0.0211
        val a = PcmRms.lowPassCoefficient(44_100)
        val expected = 1.0 - kotlin.math.exp(-2.0 * PI * PcmRms.BASS_CUTOFF_HZ / 44_100)
        assertEquals(expected, a, 1e-12)
        assertTrue("系数必须落在 (0,1)", a > 0.0 && a < 1.0)
        // 采样率越低，同一个截止频率对应的系数越大。
        assertTrue(PcmRms.lowPassCoefficient(8_000) > PcmRms.lowPassCoefficient(48_000))
    }

    @Test
    fun `打包与解包是可逆的`() {
        val packed = PcmRms.pack(0.25f, 0.75f)
        assertEquals(0.25f, PcmRms.bassOf(packed), 0f)
        assertEquals(0.75f, PcmRms.fullOf(packed), 0f)
        // 负数与零也要能原样往返（Bit 级打包，不做任何夹取）。
        val negative = PcmRms.pack(-1.5f, 0f)
        assertEquals(-1.5f, PcmRms.bassOf(negative), 0f)
        assertEquals(0f, PcmRms.fullOf(negative), 0f)
    }

    @Test
    fun `滤波状态跨缓冲连续`() {
        // 同一个正弦切成两半、分两次 analyze，低频读数必须与一次算完接近
        // （状态丢了就会差很多 —— 那正是"状态必须跨缓冲保留"的理由）。
        val sampleRate = 44_100
        val whole = sine(80.0, sampleRate, 4096)
        val oneShot = PcmRms.analyze(whole.duplicate(), C.ENCODING_PCM_16BIT, sampleRate, DoubleArray(1))

        val state = DoubleArray(1)
        val first = sine(80.0, sampleRate, 2048)
        PcmRms.analyze(first, C.ENCODING_PCM_16BIT, sampleRate, state)
        val second = sine(80.0, sampleRate, 2048)
        val secondHalf = PcmRms.analyze(second, C.ENCODING_PCM_16BIT, sampleRate, state)
        assertTrue("分两段算的低频读数应与整体接近", abs(PcmRms.bassOf(secondHalf) - PcmRms.bassOf(oneShot)) < 0.05f)
    }

    @Test
    fun `空缓冲与未知编码不抛异常`() {
        val empty = ByteBuffer.allocate(0)
        val packed = PcmRms.analyze(empty, C.ENCODING_PCM_16BIT, 44_100, DoubleArray(1))
        assertEquals(0f, PcmRms.fullOf(packed), 0f)
        val unknown = PcmRms.analyze(sine(440.0, 44_100, 128), C.ENCODING_PCM_24BIT, 44_100, DoubleArray(1))
        assertEquals(0f, PcmRms.fullOf(unknown), 0f)
        // 状态数组为空也不许崩（调用方传了长度为 0 的数组）。
        val noState = PcmRms.analyze(sine(440.0, 44_100, 128), C.ENCODING_PCM_16BIT, 44_100, DoubleArray(0))
        assertTrue(PcmRms.fullOf(noState) > 0f)
        assertEquals(sqrt(0.8 * 0.8 / 2).toFloat(), PcmRms.fullOf(noState), 0.02f)
    }
}
