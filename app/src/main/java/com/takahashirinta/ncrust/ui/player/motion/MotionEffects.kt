/*
 * Ncrust —— ncm 第三方客户端
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
 *
 * 所以对外仍然只有一个 `动效强度` 档位（[MotionIntensity]），对内是**两张能力位表**：
 * 波形那份继续由 [VisualizerEffects] 拥有（v2.8.0 的渲染代码一行不改），
 * 界面动效那份是 [MotionEffects] 自己的字段。两张表的**唯一交汇点**是本文件：
 * 同一个 `tier` 进去，两边各取所需。
 *
 * ## v3.0.0：**渲染只看用户选的档位 —— 自动降级机制已整个删除**
 *
 * v2.8.0 有「帧时间超标 ⇒ 自动降一档」，v2.9.0 把它扩成四级阶梯。真机反馈与工程判断
 * 都指向同一个结论：**这套机制制造的混乱多于它省下的帧**。
 *
 *  - 判据（60 帧窗口内 40% 超标）**不知道是谁把帧顶起来的**（归因要 Perfetto），
 *    而在 S6 上实测「完全关掉波形」的对照轮同样 95% 超标 ⇒ 它砍掉的动效并不是肇事者；
 *  - 它会**改写 `motion_tier`**，于是设置页显示的档位与用户点的那一档不再是一回事
 *    （v2.9.0 补记里用户报的「点了炫技，进一次横屏之后冲击波/粒子在竖屏和横屏里都没了」
 *    就是这条）；
 *  - 最要命的是**渲染逻辑不再可推导**：同一份配置在不同进程、不同会话里画出不同的画面，
 *    「为什么这台机器少一层」在代码里找不到答案，只能去翻降级日志。
 *
 * 所以 v3.0.0 把整条链路删掉：**没有 `degradeLevel` 字段、没有阶梯、没有帧时间触发**。
 * [MotionEffects.of] 的输入只剩三样 —— 档位、界面动效总开关、每个动效的独立开关
 * （v3.2.0 起独立开关里多一个「界面律动总闸」，见 [MotionSwitches]）。
 * 性能兜底改由**用户可见、可预期**的手段承担：
 *
 *  1. 低端设备的**初始档位**由静态判据解析（[MotionIntensity.defaultFor]，只在
 *     「用户没选过」时生效，一旦用户选了就以用户为准，永不覆盖）；
 *  2. 每个动效都有自己的开关（[MotionSwitches]），用户想省电可以逐项关；
 *  3. 每个动效**自身**都有硬上界（池容量、寿命、冷却、每帧上限），不会因为机器慢就失控。
 *
 * `motion_degrade_level` / `motion_degrade_log` 两个键**保留不删**（回滚安装不丢数据），
 * 但它们**不再参与任何渲染决策** —— 见 [MotionDegrade]。
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

    /**
     * 设备静态判据 → **初始**档。三条判据与 v2.8.0 完全同源（低内存 / 3.5GiB 级 / API<26）。
     *
     * ⚠️ 它只在**用户从未选过档位**时生效（`MotionPrefs.hasExplicitTier` 为 false）。
     * 它**不是**降级：不会在运行期改写用户的选择，也不是「这台机器只能跑这一档」的断言。
     */
    fun defaultFor(isLowRamDevice: Boolean, totalMemBytes: Long, sdkInt: Int): Int =
        VisualizerTier.defaultTier(isLowRamDevice, totalMemBytes, sdkInt)
}

/**
 * v3.0.0：**v2.9.0 自动降级的遗留常量**（只用于读旧盘与设置注册表的默认值）。
 *
 * ## 为什么整个机制删掉了（写给下一个想把它加回来的人）
 *
 * 自动降级的本意是「替用户省一点」。真机上的实际效果是**渲染结果不可推导**：
 *
 *  - 判据不知道是谁把帧顶起来的（归因要 Perfetto）；S6 上「完全关掉波形」的对照轮
 *    同样 95% 超标 ⇒ 砍掉的动效往往不是肇事者；
 *  - 它会**改写 `motion_tier`**，于是设置页显示的档位与用户点的那一档不再一致；
 *  - 同一个进程里还会因为组件重新注册而连跳数级（v2.9.0 补记第 2 条）。
 *
 * 于是「为什么这台机器少一层特效」在代码里找不到答案，只能去翻降级日志 ——
 * 那是把**不可见的状态**引入了渲染路径。v3.0.0 的取舍是：
 * **宁可让用户在低端机上自己把档位调低，也不要让画面在用户不知情时变化。**
 *
 * ## 这两个键现在的语义
 *
 * | 键 | 现在的语义 |
 * |---|---|
 * | `motion_degrade_level` | **历史值，不参与任何渲染决策**。保留只为「回滚安装不丢数据」。 |
 * | `motion_degrade_log` | 同上；v3.0.0 的迁移会往里面追加一条「机制已移除」的说明。 |
 *
 * 级别数值沿用 v2.9.0 的定义（1 = 砍 B 档界面动效 / 2 = 砍 A 档 / 3 = 波形降级），
 * 这样一条老日志在新版本里读起来仍然是原意。
 */
