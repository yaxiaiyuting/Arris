/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 用户需求第 10 条：**播放统计页**。
 */

package com.takahashirinta.ncrust.ui.screen

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.stats.DayPoint
import com.takahashirinta.ncrust.stats.SongStat
import com.takahashirinta.ncrust.stats.StatsLimits
import com.takahashirinta.ncrust.stats.StatsMath
import com.takahashirinta.ncrust.stats.StatsSnapshot
import com.takahashirinta.ncrust.stats.StatsStore
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.StatsStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/** 柱状图显示多少天。14 天 = 两周，在 360dp 宽的手机上是「一眼能读完、又不至于太粗」的档。 */
private const val RECENT_DAYS = 14

/** 宽屏下统计页的限宽：纯文本 + 图表，横跨整块平板会很难读（与关于页同一策略）。 */
private val STATS_MAX_WIDTH = 560.dp

/**
 * 播放统计页（v3.3.0 · 用户需求第 10 条）。
 *
 * ## 为什么是**第 5 个底部 tab**，而不是塞进「用户」页
 *
 * 三个候选都过了一遍：
 *
 * | 方案 | 结论 |
 * |---|---|
 * | 放进「用户」页做一张卡片 | **不可行**：`UserScreen.kt` 不在本次改动边界内，而且「统计」是一等公民目的地，藏进设置页的第二屏之后等于没有入口 |
 * | 独立导航目的地（`NavRoutes` + `NavGraph` 注册一条路由） | **不必**：现有 4 个 tab 全部不是导航目的地 —— 它们由 `MainScreen` 的 `when (selectedTab)` 直接渲染，`NavGraph` 只承载「压栈的详情页」。给统计页单开一条路由会造出一条**没有返回栈语义**的死路由（tab 切换不该压栈） |
 * | 第 5 个底部 tab | **采用**：与其余 4 项同构（`MetroBottomNav` 按 `weight` 均分，5 项在 360dp 上是 72dp/项，命中区仍然远超 48dp）；宽屏自动走 `MetroSidebar`，零额外适配 |
 *
 * ## 这一页没有「空维度」
 *
 * 任务书要的「各个类歌曲」维度**做不了**：`network/SongItem.kt` 与 `network/model/` 下
 * 没有任何流派 / 曲风 / 语种字段（服务端不下发）。所以页面上**不显示**这个维度，
 * 只在「统计口径」里如实说明（`methodNoGenre`）—— 一个永远为空的图例比没有更糟。
 * 「本地歌曲」也不算一个平台：`local/LocalPlaylistRepository.kt` 里「本地歌单」指的是
 * **本地保存的歌单定义**，曲目本身仍旧来自 ncm / QQ（见该文件的只读远程、只写本地契约），
 * 所以按平台统计时它们本来就落在各自音源里。
 *
 * ## 重组
 *
 * 「零重组」原则是针对播放器动画的（`graphicsLayer` 里读 `Animatable`）。
 * 本页是**静态快照**：数据只在进入页面时读一次、清空时再读一次，
 * 所以正常用 Compose state 即可，不需要为它造任何 `Animatable`。
 * 唯一必须遵守的是列表底部预留：`contentPadding = PaddingValues(bottom = BottomOverlayInsetDp)`，
 * **不许**在列表末尾手加 `Spacer`（AGENTS.md 的硬约束）。
 */
