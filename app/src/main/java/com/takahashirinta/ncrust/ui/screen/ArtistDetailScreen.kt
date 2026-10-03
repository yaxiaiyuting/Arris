package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.crosssource.AggregatedAlbum
import com.takahashirinta.ncrust.crosssource.ArtistKey
import com.takahashirinta.ncrust.crosssource.ArtistPage
import com.takahashirinta.ncrust.crosssource.CatalogAggregator
import com.takahashirinta.ncrust.crosssource.MatchConfidence
import com.takahashirinta.ncrust.crosssource.PlayAllDedup
import com.takahashirinta.ncrust.crosssource.SourceFilter
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.ArtistAlbumItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.PlayAllButton
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.SongTags
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.warmup.ListPrefetch
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import android.widget.Toast

/**
 * 艺人详情页（v2.4.0 · A：**双源聚合**版本）。
 *
 * 与 v2.3.0 的单源版本相比，这里换掉了三件事：
 *
 * 1. **身份带音源**：路由参数是 `(sourceKey, artistId)`，QQ 的 `artistId` 是 `singerMID`
 *    （base62 字符串），ncm 是十进制 id 的字符串形式。旧路由
 *    （[com.takahashirinta.ncrust.ui.navigation.NavRoutes.ARTIST]）仍可用，
 *    它构造出来的**就是 ncm 身份**（见 `MainNavGraph`）。
 * 2. **数据来自 [CatalogAggregator]**：专辑与曲目是两源合并后的结果，每行都带
 *    匹配置信度与**探测出来的**可用性。可用性只活在本页状态里，
 *    **绝不写回 `SongItem`**（那是落盘结构，队列/歌单/离线索引都在存它）。
 * 3. **口径可选**：顶部多一排「双源 / 只看 ncm / 只看 QQ」，两段列表都按它过滤。
 *
 * @param sourceKey 音源 key（[MusicSource.key]）。不认识的值回落 ncm
 *   （见 [MusicSource.fromKey] —— 老数据的唯一正确解释）。
 * @param artistId 该音源内的艺人标识：ncm 十进制 id / QQ `singerMID`。
 * @param onAlbumClick 点专辑。**把音源一起给出去**，否则 QQ 专辑会被当 ncm 打开。
 */
