package com.takahashirinta.ncrust.ui.screen

import android.graphics.drawable.BitmapDrawable
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.Coil
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.cache.HomeSnapshot
import com.takahashirinta.ncrust.network.NetworkAvailability
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.library.LibraryManager
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.warmup.AppWarmup
import com.takahashirinta.ncrust.ui.components.ArtistRecoCard
import com.takahashirinta.ncrust.ui.components.PlayAllButton
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.components.appPressScale
import com.takahashirinta.ncrust.ui.components.listItemAppear
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.widget.Toast

// 新歌速递容量：一次装够，不做分页。首页只是一瞥的展示位，
// 不是深度浏览入口——用户想探索会去搜索/歌单。
private const val NEW_SONGS_LIMIT = 20

@Composable
fun HomeScreen(
    onSongClick: (SongItem) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    onPlayDailyAll: ((List<SongItem>) -> Unit)? = null,
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    onPlayFm: (() -> Unit)? = null,
    // v1.4.0 · 音乐人推荐：非空时在推荐流里插入一张艺人卡（取值见 ArtistReco.shouldShow）。
    artistRecoArtistId: Long? = null,
    onArtistRecoClick: (Long) -> Unit = {}
) {
    val strings = LocalStrings.current
    // 初始 state 从 ContentCache 读取。有缓存则立即渲染，无需 spinner。
    // 后台仍会刷新——请求返回后写回缓存 + 更新 state；LazyColumn 通过 key diff 平滑替换。
    var dailySongs by remember { mutableStateOf(ContentCache.homeDailySongs ?: emptyList()) }
    var playlists by remember { mutableStateOf(ContentCache.homeRecommendPlaylists ?: emptyList()) }
    val newSongs = remember {
        mutableStateListOf<SongItem>().apply { ContentCache.homeNewSongs?.let { addAll(it) } }
    }
    // 冷启动（三块数据都空）才显示全屏 loader；有任一缓存则跳过。
    var isLoading by remember {
        mutableStateOf(dailySongs.isEmpty() && playlists.isEmpty() && newSongs.isEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    // v1.5.1 · C：无网 / 超时的降级状态。
    //  - offline：启动时系统就告诉我们没有可用网络 —— 一个请求都不发；
    //  - timedOut：有网但请求迟迟不回（半死网络），到点主动撤掉转圈，给空态 + 重试。
    var offline by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // E（方案 2 瘦身版）：榜单入口。走 ContentCache 的 15s freshness 窗口 —— warmup/上次
    // 访问刚拉过就不再请求；卡片放在首屏，但请求不阻塞其它分节渲染（拿不到就整块不显示）。
    var toplists by remember { mutableStateOf(ContentCache.toplistItems ?: emptyList()) }
    LaunchedEffect(Unit) {
        if (ContentCache.toplistItems != null && ContentCache.isToplistFresh()) return@LaunchedEffect
        // v1.5.1 · C：没网就不发（榜单只是首屏一块内容，不值得为它等超时）。
        if (!NetworkAvailability.isOnline(context)) {
            offline = true
            return@LaunchedEffect
        }
        runCatching { PlaylistApi.getToplists() }.getOrNull()?.let {
            ContentCache.putToplist(it)
            toplists = it
            HomeSnapshot.save(context, toplists = it)
        }
    }
    val gridState = rememberLazyGridState()
    // 宽屏：新歌从"整行列表"切到"自适应栅格单元"（Apple Music 式）；手机保持整行列表。
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val coroutineScope = rememberCoroutineScope()

    // 私人 FM 电台卡需要登录用户资料: 昵称(卡标题"xx的电台") + 头像(取强调色做封面)。
    // 电台卡**常驻**——资料拿不到也照常显示(标题回退通用文案), 不再因此整卡消失。
    var fmProfile by remember { mutableStateOf(ContentCache.userProfile) }
    // 头像主色调(Palette), 取不到就回退中性底色(不借用 Arris 主题色)
    var fmAccent by remember { mutableStateOf<Color?>(null) }
    val fmContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        // v1.5.1 · C：没网就别去要资料了（电台卡会退回通用标题）。
        if (!NetworkAvailability.isOnline(context)) return@LaunchedEffect
        val profile = ContentCache.userProfile
            ?: runCatching { PlaylistApi.getUserProfile() }.getOrNull()
        if (profile != null && profile.userId > 0) {
            fmProfile = profile
            ContentCache.userProfile = profile
            val accent = runCatching { extractAvatarAccent(fmContext, profile.avatarUrl) }.getOrNull()
            if (accent != null) fmAccent = accent
        }
    }

    /** v1.5.1 · C：把当前首页状态落盘（异步、失败无害），供下次无网冷启动使用。 */
    fun persistHomeSnapshot() {
        HomeSnapshot.save(
            context,
            daily = ContentCache.homeDailySongs,
            playlists = ContentCache.homeRecommendPlaylists,
            newSongs = ContentCache.homeNewSongs,
            toplists = ContentCache.toplistItems,
        )
    }

    fun loadDailySongs() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val list = PlaylistApi.getDailyRecommendSongs()
                withContext(Dispatchers.Main) {
                    dailySongs = list
                    ContentCache.homeDailySongs = list
                    offline = false
                    persistHomeSnapshot()
                }
            } catch (e: Exception) {
                android.util.Log.e("DailySongs", "Error", e)
            }
        }
    }

    fun loadPlaylists() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val list = PlaylistApi.getRecommendPlaylists()
                withContext(Dispatchers.Main) {
                    playlists = list
                    ContentCache.homeRecommendPlaylists = list
                    offline = false
                    persistHomeSnapshot()
                }
            } catch (_: Exception) { }
        }
    }

    fun loadNewSongs() {
        coroutineScope.launch(Dispatchers.IO) {
            if (newSongs.isEmpty()) isLoading = true
            error = null
            try {
                val list = PlaylistApi.getTopSongs(limit = NEW_SONGS_LIMIT, offset = 0)
                withContext(Dispatchers.Main) {
                    newSongs.clear()
                    newSongs.addAll(list)
                    ContentCache.homeNewSongs = list.toList()
                    isLoading = false
                    offline = false
                    persistHomeSnapshot()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = strings.loadFailed(e.message)
                    isLoading = false
                }
            }
        }
    }

    /** v1.5.1 · C：空态上的「重试」——先重新问一次网络，再重发三块请求。 */
    fun retryLoad() {
        error = null
        timedOut = false
        if (!NetworkAvailability.isOnline(context)) {
            offline = true
            isLoading = false
            return
        }
        offline = false
        isLoading = true
        loadDailySongs()
        loadPlaylists()
        loadNewSongs()
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { PlaylistApi.getToplists() }.getOrNull()?.let {
                ContentCache.putToplist(it)
                withContext(Dispatchers.Main) { toplists = it }
                HomeSnapshot.save(context, toplists = it)
            }
        }
    }

    LaunchedEffect(Unit) {
        // AppWarmup 刚预取过同样三个接口时直接复用缓存，避免冷启动重复网络/耗电。
        if (ContentCache.isHomeFresh()) return@LaunchedEffect
        // v1.5.1 · C 冷启动竞态：AppWarmup 把磁盘快照灌回 ContentCache 是异步的
        // （IO 线程 + JSON 解析），这里最多等 800ms 再接后面的判断，免得首帧先闪一个
        // 空态、再被随后到达的快照顶掉。有内容时这个等待一次都不会发生。
        if (dailySongs.isEmpty() && playlists.isEmpty() && newSongs.isEmpty()) {
            var waited = 0
            while (waited < 800 &&
                ContentCache.homeDailySongs == null &&
                ContentCache.homeRecommendPlaylists == null &&
                ContentCache.homeNewSongs == null
            ) {
                delay(50)
                waited += 50
            }
            ContentCache.homeDailySongs?.let { dailySongs = it }
            ContentCache.homeRecommendPlaylists?.let { playlists = it }
            ContentCache.homeNewSongs?.let { cached -> if (newSongs.isEmpty()) newSongs.addAll(cached) }
        }
        // v1.5.2：AppWarmup 的网络阶段不再阻塞 splash，它此刻很可能**正在**取同一批数据。
        // 冷启动时在这里等它收尾（它有硬预算，见 AppWarmup.NETWORK_BUDGET_MS），
        // 免得同一批接口在启动瞬间打两遍。等不到就自己发 —— 下面那段逻辑原样保留。
        if (!ContentCache.isHomeFresh() && !AppWarmup.homeFetchDone.value) {
            withTimeoutOrNull(HOME_WARMUP_JOIN_MS) { AppWarmup.homeFetchDone.first { it } }
            ContentCache.homeDailySongs?.let { dailySongs = it }
            ContentCache.homeRecommendPlaylists?.let { playlists = it }
            ContentCache.homeNewSongs?.let { cached -> if (newSongs.isEmpty()) newSongs.addAll(cached) }
            if (ContentCache.isHomeFresh()) {
                isLoading = false
                return@LaunchedEffect
            }
        }
        // v1.5.1 · C：没网就一个请求都不发 —— 直接进降级态（显示刚灌进来的快照，
        // 或者空态 + 重试）。原来这里会白等 OkHttp 的 30s connectTimeout。
        if (!NetworkAvailability.isOnline(context)) {
            offline = true
            isLoading = false
            return@LaunchedEffect
        }
        loadDailySongs()
        loadPlaylists()
        loadNewSongs()
    }

    // v1.5.1 · C：半死网络（连上了但实际不通）下请求最长会挂到 connectTimeout。
    // 转圈最多陪跑 [HOME_LOAD_TIMEOUT_MS]：到点撤掉转圈、给空态 + 重试，
    // 至少让用户知道发生了什么，而不是对着一个永远转的圈。
    LaunchedEffect(isLoading) {
        if (!isLoading) return@LaunchedEffect
        delay(HOME_LOAD_TIMEOUT_MS)
        if (isLoading) {
            timedOut = true
            isLoading = false
        }
    }

    fun songMenu(song: SongItem): List<SongMenuAction> = listOf(
        SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
            // v2.6.0 · P0：成败由返回值决定，不再无条件弹成功。
            if (LibraryManager.saveSong(context, song).isSuccess) {
                Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
            }
        },
        SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) { onSongInsertNext(song) },
        SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) { onSongAppendToQueue(song) }
    )

    Crossfade(
        targetState = isLoading,
        animationSpec = SokuouTweens.CoverFade,
        modifier = Modifier.fillMaxSize(),
        label = "HomeContentCrossfade"
    ) { loading ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MetroProgressIndicator(color = LocalMetroColors.current.primary)
            }
        } else if (dailySongs.isEmpty() && playlists.isEmpty() && newSongs.isEmpty()) {
            // v1.5.1 · C：三块数据全空（无网 / 失败 / 超时）时不再给一片空白。
            // 此前 error 只写进 state、界面上没有任何呈现，用户看到的就是一个空网格。
            HomeDegradedState(
                offline = offline,
                message = if (!offline && (timedOut || error != null)) strings.loadFailed(error) else null,
                hint = if (offline) strings.networkOfflineHint else null,
                title = if (offline) strings.networkOfflineTitle else strings.loadFailed(error),
                retryLabel = strings.retry,
                onRetry = { retryLoad() },
            )
        } else {
            ResponsiveContent {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    // 背景由 MainScreen 外层 Box 统一填充，子屏不重复画一层（消除 overdraw）
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    flingBehavior = rememberMetroFlingBehavior()
                ) {
                    // Groove 风页头：statusBar + 大字页面名，代替 TopAppBar。
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
                        ) {
                            MetroText(
                                strings.tabHome,
                                color = LocalMetroColors.current.onBackground,
                                style = LocalMetroTypography.current.pageHeading,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // E：榜单入口（全宽横滑），排在所有既有分节之前 —— 首屏可见，
                    // 且复用既有 PlaylistTile，不引入新组件。
                    if (toplists.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = strings.toplistSectionTitle)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(toplists, key = { it.id }) { tl ->
                                    PlaylistTile(
                                        playlist = tl,
                                        onClick = { onPlaylistClick(tl.id) },
                                        onPlayAll = { onPlayPlaylist(tl.id) }
                                    )
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
                    }

                    // 每日推荐：横滑大 tile；点击整块进入播放。
                    if (dailySongs.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = strings.dailySongsTitle,
                                onPlayAll = { onPlayDailyAll?.invoke(dailySongs) },
                                actions = {
                                    // 手动刷新每日推荐(#26):重新拉取并 diff 平滑替换
                                    MetroIconButton(onClick = { loadDailySongs() }) {
                                        MetroIcon(
                                            Icons.Default.Refresh,
                                            contentDescription = strings.refreshLabel,
                                            tint = LocalMetroColors.current.onSurfaceVariant,
                                            sizeDp = 22.dp
                                        )
                                    }
                                }
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(dailySongs.take(12), key = { it.id }) { song ->
                                    DailySongTile(
                                        song = song,
                                        onClick = { onSongClick(song) },
                                        onLongClick = { onShowSongMenu(song, songMenu(song)) }
                                    )
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
                    }

                    // v1.4.0 · 音乐人推荐：与「推荐歌单」同一套分节样式，融进推荐流不突兀。
                    // 是否显示完全由 ArtistReco.shouldShow（本地口味命中）决定，默认配置为空 → 不显示。
                    if (artistRecoArtistId != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = strings.artistRecoTitle)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ArtistRecoCard(
                                artistId = artistRecoArtistId,
                                onClick = onArtistRecoClick
                            )
                        }
                    }

                    // 推荐歌单：横滑大 tile。私人 FM 电台卡**常驻首位**——
                    // 不再依赖推荐歌单是否拉到、也不依赖用户资料是否拿到。
                    if (onPlayFm != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = strings.recommendPlaylistTitle)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                item(key = "fm") {
                                    FmRadioTile(
                                        title = fmProfile?.nickname?.takeIf { it.isNotEmpty() }
                                            ?.let { strings.fmRadioTitle(it) }
                                            ?: strings.fmRadioTitleGeneric,
                                        accent = fmAccent ?: LocalMetroColors.current.surfaceVariant,
                                        subtitle = strings.fmRadioSubtitle,
                                        onClick = { onPlayFm() }
                                    )
                                }
                                items(playlists, key = { it.id }) { pl ->
                                    PlaylistTile(
                                        playlist = pl,
                                        onClick = { onPlaylistClick(pl.id) },
                                        onPlayAll = { onPlayPlaylist(pl.id) }
                                    )
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
                    }

                    // 新歌：宽屏自适应栅格（多列方封面），手机保持整行列表。
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(title = strings.newSongsTitle)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(6.dp)) }
                    itemsIndexed(
                        items = newSongs,
                        // items → itemsIndexed 只为拿下标（入场动效用）；key 显式传同一条
                        // （`it.id`）⇒ LazyVerticalGrid 的 diff 行为不变。
                        key = { _, song -> song.id },
                        span = { _, _ -> if (isWide) GridItemSpan(1) else GridItemSpan(maxLineSpan) }
                    ) { index, song ->
                        if (isWide) {
                            SongGridTile(
                                song = song,
                                modifier = Modifier.listItemAppear(index),
                                onClick = { onSongClick(song) },
                                onLongClick = { onShowSongMenu(song, songMenu(song)) }
                            )
                        } else {
                            SongCard(
                                song = song,
                                style = SongCardStyle.LIST,
                                modifier = Modifier.listItemAppear(index),
                                onClick = { onSongClick(song) },
                                onShowMenu = { onShowSongMenu(song, songMenu(song)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分区标题：中字号 Regular，左对齐 16dp；右侧可选"播放全部"按钮 + 自定义动作。 */
@Composable
private fun SectionHeader(
    title: String,
    onPlayAll: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            title,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.title,
            modifier = Modifier.weight(1f)
        )
        actions()
        if (onPlayAll != null) {
            MetroIconButton(onClick = onPlayAll) {
                MetroIcon(
                    Icons.Default.PlayArrow,
                    contentDescription = LocalStrings.current.playAllButton,
                    tint = LocalMetroColors.current.primary,
                    sizeDp = 28.dp,
                )
            }
        }
    }
}

/** 每日推荐大 tile：160dp 方封面，下方歌名 + 歌手。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DailySongTile(song: SongItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val strings = LocalStrings.current
    Column(
        modifier = Modifier
            .width(160.dp)
            // v2.5.0 · A：卡片按压回弹（不消费事件，combinedClickable 行为不变）。
            .appPressScale()
            .combinedClickableFallback(onClick, onLongClick)
    ) {
        AsyncImage(
            model = CoverUrls.small(song.album?.picUrl),
            contentDescription = strings.coverDesc,
            // v2.5.0 · B：封面圆角 + 1dp 描边。形状按**渲染边长**选（≥160dp → large，
            // <160dp → small）；本 tile 固定 160dp 宽且 1:1 ⇒ 160dp ⇒ large。
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .appCoverFrame(shape = AppShapes.large),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        MetroText(
            song.name,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        MetroText(
            song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/**
 * 自适应栅格里的歌曲 tile：方封面 + 歌名 + 歌手，宽度随单元格（fillMaxWidth），
 * 区别于固定 160dp 的横滑 [DailySongTile]。宽屏新歌用它铺多列。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongGridTile(song: SongItem, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val strings = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .appPressScale()
            .combinedClickableFallback(onClick, onLongClick)
    ) {
        AsyncImage(
            model = CoverUrls.small(song.album?.picUrl),
            contentDescription = strings.coverDesc,
            // 同一尺寸规则：本栅格 minSize=148dp ⇒ 单元格 ≈148–160dp（< 160）⇒ small；
            // 只有宽屏才会走到这里。
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .appCoverFrame(shape = AppShapes.small),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        MetroText(
            song.name,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        MetroText(
            song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/** 推荐歌单大 tile：160dp 方封面 + 圆播放按钮。 */
@Composable
private fun PlaylistTile(playlist: PlaylistApi.PlaylistCard, onClick: () -> Unit, onPlayAll: () -> Unit) {    val strings = LocalStrings.current
    Column(modifier = Modifier.width(160.dp).appPressScale().clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(playlist.coverUrl),
                contentDescription = null,
                // 160dp ≥ 160dp ⇒ AppShapes.large（尺寸规则见 DailySongTile）。
                // 右下的 ▶ 浮层（PlayAllButton）不动。
                modifier = Modifier
                    .fillMaxSize()
                    .appCoverFrame(shape = AppShapes.large),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                size = 34.dp,
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            playlist.name,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        MetroText(
            strings.trackCountSongs(playlist.trackCount),
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.label,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/**
 * 私人 FM 电台大 tile：形制同 PlaylistTile，封面为 Metro 风格图形——
 * 整块用**用户头像提取的强调色**铺底，中央是一枚**对称音频波形**记号
 * (以中线为轴上下等幅的方端竖条，包络中间高两侧收，记号本身仍是方端、无圆角)；
 * 记号颜色按底色明度取黑/白以保证对比。
 *
 * v2.5.0 · B：这块强调色底板占的是**封面位**，所以与同排 PlaylistTile 的 160dp 封面
 * 取同一档圆角（[AppShapes.large]）——旧正典是「直角、无圆角」，整块不裁切。
 */
@Composable
private fun FmRadioTile(
    title: String,
    accent: Color,
    subtitle: String,
    onClick: () -> Unit
) {
    // 波形记号颜色只看与**底色 accent** 的对比，不能跟随主题明暗：
    // 浅色主题下 onBackground 是近黑，压在深色头像强调色上会看不见。
    // 深色主题用白、浅色主题用米色(主题背景色)。
    val isLightTheme = LocalMetroColors.current.background.luminance() > 0.5f
    val onAccent = when {
        accent.luminance() > 0.5f -> Color.Black
        isLightTheme -> LocalMetroColors.current.background
        else -> Color.White
    }
    Column(modifier = Modifier.width(160.dp).appPressScale().clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(AppShapes.large)
                .background(accent)
        ) {
            // 对称音频波形：5 根方端竖条，包络 0.5→1→0.5，中线上下等幅。
            // 方端(非圆角)贴合 Kanesumi；包络收口让记号有"声音起伏"而不呆板。
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h * 0.45f
                val envelope = floatArrayOf(0.5f, 0.82f, 1f, 0.82f, 0.5f)
                val barW = w * 0.085f
                val gap = w * 0.055f
                val maxHalf = h * 0.22f
                val totalW = envelope.size * barW + (envelope.size - 1) * gap
                var x = cx - totalW / 2f
                envelope.forEach { f ->
                    val half = maxHalf * f
                    drawRect(
                        color = onAccent,
                        topLeft = Offset(x, cy - half),
                        size = Size(barW, half * 2f)
                    )
                    x += barW + gap
                }
            }
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                size = 34.dp,
                onClick = onClick
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            title,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        MetroText(
            subtitle,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.label,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/**
 * 从头像位图提取强调色。Coil 在 API 26+ 默认返回硬件位图，Palette 无法读取，
 * 故显式 allowHardware(false)；取色优先 vibrant，再 dominant，最后 muted。
 */
private suspend fun extractAvatarAccent(context: Context, avatarUrl: String): Color? {
    if (avatarUrl.isBlank()) return null
    val loader = Coil.imageLoader(context)
    val result = loader.execute(
        ImageRequest.Builder(context)
            .data(CoverUrls.large(avatarUrl))
            .size(128, 128)
            .allowHardware(false)
            .build()
    )
    val bitmap = (result as? SuccessResult)?.drawable
        ?.let { (it as BitmapDrawable).bitmap }
        ?: return null
    val palette = Palette.from(bitmap).generate()
    val argb = palette.getVibrantColor(0).takeIf { it != 0 }
        ?: palette.getDominantColor(0).takeIf { it != 0 }
        ?: palette.getMutedColor(0).takeIf { it != 0 }
        ?: return null
    return Color(argb)
}

/**
 * v1.5.1 · C：首页降级空态。
 *
 * 冷启动完全没网、或者请求失败/超时且一条缓存都没有时，首页显示它 —— 说明原因 +
 * 一个「重试」入口。此前这种情况下面是一个三块全空的网格，用户既看不到内容也看不到
 * 为什么（error 写进了 state，却没有任何 UI 消费它）。
 */
@Composable
private fun HomeDegradedState(
    offline: Boolean,
    title: String,
    message: String?,
    hint: String?,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MetroIcon(
                imageVector = if (offline) Icons.Default.CloudOff else Icons.Default.Refresh,
                contentDescription = null,
                tint = LocalMetroColors.current.onSurfaceVariant,
                sizeDp = 40.dp,
            )
            Spacer(Modifier.height(16.dp))
            MetroText(
                title,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge,
            )
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                MetroText(
                    message,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodyMedium,
                )
            }
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                MetroText(
                    hint,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodyMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
            MetroButton(text = retryLabel, onClick = onRetry)
        }
    }
}

/** v1.5.1 · C：首页转圈的最长陪跑时间（半死网络下不再一路等到 OkHttp 的 30s）。 */
private const val HOME_LOAD_TIMEOUT_MS = 8_000L

/**
 * v1.5.2：冷启动时等 AppWarmup 后台预取收尾的上限。
 *
 * splash 已经不再等网络（见 AppWarmup.start），但预热仍在后台取同一批接口。
 * 这里等它一下可以避免同一批请求打两遍；等不到就自己发，不留空白。
 * 取 1.2s：正常网络下预热早就回来了，这个等待一次都不会真的走满。
 */
private const val HOME_WARMUP_JOIN_MS = 1_200L

/** 点击 + 长按合并到一个 modifier，避免每个 tile 内部重复样板。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableFallback(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
