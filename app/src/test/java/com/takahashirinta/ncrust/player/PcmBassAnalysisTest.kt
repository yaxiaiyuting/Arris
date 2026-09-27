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


    /** v3.0.0：状态形状从 `DoubleArray(1)` 改成 `Array<DoubleArray>`（每声道一份）。 */
    private fun monoState() = Array(PcmRms.MAX_CHANNELS) { DoubleArray(2) }

    @Test
    fun `低通只让低频通过`() {
        val sampleRate = 44_100
        val state = monoState()
        val bass60 = PcmRms.analyze(sine(60.0, sampleRate, 8192), C.ENCODING_PCM_16BIT, sampleRate, 1, state)
        state[0][0] = 0.0
        val bass6k = PcmRms.analyze(sine(6000.0, sampleRate, 8192), C.ENCODING_PCM_16BIT, sampleRate, 1, state)

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
        val packed = PcmRms.analyze(buffer.duplicate(), C.ENCODING_PCM_16BIT, sampleRate, 1, monoState())
        assertEquals(reference.toFloat(), PcmRms.fullOf(packed), 1e-5f)
    }

    @Test
    fun `低频读数是全带的下界`() {
        // 低通是能量意义上的"取一部分"，所以低频 RMS ≤ 全带 RMS。
        val sampleRate = 44_100
        val packed = PcmRms.analyze(sine(200.0, sampleRate, 4096), C.ENCODING_PCM_16BIT, sampleRate, 1, monoState())
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
        val packed = PcmRms.analyze(sine(440.0, sampleRate, 1024), C.ENCODING_PCM_16BIT, 0, 1, monoState())
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
        val oneShot = PcmRms.analyze(whole.duplicate(), C.ENCODING_PCM_16BIT, sampleRate, 1, monoState())

        val state = monoState()
        val first = sine(80.0, sampleRate, 2048)
        PcmRms.analyze(first, C.ENCODING_PCM_16BIT, sampleRate, 1, state)
        val second = sine(80.0, sampleRate, 2048)
        val secondHalf = PcmRms.analyze(second, C.ENCODING_PCM_16BIT, sampleRate, 1, state)
        assertTrue("分两段算的低频读数应与整体接近", abs(PcmRms.bassOf(secondHalf) - PcmRms.bassOf(oneShot)) < 0.05f)
    }

    @Test
    fun `空缓冲与未知编码不抛异常`() {
        val empty = ByteBuffer.allocate(0)
        val packed = PcmRms.analyze(empty, C.ENCODING_PCM_16BIT, 44_100, 1, monoState())
        assertEquals(0f, PcmRms.fullOf(packed), 0f)
        val unknown = PcmRms.analyze(sine(440.0, 44_100, 128), C.ENCODING_PCM_24BIT, 44_100, 1, monoState())
        assertEquals(0f, PcmRms.fullOf(unknown), 0f)
        // 状态数组为空也不许崩（调用方传了长度为 0 的数组）。
        val noState = PcmRms.analyze(sine(440.0, 44_100, 128), C.ENCODING_PCM_16BIT, 44_100, 1, Array(0) { DoubleArray(2) })
        assertTrue(PcmRms.fullOf(noState) > 0f)
        assertEquals(sqrt(0.8 * 0.8 / 2).toFloat(), PcmRms.fullOf(noState), 0.02f)
        // 没有状态 ⇒ 不做滤波，低频退化成全带（而不是崩，也不是 0）。
        assertEquals(PcmRms.fullOf(noState), PcmRms.bassOf(noState), 1e-6f)
    }

    // ------------------------------------------------------------------
    // v3.0.0 回归：交错 PCM 的滤波状态必须**每声道一份**
    // ------------------------------------------------------------------

    /** 生成交错多声道正弦（每个声道同一个信号）。 */
    private fun interleavedSine(
        freqHz: Double,
        sampleRateHz: Int,
        frames: Int,
        channels: Int,
        amplitude: Double = 0.8,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (sin(2.0 * PI * freqHz * i / sampleRateHz) * amplitude * 32767).toInt()
            repeat(channels) {
                buf.put((v and 0xFF).toByte())
                buf.put(((v shr 8) and 0xFF).toByte())
            }
        }
        buf.flip()
        return buf
    }

    /**
     * **修复前这条会红**：单一状态 + 交错推进 ⇒ 有效截止频率 × 声道数。
     *
     * 判据用「同一个 150Hz 正弦在单声道与立体声下的低频读数必须接近」——
     * 修复前立体声的状态每帧被推进两次，等效截止变成 ~300Hz，
     * 而 150Hz 正弦在 300Hz 低通下几乎原样通过 ⇒ 读数明显偏大。
     */
    @Test
    fun `立体声的低频读数与单声道一致（有效截止不被声道数放大）`() {
        val sampleRate = 44_100
        val frames = 16_384
        val mono = PcmRms.analyze(
            interleavedSine(150.0, sampleRate, frames, 1),
            C.ENCODING_PCM_16BIT, sampleRate, 1, monoState(),
        )
        val stereo = PcmRms.analyze(
            interleavedSine(150.0, sampleRate, frames, 2),
            C.ENCODING_PCM_16BIT, sampleRate, 2, monoState(),
        )
        val monoLow = PcmRms.bassOf(mono)
        val stereoLow = PcmRms.bassOf(stereo)
        assertTrue(
            "立体声低频读数被声道数放大了（单声道 $monoLow / 立体声 $stereoLow）" +
                "—— 说明滤波状态没有按声道分开",
            abs(monoLow - stereoLow) < 0.02f,
        )
        // 150Hz 正好在截止点上 ⇒ 幅度约降到 1/√2（-3dB），既不是全通也不是全阻。
        val expected = sqrt(0.8 * 0.8 / 2).toFloat()
        assertTrue("150Hz 在截止点上不该原样通过：$stereoLow", stereoLow < expected * 0.95f)
        assertTrue("150Hz 也不该被完全滤掉：$stereoLow", stereoLow > expected * 0.4f)
    }

    /** 6 声道（QQ 臻品档的实测布局）同样不许把截止频率乘 6。 */
    @Test
    fun `六声道的低频读数与单声道一致`() {
        val sampleRate = 44_100
        val frames = 8_192
        val mono = PcmRms.analyze(
            interleavedSine(150.0, sampleRate, frames, 1),
            C.ENCODING_PCM_16BIT, sampleRate, 1, monoState(),
        )
        val six = PcmRms.analyze(
            interleavedSine(150.0, sampleRate, frames, 6),
            C.ENCODING_PCM_16BIT, sampleRate, 6, monoState(),
        )
        // 6 声道共用一个状态时等效截止 ≈ 900Hz ⇒ 150Hz 几乎全过（读数≈全带 0.566）。
        assertTrue(
            "6 声道下低频通道失真（单声道 ${PcmRms.bassOf(mono)} / 6 声道 ${PcmRms.bassOf(six)}）",
            abs(PcmRms.bassOf(mono) - PcmRms.bassOf(six)) < 0.03f,
        )
    }
}
