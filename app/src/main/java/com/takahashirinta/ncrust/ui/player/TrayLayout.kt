/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · C：竖屏播放托盘（mini bar）的**唯一几何与结构落点**。纯 JVM，可单测。
 */

package com.takahashirinta.ncrust.ui.player

/**
 * 竖屏播放托盘的**高度、封面尺寸、三行结构、控制按钮清单**。
 *
 * ## 为什么必须抽出来（v2.5.2 规则 2 的直接应用）
 *
 * 「托盘高 56dp」这个事实在 v2.5.4 之前是**四个地方各写一份的字面量**：
 *
 * | # | 位置 | 用途 | 与真实高度不一致的后果 |
 * |---|---|---|---|
 * | 1 | `PlayerCard` 的 `.height(56.dp)` | 托盘本体 | — |
 * | 2 | `MainActivity` 的 `miniBarHeightPx` | 算 `collapsedOffsetY` | 卡片收起落点差一截，托盘上沿与卡片底沿之间露出空白带；死带判定与命中区同时错位 |
 * | 3 | `BottomOverlayInset.kt` 的 `144.dp / 64.dp` | 所有 `LazyColumn` 的 `contentPadding(bottom)` | 列表最后一项被托盘盖住 |
 * | 4 | `PlayerCard` 的 `miniCoverHalfPx = 28.dp` | 唯一封面 overlay 的缩放落点中心 | 收起态封面在托盘里偏上/偏下 |
 *
 * 55 → 80 的改动如果只动第 1 处，另外三处会**静默错位**。所以高度只有一个定义（[HEIGHT_DP]），
 * 其余全部由它派生。
 *
 * ## 三行结构（v2.5.5 · C 的目标布局）
 *
 * ```
 * 实时歌词                    ← 第一行 [Row.LYRIC]      bodySmall / primary
 * 歌名                        ← 第二行 [Row.TITLE]      独占，bodyMedium / onBackground
 * 作者                  音源  ← 第三行 [Row.META]       左对齐 + 右对齐，bodySmall
 * ```
 *
 * 高度预算（真机 PLC110 @560dpi 实测的字形剖面 + 排版盒换算）：
 *
 * | 行 | 行高 | px |
 * |---|---|---|
 * | 歌词 `bodySmall` | 16sp | 56 |
 * | 歌名 `bodyMedium` | 20sp | 70 |
 * | 作者+音源 `bodySmall` | 16sp | 56 |
 * | 行间距 ×2（[LINE_GAP_DP]） | 2dp | 7 |
 * | **合计** | **56dp** | **196** |
 *
 * 56dp 的托盘**刚好等于内容高度**（0 留白 ⇒ 视觉贴死），所以本版取 [HEIGHT_DP] = 80dp，
 * 上下各留 12dp。72dp（任务书下界）在行间距之后只剩 8dp 每侧，与 `bodySmall` 的字形
 * 重心（只占行高约 60%）叠加后仍然发紧。推导与两个候选值的对比见
 * `docs/verification/v2.5.5/probe-tray-regression.md` §3。
 *
 * ## 有意不做的事
 *
 * - **不做跑马灯**（`basicMarquee`）。托盘在播放全程常驻，跑马灯会持续排帧——
 *   与铁律 17（UI 动效不得影响播放性能）直接冲突，收益是一行 12sp 的小字能读完。
 *   过长一律 `TextOverflow.Ellipsis`。完整阅读是歌词面板的职责。
 * - **不加第四个控制按钮**。三个 48dp 按钮已经吃掉 144dp；在 360dp 窄屏上
 *   文本列只剩 136dp（360 − 56 封面 − 144 按钮 − 24 padding）。再加一个只剩 88dp，
 *   「歌名 / 作者 / 音源」三层必然塌成两行以上，与目标布局冲突。
 *   播放模式与「添加到下一首」在全屏播放器与歌曲菜单里已有入口。
 * - **间距不走 `AppShapes` / `AppMotion`**。那两个文件分别是**圆角** token 与**动效** token，
 *   都不装间距；本仓库没有 spacing token 文件。为一个消费者（托盘的那一个 `Column`）
 *   新建全局 spacing 体系只会得到一份没人用的常量表 —— 这是 v2.5.2 规则 2 的反向用法。
 *   详见 `docs/verification/v2.5.5/probe-tray-layout.md` §5（与任务书 §5.2 的一处显式偏差）。
 */
object TrayLayout {

    /**
     * 托盘总高度（dp）。**唯一的定义处**，其余三处消费者全部由它派生。
     *
     * 56 → 80 的理由见类 KDoc 的高度预算表：三层内容的排版盒合计 56dp，
     * 56dp 的托盘上下各只有 0dp 留白。
     */
    const val HEIGHT_DP = 80

