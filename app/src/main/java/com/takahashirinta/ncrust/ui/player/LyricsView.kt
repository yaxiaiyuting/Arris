package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.lyric.DynamicLyricFont
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LyricClock
import com.takahashirinta.ncrust.lyric.LyricSubtitleText
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepPerf
import com.takahashirinta.ncrust.lyric.LyricsSweepQuality
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.share.LyricShareLine
import com.takahashirinta.ncrust.share.LyricShareTrack
import com.takahashirinta.ncrust.ui.components.LyricShareSheet
import com.takahashirinta.ncrust.ui.HeightClass
import com.takahashirinta.ncrust.ui.LocalHeightClass
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 没有缓冲信息的老调用点：等价于「不缓冲」，行为与旧版一致。 */
private val NeverBuffering = MutableStateFlow(false)

/**
 * 歌词面板 —— 渲染交给 Kanesumi 的 MetroLyricsPanel(左对齐大字,每行独立
 * 弹簧单位,静态效果 = 原版 LazyColumn 实现)。这里只做播放器侧对接:
 *
 *  - 位置外推:Service 侧刻意保持 2Hz 广播(见 PlaybackService.startProgressUpdates
 *    注释),若直接喂给面板,跨行检测会滞后最多 500ms。这里从最近的真实采样
 *    锚点做线性外推(播放时 position ≈ 墙钟),但不再逐帧轮询:只在"下一行
 *    时间戳"的时刻唤醒一次并写 displayPosition,跨行判定精确落在时间戳上,
 *    同时避免 withFrameNanos 每帧把 GPU/CPU 拉起来。暂停/隐藏时停掉循环,不空转。
 *    锚点在每次 2Hz 采样到达时重置,外推永远从最近真实值出发。
 *  - tap-to-seek:点击行 → 本地立即跳 position + 回调解绑回调,瞬时反馈。
 */
