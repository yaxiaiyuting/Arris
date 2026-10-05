/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v3.4.7：相位的分子与分母**必须同源** —— 「波形滚动抽搐」的最终根因判据。
 *
 * ## 真机探针量到的指纹（改前的 v3.4.6）
 *
 * | 量 | 实测 |
 * |---|---|
 * | `sinceBarMs` | 只在 `{0.00, 41.18}` 两个值之间跳 |
 * | 相位 | 每 5 帧走 `0.206 × 5`，第 6 帧被锚点**覆盖回 0** |
 * | 逐帧位移 | 到达帧一次冲出 `1.176` 格（名义 `0.206`），其余帧按名义走 |
 *
 * 根因是 v3.3.2 的锚点式子 `frameClockMs − clockOffsetMs − pendingArrivalMs`：
 * `frameClockMs` 是**本类自己按 `dt` 累加**的帧时钟，且逐帧 `+= dt.toLong()` 截断 ——
 * 60Hz 每帧丢 0.667ms、120Hz 每帧丢 0.333ms（都是 **4%/秒**）。拿它去减音频线程的
 * `SystemClock.uptimeMillis()`，差值系统性跑负 ⇒ 相位被钉死、每格丢一帧位移。
 *
 * ## 这个文件钉住三条（都对「旧模型」有区分度）
 *
 * 1. **到达帧的位移 ≈ 名义步长**：这一帧相位 = 「这一帧比那根柱晚了多少」÷ 柱间隔，
 *    本来就是个小正数；要求它严格为 0 就等于把这一帧的位移丢掉。
 *    [到达帧的位移必须是名义步长 —— 不许把这一帧的位移丢掉]（旧模型量到 **0%**）
 * 2. **相位是一条闭式**：`(帧时间戳 − 最后一根被消费的柱的到达时刻) ÷ 分母`。
 *    [相位恰好等于 帧时间戳减最后一根柱的到达时刻 除以柱间隔]
 * 3. **相位不来自任何累加量**：把 `dt` 整体放大 3 倍，相位序列必须**逐帧不变**。
 *    [相位不来自任何累加时钟 —— dt 放大三倍相位逐帧不变]（任何自攒时钟都会在这里翻红）
 *
 * 另外把 v3.3.2 的跨时钟锚点在同一份时间线上复刻一遍（[WaveformScrollJitterHarness.legacyAnchor]），
 * 作为**改前对照** —— 不是为了守住旧实现，而是为了让「这条判据有区分度」这句话有数可查。
 */
class WaveformScrollPhaseAnchorTest {

    /**
     * 帧均匀（[hz]）、柱均匀（[barMs]）且**不在帧网格上**（生产如此：音频缓冲按自己的节拍到达）。
     *
     * @param legacyAnchor true = 用 v3.3.2 的跨时钟锚点复刻（改前对照）
     * @param dtScale 喂给 `pump` 的 `dt` 缩放（只影响弹道，**不该**影响相位）
     */
    private fun run(
        hz: Double,
        barMs: Double,
        seconds: Double,
        legacyAnchor: Boolean = false,
        dtScale: Float = 1f,
    ): WaveformScrollJitterHarness {
        val h = WaveformScrollJitterHarness(capacity = 512, barCount = 28)
        h.legacyAnchor = legacyAnchor
        // 帧与柱**共用同一个时间基准**（生产里两边都是 uptime 单调时钟）。
        val base = 1_000_000.0
        val frameMs = 1000.0 / hz
        var nextBar = 0.0
        var t = 0.0
        var i = 0
        while (t < seconds * 1000.0) {
            if (t >= nextBar) {
                // 真实音乐幅度：每 8 根一根安静缓冲（与既有回归测试同口径）。
                val v = if ((i / 8) % 5 == 0) 0.01f else 0.55f
                h.pushBar(base + nextBar, v)
                nextBar += barMs
            }
            h.advanceTo(base + t, dtScale = dtScale)
            t += frameMs
            i++
        }
        return h
    }

    /**
     * **症状本身**：柱到达的那一帧，可见位移必须 ≈ 名义步长。
     *
     * 旧模型（跨时钟锚点）在这一帧量到 **0%**：位移被丢掉，下一帧再补回来 ——
     * 用户读到的就是「顿一下、再冲一下」。
     */
    @Test
    fun `到达帧的位移必须是名义步长 —— 不许把这一帧的位移丢掉`() {
        listOf(
            "146.2Hz / 82.6ms（屏幕录像量到的真机工况）" to (146.2 to 82.6),
            "120Hz / 41.2ms（用户报告的那台）" to (120.0 to 41.2),
            "60Hz / 100ms（S6 形态）" to (60.0 to 100.0),
        ).forEach { (tag, cfg) ->
            val (hz, barMs) = cfg
            val h = run(hz, barMs, seconds = 6.0)
            val s = h.summary()
            assertTrue("$tag：样本太少（${s.arrivalCount} 个到达帧）", s.arrivalCount > 20)
            assertTrue(
                "$tag：到达帧最小位移只有名义的 %.0f%% —— 这一帧的位移被丢掉了".format(
                    s.arrivalMinStepRatio * 100,
                ),
                s.arrivalMinStepRatio >= 0.5f,
            )
            assertTrue(
                "$tag：到达帧最大位移是名义的 %.0f%% —— 超过 2 倍就是「冲」".format(
                    s.arrivalMaxStepRatio * 100,
                ),
                s.arrivalMaxStepRatio <= 2.0f,
            )
            assertTrue("$tag：不该有任何「顿」帧（实测 ${s.stallCount}）", s.stallCount == 0)
            assertEquals("$tag：位移不许倒退", 0, s.backsteps)
            assertTrue(
                "$tag：抖动率 %.2f%% 应低于 10%%".format(s.jitterRatio * 100),
                s.jitterRatio < 0.10,
            )
        }
    }

