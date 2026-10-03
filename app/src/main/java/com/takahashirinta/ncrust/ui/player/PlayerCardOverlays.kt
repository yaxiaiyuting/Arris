/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：从 `PlayerCard` 拆出的**两个叠加层**（唯一封面 overlay / 收起按钮）。
 *
 * 两者都在三个布局分支**之外**（与分支正交），所以按「叠加层」维度单独成文件。
 *
 * 行为等价的硬约束（铁律 25）：
 *  - 封面 overlay 的 `graphicsLayer` 逐行照搬：`scaleX/Y`、`translationX/Y`、
 *    `transformOrigin`、`rotationY/X`、`cameraDistance` 的计算顺序与表达式一处不改；
 *  - 唯一封面叠加层的三条落点（大图中心 / 托盘中心 / 顶栏中心）全部由调用点传入 ——
 *    它们由 `TrayLayout` 派生，这里不再算第二遍（v3.2.0 · P0-A 的教训）；
 *  - `StableCover` 的 `clip`/`border` 顺序保持「变换在外、裁切在内」。
 */

package com.takahashirinta.ncrust.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.player.motion.MotionClock
import com.takahashirinta.ncrust.ui.player.motion.MotionEffects
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors

/**
 * 唯一封面叠加层（原 `PlayerCard.kt` 1786–1876）：全屏 ↔ miniBar 共用这一个 cover，
 * 随 `progress` 平滑位移/缩放。窄屏大图 = 整屏宽居中；宽屏/大屏大图 = 左栏封面区实测中心与尺寸。
 *
 * 所有落点（`largeCoverCenterX/Y`、`miniCoverCenterX/Y`、`topBarCoverCenterY`、
 * `miniScale`、`boundsCenter`）都由调用点算好传进来 —— 它们来自 `TrayLayout` 与实测，
 * 在这个文件里再算一遍就是第二份事实来源。
 */
