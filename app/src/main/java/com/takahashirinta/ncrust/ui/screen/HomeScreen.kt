package com.takahashirinta.ncrust.ui.screen

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import android.graphics.drawable.BitmapDrawable
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.palette.graphics.Palette
import coil.Coil
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.takahashirinta.ncrust.account.SessionMonitor
import com.takahashirinta.ncrust.account.SessionState
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.NetworkAvailability
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.library.LibraryManager
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.warmup.AppWarmup
import com.takahashirinta.ncrust.warmup.HomeFeed
import com.takahashirinta.ncrust.ui.components.ArtistRecoCard
import com.takahashirinta.ncrust.ui.components.PlayAllButton
import com.takahashirinta.ncrust.ui.components.SessionExpiredBanner
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.components.appPressScale
import com.takahashirinta.ncrust.ui.components.listItemAppear
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.components.ArrisHomeIdentity
import com.takahashirinta.ncrust.ui.components.ArrisButton
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.MetroIconButtonSurface
import com.takahashirinta.ncrust.ui.components.arrisAccentInk
import com.takahashirinta.ncrust.ui.components.arrisSurface
import com.takahashirinta.ncrust.ui.components.drawArrisGrooves
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.AppShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.widget.Toast

@Composable
fun HomeScreen(
    onSongClick: (SongItem) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    onOpenDaily: ((List<SongItem>) -> Unit)? = null,
    onPlayDailyAll: ((List<SongItem>) -> Unit)? = null,
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    onPlayFm: (() -> Unit)? = null,
    // v1.4.0 · 音乐人推荐：非空时在推荐流里插入一张艺人卡（取值见 ArtistReco.shouldShow）。
    artistRecoArtistId: Long? = null,
    onArtistRecoClick: (Long) -> Unit = {},
    // 会话失效横幅点击 → 走现有登录流程（窄屏网页登录 / 宽屏扫码）。
    onRelogin: () -> Unit = {},
    active: Boolean = true,
) {
    val strings = LocalStrings.current
    // 会话状态：Expired 时顶部一条失效横幅；非 Valid 时每日推荐加一行「登录后为你推荐」
    // （登录失效后接口返回的是非个性化的通用列表，不能冒充个性化推荐）。
    val sessionState by SessionMonitor.state.collectAsState()
    val sessionExpired = sessionState is SessionState.Expired
    val sessionValid = sessionState is SessionState.Valid
    val loginGeneration by CookieManager.loginGenerations.collectAsState()
    val owner = remember(loginGeneration) { CookieManager.accountOwner() }
    val hasCurrentCache = ContentCache.ownerMatches(owner)
    var dailySongs by remember(owner) { mutableStateOf(if (hasCurrentCache) ContentCache.homeDailySongs.orEmpty() else emptyList()) }
    var playlists by remember(owner) { mutableStateOf(if (hasCurrentCache) ContentCache.homeRecommendPlaylists.orEmpty() else emptyList()) }
    var newSongs by remember(owner) { mutableStateOf(if (hasCurrentCache) ContentCache.homeNewSongs.orEmpty() else emptyList()) }
    var isLoading by remember(loginGeneration) { mutableStateOf(true) }
    var error by remember(loginGeneration) { mutableStateOf<String?>(null) }
    var offline by remember(loginGeneration) { mutableStateOf(false) }
    var retryCount by remember { mutableIntStateOf(0) }
    var handledRetry by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // E（方案 2 瘦身版）：榜单入口。走 ContentCache 的 15s freshness 窗口 —— warmup/上次
    // 访问刚拉过就不再请求；卡片放在首屏，但请求不阻塞其它分节渲染（拿不到就整块不显示）。
    var toplists by remember { mutableStateOf(ContentCache.toplistItems ?: emptyList()) }
    // 4.0.0：榜单卡用「榜一那首歌」的专辑图当封面（用户认的是歌，不是榜单拼贴图）。
    // 一次 /api/playlist/detail 就够；限制并发 3、只补前 12 个榜单，失败就退回原封面。
    val firstSongCovers = remember { androidx.compose.runtime.mutableStateMapOf<Long, String>() }
    val gridState = rememberLazyGridState()
    // 宽屏：新歌从"整行列表"切到"自适应栅格单元"（Apple Music 式）；手机保持整行列表。
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val isWide = windowWidthDp >= 600
    // The FM card uses a generic title until it has the current account's profile.
    var fmProfile by remember(owner) { mutableStateOf(ContentCache.userProfile.takeIf { hasCurrentCache }) }
    var fmAccent by remember(owner) { mutableStateOf<Color?>(null) }
    LaunchedEffect(active, loginGeneration) {
        if (!active || !NetworkAvailability.isOnline(context)) return@LaunchedEffect
        val started = CookieManager.snapshot()
        if (started.loginGeneration != loginGeneration) return@LaunchedEffect
        val profile = CookieManager.ifSameLogin(started) { live ->
            ContentCache.userProfile.takeIf { ContentCache.ownerMatches(live.accountOwner) }
        } ?: try {
            PlaylistApi.getUserProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (profile != null && profile.userId > 0) {
            coroutineContext.ensureActive()
            val published = CookieManager.ifSameLogin(started) { live ->
                ContentCache.bindOwner(live.accountOwner)
                fmProfile = profile
                ContentCache.userProfile = profile
            }
            if (published == null) return@LaunchedEffect
            val accent = try {
                withContext(Dispatchers.Default) { extractAvatarAccent(context, profile.avatarUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            coroutineContext.ensureActive()
            CookieManager.ifSameLogin(started) { fmAccent = accent }
        }
    }

    /**
     * 4.0.0：给榜单卡补「首曲封面」。
     *
     * 只在内存里缓存（进程级），不落盘：它是纯展示优化，重启后重拉一次的成本很低
     * （每个榜单一次明文 GET）。失败静默 —— 榜单原本的拼贴封面照常显示。
     */
    suspend fun loadChartCovers(charts: List<PlaylistApi.PlaylistCard>) {
        val pending = charts.take(12).filter { firstSongCovers[it.id] == null }
        if (pending.isEmpty() || !NetworkAvailability.isOnline(context)) return
        // Tie these reads to the visible chart list and publish each available cover.
        for (chunk in pending.chunked(3)) {
            coroutineScope {
                chunk.forEach { chart ->
                    launch {
                        val cover = try {
                            PlaylistApi.getChartFirstSongCover(chart.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (cover != null) firstSongCovers[chart.id] = cover
                    }
                }
            }
        }
    }

    // 4.0.1：榜单到手就补首曲封面 —— 用 LaunchedEffect(toplists) 而不是只在网络回调里调，
    // 因为首屏经常是**缓存命中**（ContentCache.toplistItems），那条路径不走网络回调，
    // 只在回调里调用就会导致"榜单出来了但封面还是旧的拼贴图"。
    LaunchedEffect(active, toplists) {
        if (active && toplists.isNotEmpty()) loadChartCovers(toplists)
    }

    LaunchedEffect(active, loginGeneration, retryCount) {
        if (!active) return@LaunchedEffect
        val started = CookieManager.snapshot()
        if (started.loginGeneration != loginGeneration) return@LaunchedEffect
        // The screen can compose before the disk snapshot has been restored.
        withTimeoutOrNull(LOCAL_HOME_WAIT_MS) { AppWarmup.ready.first { it } }
        CookieManager.ifSameLogin(started) { live ->
            if (ContentCache.ownerMatches(live.accountOwner)) {
                ContentCache.homeDailySongs?.let { if (dailySongs.isEmpty()) dailySongs = it }
                ContentCache.homeRecommendPlaylists?.let { if (playlists.isEmpty()) playlists = it }
                ContentCache.homeNewSongs?.let { if (newSongs.isEmpty()) newSongs = it }
            }
            if (toplists.isEmpty()) ContentCache.toplistItems?.let { toplists = it }
        }
        val force = handledRetry != retryCount
        handledRetry = retryCount
        offline = !NetworkAvailability.isOnline(context)
        error = null
        if (offline) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        suspend fun <T> load(fetch: suspend () -> List<T>, publish: (List<T>) -> Unit) {
            try {
                val result = fetch()
                kotlin.coroutines.coroutineContext.ensureActive()
                CookieManager.ifSameLogin(started) { publish(result) }
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                CookieManager.ifSameLogin(started) {
                    if (error == null) error = e.message
                }
            }
        }
        withTimeoutOrNull(HOME_LOAD_TIMEOUT_MS) {
            coroutineScope {
                launch { load({ HomeFeed.dailySongs(context, started) }) { dailySongs = it } }
                launch { load({ HomeFeed.recommendedPlaylists(context, started, force) }) { playlists = it } }
                launch { load({ HomeFeed.newSongs(context, started, force) }) { newSongs = it } }
                launch { load({ HomeFeed.toplists(context, started, force) }) { toplists = it } }
            }
        }
        isLoading = false
    }

    fun songMenu(song: SongItem) = homeSongActions(context, strings, song, onSongInsertNext, onSongAppendToQueue)

    val hasContent = dailySongs.isNotEmpty() || playlists.isNotEmpty() ||
        newSongs.isNotEmpty() || toplists.isNotEmpty()
    // 200dp 侧栏及内容边距之后，1000dp 窗口还有足够空间容纳两个入口。
    val pairRadio = windowWidthDp >= 1000 && dailySongs.isNotEmpty() && onPlayFm != null
    val fmTitle = fmProfile?.nickname?.takeIf { it.isNotEmpty() }
        ?.let(strings.fmRadioTitle) ?: strings.fmRadioTitleGeneric
    Crossfade(
        targetState = isLoading && !hasContent,
        animationSpec = SokuouTweens.CoverFade,
        modifier = Modifier.fillMaxSize(),
        label = "HomeContentCrossfade"
    ) { loading ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MetroProgressIndicator(color = LocalMetroColors.current.primary)
            }
        } else {
            ResponsiveContent {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    // 背景由 MainScreen 外层 Box 统一填充，子屏不重复画一层（消除 overdraw）
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    flingBehavior = rememberMetroFlingBehavior()
                ) {
                    item(key = "home-header", span = { GridItemSpan(maxLineSpan) }) {
                        ArrisHomeIdentity(
                            title = strings.tabHome,
                            modifier = Modifier.statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }

                    // 登录失效横幅：放在页头之下、内容之上（列表第一个内容项），点击进登录流程。
                    if (sessionExpired) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SessionExpiredBanner(onRelogin = onRelogin)
                        }
                    }

                    if (!hasContent) {
                        item(key = "home-empty", span = { GridItemSpan(maxLineSpan) }) {
                            HomeDegradedState(
                                offline = offline,
                                title = if (offline) strings.networkOfflineTitle else strings.loadFailed(error),
                                retryLabel = strings.retry,
                                onRetry = { retryCount++ },
                            )
                        }
                        return@LazyVerticalGrid
                    }

                    if (dailySongs.isNotEmpty()) {
                        item(key = "home-daily-feature", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    .then(if (pairRadio) Modifier.height(IntrinsicSize.Min) else Modifier),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                DailyMixFeature(
                                    title = strings.dailySongsTitle,
                                    detail = if (sessionValid) strings.trackCountSongs(dailySongs.size)
                                        else strings.dailySongsLoginHint,
                                    coverUrl = dailySongs.firstNotNullOfOrNull { song ->
                                        song.album?.picUrl?.takeIf { it.isNotBlank() }
                                    },
                                    coverSize = if (isWide) 120.dp else 94.dp,
                                    modifier = Modifier.weight(2f)
                                        .then(if (pairRadio) Modifier.fillMaxHeight() else Modifier),
                                    onPlayAll = onPlayDailyAll?.let { play -> { play(dailySongs) } },
                                    onOpen = onOpenDaily?.let { open -> { open(dailySongs) } },
                                )
                                if (pairRadio) {
                                    FmRadioStrip(
                                        title = fmTitle,
                                        accent = fmAccent ?: LocalMetroColors.current.primary,
                                        subtitle = strings.fmRadioSubtitle,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        onClick = onPlayFm,
                                    )
                                }
                            }
                        }
                        item(key = "home-daily-songs", span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
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
                    }

                    if (toplists.isNotEmpty()) {
                        item(key = "home-chart-heading", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = strings.toplistSectionTitle)
                        }
                        item(key = "home-charts", span = { GridItemSpan(maxLineSpan) }) {
                            ChartShelf(toplists, firstSongCovers, onPlaylistClick, onPlayPlaylist)
                        }
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

                    if (onPlayFm != null && !pairRadio) {
                        item(key = "home-radio", span = { GridItemSpan(maxLineSpan) }) {
                            FmRadioStrip(
                                title = fmTitle,
                                accent = fmAccent ?: LocalMetroColors.current.primary,
                                subtitle = strings.fmRadioSubtitle,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                onClick = onPlayFm,
                            )
                        }
                    }

                    if (playlists.isNotEmpty()) {
                        item(key = "home-playlists-heading", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = strings.recommendPlaylistTitle)
                        }
                        item(key = "home-playlists", span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(playlists, key = { it.id }) { pl ->
                                    PlaylistTile(
                                        playlist = pl,
                                        onClick = { onPlaylistClick(pl.id) },
                                        onPlayAll = { onPlayPlaylist(pl.id) }
                                    )
                                }
                            }
                        }
                    }

                    // 新歌：宽屏自适应栅格（多列方封面），手机保持整行列表。
                    if (newSongs.isNotEmpty()) {
                        item(key = "home-new-heading", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = strings.newSongsTitle)
                        }
                    }
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

@Composable
private fun SectionHeader(title: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 40.dp),
        contentAlignment = Alignment.BottomStart) {
        MetroText(
            title,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.title.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
            ),
        )
    }
}

@Composable
private fun DailyMixFeature(
    title: String,
    detail: String,
    coverUrl: String?,
    coverSize: Dp,
    modifier: Modifier,
    onPlayAll: (() -> Unit)?,
    onOpen: (() -> Unit)? = null,
) {
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = modifier
            .arrisSurface(emphasized = true)
            .then(if (onOpen == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onOpen))
            .drawWithCache {
                val inset = 16.dp.toPx() + coverSize.toPx() / 2f
                val center = Offset(if (rtl) inset else size.width - inset, size.height / 2f)
                val radius = coverSize.toPx() * 1.45f
                val wash = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = .16f), Color.Transparent),
                    center = center,
                    radius = radius,
                )
                val ink = lerp(colors.surface, colors.onBackground, .44f)
                onDrawBehind {
                    drawRect(wash)
                    drawArrisGrooves(center, radius * .83f, ink)
                }
            }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetroText(title, color = colors.onBackground, style = type.titleLarge,
                    modifier = Modifier.weight(1f, fill = false))
                if (onOpen != null) {
                    Spacer(Modifier.width(6.dp))
                    MetroIcon(Icons.AutoMirrored.Filled.ArrowForward, null,
                        tint = colors.onSurfaceVariant, sizeDp = 16.dp)
                }
            }
            MetroText(detail, color = colors.onSurfaceVariant, style = type.bodySmall)
            if (onPlayAll != null) {
                Spacer(Modifier.height(2.dp))
                ArrisButton(text = LocalStrings.current.playAllButton, onClick = onPlayAll)
            }
        }
        HomeArtwork(coverUrl, coverSize)
    }
}

