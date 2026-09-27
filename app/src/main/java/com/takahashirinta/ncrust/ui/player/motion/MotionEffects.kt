/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier

/**
 * v2.9.0：**统一「动效强度」的纯逻辑**（无 Android / 无 Compose 依赖 ⇒ JVM 单测直接可用）。
 *
 * ## 为什么要有这一层（而不是把界面动效塞进 `VisualizerEffects`）
 *
 * v2.8.0 的 `VisualizerTier` 只描述**波形**。v2.9.0 的纪律是「动效是整体视觉体验，
 * 波形与界面动效必须统一到一个档位」，但两者的**代价结构完全不同**：
 *
 * | | 波形 | 界面动效 |
 * |---|---|---|
 * | 挂载点 | 只有横屏/平板（`PlayerLayout.visualizerSlot`） | 竖屏也有（背景层） |
 * | 失败面 | 音频线程链路上的绘制 | 只是背景少一层 |
 * | 降级顺序 | **后**砍 | **先**砍 |
 *
 * 所以对外仍然只有一个 `动效强度` 档位（[MotionIntensity]），对内是**两张能力位表**：
 * 波形那份继续由 [VisualizerEffects] 拥有（v2.8.0 的渲染代码一行不改），
 * 界面动效那份是 [MotionEffects] 自己的字段。两张表的**唯一交汇点**是本文件：
 * 同一个 `tier` 进去，两边各取所需。
 *
 * ## 三层（A / B / C）与「界面动效总开关」
 *
 * | 档 | 界面动效 | 波形（v2.8.0 既有） |
 * |---|---|---|
 * | 简洁 | A 全开 | 圆角柱 + 峰值保持 + 缓动 + 镜像 |
 * | 精致（默认） | A + B | + 渐变流动 / 光点 / 呼吸 |
 * | 炫技 | A + B + C | + 冲击波 / 粒子 / 3D / 点按 |
 *
 * [MotionEffects.uiMotionEnabled] 是**总开关**（设置项 `ui_motion_enabled`，默认开）：
 * 关掉 = 三层全关、背景回纯色、零帧时钟，性能回到 v2.8.0 之前。**A 档默认开**
 * 正是通过「总开关默认 true + 档位默认精致」两条一起表达的（任务书铁律 22）。
 *
 * ## 旧「炫技细分开关」去哪了
 *
 * v2.8.0 的 `visualizer_showcase` / `visualizer_shockwave` / `visualizer_particles` /
 * `visualizer_perspective` / `visualizer_drag` 五个键在 v2.9.0 **不再参与渲染决策**：
 * 炫技档就是「全开」，细分交给档位本身。见 [MotionPrefs.migrate] 的搬运规则
 * （`tier==2 且 showcase==false` 的老盘会被搬到精致档 —— 那正是它在 v2.8.0 下**实际渲染**的样子）。
 */
object MotionIntensity {

    /**
     * 三档的取值**直接引用** [VisualizerTier]（不是复制常量）。
     *
     * 单点定义：数值语义只有 `VisualizerTier.SIMPLE/REFINED/SHOWCASE` 一处；
     * 这里只是给「统一动效强度」一个语义正确的名字。复制一份常量迟早会分叉。
     */
    const val SIMPLE: Int = VisualizerTier.SIMPLE
    const val REFINED: Int = VisualizerTier.REFINED
    const val SHOWCASE: Int = VisualizerTier.SHOWCASE

    /** 默认档：精致。与 v2.8.0 的 `VisualizerTier.REFINED` 一致（**默认值不得改**）。 */
    const val DEFAULT: Int = VisualizerTier.REFINED

    val RANGE: IntRange = VisualizerTier.RANGE

    /** 越界值归一化（回落调用方给的设备默认档，再兜底精致档）。 */
    fun sanitize(raw: Int, fallback: Int): Int = VisualizerTier.sanitize(raw, fallback)

    /** 设备静态判据 → 起始档。三条判据与 v2.8.0 完全同源（低内存 / 3.5GiB 级 / API<26）。 */
    fun defaultFor(isLowRamDevice: Boolean, totalMemBytes: Long, sdkInt: Int): Int =
        VisualizerTier.defaultTier(isLowRamDevice, totalMemBytes, sdkInt)
}

