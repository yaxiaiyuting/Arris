/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B：本地歌单详情 / 编辑页。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.takahashirinta.ncrust.local.LocalPlaylist
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.local.LocalSyncOutcome
import com.takahashirinta.ncrust.local.LocalTrackOrigin
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.PullToRefreshIndicator
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.pullToRefresh
import com.takahashirinta.ncrust.ui.components.rememberPullToRefreshState
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

/** 二次确认对话框。清空 / 删除本地歌单都要它 —— 两者都不可撤销。 */
@Composable
private fun ConfirmDialog(
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val strings = LocalStrings.current
    MetroDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(text, color = colors.onBackground, style = typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.clickable(onClick = onDismiss).padding(10.dp)) {
                    MetroText(strings.cancel, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clickable(onClick = onConfirm).padding(10.dp)) {
                    MetroText(confirmLabel, color = colors.primary)
                }
            }
        }
    }
}

/**
 * 本地歌单详情 / 编辑页（v2.3.0 · B）。
 *
 * ## 这个页面是「只加不减」的用户可见面
 *
 * 三个动作的语义必须与数据模型一致（否则用户看到的与存下来的不是一回事）：
 *
 * | 动作 | 数据层 | 用户看到 |
 * |---|---|---|
 * | 「从歌单移除」 | `tombstoned = true`，**条目不删** | 这一首从列表里消失；**下一次同步不会回来** |
 * | 「清空歌单」 | 整个曲目表清空（**含 tombstone**） | 列表空；**已删除的歌会在同步后重新出现**（清空 = 连删除记录一起放弃） |
 * | 「删除歌单」 | 元数据 + 曲目表一起删 | 这个歌单整个没了 |
 *
 * 「清空」的二次确认文案里**明说**了「删除记录也会一并清除，同步后已删除的歌会重新出现」——
 * 这是本页最容易让用户困惑的一点，藏起来只会变成「怎么又回来了」的 bug 报告。
 *
 * ## 同步时机（任务书 4.5）
 *
 * 1. 进入页面时如果 TTL 超时 ⇒ 自动同步一次（[LocalPlaylistRepository.syncIfStale]）；
 * 2. 用户下拉 ⇒ 强制同步；
 * 3. 右上角刷新图标 ⇒ 强制同步（与 QQ 歌单页同一个入口形状）；
 * 4. **其它任何时候都不自动同步**。
 */