@Composable
internal fun PlayerCardCoverOverlay(
    song: SongItem,
    progress: Animatable<Float, AnimationVector1D>,
    lyricAnimProgress: Animatable<Float, AnimationVector1D>,
    motion: MotionEffects,
    usesSideCover: Boolean,
    coverSizeDp: Dp,
    miniScale: Float,
    miniCoverCenterX: Float,
    miniCoverCenterY: Float,
    topBarCoverCenterY: Float,
    largeCoverCenterX: Float,
    largeCoverCenterY: Float,
    boundsCenter: Float,
    coverFloatPx: Float,
    onCoverBitmap: (key: String?, bitmap: Bitmap?) -> Unit,
) {
    val density = LocalDensity.current
    StableCover(
        model = CoverUrls.large(song.album?.picUrl),
        contentDescription = null,
        placeholderColor = LocalMetroColors.current.surfaceVariant,
        // v2.9.0 · A/C 档：封面浮起阴影 / 随节拍微浮动 / 3D 旋转 / 切歌淡入。
        // 全部由 `motion` 这一个能力位对象驱动，关掉时这三行的效果与 v2.8.0 逐像素一致。
        shadowElevation = if (motion.coverElevation) AppMotion.COVER_SHADOW_ELEVATION_DP else null,
        crossfadeEnabled = motion.coverTransition,
        onCoverBitmap = onCoverBitmap,
        modifier = Modifier
            .then(
                if (usesSideCover) Modifier.size(coverSizeDp)
                else Modifier.fillMaxWidth().aspectRatio(1f)
            )
            .graphicsLayer {
                val p = progress.value
                val normalizedP = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
                // v2.9.0 · B/C 档：随节拍的量。**只在 graphicsLayer 块里读**
                // （`MotionClock.generation` 是一个 Compose 状态，读它只让这一层失效，
                // 不触发任何重组 —— 与播放器整体「GPU 零重组」的原则一致）。
                // 静态时 pulse() == 0f ⇒ 下面三项都恰好是"没有效果"的值。
                // v3.2.0：A 档的「封面随节拍浮动」改用**自己的**能力位 `coverFloat`
                // （它已经被收窄到精致档）。上一版这里读的是 `coverElevation`,
                // 而那个位在简洁档恒为真 ⇒ 简洁档封面照样跟着鼓点上下浮 2dp
                // （P0-B 的根因，证据见 docs/verification/v3.2.0/probe-ui-jitter.md §6）。
                val beatPulse = if (
                    motion.coverFloat || motion.beatPulse || motion.cover3d
                ) {
                    MotionClock.generation
                    MotionClock.pulse()
                } else 0f
                // 侧栏布局（宽屏两栏 / 大屏左栏）封面恒为大图（缩到 mini 是窄屏
                // "大封面↔歌词"切换的语义）；它随分栏进度在左栏与居中之间平滑移动。
                val lyricAnimValue = if (usesSideCover) 0f else lyricAnimProgress.value

                val targetCenterX = largeCoverCenterX + lyricAnimValue * (miniCoverCenterX - largeCoverCenterX)
                // v3.2.0 · P0-A：歌词全屏时封面缩到的是**顶栏**的中心（56dp 容器），
                // 不是托盘的中心（80dp 容器）。两者在展开态相差 12dp ——
                // 用错的那一个会让封面挂在顶栏下沿之外（真机截图可见）。
                val shrinkCenterY = topBarCoverCenterY
                val targetCenterY = largeCoverCenterY + lyricAnimValue * (shrinkCenterY - largeCoverCenterY)
                val targetScale = miniScale + (1f - lyricAnimValue) * (1f - miniScale)

                val currentCenterX = miniCoverCenterX + normalizedP * (targetCenterX - miniCoverCenterX)
                val currentCenterY = miniCoverCenterY + normalizedP * (targetCenterY - miniCoverCenterY)
                val currentBaseScale = miniScale + normalizedP * (targetScale - miniScale)

                scaleX = currentBaseScale
                scaleY = currentBaseScale
                translationX = currentCenterX - boundsCenter
                // A 档（精致起）：随节拍**上浮**最多 ±2dp（任务书 §4.3）。用位移而不是缩放：
                // 缩放会与上面那条展开/收起动画的 scale 叠加，出问题时无法归因。
                val floatPx = if (motion.coverFloat) {
                    beatPulse * coverFloatPx
                } else 0f
                translationY = currentCenterY - boundsCenter - floatPx
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                // C 档：3D 旋转（竖屏为主）。角度很小（≤6°），读起来是"封面被推了一下"，
                // 不是"封面在转圈"；相机距离跟着 density 走，避免高分屏上透视夸张。
                if (motion.cover3d) {
                    rotationY = beatPulse * AppMotion.COVER_3D_DEGREES
                    rotationX = -beatPulse * AppMotion.COVER_3D_DEGREES * 0.4f
                    cameraDistance = AppMotion.COVER_3D_CAMERA_DISTANCE_FACTOR * density.density
                }
            },
        contentScale = ContentScale.Crop,
        // v2.5.0 · B：大封面 = AppShapes.large(16dp) + 1dp outlineVariant 描边。
        // 描边色用 MetroColors.divider —— 它就是 NcrustColors.outlineVariant
        // 的桥接值（见 NcrustColors.toMetroColors），本文件已经只读 MetroColors，
        // 不为了一个颜色再引第二个主题源进来。
        shape = AppShapes.large,
        frameColor = LocalMetroColors.current.divider,
    )
}

/**
 * 收起按钮叠加层（原 `PlayerCard.kt` 1878–1898）：z 序最高，保证触摸事件不被任何下层元素拦截。
 *
 * P1：大屏幕模式下**整层不挂载**（调用点负责）：
 *  ① 大屏没有"收起卡片"这个动作（出口是同一按钮 / 返回键 / 转回竖屏），
 *     留一个收起键只会让用户掉进"横屏 + 卡片收起"的怪状态；
 *  ② 它是全宽 56dp + CenterEnd 的叠加层，实测与右栏歌词面板右上角的 A-/A+
 *     字号按钮**命中区重叠**（PCL110：A+ 2569..2723px，收起键 2604..2772px），
 *     而它在 z 序更上 ⇒ 点 A+ 的右半边会被它抢走并收起卡片。
 */
@Composable
internal fun PlayerCardDismissButton(
    progress: Animatable<Float, AnimationVector1D>,
    dismissEnabled: Boolean,
    strings: Strings,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // v3.2.0 · P0-A：与顶栏同高 —— 收起键的垂直中心因此与封面、
            // 与歌名文字块三者在同一条水平线上（此前它是 56dp 的字面量，
            // 与顶栏的 56dp 是两份定义，改了任何一处都会错开）。
            .height(TrayLayout.TOP_BAR_HEIGHT_DP.dp)
            .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
            .padding(end = 8.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        if (dismissEnabled) {
            MetroIconButton(onClick = onDismiss) {
                MetroIcon(
                    Icons.Default.KeyboardArrowDown,
                    strings.collapsePlayer,
                    tint = LocalMetroColors.current.onBackground
                )
            }
        }
    }
}

