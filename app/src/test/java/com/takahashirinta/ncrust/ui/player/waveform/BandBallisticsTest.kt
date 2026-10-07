/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
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
        println("反弹：入射 −2.0 → 出射 ${out[1]}（−e·v 期望 ${0.7f * 2f}）")
        assertEquals("反弹速度必须 = −e·v", BandBallistics.RESTITUTION * 2f, out[1], 0.03f)
        // ★ v3.4.6：位置断言改了。旧实现是"整帧积分 → 穿模 → 夹回地面"，所以碰撞后
        //   小球**恰好**停在地面上；新实现在**接触时刻**（t* = 0.25ms）碰撞，
        //   然后走完这一帧剩下的 0.75ms —— 小球已经离开地面一点点，这是对的。
        //   钉住的不变量从"恰好在地面"改成"绝不在地面之下，且已开始上升"。
        assertTrue("碰撞后绝不许低于地面，实测 ${out[0]}", out[0] >= 0.5f)
        assertTrue("碰撞后应已离开地面（本帧剩余时间在上升）", out[0] > 0.5f)
    }

    /**
     * ★ v3.4.6 的核心回归：**碰撞不许注入能量**。
     *
     * 旧写法「整帧积分 → 穿模 → 把位置夹回地面」会把小球从地面以下瞬移回地面，
     * 等于每帧凭空补给 `½·g·穿透深度` 的势能；每跳只衰减 `e²`，于是存在稳定极限环
     * `h* = e²·inj/(1−e²)`。e=0.7 时 `h* ≈ 0.6%` 满幅，**远高于** [REST_APEX]，
     * 小球会在柱顶上永远微跳、永不吸附（实测 600 帧仍在 0.4006±0.006 循环）。
     *
     * 这条用例把"能量守恒"本身钉住：后继顶点必须**严格递减**、比值约等于 `e²`，
     * 并在有限跳内落进 `REST_APEX`。夹回地面的实现在第二条断言上必红。
     */
    @Test
    fun `碰撞不注入能量 —— 后继顶点严格按 e 平方衰减并收敛`() {
        val out = FloatArray(3)
        val step = BandBallistics.BallStep()
        val dt = 1f / 60f
        val ground = 0.4f
        var y = 1.0f
        var v = 0f
        var rest = false
        val apexes = mutableListOf<Float>()
        var rising = false
        var lastY = y
        for (frame in 0 until 4000) {
            step.step(y, v, rest, ground, 0f, dt, out)
            y = out[0]; v = out[1]; rest = out[2] != 0f
            // 顶点 = 上升转下降的那一刻
            if (rising && y < lastY) apexes += lastY
            rising = y > lastY
            lastY = y
            if (rest) break
        }
        println("顶点序列（落差 0.6）：" + apexes.joinToString(" ") { "%.4f".format(it) })
        assertTrue("必须记录到多次反弹，实测 ${apexes.size} 次", apexes.size >= 4)
        // ① 严格递减
        for (i in 1 until apexes.size) {
            assertTrue("顶点必须严格递减：${apexes[i - 1]} → ${apexes[i]}", apexes[i] < apexes[i - 1])
        }
        // ② 相邻顶点的比值 ≈ e²（这是"碰撞只损失能量、不注入"的直接证据）
        val e2 = BandBallistics.RESTITUTION * BandBallistics.RESTITUTION
        for (i in 1 until apexes.size) {
            val ratio = (apexes[i] - ground) / (apexes[i - 1] - ground)
            assertEquals("第 $i 跳的顶点比必须 ≈ e²", e2, ratio, 0.05f)
        }
        // ③ 必须收敛到静止（极限环会让它永不 rest）
        assertTrue("必须在有限跳内吸附静止", rest)
        assertEquals("最终必须正好停在柱顶", ground, y, 1e-6f)
        assertEquals("最终速度必须为 0", 0f, v, 0f)
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
    fun `参数依据自检 —— 顶点行程的滞空时间约 200ms，后继弹跳看得见`() {
        val g = BandBallistics.GRAVITY
        val apex = BandBallistics.KICK_APEX_MAX
        val tUp = sqrt(2f * apex / g)
        println("g=$g ⇒ 从顶点 $apex 落到地面用 ${tUp * 1000}ms（完整起落 ${tUp * 2000}ms）")

        // ★ v3.4.6：定标对象从「满幅落到 0」改成「顶点行程」—— 后者才是小球日常的行程。
        //   旧值 32 是按满幅 250ms 反解的，于是日常那 0.2 的行程只有 112ms，
        //   用户读到的就是「落地好快，像平移到下一帧」。
        assertEquals("g 的取值依据就是这条（顶点行程上升 200ms）", 0.2f, tUp, 0.012f)
        assertTrue("完整起落必须远长于一个柱间隔（83ms），实测 ${tUp * 2000}", tUp * 2f > 0.25f)
        assertTrue("但不该拖到跨过好几拍", tUp * 2f < 0.8f)

        val e = BandBallistics.RESTITUTION
        assertEquals("后继顶点比 = e²（49%）", 0.49f, e * e, 0.02f)

        // ★ 用户实测「没有运算后面连续的弹性碰撞」——把"看得见几次"钉死。
        //   阈值取 2% 满幅（感知门槛），不是 REST_APEX（那是"技术上还在动"）。
        val perceptual = 0.02f
        var h = apex
        var visible = 0
        repeat(16) {
            h *= e * e
            if (h >= perceptual) visible++
        }
        println("e=$e：从顶点 $apex 起，感知可见的后继弹跳 $visible 次")
        assertTrue("后继弹跳至少要看得见 3 次（旧值 0.45 只有 1 次），实测 $visible", visible >= 3)

        // 上界：序列必须**有限步**收敛到静止（否则柱顶上永远振铃 = 抖动）。
        var hh = apex
        var steps = 0
        while (hh >= BandBallistics.REST_APEX && steps < 64) {
            hh *= e * e
            steps++
        }
        println("收敛到 REST_APEX 用了 $steps 步")
        assertTrue("e 必须让顶点序列有限步收敛，实测 $steps 步", steps in 1..16)

        assertTrue("过冲必须是轻微量级", abs(exp(-Math.PI.toFloat() * 0.75f / sqrt(1f - 0.75f * 0.75f))) < 0.05f)
    }

    // ─────────────── v3.4.6：小球不再粘在柱顶上（用户实测回归） ───────────────

    /**
     * ★ 本次修复的**核心回归**，用户原话：
     *
     * > 「**就是粘在顶上一起平移，短线也是，一点都不真实**」
     *
     * 旧实现在"静止在地面上"那一支里**无条件** `y = ground; v = 0`，于是柱子怎么动、
     * 小球就怎么跟着平移。这条用例让地面以 `3/s` 匀速塌下去 0.2 秒：
     *
     * - **物理正确的**结果：小球脱离地面后做自由落体，`½·g·t² = 0.2` ⇒ 只落下 ~0.2，
     *   仍在地面之上（地面已落到 0.3，小球约在 0.7）；
     * - **旧实现的**结果：小球与地面逐帧相等（`y == ground == 0.3`）—— 刚体平移。
     *
     * 所以断言「小球明显高于地面」在旧实现下必红。
     */
    @Test
    fun `地面回落时小球脱离地面自由落体 —— 不再粘在柱顶一起平移`() {
        val out = FloatArray(3)
        val step = BandBallistics.BallStep()
        val dt = 1f / 60f
        val fallV = -3f           // 地面匀速回落 3/s（远快于 DETACH_MIN_V）
        var ground = 0.9f
        var y = 0.9f
        var v = 0f
        var rest = true
        val frames = 12            // 0.2s
        repeat(frames) {
            ground += fallV * dt
            val groundV = fallV
            step.step(y, v, rest, ground, groundV, dt, out)
            y = out[0]; v = out[1]; rest = out[2] != 0f
        }
        println("地面塌了 0.2s：ground=${"%.4f".format(ground)}  小球 y=${"%.4f".format(y)}  v=${"%.4f".format(v)}  rest=$rest")
        assertFalse("地面回落时小球必须脱离地面（旧实现会一直 rest=true）", rest)
        assertTrue(
            "小球必须明显高于地面（自由落体跟不上匀速塌陷）—— 旧实现这里 y == ground。" +
                "实测 y=$y ground=$ground",
            y - ground > 0.2f,
        )
        assertTrue("脱离后速度必须向下（重力在做功）", v < 0f)
    }

    /** 地面回落速度低于门槛时仍然"托着走"—— 这条保住"静止吸附 / 不空转"契约。 */
    @Test
    fun `缓慢回落的地面仍然托着小球`() {
        val out = FloatArray(3)
        val slow = -BandBallistics.DETACH_MIN_V * 0.5f
        BandBallistics.BallStep().step(0.30f, 0f, true, 0.30f + slow * (1f / 60f), slow, 1f / 60f, out)
        assertEquals("低于门槛时速度保持 0", 0f, out[1], 0f)
        assertEquals("位置跟随地面", 0.30f + slow * (1f / 60f), out[0], 1e-6f)
        assertTrue("必须仍然处于静止态", out[2] != 0f)
    }

    /** 脱离的那一刻：地面已经下落过，所以小球位置跟着地面更新，必须报"需要重绘"。 */
    @Test
    fun `脱离的那一帧必须报告变化`() {
        val out = FloatArray(3)
        // 地面这一帧落到了 0.495（原来小球站在 0.5）
        val changed = BandBallistics.BallStep().step(
            y = 0.5f, v = 0f, resting = true,
            ground = 0.495f, groundV = -3f, dtSec = 1f / 60f, out = out,
        )
        assertTrue("地面下落了，小球位置随之更新 ⇒ 必须重绘", changed)
        assertTrue("脱离后不许再自称静止", out[2] == 0f)
        assertEquals("脱离时位置贴住当前地面", 0.495f, out[0], 1e-6f)
    }

    /** 脱离之后必须**落地并反弹**（而不是穿过去或永远悬空）。 */
    @Test
    fun `脱离之后会落回柱顶并反弹`() {
        val out = FloatArray(3)
        val step = BandBallistics.BallStep()
        val dt = 1f / 60f
        val fallV = -3f
        var y = 0.9f
        var v = 0f
        var rest = true
        var bounces = 0
        var prevV = 0f
        // 地面先以 3/s 塌到 0.4，然后**停住**（模拟柱子塌完）。
        // ⚠️ 地面速度必须**如实传给步进器** —— 它靠这个值判断"托着走 / 脱离 / 顶起"。
        //    本用例第一版手动挪地面却把 groundV 传 0，于是步进器认为地面没动、
        //    把小球一路托到 0.4，一次都没弹（那是用例的错，不是实现的）。
        var ground = 0.9f
        var gv = fallV
        repeat(600) {
            if (ground > 0.4f) {
                ground = maxOf(0.4f, ground + fallV * dt)
                gv = if (ground > 0.4f) fallV else 0f
            } else {
                ground = 0.4f
                gv = 0f
            }
            step.step(y, v, rest, ground, gv, dt, out)
            y = out[0]; v = out[1]; rest = out[2] != 0f
            if (prevV < -0.5f && v > 0.5f) bounces++
            prevV = v
        }
        println("地面停在 0.4：600 帧内反弹 $bounces 次，终态 y=$y v=$v rest=$rest")
        assertTrue("必须落回地面并至少反弹一次，实测 $bounces 次", bounces >= 1)
        assertEquals("最终必须停在柱顶上", 0.4f, y, 1e-5f)
        assertTrue("最终必须静止", rest)
    }

    // ─────────────── v3.4.6：峰值短横的保持值（"曾经到过的最高点"） ───────────────

    /**
     * 峰值保持的三条不变量。
     *
     * 用户实测两次指出短横与小球"是一样的"——根因是它的位置曾经等于
     * `小球 + 常量`，即两者永远同步。补上这份状态之后，两者才开始各说各的话：
     * 小球是此刻的质点，短横是这一格到过的最高点。
     */
    @Test
    fun `峰值保持 小球上去立刻跟上 下来时不跟`() {
        val dt = 1f / 60f
        // 小球从下面升上来 ⇒ 保持值当帧就跟到小球（不许滞后）
        assertEquals("小球创出新高时保持值必须当帧跟上", 0.8f, BandBallistics.holdStep(0.8f, 0.2f, dt), 0f)
        // 小球落下去 ⇒ 保持值只按固定速度回落，不跟下去
        val held = BandBallistics.holdStep(0.1f, 0.8f, dt)
        val expected = 0.8f - BandBallistics.DASH_HOLD_DECAY_PER_SEC * dt
        assertEquals("小球下落时保持值只线性回落", expected, held, 1e-6f)
        assertTrue("必须仍然远高于小球（这才是「历史峰值」）", held > 0.1f)
    }

    /** 回落速度的标定：从满幅掉到 0 用 2 秒。 */
    @Test
    fun `峰值保持 从满幅回落到零约两秒`() {
        val t = 1f / BandBallistics.DASH_HOLD_DECAY_PER_SEC
        println("回落速度=${BandBallistics.DASH_HOLD_DECAY_PER_SEC}/s ⇒ 满幅回落用 ${t}s")
        assertEquals("回落速度的取值依据就是这条（2s）", 2f, t, 0.05f)
    }

    @Test
    fun `峰值保持 绝不低于小球 也绝不为负`() {
        assertTrue("保持值恒 ≥ 小球", BandBallistics.holdStep(0.9f, 0.1f, 1f / 60f) >= 0.9f)
        assertEquals("不得为负", 0f, BandBallistics.holdStep(0f, 0.001f, 1f), 0f)
        assertEquals("非有限输入归零", 0f, BandBallistics.holdStep(Float.NaN, 0.5f, 0.016f), 0f)
        assertEquals("非有限保持值归零", 0f, BandBallistics.holdStep(0.5f, Float.NaN, 0.016f), 0f)
        assertEquals("非有限 dt 不推进", 0.8f, BandBallistics.holdStep(0.1f, 0.8f, Float.NaN), 1e-6f)
    }
}