/**
 * v2.9.0：**自动降级的阶梯**（纯逻辑，有界）。
 *
 * 任务书铁律 23：**优先砍界面动效，再砍波形档位**。这条纪律在这里被写成一张
 * 显式的、可单测的阶梯表，而不是散落在三处的 `if`：
 *
 * | 级别 | 名字 | 动作 | 用户感知 |
 * |---|---|---|---|
 * | 0 | [NONE] | 什么都不做 | — |
 * | 1 | [UI_ADVANCED_OFF] | 关掉 **B 档**界面动效（全屏波形 / 歌词律动 / 节拍脉冲 / 视差） | 背景模糊与呼吸还在，画面安静一点 |
 * | 2 | [UI_ALL_OFF] | 再关掉 **A 档**界面动效（背景回纯色） | 播放页与 v2.8.0 完全一致 |
 * | 3 | [WAVEFORM_DOWN] | 波形档位**降一级**（写回 `motion_tier`） | 波形少一层特效 |
 *
 * ## 三条边界（铁律 4：失败处理必须有界）
 *
 * 1. **每进程最多推进一级**：调用方（`MotionPrefs.applyAutoDowngrade`）在拿到新级别后
 *    就落盘；同一个进程内 `FrameBudgetPolicy` 只判定一次，所以不会连跳三级；
 * 2. **总共最多三级**：[MAX] 之后 [next] 恒返回 `null` —— 永不自动恢复、永不无限降级；
 * 3. **用户手动改档位时重置**：`MotionPrefs.setTier` 把级别写回 [NONE]，
 *    用户「我要炫技」的显式意图永远优先于自动降级。
 *
 * ## 为什么每进程只降一级而不是一次降到底
 *
 * 与 v2.8.0 的 `VisualizerTier.downgradedTier` 同源：降级是**一次性单向阀**，
 * 判据（60 帧窗口内 40% 超标）本身不知道是谁把帧顶起来的（归因要 Perfetto）。
 * 误判的代价必须小 —— 「用户少看一层特效」而不是「用户被一次扒光所有动效」。
 * 三级阶梯让「判定错了」这件事最多只损失一档，且下一级只在**再次**触发时才发生。
 */
object MotionDegrade {

    const val NONE: Int = 0
    const val UI_ADVANCED_OFF: Int = 1
    const val UI_ALL_OFF: Int = 2
    const val WAVEFORM_DOWN: Int = 3

    /** 阶梯上界。到顶之后 [next] 恒为 null。 */
    const val MAX: Int = WAVEFORM_DOWN

    val RANGE: IntRange = NONE..MAX

    /** 越界值归一化：非法一律回落 [NONE]（不写回盘，同 `offline_cache_mb` 的口径）。 */
    fun sanitize(raw: Int): Int = if (raw in RANGE) raw else NONE

    /**
     * 推进一级。`null` = 已经到顶（**不动作**）。
     *
     * 刻意不接受「已经降过」的布尔标记：那正是 v2.8.0 用 `visualizer_auto_downgraded`
     * 表达的东西，而本版的级别本身就是一个 0..3 的水位，水位到顶即终止。
     */
    fun next(current: Int): Int? {
        val level = sanitize(current)
        return if (level >= MAX) null else level + 1
    }

    /** 该级别下界面动效的 B 档是否还能开（≤[UI_ADVANCED_OFF] 时不能）。 */
    fun allowsAdvancedUi(level: Int): Boolean = sanitize(level) < UI_ADVANCED_OFF

    /** 该级别下界面动效的 A 档是否还能开（≤[UI_ALL_OFF] 时不能）。 */
    fun allowsBasicUi(level: Int): Boolean = sanitize(level) < UI_ALL_OFF

    /** 该级别是否要求把波形档位降一级。 */
    fun cutsWaveformTier(level: Int): Boolean = sanitize(level) >= WAVEFORM_DOWN

    /**
     * 「严重超标」的门槛（窗口内超标帧数的比例）。
     *
     * 0.7 的依据：普通门槛是 0.4（`FrameBudgetPolicy.OVER_BUDGET_FRAMES / WINDOW_FRAMES`），
     * 而 PCL110 实测正好落在 0.40（24/60）—— 刚好越线的设备不该被剥夺唯一看得见的动效。
     * 0.7 对应「100 帧里 70 帧超标」，那是"一直在掉"，不是"偶尔抖"。
     */
    const val SEVERE_OVER_BUDGET_RATIO = 0.7f

