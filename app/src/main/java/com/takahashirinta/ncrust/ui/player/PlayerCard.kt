/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」）：
 *   - 向 FullPlayerControls 传入 qualityStatus（偏好档位 vs 实际文件质量不一致）。
 */

package com.takahashirinta.ncrust.ui.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.compose.AsyncImagePainter
import com.takahashirinta.ncrust.ui.player.motion.MotionBackdrop
import com.takahashirinta.ncrust.ui.player.motion.MotionClock
import com.takahashirinta.ncrust.ui.player.motion.MotionFrameClock
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs
import com.takahashirinta.ncrust.ui.theme.AppMotion
import coil.compose.rememberAsyncImagePainter
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import androidx.compose.foundation.systemGestureExclusion
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import com.takahashirinta.ncrust.ui.theme.AppShapes

@Composable
fun PlayerCard(
    song: SongItem?,
    isPlaying: Boolean,
    screenHeightPx: Float,
    progress: Animatable<Float, AnimationVector1D>,
    totalDragDistancePx: Float = 0f,
    playbackQueue: List<SongItem> = emptyList(),
    currentQueueIndex: Int = -1,
    playMode: Int = 0,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit = {},
    onPlayFromQueue: (Int) -> Unit = {},
    onMoveInQueue: (Int, Int) -> Unit = { _, _ -> },
    onTogglePlayMode: () -> Unit = {},
    onPlayNothing: () -> Unit = {},
    onSongInfoClick: () -> Unit = {},
    /**
     * v2.5.4 · E：竖屏托盘第二行「作者」那一段的点击（进艺人页）。
     *
     * 默认空实现是**有意的降级**：调用方没接线时点作者等于什么都没发生，
     * 而不是掉进「转到歌手/转到专辑」菜单 —— 后者是 [onSongInfoClick] 的语义，
     * 混用会让同一个手势在不同调用点做两件事。
     *
     * ## v2.6.1 · P0：形参从 `Long` 改成整首 [SongItem]
     *
     * 旧形状是 `(Long) -> Unit`，调用方拿到的只有 `artists[0].id`。对 QQ 曲目那是
     * **QQ 域的数字 `singerID`**，而调用方拼的是硬编码网易云的老路由 —— 周杰伦 `4558`
     * 于是跳到了马洪波（真机复现）。改成整首歌之后，身份判定由
     * [com.takahashirinta.ncrust.source.ArtistNavigator] 统一做
     * （它同时看得到 `musicSource` 与 `artists[0].mid`），
     * 与长按菜单的「转到歌手」走**同一个出口**。
     *
     * 顺带修掉的第二个缺陷：以前 `id == null` 时这里回落到 [onSongInfoClick]（弹菜单），
     * 而冷启动恢复的曲目 `id` **恒为 null**（`PlaybackStateManager` 只存了艺人**名字**）
     * —— 用户点作者名只会看到一个菜单，菜单里再点「转到歌手」又是静默失败。
     * 现在一律交给调用方，由它决定「进艺人页」还是「跳搜索」。
     */
    onArtistClick: (SongItem) -> Unit = {},
    onClearQueue: () -> Unit = {},
    onSavePlaylist: () -> Unit = {},
    // P1：大屏幕模式（横屏桌面播放器布局）开关 + 入口/出口回调。
    // bigScreen 是"用户意图"，还要叠加当前窗口方向才是生效态（见 bigScreenActive）。
    bigScreen: Boolean = false,
    onToggleBigScreen: () -> Unit = {},
    // v1.8.0 · T4：自动旋转开关（竖屏控制栏 + 大屏左栏两处图标入口，同一份状态）。
    autoRotate: Boolean = false,
    onToggleAutoRotate: () -> Unit = {}
) {
    val hasSong = song != null
    // 初始落大封面: 歌词未就绪时(加载中/确无), 全屏默认看封面而非空歌词面板;
    // lyricsReady 到位后由下方的 LaunchedEffect 自动切回歌词视图。
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // v1.5.1 · B：手势导航机型给控制栏把手留出的上抬量（见把手处的注释）。
    // 只在 navigation_mode == 2（Android 10+ 手势导航）时非零；三键导航与 API < 29 为 0。
    val gestureNavLiftDp = remember(context) {
        if (isGestureNavigation(context)) GESTURE_NAV_HANDLE_LIFT_DP else 0.dp
    }

    val strings = LocalStrings.current
    val playerViewModel: PlayerViewModel = viewModel()
    val lyrics by playerViewModel.lyrics.collectAsState()
    val translatedLyrics by playerViewModel.translatedLyrics.collectAsState()
    val lyricsLoading by playerViewModel.lyricsLoading.collectAsState()
    val lyricsSongId by playerViewModel.lyricsSongId.collectAsState()
    val lyricsNoContentSongId by playerViewModel.lyricsNoContentSongId.collectAsState()
    val showLyricsTranslation by playerViewModel.showLyricsTranslation.collectAsState()
    // v1.9.3：音译显示开关（默认关）+ 音译轨（v1.9.2 的音译数据，渲染层只读不重合并）。
    val showLyricsRomanization by playerViewModel.showLyricsRomanization.collectAsState()
    // v2.0.0 · T4：动态字号（实验性，默认关）。
    val showDynamicLyricFont by playerViewModel.showDynamicLyricFont.collectAsState()
    val romanizedLyrics by playerViewModel.romanizedLyrics.collectAsState()
    // v1.5.1 · A：逐字动画模式（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。
    // 模式 2 时 karaokeEnabled=false，行内渲染退回 v1.4.1 的整行路径。
    val lyricsWordAnimation by playerViewModel.lyricsWordAnimation.collectAsState()
    // v1.5.1 · E：歌词字号倍率（设置页与歌词界面的 A-/A+ 都改它）。
    val lyricsFontScale by playerViewModel.lyricsFontScale.collectAsState()
    // v1.5.2：逐字扫过的绘制质量（0 自动 / 1 高级软边 / 2 兼容硬边）。
    val lyricsSweepQuality by playerViewModel.lyricsSweepQuality.collectAsState()
    // 收藏库状态: 当前歌是否已收藏(右下角 加号/对号 切换用)。切歌或操作后刷新。
    var libraryTick by remember { mutableIntStateOf(0) }
    val isSongSaved = remember(song?.id, libraryTick) {
        song?.let { LibraryManager.isSongSaved(context, it.id) } ?: false
    }
    // currentPosition / progress 是 4Hz 更新的 StateFlow，直接传引用给需要的子组件，
    // 让它们在最小作用域（graphicsLayer / Canvas draw / derivedStateOf / 叶子 Text）内订阅，
    // 避免 PlayerCard 本身随位置更新 4Hz 重组
    // duration / qualityIndex / isBuffering downgraded to leaf collect inside FullPlayerControls;
    // subscribing at this scope would force the whole PlayerCard subtree to recompose on song
    // change / buffer flap, dragging in AsyncImage + Column layout for no reason.

    // 宽屏分栏进度：0 = 单栏（封面居中、控件铺满居中），1 = 两栏（左封面+控件 / 右歌词·队列）。
    // 由是否显示歌词/队列驱动；窄屏不读取该值（不触发额外重组）。
    val wideSplit by animateFloatAsState(
        targetValue = if (showLyrics || showQueue) 1f else 0f,
        animationSpec = tween(280, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "widePlayerSplit"
    )

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { screenWidthDp.toPx() }
    val dp24px = with(density) { 24.dp.toPx() }
    // 宽屏播放器两栏（Apple Music 式）：左封面 / 右歌词·队列。
    val isWidePlayer = LocalConfiguration.current.screenWidthDp >= 600
    // ---- P1 · 大屏幕模式（横屏桌面播放器布局）：第三个谓词，只让播放器读 ----
    // 全仓库另外 7 处 `screenWidthDp >= 600` 的宽屏判定一律不动：横屏时窗口宽度必然
    // >= 600dp（PCL110 实测 2800px = 800dp），把大屏模式塞进那个谓词会把首页/详情页/
    // 收藏页的布局一起改掉 —— 它们要的是"平板"，不是"横过来的手机"。
    val bigScreenActive = PlayerLayout.isBigScreenActive(
        requested = bigScreen,
        // Manifest 已声明 configChanges=orientation，旋转不重建 Activity，
        // 这里读到的一定是旋转后的窗口方向。
        orientationLandscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE,
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

    // P1 / v1.8.0 · T2：音质选择器的状态（打开态、高亮档位）已经搬进 FullPlayerControls
    // 的 PlayerQualityChip —— 竖屏 / 宽屏控制条 / 横屏大屏左栏三处共用一份实现，
    // 否则必然出现"某处高亮错档、某处少 FLAC 提示"的漂移。这里只保留高度上限，
    // 由调用点传给组件（大屏要按窗口高夹，竖屏那份在 FullPlayerControls 里按屏高夹）。
    // 选择器高度上限：竖屏 400dp 够用；横屏大屏只有 ~363dp 高（PCL110 实测 1272px），
    // 400dp 的弹层会被屏幕裁掉底部档位 —— 实测第 8 档「杜比全景声」落在屏幕外、点不到。
    // 这里按窗口高的 70% 夹一下，选择器本身是 LazyColumn，放不下时可以滚。
    val qualityPickerMaxHeightDp = with(density) {
        minOf(400.dp.toPx(), screenHeightPx * 0.7f).toDp()
    }
    // v1.8.0 · T3：可视化条的开关与高度。
    // 高度 = 窗口高 × 11%，夹在 32~56dp：PCL110 横屏（363dp 高）得 40dp、
    // S6（480dp 高）得 53dp —— 矮屏少占、高屏多给，封面区用 weight(1f) 自动让位。
    // v2.5.4 · D：算式搬进 PlayerLayout.visualizerHeightDp（可单测），这里只读结果。
    val visualizerEnabled = VisualizerSetting.state.value
    // v2.5.4 · D：**平板**（smallestScreenWidthDp >= 600，与方向无关）。
    // 与 isWidePlayer 不是同一个谓词：手机横屏的 screenWidthDp 也 >= 600，
    // 但它不该在宽屏两栏里长出可视化条（那会改掉 v1.8.0 以来手机横屏的形态）。
    val isLargeScreen =
        LocalConfiguration.current.smallestScreenWidthDp >= PlayerLayout.LARGE_SCREEN_BREAKPOINT_DP
    val orientationLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 挂载判据**只有一个落点**（PlayerLayout.visualizerSlot），A/B 矩阵写在那里的 KDoc。
    val visualizerSlot = PlayerLayout.visualizerSlot(
        enabled = visualizerEnabled,
        bigScreenActive = bigScreenActive,
        isWidePlayer = isWidePlayer,
        isLargeScreen = isLargeScreen,
        orientationLandscape = orientationLandscape,
    )
    val visualizerHeightDp = with(density) {
        PlayerLayout.visualizerHeightDp(LocalConfiguration.current.screenHeightDp.toFloat()).dp
    }

    // v2.9.0 · B 档：背景级波形条的高度。比左栏那条（32~56dp）高一档，
    // 因为它承担的是"整块背景在流动"的观感，太薄会被读成一条装饰线。
    // 仍按窗口高夹取：PCL110 横屏可用高只有 363dp，固定 120dp 会把封面压掉一圈。
    val waveBackdropHeightDp = with(density) {
        minOf(120.dp.toPx(), screenHeightPx * 0.20f).toDp()
    }

    // 迷你条与顶栏按钮的触觉反馈
    val haptic = LocalHapticFeedback.current

    // ── v2.9.0 · A 档：动效状态 + 封面位图的唯一持有者 ────────────────────────────────
    // 分级：**组合期读一次**（用户改设置或发生一次自动降级时才会重组一次）。
    // 帧路径只读这个捕获值 —— 逐帧变化的量全部走 MotionClock 的 generation，不读 state。
    val motion = MotionPrefs.effects.value
    // 背景模糊的输入。由 `StableCover` 上抛 —— 它是全仓库**唯一**持有"当前这张封面位图"
    // 的地方（跨切歌保留上一张，见那里的 KDoc）。这里再解一次码会多一份内存，
    // 而且可能与前景显示的不是同一张图（Coil 缓存被清 / 尺寸不同）。
    var coverArtKey by remember { mutableStateOf<String?>(null) }
    var coverArtBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 唯一封面 overlay：全屏 ↔ miniBar 始终是同一个 cover，只平滑移动/缩放，绝不消失。
    // 宽屏大图落点由左栏"封面区"实测得到（区域化，分辨率无关）。
    var cardRootOrigin by remember { mutableStateOf(Offset.Zero) }
    var wideCoverCenter by remember { mutableStateOf(Offset.Zero) }
    var wideCoverSizePx by remember { mutableStateOf(0f) }
    val coverSizePx = if (usesSideCover) {
        // 实测前用兜底尺寸，避免首帧 1px 让 Coil 按 1px 解码成纯色（重进时尤为明显）。
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
    val statusBarPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        .let { with(density) { it.toPx() } }
    val miniCoverCenterX = miniCoverHalfPx
    val miniCoverCenterY = statusBarPx + with(density) { TrayLayout.coverCenterOffsetDp().dp.toPx() }
    val largeCoverCenterX = if (usesSideCover) wideCoverCenter.x else screenWidthPx / 2f
    val largeCoverCenterY = if (usesSideCover) wideCoverCenter.y else screenHeightPx * 0.3f + dp24px
    val boundsCenter = coverSizePx / 2f
    // v2.9.0 · A 档：封面随节拍上浮的最大位移（px）。在这里换算一次，
    // 而不是在 graphicsLayer 块里调 `dp.toPx()` —— 那个块每帧都会跑。
    val coverFloatPx = with(density) { AppMotion.COVER_FLOAT_DP.toPx() }

    // v2.9.0：换歌时清掉**界面动效那一层**的包络与特效池（基线 / 脉冲 / 光晕 / 粒子）。
    // 不清波形环形缓冲：那会让新歌开头几帧的波形是空的（上一首的柱子滚出去是既有的换歌观感）。
    LaunchedEffect(song?.id) { MotionClock.clear() }

    // 完全收起时才激活迷你播放栏；derivedStateOf 将重组限制在阈值穿越处
    val miniBarEnabled by remember { derivedStateOf { progress.value < 0.01f } }
    val miniBarInteractionSource = remember { MutableInteractionSource() }
    // 完全展开时才激活收起按钮
    val dismissEnabled by remember { derivedStateOf { progress.value > 0.99f } }
    // ---- v1.3.0 · B-1：折叠态只挂载 mini bar，其余展开态子树一律条件挂载 ----
    // 展开态子树 = 顶部标题栏 / 歌词·队列双面板 / 底部播放控件 / 大封面模式下的曲名信息。
    // 历史：它们一度常挂载（无 gate），只切 graphicsLayer alpha，不再走 mount/dispose ——
    // 那是"Apple Music 手感"的关键（原生 View 系统里 player subview 是 app 启动就构建好、
    // 隐藏用 visibility=GONE 的），也把首次展开的构造成本从"跨阈值那一帧"挪走了。
    // 但常挂载的代价是**命中区**：Compose 的命中测试与 alpha 无关，折叠态这些 alpha≈0 的
    // 子树仍留在卡片里（卡片 = fillMaxSize + translationY(collapsedOffsetY)，整块压在屏幕
    // 下半部），详情页底部落在这一带的交互元素因此完全收不到事件（死带）。
    // 现在的取舍：折叠态不挂载 → 死带消失。代价是展开时首次挂载展开态子树要做一次
    // composition + layout + draw（冷启动/切歌后第一次展开最明显，之后有 Compose 的
    // slot table 复用，成本低于首次），压不进单帧时会看到一次轻微掉帧；冷启动那一次由
    // Splash + AppWarmup 兜底吸收。阈值 0.01 与 miniBarEnabled 同一处：拖动或点按动画
    // 一开始（约第 1 帧）就挂载，给后面 400ms 动画留出吸收时间。
    // mini bar 本身**永远挂载**（否则无法点击/上拉展开），是折叠态唯一保留的子树。
    val expandedMounted by remember { derivedStateOf { progress.value > 0.01f } }
    // 折叠态命中区让位开关（见下方根 Box 的 padding/offset 注释）。
    // 同样用 derivedStateOf 离散化：只在跨阈值那一帧重组一次，动画帧零成本。
    val hitGateInsetDp = with(density) { statusBarPx.toDp() }
    val collapsedHitGate by remember { derivedStateOf { progress.value <= 0.01f } }

    // lyricAnimProgress：0 = 大封面，1 = 小封面；驱动封面缩放 + 内容淡入淡出
    // queueSlideProgress：0 = 歌词位置，1 = 列表位置；仅 b↔c 时动画，其他时 snap
    // 两个 Animatable 均只在 graphicsLayer { } draw 阶段读取，动画帧内零 recompose
    // 初始 0（大封面）：冷启动/splash 后若当前歌无歌词，首帧即大封面，不会"停"在歌词位
    val lyricAnimProgress = remember { Animatable(0f) }
    val queueSlideProgress = remember { Animatable(0f) }
    // 仅在歌词模式下歌词可交互；阈值穿越处各触发一次重组，其余帧零重组
    val lyricsEnabled by remember { derivedStateOf { lyricAnimProgress.value > 0.5f && queueSlideProgress.value < 0.5f } }
    // 卡片基本展开(>90%)时播放器内容区才可交互。面板常挂载、graphicsLayer 只调 alpha,
    // 折叠态下歌词行/队列行在屏幕底部(迷你条与导航栏之间的缝隙)依然命中测试——
    // 用户"在导航栏底部乱按"会点到不可见的歌词行/队列行, 触发 seek/切歌。
    // 展开阈值和 alpha 淡入阈值(0.7)错开, 保证交互只在内容真正可见后开启。
    val cardExpandedForInput by remember { derivedStateOf { progress.value > 0.9f } }

    // ---- v1.4.0 · A：底部控制栏可收起（仅窄屏全屏态）----
    // controlsCollapse: 0 = 展开（每次进入全屏的默认值），1 = 完全收起。
    // 收起后控制栏整体滑出屏幕，面板（歌词/队列）顺势长高到全屏；右下角留一个极简播放键。
    // 手势：控制栏区域向上拖 = 收起；收起后右下角悬浮键向下拖 = 恢复（点 = 播放/暂停）。
    // 零重组：滑动走 graphicsLayer 平移，高度收缩在 layout 阶段读 Animatable（不触发 recompose）。
    // 只在窄屏生效：宽屏是左右两栏，右栏本来就是整轴高度，没有可让出的空间。
    val controlsCollapse = remember { Animatable(0f) }
    var controlsHeightPx by remember { mutableFloatStateOf(0f) }
    // 控制栏在**卡片根 Box 局部坐标**里的上沿，用于让根节点的整卡拖拽给"收起控制栏"让路。
    var controlsTopInCardPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val controlsCollapsedForInput by remember { derivedStateOf { controlsCollapse.value > 0.5f } }

    // 收起状态不持久化：离开全屏（卡片回到折叠态）即复位为展开。
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value <= 0.01f }
            .distinctUntilChanged()
            .collect { collapsed -> if (collapsed) controlsCollapse.snapTo(0f) }
    }

    // 收起/恢复共用的竖直拖拽检测器：拖动期间 snapTo 跟手，松手做方向敏感吸附。
    // key 带 isWidePlayer：宽屏不启用（见上）。
    // 注意：必须每次调用都**新建** Modifier 实例。SuspendPointerInputElement 内部持有
    // previousKeys 这类可变状态，同一个实例挂到两个节点上会互相踩，表现为其中一个节点
    // 的 pointer 处理器起不来（实测：控制栏能收起、悬浮键的恢复手势收不到事件）。
    fun controlsCollapseDrag(): Modifier = Modifier.pointerInput(hasSong, isWidePlayer) {
        if (!hasSong || isWidePlayer) return@pointerInput
        // current 必须自己累加：onVerticalDrag 的 dragAmount 是**每帧增量**，
        // 写成 startValue - dragAmount/h 只会得到最后一帧的位移，拖动基本不动。
        var current = 0f
        var from = 0f
        // v1.7.0 · P0：手势测速（px/s），供吸附判定做「甩动」判据。
        var startMs = 0L
        var lastMs = 0L
        detectVerticalDragGestures(
            onDragStart = {
                from = controlsCollapse.value
                current = from
            },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                val h = if (controlsHeightPx > 1f) controlsHeightPx
                        else with(density) { 200.dp.toPx() }
                current = (current - dragAmount / h).coerceIn(0f, 1f)
                if (startMs == 0L) startMs = change.uptimeMillis
                lastMs = change.uptimeMillis
                coroutineScope.launch { controlsCollapse.snapTo(current) }
            },
            onDragEnd = {
                // 速度由收起进度反推（px/s）：把手挂在被平移的控制栏兄弟节点上，
                // 直接用 position 差分同样不可靠。h = 控制栏实测高度。
                val elapsedMs = lastMs - startMs
                val h = if (controlsHeightPx > 1f) controlsHeightPx
                        else with(density) { 200.dp.toPx() }
                val velocityY = if (elapsedMs > 0L) (current - from) * h / (elapsedMs / 1000f) else 0f
                coroutineScope.launch {
                    // 方向敏感吸附：向上推（收起）要 12% 行程；向下拉（恢复）只要 5%。
                    // 恢复方向阈值刻意很小：收起会挡住内容、需要"故意"，而恢复只是把控制栏
                    // 放回来、没有任何副作用，阈值大了反而会让"划不回来"（S6 真机实测：
                    // 把手向下可拖的总行程本来就短，控制栏越高越够不到比例阈值）。
                    // 微动（两个方向都没到阈值）按出发点归位。甩动按方向直接提交。
                    // v1.7.0 · P0：收起阈值由 25% 收窄到 12%（25% 在 PCL110 上 = 235px +
                    // 42px 触摸 slop，正常速度的上滑刚好够不到）。
                    val target = ControlsDragSnap.target(from, current, velocityY)
                    controlsCollapse.animateTo(target, tween(260, easing = FastOutSlowInEasing))
                }
            },
            onDragCancel = {
                coroutineScope.launch {
                    controlsCollapse.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                }
            }
        )
    }

    // 控制栏收起的显式切换（把手上点一下即可），与拖拽共用同一条动画轴。
    fun toggleControlsCollapse() {
        coroutineScope.launch {
            val target = if (controlsCollapse.value > 0.5f) 0f else 1f
            controlsCollapse.animateTo(target, tween(260, easing = FastOutSlowInEasing))
        }
    }

    // ---- 歌词可达性驱动的「大封面 ↔ 歌词视图」自动切换 ----
    // - 切歌瞬间: 旧歌词已被 ViewModel 清空, 直接落大封面, 绝不残留上一首歌词。
    // - 歌词就绪(lyricsReady): 自动切回歌词视图（Apple Music 语义）。
    // - 歌词未就绪(仍在加载/确无): 保持大封面 + 灰按钮。
    // - 用户在队列视图时不打扰。
    // lyricsReady：歌词**属于当前歌**且非加载中。旧歌词残留(切歌过渡)不算就绪——
    // 由 lyricsSongId == song?.id 保证, 否则无歌词的新歌会被误判就绪显示旧歌词。
    val lyricsReady = lyricsSongId == song?.id && !lyricsLoading && lyrics.isNotEmpty()
    // 只有服务端明确答复"确无歌词"时才置灰歌词按钮；加载中/请求失败都保持可点，
    // 失败时点一下即触发重试，而不是一次网络抖动就把按钮永久禁用（issue: 后台被杀重进后按钮灰掉）。
    val lyricsNoContent = lyricsNoContentSongId == song?.id && lyrics.isEmpty()
    val lyricsUnavailable = lyricsNoContent && !lyricsLoading

    // 每首歌只自动切到歌词视图一次；用户手动关掉歌词后，不再被自动打开
    // （否则关歌词时 showLyrics 被 effect 立刻改回 true，wideSplit 回不到 0，
    //  表现为"封面/控件都不动"）。
    var autoSwitchedForSong by remember(song?.id) { mutableStateOf(false) }

    LaunchedEffect(song?.id, lyricsReady, lyricsLoading, showQueue) {
        when {
            showQueue -> {
                // 用户在队列：不打扰, 放弃自动回切
            }
            // 歌词就绪 → 首次自动切到歌词视图（封面缩小/左移）
            lyricsReady -> {
                if (!autoSwitchedForSong) {
                    autoSwitchedForSong = true
                    if (!showLyrics) showLyrics = true
                }
            }
            // 加载中：保持当前视图，不清空也不切回大封面 —— 切歌时封面完全不动。
            lyricsLoading -> {
            }
            // 确无歌词（加载完成且为空）→ 回大封面
            else -> if (showLyrics) showLyrics = false
        }
    }

    // P1：大屏模式右栏常驻面板。若用户此前两个面板都没开（例如该曲无歌词、或手动关了
    // 歌词），进大屏后右栏会是一整块黑。这里只在"大屏 + 两个面板都关"时打开歌词面板
    // （歌词为空时面板自己显示「暂无歌词」，比空白可读）。退出大屏不改回 —— 与既有的
    // "歌词就绪自动切歌词"行为一致，不引入第二套面板状态。
    LaunchedEffect(bigScreenActive) {
        if (bigScreenActive && !showLyrics && !showQueue) showLyrics = true
    }

    LaunchedEffect(showLyrics, showQueue) {
        when {
            showLyrics -> {
                // 与封面缩小并行
                launch { lyricAnimProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }
                // 若当前 queueSlideProgress > 0（来自列表模式），横滑回歌词位置
                if (queueSlideProgress.value > 0.01f) {
                    queueSlideProgress.animateTo(
                        0f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                    )
                }
            }
            showQueue -> {
                launch { lyricAnimProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }
                when {
                    // 已在列表位置（或正在返回），无需再动
                    queueSlideProgress.value > 0.99f -> {}
                    // 来自稳定歌词模式（lyricAnimProgress 已是 1）→ 横滑
                    lyricAnimProgress.value > 0.95f -> {
                        queueSlideProgress.animateTo(
                            1f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        )
                    }
                    // 来自大封面模式 → 不横滑，仅随封面缩小淡入
                    else -> queueSlideProgress.snapTo(1f)
                }
            }
            else -> {
                // 切回大封面：等封面展开完成，内容随 lyricAnimProgress 自然淡出
                lyricAnimProgress.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
                // 封面展开后重置滑动位置，为下次 b→c 准备
                queueSlideProgress.snapTo(0f)
            }
        }
    }

    // 展开动作触发一次歌词定位: 面板常挂载, isVisible 不会翻转, 若不做强制定位,
    // 从 mini bar 拉起后歌词停在旧位置(或用户上次手动滚动的位置)
    var lyricLocateTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }
            .distinctUntilChanged { a, b -> (a > 0.9f) == (b > 0.9f) }
            .collect { p ->
                if (p > 0.9f) lyricLocateTrigger++
            }
    }

    // 歌词/队列面板可交互(展开 + 面板在前台)时,面板区域内的纵向手势归内部列表滚动。
    // 根节点的整卡拖拽与兜底消费器都不得抢手势——否则在面板上一滑,整卡被拖走、
    // 列表几乎滚不动(issue #23)。面板外的封面/顶栏/大封面模式仍驱动整卡。
    val topBarBottomPx = statusBarPx + with(density) { 56.dp.toPx() }
    val isPanelInteractive by remember {
        derivedStateOf {
            (lyricsEnabled || queueSlideProgress.value > 0.5f) && progress.value > 0.7f
        }
    }
    // 注意区分两个不同职责的判断，不要把语义混在一起：
    //
    // ① isOverPanel —— **面板语义**：点是否落在歌词/列表这类可滚动面板上。
    //    只给下面的拖拽检测器用：落在面板内时根节点必须让路，
    //    否则内部 LazyColumn 滚不动（历史 issue #23）。它依赖 isPanelInteractive 是正确的。
    fun isOverPanel(y: Float, x: Float) = isPanelInteractive && y > topBarBottomPx &&
        (!isWidePlayer || x > panelBoundaryPx)

    // ② isOverCardVisibleArea —— **几何语义**：点是否落在卡片自己的可见矩形内。
    //    只给下面「展开态吞事件」的消费者用。
    //
    //    旧实现这里用的是 isOverPanel，于是豁免区被绑在 isPanelInteractive 上、
    //    进而绑在 lyricsEnabled 上：**关闭歌词后豁免区整个消失**，根节点在展开态
    //    把事件全部吞掉，底部播放控制栏与底部导航栏一起失效（歌词开着反而正常）。
    //    消费者要挡的只是**下层兄弟**，与自己内部显示哪个面板无关，
    //    所以这里必须只依赖几何。
    // ③ isOverCollapsibleControls —— **控制栏语义**：点是否落在"可收起的底部控制栏"上。
    //    只给根节点的整卡拖拽让路用：窄屏全屏态下，这一区域内向上拖是"收起控制栏"，
    //    而不是把整张卡片拖走。controlsTopInCardPx 由控制栏 onGloballyPositioned 实测，
    //    分辨率无关；未实测到（MAX_VALUE）时判断恒为 false，即退化成旧行为。
    // 注意这里**不**按"控制栏当前是展开还是收起"分段：控制栏收起后，它让出的那块区域
    // 属于面板（歌词的点击/滚动），而把手仍然在最底部；两种状态下这块都应该归它们，
    // 而不是让整卡拖拽来抢（S6 真机实测：收起后向下拖把手恢复，会被整卡拖拽抢走，
    // 结果是"控制栏没回来、整卡反而被拖下去"）。
    fun isOverCollapsibleControls(y: Float) =
        !usesSideCover && cardExpandedForInput && y >= controlsTopInCardPx

    fun isOverCardVisibleArea(y: Float, x: Float): Boolean {
        // 宽屏左右分栏，左栏是封面区，触摸落在左栏时不属于卡片内容区。
        // P1：大屏模式**不**参与这条豁免 —— 它的左栏里有可点控件（音质选择器），
        // 整个屏幕都算卡片可见区，根节点不吞任何事件，避免把子控件的 UP 消费掉
        // （Compose 的 waitForUpOrCancellation 见到 isConsumed 就直接取消点击）。
        if (isWidePlayer && !bigScreenActive && x <= panelBoundaryPx) return false
        // 卡片可见上沿：收起时整体下移到 collapsedOffsetY，展开时回到 0。
        // 用 progress 插值而不是直接读 cardRootOrigin.y —— graphicsLayer 的平移
        // 不会重新触发布局，onGloballyPositioned 写入的坐标在动画期间会滞后。
        val cardTopPx = cardRootOrigin.y * (1f - progress.value)
        return y >= cardTopPx
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { cardRootOrigin = it.boundsInRoot().topLeft }
            // ---- 死带修复（B-1 的必要补充）：折叠态把**命中区**上沿让出 statusBar 高 ----
            // 卡片是 fillMaxSize + translationY(collapsedOffsetY)，下面两个 pointerInput（上拉
            // 手势、展开态吞事件）挂在根 Box 上，命中区 = 整个根 Box；而 mini bar 的内容被
            // .statusBarsPadding() 下推了 statusBar 高、卡背背景也被 graphicsLayer 下推了同样
            // 高度。于是 collapsedOffsetY .. collapsedOffsetY+statusBar 这一段（S6 实测
            // 1920..2016，24dp）视觉上"卡片透明、下层详情页透出来"，事件却被卡片自己吃掉
            // ——详情页 y≈1938 的原 ⋮ 点不动就是这个，只凭不挂载 Column 子树修不掉（那一段
            // 里根本没有子节点，吃事件的正是这两个 pointerInput 自己）。
            // 做法：折叠态给根 Box 加一个 statusBar 高的 top padding（只收窄两个 pointerInput
            // 的命中区），再用等量 offset 把子节点放回原位 —— 视觉与子节点布局零变化。
            // 展开态与动画中段不加 padding：整屏吞事件的行为原样保留。
            .then(if (collapsedHitGate) Modifier.padding(top = hitGateInsetDp) else Modifier)
            // Outer modifier → runs last within this node in Main pass (after drag detector below).
            // Consumes remaining events when fully expanded so Scaffold siblings never receive them.
            // 面板区域不吞事件:内部列表需要先拿到未消费的 MOVE 才能滚动。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (progress.value > 0.99f) {
                            val pos = event.changes.firstOrNull()?.position
                            if (pos == null || !isOverCardVisibleArea(pos.y, pos.x)) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            // Inner modifier → runs first within this node in Main pass.
            // Handles drag-to-expand/collapse; runs before the outer consumer so it sees unconsumed MOVE.
            // 仅在有歌（!hasSong = 暂无播放）时可拖拽；用 hasSong 作 key，来了歌后手势重新激活。
            .pointerInput(hasSong, bigScreenActive) {
                if (!hasSong) return@pointerInput
                // P1：大屏模式下停用"整卡拖拽"。大屏是横屏桌面布局，"把卡片拖下去"没有
                // 对应语义（折叠态的 mini bar 落点是按竖屏 contentHeightPx 算的），
                // 出口固定为：同一个按钮 / 系统返回键 / 旋转回竖屏。
                if (bigScreenActive) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 面板内纵向手势完全交给内部 LazyColumn/进度条,根节点不消费任何事件。
                    if (isOverPanel(down.position.y, down.position.x)) return@awaitEachGesture
                    // 底部控制栏区域同理让路：向上拖 = 收起控制栏（v1.4.0 · A）。
                    if (isOverCollapsibleControls(down.position.y)) return@awaitEachGesture
                    val startProgress = progress.value
                    val pointer = down.id
                    val slop = viewConfiguration.touchSlop
                    // 自定义竖直 slop 检测：**忽略消费标志**累积位移。标准
                    // awaitVerticalTouchSlopOrCancellation 一旦看到事件被消费就返回 null——
                    // miniBar 的 clickable 会消费 down/事件, 导致拖拽永远起不来
                    // ("滑动拉起"失效)。这里越界后再 consume, 之后正常 drag。
                    var acc = 0f
                    var dragging = false
                    // v1.7.0 · P0：**本地累计**拖动进度，不读 progress.value。
                    // progress.snapTo 是 launch 出去的异步写，慢设备（S6/debug 包）上
                    // 一次拖动结束时 Animatable 可能还没追上手指；用它做吸附判定会出现
                    // 「明明拖了 400px，判据只看到 150px」→ 依旧弹回（S6 实测 400px 仍失败）。
                    var dragProgress = startProgress
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        if (!change.pressed) break
                        acc += change.position.y - change.previousPosition.y
                        if (abs(acc) > slop) {
                            dragging = true
                            change.consume()
                            // v1.7.0 · P0：把越过 slop 的那一段位移补进 progress。
                            // 注入事件稀疏时（adb / 快速轻扫）整个手势可能只有一两个 MOVE，
                            // 不补这一段就会出现「划了但卡片没动」，松手必然弹回。
                            val overshoot = acc - (if (acc > 0f) slop else -slop)
                            if (overshoot != 0f) {
                                dragProgress = (dragProgress - overshoot / totalDragDistancePx).coerceIn(0f, 1f)
                                val snapshot = dragProgress
                                coroutineScope.launch { progress.snapTo(snapshot) }
                            }
                            break
                        }
                    }
                    if (!dragging) return@awaitEachGesture
                    // 手动拖动循环：同样忽略消费标志读位移, 边拖边 consume
                    // （压制 miniBar clickable 的按压, 让它不会在抬手时误触发展开）。
                    // v1.7.0 · P0：顺便测一次手势速度（px/s）交给吸附判定做「甩动」判据。
                    var dragStartMs = 0L
                    var lastMs = 0L
                    var firstMove = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        if (!change.pressed) break
                        change.consume()
                        if (firstMove) {
                            dragStartMs = change.uptimeMillis
                            firstMove = false
                        }
                        lastMs = change.uptimeMillis
                        val dragAmount = change.position.y - change.previousPosition.y
                        if (dragAmount != 0f) {
                            dragProgress = (dragProgress - dragAmount / totalDragDistancePx).coerceIn(0f, 1f)
                            val snapshot = dragProgress
                            coroutineScope.launch { progress.snapTo(snapshot) }
                        }
                    }
                    // 速度用 progress 域反推（px/s）：本节点挂在被 graphicsLayer 平移的卡片里，
                    // 拖动过程中局部坐标随卡片一起移动，直接用 position 差分会低估速度。
                    // progress 与像素的换算是已知的（totalDragDistancePx），换算回来既准又稳。
                    val elapsedMs = lastMs - dragStartMs
                    val movedProgress = dragProgress - startProgress
                    val velocityY = if (elapsedMs > 0L)
                        movedProgress * totalDragDistancePx / (elapsedMs / 1000f) else 0f
                    coroutineScope.launch {
                        // v1.7.0 · P0：行程阈值 + 甩动（原实现要 progress 过中点 = 340dp，用户拖不动）
                        val target = PlayerCardDragSnap.target(startProgress, dragProgress, velocityY)
                        progress.animateTo(
                            target,
                            if (target == 1f)
                                tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                            else
                                tween(durationMillis = 260, easing = FastOutSlowInEasing)
                        )
                    }
                }
            }
            // 把上面 padding 让出的高度补回去：子节点仍从根 Box 顶开始（视觉/布局不变），
            // 只有两个 pointerInput 留在下移后的命中区里。
            .then(if (collapsedHitGate) Modifier.offset(y = -hitGateInsetDp) else Modifier)
    ) {
        // 全屏纯黑背景。展开时 translationY=0，卡片顶与封面顶/内容区顶等高；
        // 折叠时整体下移一个 statusBar 高，使顶边对齐 miniBar 内容。miniBar 自带
        // statusBarsPadding（内容被下推 statusBar 高），背景若不跟着下移，miniBar
        // 上方就会多出一条 statusBar 高的 surface 色块——即"手机 miniBar 变高一片"。
        // statusBarPx 在车机(WindowInsets=0)为 0，天然不影响车机。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = statusBarPx * (1f - progress.value)
                }
                .background(LocalMetroColors.current.background)
        )
        // 折叠态卡背：卡片整体下移后，miniBar 下方露出的是这张黑底（原底部导航/系统栏
        // 位置）。折叠时用 surface 盖住，与 miniBar 同色，避免底部黑块；展开时透明。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
                    translationY = statusBarPx * (1f - progress.value)
                }
                .background(LocalMetroColors.current.surface)
        )

        // ── v2.9.0：全屏动效背景层（竖屏 + 横屏共用同一个实现）────────────────────────
        //
        // 位置：两张纯色底板**之上**、Column（封面/歌词/控件等全部前景）**之下**。
        // 所以它是真正的"背景"：z 序最低的前景内容全都浮在它上面，且它自己没有任何
        // pointerInput ⇒ 不新增任何命中面（AGENTS.md 触摸陷阱第 1 条）。
        //
        // 关掉「界面动效」总开关、或自动降级到 UI_ALL_OFF 时**整层不挂载** ——
        // 不是 `alpha = 0`（那仍然参与绘制与命中测试）。铁律「关掉 = 零开销」由结构保证。
        if (motion.anyUiMotion) {
            MotionBackdrop(
                coverKey = coverArtKey,
                coverBitmap = coverArtBitmap,
                motion = motion,
                parallaxProvider = { progress.value },
                // 视差行程按屏幕高给（不是固定 dp）：横竖屏切换时行程跟着屏幕走，
                // 两处尺寸来源（LocalConfiguration 与 PlayerCard）不会打架。
                parallaxTravelPx = screenHeightPx * AppMotion.PARALLAX_TRAVEL_FRACTION,
                modifier = Modifier.graphicsLayer {
                    // 与前景同样的折叠淡出曲线（复用既有 (p-0.7)/0.3 的形状）。
                    alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
                },
            )
        }

        // ── v2.9.0 · 唯一的帧时钟（波形 + 界面动效共用一条循环）──────────────────────
        // 为什么必须挂在这里：竖屏手机上波形组件根本不挂载（`visualizerSlot` 恒 false），
        // 而背景呼吸/节拍脉冲/粒子在竖屏也要跑。两处各起一条循环会让动画相位每帧前进两次。
        // 详见 `ui/player/motion/MotionClock.kt` 的 KDoc。它不产生任何 UI 节点。
        MotionFrameClock(
            activeProvider = { isPlaying && !playerViewModel.isBuffering.value },
            enabled = hasSong && expandedMounted,
        )

        // ── v2.9.0 · B 档：**背景级波形**（横屏铺满底部横跨全屏 / 竖屏在歌词后面流动）──
        //
        // 它解决的问题是任务书 §7.2 的原话：「波形从左侧扩展到全屏底部横跨」。
        // 实现上**不动任何既有布局**（§7.3）：左栏那条波形原样留在原地，
        // 这一条是**额外**的一层背景 —— 所以"不改变现有横屏布局结构"这条要求由结构保证，
        // 而不是靠"改得小心"。
        //
        // 复用 `AudioVisualizerBars` 而不是再写一份画法：A/B/C 三档的渲染差异
        // （圆角柱 / 峰值 / 渐变流动 / 光点 / 冲击波 / 粒子 / 3D）都已经在那里，
        // 复制一份必然分叉。绘制成本是每帧多一次 Canvas（B 档默认关，S6 这类低端机
        // 默认简洁档 ⇒ 这一层根本不挂载）。
        if (motion.fullScreenWaveform) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(waveBackdropHeightDp),
            ) {
                // 压暗到 WAVE_BACKDROP_ALPHA：背景级波形的职责是"有东西在流动"，
                // 抢过前景就违反了 §7.3「歌词可读性优先」。前景内容全部画在它之上。
                AudioVisualizerBars(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) *
                                WAVE_BACKDROP_ALPHA
                        },
                )
                // v2.9.0（真机观感修正）：**上沿渐隐**。
                // 平铺的一条带子在真机上是一条硬边（PCL110 截图复核：横跨整屏的一条直线，
                // 看起来像渲染错误而不是设计）。这里在带子的上半部压一层由不透明到透明的
                // 渐变，波形因此"从背景里浮出来"而不是"被裁了一刀"。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to LocalMetroColors.current.background,
                                1.0f to Color.Transparent,
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // B-1：折叠态不挂载展开态子树（mini bar 在下面、永远挂载）。见 expandedMounted 注释。
            if (hasSong && expandedMounted) {
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
                        showLyrics = showLyrics,
                        showQueue = showQueue,
                        progressFlow = playerViewModel.progress,
                        positionFlow = playerViewModel.currentPosition,
                        durationFlow = playerViewModel.duration,
                        qualityIndexFlow = playerViewModel.currentQualityIndex,
                        qualityStatusFlow = playerViewModel.qualityStatus,
                        qualityOptions = strings.qualityOptions,
                        onPlayPause = onPlayPause,
                        onPlayPrevious = onPlayPrevious,
                        onPlayNext = onPlayNext,
                        onToggleLyrics = {
                            val nowReady = lyricsReady
                            showLyrics = !showLyrics
                            showQueue = false
                            // 未就绪(失败/尚未成功)时点击 = 触发一次重新加载，
                            // 不必切歌才能恢复歌词。
                            if (!nowReady && !lyricsLoading) playerViewModel.retryLyrics()
                        },
                        onToggleQueue = {
                            showQueue = !showQueue
                            showLyrics = false
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
                                libraryTick++
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
                        landscape = usesSideCover,
                        compact = usesSideCover,
                        // 大屏把音质搬到左栏就地选择器，控制条右端不再重复一个音质角标。
                        showQuality = !bigScreenActive,
                        // 大屏幕模式开关（入口/出口同一个回调、同一个图标语义）。
                        // 竖屏时它在下方那排操作按钮里（第 4 个，SpaceEvenly）；
                        // 横屏大屏时它在控制条右端（音质让出来的位置）—— 竖屏那排的
                        // 第 4 个在横向三段式布局里会顶到居中的传输组（实测与"上一首"重叠）。
                        bigScreen = bigScreenActive,
                        onToggleBigScreen = onToggleBigScreen,
                        // v2.5.5 · E：平板的横向控件条里补一个 ⤢ 入口。
                        // 判据是纯函数（PlayerLayout.bigScreenEntrySlot），A/B 矩阵写在它的 KDoc 上：
                        // 只有「平板 + 不在大屏」两格挂载，手机横屏一格都不变。
                        showBigScreenEntry = PlayerLayout.bigScreenEntrySlot(
                            isLargeScreen = isLargeScreen,
                            bigScreenActive = bigScreenActive,
                        ),
                        // v1.8.0 · T4：竖屏控制栏里的自动旋转图标。
                        autoRotate = autoRotate,
                        onToggleAutoRotate = onToggleAutoRotate,
                        // v1.8.0 · T2：竖屏 / 宽屏控制条的音质选择器。
                        preferredQualityIndexProvider = {
                            playerViewModel.currentQualityPreferenceIndex()
                        },
                        onQualitySelect = { playerViewModel.setQualityPreference(it) },
                        trailing = if (bigScreenActive) {
                            {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { onToggleBigScreen() },
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
                                    val q = queueSlideProgress.value
                                    alpha = lyricAnimProgress.value * if (q < 0.01f) 1f else 0f
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
                                    isVisible = showLyrics,
                                    forcedLocateTrigger = lyricLocateTrigger,
                                    onSeekToMs = { ms -> playerViewModel.seekTo(ms) },
                                    enabled = lyricsEnabled && cardExpandedForInput,
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
                                    centeredLayout = usesSideCover,
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
                                    val q = queueSlideProgress.value
                                    alpha = lyricAnimProgress.value * if (q > 0.01f) 1f else 0f
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
                                MetroIconButton(onClick = onTogglePlayMode) {
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
                                MetroIconButton(onClick = onSavePlaylist) {
                                    MetroIcon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = strings.saveAsPlaylist,
                                        tint = LocalMetroColors.current.onBackground,
                                        sizeDp = 24.dp
                                    )
                                }
                                // 清空队列: 停播并回到暂无播放态
                                MetroIconButton(onClick = onClearQueue) {
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
                                isActive = showQueue,
                                interactive = cardExpandedForInput,
                                queueHeaderHeightPx = queueHeaderHeightPx,
                                onPlayIndex = onPlayFromQueue,
                                onRemoveIndex = onRemoveFromQueue,
                                onMove = onMoveInQueue
                            )
                        }
                    }
                }

                when {
                    bigScreenActive -> {
                        // ---- P1 大屏幕模式（横屏桌面播放器布局）----
                        // 左栏 = 大封面（尽量占满左栏可用高度、保持正方形）+ 歌名/作者 + 音质
                        //        （就地切换档位）；右栏 = 歌词·队列（**复用既有面板，歌词引擎
                        //        一个字都没重写**）+ 底部扁平控制条。
                        // 为什么 transport 不放左栏：旧的"宽屏竖屏"形状把封面 + 歌名 + 控件全塞
                        // 在左栏，手机横屏可用高只有 ~300dp（PCL110 实测 1272px 高、扣掉左右
                        // 系统栏与挖孔），封面被挤到 ~170dp，比竖屏还小。把控制条移到右栏底部
                        // 之后，左栏只剩「歌名 + 音质」一条约 44dp 的行，封面能拿到 ~230dp。
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                // 外层 Column 已经吃过 systemBarsPadding()（横屏时系统栏贴在
                                // **左右两侧**），这里再补 displayCutout —— 嵌套的
                                // windowInsetsPadding 会自动扣掉父级已消费的 inset（Compose
                                // InsetsPaddingModifier 的 exclude 语义），所以挖孔与同侧
                                // 状态栏等宽（PCL110 实测都是 141px）时不重复留白，挖孔更宽
                                // （部分 ROM）时也不会被压住。
                                // 注：Kanesumi 的 rememberMetroInsets() 表达不了横屏的左右
                                // 系统栏 —— MetroInsets.statusBar/navigationBar 只有
                                // calculateTopPadding / calculateBottomPadding 两条边
                                // （Kanesumi MetroInsets.kt:57-63），故此处用 displayCutout。
                                .windowInsetsPadding(WindowInsets.displayCutout)
                        ) {
                            // ===== 左栏 =====
                            Column(
                                modifier = Modifier
                                    .weight(PlayerLayout.BIG_SCREEN_LEFT_FRACTION)
                                    .fillMaxHeight()
                            ) {
                                // 封面区：只作为「唯一封面 overlay」的落点参考（与宽屏两栏同一
                                // 机制，绝不在这里放第二个封面）。正方形边长 = min(区宽, 区高)
                                // ⇒ 封面总是"尽量占满左栏可用高度"。
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .onGloballyPositioned { coords ->
                                            val b = coords.boundsInRoot()
                                            wideCoverCenter = b.center - cardRootOrigin
                                            wideCoverSizePx =
                                                PlayerLayout.squareCoverSizePx(b.width, b.height)
                                        }
                                )
                                // v1.8.0 · T3：音频可视化条（**封面下、歌名/作者上**）。
                                // 高度按窗口高动态算：PCL110 横屏只有 363dp 可用高，
                                // 固定 48dp 会把封面压掉一整圈；S6（480dp）则给足 53dp。
                                // 封面区是 weight(1f)，自己吸收这段高度 —— 任何一级都
                                // 不会溢出屏幕（这正是"按可用高度动态计算"的落点）。
                                //
                                // v2.5.4 · D：挂载判据收敛进 PlayerLayout.visualizerSlot，
                                // 大屏分支与下面的宽屏两栏分支**共用同一份实现**（一个挂载
                                // 落点函数 + 一个 slot composable），避免两处各写一份 if。
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
                                            s.name,
                                            color = LocalMetroColors.current.onBackground,
                                            style = LocalMetroTypography.current.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // v2.1.0 · F：音源角标（大屏左栏，两个音源都标）。
                                        ArtistLineWithSource(
                                            song = s,
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
                                    // UWP ComboBox 移植：选中档位落回 chip 原位，菜单从锚点展开。
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
                                    // v1.8.0 · T4：大屏里的自动旋转开关。放在左栏信息行
                                    // （音质 chip 右侧）而不是控制条：横向控制条是"左组贴左 +
                                    // 传输组居中 + 右组贴右"的三段式，左组已经 3 个按钮，
                                    // 再加一个会顶到居中的传输组（P1 实测过这个重叠）。
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
                    isWidePlayer -> {
                    // 宽屏：Row 两栏，区域化布局（weight 分区，无绝对定位 / 魔法数字）。
                    // 左栏 = 封面区(weight 撑满剩余) + 歌名 + 控件；右栏 = 歌词·队列占满整轴。
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(wideLeftFraction)
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
                                        wideCoverCenter = b.center - cardRootOrigin
                                        wideCoverSizePx = minOf(b.width, b.height)
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
                                        s.name,
                                        color = LocalMetroColors.current.onBackground,
                                        style = LocalMetroTypography.current.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // v2.1.0 · F：音源角标（宽屏左栏，两个音源都标）。
                                    ArtistLineWithSource(
                                        song = s,
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
                                .weight((1f - wideLeftFraction).coerceAtLeast(0.0001f))
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
                    else -> {
                    // 窄屏：顶部标题栏 + 整宽面板 + 底部控件（保持原行为）。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                            .padding(start = 68.dp, end = 56.dp),
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
                                s.name,
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
                                song = s,
                                color = LocalMetroColors.current.onSurfaceVariant,
                                style = LocalMetroTypography.current.bodyMedium,
                                badgeStyle = LocalMetroTypography.current.bodySmall
                            )
                        }
                    }
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
                                s.name,
                                color = LocalMetroColors.current.onBackground,
                                style = LocalMetroTypography.current.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // v2.1.0 · F：音源角标（窄屏大封面 overlay，两个音源都标）。
                            ArtistLineWithSource(
                                song = s,
                                color = LocalMetroColors.current.primary,
                                style = LocalMetroTypography.current.bodyLarge,
                                badgeStyle = LocalMetroTypography.current.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { c ->
                                // 只在展开态实测（收起后高度会被收成 0，别把度量值也带偏）：
                                // 高度决定"从布局里收走多少"，上沿决定整卡拖拽在哪让路。
                                if (controlsCollapse.value < 0.01f) {
                                    controlsHeightPx = c.size.height.toFloat()
                                    controlsTopInCardPx = c.boundsInRoot().top - cardRootOrigin.y
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
                                ) { toggleControlsCollapse() }
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
                }   // end when { 大屏 / 宽屏两栏 / 窄屏 }
            }
        }

        // 迷你播放栏叠加层：始终在 Composition 中，透明度仅在绘制阶段控制，避免动画期间触发重组
        //
        // v2.5.5 · C：高度 56 → `TrayLayout.HEIGHT_DP`（80dp）—— 三层文本（歌词 / 歌名 /
        // 作者·音源）的排版盒合计 56dp，56dp 的托盘上下各只剩 0dp 留白（真机实测，
        // 见 docs/verification/v2.5.5/probe-tray-regression.md §3）。
        // 这个高度有**三个**消费者（本行 / MainActivity.collapsedOffsetY / BottomOverlayInsetDp），
        // 所以它只有 TrayLayout 一个定义处。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TrayLayout.HEIGHT_DP.dp)
                .then(
                    // 暂无播放（hasSong=false）时不可点击展开，避免空白播放器被拉起。
                    if (miniBarEnabled && hasSong) Modifier.clickable(
                        interactionSource = miniBarInteractionSource,
                        indication = null,
                        onClick = {
                            coroutineScope.launch {
                                progress.animateTo(
                                    1f,
                                    tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                                )
                            }
                        }
                    ) else Modifier
                )
                .graphicsLayer {
                    alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
                }
                .background(LocalMetroColors.current.surface)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasSong) {
                    val s = song!!
                    // 唯一封面 overlay 会落到这个方形占位处（窄屏与宽屏一致）。
                    //
                    // v2.5.5 · C：占位从「与托盘等高」改成 TrayLayout 的**定值 56dp**
                    // （托盘本身已涨到 80dp）。Row 的 CenterVertically 让它垂直居中，
                    // 中心点与 miniCoverCenterY = statusBar + HEIGHT/2 逐像素一致。
                    // 若仍用 fillMaxHeight().aspectRatio(1f)，封面会变成 80dp，
                    // 在 360dp 窄屏上把文本列再挤掉 24dp（136 → 112dp），而封面并不需要它。
                    Spacer(modifier = Modifier.size(TrayLayout.COVER_SIZE_DP.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        // ===== 第一行：实时歌词（v2.5.4 · E）=====
                        //
                        // 数据源是 `lyrics` + `currentPosition`（2Hz）+ 已存在的纯函数
                        // `currentLineIndex`，**不新建歌词引擎、不复用通知那条路**：
                        //  · `PlaybackService.mediaLyricLine` 被 `lyrics_in_media_session`
                        //    开关（默认关）挡着，而且它是个不可观察的 `@Volatile var`，
                        //    写它还会顺手重发一次通知 —— 为了托盘把它打开等于改用户的通知行为；
                        //  · 面板那条「按需唤醒」循环的唤醒表细到**词**边界，托盘只要行级。
                        //
                        // 订阅发生在 TrayLyricLine **内部**（叶子），不在这里：
                        // 在 PlayerCard 组合期 collect 2Hz 的位置流会让整棵播放器子树
                        // 每 500ms 重组一次（既有注释反复 warn 过同一件事）。
                        TrayLyricLine(
                            lyricsFlow = playerViewModel.lyrics,
                            positionFlow = playerViewModel.currentPosition,
                            style = LocalMetroTypography.current.bodySmall,
                            primaryColor = LocalMetroColors.current.primary,
                            mutedColor = LocalMetroColors.current.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    // 点歌词 = 展开播放器**并直接看歌词**（面板已就绪时
                                    // 不会与 autoSwitchedForSong 那条自动切换规则打架：
                                    // 它只在「本曲还没自动切过」时写 showLyrics，
                                    // 而我们这里是用户的显式意图，谁先谁后都是 true）。
                                    coroutineScope.launch {
                                        progress.animateTo(
                                            1f,
                                            tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                                        )
                                    }
                                    showQueue = false
                                    showLyrics = true
                                }
                        )
                        // ===== 第二行：歌名（v2.5.5 · C：独占一行）=====
                        //
                        // v2.5.4 里歌名与作者/音源挤在同一行、三者各带 `weight(1f, fill = false)`，
                        // 于是「谁被省略」取决于服务端返回的字符串长度 —— 长歌名会把作者挤成
                        // 一个字。三层布局把歌名**独占**一行，它不再与任何东西抢宽度。
                        //
                        // 歌名**不做成独立点击区**：整条托盘的点击本来就是「展开播放器」，
                        // 给它再包一层同名行为的 clickable 只会多一个命中层
                        // （见 AGENTS.md 触摸陷阱第 5/6 条：多出来的命中层是最难查的一类问题）。
                        // 「点歌名 → 展开播放器」由外层 Box 的 clickable 承载，行为逐字相同。
                        Spacer(Modifier.height(TrayLayout.LINE_GAP_DP.dp))
                        MetroText(
                            s.name,
                            color = LocalMetroColors.current.onBackground,
                            style = LocalMetroTypography.current.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        // ===== 第三行：作者（左）… 音源（右）（v2.5.5 · C）=====
                        //
                        // 左右对齐靠两个 `weight(1f)` 之间的 `Spacer` 撑开：作者的 weight
                        // 让它吃掉「作者名之后的全部剩余宽度」，音源角标因此永远贴右。
                        // v2.5.4 是两段相邻的 `weight(1f, fill = false)`，中间没有撑开物，
                        // 角标实际贴在作者名后面（真机截图可见：`苏谭谭QQ音乐` 之间只有 6dp）。
                        //
                        // 音源角标**不加 clickable** —— 判据见 probe-tray-layout.md §6.1
                        // （跨源换播需要跨源身份，v2.3.0 已判决接口里没有；「看信息」与
                        // onSongInfoClick 语义重叠；「切默认音源」是设置页的职责）。
                        Spacer(Modifier.height(TrayLayout.LINE_GAP_DP.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val artist = s.artists?.firstOrNull()
                            if (artist != null) {
                                MetroText(
                                    artist.name,
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = LocalMetroTypography.current.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            // v2.6.1 · P0：把**整首歌**交出去，不再在这里
                                            // 按 `id != null` 分流。理由有两条：
                                            //  ① 用 `id` 判断「能不能进艺人页」是错的判据 ——
                                            //     QQ 曲目的数字 id 恒存在，但它不是网易云的 id；
                                            //  ② 冷启动恢复的曲目 `id` 恒为 null，而它**恰恰**
                                            //     是最该跳搜索（而不是弹一个点了也没反应的菜单）的那一种。
                                            // 身份判定收在 ArtistNavigator 一处，这里只做转发。
                                            onArtistClick(s)
                                        }
                                )
                            } else {
                                // 没有艺人信息时也要占住左半边，否则音源角标会滑到最左 ——
                                // 「右对齐」是布局契约，不该随数据缺失而变。
                                Spacer(Modifier.weight(1f))
                            }
                            // v2.1.0 · F：音源角标（折叠态 mini bar，两个音源都标）。同字号
                            // （bodySmall）+ 次要色，与列表行逐像素同款；角标定宽、歌手让位省略。
                            ArtistLineWithSource(
                                song = s,
                                color = LocalMetroColors.current.onSurfaceVariant,
                                style = LocalMetroTypography.current.bodySmall,
                                badgeStyle = LocalMetroTypography.current.bodySmall,
                                // 歌名与作者已经由上面两段画过，这里只要角标那一段。
                                showArtist = false
                            )
                        }
                    }
                    // ===== 控制区：上一首 / 播放暂停 / 下一首（v2.5.5 · C）=====
                    //
                    // 铁律 19：三个按钮都是核心功能，缺一个属 P0 回归。
                    // **上一首是本版补的** —— 探针证明它从来没有在托盘里存在过
                    // （`git log -S "SkipPrevious" -- PlayerCard.kt` 零命中；v2.5.3 的托盘
                    // 也只有 play/pause 与 next），所以这不是「回归修复」而是「补功能」。
                    // 定性与证据见 docs/verification/v2.5.5/probe-tray-regression.md §0。
                    //
                    // 顺序走 TrayLayout.controls（PREVIOUS / PLAY_PAUSE / NEXT），
                    // 由 TrayLayoutTest 断言存在性、顺序与「一个都不能少」。
                    //
                    // 收起态才挂载：展开态三个按钮若仍在，会与歌词面板的字号按钮抢命中区
                    // （AGENTS.md 触摸陷阱第 4 条：隐藏必须走「不挂载」）。
                    if (miniBarEnabled) {
                        val onBackground = LocalMetroColors.current.onBackground
                        // 上一首
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayPrevious()
                        }) {
                            MetroIcon(
                                imageVector = Icons.Default.SkipPrevious,
                                // 无障碍：三个图标按钮只有形状可辨，contentDescription 是
                                // TalkBack 用户唯一的识别途径（旧代码两个按钮都传 null，
                                // 是既有缺口，本版一并补上）。
                                contentDescription = strings.prevButton,
                                tint = onBackground
                            )
                        }
                        // 播放 / 暂停
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayPause()
                        }) {
                            MetroIcon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                                tint = onBackground
                            )
                        }
                        // 下一首
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayNext()
                        }) {
                            MetroIcon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = strings.nextButton,
                                tint = onBackground
                            )
                        }
                    } else {
                        // 展开态：文本列可用宽度必须与收起态**逐像素相同**，
                        // 否则展开/收起动画会让文字重排。宽度由 TrayLayout 派生
                        // （旧实现写死 96.dp = 两个按钮，加第三个按钮后会少 48dp）。
                        Spacer(modifier = Modifier.width(TrayLayout.controlsPlaceholderWidthDp().dp))
                    }
                } else {
                    // 暂无播放: 卡片仍不可拉起(保持既有约束), 但给一个播放键
                    // 直接开始 Infinity——取每日推荐开播, 无需先有队列
                    MetroText(
                        strings.noSongPlaying,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    if (miniBarEnabled) {
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayNothing()
                        }) {
                            MetroIcon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = LocalStrings.current.playButton,
                                tint = LocalMetroColors.current.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        }

        // 唯一封面叠加层：全屏 ↔ miniBar 共用这一个 cover，随 progress 平滑位移/缩放。
        // 窄屏大图=整屏宽居中；宽屏大图=左栏封面区实测中心与尺寸。
        if (hasSong) {
            val s = song!!
            StableCover(
                model = CoverUrls.large(s.album?.picUrl),
                contentDescription = null,
                placeholderColor = LocalMetroColors.current.surfaceVariant,
                // v2.9.0 · A/C 档：封面浮起阴影 / 随节拍微浮动 / 3D 旋转 / 切歌淡入。
                // 全部由 `motion` 这一个能力位对象驱动，关掉时这三行的效果与 v2.8.0 逐像素一致。
                shadowElevation = if (motion.coverElevation) AppMotion.COVER_SHADOW_ELEVATION_DP else null,
                crossfadeEnabled = motion.coverTransition,
                onCoverBitmap = { key, bitmap ->
                    // 只在真的换了图/键时写 state（这个回调会在每次 Coil 状态变化时被调到，
                    // 无条件赋值会让每次状态变化都触发一次背景层重组）。
                    if (key != coverArtKey || bitmap !== coverArtBitmap) {
                        coverArtKey = key
                        coverArtBitmap = bitmap
                    }
                },
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
                        // A 档的「封面随节拍微微浮动」也读它 —— 帧时钟在 A 档本来就要跑
                        // （背景呼吸是逐帧的），所以这里不新增任何成本。
                        val beatPulse = if (
                            motion.coverElevation || motion.beatPulse || motion.cover3d
                        ) {
                            MotionClock.generation
                            MotionClock.pulse()
                        } else 0f
                        // 侧栏布局（宽屏两栏 / 大屏左栏）封面恒为大图（缩到 mini 是窄屏
                        // "大封面↔歌词"切换的语义）；它随分栏进度在左栏与居中之间平滑移动。
                        val lyricAnimValue = if (usesSideCover) 0f else lyricAnimProgress.value

                        val targetCenterX = largeCoverCenterX + lyricAnimValue * (miniCoverCenterX - largeCoverCenterX)
                        val targetCenterY = largeCoverCenterY + lyricAnimValue * (miniCoverCenterY - largeCoverCenterY)
                        val targetScale = miniScale + (1f - lyricAnimValue) * (1f - miniScale)

                        val currentCenterX = miniCoverCenterX + normalizedP * (targetCenterX - miniCoverCenterX)
                        val currentCenterY = miniCoverCenterY + normalizedP * (targetCenterY - miniCoverCenterY)
                        val currentBaseScale = miniScale + normalizedP * (targetScale - miniScale)

                        scaleX = currentBaseScale
                        scaleY = currentBaseScale
                        translationX = currentCenterX - boundsCenter
                        // A 档：随节拍**上浮**最多 ±2dp（任务书 §4.3）。用位移而不是缩放：
                        // 缩放会与上面那条展开/收起动画的 scale 叠加，出问题时无法归因。
                        val floatPx = if (motion.coverElevation) {
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

        // 收起按钮叠加层：z 序最高，保证触摸事件不被任何下层元素拦截。
        // P1：大屏幕模式下**整层不挂载**：
        //  ① 大屏没有"收起卡片"这个动作（出口是同一按钮 / 返回键 / 转回竖屏），
        //     留一个收起键只会让用户掉进"横屏 + 卡片收起"的怪状态；
        //  ② 它是全宽 56dp + CenterEnd 的叠加层，实测与右栏歌词面板右上角的 A-/A+
        //     字号按钮**命中区重叠**（PCL110：A+ 2569..2723px，收起键 2604..2772px），
        //     而它在 z 序更上 ⇒ 点 A+ 的右半边会被它抢走并收起卡片。
        // 大屏幕模式的入口/出口都放在控制条的操作行里（见 FullPlayerControls.onToggleBigScreen）。
        if (hasSong && !bigScreenActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissEnabled) {
                    MetroIconButton(onClick = onDismiss) {
                        MetroIcon(Icons.Default.KeyboardArrowDown, strings.collapsePlayer, tint = LocalMetroColors.current.onBackground)
                    }
                }
            }
        }
    }
}

