/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug2「我喜欢的歌无法全部加入播放列表」）：
 *   - ④ 收藏单曲列表新增「播放全部」入口（复用 PlayAllDialog 二次确认）。
 *     回调由上层补齐**整个**红心歌单，而不是当前已分页加载的部分。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.account.SessionMonitor
import com.takahashirinta.ncrust.account.SessionState
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.defaultPlaylistName
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.library.LibrarySongSearch
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.controls.MetroTextField
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.ArrisSegmentedTabs
import com.takahashirinta.ncrust.ui.components.AlbumSourceFilterRow
import com.takahashirinta.ncrust.ui.components.AlbumSourceTag
import com.takahashirinta.ncrust.ui.components.ArrisButton
import com.takahashirinta.ncrust.ui.components.collapsingHeader
import com.takahashirinta.ncrust.ui.components.collapsingHeaderScroll
import com.takahashirinta.ncrust.ui.components.rememberCollapsingHeaderState
import com.takahashirinta.ncrust.ui.components.CreatePlaylistDialog
import com.takahashirinta.ncrust.ui.components.PlayAllButton
import com.takahashirinta.ncrust.ui.components.SessionExpiredBanner
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import com.takahashirinta.ncrust.ui.components.SourceFilter
import com.takahashirinta.ncrust.ui.components.PlaylistCreateOutcome
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.components.appPressScale
import com.takahashirinta.ncrust.ui.components.listItemAppear
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import kotlinx.coroutines.launch
import android.widget.Toast

