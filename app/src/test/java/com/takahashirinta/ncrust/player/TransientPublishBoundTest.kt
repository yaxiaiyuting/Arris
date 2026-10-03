/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.ui.player.MAX_TRANSIENTS_PER_BUFFER
import com.takahashirinta.ncrust.ui.player.WaveformStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.0：**瞬态发布上界的一致性**。
 *
 * 这个常数在两个地方各声明了一次（有意为之，理由写在下一条）：
 *  - `AudioFeatureExtractor.MAX_TRANSIENTS_PER_BUFFER`（音频线程侧，`@UnstableApi`）；
 *  - `ui/player/AudioVisualizer.kt` 的 `MAX_TRANSIENTS_PER_BUFFER`（UI 层，普通常量）。
 *
 * 为什么不合一：前者所在的类是 media3 的 `@UnstableApi`，从 UI 层引用它会把 opt-in 需求
 * 传染过去（lint 的 `UnsafeOptInUsageError` 真的挡下过一次构建），而 UI 层根本不关心 media3。
 * 所以两边各写一次、由这条用例钉住相等 —— 与 `MotionPrefsTest` 钉住历史键名的做法同源。
 */
class TransientPublishBoundTest {

    @Test
    fun `上界与提取器的子帧上界一致`() {
        assertEquals(
            AudioFeatureExtractor.MAX_TRANSIENTS_PER_BUFFER,
            MAX_TRANSIENTS_PER_BUFFER,
        )
    }

    @Test
    fun `上界容得下一个真机缓冲里的全部子帧`() {
        // 真机实测缓冲 100ms、子帧 10ms ⇒ 一个缓冲最多 10 个子帧。
        assertTrue(
            "上界 $MAX_TRANSIENTS_PER_BUFFER 容不下 100ms/10ms 的 10 个子帧",
            MAX_TRANSIENTS_PER_BUFFER >= 10,
        )
    }

    @Test
    fun `发布端把越界的个数夹回上界`() {
        WaveformStore.resetForTest()
        val before = WaveformStore.transientCount()
        WaveformStore.onTransient(count = Int.MAX_VALUE, strength = 1f)
        val delta = WaveformStore.transientCount() - before
        assertEquals("越界个数必须被夹到上界", MAX_TRANSIENTS_PER_BUFFER.toLong(), delta)
        // 0 或负数同样被夹到 1（调用方只在 >0 时调用，这里只是防御）。
        WaveformStore.onTransient(count = 0, strength = 0f)
        assertEquals(1L, WaveformStore.transientCount() - before - MAX_TRANSIENTS_PER_BUFFER)
        WaveformStore.resetForTest()
    }

    @Test
    fun `强度被夹在 0 到 1`() {
        WaveformStore.resetForTest()
        WaveformStore.onTransient(1, 99f)
        assertEquals(1f, WaveformStore.transientStrength(), 0f)
        WaveformStore.onTransient(1, Float.NaN)
        assertEquals(0f, WaveformStore.transientStrength(), 0f)
        WaveformStore.onTransient(1, -5f)
        assertEquals(0f, WaveformStore.transientStrength(), 0f)
        WaveformStore.resetForTest()
    }
}
