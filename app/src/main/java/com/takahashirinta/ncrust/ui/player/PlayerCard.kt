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
import androidx.compose.ui.unit.Density
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
import kotlinx.coroutines.CoroutineScope
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

/**
 * 全屏播放器卡片（v1.3.0 起是三层图形架构的中间层）。
 *
 * v3.2.1 · P0：本函数原来是一个 **9092 code unit / 191 寄存器**的巨型方法
 * （真机 ART 为它分配 45MB 编译，JIT 完成后出现 `invalid weight 0.0` 崩溃）。
 * 拆分后它只剩「参数 → 建状态 → 算几何 → 画树」，三个布局分支 / 托盘 / 封面 overlay /
 * 背景层 / 副作用全部搬进同包的独立文件（见 `docs/verification/v3.2.1/probe-split-plan.md`）。
 *
 * `internal`：调用点只有同模块的 [PlayerCardOverlay]；收窄可见性是「上帝签名」
 * （25 形参 / 20 个默认值）治理的一部分 —— 见 [PlayerCardCallbacks] 的 KDoc。
 */
@Composable
internal fun PlayerCard(
    song: SongItem?,
    isPlaying: Boolean,
    screenHeightPx: Float,
    progress: Animatable<Float, AnimationVector1D>,
    // v3.2.1 · P0：下面 6 个形参**去掉了默认值**。默认值不是免费的 ——
    // Compose 编译器要为每一个生成「掩码判断 + 默认表达式」分支，同形状空实现实测
    // 每个默认值约 47 code unit（见 PlayerCardCallbacks 的 KDoc 表）。
    // 唯一的调用点 PlayerCardOverlay **本来就逐个显式传**（默认值从来没有生效过），
    // 所以去掉它们对行为零影响，只是把那 280 code unit 还给方法体预算。
    totalDragDistancePx: Float,
    playbackQueue: List<SongItem>,
    currentQueueIndex: Int,
    playMode: Int,
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
    // P1：大屏幕模式（横屏桌面播放器布局）开关 + 入口/出口回调。
    // bigScreen 是"用户意图"，还要叠加当前窗口方向才是生效态（见 bigScreenActive）。
    bigScreen: Boolean = false,
    // v1.8.0 · T4：自动旋转开关（竖屏控制栏 + 大屏左栏两处图标入口，同一份状态）。
    autoRotate: Boolean = false,
    // v3.2.1 · P0：15 个回调打成一包（签名实测占 1366 code unit，见
    // PlayerCardCallbacks 的 KDoc）。字段名与原来的形参名逐一相同。
    callbacks: PlayerCardCallbacks,
) {
    val hasSong = song != null
    // v3.2.1 · P0：**跨重组状态**与**几何计算**搬进 PlayerCardState.kt。
    // 它们是拆分后 PlayerCard 方法体里最大的两块（实测仍占 ~2000 code unit）。
    // 语义逐条等价：状态对象的生存期、派生状态的读取位置、几何算式全部没变
    // （对照表写在那个文件的 KDoc 上）。
    val ui = rememberPlayerCardUiState(progress)
    val density = LocalDensity.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    // v1.5.1 · B：手势导航机型给控制栏把手留出的上抬量（见把手处的注释）。
    // 只在 navigation_mode == 2（Android 10+ 手势导航）时非零；三键导航与 API < 29 为 0。
    val gestureNavLiftDp = remember(context) {
        if (isGestureNavigation(context)) GESTURE_NAV_HANDLE_LIFT_DP else 0.dp
    }

    // 迷你条与顶栏按钮的触觉反馈
    val haptic = LocalHapticFeedback.current

    // ── v2.9.0 · A 档：动效能力位 ────────────────────────────────────────────────
    // 分级：**组合期读一次**（用户改设置或发生一次自动降级时才会重组一次）。
    // 帧路径只读这个捕获值 —— 逐帧变化的量全部走 MotionClock 的 generation，不读 state。
    val motion = MotionPrefs.effects.value

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
    // ⚠️ 这个 remember **有意留在 PlayerCard**：键是 `(song?.id, libraryTick)`，
    // 搬进持有者会让「切歌后重新查一次」的语义消失。
    val isSongSaved = remember(song?.id, ui.libraryTick) {
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
        targetValue = if (ui.showLyrics || ui.showQueue) 1f else 0f,
        animationSpec = tween(280, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "widePlayerSplit"
    )

    val screenWidthDp = configuration.screenWidthDp.dp
    val screenWidthPx = with(density) { screenWidthDp.toPx() }
    // 状态栏高度（px）：唯一需要 Compose 环境的一步（`WindowInsets`），
    // 其余几何全部交给下面的纯函数。车机（WindowInsets=0）天然得 0，不影响车机。
    val statusBarPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        .let { with(density) { it.toPx() } }
    // ---- v3.2.1 · P0：布局谓词 + 封面落点几何（纯函数，可 JVM 单测）----
    // 含：isWidePlayer / bigScreenActive / usesSideCover / wideLeftFraction(含 A 项的正下限) /
    // panelBoundaryPx / 可视化条挂载与高度 / 封面尺寸与三个落点中心 / 命中区让位量。
    val geo = playerCardGeometry(
        density = density,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        smallestScreenWidthDp = configuration.smallestScreenWidthDp,
        orientationLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        visualizerEnabled = VisualizerSetting.state.value,
        wideSplit = wideSplit,
        bigScreenRequested = bigScreen,
        wideCoverCenter = ui.wideCoverCenter,
        wideCoverSizePx = ui.wideCoverSizePx,
        statusBarPx = statusBarPx,
    )

    // v2.9.0：换歌时清掉**界面动效那一层**的包络与特效池（基线 / 脉冲 / 光晕 / 粒子）。
    // 不清波形环形缓冲：那会让新歌开头几帧的波形是空的（上一首的柱子滚出去是既有的换歌观感）。
    LaunchedEffect(song?.id) { MotionClock.clear() }

    // 收起/恢复共用的竖直拖拽检测器：拖动期间 snapTo 跟手，松手做方向敏感吸附。
    // key 带 isWidePlayer：宽屏不启用（见上）。
    // 注意：必须每次调用都**新建** Modifier 实例。SuspendPointerInputElement 内部持有
    // previousKeys 这类可变状态，同一个实例挂到两个节点上会互相踩，表现为其中一个节点
    // 的 pointer 处理器起不来（实测：控制栏能收起、悬浮键的恢复手势收不到事件）。
    // v3.2.1 · P0：手势检测器本体搬成同文件顶层函数（见本文件末尾的
    // `controlsCollapseDragModifier`）—— 它的 60 行指针逻辑原本就编译在同一方法体里。
    // 这里保留同名局部函数，调用点（窄屏分支传 `::controlsCollapseDrag`）逐字不变。
    fun controlsCollapseDrag(): Modifier = controlsCollapseDragModifier(
        hasSong = hasSong,
        isWidePlayer = geo.isWidePlayer,
        controlsCollapse = ui.controlsCollapse,
        controlsHeightProvider = { ui.controlsHeightPx },
        density = density,
        coroutineScope = coroutineScope,
    )

    // 控制栏收起的显式切换（把手上点一下即可），与拖拽共用同一条动画轴。
    fun toggleControlsCollapse() {
        coroutineScope.launch {
            val target = if (ui.controlsCollapse.value > 0.5f) 0f else 1f
            ui.controlsCollapse.animateTo(target, tween(260, easing = FastOutSlowInEasing))
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

    // v3.2.1 · P0：5 个 LaunchedEffect（控制栏复位 / 歌词自动切换 / 大屏打开歌词 /
    // 面板切换动画 / 展开定位）整块搬进 PlayerCardEffects.kt —— 它们不产生任何 UI 节点，
    // key 与执行顺序逐条保持（对照表在那个文件的 KDoc 上）。
    PlayerCardEffects(
        ui = ui,
        progress = progress,
        songId = song?.id,
        lyricsReady = lyricsReady,
        lyricsLoading = lyricsLoading,
        bigScreenActive = geo.bigScreenActive,
    )

    // 歌词/队列面板可交互(展开 + 面板在前台)时,面板区域内的纵向手势归内部列表滚动。
    // 根节点的整卡拖拽与兜底消费器都不得抢手势——否则在面板上一滑,整卡被拖走、
    // 列表几乎滚不动(issue #23)。面板外的封面/顶栏/大封面模式仍驱动整卡。
    // v3.2.1：`topBarBottomPx` 与 `isPanelInteractive` 都搬进 PlayerCardGeometry /
    // PlayerCardUiState（算式与读取位置逐字未动）。
    // 注意区分两个不同职责的判断，不要把语义混在一起：
    //
    // ① isOverPanel —— **面板语义**：点是否落在歌词/列表这类可滚动面板上。
    //    只给下面的拖拽检测器用：落在面板内时根节点必须让路，
    //    否则内部 LazyColumn 滚不动（历史 issue #23）。它依赖 isPanelInteractive 是正确的。
    fun isOverPanel(y: Float, x: Float) = ui.isPanelInteractive && y > geo.topBarBottomPx &&
        (!geo.isWidePlayer || x > geo.panelBoundaryPx)

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
        !geo.usesSideCover && ui.cardExpandedForInput && y >= ui.controlsTopInCardPx

    fun isOverCardVisibleArea(y: Float, x: Float): Boolean {
        // 宽屏左右分栏，左栏是封面区，触摸落在左栏时不属于卡片内容区。
        // P1：大屏模式**不**参与这条豁免 —— 它的左栏里有可点控件（音质选择器），
        // 整个屏幕都算卡片可见区，根节点不吞任何事件，避免把子控件的 UP 消费掉
        // （Compose 的 waitForUpOrCancellation 见到 isConsumed 就直接取消点击）。
        if (geo.isWidePlayer && !geo.bigScreenActive && x <= geo.panelBoundaryPx) return false
        // 卡片可见上沿：收起时整体下移到 collapsedOffsetY，展开时回到 0。
        // 用 progress 插值而不是直接读 cardRootOrigin.y —— graphicsLayer 的平移
        // 不会重新触发布局，onGloballyPositioned 写入的坐标在动画期间会滞后。
        val cardTopPx = ui.cardRootOrigin.y * (1f - progress.value)
        return y >= cardTopPx
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { ui.cardRootOrigin = it.boundsInRoot().topLeft }
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
            .then(if (ui.collapsedHitGate) Modifier.padding(top = geo.hitGateInsetDp) else Modifier)
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
            .pointerInput(hasSong, geo.bigScreenActive) {
                if (!hasSong) return@pointerInput
                // P1：大屏模式下停用"整卡拖拽"。大屏是横屏桌面布局，"把卡片拖下去"没有
                // 对应语义（折叠态的 mini bar 落点是按竖屏 contentHeightPx 算的），
                // 出口固定为：同一个按钮 / 系统返回键 / 旋转回竖屏。
                if (geo.bigScreenActive) return@pointerInput
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
            .then(if (ui.collapsedHitGate) Modifier.offset(y = -geo.hitGateInsetDp) else Modifier)
    ) {
        // v3.2.1 · P0：背景三件套（纯黑底 / 折叠卡背 / 动效背景 / 唯一帧时钟 / 背景级波形）
        // 搬进 PlayerCardBackdrop.kt。它是 BoxScope 扩展 —— 背景级波形用
        // `.align(Alignment.BottomCenter)`，作用域必须与拆分前一致。
        PlayerCardBackdrop(
            progress = progress,
            statusBarPx = statusBarPx,
            motion = motion,
            coverArtKey = ui.coverArtKey,
            coverArtBitmap = ui.coverArtBitmap,
            screenHeightPx = screenHeightPx,
            isPlaying = isPlaying,
            hasSong = hasSong,
            expandedMounted = ui.expandedMounted,
            visualizerSlot = geo.visualizerSlot,
            waveBackdropHeightDp = geo.waveBackdropHeightDp,
            isBufferingFlow = playerViewModel.isBuffering,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // B-1：折叠态不挂载展开态子树（mini bar 在下面、永远挂载）。见 expandedMounted 注释。
            if (hasSong && ui.expandedMounted) {
                // v3.2.1 · P0：展开态子树（两个槽位 lambda + 三个布局分支的分发）
                // 搬进 PlayerCardExpanded.kt。挂载判据与组合树位置逐节点不变。
                PlayerCardExpanded(
                    song = song!!,
                    ui = ui,
                    geo = geo,
                    progress = progress,
                    strings = strings,
                    playerViewModel = playerViewModel,
                    callbacks = callbacks,
                    isPlaying = isPlaying,
                    haptic = haptic,
                    gestureNavLiftDp = gestureNavLiftDp,
                    lyrics = lyrics,
                    translatedLyrics = translatedLyrics,
                    lyricsLoading = lyricsLoading,
                    showLyricsTranslation = showLyricsTranslation,
                    showLyricsRomanization = showLyricsRomanization,
                    showDynamicLyricFont = showDynamicLyricFont,
                    romanizedLyrics = romanizedLyrics,
                    lyricsWordAnimation = lyricsWordAnimation,
                    lyricsFontScale = lyricsFontScale,
                    lyricsSweepQuality = lyricsSweepQuality,
                    lyricsReady = lyricsReady,
                    lyricsUnavailable = lyricsUnavailable,
                    isSongSaved = isSongSaved,
                    controlsCollapseDrag = ::controlsCollapseDrag,
                    onToggleControlsCollapse = ::toggleControlsCollapse,
                    motion = motion,
                    playbackQueue = playbackQueue,
                    currentQueueIndex = currentQueueIndex,
                    playMode = playMode,
                    screenWidthPx = screenWidthPx,
                    autoRotate = autoRotate,
                )
            }
        }

        // v3.2.1 · P0：托盘 / 唯一封面 overlay / 收起键三块的**调用点**也收进一个入口
        // （34 个实参 + 3 个闭包 → 12 个实参）。三个叠加层的挂载判据与 z 序逐字不变。
        PlayerCardOverlayLayers(
            song = song,
            hasSong = hasSong,
            isPlaying = isPlaying,
            progress = progress,
            motion = motion,
            strings = strings,
            haptic = haptic,
            callbacks = callbacks,
            playerViewModel = playerViewModel,
            ui = ui,
            geo = geo,
            coroutineScope = coroutineScope,
        )
    }
}

/**
 * v1.4.0 · A：按 [collapse]（0..1）把本节点**在布局里占的高度**收走。
 *
 * 与 graphicsLayer 平移配合使用：布局高度收走 → 兄弟节点（面板，weight 1f）顺势长高；
 * 内容本身由外层 graphicsLayer 平移出屏幕。这里在 layout 阶段直接读 Animatable，
 * **不触发 recomposition** —— 动画帧只重排这一棵子树。
 */
internal fun Modifier.collapsibleHeight(collapse: Animatable<Float, AnimationVector1D>): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val shrink = (collapse.value.coerceIn(0f, 1f) * placeable.height).roundToInt()
        layout(placeable.width, (placeable.height - shrink).coerceAtLeast(0)) {
            placeable.place(0, 0)
        }
    }

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
internal fun StableCover(
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
internal fun ArtistLineWithSource(
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
        MusicSource.BILIBILI -> strings.source.sourceBilibili
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
internal fun AudioVisualizerSlot(heightDp: Dp) {
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

/**
 * v3.2.1 · P0：控制栏收起/恢复的竖直拖拽检测器（原 `PlayerCard` 的局部函数）。
 *
 * 抽成顶层函数的唯一理由是**方法体大小**：它原本是 `PlayerCard` 那 9092 code unit
 * 里的一段。行为逐行保持：
 *
 *  - key 仍是 `(hasSong, isWidePlayer)`：宽屏不启用（宽屏是左右两栏，没有可让出的空间）；
 *  - **每次调用都新建 `Modifier` 实例**：`SuspendPointerInputElement` 内部持有
 *    `previousKeys` 这类可变状态，同一个实例挂到两个节点上会互相踩，
 *    表现为其中一个节点的 pointer 处理器起不来
 *    （实测：控制栏能收起、悬浮键的恢复手势收不到事件）；
 *  - `current` 自己累加（`onVerticalDrag` 的 `dragAmount` 是**每帧增量**）；
 *  - 速度由收起进度反推（px/s），不读 `position` 差分；
 *  - 吸附判定仍走 [ControlsDragSnap.target]（v1.7.0 · P0 的方向敏感阈值 + 甩动判据）。
 *
 * [controlsHeightProvider] 用取值函数而不是 `Float`：控制栏高度是**实测值**
 * （`onGloballyPositioned` 写入），拖动过程中可能被刷新，传值会读到过期快照。
 */
internal fun controlsCollapseDragModifier(
    hasSong: Boolean,
    isWidePlayer: Boolean,
    controlsCollapse: Animatable<Float, AnimationVector1D>,
    controlsHeightProvider: () -> Float,
    density: Density,
    coroutineScope: CoroutineScope,
): Modifier = Modifier.pointerInput(hasSong, isWidePlayer) {
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
            val h = if (controlsHeightProvider() > 1f) controlsHeightProvider()
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
            val h = if (controlsHeightProvider() > 1f) controlsHeightProvider()
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
