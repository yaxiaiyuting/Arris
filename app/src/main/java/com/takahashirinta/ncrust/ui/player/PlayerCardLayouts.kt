/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：从 `PlayerCard` 拆出的**三个互斥布局分支**。
 *
 * ## 为什么必须拆（铁律 24）
 *
 * v3.2.0 的 `PlayerCard` 是一个 **9092 code unit / 191 寄存器**的方法（release dex 实测），
 * 真机上 ART 为它分配 45MB 编译、JIT 完成后出现 `invalid weight 0.0` 崩溃。
 * 三个布局分支在**运行期互斥**（`when` 只命中一个），却全部被编译进同一个方法体 ——
 * 这就是巨人症的来源，也是本次拆分的维度（见 `docs/verification/v3.2.1/probe-split-plan.md`）。
 *
 * ## 行为等价的硬约束（铁律 25）
 *
 * 1. **只搬代码，不改语义**：每个分支的节点顺序、`Modifier` 链、`weight` 实参、
 *    回调顺序逐行保持原样（对照 v3.2.0 的 `PlayerCard.kt` 1105–1527 行）。
 * 2. **`weight` 必须留在它自己的 `Row`/`Column` 内**：三个分支各自整块搬移，
 *    所以 `RowScope`/`ColumnScope` 的归属没有一处被改变。
 * 3. **窄屏分支是 `ColumnScope` 扩展**：它在原实现里是外层 `Column` 的**直接子节点**
 *    （5 个同级 Box），不能包一层 `Column`（那会多一个布局节点）。
 * 4. **状态一个都不搬**：封面落点（`wideCoverCenter`/`wideCoverSizePx`）与控制栏实测
 *    （`controlsHeightPx`/`controlsTopInCardPx`）仍然提升在 `PlayerCard`，
 *    这里只通过回调把实测值交回去 —— 写这两个状态的时机与次数与拆分前逐帧相同。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

// ─────────────────────────────────────────────────────────────────────────────
// 分支 1：大屏幕模式（横屏桌面播放器布局）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `bigScreenActive` 分支（原 `PlayerCard.kt` 1105–1244）。
 *
 * 左栏 = 大封面（尽量占满左栏可用高度，保持正方形）+ 歌名/作者 + 音质 chip + 自动旋转；
 * 右栏 = 歌词·队列面板（复用同一个面板实现）+ 底部扁平控制条。
 *
 * 为什么 transport 不放左栏：手机横屏可用高只有 ~300dp（PCL110 实测 1272px），
 * 封面会被挤到 ~170dp、比竖屏还小；控制条移到右栏底部后左栏只剩一条 ~44dp 的信息行。
 */
