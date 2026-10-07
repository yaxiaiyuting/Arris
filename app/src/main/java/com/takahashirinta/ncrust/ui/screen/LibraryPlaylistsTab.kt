/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · A/B：库页「歌单」tab —— 本地歌单 / ncm / qm **三个按源分区的区块**。
 * v2.6.0 · P1/P2：三个区块**统一**的布局切换（卡片式 / 列表式）+ 每区块**手动**折叠。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.local.LocalPlaylist
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistHardFailure
import com.takahashirinta.ncrust.playlist.PlaylistLoadCoordinator
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.components.appPressScale
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.controls.MetroTextField
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import io.github.takahashirinta.kanesumi.core.theme.MetroTypography

/**
 * 库页「歌单」tab（v2.3.0 · A/B）。
 *
 * ## 信息架构（任务书 3.1 / 3.2）
 *
 * **按音源分区，三块，各自独立、不合并**：
 *
 * | 区块 | 内容 | 来源 |
 * |---|---|---|
 * | 本地歌单 | 可编辑、可混装音源、只加不减 + tombstone | 本地 prefs |
 * | ncm | 新建入口 + 云端歌单网格 | `PlaylistApi.getUserPlaylists` |
 * | qm | **歌单列表直接平铺** | `QqPlaylistRepository.loadList` |
 *
 * ## 「QQ 歌单一步可达」是怎么做到的（与 v2.2.0 的差别）
 *
 * v2.2.0 的库页里 QQ 歌单是一个**入口行**（`QqPlaylistEntryRow`，v2.3.0 已删），点进去才是列表 ——
 * 「看 QQ 歌单」要两步。本版把**列表本身**放进这一页：`QqPlaylistItem` 直接渲染，
 * 点击才进入（只读的）详情。入口行因此退役（本文件不再引用它）。
 *
 * ## 为什么整页是**一个** `LazyVerticalGrid`
 *
 * 三个区块的高度都不知道（本地歌单数量本地可变、两个远程源各自可能为空/加载中/报错），
 * 而「`LazyColumn` 里嵌 `LazyVerticalGrid`」在 Compose 里是非法的（同向嵌套滚动 +
 * 无限高度约束）。所以用**一个**网格：区块标题与整行条目都用 `GridItemSpan(maxLineSpan)`
 * 占满整行 —— 三种不同宽度的内容共存在同一个滚动容器里，滚动 / fling / `contentPadding`
 * 都只有一份。
 *
 * ## v2.6.0 · P1：布局切换（卡片式 / 列表式）—— **容器一个都不换，只换 `columns`**
 *
 * `GridCells.Adaptive(160.dp)` ↔ `GridCells.Fixed(1)`，两种模式下每个条目
 * 自己决定「渲染成格子还是渲染成整行」（见 [PlaylistItem] / [LocalPlaylistItem] /
 * [QqPlaylistItem] / [NewPlaylistGridItem] 的 `listMode`）。
 *
 * 为什么不写成 `if (list) LazyColumn else LazyVerticalGrid`（探针
 * `docs/verification/v2.6.0/probe-layout-switch.md` §8 的结论）：本页**没有**向
 * 滚容器传 `state`，两个容器各自 `rememberLazyGridState` ⇒ 换容器直接丢滚动位置；
 * 更糟的是把它包在调用点上会连 `remember { PlaylistLoadCoordinator() }` 与
 * `qqHasLoadedOnce` 一起重置，**真的会重发一次 QQ 歌单请求** ——
 * 那正好违反「切换后不刷新数据」（任务书 5.1）。
 *
 * 三个区块**统一**用同一个模式（任务书 5.1「两个源统一使用同一布局」）。
 * 探针 §(e) 查明这一页其实是**三个**源（本地那一段也是网格），
 * 所以「统一」按三段做 —— 只统一两段会留下一个半切换的页面。
 *
 * ## v2.6.0 · P2：三个区块各自**手动**折叠
 *
 * 折叠状态**从 prefs 读**（[LibrarySectionFoldSetting]），不是 `remember` ——
 * 本页被 `LibraryScreen` 的 `AnimatedContent(targetState = selectedCategory)` 包裹，
 * 切走再切回会卸载整棵子树，只放 `remember` 里的状态会静默展开
 * （用户看到的是「我收起来的又自己打开了」）。
 *
 * 折叠**只由点击触发**：这个文件里没有任何一处会在读取数据后改写折叠状态
 * （[LibrarySectionFold] 也没有提供那种 API）。「不自动折叠」因此是结构性的，
 * 而不是一句要靠人遵守的注释。
 *
 * 折叠 QQ 区块**不会**停掉它的 `LaunchedEffect` 加载（探针 §Q7）：本仓库的闸门是
 * **tab 级**的（`LibraryScreen` 只在 `selectedCategory == 1` 时组合本页），
 * 按折叠态去 gate 可见容器里的加载没有先例，而展开后要等一次网络往返
 * 会违反「先渲染、绝不空白 + 加载」的既有模式。
 */
