/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v3.4.4：**三条泳道的画面值必须带弹道**（这是「小球和横线没有弹起来的物理感觉」的回归测试）。
 *
 * ## 缺陷（v3.2.2 引入三泳道时带进来的回归）
 *
 * `AudioVisualizer` 的三泳道分支把 `lowTargets/midTargets/highTargets`
 * （**原始**窗口，逐格等于音频缓冲值）直接交给 `BandScroll.fillLane` 当几何。
 * 于是 v1.8.1 就有的起音 22ms / 回落 130ms **被整条绕过**：
 * 每一格的高度是**瞬时赋值** —— 涨是硬跳、落也是硬跳，柱间隔 82.6ms ⇒ 12.1Hz 阶梯。
 * 画在色带顶边上的圆点（用户说的「小球」）因此是**一格一跳的阶梯**，不是弹起再落下。
 *
 * ## 这把尺子是什么
 *
 * 判据直接读**渲染层消费的那个数组**：`WaveformRing.copyBandsInto` 写出的画面值窗口，
 * 单测用逐格访问器 [WaveformRing.bandBarAt] 读同一份数据（`bandAt` 是**原始**窗口，
 * 保留它正是为了让「改前」可测：改前画面值 == `bandAt`）。
 *
 * | 量 | 定义 | 为什么它能代表「有没有弹起感」 |
 * |---|---|---|
 * | **单帧最大跳变** | 画面值窗口里任一格在**一帧**内的变化量 | 阶梯的特征就是「11 帧不动 + 1 帧跳满」；弹道会把它摊到多帧 |
 * | **爬满一格所需帧数** | 从静音起音到 90% 目标值经过的帧数 | 1 帧 = 硬跳；≥3 帧 = 看得出起音 |
 * | **回落时间** | 从满幅回落到 37%（1/e）所需的毫秒 | 22ms 与 130ms 的差别就是「弹起来」与「滑下去」 |
 */
class WaveformBandBallisticsTest {

    /** 真机柱间隔：4096 帧 PCM 缓冲 @48kHz ≈ 82.6ms（146.2fps 录像实测 12.1 柱/秒）。 */
    private val barMs = 82.6f

    /** 146.2fps 的帧间隔（录像实测）。 */
    private val frameMs = 1000f / 146.2f

    private fun ring() = WaveformRing(capacity = 512, barCount = 28)

    /**
     * 画面值窗口里「某一帧内变化最大的那一格」的变化量。
     *
     * 这是**阶梯 vs 弹道**的直接判据：改前它等于整格的高度差（一帧跳满），
     * 改后它被起音/回落时间常数摊开。
     */
    private fun maxSingleFrameJump(ring: WaveformRing, prev: FloatArray, now: FloatArray): Float {
        var m = 0f
        for (i in 0 until 28) {
            val d = abs(now[i] - prev[i])
            if (d > m) m = d
        }
        return m
    }

    private fun snapshot(ring: WaveformRing): FloatArray {
        val out = FloatArray(28)
        for (i in 0 until 28) out[i] = ring.bandBarAt(WaveformRing.BAND_LOW, i)
        return out
    }

    @Test
    fun `静音到满幅 —— 起音必须分帧完成，不许一帧跳满`() {
        val r = ring()
        // 先跑一段静音，把窗口填满 0
        repeat(30) { r.push(0f, 0f, 0f, 0f, arrivalAtMs = (it * barMs).toLong() + 1_000_000L) }
        repeat(400) { r.pump(active = true, dtMs = frameMs) }
        var prev = snapshot(r)

        // 一根满幅柱到达
        r.push(0f, 1.0f, 0f, 0f, arrivalAtMs = 1_000_000L + (30 * barMs).toLong())
        var jumped = 0f
        var framesTo90 = -1
        for (f in 1..400) {
            r.pump(active = true, dtMs = frameMs)
            val now = snapshot(r)
            jumped = maxOf(jumped, maxSingleFrameJump(r, prev, now))
            if (framesTo90 < 0 && r.bandBarAt(WaveformRing.BAND_LOW, 27) >= 0.9f) framesTo90 = f
            prev = now
        }
        // 单帧跳变必须远小于整格高度（改前这里是 1.0 = 一帧跳满）
        assertTrue(
            "起音的单帧最大跳变 $jumped 必须 < 0.55（改前 = 1.0，一帧跳满）",
            jumped < 0.55f,
        )
        // 起音必须跨多帧（22ms 时间常数 @6.84ms/帧 ≈ 3.2 帧到 63%，≈7 帧到 90%）
        assertTrue("到 90% 用了 $framesTo90 帧，必须 ≥ 4 帧（1 帧就是硬跳）", framesTo90 >= 4)
        // 最终必须收敛到原始值（否则形状被永久压低）
        assertEquals("收敛后必须精确等于原始值", 1.0f, r.bandBarAt(WaveformRing.BAND_LOW, 27), 1e-4f)
    }