/**
 * v1.4.0 · A：按 [collapse]（0..1）把本节点**在布局里占的高度**收走。
 *
 * 与 graphicsLayer 平移配合使用：布局高度收走 → 兄弟节点（面板，weight 1f）顺势长高；
 * 内容本身由外层 graphicsLayer 平移出屏幕。这里在 layout 阶段直接读 Animatable，
 * **不触发 recomposition** —— 动画帧只重排这一棵子树。
 */
private fun Modifier.collapsibleHeight(collapse: Animatable<Float, AnimationVector1D>): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val shrink = (collapse.value.coerceIn(0f, 1f) * placeable.height).roundToInt()
        layout(placeable.width, (placeable.height - shrink).coerceAtLeast(0)) {
            placeable.place(0, 0)
        }
    }

// v2.9.0 · B 档：背景级波形的不透明度。0.32 的依据：在 OLED 黑底上 32% 的主题色
// 已经能明确读出"整块背景在流动"，而压在它上面的歌词行（onBackground 全白）
// 对比度仍远高于 WCAG AA 的 4.5:1 —— 任务书 §7.3「歌词可读性优先」。
private const val WAVE_BACKDROP_ALPHA = 0.26f

// 新封面超过该阈值仍未就绪，才退化为纯色占位（"实在不出来再禁用"）。
private const val COVER_HOLD_MS = 400L