/**
 * v3.2.1 · P0：卡片根 `Box` 的**三个叠加层**（托盘 / 唯一封面 / 收起键）打成一个入口。
 *
 * ## 为什么不是「三个调用各写一遍」
 *
 * 它们的实参一共 34 个（外加 3 个闭包）。在调用点，Compose 编译器要为**每一个**实参
 * 生成 `$changed` 位运算与装载指令 —— 这是 `PlayerCard` 方法体里最后一块可搬走的大头。
 * 打成一个入口 + 两个**状态/几何整包**（[PlayerCardUiState] / [PlayerCardGeometry]）之后，
 * 调用点只剩 12 个实参，而三个叠加层的挂载判据（`hasSong` / `bigScreenActive`）
 * 与拆分前逐字相同。
 *
 * ## 顺序即 z 序（拆之前与拆之后一致）
 *
 * 托盘 → 唯一封面 overlay → 收起按钮（最后 = z 序最高）。
 */
@Composable
internal fun BoxScope.PlayerCardOverlayLayers(
    song: SongItem?,
    hasSong: Boolean,
    isPlaying: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    motion: MotionEffects,
    strings: Strings,
    haptic: HapticFeedback,
    callbacks: PlayerCardCallbacks,
    playerViewModel: PlayerViewModel,
    ui: PlayerCardUiState,
    geo: PlayerCardGeometry,
    coroutineScope: CoroutineScope,
) {
    // 托盘的两个点击回调仍在这里构造（动画参数只有一份）：
    //  · onExpand        —— 整条托盘：展开播放器；
    //  · onExpandToLyrics —— 点第一行歌词：展开**并直接看歌词**。
    PlayerCardTray(
        song = song,
        hasSong = hasSong,
        isPlaying = isPlaying,
        miniBarEnabled = ui.miniBarEnabled,
        miniBarInteractionSource = ui.miniBarInteractionSource,
        progress = progress,
        strings = strings,
        haptic = haptic,
        lyricsFlow = playerViewModel.lyrics,
        positionFlow = playerViewModel.currentPosition,
        onExpand = {
            coroutineScope.launch {
                progress.animateTo(
                    1f,
                    tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                )
            }
        },
        onExpandToLyrics = {
            coroutineScope.launch {
                progress.animateTo(
                    1f,
                    tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                )
            }
            ui.showQueue = false
            ui.showLyrics = true
        },
        onPlayPrevious = callbacks.onPlayPrevious,
        onPlayPause = callbacks.onPlayPause,
        onPlayNext = callbacks.onPlayNext,
        onPlayNothing = callbacks.onPlayNothing,
        onArtistClick = callbacks.onArtistClick,
    )

    // 唯一封面叠加层：全屏 ↔ miniBar 共用这一个 cover，随 progress 平滑位移/缩放。
    // 窄屏大图=整屏宽居中；宽屏大图=左栏封面区实测中心与尺寸。
    if (hasSong) {
        PlayerCardCoverOverlay(
            song = song!!,
            progress = progress,
            lyricAnimProgress = ui.lyricAnimProgress,
            motion = motion,
            usesSideCover = geo.usesSideCover,
            coverSizeDp = geo.coverSizeDp,
            miniScale = geo.miniScale,
            miniCoverCenterX = geo.miniCoverCenterX,
            miniCoverCenterY = geo.miniCoverCenterY,
            topBarCoverCenterY = geo.topBarCoverCenterY,
            largeCoverCenterX = geo.largeCoverCenterX,
            largeCoverCenterY = geo.largeCoverCenterY,
            boundsCenter = geo.boundsCenter,
            coverFloatPx = geo.coverFloatPx,
            onCoverBitmap = { key, bitmap ->
                // 只在真的换了图/键时写 state（这个回调会在每次 Coil 状态变化时被调到，
                // 无条件赋值会让每次状态变化都触发一次背景层重组）。
                if (key != ui.coverArtKey || bitmap !== ui.coverArtBitmap) {
                    ui.coverArtKey = key
                    ui.coverArtBitmap = bitmap
                }
            },
        )
    }

    // 收起按钮叠加层：z 序最高，保证触摸事件不被任何下层元素拦截。
    // 大屏幕模式下整层不挂载（判据在下面这一行，理由见 PlayerCardDismissButton 的 KDoc）。
    if (hasSong && !geo.bigScreenActive) {
        PlayerCardDismissButton(
            progress = progress,
            dismissEnabled = ui.dismissEnabled,
            strings = strings,
            onDismiss = callbacks.onDismiss,
        )
    }
}
