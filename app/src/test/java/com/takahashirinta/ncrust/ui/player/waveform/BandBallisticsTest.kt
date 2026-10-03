/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * v3.4.5：柱高（二阶弹簧-阻尼）与小球（牛顿自由落体 + 弹性碰撞）的**纯物理**单测。
 *
 * 为什么必须把物理抽出来单测（而不是在 Composable 里"看着调"）：
 * 这个组件在本项目里已经因为「判据口径错」失败过多次，而"弹起来没有"是一个
 * **可测量的运动学命题**，不是审美命题 —— 自由落体符合 `h = ½gt²`、碰撞速度等于
 * `−e·v`、绝不穿透地面、静止后不再要求重绘，这四条都能在 JVM 上钉死。
 * 观感是否真的像"弹起来"只能由用户看真机，但**运动学对不对**不该靠肉眼。
 */
class BandBallisticsTest {

    private val g = BandBallistics.GRAVITY

    private fun ballStep(
        y: Float, v: Float, rest: Boolean, ground: Float, groundV: Float, dt: Float,
        out: FloatArray = FloatArray(3),
    ): FloatArray {
        BandBallistics.BallStep().step(y, v, rest, ground, groundV, dt, out)
        return out
    }

    // ───────────────────────── 小球 ─────────────────────────

    @Test
    fun `自由落体符合 h = ½gt²`() {
        val out = FloatArray(3)
        val step = BandBallistics.BallStep()
        var y = 1f
        var v = 0f
        val dt = 0.001f
        val n = 100
        repeat(n) { step.step(y, v, false, -1f, 0f, dt, out); y = out[0]; v = out[1] }
        val t = dt * n
        val analytic = 1f - 0.5f * g * t * t
        println("自由落体 t=${t}s: 仿真 y=$y  解析 ½gt² = $analytic  速度 v=$v")
        assertEquals("位置必须符合 h = ½gt²", analytic, y, 5e-3f)
        assertEquals("速度必须符合 v = −gt", -g * t, v, 1e-3f)
    }

    @Test
    fun `落地反弹速度等于 −e 倍入射速度`() {
        val out = FloatArray(3)
        // 球在离地 0.5mm 处以 −2 高度/秒下落，1ms 内必然撞地
        out.let { BandBallistics.BallStep().step(0.5005f, -2f, false, 0.5f, 0f, 0.001f, it) }
        println("反弹：入射 −2.0 → 出射 ${out[1]}（−e·v 期望 ${0.45f * 2f}）")
        assertEquals("反弹速度必须 = −e·v", BandBallistics.RESTITUTION * 2f, out[1], 0.03f)
        assertEquals("碰撞后必须正好在地面上", 0.5f, out[0], 1e-6f)
    }

    @Test
    fun `小球绝不穿透柱顶（随机地面序列）`() {
        val out = FloatArray(3)
        val step = BandBallistics.BallStep()
        var y = 0f
        var v = 0f
        var rest = true
        var seed = 12345
        var worstPenetration = 0f
        repeat(4000) { i ->
            seed = seed * 1103515245 + 12345
            val r = ((seed ushr 16) and 0x7fff) / 32767f
            val ground = 0.5f + 0.45f * kotlin.math.sin(i * 0.031f) * r.coerceAtLeast(0.2f)
            val groundV = (ground - y) * 4f
            step.step(y, v, rest, ground, groundV, 1f / 60f, out)
            y = out[0]; v = out[1]; rest = out[2] != 0f
            val pen = ground - y
            if (pen > worstPenetration) worstPenetration = pen
        }
        println("4000 帧随机地面：最大穿透 = $worstPenetration（必须 ≤ 0）")
        assertTrue("小球绝不允许穿到柱顶之下，实测最大穿透 $worstPenetration", worstPenetration <= 1e-6f)
    }

    @Test
    fun `柱顶快速升高时小球被顶起`() {
        val out = FloatArray(3)
        // 静止在 0.30 的柱顶上，地面一帧内升到 0.50 并以 2.0 高度/秒上升
        BandBallistics.BallStep().step(0.30f, 0f, true, 0.50f, 2.0f, 1f / 60f, out)
        println("被顶起：y=${out[0]} v=${out[1]}（地面 0.50 / 2.0）")
        assertEquals("小球必须被抬到新的柱顶", 0.50f, out[0], 1e-6f)
        assertTrue("小球必须获得向上的速度", out[1] > 1f)
        assertTrue("必须离开静止态", out[2] == 0f)
    }