/**
 * 切歌不闪的封面。Coil 的 [AsyncImagePainter] 在 model 变化时先进入 loading 态、
 * 画 placeholder（纯色），新图没秒出就会闪一下占位色。这里改为：
 *  - 记住最近一次成功加载的封面，切歌换图期间先沿用旧图（视觉无缝）；
 *  - 新图在 [COVER_HOLD_MS] 内就绪 → 直接换上新图；
 *  - 超过阈值仍未就绪 → 才退化为占位色。
 *
 * ## v2.5.0 · B：圆角 + 边框**必须加在这里，不能在调用点加**
 *
 * 这个 Box 的调用点（`PlayerCard` 的封面叠加层）把整个节点放在卡片布局的 (0,0)，
 * 视觉位置**全部**由它自己的 `graphicsLayer { translationX/Y/scale }` 搬过去。
 * 因此：
 *
 *  - 若把 `Modifier.clip(...)` 加在调用点、且排在那个 `graphicsLayer` **之前**
 *    （即更外层），裁切层的边界是**未被平移的布局边界**（屏幕左上角那块），
 *    移动过去的封面会被整块裁掉 —— 这是"加了圆角结果封面不见了"的形状；
 *  - 正确做法是让裁切发生在**层内**：要么写进同一个 `graphicsLayer` 的
 *    `shape` + `clip`，要么像这里一样加在**调用点 modifier 之后**（= 层的内侧）。
 *    本实现选后者，因为它同时还要画描边，且 `border` 与 `clip` 必须共用同一个 shape。
 *
 * ## mini bar 的取舍（如实记录）
 *
 * 大封面与 mini bar 封面是**同一个节点**：mini 态是整层被 `scale` 到 56dp 的结果。
 * 所以圆角与描边会**等比缩放** —— mini 态下 16dp 圆角渲染成约 2.5dp、
 * 1dp 描边渲染成约 0.16dp（基本不可见）。
 *
 * 这是**有意接受**的，不是遗漏。要让它不缩放，就得在动画的每一帧改
 * `graphicsLayer.shape` 并把描边宽度按 `1/scale` 反算 ——
 * 那会让播放器展开/收起这条**播放链路上的关键动画**每帧重建图层属性，
 * 与铁律 15（动效不得影响播放性能）冲突，代价明显大于收益。
 * 「给 mini bar 一个独立的封面渲染点」是将来可选的方案，本版不做。
 */
