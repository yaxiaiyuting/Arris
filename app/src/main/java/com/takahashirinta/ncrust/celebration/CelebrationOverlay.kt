/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 *
 * 修改说明：
 *   - v3.9.0：生日庆祝界面彻底重构。
 *     · 引入现代高级森系夜色与香槟金视觉体系 (CelebrationPalette)
 *     · 全面采用药丸胶囊交互组件 (CelebrationPillButton)
 *     · 吹气阶段重构为声波脉冲微光呼吸圈 (AcousticBreathingRing)
 *     · 祝福阶段重构为精致信笺磨砂卡片 (BlessingLines)
 *     · 彩蛋自由听众席重构为黑胶典藏音乐推荐卡片 (ListenerEggCard)
 */

package com.takahashirinta.ncrust.celebration

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

@Composable
internal fun rememberMotionAllowed(): Boolean {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val p = CelebrationPrefs.prefs(context)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { tick++ }
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false, observer
        )
        onDispose {
            p.unregisterOnSharedPreferenceChangeListener(listener)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return remember(context, tick) {
        MotionPrefs.readUiMotionEnabled(CelebrationPrefs.prefs(context)) &&
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                ) > 0
            }.getOrDefault(false)
    }
}

/** Fullscreen native layer. Closing/backgrounding removes both View clock and AudioRecord. */
@Composable
internal fun NativeCelebrationOverlay(
    kind: CelebrationKind,
    onClose: () -> Unit,
    onVisible: () -> Unit = {},
    recommendation: SongItem? = null,
    onPlayRecommendation: (SongItem) -> Unit = {},
    onRerollRecommendation: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val s = LocalStrings.current.celebration
    val options = rememberCelebrationOptions(context)
    val motion = rememberMotionAllowed()
    var failed by remember { mutableStateOf(false) }
    val animated = options.animated && motion && !failed
    var phase by remember {
        mutableStateOf(
            if (kind == CelebrationKind.LISTENER) CelebrationPhase.DONE
            else if (animated) CelebrationPhase.ARRIVING
            else CelebrationPhase.WAITING
        )
    }
    var micRequested by remember { mutableStateOf(false) }
    var micArmed by remember { mutableStateOf(false) }
    var micFeedback by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    val close by rememberUpdatedState(onClose)
    val visible by rememberUpdatedState(onVisible)
    val placement = remember { CelebrationPlacement() }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (active && phase == CelebrationPhase.WAITING) {
            micRequested = granted
            if (!granted) micFeedback = s.micDenied
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                active = false; micRequested = false; close()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { active = false; lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) visible()
    }

    LaunchedEffect(animated) {
        if (!animated) {
            phase = if (kind == CelebrationKind.LISTENER ||
                phase.ordinal >= CelebrationPhase.EXTINGUISHING.ordinal
            ) CelebrationPhase.DONE else CelebrationPhase.WAITING
        }
    }

    LaunchedEffect(phase, animated) {
        if (!animated) return@LaunchedEffect
        val next = when (phase) {
            CelebrationPhase.ARRIVING -> CelebrationTiming.ARRIVE to CelebrationPhase.PLACING
            CelebrationPhase.PLACING -> CelebrationTiming.PLACE to CelebrationPhase.LIGHTING
            CelebrationPhase.LIGHTING -> CelebrationTiming.LIGHT to CelebrationPhase.WAITING
            CelebrationPhase.EXTINGUISHING -> CelebrationTiming.EXTINGUISH to CelebrationPhase.BLACKOUT
            CelebrationPhase.BLACKOUT -> CelebrationTiming.BLACKOUT to CelebrationPhase.FIREWORKS
            CelebrationPhase.FIREWORKS -> CelebrationTiming.SHOW to CelebrationPhase.BLESSING
            CelebrationPhase.BLESSING -> CelebrationTiming.REVEAL to CelebrationPhase.DONE
            else -> null
        }
        next?.let { delay(it.first); phase = it.second }
    }

    LaunchedEffect(micRequested, phase) {
        if (!micRequested || phase != CelebrationPhase.WAITING) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            micFeedback = ""; micArmed = false
            val result = BlowRecorder.listen(context) { micArmed = true }
            micRequested = false
            if (active && phase == CelebrationPhase.WAITING) when (result) {
                BlowResult.DETECTED -> phase =
                    if (animated) CelebrationPhase.EXTINGUISHING else CelebrationPhase.DONE
                BlowResult.TIMEOUT -> micFeedback = s.micTimeout
                BlowResult.UNAVAILABLE -> micFeedback = s.micUnavailable
            }
        }
    }

    Dialog(
        onDismissRequest = { micRequested = false; close() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(CelebrationPalette.backgroundBrush)
        ) {
            AndroidView(
                factory = {
                    CelebrationArtView(it).apply {
                        importantForAccessibility =
                            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        placement.renderer = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        val p = it.positionInRoot()
                        placement.root(p.x, p.y)
                    },
                update = { view ->
                    view.onDrawingFailure = { failed = true }
                    view.configure(phase, kind, animated)
                }
            )

            CelebrationText(
                kind = kind,
                phase = phase,
                animated = animated,
                mic = micRequested,
                armed = micArmed,
                feedback = micFeedback,
                placement = placement,
                recommendation = recommendation,
                onPlayRecommendation = { song -> onPlayRecommendation(song); close() },
                onRerollRecommendation = onRerollRecommendation,
                onClose = { micRequested = false; close() },
                onMic = {
                    if (!micRequested) {
                        micFeedback = ""
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) micRequested = true
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onBlow = {
                    micRequested = false
                    phase = if (animated) CelebrationPhase.EXTINGUISHING else CelebrationPhase.DONE
                },
                onReplay = {
                    micRequested = false; micFeedback = ""
                    phase = if (animated) CelebrationPhase.ARRIVING else CelebrationPhase.WAITING
                }
            )
        }
    }
}

@Composable
private fun CelebrationText(
    kind: CelebrationKind,
    phase: CelebrationPhase,
    animated: Boolean,
    mic: Boolean,
    armed: Boolean,
    feedback: String,
    placement: CelebrationPlacement,
    recommendation: SongItem? = null,
    onPlayRecommendation: (SongItem) -> Unit = {},
    onRerollRecommendation: () -> Unit = {},
    onClose: () -> Unit,
    onMic: () -> Unit,
    onBlow: () -> Unit,
    onReplay: () -> Unit,
) {
    val s = LocalStrings.current.celebration
    val pal = CelebrationPalette
    val night = phase == CelebrationPhase.BLACKOUT || phase == CelebrationPhase.FIREWORKS
    val candle = phase == CelebrationPhase.LIGHTING || phase == CelebrationPhase.WAITING ||
        phase == CelebrationPhase.EXTINGUISHING
    val done = phase == CelebrationPhase.DONE
    val wishes = phase == CelebrationPhase.BLESSING || done

    // 预留底部交互区高度：保持全局固定，避免阶段切换时背景与插画产生高度升降或跳动
    val footerReserve = if (kind == CelebrationKind.LISTENER) 140.dp else 210.dp

    CelebrationFrame(
        placement = placement,
        heading = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部状态栏：品牌微标与关闭按钮
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetroText(
                        if (night) "" else "ARRIS / CELEBRATION",
                        color = pal.textTitle.copy(
                            alpha = if (candle) pal.brandDim else pal.brandBright
                        ),
                        style = TextStyle(
                            fontSize = 11.sp,
                            letterSpacing = 2.4.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    CelebrationCloseButton(onClick = onClose)
                }

                // 阶段主标题与叙事副标题
                if (!night) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MetroText(
                            text = if (kind == CelebrationKind.LISTENER) s.eggTitle
                            else s.titles[phase.ordinal],
                            color = pal.textTitle.copy(alpha = if (candle) 0.18f else 1f),
                            style = TextStyle(
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                letterSpacing = 0.5.sp,
                                lineHeight = 36.sp
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        MetroText(
                            text = if (kind == CelebrationKind.LISTENER) s.eggCaption
                            else s.captions[phase.ordinal],
                            color = pal.textBody.copy(alpha = if (candle) 0.16f else 1f),
                            style = TextStyle(
                                fontSize = 13.sp,
                                letterSpacing = 0.3.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                        )
                    }
                } else {
                    Spacer(Modifier.height(105.dp))
                }
            }
        },
        footer = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!night) {
                    // 祝福语卡片
                    if (wishes && kind == CelebrationKind.BIRTHDAY) {
                        BlessingLines(phase, animated)
                    }

                    // 许愿吹蜡烛阶段
                    if (phase == CelebrationPhase.WAITING) {
                        WaitingActions(
                            mic = mic,
                            armed = armed,
                            feedback = feedback,
                            onMic = onMic,
                            onBlow = onBlow
                        )
                    }

                    // 结束收尾阶段
                    if (done) {
                        if (kind == CelebrationKind.LISTENER) {
                            ListenerEggCard(
                                recommendation = recommendation,
                                onPlay = { recommendation?.let { onPlayRecommendation(it) } },
                                onReroll = onRerollRecommendation,
                                onClose = onClose,
                                continueText = s.continueListening
                            )
                        } else {
                            DoneActions(
                                isBirthday = (kind == CelebrationKind.BIRTHDAY),
                                continueText = s.continueListening,
                                replayText = s.replay,
                                onClose = onClose,
                                onReplay = onReplay
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        },
        footerReserve = footerReserve,
        footerLift = 0.dp
    )
}

/**
 * 等待吹气交互区：带有精致的声波呼吸圈与温润提示。
 */
@Composable
private fun WaitingActions(
    mic: Boolean,
    armed: Boolean,
    feedback: String,
    onMic: () -> Unit,
    onBlow: () -> Unit,
) {
    val s = LocalStrings.current.celebration
    val pal = CelebrationPalette

    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.extraLarge)
            .background(pal.glassCardBg, AppShapes.extraLarge)
            .border(1.dp, pal.glassCardBorderSubtle, AppShapes.extraLarge)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (mic) {
            AcousticBreathingRing(armed = armed)
            Spacer(Modifier.height(10.dp))
        }

        MetroText(
            text = feedback.ifBlank { s.micHint },
            color = if (feedback.isNotEmpty()) pal.textFeedback else pal.textBody,
            style = TextStyle(
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        )
        Spacer(Modifier.height(14.dp))

        val micButtonText = when {
            mic && armed -> s.micListening
            mic -> s.micCalibrating
            else -> s.micStart
        }

        CelebrationPillButton(
            text = micButtonText,
            onClick = onMic,
            enabled = !mic,
            candle = true,
            leadingIcon = if (!mic) Icons.Default.Mic else null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        CelebrationPillButton(
            text = s.manualBlow,
            onClick = onBlow,
            primary = false,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 祝福语信笺卡片：优雅精致的排版与错峰淡入。
 */
@Composable
private fun BlessingLines(phase: CelebrationPhase, animated: Boolean) {
    val s = LocalStrings.current.celebration
    val pal = CelebrationPalette

    Box(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.extraLarge)
            .background(pal.glassCardBgElevated, AppShapes.extraLarge)
            .border(1.dp, pal.glassCardBorder, AppShapes.extraLarge)
            .padding(horizontal = 24.dp, vertical = 22.dp)
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            s.wishes.forEachIndexed { index, text ->
                val alpha = remember {
                    Animatable(if (!animated || phase == CelebrationPhase.DONE) 1f else 0f)
                }
                LaunchedEffect(phase, animated) {
                    if (animated && phase == CelebrationPhase.BLESSING) {
                        alpha.snapTo(0f)
                        delay(index * 750L)
                        alpha.animateTo(1f, SokuouTweens.CoverFade)
                    } else {
                        alpha.snapTo(1f)
                    }
                }

                val isHighlight = index == 2
                MetroText(
                    text = text,
                    color = if (isHighlight) pal.textWarmHighlight else pal.textBody,
                    style = TextStyle(
                        fontSize = if (isHighlight) 17.sp else 13.sp,
                        fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = if (isHighlight) 26.sp else 22.sp,
                        letterSpacing = if (isHighlight) 0.6.sp else 0.2.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (isHighlight) 14.dp else 4.dp)
                        .graphicsLayer {
                            this.alpha = alpha.value
                            translationY = (1 - alpha.value) * 6
                        }
                )
            }
        }
    }
}

/**
 * 自由听众席彩蛋：黑胶音乐典藏推荐卡片。
 */
@Composable
private fun ListenerEggCard(
    recommendation: SongItem?,
    onPlay: () -> Unit,
    onReroll: () -> Unit,
    onClose: () -> Unit,
    continueText: String,
) {
    val pal = CelebrationPalette

    Column(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.extraLarge)
            .background(pal.glassCardBgElevated, AppShapes.extraLarge)
            .border(1.dp, pal.glassCardBorder, AppShapes.extraLarge)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (recommendation != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                MetroIcon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = pal.textWarmHighlight,
                    sizeDp = 14.dp
                )
                Spacer(Modifier.width(6.dp))
                MetroText(
                    "专属自由曲目",
                    color = pal.textWarmHighlight,
                    style = TextStyle(
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            MetroText(
                text = recommendation.name,
                color = pal.textTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            )
            Spacer(Modifier.height(4.dp))
            val artistName = recommendation.artists?.joinToString(" / ") { it.name }.orEmpty().ifBlank { "未知艺人" }
            MetroText(
                text = artistName,
                color = pal.textBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            )
            Spacer(Modifier.height(16.dp))
            CelebrationPillButton(
                text = "就听这首",
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CelebrationPillButton(
                    text = "换一首",
                    onClick = onReroll,
                    primary = false,
                    leadingIcon = Icons.Default.Refresh,
                    modifier = Modifier.weight(1f)
                )
                CelebrationPillButton(
                    text = continueText,
                    onClick = onClose,
                    primary = false,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            MetroText(
                "收藏里还没有歌，先去挑一首喜欢的吧。",
                color = pal.textBody,
                style = TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(16.dp))
            CelebrationPillButton(
                text = continueText,
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 生日完成态：温润舒展的双按钮排列。
 */
@Composable
private fun DoneActions(
    isBirthday: Boolean,
    continueText: String,
    replayText: String,
    onClose: () -> Unit,
    onReplay: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        CelebrationPillButton(
            text = continueText,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        )
        if (isBirthday) {
            Spacer(Modifier.height(10.dp))
            CelebrationPillButton(
                text = replayText,
                onClick = onReplay,
                primary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 兼容层函数：已有代码可能调用 CelebrationButton，内部统一转发至 CelebrationPillButton。
 */
@Composable
internal fun CelebrationButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = true,
    enabled: Boolean = true,
    candle: Boolean = false,
    secondaryColor: Color = CelebrationPalette.btnSecondaryText,
) {
    CelebrationPillButton(
        text = text,
        onClick = onClick,
        primary = primary,
        enabled = enabled,
        candle = candle,
        modifier = if (primary) Modifier.fillMaxWidth() else Modifier
    )
}
