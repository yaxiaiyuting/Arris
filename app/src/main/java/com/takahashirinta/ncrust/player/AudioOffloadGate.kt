/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences

/**
 * v3.4.12 · R2：audio offload 与本地 PCM 链路的**运行中门控**（纯逻辑，JVM 可单测）。
 *
 * ## 为什么需要它
 *
 * 本 fork 的 [VisualizerRenderersFactory] 在 AudioSink 里挂了 `TeeAudioProcessor`，把
 * 解码后的 PCM 旁路给波形 / 音频驱动动效。offload 模式下音频不经过应用侧处理器链，
 * 所以只要“实际 PCM 需求”存在，offload 就必须关闭；两者都关时才允许打开。
 *
 * 关键点是**运行中**：用户可能在播放过程中打开/关闭「音频可视化」或「界面动效」，
 * 不能把门控结果固化在 `PlaybackService.onCreate` 的一次性判断里。这个对象只做
 * 「当前需求 + 当前 offload 状态 → 下一步动作」的判定，监听与真正改播放器参数由
 * [PlaybackService] 负责。
 *
 * ## 与 WaveformStore / MotionPrefs 的口径
 *
 * - 波形总开关：`VisualizerSetting.state.value`（写盘与 `WaveformStore.enabled` 的源头）；
 * - 音频驱动动效：`MotionPrefs.effects.value.needsAudioFeatures`（`MotionPrefs` 每次
 *   update 会同步写 `WaveformStore.motionFeaturesEnabled`，所以两者是同一口径）。
 *
 * 注意 `needsAudioFeatures` **包含**波形的频带着色（`waveBandOn`），但不包含
 * `audio_visualizer` 总开关本身；所以需求必须是「总开关 || 动效特征」。
 *
 * 不轮询：监听由 `snapshotFlow` 完成，见 [PlaybackService.observePcmDemand]。
 */
object AudioOffloadGate {

    /** 当前实际的 PCM 消费需求。两个字段互不包含，必须分别判断。 */
    data class PcmDemand(
        /** 「音频可视化」总开关。关掉时波形整块不挂载，但频带着色开关仍可能独立存在。 */
        val waveformEnabled: Boolean,
        /** 界面动效是否需要音频特征（`MotionEffects.needsAudioFeatures`）。 */
        val motionNeedsAudioFeatures: Boolean,
    ) {
        /** 只要任一路需要 PCM，offload 就不能开。 */
        val requiresPcm: Boolean
            get() = waveformEnabled || motionNeedsAudioFeatures
    }

    /** 对播放器 track selection 的下一步动作。 */
    enum class Action {
        /** 当前无 PCM 需求、offload 还没开 → 打开。 */
        ENABLE,

        /** 当前有 PCM 需求、offload 还开着 → 关闭（且必须立刻关闭）。 */
        DISABLE,

        /** 已经处于目标状态，幂等不动。 */
        KEEP,
    }

    /** media3 的 mode 是否为「开了 offload」（ENABLED / REQUIRED 都算开）。 */
    fun isOffloadEnabled(mode: Int): Boolean =
        mode != AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED

    /**
     * 运行中切换的唯一判据。
     *
     * @param requiredPcm 当前是否仍有任一 PCM 消费方
     * @param offloadEnabled 播放器当前 track selection 的 offload 是否已开
     */
    fun action(requiredPcm: Boolean, offloadEnabled: Boolean): Action = when {
        requiredPcm && offloadEnabled -> Action.DISABLE
        !requiredPcm && !offloadEnabled -> Action.ENABLE
        else -> Action.KEEP
    }

    /** 便捷判据：当前需求是否允许 offload（与 [action] 同源）。 */
    fun allowsOffload(demand: PcmDemand): Boolean = !demand.requiresPcm
}
