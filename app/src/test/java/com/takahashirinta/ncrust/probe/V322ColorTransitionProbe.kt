/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.probe

import com.takahashirinta.ncrust.ui.player.waveform.BandColorRoles
import com.takahashirinta.ncrust.ui.player.waveform.BandDominance
import com.takahashirinta.ncrust.ui.theme.color.Cam16
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * v3.2.2 探针 · §2.4 **颜色切换方式的实测**（硬切 / 线性渐变 / 分段 + 短过渡）。
 *
 * 与 §2.3 的关键区别：这里测的是**生产类 `BandDominance` 本身**，而且按**生产时序**跑
 * —— 特征值 11 Hz 到达（真机实测缓冲粒度），判定按 **60fps 帧**推进。§2.3 的扫描是按
 * 缓冲边界判定的离线分析，两者的差别必须实测而不是假设。
 *
 * ## 判据
 *
 * 「闪不闪」在数值上就是**相邻两帧的颜色差**：
 *  - 硬切：一次切换的全部色差落在**一帧**里 ⇒ 人眼读成"跳变/频闪"；
 *  - 有过渡：同样的色差摊到 N 帧 ⇒ 每帧色差小一个数量级。
 *
 * 所以量三件事：**每帧最大色差（CAM16-UCS ΔE）**、**处于混合态的时间占比**、
 * **切换次数**（过渡不该改变切换次数 —— 它只改变"怎么过去"）。
 *
 * 输出：`probe/color-transition-measurements.md` + `probe/color-transition-trajectories.csv`
 * （后者供离线渲染对照组图，见 `probe/color-transition-compare.png`）。
 */
class V322ColorTransitionProbe {

    private companion object {
        const val FRAME_MS = 16
        const val BUFFER_FRAMES = 4410
        /** 对照的三档过渡时长（毫秒）：0 = 硬切。 */
        val TRANSITIONS = listOf(0f, 40f, 80f, 100f, 120f, 400f)
        const val ACCENT_DARK = 0xFF1DB954.toInt()
    }

    private class Stats {
        var frames = 0
        var maxFrameDeltaE = 0.0
        var mixedFrames = 0
        var switches = 0

        /** 上一次切换的过渡**还没走完**就又切了一次的次数（会制造权重跳变 ⇒ 观感上的 pop）。 */
        var interrupted = 0
    }