@Composable
fun LibraryScreen(
    // v3.8.0：库页顶部两个功能图标（听歌识曲 / 一起听）
    onOpenRecognize: () -> Unit = {},
    onOpenTogether: () -> Unit = {},
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onPlaylistClick: (PlaylistApi.PlaylistInfo) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    // Bug2-④：收藏单曲「播放全部」。由上层 loadAllLikedSongsResult 覆盖整个红心歌单。
    onPlayAllLiked: () -> Unit = {},
    // v3.4.12：整列收藏的「插播 / 追加」入口与后台补齐的 loading 反馈。
    // 播放全部仍是**先播后补**（点击即出声），不弹二次确认。
    onInsertAllLiked: () -> Unit = {},
    onAppendAllLiked: () -> Unit = {},
    likedBulkLoading: Boolean = false,
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    // v2.3.0 · A/B：本地歌单的失效信号（见下面的 LaunchedEffect）。
    localPlaylistsTick: Int = 0,
    // v2.3.0 · A：QQ 歌单**不再走二级入口**，列表直接平铺在「歌单」tab 里。
    // 这个回调改成「点了某一张 QQ 歌单」。
    onQqPlaylistClick: (com.takahashirinta.ncrust.source.Playlist) -> Unit = {},
    // v2.3.0 · B：进入某个本地歌单（可编辑）。
    onLocalPlaylistClick: (com.takahashirinta.ncrust.source.PlaylistKey) -> Unit = {},
    refreshTrigger: Int = 0,
    /**
     * v3.3.0 · 用户建议：离线 tab 要知道「哪一首正在播」—— 那一行会锁住删除
     * （删掉正在读的 span 会让 CacheDataSource 回源，断网即播放错误）。
     * 默认 -1 = 没有在播，既有调用点因此零改动即可编译。
     */
    currentSongId: Long = -1L,
    // 会话失效横幅点击 → 走现有登录流程（窄屏网页登录 / 宽屏扫码）。
    onRelogin: () -> Unit = {},
    active: Boolean = true,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current
    // v3.9.0 · 用户建议：库页顶部那一整块表头（页头 / 听歌识曲 / 一起听 / 会话横幅 / 分类条）
    // 要能随列表上滑收起，把高度让给列表（原话：「歌曲占得地方太小了」）。
    //
    // 状态放在页面级而不是某个 tab 里：`selectedCategory` 切换时四个 tab 会被 AnimatedContent
    // 卸载重建，写在分支里的 remember 会让「切去歌单看一眼再切回来，表头又弹回展开」。
    // 它同时也是**跨 tab 共享**的：用户滚到某处切 tab，表头不应该跳回去。
    val headerState = rememberCollapsingHeaderState()
    // 会话失效时顶部一条横幅：收藏刷不出来/只给试听的直接原因就在这里。
    // 必须 collectAsState 订阅：直接读 StateFlow.value 不会建立快照依赖，横幅永远不刷新。
    val sessionState by SessionMonitor.state.collectAsState()
    val sessionExpired = sessionState is SessionState.Expired

    var savedSongs by remember { mutableStateOf(LibraryManager.getSavedSongs(context)) }
    var savedAlbums by remember { mutableStateOf(LibraryManager.getSavedAlbums(context)) }
    // 红心歌单总数（含尚未分页加载的部分），供「播放全部」入口显示规模。
    // 云端红心总数与本地可见条目取大者：本地手动收藏的 QQ / B 站不在红心 id 表里，
    // 只显示云端数会让「整列播放」的规模看起来漏了它们。
    var likedTotal by remember { mutableIntStateOf(
        maxOf(LibraryManager.getLikedSongIds(context).size, savedSongs.size)
    ) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    // 库页「单曲」tab 的搜索关键词。
    //
    // 与下面的 `albumSourceFilter` 同一条理由：状态提到页面级，而不是写在 `0 ->` 分支里 ——
    // `AnimatedContent` 会把切走的那一支卸载，写在分支里的 `remember` 会随切 tab 重置，
    // 于是「切去歌单看一眼再切回来」用户刚打的关键词就没了（列表还突然变回全部）。
    // 它同样是纯 UI 状态：本地过滤，不发请求，不落盘。
    var songQuery by remember { mutableStateOf("") }
    // v3.9.0 · 用户建议：「单曲那个建议下拉可以加载更多，类似搜索里的操作」。
    //
    // 「下一批正在飞」的标记，同样提到页面级：`0 ->` 分支会被 AnimatedContent 卸载，
    // 写在分支里的 remember 会在切 tab 后归零 —— 请求还在飞、按钮却变回「加载更多」，
    // 用户再点一下就会排在 `likedPageLock` 后多拉一批（不会重复入库，但白拉一次网络）。
    var loadingMore by remember { mutableStateOf(false) }
    // v3.4.0：专辑 tab 的**音源筛选档**（全部 / 只看 ncm / 只看 qm）。
    //
    // 状态提升到页面级（而不是塞在 `2 ->` 分支里）的理由：`when (selectedCategory)`
    // 外面是 `AnimatedContent`，切到别的 tab 会把那一支**卸载**，写在分支里的
    // `remember` 会在切回时被重置 —— 用户「切走看一眼再切回来，筛选还是刚才那档」
    // 是应该成立的。它是纯 UI 状态（本地过滤，不发请求，不落盘），
    // 与 `playlistLayout` / `sectionFold` 那两个「必须落盘」的偏好不是一类东西。
    var albumSourceFilter by remember { mutableStateOf(SourceFilter.ALL) }
    // v2.3.0 · A：**分类顺序改成 单曲 / 歌单 / 专辑**（用户拍板，任务书 3.2）。
    // v2.2.1 及以前是 单曲 / 专辑 / 歌单。改顺序不是「换个 position」那么简单：
    // 下面所有 `selectedCategory == N` 的判定都必须跟着换（本版一次性改完，
    // 并在 LibraryPlaylistsTab 的 KDoc 里记下三个下标）。
    // v3.3.0 · 用户建议：**离线**作为库页的一等 tab。
    //
    // 追加在末尾（不插队）是有意的：所有 `selectedCategory == N` 的判定都按
    // 「0=单曲 / 1=歌单 / 2=专辑」写死，插队会让它们**静默指错页**
    // （与统计页追加成第 5 个 tab 同一条理由）。
    val categories = listOf(
        strings.categoryTracks,
        strings.categoryPlaylists,
        strings.categoryAlbums,
        strings.categoryOffline,
    )

    var playlists by remember { mutableStateOf<List<PlaylistApi.PlaylistInfo>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    // v2.3.0 · B：本地歌单（可编辑、只加不减 + tombstone）。它**不依赖任何网络状态** ——
    // 本地歌单存在的意义之一就是「ncm/QQ 都挂了也能看到自己的歌单」。
    var localPlaylists by remember { mutableStateOf(LocalPlaylistStore.readPlaylists(context)) }
    var showCreateLocal by remember { mutableStateOf(false) }
    // v2.6.0 · P1/P2：布局模式 + 三个区块的折叠状态。两者的真源都是
    // `ncrust_settings`（见 PlaylistLayoutSetting / LibrarySectionFoldSetting 的 KDoc）。
    //
    // **初值从 prefs 读、而不是写死默认值**：这一页被 MainScreen 的
    // 页面切换反复卸载重建，写死初值会让「重启后保持」变成「每次重建都回到默认」。
    // 读一次 prefs 是主线程 IO，但它是三个布尔 + 一个 Int（SharedPreferences 有内存缓存，
    // 首次加载后不再触盘），与同页已有的 LocalPlaylistStore.readPlaylists 同级。
    var playlistLayout by remember { mutableStateOf(PlaylistLayoutSetting.read(context)) }
    var sectionFold by remember { mutableStateOf(LibrarySectionFoldSetting.read(context)) }
    // v1.3.0 · B4：收藏页的「新建歌单」入口（建空歌单），与全屏播放器的「保存队列为歌单」共用对话框。
    var showCreatePlaylist by remember { mutableStateOf(false) }
    fun loadPlaylists() {
        coroutineScope.launch {
            isLoadingPlaylists = true
            playlistError = null
            try {
                if (!CookieManager.hasCookie(context)) {
                    playlistError = strings.notLoggedInForPlaylists
                } else {
                    val started = CookieManager.snapshot()
                    val userId = PlaylistApi.getCurrentUserId()
                    val result = PlaylistApi.getAllUserPlaylists(userId, started)
                    val live = CookieManager.snapshot()
                    if (live.loginGeneration == started.loginGeneration) CookieManager.ifCurrent(live) { playlists = result }
                }
            } catch (e: Exception) {
                playlistError = if (!CookieManager.hasCookie(context))
                    strings.notLoggedInForPlaylists
                else strings.loadFailed(e.message)
            } finally {
                isLoadingPlaylists = false
            }
        }
    }

    fun reloadLocal() {
        val songs = LibraryManager.getSavedSongs(context)
        savedSongs = songs
        savedAlbums = LibraryManager.getSavedAlbums(context)
        likedTotal = maxOf(
            LibraryManager.getLikedSongIds(context).size,
            songs.size,
        )
        localPlaylists = LocalPlaylistStore.readPlaylists(context)
    }

    LaunchedEffect(active, selectedCategory) {
        if (!active) return@LaunchedEffect
        reloadLocal()
        // 歌单 tab 现在是下标 1（v2.3.0 · A 的顺序调整）。
        if (selectedCategory == 1 && playlists.isEmpty() && !isLoadingPlaylists) {
            loadPlaylists()
        }
    }

    // 翻页预览只读本地内容。选中库页或在库页完成登录后，再刷新云端。
    LaunchedEffect(active, refreshTrigger) {
        if (!active) return@LaunchedEffect
        LibraryManager.refreshFromCloud(context)
        reloadLocal()
    }

    // v2.3.0 · B：本地歌单不是可观察数据源（SharedPreferences），所以由上层用一个
    // 递增的信号告诉本页「它变了，重读一次」。从歌曲菜单里加过歌之后必须重读，
    // 否则用户回到库页看到的还是加之前的样子。
    LaunchedEffect(localPlaylistsTick) {
        if (localPlaylistsTick > 0) localPlaylists = LocalPlaylistStore.readPlaylists(context)
    }

    // 收藏单曲的列表状态。分页**不再**由「滚到末尾」自动触发（见下面 loadMore 的注释），
    // 但 state 仍要提升到这里：`0 ->` 分支会被 AnimatedContent 卸载重建，
    // 写在分支里的 remember 会让「切去歌单看一眼再切回来」滚动位置归零。
    val songListState = rememberLazyListState()

    if (showCreatePlaylist) {
        val createStrings = strings
        CreatePlaylistDialog(
            defaultName = defaultPlaylistName(),
            songCount = 0,
            onDismiss = { showCreatePlaylist = false },
            onCreate = { name, privacy ->
                if (!CookieManager.hasCookie(context)) {
                    PlaylistCreateOutcome.FAILED
                } else {
                    val id = PlaylistEditApi.createPlaylist(name, privacy, CookieManager.snapshot())
                    when {
                        id != null -> {
                            Toast.makeText(context, createStrings.playlistCreated(name), Toast.LENGTH_SHORT).show()
                            loadPlaylists()
                            PlaylistCreateOutcome.SUCCESS
                        }
                        PlaylistEditApi.lastError is PlaylistWriteResult.RateLimited ->
                            PlaylistCreateOutcome.RATE_LIMITED
                        else -> PlaylistCreateOutcome.FAILED
                    }
                }
            }
        )
    }

    // v2.3.0 · B：新建**本地**歌单。与「新建 ncm 歌单」是两个不同的对话框 ——
    // 前者只写本地 prefs，后者会发远程写请求（`PlaylistEditApi.createPlaylist`）。
    // 按钮文案与落点都不同，合成一个对话框会让用户以为是同一件事。
    if (showCreateLocal) {
        LocalPlaylistCreateDialog(
            onDismiss = { showCreateLocal = false },
            onCreate = { name ->
                // H09：createLocalOnly 现在会在写盘失败时返回 null。丢掉返回值就会
                // "没建出来却弹已创建"，用户回头找不到这个歌单。
                val created = LocalPlaylistRepository.createLocalOnly(context, name)
                localPlaylists = LocalPlaylistStore.readPlaylists(context)
                if (created != null) {
                    showCreateLocal = false
                    Toast.makeText(context, strings.localPlaylistCreated(name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, strings.localPlaylistSyncFailed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // v3.9.0：`collapsingHeaderScroll` 挂在**整页最外层**。它只在滚动位移里消费「表头那一段」，
    // 点按（tab / 快捷入口 / 列表行）根本不经过嵌套滚动，所以不会吃掉点击。
    Box(modifier = Modifier.fillMaxSize().collapsingHeaderScroll(headerState)) {
    ResponsiveContent {
        // 背景由 MainScreen 外层 Box 统一填充，此处不重复画一层
        //
        // `statusBarsPadding()` 从「页头」上移到**整列**（v3.9.0）。理由：表头收起到底时页面最上面
        // 就是列表，若留白仍挂在页头身上，它会跟着表头一起滑走 —— 列表第一行会钻到状态栏底下。
        // 挂在整列上 ⇒ 展开态与改动前**逐像素相同**（页头本来就是这一列的第一个子节点），
        // 收起态列表也从状态栏下沿开始。
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ── 可收起表头（v3.9.0）────────────────────────────────────────────────
            // 整块一起上滑滑出、被裁掉；收起的只是它的**布局高度**，子节点尺寸与内部排版不变，
            // 所以 tab 条是完整地滑走的，不会被拦腰截断（机制见 CollapsingHeader.kt 的 KDoc）。
            // 多包一层 Column 不引入任何间距（Column 默认无间隔），四个子节点的排布与改动前一致。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .collapsingHeader(headerState)
            ) {
                // Groove 风大字页头。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        MetroText(
                            strings.tabLibrary,
                            color = LocalMetroColors.current.onBackground,
                            style = LocalMetroTypography.current.pageHeading.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    val chinese = com.takahashirinta.ncrust.ui.i18n.currentLanguageCode().startsWith("zh")
                    com.takahashirinta.ncrust.ui.components.ArrisShortcut(
                        if (chinese) "听歌识曲" else "Recognize",
                        if (chinese) "留住偶然的旋律" else "Find that melody",
                        androidx.compose.material.icons.Icons.Default.Mic, Modifier.weight(1f), onOpenRecognize)
                    com.takahashirinta.ncrust.ui.components.ArrisShortcut(
                        if (chinese) "一起听" else "Listen together",
                        if (chinese) "分享同一个时刻" else "Share this moment",
                        androidx.compose.material.icons.Icons.Default.People, Modifier.weight(1f), onOpenTogether)
                }
                Spacer(Modifier.height(4.dp))
                // 条件挂载 ⇒ 表头高度会变。高度变化由 `CollapsingHeaderState.onMeasured` 兜住：
                // 收到底时会跟着新高度重新贴底（不会在列表顶上露一条 tab 条的下边缘），
                // 反过来（横幅摘掉、表头变矮）则每帧把收起量夹回新区间。
                if (sessionExpired) {
                    SessionExpiredBanner(onRelogin = onRelogin)
                    Spacer(Modifier.height(4.dp))
                }
                ArrisSegmentedTabs(
                    selected = selectedCategory,
                    items = categories,
                    onSelect = { index -> selectedCategory = index },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // 内容区不参与收起：表头变矮后它自然拿到更多高度（`fillMaxSize` 在 Column 里
            // 拿的就是**剩余**高度，等价于 weight(1f)），所以这里既不加 padding 也不加 offset。

            // Tab 切换用 AnimatedContent 做方向感知的横向滑入。
            // 距离 1/16 屏宽（比 NavGraph 的 1/8 更轻——同页内切 tab，不应有"翻页"的重量感）；
            // 200ms MetroDefault 曲线。向右切（index 增）新页从右入、旧页向左出；向左切反之。
            AnimatedContent(
                targetState = selectedCategory,
                transitionSpec = {
                    val goingRight = targetState > initialState
                    val enterSign = if (goingRight) 1 else -1
                    val exitSign = if (goingRight) -1 else 1
                    (slideInHorizontally(
                        animationSpec = tween(200, easing = MetroDefault),
                        initialOffsetX = { fullWidth -> enterSign * fullWidth / 16 }
                    ) + fadeIn(animationSpec = tween(160, easing = MetroDefault))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(200, easing = MetroDefault),
                        targetOffsetX = { fullWidth -> exitSign * fullWidth / 16 }
                    ) + fadeOut(animationSpec = tween(160, easing = MetroDefault)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "LibraryCategoryContent"
            ) { category -> when (category) {
                0 -> {
                    // 库页「单曲」tab 的本地搜索：**只过滤已加载的 savedSongs，不发任何请求**
                    // （纯逻辑与匹配规则见 LibrarySongSearch 的 KDoc）。
                    //
                    // visibleSongs 用 remember 缓存：几百条 × 每个词 3 段 contains 不必每帧重算，
                    // 只有 savedSongs / songQuery 变化时才重来一次。
                    val visibleSongs = remember(savedSongs, songQuery) {
                        LibrarySongSearch.match(savedSongs, songQuery)
                    }
                    // 判空走同一个 keyword()：UI 里再写一份 trim 迟早漂移成
                    // 「输入框看起来在搜索、结果却按未搜索处理」。
                    val hasKeyword = LibrarySongSearch.keyword(songQuery).isNotEmpty()
                    // 「已载 N / 总数 M」：红心歌单是分页拉下来的，likedTotal（云端总数）可能大于
                    // 已加载的 savedSongs.size，此时搜索只能覆盖已加载的那一段。这一行**只说明范围**，
                    // 不触发任何补齐请求 —— 补齐是列表末尾那个「加载更多」按钮的事（v3.9.0 起由用户
                    // 自己点，不再自动拉；搜索中那个按钮会摘掉，见该 item 的注释）。
                    //
                    // 文案用两段**既有**计数拼出，不新增第三条文案：它只是范围提示，
                    // 而每加一条文案都要在 8 个语言文件里各写一遍。
                    val scopeCountText = if (likedTotal > savedSongs.size) {
                        strings.searchSourceCount(savedSongs.size) +
                            " / " +
                            strings.searchSourceTotal(likedTotal)
                    } else {
                        null
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        // v3.9.0 · 用户原话里点名了「搜索框」：它不再固定在列表之外，而是
                        // 「单曲」列表的**第一项**，跟着列表一起滚走。不这么做的话，表头收起之后
                        // 它仍然独占一条高度，「歌曲占的地方太小」只解决了一半。
                        //
                        // 为什么是「列表第一项」而不是「提到表头」：这个输入框只对**单曲**有意义
                        // （它过滤的是 savedSongs）。提升到表头会让歌单 / 专辑 / 离线三个 tab 上
                        // 也挂一个输了没反应的输入框。
                        //
                        // 两档空态**不搬家**（没有列表可挂，输入框必须留在空态 Box 之上）：
                        // · 「这次没搜到」那一档必须留着 —— 否则用户没有任何入口清掉关键词，
                        //   空态变成死胡同。这正是 v3.2.0 搜索页踩过的坑：筛选行用
                        //   「筛选后的结果」决定自己是否可见，因果反了，点进去就再也出不来；
                        // · 「库里本来没有歌」那一档也留着 —— savedSongs 由后台刷新重读，
                        //   输关键词期间它可能被刷成空（换账号 / 云端清空），那时同样
                        //   只有这个输入框能把人放出去。
                        if (savedSongs.isEmpty()) {
                            LibrarySearchFieldBlock(
                                value = songQuery,
                                onValueChange = { songQuery = it },
                                placeholder = strings.search.librarySearchHint,
                                countText = scopeCountText,
                            )
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                MetroText(strings.noSavedSongs, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                            }
                        } else if (visibleSongs.isEmpty()) {
                            // 有歌但没命中：这句只描述「这次搜索」，与「库里没有歌」分开 ——
                            // 前者用户的下一步是改关键词，后者是去别处收藏。
                            LibrarySearchFieldBlock(
                                value = songQuery,
                                onValueChange = { songQuery = it },
                                placeholder = strings.search.librarySearchHint,
                                countText = scopeCountText,
                            )
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                MetroText(
                                    strings.search.librarySearchEmpty,
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = LocalMetroTypography.current.bodyLarge,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                state = songListState,
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                // v3.9.0：搜索框 + 范围计数行作为列表的前两项。
                                // `songQuery` 是**页面级** state（见它自己的 KDoc），
                                // 所以 item 被 LazyColumn 回收、滚出屏幕再滚回来时文字还在；
                                // 焦点不保证保留（item 被回收 ⇒ 输入框节点离开组合 ⇒ 焦点清掉、
                                // 键盘收起）—— 这是把输入框放进列表的固有代价，换来的是它能滚走。
                                item(key = "library-search") {
                                    LibrarySearchFieldBlock(
                                        value = songQuery,
                                        onValueChange = { songQuery = it },
                                        placeholder = strings.search.librarySearchHint,
                                        countText = scopeCountText,
                                    )
                                }
                                // Bug2-④：整张红心歌单的「播放全部」入口。
                                // 未搜索时显示的是 total（云端数量）而非 savedSongs.size（已加载数量），
                                // 避免用户以为"我喜欢的歌"只有已翻页的那几十首。
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        MetroText(
                                            // 搜索中显示**命中数**：用户此刻问的是「我搜到几首」，
                                            // 仍显示云端总数（273）而列表只有 3 行会像是坏了。
                                            // 未搜索时保持 likedTotal —— 它描述的是红心歌单规模，
                                            // 与 savedSongs.size（已翻页加载的部分）不是同一件事。
                                            if (hasKeyword) strings.trackCountSongs(visibleSongs.size)
                                            else strings.trackCountSongs(likedTotal),
                                            color = LocalMetroColors.current.onSurfaceVariant,
                                            style = LocalMetroTypography.current.bodySmall
                                        )
                                        if (likedBulkLoading) {
                                            Spacer(Modifier.width(8.dp))
                                            MetroText(
                                                strings.likedPlayAllLoading(likedTotal),
                                                color = LocalMetroColors.current.primary,
                                                style = LocalMetroTypography.current.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        PlayAllButton(onClick = {
                                            // 后台补齐期间不重复触发；播放本身已经在第一次点击时开始。
                                            if (!likedBulkLoading) onPlayAllLiked()
                                        })
                                        // v3.4.12：旁边的「插播 / 追加」入口。单独放到菜单里而不是
                                        // 再摆两个按钮：这一行同时要放计数与 loading 文案，320dp 宽
                                        // 再塞两个按钮必然把歌名/计数挤没。
                                        Box {
                                            var showBulkMenu by remember { mutableStateOf(false) }
                                            MetroIconButton(onClick = { showBulkMenu = true }) {
                                                MetroIcon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = strings.actionAppendToQueue,
                                                    tint = LocalMetroColors.current.onBackground,
                                                    sizeDp = 22.dp
                                                )
                                            }
                                            MetroDropdownMenu(
                                                expanded = showBulkMenu,
                                                onDismissRequest = { showBulkMenu = false },
                                                containerColor = LocalMetroColors.current.surface,
                                            ) {
                                                MetroDropdownMenuItem(
                                                    text = strings.insertNextTitle,
                                                    textColor = LocalMetroColors.current.onBackground,
                                                    onClick = {
                                                        showBulkMenu = false
                                                        if (!likedBulkLoading) onInsertAllLiked()
                                                    },
                                                )
                                                MetroDropdownMenuItem(
                                                    text = strings.actionAppendToQueue,
                                                    textColor = LocalMetroColors.current.onBackground,
                                                    onClick = {
                                                        showBulkMenu = false
                                                        if (!likedBulkLoading) onAppendAllLiked()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                // v2.5.0 · A：列表入场（淡入 + 上滑）。items → itemsIndexed 只为拿下标，
                                // key 显式传同一条（`it.id`）⇒ LazyColumn 的 diff 行为不变。
                                // 搜索只改**画什么**、不改条目身份：key 仍是 song.id，
                                // LazyColumn 的 diff 行为与过滤前完全一致。
                                itemsIndexed(visibleSongs, key = { _, song -> song.id }) { index, song ->
                                    SongCard(
                                        song = song,
                                        style = SongCardStyle.LIST,
                                        modifier = Modifier.listItemAppear(index),
                                        onClick = { onSongClick(song) },
                                        onShowMenu = {
                                            onShowSongMenu(song, listOf(
                                                SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                                    // v2.6.0 · P0：成败由返回值决定（这一页本来就在库里，多数是幂等成功）。
                                                    if (LibraryManager.saveSong(context, song).isSuccess) {
                                                        Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                                    onSongInsertNext(song)
                                                },
                                                SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                                    onSongAppendToQueue(song)
                                                },
                                                SongMenuAction(Icons.Default.Delete, strings.actionRemoveFromLibrary) {
                                                    LibraryManager.removeSong(context, song.id)
                                                    savedSongs = LibraryManager.getSavedSongs(context)
                                                    savedAlbums = LibraryManager.getSavedAlbums(context)
                                                }
                                            ))
                                        }
                                    )
                                }
                                // v3.9.0 · 用户建议：「单曲那个建议下拉可以加载更多，类似搜索里的操作」。
                                //
                                // 把原来的「滚到末尾自动拉下一批」换成**列表末尾一个显式按钮**。
                                // 理由是搜索页那条已经写明过的（`SearchStrings.searchLoadMore` 的 KDoc）：
                                // 自动加载在「结果不足一屏」时判据恒真 —— 列表末尾永远「接近末尾」，
                                // 于是打开库页就会把后面每一页都拉下来。用户要的不是「替我拉完」，
                                // 而是一个能自己决定的出口。
                                //
                                // 挂载条件（三个都必要）：
                                // ① `!hasKeyword`：搜索态下列表是「**已加载集合**」的子集，翻页只影响
                                //    未过滤的那份集合。底部再放一个「加载更多」，用户会以为搜索能翻页
                                //    —— 点了半天结果还是那几条，看起来就是坏的；
                                // ② `savedSongs.size < likedTotal`：还有没拉的（红心总数含尚未分页的部分）；
                                // ③ 作为 LazyColumn 的最后一个 item，不改 tab 的既有结构。
                                if (!hasKeyword && savedSongs.size < likedTotal) {
                                    item(key = "load-more") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                                // 两种状态同高，避免「加载中」从按钮换成文字时列表抖一下。
                                                .heightIn(min = 50.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (loadingMore) {
                                                // 加载中换成纯文字（与搜索页 `SearchLoadMoreRow` 同形），
                                                // 而不是留一个「看起来能点、其实点不动」的禁用按钮。
                                                MetroText(
                                                    strings.searchLoadingMore,
                                                    color = LocalMetroColors.current.onSurfaceVariant,
                                                    style = LocalMetroTypography.current.bodySmall,
                                                )
                                            } else {
                                                ArrisButton(
                                                    strings.searchLoadMore,
                                                    // 次要样式：它是列表的出口，不是这一页的主操作
                                                    // （主操作是「播放全部」，它在列表第一行）。
                                                    primary = false,
                                                    onClick = {
                                                        loadingMore = true
                                                        coroutineScope.launch {
                                                            try {
                                                                // 一次一批（`loadMoreLikedSongs` 内部有
                                                                // `likedPageLock` 与游标，幂等且串行）。
                                                                // 它返回的是**追加后的完整可见列表**，
                                                                // 不是新增那一段，所以直接整体回写。
                                                                val more =
                                                                    LibraryManager.loadMoreLikedSongs(context)
                                                                // 空返回 = 这次没拉到（网络失败或服务端已无更多）。
                                                                // **不提示、不自动重试**，只把按钮复位：
                                                                // · 没有可读的失败原因 —— `loadMoreLikedSongs`
                                                                //   把 API 失败吞成 emptyList，拿 `strings.loadFailed(null)`
                                                                //   会弹「加载失败：null」，那是假信息；
                                                                // · 想再试的人自己再点一次即可（手动重试，天然不会死循环）；
                                                                // · 真没更多了也不会有副作用：下一次 `savedSongs` 变化时
                                                                //   `savedSongs.size < likedTotal` 自己就不再成立。
                                                                if (more.isNotEmpty()) savedSongs = more
                                                            } finally {
                                                                // try/finally 而不是只在成功分支复位：
                                                                // 协程被取消（离开库页）时也必须把标记清掉，
                                                                // 否则下次进来按钮永远是「正在加载」。
                                                                loadingMore = false
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // v2.3.0 · A：歌单 tab —— **单曲 / 歌单 / 专辑** 里的第二个。
                    // v2.2.1 及以前它在下标 2（顺序是 单曲/专辑/歌单）。
                    LibraryPlaylistsTab(
                        localPlaylists = localPlaylists,
                        neteasePlaylists = playlists,
                        isLoadingNetease = isLoadingPlaylists,
                        neteaseError = playlistError,
                        onRetryNetease = { loadPlaylists() },
                        onCreateNetease = { showCreatePlaylist = true },
                        onNeteaseClick = onPlaylistClick,
                        onPlayNetease = onPlayPlaylist,
                        onLocalClick = { pl -> onLocalPlaylistClick(pl.key) },
                        onCreateLocal = { showCreateLocal = true },
                        onQqClick = onQqPlaylistClick,
                        layout = playlistLayout,
                        // 「切换后立即生效」= 先写内存状态（本帧重组）、再落盘。
                        // 落盘用 apply()（异步），所以点击不会卡帧；而状态已经改了，
                        // 所以**立即生效**与**持久化**两半都不依赖对方完成（铁律 24）。
                        onLayoutChange = { next ->
                            playlistLayout = next
                            PlaylistLayoutSetting.write(context, next)
                        },
                        fold = sectionFold,
                        // 折叠**只由这里触发**（用户的点击）。这个 lambda 之外
                        // 没有任何一处会写 sectionFold —— 那就是「不自动折叠」的保证。
                        onToggleSection = { section ->
                            val next = sectionFold.toggled(section)
                            sectionFold = next
                            LibrarySectionFoldSetting.write(context, section, next.isCollapsed(section))
                        },
                    )
                }
                2 -> {
                    // v3.4.0：专辑 tab 的音源筛选（用户建议：「专辑界面建议加个来自 ncm 或者 qq」）。
                    //
                    // 三件事按顺序：
                    // ① 未筛选为空 ⇒ 与今天完全一样（`noSavedAlbums`）。这一句与筛选无关，
                    //    所以它**不看** `albumSourceFilter`。
                    // ② 筛选行的挂载判据只用**未筛选**的总数（`shouldShowFilterRow`）——
                    //    v3.2.0 · P0-D 的教训：拿「筛选后的结果」决定筛选行自己的可见性，
                    //    会让它在最该出现的那一刻消失（点「只看 qm」→ 空 ⇒ 连档位一起没了）。
                    // ③ 筛选后为空 ⇒ 一句**指名当前档位**的空态，而不是复用「暂无收藏专辑」
                    //    （那会让用户以为自己的收藏丢了）。
                    val visibleAlbums = AlbumSourceTag.filterAlbums(
                        savedAlbums,
                        albumSourceFilter,
                        { AlbumSourceTag.sourceOf(it) },
                    )
                    if (savedAlbums.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MetroText(strings.noSavedAlbums, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (AlbumSourceTag.shouldShowFilterRow(savedAlbums.size)) {
                                AlbumSourceFilterRow(
                                    selected = albumSourceFilter,
                                    onSelect = { albumSourceFilter = it },
                                )
                            }
                            if (visibleAlbums.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    MetroText(
                                        AlbumSourceTag.filteredEmptyText(albumSourceFilter, strings),
                                        color = LocalMetroColors.current.onSurfaceVariant,
                                        style = LocalMetroTypography.current.bodyLarge,
                                    )
                                }
                            } else {
                                // 自适应栅格：列数随内容栏宽度变化（宽屏多列），取代写死的 2 列。
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 160.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                    flingBehavior = rememberMetroFlingBehavior()
                                ) {
                                    items(visibleAlbums, key = { it.albumId }) { album ->
                                        LibraryAlbumGridItem(
                                            album = album,
                                            modifier = Modifier.fillMaxWidth().animateItem(
                                                fadeInSpec = tween(150, easing = MetroDefault),
                                                placementSpec = tween(220, easing = MetroDefault),
                                                fadeOutSpec = tween(120, easing = MetroDefault)
                                            ),
                                            onClick = { onAlbumClick(album.albumId) },
                                            onPlayAll = { onPlayAlbum(album.albumId) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // v3.3.0 · 用户建议：**离线歌直接在库里点播**。
                //
                // 复用与设置页弹窗**同一份**读取 / 对账 / 点播语义（OfflineLibraryTab），
                // 抄一份列表必然漂移 —— 漂移方向是「库里能播、设置里不能删」这类
                // 用户无法理解的差异。写成单表达式分支，避免再动 when 的括号结构。
                3 -> OfflineLibraryTab(
                    currentSongId = currentSongId,
                    onPlayTrack = { song -> song?.let(onSongClick) },
                )
            } }   // when (category) + AnimatedContent 的 lambda
        }
    }

    }
}

/**
 * 「单曲」tab 的搜索框 + 范围计数行（v3.9.0 抽出）。
 *
 * 抽出来是因为它现在有**三个**挂载点，而三处的观感与行为必须一致：
 *
 * - `LazyColumn` 的**第一项**（正常列表态）—— 跟着列表一起滚走，
 *   这正是用户原话里「搜索框」那一条；
 * - 两档空态里（`savedSongs` 为空 / 关键词没命中）—— 那里没有列表可挂，
 *   输入框必须留在空态之上，否则用户在任何一档里都失去了「改关键词 / 清关键词」的入口
 *   （v3.2.0 搜索页踩过的死胡同）。
 *
 * 三处各抄一份迟早漂移成「列表里的输入框有 placeholder、空态里的没有」这种鬼差异，
 * 所以只留一份实现，各调用点只决定**放哪儿**。
 *
 * ⚠️ 外层包一个 `Column`：`LazyColumn` 的 item 期望**一个**根节点，而这个块要输出两个兄弟
 * （输入框 + 可选计数行），包一层最稳妥（顺带让空态里两行的排布与列表里完全一致）。
 */
@Composable
private fun LibrarySearchFieldBlock(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    /** 「已载 N / 总数 M」；null = 不显示（已全部加载）。 */
    countText: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MetroTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )
        if (countText != null) {
            MetroText(
                text = countText,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.caption,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * B4：「新建歌单」格子。与歌单格子同尺寸、同圆角（v2.5.0 · B 起封面格一律裁圆角；
 * 旧正典是「直角、无圆角」），占位色块按同一条尺寸规则取 [AppShapes.large]。
 * 内容仍然只有一个居中的 +。
 *
 * v2.6.0 · P1：`listMode = true` 时改成整行（48dp 占位块 + 文案），
 * 尺寸与 [PlaylistItem] 的列表式逐值相同 —— 两个「＋」入口必须与它们所在的
 * 那一列同形，否则列表式页面里会冒出两个方形大格子。
 */
@Composable
fun NewPlaylistGridItem(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    // v2.3.0 · A/B：同一个「＋」格子被三处复用（新建 ncm 歌单 / 新建本地歌单），
    // 文案必须能改 —— 默认值保持既有调用点的行为不变。
    label: String? = null,
    listMode: Boolean = false,
) {
    val strings = LocalStrings.current
    val text = label ?: strings.playlistNew
    if (listMode) {
        Row(
            modifier = modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(AppShapes.small)
                    .background(LocalMetroColors.current.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                MetroIcon(
                    imageVector = Icons.Default.Add,
                    contentDescription = strings.playlistNew,
                    tint = LocalMetroColors.current.primary,
                    sizeDp = 24.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            MetroText(
                text,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Column(modifier = modifier.appPressScale().clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // 与同格封面同形：栅格 minSize=160dp ⇒ 单元格 ≥160dp ⇒ large。
                .clip(AppShapes.large)
                .background(LocalMetroColors.current.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(
                imageVector = Icons.Default.Add,
                contentDescription = strings.playlistNew,
                tint = LocalMetroColors.current.primary,
                sizeDp = 40.dp,
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            text,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * ncm 歌单条目（v2.6.0 · P1 起两种布局）。
 *
 * 卡片式与 v2.5.0 逐值相同；列表式新增 —— 48dp 封面（`CoverUrls.small` 的
 * 既有封装不变）+ 歌单名 + 「N 首」+ 右侧 ▶（列表式下 ▶ 从封面右下角
 * 移成行尾的独立入口：48dp 的封面上放不下一个 36dp 的按钮）。
 */
@Composable
fun PlaylistItem(
    playlist: PlaylistApi.PlaylistInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit,
    listMode: Boolean = false,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    if (listMode) {
        Row(
            modifier = modifier
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(AppShapes.small)
                    .background(colors.surfaceVariant),
            ) {
                AsyncImage(
                    model = CoverUrls.small(playlist.coverImgUrl),
                    contentDescription = strings.playlistCoverDesc,
                    // 形状按渲染边长：48dp < 160dp ⇒ AppShapes.small。
                    modifier = Modifier.fillMaxSize().appCoverFrame(shape = AppShapes.small),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                MetroText(
                    playlist.name,
                    color = colors.onSurface,
                    style = typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetroText(strings.trackCount(playlist.trackCount), color = colors.onSurfaceVariant, style = typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            PlayAllButton(size = 32.dp, onClick = onPlayAll)
        }
        return
    }
    Column(modifier = modifier.appPressScale().clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(playlist.coverImgUrl),
                contentDescription = strings.playlistCoverDesc,
                // v2.5.0 · B：圆角 + 1dp 描边。形状按渲染边长：栅格 minSize=160dp
                // ⇒ 单元格 ≥160dp ⇒ AppShapes.large（窄屏 360dp 内容宽下是 2 列 179dp）。
                modifier = Modifier
                    .fillMaxSize()
                    .appCoverFrame(shape = AppShapes.large),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(playlist.name, color = colors.onBackground, style = typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        MetroText(strings.trackCount(playlist.trackCount), color = colors.onSurfaceVariant, style = typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun LibraryAlbumGridItem(
    album: AlbumInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    val strings = LocalStrings.current
    // v3.4.0：**音源角标**。位置与写法都照 `AggregatedAlbumGridItem`（艺人页的专辑格子，
    // v2.4.0 · A 起就在这一行的**第一段**显示音源）—— 拼贴格子空间紧，
    // 「音源 · 艺人 · 曲目数」一行内解决，而不是给角标单开一行。
    //
    // ⚠️ 判不出来时 `badge == null` ⇒ 这一行**只有**「艺人 · N首」，不画角标。
    // 绝不回落成「ncm」（那会给错信息），判据见 `AlbumSourceTag`。
    val sourceBadge = remember(album, strings) { AlbumSourceTag.badgeLabel(album, strings) }
    val infoLine = listOfNotNull(
        sourceBadge,
        strings.albumArtistAndCount(album.artist, album.songCount),
    ).joinToString(" · ")
    Column(modifier = modifier.appPressScale().clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(album.picUrl),
                contentDescription = strings.albumCoverDesc,
                // 同上：栅格单元格 ≥160dp ⇒ AppShapes.large。
                modifier = Modifier
                    .fillMaxSize()
                    .appCoverFrame(shape = AppShapes.large),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(album.name, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        MetroText(infoLine, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(6.dp))
    }
}
