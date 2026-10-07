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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
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
import com.takahashirinta.ncrust.lyric.SweepSample
import com.takahashirinta.ncrust.lyric.SweepTrack
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * Metro 歌词面板 -- 左对齐大字,直接复刻 Ncrust 接入前的 LazyColumn 实现,
 * 叠加 Apple Music 式渐入加载。
 *
 * 静态 / 强调(复刻原版):
 *  - 左对齐 32sp 粗体;lineHeight 42sp;行间同字号。
 *  - 颜色离散:当前行 primary、过去行 White@0.6、未来行 Gray@0.4(alpha 烤进颜色,
 *    不走 graphicsLayer alpha,与原版一致)。
 *  - 缩放连续:单个 `smoothCurrentIndex: Animatable<Float>` 在跨行时用 QuickSwitch
 *    (180ms FastOutSlowIn,等同原版 tween(180, FastOutSlowInEasing))从旧行号滑到
 *    新行号;每行 graphicsLayer 按 `dist = abs(index - smoothCurrentIndex.value)` 连续
 *    算 `scale = lerp(1.0, 0.82, (dist/1.8).coerceIn(0,1))`,锚点左侧 TransformOrigin(0, 0.5)。
 *    行 i 退场与行 i+1 进场是同一时刻的连续交接,无翻转瞬间。
 *
 * 渐入加载(新增):歌词从空 -> 非空(或换歌)时,整面板 alpha 0 -> 1 用 CoverFade
 * (400ms CubicBezier(0.2,0,0,1))渐入;未完成时是空的,符合 Apple "没加载完是空的,
 * 然后渐入"。无弹簧、无过冲、无颜色动画。
 *
 * 性能:`smoothCurrentIndex.value` 与 `fadeIn.value` 只在 graphicsLayer lambda 里读,
 * 动画帧内零重组;面板只在离散当前行索引变化时重组(LazyColumn 懒渲染,离屏行不组合)。
 *
 * 滚动交互沿用原版:当前行自动滚到视口 36% 高处(animateScrollToItem,有动画);
 * 用户手动滚动暂停自动跟随 5 秒后恢复;面板显现瞬间 snap 到当前行;点某行回调
 * [onLineClick](tap-to-seek)。
 *
 * @param isVisible 首次变为 true 时瞬间跳到当前行,不等自动滚动慢慢滚过去。
 * @param currentPositionMillis draw phase / derived 阶段读取的播放位置(ms),
 *   内部必须读 snapshot state;面板据此二分定位当前行,只在跨行时重组。
 */
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
)

