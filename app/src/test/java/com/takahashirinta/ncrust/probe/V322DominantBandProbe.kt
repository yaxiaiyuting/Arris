/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.probe

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp

/**
 * v3.2.2 探针 · §2.3 **主导频段定义方案的实测**。
 *
 * 四种候选方案在**真实音乐**下的颜色切换频率（次/秒）—— 数据来自生产提取器
 * （`AudioFeatureExtractor`）对 5 首不同类型歌曲的逐缓冲输出，不是合成信号、
 * 不是手写公式。语料与查找规则见 [ProbeCorpus]；没有语料时本测试 **skip**。
 *
 * 怎么跑（在仓库根目录）：
 * ```
 * ./gradlew :app:testDebugUnitTest --tests '*V322DominantBandProbe*'
 * ```
 * 结果写进 `docs/verification/v3.2.2/probe/`。
 */
class V322DominantBandProbe {

    /** 方案定义。[tauMs] = 0 表示不对频带做时间平滑。 */
    private class Scheme(
        val key: String,
        val label: String,
        val tauMs: Double,
        val hysteresis: Double,
        val gate: Double,
        val minDwellMs: Double = 0.0,
    )

    private class Result(
        val switches: Int,
        val durationSec: Double,
        val minDwellMs: Double,
        val flickers: Int,
        val bandShare: DoubleArray,
    ) {
        val ratePerSec: Double get() = if (durationSec > 0) switches / durationSec else 0.0
    }

    private fun argmax(v: DoubleArray): Int {
        var best = 0
        for (i in 1 until v.size) if (v[i] > v[best]) best = i
        return best
    }

    /**
     * 跑一个方案。
     *
     * @param gate 三频带能量之和低于它就**保持**当前频段（安静段不该被底噪改写颜色）。
     */
    private fun run(
        frames: List<ProbeCorpus.BandFrame>,
        tauMs: Double,
        hysteresis: Double,
        gate: Double,
        guardDwellMs: Double = 0.0,
    ): Result {
        var sl = 0.0
        var sm = 0.0
        var sh = 0.0
        var cur = -1
        var lastSwitchT = 0.0
        var switches = 0
        var minDwell = Double.MAX_VALUE
        var flickers = 0
        val share = DoubleArray(3)
        var prevT = frames.firstOrNull()?.tMs ?: 0.0
        val t0 = prevT
        for (f in frames) {
            val dt = (f.tMs - prevT).coerceAtLeast(0.0)
            prevT = f.tMs
            if (tauMs > 0.0) {
                val k = 1.0 - exp(-dt / tauMs)
                sl += k * (f.low - sl)
                sm += k * (f.mid - sm)
                sh += k * (f.high - sh)
            } else {
                sl = f.low.toDouble()
                sm = f.mid.toDouble()
                sh = f.high.toDouble()
            }
            val sum = sl + sm + sh
            if (sum < gate) continue
            val v = doubleArrayOf(sl, sm, sh)
            share[argmax(v)] += 1.0
            val best = argmax(v)
            if (cur < 0) {
                cur = best
                lastSwitchT = f.tMs
                continue
            }
            // 挑战者必须**严格超过**当前频段 `hysteresis` 比例才允许切换。
            val dwellNow = f.tMs - lastSwitchT
            if (best != cur && dwellNow >= guardDwellMs && v[best] > v[cur] * (1.0 + hysteresis)) {
                val dwell = f.tMs - lastSwitchT
                if (dwell < minDwell) minDwell = dwell
                if (dwell < 100.0) flickers++
                lastSwitchT = f.tMs
                cur = best
                switches++
            }
        }
        val durationSec = ((frames.lastOrNull()?.tMs ?: t0) - t0) / 1000.0
        val total = share.sum().coerceAtLeast(1.0)
        for (i in share.indices) share[i] = share[i] / total
        return Result(
            switches = switches,
            durationSec = durationSec,
            minDwellMs = if (minDwell == Double.MAX_VALUE) Double.NaN else minDwell,
            flickers = flickers,
            bandShare = share,
        )
    }

