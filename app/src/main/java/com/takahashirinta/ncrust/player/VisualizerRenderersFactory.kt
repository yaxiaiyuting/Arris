/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor

/**
 * v1.8.0 · T3：把音频链路"旁路"出一份 PCM 给可视化用。
 *
 * ## 为什么是这条路（调研结论，别再找别的 API 了）
 *
 * 任务书里假设的 `AudioListener.onAudioSamples` **在 media3 里根本不存在** ——
 * 已用 javap 逐个版本核实：1.4.1 / 1.5.0 / 1.6.0 / 1.7.0 / 1.8.x / 1.9.0 / 1.11.1 的
 * media3-exoplayer 里都没有 `AudioSamples` / `AudioListener` 类，
 * `AudioSink.Listener` 与 `AnalyticsListener` 也没有样本回调。
 *
 * 官方可用的替代是 [TeeAudioProcessor] + [WaveformAudioBufferSink]（1.4.1 起就有，
 * 1.11.1 签名逐字一致）：
 *  - **不需要任何权限** —— 它在应用自己的音频处理链上取数据，不碰录音；
 *    备选方案 `android.media.audiofx.Visualizer` 从 AOSP 7.1.1 起就要求
 *    **RECORD_AUDIO**（不是"Android 10+ 才要"），与项目定位冲突，不采用。
 *  - 与 FFmpeg 软解路径**天然兼容**：`FfmpegAudioRenderer` 继承 `DecoderAudioRenderer`，
 *    而 DefaultRenderersFactory 反射创建它时传的是**同一个 AudioSink 实例**
 *    （DefaultRenderersFactory.java:532-543）；只要 override [buildAudioSink]，
 *    MediaCodec 与 FFmpeg 两条解码路径都会经过这个 tee。
 *
 * ## 线程与开销
 *
 * [WaveformAudioBufferSink] 的回调发生在 **ExoPlayer 的 playback 线程**
 * （`ExoPlayer:Playback`，THREAD_PRIORITY_AUDIO）—— 所以 [WaveformStore.onBar] 里
 * 只允许"一次数组写 + 一次 volatile 自增"，绝不能碰 Compose 状态、不能加锁、不能分配。
 *
 * 处理链顺序是「用户 processors（本 tee 在这里）→ SilenceSkipping → Sonic → 输出」
 * （DefaultAudioSink.java:176-186）⇒ tee 看到的是**解码后的原始 PCM**（未跳静音、未变速），
 * 这正是可视化想要的。
 *
 * ⚠️ [buildAudioSink] 是 base 实现里唯一构造 AudioSink 的地方，override 时要**逐项复刻**
 * base 的三个参数（float 输出 / playback params），否则会静默改掉音频输出行为。
 */
@UnstableApi
class VisualizerRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        // v2.2.1 · P0：**不能用 media3 的 `WaveformAudioBufferSink(…, 1, …)`。**
        //
        // 它的第二个参数是「把输入混成几声道」，传 1 就会在 flush 时构造
        // `ChannelMixingMatrix.create(输入声道数, 1)`；而 media3 1.5.0 只实现了
        // 「同声道数 / 1→2 / 2→1」三种矩阵，**6→1 直接抛 UnsupportedOperationException**。
        // QQ 的臻品档（本应用档位表里的 dolby / jyeffect）实测就是 6 声道 FLAC，
        // 于是「切杜比」必然把 AudioSink 打坏、之后任何档位都播不出声 —— 详见
        // [TransparentWaveformSink] 的档头与 docs/verification/v2.2.1/p0-quality-loop/PROBE.md。
        //
        // 换来的是可视化柱高语义完全不变（同样是 RMS，只是对所有声道一起算）。
        val waveformSink = TransparentWaveformSink()
        val processors = arrayOf<AudioProcessor>(TeeAudioProcessor(waveformSink))
        return DefaultAudioSink.Builder(context)
            .setAudioProcessors(processors)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
