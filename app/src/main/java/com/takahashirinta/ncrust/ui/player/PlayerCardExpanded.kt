/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：从 `PlayerCard` 拆出的**展开态子树**（两个槽位 lambda + 三个布局分支的分发）。
 *
 * ## 它在组合树里的位置与拆分前逐节点相同
 *
 * `PlayerCard` 的 `Column { if (hasSong && expandedMounted) { …本函数… } }` ——
 * 本函数是 **`ColumnScope` 扩展**，因为它内部的窄屏分支（[PlayerCardNarrowLayout]）
 * 也是 `ColumnScope` 扩展、并且直接向外层 `Column` 里放 5 个同级子节点。
 * 包一层 `Column` 会多一个布局节点、`weight` 的分配对象也随之改变 —— 那不是逐字节等价。
 *
 * ## 挂载判据不变
 *
 * `hasSong && expandedMounted` 仍由调用点判断（折叠态整棵展开态子树不挂载，mini bar 永远挂载，
 * 见 AGENTS.md 触摸陷阱 §4）。本函数只在判据成立时被调用。
 *
 * ## 两个槽位 lambda 的构造点搬到了这里
 *
 * `playerControls` / `playerPanels` 的**闭包捕获**原先把 ~35 个局部量装进捕获数组；
 * 现在这些量是形参（13 个整包/单个量），调用点的装载指令大幅减少 —— 这是
 * `PlayerCard` 方法体跌破 2000 code unit 的最后一块。
 */

package com.takahashirinta.ncrust.ui.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.player.motion.MotionBackdrop
import com.takahashirinta.ncrust.ui.player.motion.MotionClock
import com.takahashirinta.ncrust.ui.player.motion.MotionFrameClock
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.player.motion.MotionEffects
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 展开态子树（原 `PlayerCard` 的 `if (hasSong && expandedMounted)` 块，逐行搬移）。
 */