@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val s = strings.stats
    val metro = LocalMetroColors.current
    val typo = LocalMetroTypography.current

    // 时区在进入页面时取一次。系统改时区会让页面重组（Configuration 变更），
    // 那时这里会重新求值 —— 不需要额外的监听。
    val zone = remember { TimeZone.getDefault() }

    var reload by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(StatsSnapshot.EMPTY) }
    var loaded by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }

    // 读盘（首次是 80 KB 的 Gson 解析）放 IO：组合期不做文件 IO。
    LaunchedEffect(reload) {
        snapshot = withContext(Dispatchers.IO) { StatsStore.snapshot(context) }
        loaded = true
    }

    val songRows: List<SongStat> = remember(snapshot) {
        StatsMath.topN(snapshot.songs.values.toList(), StatsLimits.TOP_SONGS) { it.ms }
    }
    val sourceRows: List<Pair<String, Long>> = remember(snapshot) {
        snapshot.bySource.entries
            .filter { it.value > 0L }
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
    val chartPoints = remember(snapshot, zone) {
        StatsMath.recentDaySeries(
            byDay = snapshot.byDay,
            todayEpochMs = System.currentTimeMillis(),
            days = RECENT_DAYS,
            zone = zone,
        )
    }

    ResponsiveContent(maxWidth = STATS_MAX_WIDTH) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 底部一律交给 BottomOverlayInsetDp（窄屏 168dp / 宽屏 88dp）：
            // 托盘与底部导航是**独立图层**，会盖在列表最后一项上。手加 Spacer 是禁止的。
            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
        ) {
            // Groove 风页头：statusBar + 大字页面名（与首页同款，不引入 TopAppBar）。
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                ) {
                    MetroText(
                        text = s.title,
                        color = metro.onBackground,
                        style = typo.pageHeading,
                    )
                    Spacer(Modifier.height(6.dp))
                    MetroText(
                        text = s.subtitle,
                        color = metro.onSurfaceVariant,
                        style = typo.caption,
                    )
                    snapshot.firstDayKey?.let { first ->
                        Spacer(Modifier.height(2.dp))
                        MetroText(
                            text = s.since(first),
                            color = metro.onSurfaceVariant,
                            style = typo.label,
                        )
                    }
                }
            }

            // 三态显式分开：**读盘完成之前什么都不渲染**，否则会先闪一帧
            // 「总时长 0 秒 / 0 次 / 0 天」的假数据 —— 统计页上出现一个假 0，
            // 比出现一块空白糟得多（用户会以为数据丢了）。
            if (!loaded) {
                // 页头已经画出来了，这里刻意保持为空。
            } else if (snapshot.isEmpty) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        MetroText(
                            text = s.empty,
                            color = metro.onBackground,
                            style = typo.title,
                        )
                        Spacer(Modifier.height(8.dp))
                        MetroText(
                            text = s.emptyHint,
                            color = metro.onSurfaceVariant,
                            style = typo.body,
                        )
                    }
                }
            } else {
                // ---- 总览 ----
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(
                                label = s.totalLabel,
                                value = statsDurationText(snapshot.totalMs, s),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = s.playsLabel,
                                value = snapshot.totalPlays.toString(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(
                                label = s.activeDaysLabel,
                                value = snapshot.activeDays.toString(),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                label = s.dailyAverageLabel,
                                value = statsDurationText(
                                    StatsMath.averagePerActiveDay(
                                        snapshot.totalMs,
                                        snapshot.activeDays,
                                    ),
                                    s,
                                ),
                                hint = s.dailyAverageHint,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ---- 最近 14 天 ----
                item {
                    SectionHeader(title = s.recentDaysTitle, topPadding = 24.dp)
                    val peak = chartPoints.maxOfOrNull { it.ms } ?: 0L
                    if (peak > 0L) {
                        MetroText(
                            text = s.peakLabel(statsDurationText(peak, s)),
                            color = metro.onSurfaceVariant,
                            style = typo.label,
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp),
                        )
                    }
                    DayBarChart(
                        points = chartPoints,
                        barColor = metro.primary,
                        trackColor = metro.divider,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(96.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    // 刻度用 Row + weight 而不是 Canvas 里的 drawText：14 个槽位与柱子的
                    // 槽位算法必须一致，交给同一套 weight 就不会出现「标签与柱子错位半格」。
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        chartPoints.forEach { point ->
                            MetroText(
                                text = StatsMath.dayAxisLabel(point.dayKey),
                                color = metro.onSurfaceVariant,
                                style = typo.label.copy(textAlign = TextAlign.Center),
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ---- 各平台 ----
                if (sourceRows.isNotEmpty()) {
                    item {
                        SectionHeader(title = s.bySourceTitle, topPadding = 24.dp)
                    }
                    items(count = sourceRows.size) { index ->
                        val (key, ms) = sourceRows[index]
                        ShareRow(
                            title = sourceLabel(key, s),
                            trailing = statsDurationText(ms, s) + "  ·  " +
                                s.sharePercent(StatsMath.sharePercent(ms, snapshot.totalMs)),
                            fraction = if (snapshot.totalMs > 0L) {
                                ms.toFloat() / snapshot.totalMs.toFloat()
                            } else {
                                0f
                            },
                        )
                    }
                }

                // ---- 听得最多的歌曲 ----
                if (songRows.isNotEmpty()) {
                    item {
                        SectionHeader(title = s.topSongsTitle, topPadding = 24.dp)
                    }
                    items(count = songRows.size) { index ->
                        val song = songRows[index]
                        ShareRow(
                            title = song.name.ifEmpty { s.unknownSong },
                            subtitle = song.artist.ifEmpty { s.unknownArtist } +
                                "  ·  " + s.songPlays(song.plays),
                            trailing = statsDurationText(song.ms, s) + "  ·  " +
                                s.sharePercent(StatsMath.sharePercent(song.ms, snapshot.totalMs)),
                            fraction = if (snapshot.totalMs > 0L) {
                                song.ms.toFloat() / snapshot.totalMs.toFloat()
                            } else {
                                0f
                            },
                            rank = index + 1,
                        )
                    }
                }
            }

            // ---- 统计口径 ----
            item {
                SectionHeader(title = s.methodTitle, topPadding = 28.dp)
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    MethodLine(s.methodListen)
                    MethodLine(s.methodPlayThreshold)
                    MethodLine(s.methodFlush)
                    MethodLine(s.methodDay(zone.id))
                    // 如实说明「曲风 / 语种」维度不存在以及为什么 —— 用户看不到一个维度时，
                    // 最糟的处理是让他以为「我听的歌都没分类」。
                    MethodLine(s.methodNoGenre)
                }
            }

            // ---- 清除 ----
            item {
                Spacer(Modifier.height(20.dp))
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    MetroButton(
                        text = s.clearLabel,
                        onClick = { showClear = true },
                        containerColor = metro.surfaceVariant,
                        contentColor = metro.onSurface,
                        leadingIcon = Icons.Default.Delete,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showClear) {
        MetroDialog(onDismissRequest = { showClear = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                MetroText(
                    text = s.clearTitle,
                    color = metro.onSurface,
                    style = typo.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                MetroText(
                    text = s.clearMessage,
                    color = metro.onSurfaceVariant,
                    style = typo.body,
                )
            }
            MetroDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                MetroButton(
                    text = s.clearCancel,
                    onClick = { showClear = false },
                    containerColor = metro.surfaceVariant,
                    contentColor = metro.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MetroButton(
                    text = s.clearConfirm,
                    onClick = {
                        StatsStore.clear(context)
                        showClear = false
                        // 立刻重读：清空后要让页面马上回到空态，不能等下次进页面。
                        reload += 1
                        Toast.makeText(context, s.cleared, Toast.LENGTH_SHORT).show()
                    },
                    containerColor = metro.primary,
                    contentColor = metro.onPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 时长文案。
 *
 * 三档粒度（时/分、分/秒、秒）由 `StatsStrings` 的三个 lambda 拼，
 * 判断「该显示到哪一档」的逻辑**只在这里有一份**：
 * 分开写的话，「总时长 0 分 0 秒」这种文案会从别的分支漏出来。
 */
private fun statsDurationText(ms: Long, s: StatsStrings): String {
    val parts = StatsMath.durationParts(ms)
    return when {
        parts.hours > 0 -> s.durationHm(parts.hours, parts.minutes)
        parts.minutes > 0 -> s.durationMinSec(parts.minutes, parts.seconds)
        else -> s.durationSec(parts.seconds)
    }
}

/**
 * 音源显示名。
 *
 * 用 `MusicSource.*.key` 而不是把 `"netease"` 写死：那几个 key 是**持久化契约**
 * （进队列 JSON、离线缓存 key、MediaItem.mediaId），改名会让老数据读不出来，
 * 所以它们比这里的显示名稳定得多。
 *
 * `else` 分支不是防御性编程：音源是会增加的（v3.1.0 就加了 B 站），
 * 而**旧版本写下的统计里可能出现新音源的 key**（用户降级安装）。
 * 那种情况下显示原始 key，比显示一行空白诚实。
 */
private fun sourceLabel(key: String, s: StatsStrings): String = when (key) {
    MusicSource.NETEASE.key -> s.sourceNetease
    MusicSource.QQMUSIC.key -> s.sourceQq
    MusicSource.BILIBILI.key -> s.sourceBili
    else -> s.sourceOther(key)
}

@Composable
private fun SectionHeader(title: String, topPadding: Dp = 20.dp) {
    val metro = LocalMetroColors.current
    MetroText(
        text = title,
        color = metro.onBackground,
        style = LocalMetroTypography.current.title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 8.dp),
    )
}

/** 总览格子：直角、无阴影，信息优先（Kanesumi Design）。 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val metro = LocalMetroColors.current
    val typo = LocalMetroTypography.current
    Column(
        modifier = modifier
            .background(metro.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        MetroText(
            text = label,
            color = metro.onSurfaceVariant,
            style = typo.label,
            maxLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        MetroText(
            text = value,
            color = metro.onSurface,
            style = typo.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            MetroText(
                text = hint,
                color = metro.onSurfaceVariant,
                style = typo.label,
                maxLines = 2,
            )
        }
    }
}

/**
 * 按天的柱状图（自绘 Canvas）。
 *
 * 仓库**没有**图表库、也没有 material3（见 AGENTS.md），
 * 所以这里照 `ui/player/SlimProgressBar.kt` 的手法直接 `Canvas { }` + `drawRect`。
 *
 * 三条画法上的决定：
 * - **天数为 0 也画一条 2dp 基线**：让「这天没听」与「这天没画」在视觉上不同 ——
 *   图表的空白格必须能被读成「0」，而不是「渲染漏了」；
 * - 高度按**峰值归一化**而不是按绝对值：只看「最近 14 天」时，一个月前的高峰
 *   不该把最近两周压成一条线。峰值另用一行文案给出（`statsPeakLabel`），
 *   所以归一化不会丢掉量级信息；
 * - `points` 为空（days <= 0）时直接不画，避免除零。
 */
@Composable
private fun DayBarChart(
    points: List<DayPoint>,
    barColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val peak = points.maxOf { it.ms }
        val slot = size.width / points.size
        // 柱子占槽位的 72%，其余是间隙：Kanesumi 是直角风格，间隙是唯一的呼吸感来源。
        val gap = slot * 0.28f
        val barWidth = slot - gap
        val baseline = 2.dp.toPx()
        val usable = size.height - baseline
        points.forEachIndexed { index, point ->
            val x = index * slot + gap / 2f
            drawRect(
                color = trackColor,
                topLeft = Offset(x, size.height - baseline),
                size = Size(barWidth, baseline),
            )
            if (peak > 0L && point.ms > 0L) {
                val h = usable * (point.ms.toFloat() / peak.toFloat())
                drawRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                )
            }
        }
    }
}

/** 「占比条 + 标题 + 数值」的一行（各平台与 Top 歌曲共用）。 */
@Composable
private fun ShareRow(
    title: String,
    trailing: String,
    fraction: Float,
    subtitle: String? = null,
    rank: Int? = null,
) {
    val metro = LocalMetroColors.current
    val typo = LocalMetroTypography.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rank != null) {
                MetroText(
                    text = rank.toString(),
                    color = metro.onSurfaceVariant,
                    style = typo.label.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.width(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                MetroText(
                    text = title,
                    color = metro.onSurface,
                    style = typo.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    MetroText(
                        text = subtitle,
                        color = metro.onSurfaceVariant,
                        style = typo.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MetroText(
                text = trailing,
                color = metro.onSurfaceVariant,
                style = typo.label,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(metro.divider)
        ) {
            val clamped = fraction.coerceIn(0f, 1f)
            if (clamped > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(clamped)
                        .height(4.dp)
                        .background(metro.primary)
                )
            }
        }
    }
}

/** 「统计口径」里的一行说明。 */
@Composable
private fun MethodLine(text: String) {
    val metro = LocalMetroColors.current
    MetroText(
        text = "· $text",
        color = metro.onSurfaceVariant,
        style = LocalMetroTypography.current.caption,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}