    @Test
    fun colorTransitionModes() {
        val dir = ProbeCorpus.locate()
        assumeTrue("未找到探针音频语料，跳过实测", dir != null)
        val corpus = dir!!
        val outDir = ProbeCorpus.outputDir()

        val palette = IntArray(3)
        check(BandColorRoles.paletteFor(ACCENT_DARK, true, palette)) { "paletteFor 失败" }
        val weights = FloatArray(3)

        data class Row(val genre: String, val transitionMs: Float, val stats: Stats)

        val rows = ArrayList<Row>()
        val csv = StringBuilder()
        csv.append("# 颜色轨迹（每帧）：genre,transition_ms,t_ms,low_w,mid_w,high_w,argb\n")

        for ((genre, label) in ProbeCorpus.EXPECTED) {
            val track = ProbeCorpus.load(corpus, genre, label)
            val frames = ProbeCorpus.extract(track, BUFFER_FRAMES)
            for (transitionMs in TRANSITIONS) {
                val band = BandDominance(transitionMs = transitionMs)
                val stats = Stats()
                var prevArgb = 0
                var prevDominant = BandDominance.NONE
                // 暖机：起播头几帧颜色从"还没结论"（单一主题色）落到第一个结论，
                // 那一次跳变不属于"切换"语义，把它算进来会把三种方式的差异糊掉。
                var warmedUp = false
                var current = frames[0]
                var pushed = 1
                val totalFrames = ((frames.lastOrNull()?.tMs ?: 0.0) / FRAME_MS).toInt()
                for (frame in 0 until totalFrames) {
                    val nowMs = frame * FRAME_MS
                    // 生产时序：缓冲 11 Hz 到达（volatile 标量保持上一次的值），判定按帧推进。
                    while (pushed < frames.size && frames[pushed].tMs <= nowMs) {
                        current = frames[pushed]
                        pushed++
                    }
                    // 必须在 update **之前**读：切换会把 transition 归零，
                    // update 之后读到的永远是 0，那样"打断计数"会恒等于切换次数。
                    val transitionWasComplete = band.transitionProgress() >= 1f
                    band.update(current.low, current.mid, current.high, available = true, dtMs = FRAME_MS.toFloat())
                    if (band.dominant != prevDominant) {
                        if (prevDominant != BandDominance.NONE) {
                            stats.switches++
                            if (!transitionWasComplete) stats.interrupted++
                        }
                        prevDominant = band.dominant
                    }
                    if (!warmedUp) {
                        if (band.dominant != BandDominance.NONE && band.transitionProgress() >= 1f) {
                            warmedUp = true
                            prevArgb = 0
                        }
                        continue
                    }
                    val ok = band.weights(weights)
                    val argb = if (ok) BandColorRoles.mix(palette, weights) else ACCENT_DARK
                    stats.frames++
                    if (prevArgb != 0) {
                        val de = Cam16.fromInt(prevArgb).distance(Cam16.fromInt(argb))
                        if (de > stats.maxFrameDeltaE) stats.maxFrameDeltaE = de
                    }
                    val mixed = (weights[0] > 0.001f && weights[1] > 0.001f) ||
                        (weights[1] > 0.001f && weights[2] > 0.001f) ||
                        (weights[0] > 0.001f && weights[2] > 0.001f)
                    if (mixed) stats.mixedFrames++
                    prevArgb = argb
                    // 只落电子乐（切换最密、图表最有代表性）；全部过渡档都落，供离线对照图使用。
                    if (genre == "electronic") {
                        csv.append(genre).append(',').append(transitionMs).append(',')
                            .append(nowMs).append(',')
                            .append("%.4f".format(weights[0])).append(',')
                            .append("%.4f".format(weights[1])).append(',')
                            .append("%.4f".format(weights[2])).append(',')
                            .append("#%06X".format(argb and 0xFFFFFF)).append('\n')
                    }
                }
                rows += Row(genre, transitionMs, stats)
            }
            println("[probe] color-transition $genre 完成")
        }

        fun label(ms: Float): String = when (ms) {
            0f -> "a. 硬切（过渡 0ms）"
            in 50f..100f -> "c. 分段 + 短过渡 ${ms.toInt()}ms"
            else -> "过渡 ${ms.toInt()}ms"
        }

        val md = StringBuilder()
        md.append("# 探针 §2.4 颜色切换方式实测（生产 `BandDominance`，60fps 判定 / 11Hz 输入）\n\n")
        md.append("主题色取预设·云杉 `#1DB954`（深色主题），三角色 = `BandColorRoles.paletteFor`。\n\n")
        md.append("「每帧最大色差」= 相邻两帧混合色的 CAM16-UCS ΔE 的最大值 —— 它就是「闪不闪」的数值形式：\n")
        md.append("硬切会把一次切换的全部色差压进**一帧**，有过渡则摊到 N 帧。\n\n")
        md.append("| 方式 | 5 首合计切换次数 | 每帧最大色差 ΔE | 混合态帧占比 | 过渡被打断次数 |\n|---|---|---|---|---|\n")
        for (t in TRANSITIONS) {
            val sub = rows.filter { it.transitionMs == t }
            val totalSwitch = sub.sumOf { it.stats.switches }
            val maxDe = sub.maxOf { it.stats.maxFrameDeltaE }
            val mixed = sub.sumOf { it.stats.mixedFrames }.toDouble() / sub.sumOf { it.stats.frames }
            md.append("| ").append(label(t))
                .append(" | ").append(totalSwitch)
                .append(" | ").append("%.2f".format(maxDe))
                .append(" | ").append("%.1f%%".format(mixed * 100))
                .append(" | ").append(sub.sumOf { it.stats.interrupted })
                .append(" |\n")
        }
        md.append("\n## 逐曲：每帧最大色差\n\n| 类型 | 硬切 | 线性渐变 400ms | 短过渡 80ms |\n|---|---|---|---|\n")
        for ((genre, _) in ProbeCorpus.EXPECTED) {
            md.append("| ").append(genre)
            for (t in TRANSITIONS) {
                val r = rows.first { it.genre == genre && it.transitionMs == t }
                md.append(" | ").append("%.2f".format(r.stats.maxFrameDeltaE))
            }
            md.append(" |\n")
        }

        File(outDir, "color-transition-measurements.md").writeText(md.toString())
        File(outDir, "color-transition-trajectories.csv").writeText(csv.toString())
        println(md)

        // 硬切必须明显比短过渡"跳" —— 否则这三级防闪里最贵的那一级就是白加的。
        val hardCut = rows.filter { it.transitionMs == 0f }.maxOf { it.stats.maxFrameDeltaE }
        val short = rows.filter { it.transitionMs == 80f }.maxOf { it.stats.maxFrameDeltaE }
        assertTrue("硬切的每帧色差必须明显大于 80ms 过渡: $hardCut vs $short", hardCut > short * 2.0)
    }
}
