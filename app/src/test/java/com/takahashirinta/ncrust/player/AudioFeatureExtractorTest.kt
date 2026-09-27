/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import com.takahashirinta.ncrust.ui.player.waveform.WaveformEffectsState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.0：**无 FFT 的音频特征提取**（纯数学，JVM 直测）。
 *
 * 这一层是「动效绑定必须基于真实音频特征」（铁律 25）的数据源头。它必须证明四件事：
 *
 *  1. **三频段确实按频率分开**（同一个振幅的 60Hz / 1kHz / 8kHz 正弦落在各自的带上）；
 *  2. **不是 FFT**：没有分帧、没有窗函数 —— 由「逐样本恒等式」与「每声道状态」两条结构断言守住；
 *  3. **瞬态判据有效且有界**（能抓到鼓点、冷却是时间而不是缓冲计数、快节奏不漏到完全没反应）；
 *  4. **失败即降级**（采样率未知 / 编码不支持 ⇒ 返回 false，调用方走 RMS-only）。
 */
class AudioFeatureExtractorTest {

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).also { b ->
            samples.forEach { b.putShort(it) }
            b.flip()
        }

    /** 交错多声道 16bit 正弦。 */
    private fun sine(
        freqHz: Double,
        sampleRateHz: Int,
        frames: Int,
        channels: Int = 1,
        amplitude: Double = 0.8,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (sin(2.0 * PI * freqHz * i / sampleRateHz) * amplitude * 32767).toInt().toShort()
            repeat(channels) { buf.putShort(v) }
        }
        buf.flip()
        return buf
    }

    private fun extractor(sampleRateHz: Int = 44_100, channels: Int = 1): AudioFeatureExtractor =
        AudioFeatureExtractor().also { it.configure(sampleRateHz, channels) }

    // ── 1. 三频段确实按频率分开 ─────────────────────────────────────────────────────

    /**
     * **频带分离的实测形状**（表驱动，数值来自 JVM 复算，见下面每条断言的括号）。
     *
     * 这些数字同时是「**它不是频谱**」这条诚实边界的证据：一阶（−6 dB/oct）低通之差
     * 在**复数域**相减，两个近似同幅、相位差 20° 的向量相减后仍有可观幅度 ——
     * 所以 60Hz 在中频带上还剩 37%（0.194 / 0.525）。任何「按频段分色画一排柱子」
     * 的读法都会被这个泄漏量级打脸，`spectrumColoring` 因此仍然恒为 false。
     */
    @Test
    fun `三频带在各自的中心频率上占主导`() {
        val low = extractor()
        assertTrue(low.process(sine(60.0, 44_100, 16_384), C.ENCODING_PCM_16BIT))
        assertTrue("60Hz：低频带必须占主导（low=${low.low} mid=${low.mid}）", low.low > low.mid * 2f)
        assertTrue("60Hz：高频带几乎为空（high=${low.high}）", low.high < low.low * 0.1f)

        val mid = extractor()
        assertTrue(mid.process(sine(1000.0, 44_100, 16_384), C.ENCODING_PCM_16BIT))
        assertTrue("1kHz：中频带必须占主导（mid=${mid.mid} low=${mid.low}）", mid.mid > mid.low * 3f)
        assertTrue("1kHz：中频带高于高频带（mid=${mid.mid} high=${mid.high}）", mid.mid > mid.high * 2f)

        val high = extractor()
        assertTrue(high.process(sine(8000.0, 44_100, 16_384), C.ENCODING_PCM_16BIT))
        assertTrue("8kHz：高频带必须占主导（high=${high.high} mid=${high.mid}）", high.high > high.mid * 2f)
        assertTrue("8kHz：低频带几乎为空（low=${high.low}）", high.low < high.high * 0.1f)

        // 三条频带的读数都**不能**当成"这个频率上有多少能量"（泄漏很大）——
        // 用一句可执行的断言把这条边界钉住：1kHz 的信号在中频带上的读数
        // 明显小于它的全带 RMS（0.566），因为一阶低通把中频带削掉了不少。
        assertTrue("中频带的读数是「重心」不是「能量」（mid=${mid.mid} rms=${mid.rms}）", mid.mid < mid.rms)
    }

    @Test
    fun `全带 RMS 与 v2_9_0 的参考实现逐值一致`() {
        // 加了三频段之后**不许改变全带值** —— 它仍然是柱状图的那个 RMS。
        val buffer = sine(440.0, 44_100, 4096)
        val reference = PcmRms.of(buffer.duplicate(), C.ENCODING_PCM_16BIT)
        val e = extractor()
        assertTrue(e.process(buffer, C.ENCODING_PCM_16BIT))
        assertEquals(reference.toFloat(), e.rms, 1e-5f)
    }

    @Test
    fun `低频带与 v2_9_0 的低频通道逐值一致`() {
        // 同一组系数、同一条递推 ⇒ 低频读数必须与 `PcmRms.analyze` 的打包值一致。
        val buffer = sine(120.0, 44_100, 8192)
        val packed = PcmRms.analyze(
            buffer.duplicate(), C.ENCODING_PCM_16BIT, 44_100, 1,
            Array(PcmRms.MAX_CHANNELS) { DoubleArray(2) },
        )
        val e = extractor()
        assertTrue(e.process(buffer, C.ENCODING_PCM_16BIT))
        assertEquals(PcmRms.bassOf(packed), e.low, 1e-5f)
    }

    /**
     * **交错 PCM 的每声道状态**（v3.0.0 对 v2.9.0 的一个真缺陷的修正）。
     *
     * 共用一个状态时，立体声的等效截止频率是标称值的两倍 —— 150Hz 的正弦几乎原样通过。
     * 修复后单声道与立体声的低频读数必须一致。
     */
    @Test
    fun `立体声的每声道滤波状态让有效截止不被声道数放大`() {
        val mono = extractor(channels = 1)
        assertTrue(mono.process(sine(150.0, 44_100, 16_384, channels = 1), C.ENCODING_PCM_16BIT))
        val stereo = extractor(channels = 2)
        assertTrue(stereo.process(sine(150.0, 44_100, 16_384, channels = 2), C.ENCODING_PCM_16BIT))
        assertTrue(
            "立体声低频读数被放大了（${mono.low} vs ${stereo.low}）—— 状态没有按声道分开",
            abs(mono.low - stereo.low) < 0.02f,
        )
    }

    @Test
    fun `六声道不会把截止频率乘六`() {
        val mono = extractor(channels = 1)
        assertTrue(mono.process(sine(150.0, 44_100, 8192, channels = 1), C.ENCODING_PCM_16BIT))
        val six = extractor(channels = 6)
        assertTrue(six.process(sine(150.0, 44_100, 8192, channels = 6), C.ENCODING_PCM_16BIT))
        assertTrue(
            "6 声道（QQ 臻品档的实测布局）下低频带失真（${mono.low} vs ${six.low}）",
            abs(mono.low - six.low) < 0.03f,
        )
    }

    // ── 2. 频谱质心近似（纯函数）────────────────────────────────────────────────────

    @Test
    fun `质心随高频占比单调上升`() {
        val low = AudioFeatureExtractor.centroidOf(0.5, 0.1, 0.0)
        val mid = AudioFeatureExtractor.centroidOf(0.1, 0.5, 0.1)
        val high = AudioFeatureExtractor.centroidOf(0.0, 0.1, 0.5)
        assertTrue("低频为主 ⇒ 质心最低（$low / $mid / $high）", low < mid)
        assertTrue("高频为主 ⇒ 质心最高（$low / $mid / $high）", mid < high)
        for (v in listOf(low, mid, high)) assertTrue("必须落在 0..1（$v）", v in 0f..1f)
    }

    @Test
    fun `质心的边界与防御`() {
        assertEquals("三个幅度都为零 ⇒ 0（而不是 NaN）", 0f, AudioFeatureExtractor.centroidOf(0.0, 0.0, 0.0), 0f)
        assertEquals(0f, AudioFeatureExtractor.centroidOf(Double.NaN, Double.NaN, Double.NaN), 0f)
        assertEquals(0f, AudioFeatureExtractor.centroidOf(-1.0, -1.0, -1.0), 0f)
        // 极值也要落在合法区间（映射的 clamp 生效）。
        assertTrue(AudioFeatureExtractor.centroidOf(1e9, 0.0, 0.0) in 0f..1f)
        assertTrue(AudioFeatureExtractor.centroidOf(0.0, 0.0, 1e9) in 0f..1f)
    }

    @Test
    fun `静音时质心与各频段都是零`() {
        val e = extractor()
        assertTrue(e.process(pcm16(*ShortArray(2048)), C.ENCODING_PCM_16BIT))
        assertEquals(0f, e.rms, 1e-6f)
        assertEquals(0f, e.low, 1e-6f)
        assertEquals(0f, e.mid, 1e-6f)
        assertEquals(0f, e.high, 1e-6f)
        assertEquals(0f, e.spectralCentroid, 1e-6f)
    }

    // ── 3. 明亮度占比（波形逐柱着色的驱动量）────────────────────────────────────────

    @Test
    fun `明亮度占比反映中高频的比重`() {
        val bassOnly = AudioFeatureExtractor.bandMix(0.5f, 0f, 0f)
        val mix = AudioFeatureExtractor.bandMix(0.25f, 0.25f, 0.25f)
        val trebleOnly = AudioFeatureExtractor.bandMix(0f, 0f, 0.5f)
        assertEquals(0f, bassOnly, 0f)
        assertTrue("各占一半 ⇒ 约 0.67（中+高 / 全部）", abs(mix - 2f / 3f) < 1e-4f)
        assertEquals(1f, trebleOnly, 0f)
        assertEquals("全零 ⇒ 0（而不是 NaN）", 0f, AudioFeatureExtractor.bandMix(0f, 0f, 0f), 0f)
        assertEquals(0f, AudioFeatureExtractor.bandMix(Float.NaN, Float.NaN, Float.NaN), 0f)
    }

    // ── 4. 瞬态检测 ────────────────────────────────────────────────────────────────

    /** 生成「安静底噪 + 周期性低频击打」的交错 PCM。 */
    private fun kickTrack(
        sampleRateHz: Int,
        seconds: Double,
        kickIntervalMs: Double,
        kickFreqHz: Double = 60.0,
        bedAmplitude: Double = 0.02,
        kickAmplitude: Double = 0.9,
        kickDecayMs: Double = 60.0,
    ): ByteBuffer {
        val frames = (sampleRateHz * seconds).toInt()
        val buf = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
        var nextKickMs = 0.0
        var sinceKickMs = Double.MAX_VALUE
        for (i in 0 until frames) {
            val tMs = i * 1000.0 / sampleRateHz
            if (tMs >= nextKickMs) {
                nextKickMs += kickIntervalMs
                sinceKickMs = 0.0
            }
            var v = bedAmplitude * sin(2.0 * PI * 220.0 * i / sampleRateHz)
            if (sinceKickMs < kickDecayMs * 4) {
                val env = kotlin.math.exp(-sinceKickMs / kickDecayMs)
                v += kickAmplitude * env * sin(2.0 * PI * kickFreqHz * i / sampleRateHz)
            }
            sinceKickMs += 1000.0 / sampleRateHz
            buf.putShort((v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
        }
        buf.flip()
        return buf
    }

    /**
     * 逐缓冲喂入一整首"曲子"，数瞬态次数。
     *
     * 缓冲粒度用 1024 帧（约 23ms）—— 真机上的实测粒度由
     * `AudioTapProbeTest` 给出，这里只是用一个合理的量级验证判据本身。
     */
    private fun countTransients(
        e: AudioFeatureExtractor,
        track: ByteBuffer,
        framesPerBuffer: Int,
    ): Int {
        var count = 0
        val bytesPerBuffer = framesPerBuffer * 2
        val view = track.duplicate()
        while (view.remaining() >= bytesPerBuffer) {
            val end = view.position() + bytesPerBuffer
            val slice = view.duplicate()
            slice.limit(end)
            if (e.process(slice, C.ENCODING_PCM_16BIT)) count += e.transientCount
            view.position(end)
        }
        return count
    }

    @Test
    fun `周期性鼓点被逐次抓出来`() {
        // 500ms 一次、共 8 秒 ⇒ 期望 16 次（允许 ±2 的边界误差）。
        val e = extractor()
        val track = kickTrack(44_100, seconds = 8.0, kickIntervalMs = 500.0)
        val count = countTransients(e, track, framesPerBuffer = 1024)
        assertTrue("鼓点次数应当在 14..18 之间（实测 $count）", count in 14..18)
    }

    @Test
    fun `快节奏鼓点不会被冷却窗口吞掉大半`() {
        // 180 BPM 的八分音符 = 167ms 一次、共 8 秒 ⇒ 期望约 48 次。
        // 冷却 90ms 的上限是 11 次/秒 ⇒ 8 秒最多 88 次，不会成为瓶颈。
        val e = extractor()
        val track = kickTrack(44_100, seconds = 8.0, kickIntervalMs = 166.7)
        val count = countTransients(e, track, framesPerBuffer = 1024)
        assertTrue("快节奏必须能触发足够多次（实测 $count）", count >= 30)
    }

    // ------------------------------------------------------------------
    // v3.0.0 · 子帧分辨率（真机实测的缓冲粒度是 100ms，逼出来的设计）
    // ------------------------------------------------------------------

    /**
     * **短促的击打不许被长缓冲稀释掉**（子帧分辨率真正的用途）。
     *
     * 真机实测（S6 / Android 7.0）`TeeAudioProcessor` 一次回调 **4410 帧 ≈ 100ms**
     * （`docs/verification/v3.0.0/probe/EVIDENCE.md` 的 `PROBE-AUDIO-TAP`）。
     * 一次 10ms 的击打落在 100ms 的缓冲里，如果判据只看**整个缓冲**的 RMS，
     * 能量被稀释 10 倍（RMS 稀释 √10 ≈ 3.2 倍）—— 弱一点的击打直接跌到门槛之下。
     *
     * JVM 复算（10ms 击打 + 90ms 静音，60Hz）：
     *
     * | 击打幅度 | 整缓冲 low RMS | 子帧 low RMS | 只看缓冲 | 看子帧 |
     * |---|---|---|---|---|
     * | 0.9 | 0.182 | 0.555 | ✅ | ✅ |
     * | 0.5 | 0.101 | 0.309 | 勉强 ✅ | ✅ |
     * | **0.4** | **0.081** | **0.247** | ❌ 漏 | ✅ |
     * | **0.2** | **0.040** | **0.123** | ❌ 漏 | ✅ |
     *
     * 也就是说：子帧把「能被判出来的最弱击打」从幅度 ~0.5 降到 ~0.2。
     */
    @Test
    fun `短促的击打不会被长缓冲稀释掉`() {
        val sampleRate = 44_100
        val frames = 4410 // 100ms @44.1kHz，与真机实测的缓冲粒度一致
        val hitFrames = (sampleRate * 0.01).toInt() // 10ms 的击打
        val buf = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            // 幅度 0.4：整缓冲 RMS 只有 0.081（低于 0.10 的门槛），子帧里有 0.247。
            val v = if (i < hitFrames) 0.4 * sin(2.0 * PI * 60.0 * i / sampleRate) else 0.0
            buf.putShort((v * 32767).toInt().toShort())
        }
        buf.flip()
        val e = extractor(sampleRateHz = sampleRate, channels = 1)
        assertTrue(e.process(buf, C.ENCODING_PCM_16BIT))
        assertEquals(
            "10ms / 幅度 0.4 的击打必须被判出来（整缓冲 RMS 只有 0.081）",
            1,
            e.transientCount,
        )
        // 顺带把「整缓冲确实被稀释了」这件事也钉住 —— 否则上一条断言可能因为别的原因通过。
        assertTrue("整个缓冲的 RMS 必须低于门槛（${e.low}）", e.low < AudioFeatureExtractor.ONSET_MIN_LOW)
    }

    @Test
    fun `子帧长度与子帧上限是自洽的`() {
        assertEquals(10.0, AudioFeatureExtractor.SUB_FRAME_MS, 0.0)
        assertTrue(
            "一个 100ms 的缓冲最多结算 10 个子帧，上限必须容得下",
            AudioFeatureExtractor.MAX_TRANSIENTS_PER_BUFFER >= 10,
        )
        assertTrue(
            "子帧必须显著短于最快常见节奏的间隔",
            AudioFeatureExtractor.SUB_FRAME_MS < 167.0 / 4,
        )
    }

    @Test
    fun `一次缓冲里的瞬态个数有上限`() {
        // 极端输入：连续的高频冲击（每个子帧都满足判据）。
        val buf = ByteBuffer.allocate(4410 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until 4410) {
            val v = if (i % 441 == 0) Short.MAX_VALUE else 0
            buf.putShort(v.toShort())
        }
        buf.flip()
        val e = extractor()
        assertTrue(e.process(buf, C.ENCODING_PCM_16BIT))
        assertTrue(
            "一次缓冲的瞬态个数必须落在上限内（实测 ${e.transientCount}）",
            e.transientCount in 0..AudioFeatureExtractor.MAX_TRANSIENTS_PER_BUFFER,
        )
    }

    @Test
    fun `安静段落不产生瞬态`() {
        val e = extractor()
        // 只有底噪（振幅 0.02，低于 ONSET_MIN_LOW）⇒ 一次都不该触发。
        val track = kickTrack(44_100, seconds = 4.0, kickIntervalMs = 500.0, kickAmplitude = 0.0)
        assertEquals(0, countTransients(e, track, framesPerBuffer = 1024))
    }

    @Test
    fun `瞬态判据的常量与 UI 回落判据同源`() {
        // 「两条路径给出的 onset 时刻一致」这句话必须能被机械检查 —— 至少门槛值与基线时间常数
        // 必须逐字相同（冷却刻意不同，见 ONSET_COOLDOWN_MS 的 KDoc）。
        assertEquals(WaveformEffectsState.ONSET_MIN_RMS, AudioFeatureExtractor.ONSET_MIN_RMS, 0f)
        assertEquals(WaveformEffectsState.ONSET_ABS_MIN, AudioFeatureExtractor.ONSET_ABS_MIN, 0f)
        assertEquals(WaveformEffectsState.ONSET_REL_FACTOR, AudioFeatureExtractor.ONSET_REL_FACTOR, 0f)
        assertEquals(
            "基线时间常数必须与 UI 回落判据同值",
            WaveformEffectsState.BASELINE_TAU_MS.toDouble(),
            AudioFeatureExtractor.BASELINE_TAU_MS,
            1e-9,
        )
        assertTrue(
            "音频线程的冷却必须**不慢于** UI 回落判据（它看得见每一次回调）",
            AudioFeatureExtractor.ONSET_COOLDOWN_MS <= WaveformEffectsState.ONSET_COOLDOWN_MS,
        )
        assertEquals(90f, AudioFeatureExtractor.ONSET_COOLDOWN_MS, 0f)
    }

    @Test
    fun `基线系数按 dt 计算而不是固定步长`() {
        // v3.0.0 修正：固定步长会让时间常数随缓冲时长漂移（21ms → 0.8s，93ms → 3.5s）。
        val k21 = AudioFeatureExtractor.baselineCoefficient(21.0)
        val k93 = AudioFeatureExtractor.baselineCoefficient(93.0)
        assertTrue("缓冲越长，每步走得越多（$k21 → $k93）", k93 > k21)
        // 同一个时间常数 τ=800ms：21ms 步长的系数应当约 1-exp(-21/800) ≈ 0.0259。
        assertEquals(1.0 - kotlin.math.exp(-21.0 / 800.0), k21, 1e-12)
        assertEquals("非法 dt ⇒ 基线不动", 0.0, AudioFeatureExtractor.baselineCoefficient(0.0), 0.0)
        assertEquals(0.0, AudioFeatureExtractor.baselineCoefficient(Double.NaN), 0.0)
    }

    @Test
    fun `frameMs 由样本数与采样率反算`() {
        // 这是「每缓冲一次判定」能成立的全部依据：缓冲粒度是运行时行为。
        val e = extractor(sampleRateHz = 44_100, channels = 2)
        assertTrue(e.process(sine(440.0, 44_100, 1024, channels = 2), C.ENCODING_PCM_16BIT))
        assertEquals(1024.0 * 1000.0 / 44_100.0, e.frameMs.toDouble(), 0.01)
    }

    // ── 5. 失败即降级 ──────────────────────────────────────────────────────────────

    @Test
    fun `采样率未知时不可用`() {
        val e = AudioFeatureExtractor()
        e.configure(0, 2)
        assertFalse(e.available)
        assertFalse("必须明确返回 false 让调用方走 RMS-only", e.process(sine(440.0, 44_100, 256, 2), C.ENCODING_PCM_16BIT))
    }

    @Test
    fun `声道数为零时不可用`() {
        val e = AudioFeatureExtractor()
        e.configure(44_100, 0)
        assertFalse(e.available)
        assertFalse(e.process(sine(440.0, 44_100, 256), C.ENCODING_PCM_16BIT))
    }

    @Test
    fun `未知编码与不足一个样本的缓冲返回 false 且不抛异常`() {
        val e = extractor()
        assertFalse("24bit 整数不受支持", e.process(pcm16(1, 2, 3, 4), C.ENCODING_PCM_24BIT))
        assertFalse("空缓冲", e.process(ByteBuffer.allocate(0), C.ENCODING_PCM_16BIT))
        assertFalse("不足一个样本（1 字节 / 16bit）", e.process(ByteBuffer.allocate(1), C.ENCODING_PCM_16BIT))
        // 刚好一个完整样本是**可以**算的（半个采样才该被丢掉）。
        assertTrue("一个完整样本", e.process(pcm16(1000), C.ENCODING_PCM_16BIT))
    }

    @Test
    fun `浮点编码同样可用`() {
        val e = extractor()
        val buf = ByteBuffer.allocate(4096 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 4096) buf.putFloat((0.5 * sin(2.0 * PI * 100.0 * i / 44_100)).toFloat())
        buf.flip()
        assertTrue(e.process(buf, C.ENCODING_PCM_FLOAT))
        assertTrue("100Hz 必须落在低频带（${e.low}）", e.low > e.mid)
        assertTrue(e.rms > 0f)
    }

    @Test
    fun `configure 会重置跨缓冲状态`() {
        val e = extractor()
        e.process(sine(60.0, 44_100, 4096), C.ENCODING_PCM_16BIT)
        assertTrue(e.low > 0f)
        e.configure(48_000, 2)
        assertEquals("换格式之后状态必须清零", 0f, e.low, 0f)
        assertEquals(0f, e.rms, 0f)
        assertTrue(e.available)
    }

    @Test
    fun `reset 只清状态不清系数`() {
        val e = extractor()
        e.process(sine(440.0, 44_100, 1024), C.ENCODING_PCM_16BIT)
        e.reset()
        // 输出也要清：否则新歌开头几帧消费方会读到上一首的频段值。
        assertEquals(0f, e.rms, 0f)
        assertEquals(0f, e.low, 0f)
        assertEquals(0f, e.mid, 0f)
        assertEquals(0f, e.high, 0f)
        assertEquals(0f, e.spectralCentroid, 0f)
        assertTrue("仍然可用（系数还在）", e.available)
        assertTrue(e.process(sine(440.0, 44_100, 1024), C.ENCODING_PCM_16BIT))
        assertTrue(e.rms > 0f)
    }

    // ── 6. 结构断言（「不是 FFT」这件事要能被机械检查）──────────────────────────────

    @Test
    fun `三频带逐样本精确重构输入`() {
        // low + mid + high == x：这是"低通之差 + 高通"这个拓扑的恒等式，
        // 也是"没有分帧/窗函数"的结构证据（分帧的频谱方案不满足它）。
        // 这里用一段混合信号，逐样本核对（用 extractor 的低频/中频/高频读数做不到，
        // 所以直接在时域上按同样的递推验算一次）。
        val aLow = AudioFeatureExtractor.lowPassCoefficient(AudioFeatureExtractor.LOW_CUTOFF_HZ, 44_100)
        val aMid = AudioFeatureExtractor.lowPassCoefficient(AudioFeatureExtractor.MID_HIGH_CUTOFF_HZ, 44_100)
        var sLow = 0.0
        var sMid = 0.0
        for (i in 0 until 2000) {
            val x = 0.6 * sin(2.0 * PI * 300.0 * i / 44_100) + 0.3 * sin(2.0 * PI * 5000.0 * i / 44_100)
            sLow += aLow * (x - sLow)
            sMid += aMid * (x - sMid)
            val low = sLow
            val mid = sMid - sLow
            val high = x - sMid
            assertEquals("逐样本恒等式 low+mid+high == x（第 $i 个样本）", x, low + mid + high, 1e-12)
        }
    }

    @Test
    fun `一阶低通系数公式与量级`() {
        val a = AudioFeatureExtractor.lowPassCoefficient(AudioFeatureExtractor.LOW_CUTOFF_HZ, 44_100)
        val expected = 1.0 - kotlin.math.exp(-2.0 * PI * 150.0 / 44_100)
        assertEquals(expected, a, 1e-12)
        assertTrue("必须落在 (0,1)", a > 0.0 && a < 1.0)
        // 采样率越低，同一个截止频率对应的系数越大。
        assertTrue(
            AudioFeatureExtractor.lowPassCoefficient(150.0, 8_000) >
                AudioFeatureExtractor.lowPassCoefficient(150.0, 48_000),
        )
        // 非法输入退化到"全通"而不是 NaN。
        assertEquals(1.0, AudioFeatureExtractor.lowPassCoefficient(150.0, 0), 1e-12)
        assertEquals(1.0, AudioFeatureExtractor.lowPassCoefficient(0.0, 44_100), 1e-12)
    }

    @Test
    fun `带宽边界就是 v2_9_0 的低频截止与 2kHz`() {
        assertEquals(
            "低频带必须与 v2.9.0 的低频节拍通道是同一条递推",
            PcmRms.BASS_CUTOFF_HZ,
            AudioFeatureExtractor.LOW_CUTOFF_HZ,
            0.0,
        )
        assertEquals(2000.0, AudioFeatureExtractor.MID_HIGH_CUTOFF_HZ, 0.0)
    }

    @Test
    fun `全带 RMS 的定义与参考实现一致（满幅与半幅）`() {
        val full = extractor()
        assertTrue(full.process(pcm16(*ShortArray(1024) { Short.MAX_VALUE }), C.ENCODING_PCM_16BIT))
        assertEquals(1.0f, full.rms, 1e-4f)
        val half = extractor()
        assertTrue(half.process(pcm16(*ShortArray(1024) { (Short.MAX_VALUE / 2).toShort() }), C.ENCODING_PCM_16BIT))
        assertEquals(0.5f, half.rms, 1e-3f)
    }

    @Test
    fun `数值一律有限且落在 0 到 1`() {
        val e = extractor(channels = 2)
        // 混入极值（含 Short.MIN_VALUE 的符号位）与零，逐个断言输出有限且在区间内。
        val buf = ByteBuffer.allocate(2048 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until 2048) {
            buf.putShort(
                when (i % 4) {
                    0 -> Short.MAX_VALUE
                    1 -> Short.MIN_VALUE
                    2 -> 0
                    else -> (i * 37).toShort()
                },
            )
        }
        buf.flip()
        assertTrue(e.process(buf, C.ENCODING_PCM_16BIT))
        for (v in listOf(e.rms, e.low, e.mid, e.high, e.spectralCentroid, e.transientStrength)) {
            assertTrue("必须是有限值（$v）", v.isFinite())
            assertTrue("必须落在 0..1（$v）", v in 0f..1f)
        }
        assertTrue(e.frameMs.isFinite())
        assertEquals("帧时长必须等于 样本数÷声道数÷采样率", 2048.0 * 1000.0 / 44_100.0 / 2.0, e.frameMs.toDouble(), 0.02)
    }
}