    /** 由「窗口内超标帧数 / 窗口长度」判定是否严重。 */
    fun isSevere(overBudgetFrames: Int, windowFrames: Int): Boolean {
        if (windowFrames <= 0) return false
        return overBudgetFrames.toFloat() / windowFrames >= SEVERE_OVER_BUDGET_RATIO
    }

    /**
     * v2.9.0（真机反馈后补）：**静态判据已经把某台设备放到最低档时，阶梯止步于 [UI_ADVANCED_OFF]。**
     *
     * ## 为什么（真机实测暴露的退化）
     *
     * S6（Android 7.0 / API 24）被 [MotionIntensity.defaultFor] 判成低端 ⇒ 起始档 = 简洁。
     * 简洁档下 B/C 档界面动效**本来就是关的** ⇒ 第 1 级降级是一个**空操作**，
     * 而第 2 级会把 A 档（背景模糊 + 呼吸 + 封面阴影）也砍掉 —— 那是这台设备**唯一**的动效。
     *
     * 更要命的是「砍了也白砍」：v2.8.0 自己的 KDoc 就写着判据**不知道是谁把帧顶起来的**
     * （归因要 Perfetto），而 S6 在**完全关掉波形**的对照轮里同样 95% 超标 —— 说明它本来就慢，
     * 砍掉每帧只有「一次图层属性更新」的背景呼吸并不会让它变快。用户看到的只是
     * 「播放页变回了 v2.8.0 的老样子」，而且**永不恢复**。这是纯粹的损失。
     *
     * 所以：静态判据已判定为最低档的设备，阶梯到 [UI_ADVANCED_OFF] 为止（只砍 B/C，
     * 且只在那台设备确实有 B/C 可砍时才会推进 —— 见 [nextEffective]）。
     * 非低端设备（有 B/C 可砍、也砍得起）仍然走完整的三级阶梯。
     *
     * @param atFloorTier 调用方用**同一个**静态判据算出来的「起始档是否已是最低档」
     *   （`MotionIntensity.defaultFor(...) == SIMPLE`），不是用户当前选的档位 ——
     *   用户显式选炫技的设备应当享有完整阶梯。
     * @param severe v2.9.0（真机反馈后补）：判据本身是否**严重**超标（见 [SEVERE_OVER_BUDGET_RATIO]）。
     *
     * ## 为什么还要一个「严重」闸门（两台真机各踩了一次）
     *
     * S6 与 PCL110 实测都在**一次 30 秒窗口**里把水位推到了 2（砍掉 A 档 = 播放页变回 v2.8.0
     * 的老样子），而两台的判据分别是 `54/60` 与 **`24/60`（正好卡在 40% 门槛上）**。
     * 24/60 说明这台 144Hz 旗舰只是"偶尔抖"，而 v2.8.0 自己的 KDoc 就写着判据
     * **不知道是谁把帧顶起来的**（归因要 Perfetto）—— 拿一个刚好越线的比例去永久砍掉
     * 用户唯一能看到的界面动效，收益与代价完全不成比例。
     *
     * 所以：**只有严重超标（≥ [SEVERE_OVER_BUDGET_RATIO]）才允许把阶梯推过第 1 级。**
     * 轻微超标只砍 B/C（那部分本来就"可有可无"），A 档（背景模糊 + 呼吸）保留 ——
     * 它是普通用户唯一看得见的那一层。
     */
    fun maxLevelFor(atFloorTier: Boolean, severe: Boolean, userChoseTier: Boolean = false): Int = when {
        // v2.9.0（真机反馈第三轮）：**用户显式选过档位 ⇒ 阶梯止步于第 1 级。**
        //
        // 现场：用户把档位调到「炫技」，进了一次横屏之后冲击波 / 粒子 / 3D **在竖屏和横屏
        // 里都消失了** —— 那不是画不出来，是阶梯走到了第 3 级、把 `motion_tier` 从 2 改成了 1
        // （而「写回档位」正是 v2.8.0 留下的语义）。用户没有任何办法知道这件事发生过。
        //
        // 判据本身（v2.8.0 自己的 KDoc）**不知道是谁把帧顶起来的**，归因要 Perfetto。
        // 拿这样一个信号去**永久覆盖用户刚刚做出的显式选择**，代价与收益完全不成比例：
        // 用户点「炫技」就是在说"我要看效果"，把效果收走不是省电，是违约。
        //
        // 所以：显式选过档位 ⇒ 只砍 B/C 这些"额外"的界面动效，**绝不动波形档位、
        // 也绝不砍 A 档**；没选过（用设备判据解析出来的默认档）⇒ 才允许走完整阶梯 ——
        // 那时的档位是**应用自己**定的，应用当然可以自己调整。
        userChoseTier -> UI_ADVANCED_OFF
        atFloorTier -> UI_ADVANCED_OFF
        !severe -> UI_ADVANCED_OFF
        else -> MAX
    }