@Composable
fun NcrustLyricsPanel(
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
) {
    val currentPosition by rememberUpdatedState(currentPositionMillis)
    val timestamps = remember(lines) { LongArray(lines.size) { lines[it].timestampMillis } }
    // 离散当前行:二分定位,只在跨行时变 -> 面板只在跨行时重组。
    val currentIndex by remember(lines) {
        derivedStateOf { currentLineIndex(currentPosition(), timestamps) }
    }
    // 连续当前行:跨行时 QuickSwitch 滑过去,驱动每行 graphicsLayer 缩放。
    val smoothCurrentIndex = remember { Animatable(currentIndex.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            smoothCurrentIndex.animateTo(currentIndex.toFloat(), SokuouTweens.QuickSwitch)
        }
    }

    // 渐入加载:空 -> 非空(或换歌)时整面板 alpha 0 -> 1。由 lines 直接派生,
    // 不用手动 Animatable —— 否则一旦那个 LaunchedEffect 没跑完/没重启,
    // 面板会永远停在 alpha=0, 表现为"歌词明明有却一片空白"。
    val fadeIn by animateFloatAsState(
        targetValue = if (lines.isNotEmpty()) 1f else 0f,
        animationSpec = SokuouTweens.CoverFade,
        label = "lyricsFadeIn",
    )

    // a11y:liveRegion 播报当前行文本,map + distinctUntilChanged 压掉帧级位置流。
    val a11yText = remember { mutableStateOf("") }
    LaunchedEffect(lines) {
        snapshotFlow { currentPosition() }
            .map { currentLineIndex(it, timestamps) }
            .distinctUntilChanged()
            .collect { idx ->
                val line = if (idx >= 0 && idx < lines.size) lines[idx] else null
                // v1.9.3：拼接收进 LyricSubtitleText（JVM 单测覆盖）。音译开关关掉时
                // 它的输出与 v1.9.2 的表达式逐字节一致 —— TalkBack 播报内容不变。
                val text = line?.let {
                    LyricSubtitleText.a11yText(it.text, it.translation, it.romanization)
                } ?: ""
                if (text != a11yText.value) a11yText.value = text
            }
    }

    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    var programmaticScrolling by remember { mutableStateOf(false) }
    var lastAutoScrolledIndex by remember { mutableIntStateOf(-1) }

    // ─────────────────────────────────────────────────────────────────────
    // v2.5.2：条目**实测高度**缓存 + 「整条居中」的定位助手
    //
    // 用户反馈：「自动居中只是把第一行居中，多行歌词、尤其是带翻译的歌，
    // 大屏模式下看起来很痛苦」。根因是定位语义是「**顶边**落在目标高度」，
    // 而一个条目是 `原句 + 译文 + 音译`（含折行）的整块 —— 行数越多，整块越往下坠。
    // 完整推导见 `LyricsPanelScroll.blockTopPx` 的 KDoc。
    //
    // 高度只能实测（折行数取决于可用宽度与字号），所以：
    //  · 用 `snapshotFlow` 把**已经排版过的**条目高度记下来（只有可见的 5~8 条，
    //    开销是每次布局写几个 int）；
    //  · 用普通 `HashMap` 而不是 `mutableStateMapOf`：它**不参与重组**，只在滚动回调里读。
    //    换成 state map 会让每次布局都触发一次面板重组 —— 那是本仓库明令禁止的动画期重组。
    // ─────────────────────────────────────────────────────────────────────
    val itemHeights = remember { HashMap<Int, Int>() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { infos ->
                for (info in infos) itemHeights[info.index] = info.size
            }
    }

    val density = LocalDensity.current
    val baseFadePx = with(density) { LyricsPanelScroll.BASE_FADE_DP.dp.toPx() }
    val minFadePx = with(density) { LyricsPanelScroll.MIN_FADE_DP.dp.toPx() }

    /** 顶边下限 = 上下渐隐带高度（与 `LyricsView` 画出来的是同一个数）。 */
    fun minTopPxOf(viewportHeightPx: Int): Float =
        LyricsPanelScroll.fadeHeightPx(viewportHeightPx, baseFadePx, minFadePx)

    /** 「整条居中」的 offset；高度还没量到 ⇒ 自动回落 v2.5.1 的顶边语义。 */
    fun offsetFor(targetItemIndex: Int, viewportHeightPx: Int): Int =
        LyricsPanelScroll.blockOffsetPx(
            viewportHeightPx = viewportHeightPx,
            leadFraction = leadFraction,
            itemHeightPx = itemHeights[targetItemIndex] ?: -1,
            minTopPx = minTopPxOf(viewportHeightPx),
        )

    /**
     * 直接摆到「整条居中」（无动画）。
     *
     * 高度未知时先按 v2.5.1 的位置摆一次（那一步同时让它**进入可见区**、从而被量到），
     * 等一帧让排版落地，再用实测高度做一次纠正。纠正用 `scrollBy`：这条路径本来就是跳变，
     * 多一段动画反而会看出「跳到位再弹一下」。
     */
    suspend fun placeLine(targetItemIndex: Int, viewportHeightPx: Int) {
        listState.scrollToItem(targetItemIndex, offsetFor(targetItemIndex, viewportHeightPx))
        if ((itemHeights[targetItemIndex] ?: -1) > 0) return
        withFrameNanos { }
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetItemIndex } ?: return
        itemHeights[targetItemIndex] = info.size
        val correction = LyricsPanelScroll.blockCorrectionPx(
            viewportHeightPx = viewportHeightPx,
            leadFraction = leadFraction,
            itemHeightPx = info.size,
            minTopPx = minTopPxOf(viewportHeightPx),
            currentTopPx = info.offset,
        )
        if (correction != 0) listState.scrollBy(correction.toFloat())
    }

    /**
     * 动画走到「整条居中」。
     *
     * 高度已知 ⇒ 一次动画到位（播放中的跨行跟随走的就是这条，也是用户见得最多的一条）。
     * 高度未知（跳到很远、从未排版过的一行）⇒ 先**瞬时**摆到旧的顶边位置让它可见，
     * 拿到高度后再动画走到居中位置。顺序刻意如此：反过来的话用户会先看到
     * 「跳到位、再弹一下」，比「跳到附近、再平滑滑正」更突兀。
     */
    suspend fun animateToLine(targetItemIndex: Int, viewportHeightPx: Int) {
        if ((itemHeights[targetItemIndex] ?: -1) <= 0) {
            listState.scrollToItem(
                targetItemIndex,
                LyricsPanelScroll.leadOffsetPx(viewportHeightPx, leadFraction),
            )
            withFrameNanos { }
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetItemIndex }
                ?.let { itemHeights[targetItemIndex] = it.size }
        }
        listState.animateScrollToItem(targetItemIndex, offsetFor(targetItemIndex, viewportHeightPx))
    }

    // 换歌 / 换歌词表：清状态 + 把当前行重新定位。
    //
    // v2.0.1：这里原来是**无条件** `scrollToItem(0)`（回顶）。在大屏模式下（右栏歌词面板
    // 只有约 260dp 高、而顶部留白 200dp 占掉 77%）「回顶」直接等于「第一句被推到面板底边
    // 之外」，而前奏期间（第一句还没到，currentIndex == -1）下面三条自动路径**全部**提前返回，
    // 于是这个错误位置会一直保持到第一句开唱为止 —— 用户报告的「歌词被自动拉到最顶端、
    // 看不见第一句」。
    //
    // 改成复用同一条定位规则（当前行落在视口 36% 处，-1 视为第 0 行）之后：
    //  - 切歌时位置本来就是 0（PlayerViewModel.resetLyricsForNewSong 显式归零）⇒ 与"回顶"等价；
    //  - 「进大屏 / 点 A± 导致歌词表重建」时不再丢掉当前行（旧实现会把列表甩回顶端，
    //    再等下一次跨行才纠正）。
    // 配合 LyricsPanelScroll 的顶部留白夹取，"回顶"与"首句落在 36%"在数学上已经是同一个位置。
    LaunchedEffect(lines) {
        userScrolling = false
        // v2.5.2：换歌词表 ⇒ 条目高度全部作废（行数/译文/音译都可能变），必须清缓存。
        itemHeights.clear()
        val idx = currentIndex.coerceAtLeast(0)
        smoothCurrentIndex.snapTo(idx.toFloat())
        lastAutoScrolledIndex = currentIndex
        val vh = listState.layoutInfo.viewportSize.height
        if (vh > 0) {
            placeLine(
                LyricsPanelScroll.targetItemIndex(currentIndex, lines.size),
                vh,
            )
        } else {
            // 还没排版（首帧）：先回顶。顶部留白一旦按视口夹取，"回顶"就是"首句落在 36%"。
            listState.scrollToItem(0)
        }
    }

    // 面板显现瞬间直接跳到当前行,不等自动滚动逐行滚过去。
    LaunchedEffect(isVisible) {
        if (!isVisible || lines.isEmpty()) return@LaunchedEffect
        var vh = listState.layoutInfo.viewportSize.height
        if (vh == 0) {
            delay(16)
            vh = listState.layoutInfo.viewportSize.height
        }
        val idx = currentIndex.coerceAtLeast(0)
        smoothCurrentIndex.snapTo(idx.toFloat())
        placeLine(
            LyricsPanelScroll.targetItemIndex(currentIndex, lines.size),
            vh,
        )
        lastAutoScrolledIndex = idx
    }

    // 外部强制定位(如播放器展开): 语义同 isVisible 定位, 但由调用方递增触发。
    // 顺带解除用户手动滚动暂停——展开瞬间用户预期歌词就在当前行。
    LaunchedEffect(forcedScrollTrigger) {
        if (forcedScrollTrigger == 0 || !isVisible || lines.isEmpty()) return@LaunchedEffect
        userScrolling = false
        lastAutoScrolledIndex = -1
        var vh = listState.layoutInfo.viewportSize.height
        if (vh == 0) {
            delay(16)
            vh = listState.layoutInfo.viewportSize.height
        }
        val idx = currentIndex.coerceAtLeast(0)
        smoothCurrentIndex.snapTo(idx.toFloat())
        placeLine(
            LyricsPanelScroll.targetItemIndex(currentIndex, lines.size),
            vh,
        )
        lastAutoScrolledIndex = idx
    }

    // 跨行自动滚动:当前行滚到视口 36% 高处(有动画)。
    //
    // v2.0.1：`currentIndex == -1`（前奏 / 拖回开头 —— 还没到第一句）**也要定位**，目标按第 0 行算。
    // 旧实现在这里 `currentIndex < 0` 直接 return，于是"歌首期间没有任何一条路径把歌词摆正"：
    // 大屏下就表现为列表停在原地（或贴在最顶端），第一句看不见。
    // lastAutoScrolledIndex 记的仍是**原始** currentIndex，所以启动时（-1 == -1）不会多滚一次。
    LaunchedEffect(currentIndex) {
        if (userScrolling || currentIndex == lastAutoScrolledIndex) return@LaunchedEffect
        lastAutoScrolledIndex = currentIndex
        val vh = listState.layoutInfo.viewportSize.height
        programmaticScrolling = true
        try {
            animateToLine(
                LyricsPanelScroll.targetItemIndex(currentIndex, lines.size),
                vh,
            )
        } finally {
            programmaticScrolling = false
        }
    }

    // 用户手动滚动 -> 暂停自动跟随（谁来解除见下面的 v2.3.0 · E 段）。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !programmaticScrolling) {
            if (!userScrolling) {
                userScrolling = true
                onUserScrolled()
            }
        }
        // ★ v2.3.0 · E：这里原来是 `else if (!isScrollInProgress && userScrolling) { delay(5000);
        //   userScrolling = false; lastAutoScrolledIndex = -1 }` —— **只放旗子、不滚动**。
        //   于是「手动调整后歌词留在原地」：重新定位要等 `LaunchedEffect(currentIndex)`，
        //   而它只在**换行**时才跑；用户在间奏里、或暂停后翻看歌词时当前行不变，
        //   那条 effect 一次都不会执行。解除旗子的职责已移交下面的世代计时器
        //   （它做完「解除 + 真正滚回目标位置」两件事），见 LyricsAutoCenter。
    }

    // ------------------------------------------------------------------ v2.3.0 · E
    // 横屏 / 大屏「5s 无触碰自动居中」。
    //
    // ## 计时器凭什么能重来（而不是只跑一次）
    //
    // 每次用户**碰**面板（按下 / 抬起）就把 `touchGeneration` +1；它同时是
    // `LaunchedEffect` 的 key。Compose 在 key 变化时取消上一个协程 ⇒ 天然得到
    // 「最后一次交互之后 5 秒」的语义，不需要手写 Job 管理，也不可能有两个计时器并存。
    //
    // ## 为什么只认「触摸」而不认播放/暂停/seek（任务书 7.2 要求探针确认后决定）
    //
    // · `seek` 有它自己的两条重定位路径（点行 → `onLineClick` 立即定位；
    //   拖动进度条 → 行变化触发 `LaunchedEffect(currentIndex)`），再让 seek 重置计时
    //   只会**推迟**回正，没有任何额外收益；
    // · `播放/暂停` 根本不移动列表，把它算作「交互」会让「暂停后翻看歌词」这种
    //   **最需要回正**的场景永远等不到回正（用户不会每隔 5 秒按一次暂停）。
    //
    // 所以判据收窄成一句：**只有手指落在面板上才算交互**。这也是任务书 7.2 的字面语义。
    var pointerDown by remember { mutableStateOf(false) }
    var touchGeneration by remember { mutableIntStateOf(LyricsAutoCenter.GENERATION_NEVER_TOUCHED) }

    LaunchedEffect(touchGeneration) {
        if (!LyricsAutoCenter.shouldSchedule(touchGeneration)) return@LaunchedEffect
        delay(LyricsAutoCenter.IDLE_TIMEOUT_MS)
        // 到点这一刻手指还按着 ⇒ 放弃（拖拽中不打断）。抬起时世代 +1，会重新计时。
        if (!LyricsAutoCenter.shouldRecenter(pointerDown, lines.size, enabled)) return@LaunchedEffect
        // 先解除「别跟着我」，再滚 —— 顺序不能反：animateScrollToItem 会让
        // isScrollInProgress 变 true，若此时 userScrolling 还是 true，跨行 effect 会继续被挡住。
        userScrolling = false
        lastAutoScrolledIndex = currentIndex
        val vh = listState.layoutInfo.viewportSize.height
        if (vh <= 0) return@LaunchedEffect
        programmaticScrolling = true
        try {
            // 用 animateScrollToItem 而不是 scrollToItem：任务书 7.3 要求「动画平滑、不突兀」。
            // v2.5.2：目标是「**整条**歌词（原句+译文+音译）的中点落在视口正中」，
            // 不再是「条目顶边落在正中」—— 后者正是用户报的「只把第一行居中」。
            animateToLine(
                LyricsPanelScroll.targetItemIndex(currentIndex, lines.size),
                vh,
            )
        } finally {
            programmaticScrolling = false
        }
    }

    // v2.0.1：面板根节点改用 BoxWithConstraints —— 顶部留白要按**视口高度**夹取
    // （`min(200dp, 36% × 视口)`，见 LyricsPanelScroll）。用它而不是 `listState.layoutInfo`：
    // 后者要等第一次排版之后才有值，进大屏那一帧会先按 200dp 排一遍、下一帧才跳回来
    // （正好就是本 bug 的表象）；`maxHeight` 是**约束**，同一帧就是对的。
    // 约束无界（父级可滚动 / 未指定高度）时回落基准值，行为与 v2.0.0 一致。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeIn }
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
                text = AnnotatedString(a11yText.value)
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
            modifier = Modifier.fillMaxSize(),
        ) {
            // v2.0.1：顶部留白 = min(200dp, 36% × 视口)。它同时是"列表顶端"与"第一句的定位槽"，
            // 所以"回顶"在大屏短面板下也不会把第一句推出可视区（详见 LyricsPanelScroll）。
            item(key = "top_spacer") { Spacer(Modifier.height(topSpacerHeight)) }

            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                // 颜色离散:基于整数 currentIndex,跨行时翻转;连续 scale 会盖住这一瞬。
                val color = when {
                    currentIndex < 0 -> futureLineColor
                    index < currentIndex -> pastLineColor
                    index == currentIndex -> currentLineColor
                    else -> futureLineColor
                }
                // v2.0.0 · T4：动态字号（实验性）。倍率为 1f 时**原样使用面板字号**，
                // 刻意不写成 `fontSize * 1f`：这样"关掉开关 ⇒ 渲染表达式与 v1.9.3 一致"
                // 是可以逐行审计的，而不是"值相等但 diff 变了"。
                // 译文/音译同乘同一个倍率 ⇒ 三层比例恒定（与 A-/A+ 的做法一致）。
                val lineScale = line.fontScale
                val lineFontSize = if (lineScale == 1f) fontSize else fontSize * lineScale
                val lineLineHeight = if (lineScale == 1f) lineHeight else lineHeight * lineScale
                val lineTranslationFontSize =
                    if (lineScale == 1f) translationFontSize else translationFontSize * lineScale
                val lineTranslationLineHeight =
                    if (lineScale == 1f) translationLineHeight else translationLineHeight * lineScale
                val lineRomanizationFontSize =
                    if (lineScale == 1f) romanizationFontSize else romanizationFontSize * lineScale
                val lineRomanizationLineHeight =
                    if (lineScale == 1f) romanizationLineHeight else romanizationLineHeight * lineScale
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(enabled, line.timestampMillis) {
                            if (enabled) detectTapGestures { onLineClick(line.timestampMillis) }
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // 连续距离驱动缩放:无翻转瞬间,行间渐变交接。
                                // 缩放放在整行(原句+翻译)外层,双语同时放大/缩小。
                                val dist = abs(index - smoothCurrentIndex.value)
                                var scale = lerp(1f, inactiveScale, (dist / 1.8f).coerceIn(0f, 1f))
                                // v2.9.0 · B 档：只有**当前行**随节拍脉冲。
                                //
                                // `MotionClock.generation` 刻意写在 `if (index == currentIndex)`
                                // **里面**：Compose 的快照观察是动态的，只有当前行这一层会订阅
                                // 帧时钟 —— 若写在外面，28 行歌词每帧各失效一次，那是纯浪费。
                                // 跨行时 `smoothCurrentIndex` 变化会让所有行重新执行本块，
                                // 依赖因此自然迁移到新的当前行，不会留下悬空的订阅。
                                if (lyricPulseEnabled && index == currentIndex) {
                                    MotionClock.generation
                                    scale *= 1f + MotionClock.pulse() * AppMotion.LYRIC_PULSE_SCALE
                                }
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            },
                    ) {
                        // v1.5.0 · B：有逐字数据且开关打开时走卡拉 OK 渲染，否则与 v1.4.1 完全一致。
                        LyricLineBody(
                            text = line.text,
                            words = line.words,
                            endMs = line.endMs,
                            color = color,
                            highlightColor = currentLineColor,
                            // 只有「当前行」才做逐字高亮：已唱完的行会切成 pastLineColor，
                            // 若在那些行上再叠高亮色，整行会被重新点亮成 primary，破坏过去行的弱化。
                            enabled = karaokeEnabled && index == currentIndex,
                            mode = wordAnimationMode,
                            sweepConfig = sweepConfig,
                            currentPositionMillis = currentPosition,
                            style = TextStyle(
                                fontSize = lineFontSize,
                                lineHeight = lineLineHeight,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        if (line.translation.isNotEmpty()) {
                            // Spotify 式双语:原句下方小号、降透明度渲染翻译。
                            MetroText(
                                text = line.translation,
                                style = TextStyle(
                                    fontSize = lineTranslationFontSize,
                                    lineHeight = lineTranslationLineHeight,
                                    fontWeight = FontWeight.Normal,
                                ),
                                softWrap = true,
                                color = color.copy(alpha = color.alpha * 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            )
                        }
                        // v1.9.3：音译槽（原文 → 译文 → 音译，与 ncm 官方客户端的三层顺序一致）。
                        // 刻意**不**把上面那个译文槽重构成共用的私有 composable：本版的红线之一是
                        // 「关掉音译开关时渲染路径与 v1.9.2 逐字节一致」，让译文分支源码保持原样是
                        // 最省事的证明方式（同构的十来行重复一次，换一条可审计的不变量）。
                        //
                        // 隐藏走**条件挂载**：romanization 为空时这个 MetroText 根本不在 Composition 里
                        // （AGENTS.md「Compose 触摸陷阱」第 1/4 条：alpha=0 的节点仍然参与命中测试）。
                        // 也没有任何 pointerInput：它落在主行 Box 的 detectTapGestures 命中区内，
                        // 点它 == 点主行（seek 到本行），不新增任何命中区。
                        if (line.romanization.isNotEmpty()) {
                            MetroText(
                                text = line.romanization,
                                style = TextStyle(
                                    fontSize = lineRomanizationFontSize,
                                    lineHeight = lineRomanizationLineHeight,
                                    fontWeight = FontWeight.Normal,
                                ),
                                softWrap = true,
                                color = color.copy(alpha = color.alpha * 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(200.dp)) }
        }
    }
}


/**
 * v1.5.0 · B / v1.5.1 · A / v1.5.2 —— 单行歌词渲染。
 *
 * 三种模式（[mode]，见 [LyricsWordAnimationMode]）：
 *  - [LyricsWordAnimationMode.OFF]：不做逐字，当前行整行高亮 —— 与 v1.4.1 逐字节一致；
 *  - [LyricsWordAnimationMode.HARD_CUT]：v1.5.0 的逐词硬切，向后兼容保留（按**词**离散跳）；
 *  - [LyricsWordAnimationMode.GRADIENT_SWEEP]（默认）：连续光标 + 软边横扫。
 *
 * **v1.5.2 修掉的问题**（PCL110 上肉眼可见的「一顿一顿 + 行首硬切」）：
 *
 *  1. **光标不再按词跳变**。v1.5.1 的 [playedCutCharCount] 里那段「词内按时间线性插值」在真机上
 *     从未生效 —— 调用方 [LyricsView] 只在「行时间戳 ∪ 词起始时刻」写播放位置，绘制阶段永远读到
 *     词边界那一刻，于是每个词唱完才跳一格，软边只在替跳变磨圆。现在位置在**当前行正在唱**的
 *     窗口内逐帧推进，[SweepTrack] 给出任意时刻的连续光标。
 *  2. **渐变带宽度绑字号，不绑整行宽度**。v1.5.1 取整行宽度的 12%（`SWEEP_EDGE_FRACTION`），
 *     短行糊成一片、长行细得看不见，且与字号无关。现在取 [LyricsSweepConfig.fadeEm] × 字号
 *     （默认 0.65 em ≈ 0.65 个字宽），与 AMLL 的 `wordFadeWidth = 0.5 × 行高` 等效。
 *  3. **渐变带以光标为中心**，光标恰好落在 50% 透明度处 —— 「光标 == 播放进度」由单测断言，
 *     v1.5.1 的渐变带是「光标贴右沿、整条带向左展开」，光标与进度差着半个带宽。
 *  4. **首尾各外扩半个渐变带**，渐变完整地滑入 / 滑出整行，行首不再是硬切（v1.5.1 把
 *     `edge` 夹在「行左沿 → 扫过点」之间，行首那个词的软边会被夹到 ~1px，等于硬切）。
 *  5. **离屏层只开在渐变带这条窄带上**，而不是整行。离屏面积从「整行宽 × 行高」降到
 *     「0.65 em × 行高」（约 1/20），低端机的 render-target 切换代价随之下降。
 *     [LyricsSweepConfig.softEdge] = false 时完全不用离屏层，退化成纯 `clipRect` 硬边扫过 ——
 *     **位置仍然是连续的**，只是边缘不磨圆；这是 API 24 / 低内存设备的自动降级档。
 *
 * **v1.5.1 · A 的既有设计原样保留**：
 *  1. 弃用 `TextLayoutResult.getPathForRange` + `clipPath`（该 API 内部走
 *     `android.text.Layout.getSelectionPath`，在 Android 16 (API 36) 上取不到可用的裁剪区域，
 *     v1.5.0 因此在 PCL110 上完全没有逐字效果）；现在只用 `clipRect` 与
 *     `canvas.saveLayer` + `drawRect(blendMode = DstIn)` 这两条最稳定的原语。
 *     不用 `CompositingStrategy.Offscreen`：它在低版本上会回落成 Auto，DstIn 就会作用到
 *     整个目标画布、把底下的文字一起擦掉；
 *  2. **零重组**：播放位置只在 draw 阶段读 —— 逐字推进只让这一行重绘，不触发任何重组；
 *  3. **不自己排版**：高亮层与底层用同一 [TextStyle]（只差颜色）排版，换行 / 断字 / CJK
 *     避头尾完全交给 Compose，两层版面逐像素一致；
 *  4. **换行不跨行横扫**：软边遮罩逐行画，正在唱的那一行独立渐变，已唱完的行整行保留。
 *
 * 没有逐字数据、模式为 OFF、或不是当前行时，退回与 v1.4.1 完全相同的整行 MetroText。
 */
@Composable
private fun LyricLineBody(
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
) {
    if (!enabled || words.isEmpty() || mode == LyricsWordAnimationMode.OFF) {
        MetroText(
            text = text,
            style = style,
            softWrap = true,
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    // 未唱部分弱化：当前行原来的整行 primary 变成「已唱的实心 + 未唱的淡影」，
    // 与 Kanesumi 的离散取色一致；唱完的部分才回到满色 primary。
    // 比例可配（默认 0.4，与 AMLL 当前行的 --dark-mask-alpha 同档）。
    val unsungColor = color.copy(alpha = color.alpha * sweepConfig.inactiveAlpha)
    // 渐变带宽度绑字号：不随行宽变化，短行长行观感一致。
    val density = LocalDensity.current
    val fadePx = remember(style.fontSize, sweepConfig.fadeEm, density) {
        val fs = style.fontSize
        if (fs.isSp) with(density) { fs.toPx() * sweepConfig.fadeEm }
        else with(density) { LyricsSweepConfig.DEFAULT_FADE_DP.dp.toPx() }
    }
    // 底层排版结果同时给高亮层用：两层 style 只差颜色，版面完全一致。
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    // 扫过轨道：由排版结果**派生**（不是塞在 onTextLayout 回调里 —— 否则 fadePx / 配置变化
    // 不会触发重排版，轨道就永远停在 null）。remember 的键保证只在真正变样时重建。
    val track = remember(layout, words, endMs, fadePx, sweepConfig.easing, mode, text.length) {
        val lr = layout
        if (lr == null || mode == LyricsWordAnimationMode.HARD_CUT) {
            null
        } else {
            SweepTrack.build(
                words = words,
                textLength = text.length,
                endMs = endMs,
                geo = TextLayoutSweepGeometry(lr),
                fadePx = fadePx,
                easing = sweepConfig.easing,
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        // 底层：整行用弱化色画一遍。
        BasicText(
            text = text,
            style = style.copy(color = unsungColor),
            softWrap = true,
            modifier = Modifier.fillMaxWidth(),
            onTextLayout = { layout = it },
        )
        // 高亮层：同一份文本、高亮色，按模式裁掉「还没唱到」的部分。
        // clearAndSetSemantics：文本已经由底层节点暴露给无障碍，这一层不能再报一遍。
        BasicText(
            text = text,
            style = style.copy(color = highlightColor),
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { }
                .drawWithContent {
                    val lr = layout ?: return@drawWithContent
                    val pos = currentPositionMillis()
                    if (mode == LyricsWordAnimationMode.HARD_CUT) {
                        drawHardCut(lr, pos, words, text.length, endMs)
                        return@drawWithContent
                    }
                    val sweep = track
                    if (sweep == null) {
                        // 有词、但词的字符区间全部越界（坏数据）：整行高亮，与 v1.5.1 行为一致。
                        drawContent()
                        return@drawWithContent
                    }
                    // 还没唱到第一个词 → 高亮层整层不画，露出的就是底层的未唱色。
                    val sample = sweep.sample(pos) ?: return@drawWithContent
                    drawSweep(lr, sample, fadePx, sweepConfig.softEdge)
                },
        )
    }
}

/**
 * v1.5.0 的逐词硬切：按**词**离散推进（词内不插值），保留只为向后兼容。
 * 已唱完的行整行保留，正在唱的这行只保留到扫过点。
 */
private fun ContentDrawScope.drawHardCut(
    lr: TextLayoutResult,
    positionMillis: Long,
    words: List<LrcWord>,
    textLength: Int,
    endMs: Long?,
) {
    val cut = playedCutCharCount(words, positionMillis, textLength, endMs)
    if (cut <= 0f) return
    val anchor = cutAnchor(lr, cut, textLength)
    val lineTop = lr.getLineTop(anchor.lineIndex)
    val lineBottom = lr.getLineBottom(anchor.lineIndex)
    // clipRect 的 block 接收者是 DrawScope（没有 drawContent），
    // 所以先把 ContentDrawScope 捕获下来，两条绘制路径都用显式接收者。
    val content = this
    if (anchor.lineIndex > 0) {
        clipRect(bottom = lr.getLineBottom(anchor.lineIndex - 1)) { content.drawContent() }
    }
    clipRect(top = lineTop, right = anchor.x, bottom = lineBottom) { content.drawContent() }
}

/**
 * v1.5.2 的扫过绘制：**只有中间那条窄渐变带需要离屏层**。
 *
 *  ① 光标所在排版行**之上**的行：整行保留（不透明裁剪，零离屏）；
 *  ② 当前行里「已唱实心」的一段：由 [SweepBand.litOnLeft] 决定它在渐变带的左侧还是右侧（零离屏）；
 *  ③ 渐变带本身：把高亮文字画进一个**只有渐变带那么大**的离屏层，再用 DstIn + 横向渐变擦出软边。
 *
 * [softEdge] = false 时跳过 ③，②的边界直接取光标 —— 硬边但仍然是连续运动。
 */
private fun ContentDrawScope.drawSweep(
    lr: TextLayoutResult,
    sample: SweepSample,
    fadePx: Float,
    softEdge: Boolean,
) {
    val content = this
    val band = sample.band(fadePx)
    // 轨道保证 lineIndex 落在版面内，这里再夹一次纯粹是防御（版面在极端情况下可能先变）。
    val lineIndex = sample.lineIndex.coerceIn(0, (lr.lineCount - 1).coerceAtLeast(0))
    val lineTop = lr.getLineTop(lineIndex)
    val lineBottom = lr.getLineBottom(lineIndex)

    // ① 已经整行唱完的行。用「当前行上沿」而不是「上一行下沿」：两行之间的行距也归上一行，
    //    避免行距里的抗锯齿残影被切掉。
    if (lineIndex > 0) {
        clipRect(bottom = lineTop) { content.drawContent() }
    }

    // ② 当前行的实心部分。软边时边界在渐变带的外端，硬边时边界就是光标本身。
    val solidEdge = if (softEdge) {
        if (band.litOnLeft) band.fadeStart else band.fadeEnd
    } else {
        sample.x
    }
    if (band.litOnLeft) {
        clipRect(top = lineTop, right = solidEdge, bottom = lineBottom) { content.drawContent() }
    } else {
        clipRect(top = lineTop, left = solidEdge, bottom = lineBottom) { content.drawContent() }
    }

    if (!softEdge) return

    // ③ 渐变带：离屏层只覆盖这一段。夹到版面内，避免光标远离文字时开一块巨大的离屏缓冲。
    val left = band.fadeStart.coerceIn(0f, size.width)
    val right = band.fadeEnd.coerceIn(0f, size.width)
    if (right <= left) return
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(left, lineTop, right, lineBottom), Paint())
    clipRect(left = left, top = lineTop, right = right, bottom = lineBottom) { content.drawContent() }
    // 渐变用**未夹取**的原始带宽，这样夹取只影响离屏层大小、不影响渐变斜率。
    // 越界部分靠 TileMode.Clamp 自动钳到两端（实心侧全保留、未唱侧全擦除）。
    drawRect(
        brush = if (band.litOnLeft) {
            Brush.horizontalGradient(
                colorStops = arrayOf(0f to Color.Black, 1f to Color.Transparent),
                startX = band.fadeStart,
                endX = band.fadeEnd,
            )
        } else {
            Brush.horizontalGradient(
                colorStops = arrayOf(0f to Color.Transparent, 1f to Color.Black),
                startX = band.fadeStart,
                endX = band.fadeEnd,
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
