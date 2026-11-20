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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.local.LocalPlaylist
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.local.LocalSyncOutcome
import com.takahashirinta.ncrust.local.LocalTrackOrigin
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.PlaylistCoverPlaceholder
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
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
    // 多选：与云端歌单详情同一套交互（长按进入、点行切换、返回键先退出）。
    // 这里按 trackKey.tag 记账而不是 songId：本地歌单的行可能还没有对应曲目（song == null），
    // tag 才是这一行的稳定身份。
    var selecting by remember { mutableStateOf(false) }
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()

    fun reload() {
        val (m, t) = LocalPlaylistRepository.read(context, playlistKey)
        meta = m
        tracks = t
    }

    /** 行尾「⋯」的单条菜单（内容与旧的长按菜单逐项一致，只是换了入口）。 */
    fun openRowMenu(song: SongItem, tag: String) {
        onShowSongMenu(
            song,
            listOf(
                SongMenuAction(Icons.Default.Refresh, strings.actionInsertNext) { onSongInsertNext(song) },
                SongMenuAction(Icons.Default.MoreVert, strings.actionAppendToQueue) { onSongAppendToQueue(song) },
                // 规则 6：移除 = 打 tombstone，不是删除记录。
                SongMenuAction(Icons.Default.Delete, strings.localPlaylistRemoveTrack) {
                    val key = tracks.firstOrNull { it.trackKey.tag == tag }?.trackKey ?: return@SongMenuAction
                    val ok = LocalPlaylistRepository.removeTrack(context, playlistKey, key) != null
                    reload()
                    Toast.makeText(
                        context,
                        if (ok) strings.localPlaylistRemoved else strings.localPlaylist.syncFailed,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ),
        )
    }

    /** 批量从本地歌单移除选中行（逐条 tombstone，失败即停并保留选中集）。 */
    fun removeSelected() {
        if (selectedTags.isEmpty()) return
        var failed = false
        selectedTags.forEach { tag ->
            val key = tracks.firstOrNull { it.trackKey.tag == tag }?.trackKey ?: return@forEach
            if (LocalPlaylistRepository.removeTrack(context, playlistKey, key) == null) failed = true
        }
        reload()
        if (!failed) {
            Toast.makeText(context, strings.localPlaylistRemoved, Toast.LENGTH_SHORT).show()
            selecting = false
            selectedTags = emptySet()
        } else {
            Toast.makeText(context, strings.localPlaylist.syncFailed, Toast.LENGTH_SHORT).show()
            selectedTags = selectedTags.filterTo(mutableSetOf()) { tag -> tracks.any { it.trackKey.tag == tag } }
        }
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
                is LocalSyncOutcome.Partial -> {
                    // 数据不完整（缓存回退/截断）：显示拿到了多少，但不宣称"完整同步成功"。
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
                // 持久化失败时不能提示「已清空」：保留对话框，用户可以重试。
                val ok = LocalPlaylistRepository.clear(context, playlistKey) != null
                reload()
                showClearConfirm = !ok
                Toast.makeText(
                    context,
                    if (ok) strings.localPlaylistCleared else strings.localPlaylist.syncFailed,
                    Toast.LENGTH_SHORT,
                ).show()
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
                // 删除也可能写盘失败（Store 会回滚内存）：失败时不能退页、不能报"已删除"，
                // 否则用户回到列表会看到它还在，或者以为丢了数据。
                if (LocalPlaylistRepository.deletePlaylist(context, playlistKey)) {
                    showDeleteConfirm = false
                    Toast.makeText(context, strings.localPlaylistDeleted, Toast.LENGTH_SHORT).show()
                    onBack()
                } else {
                    Toast.makeText(context, strings.localPlaylist.syncFailed, Toast.LENGTH_SHORT).show()
                }
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
        // 本地歌单的「刷新」只能同步远端来源，而纯本地歌单根本没有来源 —— 下拉刷新在这里
        // 只会在按钮行里留一个转圈的空壳（真机视频）。同步入口保留在右上角那个按钮。
        onTopEndAction = { runSync(force = true) },
        topEndIcon = Icons.Default.Refresh,
        topEndContentDescription = strings.localPlaylistSync,
        header = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 8.dp),
            ) {
                if (selecting) {
                    LocalSelectionBar(
                        count = selectedTags.size,
                        allSelected = selectedTags.size == tracks.size && tracks.isNotEmpty(),
                        onExit = { selecting = false; selectedTags = emptySet() },
                        onToggleAll = {
                            val all = tracks.map { it.trackKey.tag }.toSet()
                            selectedTags = if (selectedTags == all) emptySet() else all
                        },
                        onRemove = { removeSelected() },
                    )
                } else {
                    // 本应用内新建的歌单在服务端没有封面（本地也没有），标题旁给一块名字占位，
                    // 否则这一页看上去像"缺了封面"的半成品。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 封面优先用**第一首歌的专辑封面**（多数播放器就是这个做法），
                        // 只有歌单为空或那首歌也没封面时才退回名字占位。
                        val coverUrl = playable.firstOrNull()?.album?.picUrl
                        if (!coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = CoverUrls.small(coverUrl),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .appCoverFrame(shape = AppShapes.small),
                            )
                        } else {
                            PlaylistCoverPlaceholder(
                                name = meta?.name ?: strings.localPlaylistSectionTitle,
                                letterSize = 26.sp,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(AppShapes.small),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
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
                        }
                    }
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
                }
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
                    modifier = Modifier.background(
                        if (row.trackKey.tag in selectedTags) LocalMetroColors.current.surfaceVariant
                        else Color.Transparent
                    ),
                    onClick = {
                        // 多选时点行 = 切换选中，绝不触发播放。
                        if (selecting) selectedTags = selectedTags.toggleTag(row.trackKey.tag)
                        else onSongClick(song)
                    },
                    // 长按 = 进多选并选中该行；单条菜单改由行尾「⋯」承担，两个入口不再抢同一个手势。
                    onShowMenu = {
                        selectedTags = if (selecting) selectedTags.toggleTag(row.trackKey.tag)
                        else setOf(row.trackKey.tag).also { selecting = true }
                    },
                    actions = when {
                        selecting -> ({ LocalSelectionMark(row.trackKey.tag in selectedTags) })
                        else -> ({ LocalMenuButton { openRowMenu(song, row.trackKey.tag) } })
                    },
                )
            }
        }
    }
}