    /** 相位必须**恰好**是那条闭式（分子分母同源 ⇒ 可以逐帧等号，不是「差不多」）。 */
    @Test
    fun `相位恰好等于 帧时间戳减最后一根柱的到达时刻 除以柱间隔`() {
        val h = run(146.2, 82.6, seconds = 6.0)
        val arr = h.barArrivalsMs
        var checked = 0
        h.frames.drop(WaveformScrollJitterHarness.SKIP_FRAMES).forEach { f ->
            val consumed = f.shiftedCells
            if (consumed <= 0) return@forEach
            // 两个时间戳都是**毫秒整数**（环收到的就是 Long）：先各自截断再相减，
            // 与 `pump` 里那两行逐字一致 —— 不这样写会引入 0.4ms 的假误差。
            val nowMs = f.tMs.toLong()
            val lastArrival = arr[(consumed - 1).toInt()].toLong()
            val expected = ((nowMs - lastArrival).toFloat() / f.intervalMs).coerceIn(0f, 1f)
            assertEquals(
                "t=%.3f 的相位与闭式不符（最后一根柱到达于 %d，分母 %.3f）".format(
                    f.tMs, lastArrival, f.intervalMs,
                ),
                expected,
                f.phase,
                1e-6f,
            )
            checked++
        }
        assertTrue("检查的帧太少（$checked）", checked > 500)
    }

    /**
     * **相位不来自任何累加量**：把 `dt` 整体放大 3 倍，相位序列必须逐帧不变。
     *
     * 这一条对「自攒帧时钟」的模型是致命的 —— 无论它累加的是 `dt` 还是 `dt.toLong()`，
     * 只要相位里含一个累加量，`dt` 一变相位就变。弹道（柱高 / 小球）会跟着 `dt` 变，
     * 那是另一层，不在本判据里。
     */
    @Test
    fun `相位不来自任何累加时钟 —— dt 放大三倍相位逐帧不变`() {
        val normal = run(146.2, 82.6, seconds = 6.0, dtScale = 1f)
        val scaled = run(146.2, 82.6, seconds = 6.0, dtScale = 3f)
        assertEquals("两趟的帧数必须一致", normal.frames.size, scaled.frames.size)
        val from = WaveformScrollJitterHarness.SKIP_FRAMES
        for (i in from until normal.frames.size) {
            val a = normal.frames[i]
            val b = scaled.frames[i]
            assertEquals("t=%.3f：平移格数不一致".format(a.tMs), a.shiftedCells, b.shiftedCells)
            assertTrue(
                "t=%.3f：dt 放大 3 倍后相位从 %.6f 变成 %.6f —— 相位里含累加量".format(
                    a.tMs, a.phase, b.phase,
                ),
                abs(a.phase - b.phase) <= 1e-6f,
            )
        }
    }

    /**
     * **改前对照（特征化）**：同一份时间线上，v3.3.2 的跨时钟锚点会把到达帧的位移丢掉。
     *
     * 保留它是为了让上面那三条判据的**区分度**可查：如果有一天有人把相位改回「自己攒时钟」，
     * 这一条会继续绿（它测的是测试内的复刻），而上面三条会红 —— 两条一起读就知道退化成什么形状。
     */
    @Test
    fun `旧模型在同一份时间线上会把到达帧的位移丢掉（特征化）`() {
        val before = run(60.0, 100.0, seconds = 6.0, legacyAnchor = true).summary()
        val after = run(60.0, 100.0, seconds = 6.0).summary()
        assertTrue(
            "旧模型的到达帧位移实测 %.0f%%~%.0f%% 名义 —— 本用例假定它会塌到 50%% 以下".format(
                before.arrivalMinStepRatio * 100, before.arrivalMaxStepRatio * 100,
            ),
            before.arrivalMinStepRatio < 0.5f,
        )
        assertTrue(
            "新模型的到达帧位移实测 %.0f%%~%.0f%% 名义 —— 必须回到名义步长附近".format(
                after.arrivalMinStepRatio * 100, after.arrivalMaxStepRatio * 100,
            ),
            after.arrivalMinStepRatio >= 0.5f,
        )
        assertTrue(
            "旧模型的最大步是名义的 %.1f 倍（>1.6 就是「冲」）".format(before.maxStepRatio),
            before.maxStepRatio > 1.6f,
        )
        assertTrue(
            "新模型的最大步是名义的 %.3f 倍".format(after.maxStepRatio),
            after.maxStepRatio <= 1.6f,
        )
    }
}
