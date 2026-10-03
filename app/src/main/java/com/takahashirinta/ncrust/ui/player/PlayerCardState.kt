/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：`PlayerCard` 拆分（铁律 24）时从它方法体里搬出来的**两块非 UI 代码**：
 *   1. [PlayerCardUiState] —— 全部 `remember`/`Animatable`/`derivedStateOf` 状态的持有者；
 *   2. [playerCardGeometry] —— 封面落点 / 分栏 / 可视化条的**纯几何计算**。
 *
 * ## 为什么必须搬（不是代码洁癖，是 dex 指标）
 *
 * v3.2.0 的 `PlayerCard` 是 **9092 code unit / 191 寄存器**的单个方法：真机上 ART 为它
 * 分配 45MB 编译、JIT 完成后出现 `invalid weight 0.0` 崩溃。把三个布局分支搬出去之后
 * 实测仍有 **3793 code unit**（release dex）——剩下的主体就是**状态初始化**与**几何计算**。
 * 本轮把这两块搬进本文件，`PlayerCard` 只剩「参数 + 一次性建状态 + 一次几何调用 + 画树」。
 *
 * ## 行为等价（铁律 25）：状态一个都没有换语义
 *
 * | 原来（`PlayerCard` 内） | 现在 | 等价理由 |
 * |---|---|---|
 * | `remember { mutableStateOf(x) }` | [PlayerCardUiState] 的字段 | 持有者本身由 `remember(progress)` 创建 ⇒ **同一个组合位置只有一份**，生存期与拆分前逐帧相同 |
 * | `remember { Animatable(0f) }` | 同上（`val`） | 同上；`Animatable` 不参与 Compose 快照，只是普通对象 |
 * | `remember { derivedStateOf { … } }` | 同上（`by derivedStateOf`） | 派生状态在持有者创建时构造一次；它读的仍是同一个 `progress.value`，**读取位置（`PlayerCard` 的组合期）没有变** |
 * | `with(density) { … }` 一串几何量 | [playerCardGeometry] 纯函数 | 输入输出逐一对应；每次组合重算一次（与拆分前逐帧相同），**不是** `remember` 缓存 |
 *
 * ⚠️ 有意**不**搬的两样东西：
 *  - `autoSwitchedForSong`：它是 `remember(song?.id)`，**换歌要重置** —— 放进持有者会让
 *    「每首歌只自动切一次歌词」变成「一辈子只切一次」；
 *  - `isSongSaved`：`remember(song?.id, libraryTick)` + 需要 `Context`，键语义同上。
 *
 * ## 几何计算为什么要单独抽成纯函数
 *
 * 它同时被**封面 overlay**（落点中心/尺寸）与**命中测试**（分栏边界）消费，
 * 一旦两处各算一遍就会出现「封面画在这里、点击判定在那里」。抽成纯函数之后
 * `PlayerCardGeometryTest` 可以在 JVM 上把每一档的数值钉住（含 `wideSplit` 端点）。
 */

package com.takahashirinta.ncrust.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel

/**
 * `PlayerCard` 的**全部跨重组状态**（见文件 KDoc 的等价表）。
 *
 * `@Stable`：字段要么是 Compose 快照状态、要么是不可变对象，所以 Compose 可以安全地
 * 把它当作稳定参数传给子 composable（跳过不必要的重组）。
 */
