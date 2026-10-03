package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
// v2.5.0 · D：「添加到下一首播放」。与「播放」用不同图标 —— 两者语义不同
// （前者不打断当前播放，只排队），同图标会让人以为点哪个都一样。
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.crosssource.CrossSourceMatcher
import com.takahashirinta.ncrust.crosssource.AggregatedSong
import com.takahashirinta.ncrust.crosssource.CatalogAggregator
import com.takahashirinta.ncrust.crosssource.TrackPage
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.source.songRefOf
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.components.SongTags
import com.takahashirinta.ncrust.ui.components.TopScrimIconButton
// v2.5.0 · A/D：按压回弹（AppMotion.pressScale，零重组）。
import com.takahashirinta.ncrust.ui.components.appPressScale
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.viewmodel.SongViewModel
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 单曲信息页（v2.4.0 · E：歌词之上多了**双源版本区**）。
 *
 * 页面有两块，各自独立：
 *
 * 1. **两源版本区**（本版新增）：`CatalogAggregator.loadTrack` 找出同一首歌在另一源上的
 *    版本，每行显示源、探测出来的可用性与「默认该播哪一个」。这是「不再默认 ncm」的
 *    落点 —— 哪一源可播就默认哪一源，两源都不可播时如实说明。
 * 2. **歌词区**（既有）：仍然走单源的 [SongViewModel]（ncm `song/lyric`）。
 *    QQ 的取词链路不在本次改动范围，所以只在 ncm 一侧触发。
 *
 * ## 路由锚点为什么还要补一次元数据
 *
 * 路由只有 `(source, id)` —— 没有曲名与艺人。而跨源召回的**唯一**输入就是
 * 「曲名 + 艺人」（[com.takahashirinta.ncrust.crosssource.CrossSourceMatcher] 没有共享 id 空间，
 * 这是铁律 17 的前提）。拿不到名字就**没法搜**，此时若还显示「另一源未找到对应条目」，
 * 那是一句假话。所以 ncm 一侧先用既有的 `song/detail`（`PlaylistApi.getSongsByIds`，
 * 「转到歌手/专辑」已经在用的同一个接口，不新增网络路径）把锚点补全；
 * 补全**不改身份**（`TrackKey` 只看 `(source, id)`），还是同一首歌。
 *
 * @param sourceKey 音源 key（[MusicSource.key]）。
 * @param songId 该音源内的曲目标识：ncm 十进制 id / QQ 数字 id（裸 id，见 [routeAnchorSong]）。
 * @param onPlay 播放某一行版本。**传的是那一行真实的 `SongItem`**（带音源与载荷），
 *   不是本页的路由锚点 —— 否则 QQ 版本会被当 ncm 播。
 */
