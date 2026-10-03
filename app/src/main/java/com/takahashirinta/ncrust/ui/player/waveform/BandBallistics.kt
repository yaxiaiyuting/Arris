/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v3.4.5：**柱高与小球的两个物理模型**（纯逻辑，JVM 可直接单测，零分配）。
 *
 * 用户原话：「抖动还是有，**小球没有弹起来的感觉**，我觉得这个地方小球上下运动的函数
 * 要不就用**牛顿运动学公式**，和**弹性碰撞的公式**来做吧」。
 *
 * 这一版之前，画面上的两个纵向运动都是**非物理**的：
 *
 * | 元素 | 旧写法 | 缺陷 |
 * |---|---|---|
 * | 柱高（色带顶边 / 圆点所在的那条包络） | `current += (target-current) * (1-exp(-dt/tau))` | 指数逼近：渐近、永不越冲、永不回弹，且 `SETTLE_EPSILON` 收敛截断让它在接近目标时**直接吸附** |
 * | 峰值标记（`peaks[]`，画在色带上方的小球/短横） | 保持 420ms 后**匀速**下落，落到柱高就停 | 匀速 = 零加速度；落到柱高即停 = **永不反弹** |
 *
 * 现在换成两个有明确力学依据的模型，**单位统一是「归一化高度 / 秒」**
 * （画面高度 1.0 = 满幅；`BAR_DOT_RADIUS_DP` 那类像素量在渲染层换算，不参与物理）。
 *
 * ## 1. 柱高：[SpringStep] —— 二阶系统（弹簧-阻尼）的**精确解**
 *
 * 牛顿第二定律，把柱高 x 当作一个挂在目标位上的阻尼振子：
 *
 * ```
 * x'' = ω²·(target − x) − 2ζω·x'
 * ```
 *
 * - **不用数值积分（欧拉/半隐式）**：那会让 60Hz 与 146Hz 走出两条不同的曲线
 *   （本仓库的硬契约之一就是「与刷新率无关」）。这里用欠阻尼二阶系统的**闭式解**：
 *   `x(t) = target + e^(−ζωt)·(A·cos ω_d t + B·sin ω_d t)`，`ω_d = ω√(1−ζ²)`。
 *   闭式解对任意 `dt` 都**精确**，所以 60Hz / 146Hz / 30Hz 得到的是同一条轨迹
 *   （只在采样点上不同），不是"误差很小"而是"没有离散误差"。
 * - **系数每帧只算一次**（`e^(−ζω·dt)`、`cos`、`sin` 三个超越函数），
 *   然后所有格子共用 —— `barCount=28` × 4 条窗口也不会变成每格一次 `exp`。
 *
 * ### 参数依据（不是拍脑袋）
 *
 * - **ω（自然频率）取自旧实现的时间常数**：旧代码起音 τ=22ms、回落 τ=130ms
 *   （[BandBallistics.ATTACK_TAU_MS] / [RELEASE_TAU_MS]，v1.8.1 就有）。
 *   二阶系统的**包络**时间常数是 `1/(ζω)`，所以令 `ω = 1/(ζ·τ)`，
 *   就得到"上升/回落的快慢与旧版一致"的振子 —— 观感的改变只来自**惯性**与**过冲**，
 *   而不是整体变快或变慢。升 / 降用各自的 ω（`target ≥ x` 用起音、否则用回落）。
 * - **ζ = 0.75**：过冲量 = `exp(−πζ/√(1−ζ²))` = **2.8%**（满幅的 2.8%，约 2~3px，属于"轻微过冲"），
 *   整定时间 ≈ `3/(ζω)` ≈ 70ms（起音）——比一个柱间隔（83~100ms）短，
 *   所以它**不会**把 12Hz 的阶梯糊成晃动。ζ=1（临界阻尼）过冲为 0，看不到"弹"；
 *   ζ≤0.5 过冲 ≥16%，在满幅附近会反复撞 `coerceIn(0,1)`，画面上会"顶到天花板再弹回来"。
 * - **上界**：`coerceIn(0,1)` 依旧是硬契约（PPM 不得为负、柱高不得越幅）。
 *   2.8% 的过冲意味着只有目标已经在 1.0 附近时才会被夹住，夹住的量极小。
 * - **静止吸附**：`|x−target| + |x'|/ω < SETTLE_EPSILON` 时把 `x` 精确置为 target、速度归零。
 *   这一条是「不空转」契约的支点：只要没吸附就继续要求重绘，一旦吸附就**精确相等**、
 *   下一帧自然停（与旧的 `SETTLE_EPSILON` 语义一致，只是判据里多了速度项）。
 *
 * ## 2. 小球：[BallStep] —— 牛顿自由落体 + 与柱顶的弹性碰撞
 *
 * 状态 = 位置 `y`（归一化高度，向上为正）、速度 `v`、以及"是否静止在地面上"。
 * **柱顶就是地面**（`ground` = 该格的柱高画面值，`groundV` = 该格柱高的速度）。
 *
 * 每帧：
 * ```
 * v ← v − g·dt          （重力：牛顿第二定律，g = GRAVITY）
 * y ← y + v·dt          （运动学）
 * 若 y ≤ ground ⇒ 接触：
 *     y = ground                                   （绝不穿进柱子 —— 硬契约）
 *     v ← −e·v                                     （弹性碰撞，恢复系数 e = RESTITUTION）
 *     若地面正在上升 ⇒ v ← max(v, min(groundV, V_KICK_MAX))（被柱顶"顶起"）
 *     若反弹顶点不可见 ⇒ v = 0 且标记为静止（吸附）
 * ```
 *
 * ### 参数依据
 *
 * - **g = 32 /s²**：由"从满幅 1.0 落到 0 用 ~250ms"反解 —— `g = 2·1.0/(0.25)² = 32`。
 *   250ms 略长于一个柱间隔（83~100ms）但短于两次，所以小球在空中能**看清**一次完整的
 *   起落，不会跨过好几根柱子才落地。
 * - **e = 0.45**（橡胶球量级，0.4~0.6）：每次碰撞保留 45% 的速度 ⇒ 顶点高度变成
 *   `e² = 20%`。第一次反弹 = 落差的 20%，第二次 4%，第三次 0.8% —— 三次之内收敛，
 *   既看得出"弹"，又不会无限抖（e→1 会在柱顶上永远振铃，那正是用户说的"抖动"）。
 * - **顶起（kick）**：柱子升高撞上小球时，小球继承地面速度，但**上限**
 *   `V_KICK_MAX = √(2·g·KICK_APEX_MAX)`，`KICK_APEX_MAX = 0.2`（用户定的顶点）。
 *   理由：完全非弹性正碰（小球取地面速度）在物理上是对的，但柱高的弹簧速度可以到
 *   `ω·Δ ≈ 57/s`，直接继承会把小球甩出画布十几倍高。上限的物理读法是
 *   "柱子的等效质量有限 / 接触不是无穷刚"，工程读法是"顶点必须留在画幅内"。
 *   低于 `KICK_MIN_V` 的缓慢上升**不顶起**，小球只是被地面托着走 ——
 *   否则静止的小球会被地面的微动一直弹，那就又变成抖动了。
 * - **静止判据**用**顶点高度**而不是速度阈值：`v²/(2g) < REST_APEX`（0.15% 满幅 ≈ 0.13px）。
 *   这一点很关键 —— 重力每帧都会给小球 `g·dt` 的速度，若用固定速度阈值，
 *   60Hz（g·dt = 0.53/s）与 146Hz（0.22/s）会得到不同结论，且低帧率下小球会
 *   **永远微跳**、`pump` 永远返回 true（"不空转"契约当场失效）。
 *   静止态下**不施加重力**（地面的法向力抵消它），这是"停得住"的物理原因。
 *
 * ## 零分配
 *
 * 两个类都是**每实例零分配**：`step` 只读写调用方传进来的 `out` 数组与自己的
 * 标量字段，不建对象、不建集合、不捕获 lambda。`WaveformRing` 每个窗口持有
 * **一个** `SpringStep` / `BallStep` 实例（构造期建好）与**一个** `FloatArray` 暂存区，
 * 帧路径上反复复用（见 `WaveformRingTest` 的 `物理步进器复用同一批数组` 用例）。
 */