@Composable
fun ArtistDetailScreen(
    sourceKey: String,
    artistId: String,
    /**
     * v2.6.1 · P0：路由带过来的艺人名（空串 = 调用方也不知道）。
     *
     * 它同时解决两件事，两件在 QQ 侧都只能靠它：
     *  ① 页面标题（否则退化成「未知艺人」）；
     *  ② 对端召回的**搜索关键词**（`CatalogAggregator.loadArtist`）——
     *     空关键词不会报错，只会静默地什么都搜不到。
     * 名字**只用于展示与召回**，绝不参与身份判定（身份是 `(source, artistId)`）。
     */
    initialName: String = "",
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (MusicSource, String) -> Unit,
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    /**
     * v2.6.0 · P1：「全部播放」——**替换**当前队列并从第一首起播。
     *
     * 语义与库页红心歌单的 ▶ 一致（`replaceQueueAndPlay`），**不是**追加：
     * 用户在歌手页点「全部播放」的意思是「现在开始听这位歌手的歌」，
     * 追加会把当前队列里剩下的几十首夹在中间。
     */
    onPlayAllSongs: (List<SongItem>) -> Unit = {},
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val source = remember(sourceKey) { MusicSource.fromKey(sourceKey) }

    // 锚点的名字：路由里没有它（`artist/{source}/{artistId}` 三段已满），而
    // `CatalogAggregator.loadArtist` 会拿它当搜索关键词去拉 ncm 热门曲、并对端召回 ——
    // 空串不会报错，只会**静默地什么都搜不到**。
    // ncm 一侧先读 [ContentCache]（上一次进本页写回的），QQ 一侧没有可读的名字来源，
    // 从空串起步（页面标题随后由聚合结果补，见 displayName）。**不编名字、也不写死「ncm」**。
    // 缓存也没命中时，真正的名字在 [LaunchedEffect] 里现取一次（见那里的注释）。
    val cachedName = remember(source, artistId, initialName) {
        val fromRoute = initialName.trim()
        if (fromRoute.isNotEmpty()) {
            // 路由带来的名字优先：它是**用户点的那首歌**上的名字，
            // 比缓存新、也比 ncm 那条"按 id 现取"的路径少一次往返。
            fromRoute
        } else if (source == MusicSource.NETEASE) {
            ContentCache.getArtistAlbums(artistId.toLongOrNull() ?: -1L)?.artist?.name.orEmpty()
        } else {
            ""
        }
    }
    val anchor = remember(source, artistId, cachedName) { ArtistKey(source, artistId, cachedName) }

    var page by remember(anchor) { mutableStateOf<ArtistPage?>(null) }
    var isLoading by remember(anchor) { mutableStateOf(true) }
    var error by remember(anchor) { mutableStateOf<String?>(null) }
    // 重试：LaunchedEffect 的 key 里加一个自增 tick（比把加载写成局部函数更好读，
    // 也不会把 coroutineScope 泄漏进 Composable 的闭包）。
    var reloadTick by remember(anchor) { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(SourceFilter.BOTH) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(anchor, reloadTick) {
        isLoading = true
        error = null
        try {
            // 锚点的名字：`CatalogAggregator.loadArtist` 拿它当**搜索关键词**去拉 ncm 热门曲、
            // 并召回对端艺人。空串不会报错，只会**静默地什么都搜不到**（`searchTracks` /
            // 搜索接口遇到空关键词就返回空列表）—— 而路由里没有名字。
            //
            // ncm 一侧因此在这里现取一次 `/api/artist/albums/{id}`（旧页面本来就是它），
            // 拿到名字后写回 [ContentCache]，下次进入就不用再取。
            // QQ 一侧没有「按 singerMID 取艺人名」的既有接口（mid 只出现在专辑/曲目列表里），
            // 所以那里仍然从空串起步：宁可少搜，也不编一个名字。
            val resolvedName = if (source == MusicSource.NETEASE && anchor.name.isBlank()) {
                val id = artistId.toLongOrNull()
                if (id == null) {
                    ""
                } else {
                    runCatching {
                        RetrofitClient.api.getArtistAlbums(id).takeIf { it.code == 200 }?.also {
                            ContentCache.putArtistAlbums(id, it)
                        }?.artist?.name
                    }.getOrNull().orEmpty()
                }
            } else {
                anchor.name
            }
            val effective =
                if (resolvedName == anchor.name) anchor else ArtistKey(source, artistId, resolvedName)
            page = CatalogAggregator.loadArtist(context, effective)
        } catch (e: Exception) {
            // 聚合失败**只**让本页显示错误态：不清缓存、不写坏数据、绝不崩。
            // `loadArtist` 内部每个 IO 都已经包了 runCatching，能走到这里的是编排层的意外。
            error = strings.loadFailed(e.message)
        } finally {
            isLoading = false
        }
    }

    val loaded = page
    // 标题取**聚合结果**：ncm 一侧就是锚点/缓存里的名字；QQ 一侧锚点没有名字，
    // 退回对端艺人的名字（匹配上了才存在），再退回「未知歌手」。
    val displayName = loaded?.merged?.name?.takeIf { it.isNotBlank() }
        ?: loaded?.merged?.aliases?.firstOrNull()?.name
        ?: strings.unknownArtistName

    // 口径过滤：两段列表都用同一份 SourceFilter，切口径时列表立即变。
    val albums = remember(loaded, filter) {
        loaded?.albums.orEmpty().filter { filter.accepts(it.key.source) }
    }
    val songs = remember(loaded, filter) {
        loaded?.songs.orEmpty().filter { filter.accepts(it.song.musicSource) }
    }
    // 上移到 Composable 作用域用 remember 缓存：避免 items 块内反复分组破坏稳定性。
    val albumRows = remember(albums) { albums.chunked(2) }
    val preferredSource = loaded?.preferredSource
    // v2.6.0 · P1：跨源混播去重的**唯一落点**（`PlayAllDedup`）。在这里算一次并缓存：
    // 判据是纯函数，但列表可能有几百行，放进 `items` 里逐行重算没必要。
    //
    // 输入是**当前口径过滤后**的 `songs` —— 用户切到「只看 QQ」再点全部播放时，
    // 播的就该是 QQ 那一段，而不是偷偷播全部（任务书 4.1「顺序：按当前排序」）。
    // v3.1.0 · P0-C：进入艺人页预取前 N 首封面（只封面，不预取 URL）。
    val prefetchContext = LocalContext.current
    LaunchedEffect(songs) {
        ListPrefetch.prefetchList(prefetchContext, "artist:$artistId", songs.map { it.song })
    }
    val playAllPlan = remember(songs, preferredSource) {
        PlayAllDedup.plan(songs, preferredSource)
    }
    val availabilityNote = loaded?.availabilityNote.orEmpty()

    DetailScaffold(
        title = strings.artistDetailTitle,
        onBack = onBack,
        isLoading = isLoading,
        hasCachedContent = loaded != null,
        error = error,
        onRetry = { reloadTick++ },
        header = {
            // 顶部 statusBar + 56dp 为浮层返回箭头让路（浮层箭头会盖在这块黑色上）。
            Column(modifier = Modifier.statusBarsPadding()) {
                Spacer(Modifier.height(56.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    MetroText(
                        displayName,
                        color = LocalMetroColors.current.onBackground,
                        style = TextStyle(fontSize = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        // 计数取**聚合结果**（当前口径下列出的张数 / 首数）：
                        // 单源的 albumSize / musicSize 在这里已经不存在了，
                        // 编一个数字出来比少一行更糟。
                        MetroText(
                            strings.artistAlbumCount(albums.size),
                            color = LocalMetroColors.current.onSurfaceVariant,
                            style = TextStyle(fontSize = 14.sp)
                        )
                        Spacer(Modifier.width(16.dp))
                        MetroText(
                            strings.artistSongCount(songs.size),
                            color = LocalMetroColors.current.onSurfaceVariant,
                            style = TextStyle(fontSize = 14.sp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // 展示口径：双源 / 只看 ncm / 只看 QQ。三个标签的文案全部来自
                // `strings.source.*`，顺序与 `SourceFilter` 的 ordinal 一一对应。
                MetroTabRow(
                    items = listOf(
                        MetroTabItem(strings.source.aggFilterBoth),
                        MetroTabItem(strings.source.aggFilterNetease),
                        MetroTabItem(strings.source.aggFilterQq)
                    ),
                    selectedTabIndex = filter.ordinal,
                    onTabSelected = { index ->
                        filter = SourceFilter.entries.getOrElse(index) { SourceFilter.BOTH }
                    }
                )
                Spacer(Modifier.height(8.dp))
                MetroTabRow(
                    items = listOf(MetroTabItem(strings.categoryAlbums), MetroTabItem(strings.categoryTracks)),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { index -> selectedTab = index }
                )
                Spacer(Modifier.height(12.dp))
            }
        },
        content = {
            when (selectedTab) {
                0 -> {
                    if (albumRows.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                MetroText(strings.noAlbums, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 16.sp))
                            }
                        }
                    } else {
                        items(albumRows, key = { row -> row.first().key.tag }) { row ->
                            // 边到边 tile：spacedBy 2dp 制造 Metro 拼贴感。
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (album in row) {
                                    AggregatedAlbumGridItem(
                                        album = album,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onAlbumClick(album.key.source, album.key.id) }
                                    )
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                1 -> {
                    if (songs.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                MetroText(strings.noHotSongs, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 16.sp))
                            }
                        }
                    } else {
                        // 先解释「默认放哪一源、为什么」，再列行 —— 这两句只在有内容时才有意义。
                        if (preferredSource != null || availabilityNote.isNotBlank()) {
                            item(key = "agg-note") {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    if (preferredSource != null) {
                                        MetroText(
                                            strings.source.aggPreferredSource(
                                                SongTags.sourceLabel(preferredSource, strings)
                                            ),
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
                        // v2.6.0 · P1：全部播放入口。放在**歌曲 tab 内**（不是页面 header）——
                        // header 是两个 tab 共用的，放那里会让「专辑」tab 上也出现一个
                        // 播放歌曲的按钮。行内还有一个规模提示，用户点之前知道会播多少首。
                        item(key = "play-all") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MetroText(
                                    strings.artistSongCount(playAllPlan.songs.size),
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = LocalMetroTypography.current.bodySmall,
                                )
                                Spacer(Modifier.weight(1f))
                                // 空列表时**不挂载**按钮（任务书 4.1「置灰或隐藏」）：
                                // 这一格在 `songs.isEmpty()` 分支里根本不会执行，
                                // 所以真实的空态由上面的 `noHotSongs` 承担 —— 见那里的注释。
                                PlayAllButton(onClick = { onPlayAllSongs(playAllPlan.songs) })
                            }
                        }
                        items(songs, key = { it.key.tag }) { row ->
                            Column {
                                SongCard(
                                    song = row.song,
                                    style = SongCardStyle.COMPACT,
                                    // 探测出来的可用性**只在这里传**：不写回 row.song。
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
                                // 匹配可追溯（铁律 17 第三条）：等级只在**真的合并了**且另一端
                                // 确实存在时才说（`mergeable && hasOtherSource`）；依据只要非空就显示
                                // —— 用户要能回答「为什么这两条被认为是一样的」。
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
            }
        }
    )
}

/**
 * 匹配置信度 → 本地化等级名（v2.4.0 · E）。
 *
 * 等级名是**封闭集合**（[MatchConfidence] 的五个取值），所以映射写在这里、文案来自
 * `strings.source.aggConfidence*`；`MatchConfidence.reason` 那种自由文本才走
 * [Strings.source] 的 `aggMatchReason`。抽成纯函数是为了让「等级 → 文案」只有一处定义。
 */
private fun confidenceLabel(c: MatchConfidence, s: Strings): String = when (c) {
    MatchConfidence.EXACT -> s.source.aggConfidenceExact
    MatchConfidence.HIGH -> s.source.aggConfidenceHigh
    MatchConfidence.MEDIUM -> s.source.aggConfidenceMedium
    MatchConfidence.LOW -> s.source.aggConfidenceLow
    MatchConfidence.NONE -> s.source.aggConfidenceNone
}

/**
 * 聚合后的一张专辑（v2.4.0 · A）。
 *
 * 与旧的 `ArtistAlbumGridItem`（单源 `ArtistAlbumItem`）的区别就是本版要展示的东西：
 * **这张专辑在哪几个源上有、另一源有没有、为什么认为两源是同一张**。
 * 点它时必须把 `album.key` 的**音源与 id 一起**给出去 —— 否则 QQ 专辑会被当 ncm 打开。
 */
@Composable
private fun AggregatedAlbumGridItem(
    album: AggregatedAlbum,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val strings = LocalStrings.current
    val sourceName = SongTags.sourceLabel(album.key.source, strings)
    Column(modifier = modifier.clickable { onClick() }) {
        AsyncImage(
            model = CoverUrls.small(album.picUrl),
            contentDescription = strings.albumCoverDesc,
            // v2.5.0 · B：封面圆角 + 1dp 描边。形状按**渲染边长**选（≥160dp → large，
            // <160dp → small）；本页是 2 列拼贴（chunked(2) + weight(1f)，间距 2dp），
            // 窄屏 360dp 内容宽下单格 179dp、宽屏更宽 ⇒ 都 ≥160 ⇒ large。
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .appCoverFrame(shape = AppShapes.large),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        MetroText(
            album.name,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        // 来源角标 + 曲目数 + 发行日期，一行内解决：拼贴格子空间很紧。
        val infoLine = buildList {
            add(if (album.isDual) strings.source.aggFilterBoth else sourceName)
            album.trackCount?.let { add(strings.trackCount(it)) }
            album.publishDate?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
        MetroText(
            infoLine,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        // 只在「另一源没有对应」**且**匹配器给出了依据时补一句 —— 空白串时什么都不说。
        if (!album.isDual && album.reason.isNotBlank()) {
            MetroText(
                strings.source.aggOnlyOn(sourceName),
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * v2.3.0 的单源专辑格子。**保留**：它是公开 API，且单源列表（搜索结果等）仍可能需要它。
 * 艺人页自 v2.4.0 起改用 [AggregatedAlbumGridItem]（它多带音源与匹配信息）。
 */
@Composable
fun ArtistAlbumGridItem(album: ArtistAlbumItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val strings = LocalStrings.current
    Column(modifier = modifier.clickable { onClick() }) {
        AsyncImage(model = CoverUrls.small(album.picUrl), contentDescription = strings.albumCoverDesc, modifier = Modifier.fillMaxWidth().aspectRatio(1f).appCoverFrame(shape = AppShapes.large), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(6.dp))
        MetroText(album.name, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        album.publishTime?.let {
            val year = java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
            MetroText("$year · ${strings.trackCount(album.size ?: 0)}", color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}