@Composable
fun LibraryPlaylistsTab(
    localPlaylists: List<LocalPlaylist>,
    neteasePlaylists: List<PlaylistApi.PlaylistInfo>,
    isLoadingNetease: Boolean,
    neteaseError: String?,
    onRetryNetease: () -> Unit,
    onCreateNetease: () -> Unit,
    onNeteaseClick: (PlaylistApi.PlaylistInfo) -> Unit,
    onPlayNetease: (Long) -> Unit,
    onLocalClick: (LocalPlaylist) -> Unit,
    onCreateLocal: () -> Unit,
    onQqClick: (Playlist) -> Unit,
    // v2.6.0 · P1：布局模式。由 LibraryScreen 从 prefs 读出并回写，
    // 本 composable 只负责渲染 + 上报切换意图（单一职责，也让 prefs 那侧可单测）。
    layout: PlaylistLayout = PlaylistLayout.CARD,
    onLayoutChange: (PlaylistLayout) -> Unit = {},
    // v2.6.0 · P2：三个区块的折叠状态。同上，状态的归属在 LibraryScreen。
    fold: LibrarySectionFold = LibrarySectionFold.ALL_EXPANDED,
    onToggleSection: (LibrarySection) -> Unit = {},
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val context = LocalContext.current

    // ---- QQ 歌单：本页自己加载（与 v2.2.0 删除的 QQ 歌单列表页同一套闸门与仓库，不另写一条数据路径）----
    val coordinator = remember { PlaylistLoadCoordinator() }
    var qqPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var qqLoading by remember { mutableStateOf(false) }
    var qqFailure by remember { mutableStateOf<PlaylistHardFailure?>(null) }
    var qqDegradation by remember { mutableStateOf<PlaylistDegradation?>(null) }
    var qqHasLoadedOnce by remember { mutableStateOf(false) }
    var qqReloadTick by remember { mutableIntStateOf(0) }
    val ownerId = remember { QqPlaylistRepository.currentOwnerId(context) }

    LaunchedEffect(ownerId, qqReloadTick) {
        if (qqLoading) return@LaunchedEffect
        qqLoading = true
        coordinator.onOwnerChanged(ownerId)
        val load = coordinator.beginList(ownerId)
        val result = QqPlaylistRepository.loadList(forceRefresh = qqReloadTick > 0)
        // 账号在请求飞行期间变过 ⇒ 整包丢弃（v2.2.0 的世代闸门，原样复用）。
        if (coordinator.isCurrent(load)) {
            when (result) {
                is PlaylistResult.Data -> {
                    coordinator.accept(load, result.outcome.data.size)
                    qqPlaylists = result.outcome.data
                    qqDegradation = result.outcome.degradation
                    qqFailure = null
                }
                is PlaylistResult.Failed -> {
                    coordinator.fail(load, result.reason.toReason())
                    qqFailure = result.reason
                    qqDegradation = null
                }
            }
            qqHasLoadedOnce = true
        }
        qqLoading = false
    }

    val qqOrdered = remember(qqPlaylists) {
        qqPlaylists.filter { it.isFavorite } +
            qqPlaylists.filter { it.isOwned && !it.isFavorite } +
            qqPlaylists.filter { !it.isOwned }
    }
    val qqNeedLogin = qqFailure == PlaylistHardFailure.NeedLogin

    val listMode = layout == PlaylistLayout.LIST
    // 列表式下**每个条目都必须是整行**。格子条目因此把 span 显式改成整行；
    // 整行条目沿用 `maxLineSpan`（列表式下它恰好等于 1，两种模式都自洽）。
    val collapsedLocal = fold.isCollapsed(LibrarySection.LOCAL)
    val collapsedNetease = fold.isCollapsed(LibrarySection.NETEASE)
    val collapsedQq = fold.isCollapsed(LibrarySection.QQMUSIC)

    LazyVerticalGrid(
        columns = if (listMode) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
        flingBehavior = rememberMetroFlingBehavior(),
    ) {
        // ==================================================== 顶部：布局切换
        // v2.6.0 · P1。放在**所有区块之前**、且**不受任何折叠影响** ——
        // 若把它放进某个区块里，收起那个区块会把切换按钮一起收掉，
        // 用户就再也没法切回卡片式（一个把出口藏在门后的死锁）。
        item(key = "hdr-layout", span = { GridItemSpan(maxLineSpan) }) {
            PlaylistLayoutSwitch(layout = layout, onLayoutChange = onLayoutChange)
        }

        // ============================================================ 本地歌单
        item(key = "hdr-local", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                text = strings.localPlaylistSectionTitle,
                collapsed = collapsedLocal,
                count = localPlaylists.size,
                onToggle = { onToggleSection(LibrarySection.LOCAL) },
                colors = colors,
                typography = typography,
            )
        }
        if (!collapsedLocal) {
            if (localPlaylists.isEmpty()) {
                item(key = "local-empty", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHint(strings.localPlaylistEmpty, colors, typography)
                }
            }
            item(key = "local-create", span = { gridSpan(listMode) }) {
                NewPlaylistGridItem(
                    modifier = Modifier.fillMaxWidth().animateItem(
                        fadeInSpec = tween(150, easing = MetroDefault),
                        placementSpec = tween(220, easing = MetroDefault),
                        fadeOutSpec = tween(120, easing = MetroDefault),
                    ),
                    listMode = listMode,
                    onClick = onCreateLocal,
                    label = strings.localPlaylistNew,
                )
            }
            items(localPlaylists, key = { "local-" + it.key.tag }, span = { gridSpan(listMode) }) { pl ->
                LocalPlaylistItem(
                    playlist = pl,
                    listMode = listMode,
                    modifier = Modifier.fillMaxWidth().animateItem(
                        fadeInSpec = tween(150, easing = MetroDefault),
                        placementSpec = tween(220, easing = MetroDefault),
                        fadeOutSpec = tween(120, easing = MetroDefault),
                    ),
                    onClick = { onLocalClick(pl) },
                )
            }
        }

        // ============================================================== ncm
        item(key = "hdr-netease", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                text = strings.sourceNetease,
                collapsed = collapsedNetease,
                count = neteasePlaylists.size,
                onToggle = { onToggleSection(LibrarySection.NETEASE) },
                colors = colors,
                typography = typography,
            )
        }
        if (!collapsedNetease) {
            when {
                isLoadingNetease && neteasePlaylists.isEmpty() -> {
                    item(key = "ne-loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                            MetroProgressIndicator(color = colors.primary)
                        }
                    }
                }
                neteaseError != null && neteasePlaylists.isEmpty() -> {
                    item(key = "ne-error", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            MetroText(neteaseError, color = colors.onSurfaceVariant, style = typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.clickable(onClick = onRetryNetease).padding(vertical = 6.dp)) {
                                MetroText(strings.retry, color = colors.primary, style = typography.bodyMedium)
                            }
                        }
                    }
                }
                neteasePlaylists.isEmpty() -> {
                    item(key = "ne-empty", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHint(strings.noPlaylists, colors, typography)
                    }
                }
                else -> {
                    item(key = "ne-create", span = { gridSpan(listMode) }) {
                        NewPlaylistGridItem(
                            modifier = Modifier.fillMaxWidth().animateItem(
                                fadeInSpec = tween(150, easing = MetroDefault),
                                placementSpec = tween(220, easing = MetroDefault),
                                fadeOutSpec = tween(120, easing = MetroDefault),
                            ),
                            listMode = listMode,
                            onClick = onCreateNetease,
                        )
                    }
                    items(neteasePlaylists, key = { "ne-" + it.id }, span = { gridSpan(listMode) }) { pl ->
                        PlaylistItem(
                            playlist = pl,
                            listMode = listMode,
                            modifier = Modifier.fillMaxWidth().animateItem(
                                fadeInSpec = tween(150, easing = MetroDefault),
                                placementSpec = tween(220, easing = MetroDefault),
                                fadeOutSpec = tween(120, easing = MetroDefault),
                            ),
                            onClick = { onNeteaseClick(pl) },
                            onPlayAll = { onPlayNetease(pl.id) },
                        )
                    }
                }
            }
        }

        // ============================================================ qm
        // ⚠️ QQ 的标题**不是** [SectionHeader]：它右侧多一个手动刷新按钮，
        //    所以折叠开关挂在这个内联 Row 上（探针 §(b) 的提醒：做进 SectionHeader 会漏掉 QQ）。
        item(key = "hdr-qq", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleSection(LibrarySection.QQMUSIC) }
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetroText(strings.sourceQqMusic, color = colors.onSurfaceVariant, style = typography.caption)
                SectionFoldAction(collapsedQq, qqOrdered.size, colors, typography)
                Spacer(Modifier.weight(1f))
                // 手动刷新：与 QQ 歌单独立页面同一个入口形状（v2.2.0 的约定）。
                Box(
                    modifier = Modifier
                        .clickable(enabled = !qqLoading) { qqReloadTick++ }
                        .padding(6.dp),
                ) {
                    MetroIcon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = strings.playlistRefresh,
                        tint = colors.primary,
                        sizeDp = 18.dp,
                    )
                }
            }
        }
        if (!collapsedQq) {
            qqDegradation?.let { deg ->
                item(key = "qq-degraded", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHint(
                        text = when (deg) {
                            PlaylistDegradation.OFFLINE -> strings.playlistOffline
                            PlaylistDegradation.NEED_LOGIN -> strings.playlistLoginExpired
                            PlaylistDegradation.TRUNCATED -> strings.playlistTruncated
                        },
                        colors = colors,
                        typography = typography,
                    )
                }
            }
            when {
                !qqHasLoadedOnce && qqLoading -> {
                    item(key = "qq-loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                            MetroProgressIndicator(color = colors.primary)
                        }
                    }
                }
                // 空状态必须**分两种**（任务书 3.1）：「未登录」与「暂无歌单」是两件事 ——
                // 后者是已登录但这个账号确实没有歌单，前者是还没登录，出口完全不同。
                qqNeedLogin -> {
                    item(key = "qq-need-login", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHint(strings.playlistLoginRequired, colors, typography)
                    }
                }
                qqOrdered.isEmpty() -> {
                    item(key = "qq-empty", span = { GridItemSpan(maxLineSpan) }) {
                        SectionHint(
                            if (qqFailure != null) strings.playlistLoadFailed else strings.playlistEmpty,
                            colors,
                            typography,
                        )
                    }
                }
                else -> {
                    items(qqOrdered, key = { "qq-" + it.key.tag }, span = { gridSpan(listMode) }) { pl ->
                        QqPlaylistItem(
                            playlist = pl,
                            listMode = listMode,
                            modifier = Modifier.fillMaxWidth().animateItem(
                                fadeInSpec = tween(150, easing = MetroDefault),
                                placementSpec = tween(220, easing = MetroDefault),
                                fadeOutSpec = tween(120, easing = MetroDefault),
                            ),
                            onClick = { onQqClick(pl) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 格子条目在列表式下必须占满整行。
 *
 * 列表式用的是 `GridCells.Fixed(1)`，所以 `maxLineSpan == 1` ——
 * 这个函数的两种模式其实给出同一个结果（1）。**保留这个函数而不是直接写死
 * `GridItemSpan(1)`**，是为了让「列数与 span 必须一致」这条耦合留在**一处**：
 * 将来若把列表式的列数改成 2，只有这里需要跟着改，而写死的 `GridItemSpan(1)`
 * 会静默做出一个挤在左半边的「列表」。
 */
private fun LazyGridItemSpanScope.gridSpan(listMode: Boolean): GridItemSpan =
    GridItemSpan(if (listMode) maxLineSpan else 1)

private fun PlaylistHardFailure.toReason(): PlaylistLoadCoordinator.Reason = when (this) {
    PlaylistHardFailure.NeedLogin -> PlaylistLoadCoordinator.Reason.NEED_LOGIN
    PlaylistHardFailure.Network -> PlaylistLoadCoordinator.Reason.NETWORK
    PlaylistHardFailure.NotFound -> PlaylistLoadCoordinator.Reason.NOT_FOUND
    is PlaylistHardFailure.Other -> PlaylistLoadCoordinator.Reason.NETWORK
}

/**
 * 区块标题。整行 span，左对齐，与库页的 Groove 风格一致。
 *
 * v2.6.0 · P2：整行可点 ⇒ **手动**折叠。右侧的状态文字在展开时是
 * 「收起」、在收起时是「展开全部 N 个」。
 */
@Composable
private fun SectionHeader(
    text: String,
    colors: MetroColors,
    typography: MetroTypography,
    collapsed: Boolean = false,
    count: Int = 0,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetroText(text, color = colors.onSurfaceVariant, style = typography.caption)
        if (onToggle != null) {
            SectionFoldAction(collapsed, count, colors, typography)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** 区块内的说明 / 空状态。**不是**居中大空态 —— 它只是这一块没有内容，别的块还有。 */
@Composable
private fun SectionHint(text: String, colors: MetroColors, typography: MetroTypography) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 2.dp, top = 2.dp, bottom = 8.dp)) {
        MetroText(text, color = colors.onSurfaceVariant, style = typography.bodySmall)
    }
}

/**
 * 折叠状态的**可见指示**（v2.6.0 · P2）。
 *
 * 样式逐值沿用 [SectionHint]（`onSurfaceVariant` + `bodySmall`、同样的内外边距），
 * 只多一个 primary 色 —— 它是可点的动作，颜色是这里唯一的「能点」提示。
 *
 * **不用 `Icons.ExpandMore` 之类的箭头**：本仓库的图标语义表里没有「展开/收起」
 * （探针 §6 的检索结果：`expandVertically`/`shrinkVertically` 全仓 0 命中），
 * 引入一个新箭头只会多一个要维护的约定，而文字本来就最清楚。
 *
 * 收起时显示**数量**（「展开全部 N 个」）而不是只写「已收起」：
 * 用户要能判断「值不值得展开」，而不带数字的「已收起」回答不了这个问题。
 */
@Composable
private fun SectionFoldAction(
    collapsed: Boolean,
    count: Int,
    colors: MetroColors,
    typography: MetroTypography,
) {
    val strings = LocalStrings.current
    Spacer(Modifier.width(8.dp))
    MetroText(
        if (collapsed) strings.playlists.sectionExpandAll(count) else strings.playlists.sectionCollapse,
        color = colors.primary,
        style = typography.bodySmall,
    )
}

/**
 * v2.6.0 · P1：布局切换按钮（列表式 / 卡片式）。
 *
 * 两个**互斥**的小按钮，当前模式高亮。用互斥按钮而不是一个 toggle：
 * toggle 的图标只能表达「点它会变成什么」，而用户在点之前想要的是
 * 「现在是什么」—— 两个按钮同时回答了这两个问题。
 *
 * 文案**不复用**其他功能的词（AGENTS.md v2.1.3 规则 10「跨功能的文案不要复用」）：
 * `playlists.layoutCard` / `playlists.layoutList` 是这一处独有的两条。
 */
@Composable
private fun PlaylistLayoutSwitch(
    layout: PlaylistLayout,
    onLayoutChange: (PlaylistLayout) -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        // 顺序 = 「列表式 → 卡片式」，与 `PlaylistLayout` 的声明顺序无关，
        // 是 UI 上的从左到右。两个按钮的图标形状本身说明了它们是什么。
        LayoutSwitchChip(
            icon = Icons.AutoMirrored.Filled.ViewList,
            label = strings.playlists.layoutList,
            selected = layout == PlaylistLayout.LIST,
            colors = colors,
            typography = typography,
            onClick = { onLayoutChange(PlaylistLayout.LIST) },
        )
        Spacer(Modifier.width(6.dp))
        LayoutSwitchChip(
            icon = Icons.Default.GridView,
            label = strings.playlists.layoutCard,
            selected = layout == PlaylistLayout.CARD,
            colors = colors,
            typography = typography,
            onClick = { onLayoutChange(PlaylistLayout.CARD) },
        )
    }
}

@Composable
private fun LayoutSwitchChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    colors: MetroColors,
    typography: MetroTypography,
    onClick: () -> Unit,
) {
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Row(
        modifier = Modifier
            // 触摸区只增不减（AGENTS.md 的 Compose 触摸陷阱第 7 条）：视觉上的图标只有
            // 18dp，命中区靠 vertical = 10dp 撑到 ≈38dp 高、并自带横向 8dp 余量。
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetroIcon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            sizeDp = 18.dp,
        )
        Spacer(Modifier.width(4.dp))
        MetroText(label, color = tint, style = typography.bodySmall)
    }
}

// ============================================================================================
// 三种条目 × 两种布局（v2.6.0 · P1）
//
// 规格出处（探针 §(c)，全部从既有代码逐值量出来，没有新造的尺寸）：
//   列表式：封面 48dp（原 QQ 整行）、行高 64dp = 48 + 8×2、封面↔文字 12dp、
//           圆角 AppShapes.small（8dp）+ 1dp 描边、标题 bodyLarge 单行省略、
//           副标题 bodySmall。
//   卡片式：GridCells.Adaptive(160.dp)（窄屏 2 列 × 179dp）、封面 aspectRatio(1f)、
//           圆角 AppShapes.large（16dp）+ 1dp 描边、▶ 36dp 右下 padding 6dp、
//           标题 bodyMedium 单行、副标题 bodySmall、格间距 2dp。
// ============================================================================================

/**
 * QQ 歌单条目（v2.6.0 · P1 起两种布局）。
 *
 * 列表式逐值沿用 v2.3.0 的整行排布（它本来就是这个形状）；
 * 卡片式与另两段一致（大封面 + 歌单名 + 「N 首 · 我喜欢」）。
 */
@Composable
private fun QqPlaylistItem(
    playlist: Playlist,
    listMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    val subtitle = buildString {
        append(strings.playlistTrackCount(playlist.trackCount))
        if (playlist.isFavorite) {
            append(" · ")
            append(strings.playlistFavorite)
        }
    }
    if (listMode) {
        PlaylistListRow(
            coverUrl = playlist.coverUrl,
            title = playlist.name,
            subtitle = subtitle,
            modifier = modifier,
            onClick = onClick,
        )
    } else {
        PlaylistCardCell(
            coverUrl = playlist.coverUrl,
            title = playlist.name,
            subtitle = strings.trackCount(playlist.trackCount),
            modifier = modifier,
            onClick = onClick,
        )
    }
}

/**
 * 本地歌单条目（v2.6.0 · P1）。
 *
 * 本地歌单**没有封面图**，所以两种布局都用占位色块 + 居中图标：
 * 卡片式用与同格封面同形的方形（`AppShapes.large`），列表式用 48dp 小方块
 * （`AppShapes.small`）。曲目数**不在这里读 prefs** ——
 * 列表渲染时对每个格子做一次 `SharedPreferences` 读会让滚动带上 IO
 * （v2.3.0 的既有约定，本版一行未改）。副标题因此是「本地 / 已绑定远程源」徽标。
 */
@Composable
private fun LocalPlaylistItem(
    playlist: LocalPlaylist,
    listMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val subtitle = if (playlist.hasRemoteSource) strings.localPlaylistSync else strings.localPlaylistLocalBadge
    if (listMode) {
        PlaylistListRow(
            coverUrl = null,
            title = playlist.name,
            subtitle = subtitle,
            modifier = modifier,
            onClick = onClick,
        )
    } else {
        Column(modifier = modifier.appPressScale().clickable { onClick() }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    // v2.5.0 · B：本地歌单格子没有封面图，占位色块与同格封面同形
                    // （栅格 minSize=160dp ⇒ 单元格 ≥160dp ⇒ AppShapes.large）。
                    .clip(AppShapes.large)
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                MetroIcon(
                    imageVector = Icons.Default.PlaylistPlay,
                    contentDescription = null,
                    tint = colors.primary.copy(alpha = 0.45f),
                    sizeDp = 32.dp,
                )
            }
            Spacer(Modifier.height(6.dp))
            MetroText(
                playlist.name,
                color = colors.onBackground,
                style = LocalMetroTypography.current.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            MetroText(
                subtitle,
                color = colors.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * 列表式共用行：48dp 封面 + 标题 + 副标题。
 *
 * 三个源共用这一个 composable（而不是各写一份）：它们在这一档里**必须逐像素一致**
 * ——「统一布局」的全部意义就在这里，三份实现必然漂移成「QQ 那行高 64、
 * ncm 那行高 60」。
 */
@Composable
private fun PlaylistListRow(
    coverUrl: String?,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                // v2.5.0 · B：占位底板先裁圆，方形底才不会从圆角封面四角露出来。
                .clip(AppShapes.small)
                .background(colors.surfaceVariant),
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // 圆角 + 1dp 描边；形状按渲染边长：48dp < 160dp ⇒ AppShapes.small。
                    modifier = Modifier.fillMaxSize().appCoverFrame(shape = AppShapes.small),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                title,
                color = colors.onSurface,
                style = typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetroText(subtitle, color = colors.onSurfaceVariant, style = typography.bodySmall)
        }
    }
}

/** 卡片式共用格：大封面 + 标题 + 副标题。同上，三个源共用一份。 */
@Composable
private fun PlaylistCardCell(
    coverUrl: String?,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    Column(modifier = modifier.appPressScale().clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // 栅格 minSize=160dp ⇒ 单元格 ≥160dp ⇒ AppShapes.large。
                .clip(AppShapes.large)
                .background(colors.surfaceVariant),
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().appCoverFrame(shape = AppShapes.large),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            title,
            color = colors.onBackground,
            style = typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        MetroText(
            subtitle,
            color = colors.onSurfaceVariant,
            style = typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** 「新建本地歌单」对话框。复用 Kanesumi 的 `MetroDialog` + `MetroTextField`。 */
@Composable
fun LocalPlaylistCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    var name by remember { mutableStateOf("") }
    MetroDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(
                strings.localPlaylistNew,
                color = colors.onBackground,
                style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(10.dp))
            MetroTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = strings.localPlaylistNameHint,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.clickable(onClick = onDismiss).padding(10.dp)) {
                    MetroText(strings.cancel, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clickable(enabled = name.isNotBlank()) { onCreate(name.trim()) }
                        .padding(10.dp),
                ) {
                    MetroText(
                        strings.localPlaylistCreate,
                        color = if (name.isNotBlank()) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