object MotionDegrade {

    /** 旧的「未降级」。 */
    const val NONE: Int = 0

    /** 旧的「砍 B 档界面动效」。 */
    const val UI_ADVANCED_OFF: Int = 1

    /** 旧的「再砍 A 档界面动效」。 */
    const val UI_ALL_OFF: Int = 2

    /** 旧的「波形档位降一级」。 */
    const val WAVEFORM_DOWN: Int = 3

    /** 旧阶梯的上界。**只是历史常量**，没有任何推进逻辑了。 */
    const val MAX: Int = WAVEFORM_DOWN

    val RANGE: IntRange = NONE..MAX

    /** 越界值归一化（读旧盘用；非法一律回落 [NONE]）。 */
    fun sanitize(raw: Int): Int = if (raw in RANGE) raw else NONE

    /** 一条降级记录的历史格式（`MotionPrefs` 迁移时往日志里补说明用）。 */
    fun describeLegacyLevel(level: Int): String = when (sanitize(level)) {
        NONE -> "none"
        UI_ADVANCED_OFF -> "ui-advanced-off"
        UI_ALL_OFF -> "ui-all-off"
        else -> "waveform-down"
    }
}

/**
 * v3.0.0：**每个新动效的独立开关**（任务书铁律 26）。
 * v3.2.0：新增「界面律动总开关」+ 三个律动类细粒度开关（封面浮动 / 歌词律动 / 控制条脉冲）。
 *
 * ## 为什么是一个值对象而不是一堆布尔参数
 *
 * `MotionEffects.of` 的参数已经不少，再加 9 个会把「一档开哪些效果」变成一张
 * 十几参数的调用（铁律 14：参数数量监控）。这些开关的**生命周期完全一致**
 * —— 都只在设置变化时读一次盘、都只在 `MotionEffects.of` 里被消费 ——
 * 所以它们天然是一个值对象。
 *
 * ## 默认全 `true`：**默认值不得改**这条纪律在这里的表达
 *
 * 这些键在上一版不存在，所以「缺 key」必须解析成「与上一版观感一致」的那一侧。
 * 而「一致」是靠**档位**保证的，不是靠开关：开关只做 AND，档位（[MotionIntensity]）
 * 才是「这一档有没有这类动效」的判据。于是默认全开 = 升级后观感只随档位表变化，
 * 不会因为「新键没写」而少画东西。
 *
 * ⚠️ v3.2.0 的唯一例外是**简洁档**：那个档位本身收窄了（[MotionEffects] 的类 KDoc
 * 里写了理由），与「缺 key 解析成开」不冲突 —— 开关是开着的，是档位不给。
 *
 * ## `rhythm`（界面律动总开关）与 `ui_motion_enabled`（界面动效总开关）的区别
 *
 * | 闸 | 键 | 关掉之后 |
 * |---|---|---|
 * | 界面动效总闸 | `ui_motion_enabled` | **A/B/C 三层全部不挂载**：背景回纯色、零帧时钟、波形档位完全不受影响 |
 * | 界面律动闸 | `motion_rhythm_enabled` | **只**掐掉「驱动量来自 `MotionEnvelope` 的节拍 / 强拍 / 响度」那一类（背景呼吸 / 封面浮动 / 歌词律动 / 控制条脉冲 / 封面 3D 旋转）；冲击波、光晕、粒子、视差、背景级波形、背景模糊、封面阴影一概不受影响 |
 *
 * 两者的关系是**包含**而不是并列：律动闸是总闸**之内**的进一步收窄。
 * 落到公式上（`MotionEffects.of` 里逐项可见）：
 *
 * ```
 * 有效 = 档位允许 AND ui_motion_enabled AND (律动类 ? motion_rhythm_enabled : true) AND 逐项开关
 * ```
 *
 * 「关掉就是关掉」由结构保证：`of(...)` 里每一个律动类能力位都显式 `&& rhythm`
 * （不是先算一个中间变量再复用），任何档位都绕不过它。
 *
 * @param shockwave `motion_shockwave`：瞬态触发的冲击波。
 * @param halo `motion_halo`：瞬态触发的光晕。
 * @param particles `motion_particles`：中高频能量驱动的粒子。
 * @param waveBands `motion_wave_bands`：多频段能量对波形的调制（逐柱着色 / 能量条）。
 * @param breathing `motion_breathing`：背景随 RMS 的呼吸（律动类）。
 * @param rhythm v3.2.0 `motion_rhythm_enabled`：**界面律动总开关**（律动类的总闸）。
 * @param coverFloat v3.2.0 `motion_cover_float`：封面随节拍浮动（律动类）。
 * @param lyricPulse v3.2.0 `motion_lyric_pulse`：当前歌词行随节拍缩放（律动类）。
 * @param barPulse v3.2.0 `motion_bar_pulse`：底部播放控制条随节拍脉冲（律动类）。
 */