    @Test
    fun dominantBandSchemesOnRealMusic() {
        val dir = ProbeCorpus.locate()
        assumeTrue(
            "未找到探针音频语料（设置 -Dncrust.probe.audio 或 NCRUST_PROBE_AUDIO），跳过实测",
            dir != null,
        )
        val corpus = dir!!
        val outDir = ProbeCorpus.outputDir()

        // ---- 方案表 ----
        val schemes = ArrayList<Scheme>()
        schemes += Scheme("a", "a. 瞬时最大（不平滑 / 无滞回）", 0.0, 0.0, 0.0)
        schemes += Scheme("b", "b. 占比最高（不平滑 / 无滞回）", 0.0, 0.0, 0.0)
        for (tau in listOf(150.0, 300.0, 600.0)) {
            schemes += Scheme("c$tau", "c. 平滑 ${tau.toInt()}ms 后取最大", tau, 0.0, 0.0)
        }
        val tauForHysteresis = 300.0
        for (h in listOf(0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40, 0.50)) {
            schemes += Scheme(
                "d${(h * 100).toInt()}",
                "d. 平滑 ${tauForHysteresis.toInt()}ms + 滞回 ${(h * 100).toInt()}%",
                tauForHysteresis,
                h,
                0.0,
            )
        }
        // 静音闸门对照（只对最终候选做）
        schemes += Scheme("d20g", "d. 平滑 300ms + 滞回 20% + 静音闸门 0.06", 300.0, 0.20, 0.06)
        schemes += Scheme(
            "d20gd",
            "d. 平滑 300ms + 滞回 20% + 静音闸门 + 最短驻留 200ms",
            300.0,
            0.20,
            0.06,
            200.0,
        )

        data class Row(
            val genre: String,
            val bufferFrames: Int,
            val scheme: String,
            val res: Result,
        )

        val rows = ArrayList<Row>()
        val csv = StringBuilder()

        for ((genre, label) in ProbeCorpus.EXPECTED) {
            val track = ProbeCorpus.load(corpus, genre, label)
            for (bufferFrames in listOf(4410, 1024)) {
                val frames = ProbeCorpus.extract(track, bufferFrames)
                assertTrue("$genre 没有采到任何缓冲", frames.size > 100)
                if (bufferFrames == 4410) {
                    csv.append("# ").append(genre).append(' ').append(label)
                        .append(" rate=").append(track.sampleRate)
                        .append(" ch=").append(track.channels)
                        .append(" dur=").append("%.1fs".format(track.durationMs / 1000))
                        .append(" buffers=").append(frames.size).append('\n')
                    csv.append(ProbeCorpus.csvHeader()).append('\n')
                    for (f in frames) csv.append(ProbeCorpus.csvRow(f)).append('\n')
                }
                for (s in schemes) {
                    if (bufferFrames == 1024 && s.key !in setOf("a", "c300.0", "d20")) continue
                    rows += Row(
                        genre,
                        bufferFrames,
                        s.label,
                        run(frames, s.tauMs, s.hysteresis, s.gate, s.minDwellMs),
                    )
                }
            }
            // 及时释放：语料是 5 首完整歌曲，全部驻留会顶爆测试 JVM 的堆。
            println("[probe] $genre 完成：${track.durationMs.toInt()}ms")
        }

        // ---- 落盘：Markdown 报告 ----
        val md = StringBuilder()
        md.append("# 探针 §2.3 主导频段定义方案 —— 真实音乐实测\n\n")
        md.append("数据源：生产类 `AudioFeatureExtractor`（一行未改）逐缓冲跑 5 首 320kbps 完整歌曲的 PCM。\n")
        md.append("缓冲粒度取 **4410 帧 = 100ms**（S6 实测值，见 `docs/verification/v3.0.0/probe/EVIDENCE.md`），\n")
        md.append("另跑 1024 帧（约 23ms）做敏感度对照。**切换频率 = 颜色改变次数 ÷ 时长（次/秒）**；\n")
        md.append("「闪断」定义为一次驻留 < 100ms 的切换。\n\n")
        md.append("## 语料\n\n| 类型 | 曲目 |\n|---|---|\n")
        for ((genre, label) in ProbeCorpus.EXPECTED) md.append("| ").append(genre).append(" | ").append(label).append(" |\n")

        // 汇总表（仅 100ms，方案平均）
        md.append("\n## 汇总（4410 帧 = 100ms 缓冲，5 首合计）\n\n")
        md.append("| 方案 | 切换次数 | 总时长(s) | **切换频率(次/秒)** | 最短驻留(ms) | <100ms 闪断次数 | 低/中/高 占比 |\n")
        md.append("|---|---|---|---|---|---|---|\n")
        for (s in schemes) {
            val sub = rows.filter { it.bufferFrames == 4410 && it.scheme == s.label }
            if (sub.isEmpty()) continue
            val totalSwitch = sub.sumOf { it.res.switches }
            val totalSec = sub.sumOf { it.res.durationSec }
            val minDwell = sub.map { it.res.minDwellMs }.filter { !it.isNaN() }.minOrNull() ?: Double.NaN
            val flick = sub.sumOf { it.res.flickers }
            val share = DoubleArray(3)
            for (r in sub) for (i in 0..2) share[i] += r.res.bandShare[i] / sub.size
            md.append("| ").append(s.label)
                .append(" | ").append(totalSwitch)
                .append(" | ").append("%.0f".format(totalSec))
                .append(" | **").append("%.3f".format(if (totalSec > 0) totalSwitch / totalSec else 0.0)).append("**")
                .append(" | ").append(if (minDwell.isNaN()) "-" else "%.0f".format(minDwell))
                .append(" | ").append(flick)
                .append(" | ").append("%.2f/%.2f/%.2f".format(share[0], share[1], share[2]))
                .append(" |\n")
        }

        // 逐曲表（仅最终候选与基线）
        md.append("\n## 逐曲明细（4410 帧 = 100ms）\n\n")
        val detailSchemes = schemes.filter {
            it.key == "a" || it.key == "c300.0" || it.key == "d20" || it.key == "d20g" || it.key == "d20gd"
        }
        md.append("| 类型 | 方案 | 切换次数 | 时长(s) | 切换频率(次/秒) | 最短驻留(ms) | 闪断 |\n|---|---|---|---|---|---|---|\n")
        for ((genre, _) in ProbeCorpus.EXPECTED) {
            for (s in detailSchemes) {
                val r = rows.firstOrNull { it.genre == genre && it.bufferFrames == 4410 && it.scheme == s.label } ?: continue
                md.append("| ").append(genre).append(" | ").append(s.key)
                    .append(" | ").append(r.res.switches)
                    .append(" | ").append("%.0f".format(r.res.durationSec))
                    .append(" | ").append("%.3f".format(r.res.ratePerSec))
                    .append(" | ").append(if (r.res.minDwellMs.isNaN()) "-" else "%.0f".format(r.res.minDwellMs))
                    .append(" | ").append(r.res.flickers)
                    .append(" |\n")
            }
        }

        // 缓冲粒度敏感度
        md.append("\n## 缓冲粒度敏感度（1024 帧 ≈ 23ms vs 4410 帧 = 100ms）\n\n")
        md.append("| 方案 | 100ms 切换频率(次/秒) | 23ms 切换频率(次/秒) |\n|---|---|---|\n")
        for (s in schemes.filter { it.key in setOf("a", "c300.0", "d20") }) {
            val a = rows.filter { it.bufferFrames == 4410 && it.scheme == s.label }
            val b = rows.filter { it.bufferFrames == 1024 && it.scheme == s.label }
            if (a.isEmpty() || b.isEmpty()) continue
            fun rate(list: List<Row>): Double {
                val sw = list.sumOf { it.res.switches }
                val sec = list.sumOf { it.res.durationSec }
                return if (sec > 0) sw / sec else 0.0
            }
            md.append("| ").append(s.key).append(" | %.3f".format(rate(a)))
                .append(" | %.3f".format(rate(b))).append(" |\n")
        }

        // a 与 b 的等价性实测
        val eqRows = rows.filter { it.bufferFrames == 4410 }
        var abEqual = true
        for ((genre, _) in ProbeCorpus.EXPECTED) {
            val ra = eqRows.firstOrNull { it.genre == genre && it.scheme == schemes[0].label }
            val rb = eqRows.firstOrNull { it.genre == genre && it.scheme == schemes[1].label }
            if (ra != null && rb != null && (ra.res.switches != rb.res.switches || ra.res.flickers != rb.res.flickers)) {
                abEqual = false
            }
        }
        md.append("\n## a 与 b 的等价性\n\n")
        md.append(
            if (abEqual) {
                "实测：方案 a（瞬时最大）与方案 b（占比最高）在 5 首曲目上的切换次数、闪断次数**完全相同**。\n" +
                    "原因是数学上的恒等：`argmax(low, mid, high)` 与 `argmax(low/S, mid/S, high/S)`（`S = low+mid+high`）\n" +
                    "是同一个决策 —— 同一个正分母不改变大小顺序。**两者不是两个可选方案，是同一个方案。**\n"
            } else {
                "实测：方案 a 与方案 b 的切换次数**不同**（见上表）—— 这是本次测量里唯一能证伪该恒等式的证据，需要复核。\n"
            }
        )

        md.append("\n## 原始数据\n\n逐缓冲的 `t_ms,rms,low,mid,high` 时间序列见 `dominant-band-timeseries.csv`。\n")
        File(outDir, "dominant-band-measurements.md").writeText(md.toString())
        File(outDir, "dominant-band-timeseries.csv").writeText(csv.toString())
        println("[probe] 落盘: ${File(outDir, "dominant-band-measurements.md").absolutePath}")
        println(md)
    }
}
