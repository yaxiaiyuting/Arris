package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.crosssource.AlbumKey
import com.takahashirinta.ncrust.crosssource.AlbumLookup
import com.takahashirinta.ncrust.crosssource.ArtistKey
import com.takahashirinta.ncrust.crosssource.AlbumPage
import com.takahashirinta.ncrust.crosssource.CatalogAggregator
import com.takahashirinta.ncrust.crosssource.MatchConfidence
import com.takahashirinta.ncrust.crosssource.SourceFilter
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.qq.QqCatalogApi
import com.takahashirinta.ncrust.source.ArtistNav
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.source.trackKey
import com.takahashirinta.ncrust.ui.components.AlbumSourceTag
import com.takahashirinta.ncrust.ui.components.DetailHeader
import com.takahashirinta.ncrust.ui.components.PullToRefreshIndicator
import com.takahashirinta.ncrust.ui.components.pullToRefresh
import com.takahashirinta.ncrust.ui.components.rememberPullToRefreshState
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.SongTags
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.warmup.ListPrefetch
import com.takahashirinta.ncrust.ui.i18n.Strings
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import android.widget.Toast

/**
 * 专辑详情页（v2.4.0 · B：**双源聚合**版本）。
 *
 * 三件与单源版本不同的事：
 *
 * 1. **身份带音源**：`(sourceKey, albumId)`，QQ 的 `albumId` 是 albumMid（base62 字符串）。
 *    旧路由（[com.takahashirinta.ncrust.ui.navigation.NavRoutes.ALBUM]）仍可用，
 *    它构造出来的是 ncm 身份。
 * 2. **曲目来自 [CatalogAggregator]**：两源合并 + 每首带探测出来的可用性；
 *    「播放全部」播的是**当前口径过滤后**的那一份列表，不是两源全量。
 * 3. **订阅按钮只在 ncm 一侧出现**：本应用没有 QQ 专辑订阅接口，
 *    拿 QQ 专辑去写一条 ncm 订阅是**静默写坏数据**，比没有按钮糟得多。
 *
 * @param sourceKey 音源 key（[MusicSource.key]）。
 * @param albumId 该音源内的专辑标识：ncm 十进制 id / QQ albumMid。
 * @param onArtistClick 点副标题（艺人）。**音源一起给出去**；QQ 一侧拿不到 `singerMID`，
 *   此时副标题不可点（见 [artistTarget]）。
 */
