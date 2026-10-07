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
 * ⚠️ 死区：折叠态播放器卡片的 fillMaxSize + translationY(collapsedOffsetY) 在屏幕
 * y≈collapsedOffsetY 以下形成「不可见、但仍参与命中测试、且先于本页拿到事件」的死带。
 * 实测：同一个 ⋮ 放 header 里（y≈1842–2016）时 y=1850 能点、y=1938 连本页 Initial pass
 * 都收不到。详情页的交互元素不得落在该带内；需要底部操作时用顶部 scrim 或列表行承载。
 *
 * 也因此不用「⋮ → 底部菜单」两跳：MetroBottomSheet 在本页 LazyColumn 之下会被盖住，
 * 且自身就在死带里。改为 ⋮ 直接开 EditPlaylistDialog（删除在该对话框底部，仍二次确认）。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val isOwned = info?.isOwnedBy(ownerUid) == true

    fun loadSongs() {
        coroutineScope.launch {
            if (songs.isEmpty()) isLoading = true
            error = null
            try {
                val fresh = PlaylistApi.getPlaylistDetail(playlistId)
                songs = fresh
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
        ListPrefetch.prefetchList(prefetchContext, "playlist:$playlistId", songs)
    }
    val coverUrl = playlistCoverUrl.ifEmpty { songs.firstOrNull()?.album?.picUrl }

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

    DetailScaffold(
        title = strings.playlistDetailTitle,
        onBack = onBack,
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
        onTopEndAction = if (isOwned) ({ showEdit = true }) else null,
        topEndIcon = Icons.Default.MoreVert,
        topEndContentDescription = strings.playlistEditTitle,
        content = {
            items(songs, key = { it.id }) { song ->
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    onClick = { onSongClick(song) },
                    onShowMenu = {
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
                )
            }
        }
    )
}

/** 歌单操作菜单的行（直角、无圆角，与 SongMenuSheet 同款排版）。 */
@Composable
private fun PlaylistActionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetroText(
            label,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyLarge,
        )
    }
}