    /**
     * 三行之间的垂直间距（dp）。两行小字 + 一行中字之间的分野靠留白而不是分割线
     * （Kanesumi：不用色块/线条堆结构）。
     */
    const val LINE_GAP_DP = 2

    /**
     * 托盘内封面正方形的边长（dp）。
     *
     * **与托盘等高改成定值**是有意的：托盘从 56 涨到 80 之后，若封面仍取
     * `fillMaxHeight().aspectRatio(1f)`，一个 80dp 的封面会把窄屏（360dp）的文本列
     * 再挤掉 24dp（136 → 112dp），而封面本身并不需要那 24dp。
     * 56dp 是 v1.0 起就在用的托盘封面尺寸，保持不变 ⇒ 封面相关的观感零变化。
     */
    const val COVER_SIZE_DP = 56

    /** 封面在托盘内**垂直居中**（与 [HEIGHT_DP] / [COVER_SIZE_DP] 一起决定落点中心）。 */
    fun coverHalfDp(): Float = COVER_SIZE_DP / 2f

    /**
     * v3.2.0 · P0-A：封面的**左边距**（dp）。
     *
     * ## 这个常量是为一次真机可见的错位而存在的
     *
     * v3.1.0 及以前，唯一封面 overlay 的落点中心是
     * `miniCoverCenterX = coverHalfDp()`，即**左边缘正好压在屏幕 x=0 上**。
     * 收起态（托盘）里那只是一个贴边的封面，看不出问题；但**歌词全屏模式**下，
     * 顶栏的文字块从 [topBarTextStartDp] 开始（右侧 68dp 起），
     * 于是「一个紧贴左边缘、且比文字块低 12dp 的封面」在左上角孤零零地吊着 ——
     * 真机截图证据见 `docs/verification/v3.2.0/probe-mini-cover.md`。
     *
     * 取 16dp 而不是托盘内部那个 12dp 的间隙，是因为这里要对齐的是
     * **应用级的内容边距**（`ResponsiveContent` / 搜索框 / 设置行都是 16dp），
     * 而不是组件内部两个元素之间的间隙。判据：这个值改小会让封面与
     * 页面其它内容的左边缘对不上，而 12 正是「封面与文字之间」的间距语义。
     */
    const val COVER_START_DP = 16

    /**
     * 封面与右侧文字之间的水平间隙（dp）。
     *
     * 与 `TrayLayout` 的文本列 `padding(horizontal = 12.dp)` 是**同一个数**，
     * 但语义不同：那一个是文字列自身的内边距，这一个专指「封面右边 → 文字左边」。
     * 两个数偶然相等，所以曾经被写成同一个字面量 —— 而它们一旦分头改，
     * 表现就是「托盘里的文字与全屏顶栏的文字差几 dp」，属于最难查的一类错位。
     */
    const val COVER_TEXT_GAP_DP = 12

    /**
     * v3.2.0 · P0-A：**全屏（歌词模式）顶栏的高度**（dp）。
     *
     * 这是本版新增的**第二个**几何事实，它的必要性来自一次实测错位：
     *
     * | 状态 | 顶栏容器 | 高度 | 封面应有的中心 Y |
     * |---|---|---|---|
     * | 收起（托盘） | mini bar | [HEIGHT_DP] = 80dp | `statusBar + 40dp` |
     * | 展开（歌词全屏） | 窄屏顶栏 | **56dp** | `statusBar + 28dp` |
     *
     * 而 v3.1.0 在**两种状态下都用 40dp**（`coverCenterOffsetDp()`）——
     * 于是歌词全屏模式下封面比它所在的顶栏低了整整 **12dp**：
     * 上沿在状态栏下方 12dp、下沿溢出顶栏 12dp，看起来就是「贴在状态栏左下的一个孤块」。
     * 真机实测（SM-G9209 / 1440×2560 / density 4）：
     * 顶栏 96..320px（中心 208），封面实际 142..365px（中心 253）——**差 45px ≈ 11.6dp**。
     *
     * 56 这个数原本是**三处字面量**（`PlayerCard` 的顶栏 `.height(56.dp)`、
     * `topBarBottomPx`、收起按钮叠加层），本版让它们统一引用这里 ——
     * 「顶栏多高」从此只有一个定义处。
     */
    const val TOP_BAR_HEIGHT_DP = 56

    /** 全屏顶栏内封面的中心偏移（相对状态栏下沿，dp）。 */
    fun topBarCoverCenterOffsetDp(): Float = TOP_BAR_HEIGHT_DP / 2f

    /**
     * 封面落点中心 X（dp）：左边距 + 半个封面。
     *
     * **这是 overlay 唯一的计算入口**（`PlayerCard` 不再自己写 `coverHalfDp()`），
     * 所以「加了左边距但忘了改 overlay」在结构上不可能发生。
     */
    fun coverCenterXDp(): Float = COVER_START_DP + COVER_SIZE_DP / 2f