@Composable
private fun StableCover(
    model: Any?,
    contentDescription: String?,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** v2.5.0 · B：圆角。默认 `AppShapes.large`（播放页封面是"大封面"档）。 */
    shape: Shape = AppShapes.large,
    /** v2.5.0 · B：描边色。传 `null` 表示不画描边（留给将来的"无边框"场景）。 */
    frameColor: Color? = null,
    /**
     * v2.9.0 · A 档：浮起阴影的高度。`null` = 不画阴影（= v2.8.0 的观感）。
     *
     * **必须传 `AppShapes.large` 作为 shape**（见下面的实现）：阴影与裁切/描边共用同一个
     * shape 才不会在圆角处露出错位的方角。
     */
    shadowElevation: androidx.compose.ui.unit.Dp? = null,
    /** v2.9.0 · A 档：切歌时是否淡入（`AppMotion.coverFade`）。`false` = v2.8.0 的硬切。 */
    crossfadeEnabled: Boolean = false,
    /**
     * v2.9.0 · A 档：把「当前正在显示的封面位图 + 它的缓存键」上抛给调用方。
     *
     * 为什么需要这个回调：背景模糊的输入必须是**前景正在显示的那张图**。这个 Box 是
     * 全仓库唯一持有它的地方（`lastBitmap` 跨切歌保留）。在别处重新解码会多一份内存，
     * 而且可能与前景不是同一张（Coil 缓存被清 / 请求尺寸不同）。
     *
     * `key` 传 `null` 表示"当前没有可用封面"（调用方据此回退纯色背景）。
     */
    onCoverBitmap: (key: String?, bitmap: Bitmap?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    // 阴影颜色：纯黑而不是主题色。阴影的职责是"分层"，用主题色会让每张封面
    // 都投出一圈品牌色的光晕 —— 与 v2.5.0「颜色应当来自封面本身」的取色目标冲突。
    val shadowColor = Color.Black
    val painter = rememberAsyncImagePainter(
        model = model,
        imageLoader = Coil.imageLoader(context),
    )
    val state = painter.state
    // 最近一次成功加载的封面位图（跨切歌保留）。
    var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // v2.9.0 · A 档：这幅位图对应的缓存键（背景模糊用 URL 作键；与位图同时更新，
    // 保证背景与前景永远是同一首歌的图）。
    var lastKey by remember { mutableStateOf<String?>(null) }
    // v2.9.0 · A 档：切歌淡入。**不卸载任何子树** —— 只驱动上层 Image 的 alpha。
    // v2.6.0 的教训是 `AnimatedContent` 会在切换期把旧子树卸载掉（状态丢失 + 命中区抖动），
    // 这里刻意用一个 `Animatable` + `graphicsLayer` 实现同一观感，子树始终挂载。
    val crossfade = remember { Animatable(1f) }
    val modelKey = model as? String
    // 新图超过阈值仍未就绪 → 退化占位色。
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        val s = state
        if (s is AsyncImagePainter.State.Success) {
            (s.result.drawable as? BitmapDrawable)?.bitmap?.let {
                lastBitmap = it
                lastKey = modelKey
                // 上抛给背景层。同键同实例时不重复回调（避免每次状态变化都重组背景层）。
                onCoverBitmap(modelKey, it)
            }
            timedOut = false
        }
    }
    LaunchedEffect(model) {
        timedOut = false
        delay(COVER_HOLD_MS)
        if (painter.state !is AsyncImagePainter.State.Success) {
            timedOut = true
            // 超时且没有旧图可垫 ⇒ 当前确实没有封面，背景层据此回退纯色。
            if (lastBitmap == null) onCoverBitmap(null, null)
        }
    }
    // v2.9.0 · A 档：model 变化 ⇒ 淡入一次。关掉时 snap 到 1f（= 与 v2.8.0 逐帧一致）。
    LaunchedEffect(model, crossfadeEnabled) {
        if (!crossfadeEnabled) {
            crossfade.snapTo(1f)
            return@LaunchedEffect
        }
        crossfade.snapTo(0f)
        crossfade.animateTo(1f, AppMotion.coverFade)
    }

    // ⚠️ 顺序：`modifier`（含调用点的 graphicsLayer 变换）在前，clip/border 在后。
    // Compose 的修饰符链是"先写的在外层"，所以这里是 **变换在外、裁切在内** ——
    // 裁切发生在层内、随后被整体平移/缩放，这正是上面 KDoc 说明的正确顺序。
    // 反过来写会把封面裁在屏幕左上角。
    Box(
        modifier = modifier
            // v2.9.0 · A 档：浮起阴影。**在 clip 之前**（= 更外层），这样阴影画在裁切层之外、
            // 跟随同一个 `graphicsLayer` 变换一起移动/缩放；顺序反过来阴影会被自己裁掉。
            // `clip = false` 是有意的：裁切交给下面的 `clip(shape)`，两处都裁会多一层离屏缓冲。
            .then(
                if (shadowElevation != null) {
                    Modifier.shadow(
                        elevation = shadowElevation,
                        shape = shape,
                        clip = false,
                        ambientColor = shadowColor,
                        spotColor = shadowColor,
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .then(
                if (frameColor != null) Modifier.border(1.dp, frameColor, shape) else Modifier
            )
    ) {
        // 底层：切歌后旧图垫底（超阈值退化占位色）。
        val bg = if (timedOut) null else lastBitmap
        if (bg != null) {
            Image(
                painter = remember(bg) { BitmapPainter(bg.asImageBitmap()) },
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        } else {
            Box(Modifier.matchParentSize().background(placeholderColor))
        }
        // 上层：当前请求的 painter。必须真正绘制它，Coil 才会在 onRemembered 里发起
        // 请求；loading 时它不画东西，露出底层旧图/占位色。
        //
        // v2.9.0 · A 档：`crossfade` 只驱动这一层的 alpha（在 graphicsLayer 块里读，
        // 每帧只让这一层失效、不触发重组，也不卸载任何子树）。
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = crossfade.value },
            contentScale = contentScale,
        )
    }
}

/**
 * v2.1.0 · F：「歌手名 · 音源」一行。播放页的四个歌曲信息区（窄屏顶栏 / 窄屏大封面
 * overlay / 宽屏左栏 / 大屏左栏）与折叠态 mini bar 共用这一份实现。
 *
 * **两个音源都标**（与列表行 `SongCard.sourceBadge` 只标非网易云不同）：播放页是用户
 * 唯一能确认「现在放的是哪一家」的地方 —— 队列里 QQ 音乐与网易云混在一起，只标一边
 * 等于让另一边变成"看不出是什么"。列表页不标网易云是为了给长列表降噪，这个理由在
 * 播放页不成立。
 *
 * 视觉语言与列表行保持一致：小一号字（bodySmall）、次要色、`·` 分隔、**无边框无底色**
 * （Kanesumi：直角、不用色块堆信息）。
 *
 * 角标与歌手**同一行**而不是新起一行：窄屏顶栏是固定 56dp 高的 Box、窄屏大封面信息区
 * 是压在封面上的 overlay，多起一行会挤到既有版式（前者会被裁，后者会多盖住封面）。
 *
 * [Modifier.weight] 只给歌手（`fill = false`，按内容收窄）：歌手过长时由它自己省略，
 * 角标永远完整可见 —— 反过来（角标被挤掉）就正好丢掉了这个角标存在的意义。
 * 基线对齐（[alignByBaseline]）而不是垂直居中：两段字号不同，居中会让角标浮起来。
 */
@Composable
private fun ArtistLineWithSource(
    song: SongItem,
    color: Color,
    style: TextStyle,
    badgeStyle: TextStyle,
    modifier: Modifier = Modifier,
    /**
     * v2.5.4 · E：是否连艺人一起画。
     *
     * 托盘改版后艺人由**它自己那一段**负责（因为要单独可点击进艺人页），
     * 这里就只剩角标。默认 `true` 是为了另外 4 个调用点一行都不用改。
     */
    showArtist: Boolean = true,
) {
    val strings = LocalStrings.current
    // 走 song.musicSource（枚举）而不是原始 source 字符串：null / 未知 key 的旧数据在
    // 这里也落到「网易云」，与列表行同一条判定（SongSourceExt.musicSource）。
    val sourceLabel = when (song.musicSource) {
        MusicSource.NETEASE -> strings.sourceNetease
        MusicSource.QQMUSIC -> strings.sourceQqMusic
    }
    val artistStr = if (showArtist) song.artists?.joinToString("/") { it.name }.orEmpty() else ""
    Row(modifier = modifier) {
        if (artistStr.isNotEmpty()) {
            MetroText(
                artistStr,
                color = color,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .alignByBaseline()
            )
            Spacer(Modifier.width(6.dp))
        }
        MetroText(
            if (artistStr.isNotEmpty()) "· $sourceLabel" else sourceLabel,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = badgeStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline()
        )
    }
}

/**
 * v2.5.4 · E：竖屏播放托盘**第一行**（实时歌词）。
 *
 * ## 订阅为什么必须在这里，而不是在 PlayerCard
 *
 * `currentPosition` 是 **2Hz** 更新的 `StateFlow`（`PlaybackService` 的 `delay(500)`
 * 心跳）。在 `PlayerCard` 的组合期 `collectAsState`，等于让整个播放器子树
 * （封面 `AsyncImage` + 歌词面板 + 队列）每 500ms 重组一次 ——
 * 这正是 `PlayerCard` 里那段注释反复 warn 的事（「让它们在最小作用域
 * （graphicsLayer / Canvas draw / derivedStateOf / 叶子 Text）内订阅」）。
 *
 * 所以两个流都在**这个叶子**里订阅。
 *
 * ## 行级节流怎么做到的
 *
 * `combine(歌词, 位置)` 每 500ms 产出一个新值，但 `distinctUntilChanged()` 把它压成
 * **只有文本真的变了才向下游发一个新值**。于是：
 * - 一行的持续时间里（通常 2~5 秒）本组件的 recomposition 次数 = **0**；
 * - 跨行的那一刻重组 **1 次**。
 *
 * 判据本身是纯函数（[TrayLyric.lineAt]），由 `TrayLyricTest` 钉住 ——
 * 「不逐字、不逐帧」这条要求因此是可执行断言，而不是一句承诺。
 *
 * ## 性能与异常（铁律 4 / 17）
 *
 * - 时间戳数组在**歌词列表变化时**才构造一次（`map` 在上游），不在 2Hz 路径里；
 * - 没有歌词 / 还没到第一行 ⇒ [TrayLyric.lineAt] 返回 `null` ⇒ 这里画一个**空串**。
 *   Compose 的空文本仍然占一行高（字号决定），所以托盘高度**不会**因为歌词
 *   就绪而跳一下 —— 这是有意保留的稳定性，不是漏了 `if`。
 * - 本组件不碰播放链路：只读两个 StateFlow，任何异常都止步于文本渲染。
 */
@Composable
private fun TrayLyricLine(
    lyricsFlow: kotlinx.coroutines.flow.StateFlow<List<com.takahashirinta.ncrust.lyric.LrcLine>>,
    positionFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    style: TextStyle,
    primaryColor: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
) {
    // `collectAsState` 是 @Composable，不能写在 `remember` 的 calculation 里
    // （那会破坏 Compose 的槽位语义）。流本身用 remember 缓存，只有两条上游换实例时才重建。
    val lineFlow = remember(lyricsFlow, positionFlow) {
        lyricsFlow
            .map { lines -> lines to TrayLyric.timestampsOf(lines) }
            .combine(positionFlow) { (lines, timestamps), position ->
                TrayLyric.lineAt(lines, timestamps, position)
            }
            .distinctUntilChanged()
    }
    val line by lineFlow.collectAsState(initial = null)
    MetroText(
        // 一个**空格**而不是空串：真机实测（S6 / Android 7.0）空串的 `MetroText`
        // 量出来是 **0 高**，托盘会塌成一行、歌词就绪的那一刻再跳成两行。
        // 空格保留了一行的高度又不显示任何东西 —— 这是「托盘高度稳定」的落点，
        // 不是随手写的占位符，改回 `orEmpty()` 会让那个跳动回来。
        text = line ?: " ",
        // 有实时歌词时用主色（它是"正在发生的事"），没有时这行本就是空的、颜色无所谓。
        color = if (line != null) primaryColor else mutedColor,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * v2.5.4 · D：音频可视化条的**唯一挂载落点**（横屏大屏左栏 / 平板横屏宽屏左栏共用）。
 *
 * 抽出来的理由与 `PlayerQualityChip` 一样：同一个功能出现两份实现，必然漂移成
 * 「某一边高度不同 / 某一边忘了挂 / 某一边订阅了缓冲状态导致整棵子树重组」。
 *
 * ## 性能契约（铁律 17：UI 动效不得影响播放性能）
 *
 * - [isBuffering] 传的是 **StateFlow 引用**，不是值：订阅发生在
 *   [AudioVisualizerBars] 的帧循环内部（`activeProvider` 每次只做一次
 *   `StateFlow.value` 读），**不在 PlayerCard 的组合期**。在调用点 `collectAsState`
 *   会让整棵播放器子树随缓冲抖动重组 —— 那正是 v1.8.0 注释里 warn 过的事。
 * - 调用方负责「不挂载」：关掉开关 / 不在允许的形态里时整块不组合，
 *   连帧时钟都不跑（不是 `alpha = 0` —— 见 AGENTS.md 触摸陷阱第 1/4 条）。
 * - 本组件不挂任何 `pointerInput` / `clickable`，不新增命中面
 *   （它画在封面区里，不会与歌词面板的字号按钮抢事件）。
 *
 * ## 异常隔离（铁律 4）
 *
 * [AudioVisualizerBars] 内部对 `WaveformStore` 只有一次数组拷贝与一次 Canvas 绘制，
 * 音频线程侧的写入由 `TransparentWaveformSink` 自己吞掉异常 —— 可视化失败
 * 不会冒泡到播放链路。这里不再包一层 `runCatching`（`@Composable` 里的
 * try/catch 会破坏 Compose 的重组语义，反而制造新的失败面）。
 */
@Composable
private fun AudioVisualizerSlot(heightDp: Dp) {
    // v2.9.0：`activeProvider` 参数**取消**了 —— 「播放中且未在缓冲」现在由
    // `MotionFrameClock`（在 PlayerCard 里挂载恰好一次）读取，本组件退化为纯读取方。
    // 保留这个参数只会留下一个"看起来在驱动帧循环、其实没人读"的假接口。
    AudioVisualizerBars(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(heightDp),
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * v1.5.1 · B：控制栏把手在手势导航机型上要抬离系统手势带的高度。
 *
 * 实测（PCL110 / Android 16 / navigation_mode=2）：从屏幕底部往上 0~13dp 起手的上滑
 * 100% 被判成「回到桌面」，28dp 起手才会交给应用；把这段声明成
 * `systemGestureExclusion` 也**没用** —— 排除矩形确实注册进了
 * `dumpsys window` 的 `mSystemGestureExclusion`，系统照样把上滑当自家手势。
 * 32dp 是实测安全阈值（28dp 可用）之上的保守取值：
 * 拖拽带整体上抬后，带内任意一点起手都不再落在系统手势带里。
 */
private val GESTURE_NAV_HANDLE_LIFT_DP = 32.dp

/**
 * 是否处于 Android 10+ 的手势导航（`navigation_mode == 2`）。
 *
 * `navigation_mode` 是 API 29 引入的 secure setting；读取 secure setting 不需要权限，
 * 设置项不存在（API < 29 或三键导航写的是 0）时 `getInt` 返回默认值 0 ⇒ 不上抬，
 * 老设备与三键导航机型的视觉/行为完全不变。
 */
private fun isGestureNavigation(context: android.content.Context): Boolean = runCatching {
    android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2
}.getOrDefault(false)