@Stable
internal class PlayerCardUiState(
    private val progress: Animatable<Float, AnimationVector1D>,
) {
    // ---- 可变状态（原来是一个个 remember { mutableStateOf }）----
    var showLyrics by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var libraryTick by mutableIntStateOf(0)
    var coverArtKey by mutableStateOf<String?>(null)
    var coverArtBitmap by mutableStateOf<Bitmap?>(null)

    /** 卡片根节点在 root 坐标系里的原点（`onGloballyPositioned` 写入）。 */
    var cardRootOrigin by mutableStateOf(Offset.Zero)

    /** 宽屏/大屏「封面区」实测中心与边长（唯一封面 overlay 的落点来源）。 */
    var wideCoverCenter by mutableStateOf(Offset.Zero)
    var wideCoverSizePx by mutableFloatStateOf(0f)

    /** 控制栏实测高度与它在卡片局部坐标里的上沿（整卡拖拽让路用）。 */
    var controlsHeightPx by mutableFloatStateOf(0f)
    var controlsTopInCardPx by mutableFloatStateOf(Float.MAX_VALUE)

    /** 展开动作触发的歌词定位计数（每次展开 +1）。 */
    var lyricLocateTrigger by mutableIntStateOf(0)

    // ---- 动画轴（原来是一个个 remember { Animatable }）----
    val lyricAnimProgress = Animatable(0f)
    val queueSlideProgress = Animatable(0f)
    val controlsCollapse = Animatable(0f)

    /** 托盘整条的交互源（`clickable` 的按压态）。 */
    val miniBarInteractionSource = MutableInteractionSource()

    // ---- 阈值门（原来是一个个 remember { derivedStateOf }）----
    // 全部只在**跨阈值那一帧**失效，动画帧零成本（v1.3.0 · B-1 的取舍）。

    /** 完全收起时才激活迷你播放栏。 */
    val miniBarEnabled by derivedStateOf { progress.value < 0.01f }

    /** 完全展开时才激活收起按钮。 */
    val dismissEnabled by derivedStateOf { progress.value > 0.99f }

    /** 折叠态不挂载展开态子树（mini bar 永远挂载）。 */
    val expandedMounted by derivedStateOf { progress.value > 0.01f }

    /** 折叠态命中区让位开关（见根 Box 的 padding/offset 注释）。 */
    val collapsedHitGate by derivedStateOf { progress.value <= 0.01f }

    /** 仅在歌词模式下歌词可交互。 */
    val lyricsEnabled by derivedStateOf {
        lyricAnimProgress.value > 0.5f && queueSlideProgress.value < 0.5f
    }

    /** 卡片基本展开(>90%)时播放器内容区才可交互。 */
    val cardExpandedForInput by derivedStateOf { progress.value > 0.9f }

    /** 控制栏是否已收起（用于挂载右下角悬浮播放键）。 */
    val controlsCollapsedForInput by derivedStateOf { controlsCollapse.value > 0.5f }

    /**
     * 面板可交互（展开 + 面板在前台）—— 根节点拖拽要为面板内部滚动让路。
     *
     * 判据与拆分前逐字相同：`(lyricsEnabled || queueSlideProgress > 0.5f) && progress > 0.7f`
     * —— 注意是 **||**：歌词在前台**或**队列已滑到位，任一成立即算面板在前台。
     */
    val isPanelInteractive by derivedStateOf {
        (lyricsEnabled || queueSlideProgress.value > 0.5f) && progress.value > 0.7f
    }
}

/**
 * 创建（并跨重组保留）[PlayerCardUiState]。
 *
 * `remember(progress)`：`progress` 是 `MainScreen` 持有的同一个 `Animatable`，正常情况下
 * 永远不变 ⇒ 持有者只创建一次。真出现换实例（例如测试里重建）时重建也**必须**重建，
 * 否则派生状态会去读一个已经没人更新的旧 `Animatable`。
 */
@Composable
internal fun rememberPlayerCardUiState(
    progress: Animatable<Float, AnimationVector1D>,
): PlayerCardUiState = remember(progress) { PlayerCardUiState(progress) }

/**
 * 卡片几何（px / Dp），全部由 [playerCardGeometry] 一次算出。
 *
 * 消费方：唯一封面 overlay（落点与缩放）、命中测试（分栏边界）、控制栏把手、
 * 可视化条与背景级波形的高度、音质选择器的高度上限、根 Box 的命中区让位。
 */
@Immutable
internal class PlayerCardGeometry(
    val isWidePlayer: Boolean,
    val bigScreenActive: Boolean,
    val usesSideCover: Boolean,
    val wideLeftFraction: Float,
    val panelBoundaryPx: Float,
    val qualityPickerMaxHeightDp: Dp,
    val isLargeScreen: Boolean,
    val orientationLandscape: Boolean,
    val visualizerSlot: Boolean,
    val visualizerHeightDp: Dp,
    val waveBackdropHeightDp: Dp,
    val coverSizePx: Float,
    val coverSizeDp: Dp,
    val miniScale: Float,
    val miniCoverCenterX: Float,
    val miniCoverCenterY: Float,
    val topBarCoverCenterY: Float,
    val largeCoverCenterX: Float,
    val largeCoverCenterY: Float,
    val boundsCenter: Float,
    val coverFloatPx: Float,
    val hitGateInsetDp: Dp,
    val topBarBottomPx: Float,
)