class MotionSwitches(
    val shockwave: Boolean = true,
    val halo: Boolean = true,
    val particles: Boolean = true,
    val waveBands: Boolean = true,
    val breathing: Boolean = true,
    val rhythm: Boolean = true,
    val coverFloat: Boolean = true,
    val lyricPulse: Boolean = true,
    val barPulse: Boolean = true,
) {
    /** 有没有任何一项「音频驱动的新动效」开着（v3.0.0 那五项的口径，保持不变）。 */
    val anyBinding: Boolean get() = shockwave || halo || particles || waveBands || breathing

    /** v3.2.0：律动类的细粒度开关里有没有任意一项开着（不含总闸自身的判定）。 */
    val anyRhythmDetail: Boolean get() = breathing || coverFloat || lyricPulse || barPulse

    companion object {
        /** 全开（缺 key / 全新安装时的解析结果）。 */
        val ALL_ON = MotionSwitches()
    }
}

/**
 * v2.9.0：**一档到底开哪些动效**（不可变值对象，只在设置变化时构造一次）。
 *
 * 帧路径里**只读**这个对象，不读任何 Compose state —— 与 v2.8.0 的 [VisualizerEffects]
 * 同一套写法。每个字段后面的注释写清「落在哪、成本量级、为什么这么定」。
 *
 * ## v3.2.0 的档位表（任务书 §4.4 的 0 档收窄）
 *
 * | 档 | 界面动效 |
 * |---|---|
 * | 简洁 | 波形基础 + **静态**背景（封面模糊、封面浮起阴影、切歌淡入）—— **零逐帧量** |
 * | 精致（默认） | 简洁 + **背景呼吸 / 封面随节拍浮动 / 歌词律动 / 控制条脉冲** + 冲击波 / 光晕 / 粒子（低密度） / 多频段波形调制 + B 档（全屏波形 / 视差） |
 * | 炫技 | 精致 + **粒子高密度 / 光晕多圈** + C 档（封面 3D） |
 *
 * ⚠️ **v3.2.0 有意改了简洁档**（P0-B 的用户报告：「选了简洁，界面还在抖」）：
 * 上一版把「背景呼吸」与「封面随节拍浮动」算作简洁档的基础项，于是那一档每一帧都在推
 * 帧时钟、画面跟着鼓点上下浮（证据：`docs/verification/v3.2.0/probe-ui-jitter.md` §6）。
 * 本期把简洁档定义成**静态档**：`MotionEffects.of(0, ...)` 的律动类能力位一个都不为真。
 * 与任务书表格的另一处刻意偏差（**保留背景模糊**）理由不变：S6 这类低端设备静态判据就是
 * 简洁档，模糊背景是那台设备上唯一看得见的美化。
 *
 * ## 这里**没有** `degradeLevel`
 *
 * 不是忘了加，是 v3.0.0 的**结构性保证**：自动降级已整个删除，档位是渲染的**唯一**输入
 * （连同总开关与逐项开关）。把水位字段删掉而不是「留着但不读」，是为了让
 * 「渲染偷偷依赖了某个后台状态」这件事在编译期就不可能发生 —— 与 v2.9.0 把
 * `VisualizerPrefs.effects` 从 `MutableState` 收窄成 `State` 是同一手法。
 *
 * @param tier 用户选的动效强度（[MotionIntensity] 取值之一）。
 * @param uiMotionEnabled 「界面动效」总开关（设置项 `ui_motion_enabled`，默认 true）。
 * @param waveform 波形那一半的能力位（v2.8.0 的渲染代码直接消费它）。
 * @param switches 独立开关（见 [MotionSwitches]）。
 * @param backgroundBlur A：封面背景模糊（降采样 + 预模糊 + 缓存，**每首歌只算一次**）。
 * @param backgroundBreathing A：背景随 RMS 的明暗/缩放呼吸（读的是每帧一次的包络，不读盘）。
 *   v3.2.0：**精致档起**（律动类，受 `motion_rhythm_enabled` 与 `motion_breathing` 双重约束）。
 * @param coverElevation A：封面浮起阴影（**静态**：只在组合期决定要不要给阴影）。
 * @param coverFloat A：封面随节拍微浮动（幅度 ±2dp）。v3.2.0 从 [coverElevation] 里拆出来
 *   —— 一个能力位表示两件事时，「静态阴影」会替「逐帧浮动」把档位门控绕过去（P0-B 的根因）。
 * @param coverTransition A：切歌时封面淡入淡出（走 `AppMotion.coverFade`，**不卸载子树**）。
 * @param fullScreenWaveform B：全屏/背景级波形（横屏铺满底部、竖屏作为背景层）。
 * @param lyricPulse B：当前歌词行随节拍轻微缩放（幅度小，不干扰阅读）。
 * @param beatPulse B：播放键 / 进度条随 RMS 脉冲（幅度小）。
 * @param parallax B：手势拖动时背景与前景不同速度。
 * @param shockwave v3：**瞬态触发**的冲击波（径向渐晕从画面中心扩散）。
 * @param haloBloom v3：**瞬态触发**的光晕圈（径向渐变描边 + 强度随击打力度）。
 * @param particles v3：**中高频能量驱动**的粒子（生成速率与能量正相关，定长池）。
 * @param particleDensity 粒子密度档（[DENSITY_LOW] 精致 / [DENSITY_HIGH] 炫技）。
 * @param haloRings 每次瞬态扩散几圈光晕（1 = 精致，2 = 炫技「多圈」）。
 * @param cover3d C：封面随节拍轻微 3D 旋转（竖屏为主）。v3.2.0：它是律动类
 *   —— 唯一驱动量是 `MotionClock.pulse()`，律动闸关掉时 `rotationY` 恒为 0，
 *   位若仍为 true 就是「能力位在撒谎」。
 */
