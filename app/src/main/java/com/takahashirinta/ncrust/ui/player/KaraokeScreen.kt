/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LyricsSweepConfig
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow

internal class KaraokeControls(
    val title: String,
    val artist: String,
    val duration: StateFlow<Long>,
    val progress: StateFlow<Float>,
    val buffering: StateFlow<Boolean>,
    val transport: PlayerCardCallbacks,
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
) {
    val strings = LocalStrings.current
    val copy = strings.playerUi.karaoke
    val colors = LocalMetroColors.current
    val translatedPrimary = controls.translatedPrimary

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 430.dp
        val headline = (minOf(maxWidth.value / 10.5f, if (compact) 39f else 58f)
            .coerceAtLeast(23f) * fontScale).coerceIn(20f, 72f)
        Column(
            Modifier.widthIn(max = 960.dp).fillMaxSize()
                .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 2.dp else 8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KaraokeButton(PlayerGlyphs.Back, copy.exit, controls.exit)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    MetroText(controls.title, color = colors.onBackground, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = TextStyle(fontWeight = FontWeight.SemiBold))
                    if (controls.artist.isNotBlank()) {
                        MetroText(controls.artist, color = colors.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 12.sp))
                    }
                }
                if (translations.isNotEmpty()) {
                    MetroText(
                        if (translatedPrimary) copy.translation else copy.original,
                        Modifier.clip(AppShapes.full).clickable(
                            role = Role.Button, onClickLabel = copy.changeLanguage, onClick = controls.toggleLanguage,
                        ).padding(horizontal = 10.dp, vertical = 14.dp),
                        color = colors.primary, style = TextStyle(fontSize = 13.sp),
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val frame = cue.current to cue.next
                val content: @Composable (Pair<Int, Int>) -> Unit = { (current, next) ->
                    key(current, next) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(vertical = if (compact) 4.dp else 12.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val line = lines.getOrNull(current)
                            if (line == null) {
                                val caption = when {
                                    loading && lines.isEmpty() -> copy.loading
                                    lines.isEmpty() -> strings.noLyrics
                                    cue.count != null -> copy.ready + "  " + cue.count
                                    else -> copy.ready
                                }
                                MetroText(caption, color = colors.primary,
                                    style = TextStyle(fontSize = 17.sp, textAlign = TextAlign.Center))
                            } else {
                                val translation = translations[line.timestampMillis]
                                val primaryTranslation = translation.takeIf { translatedPrimary }
                                val preferTranslation = primaryTranslation != null
                                Column(
                                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onSeek(line.timestampMillis) },
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    val mainStyle = TextStyle(
                                        fontSize = headline.sp, lineHeight = (headline * 1.18f).sp,
                                        textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                                    )
                                    LyricLineBody(
                                        text = primaryTranslation?.text ?: line.text,
                                        words = primaryTranslation?.words ?: line.words,
                                        endMs = if (primaryTranslation != null) primaryTranslation.endMs else line.endMs,
                                        color = colors.onBackground, highlightColor = colors.onBackground,
                                        enabled = wordsEnabled, mode = animationMode, currentPositionMillis = position,
                                        style = mainStyle, sweepConfig = sweep,
                                    )
                                    val secondary = if (preferTranslation) line.text else translation?.text
                                    if (!secondary.isNullOrBlank()) {
                                        LyricLineBody(
                                            text = secondary,
                                            words = if (preferTranslation) line.words else translation?.words.orEmpty(),
                                            endMs = if (preferTranslation) line.endMs else translation?.endMs,
                                            color = colors.onSurfaceVariant, highlightColor = colors.onBackground,
                                            enabled = wordsEnabled, mode = animationMode, currentPositionMillis = position,
                                            style = mainStyle.copy(fontSize = (headline * 0.52f).sp,
                                                lineHeight = (headline * 0.7f).sp, fontWeight = FontWeight.Medium),
                                            sweepConfig = sweep,
                                        )
                                    }
                                    if (line.romanization.isNotBlank()) {
                                        MetroText(line.romanization, Modifier.fillMaxWidth(),
                                            color = colors.onSurfaceVariant, style = TextStyle(
                                                fontSize = (headline * 0.39f).sp, textAlign = TextAlign.Center))
                                    }
                                }
                            }
                            lines.getOrNull(next)?.let { upcoming ->
                                val text = if (translatedPrimary)
                                    translations[upcoming.timestampMillis]?.text ?: upcoming.text else upcoming.text
                                MetroText(
                                    text, Modifier.fillMaxWidth().clickable(role = Role.Button) { onSeek(upcoming.timestampMillis) }
                                        .padding(vertical = 5.dp),
                                    color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontSize = (headline * 0.59f).sp, textAlign = TextAlign.Center),
                                )
                            }
                        }
                    }
                }
                if (ValueAnimator.areAnimatorsEnabled()) {
                    AnimatedContent(
                        targetState = frame, contentAlignment = Alignment.Center,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(110)) },
                        label = "karaoke phrase",
                    ) { frame -> content(frame) }
                } else content(frame)
            }

            SlimProgressBar(controls.progress, controls.duration, controls.buffering,
                onSeek = { fraction ->
                    val duration = controls.duration.value
                    if (duration > 0L) onSeek((duration * fraction).toLong())
                }, horizontalPadding = 8.dp)
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KaraokeButton(PlayerGlyphs.Previous, strings.prevButton, controls.transport.onPlayPrevious)
                KaraokeButton(
                    if (playing) PlayerGlyphs.Pause else PlayerGlyphs.Play,
                    if (playing) strings.pauseButton else strings.playButton,
                    controls.transport.onPlayPause, primary = true,
                )
                KaraokeButton(PlayerGlyphs.Next, strings.nextButton, controls.transport.onPlayNext)
            }
        }
    }
}

@Composable
internal fun KaraokeButton(icon: ImageVector, label: String, onClick: () -> Unit, primary: Boolean = false) {
    val colors = LocalMetroColors.current
    val tint = if (primary) colors.background else colors.onBackground
    Box(
        Modifier.size(if (primary) 48.dp else 44.dp).clip(AppShapes.full)
            .background(if (primary) colors.primary else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroIcon(imageVector = icon, contentDescription = label, tint = tint, sizeDp = 20.dp)
    }
}