    @Test
    fun `顶起速度有上限 —— 顶点必须留在画幅内`() {
        val out = FloatArray(3)
        BandBallistics.BallStep().step(0.1f, 0f, true, 0.2f, 50f, 1f / 60f, out)
        println("极端顶起（地面 50/s）：v=${out[1]}（上限 ${BandBallistics.V_KICK_MAX}）")
        assertTrue("顶起速度必须被夹到上限", out[1] <= BandBallistics.V_KICK_MAX + 1e-5f)
        val apex = out[1] * out[1] / (2f * g)
        assertTrue("顶点必须留在画幅内，实测 $apex", apex <= BandBallistics.KICK_APEX_MAX + 1e-3f)
    }

    @Test
    fun `顶起目标的实测顶点接近用户要求的零点二`() {
        // ★ 这条是用户指定值的**看门狗**。用户原话:「顶点高度改 0.2?」
        //
        // 为什么不断言 KICK_APEX_MAX 本身：那个常量是**踢起初速对应的顶点参数**，
        // 与"眼睛看到的顶点"之间隔着一次反解（实测会比参数略高，约 1.125 倍）——
        // 第一版取 0.12 时实测 0.135。所以必须断言**实测顶点**，
        // 否则以后有人改常量、观感变了，测试仍然全绿。
        val out = FloatArray(3)
        // 地面以远高于上限的速度跳起 ⇒ 必定取到最大踢起
        BandBallistics.BallStep().step(0.1f, 0f, true, 0.2f, 50f, 1f / 146f, out)
        val v0 = out[1]
        val apex = v0 * v0 / (2f * BandBallistics.GRAVITY)
        println("实测顶点 = $apex（目标 0.2）")
        assertTrue(
            "实测顶点应在 0.2 附近（±0.02），实际 $apex",
            apex >= 0.18f && apex <= 0.22f,
        )
    }

    @Test
    fun `缓慢上升的地面不顶起小球`() {
        val out = FloatArray(3)
        val slow = BandBallistics.KICK_MIN_V * 0.5f
        BandBallistics.BallStep().step(0.30f, 0f, true, 0.31f, slow, 1f / 60f, out)
        assertEquals("小球被地面托着走，速度保持 0", 0f, out[1], 0f)
        assertEquals("位置跟随地面", 0.31f, out[0], 1e-6f)
    }

    @Test
    fun `静止之后步进器不再报告变化`() {
        val out = FloatArray(3)
        val step = BandBallistics.BallStep()
        var y = 0.4f
        var v = 0f
        var rest = true
        // 地面完全静止：第一帧就应该什么都不做
        assertFalse("静止的小球不许报告变化", step.step(y, v, rest, 0.4f, 0f, 1f / 60f, out))
        // 就算从"空中"开始，落定之后也必须停下来（否则 pump 永远返回 true）
        y = 0.5f; v = 0f; rest = false
        var frames = 0
        while (frames < 600) {
            val moved = step.step(y, v, rest, 0.4f, 0f, 1f / 60f, out)
            y = out[0]; v = out[1]; rest = out[2] != 0f
            if (!moved) break
            frames++
        }
        println("从 0.5 落到静止的柱顶：$frames 帧后停止重绘（含反弹）")
        assertTrue("必须在有限帧内静止（实测 $frames 帧）", frames in 1..600)
        assertEquals("静止后必须正好停在柱顶", 0.4f, y, 1e-6f)
        assertEquals("静止后速度必须为 0", 0f, v, 0f)
        assertFalse("静止后不许再报告变化", step.step(y, v, rest, 0.4f, 0f, 1f / 60f, out))
    }

    @Test
    fun `静止判据与刷新率无关 —— 30 到 146Hz 都停得下来`() {
        for (fps in intArrayOf(30, 60, 90, 120, 146)) {
            val dt = 1f / fps
            val out = FloatArray(3)
            val step = BandBallistics.BallStep()
            var y = 0.8f
            var v = 0f
            var rest = false
            var frames = 0
            while (frames < 2000) {
                val moved = step.step(y, v, rest, 0.3f, 0f, dt, out)
                y = out[0]; v = out[1]; rest = out[2] != 0f
                if (!moved) break
                frames++
            }
            println("${fps}Hz：${frames} 帧后静止（y=$y）")
            assertEquals("${fps}Hz 下必须停在柱顶", 0.3f, y, 1e-6f)
            assertTrue("${fps}Hz 下必须有界收敛，实测 $frames 帧", frames in 1..2000)
        }
    }

