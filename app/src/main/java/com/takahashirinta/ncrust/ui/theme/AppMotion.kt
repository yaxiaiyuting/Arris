/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · A：全局动效规范（本仓库**唯一**允许新增动画参数的地方）。
 */

package com.takahashirinta.ncrust.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v2.5.0 · A：全局动效规格（**唯一落点**）。
 *
 * ## 现状（探针实测，不是假设）
 *
 * `docs/verification/v2.5.0/probe-motion.md`：
 *  - 本仓库有 **41 处**手写 `tween(...)`、**0 处** `spring(...)`；
 *  - 没有统一的动效规格对象 —— 但参数其实**已经收敛成一套词汇**
 *    （260 / 220 / 200 / 150 / 120 五档时长 + `FastOutSlowInEasing` /
 *    `MetroDefault` / `CubicBezier(0.2,0,0,1)` 三条曲线）。
 *
 * 所以本对象的取值策略是**归纳既有取值 + 补上缺的物理类**，不是发明一套新数值。
 *
 * ## 取值出处（逐条可核，见 `probe-splayer-ref.md` §5）
 *
 * ⚠️ **任务书 §3.3 里那组"四档 spatial/effects"在这份骨架里是不成立的**，探针已证伪：
 * Material 3 Expressive 的官方表述是「**三种速度**（fast / default / slow）×
 * **两种类型**（spatial / effects）= **六个弹簧**」，不是四档。
 * 官方也没有给出任何「弹簧 → tween」的对应值（`MotionScheme` 的 12 个 spec
 * **全部**是 `spring(...)`，代码里没有 tween 分支）。
 * 所以下面凡是标「官方」的都逐值取自 `ExpressiveMotionTokens.kt`，
 * 凡是标「本项目既有」的都取自本仓库既有的 41 处 `tween`。
 *
 * ## 本版**不做**的事（有意为之）
 *
 * **不把 41 处存量 `tween` 批量改走 [AppMotion]。** 理由：
 *  1. 逐处判断「这是空间类还是效果类」需要读 41 处上下文，改错会让手感变化且**无法归因**；
 *  2. 其中 `PlayerCard.kt` / `NcrustLyricsPanel.kt` 被 `AGENTS.md` 明确标注为
 *     load-bearing、**禁止批量重构**；
 *  3. 把「圆角/动效升级」与「播放路径动画行为变化」混进同一次提交，一旦真机掉帧就无法定位
 *     —— 与铁律 15 的取证要求直接冲突。
 *
 * 存量的 41 处作为**已知缺口**写入未验证清单；本版只保证「**新增**动效不散落」。
 *
 * ## 为什么 Float 与 Dp 两套都有
 *
 * 任务书 §3.3 给的骨架是 `spring<Dp>(...)`，而本应用真正被动画驱动的主角是
 * `progress: Animatable<Float>`（播放器卡片，GPU 零重组的核心）。
 * 两者不能互相赋值（`SpringSpec<Float>` ≠ `SpringSpec<Dp>`），所以两套都提供：
 *  - `spatial*`   → `SpringSpec<Float>`，给 `Animatable<Float>` / `animateFloatAsState`；
 *  - `spatial*Dp` → `SpringSpec<Dp>`，给 `Animatable<Dp>` / `animateDpAsState`。
 *
 * 不加第三套：本仓库没有任何需要位置弹簧的非 Float/Dp 属性。
 *
 * ## 与项目自身铁律的关系
 *
 * 这些 spec **只描述时间**，不规定怎么消费。消费侧仍必须遵守
 * 「GPU 零重组」：单一 `progress` + 在 `graphicsLayer { }` / draw 阶段读取，
 * **不得**为了用这些 spec 而新引入逐帧重组的 `animateFloatAsState`。
 */
object AppMotion {

    // ─────────────────────────────────────────────────────────────────────
    // 空间类（位置 / 尺寸 / 卡片入场）——**官方 M3 Expressive spatial 三档**
    //
    // 逐值取自 androidx `ExpressiveMotionTokens.kt`（文件头 VERSION v0_14_0）：
    //   spatial fast    = damping 0.6f / stiffness  800.0f
    //   spatial default = damping 0.8f / stiffness  380.0f
    //   spatial slow    = damping 0.8f / stiffness  200.0f
    //
    // 注意 dampingRatio < 1 ⇒ **会过冲**。这正是 Expressive 的手感来源，
    // 但也意味着它们**不能**用来驱动带阈值判定的 `progress`（见 [playerExpand]）。
    // ─────────────────────────────────────────────────────────────────────