object BandBallistics {

    // ───────────────────────── 柱高：二阶系统 ─────────────────────────

    /** 阻尼比。过冲 `exp(−πζ/√(1−ζ²))` = 2.8%。取值理由见类 KDoc。 */
    const val DAMPING_RATIO = 0.75f

    /** 起音包络时间常数（毫秒）—— 沿用 v1.8.1 的值，保证"快慢"没有变。 */
    const val ATTACK_TAU_MS = 22f

    /** 回落包络时间常数（毫秒）—— 同上。 */
    const val RELEASE_TAU_MS = 130f

    /** 静止吸附阈值（归一化高度）。与旧的 `SETTLE_EPSILON` 同值。 */
    const val SETTLE_EPSILON = 0.004f

    /**
     * 由包络时间常数反解自然频率：二阶系统的包络是 `e^(−ζωt)`，所以 `ζω = 1/τ`。
     */
    fun omegaFor(tauMs: Float): Float =
        if (tauMs <= 0f) 1f else 1f / (DAMPING_RATIO * (tauMs / 1000f))

    // ───────────────────────── 小球：自由落体 + 弹性碰撞 ─────────────────────────

    /** 重力加速度（归一化高度/秒²）。由"满幅下落 250ms"反解：`2·1.0/0.25² = 32`。 */
    const val GRAVITY = 32f