@Composable
internal fun PlayerCardBigScreenLayout(
    song: SongItem,
    geo: PlayerCardGeometry,
    ui: PlayerCardUiState,
    strings: Strings,
    progress: Animatable<Float, AnimationVector1D>,
    playerViewModel: PlayerViewModel,
    autoRotate: Boolean,
    onToggleAutoRotate: () -> Unit,
    onSongInfoClick: () -> Unit,
    onCoverRegionMeasured: (center: Offset, sizePx: Float) -> Unit,
    playerPanels: @Composable (Modifier) -> Unit,
    playerControls: @Composable () -> Unit,
) {
    val qualityPickerMaxHeightDp = geo.qualityPickerMaxHeightDp
    val visualizerSlot = geo.visualizerSlot
    val visualizerHeightDp = geo.visualizerHeightDp
    val cardRootOrigin = ui.cardRootOrigin
    Row(
        modifier = Modifier
            .fillMaxSize()
            // 外层 Column 已经吃过 systemBarsPadding()（横屏时系统栏贴在**左右两侧**），
            // 这里再补 displayCutout —— 嵌套的 windowInsetsPadding 会自动扣掉父级已消费的
            // inset（Compose InsetsPaddingModifier 的 exclude 语义），所以挖孔与同侧
            // 状态栏等宽（PCL110 实测都是 141px）时不重复留白，挖孔更宽（部分 ROM）时
            // 也不会被压住。
            // 注：Kanesumi 的 rememberMetroInsets() 表达不了横屏的左右系统栏 ——
            // MetroInsets.statusBar/navigationBar 只有 calculateTopPadding /
            // calculateBottomPadding 两条边（Kanesumi MetroInsets.kt:57-63），
            // 故此处用 displayCutout。
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        // ===== 左栏 =====
        Column(
            modifier = Modifier
                .weight(PlayerLayout.BIG_SCREEN_LEFT_FRACTION)
                .fillMaxHeight()
        ) {
            // 封面区：只作为「唯一封面 overlay」的落点参考（与宽屏两栏同一机制，
            // 绝不在这里放第二个封面）。正方形边长 = min(区宽, 区高)
            // ⇒ 封面总是"尽量占满左栏可用高度"。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInRoot()
                        onCoverRegionMeasured(
                            b.center - cardRootOrigin,
                            PlayerLayout.squareCoverSizePx(b.width, b.height),
                        )
                    }
            )
            // v1.8.0 · T3：音频可视化条（**封面下、歌名/作者上**）。
            // 高度按窗口高动态算：PCL110 横屏只有 363dp 可用高，固定 48dp 会把封面
            // 压掉一整圈；S6（480dp）则给足 53dp。封面区是 weight(1f)，自己吸收这段高度。
            //
            // v2.5.4 · D：挂载判据收敛进 PlayerLayout.visualizerSlot，
            // 大屏分支与宽屏两栏分支**共用同一份实现**，避免两处各写一份 if。
            if (visualizerSlot) {
                AudioVisualizerSlot(heightDp = visualizerHeightDp)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 与竖屏/宽屏一致：点歌名上拉「转到歌手/转到专辑」菜单。
                        .clickable { onSongInfoClick() }
                ) {
                    MetroText(
                        song.name,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // v2.1.0 · F：音源角标（大屏左栏，两个音源都标）。
                    ArtistLineWithSource(
                        song = song,
                        color = LocalMetroColors.current.primary,
                        style = LocalMetroTypography.current.bodyMedium,
                        badgeStyle = LocalMetroTypography.current.bodySmall
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 音质：就地切换。走设置页那条现成通道
                // （PlayerViewModel.setQualityPreference → 按当前网络写 prefs，
                // 再走 onQualityPreferenceChanged：只改偏好、档位真变了才重取链），
                // 不再是"点一下跳设置页"。
                // v1.8.0 · T2：改用与竖屏 / 宽屏共用的 PlayerQualityChip。
                // 高亮的是**偏好档位**（打开那一刻现读）而不是实际档位 ——
                // 实际档位可能因版权/设备被降级，选择器要反映"我选的是哪档"。
                PlayerQualityChip(
                    qualityIndexFlow = playerViewModel.currentQualityIndex,
                    qualityStatusFlow = playerViewModel.qualityStatus,
                    options = strings.qualityOptions,
                    preferredIndexProvider = {
                        playerViewModel.currentQualityPreferenceIndex()
                    },
                    onSelect = { playerViewModel.setQualityPreference(it) },
                    maxHeightDp = qualityPickerMaxHeightDp,
                    horizontalAlignment = Alignment.End,
                )
                // v1.8.0 · T4：大屏里的自动旋转开关。放在左栏信息行（音质 chip 右侧）
                // 而不是控制条：横向控制条是"左组贴左 + 传输组居中 + 右组贴右"的三段式，
                // 左组已经 3 个按钮，再加一个会顶到居中的传输组（P1 实测过这个重叠）。
                RotationToggleButton(
                    autoRotate = autoRotate,
                    onToggle = onToggleAutoRotate,
                    size = 40.dp,
                    iconSize = 22.dp,
                    contentDescription =
                        if (autoRotate) strings.autoRotateOn else strings.autoRotateOff,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        // ===== 右栏 =====
        Column(
            modifier = Modifier
                .weight(1f - PlayerLayout.BIG_SCREEN_LEFT_FRACTION)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                playerPanels(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                )
            }
            playerControls()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 分支 2：宽屏两栏（isWidePlayer）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `isWidePlayer` 分支（原 `PlayerCard.kt` 1245–1336）。
 *
 * 宽屏：`Row` 两栏，区域化布局（weight 分区，无绝对定位 / 魔法数字）。
 * 左栏 = 封面区（weight 撑满剩余）+ 歌名 + 控件；右栏 = 歌词·队列占满整轴。
 *
 * ## v3.2.1 · A：分栏比例的**对称正下限**
 *
 * 左栏与右栏是**对称位置**，两者的权重都必须 `> 0`（`Modifier.weight` 内部是
 * `require(weight > 0.0)`，收到 0 或 NaN 会直接崩 App）。v3.2.0 只有右栏有下限保护，
 * 本轮把两侧都收敛到 [PlayerLayout.MIN_SPLIT_FRACTION]。
 */
@Composable
internal fun PlayerCardWideLayout(
    song: SongItem,
    geo: PlayerCardGeometry,
    ui: PlayerCardUiState,
    progress: Animatable<Float, AnimationVector1D>,
    onCoverRegionMeasured: (center: Offset, sizePx: Float) -> Unit,
    onSongInfoClick: () -> Unit,
    playerPanels: @Composable (Modifier) -> Unit,
    playerControls: @Composable () -> Unit,
) {
    // v3.2.1 · P0：几何/状态改成**整包传参**（`geo` / `ui`）—— 每个标量形参在调用点
    // 都要付一次 `$changed` 位运算与实参装载，包成对象后只有两个参数。
    val wideLeftFraction = geo.wideLeftFraction
    val visualizerSlot = geo.visualizerSlot
    val visualizerHeightDp = geo.visualizerHeightDp
    val cardRootOrigin = ui.cardRootOrigin
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                // v3.2.1 · A（铁律 26）：与右栏**对称**的正下限。
                // 出口处（PlayerLayout.wideLeftFraction）已经夹过一次，
                // 这里再夹一次是防「将来新增调用点绕过那个出口」——
                // 两次都走同一个常量 MIN_SPLIT_FRACTION，不存在漂移。
                .weight(wideLeftFraction.coerceAtLeast(PlayerLayout.MIN_SPLIT_FRACTION))
                .fillMaxHeight()
        ) {
            // 封面区：只作为"唯一封面 overlay"在宽屏的落点参考——实测其中心与
            // 尺寸，交给 overlay 封面定位。这里不再放第二个封面，保证全屏 ↔
            // miniBar 始终是同一个 cover。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        val b = coords.boundsInRoot()
                        // 封面在左栏封面区内居中（不贴边，视觉更和谐）。
                        onCoverRegionMeasured(b.center - cardRootOrigin, minOf(b.width, b.height))
                    }
            )
            // v2.5.4 · D：**平板横屏**的音频可视化条就挂在这里。
            //
            // 位置与横屏大屏左栏**逐像素同款**（封面下、歌名/作者上），
            // 用同一个 AudioVisualizerSlot，高度也走同一条算式 ——
            // 两处若各写一份，「某一边高度不同/某一边忘了挂」必然发生。
            //
            // 为什么是这一格：平板横屏走的是**宽屏两栏**（isWidePlayer），
            // 而不是 bigScreenActive（那是用户点 ⤢ 之后的横屏桌面布局）。
            // v1.8.0 把可视化只挂在后者里，于是平板横屏永远看不到它 ——
            // 而平板上连 ⤢ 入口都没有（横屏控制条变体里没有那个按钮）。
            // 挂载判据见 PlayerLayout.visualizerSlot 的 A/B 矩阵：
            // 只有「平板 + 横屏」这一格由无变有，其余五格不动。
            if (visualizerSlot) {
                AudioVisualizerSlot(heightDp = visualizerHeightDp)
            }
            // 歌名 / 歌手 + 控件：限宽居中，与封面成组（单栏时不再铺满整宽显得散）。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                        .clickable { onSongInfoClick() }
                ) {
                    MetroText(
                        song.name,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // v2.1.0 · F：音源角标（宽屏左栏，两个音源都标）。
                    ArtistLineWithSource(
                        song = song,
                        color = LocalMetroColors.current.primary,
                        style = LocalMetroTypography.current.bodyLarge,
                        badgeStyle = LocalMetroTypography.current.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                ) {
                    playerControls()
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // 右栏常挂载（weight 给极小下限，wideSplit=0 时宽度趋 0 但不卸载）：
        // 避免开关歌词时面板反复 mount/unmount 导致歌词状态丢失/不再重绘。
        Box(
            modifier = Modifier
                // v3.2.1 · A（铁律 26）：左栏与右栏共用同一个下限出口函数，
                // 两侧都不再出现 `0.0001f` 字面量。
                .weight(PlayerLayout.wideRightFraction(wideLeftFraction))
                .fillMaxHeight()
        ) {
            playerPanels(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 分支 3：窄屏（竖屏）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `else`（窄屏）分支（原 `PlayerCard.kt` 1337–1527）。
 *
 * ⚠️ 这是 **`ColumnScope` 扩展函数**，不是普通 composable：原实现里这 5 个 `Box`
 * 是外层 `Column` 的**直接子节点**（其中面板区用 `Modifier.weight(1f)` 吃掉剩余高度）。
 * 包一层 `Column` 会多出一个布局节点，`weight` 的分配对象也随之改变 ——
 * 那不是「逐字节等价」。
 */
@Composable
internal fun ColumnScope.PlayerCardNarrowLayout(
    song: SongItem,
    geo: PlayerCardGeometry,
    ui: PlayerCardUiState,
    strings: Strings,
    progress: Animatable<Float, AnimationVector1D>,
    isPlaying: Boolean,
    haptic: HapticFeedback,
    gestureNavLiftDp: Dp,
    controlsCollapseDrag: () -> Modifier,
    onToggleControlsCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSongInfoClick: () -> Unit,
    onControlsMeasured: (heightPx: Float, topInCardPx: Float) -> Unit,
    playerPanels: @Composable (Modifier) -> Unit,
    playerControls: @Composable () -> Unit,
) {
    val lyricAnimProgress = ui.lyricAnimProgress
    val controlsCollapse = ui.controlsCollapse
    val controlsCollapsedForInput = ui.controlsCollapsedForInput
    val cardRootOrigin = ui.cardRootOrigin
    // 顶部标题栏
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // v3.2.0 · P0-A：高度与文字起始位都走 TrayLayout 的唯一来源 ——
            // 封面 overlay 的落点就是用这两个数算出来的，写死字面量必然漂移。
            .height(TrayLayout.TOP_BAR_HEIGHT_DP.dp)
            .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
            .padding(start = TrayLayout.topBarTextStartDp().dp, end = 56.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = lyricAnimProgress.value }
                // 歌名区域可点: 上拉"转到歌手/转到专辑"菜单(类 Apple Music)
                .clickable { onSongInfoClick() }
        ) {
            MetroText(
                song.name,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(
                    iterations = Int.MAX_VALUE,
                    animationMode = MarqueeAnimationMode.Immediately,
                    initialDelayMillis = 2000,
                    repeatDelayMillis = 2500,
                    velocity = 48.dp
                )
            )
            // v2.1.0 · F：音源角标（窄屏顶栏，两个音源都标）。同一行的另一段
            // 是 basicMarquee 的歌名，这里不参与跑马灯。
            ArtistLineWithSource(
                song = song,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodyMedium,
                badgeStyle = LocalMetroTypography.current.bodySmall
            )
        }
    }
    // 面板区（吃掉剩余高度）
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
    ) {
        playerPanels(Modifier.fillMaxSize())
        // 收起态悬浮播放键（v1.4.0 · A）：只在控制栏真的收起后挂载 ——
        // alpha=0 却常挂载的按钮会变成一块"摸不着的命中区"（见 B-1 的教训）。
        // 点 = 播放/暂停；向下拖 = 把控制栏拉回来（复用同一条收起进度轴）。
        if (controlsCollapsedForInput) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(56.dp)
                    .background(LocalMetroColors.current.surfaceVariant)
                    .then(controlsCollapseDrag())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = 30.dp
                )
            }
        }
        // 大封面模式下的曲名/歌手信息：overlay 在内容区底部，不占高度。
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .graphicsLayer {
                    alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) *
                            (1f - lyricAnimProgress.value)
                }
                // 歌名区域可点: 上拉"转到歌手/转到专辑"菜单(类 Apple Music)
                .clickable { onSongInfoClick() }
        ) {
            MetroText(
                song.name,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // v2.1.0 · F：音源角标（窄屏大封面 overlay，两个音源都标）。
            ArtistLineWithSource(
                song = song,
                color = LocalMetroColors.current.primary,
                style = LocalMetroTypography.current.bodyLarge,
                badgeStyle = LocalMetroTypography.current.bodySmall
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    // 底部控制栏（可收起）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { c ->
                // 只在展开态实测（收起后高度会被收成 0，别把度量值也带偏）：
                // 高度决定"从布局里收走多少"，上沿决定整卡拖拽在哪让路。
                if (controlsCollapse.value < 0.01f) {
                    onControlsMeasured(
                        c.size.height.toFloat(),
                        c.boundsInRoot().top - cardRootOrigin.y,
                    )
                }
            }
            // 收起：layout 阶段把高度收走（面板顺势长高），内容用 graphicsLayer
            // 平移出屏幕 —— 全程零 recomposition，只有这一棵子树重新布局。
            .collapsibleHeight(controlsCollapse)
            .then(controlsCollapseDrag())
            .graphicsLayer {
                alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
                translationY = controlsCollapse.value * size.height
            }
    ) {
        playerControls()
    }
    // 控制栏把手（v1.4.2）：常驻在内容区最底部，**收起态也可见**。
    // 用户反馈：「划下去就划不上来了」—— 收起后歌词面板占满全屏，歌词自己的
    // 点击/滚动都在抢手势，只靠"右下角悬浮键下滑"恢复既难发现也难命中。
    // 这里给一个明确的小横条：向上拖=收起、向下拖=恢复、点一下=切换。
    // 它挂在 Column 里（在最底部、系统栏之上），控制栏收起时不会被一起带走。
    // 触摸契约（v1.5.0 · C2）：视觉 40×3dp 不变；
    //  - 拖拽带 = 全宽 × 24dp（外层）—— 保持 v1.4.2 的手感，宽度**故意不缩到 48dp**：
    //    1440px 宽的 S6 上 48dp 只有 192px，用户抱怨过「划下去就划不上来」；
    //  - 点按命中盒 = 居中 48×24dp（内层显式声明）—— 满足 48dp 最小触摸目标，
    //    并承载无障碍语义。两者取并集，命中区只增不减。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 手势导航机型要把整条拖拽带抬离系统「回到桌面」手势带：
            // 实测（PCL110 / Android 16）从屏幕底部往上 0~13dp 起手的上滑
            // 100% 被判成系统手势，28dp 起手才会交给应用；而这一段**无法**用
            // systemGestureExclusion 排除（排除矩形确实注册进了
            // mSystemGestureExclusion，系统照样吞掉）。所以按导航模式把
            // 拖拽带整体上抬 32dp —— 这样带内任意一点起手都在安全区。
            // 三键导航 / API < 29（无 navigation_mode 设置项）不抬，行为不变。
            .padding(bottom = gestureNavLiftDp)
            .height(24.dp)
            // v1.5.1 · B —— 全面屏手势冲突：把手在屏幕**最底边**（卡片是
            // fillMaxSize，覆盖到系统手势区），Android 10+ 的手势导航会把从
            // 这里起手的上滑当成"回到桌面"抢走，把手于是只能点按、拖不动。
            //
            // 这里声明系统手势排除区（API 29+ 生效、低版本自动忽略）。
            // 实测（PCL110 / Android 16）：排除矩形确实注册进了 dumpsys 的
            // mSystemGestureExclusion，**但系统照样吞掉底部 0~13dp 起手的上滑**
            // —— 真正解决问题的是上面那个「按导航模式上抬 32dp」，这一句是配合
            // （对左右边缘与部分 ROM 仍然有效）。
            //
            // 只排除**这一条**：系统在同一屏幕边缘只认最靠上的那一个排除矩形，
            // 若把收起态那个悬浮播放键也一起排除，两个矩形里只有更靠上的悬浮键
            // 会被采信，把手反而失效。宽 × 24dp 远小于系统允许的上限（200dp）。
            .systemGestureExclusion()
            .then(controlsCollapseDrag()),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggleControlsCollapse() }
                // 此前把手对无障碍服务完全不可见：TalkBack 用户把控制栏收起后
                // 没有任何办法把它拿回来。
                .semantics { contentDescription = strings.controlsHandleLabel },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 3.dp)
                    .background(
                        LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.55f)
                    )
            )
        }
    }
}
