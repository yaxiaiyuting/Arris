/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 回归：offload 必须在**运行中**跟随实际 PCM 需求，不能只在服务创建时判断一次。
 *
 * 这个测试把「全关闭 / 只开波形 / 只开动效 / 运行中往返」四种状态钉死；播放器监听本身
 * 在 `PlaybackService.observePcmDemand`，这里只验证判定层。
 */
class AudioOffloadGateTest {

    @Test
    fun `全关闭 - 允许 offload`() {
        val demand = AudioOffloadGate.PcmDemand(
            waveformEnabled = false,
            motionNeedsAudioFeatures = false,
        )
        assertFalse(demand.requiresPcm)
        assertTrue(AudioOffloadGate.allowsOffload(demand))
        assertEquals(
            AudioOffloadGate.Action.ENABLE,
            AudioOffloadGate.action(requiredPcm = demand.requiresPcm, offloadEnabled = false),
        )
    }

    @Test
    fun `只开波形 - 必须关闭 offload`() {
        val demand = AudioOffloadGate.PcmDemand(
            waveformEnabled = true,
            motionNeedsAudioFeatures = false,
        )
        assertTrue(demand.requiresPcm)
        assertFalse(AudioOffloadGate.allowsOffload(demand))
        assertEquals(
            AudioOffloadGate.Action.DISABLE,
            AudioOffloadGate.action(requiredPcm = demand.requiresPcm, offloadEnabled = true),
        )
    }

    @Test
    fun `只开音频动效 - 即使波形关闭也必须关闭 offload`() {
        // 这正是 R2 最容易漏掉的一路：波形总开关关掉 ≠ 不需要 PCM。
        val demand = AudioOffloadGate.PcmDemand(
            waveformEnabled = false,
            motionNeedsAudioFeatures = true,
        )
        assertTrue(demand.requiresPcm)
        assertFalse(AudioOffloadGate.allowsOffload(demand))
        assertEquals(
            AudioOffloadGate.Action.DISABLE,
            AudioOffloadGate.action(requiredPcm = demand.requiresPcm, offloadEnabled = true),
        )
    }

    @Test
    fun `波形与动效同时打开 - 关闭 offload`() {
        val demand = AudioOffloadGate.PcmDemand(
            waveformEnabled = true,
            motionNeedsAudioFeatures = true,
        )
        assertTrue(demand.requiresPcm)
        assertEquals(
            AudioOffloadGate.Action.DISABLE,
            AudioOffloadGate.action(requiredPcm = demand.requiresPcm, offloadEnabled = true),
        )
    }

    @Test
    fun `运行中往返 - 关到开再到关不丢失门控`() {
        // 全关：允许 offload。
        var offloadEnabled = false
        var demand = AudioOffloadGate.PcmDemand(false, false)
        var action = AudioOffloadGate.action(demand.requiresPcm, offloadEnabled)
        assertEquals(AudioOffloadGate.Action.ENABLE, action)
        offloadEnabled = true

        // 运行中只开波形：必须立即关闭，不能等 service 重建。
        demand = demand.copy(waveformEnabled = true)
        action = AudioOffloadGate.action(demand.requiresPcm, offloadEnabled)
        assertEquals(AudioOffloadGate.Action.DISABLE, action)
        offloadEnabled = false

        // 波形关、动效开：仍然不能开 offload。
        demand = demand.copy(waveformEnabled = false, motionNeedsAudioFeatures = true)
        action = AudioOffloadGate.action(demand.requiresPcm, offloadEnabled)
        assertEquals(AudioOffloadGate.Action.KEEP, action)

        // 动效也关：重新允许 offload。
        demand = demand.copy(motionNeedsAudioFeatures = false)
        action = AudioOffloadGate.action(demand.requiresPcm, offloadEnabled)
        assertEquals(AudioOffloadGate.Action.ENABLE, action)
        offloadEnabled = true

        // 再打开波形：又必须关闭。
        demand = demand.copy(waveformEnabled = true)
        action = AudioOffloadGate.action(demand.requiresPcm, offloadEnabled)
        assertEquals(AudioOffloadGate.Action.DISABLE, action)
    }

    @Test
    fun `已处于目标状态时幂等 - 不重复改播放器参数`() {
        assertEquals(
            AudioOffloadGate.Action.KEEP,
            AudioOffloadGate.action(requiredPcm = true, offloadEnabled = false),
        )
        assertEquals(
            AudioOffloadGate.Action.KEEP,
            AudioOffloadGate.action(requiredPcm = false, offloadEnabled = true),
        )
    }

    @Test
    fun `media3 mode 判定 - DISABLED 之外都算已开启`() {
        assertFalse(
            AudioOffloadGate.isOffloadEnabled(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            ),
        )
        assertTrue(
            AudioOffloadGate.isOffloadEnabled(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
            ),
        )
        assertTrue(
            AudioOffloadGate.isOffloadEnabled(
                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED,
            ),
        )
    }
}