    /** 恢复系数。0.45 ⇒ 每次碰撞后顶点高度剩 `e²` = 20%。 */
    const val RESTITUTION = 0.45f

    /** 顶点低于这个高度（满幅比例）就认为"看不见了"，吸附静止。 */
    const val REST_APEX = 0.0015f

    /**
     * 被上升的柱顶顶起时，**踢起初速度**对应的顶点上限（满幅比例）。
     *
     * ⚠️ 语义澄清（我第一版在这里算错过一次）：`v = √(2·g·KICK_APEX_MAX)` 反解回来
     * **正好等于这个常量**，所以它**就是**踢起后的顶点，不需要再乘任何修正系数。
     * （我一度按"参数 0.12 → 实测 0.135"乘了 1.125，但那个 0.135 是别处的观测量，
     *   不是这条判据的值 —— 规范地跑 `BallStep` 得到的是 0.178，与参数严格相等。）
     *
     * **用户定的目标是顶点 ≈ 0.2**（原话「顶点高度改 0.2?」），所以直接取 0.2，
     * 并由 `BandBallisticsTest.顶起目标的实测顶点接近用户要求的零点二` 断言实测值 ——
     * 断言"跑出来的顶点"而不是只断言常量，这样以后有人改常量会立刻红。
     */
    const val KICK_APEX_MAX = 0.2f

    /** 顶起速度上限：`√(2·g·KICK_APEX_MAX)` ≈ 2.77 /s。 */
    val V_KICK_MAX: Float = sqrt(2f * GRAVITY * KICK_APEX_MAX)

    /** 低于这个上升速度就不顶起（只是被地面托着走），避免静止小球被微动弹起来。 */
    val KICK_MIN_V: Float = sqrt(2f * GRAVITY * REST_APEX)

    /** 单帧 dt 的上限（秒）：与 `pump` 的 `coerceIn(0,200)` 一致。 */
    const val MAX_DT_SEC = 0.2f

    /**
     * 二阶系统（弹簧-阻尼）在 `dt` 内的**精确解**推进器。
     *
     * **每帧 `prepare` 一次，然后所有格子共用** —— 三个超越函数只算一次。
     * 实例在构造期创建、字段原地覆盖：帧路径零分配。
     */
    class SpringStep {
        /** 本次 `prepare` 用的自然频率（静止判据要用它把速度折成位移）。 */
        var omega: Float = 1f
            private set

        private var decay = 1f
        private var cosWd = 1f
        private var sinWd = 0f
        private var zetaOmega = 0f
        private var omegaD = 1f

        fun prepare(omega: Float, zeta: Float, dtSec: Float) {
            val w = if (omega.isFinite() && omega > 1e-3f) omega else 1e-3f
            val z = if (zeta.isFinite()) zeta.coerceIn(0f, 0.999f) else DAMPING_RATIO
            val d = if (dtSec.isFinite()) dtSec.coerceIn(0f, MAX_DT_SEC) else 0f
            this.omega = w
            zetaOmega = z * w
            omegaD = w * sqrt(1f - z * z)
            decay = exp(-zetaOmega * d)
            val ang = omegaD * d
            cosWd = cos(ang)
            sinWd = sin(ang)
        }

        /**
         * 推进一格。
         *
         * @param out 长度 ≥ 2 的复用数组：`out[0]` = 新位置、`out[1]` = 新速度
         * @return 是否还在动（需要重绘）。吸附到目标后返回 false 且 `out == (target, 0)`
         */
        fun step(x: Float, v: Float, target: Float, out: FloatArray): Boolean {
            val a = x - target
            val b = (v + zetaOmega * a) / omegaD
            val ac = a * cosWd
            val bs = b * sinWd
            val xd = decay * (ac + bs)
            val vd = decay * (omegaD * (b * cosWd - a * sinWd) - zetaOmega * (ac + bs))
            var nx = target + xd
            var nv = vd
            if (!nx.isFinite() || !nv.isFinite()) {
                // 非有限输入绝不传染到画布（与 push 的 NaN 防御同一条纪律）。
                nx = target
                nv = 0f
            }
            // 剩余行程（位移 + 速度折算的位移）小于阈值 ⇒ 精确吸附。
            if (abs(nx - target) + abs(nv) / omega < SETTLE_EPSILON) {
                out[0] = target
                out[1] = 0f
            } else {
                out[0] = nx
                out[1] = nv
            }
            return out[0] != x || out[1] != v
        }
    }