/** 选中集切换：字符串 tag 版的 toggle（`PlaylistSelection` 只认 songId，本地行没有）。 */
private fun Set<String>.toggleTag(tag: String): Set<String> =
    if (tag in this) this - tag else this + tag

/**
 * 本地歌单的多选条。
 *
 * 为什么不做成和云端详情页同一个 `PlaylistSelectionBar`：那个是 `PlaylistDetailScreen`
 * 的私有实现，且动作集不同（云端是「移出歌单」/「下一首播放」，本地只有「从本地歌单移除」）。
 * 复制这 40 行比把两边耦合成一个带 6 个参数的通用控件更容易读。
 */
@Composable
private fun LocalSelectionBar(
    count: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val strings = LocalStrings.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onExit).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            MetroText("✕", color = colors.onBackground, style = typography.titleMedium)
        }
        MetroText(
            strings.playlists.selectionCount(count),
            modifier = Modifier.weight(1f),
            color = colors.onBackground,
            style = typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LocalSelectionAction(
            text = if (allSelected) strings.playlists.clearSelection else strings.playlists.selectAll,
            color = colors.onBackground,
            onClick = onToggleAll,
        )
        LocalSelectionAction(
            text = strings.localPlaylistRemoveTrack,
            color = if (count > 0) colors.primary else colors.onSurfaceVariant,
            enabled = count > 0,
            onClick = onRemove,
        )
    }
}

@Composable
private fun LocalSelectionAction(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // 与云端选择条同一套反馈：可被新点击打断的 Animatable，连点只留最后一次。
    val feedback = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.heightIn(min = 48.dp)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
                scope.launch {
                    feedback.snapTo(0.16f)
                    feedback.animateTo(0f, tween(220))
                }
                onClick()
            }
            .background(if (enabled) color.copy(alpha = feedback.value) else Color.Transparent)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(text, color = color, style = LocalMetroTypography.current.bodyMedium, maxLines = 1)
    }
}

/** 行尾勾选框：与云端详情页同一个视觉（方块 + 勾），多选时替换掉「⋯」。 */
@Composable
private fun LocalSelectionMark(checked: Boolean) {
    val colors = LocalMetroColors.current
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(if (checked) colors.primary else colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                MetroText("✓", color = colors.onPrimary, style = LocalMetroTypography.current.bodySmall)
            }
        }
    }
}

/** 行尾「⋯」：单条菜单入口（长按已改为进多选）。 */
@Composable
private fun LocalMenuButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroIcon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = LocalStrings.current.playlists.moreActions,
            tint = LocalMetroColors.current.onSurfaceVariant,
            sizeDp = 20.dp,
        )
    }
}