@Composable
internal fun LyricsView(
    lyrics: List<LrcLine>,
    translatedLyrics: List<LrcLine> = emptyList(),
    // v2.9.0 · B 档：歌词律动（当前行随节拍轻微缩放）。默认 **false** ——
    // 关掉时渲染路径与 v2.8.0 逐字节一致（见 NcrustLyricsPanel 的同名参数）。
    lyricPulseEnabled: Boolean = false,
    // v2.9.0 · P1（用户报告「歌词会在歌曲没加载出来之前先播放」）：
    // **当前曲目的稳定身份**（换歌时必须变、同一首歌内必须不变）。
    // 默认 null 时下面那条「换歌作废锚点」的效果不发生 —— 老调用点行为不变。
    trackKey: Any? = null,
    showTranslation: Boolean = true,
    // v1.9.3：音译轨（罗马音 / 粤拼），与 translatedLyrics 同构。数据来自 PlayerViewModel
    // 的 v1.9.2 音译轨，渲染层**不重新合并**，只按 timeMs 配对取用。
    romanizedLyrics: List<LrcLine> = emptyList(),
    // v1.9.3：音译显示开关（默认关 ⇒ 渲染路径与 v1.9.2 逐字节一致）。
    showRomanization: Boolean = false,
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    // v3.6.5：音频还在缓冲 / 尚未真正出声时，歌词外推必须停住。
    // 传 StateFlow 引用而不是 Boolean：订阅发生在 LyricsView 内部，
    // 缓冲状态翻转不会让整棵播放器子树重组。
    isBufferingFlow: StateFlow<Boolean> = NeverBuffering,
    isVisible: Boolean,
    forcedLocateTrigger: Int = 0,
    onSeekToMs: (Long) -> Unit,
    enabled: Boolean = true,
    onUserScrolled: () -> Unit = {},
    // 歌词是否仍在加载：加载中且暂无内容时留空，避免切歌瞬间闪一下"暂无歌词"。
    isLoading: Boolean = false,
    // v1.5.0 · B：逐字高亮开关（设置页「逐字歌词」，默认开）。关掉或该行没有 yrc 数据时，
    // 行内渲染与 v1.4.1 完全一致。
    wordByWordEnabled: Boolean = true,
    // v1.5.1 · A：逐字动画模式（渐变扫过 / 逐字硬切 / 关闭逐字），见 LyricsWordAnimationMode。
    wordAnimationMode: Int = LyricsWordAnimationMode.GRADIENT_SWEEP,
    // v1.5.1 · E：歌词字号倍率（0.7~1.5，默认 1.0）。乘在面板的 fontSize/lineHeight 上，
    // 逐字裁剪与自动换行都跟着 TextLayoutResult 走，不需要额外补偿。
    fontScale: Float = 1f,
    // v1.5.2：逐字扫过的绘制质量（LyricsSweepQuality.AUTO/SOFT/EDGE）。
    // 只在「软边 vs 硬边」之间切换，光标位置算法两者完全相同，所以降级不会退回按词跳变。
    sweepQuality: Int = LyricsSweepQuality.AUTO,
    // 点一下 A- / A+ 回调一步（±1 档），倍率换算在 ViewModel 里做。
    onFontScaleStep: (Int) -> Unit = {},
    // v2.0.0 · T4：动态字号（实验性，默认**关**）。按每句估算折行数给这一句一个离散倍率，
    // 纯逻辑见 [com.takahashirinta.ncrust.lyric.DynamicLyricFont]。关掉时下面所有分支都不走，
    // 每行 fontScale = 1f ⇒ 面板里的表达式与 v1.9.3 逐字节一致。
    dynamicFontEnabled: Boolean = false,
    // v2.3.0 · E：**横屏 / 大屏右栏**布局。为 true 时当前行定位到视口正中（0.5）
    // 而不是竖屏的 0.36 —— 短面板下 0.36 看起来是歪的（见 LyricsPanelScroll.CENTER_FRACTION）。
    // 默认 false ⇒ 竖屏调用点的渲染位置一个像素都不动。
    centeredLayout: Boolean = false,
    karaoke: KaraokeControls? = null,
    onOpenKaraoke: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    // v3.4.12：歌词字号按高度档缩放（A- / A+ 的 fontScale 仍在其上乘算，层级比例不变）。
    val baseFontSp = when (LocalHeightClass.current) {
        HeightClass.Regular -> 32f
        HeightClass.Compact -> 24f
        HeightClass.Tiny -> 20f
    }
    val baseLineHeightSp = when (LocalHeightClass.current) {
        HeightClass.Regular -> 42f
        HeightClass.Compact -> 32f
        HeightClass.Tiny -> 27f
    }
    val baseTranslationSp = when (LocalHeightClass.current) {
        HeightClass.Regular -> 20f
        HeightClass.Compact -> 16f
        HeightClass.Tiny -> 14f
    }
    val baseTranslationLineSp = when (LocalHeightClass.current) {
        HeightClass.Regular -> 26f
        HeightClass.Compact -> 21f
        HeightClass.Tiny -> 18f
    }
    if (lyrics.isEmpty() && karaoke == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!isLoading) {
                MetroText(strings.noLyrics, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 18.sp))
            }
            if (enabled && onOpenKaraoke != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp)) {
                    KaraokeButton(PlayerGlyphs.Karaoke, strings.playerUi.karaoke.enter, onOpenKaraoke)
                }
            }
        }
        return
    }

    // list 引用保持稳定,让面板的测量缓存以它为 key 不被误清。
    // 翻译按时间戳精确对齐原句(网易 tlyric 与原 lrc 时间戳一致),缺失的行不显示译文。
    // v1.5.0 · B：逐字时间轴(words/endMs)由 LrcLine 原样带过去 —— 它来自 yrc，已经由
    // YrcParser 对齐到 LRC 的文本上；这里不做任何加工，也不改变行的集合与顺序。
    // v1.9.3：音译同理 —— 按 timeMs 精确配对，配不上的行就是空串（不显示）。
    // 开关关掉时连 Map 都不建（默认路径零额外分配）。
    // "要不要显示这一行"由 LyricSubtitleText.visibleRomanization 决定：空白行、与原文逐字相同的行
    // 都被丢掉，所以「无音译的歌」打开开关也不会多出一行空行（有 JVM 单测 + 真实样本断言）。
    // v2.0.0 · T4：动态字号需要"文本可用宽度"。面板本身是 fillMaxSize + 20dp 横向内边距
    // （见下方 NcrustLyricsPanel 的 modifier），所以可用宽度 = 本 Box 宽 − 40dp；
    // 首帧拿不到宽度时按 0 处理 ⇒ 倍率一律 1f（等价于功能没开），拿到宽度后重算一次。
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    val dynamicFontAvailablePx = with(LocalDensity.current) {
        (panelWidthPx - 40.dp.toPx()).coerceAtLeast(0f)
    }
    /**
     * 4.0.0：**宽度感知的字号上限**。
     *
     * 字号此前只按**高度档**给（Regular 32sp / Compact 24 / Tiny 20）。竖屏手机是"高而窄"：
     * 高度档给 Regular ⇒ 32sp，可面板只有 ~360dp 宽，一句 15 字的中文要折成 3 行，
     * 译文/音译再一叠就撑破面板 —— 也就是"横屏正常、竖屏用不了"。
     *
     * 这里按「一行至少放得下 9.5 个全角字」反推上限，下限 16sp；
     * 只**降**不**升**（`min(基准, 上限)`），所以横屏/大屏行为不变。
     */
    val widthCapSp = with(LocalDensity.current) {
        if (panelWidthPx <= 0f) baseFontSp
        else (panelWidthPx.toDp().value / 9.5f).coerceIn(16f, baseFontSp)
    }
    /** 实际生效的倍率：用户 A± 的倍率 × 宽度上限带来的压缩。 */
    val effectiveScale = fontScale * (widthCapSp / baseFontSp)

    // 基准字号（px）：判定折行数用，与面板拿到的 sp 同源（用户 A-/A+ 是"基准"，
    // 动态倍率在它之上浮动，不替换它）。
    val baseOriginalFontPx = with(LocalDensity.current) { (baseFontSp * effectiveScale).sp.toPx() }
    // 每行的字号倍率。键里带上宽度与 fontScale：换歌/旋转/A-/A+ 才重算；面板宽度变化会
    // 触发一次重算（首帧一次，之后稳定）。刻意**不**写进歌词缓存 —— 它是派生显示量
    // （AGENTS.md：能不加缓存字段就不加）。
    val lineScales = remember(
        lyrics, dynamicFontEnabled, dynamicFontAvailablePx, effectiveScale,
    ) {
        if (!dynamicFontEnabled || dynamicFontAvailablePx <= 0f) {
            emptyList()
        } else {
            DynamicLyricFont.scalesForLyrics(
                lines = lyrics,
                availWidthPx = dynamicFontAvailablePx,
                fontSizePx = baseOriginalFontPx,
                baseFontSizeSp = baseFontSp * effectiveScale,
            )
        }
    }

    val useWordTiming = wordByWordEnabled && wordAnimationMode != LyricsWordAnimationMode.OFF
    val lineStarts = remember(lyrics, useWordTiming) { LyricClock.lineStarts(lyrics, useWordTiming) }
    val panelLines = remember(
        lyrics, translatedLyrics, showTranslation, romanizedLyrics, showRomanization, lineScales, lineStarts,
    ) {
        val tMap = if (showTranslation) translatedLyrics.associateBy { it.timeMs } else emptyMap()
        val rMap = if (showRomanization) romanizedLyrics.associateBy { it.timeMs } else emptyMap()
        lyrics.mapIndexed { index, line ->
            NcrustLyricLine(
                timestampMillis = line.timeMs,
                text = line.text,
                translation = tMap[line.timeMs]?.text ?: "",
                romanization = LyricSubtitleText.visibleRomanization(
                    main = line.text,
                    romanization = rMap[line.timeMs]?.text ?: "",
                    show = showRomanization,
                ),
                words = line.words,
                endMs = line.endMs,
                fontScale = lineScales.getOrElse(index) { 1f },
                playbackStartMillis = lineStarts[index],
            )
        }
    }

    // 订阅位置流:collectAsState 建 State;displayPosition 只在面板 draw/derived
    // 阶段被读,按需写一次也只重算当前行,不触发本 Composable 重组。
    val positionState = positionFlow.collectAsStateWithLifecycle()
    val isBuffering by isBufferingFlow.collectAsStateWithLifecycle()
    val positionChanged = remember { Channel<Unit>(Channel.CONFLATED) }
    // 初值取当前流值而非 0:否则面板首次出现时会先按"位置 0"高亮第一行/吸顶,
    // 等锚点被采样纠正后才跳回真实行。按需唤醒把纠错窗口拉长到最多 1s, 这个初值
    // 尤为重要。
    val displayPosition = remember { mutableLongStateOf(positionState.value) }
    val anchor = remember {
        PositionAnchor().apply {
            anchorPosMs = positionState.value
            anchorNanos = System.nanoTime()
            anchorTrackKey = trackKey
        }
    }

    val karaokeActive = karaoke != null
    val karaokeTranslations = remember(karaokeActive, showTranslation, lyrics, translatedLyrics) {
        if (!karaokeActive || !showTranslation || translatedLyrics.isEmpty()) emptyMap()
        else {
            val originals = lyrics.associateBy { it.timeMs }
            buildMap {
                for (line in translatedLyrics) {
                    val original = originals[line.timeMs] ?: continue
                    if (line.text.isNotBlank() && line.text != original.text) put(line.timeMs, line)
                }
            }
        }
    }
    val karaokeTimeline = remember(karaokeActive, lyrics, karaokeTranslations, lineStarts) {
        if (karaokeActive) KaraokeTimeline(lyrics, karaokeTranslations, lineStarts) else null
    }
    val wakeSchedule = remember(lyrics, lineStarts, karaokeTranslations, karaokeTimeline, useWordTiming) {
        LyricWakeSchedule(
            lines = lyrics,
            lineStarts = lineStarts,
            translations = karaokeTranslations,
            useWordTiming = useWordTiming,
            extraBoundaries = karaokeTimeline?.boundaries.orEmpty(),
        )
    }

    // v1.5.2：扫过参数（渐变带宽度 / 未唱透明度 / 缓动 / 软边），由设备能力 + 设置解析。
    val context = LocalContext.current
    val sweepConfig = remember(context, sweepQuality) {
        LyricsSweepPerf.resolve(
            context,
            context.getSharedPreferences(
                LyricsDisplayPrefs.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            ),
        )
    }

    // An incoming audio sample re-anchors the clock and interrupts a pending wait.
    // A change of lyric presentation replaces the schedule without rewinding the clock.
    val currentTrackKey = rememberUpdatedState(trackKey)
    val currentPlaying = rememberUpdatedState(isPlaying)
    val currentBuffering = rememberUpdatedState(isBuffering)
    val currentKaraokeActive = rememberUpdatedState(karaokeActive)
    LaunchedEffect(Unit) {
        snapshotFlow { positionState.value }.collect { pos ->
            val now = System.nanoTime()
            val sampleDiscontinuity = currentKaraokeActive.value &&
                kotlin.math.abs(pos - anchor.positionAt(now)) > 320L
            anchor.anchorPosMs = pos
            anchor.anchorNanos = now
            anchor.speed = currentPlaybackSpeed()
            anchor.anchorTrackKey = currentTrackKey.value
            val drift = kotlin.math.abs(pos - displayPosition.longValue)
            if (!currentPlaying.value || currentBuffering.value || sampleDiscontinuity || drift > 1_500L) {
                displayPosition.longValue = pos
            }
            positionChanged.trySend(Unit)
        }
    }

    // v2.9.0 · P1（用户报告「歌词会在歌曲没加载出来之前先播放」）：**换歌时作废外推锚点**。
    //
    // ## 症状与根因（先读这段，再改这里）
    //
    // 点一首新歌之后，歌词面板在新歌音频还没加载出来时就开始滚动 / 高亮 —— 看起来像
    // 「歌词先播了」。根因不是歌词引擎，而是**锚点的归属**：
    //
    //  - `anchor` 是一个「位置 + 单调时刻」的快照，外推循环用 `anchorPosMs + 已过时间`
    //    当作当前播放位置；
    //  - 切歌那一刻，`anchorPosMs` 还是**上一首**的位置（比如 3:30），而 `isPlaying`
    //    要等 ExoPlayer 的状态机走到 `STATE_BUFFERING` 才翻假 —— 中间那一小段里
    //    外推循环是**开着**的；
    //  - 真正把锚点拉回来的是 2Hz 位置采样，最多要等 500ms（再加上缓冲时间）。
    //
    // 于是那一段窗口里，新歌的歌词被「上一首的时间戳」定位 —— 直接跳到很靠后的行、
    // 或者逐字光标一路扫过去。等第一条采样到达才 `drift > 1500` 拉回开头，用户看到的就是
    // 「歌词先播了一下，然后跳回第一句」。
    //
    // ## 修法
    //
    // 1. 换歌**立即**把显示位置与锚点归零（新歌总是从 0 开始）；
    // 2. 外推**只在锚点属于当前曲目时**才允许（见下面 `anchorValid`）——
    //    这样即使有一条迟到的旧采样把锚点重新武装起来，也不会被拿去做外推。
    //
    // ## 代价（如实记录）
    //
    // 「进程重建后续播」会先显示第一行，最多 500ms 后被第一条采样纠正到真实行。
    // 这个代价比"歌词先播"小得多，而且只发生在冷重建这一种场景。
    LaunchedEffect(trackKey) {
        anchor.anchorPosMs = 0L
        anchor.anchorNanos = System.nanoTime()
        anchor.anchorTrackKey = trackKey
        displayPosition.longValue = 0L
    }

    LaunchedEffect(isPlaying, isBuffering, isVisible, enabled, trackKey) {
        anchor.anchorPosMs = positionState.value
        anchor.anchorNanos = System.nanoTime()
        anchor.anchorTrackKey = trackKey
        displayPosition.longValue = positionState.value
    }
    LaunchedEffect(isPlaying, isBuffering, isVisible, enabled, wakeSchedule, trackKey) {
        anchor.speed = currentPlaybackSpeed()
        if (!isPlaying || isBuffering || !isVisible || !enabled) return@LaunchedEffect
        val boundaries = wakeSchedule.boundaries
        while (true) {
            // Wait for the first real sample and do not advance another track's clock.
            if (positionState.value <= 0L || anchor.anchorTrackKey != trackKey) {
                positionChanged.receive()
                continue
            }

            val nowMs = anchor.positionAt(System.nanoTime())
            val sweeping = wakeSchedule.sweepingAt(nowMs)
            val previous = displayPosition.longValue
            val current = LyricClock.displayPosition(previous, nowMs, boundaries, sweeping)
            if (current != previous) displayPosition.longValue = current

            if (sweeping) {
                withFrameNanos { }
            } else {
                val next = LyricClock.nextBoundaryAfter(boundaries, nowMs)
                if (next == null) {
                    positionChanged.receive()
                } else {
                    val waitMs = ((next - nowMs) / anchor.speed).toLong().coerceIn(1L, 1_000L)
                    withTimeoutOrNull(waitMs) { positionChanged.receive() }
                }
            }
        }
    }

    // ── v3.3.0 · 9：歌词复制 / 分享 / 出图的入口 ────────────────────────────────
    //
    // 三条设计约束，读一眼再改：
    //
    // 1. **只加一个入口，不动既有动画 / 手势 / 命中测试**。分享按钮挂在上方那一排
    //    （A- / A+ 左边），与它们共用 44×28dp 的命中盒；不新增任何全屏或贴边浮层，
    //    因此不会在本应用那套「播放器卡片死带」问题上新增一条。
    // 2. **点击那一刻把歌词与位置快照下来**（[SharePayload]），不是让弹层实时读播放位置。
    //    出图要几秒，期间用户可能已经听到下一段；选段必须固定在他点下去时看到的那几句上。
    //    快照同时是「弹层打开时不再持有 LyricsView 的 recomposition 依赖」的保证。
    // 3. **曲目身份从 PlayerViewModel 取**（PlayerCard 里那个 `viewModel()` 的同一个实例，
    //    作用域是 Activity）：本组件的唯一调用点在 `PlayerCardExpanded`，它的签名不在本次
    //    改动范围内。这里只在**点击时**读 `.value`（不 collectAsState），
    //    所以不会新增任何逐帧 / 逐次重组订阅。
    val shareViewModel: PlayerViewModel = viewModel()
    var sharePayload by remember { mutableStateOf<SharePayload?>(null) }

    val lyricTools: @Composable () -> Unit = {
        if (onOpenKaraoke != null) {
            KaraokeButton(PlayerGlyphs.Karaoke, strings.playerUi.karaoke.enter, onOpenKaraoke)
        }
        LyricShareButton(description = strings.share.action) {
            sharePayload = SharePayload(
                track = LyricShareTrack(
                    name = shareViewModel.currentSongName.value.orEmpty(),
                    artist = shareViewModel.currentSongArtist.value.orEmpty(),
                    coverUrl = shareViewModel.currentSongArtwork.value,
                ),
                lines = buildShareLines(lyrics, translatedLyrics, romanizedLyrics),
                positionMs = displayPosition.longValue,
            )
        }
        FontScaleButton(
            label = "A-",
            description = strings.lyricsFontSmaller,
            enabled = fontScale > LyricsDisplayPrefs.FONT_SCALE_MIN + 0.001f,
        ) { onFontScaleStep(-1) }
        FontScaleButton(
            label = "A+",
            description = strings.lyricsFontLarger,
            enabled = fontScale < LyricsDisplayPrefs.FONT_SCALE_MAX - 0.001f,
        ) { onFontScaleStep(+1) }
    }
    val seek: (Long) -> Unit = { ms ->
        anchor.anchorPosMs = ms
        anchor.anchorNanos = System.nanoTime()
        anchor.anchorTrackKey = trackKey
        displayPosition.longValue = ms
        positionChanged.trySend(Unit)
        onSeekToMs(ms)
    }
    if (karaoke != null && karaokeTimeline != null) {
        val cue by remember(karaokeTimeline, displayPosition) {
            androidx.compose.runtime.derivedStateOf { karaokeTimeline.cueAt(displayPosition.longValue) }
        }
        androidx.compose.runtime.key(trackKey) {
            if (karaoke.embedded) {
                PortraitFocusLyrics(karaoke, panelLines, lineStarts, karaokeTranslations, cue, { displayPosition.longValue },
                    seek, isLoading, wordByWordEnabled, wordAnimationMode, sweepConfig, fontScale,
                    interactive = enabled && isVisible, visible = isVisible,
                    forcedScrollTrigger = forcedLocateTrigger, onUserScrolled = onUserScrolled, tools = lyricTools)
            } else {
                KaraokeScreen(karaoke, panelLines, karaokeTranslations, cue, { displayPosition.longValue },
                    seek, isPlaying, isLoading, wordByWordEnabled, wordAnimationMode, sweepConfig, fontScale,
                    interactive = enabled && isVisible)
            }
        }
    } else Box(
        modifier = Modifier
            .fillMaxSize()
            // 4.0.2：**面板不贴到底**。竖屏时歌词面板的下沿与进度条/可视条紧邻，
            // 列表最后一行（含遮罩渐隐）压过去就会挡掉它一部分。留 10dp 缓冲。
            .padding(bottom = 10.dp)
            .onSizeChanged {
                // v2.0.0 · T4：动态字号要按可用宽度估算折行数（见上）。
                panelWidthPx = it.width.toFloat()
            }
    ) {
        NcrustLyricsPanel(
            lines = panelLines,
            playbackStarts = lineStarts,
            currentPositionMillis = { displayPosition.longValue },
            isVisible = isVisible,
            forcedScrollTrigger = forcedLocateTrigger,
            enabled = enabled,
            karaokeEnabled = wordByWordEnabled,
            wordAnimationMode = wordAnimationMode,
            sweepConfig = sweepConfig,
            onLineClick = if (enabled) seek else { _ -> },
            onUserScrolled = onUserScrolled,
            // 已播/未播行颜色随主题适配：Kanesumi 面板默认写死 White@0.6 / Gray@0.4，
            // 浅色底上白色已播行会看不见。这里改用语义色。
            pastLineColor = LocalMetroColors.current.onBackground.copy(alpha = 0.6f),
            futureLineColor = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f),
            // v1.5.1 · E：字号倍率乘在基础字号上（行高同步乘，行距比例不变）。
            // 当前行的放大动画是紧跟在渲染里的 scale，与基础字号是乘数关系，所以字号变了
            // 缩放动画不需要任何额外处理。
            fontSize = (baseFontSp * effectiveScale).sp,
            lineHeight = (baseLineHeightSp * effectiveScale).sp,
            translationFontSize = (baseTranslationSp * effectiveScale).sp,
            translationLineHeight = (baseTranslationLineSp * effectiveScale).sp,
            // v1.9.3：音译字号同样乘 fontScale（A- / A+ 三档一起缩放，层级比例恒定）。
            romanizationFontSize = (LyricsDisplayPrefs.ROMANIZATION_FONT_SP * effectiveScale).sp,
            romanizationLineHeight = (LyricsDisplayPrefs.ROMANIZATION_LINE_HEIGHT_SP * effectiveScale).sp,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            // v2.3.0 · E：横屏把定位目标从「上方 36%」换成「正中 50%」。
            // 4.0.11：横屏/宽屏（centeredLayout）给当前句补一根左侧竖条 —— 短面板里靠字号差
            // 判断当前句太费眼。竖屏不加：竖屏的行距更宽，字号差已经够用。
            currentMarker = centeredLayout,
            leadFraction = if (centeredLayout) {
                LyricsPanelScroll.CENTER_FRACTION
            } else {
                LyricsPanelScroll.LEAD_FRACTION
            },
            // v2.9.0 · B 档：纯透传（判定在 PlayerCard，本组件不读设置）。
            lyricPulseEnabled = lyricPulseEnabled,
        )

        // v1.5.1 · E：歌词界面里的快捷字号调节（A- / A+）。
        // 放右上角而不是底部：底部是控制栏把手与系统手势区（任务 B 刚处理过），
        // 贴边悬浮在这一行也不会跟歌词的点击/滚动抢手势（只在两个 44×28dp 的盒子里）。
        // 只在播放器可交互时挂载 —— 折叠态/宽屏封面态不该出现可点却看不见的按钮。
        if (enabled) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // v3.3.0 · 9：分享歌词。放在 A- / A+ 左边（先于字号调节）——
                // 它是一次性的动作，而字号是会被反复调的，高频的贴近屏幕右边缘更顺手。
                lyricTools()
            }
        }

    }
    // v3.3.0 · 9：分享弹层。`MetroDialog` 是独立 Window（见 LyricShareSheet 的 KDoc），
    // 因此它不会被播放器卡片的 graphicsLayer 平移影响，也不参与卡片的命中测试。
    sharePayload?.let { payload ->
        LyricShareSheet(
            track = payload.track,
            lyrics = payload.lines,
            positionMs = payload.positionMs,
            // 「复制下来的 == 屏幕上看到的」：两个初值直接跟随当前显示开关。
            defaultShowTranslation = showTranslation,
            defaultShowRomanization = showRomanization,
            onDismiss = { sharePayload = null },
        )
    }
}

