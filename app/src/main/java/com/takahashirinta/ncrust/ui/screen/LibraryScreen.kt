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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.defaultPlaylistName
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
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
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.AlbumSourceFilterRow
import com.takahashirinta.ncrust.ui.components.AlbumSourceTag
import com.takahashirinta.ncrust.ui.components.CreatePlaylistDialog
import com.takahashirinta.ncrust.ui.components.PlayAllButton
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
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onPlaylistClick: (PlaylistApi.PlaylistInfo) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    // Bug2-④：收藏单曲「播放全部」。由上层 loadAllLikedSongs 覆盖整个红心歌单。
    onPlayAllLiked: () -> Unit = {},
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
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current

    var savedSongs by remember { mutableStateOf(LibraryManager.getSavedSongs(context)) }
    var savedAlbums by remember { mutableStateOf(LibraryManager.getSavedAlbums(context)) }
    // 红心歌单总数（含尚未分页加载的部分），供「播放全部」入口显示规模。
    var likedTotal by remember { mutableIntStateOf(LibraryManager.getLikedSongIds(context).size) }
    var selectedCategory by remember { mutableIntStateOf(0) }
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
                    val userId = PlaylistApi.getCurrentUserId()
                    val result = PlaylistApi.getUserPlaylists(userId)
                    playlists = result.playlists
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
        savedSongs = LibraryManager.getSavedSongs(context)
        savedAlbums = LibraryManager.getSavedAlbums(context)
        likedTotal = LibraryManager.getLikedSongIds(context).size
        localPlaylists = LocalPlaylistStore.readPlaylists(context)
    }

    LaunchedEffect(selectedCategory) {
        reloadLocal()
        // 歌单 tab 现在是下标 1（v2.3.0 · A 的顺序调整）。
        if (selectedCategory == 1 && playlists.isEmpty() && !isLoadingPlaylists) {
            loadPlaylists()
        }
    }

    // 进入收藏页时后台拉取云端收藏（收藏单曲 + 收藏专辑），刷新后平滑 diff 更新。
    // 失败/未登录不影响展示——本地缓存先渲染，绝不出现空白+加载动画。
    LaunchedEffect(Unit) {
        LibraryManager.refreshFromCloud(context)
        reloadLocal()
    }

    // 登录完成后（cookieRefreshTrigger 变化）再拉一次云端，保证重新安装/刚登录后库非空。
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            LibraryManager.refreshFromCloud(context)
            reloadLocal()
        }
    }

    // v2.3.0 · B：本地歌单不是可观察数据源（SharedPreferences），所以由上层用一个
    // 递增的信号告诉本页「它变了，重读一次」。从歌曲菜单里加过歌之后必须重读，
    // 否则用户回到库页看到的还是加之前的样子。
    LaunchedEffect(localPlaylistsTick) {
        if (localPlaylistsTick > 0) localPlaylists = LocalPlaylistStore.readPlaylists(context)
    }

    // 收藏单曲分页懒加载：滚动到列表末尾时拉取下一批详情。
    val songListState = rememberLazyListState()
    LaunchedEffect(selectedCategory) {
        if (selectedCategory != 0) return@LaunchedEffect
        snapshotFlow { songListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastIndex ->
                val total = LibraryManager.getLikedSongIds(context).size
                if (lastIndex >= savedSongs.size - 5 && savedSongs.size < total) {
                    val more = LibraryManager.loadMoreLikedSongs(context)
                    if (more.isNotEmpty()) savedSongs = more
                }
            }
    }

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
                    val id = PlaylistEditApi.createPlaylist(name, privacy)
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
                LocalPlaylistRepository.createLocalOnly(context, name)
                localPlaylists = LocalPlaylistStore.readPlaylists(context)
                showCreateLocal = false
                Toast.makeText(context, strings.localPlaylistCreated(name), Toast.LENGTH_SHORT).show()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    ResponsiveContent {
        // 背景由 MainScreen 外层 Box 统一填充，此处不重复画一层
        Column(modifier = Modifier.fillMaxSize()) {
            // Groove 风大字页头。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            ) {
                MetroText(
                    strings.tabLibrary,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.pageHeading,
                )
            }
            Spacer(Modifier.height(4.dp))
            MetroTabRow(
                selectedTabIndex = selectedCategory,
                items = categories.map { MetroTabItem(it) },
                onTabSelected = { index -> selectedCategory = index }
            )

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
                    if (savedSongs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MetroText(strings.noSavedSongs, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = songListState,
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                            // Bug2-④：整张红心歌单的「播放全部」入口。
                            // 显示的是 total（云端数量）而非 savedSongs.size（已加载数量），
                            // 避免用户以为"我喜欢的歌"只有已翻页的那几十首。
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MetroText(
                                        strings.trackCountSongs(likedTotal),
                                        color = LocalMetroColors.current.onSurfaceVariant,
                                        style = LocalMetroTypography.current.bodySmall
                                    )
                                    Spacer(Modifier.weight(1f))
                                    PlayAllButton(onClick = onPlayAllLiked)
                                }
                            }
                            // v2.5.0 · A：列表入场（淡入 + 上滑）。items → itemsIndexed 只为拿下标，
                            // key 显式传同一条（`it.id`）⇒ LazyColumn 的 diff 行为不变。
                            itemsIndexed(savedSongs, key = { _, song -> song.id }) { index, song ->
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