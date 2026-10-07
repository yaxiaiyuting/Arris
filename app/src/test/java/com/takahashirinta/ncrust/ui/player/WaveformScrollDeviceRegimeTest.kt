/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v3.4.4：把 **146.2fps 屏幕录像量到的真机工况**钉进回归测试。
 *
 * ## 工况不是猜的 —— 全部来自 `.scratch/wf-measure/` 的逐帧量测
 *
 * | 量 | 实测值 | 怎么量的 |
 * |---|---|---|
 * | 捕获帧间隔 | **6.84ms（146.2fps）** | `ffprobe`：1422 帧 / 9.725s |
 * | 柱间隔 | **82.6ms（12.1 柱/秒）** | 波形色带「大变」事件的周期 = 12.08 帧 |
 * | 格宽 | **10.818px** | 顶边包络在点距上的傅里叶基频 |
 * | 稳态位移 | **-0.88px/帧 = -128px/s = 11.9 格/秒** | 2-D ZNCC（±5px，无混叠）+ 相位法，两个独立估计一致 |
 * | 应用是否掉帧 | **没有**：1421 个帧间里只有 17 个（1.2%）像素几乎不变 | 说明**不是**渲染跟不上，是**位移本身**在到达帧上不匀 |
 *
 * ## 这条测试守什么
 *
 * 柱到达帧的位移在数学上等于 `名义步长 + (L̂ − L)/T`（`L` = 该柱到达时刻与消费帧之间
 * 的滞后，0..dt）。它**不允许为负**（那才是用户说的「往回拖动」），也不允许超过
 * 名义步长的两倍。这条界是相位模型的硬约束，不是我在测试里定的口味。
 */
class WaveformScrollDeviceRegimeTest {

    private val frameMs = 1000.0 / 146.2
    private val barMs = 82.6

    /**
     * @param offGridArrival true = 到达时间戳取音频缓冲的**真实时刻**（生产如此，不在帧网格上）
     */
    private fun run(
        seconds: Double,
        offGridArrival: Boolean = true,
    ): WaveformScrollJitterHarness {
        val h = WaveformScrollJitterHarness(capacity = 512, barCount = 28)
        // ⚠️ v3.4.7：帧与柱**必须共用同一个时间基准**（生产里两边都是 uptime 单调时钟）。
        // 旧版把柱的时间戳整体加了 1e6 —— 在「自己攒帧时钟 + 一次性标定偏移」的旧模型下
        // 这无所谓（偏移会被标定吃掉），但相位改成**两个真实时间戳相减**之后，
        // 基准不一致就是真的不一致：相位会恒为 0。基准取一个正数（0 是「没给时钟」的哨兵）。
        val base = 1_000_000.0
        var nextBar = 0.0
        var t = 0.0
        var i = 0
        while (t < seconds * 1000.0) {
            if (t >= nextBar) {
                // 真实音乐幅度：每 8 根一根安静缓冲（与 `WaveformScrollJitterRegressionTest` 同口径）
                val v = if ((i / 8) % 5 == 0) 0.01f else 0.55f
                h.pushBar(base + if (offGridArrival) nextBar else t, v)
                nextBar += barMs
            }
            h.advanceTo(base + t)
            t += frameMs
            i++
        }
        return h
    }

    @Test
    fun `录像工况下 —— 位移绝不许倒退，且到达帧不许超出一格`() {
        val h = run(seconds = 6.0)
        val s = h.summary()
        println(
            "146.2fps / 82.6ms 柱：柱间隔=${s.meanBarIntervalMs}ms " +
                "名义步长=${s.nominalStep} 实测均步=${s.meanStep} " +
                "抖动率=${s.jitterRatio * 100}% 停顿帧=${s.stallCount} 倒退帧=${s.backsteps} " +
                "最小步=${s.minStepRatio}x 最大步=${s.maxStepRatio}x",
        )
        assertTrue(
            "147 Hz 下 6 秒里一帧倒退都没有 —— 实测 ${s.backsteps} 帧",
            s.backsteps == 0,
        )
        // 到达帧的位移上界：名义步长 + 一个柱周期内的相位误差，实测最大 1.2 倍左右；
        // 超过 2 倍就说明相位被清零/回绕（v3.2.4 的形状）。
        assertTrue(
            "单帧最大位移 ${s.maxStepRatio} 倍名义 —— 超过 2 倍就是「冲」",
            s.maxStepRatio < 2.0f,
        )
        // 平均步长必须等于名义步长（== 一格/柱周期这个物理事实）
        assertTrue(
            "平均步长 ${s.meanStep} 必须等于名义 ${s.nominalStep}",
            abs(s.meanStep - s.nominalStep) < s.nominalStep * 0.05,
        )
    }

    @Test
    fun `录像工况下 —— 抖动率必须与 v3_3_2 的记录同一量级`() {
        val h = run(seconds = 6.0)
        val s = h.summary()
        // 已知：帧时间抖动本身贡献 0（位移本来就正比 dt），这一条只防止
        // 有人把「量化误差」重新引进相位 —— 真机录像量的位移 sd 约 0.4px / 名义 0.88px。
        assertTrue(
            "抖动率 %.2f%% —— 超过 20%% 就是又回到「顿-冲」".format(s.jitterRatio * 100),
            s.jitterRatio < 0.20,
        )
    }
}
