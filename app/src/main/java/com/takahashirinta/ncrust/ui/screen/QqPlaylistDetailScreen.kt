/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：QQ 歌单详情（只读、分页、离线可看、账号切换竞态防护）。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistHardFailure
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.playlist.PlaylistLoadCoordinator
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.PlaylistTrack
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * QQ 歌单详情（v2.2.0）。
 *
 * ## 竞态防护（任务书第 4.2 条）
 *
 * 每次加载都经 [PlaylistLoadCoordinator] 领号，响应回来先判
 * `isCurrent(load)`（generation + key + ownerId 三重）再落地。
 * 因此「切歌单 / 切账号时旧响应后到」在**结构上**不可能覆盖新数据。
 *
 * ## 分页（任务书第 4.1 条）
 *
 * 详情由 [QqPlaylistRepository.loadDetail] 顺序拉完全部分页；每拉到一页就通过 `onPage`
 * 回调**渐进显示**（大歌单不必等全部拉完才出内容）。被 `MAX_PAGES` 截断时显示明确提示，
 * 而不是假装「就这么多」。
 *
 * ## 离线
 *
 * 缓存里有数据时**一个请求都不发**（TTL 内）；网络失败或登录过期时回落到缓存并挂横幅。
 */
@Composable
fun QqPlaylistDetailScreen(
    playlistId: String,
    ownerId: String,
    dirId: Long,
    playlistName: String,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    onRelogin: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val key = remember(playlistId, ownerId) {
        PlaylistKey(MusicSource.QQMUSIC, playlistId, ownerId)
    }
    val coordinator = remember(key) { PlaylistLoadCoordinator() }

    var songs by remember(key) { mutableStateOf<List<SongItem>>(emptyList()) }
    var tracks by remember(key) { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var isLoading by remember(key) { mutableStateOf(false) }
    var degradation by remember(key) { mutableStateOf<PlaylistDegradation?>(null) }
    var failure by remember(key) { mutableStateOf<PlaylistHardFailure?>(null) }
    var hasLoadedOnce by remember(key) { mutableStateOf(false) }
    // 歌单名以**接口返回的 dirinfo.title 为准**，nav 参数只做首帧占位。
    // 路由里的名字要过 URL 编解码，中文/空格/斜杠都可能失真；而详情接口每次都会带回真名。
    var resolvedName by remember(key) { mutableStateOf(playlistName) }

    fun load(force: Boolean) {
        if (isLoading) return
        isLoading = true
        // 领号：本次请求绑定到 (世代, key=歌单+账号)。
        val load = coordinator.beginDetail(key)
        scope.launch {
            val result = QqPlaylistRepository.loadDetail(
                key = key,
                dirId = dirId,
                forceRefresh = force,
                onPage = { page ->
                    // 渐进显示也必须过闸门：分页回调可能在切账号之后才被调用。
                    if (coordinator.isCurrent(load)) {
                        songs = songs + page.songs
                        tracks = tracks + page.tracks
                        // 服务端给了真名就用真名（首帧之后即可纠正 nav 参数的失真）。
                        page.name?.takeIf { it.isNotBlank() }?.let { resolvedName = it }
                    }
                },
            )
            // ★ 闸门：旧响应到此为止，绝不写进 UI。
            if (!coordinator.isCurrent(load)) return@launch
            when (result) {
                is PlaylistResult.Data -> {
                    coordinator.accept(load, result.outcome.data.tracks.size)
                    songs = result.outcome.data.songs
                    tracks = result.outcome.data.tracks
                    degradation = result.outcome.degradation
                    failure = null
                }
                is PlaylistResult.Failed -> {
                    coordinator.fail(load, PlaylistLoadCoordinator.Reason.NETWORK)
                    failure = result.reason
                    degradation = null
                }
            }
            hasLoadedOnce = true
            isLoading = false
        }
    }

    LaunchedEffect(key) {
        songs = emptyList()
        tracks = emptyList()
        hasLoadedOnce = false
        load(force = false)
    }

    DetailScaffold(
        // 同 QqPlaylistScreen：title 已弃用且会被返回箭头压住，标题由 header 渲染。
        title = "",
        onBack = onBack,
        isLoading = isLoading && !hasLoadedOnce,
        hasCachedContent = hasLoadedOnce && songs.isNotEmpty(),
        error = failure?.let {
            when (it) {
                PlaylistHardFailure.NeedLogin -> strings.playlistLoginExpired
                PlaylistHardFailure.NotFound -> strings.playlistNotFound
                PlaylistHardFailure.Network -> strings.playlistLoadFailed
                is PlaylistHardFailure.Other -> strings.playlistLoadFailed
            }
        },
        onRetry = { load(force = true) },
        onTopEndAction = if (songs.isNotEmpty()) ({ onReplaceAndPlay(songs) }) else null,
        topEndIcon = Icons.Default.PlayArrow,
        topEndContentDescription = strings.playAllButton,
        header = {
            // 同 QqPlaylistScreen：top = 56dp 让开顶部 scrim（见该文件的说明）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 8.dp),
            ) {
                MetroText(
                    resolvedName.ifBlank { strings.qqPlaylistsTitle },
                    color = colors.onSurface,
                    style = typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                MetroText(strings.sourceQqMusic, color = colors.primary, style = typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                MetroText(
                    strings.playlistTrackCount(tracks.size.coerceAtLeast(songs.size)),
                    color = colors.onSurfaceVariant,
                    style = typography.bodySmall,
                )
                // v2.3.0 · B：把这张**只读**的 QQ 歌单转存成可编辑的本地歌单。
                // 动作落在 header（y≈56dp 起，远离「播放器死带」），且只在真的拉到曲目后出现。
                // 转存后**立刻同步一次**，用户进本地歌单时看到的不是一张空表。
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    val local = LocalPlaylistRepository.adoptRemote(
                                        context = context,
                                        key = key,
                                        name = resolvedName.ifBlank { strings.qqPlaylistsTitle },
                                        dirId = dirId,
                                    )
                                    LocalPlaylistRepository.sync(context, local, force = true)
                                    Toast.makeText(
                                        context,
                                        strings.localPlaylistAdopted(local.name),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                    ) {
                        MetroText(strings.localPlaylistAdopt, color = colors.primary, style = typography.bodyMedium)
                    }
                }
                degradation?.let { deg ->
                    Spacer(Modifier.height(6.dp))
                    val text = when (deg) {
                        PlaylistDegradation.OFFLINE -> strings.playlistOffline
                        PlaylistDegradation.NEED_LOGIN -> strings.playlistLoginExpired
                        PlaylistDegradation.TRUNCATED -> strings.playlistTruncated
                    }
                    MetroText(text, color = colors.onSurfaceVariant, style = typography.bodySmall)
                    if (deg == PlaylistDegradation.NEED_LOGIN) {
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clickable(onClick = onRelogin)
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                        ) {
                            MetroText(strings.playlistRelogin, color = colors.primary, style = typography.bodyMedium)
                        }
                    }
                }
            }
        },
    ) {
        if (songs.isEmpty() && !isLoading) {
            item(key = "empty-tracks") {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    MetroText(
                        strings.playlistEmptyTracks,
                        color = colors.onSurfaceVariant,
                        style = typography.bodyLarge,
                    )
                }
            }
        }
        // 复用既有 SongCard（与 ncm 歌单详情同一套行组件）—— 不另写一套列表行。
        itemsIndexed(songs, key = { i, s -> "t-$i-${s.id}" }) { _, song ->
            SongCard(
                song = song,
                onClick = { onSongClick(song) },
                onShowMenu = { onShowSongMenu(song, emptyList()) },
                isCurrentPlaying = false,
            )
        }
    }
}