    /**
     * v2.9.0：**推进到下一个「确实有东西可砍」的级别**；没有则返回 `null`。
     *
     * 与裸的 [next] 的区别：`next` 只按水位递增，会在「B 档本来就是关的」这种配置上空推一级，
     * 让水位与实际观感脱钩（水位说降了、画面没变；下一次再降就直接砍到 A 档）。
     * 这里逐级试到第一个**真的会改变画面**的级别为止 —— 水位因此始终等价于「实际生效的削减」。
     *
     * @param advancedUiOn 当前配置下 B 档是否有任何一项开着。
     * @param basicUiOn 当前配置下 A 档是否有任何一项开着。
     * @param waveformAboveFloor 波形档位是否高于最低档（否则第 3 级也是空操作）。
     * @param maxLevel 上界（见 [maxLevelFor]）。
     */
    fun nextEffective(
        current: Int,
        advancedUiOn: Boolean,
        basicUiOn: Boolean,
        waveformAboveFloor: Boolean,
        maxLevel: Int = MAX,
    ): Int? {
        val cap = maxLevel.coerceIn(NONE, MAX)
        var level = sanitize(current)
        while (level < cap) {
            level++
            val effective = when (level) {
                UI_ADVANCED_OFF -> advancedUiOn
                UI_ALL_OFF -> basicUiOn
                WAVEFORM_DOWN -> waveformAboveFloor
                else -> false
            }
            if (effective) return level
        }
        return null
    }
}

/**
 * v2.9.0：**一档到底开哪些动效**（不可变值对象，只在设置/降级变化时构造一次）。
 *
 * 帧路径里**只读**这个对象，不读任何 Compose state —— 与 v2.8.0 的 [VisualizerEffects]
 * 同一套写法。每个字段后面的注释写清「落在哪、成本量级、为什么这么定」。
 *
 * @param tier 用户选的动效强度（[MotionIntensity] 取值之一），**未含自动降级**。
 * @param degradeLevel [MotionDegrade] 的当前水位。
 * @param uiMotionEnabled 「界面动效」总开关（设置项 `ui_motion_enabled`，默认 true）。
 * @param waveform 波形那一半的能力位（v2.8.0 的渲染代码直接消费它）。
 * @param backgroundBlur A：封面背景模糊（降采样 + 预模糊 + 缓存，**每首歌只算一次**）。
 * @param backgroundBreathing A：背景随 RMS 的明暗/缩放呼吸（读的是每帧一次的包络，不读盘）。
 * @param coverElevation A：封面浮起阴影 + 随节拍微浮动（幅度 ±2dp）。
 * @param coverTransition A：切歌时封面淡入淡出（走 `AppMotion.coverFade`，**不卸载子树**）。
 * @param fullScreenWaveform B：全屏/背景级波形（横屏铺满底部、竖屏作为背景层）。
 * @param lyricPulse B：当前歌词行随节拍轻微缩放（幅度小，不干扰阅读）。
 * @param beatPulse B：播放键 / 进度条随 RMS 脉冲（幅度小）。
 * @param parallax B：手势拖动时背景与前景不同速度。
 * @param particles C：背景粒子（定长池、零分配、数量有上限）。
 * @param haloBloom C：强节拍时从中心扩散光环（RMS 相对基线突变 + 冷却）。
 * @param cover3d C：封面随节拍轻微 3D 旋转（竖屏为主）。
 */
