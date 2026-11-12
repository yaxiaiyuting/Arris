/*
 * Ncrust -- ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 本文件派生自 Kanesumi（Apache-2.0）的 kanesumi-controls/MetroLyricsPanel.kt：
 * 逐字高亮需要控制「单行内部怎么画」，而 Kanesumi 的面板只接受纯文本、无法从外部注入
 * 渲染；把面板整体搬进本仓库，是为了让 v1.5.0 的构建**只依赖 Ncrust 一个 git 仓库**
 * （Kanesumi 走组合构建、不在本仓库版本控制内，改它会让发布产物不可复现）。
 * 除单行渲染外，滚动/定位/缩放/渐入/a11y 等行为与原版逐行一致，改动处均标「v1.5.0 · B」。
 * 原 Apache-2.0 代码与本 GPLv3 项目的兼容性见 THIRD-PARTY-LICENSES.md。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.takahashirinta.ncrust.ui.player.motion.MotionClock
import com.takahashirinta.ncrust.ui.theme.AppMotion
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import com.takahashirinta.ncrust.lyric.LrcWord
import com.takahashirinta.ncrust.lyric.LyricSubtitleText
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepConfig
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.lyric.SweepGeometry
import com.takahashirinta.ncrust.lyric.SweepTrack
import com.takahashirinta.ncrust.lyric.sweepHalfWidth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

@Immutable
data class NcrustLyricLine(
    val timestampMillis: Long,
    val text: String,
    // 可选翻译(Spotify 式双语):非空时渲染在原句下方,小号降透明度。
    val translation: String = "",
    // v1.9.3：可选音译（罗马音 / 粤拼）：非空时渲染在**译文下方**，字号再低一档。
    // 「要不要显示」由调用方用 LyricSubtitleText.visibleRomanization 决定（开关 / 空白行 /
    // 与原文逐字相同都在那里被丢掉），面板只负责「非空就挂载」。
    val romanization: String = "",
    // v1.5.0 · B：逐字时间轴（来自 yrc）。空 = 该行没有逐字数据，按普通整行渲染。
    val words: List<LrcWord> = emptyList(),
    // v1.5.0 · B：整行结束时刻（yrc 的行时长）。null = 未知。
    val endMs: Long? = null,
    // v2.0.0 · T4：动态字号倍率（实验性，默认 1f = 与 v1.9.3 逐字节一致）。
    // 它是"这一句自己的静态属性"，与"谁是当前行"无关 —— 绝不能按 currentIndex 改字号，
    // 否则每次切句都会有两行重排 + item 高度抖动。
    val fontScale: Float = 1f,
    val playbackStartMillis: Long = timestampMillis,
)

private val LyricHandoff = tween<Float>(360, easing = SokuouTweens.CoverFade.easing)

private data class LyricFocusTarget(val index: Int, val strength: Float)

private data class LyricFollowRequest(
    val focus: LyricFocusTarget,
    val viewport: IntSize,
    val browsing: Boolean,
    val rowMetrics: Any,
)

private data class LyricFocusFrame(
    val center: Float,
    val target: LyricFocusTarget,
    val source: Map<Int, Float> = emptyMap(),
    val phase: Float = 1f,
) {
    fun emphasis(row: Int): Float = lerp(
        source[row] ?: 0f,
        if (row == target.index) target.strength else 0f,
        phase,
    )

    // On interruption, keep exactly the rows already visible in the crossfade.
    // A seek across several cues must not highlight the ones it skipped.
    fun weights(): Map<Int, Float> = buildMap {
        for (row in source.keys + target.index) {
            val weight = emphasis(row)
            if (weight > 0f) put(row, weight)
        }
    }
}

@Composable
internal fun NcrustLyricsPanel(
    lines: List<NcrustLyricLine>,
    currentPositionMillis: () -> Long,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    // 外部强制定位信号(递增即可): 面板常挂载时 isVisible 不会翻转,
    // 播放器从折叠态展开/重新唤起需要主动把滚动锚回当前行
    forcedScrollTrigger: Int = 0,
    currentLineColor: Color = LocalMetroColors.current.primary,
    pastLineColor: Color = Color.White.copy(alpha = 0.6f),
    futureLineColor: Color = Color.Gray.copy(alpha = 0.4f),
    fontSize: TextUnit = 32.sp,
    lineHeight: TextUnit = 42.sp,
    translationFontSize: TextUnit = 20.sp,
    translationLineHeight: TextUnit = 26.sp,
    // v1.9.3：音译字号 / 行高。调用方（LyricsView）把 fontScale 乘好后传入，
    // 所以 A- / A+ 会同时缩放原文 / 译文 / 音译三者，比例恒定。
    romanizationFontSize: TextUnit = LyricsDisplayPrefs.ROMANIZATION_FONT_SP.sp,
    romanizationLineHeight: TextUnit = LyricsDisplayPrefs.ROMANIZATION_LINE_HEIGHT_SP.sp,
    inactiveScale: Float = 0.82f,
    enabled: Boolean = true,
    // v1.5.0 · B：逐字高亮开关。false（或该行没有逐字数据）时行内渲染与 v1.4.1 相同。
    karaokeEnabled: Boolean = true,
    // v1.5.1 · A：逐字动画模式（见 LyricsWordAnimationMode）。只在当前行 + 有逐字数据时起作用。
    wordAnimationMode: Int = LyricsWordAnimationMode.GRADIENT_SWEEP,
    // v1.5.2：逐字扫过的可调参数（渐变带宽度 / 未唱透明度 / 缓动 / 是否用离屏软边）。
    // 默认值即定稿参数，见 LyricsSweepConfig 的逐项说明。
    sweepConfig: LyricsSweepConfig = LyricsSweepConfig.DEFAULT,
    onLineClick: (Long) -> Unit = {},
    onUserScrolled: () -> Unit = {},
    // v2.3.0 · E：定位目标比例。默认 = 竖屏的 LEAD_FRACTION(0.36)，
    // 横屏 / 大屏右栏由调用方传 CENTER_FRACTION(0.5) —— 短面板下 0.36 看起来是歪的。
    leadFraction: Float = LyricsPanelScroll.LEAD_FRACTION,
    // v2.9.0 · B 档：**歌词律动**（当前行随节拍轻微缩放，任务书 §5.2）。
    // 默认 **false** —— 关闭时下面那一行的渲染表达式与 v2.8.0 逐字相同
    // （`scale` 就是原来的值，不额外乘 1f），这是"关掉开关等于没这功能"的机械保证。
    // 幅度上限见 `AppMotion.LYRIC_PULSE_SCALE`（3%，判据是"不干扰阅读"）。
    lyricPulseEnabled: Boolean = false,
    focusIndex: Int? = null,
    focusActive: Boolean = true,
    focusedRowMetrics: Any? = null,
    focusedRow: (@Composable (Int, NcrustLyricLine, Boolean, () -> Float, () -> Float) -> Unit)? = null,
    /**
     * 4.0.11：**当前句左侧竖条**（横屏/宽屏用）。
     *
     * 竖屏跟唱页本来就有这根条（`KaraokeScreen` 的 focus 区），但横屏歌词面板一直没有，
     * 于是"当前句在哪"只能靠字号差与颜色差判断，扫视成本高。开着它就给当前行画一条
     * 主轴色的圆角短条，高度随行高自适应，横屏短面板里尤其明显。
     */
    currentMarker: Boolean = false,
) {
    val currentPosition by rememberUpdatedState(currentPositionMillis)
    val timestamps = remember(lines) { LongArray(lines.size) { lines[it].playbackStartMillis } }
    val timedIndex by remember(timestamps) {
        derivedStateOf { currentLineIndex(currentPosition(), timestamps) }
    }
    val currentIndex = focusIndex ?: timedIndex
    val target by rememberUpdatedState(
        LyricFocusTarget(currentIndex.coerceAtLeast(0), if (focusActive && currentIndex >= 0) 1f else 0f),
    )
    var visualFocus by remember { mutableStateOf(LyricFocusFrame(target.index.toFloat(), target)) }
    var relocationAlpha by remember { mutableStateOf(1f) }
    val fadeIn = remember { Animatable(0f) }
    LaunchedEffect(lines.isNotEmpty()) {
        if (lines.isEmpty()) fadeIn.snapTo(0f)
        else fadeIn.animateTo(1f, SokuouTweens.CoverFade)
    }

    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val rowMetrics by rememberUpdatedState(focusedRowMetrics ?: listOf(
        fontSize, lineHeight, translationFontSize, translationLineHeight, romanizationFontSize, romanizationLineHeight,
    ))
    val onScrolled by rememberUpdatedState(onUserScrolled)
    val density = LocalDensity.current
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    val baseFadePx = with(density) { LyricsPanelScroll.BASE_FADE_DP.dp.toPx() }
    val minFadePx = with(density) { LyricsPanelScroll.MIN_FADE_DP.dp.toPx() }
    val portrait = focusedRow != null
    val maxDefocusPx = with(density) { (if (portrait) 1.4.dp else 0.8.dp).toPx() }

    fun correction(height: Int, item: LazyListItemInfo): Int =
        LyricsPanelScroll.blockCorrectionPx(
            viewportHeightPx = height,
            leadFraction = leadFraction,
            itemHeightPx = item.size,
            minTopPx = LyricsPanelScroll.fadeHeightPx(height, baseFadePx, minFadePx),
            currentTopPx = item.offset,
        )

    suspend fun placeLine(index: Int, height: Int) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) {
            listState.scrollBy(correction(height, item).toFloat())
        } else {
            listState.scrollToItem(index, LyricsPanelScroll.leadOffsetPx(height, leadFraction))
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let {
                listState.scrollBy(correction(height, it).toFloat())
            }
        }
    }

    suspend fun handoff(goal: LyricFocusTarget, move: (Float) -> Unit = {}) {
        val from = visualFocus
        val source = from.weights()
        animate(0f, 1f, animationSpec = LyricHandoff) { phase, _ ->
            move(phase)
            visualFocus = if (phase == 1f) LyricFocusFrame(goal.index.toFloat(), goal) else {
                LyricFocusFrame(lerp(from.center, goal.index.toFloat(), phase), goal, source, phase)
            }
        }
    }

    // Every visible handoff uses the current measured offset, including when the previous
    // handoff was interrupted. The same frame moves the list and updates both lyric rows.
    LaunchedEffect(lines, isVisible, forcedScrollTrigger, leadFraction) {
        if (!isVisible || lines.isEmpty()) return@LaunchedEffect
        userScrolling = false
        var needsPlacement = true
        var lastRowMetrics = rowMetrics
        snapshotFlow { LyricFollowRequest(target, viewport, userScrolling, rowMetrics) }
            .collectLatest { (goal, size, browsing, metrics) ->
                val height = size.height
                if (height <= 0) return@collectLatest
                if (metrics != lastRowMetrics) {
                    // A font or primary-language change can reflow rows without resizing the viewport.
                    // Let the new text layout reach LazyList before measuring the next correction.
                    withFrameNanos { }
                    withFrameNanos { }
                    lastRowMetrics = metrics
                }
                val index = LyricsPanelScroll.targetItemIndex(goal.index, lines.size)
                if (needsPlacement) {
                    placeLine(index, height)
                    visualFocus = LyricFocusFrame(goal.index.toFloat(), goal)
                    relocationAlpha = 1f
                    needsPlacement = false
                    return@collectLatest
                }

                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                val startAlpha = relocationAlpha
                if (browsing) {
                    relocationAlpha = 1f
                    if (visualFocus.target != goal || visualFocus.phase < 1f) handoff(goal)
                } else if (item == null) {
                    // A distant seek can move the virtualized window. Conceal that layout
                    // change and reveal the target without highlighting the skipped lines.
                    animate(startAlpha, 0f, animationSpec = SokuouTweens.QuickSwitch) { alpha, _ ->
                        relocationAlpha = alpha
                    }
                    placeLine(index, height)
                    visualFocus = LyricFocusFrame(goal.index.toFloat(), goal)
                    animate(0f, 1f, animationSpec = SokuouTweens.SheetAppear) { alpha, _ ->
                        relocationAlpha = alpha
                    }
                } else {
                    val distance = correction(height, item).toFloat()
                    if (visualFocus.target == goal && visualFocus.phase == 1f &&
                        abs(distance) < 1f && startAlpha == 1f
                    ) return@collectLatest
                    listState.scroll {
                        var applied = 0f
                        handoff(goal) { phase ->
                            val travel = distance * phase
                            scrollBy(travel - applied)
                            applied = travel
                            relocationAlpha = lerp(startAlpha, 1f, phase)
                        }
                    }
                }
            }
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && !userScrolling) {
                userScrolling = true
                onScrolled()
            }
        }
    }

    var pointerDown by remember { mutableStateOf(false) }
    var touchGeneration by remember { mutableIntStateOf(LyricsAutoCenter.GENERATION_NEVER_TOUCHED) }
    LaunchedEffect(touchGeneration, isVisible, enabled) {
        if (!isVisible || !LyricsAutoCenter.shouldSchedule(touchGeneration)) return@LaunchedEffect
        delay(LyricsAutoCenter.IDLE_TIMEOUT_MS)
        if (LyricsAutoCenter.shouldRecenter(pointerDown, lines.size, enabled)) userScrolling = false
    }

    // v2.0.1：面板根节点改用 BoxWithConstraints —— 顶部留白要按**视口高度**夹取
    // （`min(200dp, 36% × 视口)`，见 LyricsPanelScroll）。用它而不是 `listState.layoutInfo`：
    // 后者要等第一次排版之后才有值，进大屏那一帧会先按 200dp 排一遍、下一帧才跳回来
    // （正好就是本 bug 的表象）；`maxHeight` 是**约束**，同一帧就是对的。
    // 约束无界（父级可滚动 / 未指定高度）时回落基准值，行为与 v2.0.0 一致。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeIn.value * relocationAlpha }
            // v2.3.0 · E：只**观察**指针事件、绝不消费（Initial pass + 什么都不改），
            // 所以 LazyColumn 自己的滚动 / 点行 seek 的手势一个都不受影响。
            // key 用 Unit：检测器只读/写 state，不需要随参数重建。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Press -> {
                                pointerDown = true
                                touchGeneration = LyricsAutoCenter.nextGeneration(touchGeneration)
                            }
                            PointerEventType.Release -> {
                                pointerDown = false
                                // 抬起时 +1：这一次的 5s 计时从**松手**开始算。
                                touchGeneration = LyricsAutoCenter.nextGeneration(touchGeneration)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            .semantics {
                val line = lines.getOrNull(timedIndex)
                text = AnnotatedString(line?.let {
                    LyricSubtitleText.a11yText(it.text, it.translation, it.romanization)
                }.orEmpty())
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val topSpacerHeight = with(LocalDensity.current) {
            LyricsPanelScroll.topSpacerHeightPx(
                viewportHeightPx = if (maxHeight.value.isFinite()) maxHeight.roundToPx() else 0,
                baseSpacerPx = LyricsPanelScroll.BASE_SPACER_DP.dp.toPx(),
                leadFraction = leadFraction,
            ).toDp()
        }
        LazyColumn(
            state = listState,
            userScrollEnabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> viewport = size },
        ) {
            // v2.0.1：顶部留白 = min(200dp, 36% × 视口)。它同时是"列表顶端"与"第一句的定位槽"，
            // 所以"回顶"在大屏短面板下也不会把第一句推出可视区（详见 LyricsPanelScroll）。
            item(key = "top_spacer") { Spacer(Modifier.height(topSpacerHeight)) }
            // 4.0.2：**底部留白**。竖屏下歌词面板是 weight(1f) 的直接上方邻居是可视条/进度区，
            // 最后一行贴到底边时会盖住那一条（用户截图里"可视条被挡一部分"就是这个）。
            // 给列表留 28dp 尾巴，滚到底也不会贴边。

            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val emphasis = { visualFocus.emphasis(index) }
                val defocus = {
                    if (userScrolling) 0f else {
                        val distance = index - visualFocus.center
                        val depth = if (portrait) {
                            if (distance >= 0f) distance / 1.9f else -distance / 6f
                        } else abs(distance) / 2.4f
                        maxDefocusPx * depth.coerceIn(0f, 1f) * (1f - emphasis())
                    }
                }
                val restingColor = {
                    lerpColor(futureLineColor, pastLineColor, (visualFocus.center - index).coerceIn(0f, 1f))
                }
                // Font scale belongs to the line, so singing or pausing never reflows it.
                val fontScale = line.fontScale
                val textStyle = TextStyle(
                    fontSize = fontSize * fontScale,
                    lineHeight = lineHeight * fontScale,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) {
                            userScrolling = false
                            onLineClick(line.playbackStartMillis)
                        }
                        .padding(vertical = if (portrait) 8.dp else 10.dp)
                        .graphicsLayer {
                            val focus = emphasis()
                            val isBrowsing = userScrolling
                            val baseScale = if (portrait && isBrowsing) 0.78f else inactiveScale
                            var scale = lerp(baseScale, 1f, focus)
                            if (lyricPulseEnabled && focus > 0f) {
                                MotionClock.generation
                                scale *= 1f + MotionClock.pulse() * AppMotion.LYRIC_PULSE_SCALE * focus
                            }
                            scaleX = scale
                            scaleY = scale
                            val textInset = if (portrait) 19.dp.toPx() else if (currentMarker) 12.dp.toPx() else 0f
                            val pivot = (textInset / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(
                                if (leftToRight) pivot else 1f - pivot, 0.5f,
                            )

                            val viewport = listState.layoutInfo
                            val item = viewport.visibleItemsInfo.firstOrNull { it.index == index + 1 }
                            val fade = LyricsPanelScroll.fadeHeightPx(
                                viewport.viewportEndOffset - viewport.viewportStartOffset, baseFadePx, minFadePx,
                            )
                            val edgeAlpha = if (item == null) 1f else {
                                val middle = item.offset + item.size * 0.5f
                                val distance = minOf(
                                    middle - viewport.viewportStartOffset,
                                    viewport.viewportEndOffset - middle,
                                )
                                (distance / fade.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            }
                            alpha = edgeAlpha
                        }
                        .then(
                            if (currentMarker) Modifier.drawBehind {
                                val focus = emphasis()
                                if (focus > 0f) {
                                    val width = 3.dp.toPx()
                                    val top = 6.dp.toPx()
                                    val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width
                                    drawRoundRect(
                                        color = currentLineColor.copy(alpha = focus * 0.9f),
                                        topLeft = Offset(x, top),
                                        size = Size(width, (size.height - top * 2).coerceAtLeast(0f)),
                                        cornerRadius = CornerRadius(width / 2f),
                                    )
                                }
                            } else Modifier,
                        ),
                ) {
                    if (focusedRow != null) {
                        focusedRow(index, line, userScrolling, emphasis, defocus)
                    } else {
                        Column(Modifier.fillMaxWidth().padding(start = if (currentMarker) 12.dp else 0.dp)) {
                            LyricLineBody(
                                text = line.text,
                                words = line.words,
                                endMs = line.endMs,
                                color = currentLineColor,
                                highlightColor = currentLineColor,
                                enabled = karaokeEnabled,
                                mode = wordAnimationMode,
                                sweepConfig = sweepConfig,
                                currentPositionMillis = currentPosition,
                                style = textStyle,
                                emphasis = emphasis,
                                restingColor = restingColor,
                                defocus = defocus,
                            )
                            listOf(
                                Triple(line.translation, translationFontSize, translationLineHeight),
                                Triple(line.romanization, romanizationFontSize, romanizationLineHeight),
                            ).forEach { (subtitle, size, height) ->
                                if (subtitle.isNotEmpty()) {
                                    LyricCaption(
                                        text = subtitle,
                                        style = TextStyle(fontSize = size * fontScale, lineHeight = height * fontScale),
                                        color = {
                                            val color = lerpColor(restingColor(), currentLineColor, emphasis())
                                            color.copy(alpha = color.alpha * 0.6f)
                                        },
                                        defocus = defocus,
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(200.dp)) }
        }
    }
}


/**
 * One measured text layout serves both the resting line and its word sweep.
 * Reading emphasis in draw keeps the glyphs and their measured row stable during handoffs.
 */
@Composable
internal fun LyricLineBody(
    text: String,
    words: List<LrcWord>,
    endMs: Long?,
    color: Color,
    highlightColor: Color,
    enabled: Boolean,
    mode: Int,
    currentPositionMillis: () -> Long,
    style: TextStyle,
    sweepConfig: LyricsSweepConfig,
    emphasis: () -> Float = { 1f },
    restingColor: (() -> Color)? = null,
    defocus: () -> Float = { 0f },
) {
    if (!enabled || words.isEmpty() || mode == LyricsWordAnimationMode.OFF) {
        LyricCaption(
            text = text,
            style = style,
            color = { restingColor?.let { lerpColor(it(), color, emphasis()) } ?: color },
            defocus = defocus,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val unsungColor = color.copy(alpha = color.alpha * sweepConfig.inactiveAlpha)
    val density = LocalDensity.current
    val fadePx = remember(style.fontSize, sweepConfig.fadeEm, density) {
        val fs = style.fontSize
        if (fs.isSp) with(density) { fs.toPx() * sweepConfig.fadeEm }
        else with(density) { LyricsSweepConfig.DEFAULT_FADE_DP.dp.toPx() }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val track = remember(layout, words, endMs, fadePx, sweepConfig.easing, mode, text.length) {
        val lr = layout
        if (lr == null || mode == LyricsWordAnimationMode.HARD_CUT) {
            null
        } else {
            SweepTrack.build(words, text.length, endMs, TextLayoutSweepGeometry(lr), fadePx, sweepConfig.easing)
        }
    }
    val sampleCursor = remember { SweepTrack.SampleCursor() }
    BasicText(
        text = text,
        style = style.copy(color = unsungColor),
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().drawWithContent {
            val lr = layout
            if (lr == null) {
                drawContent()
                return@drawWithContent
            }
            val focus = emphasis()
            val base = restingColor?.let { lerpColor(it(), unsungColor, focus) } ?: unsungColor
            drawDefocusedText(lr, base, defocus())
            if (focus <= 0f) return@drawWithContent
            val highlight = highlightColor.copy(alpha = highlightColor.alpha * focus)
            val position = currentPositionMillis()
            if (mode == LyricsWordAnimationMode.HARD_CUT) {
                drawHardCut(lr, position, words, text.length, endMs, highlight)
            } else if (track == null) {
                drawText(lr, color = highlight)
            } else {
                track.withSample(position, sampleCursor) { line, x ->
                    drawSweep(lr, line, x, track.rtl, fadePx, sweepConfig.softEdge, highlight)
                }
            }
        },
    )
}

@Composable
internal fun LyricCaption(
    text: String,
    style: TextStyle,
    color: () -> Color,
    defocus: () -> Float,
    modifier: Modifier = Modifier,
) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = text,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            val lr = layout ?: return@drawWithContent
            drawDefocusedText(lr, color(), defocus())
        },
    )
}

// Soft focus lives in glyph draws. Changing the radius during a handoff does not
// install a new blur shader or filter the entire lyric row into an offscreen layer.
private fun DrawScope.drawDefocusedText(layout: TextLayoutResult, color: Color, radius: Float) {
    if (radius < 0.4f) {
        drawText(layout, color = color)
        return
    }
    val spread = (radius - 0.4f).coerceIn(0f, 1f)
    val side = color.copy(alpha = color.alpha * 0.12f * spread)
    val corner = color.copy(alpha = color.alpha * 0.04f * spread)
    drawText(layout, color = side, topLeft = Offset(-radius, 0f))
    drawText(layout, color = side, topLeft = Offset(radius, 0f))
    drawText(layout, color = side, topLeft = Offset(0f, -radius))
    drawText(layout, color = side, topLeft = Offset(0f, radius))
    drawText(layout, color = corner, topLeft = Offset(-radius, -radius))
    drawText(layout, color = corner, topLeft = Offset(radius, -radius))
    drawText(layout, color = corner, topLeft = Offset(-radius, radius))
    drawText(layout, color = corner, topLeft = Offset(radius, radius))
    drawText(layout, color = color.copy(alpha = color.alpha * (1f - 0.64f * spread)))
}

/**
 * v1.5.0 的逐词硬切：按**词**离散推进（词内不插值），保留只为向后兼容。
 * 已唱完的行整行保留，正在唱的这行只保留到扫过点。
 */
private fun DrawScope.drawHardCut(
    lr: TextLayoutResult,
    positionMillis: Long,
    words: List<LrcWord>,
    textLength: Int,
    endMs: Long?,
    color: Color,
) {
    val cut = playedCutCharCount(words, positionMillis, textLength, endMs)
    if (cut <= 0f) return
    val anchor = cutAnchor(lr, cut, textLength)
    val lineTop = lr.getLineTop(anchor.lineIndex)
    val lineBottom = lr.getLineBottom(anchor.lineIndex)
    if (anchor.lineIndex > 0) {
        clipRect(bottom = lr.getLineBottom(anchor.lineIndex - 1)) { drawText(lr, color = color) }
    }
    clipRect(top = lineTop, right = anchor.x, bottom = lineBottom) { drawText(lr, color = color) }
}

/**
 * v1.5.2 的扫过绘制：**只有中间那条窄渐变带需要离屏层**。
 *
 *  ① 光标所在排版行**之上**的行：整行保留（不透明裁剪，零离屏）；
 *  ② 当前行的实心高亮按阅读方向裁剪（零离屏）；
 *  ③ 渐变带本身：把高亮文字画进一个**只有渐变带那么大**的离屏层，再用 DstIn + 横向渐变擦出软边。
 *
 * [softEdge] = false 时跳过 ③，②的边界直接取光标 —— 硬边但仍然是连续运动。
 */
private fun DrawScope.drawSweep(
    lr: TextLayoutResult,
    sampledLine: Int,
    x: Float,
    rtl: Boolean,
    fadePx: Float,
    softEdge: Boolean,
    color: Color,
) {
    val halfWidth = sweepHalfWidth(fadePx)
    val fadeStart = x - halfWidth
    val fadeEnd = x + halfWidth
    // 轨道保证 lineIndex 落在版面内，这里再夹一次纯粹是防御（版面在极端情况下可能先变）。
    val lineIndex = sampledLine.coerceIn(0, (lr.lineCount - 1).coerceAtLeast(0))
    val lineTop = lr.getLineTop(lineIndex)
    val lineBottom = lr.getLineBottom(lineIndex)

    // ① 已经整行唱完的行。用「当前行上沿」而不是「上一行下沿」：两行之间的行距也归上一行，
    //    避免行距里的抗锯齿残影被切掉。
    if (lineIndex > 0) {
        clipRect(bottom = lineTop) { drawText(lr, color = color) }
    }

    // ② 当前行的实心部分。软边时边界在渐变带的外端，硬边时边界就是光标本身。
    val solidEdge = if (softEdge) {
        if (rtl) fadeEnd else fadeStart
    } else {
        x
    }
    if (!rtl) {
        clipRect(top = lineTop, right = solidEdge, bottom = lineBottom) { drawText(lr, color = color) }
    } else {
        clipRect(top = lineTop, left = solidEdge, bottom = lineBottom) { drawText(lr, color = color) }
    }

    if (!softEdge) return

    // ③ 渐变带：离屏层只覆盖这一段。夹到版面内，避免光标远离文字时开一块巨大的离屏缓冲。
    val left = fadeStart.coerceIn(0f, size.width)
    val right = fadeEnd.coerceIn(0f, size.width)
    if (right <= left) return
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(left, lineTop, right, lineBottom), Paint())
    clipRect(left = left, top = lineTop, right = right, bottom = lineBottom) { drawText(lr, color = color) }
    // 渐变用**未夹取**的原始带宽，这样夹取只影响离屏层大小、不影响渐变斜率。
    // 越界部分靠 TileMode.Clamp 自动钳到两端（实心侧全保留、未唱侧全擦除）。
    drawRect(
        brush = if (!rtl) {
            Brush.horizontalGradient(
                colorStops = arrayOf(0f to Color.Black, 1f to Color.Transparent),
                startX = fadeStart,
                endX = fadeEnd,
            )
        } else {
            Brush.horizontalGradient(
                colorStops = arrayOf(0f to Color.Transparent, 1f to Color.Black),
                startX = fadeStart,
                endX = fadeEnd,
            )
        },
        topLeft = Offset(left, lineTop),
        size = Size(right - left, lineBottom - lineTop),
        blendMode = BlendMode.DstIn,
    )
    canvas.restore()
}

/**
 * 把 Compose 的 [TextLayoutResult] 适配成 [SweepGeometry]。
 *
 * Compose 的 `getLineLeft/getLineRight/getBoundingBox` 全部是**视觉坐标**，而轨道要的是
 * **阅读顺序**（RTL 下「前缘」在右边）。方向翻转统一收在这一层，于是 [SweepTrack] 完全与
 * 书写方向无关，可以脱离 Compose 单测（见 SweepTrackTest 的 RTL 用例）。
 */
private class TextLayoutSweepGeometry(private val lr: TextLayoutResult) : SweepGeometry {

    override val rtl: Boolean = lr.layoutInput.layoutDirection == LayoutDirection.Rtl

    override fun lineForChar(charIndex: Int): Int = lr.getLineForOffset(charIndex)

    override fun lineStart(lineIndex: Int): Float =
        if (rtl) lr.getLineRight(lineIndex) else lr.getLineLeft(lineIndex)

    override fun lineEnd(lineIndex: Int): Float =
        if (rtl) lr.getLineLeft(lineIndex) else lr.getLineRight(lineIndex)

    override fun charStart(charIndex: Int): Float {
        val box = lr.getBoundingBox(charIndex)
        return if (rtl) box.right else box.left
    }

    override fun charEnd(charIndex: Int): Float {
        val box = lr.getBoundingBox(charIndex)
        return if (rtl) box.left else box.right
    }
}

/**
 * 已唱到的位置，用「字符数」的浮点值表示。
 *
 * - 越过 yrc 的行结束时刻 → 整行（兜住「词的字符区间没铺满整行」的情况：LRC 的美化文本
 *   可能比词拼接多出结尾标点）；
 * - 正在唱的那个词 → 在该词的字符区间内按时间线性插值（软边因此是连续推进的）；
 * - 词还没开始 → 不推进。
 */
private fun playedCutCharCount(
    words: List<LrcWord>,
    positionMillis: Long,
    textLength: Int,
    endMs: Long?,
): Float {
    if (textLength <= 0) return 0f
    if (endMs != null && positionMillis >= endMs) return textLength.toFloat()
    var cut = 0f
    for (w in words) {
        if (w.startMs > positionMillis) continue
        val start = w.charStart.coerceIn(0, textLength).toFloat()
        val end = w.charEndExclusive.coerceIn(w.charStart.coerceIn(0, textLength), textLength).toFloat()
        val dur = w.durationMs
        val advanced = if (dur > 0L && positionMillis < w.startMs + dur) {
            val t = ((positionMillis - w.startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            start + (end - start) * t
        } else {
            end
        }
        if (advanced > cut) cut = advanced
    }
    return cut.coerceIn(0f, textLength.toFloat())
}

/** 扫过点在版面里的落点：行号 + 该行内的 x（字符内按 [cut] 的小数部分线性插值）。 */
private class CutAnchor(val lineIndex: Int, val x: Float)

private fun cutAnchor(lr: TextLayoutResult, cut: Float, textLength: Int): CutAnchor {
    val lastIndex = (textLength - 1).coerceAtLeast(0)
    val index = cut.toInt().coerceIn(0, lastIndex)
    val fraction = (cut - index).coerceIn(0f, 1f)
    val lineIndex = lr.getLineForOffset(index)
    val box = lr.getBoundingBox(index)
    return CutAnchor(lineIndex, box.left + box.width * fraction)
}

// 播放位置(ms) -> 当前行索引。-1 表示还没到第一行(全部未来行)。
// v1.5.2：提升为 internal —— LyricsView 的逐帧扫过窗口也要用它判「当前行」，
// 两处必须是同一份二分查找，否则「面板高亮的行」与「正在推进的窗口」会错开一行。
internal fun currentLineIndex(positionMillis: Long, timestamps: LongArray): Int {
    if (timestamps.isEmpty()) return -1
    if (positionMillis < timestamps[0]) return -1
    var lo = 0
    var hi = timestamps.size - 1
    if (positionMillis >= timestamps[hi]) return hi
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (timestamps[mid] <= positionMillis) lo = mid else hi = mid - 1
    }
    return lo
}


