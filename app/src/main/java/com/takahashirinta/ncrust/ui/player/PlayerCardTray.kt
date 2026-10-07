/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：从 `PlayerCard` 拆出的**折叠态托盘（mini bar）**。
 *
 * 托盘是折叠态**唯一常挂载**的子树（v1.3.0 · B-1），也是 v3.2.0 崩溃堆栈指向的那个
 * `Modifier.weight(1f)`（原 `PlayerCard.kt:1583`）所在之处。拆出来的目的与
 * `PlayerCardLayouts.kt` 相同：让 `PlayerCard` 不再是一个 9092 code unit 的方法体。
 *
 * 行为等价的硬约束（铁律 25）：
 *  - 节点顺序、`Modifier` 链、`weight` 实参、`contentDescription` 一字不改；
 *  - 三个控制按钮的挂载条件仍是 `miniBarEnabled`（收起态才挂载，见 AGENTS.md 触摸陷阱 §4）；
 *  - 展开态的控制区占位宽度仍由 `TrayLayout.controlsPlaceholderWidthDp()` 派生 ——
 *    展开/收起动画不会让文字重排；
 *  - 托盘的两个 `clickable` 仍分别对应「整条展开」与「点歌词展开并直接看歌词」，
 *    动画参数由调用点传入（不在这里复制一份 tween 常量）。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.i18n.Strings
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 折叠态托盘（原 `PlayerCard.kt` 1532–1784）。
 *
 * 高度、封面尺寸、三行结构、控制按钮清单**全部由 [TrayLayout] 派生**（v2.5.5 · C 的唯一落点），
 * 这里不出现任何字面量 16 / 56 / 80 / 2。
 */
@Composable
internal fun PlayerCardTray(
    song: SongItem?,
    hasSong: Boolean,
    isPlaying: Boolean,
    miniBarEnabled: Boolean,
    miniBarInteractionSource: MutableInteractionSource,
    progress: Animatable<Float, AnimationVector1D>,
    strings: Strings,
    haptic: HapticFeedback,
    lyricsFlow: StateFlow<List<LrcLine>>,
    positionFlow: StateFlow<Long>,
    onExpand: () -> Unit,
    onExpandToLyrics: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayNothing: () -> Unit,
    onArtistClick: (SongItem) -> Unit,
) {
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
                    onClick = onExpand
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
                // v3.2.0 · P0-A：封面的左边距。**必须与 overlay 的 miniCoverCenterX 同源**
                // （都由 TrayLayout 派生），否则「占位在这里、封面画在那里」会错位。
                Spacer(modifier = Modifier.width(TrayLayout.COVER_START_DP.dp))
                Spacer(modifier = Modifier.size(TrayLayout.COVER_SIZE_DP.dp))
                TrayInfoColumn(
                    song = s,
                    strings = strings,
                    lyricsFlow = lyricsFlow,
                    positionFlow = positionFlow,
                    onExpandToLyrics = onExpandToLyrics,
                    onArtistClick = onArtistClick,
                )
                TrayControls(
                    isPlaying = isPlaying,
                    miniBarEnabled = miniBarEnabled,
                    strings = strings,
                    haptic = haptic,
                    onPlayPrevious = onPlayPrevious,
                    onPlayPause = onPlayPause,
                    onPlayNext = onPlayNext,
                )
            } else {
                TrayNoSong(
                    strings = strings,
                    miniBarEnabled = miniBarEnabled,
                    haptic = haptic,
                    onPlayNothing = onPlayNothing,
                )
            }
        }
    }
}

/**
 * 托盘的三行文本（歌词 / 歌名 / 作者·音源）。
 *
 * 三行结构、行间距、左右对齐契约（作者左、音源右）全部来自 v2.5.5 · C 的探针结论；
 * 「展开态文本列宽度与收起态逐像素相同」由 [TrayLayout.controlsPlaceholderWidthDp] 保证
 * （占位在 `TrayControls` 里）。
 */