/**
 * v1.5.1 · E：A- / A+ 小按钮。视觉克制（13sp、无背景），但命中区按 44×28dp 给足；
 * 到端点时置灰 + 不可点，并带无障碍描述（TalkBack 能读到「放大歌词字号」）。
 */
@Composable
private fun FontScaleButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 28.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        MetroText(
            label,
            color = if (enabled) {
                LocalMetroColors.current.onSurfaceVariant
            } else {
                LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.35f)
            },
            style = TextStyle(fontSize = 13.sp),
        )
    }
}

/**
 * v3.3.0 · 9：歌词分享入口。
 *
 * 与 [FontScaleButton] **同一套触摸契约**（44×28dp 命中盒、13sp 那一排的位置与内边距），
 * 只把文字换成一个 16dp 的分享图标 —— 于是这一排的命中区与视觉重量都没有变化，
 * 既有的 A- / A+ 手感一个像素都不动。
 *
 * `contentDescription` 用 `ShareStrings.action`（简中「分享歌词」）。这不只是无障碍：
 * `adb shell uiautomator dump` 能按这个 content-desc 精确定位节点的 bounds，
 * 于是「点开分享弹层」在自动化验证里是可寻址的一步（见本版的验证说明）。
 */
@Composable
private fun LyricShareButton(description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 28.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        MetroIcon(
            imageVector = PlayerGlyphs.Share,
            contentDescription = null,
            tint = LocalMetroColors.current.onSurfaceVariant,
            sizeDp = 16.dp,
        )
    }
}