    @Test
    fun `满幅到静音 —— 回落必须比起音慢，且不许一帧跳回`() {
        val r = ring()
        repeat(30) { r.push(0f, 1.0f, 0f, 0f, arrivalAtMs = (it * barMs).toLong() + 1_000_000L) }
        repeat(400) { r.pump(active = true, dtMs = frameMs) }
        // 静音柱到达后开始计时：从 1.0 掉到 1/e 需要 ≈ RELEASE_TAU_MS
        r.push(0f, 0f, 0f, 0f, arrivalAtMs = 1_000_000L + (30 * barMs).toLong())
        var msToE = -1f
        var prev = snapshot(r)
        var jumped = 0f
        for (f in 1..400) {
            r.pump(active = true, dtMs = frameMs)
            val now = snapshot(r)
            jumped = maxOf(jumped, maxSingleFrameJump(r, prev, now))
            if (msToE < 0 && r.bandBarAt(WaveformRing.BAND_LOW, 27) <= 1f / 2.7182817f) {
                msToE = f * frameMs
            }
            prev = now
        }
        assertTrue("回落到 1/e 用了 ${msToE}ms，必须 ≥ 80ms（130ms 时间常数）", msToE >= 80f)
        assertTrue("回落的单帧最大跳变 $jumped 必须 < 0.30", jumped < 0.30f)
        // 回落比起音慢：起音 22ms，回落 130ms ⇒ 时间常数比 ≈ 5.9
        assertTrue(
            "回落时间常数必须明显大于起音（22ms）",
            WaveformRing.RELEASE_TAU_MS > WaveformRing.ATTACK_TAU_MS * 4f,
        )
    }

    @Test
    fun `新柱进入最右格 —— 那一格必须滑过去，不许瞬间赋值`() {
        // 判据只看**最右那一格**（新柱的落点）：它不受窗口平移干扰，
        // 是「瞬时赋值 vs 弹道」唯一干净的口径。
        // 改前：最右格 == 原始窗口最右格 ⇒ 进柱那一帧就跳满整格（比例 = 1.0）。
        // 改后：最右格是**上一格画面值的延续**，按 22ms 时间常数滑向新值。
        val r = ring()
        var t = 1_000_000L
        // 交替响 / 静，每根柱一个周期，让「一格」的落差尽可能大
        var worstInstant = 0f
        var minFramesTo90 = Int.MAX_VALUE
        repeat(30) { k ->
            val v = if (k % 2 == 0) 0.9f else 0.02f
            val before = r.bandBarAt(WaveformRing.BAND_LOW, 27)
            r.push(0f, v, 0f, 0f, arrivalAtMs = t)
            var frames = -1
            for (f in 1..60) {
                r.pump(active = true, dtMs = frameMs)
                if (frames < 0 && abs(r.bandBarAt(WaveformRing.BAND_LOW, 27) - v) <= abs(v - before) * 0.1f) {
                    frames = f
                }
            }
            val step = abs(v - before)
            if (step > 0.2f) {
                // 第一帧就交付了多少比例的整格落差
                worstInstant = maxOf(worstInstant, 0f)
                if (frames in 1 until minFramesTo90) minFramesTo90 = frames
            }
            t += barMs.toLong()
        }
        println("最右格：达到新值 90% 所需帧数（取最小）= $minFramesTo90（改前 = 1）")
        assertTrue(
            "新柱落点必须分帧滑过去：实测最快 $minFramesTo90 帧到 90%，必须 ≥ 4（改前是 1 帧硬跳）",
            minFramesTo90 >= 4,
        )
    }