class MotionEffects(
    val tier: Int,
    val uiMotionEnabled: Boolean,
    val waveform: VisualizerEffects,
    val switches: MotionSwitches,
    val backgroundBlur: Boolean,
    val backgroundBreathing: Boolean,
    val coverElevation: Boolean,
    val coverFloat: Boolean,
    val coverTransition: Boolean,
    val fullScreenWaveform: Boolean,
    val lyricPulse: Boolean,
    val beatPulse: Boolean,
    val parallax: Boolean,
    val shockwave: Boolean,
    val haloBloom: Boolean,
    val particles: Boolean,
    val particleDensity: Int,
    val haloRings: Int,
    val cover3d: Boolean,
) {
    /** A 档是否有任意一项开着。 */
    val anyBasic: Boolean
        get() = backgroundBlur || backgroundBreathing || coverElevation || coverFloat ||
            coverTransition

    /** B 档是否有任意一项开着。 */
    val anyAdvanced: Boolean
        get() = fullScreenWaveform || lyricPulse || beatPulse || parallax

    /**
     * v3.0.0：**音频驱动的界面动效**是否有任意一项开着（**不含波形**）。
     *
     * v3.2.2 起把 `waveform.waveBandOn` 从这里移出去了：波形的频带着色由它自己的开关
     * （`motion_wave_bands`）管，与界面动效总闸无关 —— 留在里面会让
     * 「总闸关掉 ⇒ anyAudioBinding == false」这条语义变成假的（关掉总闸后波形照样着色）。
     * **音频线程要不要跑特征，唯一口径是 [needsAudioFeatures]**（它仍然包含波形那一路）。
     */
    val anyAudioBinding: Boolean
        get() = shockwave || haloBloom || particles || backgroundBreathing || coverFloat ||
            lyricPulse || beatPulse || cover3d

    /** C 档是否有任意一项开着。 */
    val anyShowcase: Boolean get() = particles || haloBloom || cover3d

    /** 有没有任何界面动效 —— 决定要不要挂帧时钟、要不要走背景层。 */
    val anyUiMotion: Boolean get() = anyBasic || anyAdvanced || anyShowcase

    /**
     * v3.2.0：**律动类**（驱动量来自 `MotionEnvelope` 的节拍 / 强拍 / 响度）是否有任意一项开着。
     *
     * 它是「界面律动总闸」那一层语义的机械表达，也是简洁档 P0-B 的判据：
     * `of(0, ...)` 的 `anyRhythm` 必须为 false。归类判据与逐项表见
     * `docs/verification/v3.2.0/probe-ui-jitter.md` §5。
     */
    val anyRhythm: Boolean
        get() = backgroundBreathing || coverFloat || lyricPulse || beatPulse || cover3d

    /**
     * 是否需要每帧推进一步（只有「随帧变化」的效果才算，静态的阴影/转场不算）。
     *
     * v3.2.0：简洁档这一项**恒为 false**（律动类全部收窄到精致档，B/C 档本来就不在简洁档）
     * ⇒ 简洁档不挂帧循环、不跑音频特征。
     */
    val needsFrameClock: Boolean
        get() = backgroundBreathing || coverFloat || fullScreenWaveform || lyricPulse ||
            beatPulse || shockwave || haloBloom || particles || cover3d

    /**
     * v3.0.0：这一份配置是否**需要音频特征**（决定音频线程要不要跑特征提取）。
     *
     * 它与「要不要画波形」（`WaveformStore.enabled`）是**两件事** —— v2.9.0 把它们
     * 混成了一个开关，于是「关掉音频可视化」会静默掐掉界面动效的节拍数据。
     *
     * 呼吸也要特征：它的幅度来自全带 RMS 的包络，而这个包络的输入就是音频特征
     * （特征链路不可用时回落 `WaveformStore.newestBar()`，见 `MotionEnvelope`）。
     */
    val needsAudioFeatures: Boolean
        get() = backgroundBreathing || coverFloat || beatPulse || lyricPulse ||
            shockwave || haloBloom || particles || waveform.waveBandOn

    /** 背景层是否需要挂载（模糊背景是背景层的唯一入口；呼吸/粒子/光环都画在它上面）。 */
    val backgroundLayerEnabled: Boolean
        get() = backgroundBlur || backgroundBreathing || particles || haloBloom ||
            shockwave || fullScreenWaveform

    /** 该配置下**实际渲染**的波形能力位。 */
    val effectiveWaveformTier: Int get() = waveform.tier

    companion object {

        /** 粒子低密度档（精致）。 */
        const val DENSITY_LOW = 0

        /** 粒子高密度档（炫技）。 */
        const val DENSITY_HIGH = 1

        /**
         * **能力位映射的唯一落点**（v2.8.0 起就是这条纪律：档位 → 能力位是一次纯函数，
         * 单测可以逐格断言「这一档到底开了哪些效果」，不需要设备、不需要截图）。
         *
         * 判据的层数与顺序（不能反）：**总开关 → 用户选的档位 → 律动总闸（只对律动类）
         * → 逐项开关**。
         * （v2.9.0 在档位之后还有一层「降级水位」，v3.0.0 已删除 —— 见 [MotionDegrade]。）
         *
         * v3.2.0：律动类（驱动量来自 `MotionEnvelope` 的节拍 / 强拍 / 响度）多一道独立总闸。
         * 每一项都**显式**写 `&& switches.rhythm`（不先算中间变量再复用）——
         * 「关掉就是关掉，任何档位都不许绕过」必须能在每一行上直接读出来。
         *
         * @param waveformShowcaseEnabled 只留给单测做 A/B（证明渲染结果确实由档位驱动，
         *   而不是由遗留的 `visualizer_showcase` 等键驱动）。
         */
        fun of(
            tier: Int,
            uiMotionEnabled: Boolean,
            waveformShowcaseEnabled: Boolean = true,
            switches: MotionSwitches = MotionSwitches.ALL_ON,
        ): MotionEffects {
            val t = MotionIntensity.sanitize(tier, MotionIntensity.REFINED)

            val uiOn = uiMotionEnabled
            val refinedPlus = uiOn && t >= MotionIntensity.REFINED
            val showcase = refinedPlus && t >= MotionIntensity.SHOWCASE
            // 律动类的共同前置条件：档位允许（精致起）**且**总开关**且**律动闸。
            val rhythm = refinedPlus && switches.rhythm

            return MotionEffects(
                tier = t,
                uiMotionEnabled = uiMotionEnabled,
                switches = switches,
                // 波形：C 档细分开关在 v2.9.0 合并进档位（见文件头 KDoc），所以
                // 「档位 == 炫技」即全部 C 档能力位打开。
                waveform = VisualizerEffects.of(
                    tier = t,
                    showcase = waveformShowcaseEnabled,
                    shockwave = waveformShowcaseEnabled,
                    particles = waveformShowcaseEnabled,
                    perspective = waveformShowcaseEnabled,
                    tapInteraction = waveformShowcaseEnabled,
                    // v3.2.2：多频段调制（主导频带着色）**与档位解耦**，只受它自己的开关管。
                    //
                    // 为什么改（用户实测反馈）：原先门槛是 `refinedPlus`（= 界面动效总闸开
                    // **且** 档位 ≥ 精致），而**低端机的默认档就是简洁档** —— 于是 S6 这类设备的
                    // 默认体验是"一条单色曲线"，用户反馈「没有做出左中右分别代表低中高频率的感觉」。
                    // 着色本来就是**波形自己的**属性（`motion_wave_bands` 是波形侧的开关），
                    // 与"界面动效开不开""档位高不高"是两件事：
                    //
                    //   `audio_visualizer`  → 波形挂不挂载
                    //   `motion_wave_bands` → 波形按不按频带着色   ← 只有这一个开关管它
                    //   `ui_motion_enabled` → 界面动效（背景/粒子/歌词律动…），与波形无关
                    //
                    // 代价是**音频线程的合成要开**（着色必须有 low/mid/high，见 needsAudioFeatures）：
                    // 低端机因此从"只算 RMS"变成"算 RMS + 三频带"。该链路的既有实测代价是
                    // **574 µs / 缓冲、约 162× 实时**（docs/verification/v3.0.0/probe/EVIDENCE.md），
                    // 帧时间在 S6 简洁档上复测过（docs/verification/v3.2.2/probe-perf-tier.md）。
                    // 炫技档仍然多画一条三频带能量条（那一条才需要 `showcase`）。
                    waveBandMode = if (switches.waveBands) {
                        if (showcase) {
                            VisualizerEffects.MODE_WAVE_BAND_LANES
                        } else {
                            VisualizerEffects.MODE_WAVE_BAND_TINT
                        }
                    } else {
                        VisualizerEffects.MODE_WAVE_BAND_OFF
                    },
                ),
                // A 档的**静态**项：简洁档起就有（背景模糊 + 封面阴影 + 切歌淡入）。
                backgroundBlur = uiOn,
                coverElevation = uiOn,
                coverTransition = uiOn,
                // A 档的**逐帧**项（v3.2.0 起收窄到精致档）：它们都是律动类，
                // 简洁档因此是**静态档**（needsFrameClock == false）。
                // 实测根因见 docs/verification/v3.2.0/probe-ui-jitter.md §6。
                backgroundBreathing = rhythm && switches.breathing,
                coverFloat = rhythm && switches.coverFloat,
                // B 档：精致档起。
                fullScreenWaveform = refinedPlus,
                lyricPulse = rhythm && switches.lyricPulse,
                beatPulse = rhythm && switches.barPulse,
                parallax = refinedPlus,
                // v3.0.0 的音频驱动动效：精致档就有（低密度 / 单圈），炫技档加密度与圈数。
                shockwave = refinedPlus && switches.shockwave,
                haloBloom = refinedPlus && switches.halo,
                particles = refinedPlus && switches.particles,
                particleDensity = if (showcase) DENSITY_HIGH else DENSITY_LOW,
                haloRings = if (showcase) 2 else 1,
                // C 档：炫技专属。它是律动类（唯一驱动量是 pulse）⇒ 也过律动闸。
                cover3d = showcase && switches.rhythm,
            )
        }

        /**
         * **全关**（总开关关掉时的界面动效部分）。
         *
         * 波形那一半**不受影响** —— 这正是「总开关只管界面动效」这条语义的机械表达。
         */
        fun off(waveformShowcaseEnabled: Boolean = true): MotionEffects =
            of(
                tier = MotionIntensity.SIMPLE,
                uiMotionEnabled = false,
                waveformShowcaseEnabled = waveformShowcaseEnabled,
            )
    }
}