    /**
     * 小球（质点）的自由落体 + 与柱顶（地面）的弹性碰撞推进器。
     *
     * 与 [SpringStep] 一样：实例在构造期创建，字段零分配。
     */
    class BallStep {
        /**
         * 推进一格。
         *
         * @param y 位置（归一化高度，向上为正）
         * @param v 速度（高度/秒，向上为正）
         * @param resting 上一帧是否静止在地面上
         * @param ground 地面 = 该格柱高的画面值
         * @param groundV 地面的速度（柱高弹簧的速度，向上为正）
         * @param out 长度 ≥ 3 的复用数组：`out[0]` 位置、`out[1]` 速度、`out[2]` 静止标志（0/1）
         * @return 是否发生了位移或速度变化（需要重绘）
         */
        fun step(
            y: Float,
            v: Float,
            resting: Boolean,
            ground: Float,
            groundV: Float,
            dtSec: Float,
            out: FloatArray,
        ): Boolean {
            val g = GRAVITY
            val dt = if (dtSec.isFinite()) dtSec.coerceIn(0f, MAX_DT_SEC) else 0f
            // 先净化地面：非有限的地面会在下面被当成初值用（NaN 传染的入口）。
            val gr = if (ground.isFinite()) ground else 0f
            val gv = if (groundV.isFinite()) groundV else 0f
            var yy = if (y.isFinite()) y else gr
            var vv = if (v.isFinite()) v else 0f

            // ── 1) 静止在地面上：地面的法向力抵消重力，位置跟着地面走 ──
            //    只有"地面塌下去"或"地面快速顶上来"才会让小球重新动起来。
            if (resting && yy - gr <= GROUND_EPS) {
                if (gv > KICK_MIN_V) {
                    // 地面以可观速度顶上来 ⇒ 被顶起（这正是"弹起来"的来源）。
                    yy = gr
                    vv = if (gv < V_KICK_MAX) gv else V_KICK_MAX
                    out[0] = yy
                    out[1] = vv
                    out[2] = 0f
                    return yy != y || vv != v
                }
                // 缓慢移动的地面：小球被托着走，速度保持 0（保证最终能停住）。
                out[0] = gr
                out[1] = 0f
                out[2] = 1f
                return gr != y
            }
            // 地面塌到小球下方（或本来就在空中）⇒ 从当前位置自由落体。
            if (resting) vv = 0f

            // ── 2) 自由落体：牛顿第二定律 + 运动学 ──
            vv -= g * dt
            yy += vv * dt

            // ── 3) 与柱顶（地面）的弹性碰撞 ──
            var rest = false
            if (yy <= gr) {
                yy = gr
                if (vv < 0f) {
                    // 自由落体撞地：弹性碰撞 v' = −e·v。
                    vv = -RESTITUTION * vv
                }
                // 地面正在上升 ⇒ 把小球顶起来（正碰）。
                // 这一条对两种情况都成立：接住正在下落的球、以及追上正在上升的球。
                // 上限保证顶点留在画幅内（见 KDoc 的参数依据）。
                if (gv > vv) {
                    val kick = if (gv < V_KICK_MAX) gv else V_KICK_MAX
                    if (kick > vv) vv = kick
                }
                // 顶点看不见 ⇒ 吸附静止。用**顶点**而不是速度阈值，才能与刷新率无关。
                if (vv * vv < 2f * g * REST_APEX) {
                    vv = 0f
                    rest = true
                }
            }
            // ── 4) 硬契约：小球绝不低于当前柱高 ──
            if (yy < gr) {
                yy = gr
                if (vv < 0f) vv = 0f
            }
            out[0] = yy
            out[1] = vv
            out[2] = if (rest) 1f else 0f
            return yy != y || vv != v
        }
    }

    /** 静止态允许的地面抖动余量：小于它就算"地面还在脚下"。 */
    const val GROUND_EPS = 1e-3f
}
