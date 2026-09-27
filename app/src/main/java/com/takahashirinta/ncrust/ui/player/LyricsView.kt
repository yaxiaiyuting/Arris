package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.lyric.DynamicLyricFont
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LyricSubtitleText
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepPerf
import com.takahashirinta.ncrust.lyric.LyricsSweepQuality
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.lyric.SweepTrack
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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
fun LyricsView(
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
) {
    val strings = LocalStrings.current
    if (lyrics.isEmpty()) {
        if (!isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MetroText(strings.noLyrics, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 18.sp))
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
    // 基准字号（px）：判定折行数用，与面板拿到的 sp 同源（用户 A-/A+ 是"基准"，
    // 动态倍率在它之上浮动，不替换它）。
    val baseOriginalFontPx = with(LocalDensity.current) { (32f * fontScale).sp.toPx() }
    // 每行的字号倍率。键里带上宽度与 fontScale：换歌/旋转/A-/A+ 才重算；面板宽度变化会
    // 触发一次重算（首帧一次，之后稳定）。刻意**不**写进歌词缓存 —— 它是派生显示量
    // （AGENTS.md：能不加缓存字段就不加）。
    val lineScales = remember(
        lyrics, dynamicFontEnabled, dynamicFontAvailablePx, fontScale,
    ) {
        if (!dynamicFontEnabled || dynamicFontAvailablePx <= 0f) {
            emptyList()
        } else {
            lyrics.map {
                DynamicLyricFont.clampScale(
                    raw = DynamicLyricFont.scaleFor(
                        text = it.text,
                        availWidthPx = dynamicFontAvailablePx,
                        fontSizePx = baseOriginalFontPx,
                    ),
                    baseFontSizeSp = 32f * fontScale,
                )
            }
        }
    }

    val panelLines = remember(
        lyrics, translatedLyrics, showTranslation, romanizedLyrics, showRomanization, lineScales,
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
            )
        }
    }

    // 订阅位置流:collectAsState 建 State;displayPosition 只在面板 draw/derived
    // 阶段被读,按需写一次也只重算当前行,不触发本 Composable 重组。
    val positionState = positionFlow.collectAsState()
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

    // 唤醒时刻表（升序去重）：行时间戳 + 每个词的起始时间。
    // v1.5.0 · B：逐字高亮要求唤醒精度到"词"，但绝不能改成逐帧轮询 —— 这里只是把原来
    // 的"行边界"细化成"行边界 ∪ 词边界"，仍然是**按需唤醒**：静态时零状态写入、零帧调度，
    // 只在真正跨行/跨词的那一刻动一次。词密度约每秒 3~8 个，远低于 60fps。
    // 2Hz 采样到达时锚点会被重置回真实值，所以细粒度外推不会累积误差。
    val boundaries = remember(lyrics) {
        val set = java.util.TreeSet<Long>()
        for (line in lyrics) {
            set.add(line.timeMs)
            for (w in line.words) set.add(w.startMs)
        }
        LongArray(set.size) { set.elementAt(it) }
    }

    // v1.5.2：行时间戳数组。与面板内部用的是**同一个** currentLineIndex 二分查找 ——
    // 两边必须是同一份判定，否则「面板高亮的行」和「正在逐帧推进的窗口」会错开一行。
    val timestamps = remember(lyrics) { LongArray(lyrics.size) { lyrics[it].timeMs } }

    // v1.5.2：逐字扫过的「活动窗口」——每行从**第一个词开始**到**最后一个词唱完（含行尾收束）**。
    // 只有落在这个窗口里才逐帧推进位置；前奏 / 间奏 / 行尾留白一律回到按需唤醒，
    // 静态期仍然是零状态写入、零帧调度（v1.4.1 的性质一行未丢）。
    // 一个窗口 ≈ 这一行真正在唱的 2~5 秒，所以整首歌的帧调度时间与「一直在唱」的直觉一致。
    val sweepWindows = remember(lyrics, wordByWordEnabled, wordAnimationMode) {
        buildSweepWindows(lyrics, wordByWordEnabled, wordAnimationMode)
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

    // v1.5.2：换歌（歌词表身份变化）时，外推状态立刻按当前真实位置重来一次。
    // ViewModel 侧已经在切歌路径把 currentPosition 归零，这里再兜一道：只要歌词换了，
    // 就绝不允许继续用上一首的外推值去定位新歌词的行。
    LaunchedEffect(lyrics) {
        anchor.anchorPosMs = positionState.value
        anchor.anchorNanos = System.nanoTime()
        displayPosition.longValue = positionState.value
    }

    // 2Hz 采样到达时重置外推锚点(首帧前锚点已就位,避免一帧闪到末尾)。
    // v1.4.1：同时把"位置流与显示位置脱节"的情况拉回来 —— 旧实现只在
    // isPlaying/isVisible/timestamps 变化时同步 displayPosition，于是
    // **暂停态点进度条跳到别处时歌词纹丝不动**，要按一下播放（或暂停）才跟上。
    // 阈值 1.5s：正常播放时 displayPosition 只会比采样超前 ≤1 个 tick（500ms），
    // 超过就说明发生了 seek（前进或后退），必须立刻对齐，否则前进跳转要等
    // 下一行边界、后退跳转则永远追不上（外推循环只写更大的值）。
    // `trackKey` 用 rememberUpdatedState 读取：本效果以 Unit 为 key（只订阅一次位置流），
    // 直接捕获 `trackKey` 会永远读到首次组合时的那个值，于是「这条采样属于哪一首」永远错。
    val currentTrackKey = rememberUpdatedState(trackKey)
    LaunchedEffect(Unit) {
        snapshotFlow { positionState.value }.collect { pos ->
            anchor.anchorPosMs = pos
            anchor.anchorNanos = System.nanoTime()
            anchor.anchorTrackKey = currentTrackKey.value
            val drift = kotlin.math.abs(pos - displayPosition.longValue)
            if (!isPlaying || drift > 1_500L) {
                displayPosition.longValue = pos
            }
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

    // 外推循环 = v1.4.1 的按需唤醒 + v1.5.2 的逐字逐帧窗口:
    //
    //  - **逐字活动窗口内**（当前行有 yrc 且正在唱）：用 `withFrameNanos` 逐帧把 displayPosition
    //    推到真实时刻。这是 v1.5.2 修掉的核心问题 —— v1.5.1 只在「行时间戳 ∪ 词起始时刻」写一次
    //    位置，绘制阶段永远读到词边界那一刻，于是 playedCutCharCount 里那段「词内线性插值」
    //    在真机上**从未被喂到中间值**，光标每唱完一个词才跳一格，软边只是替跳变磨圆。
    //  - **窗口外**（前奏 / 间奏 / 行尾留白 / 没有逐字数据）：原样回到按需唤醒 —— 睡到下一个
    //    行/词边界再写一次。静态时零状态写入、零帧调度，不会让渲染管线在整首歌里 60fps 空转。
    //
    // 2Hz 采样会把锚点重置回真实值，所以两种模式下的外推误差都不累积。暂停 / 隐藏 / 面板不可交互即停。
    LaunchedEffect(isPlaying, isVisible, enabled, boundaries, sweepWindows, trackKey) {
        if (!isPlaying || !isVisible || !enabled) {
            displayPosition.longValue = positionState.value
            return@LaunchedEffect
        }
        while (true) {
            // 锚点必须属于当前曲目才允许外推：迟到的旧采样可能把锚点重新指向上一首的位置，
            // 那种值一旦参与外推就会让新歌的歌词跳到错误的行（见上面「换歌作废锚点」）。
            // 不匹配时退化成"用画面上的值"，等第一条属于本曲的采样到达即可。
            val anchorValid = anchor.anchorTrackKey == trackKey
            val nowMs = if (anchorValid) {
                anchor.anchorPosMs + (System.nanoTime() - anchor.anchorNanos) / 1_000_000L
            } else {
                displayPosition.longValue
            }
            // 位置回退（seek / 切歌）：外推值是**单调抬高**出来的，不主动跟下去就会停在旧位置；
            // 把它拉回 nowMs，保证面板永远不会拿一个"未来"的位置去定位歌词行。
            if (displayPosition.longValue > nowMs + POSITION_SNAP_BACK_MS) {
                displayPosition.longValue = nowMs
            }
            // ① 正在唱的这一行 → 逐帧推进。
            if (inSweepWindow(nowMs, sweepWindows, timestamps)) {
                withFrameNanos { }
                if (anchorValid) {
                    val t = anchor.anchorPosMs +
                        (System.nanoTime() - anchor.anchorNanos) / 1_000_000L
                    if (t > displayPosition.longValue) displayPosition.longValue = t
                }
                continue
            }
            // ② 其余时间 → 按需唤醒。
            val next = nextLineBoundaryAfter(boundaries, nowMs)
            if (next == null) {
                // 已越过最后一行:无跨行可等,低频醒来等采样/seek 改变锚点。
                delay(500)
                continue
            }
            val waitMs = next - nowMs
            // 上限 1s:seek/tap 改变锚点后,最多 1s 重新对齐。
            if (waitMs > 0) delay(waitMs.coerceAtMost(1_000L))
            if (!anchorValid) continue
            val extrapolated =
                anchor.anchorPosMs + (System.nanoTime() - anchor.anchorNanos) / 1_000_000L
            // 只在真正越过该边界时写,长间隔里不会每秒写一次状态。
            if (extrapolated >= next && extrapolated > displayPosition.longValue) {
                displayPosition.longValue = extrapolated
            }
        }
    }

    // v1.7.0 · P1：上下渐隐高度跟随面板视口。
    // 竖屏全屏播放器面板高约 700dp，100dp 渐隐只吃边角；但横屏大屏模式右栏只有
    // 约 210dp 高（PCL110 实测：1272px 高 − 系统栏/挖孔 − 控制条 ~84dp），两条
    // 100dp 的渐隐会把整块歌词糊掉。取 min(100dp, 30% 视口高)，竖屏数值分毫不变，
    // 横屏自动收窄；不引入任何新的渲染路径（仍是同样两个 Box + 同一个 verticalGradient）。
    //
    // ★ v2.5.2：公式搬进 `LyricsPanelScroll.fadeHeightPx` —— 从本版起**面板自己也要用它**
    //   （它是「整条歌词最高能摆到哪」的下限，见 `blockTopPx` 的 `minTopPx`）。
    //   两处各写一份就是第二处真相：渐隐带调窄了而定位没跟着调，当前行就会被压在渐隐里发灰。
    var panelHeightPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val fadeHeight = with(LocalDensity.current) {
        LyricsPanelScroll.fadeHeightPx(
            viewportHeightPx = if (panelHeightPx.isFinite()) panelHeightPx.toInt() else 0,
            baseFadePx = LyricsPanelScroll.BASE_FADE_DP.dp.toPx(),
            minFadePx = LyricsPanelScroll.MIN_FADE_DP.dp.toPx(),
        ).toDp()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                panelHeightPx = it.height.toFloat()
                // v2.0.0 · T4：动态字号要按可用宽度估算折行数（见上）。
                panelWidthPx = it.width.toFloat()
            }
    ) {
        NcrustLyricsPanel(
            lines = panelLines,
            currentPositionMillis = { displayPosition.longValue },
            isVisible = isVisible,
            forcedScrollTrigger = forcedLocateTrigger,
            enabled = enabled,
            karaokeEnabled = wordByWordEnabled,
            wordAnimationMode = wordAnimationMode,
            sweepConfig = sweepConfig,
            onLineClick = if (enabled) { ms ->
                // 点击行:本地立即定位,不等 2Hz 采样回传,seek 手感即时。
                anchor.anchorPosMs = ms
                anchor.anchorNanos = System.nanoTime()
                displayPosition.longValue = ms
                onSeekToMs(ms)
            } else { _ -> },
            onUserScrolled = onUserScrolled,
            // 已播/未播行颜色随主题适配：Kanesumi 面板默认写死 White@0.6 / Gray@0.4，
            // 浅色底上白色已播行会看不见。这里改用语义色。
            pastLineColor = LocalMetroColors.current.onBackground.copy(alpha = 0.6f),
            futureLineColor = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f),
            // v1.5.1 · E：字号倍率乘在基础字号上（行高同步乘，行距比例不变）。
            // 当前行的放大动画是紧跟在渲染里的 scale，与基础字号是乘数关系，所以字号变了
            // 缩放动画不需要任何额外处理。
            fontSize = (32 * fontScale).sp,
            lineHeight = (42 * fontScale).sp,
            translationFontSize = (20 * fontScale).sp,
            translationLineHeight = (26 * fontScale).sp,
            // v1.9.3：音译字号同样乘 fontScale（A- / A+ 三档一起缩放，层级比例恒定）。
            romanizationFontSize = (LyricsDisplayPrefs.ROMANIZATION_FONT_SP * fontScale).sp,
            romanizationLineHeight = (LyricsDisplayPrefs.ROMANIZATION_LINE_HEIGHT_SP * fontScale).sp,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            // v2.3.0 · E：横屏把定位目标从「上方 36%」换成「正中 50%」。
            leadFraction = if (centeredLayout) {
                LyricsPanelScroll.CENTER_FRACTION
            } else {
                LyricsPanelScroll.LEAD_FRACTION
            },
            // v2.9.0 · B 档：纯透传（判定在 PlayerCard，本组件不读设置）。
            lyricPulseEnabled = lyricPulseEnabled,
        )

        // 上下边缘淡出,让歌词从黑里浮出来(沿用原实现;高度见 fadeHeight)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(listOf(LocalMetroColors.current.background, Color.Transparent))
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(fadeHeight)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, LocalMetroColors.current.background))
                )
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
        }
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