@Composable
fun AlbumDetailScreen(
    sourceKey: String,
    albumId: String,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    /**
     * v2.6.1 · P0：点副标题（艺人）。**交出一个完整的身份决策对象**（`source + id + name`），
     * 而不是拆开的两个字段 —— 名字是 QQ 侧唯一能拿到艺人名的来源（`singerMID`
     * 没有"按 mid 取名字"的接口），拆开传必然有人漏传。
     */
    onArtistClick: (ArtistNav.Direct) -> Unit = {},
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val source = remember(sourceKey) { MusicSource.fromKey(sourceKey) }
    val albumIdLong = remember(albumId) { albumId.toLongOrNull() }

    // 头部元信息（封面 / 发行日期 / 厂牌 / 艺人）只存在于 ncm `/api/v1/album/{id}` 的
    // 响应里 —— 聚合器只回传**曲目**（[AlbumPage] 没有这些字段）。所以这里保留
    // 「缓存优先 + 后台刷新」的元信息读取，它同时把 [ContentCache] 写回，
    // 让下次进入的第一帧就有封面和日期。QQ 一侧没有对应端点，头部只显示聚合出来的
    // 名字与曲目数（**不编字段**）。
    val cachedAlbum = remember(source, albumIdLong) {
        if (source == MusicSource.NETEASE) ContentCache.getAlbum(albumIdLong ?: -1L)?.album else null
    }
    var albumMeta by remember(source, albumIdLong) { mutableStateOf(cachedAlbum) }

    // 聚合器锚点的名字与歌手：ncm 一侧来自缓存（名字参与对端召回的搜索关键词，
    // 歌手参与「同名专辑里谁是正主」的判定），QQ 一侧路由里两样都没有，只能先用空串。
    //
    // v3.4.11：**歌手此前从来没有被填进锚点** —— `AlbumKey.artistKey` 一直有字段、
    // 但没有任何调用点写过它，于是聚合器里的 `anchorArtist` 恒为空串，
    // 「用歌手名匹配专辑作者」这条判据在结构上不可能生效（用户报的正是这件事）。
    val cachedName = cachedAlbum?.name.orEmpty()
    val cachedArtist = cachedAlbum?.artist?.name.orEmpty()
    val anchorArtistKey = remember(cachedArtist) {
        cachedArtist.takeIf { it.isNotBlank() }?.let { ArtistKey(source, "", it) }
    }
    val anchor = remember(source, albumId, cachedName, anchorArtistKey) {
        AlbumKey(source, albumId, cachedName, anchorArtistKey)
    }

    /**
     * 解析出来的歌手名（v3.4.11）。
     *
     * 为什么要单独存一份：`anchor` 来自缓存（首帧就定），而直取元信息是**异步**的 ——
     * 歌手名到手之后要能把它带进**这一轮**的聚合锚点，否则第一次进入这一页时
     * 锚点里仍然是空歌手，而那正是「优先原唱」最该生效的时机。
     */
    var resolvedArtist by remember(source, albumId) { mutableStateOf(cachedArtist) }

    var page by remember(anchor) { mutableStateOf<AlbumPage?>(null) }
    var isLoading by remember(anchor, cachedAlbum) { mutableStateOf(cachedAlbum == null) }
    var error by remember(anchor) { mutableStateOf<String?>(null) }
    var reloadTick by remember(anchor) { mutableIntStateOf(0) }

    // v3.4.11：**下拉刷新**（用户报「作者界面里的单曲和专辑仍然没有下滑刷新」，
    // 专辑详情页同样缺这个入口）。复用既有组件，与搜索页 / 歌单详情页逐字同一条写法 ——
    // `DetailScaffold` 从 v2.3.0 就暴露了 `listState` 与 `contentModifier` 这两个口子。
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    var filter by remember { mutableStateOf(SourceFilter.BOTH) }
    var showPlayAllDialog by remember { mutableStateOf(false) }
    // 专辑收藏状态: 本地缓存是即时真源(云端异步同步), 切换后自增 tick 刷新。
    var albumTick by remember { mutableIntStateOf(0) }
    val isAlbumSaved = remember(albumIdLong, albumTick) {
        albumIdLong != null && LibraryManager.getSavedAlbums(context).any { it.albumId == albumIdLong }
    }

    LaunchedEffect(anchor, reloadTick) {
        isLoading = true
        error = null
        // 锚点的名字：`CatalogAggregator.loadAlbum` 拿它当**搜索关键词**去召回另一源的同一张
        // 专辑。空串不报错，只会静默地配不上（搜索接口遇到空关键词直接返回空列表），
        // 而路由 `album/{source}/{albumId}` 里没有名字 —— 所以这里先把它解出来。
        var resolvedName = anchor.name
        // ① ncm：`/api/v1/album/{id}` 既给头部元信息（封面 / 发行日期 / 厂牌 / 艺人），
        //    也给专辑名。它同时写回 [ContentCache]，让下次进入第一帧就有内容。
        if (source == MusicSource.NETEASE && albumIdLong != null) {
            try {
                val response = RetrofitClient.api.getAlbumDetail(albumIdLong)
                ContentCache.putAlbum(albumIdLong, response)
                albumMeta = response.album
                response.album?.name?.takeIf { it.isNotBlank() }?.let { resolvedName = it }
                resolvedArtist = response.album?.artist?.name.orEmpty()
            } catch (_: Exception) {
                // 元信息是**增强**：失败不影响页面（有缓存就继续用缓存、没有就少两行信息）。
            }
        } else if (source == MusicSource.QQMUSIC && resolvedName.isBlank()) {
            // ② QQ：没有 ContentCache，也没有「按 albumMid 取专辑名」的轻量接口 ——
            //    唯一带专辑名的就是曲目列表接口本身。所以这里必须先取一次：
            //    没有名字，`findCounterpartAlbum` 连搜索关键词都构造不出来，
            //    QQ 专辑页就**永远**配不上 ncm 那一侧（而「配上」正是本版存在的理由）。
            //    代价是这一次请求与聚合器内部那一次重复；详情页是低频路径，换的是功能可用。
            resolvedName = runCatching { QqCatalogApi.albumDetail(albumId)?.name }.getOrNull().orEmpty()
        }
        // 用**解出来的名字与歌手**构造这一轮的锚点：名字参与对端召回（搜索关键词），
        // 歌手参与「同名专辑里谁是正主」的判定（`AlbumLookup`）。
        val effectiveArtist = resolvedArtist.takeIf { it.isNotBlank() }
            ?.let { ArtistKey(source, anchor.artistKey?.id.orEmpty(), it) }
        val effective = AlbumKey(source, albumId, resolvedName, effectiveArtist)
        try {
            page = CatalogAggregator.loadAlbum(context, effective)
        } catch (e: Exception) {
            // 聚合失败只显示错误态：不清缓存、不写坏数据、绝不崩。
            error = strings.loadFailed(e.message)
        } finally {
            isLoading = false
        }
    }

    val loaded = page
    val mergedName = loaded?.merged?.name?.takeIf { it.isNotBlank() }
    // 标题优先用聚合结果（两源匹配后的名字），退回首屏元信息，再退到曲目里带的专辑名。
    val displayName = mergedName
        ?: albumMeta?.name?.takeIf { it.isNotBlank() }
        ?: loaded?.songs?.firstOrNull()?.song?.album?.name
        ?: ""
    val subtitle = albumMeta?.artist?.name?.takeIf { it.isNotBlank() }
        ?: loaded?.songs?.firstOrNull()?.song?.artists?.joinToString("/") { it.name }?.takeIf { it.isNotBlank() }

    // 艺人入口：路由 `album/{source}/{albumId}` 里没有艺人信息，所以从元信息或曲目里取。
    // **只在主源是 ncm 时给入口**：QQ 曲目也带一个数字艺人 id，但那是 QQ 的数字 id，
    // 而 QQ 艺人路由要的是 `singerMID` —— 拿数字 id 当 mid 必然跳到错误的艺人页，
    // 所以宁可没有入口（不猜、不退回 ncm）。
    val artistTarget = remember(source, albumMeta, loaded) {
        if (source != MusicSource.NETEASE) {
            null
        } else {
            val id = albumMeta?.artist?.id?.toString()
                ?: loaded?.songs?.firstOrNull { it.song.musicSource == MusicSource.NETEASE }
                    ?.song?.artists?.firstOrNull()?.id?.toString()
            // 名字取同一处来源（有 id 的那一条），取不到就给空串 —— 名字只影响
            // 标题与对端召回，**不影响身份**，所以这里不为了凑一个名字去别处捞。
            id?.let {
                ArtistNav.Direct(
                    MusicSource.NETEASE,
                    it,
                    albumMeta?.artist?.name.orEmpty(),
                )
            }
        }
    }

    // 口径过滤：**两段都过滤**，且「播放全部」用的就是这一份 —— 用户选了「只看某源」
    // 却把另一源的歌也塞进队列，等于把这个口径当摆设。
    val songs = remember(loaded, filter) {
        loaded?.songs.orEmpty().filter { filter.accepts(it.song.musicSource) }
    }
    val songItems = remember(songs) { songs.map { it.song } }
    // v3.1.0 · P0-C：进入专辑页就预取前 N 首的封面（N 按网络类型：WiFi 5 / 移动 2 / 离线 0）。
    // 只预取封面与元数据，**绝不预取 URL**（URL 有时效，见 ListPrefetch 的 KDoc）。
    val prefetchContext = LocalContext.current
    LaunchedEffect(songItems) {
        ListPrefetch.prefetchList(prefetchContext, "album:$albumId", songItems)
    }
    val preferredSource = loaded?.preferredSource
    val availabilityNote = loaded?.availabilityNote.orEmpty()

    if (showPlayAllDialog) {
        PlayAllDialog(
            songCount = songItems.size,
            onDismiss = { showPlayAllDialog = false },
            onReplaceAndPlay = { onReplaceAndPlay(songItems) },
            onInsertNext = { onInsertNext(songItems) }
        )
    }

    DetailScaffold(
        title = strings.albumDetailTitle,
        onBack = onBack,
        isLoading = isLoading,
        hasCachedContent = loaded != null || albumMeta != null,
        error = error,
        onRetry = { reloadTick++ },
        listState = listState,
        contentModifier = Modifier.pullToRefresh(
            state = pullState,
            // `atTop` 判据与搜索页 / 歌单详情页 / 艺人页**逐字相同**。
            atTop = {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            },
            // 刷新 = 重跑聚合（`LaunchedEffect(anchor, reloadTick)` 的 key 里有 reloadTick）。
            onRefresh = { reloadTick++ },
        ),
        header = {
            DetailHeader(
                coverUrl = albumMeta?.picUrl ?: loaded?.songs?.firstOrNull()?.song?.album?.picUrl,
                title = displayName,
                subtitle = subtitle,
                // 点击作曲者 → 跳歌手页, 无按动反馈。拿不到可校验的艺人身份时**不可点**。
                onSubtitleClick = artistTarget?.let { target -> { onArtistClick(target) } },
                infoLines = buildList {
                    // v3.4.0：**音源角标**（用户建议：「专辑界面建议加个来自 ncm 或者 qq」）。
                    //
                    // 详情页的音源是**路由给的**（`album/{source}/{albumId}`），不是反推的 ——
                    // 所以这里直接用 `source`，与收藏页格子那条「从落盘字段反推」的路互不相关
                    // （后者的判据见 `AlbumSourceTag`）。
                    //
                    // 位置放在 infoLines 的**第一行**：它与「发行: …」「厂牌: …」是同一层
                    // 元信息，且这一行**不可点**（详情页的交互元素不得落在底部播放器卡片的
                    // 命中死带里，见 AGENTS.md 触摸陷阱第 2 条 —— 纯文本没有这个问题）。
                    //
                    // ⚠️ 文案出口与搜索结果页、收藏页格子**同一处**（`SongTags.sourceLabel`）。
                    // 目前这一行是「裸音源名」（与专辑格子那行的第一段逐字相同）；
                    // 建议改成带标签的一句，见交付报告里的 `albumSourceInfo` 新键。
                    AlbumSourceTag.sourceLabel(source, strings)?.let { add(strings.source.albumSourceInfo(it)) }
                    albumMeta?.publishTime?.let { time ->
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            .format(java.util.Date(time))
                        add(strings.albumReleaseDate(date))
                    }
                    albumMeta?.company?.takeIf { it.isNotBlank() }?.let { add(strings.albumLabel(it)) }
                    add(strings.trackCountSongs(songs.size))
                },
                onPlayAll = if (songItems.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
                headerActions = {
                    // 订阅专辑**只有 ncm 有写接口**（`LibraryManager.subscribeAlbum` 写的是
                    // ncm 收藏）。QQ 一侧没有对应能力 → 这个按钮干脆不挂，
                    // 绝不拿一张 QQ 专辑去写一条 ncm 的订阅。
                    if (source == MusicSource.NETEASE && albumIdLong != null && songItems.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(LocalMetroColors.current.surfaceVariant)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (isAlbumSaved) {
                                            LibraryManager.removeAlbum(context, albumIdLong)
                                            Toast.makeText(context, strings.removedFromLibrary, Toast.LENGTH_SHORT).show()
                                        } else {
                                            LibraryManager.subscribeAlbum(context, AlbumInfo(
                                                albumId = albumIdLong,
                                                name = displayName,
                                                picUrl = albumMeta?.picUrl ?: "",
                                                artist = albumMeta?.artist?.name ?: subtitle ?: "",
                                                songCount = loaded?.songs?.size ?: 0
                                            ))
                                            Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                        }
                                        albumTick++
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                MetroText(
                                    if (isAlbumSaved) strings.actionUnsaveAlbum else strings.actionSaveAlbum,
                                    color = LocalMetroColors.current.primary,
                                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            )
        },
        content = {
            // 下拉指示器是列表的**第一项**（既有用法）：只在真的拉动或刷新中挂载，
            // 不做「alpha = 0 常挂载」（AGENTS.md 触摸陷阱第 1 条）。
            item(key = "pull-refresh") { PullToRefreshIndicator(state = pullState) }
            if (songs.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        MetroText(strings.noHotSongs, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 16.sp))
                    }
                }
            } else {
                // 先解释「默认放哪一源、为什么」，再列行。
                if (preferredSource != null || availabilityNote.isNotBlank()) {
                    item(key = "agg-note") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            if (preferredSource != null) {
                                MetroText(
                                    strings.source.aggPreferredSource(SongTags.sourceLabel(preferredSource, strings)),
                                    color = LocalMetroColors.current.primary,
                                    style = LocalMetroTypography.current.bodySmall
                                )
                            }
                            if (availabilityNote.isNotBlank()) {
                                MetroText(
                                    strings.source.aggAvailabilityNote(availabilityNote),
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = LocalMetroTypography.current.bodySmall
                                )
                            }
                        }
                    }
                }
                // 直接消费上面 remember(songs) 生成的列表，避免每次重组在 items 块内
                // 重新 new SongItem 破坏稳定性；key 用**带音源**的身份串，
                // 因为两源的数字 id 完全可能撞号。
                items(songs, key = { it.song.trackKey }) { row ->
                    Column {
                        SongCard(
                            song = row.song,
                            style = SongCardStyle.COMPACT,
                            // 探测出来的可用性**只在这里传**：不写回 row.song（它是落盘结构）。
                            availabilityOverride = row.availability,
                            onClick = { onSongClick(row.song) },
                            onShowMenu = {
                                onShowSongMenu(row.song, listOf(
                                    SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                        // v2.6.0 · P0：成败由返回值决定，不再无条件弹成功。
                                        if (LibraryManager.saveSong(context, row.song).isSuccess) {
                                            Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                        onSongInsertNext(row.song)
                                    },
                                    SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                        onSongAppendToQueue(row.song)
                                    }
                                ))
                            }
                        )
                        // 匹配可追溯（铁律 17 第三条）：等级只在真的合并了、且另一端确实存在时
                        // 才说；依据非空就显示。
                        if (row.confidence.mergeable && row.hasOtherSource) {
                            MetroText(
                                strings.source.aggConfidence(confidenceLabel(row.confidence, strings)),
                                color = LocalMetroColors.current.onSurfaceVariant,
                                style = LocalMetroTypography.current.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        if (row.matchReason.isNotBlank()) {
                            MetroText(
                                strings.source.aggMatchReason(row.matchReason),
                                color = LocalMetroColors.current.onSurfaceVariant,
                                style = LocalMetroTypography.current.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    )
}

/**
 * 匹配置信度 → 本地化等级名（v2.4.0 · E）。
 *
 * 与 `ArtistDetailScreen` 里的同名私有函数是**同一张映射表**：等级是封闭集合，
 * 文案来自 `strings.source.aggConfidence*`。两处各留一份私有副本而不是抽公共工具，
 * 是因为它只是一个 `when`；真正需要共享的是「等级文案从 strings 来」这条规矩。
 */
private fun confidenceLabel(c: MatchConfidence, s: Strings): String = when (c) {
    MatchConfidence.EXACT -> s.source.aggConfidenceExact
    MatchConfidence.HIGH -> s.source.aggConfidenceHigh
    MatchConfidence.MEDIUM -> s.source.aggConfidenceMedium
    MatchConfidence.LOW -> s.source.aggConfidenceLow
    MatchConfidence.NONE -> s.source.aggConfidenceNone
}