    @Test
    fun `原始窗口与画面值的差别就是这一版修掉的东西`() {
        val r = ring()
        var t = 1_000_000L
        var rawStep = 0f
        var barStep = 0f
        repeat(30) { k ->
            val v = if (k % 2 == 0) 0.9f else 0.02f
            val rawBefore = r.bandAt(WaveformRing.BAND_LOW, 27)
            val barBefore = r.bandBarAt(WaveformRing.BAND_LOW, 27)
            r.push(0f, v, 0f, 0f, arrivalAtMs = t)
            r.pump(active = true, dtMs = frameMs)   // 只推进一帧
            rawStep = maxOf(rawStep, abs(r.bandAt(WaveformRing.BAND_LOW, 27) - rawBefore))
            barStep = maxOf(barStep, abs(r.bandBarAt(WaveformRing.BAND_LOW, 27) - barBefore))
            repeat(11) { r.pump(active = true, dtMs = frameMs) }
            t += barMs.toLong()
        }
        println("进柱后第一帧：原始窗口变化=$rawStep（= 整格落差，瞬时赋值）  画面值变化=$barStep")
        assertTrue("原始窗口本来就是瞬时赋值", rawStep > 0.5f)
        assertTrue(
            "画面值第一帧只许走一小部分：实测 $barStep，必须 < 原始窗口的一半（$rawStep）",
            barStep < rawStep * 0.5f,
        )
    }

    @Test
    fun `弹道与帧率无关 —— 60Hz 与 146Hz 下同一条曲线`() {
        // ⚠️ 口径修正（v3.4.5）：旧写法按**帧数**推进（`while (elapsed < barMs)`），
        // 于是 60Hz 走 5 帧 = 83.3ms、146Hz 走 13 帧 = 88.9ms —— **两次模拟的总时长差 111ms**。
        // 那量到的是采样网格的相位差，不是积分器的性质（换弹簧之后它一度报到 0.031）。
        // 现在两次运行推柱的**模拟时刻**与**总时长**都相同，只剩"目标切换被观测到的时刻
        // 被量化到帧边界"这一项（≤ 一帧），那才是真正的刷新率无关性。
        fun trajectory(fps: Double): FloatArray {
            val r = ring()
            val dt = (1000.0 / fps).toFloat()
            val totalMs = 2000f
            var sim = 0f
            var nextBar = 0f
            var k = 0
            var t = 1_000_000L
            while (sim < totalMs) {
                if (sim >= nextBar) {
                    r.push(0f, if (k == 12) 1.0f else 0f, 0f, 0f, arrivalAtMs = t)
                    k++
                    nextBar += barMs
                    t += barMs.toLong()
                }
                r.pump(active = true, dtMs = dt)
                sim += dt
            }
            return snapshot(r)
        }
        val a = trajectory(60.0)
        val b = trajectory(146.2)
        var worst = 0f
        for (i in 0 until 28) worst = maxOf(worst, abs(a[i] - b[i]))
        println("60Hz vs 146.2Hz 画面值最大差 = $worst（二阶闭式解 ⇒ 与刷新率无关）")
        assertTrue("两条刷新率下的画面值必须基本一致，实测最大差 $worst", worst < 0.02f)
    }

    @Test
    fun `频带位移仍然进重绘判据 —— 弹道尾巴不许被冻住`() {
        val r = ring()
        repeat(30) { r.push(0f, 1.0f, 0f, 0f, arrivalAtMs = (it * barMs).toLong() + 1_000_000L) }
        repeat(400) { r.pump(active = true, dtMs = frameMs) }
        // 静音：全带值也归零 ⇒ hadSignal 为 false。此时**弹道尾巴**必须仍然要求重绘，
        // 否则回落会被冻在画面上（v1.8.1 被单测抓过的同形状缺陷）。
        r.push(0f, 0f, 0f, 0f, arrivalAtMs = 1_000_000L + (30 * barMs).toLong())
        var redraws = 0
        repeat(40) {
            if (r.pump(active = true, dtMs = frameMs)) redraws++
        }
        assertTrue("静音后的回落尾巴必须继续要求重绘（实测 $redraws 帧）", redraws >= 10)
    }
}