class MotionEffects(
    val tier: Int,
    val degradeLevel: Int,
    val uiMotionEnabled: Boolean,
    val waveform: VisualizerEffects,
    val backgroundBlur: Boolean,
    val backgroundBreathing: Boolean,
    val coverElevation: Boolean,
    val coverTransition: Boolean,
    val fullScreenWaveform: Boolean,
    val lyricPulse: Boolean,
    val beatPulse: Boolean,
    val parallax: Boolean,
    val particles: Boolean,
    val haloBloom: Boolean,
    val cover3d: Boolean,
) {
    /** A 档是否有任意一项开着。 */
    val anyBasic: Boolean
        get() = backgroundBlur || backgroundBreathing || coverElevation || coverTransition

    /** B 档是否有任意一项开着。 */
    val anyAdvanced: Boolean
        get() = fullScreenWaveform || lyricPulse || beatPulse || parallax

    /** C 档是否有任意一项开着。 */
    val anyShowcase: Boolean get() = particles || haloBloom || cover3d

    /** 有没有任何界面动效 —— 决定要不要挂帧时钟、要不要走背景层。 */
    val anyUiMotion: Boolean get() = anyBasic || anyAdvanced || anyShowcase

    /** 是否需要每帧推进一步（只有「随帧变化」的效果才算，静态的阴影/转场不算）。 */
    val needsFrameClock: Boolean
        get() = backgroundBreathing || fullScreenWaveform || lyricPulse || beatPulse ||
            particles || haloBloom || cover3d

    /** 背景层是否需要挂载（模糊背景是背景层的唯一入口；呼吸/粒子/光环都画在它上面）。 */
    val backgroundLayerEnabled: Boolean
        get() = backgroundBlur || backgroundBreathing || particles || haloBloom || fullScreenWaveform

    /** 该配置下**实际渲染**的波形能力位（已含降级后的档位）。 */
    val effectiveWaveformTier: Int get() = waveform.tier

    companion object {

        /**
         * **全关**（总开关关掉、或降级到 [MotionDegrade.UI_ALL_OFF] 及以上时的界面动效部分）。
         *
         * 波形那一半**不受影响** —— 这正是「总开关只管界面动效」这条语义的机械表达。
         */
        fun of(
            tier: Int,
            uiMotionEnabled: Boolean,
            degradeLevel: Int,
            waveformShowcaseEnabled: Boolean = true,
        ): MotionEffects {
            val t = MotionIntensity.sanitize(tier, MotionIntensity.REFINED)
            val level = MotionDegrade.sanitize(degradeLevel)

            // 自动降级到第 3 级 ⇒ 波形档位降一级（写回 `motion_tier` 的是降级后的值，
            // 这里再算一次是为了「盘上的值被别人改了」的防御；两级同时生效时取更低的那个）。
            val waveformTier = if (MotionDegrade.cutsWaveformTier(level)) {
                (t - 1).coerceAtLeast(MotionIntensity.SIMPLE)
            } else {
                t
            }

            // 界面动效：总开关 → 降级水位 → 档位，三层判据，顺序不能反。
            val uiOn = uiMotionEnabled
            val basic = uiOn && MotionDegrade.allowsBasicUi(level)
            val advanced = basic && MotionDegrade.allowsAdvancedUi(level) &&
                t >= MotionIntensity.REFINED
            val showcase = advanced && t >= MotionIntensity.SHOWCASE

            return MotionEffects(
                tier = t,
                degradeLevel = level,
                uiMotionEnabled = uiMotionEnabled,
                // 波形：C 档细分开关在 v2.9.0 合并进档位（见文件头 KDoc），所以
                // 「档位 == 炫技」即全部 C 档能力位打开。`waveformShowcaseEnabled` 只留给
                // 单测做 A/B（证明渲染结果确实由档位驱动，而不是由遗留键驱动）。
                waveform = VisualizerEffects.of(
                    tier = waveformTier,
                    showcase = waveformShowcaseEnabled,
                    shockwave = waveformShowcaseEnabled,
                    particles = waveformShowcaseEnabled,
                    perspective = waveformShowcaseEnabled,
                    tapInteraction = waveformShowcaseEnabled,
                    autoDowngraded = level > MotionDegrade.NONE,
                ),
                backgroundBlur = basic,
                backgroundBreathing = basic,
                coverElevation = basic,
                coverTransition = basic,
                fullScreenWaveform = advanced,
                lyricPulse = advanced,
                beatPulse = advanced,
                parallax = advanced,
                particles = showcase,
                haloBloom = showcase,
                cover3d = showcase,
            )
        }
    }
}