/** The current recommendation batch is presented as a playlist in its original order. */
@Composable
internal fun DailyRecommendationsScreen(
    songs: List<SongItem>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onInsertNext: (SongItem) -> Unit,
    onAppend: (SongItem) -> Unit,
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit,
) {
    val strings = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val coverSize = if (LocalConfiguration.current.screenWidthDp >= 600) 120.dp else 94.dp
    DetailScaffold(
        title = strings.dailySongsTitle,
        onBack = onBack,
        navigationOverArtwork = false,
        header = {
            DailyMixFeature(
                title = strings.dailySongsTitle,
                detail = if (songs.isEmpty()) strings.playlistEmptyTracks else strings.trackCountSongs(songs.size),
                coverUrl = songs.firstNotNullOfOrNull { it.album?.picUrl?.takeIf(String::isNotBlank) },
                coverSize = coverSize,
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 16.dp),
                onPlayAll = if (songs.isNotEmpty()) ({ onPlay(0) }) else null,
            )
        },
    ) {
        listItemsIndexed(songs) { index, song ->
            SongCard(song = song, style = SongCardStyle.COMPACT,
                onClick = { onPlay(index) },
                onShowMenu = { onShowSongMenu(song, homeSongActions(context, strings, song, onInsertNext, onAppend)) })
        }
    }
}