/**
 * 把窗口/密度/实测值算成 [PlayerCardGeometry]（**纯函数**，JVM 可单测）。
 *
 * 逐项对应拆分前 `PlayerCard` 里的同名 `val`（注释一并保留在下面），一式不改 ——
 * 唯一的形式变化是「`with(density) { … }` 的密度变成参数」。
 *
 * @param screenWidthDp `LocalConfiguration.screenWidthDp`
 * @param screenHeightDp `LocalConfiguration.screenHeightDp`（可视化条高度用的就是它，
 *   不是 `screenHeightPx` 换算回来的那个 —— 两者在车机/分屏下不相等）
 * @param smallestScreenWidthDp `LocalConfiguration.smallestScreenWidthDp`
 * @param bigScreenRequested 用户意图（`bigScreen` 形参），生效态还要叠加窗口方向
 * @param statusBarPx 状态栏高度（px）—— 由调用点从 `WindowInsets` 读出后传入
 *   （那是唯一需要 Compose 环境的一步）
 */
@Suppress("LongParameterList")
internal fun playerCardGeometry(
    density: Density,
    screenWidthPx: Float,
    screenHeightPx: Float,
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestScreenWidthDp: Int,
    orientationLandscape: Boolean,
    visualizerEnabled: Boolean,
    wideSplit: Float,
    bigScreenRequested: Boolean,
    wideCoverCenter: Offset,
    wideCoverSizePx: Float,
    statusBarPx: Float,
): PlayerCardGeometry {
    val dp24px = with(density) { 24.dp.toPx() }
    // 宽屏播放器两栏（Apple Music 式）：左封面 / 右歌词·队列。
    val isWidePlayer = screenWidthDp >= PlayerLayout.WIDE_BREAKPOINT_DP
    // ---- P1 · 大屏幕模式：第三个谓词，只让播放器读 ----
    // 全仓库另外 7 处 `screenWidthDp >= 600` 的宽屏判定一律不动：横屏时窗口宽度必然
    // >= 600dp（PCL110 实测 2800px = 800dp），把大屏模式塞进那个谓词会把首页/详情页/
    // 收藏页的布局一起改掉 —— 它们要的是"平板"，不是"横过来的手机"。
    val bigScreenActive = PlayerLayout.isBigScreenActive(
        requested = bigScreenRequested,
        orientationLandscape = orientationLandscape,
    )
    // 封面走"侧栏大图"路径（宽屏两栏 or 大屏左栏）：封面尺寸由实测的封面区决定，
    // 而不是窄屏的"整屏宽"。
    val usesSideCover = isWidePlayer || bigScreenActive
    // 左栏占整宽的比例：随 wideSplit 在 100%(单栏) 与 44%(两栏) 间过渡。窄屏恒为 1。
    val wideLeftFraction = PlayerLayout.wideLeftFraction(isWidePlayer, wideSplit)

    // 分栏**语义边界**（px）：左侧=封面/信息区，右侧=歌词·队列面板。命中测试用。
    // P1 顺手修：旧实现两处都写 `screenWidthPx / 2`，而真实边界是 0.44×宽（两栏稳定态）
    // ⇒ 44%~50% 那条窄带（真实属于歌词面板）被判成"封面区"，带内上下拖歌词会被整卡
    // 拖拽抢走。现在统一走 PlayerLayout（真实边界，且跟随 wideSplit 动画）。
    val panelBoundaryPx = if (bigScreenActive) {
        PlayerLayout.bigScreenLeftBoundaryPx(screenWidthPx)
    } else {
        PlayerLayout.splitBoundaryPx(screenWidthPx, isWidePlayer, wideSplit)
    }

    // 选择器高度上限：竖屏 400dp 够用；横屏大屏只有 ~363dp 高（PCL110 实测 1272px），
    // 400dp 的弹层会被屏幕裁掉底部档位 —— 实测第 8 档「杜比全景声」落在屏幕外、点不到。
    // 这里按窗口高的 70% 夹一下，选择器本身是 LazyColumn，放不下时可以滚。
    val qualityPickerMaxHeightDp = with(density) {
        minOf(400.dp.toPx(), screenHeightPx * 0.7f).toDp()
    }

    // v2.5.4 · D：**平板**（smallestScreenWidthDp >= 600，与方向无关）。
    // 与 isWidePlayer 不是同一个谓词：手机横屏的 screenWidthDp 也 >= 600，
    // 但它不该在宽屏两栏里长出可视化条（那会改掉 v1.8.0 以来手机横屏的形态）。
    val isLargeScreen = smallestScreenWidthDp >= PlayerLayout.LARGE_SCREEN_BREAKPOINT_DP
    // 挂载判据**只有一个落点**（PlayerLayout.visualizerSlot），A/B 矩阵写在那里的 KDoc。
    val visualizerSlot = PlayerLayout.visualizerSlot(
        enabled = visualizerEnabled,
        bigScreenActive = bigScreenActive,
        isWidePlayer = isWidePlayer,
        isLargeScreen = isLargeScreen,
        orientationLandscape = orientationLandscape,
    )
    // 高度 = 窗口高 × 11%，夹在 32~56dp：PCL110 横屏（363dp 高）得 40dp、
    // S6（480dp 高）得 53dp —— 矮屏少占、高屏多给，封面区用 weight(1f) 自动让位。
    val visualizerHeightDp = with(density) {
        PlayerLayout.visualizerHeightDp(screenHeightDp.toFloat()).dp
    }

    // v2.9.0 · B 档：背景级波形条的高度。比左栏那条（32~56dp）高一档，
    // 因为它承担的是"整块背景在流动"的观感，太薄会被读成一条装饰线。
    // 仍按窗口高夹取：PCL110 横屏可用高只有 363dp，固定 120dp 会把封面压掉一圈。
    val waveBackdropHeightDp = with(density) {
        minOf(120.dp.toPx(), screenHeightPx * 0.20f).toDp()
    }

    // 实测前用兜底尺寸，避免首帧 1px 让 Coil 按 1px 解码成纯色（重进时尤为明显）。
    val coverSizePx = if (usesSideCover) {
        if (wideCoverSizePx > 0f) wideCoverSizePx
        else PlayerLayout.coverFallbackSizePx(screenWidthPx, screenHeightPx)
    } else screenWidthPx
    val coverSizeDp = with(density) { coverSizePx.toDp() }

    // ---- v2.5.5 · C：托盘几何全部由 TrayLayout 派生（唯一落点）----
    // 托盘从 56dp 加到 80dp 时，这一组常量是「封面落点忘记跟着改」的现场：
    // 旧写法 `miniCoverHalfPx = 28.dp` 隐含了「托盘高 == 封面高」这个前提，
    // 托盘加高之后封面中心会偏上 12dp。现在中心显式取**托盘中心**
    // （`statusBar + HEIGHT/2`），且封面尺寸是独立的 56dp 定值 —— 两者不再耦合。
    // 公式在旧值（HEIGHT=COVER=56）上退化为 `statusBar + 28dp`，与旧实现逐值相同。
    val miniCoverHalfPx = with(density) { TrayLayout.coverHalfDp().dp.toPx() }
    val miniScale = miniCoverHalfPx * 2f / coverSizePx
    // v3.2.0 · P0-A：落点中心 X 由 TrayLayout 唯一决定（左边距 16dp + 半个封面）。
    // 旧写法 `miniCoverHalfPx` = 28dp ⇒ 封面左边缘压在屏幕 x=0 上（真机可见的贴边）。
    val miniCoverCenterX = with(density) { TrayLayout.coverCenterXDp().dp.toPx() }
    // v3.2.0 · P0-A：**两个中心 Y，不是一个**。
    //  · 收起态的目标容器是托盘（HEIGHT_DP = 80dp）⇒ 中心 statusBar + 40dp；
    //  · 展开态（歌词全屏）的目标容器是窄屏顶栏（TOP_BAR_HEIGHT_DP = 56dp）
    //    ⇒ 中心 statusBar + 28dp。
    // v3.1.0 两种状态都用 40dp，于是歌词全屏下封面比顶栏低 12dp（真机实测差 45px）。
    val miniCoverCenterY = statusBarPx + with(density) { TrayLayout.coverCenterOffsetDp().dp.toPx() }
    val topBarCoverCenterY =
        statusBarPx + with(density) { TrayLayout.topBarCoverCenterOffsetDp().dp.toPx() }
    val largeCoverCenterX = if (usesSideCover) wideCoverCenter.x else screenWidthPx / 2f
    val largeCoverCenterY = if (usesSideCover) wideCoverCenter.y else screenHeightPx * 0.3f + dp24px
    val boundsCenter = coverSizePx / 2f

    // v2.9.0 · A 档：封面随节拍上浮的最大位移（px）。在这里换算一次，
    // 而不是在 graphicsLayer 块里调 `dp.toPx()` —— 那个块每帧都会跑。
    val coverFloatPx = with(density) { AppMotion.COVER_FLOAT_DP.toPx() }

    // 折叠态命中区让位：根 Box 的 top padding（见根 Box 的注释）。
    val hitGateInsetDp = with(density) { statusBarPx.toDp() }
    // 歌词/队列面板可交互时，面板区域内的纵向手势归内部列表滚动。
    val topBarBottomPx = statusBarPx + with(density) { TrayLayout.TOP_BAR_HEIGHT_DP.dp.toPx() }

    return PlayerCardGeometry(
        isWidePlayer = isWidePlayer,
        bigScreenActive = bigScreenActive,
        usesSideCover = usesSideCover,
        wideLeftFraction = wideLeftFraction,
        panelBoundaryPx = panelBoundaryPx,
        qualityPickerMaxHeightDp = qualityPickerMaxHeightDp,
        isLargeScreen = isLargeScreen,
        orientationLandscape = orientationLandscape,
        visualizerSlot = visualizerSlot,
        visualizerHeightDp = visualizerHeightDp,
        waveBackdropHeightDp = waveBackdropHeightDp,
        coverSizePx = coverSizePx,
        coverSizeDp = coverSizeDp,
        miniScale = miniScale,
        miniCoverCenterX = miniCoverCenterX,
        miniCoverCenterY = miniCoverCenterY,
        topBarCoverCenterY = topBarCoverCenterY,
        largeCoverCenterX = largeCoverCenterX,
        largeCoverCenterY = largeCoverCenterY,
        boundsCenter = boundsCenter,
        coverFloatPx = coverFloatPx,
        hitGateInsetDp = hitGateInsetDp,
        topBarBottomPx = topBarBottomPx,
    )
}