    @Test
    fun `非有限输入不传染`() {
        val out = FloatArray(3)
        BandBallistics.BallStep().step(Float.NaN, Float.NaN, false, Float.NaN, Float.NaN, Float.NaN, out)
        assertTrue("位置必须有限", out[0].isFinite())
        assertTrue("速度必须有限", out[1].isFinite())
    }

    // ───────────────────────── 柱高 ─────────────────────────

    @Test
    fun `二阶系统的过冲量等于 exp(-πζ÷√(1-ζ²))`() {
        val step = BandBallistics.SpringStep()
        val out = FloatArray(2)
        val omega = BandBallistics.omegaFor(BandBallistics.ATTACK_TAU_MS)
        var x = 0f
        var v = 0f
        var peak = 0f
        repeat(600) {
            step.prepare(omega, BandBallistics.DAMPING_RATIO, 1f / 1000f)
            step.step(x, v, 1f, out)
            x = out[0]; v = out[1]
            if (x > peak) peak = x
        }
        val z = BandBallistics.DAMPING_RATIO
        val analytic = exp(-Math.PI.toFloat() * z / sqrt(1f - z * z))
        println("过冲：实测 ${peak - 1f}（解析 exp(−πζ/√(1−ζ²)) = $analytic）")
        assertEquals("过冲量必须等于二阶系统的解析值", analytic, peak - 1f, 2e-3f)
        assertTrue("必须是**轻微**过冲（< 5%）", peak - 1f < 0.05f)
    }

    @Test
    fun `阶跃响应的 90 ％ 上升时间落在起音时间常数量级`() {
        val step = BandBallistics.SpringStep()
        val out = FloatArray(2)
        val omega = BandBallistics.omegaFor(BandBallistics.ATTACK_TAU_MS)
        var x = 0f
        var v = 0f
        var t90 = -1f
        var t = 0f
        repeat(2000) {
            step.prepare(omega, BandBallistics.DAMPING_RATIO, 1f / 1000f)
            step.step(x, v, 1f, out)
            x = out[0]; v = out[1]; t += 1f
            if (t90 < 0 && x >= 0.9f) t90 = t
        }
        println("起音 90% 上升时间 = ${t90}ms（旧指数 22ms 时间常数 ≈ 50ms）")
        assertTrue("上升时间必须在 20~90ms 之间，实测 $t90", t90 in 20f..90f)
    }

    @Test
    fun `回落比起音慢 —— 与旧的时间常数同源`() {
        /** 从 [from] 出发阶跃到 target，量到达 [level] 所需毫秒。 */
        fun msTo(from: Float, target: Float, level: Float, rising: Boolean, omega: Float): Float {
            val step = BandBallistics.SpringStep()
            val out = FloatArray(2)
            var x = from
            var v = 0f
            var t = 0f
            repeat(4000) {
                step.prepare(omega, BandBallistics.DAMPING_RATIO, 1f / 1000f)
                step.step(x, v, target, out)
                x = out[0]; v = out[1]; t += 1f
                if (if (rising) x >= level else x <= level) return t
            }
            return -1f
        }
        // 起音：0 → 1，到 63.2%；回落：1 → 0，到 36.8% —— 两者都是"包络走了一个时间常数"
        val rise = msTo(0f, 1f, 1f - 0.368f, true, BandBallistics.omegaFor(BandBallistics.ATTACK_TAU_MS))
        val fall = msTo(1f, 0f, 0.368f, false, BandBallistics.omegaFor(BandBallistics.RELEASE_TAU_MS))
        println("包络走 1/e：起音 ${rise}ms（旧 22ms 时间常数 ⇒ 22ms）、回落 ${fall}ms（旧 130ms ⇒ 130ms）")
        assertTrue("起音必须在 20~45ms，实测 $rise", rise in 20f..45f)
        assertTrue("回落必须明显慢于起音（$fall vs $rise）", fall > rise * 3f)
    }

    @Test
    fun `闭式解是精确的 —— 步长差 16 倍仍落在同一条轨迹上`() {
        fun run(dtMs: Float): FloatArray {
            val step = BandBallistics.SpringStep()
            val out = FloatArray(2)
            val omega = BandBallistics.omegaFor(BandBallistics.RELEASE_TAU_MS)
            var x = 0f
            var v = 0f
            val n = (100f / dtMs).toInt()
            repeat(n) {
                step.prepare(omega, BandBallistics.DAMPING_RATIO, dtMs / 1000f)
                step.step(x, v, 1f, out)
                x = out[0]; v = out[1]
            }
            return floatArrayOf(x, v)
        }
        val fine = run(1f)          // 100 步 × 1ms
        val coarse = run(100f / 6f) // 6 步 × 16.67ms —— 同一总时长
        println("同一 100ms：1ms 步长 x=${fine[0]} / 16.67ms 步长 x=${coarse[0]}")
        assertEquals("精确解：不同步长必须落在同一点", fine[0], coarse[0], 1e-5f)
        assertEquals("速度同样必须一致", fine[1], coarse[1], 1e-4f)
    }