private fun homeSongActions(
    context: Context,
    strings: Strings,
    song: SongItem,
    onInsertNext: (SongItem) -> Unit,
    onAppend: (SongItem) -> Unit,
): List<SongMenuAction> = listOf(
    SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
        if (LibraryManager.saveSong(context, song).isSuccess) {
            Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
        }
    },
    SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) { onInsertNext(song) },
    SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) { onAppend(song) },
)

/** Compact individual tracks retain their tap and long-press actions. */
@Composable
private fun DailySongTile(song: SongItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    Row(
        modifier = Modifier
            .width(224.dp)
            .appPressScale(pressedScale = AppMotion.BROWSE_PRESS_SCALE)
            .arrisSurface()
            .combinedClickableFallback(onClick, onLongClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeArtwork(song.album?.picUrl, 44.dp)
        Column(Modifier.weight(1f)) {
            MetroText(song.name, color = colors.onBackground,
                style = type.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetroText(song.artists?.joinToString("/") { it.name } ?: LocalStrings.current.unknownArtist,
                color = colors.onSurfaceVariant, style = type.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two rows per shelf card, with the next card peeking in on a phone. */
@Composable
private fun ChartShelf(
    charts: List<PlaylistApi.PlaylistCard>,
    firstSongCovers: Map<Long, String>,
    onOpen: (Long) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val colors = LocalMetroColors.current
    val groups = remember(charts) { charts.chunked(2) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 48.dp).coerceIn(1.dp, 272.dp)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            flingBehavior = rememberMetroFlingBehavior(),
        ) {
            items(groups, key = { it.first().id }) { group ->
                Column(Modifier.width(cardWidth).arrisSurface().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    group.forEachIndexed { index, chart ->
                        if (index > 0) {
                            Spacer(Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                .height(1.dp).background(colors.onBackground.copy(alpha = .06f)))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier.weight(1f).heightIn(min = 64.dp).clip(AppShapes.small)
                                    .clickable(role = Role.Button) { onOpen(chart.id) }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HomeArtwork(firstSongCovers[chart.id] ?: chart.coverUrl, 44.dp)
                                Column(Modifier.weight(1f)) {
                                    MetroText(chart.name, color = colors.onBackground,
                                        style = LocalMetroTypography.current.bodyMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    MetroText(LocalStrings.current.trackCountSongs(chart.trackCount),
                                        color = colors.onSurfaceVariant, style = LocalMetroTypography.current.bodySmall)
                                }
                            }
                            MetroIconButtonSurface(Icons.Default.PlayArrow, LocalStrings.current.playAllButton) {
                                onPlay(chart.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeArtwork(coverUrl: String?, size: Dp) {
    val colors = LocalMetroColors.current
    Box(Modifier.size(size).appCoverFrame(shape = AppShapes.small).background(colors.surfaceVariant),
        contentAlignment = Alignment.Center) {
        MetroIcon(Icons.Default.MusicNote, null, tint = colors.onSurfaceVariant, sizeDp = 22.dp)
        AsyncImage(model = CoverUrls.small(coverUrl), contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * 自适应栅格里的歌曲 tile：方封面 + 歌名 + 歌手，宽度随单元格（fillMaxWidth），
 * 宽屏新歌用它铺多列。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongGridTile(song: SongItem, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val strings = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .appPressScale(pressedScale = AppMotion.BROWSE_PRESS_SCALE)
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
        Spacer(Modifier.height(10.dp))
        MetroText(
            song.name,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        MetroText(
            song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/** 推荐歌单大 tile：160dp 方封面 + 圆播放按钮。 */
@Composable
private fun PlaylistTile(playlist: PlaylistApi.PlaylistCard, onClick: () -> Unit, onPlayAll: () -> Unit) {    val strings = LocalStrings.current
    Column(modifier = Modifier.width(160.dp).appPressScale(pressedScale = AppMotion.BROWSE_PRESS_SCALE).clickable { onClick() }) {
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
        Spacer(Modifier.height(10.dp))
        MetroText(
            playlist.name,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        MetroText(
            strings.trackCountSongs(playlist.trackCount),
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.bodySmall,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/** Keep the radio shortcut distinct from the cover shelves. */
@Composable
private fun FmRadioStrip(
    title: String,
    accent: Color,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    Row(
        modifier = modifier
            .appPressScale(pressedScale = AppMotion.BROWSE_PRESS_SCALE)
            .arrisSurface()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp)
                .background(lerp(colors.surfaceVariant, accent, .14f), AppShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(Icons.Default.GraphicEq, null, tint = colors.onBackground, sizeDp = 22.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MetroText(title, color = colors.onBackground, style = type.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetroText(subtitle, color = colors.onSurfaceVariant, style = type.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MetroIcon(Icons.Default.PlayArrow, null, tint = arrisAccentInk(), sizeDp = 24.dp)
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
        ?.let { (it as? BitmapDrawable)?.bitmap }
        ?: return null
    val palette = Palette.from(bitmap).generate()
    val argb = palette.getVibrantColor(0).takeIf { it != 0 }
        ?: palette.getDominantColor(0).takeIf { it != 0 }
        ?: palette.getMutedColor(0).takeIf { it != 0 }
        ?: return null
    return Color(argb)
}

/** 首页无可展示栏目时提供原因和重试入口。 */
@Composable
private fun HomeDegradedState(
    offline: Boolean,
    title: String,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .arrisSurface().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetroIcon(
            imageVector = if (offline) Icons.Default.CloudOff else Icons.Default.Refresh,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            sizeDp = 36.dp,
        )
        Spacer(Modifier.height(12.dp))
        MetroText(title, color = colors.onBackground, style = type.titleLarge.copy(textAlign = TextAlign.Center))
        Spacer(Modifier.height(16.dp))
        ArrisButton(text = retryLabel, onClick = onRetry)
    }
}

/** v1.5.1 · C：首页转圈的最长陪跑时间（半死网络下不再一路等到 OkHttp 的 30s）。 */
private const val HOME_LOAD_TIMEOUT_MS = 8_000L

private const val LOCAL_HOME_WAIT_MS = 700L

/** 点击 + 长按合并到一个 modifier，避免每个 tile 内部重复样板。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableFallback(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