@Composable
private fun RowScope.TrayInfoColumn(
    song: SongItem,
    strings: Strings,
    lyricsFlow: StateFlow<List<LrcLine>>,
    positionFlow: StateFlow<Long>,
    onExpandToLyrics: () -> Unit,
    onArtistClick: (SongItem) -> Unit,
) {
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
            lyricsFlow = lyricsFlow,
            positionFlow = positionFlow,
            style = LocalMetroTypography.current.bodySmall,
            primaryColor = LocalMetroColors.current.primary,
            mutedColor = LocalMetroColors.current.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onExpandToLyrics() }
        )
        // ===== 第二行：歌名（v2.5.5 · C：独占一行）=====
        //
        // v2.5.4 里歌名与作者/音源挤在同一行、三者各带 `weight(1f, fill = false)`，
        // 于是「谁被省略」取决于服务端返回的字符串长度 —— 长歌名会把作者挤成一个字。
        // 三层布局把歌名**独占**一行，它不再与任何东西抢宽度。
        //
        // 歌名**不做成独立点击区**：整条托盘的点击本来就是「展开播放器」，
        // 给它再包一层同名行为的 clickable 只会多一个命中层
        // （见 AGENTS.md 触摸陷阱第 5/6 条：多出来的命中层是最难查的一类问题）。
        // 「点歌名 → 展开播放器」由外层 Box 的 clickable 承载，行为逐字相同。
        Spacer(Modifier.height(TrayLayout.LINE_GAP_DP.dp))
        MetroText(
            song.name,
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
        // 角标实际贴在作者名后面（真机截图可见：`苏谭谭qm` 之间只有 6dp）。
        //
        // 音源角标**不加 clickable** —— 判据见 probe-tray-layout.md §6.1
        // （跨源换播需要跨源身份，v2.3.0 已判决接口里没有；「看信息」与
        // onSongInfoClick 语义重叠；「切默认音源」是设置页的职责）。
        Spacer(Modifier.height(TrayLayout.LINE_GAP_DP.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val artist = song.artists?.firstOrNull()
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
                            //     QQ 曲目的数字 id 恒存在，但它不是 ncm 的 id；
                            //  ② 冷启动恢复的曲目 `id` 恒为 null，而它**恰恰**
                            //     是最该跳搜索（而不是弹一个点了也没反应的菜单）的那一种。
                            // 身份判定收在 ArtistNavigator 一处，这里只做转发。
                            onArtistClick(song)
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
                song = song,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall,
                badgeStyle = LocalMetroTypography.current.bodySmall,
                // 歌名与作者已经由上面两段画过，这里只要角标那一段。
                showArtist = false
            )
        }
    }
}

/**
 * ===== 控制区：上一首 / 播放暂停 / 下一首（v2.5.5 · C）=====
 *
 * 铁律 19：三个按钮都是核心功能，缺一个属 P0 回归。
 * **上一首是本版补的** —— 探针证明它从来没有在托盘里存在过
 * （`git log -S "SkipPrevious" -- PlayerCard.kt` 零命中），所以这不是「回归修复」而是「补功能」。
 * 顺序走 [TrayLayout.controls]（PREVIOUS / PLAY_PAUSE / NEXT），由 `TrayLayoutTest` 断言。
 *
 * 收起态才挂载：展开态三个按钮若仍在，会与歌词面板的字号按钮抢命中区
 * （AGENTS.md 触摸陷阱第 4 条：隐藏必须走「不挂载」）。
 */
@Composable
private fun RowScope.TrayControls(
    isPlaying: Boolean,
    miniBarEnabled: Boolean,
    strings: Strings,
    haptic: HapticFeedback,
    onPlayPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
) {
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
}

/**
 * 暂无播放时的托盘右侧：一个直接开始 Infinity 的播放键。
 * 卡片仍不可拉起（保持既有约束）—— `hasSong=false` 时托盘的 clickable 本来就不挂。
 */
@Composable
private fun RowScope.TrayNoSong(
    strings: Strings,
    miniBarEnabled: Boolean,
    haptic: HapticFeedback,
    onPlayNothing: () -> Unit,
) {
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
                contentDescription = strings.playButton,
                tint = LocalMetroColors.current.onBackground
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
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
    lyricsFlow: StateFlow<List<LrcLine>>,
    positionFlow: StateFlow<Long>,
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
