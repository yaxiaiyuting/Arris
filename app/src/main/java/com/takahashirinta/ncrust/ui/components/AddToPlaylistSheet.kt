package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.controls.MetroBottomSheet
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.insets.metroNavigationBarsPadding
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroTextField
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

/**
 * v1.3.0 · B3：把歌曲加入歌单的选择列表。
 *
 * 只列**本人自建**歌单（[PlaylistApi.PlaylistInfo.isOwnedBy]）：收藏的他人歌单服务端会
 * 拒绝写入（manipulate/tracks 返回 404 歌单不存在），列出来只会让用户白点一次。
 *
 * 不预判「这首歌是否已在歌单里」：实测 detail 的 n 上限 1000 且没有可翻页的曲目端点，
 * >1000 首的歌单判重不可靠；而重复添加服务端会返回 502，按幂等成功提示即可（[onResult] 带 isDuplicate）。
 */
@Composable
fun AddToPlaylistSheet(
    songCount: Int,
    onDismiss: () -> Unit,
    onPick: suspend (playlistId: Long) -> AddToPlaylistResult,
    onCreateNew: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val scope = rememberCoroutineScope()

    var playlists by remember { mutableStateOf<List<PlaylistApi.PlaylistInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var addingId by remember { mutableStateOf<Long?>(null) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val uid = PlaylistApi.getCurrentUserId()
                playlists = PlaylistApi.getUserPlaylists(uid).playlists.filter { it.isOwnedBy(uid) }
            } catch (e: Exception) {
                error = strings.loadFailed(e.message)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    MetroBottomSheet(
        onDismiss = onDismiss,
        // 标题区当 dragHandle：MetroBottomSheet 只在 handle 区挂纵向拖拽手势
        // （与 SongMenuSheet 同款做法，整块标题可下滑收起）。
        dragHandle = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                MetroText(
                    strings.addToPlaylistTitle,
                    color = colors.onBackground,
                    style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (songCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    MetroText(
                        strings.trackCountSongs(songCount),
                        color = colors.onSurfaceVariant,
                        style = typography.bodySmall,
                    )
                }
            }
        },
    ) {
        MetroDivider()

        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) { MetroProgressIndicator(color = colors.primary) }

            error != null -> Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MetroText(error!!, color = Color.Red, style = typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clickable { load() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        MetroText(strings.retry, color = colors.primary)
                    }
                }
            }

            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item(key = "new") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCreateNew() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = colors.primary,
                            sizeDp = 24.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        MetroText(
                            strings.playlistNew,
                            color = colors.primary,
                            style = typography.bodyLarge,
                        )
                    }
                }

                if (playlists.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MetroText(
                                strings.playlistNoOwned,
                                color = colors.onSurfaceVariant,
                                style = typography.bodyMedium,
                            )
                        }
                    }
                }

                items(playlists, key = { it.id }) { playlist ->
                    val adding = addingId == playlist.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = addingId == null) {
                                addingId = playlist.id
                                scope.launch {
                                    val result = onPick(playlist.id)
                                    addingId = null
                                    // 成功/重复都关闭：重复也是"这首歌已经在里面"的终态。
                                    if (result == AddToPlaylistResult.SUCCESS ||
                                        result == AddToPlaylistResult.DUPLICATE
                                    ) {
                                        onDismiss()
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = CoverUrls.small(playlist.coverImgUrl),
                            contentDescription = null,
                            // v2.5.0 · B：圆角 + 1dp 描边。形状按渲染边长：48dp < 160dp ⇒ small。
                            modifier = Modifier
                                .size(48.dp)
                                .appCoverFrame(shape = AppShapes.small),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            MetroText(
                                playlist.name,
                                color = colors.onBackground,
                                style = typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MetroText(
                                strings.trackCount(playlist.trackCount),
                                color = colors.onSurfaceVariant,
                                style = typography.bodySmall,
                            )
                        }
                        if (adding) {
                            MetroProgressIndicator(color = colors.primary, sizeDp = 20.dp, strokeDp = 2.dp)
                        } else if (playlist.privacy == PlaylistEditApi.PRIVACY_PRIVATE) {
                            MetroText(
                                strings.playlistPrivacyPrivate,
                                color = colors.onSurfaceVariant,
                                style = typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.metroNavigationBarsPadding())
    }
}

/** 单次「加入歌单」的结果，供 sheet 决定关闭还是留下列错误。 */
enum class AddToPlaylistResult { SUCCESS, DUPLICATE, FAILED }

/**
 * v2.3.0 · B：「加入**本地**歌单」选择器。
 *
 * ## 为什么与 [AddToPlaylistSheet] 分开而不是加一个参数
 *
 * 两者的**幂等语义不同**：ncm 的加歌由服务端 502 兜底（重复添加返回 502，
 * 客户端按幂等成功提示）；本地歌单的重复添加是**明确的规则 7**
 * （清除 tombstone + origin 改 LOCAL，见 `LocalPlaylistSync.addManual`）。
 * 把两种语义塞进同一个 sheet，迟早会有人把「502 幂等」套到本地路径上。
 *
 * ## 空列表也要能用
 *
 * 一个本地歌单都没有时，sheet 里只有「新建」一项 —— 不能因为「没有可选项」
 * 就把整个入口藏起来，那样用户永远建不出第一个本地歌单。
 */
@Composable
fun LocalPlaylistPickerDialog(
    playlists: List<com.takahashirinta.ncrust.local.LocalPlaylist>,
    onDismiss: () -> Unit,
    onPick: (com.takahashirinta.ncrust.local.LocalPlaylist) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    MetroDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(
                strings.localPlaylistChoose,
                color = colors.onBackground,
                style = typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (creating) {
                MetroTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = strings.localPlaylistNameHint,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.clickable { creating = false }.padding(10.dp)) {
                        MetroText(strings.cancel, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clickable(enabled = newName.isNotBlank()) { onCreateNew(newName.trim()) }
                            .padding(10.dp),
                    ) {
                        MetroText(strings.localPlaylistCreate, color = colors.primary)
                    }
                }
            } else {
                if (playlists.isEmpty()) {
                    MetroText(
                        strings.localPlaylistEmpty,
                        color = colors.onSurfaceVariant,
                        style = typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // 列表可能很长，但本地歌单有 100 的上限（LocalPlaylistSync.MAX_PLAYLISTS），
                // 这里不做虚拟化 —— 一个可滚动的 Column 足够，且避免了嵌套 LazyColumn 的约束问题。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    playlists.forEach { pl ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pl) }
                                .padding(vertical = 12.dp),
                        ) {
                            MetroText(pl.name, color = colors.onBackground, style = typography.bodyLarge)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 12.dp),
                ) {
                    MetroText(strings.localPlaylistNew, color = colors.primary, style = typography.bodyMedium)
                }
            }
        }
    }
}
