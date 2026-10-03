package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.search.TrackAvailability
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouPresets
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import com.takahashirinta.ncrust.ui.theme.AppShapes

enum class SongCardStyle {
    LIST,
    COMPACT,
    GRID
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    song: SongItem,
    style: SongCardStyle = SongCardStyle.LIST,
    modifier: Modifier = Modifier,
    coverSize: Dp = Dp.Unspecified,
    onClick: () -> Unit = {},
    onShowMenu: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    isCurrentPlaying: Boolean = false,
    showCover: Boolean = true,
    /**
     * v2.4.0 · E：聚合页**探测出来**的版权可用性。
     *
     * 默认 null = 完全按今天的行为走（从 [SongItem] 推导）。加在参数表**末尾**且带默认值，
     * 所以既有的全部调用点一个字节都不用改。
     *
     * 为什么不让调用方直接把可用性写进 `SongItem`：那是落盘结构（队列 / 本地歌单 /
     * 离线索引都在存），写进去会出现「上周探测能播、今天显示可播放、点下去 404」。
     * 详见 [SongTags.of] 的 3 参重载。
     */
    availabilityOverride: TrackAvailability? = null
) {
    val strings = LocalStrings.current
    val artistStr = song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist
    val albumName = song.album?.name ?: ""
    val durationStr = song.duration?.let { formatDuration(it) } ?: ""
    // v2.3.0 · C/D：音源归属 + 版权可用性 + 原唱/翻唱。
    //
    // v2.1.0 · E 时这里只标 QQ（`else -> ""`）。改成两源都标的原因是聚合搜索会把两源的
    // 条目混进同一个列表，而两源的 id 完全独立 —— 实测同关键词下会出现**完全同名**的行
    // （《晴天》ncm 186016 / QQ 00083kc41YcFuR），不标音源用户判断不出哪行是哪个源。
    // 装配规则（含「什么时候什么都不显示」）全部在 SongTags 里，JVM 可单测。
    val tags = remember(song, strings, availabilityOverride) {
        SongTags.of(song, strings, availabilityOverride)
    }
    val sourceBadge = tags.firstOrNull { it.kind == SongTagKind.SOURCE }?.text.orEmpty()
    val extraBadges = tags.filter { it.kind != SongTagKind.SOURCE }
    val coverOriginLine = remember(song, strings) { SongTags.coverOriginLine(song, strings) }

    when (style) {
        SongCardStyle.LIST, SongCardStyle.COMPACT -> {
            val actualCoverSize = when {
                coverSize != Dp.Unspecified -> coverSize
                else -> 72.dp
            }

            // 无边框版式：封面贴屏幕左沿（无左 padding），文字与右侧 actions 保留 16dp 右 padding。
            // 长按触发菜单；右侧 MoreVert 按钮取消，避免与"无边框"视觉冲突。
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    // v2.5.0 · A：按压回弹。放在 clickable **之前**（更靠外层），
                    // 缩放作用于包含点击区的整行；本修饰符只观察指针、不消费事件，
                    // 所以下面 combinedClickable 的点击/长按行为一字不变。
                    .appPressScale()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { onShowMenu?.invoke() }
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCover) {
                    // v2.5.0 · B：封面圆角 + 1dp 描边。形状按**渲染边长**选，规则是
                    // 「≥160dp → AppShapes.large，<160dp → AppShapes.small」：本行封面是
                    // 72dp（搜索结果传 56dp），都 < 160 ⇒ small。
                    AsyncImage(
                        model = CoverUrls.small(song.album?.picUrl),
                        contentDescription = strings.coverDesc,
                        // 纯色占位:避免低端机解码完成前出现"空方块"闪变(Metro 不做 crossfade,直接落图)
                        placeholder = ColorPainter(LocalMetroColors.current.surfaceVariant),
                        modifier = Modifier
                            .size(actualCoverSize)
                            .appCoverFrame(shape = AppShapes.small),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(14.dp))
                } else {
                    Spacer(Modifier.width(16.dp))
                }

                Column(Modifier.weight(1f)) {
                    // v2.3.0 · C/D：歌名 + 角标。**角标必须与歌名同一行**，不能接在副标题末尾 ——
                    // release 真机验证时发现：副标题是 maxLines=1 + Ellipsis，而它已经装着
                    // 「艺人 · 专辑  时长  · 音源」，长专辑名（如
                    // `The Life of a Showgirl: The Encore`）会把后面的「可播放 / 原唱」
                    // 整段省略掉。而那两个角标恰恰是**用户唯一需要一眼看到的信息**
                    // （任务书 5.4「用户能一眼看出哪首歌能播、用哪个源」）。
                    // 任务书 6.3 也要求「标签位置：歌曲名前面或后面，统一」。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroText(
                            song.name,
                            color = if (isCurrentPlaying) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            style = LocalMetroTypography.current.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // weight 而不是 wrapContent：歌名过长时**让歌名自己省略**，
                            // 角标始终留在屏上。
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (extraBadges.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            SongTagChips(extraBadges)
                        }
                    }
                    MetroText(
                        buildString {
                            append(artistStr)
                            if (albumName.isNotEmpty()) append(" · $albumName")
                            if (durationStr.isNotEmpty()) append("  $durationStr")
                            if (sourceBadge.isNotEmpty()) append("  · $sourceBadge")
                        },
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // v2.3.0 · D：只有确证是翻唱**且**服务端给了原曲信息时才多出这一行。
                    // 实测翻唱里 51% 有 originSongSimpleData，其余只在上面的角标里显示「翻唱」。
                    if (coverOriginLine != null) {
                        MetroText(
                            coverOriginLine,
                            color = LocalMetroColors.current.onSurfaceVariant,
                            style = LocalMetroTypography.current.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (actions != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        actions.invoke(this)
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
        }

        SongCardStyle.GRID -> {
            // 按压缩放动画：Animatable + graphicsLayer，动画帧仅在 draw 阶段消费。
            // GRID 用**自己的** 1.03x 曲线（不是 `Modifier.appPressScale` 的 1.05x）——
            // 它同时承担点击/长按，换掉会改动这里的手感；LIST/COMPACT 行的回弹则由
            // appPressScale 统一提供，两条路径不叠加。
            val scaleAnim = remember { Animatable(1f) }
            val scaleScope = rememberCoroutineScope()
            Column(
                modifier = modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                scaleScope.launch { scaleAnim.animateTo(1.03f, SokuouPresets.QuickInteraction) }
                                tryAwaitRelease()
                                scaleScope.launch { scaleAnim.animateTo(1f, SokuouPresets.QuickInteraction) }
                            },
                            onTap = { onClick() },
                            onLongPress = { onShowMenu?.invoke() }
                        )
                    }
                    .graphicsLayer {
                        val s = scaleAnim.value
                        scaleX = s
                        scaleY = s
                    }
            ) {
                AsyncImage(
                    model = CoverUrls.small(song.album?.picUrl),
                    contentDescription = strings.coverDesc,
                    placeholder = ColorPainter(LocalMetroColors.current.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        // 同一尺寸规则：GRID 格子宽随列数变化，按最常见的自适应栅格
                        // （单元格 ≈148–160dp，< 160）取 small；若将来放进 2 列大格
                        // （≥160dp），按同一规则应改 large。本形态当前无调用点。
                        .appCoverFrame(shape = AppShapes.small),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
                // 网格格子里空间更紧，所以角标只显示**可用性**（能不能播），
                // 版本标签留给列表形态 —— 一格里塞三个角标会变成噪音。
                val gridBadges = extraBadges.filter { it.kind == SongTagKind.AVAILABILITY }
                MetroText(
                    if (gridBadges.isEmpty()) {
                        song.name
                    } else {
                        song.name + "  " + gridBadges.joinToString(" ") { it.text }
                    },
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MetroText(
                    if (sourceBadge.isEmpty()) "$artistStr · $albumName" else "$artistStr · $sourceBadge",
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * v2.3.0 · C/D：一行小角标（可播放 / 需会员 / 无版权 / 原唱 / 翻唱）。
 *
 * 直角、无圆角、小字号、低饱和底色 —— Kanesumi Design 的「信息优先、不抢主视觉」。
 * 颜色按 [SongTagKind] 分：可用性用主题色系（能不能播是要紧信息），版本用中性色
 * （原唱/翻唱是补充信息）。
 *
 * 尺寸刻意压到 [chipFontSp]：它要能与歌名同行而不把歌名挤没。
 */
private val chipFontSp = 10

@Composable
private fun SongTagChips(tags: List<SongTag>, modifier: Modifier = Modifier) {
    val colors = LocalMetroColors.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        tags.forEachIndexed { index, tag ->
            if (index > 0) Spacer(Modifier.width(4.dp))
            val tint = when (tag.kind) {
                SongTagKind.AVAILABILITY -> colors.primary
                SongTagKind.VERSION -> colors.onSurfaceVariant
                SongTagKind.SOURCE -> colors.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                MetroText(
                    tag.text,
                    color = tint,
                    style = TextStyle(fontSize = chipFontSp.sp, lineHeight = (chipFontSp + 2).sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayAllButton(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    // 圆形外框(用户决策): 直径 = 原边长。clip 先于 background, 裁切不产生
    // 额外合成层, 与方形按钮同价。
    //
    // v2.5.0 · A：圆形的 shape 常量换成 `AppShapes.full`。二者对正方形完全等价
    // （`full` = 百分比 50% 的圆角，Material 3 官方把这一档叫 `full`，
    // 见 probe-splayer-ref.md §4）。换的理由不是外观，是**单一落点**：
    // 圆角规范只允许有一个文件出现 shape 构造器，否则第一次改规范就要全仓库找
    // （`AppShapesSingleSourceTest` 会拦下这一处）。
    Box(
        modifier = modifier
            .size(size)
            .clip(AppShapes.full)
            .background(LocalMetroColors.current.primary)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MetroIcon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = LocalStrings.current.playAllButton,
            tint = LocalMetroColors.current.onPrimary,
            sizeDp = size * 0.55f
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