/**
 * v3.3.0 · 9：打开分享弹层那一刻的快照。
 *
 * 「快照」而不是「实时读」是刻意的：出图最长要几秒（封面 4s 超时 + 渲染），
 * 期间播放位置会继续走。用户点的是「这一句」，出图/复制就必须是这一句。
 * 顺带的好处是本数据类把弹层的输入固定下来，弹层不再持有 LyricsView 的任何可观察状态。
 */
private data class SharePayload(
    val track: LyricShareTrack,
    val lines: List<LyricShareLine>,
    val positionMs: Long,
)

/**
 * v3.3.0 · 9：把三份歌词轨（原文 / 译文 / 音译）按 `timeMs` 合成分享用的行表。
 *
 * 与 `LyricsView` 里给面板用的那份合并**刻意分开**：面板那份受 `showTranslation` /
 * `showRomanization` 开关影响（关掉就不合并），而分享弹层要能**独立**勾选译文/音译
 * —— 从面板那份里取数据会让「屏幕上关着译文时分享弹层勾不出译文」。
 * 代价是这里再做一次 `associateBy`，只在点击分享时发生一次，可以接受。
 */
private fun buildShareLines(
    lyrics: List<LrcLine>,
    translatedLyrics: List<LrcLine>,
    romanizedLyrics: List<LrcLine>,
): List<LyricShareLine> {
    if (lyrics.isEmpty()) return emptyList()
    if (translatedLyrics.isEmpty() && romanizedLyrics.isEmpty()) {
        return lyrics.map { LyricShareLine(timeMs = it.timeMs, text = it.text) }
    }
    val tMap = translatedLyrics.associateBy { it.timeMs }
    val rMap = romanizedLyrics.associateBy { it.timeMs }
    return lyrics.map { line ->
        LyricShareLine(
            timeMs = line.timeMs,
            text = line.text,
            translation = tMap[line.timeMs]?.text.orEmpty(),
            romanization = rMap[line.timeMs]?.text.orEmpty(),
        )
    }
}

// Only the UI thread reads Media3 parameters. The player may not be constructed yet.
private fun currentPlaybackSpeed(): Float = runCatching {
    com.takahashirinta.ncrust.player.PlaybackService.instance?.player?.playbackParameters?.speed
}.getOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 1f

private class PositionAnchor {
    fun positionAt(nowNanos: Long): Long = LyricClock.positionAt(
        anchorPosMs, anchorNanos, nowNanos, speed,
    )

    var speed = 1f
    var anchorPosMs: Long = 0L
    var anchorNanos: Long = 0L

    /**
     * v2.9.0 · P1：这个锚点属于哪一首歌。
     *
     * 外推只有在 [anchorTrackKey] 与当前曲目一致时才允许 —— 锚点是一个「上一首的位置 + 时刻」
     * 的快照，拿它去外推新歌的时间轴就是我们修的那个 bug（见 `LyricsView` 里
     * 「换歌作废锚点」那段注释）。
     */
    var anchorTrackKey: Any? = null
}