    /** 官方 spatial fast（0.6 / 800）。小元件位移、图标切换。 */
    val spatialFast: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** 官方 spatial default（0.8 / 380）。卡片入场、面板切换的默认值。 */
    val spatialDefault: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** 官方 spatial slow（0.8 / 200）。大面板入场（宽屏侧栏、整页内容替换）。 */
    val spatialSlow: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f)

    /** [spatialFast] 的 Dp 版本。 */
    val spatialFastDp: SpringSpec<Dp> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** [spatialDefault] 的 Dp 版本。 */
    val spatialDefaultDp: SpringSpec<Dp> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /**
     * **不过冲**的空间弹簧（临界阻尼，`dampingRatio = 1.0`）。
     *
     * 用途只有一个但很关键：**驱动带阈值判定的 `progress`**。
     * 官方的三档 spatial 都会过冲，而本应用的播放器 `progress` 同时是命中测试的开关
     * （见 [playerExpand]），过冲会让阈值反复穿越。这**不是**官方 token，
     * 是本项目在自己的约束下必须补的一档，名字上刻意与官方三档区分开。
     */
    val spatialNoOvershoot: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 800f)

    /**
     * **播放器卡片展开**：临界阻尼弹簧（不是官方 spatial token，理由见下）。
     *
     * ⚠️ 这一条是本版**受约束**的落地，不是随手换的缓动。原因（详见 `probe-motion.md` §2.2）：
     * `progress` 同时是**命中测试的开关** ——
     *
     *  - `progress < 0.01f` ⇒ 整棵展开态子树**不挂载**；
     *  - `progress > 0.99f` ⇒ 卡片根节点**吞掉所有事件**；
     *  - 折叠态靠 `padding(top = hitGateInsetDp)` + 等量 `offset` 收窄命中区。
     *
     * 若用会过冲的弹簧（官方 spatial 三档的 dampingRatio 都 < 1），`progress` 会越过 1.0
     * 再回弹，**反复跨越 0.99 / 0.01 两个阈值** ⇒ 子树挂载抖动 + 命中区闪烁。
     * 因此这里固定 `dampingRatio = 1.0f`，并由 `AppMotionSpecTest` 断言
     * 「播放器转场不得过冲」—— 把这个约束钉死，防止下一个人「顺手调成 0.6 更活泼」。
     *
     * `stiffness = 260f` 是照既有 `tween(400ms)` 的**主观速度**配的：
     * 临界阻尼下 ≈4/ω₀ 收敛，ω₀ = √260 ≈ 16.1 rad/s ⇒ ≈250ms 到 98%，
     * 与 400ms 的 `CubicBezier(0.2,0,0,1)`（慢起快收，前段很慢）观感接近。
     */
    val playerExpand: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 260f)

    /**
     * **播放器卡片收起**：临界阻尼，比展开更快（`stiffness` 更大）。
     *
     * 照既有 `tween(260ms FastOutSlowInEasing)` 的主观速度配：收起应当比展开**果断**
     * —— 用户已经决定不看它了，慢吞吞地滑下去是拖沓。同样不过冲（同 [playerExpand] 的理由）。
     */
    val playerCollapse: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 420f)

    /**
     * **按压回弹**：`dampingRatio = 0.45f` 有意欠阻尼，松开后回弹一下。
     *
     * 只用于**缩放**（不参与任何阈值判定），所以过冲是安全的、也是想要的效果。
     */
    val pressScale: SpringSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 900f)

    /** 按压放大倍数（任务书 §3.3「按钮点击：scale 1.05x 回弹」）。 */
    const val PRESS_SCALE: Float = 1.05f

    // ─────────────────────────────────────────────────────────────────────
    // 效果类弹簧 ——**官方 M3 Expressive effects 三档**
    //
    // 逐值取自 `ExpressiveMotionTokens.kt`：
    //   effects fast    = damping 1.0f / stiffness 3800.0f
    //   effects default = damping 1.0f / stiffness 1600.0f
    //   effects slow    = damping 1.0f / stiffness  800.0f
    //
    // Standard 与 Expressive 在 effects 上**完全相同**，只有 spatial 三档不同。
    // 三档都是临界阻尼（不过冲）—— 透明度这类无质量属性本来就不该弹。
    // ─────────────────────────────────────────────────────────────────────

    /** 官方 effects fast（1.0 / 3800）。 */
    val effectsSpringFast: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 3800f)

    /** 官方 effects default（1.0 / 1600）。 */
    val effectsSpringDefault: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 1600f)

    /** 官方 effects slow（1.0 / 800）。 */
    val effectsSpringSlow: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 800f)

    // ─────────────────────────────────────────────────────────────────────
    // 效果类 tween
    //
    // ⚠️ 任务书 §3.3 把 `effects` / `effectsFast` 给成 tween。官方**没有**这样的
    //    对应值（`MotionScheme` 全用弹簧），所以这两条**不是**官方 token，而是
    //    本项目自己的一组「短促、非物理」过渡 —— 它们服务的是
    //    「透明度/颜色这类属性用弹簧会显得忽快忽慢」这个具体问题。
    // ─────────────────────────────────────────────────────────────────────

    /** 通用效果过渡：200ms + `FastOutSlowInEasing`。 */
    val effects: TweenSpec<Float> = tween(durationMillis = 200, easing = FastOutSlowInEasing)

    /** 短促效果过渡：100ms 线性。 */
    val effectsFast: TweenSpec<Float> = tween(durationMillis = 100, easing = LinearEasing)

    // ─────────────────────────────────────────────────────────────────────
    // 本项目既有曲线（**归纳，不是发明**）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 面板 / 抽屉**登场**：300ms + `CubicBezier(0.2, 0, 0, 1)`（慢起快收）。
     *
     * 逐字沿用 Kanesumi `SokuouTweens.SheetAppear` 的取值 —— 这条曲线在本仓库
     * 出现 6 次（`probe-motion.md` §1.2），是已沉淀的手感，不换。
     *
     * 附注（可核，见 `probe-splayer-ref.md` §8-冲突3）：`CubicBezier(0.2, 0, 0, 1)`
     * 与 M3 官方的 `EasingEmphasizedCubicBezier` **逐值相同** ——
     * 也就是说这条既有曲线本来就跟 M3 一致，不需要为了"对齐 M3"去改它。
     */
    val sheetAppear: TweenSpec<Float> = tween(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

    /**
     * 面板 / 抽屉**收起**：260ms + `FastOutSlowInEasing`。
     *
     * 同样逐字沿用 `SokuouTweens.SheetDismiss`。收起短促收敛、避免拖尾。
     */
    val sheetDismiss: TweenSpec<Float> = tween(260, easing = FastOutSlowInEasing)

    /** 封面 / 大图淡入：400ms + `CubicBezier(0.2, 0, 0, 1)`（沿用 `SokuouTweens.CoverFade`）。 */
    val coverFade: TweenSpec<Float> = tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))

    /**
     * 列表 item **入场**：220ms 淡入 + 上滑（任务书 §3.3「列表 item 出现：淡入 + 上滑」）。
     *
     * 上滑距离见 [LIST_ITEM_RISE_DP]。这条动效**必须有界**（铁律 15）——
     * 只对下标 < `ListItemAppear.MAX_ANIMATED_INDEX` 的 item 播放，
     * 判定抽在 `ui/components/ListItemAppear.kt` 并配单测。
     */
    val listItemEnter: TweenSpec<Float> = tween(220, easing = FastOutSlowInEasing)

    /** 列表 item 入场上滑距离。8dp：只够「推上来」的暗示，不足以让列表跳动。 */
    val LIST_ITEM_RISE_DP: Dp = 8.dp

    /**
     * 主题色切换过渡：300ms。
     *
     * 注意：**颜色本身不做逐帧插值**（那会逐帧重组）。这条 spec 只用于驱动
     * 「叠层 alpha」这类零重组手段（Kanesumi 的 `MetroBottomNav` 双 Icon 叠 Alpha 是参照实现）。
     */
    val colorTransition: TweenSpec<Float> = tween(300, easing = FastOutSlowInEasing)

    // ─────────────────────────────────────────────────────────────────────
    // 页面转场：**用户可配，默认启用**（v2.5.1 · F）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 页面转场时长（毫秒）。**唯一的调用点是 `ui/navigation/NavGraph.kt`**，
     * 且只在 [PageTransitionSetting] 判定「用户开着转场」时才会被用上。
     *
     * ## 从「本版不做」到「用户可配」的完整沿革（下一个读到这里的人先看这段）
     *
     * - **v2.5.0**：`NavGraph.kt` 显式把四个转场设成 `EnterTransition.None` /
     *   `ExitTransition.None`，注释写明那是**用户决策**，理由是
     *   「转场期间新旧两页同帧渲染, slide/fade 每帧都要全屏合成, 低端机上
     *   是切换动作的主要掉帧源」。当时的结论是「任务书未给新证据，交回产品决策」，
     *   本常量因此**没有任何调用点**，只是一份书面记录。
     * - **v2.5.1**：用户**拍板改为「用户可配、默认启用」**。
     *   所以本版第一次真的把它接上 —— 但接法是**开关 + 默认值**，
     *   而不是把 v2.5.0 那条「低端机会掉帧」的顾虑直接删掉：
     *   顾虑仍然成立，只是处置方式从「一刀切不做」改成「默认开、用户可关」。
     *   关掉时走 [PageTransitionSetting.durationMs] 返回的 **0**，
     *   `NavGraph` 直接挂 `EnterTransition.None`（零动画、零中间帧、零残留计算）。
     *
     * ## 取证要求（**没有豁免**）
     *
     * 只要这个值 > 0，就必须在低端真机（Android 7.0 / 3GB 这一档）上用
     * `adb shell dumpsys gfxinfo <pkg> framestats` 记录**开启时**的 90 分位帧时间，
     * 并与关闭时对照。**模拟器结论不构成证据，debug 包数据也不构成证据**（铁律 15）——
     * 本版的数据见 `docs/verification/v2.5.1/verification/framestats-*.txt`。
     *
     * 取值 260ms 沿用 v2.5.0 留下的常量与既有的 `sheetDismiss`/`sheetAppear` 同族时长，
     * 不是本版新拍的数。
     */
    const val PAGE_TRANSITION_MS: Int = 260

    /**
     * 页面转场的缓动曲线：`FastOutSlowInEasing`。
     *
     * 与 [sheetDismiss]（260ms + FastOutSlowInEasing）**同一条** —— 页面转场在体感上
     * 就是「一层面板让位给另一层」，没有理由为它单独发明一条曲线。
     *
     * 不用 [spatialDefault] 那类弹簧：转场驱动的是**整页位移**，弹簧的过冲会让页面
     * 越过目标位置再弹回（边缘会出现一条背景色），而 tween 收敛即停。
     */
    val pageTransitionEasing: Easing = FastOutSlowInEasing

    /**
     * 页面转场动画规格（泛型：转场同时驱动 `IntOffset` 位移与 `Float` 透明度，
     * 两者需要各自的 `FiniteAnimationSpec` 实例）。
     *
     * ⚠️ **关掉转场时不要调用它**。`EnterTransition.None` / `ExitTransition.None`
     * 才是零开销路径；本函数只负责「开」的那一半，判定在
     * [PageTransitionSetting.durationMs]（返回 0 即代表关）。
     */
    fun <T> pageTransitionSpec(): TweenSpec<T> =
        tween(durationMillis = PAGE_TRANSITION_MS, easing = pageTransitionEasing)

    // ─────────────────────────────────────────────────────────────────────
    // v2.9.0：界面动效的**幅度**规格（本仓库唯一允许新增动效参数的地方）
    //
    // 与上面的 spec 分工：上面那些描述**时间**（多快），这里描述**幅度**（多大）。
    // 幅度参数刻意也放在这里，而不是散在 `ui/player/motion/` 的各文件里 ——
    // 「这个动效是不是太夸张」必须能在一屏内改完，否则调参必然走形。
    //
    // 全部取值的原则是**小**：界面动效的职责是让画面「活」，不是抢主视觉。
    // 任务书 §4.2/§4.3/§6.1 逐条给了量级（亮度 ±5%、缩放 ±1%、浮动 ±2dp），
    // 本对象逐条照抄，不自行放大。
    // ─────────────────────────────────────────────────────────────────────

    /**
     * v2.9.0 · A 档：封面**浮起阴影**的高度（dp）。
     *
     * 8dp：足够让封面从背景里「浮」出来，又不会在 OLED 纯黑底上显出一圈灰边。
     * 阴影的 **shape 必须用 `AppShapes.large`**（与封面自身的裁切形状同一个）——
     * 形状不一致时阴影会在圆角处露出来，看起来像一个错位的方框。
     */
    val COVER_SHADOW_ELEVATION_DP: Dp = 8.dp

    /**
     * v2.9.0 · A 档：封面随节拍浮动的最大位移（dp）。任务书 §4.3：「幅度小（±2dp）」。
     *
     * 实现是位移而不是缩放：缩放会同时改变封面的视觉尺寸（与 `progress` 那条
     * 展开/收起动画的 scale 叠加后无法归因），位移只影响位置。
     */
    val COVER_FLOAT_DP: Dp = 2.dp

    /**
     * v2.9.0 · A 档：背景呼吸的**亮度**幅度（±比例）。任务书 §4.2：「亮度 ±5%」。
     *
     * 作用在背景层的 `alpha` 上（不是颜色插值）：颜色插值会迫使 shader 重建，
     * 与 v2.8.0「呼吸只改 alpha」的同一条理由。
     */
    const val BREATH_ALPHA_AMPLITUDE: Float = 0.05f

    /** v2.9.0 · A 档：背景呼吸的**缩放**幅度（±比例）。任务书 §4.2：「缩放 ±1%」。 */
    const val BREATH_SCALE_AMPLITUDE: Float = 0.01f

    /**
     * v2.9.0 · B 档：**视差系数**（背景相对前景的位移比例）。
     *
     * 0.35 = 背景走前景 35% 的行程。取值依据：视差的观感来自「差」，不是「量」——
     * 超过 0.5 背景会显得在「追赶」前景，低于 0.2 肉眼分辨不出。
     * 竖屏为主（任务书 §5.4）：横屏时卡片位移被分栏布局吸收，视差几乎不可见。
     */
    const val PARALLAX_FACTOR: Float = 0.35f

    /**
     * v2.9.0 · B 档：**视差行程**（收起态下背景相对展开态的位移，屏幕高的比例）。
     *
     * 0.06 = 屏幕高的 6%。它与 [PARALLAX_FACTOR] 是两件事，都要有：
     *  - [PARALLAX_TRAVEL_FRACTION] 是**总行程**（背景相对前景的位移上限）；
     *  - [PARALLAX_FACTOR] 是**差速比例**（背景走前景行程的百分之多少）。
     *
     * 6% 的依据：S6（2560px 高）上是 154px，肉眼能明确读出"背景在跟着动"；
     * 再大（≥10%）展开动画期间背景会露出边缘（模糊图是 `ContentScale.Crop`，
     * 位移超过裁切余量就会看到黑边）。
     */
    const val PARALLAX_TRAVEL_FRACTION: Float = 0.06f

    /**
     * v2.9.0 · B 档：**歌词当前行**随节拍的最大额外缩放（1.0 → 1.0 + 本值）。
     *
     * 0.03 = 3%。判据是「不干扰阅读」：歌词行本来就有一个基于行距的
     * `inactiveScale` 缩放曲线（`NcrustLyricsPanel`），再叠一个 3% 的脉冲
     * 在视觉上刚好能察觉「跳了一下」，又不会让行距抖动。
     */
    const val LYRIC_PULSE_SCALE: Float = 0.03f

    /** v2.9.0 · B 档：播放控制条随节拍的最大额外缩放。2% —— 按钮是点击目标，缩放不能大到影响命中手感。 */
    const val BEAT_PULSE_SCALE: Float = 0.02f

    /**
     * v2.9.0 · C 档：封面随节拍的 **3D 旋转**最大角度（度）。
     *
     * 6°：`rotationY` 在 6° 时能明确读出立体感，又不至于让封面文字变形
     * （超过 10° 时 Kanesumi 的直角描边会出现明显的透视错位）。
     */
    const val COVER_3D_DEGREES: Float = 6f

    /**
     * v2.9.0 · C 档：3D 旋转的相机距离系数（× density）。与 `AudioVisualizer` 的
     * `PERSPECTIVE_CAMERA_DISTANCE_FACTOR` 同一个量级 —— 相机太近会看到夸张的透视，
     * 太远则等于没转。
     */
    const val COVER_3D_CAMERA_DISTANCE_FACTOR: Float = 24f
}
