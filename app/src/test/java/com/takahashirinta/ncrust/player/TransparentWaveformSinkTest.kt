/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.1 · P0 回归单测：**可视化 tee 不得因为声道数把播放器搞崩**。
 *
 * 被钉住的事实（真机 + media3 1.5.0 字节码双重验证）：
 *  - QQ「臻品音质」档回的 `Q001…flac` 是 **6 声道** FLAC；
 *  - media3 的 `ChannelMixingMatrix.createMixingCoefficients` 只实现
 *    「N→N / 1→2 / 2→1」，**6→1 抛 UnsupportedOperationException**；
 *  - 旧代码 `WaveformAudioBufferSink(barsPerSecond, 1, …)` 正好请求 6→1，
 *    于是切一下「杜比」就把 AudioSink 打坏，之后同一 player 实例任何档位都播不出来。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import androidx.media3.common.audio.ChannelMixingMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransparentWaveformSinkTest {

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).also { b ->
            samples.forEach { b.putShort(it) }
            b.flip()
        }

    private fun pcmFloat(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder()).also { b ->
            samples.forEach { b.putFloat(it) }
            b.flip()
        }

    /**
     * 核心回归：**旧配置在 6 声道输入上必然抛异常**。
     * 这条用例把「为什么必须换掉 WaveformAudioBufferSink(…, 1, …)」固定成证据，
     * 而不是一句注释 —— 谁想退回单声道混音，先看它红。
     */
    @Test
    fun `mono output config is what used to throw for six channel input`() {
        assertThrows(UnsupportedOperationException::class.java) {
            ChannelMixingMatrix.create(2, 1).let { /* 2→1 支持 */ }
            ChannelMixingMatrix.create(6, 1)
        }
    }

    /** 新的 sink 对 6 声道输入**不抛异常**（它根本不建矩阵）。 */
    @Test
    fun `six channel pcm16 buffer is handled without throwing`() {
        val sink = TransparentWaveformSink()
        sink.flush(44_100, 6, C.ENCODING_PCM_16BIT)
        // 两个 frame × 6 声道，全零 ⇒ RMS = 0，且**绝不抛**。
        sink.handleBuffer(pcm16(*ShortArray(12)))
        Unit
    }

    /** RMS 数学本身：满幅 = 1.0、静音 = 0.0，与声道数无关（6 声道也照算）。 */
    @Test
    fun `rms is channel count agnostic`() {
        val monoFull = pcm16(Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE)
        assertEquals(1.0, PcmRms.of(monoFull, C.ENCODING_PCM_16BIT), 0.001)

        val silence = pcm16(*ShortArray(12))
        assertEquals(0.0, PcmRms.of(silence, C.ENCODING_PCM_16BIT), 0.0001)

        // 6 声道（QQ 臻品档的实测布局）：旧实现在这一档上抛异常，新实现照常算出数值。
        val sixChannel = pcm16(
            Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE, Short.MAX_VALUE,
            Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE,
        )
        assertEquals(1.0, PcmRms.of(sixChannel, C.ENCODING_PCM_16BIT), 0.001)
    }

    @Test
    fun `rms of float samples is in unit scale`() {
        val half = pcmFloat(0.5f, -0.5f, 0.5f, -0.5f)
        assertEquals(0.5, PcmRms.of(half, C.ENCODING_PCM_FLOAT), 0.001)
    }

    @Test
    fun `float and unknown encodings never throw`() {
        val sink = TransparentWaveformSink()
        sink.flush(48_000, 2, C.ENCODING_PCM_FLOAT)
        sink.handleBuffer(pcmFloat(0.5f, -0.5f, 0.25f, -0.25f))
        // 24bit / 32bit 整数等未支持编码：静默返回，绝不把异常抛到音频线程上。
        sink.flush(48_000, 6, C.ENCODING_PCM_24BIT)
        sink.handleBuffer(pcm16(1, 2, 3, 4, 5, 6))
        sink.flush(48_000, 0, C.ENCODING_INVALID)
        sink.handleBuffer(pcm16(1))
        Unit
    }

    /** 空缓冲区 / 半个采样也不能炸（tee 的 flush 边界会送来空 buffer）。 */
    @Test
    fun `empty and ragged buffers are ignored`() {
        val sink = TransparentWaveformSink()
        sink.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        sink.handleBuffer(ByteBuffer.allocate(0))
        sink.handleBuffer(ByteBuffer.allocate(3))
        Unit
    }

    // ------------------------------------------------------------------
    // v2.8.0 · P1-A：以下四组用例钉住探针 §4 的「唯一还能把播放打挂的路径」。
    //
    // 为什么必须用注入点（构造参数）才能测：真机上没有办法让 WaveformStore.onBar 抛异常，
    // 也没有办法观测「关掉开关后 RMS 到底有没有跑」。隔离边界与开关前置这两条契约
    // 一旦只写在注释里，下一次有人往 push() 里加一行加锁/拼日志就会原样恢复 v2.2.1 的
    // 级联形状（tee 异常 → queueInput → DefaultAudioSink.handleBuffer → onPlayerError → 降档循环）。
    // ------------------------------------------------------------------

    /** 旧实现（duplicate + asShortBuffer）的逐字参考版，只用于数值对齐，不再进生产路径。 */
    private fun referenceRms(view: ByteBuffer, encoding: Int): Double {
        val bps = PcmRms.bytesPerSample(encoding)
        if (bps == 0) return 0.0
        if (view.remaining() / bps <= 0) return 0.0
        val copy = view.duplicate().order(ByteOrder.nativeOrder())
        var sum = 0.0
        var n = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val shorts = copy.asShortBuffer()
                while (shorts.hasRemaining()) {
                    val v = shorts.get() / 32768.0
                    sum += v * v
                    n++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floats = copy.asFloatBuffer()
                while (floats.hasRemaining()) {
                    val v = floats.get().toDouble()
                    sum += v * v
                    n++
                }
            }
        }
        if (n == 0) return 0.0
        return kotlin.math.sqrt(sum / n)
    }

    /**
     * 零分配改写**没有改数值**：与旧实现逐样本对齐，且**不改上游 buffer 的游标**。
     *
     * 覆盖三种真实形态：16bit 交错、float、以及 position/limit 被上游挪过的窗口
     * （tee 交过来的 buffer 不保证 position == 0）。
     */
    @Test
    fun `zero allocation rms matches the old duplicate based implementation`() {
        val pcm16Buffer = ByteBuffer.allocate(2048).order(ByteOrder.nativeOrder())
        var seed = 12345
        repeat(1024) {
            seed = seed * 1103515245 + 12345
            pcm16Buffer.putShort((seed shr 8).toShort())
        }
        pcm16Buffer.flip()
        assertEquals(referenceRms(pcm16Buffer, C.ENCODING_PCM_16BIT), PcmRms.of(pcm16Buffer, C.ENCODING_PCM_16BIT), 1e-12)
        assertEquals("position 不能被动过", 0, pcm16Buffer.position())
        assertEquals(2048, pcm16Buffer.limit())

        val floatBuffer = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder())
        repeat(256) { i -> floatBuffer.putFloat(((i % 17) - 8) / 8f) }
        floatBuffer.flip()
        assertEquals(referenceRms(floatBuffer, C.ENCODING_PCM_FLOAT), PcmRms.of(floatBuffer, C.ENCODING_PCM_FLOAT), 1e-12)

        // 非零 position + 非零 arrayOffset 的窗口（上游只放出中间一段）。
        val windowed = pcm16Buffer.duplicate().order(ByteOrder.nativeOrder())
        windowed.position(100).limit(800)
        assertEquals(referenceRms(windowed, C.ENCODING_PCM_16BIT), PcmRms.of(windowed, C.ENCODING_PCM_16BIT), 1e-12)
        assertEquals(100, windowed.position())
        assertEquals(800, windowed.limit())
    }

    /** 极值对齐：满幅 / 静音 / 负数，逐值相等（含 Short.MIN_VALUE 的符号位）。 */
    @Test
    fun `zero allocation rms keeps the sign and scale of extreme samples`() {
        val buffer = pcm16(
            Short.MAX_VALUE, Short.MIN_VALUE, 0, -1, 1, Short.MIN_VALUE,
        )
        assertEquals(
            referenceRms(buffer.duplicate().order(ByteOrder.nativeOrder()), C.ENCODING_PCM_16BIT),
            PcmRms.of(buffer, C.ENCODING_PCM_16BIT),
            1e-12,
        )
    }

    /** ① 消费端抛异常 ⇒ 被吞掉，**绝不向上抛**，且计入 droppedBarCount。 */
    @Test
    fun `a throwing bar consumer never propagates out of handleBuffer`() {
        var calls = 0
        val sink = TransparentWaveformSink(
            enabled = { true },
            // v2.9.0：onBar 现在收两个参数（全带 RMS + 低频通道）。
            onBar = { _, _ ->
                calls++
                throw IllegalStateException("模拟 push() 里将来加了会抛的代码")
            },
        )
        sink.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        // 多次调用都不抛（一次都不行，抛一次就是 onPlayerError → 降档循环）
        repeat(5) { sink.handleBuffer(pcm16(1000, -1000, 2000, -2000)) }
        assertEquals(5, calls)
        assertEquals(5L, sink.droppedBarCount)
    }

    /** ② PCM 分析自己抛异常 ⇒ 同样被吞掉，消费端不会被调用（不会补一个假的 0 柱）。 */
    @Test
    fun `a throwing rms is isolated and drops the bar`() {
        var consumed = 0
        val sink = TransparentWaveformSink(
            enabled = { true },
            onBar = { _, _ -> consumed++ },
            analyze = { _, _ -> throw ArithmeticException("模拟 PCM 分析内部炸了") },
        )
        sink.flush(44_100, 6, C.ENCODING_PCM_16BIT)
        sink.handleBuffer(pcm16(1, 2, 3, 4, 5, 6))
        assertEquals(0, consumed)
        assertEquals(1L, sink.droppedBarCount)
    }

    /** ③ 开关关掉 ⇒ **PCM 遍历一次都不跑**（探针 §5：「关掉开关 = 零开销」此前不成立）。 */
    @Test
    fun `disabling the visualizer skips the whole rms walk`() {
        var rmsCalls = 0
        var consumed = 0
        val sink = TransparentWaveformSink(
            enabled = { false },
            onBar = { _, _ -> consumed++ },
            analyze = { _, _ ->
                rmsCalls++
                PcmRms.pack(0.5f, 0.5f)
            },
        )
        sink.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        repeat(8) { sink.handleBuffer(pcm16(1000, -1000, 2000, -2000)) }
        assertEquals("关掉开关后不得再遍历 PCM", 0, rmsCalls)
        assertEquals(0, consumed)
    }

    /** ④ 未知编码 / 声道数 0 ⇒ 连开关都不看（位置最靠前的漏斗）。 */
    @Test
    fun `unknown encoding and zero channels short circuit before the gate`() {
        var gateReads = 0
        var rmsCalls = 0
        val sink = TransparentWaveformSink(
            enabled = {
                gateReads++
                true
            },
            analyze = { _, _ ->
                rmsCalls++
                PcmRms.pack(0f, 0f)
            },
        )
        sink.flush(44_100, 6, C.ENCODING_PCM_24BIT)
        sink.handleBuffer(pcm16(1, 2, 3))
        sink.flush(44_100, 0, C.ENCODING_PCM_16BIT)
        sink.handleBuffer(pcm16(1, 2, 3))
        assertEquals(0, gateReads)
        assertEquals(0, rmsCalls)
    }
}