private class PositionAnchor {
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

// 返回严格大于 positionMillis 的最小行时间戳;没有则 null。timestamps 升序。
private fun nextLineBoundaryAfter(timestamps: LongArray, positionMillis: Long): Long? {
    if (timestamps.isEmpty() || positionMillis >= timestamps.last()) return null
    var lo = 0
    var hi = timestamps.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (timestamps[mid] <= positionMillis) lo = mid + 1 else hi = mid
    }
    return timestamps[lo]
}

// ---------------------------------------------------------------------------
// v1.5.2：逐字扫过的活动窗口
// ---------------------------------------------------------------------------

/** 该行没有逐字扫过窗口（无 yrc / 开关关掉 / 时间轴不可用）的哨兵值。 */
private const val NO_SWEEP_WINDOW = Long.MIN_VALUE

/**
 * 外推值允许超前真实时刻多少毫秒。
 *
 * 正常播放时外推最多领先一个 2Hz 采样周期（500ms）再加一点调度抖动；超过这个量就说明
 * 发生了回退（seek 或切歌），必须立刻把 displayPosition 拉回来。取 1000ms 是为了不去
 * 干扰正常的采样抖动。
 */
private const val POSITION_SNAP_BACK_MS = 1_000L

/**
 * 每行的扫过活动窗口，与歌词行下标一一对应。
 *
 * 存在的意义是把「逐帧」严格限制在**真正在唱**的那几秒里：窗口外一律回到 v1.4.1 的按需唤醒，
 * 于是前奏 / 间奏 / 行尾留白期间既不写状态也不请求帧。这不是可选的优化 ——
 * 不加窗口就等于整首歌 60fps 空转，S6 这种 2015 年的机器上会白掉电。
 */
