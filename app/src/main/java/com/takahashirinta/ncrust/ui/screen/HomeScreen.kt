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
import androidx.compose.material.icons.Icons
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
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.warmup.AppWarmup
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
import com.takahashirinta.ncrust.ui.components.ArrisHomeIdentity
import com.takahashirinta.ncrust.ui.components.ArrisButton
import com.takahashirinta.ncrust.ui.components.MetroIconButtonSurface
import com.takahashirinta.ncrust.ui.components.arrisAccentInk
import com.takahashirinta.ncrust.ui.components.arrisSurface
import com.takahashirinta.ncrust.ui.components.drawArrisGrooves
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.AppShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    var offline by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // E（方案 2 瘦身版）：榜单入口。走 ContentCache 的 15s freshness 窗口 —— warmup/上次
    // 访问刚拉过就不再请求；卡片放在首屏，但请求不阻塞其它分节渲染（拿不到就整块不显示）。
    var toplists by remember { mutableStateOf(ContentCache.toplistItems ?: emptyList()) }
    // 4.0.0：榜单卡用「榜一那首歌」的专辑图当封面（用户认的是歌，不是榜单拼贴图）。
    // 一次 /api/playlist/detail 就够；限制并发 3、只补前 12 个榜单，失败就退回原封面。
    val firstSongCovers = remember { androidx.compose.runtime.mutableStateMapOf<Long, String>() }
    /**
     * H15：一次首页请求**发出时**的会话归属，用来挡住"换号之后才到达"的旧响应。
     *
     * `ContentCache.bindOwner` 只在**换号那一刻**清理已经存在的值，挡不住之后到达的写：
     * A 的三块个性化数据如果在切到 B 之后才回来，就会被写进同一个 ContentCache、
     * 再被 [persistHomeSnapshot] 落盘 —— 用户看到的是 B 的首页上摆着 A 的每日推荐。
     * 判据与 `HomeSnapshot.save` 的提交闸门**同一条**（都走 `CookieManager.accountOwner()`）。
     */
    fun sessionStillAt(startedOwner: String?): Boolean = CookieManager.accountOwner() == startedOwner

    val gridState = rememberLazyGridState()
    // 宽屏：新歌从"整行列表"切到"自适应栅格单元"（Apple Music 式）；手机保持整行列表。
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val coroutineScope = rememberCoroutineScope()
    /**
     * 榜单：与首屏另外三块**同批发出**（v3.7.11）。
     *
     * 旧实现是一个独立的 `LaunchedEffect(Unit)`，它要排在首页首次加载的那个 effect 之后才跑，
     * 而后者当时还在等 800ms 轮询 + 1.2s 预热 —— 于是榜单永远是最后才出现的一块。
     */
    fun loadToplists() {
        if (ContentCache.toplistItems != null && ContentCache.isToplistFresh()) {
            if (toplists.isEmpty()) ContentCache.toplistItems?.let { toplists = it }
            return
        }
        val ownerAtStart = CookieManager.accountOwner()
        if (!NetworkAvailability.isOnline(context)) return
        coroutineScope.launch(Dispatchers.IO) {
            val list = runCatching { PlaylistApi.getToplists() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (!sessionStillAt(ownerAtStart)) return@withContext
                ContentCache.putToplist(list)
                toplists = list
                HomeSnapshot.save(context, toplists = list)
            }
        }
    }

    // 私人 FM 电台卡需要登录用户资料: 昵称(卡标题"xx的电台") + 头像(取强调色做封面)。
    // 电台卡**常驻**——资料拿不到也照常显示(标题回退通用文案), 不再因此整卡消失。
    var fmProfile by remember { mutableStateOf(ContentCache.userProfile) }
    // 头像主色调(Palette), 取不到就回退中性底色(不借用 Arris 主题色)
    var fmAccent by remember { mutableStateOf<Color?>(null) }
    val fmContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // v1.5.1 · C：没网就别去要资料了（电台卡会退回通用标题）。
        if (!NetworkAvailability.isOnline(context)) return@LaunchedEffect
        val started = CookieManager.snapshot()
        val profile = ContentCache.userProfile
            ?: runCatching { PlaylistApi.getUserProfile() }.getOrNull()
        if (profile != null && profile.userId > 0) {
            CookieManager.ifCurrent(started) {
                fmProfile = profile
                ContentCache.userProfile = profile
            }
            val accent = runCatching { extractAvatarAccent(fmContext, profile.avatarUrl) }.getOrNull()
            CookieManager.ifCurrent(started) { if (accent != null) fmAccent = accent }
        }
    }

    /**
     * 4.0.0：给榜单卡补「首曲封面」。
     *
     * 只在内存里缓存（进程级），不落盘：它是纯展示优化，重启后重拉一次的成本很低
     * （每个榜单一次明文 GET）。失败静默 —— 榜单原本的拼贴封面照常显示。
     */
    fun loadChartCovers(charts: List<PlaylistApi.PlaylistCard>) {
        val pending = charts.take(12).filter { firstSongCovers[it.id] == null }
        if (pending.isEmpty() || !NetworkAvailability.isOnline(context)) return
        coroutineScope.launch(Dispatchers.IO) {
            // 分批（每批 3 个）串行推进：首页不要因为补封面把连接池占满（每张一次明文 GET）。
            pending.chunked(3).forEach { chunk: List<PlaylistApi.PlaylistCard> ->
                val fetched = kotlinx.coroutines.coroutineScope {
                    chunk.map { chart ->
                        async {
                            chart.id to runCatching { PlaylistApi.getChartFirstSongCover(chart.id) }.getOrNull()
                        }
                    }.awaitAll()
                }
                withContext(Dispatchers.Main) {
                    fetched.forEach { (id, cover) -> if (cover != null) firstSongCovers[id] = cover }
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
        // H15：归属在**请求发出时**捕获（本函数跑在 Main 上），响应回来时再复核。
        val ownerAtStart = CookieManager.accountOwner()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val list = PlaylistApi.getDailyRecommendSongs()
                withContext(Dispatchers.Main) {
                    // 换过账号 ⇒ 这是上一个会话的推荐，不写内存、不落盘。
                    if (!sessionStillAt(ownerAtStart)) return@withContext
                    dailySongs = list
                    ContentCache.homeDailySongs = list
                    offline = false
                    persistHomeSnapshot()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("DailySongs", "Error", e)
            }
        }
    }

    /**
     * 4.0.0：该换批时自动换（每天 6:00 之后首次进入首页）。
     * 只在"缓存里的日推日 ≠ 当前日推日"时发一次请求，其它时候零开销。
     */
    fun maybeAutoRefreshDaily() {
        if (!com.takahashirinta.ncrust.network.DailyRecommendation.needsRefresh(context)) return
        if (!NetworkAvailability.isOnline(context)) return
        loadDailySongs()
    }
    fun loadPlaylists() {
        val ownerAtStart = CookieManager.accountOwner()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val list = PlaylistApi.getRecommendPlaylists()
                withContext(Dispatchers.Main) {
                    if (!sessionStillAt(ownerAtStart)) return@withContext
                    playlists = list
                    ContentCache.homeRecommendPlaylists = list
                    offline = false
                    persistHomeSnapshot()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    fun loadNewSongs() {
        val ownerAtStart = CookieManager.accountOwner()
        coroutineScope.launch(Dispatchers.IO) {
            if (newSongs.isEmpty()) isLoading = true
            error = null
            try {
                val list = PlaylistApi.getTopSongs(limit = NEW_SONGS_LIMIT, offset = 0)
                withContext(Dispatchers.Main) {
                    if (!sessionStillAt(ownerAtStart)) return@withContext
                    newSongs.clear()
                    newSongs.addAll(list)
                    ContentCache.homeNewSongs = list.toList()
                    isLoading = false
                    offline = false
                    persistHomeSnapshot()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        if (!NetworkAvailability.isOnline(context)) {
            offline = true
            isLoading = false
            return
        }
        offline = false
        isLoading = true
        // 4.0.0：日推按天换批 —— 该换才发请求，否则直接用缓存，避免每次进首页白打一次接口。
        maybeAutoRefreshDaily()
        if (!com.takahashirinta.ncrust.network.DailyRecommendation.needsRefresh(context) &&
            dailySongs.isEmpty()
        ) {
            // 缓存是今天这一批但内存里还没内容（如刚清过缓存）：正常拉一次，不标记为"换批"。
            loadDailySongs()
        }
        loadPlaylists()
        loadNewSongs()
        val ownerAtStart = CookieManager.accountOwner()
        coroutineScope.launch(Dispatchers.IO) {
            runCatching { PlaylistApi.getToplists() }.getOrNull()?.let {
                if (!sessionStillAt(ownerAtStart)) return@launch
                ContentCache.putToplist(it)
                withContext(Dispatchers.Main) { toplists = it }
                HomeSnapshot.save(context, toplists = it)
            }
        }
    }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // AppWarmup 刚预取过同样三个接口时直接复用缓存，避免冷启动重复网络/耗电。
        // S14：新鲜度必须属于当前账号，否则换账号后会拿旧推荐跳过加载。
        val owner = CookieManager.accountOwner()
        if (ContentCache.isHomeFreshFor(owner)) return@LaunchedEffect
        // v1.5.1 · C 冷启动竞态：AppWarmup 把磁盘快照灌回 ContentCache 是异步的
        // （IO 线程 + JSON 解析），这里最多等 800ms 再接后面的判断，免得首帧先闪一个
        // v3.7.11：这里曾经有两段**白等**：先轮询 800ms 等 ContentCache 被填，
        // 再 `withTimeoutOrNull(1200ms)` 等 AppWarmup 收尾。冷启动时用户看到的是
        // 「转圈近两秒，然后只有推荐歌单和新歌速递，每日推荐与榜单过一会才冒出来」。
        //
        // 现在改成「能拿到就用、拿不到立刻自己发」，且**四块并发**：
        //  ① 有缓存（磁盘快照 / 预热已写入）⇒ 直接显示；
        //  ② 预热还在飞 ⇒ await 它的在途请求（同一批网络请求，不重复）；
        //  ③ 预热没跑 / 已收尾 ⇒ 自己发。
        ContentCache.homeDailySongs?.let { if (dailySongs.isEmpty()) dailySongs = it }
        ContentCache.homeRecommendPlaylists?.let { if (playlists.isEmpty()) playlists = it }
        ContentCache.homeNewSongs?.let { cached -> if (newSongs.isEmpty()) newSongs.addAll(cached) }
        if (toplists.isEmpty()) ContentCache.toplistItems?.let { toplists = it }

        val inFlight = AppWarmup.homeLoads
        if (inFlight != null && inFlight.isCurrent() && inFlight.owner == owner && !ContentCache.isHomeFreshFor(owner)) {
            // 只 await 很短一段时间：预热那三条本来就已经发出去了，通常几十~几百毫秒内就回来。
            // 超时也不空等 —— 下面照样自己发，用户不会对着转圈。
            val arrived = withTimeoutOrNull(WARMUP_AWAIT_MS) {
                val daily = runCatching { inFlight.daily.await() }.getOrNull()
                val pls = runCatching { inFlight.playlists.await() }.getOrNull()
                val top = runCatching { inFlight.newSongs.await() }.getOrNull()
                Triple(daily, pls, top)
            }
            arrived?.takeIf { inFlight.isCurrent() }?.let { (daily, pls, top) ->
                daily?.let { if (it.isNotEmpty() && sessionStillAt(owner)) dailySongs = it }
                pls?.let { if (it.isNotEmpty() && sessionStillAt(owner)) playlists = it }
                top?.let { list ->
                    if (list.isNotEmpty() && sessionStillAt(owner) && newSongs.isEmpty()) {
                        newSongs.addAll(list)
                    }
                }
            }
            if (dailySongs.isNotEmpty() && playlists.isNotEmpty() && newSongs.isNotEmpty()) {
                isLoading = false
                loadToplists()
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
        // 每日推荐 + 推荐歌单 + 新歌速递 + 榜单**同时发出**，谁先回来谁先上图。
        loadDailySongs()
        loadPlaylists()
        loadNewSongs()
        loadToplists()
    }


    // v1.5.1 · C：半死网络（连上了但实际不通）下请求最长会挂到 connectTimeout。
    // 转圈最多陪跑 [HOME_LOAD_TIMEOUT_MS]：到点撤掉转圈、给空态 + 重试，
    // 至少让用户知道发生了什么，而不是对着一个永远转的圈。
    LaunchedEffect(active, isLoading) {
        if (!active || !isLoading) return@LaunchedEffect
        delay(HOME_LOAD_TIMEOUT_MS)
        isLoading = false
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

    val hasContent = dailySongs.isNotEmpty() || playlists.isNotEmpty() ||
        newSongs.isNotEmpty() || toplists.isNotEmpty()
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
                                hint = strings.networkOfflineHint.takeIf { offline },
                                retryLabel = strings.retry,
                                onRetry = { retryLoad() },
                            )
                        }
                        return@LazyVerticalGrid
                    }

                    if (dailySongs.isNotEmpty()) {
                        item(key = "home-daily-feature", span = { GridItemSpan(maxLineSpan) }) {
                            DailyMixFeature(
                                title = strings.dailySongsTitle,
                                detail = if (sessionValid) strings.trackCountSongs(dailySongs.size)
                                    else strings.dailySongsLoginHint,
                                coverUrl = dailySongs.firstNotNullOfOrNull { song ->
                                    song.album?.picUrl?.takeIf { it.isNotBlank() }
                                },
                                coverSize = if (isWide) 120.dp else 94.dp,
                                onPlayAll = onPlayDailyAll?.let { play -> { play(dailySongs) } },
                            )
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

                    if (onPlayFm != null) {
                        item(key = "home-radio", span = { GridItemSpan(maxLineSpan) }) {
                            FmRadioStrip(
                                title = fmProfile?.nickname?.takeIf { it.isNotEmpty() }
                                    ?.let { strings.fmRadioTitle(it) } ?: strings.fmRadioTitleGeneric,
                                accent = fmAccent ?: LocalMetroColors.current.primary,
                                subtitle = strings.fmRadioSubtitle,
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
    onPlayAll: (() -> Unit)?,
) {
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .arrisSurface(emphasized = true)
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
            MetroText(title, color = colors.onBackground, style = type.titleLarge)
            MetroText(detail, color = colors.onSurfaceVariant, style = type.bodySmall)
            if (onPlayAll != null) {
                Spacer(Modifier.height(2.dp))
                ArrisButton(text = LocalStrings.current.playAllButton, onClick = onPlayAll)
            }
        }
        HomeArtwork(coverUrl, coverSize)
    }
}

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
    onClick: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
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
    hint: String?,
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
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            MetroText(hint, color = colors.onSurfaceVariant,
                style = type.bodySmall.copy(textAlign = TextAlign.Center))
        }
        Spacer(Modifier.height(16.dp))
        ArrisButton(text = retryLabel, onClick = onRetry)
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

/**
 * await 预热的在途请求最多等多久（v3.7.11）。
 *
 * 这三条请求在预热阶段**已经发出**，正常几十~几百毫秒就回；等 700ms 还没回说明网络慢，
 * 与其继续空转，不如照旧自己发（最坏只是慢网下多一次请求，用户看到的却是"立刻有内容"）。
 */
private const val WARMUP_AWAIT_MS = 700L

/** 点击 + 长按合并到一个 modifier，避免每个 tile 内部重复样板。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableFallback(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