    /**
     * 顶栏/托盘里**文字块的起始 x**（dp）= 左边距 + 封面宽 + 间隙。
     *
     * 托盘的行首占位（`Spacer(COVER_SIZE_DP)`）与窄屏顶栏的 `padding(start = …)`
     * 都必须用它，否则「封面移动了、文字没动」会表现为两者错位。
     */
    fun topBarTextStartDp(): Int = COVER_START_DP + COVER_SIZE_DP + COVER_TEXT_GAP_DP

    /**
     * 收起态唯一封面 overlay 的**落点中心**（相对托盘上沿，dp；托盘上沿已含状态栏 padding）。
     *
     * 旧实现是 `miniCoverCenterY = statusBarPx + miniCoverHalfPx`，因为那时
     * 托盘高 == 封面高（56 == 56），「托盘中心」与「封面中心」是同一个点。
     * 现在两者不等，所以必须显式取**托盘中心**：
     * `statusBar + HEIGHT/2 = statusBar + 40dp`，封面 56dp 居中后中心正是这一点。
     *
     * 公式 `+ HEIGHT/2` 在旧值上退化为 `+ 28dp`，与旧实现**逐值相同** ——
     * 这条退化由 `TrayLayoutTest` 钉住（改 HEIGHT 会同时让用例和渲染一起变，
     * 不会出现「只改了高度、封面落点忘了改」的中间态）。
     */
    fun coverCenterOffsetDp(): Float = HEIGHT_DP / 2f

    /**
     * 三行的**语义**顺序。UI 必须按这个顺序画（`TrayLayoutTest` 断言顺序），
     * 这样「谁在第一行」不靠读 `PlayerCard` 的 `Column` 相信。
     */
    enum class Row { LYRIC, TITLE, META }

    val rows: List<Row> = listOf(Row.LYRIC, Row.TITLE, Row.META)

    /**
     * 控制按钮的**清单与顺序**（铁律 19：上一首 / 播放暂停 / 下一首是核心功能，缺失属 P0）。
     *
     * 抽成枚举而不是「在 `Row` 里写三个 `MetroIconButton`」的唯一理由：
     * v2.5.5 的探针证明托盘**从来没有过上一首按钮**
     * （`git log -S "SkipPrevious" -- PlayerCard.kt` 零命中），
     * 也就是说「少一个按钮」这件事在源码 review 里看不出来。
     * 枚举 + `TrayLayoutTest` 的存在性断言让它变成一条可执行的红线。
     */
    enum class Control { PREVIOUS, PLAY_PAUSE, NEXT }

    val controls: List<Control> = listOf(Control.PREVIOUS, Control.PLAY_PAUSE, Control.NEXT)

    /** 控制区总宽（dp）：每个 `MetroIconButton` 的触摸区是 48dp（Kanesumi 的无障碍下限）。 */
    const val CONTROL_TOUCH_TARGET_DP = 48

    fun controlsWidthDp(): Int = controls.size * CONTROL_TOUCH_TARGET_DP

    /**
     * 托盘**不展示**控制按钮时占位的宽度（dp）。
     *
     * 展开态（`progress >= 0.01`）三个按钮不挂载（命中区问题，见 AGENTS.md 触摸陷阱 §4），
     * 但文本列的可用宽度必须与收起态**逐像素相同**，否则展开/收起动画会让文字重排。
     * 旧实现写死 `Spacer(width = 96.dp)`（两个按钮），本版改为由 [controls] 派生。
     */
    fun controlsPlaceholderWidthDp(): Int = controlsWidthDp()

    // ---------------------------------------------------------------- 派生量

    /** 窄屏底部导航高度（dp）。托盘贴在其上方。 */
    const val BOTTOM_NAV_HEIGHT_DP = 80

    /** 滚动内容底部预留的视觉缓冲（dp）。 */
    const val BOTTOM_INSET_BUFFER_DP = 8

    /**
     * 屏幕底部被浮层遮挡的总高度（dp）—— 所有 `LazyColumn` 的 `contentPadding(bottom)`。
     *
     * - 窄屏：底部导航 [BOTTOM_NAV_HEIGHT_DP] + 托盘 [HEIGHT_DP] + 缓冲；
     * - 宽屏：无底部导航（改用左侧 sidebar），只剩托盘 + 缓冲。
     *
     * v2.5.4 之前这里是另一个 56dp 字面量（`144.dp / 64.dp`）—— 那正是
     * 「托盘加高之后列表最后一项被盖住」的成因。现在它由 [HEIGHT_DP] 派生，
     * **不存在「改了高度忘了改预留」的可能**。
     */
    fun bottomOverlayInsetDp(isWideLayout: Boolean): Int =
        if (isWideLayout) HEIGHT_DP + BOTTOM_INSET_BUFFER_DP
        else BOTTOM_NAV_HEIGHT_DP + HEIGHT_DP + BOTTOM_INSET_BUFFER_DP
}