@Composable
fun LocalPlaylistDetailScreen(
    playlistKey: PlaylistKey,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var meta by remember { mutableStateOf<LocalPlaylist?>(null) }
    var tracks by remember { mutableStateOf(emptyList<com.takahashirinta.ncrust.local.LocalPlaylistTrack>()) }
    var isSyncing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    fun reload() {
        val (m, t) = LocalPlaylistRepository.read(context, playlistKey)
        meta = m
        tracks = t
    }

    fun runSync(force: Boolean) {
        if (isSyncing) return
        val m = meta ?: return
        isSyncing = true
        scope.launch {
            val outcome = LocalPlaylistRepository.sync(context, m, force = force)
            when (outcome) {
                is LocalSyncOutcome.Success -> {
                    Toast.makeText(
                        context,
                        strings.localPlaylistSynced(outcome.added, outcome.skipped),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                LocalSyncOutcome.NoRemoteSource ->
                    Toast.makeText(context, strings.localPlaylistSyncNoSource, Toast.LENGTH_SHORT).show()
                LocalSyncOutcome.Failed ->
                    Toast.makeText(context, strings.localPlaylistSyncFailed, Toast.LENGTH_SHORT).show()
            }
            reload()
            isSyncing = false
            pullState.refreshing = false
        }
    }

    LaunchedEffect(playlistKey) {
        reload()
        // 进入时按 TTL 同步一次；**不强制**，所以缓存新鲜时一个请求都不发。
        val m = meta
        if (m != null && m.hasRemoteSource) {
            scope.launch {
                val outcome = LocalPlaylistRepository.syncIfStale(context, m)
                if (outcome != null) reload()
            }
        }
    }

    if (showClearConfirm) {
        val name = meta?.name.orEmpty()
        ConfirmDialog(
            text = strings.localPlaylistClearConfirm(name),
            confirmLabel = strings.localPlaylistClear,
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                LocalPlaylistRepository.clear(context, playlistKey)
                reload()
                showClearConfirm = false
                Toast.makeText(context, strings.localPlaylistCleared, Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showDeleteConfirm) {
        val name = meta?.name.orEmpty()
        ConfirmDialog(
            text = strings.localPlaylistDeleteConfirm(name),
            confirmLabel = strings.localPlaylistDelete,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                LocalPlaylistRepository.deletePlaylist(context, playlistKey)
                showDeleteConfirm = false
                Toast.makeText(context, strings.localPlaylistDeleted, Toast.LENGTH_SHORT).show()
                onBack()
            },
        )
    }

    val visible = tracks.filter { it.isVisible }
    val playable = visible.mapNotNull { it.song }

    DetailScaffold(
        title = "",
        onBack = onBack,
        isLoading = false,
        hasCachedContent = true,
        // 右上角：同步 + 更多（清空 / 删除）。**必须在顶部 scrim** ——
        // 详情页底部的按钮会落进「播放器死带」（AGENTS.md 的 Compose 触摸陷阱第 2 条）。
        listState = listState,
        contentModifier = Modifier.pullToRefresh(
            state = pullState,
            // 「在顶部」的判据只有 LazyListState 能回答：第一项可见且偏移为 0。
            // 不看它就会把「往下滚列表」误判成下拉刷新（PullToRefresh 的 KDoc 里那条）。
            atTop = {
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            },
            onRefresh = { runSync(force = true) },
        ),
        onTopEndAction = { runSync(force = true) },
        topEndIcon = Icons.Default.Refresh,
        topEndContentDescription = strings.localPlaylistSync,
        header = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 8.dp),
            ) {
                MetroText(
                    meta?.name ?: strings.localPlaylistSectionTitle,
                    color = colors.onSurface,
                    style = typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    strings.playlistTrackCount(visible.size),
                    color = colors.onSurfaceVariant,
                    style = typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playable.isNotEmpty()) {
                        MetroButton(
                            text = strings.playAllButton,
                            onClick = { onReplaceAndPlay(playable) },
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    if (meta?.hasRemoteSource == true) {
                        Box(
                            modifier = Modifier
                                .clickable(enabled = !isSyncing) { runSync(force = true) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            MetroText(
                                if (isSyncing) strings.localPlaylistSyncing else strings.localPlaylistSync,
                                color = colors.primary,
                                style = typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clickable { showClearConfirm = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        MetroText(strings.localPlaylistClear, color = colors.onSurfaceVariant, style = typography.bodyMedium)
                    }
                    Box(
                        modifier = Modifier
                            .clickable { showDeleteConfirm = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        MetroText(strings.localPlaylistDelete, color = colors.onSurfaceVariant, style = typography.bodyMedium)
                    }
                }
                PullToRefreshIndicator(state = pullState)
            }
        },
    ) {
        if (visible.isEmpty()) {
            item(key = "empty") {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    MetroText(
                        strings.localPlaylistEmptyTracks,
                        color = colors.onSurfaceVariant,
                        style = typography.bodyLarge,
                    )
                }
            }
        }
        items(visible.size, key = { i -> "t-" + visible[i].trackKey.tag }) { i ->
            val row = visible[i]
            val song = row.song
            if (song != null) {
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    onClick = { onSongClick(song) },
                    onShowMenu = {
                        onShowSongMenu(
                            song,
                            listOf(
                                SongMenuAction(Icons.Default.Refresh, strings.actionInsertNext) {
                                    onSongInsertNext(song)
                                },
                                SongMenuAction(Icons.Default.MoreVert, strings.actionAppendToQueue) {
                                    onSongAppendToQueue(song)
                                },
                                // 规则 6：移除 = 打 tombstone，不是删除记录。
                                SongMenuAction(Icons.Default.Delete, strings.localPlaylistRemoveTrack) {
                                    LocalPlaylistRepository.removeTrack(context, playlistKey, row.trackKey)
                                    reload()
                                    Toast.makeText(context, strings.localPlaylistRemoved, Toast.LENGTH_SHORT).show()
                                },
                            ),
                        )
                    },
                )
            }
        }
    }

    // 下拉刷新挂在内容 LazyColumn 上（DetailScaffold 内部持有它自己的 listState，
    // 所以这里只能用「是否在顶部」这样一个与具体 state 无关的判据：
    // DetailScaffold 的列表在顶部时 firstVisibleItemIndex == 0，而我们拿不到那个 state，
    // 于是用 pullState 的位移累计 + 阈值即可 —— 阈值逻辑在 PullToRefresh 里已单测）。
}