@Composable
fun SongDetailScreen(
    sourceKey: String,
    songId: String,
    onBack: () -> Unit,
    onPlay: (SongItem) -> Unit = {},
    /**
     * v2.5.0 · D：「添加到下一首播放」。把**那一行真实的 `SongItem`** 排到当前播放队列的
     * 下一首位置，**不打断正在播的那首歌**。
     *
     * 默认空实现：本页有三个调用点（两处路由 + 预览），漏传时退化成"点了没反应"，
     * 而不是编译不过 —— 与 [onPlay] 的既有约定一致。
     */
    onAddToNext: (SongItem) -> Unit = {}
) {
    val strings = LocalStrings.current
    val source = remember(sourceKey) { MusicSource.fromKey(sourceKey) }
    val numericId = remember(songId) { songId.toLongOrNull() }

    val viewModel: SongViewModel = viewModel()
    val songDetail by viewModel.songDetail.collectAsState()
    val lyric by viewModel.lyric.collectAsState()
    val translatedLyric by viewModel.translatedLyric.collectAsState()

    // 歌词区仍是既有的**ncm 单源**链路：拿一个带 bit62 标志位的合成 id 去问 ncm 取词
    // 只会拿回空，所以 QQ 一侧不触发（那条链路不在本次改动范围）。
    LaunchedEffect(numericId, source) {
        if (source == MusicSource.NETEASE) numericId?.let { viewModel.loadSongDetail(it) }
    }

    // 路由锚点：只有身份，没有名字/艺人。id 解析不出来时（理论上不会发生，路由由
    // `NavRoutes.song` 生成）保持 null —— 此时**不加载也不报错**，页面只剩歌词区，
    // 不去编一句错误文案。
    val anchor = remember(source, numericId) { numericId?.let { routeAnchorSong(source, it) } }

    var page by remember(anchor) { mutableStateOf<TrackPage?>(null) }
    // 实际拿去召回的锚点（可能在 ncm 一侧被补全）。它与 [anchor] 同身份，
    // 「有没有搜过」由它的名字是否为空决定。
    var recallSong by remember(anchor) { mutableStateOf(anchor) }
    var isLoading by remember(anchor) { mutableStateOf(anchor != null) }
    var error by remember(anchor) { mutableStateOf<String?>(null) }
    var reloadTick by remember(anchor) { mutableIntStateOf(0) }

    LaunchedEffect(anchor, reloadTick) {
        val base = anchor ?: return@LaunchedEffect
        isLoading = true
        error = null
        try {
            val enriched = if (source == MusicSource.NETEASE && numericId != null) {
                runCatching { PlaylistApi.getSongsByIds(listOf(numericId)).firstOrNull() }
                    .getOrNull()
                    ?: base
            } else {
                base
            }
            recallSong = enriched
            page = CatalogAggregator.loadTrack(enriched)
        } catch (e: Exception) {
            // 聚合失败只让版本区显示错误态：歌词区照常渲染，不崩、不写坏数据。
            error = strings.loadFailed(e.message)
        } finally {
            isLoading = false
        }
    }

    val trackPage = page
    // 「真的搜过」：只有锚点带了曲名，`searchTracks` 才可能构造出关键词。
    val searched = recallSong?.name?.isNotBlank() == true

    // Groove 无边框：不再套 M3 TopAppBar Surface，返回箭头浮在内容上方共用 TopScrimIconButton。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalMetroColors.current.background)
    ) {
        ResponsiveContent(maxWidth = 720.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 顶部预留 status bar + 72dp 让首行文字落在 scrim 之下、不被返回箭头挡住。
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 16.dp)
            ) {
                // ---------------- 两源版本区（v2.4.0 · E） ----------------
                // id 解析不出来（理论上不会发生，路由由 `NavRoutes.song` 生成）时整块不挂载：
                // 一个只有标题没有内容的区块比没有区块更让人困惑。
                if (anchor != null) {
                    MetroText(
                        strings.source.aggSongDetailTitle,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    MetroText(
                        strings.source.aggVersionsTitle,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    when {
                        trackPage != null -> {
                            trackPage.versions.forEach { version ->
                                SongVersionRow(
                                    version = version,
                                    isPreferred = version.key == trackPage.preferredKey,
                                    onPlay = { onPlay(version.song) },
                                    onAddToNext = { onAddToNext(version.song) }
                                )
                            }
                            // 「另一源没有可校验的对应条目」是**结论**，只在真的搜过时才下 ——
                            // QQ 路由拿不到曲名（没有 songmid 就没有详情），那时候是「没法搜」，
                            // 说成「未匹配」就是一句假话。
                            if (trackPage.versions.size == 1 && searched) {
                                MetroText(
                                    strings.source.aggUnmatched,
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = LocalMetroTypography.current.bodySmall
                                )
                            }
                        }
                        isLoading -> {
                            MetroText(
                                strings.source.aggProbing,
                                color = LocalMetroColors.current.onSurfaceVariant,
                                style = LocalMetroTypography.current.bodySmall
                            )
                        }
                        error != null -> {
                            MetroText(
                                error.orEmpty(),
                                color = LocalMetroColors.current.onSurfaceVariant,
                                style = LocalMetroTypography.current.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ---------------- 既有歌词区（单源，不动） ----------------
                songDetail?.let { song ->
                    MetroText(song.name, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.headlineMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    MetroText(
                        song.artists.joinToString("/") { it.name },
                        color = Color(0xFF1DB954),
                        style = LocalMetroTypography.current.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    MetroText(song.album.name ?: strings.unknownAlbum, color = LocalMetroColors.current.onSurfaceVariant)
                    if (song.duration > 0) {
                        MetroText(formatDuration(song.duration), color = LocalMetroColors.current.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                MetroText(strings.lyricsLabel, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))

                lyric?.let { lrc ->
                    MetroText(lrc, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyMedium)
                } ?: MetroText(strings.noLyrics, color = LocalMetroColors.current.onSurfaceVariant)
            }
        }

        TopScrimIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = strings.back,
            onClick = onBack
        )
    }
}

/**
 * 路由 id → 锚点曲目（v2.4.0 · E）。
 *
 * 路由里只有 `(source, id)`，所以锚点**天生没有名字/艺人/专辑** —— 元数据由调用方补
 * （见 [SongDetailScreen] 的 KDoc）。
 *
 * QQ 的合成 id 带 `1L shl 62` 标志位（[SourceIds.qqId]）：路由里若已经是带标志位的形态
 * 就**原样透传**，否则在这里补标志位。补这一步不是洁癖 —— ncm 与 QQ 的数字 id 各自
 * 独立编号，裸 id 当 QQ id 用会让两套 id 空间混在一起，撞号就是「给错单曲」。
 * 标志位缺省时的 songmid 只能用 id 的字符串形式兜底（该兜底是确定性的，
 * 见 `SourceIds.qqId` 的 `hashSourceId`），因为我们手上没有 songmid。
 */
private fun routeAnchorSong(source: MusicSource, idFromRoute: Long): SongItem {
    val id = if (source == MusicSource.QQMUSIC && !SourceIds.isQqId(idFromRoute)) {
        SourceIds.qqId(idFromRoute, idFromRoute.toString())
    } else {
        idFromRoute
    }
    return songRefOf(source, id = id, sourceId = null, mediaId = null)
}

/**
 * 一行「另一个源的同一首歌」。
 *
 * 三件事：**这是哪个源**（[SongTags.sourceLabel]）、**这次探测能不能播**
 * （[SongTags.availabilityLabel]，`UNKNOWN` 时返回 null ⇒ 什么都不显示）、
 * **是不是默认该播的那一行**（[SongTags] 之外的 `aggDefaultPlayable`）。
 * 前两件都复用列表行的角标装配，避免同一个事实在两处各写一套文案。
 */
@Composable
private fun SongVersionRow(
    version: AggregatedSong,
    isPreferred: Boolean,
    onPlay: () -> Unit,
    onAddToNext: () -> Unit
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val availability = SongTags.availabilityLabel(version.availability, strings)
    val subtitle = buildList {
        version.song.artists?.joinToString("/") { it.name }?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(SongTags.sourceLabel(version.song.musicSource, strings))
        availability?.let { add(it) }
    }.joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 锚点是「只有 id」构造出来的，名字可能为空（QQ 路由）—— 空就**不画这一行**，
            // 不用占位文案去补（曲名不是可以猜的东西）。
            if (version.song.name.isNotBlank()) {
                MetroText(
                    version.song.name,
                    color = colors.onBackground,
                    style = LocalMetroTypography.current.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MetroText(
                subtitle,
                color = colors.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // ★ 「已默认选中有版权的音源」是一句**关于事实的断言**，只有在真的探到
            //   `PLAYABLE` 时才能说。真机验证时抓到过反例：Adele《Strangers By Nature》
            //   两源都是「需会员」，而这一行仍然显示「已默认选中有版权的音源」——
            //   铁律 7 明令禁止这种「降级时回显虚高」。两源都没确证可播时，
            //   如实说「两源都未能确证可播放」。
            if (isPreferred) {
                val confirmed = CrossSourceMatcher.isConfirmedPlayable(version.availability)
                MetroText(
                    if (confirmed) strings.source.aggDefaultPlayable else strings.source.aggNoPlayable,
                    color = if (confirmed) colors.primary else colors.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // v2.5.0 · D：「添加到下一首播放」。**放在播放键左侧**：播放仍是这一行的主操作
        // （用户点一行最可能的意图是听它），新按钮是次要动作，不该抢占主操作的手指落点。
        //
        // 触摸契约：与右侧播放键同规格 —— 视觉 24dp、命中区 48dp
        // （见 `PlayerCard` 那条「小控件命中区只增不减」）。两个 48dp 命中盒相邻，
        // 中间不额外留间隙：留了就会出现"点在两键之间毫无反应"的死区。
        Box(
            modifier = Modifier
                .size(48.dp)
                .appPressScale()
                .clickable { onAddToNext() },
            contentAlignment = Alignment.Center
        ) {
            MetroIcon(
                imageVector = Icons.Default.QueuePlayNext,
                contentDescription = strings.queue.actionAddToNext,
                tint = colors.onSurfaceVariant,
                sizeDp = 24.dp
            )
        }
        // 触摸契约：视觉 24dp，命中区 48dp（见 `PlayerCard` 那条「小控件命中区只增不减」）。
        Box(
            modifier = Modifier
                .size(48.dp)
                .appPressScale()
                .clickable { onPlay() },
            contentAlignment = Alignment.Center
        ) {
            MetroIcon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = strings.playButton,
                tint = colors.primary,
                sizeDp = 24.dp
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