/**
 * v3.2.1 · P0：`PlayerCard` 的 **15 个回调**打成一包（铁律 14「上帝类参数数量监控」的正向用法）。
 *
 * ## 为什么必须打包（实测数字，不是审美）
 *
 * `PlayerCard` 原本有 **25 个形参、其中 20 个带默认值**。Compose 编译器要为每个形参生成
 * `$changed` 位运算，为每个默认值生成「掩码判断 + 默认表达式」分支。用同形状的空实现实测
 * （debug dex，同一台机器同一次构建）：
 *
 * | 形状 | code unit |
 * |---|---|
 * | 25 参数 + 20 个默认值 | 1366 |
 * | 25 参数、**无**默认值 | 426 |
 * | 11 参数（回调打包）+ 6 个默认值 | 595 |
 *
 * 也就是说**光是签名**就占掉了 1366 code unit —— 这既是 `PlayerCard` 长期压不到
 * 2000 以下的原因，也正是 v2.0.0 · HF1（dex 参数寄存器上限）那条教训的同一形状。
 *
 * ## 改的是签名，不是行为
 *
 * 字段名与 `PlayerCard` 原来的形参名**逐一相同**，默认值也逐一相同（`onPlayPause` /
 * `onDismiss` 是必填，其余 13 个为空实现）—— 所以调用点只是把「摊开的 15 个实参」
 * 换成「一个对象」，回调的调用时机与次数不变。
 *
 * `@Immutable`：字段全是函数类型（Compose 视为稳定），所以 `PlayerCard` 仍然可以跳过
 * 不必要的重组 —— 前提是调用点用 `remember(...)` 缓存这个对象（`PlayerCardOverlay` 就这么做）。
 */
@Immutable
internal class PlayerCardCallbacks(
    val onPlayPause: () -> Unit,
    val onDismiss: () -> Unit,
    val onPlayPrevious: () -> Unit = {},
    val onPlayNext: () -> Unit = {},
    val onRemoveFromQueue: (Int) -> Unit = {},
    val onPlayFromQueue: (Int) -> Unit = {},
    val onMoveInQueue: (Int, Int) -> Unit = { _, _ -> },
    val onTogglePlayMode: () -> Unit = {},
    val onPlayNothing: () -> Unit = {},
    val onSongInfoClick: () -> Unit = {},
    val onArtistClick: (SongItem) -> Unit = {},
    val onClearQueue: () -> Unit = {},
    val onSavePlaylist: () -> Unit = {},
    val onToggleBigScreen: () -> Unit = {},
    val onToggleAutoRotate: () -> Unit = {},
)
