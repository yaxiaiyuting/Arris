/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v1.3.0 · B4/B5：歌单编辑（名称/简介/隐私）与删除入口放**顶部 scrim 右上角**
 * （DetailScaffold 的 onTopEndAction），不再放页面内容里。
 *
 * 歌单详情多选：**长按行 = 直接进多选并选中该行**（不再弹单条菜单），原来的单条菜单
 * 挪到行尾的「⋯」。多选状态下点行 = 切换选中（不触发播放），顶部出现选择条
 * （已选数量 / 全选 / 退出 / 移出歌单），返回键先退多选而不是退页面。
 * 状态迁移全部在 [PlaylistSelection] 里（纯函数，JVM 单测覆盖）。
 *
 * ⚠️ 死区：折叠态播放器卡片的 fillMaxSize + translationY(collapsedOffsetY) 在屏幕
 * y≈collapsedOffsetY 以下形成「不可见、但仍参与命中测试、且先于本页拿到事件」的死带。
 * 实测：同一个 ⋮ 放 header 里（y≈1842–2016）时 y=1850 能点、y=1938 连本页 Initial pass
 * 都收不到。详情页的交互元素不得落在该带内；需要底部操作时用顶部 scrim 或列表行承载。
 * 多选的选择条因此也在**顶部**，不放底部。
 *
 * 也因此不用「⋮ → 底部菜单」两跳：MetroBottomSheet 在本页 LazyColumn 之下会被盖住，
 * 且自身就在死带里。改为 ⋮ 直接开 EditPlaylistDialog（删除在该对话框底部，仍二次确认）。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.DetailHeader
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.EditPlaylistDialog
import com.takahashirinta.ncrust.ui.components.EditPlaylistOutcome
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.warmup.ListPrefetch
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.insets.metroStatusBarsPadding
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistName: String = "",
    playlistCoverUrl: String = "",
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    val cached = remember(playlistId) { ContentCache.getPlaylistSongs(playlistId) }
    var songs by remember(playlistId) { mutableStateOf(cached ?: emptyList()) }
    var isLoading by remember(playlistId) { mutableStateOf(cached == null) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    var showPlayAllDialog by remember { mutableStateOf(false) }
    // v1.3.0 · B4：编辑/删除入口只在**本人自建**歌单上出现。
    var info by remember(playlistId) { mutableStateOf<PlaylistApi.PlaylistInfo?>(null) }
    var ownerUid by remember { mutableLongStateOf(0L) }
    var showEdit by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 顶部标题优先用路由带进来的名字；改名成功后要立刻反映，故用信息里的名字覆盖。
    var displayName by remember(playlistId) { mutableStateOf(playlistName) }
    // 多选状态：纯数据 + 纯函数迁移，见 PlaylistSelection（JVM 单测覆盖 6 条迁移）。
    var selection by remember(playlistId) { mutableStateOf(PlaylistSelection.None) }
    val selecting = selection.active
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val isOwned = info?.isOwnedBy(ownerUid) == true
    // 长按一律进多选：用户对「长按 = 选歌」的预期与歌单归属无关。是否**能移出**由
    // isOwned 单独判定（他人歌单服务端会 404），不能移出的歌单改用「添加到下一首播放」
    // 这个所有歌单都成立的动作 —— 否则选择条会是一个没有任何动作的死开关。
    val canSelect = songs.isNotEmpty()

    fun loadSongs() {
        coroutineScope.launch {
            if (songs.isEmpty()) isLoading = true
            error = null
            try {
                val fresh = PlaylistApi.getPlaylistDetail(playlistId)
                songs = fresh
                // 刷新回来的曲目可能少了（别人删了 / 缓存是旧的）：选中集里已经不存在的
                // id 留着会让「已选 N 首」与实际能移出的数量对不上。
                selection = selection.retainExisting(fresh.map { it.id })
                ContentCache.putPlaylistSongs(playlistId, fresh)
            } catch (e: Exception) {
                if (songs.isEmpty()) error = strings.loadFailed(e.message)
            } finally {
                isLoading = false
            }
        }
    }

    // 元信息单独拉一次（n=0 的轻量请求）：详情页要判断"是不是我的歌单"才能决定是否给编辑入口。
    fun loadInfo() {
        coroutineScope.launch {
            runCatching {
                val uid = PlaylistApi.getCurrentUserId()
                ownerUid = uid
                val fetched = PlaylistApi.fetchPlaylistInfo(playlistId)
                info = fetched
                fetched?.name?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }
        }
    }

    LaunchedEffect(playlistId) {
        loadSongs()
        loadInfo()
    }

    // v3.1.0 · P0-C：进入歌单页预取前 N 首封面（只封面，不预取 URL）。
    val prefetchContext = LocalContext.current
    LaunchedEffect(songs) {
        ListPrefetch.prefetchList(prefetchContext, songs)
    }
    val coverUrl = playlistCoverUrl.ifEmpty { songs.firstOrNull()?.album?.picUrl }

    /** 单条菜单的内容（长按进多选之前，这份内容原封不动地挂在长按上）。 */
    fun openSongMenu(song: SongItem) {
        onShowSongMenu(song, buildList {
            add(SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                // v2.6.0 · P0：成败由返回值决定，不再无条件弹成功。
                if (LibraryManager.saveSong(context, song).isSuccess) {
                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                }
            })
            add(SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                onSongInsertNext(song)
            })
            add(SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                onSongAppendToQueue(song)
            })
            // v1.3.0 · B5：只有本人自建歌单能移除曲目（他人歌单服务端 404）。
            if (isOwned) {
                add(SongMenuAction(Icons.Default.Delete, strings.removeFromPlaylist) {
                    coroutineScope.launch {
                        if (PlaylistEditApi.removeSongs(playlistId, listOf(song.id)) != null) {
                            songs = songs.filterNot { it.id == song.id }
                            ContentCache.invalidatePlaylist(playlistId)
                            Toast.makeText(context, strings.removedFromPlaylist, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, strings.playlistCreateFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        })
    }

    /**
     * 批量移出选中曲目。
     *
     * 一次请求带全部选中 id（`manipulate/tracks` 收 trackIds 数组，内部按 500 分批，
     * 并且走 [com.takahashirinta.ncrust.network.PlaylistWriteGate] 的 2s 串行闸门）。
     * 失败时**保留**选中集，用户可以原地重试；成功后本地剔除 + 退出多选。
     */
    fun removeSelected() {
        val ids = selection.ids.toList()
        if (ids.isEmpty()) return
        coroutineScope.launch {
            if (PlaylistEditApi.removeSongs(playlistId, ids) != null) {
                songs = songs.filterNot { it.id in ids }
                ContentCache.invalidatePlaylist(playlistId)
                selection = selection.afterRemove(ids)
                Toast.makeText(context, strings.removedFromPlaylist, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, strings.playlists.removeFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 返回键先退多选，不直接退页面（选择条上的 ✕ 是同一个出口）。
    BackHandler(enabled = selecting) { selection = selection.exit() }

    if (showPlayAllDialog) {
        PlayAllDialog(
            songCount = songs.size,
            onDismiss = { showPlayAllDialog = false },
            onReplaceAndPlay = { onReplaceAndPlay(songs) },
            onInsertNext = { onInsertNext(songs) }
        )
    }

    if (showEdit && info != null) {
        val current = info!!
        EditPlaylistDialog(
            initialName = current.name,
            initialDesc = current.description,
            initialPrivacy = current.privacy,
            onDismiss = { showEdit = false },
            onDelete = { showDeleteConfirm = true },
            onSave = { name, desc, privacy ->
                var changed = false
                var ok = true
                // 三个字段三个端点：只发改动过的，少一次写请求就少 2s 闸门 + 少一次撞 405 的机会。
                if (name.isNotBlank() && name != current.name) {
                    changed = true
                    ok = PlaylistEditApi.renamePlaylist(playlistId, name) && ok
                }
                if (desc != current.description) {
                    changed = true
                    ok = PlaylistEditApi.updatePlaylistDesc(playlistId, desc) && ok
                }
                if (privacy != current.privacy) {
                    changed = true
                    ok = PlaylistEditApi.updatePlaylistPrivacy(playlistId, privacy) && ok
                }
                when {
                    !ok && PlaylistEditApi.lastError is PlaylistWriteResult.RateLimited ->
                        EditPlaylistOutcome.RATE_LIMITED
                    !ok -> EditPlaylistOutcome.FAILED
                    !changed -> EditPlaylistOutcome.SUCCESS
                    else -> {
                        info = current.copy(name = name, description = desc, privacy = privacy)
                        displayName = name
                        // 服务端 detail 本身有陈旧缓存，本地这份不丢就会出现"改了名进详情还是旧名"。
                        ContentCache.invalidatePlaylist(playlistId)
                        EditPlaylistOutcome.SUCCESS
                    }
                }
            }
        )
    }

    if (showDeleteConfirm) {
        MetroDialog(onDismissRequest = { showDeleteConfirm = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                MetroText(
                    strings.playlistDeleteConfirm(displayName.ifEmpty { strings.categoryPlaylists }),
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                MetroText(
                    strings.playlistDeleteWarning,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                )
            }
            MetroDivider()
            Row(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showDeleteConfirm = false }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MetroText(
                        strings.cancel,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodyLarge,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            showDeleteConfirm = false
                            coroutineScope.launch {
                                if (PlaylistEditApi.deletePlaylist(playlistId)) {
                                    ContentCache.invalidatePlaylist(playlistId)
                                    Toast.makeText(context, strings.playlistDeleted, Toast.LENGTH_SHORT).show()
                                    onBack()
                                } else {
                                    Toast.makeText(context, strings.playlistDeleteFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MetroText(
                        strings.playlistDelete,
                        color = LocalMetroColors.current.primary,
                        style = LocalMetroTypography.current.bodyLarge,
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        DetailScaffold(
            title = strings.playlistDetailTitle,
            // 多选时返回箭头被选择条盖住，这里再兜一层：连点两次也不会直接退页面。
            onBack = { if (selecting) selection = selection.exit() else onBack() },
            isLoading = isLoading,
            hasCachedContent = songs.isNotEmpty(),
            error = error,
            onRetry = { loadSongs() },
            header = {
                // ⚠️ 死区：折叠态播放器卡片的 fillMaxSize + translationY(collapsedOffsetY)
                // 在屏幕 y≈collapsedOffsetY 以下形成不可见但仍参与命中测试、且先于本页拿到
                // 事件的死带。所有详情页的交互元素不得落在该带内。
                // 已踩坑：本页的「编辑歌单」⋮（v1.3.0，实测 y=1850 可点、y=1938 连页面级
                // Initial pass 都收不到）、AlbumDetail 的收藏按钮（侥幸落在带子上沿之上）。
                // 需要底部操作时用列表行入口或顶部 scrim 承载 —— 故 ⋮ 已移到 TopScrim 右上。
                DetailHeader(
                    coverUrl = coverUrl,
                    title = displayName.ifEmpty { strings.categoryPlaylists },
                    subtitle = null,
                    infoLines = listOf(strings.trackCountSongs(songs.size)),
                    onPlayAll = if (songs.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
                )
            },
            // 编辑入口放顶部 scrim 右上（与返回箭头同一带，实测不在死区内）。
            // 只保留这一个入口：header 里再放一个会让窄屏同时出现两个 ⋮。
            // 多选期间让位给选择条（同一条带，不能同时挂两个动作）。
            onTopEndAction = if (isOwned && !selecting) ({ showEdit = true }) else null,
            topEndIcon = Icons.Default.MoreVert,
            topEndContentDescription = strings.playlistEditTitle,
            content = {
                items(songs, key = { it.id }) { song ->
                    val rowSelected = selection.isSelected(song.id)
                    SongCard(
                        song = song,
                        style = SongCardStyle.COMPACT,
                        modifier = Modifier
                            // 选中行给一层中性底色（surfaceVariant），与「正在播放」的主题色
                            // 歌名区分开：一个说「你选了它」，一个说「它在播」。
                            .background(
                                if (rowSelected) LocalMetroColors.current.surfaceVariant
                                else Color.Transparent
                            )
                            .semantics { if (selecting) selected = rowSelected },
                        onClick = {
                            // 多选时点行 = 切换选中，绝不触发播放。
                            if (selecting) selection = selection.toggle(song.id)
                            else onSongClick(song)
                        },
                        onShowMenu = {
                            // 长按 = 进多选并选中该行；已经在多选里就等同于点一下（切换）。
                            // 原来的单条菜单改由行尾「⋯」承担，两个入口不再抢同一个手势。
                            selection = if (selecting) selection.toggle(song.id)
                            else selection.enter(song.id)
                        },
                        actions = when {
                            // 多选时行尾换成复选框（与长按进来的那一行同位置，视线不用跳）。
                            selecting -> ({ SelectionMark(rowSelected) })
                            // 非多选：行尾「⋯」= 原来的单条菜单，功能不减（长按也能开）。
                            else -> ({ RowMenuButton { openSongMenu(song) } })
                        },
                    )
                }
            }
        )

        // 选择条钉在**顶部**：本页底部落在播放器死带里（见文件头），放底部会点不动。
        if (selecting) {
            PlaylistSelectionBar(
                count = selection.count,
                allSelected = selection.isAllSelected(songs.map { it.id }),
                onExit = { selection = selection.exit() },
                onToggleAll = {
                    val all = songs.map { it.id }
                    selection = if (selection.isAllSelected(all)) selection.clearSelection()
                    else selection.selectAll(all)
                },
                onRemove = { removeSelected() },
                removeEnabled = isOwned,
                onInsertNext = {
                    val picked = songs.filter { it.id in selection.ids }
                    if (picked.isNotEmpty()) {
                        onInsertNext(picked)
                        selection = selection.exit()
                        Toast.makeText(context, strings.queue.queueAddToNextDone, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * 多选选择条：`✕ 退出` / `已选 N 首` / `全选（取消全选）` / `移出歌单`。
 *
 * 配色只取 [LocalMetroColors]（背景用 surface、文字用 onBackground / onSurfaceVariant、
 * 危险动作沿用主题色 primary —— 与「删除歌单」对话框同一套），不引入新的颜色常量。
 */
@Composable
private fun PlaylistSelectionBar(
    count: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    onRemove: () -> Unit,
    removeEnabled: Boolean,
    onInsertNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val strings = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            // 这是盖在页面顶部的一层：空白处也要吃掉点击，否则事件会穿透到下面的
            // 返回箭头 / 头部封面（Compose 里「只有背景和文字」的节点不参与命中测试）。
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .metroStatusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetroIconButton(onClick = onExit, touchTargetDp = 48.dp) {
                MetroIcon(
                    imageVector = Icons.Default.Close,
                    contentDescription = strings.playlists.exitSelection,
                    tint = colors.onBackground,
                    sizeDp = 20.dp,
                )
            }
            MetroText(
                strings.playlists.selectionCount(count),
                modifier = Modifier.weight(1f),
                color = colors.onBackground,
                style = typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SelectionBarAction(
                text = if (allSelected) strings.playlists.clearSelection else strings.playlists.selectAll,
                color = colors.onBackground,
                onClick = onToggleAll,
            )
            if (removeEnabled) {
                SelectionBarAction(
                    text = strings.removeFromPlaylist,
                    // 一首都没选时置灰：这个动作没有可作用的对象，点下去只会弹失败提示。
                    color = if (count > 0) colors.primary else colors.onSurfaceVariant,
                    enabled = count > 0,
                    onClick = onRemove,
                )
            } else {
                // 他人/收藏歌单不能移出（服务端 404），给一个所有歌单都成立的动作。
                SelectionBarAction(
                    text = strings.actionAddToNext,
                    color = if (count > 0) colors.primary else colors.onSurfaceVariant,
                    enabled = count > 0,
                    onClick = onInsertNext,
                )
            }
        }
        MetroDivider()
    }
}

/** 选择条上的文字动作：触控区高度 ≥48dp（无障碍最小尺寸），无背板、无指示涟漪。 */
@Composable
private fun SelectionBarAction(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(
            text,
            color = color,
            style = LocalMetroTypography.current.bodyMedium,
            maxLines = 1,
        )
    }
}

/** 行尾「⋯」：原来的单条菜单入口（多选模式外常驻，长按仍然等效）。 */
@Composable
private fun RowMenuButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            // 命中区 48dp（无障碍最小触控尺寸）；图标本身 20dp 保持与行内文字同一视觉重量。
            .size(48.dp)
            .clickable(onClick = onClick),
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

/** 多选模式下行尾的勾选框。点整行切换选中，勾本身不单独接点击。 */
@Composable
private fun SelectionMark(selected: Boolean) {
    val colors = LocalMetroColors.current
    // 与「⋯」盒子同尺寸：两种模式下行尾图标落在同一条竖线上，切换时视线不用重新定位。
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        MetroIcon(
            imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            sizeDp = 24.dp,
        )
    }
}