private class SweepWindows(val starts: LongArray, val ends: LongArray) {
    val size: Int get() = starts.size
}

private fun buildSweepWindows(
    lyrics: List<LrcLine>,
    wordByWordEnabled: Boolean,
    wordAnimationMode: Int,
): SweepWindows {
    val starts = LongArray(lyrics.size) { NO_SWEEP_WINDOW }
    val ends = LongArray(lyrics.size) { NO_SWEEP_WINDOW }
    if (!wordByWordEnabled || wordAnimationMode == LyricsWordAnimationMode.OFF) {
        return SweepWindows(starts, ends)
    }
    lyrics.forEachIndexed { index, line ->
        val words = line.words
        if (words.isEmpty()) return@forEachIndexed
        var first = Long.MAX_VALUE
        var lastEnd = Long.MIN_VALUE
        for (w in words) {
            if (w.startMs < first) first = w.startMs
            val e = w.startMs + w.durationMs
            if (e > lastEnd) lastEnd = e
        }
        if (first == Long.MAX_VALUE || lastEnd < first) return@forEachIndexed
        // 行尾：yrc 的「行首 + 行时长」与「末段 start + dur」实测只有 92.7% 相等，取较大者兜住
        // 行尾留白；再按 SweepTrack 的行尾收束封顶，让窗口结束时刻与轨道最后一个节点**完全对齐**
        // （早一帧退回按需唤醒没关系，晚一帧就白烧帧）。
        val lineEnd = maxOf(line.endMs ?: lastEnd, lastEnd)
        starts[index] = first
        ends[index] = minOf(lineEnd, lastEnd + SweepTrack.TERMINAL_MAX_MS)
    }
    return SweepWindows(starts, ends)
}

/**
 * 当前位置是否落在「正在唱的那一行」的扫过窗口里。
 *
 * 与面板用同一个 [currentLineIndex]，所以「哪一行是高亮行」与「哪一行在逐帧推进」永远一致。
 */
private fun inSweepWindow(
    positionMs: Long,
    windows: SweepWindows,
    timestamps: LongArray,
): Boolean {
    val index = currentLineIndex(positionMs, timestamps)
    if (index < 0 || index >= windows.size) return false
    val start = windows.starts[index]
    if (start == NO_SWEEP_WINDOW) return false
    return positionMs >= start && positionMs < windows.ends[index]
}