@Composable
internal fun ColumnScope.PlayerCardExpanded(
    song: SongItem,
    ui: PlayerCardUiState,
    geo: PlayerCardGeometry,
    progress: Animatable<Float, AnimationVector1D>,
    strings: Strings,
    playerViewModel: PlayerViewModel,
    callbacks: PlayerCardCallbacks,
    isPlaying: Boolean,
    haptic: HapticFeedback,
    gestureNavLiftDp: Dp,
    lyrics: List<LrcLine>,
    translatedLyrics: List<LrcLine>,
    lyricsLoading: Boolean,
    showLyricsTranslation: Boolean,
    showLyricsRomanization: Boolean,
    showDynamicLyricFont: Boolean,
    romanizedLyrics: List<LrcLine>,
    lyricsWordAnimation: Int,
    lyricsFontScale: Float,
    lyricsSweepQuality: Int,
    lyricsReady: Boolean,
    lyricsUnavailable: Boolean,
    isSongSaved: Boolean,
    controlsCollapseDrag: () -> Modifier,
    onToggleControlsCollapse: () -> Unit,
    /** 动效能力位（组合期读一次，帧路径只读捕获值）。 */
    motion: MotionEffects,
    /** 队列（队列面板与「上一首可用性」都要）。 */
    playbackQueue: List<SongItem>,
    currentQueueIndex: Int,
    playMode: Int,
    /** 面板横滑位移用的窗口宽（px）。 */
    screenWidthPx: Float,
    /** 自动旋转开关（大屏左栏那一行的入口）。 */
    autoRotate: Boolean,
) {
    // `context` 与拆分前同源（同一个 CompositionLocal，值相同）：用于收藏 Toast。
    val context = LocalContext.current
            val s = song!!

            // 底部播放控件（窄屏整宽 / 宽屏左栏共用）。常挂载，alpha 只在 draw 阶段调。
            val playerControls: @Composable () -> Unit = {
                // v2.9.0 · B 档：整条控制区随节拍轻微脉冲（任务书 §5.3）。
                // 2% 是刻意的上限 —— 控制条是点击目标，缩放再大就会影响点击手感。
                // 逐帧量在 graphicsLayer 块里读，只让这一层失效，不触发重组。
                Box(
                    modifier = Modifier.graphicsLayer {
                        if (motion.beatPulse) {
                            MotionClock.generation
                            val pulseScale = 1f + MotionClock.pulse() * AppMotion.BEAT_PULSE_SCALE
                            scaleX = pulseScale
                            scaleY = pulseScale
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                    }
                ) {
                FullPlayerControls(
                    isPlaying = isPlaying,
                    showLyrics = ui.showLyrics,
                    showQueue = ui.showQueue,
                    progressFlow = playerViewModel.progress,
                    positionFlow = playerViewModel.currentPosition,
                    durationFlow = playerViewModel.duration,
                    qualityIndexFlow = playerViewModel.currentQualityIndex,
                    qualityStatusFlow = playerViewModel.qualityStatus,
                    qualityBitrateFlow = playerViewModel.currentBitrate,
                    qualityOptions = strings.qualityOptions,
                    onPlayPause = callbacks.onPlayPause,
                    onPlayPrevious = callbacks.onPlayPrevious,
                    onPlayNext = callbacks.onPlayNext,
                    onToggleLyrics = {
                        val nowReady = lyricsReady
                        ui.showLyrics = !ui.showLyrics
                        ui.showQueue = false
                        // 未就绪(失败/尚未成功)时点击 = 触发一次重新加载，
                        // 不必切歌才能恢复歌词。
                        if (!nowReady && !lyricsLoading) playerViewModel.retryLyrics()
                    },
                    onToggleQueue = {
                        ui.showQueue = !ui.showQueue
                        ui.showLyrics = false
                    },
                    onAddToLibrary = {
                        val cur = song
                        if (cur != null) {
                            // 已在库 → 移出; 不在库 → 收藏。本地即时生效, 云端异步同步。
                            // v2.6.0 · P0：两个动作都按**真实结果**提示，不再无条件弹成功
                            // （旧代码里 removeSong/saveSong 都返回 Unit，提示与事实脱钩）。
                            if (LibraryManager.isSongSaved(context, cur.id)) {
                                if (LibraryManager.removeSong(context, cur.id)) {
                                    Toast.makeText(context, strings.removedFromLibrary, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                if (LibraryManager.saveSong(context, cur).isSuccess) {
                                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                }
                            }
                            ui.libraryTick++
                        }
                    },
                    isInLibrary = isSongSaved,
                    isBufferingFlow = playerViewModel.isBuffering,
                    onSeek = { fraction ->
                        val dur = playerViewModel.duration.value
                        if (dur > 0) {
                            playerViewModel.seekTo((fraction * dur).toLong())
                        }
                    },
                    lyricsUnavailable = lyricsUnavailable,
                    previousEnabled = playMode != QueueModes.INFINITY,
                    // 大屏模式也用扁平横向控制条（竖屏那套大按钮堆叠在 300dp 高里放不下）。
                    landscape = geo.usesSideCover,
                    compact = geo.usesSideCover,
                    // 大屏把音质搬到左栏就地选择器，控制条右端不再重复一个音质角标。
                    showQuality = !geo.bigScreenActive,
                    // 大屏幕模式开关（入口/出口同一个回调、同一个图标语义）。
                    // 竖屏时它在下方那排操作按钮里（第 4 个，SpaceEvenly）；
                    // 横屏大屏时它在控制条右端（音质让出来的位置）—— 竖屏那排的
                    // 第 4 个在横向三段式布局里会顶到居中的传输组（实测与"上一首"重叠）。
                    bigScreen = geo.bigScreenActive,
                    onToggleBigScreen = callbacks.onToggleBigScreen,
                    // v2.5.5 · E：平板的横向控件条里补一个 ⤢ 入口。
                    // 判据是纯函数（PlayerLayout.bigScreenEntrySlot），A/B 矩阵写在它的 KDoc 上：
                    // 只有「平板 + 不在大屏」两格挂载，手机横屏一格都不变。
                    showBigScreenEntry = PlayerLayout.bigScreenEntrySlot(
                        isLargeScreen = geo.isLargeScreen,
                        bigScreenActive = geo.bigScreenActive,
                    ),
                    // v1.8.0 · T4：竖屏控制栏里的自动旋转图标。
                    autoRotate = autoRotate,
                    onToggleAutoRotate = callbacks.onToggleAutoRotate,
                    // v1.8.0 · T2：竖屏 / 宽屏控制条的音质选择器。
                    preferredQualityIndexProvider = {
                        playerViewModel.currentQualityPreferenceIndex()
                    },
                    onQualitySelect = { playerViewModel.setQualityPreference(it) },
                    trailing = if (geo.bigScreenActive) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { callbacks.onToggleBigScreen() },
                                contentAlignment = Alignment.Center
                            ) {
                                MetroIcon(
                                    imageVector = Icons.Default.CloseFullscreen,
                                    contentDescription = strings.bigScreenExit,
                                    tint = LocalMetroColors.current.primary,
                                    sizeDp = 24.dp
                                )
                            }
                        }
                    } else null
                )
                }
            }

            // 歌词 / 队列双面板（窄屏整宽 / 宽屏右栏共用）。
            val playerPanels: @Composable (Modifier) -> Unit = { panelModifier ->
                Box(modifier = panelModifier) {
                    // 歌词面板：translationX 从 0 滑至 -screenWidthPx，确保非歌词模式下完全移出屏幕，
                    // 彻底消除与列表面板的命中测试重叠（combinedClickable 忽略 isConsumed 标志）
                    // alpha 用阶梯而非交叉淡化: 切换时源面板瞬时隐藏、目标面板单层全宽滑入,
                    // 每帧只合成一个面板——原先 260ms 内两个全屏面板同时 alpha 混合是
                    // 低端机上左右切换动作的主要 GPU 成本。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val q = ui.queueSlideProgress.value
                                alpha = ui.lyricAnimProgress.value * if (q < 0.01f) 1f else 0f
                                translationX = -q * screenWidthPx
                            }
                    ) {
                        // 切歌时旧歌词渐隐、新歌词渐显（Sokuou UWP 缓动）。
                        // key = song?.id：同一首歌的歌词更新不触发 Crossfade, 只换内容。
                        Crossfade(
                            targetState = song?.id,
                            animationSpec = SokuouTweens.CoverFade,
                            label = "LyricsCrossfade"
                        ) { _ ->
                            LyricsView(
                                lyrics = lyrics,
                                translatedLyrics = translatedLyrics,
                                showTranslation = showLyricsTranslation,
                                romanizedLyrics = romanizedLyrics,
                                showRomanization = showLyricsRomanization,
                                positionFlow = playerViewModel.currentPosition,
                                isPlaying = isPlaying,
                                isVisible = ui.showLyrics,
                                forcedLocateTrigger = ui.lyricLocateTrigger,
                                onSeekToMs = { ms -> playerViewModel.seekTo(ms) },
                                enabled = ui.lyricsEnabled && ui.cardExpandedForInput,
                                onUserScrolled = {},
                                isLoading = lyricsLoading,
                                wordByWordEnabled = lyricsWordAnimation != LyricsWordAnimationMode.OFF,
                                wordAnimationMode = lyricsWordAnimation,
                                fontScale = lyricsFontScale,
                                sweepQuality = lyricsSweepQuality,
                                onFontScaleStep = { delta -> playerViewModel.stepLyricsFontScale(delta) },
                                // v2.0.0 · T4：动态字号（实验性，默认关）。
                                dynamicFontEnabled = showDynamicLyricFont,
                                // v2.3.0 · E：横屏 / 大屏右栏（usesSideCover = 宽屏或大屏模式）
                                // 的歌词面板只有约 210~280dp 高，当前行定位到**正中**而不是
                                // 竖屏的 36%；配合面板里的「5s 无触碰自动居中」，
                                // 用户手动翻过歌词之后它会自己回到正中。
                                centeredLayout = geo.usesSideCover,
                                // v2.9.0 · B 档：歌词律动（当前行随节拍轻微缩放）。
                                lyricPulseEnabled = motion.lyricPulse,
                                // v2.9.0 · P1：歌词引擎用它判断"外推锚点属于哪一首"。
                                // 必须是一个**换歌必变、同曲不变**的稳定身份 ——
                                // `(音源, id)` 是本仓库对曲目身份的既有口径（v2.6.1/v2.6.2 的
                                // `TrackKey` 同源），不能只用 id（两个音源的 id 空间虽然结构性
                                // 隔离，但把口径写全比依赖那个不变量便宜）。
                                trackKey = song?.let { "${it.source}:${it.id}" },
                            )
                        }
                    }

                    // 列表面板：translationX 从 +screenWidthPx 滑至 0，稳定态时完全在屏幕外
                    // alpha 阶梯同上: q < 0.01 时完全透明, 切换只合成单个面板
                    // 面板标题行高度: 队列自动定位要按"整个队列区域"(含标题行)居中
                    var queueHeaderHeightPx by remember { mutableFloatStateOf(0f) }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val q = ui.queueSlideProgress.value
                                alpha = ui.lyricAnimProgress.value * if (q > 0.01f) 1f else 0f
                                translationX = (1f - q) * screenWidthPx
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .onGloballyPositioned {
                                    queueHeaderHeightPx = it.size.height.toFloat()
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MetroText(
                                strings.queueTitle,
                                color = LocalMetroColors.current.onBackground,
                                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            MetroIconButton(onClick = callbacks.onTogglePlayMode) {
                                MetroIcon(
                                    imageVector = when (playMode) {
                                        QueueModes.SINGLE -> Icons.Default.RepeatOne
                                        QueueModes.SHUFFLE -> Icons.Default.Shuffle
                                        QueueModes.LINE -> Icons.Default.PlaylistPlay
                                        QueueModes.INFINITY -> Icons.Default.AllInclusive
                                        else -> Icons.Default.Repeat
                                    },
                                    contentDescription = strings.playModeButton,
                                    tint = if (playMode != QueueModes.CYCLE) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                                    sizeDp = 24.dp
                                )
                            }
                            MetroIconButton(onClick = callbacks.onSavePlaylist) {
                                MetroIcon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = strings.saveAsPlaylist,
                                    tint = LocalMetroColors.current.onBackground,
                                    sizeDp = 24.dp
                                )
                            }
                            // 清空队列: 停播并回到暂无播放态
                            MetroIconButton(onClick = callbacks.onClearQueue) {
                                MetroIcon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = strings.clearQueue,
                                    tint = LocalMetroColors.current.onBackground,
                                    sizeDp = 24.dp
                                )
                            }
                        }
                        MetroDivider(color = LocalMetroColors.current.divider)
                        QueueView(
                            queue = playbackQueue,
                            currentIndex = currentQueueIndex,
                            playMode = playMode,
                            isActive = ui.showQueue,
                            interactive = ui.cardExpandedForInput,
                            queueHeaderHeightPx = queueHeaderHeightPx,
                            onPlayIndex = callbacks.onPlayFromQueue,
                            onRemoveIndex = callbacks.onRemoveFromQueue,
                            onMove = callbacks.onMoveInQueue
                        )
                    }
                }
            }

            // v3.2.1 · P0：三个互斥的布局分支搬进 PlayerCardLayouts.kt。
            // 运行期仍然只有一棵子树被组合（`when` 语义未变），
            // 但编译期不再把三棵树的构建指令全部塞进 PlayerCard 这一个方法体。
            //
            // 封面区的实测值（wideCoverCenter / wideCoverSizePx）与控制栏实测值仍由
            // **PlayerCard** 持有 —— 这里只把实测结果回写，写状态的对象与时机不变。
            val onCoverRegionMeasured: (androidx.compose.ui.geometry.Offset, Float) -> Unit =
                { center, sizePx ->
                    ui.wideCoverCenter = center
                    ui.wideCoverSizePx = sizePx
                }
            when {
                geo.bigScreenActive -> PlayerCardBigScreenLayout(
                    song = song,
                    geo = geo,
                    ui = ui,
                    strings = strings,
                    progress = progress,
                    playerViewModel = playerViewModel,
                    autoRotate = autoRotate,
                    onToggleAutoRotate = callbacks.onToggleAutoRotate,
                    onSongInfoClick = callbacks.onSongInfoClick,
                    onCoverRegionMeasured = onCoverRegionMeasured,
                    playerPanels = playerPanels,
                    playerControls = playerControls,
                )

                geo.isWidePlayer -> PlayerCardWideLayout(
                    song = song,
                    geo = geo,
                    ui = ui,
                    progress = progress,
                    onCoverRegionMeasured = onCoverRegionMeasured,
                    onSongInfoClick = callbacks.onSongInfoClick,
                    playerPanels = playerPanels,
                    playerControls = playerControls,
                )

                else -> PlayerCardNarrowLayout(
                    song = song,
                    geo = geo,
                    ui = ui,
                    strings = strings,
                    progress = progress,
                    isPlaying = isPlaying,
                    haptic = haptic,
                    gestureNavLiftDp = gestureNavLiftDp,
                    controlsCollapseDrag = controlsCollapseDrag,
                    onToggleControlsCollapse = onToggleControlsCollapse,
                    onPlayPause = callbacks.onPlayPause,
                    onSongInfoClick = callbacks.onSongInfoClick,
                    onControlsMeasured = { heightPx, topInCardPx ->
                        ui.controlsHeightPx = heightPx
                        ui.controlsTopInCardPx = topInCardPx
                    },
                    playerPanels = playerPanels,
                    playerControls = playerControls,
                )
            }}