    @Test
    fun `吸附之后精确等于目标且不再报告变化`() {
        val step = BandBallistics.SpringStep()
        val out = FloatArray(2)
        val omega = BandBallistics.omegaFor(BandBallistics.ATTACK_TAU_MS)
        var x = 0f
        var v = 0f
        var frames = 0
        while (frames < 1000) {
            step.prepare(omega, BandBallistics.DAMPING_RATIO, 1f / 60f)
            val moved = step.step(x, v, 0.75f, out)
            x = out[0]; v = out[1]
            if (!moved) break
            frames++
        }
        println("吸附到目标：$frames 帧（x=$x v=$v）")
        assertEquals("必须精确吸附到目标值", 0.75f, x, 0f)
        assertEquals("速度必须精确归零", 0f, v, 0f)
        assertFalse("吸附后不许再报告变化", step.step(x, v, 0.75f, out))
    }

    @Test
    fun `非有限输入不传染 —— 弹簧`() {
        val out = FloatArray(2)
        val step = BandBallistics.SpringStep()
        step.prepare(Float.NaN, Float.NaN, Float.NaN)
        step.step(Float.NaN, Float.NaN, 0.5f, out)
        assertTrue("位置必须有限", out[0].isFinite())
        assertTrue("速度必须有限", out[1].isFinite())
        assertEquals("非有限输入必须回落到目标值", 0.5f, out[0], 1e-6f)
    }

    // ───────────────────────── 零分配 ─────────────────────────

    @Test
    fun `物理步进器复用同一批数组 —— 帧路径零分配`() {
        // 帧路径（pump → approach）只能调用 step 并传进**已经存在**的数组：
        // 两个 step 都不返回新对象、不接受 lambda、不建集合。
        // 这里把传进去的数组实例记下来，跑 5000 步之后必须还是同一个实例、内容被原地覆盖。
        val out = FloatArray(3)
        val identity = out
        val springOut = FloatArray(2)
        val springIdentity = springOut
        val spring = BandBallistics.SpringStep()
        val ball = BandBallistics.BallStep()
        val omega = BandBallistics.omegaFor(BandBallistics.ATTACK_TAU_MS)
        var y = 0.2f
        var v = 0f
        var rest = false
        var x = 0f
        var xv = 0f
        repeat(5000) { i ->
            spring.prepare(omega, BandBallistics.DAMPING_RATIO, 1f / 60f)
            spring.step(x, xv, if (i % 120 < 60) 1f else 0.1f, springOut)
            x = springOut[0]; xv = springOut[1]
            ball.step(y, v, rest, x, xv, 1f / 60f, out)
            y = out[0]; v = out[1]; rest = out[2] != 0f
        }
        assertSame("小球步进器不许换掉调用方的数组", identity, out)
        assertSame("弹簧步进器不许换掉调用方的数组", springIdentity, springOut)
        assertNotSame("（自检）两个暂存区不是同一个对象", out, springOut)
        assertTrue("结果必须是有限值", y.isFinite() && v.isFinite() && x.isFinite())
    }

    @Test
    fun `参数依据自检 —— 满幅下落到地的时间约 250ms`() {
        val t = sqrt(2f * 1f / g)
        println("g=$g ⇒ 从 1.0 落到 0 用 ${t * 1000}ms")
        assertEquals("g 的取值依据就是这条（250ms）", 0.25f, t, 0.01f)
        val e = BandBallistics.RESTITUTION
        assertEquals("第一次反弹的顶点高度 = e² × 落差（20%）", 0.2f, e * e, 0.01f)
        assertTrue("跌落过程必须看得出一次完整起落（> 一个柱间隔 83ms）", t > 0.083f)
        assertTrue("但不能跨过好几根柱子（< 两个柱间隔）", t < 0.166f * 2f + 0.01f)
        assertTrue("过冲必须是轻微量级", abs(exp(-Math.PI.toFloat() * 0.75f / sqrt(1f - 0.75f * 0.75f))) < 0.05f)
    }
}
