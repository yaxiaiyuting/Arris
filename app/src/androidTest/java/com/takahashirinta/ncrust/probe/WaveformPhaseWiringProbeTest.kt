/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 探针：**生产单例链路**上的相位（不是另建一个 `WaveformRing`）。
 *
 * 为什么必须有这一条：`WaveformRing` 的单测再多，也只能证明「喂给它 `nowMs` 时算得对」。
 * 它在设备上真正跑的是另一条链：
 *
 * ```
 * 音频线程  WaveformStore.onBar(...)        → ring.push(arrivalAtMs = SystemClock.uptimeMillis())
 * 帧线程    MotionClock.frame(nowMs = …)     → WaveformStore.pump(…) → ring.pump(…, nowMs)
 * ```
 *
 * 这条链上任何一处漏传 / 传错单位（比如把累加的 `dt` 当成帧时间戳塞进去），
 * 单元测试都会全绿而设备上照旧抽搐。本探针只做一件事：**用一个明显不对的 `dt`
 * 把两条口径分开** —— 锚点生效时相位只由真实时间戳决定，累加口径会被 `dt` 顶到 1.0。
 */

package com.takahashirinta.ncrust.probe

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.takahashirinta.ncrust.ui.player.WaveformStore
import com.takahashirinta.ncrust.ui.player.motion.MotionClock
import com.takahashirinta.ncrust.ui.player.motion.MotionEffects
import com.takahashirinta.ncrust.ui.player.motion.MotionIntensity
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveformPhaseWiringProbeTest {

    private fun emit(line: String) {
        android.util.Log.i("WIRINGPROBE", line)
        println(line)
    }

    @Test
    fun 生产单例链路上的相位由真实时间戳决定_不是按dt累加() {
        // 生产默认开关（`VisualizerSetting.DEFAULT_ENABLED = true`），直接置位以免依赖 SharedPreferences。
        WaveformStore.enabled = true
        val motion = MotionEffects.of(tier = MotionIntensity.REFINED, uiMotionEnabled = false)
        val waveform = VisualizerEffects.BASELINE

        // 音频线程那一侧：`onBar` 内部自己打 `SystemClock.uptimeMillis()` 的时间戳 ——
        // 与生产逐字同一条路径，测试只负责在它前后读一次同一个时钟。
        val arrivals = ArrayList<Long>()
        repeat(3) { i ->
            WaveformStore.onBar(0.6, 0.5, 0.4, 0.3)
            arrivals.add(SystemClock.uptimeMillis())
            // ⚠️ 最后一次**不睡**：帧必须在「距上一根柱只过了几毫秒」的那一刻落下，
            // 否则 elapsed 本身就已经等于一个柱间隔，相位天然是 1.0 —— 那样两套口径量不出区别
            // （本探针第一版就是栽在这里：elapsed=80ms 而柱间隔≈80ms）。
            if (i < 2) Thread.sleep(80)
        }

        // 帧那一侧：喂一个**远大于真实柱间隔**的 dt（200ms）。
        //  · 锚点生效 ⇒ 相位 =（这一帧的时间戳 − 最后一根柱的到达时刻）÷ 柱间隔 ≈ 30/80；
        //  · 累加口径 ⇒ 相位被这 200ms 直接顶到 1.0。
        val now = SystemClock.uptimeMillis()
        MotionClock.frame(
            active = true,
            dtMs = 200f,
            waveform = waveform,
            motion = motion,
            nowMs = now,
        )
        val phase = WaveformStore.scrollPhase01()
        val elapsed = now - arrivals.last()
        emit("WIRING 第一帧: elapsed=%dms dt=200ms phase=%.4f".format(elapsed, phase))
        assertTrue(
            "相位 %.4f —— 它该由真实时间戳（本次 elapsed=%dms）决定，而不是被 dt=200ms 累加到 1.0"
                .format(phase, elapsed),
            phase < 0.6f,
        )

        // 同一口径下相位必须随真实时间前进（而不是随 dt —— 这一帧的 dt 同样是 200）。
        Thread.sleep(30)
        val now2 = SystemClock.uptimeMillis()
        MotionClock.frame(
            active = true,
            dtMs = 200f,
            waveform = waveform,
            motion = motion,
            nowMs = now2,
        )
        val phase2 = WaveformStore.scrollPhase01()
        emit("WIRING 第二帧: elapsed=%dms dt=200ms phase=%.4f".format(now2 - arrivals.last(), phase2))
        assertTrue("相位必须随真实时间前进：%.4f -> %.4f".format(phase, phase2), phase2 > phase)
    }
}
