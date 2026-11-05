/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.RoundedCornerShape
import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LyricsSweepConfig
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow

/**
 * 3.7.30 · LTS 的跟唱文案。
 *
 * 主线把这组放在 `Strings.playerUi.karaoke`（8 语言齐全）。LTS 这条线不扩 i18n 面
 * （要动 Strings + 8 个 locale + 迁移测试），因此收敛成一个本地对象。
 */
internal object KaraokeCopy {
    const val enter = "跟唱"
    const val exit = "退出跟唱"
    const val original = "原文"
    const val translation = "译文"
    const val changeLanguage = "切换主行"
    const val loading = "正在载入歌词"
    const val ready = "准备"
}

internal class KaraokeControls(
    val embedded: Boolean,
    val title: String,
    val artist: String,
    val cover: Bitmap?,
    val position: StateFlow<Long>,
    val duration: StateFlow<Long>,
    val progress: StateFlow<Float>,
    val buffering: StateFlow<Boolean>,
    val transport: PlayerCardCallbacks,
    val previousEnabled: Boolean,
    val exit: () -> Unit,
    val translatedPrimary: Boolean,
    val toggleLanguage: () -> Unit,
)

@Composable
internal fun KaraokeScreen(
    controls: KaraokeControls,
    lines: List<NcrustLyricLine>,
    translations: Map<Long, LrcLine>,
    cue: KaraokeCue,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    playing: Boolean,
    loading: Boolean,
    wordsEnabled: Boolean,
    animationMode: Int,
    sweep: LyricsSweepConfig,
    fontScale: Float,
    interactive: Boolean,
) {
    val strings = LocalStrings.current
    val copy = KaraokeCopy
    val colors = LocalMetroColors.current
    val focusSweep = remember(sweep) { sweep.copy(inactiveAlpha = maxOf(sweep.inactiveAlpha, 0.62f)) }
    val density = LocalDensity.current
    val phraseTravel = with(density) { 10.dp.roundToPx() }
    val animateStage = ValueAnimator.areAnimatorsEnabled()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 430.dp
        val split = compact && maxWidth >= 600.dp
        val horizontalMargin = if (compact) 16.dp else 24.dp
        val stageWidth = minOf(maxWidth.value, 880f) - horizontalMargin.value * 2
        val phraseWidth = if (split) stageWidth * 0.62f else stageWidth
        val headline = (minOf(phraseWidth / 8.7f, if (compact) 41f else 54f)
            .coerceAtLeast(23f) * fontScale).coerceIn(20f, 68f)

        Column(
            Modifier.widthIn(max = 880.dp).fillMaxSize()
                .padding(horizontal = horizontalMargin, vertical = if (compact) 2.dp else 10.dp),
        ) {
            KaraokeHeader(controls, compact, hasTranslations = translations.isNotEmpty())

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                contentAlignment = Alignment.TopStart,
            ) {
                val topSpace = if (compact) minOf(maxHeight * 0.13f, 28.dp)
                    else minOf(maxHeight * 0.26f, 148.dp)
                if (lines.isEmpty()) {
                    MetroText(
                        if (loading) copy.loading else strings.noLyrics,
                        Modifier.align(Alignment.Center),
                        color = colors.onSurfaceVariant,
                        style = TextStyle(fontSize = 17.sp, textAlign = TextAlign.Center),
                    )
                } else {
                    val stage: @Composable (KaraokeCue, Modifier) -> Unit = { frame, previewMotion ->
                        val current = lines.getOrNull(frame.current)
                        val next = lines.getOrNull(frame.next)
                        val isActiveFrame = frame.current == cue.current && frame.next == cue.next
                        val canSeek = interactive && isActiveFrame
                        val focus: @Composable (Modifier) -> Unit = { modifier ->
                            if (current == null) {
                                if (frame.count != null) KaraokeCountIn(copy.ready, frame.count, modifier)
                            } else {
                                val translation = translations[current.timestampMillis]
                                KaraokeFocus(
                                    line = current,
                                    translation = translation,
                                    translatedPrimary = controls.translatedPrimary,
                                    position = position,
                                    wordsEnabled = wordsEnabled,
                                    mode = animationMode,
                                    sweep = focusSweep,
                                    headline = headline,
                                    animateRail = animateStage && isActiveFrame,
                                    modifier = modifier.clickable(enabled = canSeek, role = Role.Button) {
                                        onSeek(current.timestampMillis)
                                    },
                                )
                            }
                        }
                        val context: @Composable (NcrustLyricLine, Modifier, Boolean) -> Unit = { line, modifier, subdued ->
                            KaraokeContextPhrase(
                                line = line,
                                translatedPrimary = controls.translatedPrimary,
                                headline = headline,
                                subdued = subdued,
                                showSecondary = false,
                                modifier = modifier.clickable(enabled = canSeek, role = Role.Button) {
                                    onSeek(line.timestampMillis)
                                },
                            )
                        }
                        val upcoming: @Composable (Modifier) -> Unit = { modifier ->
                            if (next != null) context(next, modifier.then(previewMotion), false)
                        }

                        key(frame.current, frame.next) {
                            val contentModifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(top = topSpace, bottom = if (compact) 6.dp else 16.dp)
                            if (split) {
                                Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                                    focus(Modifier.weight(0.62f))
                                    val preview = Modifier.weight(0.38f)
                                    if (next == null) Spacer(preview) else upcoming(preview.padding(top = 4.dp))
                                }
                            } else {
                                Column(
                                    contentModifier,
                                    verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 24.dp),
                                ) {
                                    focus(Modifier.fillMaxWidth())
                                    upcoming(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    if (animateStage) {
                        AnimatedContent(
                            targetState = cue,
                            contentKey = { it.current to it.next },
                            transitionSpec = {
                                val direction = if (targetState.next == -1 ||
                                    (initialState.next != -1 && targetState.next >= initialState.next)
                                ) 1 else -1
                                val incoming = fadeIn(tween(215, delayMillis = 25)) +
                                    slideInVertically(tween(295, easing = CueArrivalEasing)) { phraseTravel * direction }
                                val outgoing = fadeOut(tween(125)) +
                                    slideOutVertically(tween(175)) { -phraseTravel * direction / 2 }
                                incoming togetherWith outgoing
                            },
                            label = "karaoke cue",
                        ) { frame ->
                            stage(
                                frame,
                                Modifier.animateEnterExit(
                                    enter = fadeIn(tween(180, delayMillis = 90)),
                                    exit = ExitTransition.None,
                                ),
                            )
                        }
                    } else stage(cue, Modifier)
                }
            }

            KaraokeTransport(controls, playing, onSeek)
        }
    }
}

@Composable
internal fun PortraitFocusLyrics(
    controls: KaraokeControls,
    lines: List<NcrustLyricLine>,
    translations: Map<Long, LrcLine>,
    cue: KaraokeCue,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    loading: Boolean,
    wordsEnabled: Boolean,
    animationMode: Int,
    sweep: LyricsSweepConfig,
    fontScale: Float,
    interactive: Boolean,
    visible: Boolean,
    forcedScrollTrigger: Int,
    onUserScrolled: () -> Unit,
    tools: @Composable () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val focusSweep = remember(sweep) { sweep.copy(inactiveAlpha = maxOf(sweep.inactiveAlpha, 0.62f)) }
    val animateRail = ValueAnimator.areAnimatorsEnabled()
    val followIndex = if (cue.current >= 0) cue.current else cue.next
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        val headline = (minOf(46f, (maxWidth.value - 19f) / 8.5f)
            .coerceAtLeast(23f) * fontScale).coerceIn(20f, 68f)
        Column(Modifier.fillMaxSize().padding(bottom = 7.dp)) {
            if (interactive && lines.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tools()
                    if (translations.isNotEmpty()) KaraokeLanguageButton(controls)
                }
            }
            if (lines.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MetroText(
                        if (loading) KaraokeCopy.loading else strings.noLyrics,
                        color = colors.onSurfaceVariant,
                        style = TextStyle(fontSize = 17.sp, textAlign = TextAlign.Center),
                    )
                }
            } else {
                NcrustLyricsPanel(
                    lines = lines,
                    currentPositionMillis = position,
                    modifier = Modifier.weight(1f),
                    isVisible = visible,
                    forcedScrollTrigger = forcedScrollTrigger,
                    enabled = interactive,
                    onLineClick = onSeek,
                    onUserScrolled = onUserScrolled,
                    focusIndex = followIndex,
                    focusedRow = { index, line, browsing ->
                        if (index == cue.current) {
                            KaraokeFocus(
                                line, translations[line.timestampMillis], controls.translatedPrimary,
                                position, wordsEnabled, animationMode, focusSweep, headline,
                                animateRail = animateRail, modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            if (index == cue.next && cue.count != null) {
                                KaraokeCountIn(KaraokeCopy.ready, cue.count, Modifier.fillMaxWidth())
                            }
                            val lower = !browsing && cue.current >= 0 && index > cue.current
                            val blur = if (!lower) 0.dp else if (index == cue.current + 1) 0.7.dp else 1.3.dp
                            KaraokeContextPhrase(
                                line = line,
                                translatedPrimary = controls.translatedPrimary,
                                headline = headline,
                                subdued = !browsing && index != cue.next,
                                showSecondary = true,
                                fullText = true,
                                modifier = if (blur > 0.dp) Modifier.fillMaxWidth().blur(blur) else Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
            }
        }
    }
}

private val CueArrivalEasing = CubicBezierEasing(0.22f, 0.8f, 0.2f, 1f)

@Composable
private fun KaraokeHeader(controls: KaraokeControls, compact: Boolean, hasTranslations: Boolean) {
    val copy = KaraokeCopy
    val colors = LocalMetroColors.current
    val cover = remember(controls.cover) { controls.cover?.asImageBitmap() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KaraokeButton(PlayerGlyphs.Back, copy.exit, controls.exit)
        Box(
            Modifier.size(if (compact) 35.dp else 42.dp)
                .clip(AppShapes.small)
                .background(colors.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                MetroIcon(PlayerGlyphs.Karaoke, contentDescription = null, tint = colors.primary, sizeDp = 17.dp)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            MetroText(
                controls.title, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            )
            if (controls.artist.isNotBlank()) {
                MetroText(
                    controls.artist, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 12.sp),
                )
            }
        }
        if (hasTranslations) KaraokeLanguageButton(controls)
    }
}

@Composable
private fun KaraokeLanguageButton(controls: KaraokeControls) {
    val copy = KaraokeCopy
    val colors = LocalMetroColors.current
    MetroText(
        if (controls.translatedPrimary) copy.translation else copy.original,
        Modifier.widthIn(max = 106.dp).clip(AppShapes.full).clickable(
            role = Role.Button, onClickLabel = copy.changeLanguage, onClick = controls.toggleLanguage,
        ).padding(horizontal = 12.dp, vertical = 14.dp),
        color = colors.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    )
}

@Composable
private fun KaraokeFocus(
    line: NcrustLyricLine,
    translation: LrcLine?,
    translatedPrimary: Boolean,
    position: () -> Long,
    wordsEnabled: Boolean,
    mode: Int,
    sweep: LyricsSweepConfig,
    headline: Float,
    animateRail: Boolean,
    modifier: Modifier,
) {
    val colors = LocalMetroColors.current
    val lastWordEnd = remember(line, translation) {
        maxOf(
            line.words.maxOfOrNull { it.startMs + it.durationMs } ?: line.timestampMillis,
            translation?.words?.maxOfOrNull { it.startMs + it.durationMs } ?: line.timestampMillis,
        )
    }
    val railProgress: (() -> Float)? = if (animateRail && wordsEnabled && lastWordEnd > line.timestampMillis) {
        { (position() - line.timestampMillis).toFloat() / (lastWordEnd - line.timestampMillis) }
    } else null
    val primaryTranslation = translation.takeIf { translatedPrimary }
    val mainStyle = TextStyle(
        fontSize = headline.sp,
        lineHeight = (headline * 1.17f).sp,
        textAlign = TextAlign.Start,
        fontWeight = FontWeight.Bold,
    )
    Column(
        modifier.cueRail(colors.primary, focused = true, progress = railProgress).padding(start = 19.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        LyricLineBody(
            text = primaryTranslation?.text ?: line.text,
            words = primaryTranslation?.words ?: line.words,
            endMs = if (primaryTranslation != null) primaryTranslation.endMs else line.endMs,
            color = colors.onBackground,
            highlightColor = colors.onBackground,
            enabled = wordsEnabled,
            mode = mode,
            currentPositionMillis = position,
            style = mainStyle,
            sweepConfig = sweep,
        )
        val secondary = if (primaryTranslation != null) line.text else translation?.text
        if (!secondary.isNullOrBlank()) {
            LyricLineBody(
                text = secondary,
                words = if (primaryTranslation != null) line.words else translation?.words.orEmpty(),
                endMs = if (primaryTranslation != null) line.endMs else translation?.endMs,
                color = colors.onBackground.copy(alpha = 0.76f),
                highlightColor = colors.onBackground,
                enabled = wordsEnabled,
                mode = mode,
                currentPositionMillis = position,
                style = mainStyle.copy(
                    fontSize = (headline * 0.53f).sp, lineHeight = (headline * 0.69f).sp, fontWeight = FontWeight.Medium,
                ),
                sweepConfig = sweep,
            )
        }
        if (line.romanization.isNotBlank()) {
            MetroText(
                line.romanization, Modifier.fillMaxWidth(), color = colors.onSurfaceVariant,
                style = TextStyle(fontSize = (headline * 0.38f).sp, textAlign = TextAlign.Start),
            )
        }
    }
}

@Composable
private fun KaraokeContextPhrase(
    line: NcrustLyricLine,
    translatedPrimary: Boolean,
    headline: Float,
    subdued: Boolean,
    showSecondary: Boolean,
    modifier: Modifier,
    fullText: Boolean = false,
) {
    val colors = LocalMetroColors.current
    val preferTranslation = translatedPrimary && line.translation.isNotBlank()
    val main = if (preferTranslation) line.translation else line.text
    val secondary = if (preferTranslation) line.text else line.translation
    val size = headline * if (subdued) 0.44f else 0.55f
    val color = colors.onBackground.copy(alpha = if (subdued) 0.51f else 0.70f)
    Column(
        modifier.padding(start = 19.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MetroText(
            main, color = color, maxLines = if (fullText) Int.MAX_VALUE else if (subdued) 1 else 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.23f).sp, fontWeight = FontWeight.Medium),
        )
        if (showSecondary && secondary.isNotBlank()) {
            MetroText(
                secondary, color = color.copy(alpha = color.alpha * 0.83f), maxLines = if (fullText) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = (size * 0.78f).sp),
            )
        }
    }
}

@Composable
private fun KaraokeCountIn(description: String, remaining: Int, modifier: Modifier) {
    val colors = LocalMetroColors.current
    Row(
        modifier.cueRail(colors.primary, focused = true).padding(start = 19.dp, top = 9.dp, bottom = 9.dp)
            .clearAndSetSemantics { contentDescription = "$description $remaining" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val brightness by animateFloatAsState(
                if (index < remaining) 1f else 0.23f,
                animationSpec = tween(185, easing = CueArrivalEasing),
                label = "count-in dot",
            )
            Box(
                Modifier.size(7.dp)
                    .graphicsLayer { alpha = brightness }
                    .clip(AppShapes.full)
                    .background(colors.primary),
            )
        }
    }
}

private fun Modifier.cueRail(accent: Color, focused: Boolean, progress: (() -> Float)? = null): Modifier = drawWithCache {
    val x = if (layoutDirection == LayoutDirection.Ltr) 3.dp.toPx() else size.width - 3.dp.toPx()
    val marker = Offset(x, 7.dp.toPx())
    val track = Brush.verticalGradient(
        listOf(accent.copy(alpha = 0.65f), accent.copy(alpha = 0f)),
        startY = marker.y,
        endY = maxOf(marker.y + 1f, size.height),
    )
    onDrawBehind {
        if (focused) {
            drawLine(track, start = marker, end = Offset(x, size.height), strokeWidth = 1.dp.toPx())
            val sung = progress?.invoke()?.coerceIn(0f, 1f) ?: 0f
            if (sung > 0f) {
                val tail = Offset(x, marker.y + (size.height - marker.y) * sung)
                drawLine(accent.copy(alpha = 0.16f), marker, tail, 5.dp.toPx(), StrokeCap.Round)
                drawLine(accent.copy(alpha = 0.83f), marker, tail, 1.3.dp.toPx(), StrokeCap.Round)
            }
            drawLine(
                accent, start = Offset(x, 3.dp.toPx()), end = Offset(x, 14.dp.toPx()),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
            )
        } else {
            drawCircle(accent.copy(alpha = 0.65f), radius = 2.dp.toPx(), center = marker, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
internal fun CompactPlaybackProgress(
    positionFlow: StateFlow<Long>,
    progressFlow: StateFlow<Float>,
    durationFlow: StateFlow<Long>,
    bufferingFlow: StateFlow<Boolean>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMetroColors.current
    val sampledPosition by positionFlow.collectAsStateWithLifecycle()
    val duration by durationFlow.collectAsStateWithLifecycle()
    val durationText = formatDuration(duration)
    val timeWidth = (durationText.length.coerceAtLeast(4) * 7).dp
    val timeStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Row(
        modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetroText(
            formatDuration(sampledPosition), Modifier.widthIn(min = timeWidth),
            color = colors.onSurfaceVariant, style = timeStyle, maxLines = 1,
        )
        SlimProgressBar(
            progressFlow, durationFlow, bufferingFlow,
            onSeek = { fraction ->
                val length = durationFlow.value
                if (length > 0) onSeek((length * fraction).toLong())
            },
            modifier = Modifier.weight(1f),
            horizontalPadding = 0.dp,
        )
        MetroText(
            durationText, Modifier.widthIn(min = timeWidth),
            color = colors.onSurfaceVariant, style = timeStyle.copy(textAlign = TextAlign.End), maxLines = 1,
        )
    }
}

@Composable
private fun KaraokeTransport(controls: KaraokeControls, playing: Boolean, onSeek: (Long) -> Unit) {
    CompactPlaybackProgress(controls.position, controls.progress, controls.duration, controls.buffering, onSeek)
    KaraokeTransport(
        isPlaying = playing,
        previousEnabled = controls.previousEnabled,
        transport = controls.transport,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
    )
}

@Composable
internal fun KaraokeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    Box(
        Modifier.size(44.dp).clip(AppShapes.full)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroIcon(
            imageVector = icon, contentDescription = label, tint = colors.onBackground, sizeDp = 20.dp,
        )
    }
}

/**
 * 3.7.30 · LTS 用的紧凑传输键（上一首 / 播放暂停 / 下一首）。
 *
 * 主线把它放在 `FullPlayerControls.CompactTransportControls`（同文件里还有玩家的
 * 巨型控制条）。LTS 那份文件里 `transportSurface` 是 private，直接搬会连带改它的可见性；
 * 这里保留同一套排布与触感，只让依赖留在跟唱页面内部。
 */
@Composable
private fun KaraokeTransport(
    isPlaying: Boolean,
    previousEnabled: Boolean,
    transport: PlayerCardCallbacks,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val haptic = LocalHapticFeedback.current
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KaraokeSkipButton(PlayerGlyphs.Previous, strings.prevButton, previousEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            transport.onPlayPrevious()
        }
        Box(
            Modifier.size(56.dp).clip(AppShapes.full)
                .background(colors.primary)
                .clickable(role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    transport.onPlayPause()
                },
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(
                imageVector = if (isPlaying) PlayerGlyphs.Pause else PlayerGlyphs.Play,
                contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                tint = colors.onPrimary,
                sizeDp = 26.dp,
            )
        }
        KaraokeSkipButton(PlayerGlyphs.Next, strings.nextButton, enabled = true) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            transport.onPlayNext()
        }
    }
}

@Composable
private fun KaraokeSkipButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    Box(
        Modifier.size(48.dp).clip(AppShapes.full).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroIcon(
            icon, contentDescription = label, sizeDp = 24.dp,
            tint = if (enabled) colors.onBackground else colors.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** 3.7.30：歌词页工具条上的「跟唱」入口。 */
@Composable
internal fun KaraokeEntryButton(onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    Box(
        Modifier.size(44.dp, 28.dp).clip(AppShapes.small).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroIcon(
            imageVector = PlayerGlyphs.Karaoke,
            contentDescription = KaraokeCopy.enter,
            tint = colors.onSurfaceVariant,
            sizeDp = 18.dp,
        )
    }
}
