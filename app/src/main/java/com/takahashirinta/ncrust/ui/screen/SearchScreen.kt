package com.takahashirinta.ncrust.ui.screen

import com.takahashirinta.ncrust.bili.BiliSourceProvider
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.library.SearchHistoryManager
import com.takahashirinta.ncrust.library.SearchHistoryMigration
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.AlbumSearchItem
import com.takahashirinta.ncrust.ui.components.ArtistSearchItem
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.ui.components.PullToRefreshIndicator
import com.takahashirinta.ncrust.ui.components.SearchEmptyKind
import com.takahashirinta.ncrust.ui.components.SourceCounts
import com.takahashirinta.ncrust.ui.components.SourceFilter
import com.takahashirinta.ncrust.ui.components.pullToRefresh
import com.takahashirinta.ncrust.ui.components.rememberPullToRefreshState
import com.takahashirinta.ncrust.warmup.ListPrefetch
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongTags
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.components.listItemAppear
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.desaturateColor
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.viewmodel.SearchViewModel
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import android.widget.Toast

enum class BatchQueueAction { PLAY_NOW, INSERT_NEXT, APPEND }

@Composable
fun SearchScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onInsertNext: (SongItem) -> Unit = {},
    onAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    onAlbumBatch: (albumId: Long, action: BatchQueueAction) -> Unit = { _, _ -> },
    onArtistBatch: (artistName: String, action: BatchQueueAction) -> Unit = { _, _ -> },
    themeIndex: Int = 0,
    /**
     * v2.6.1 · P0：**外部投递的搜索关键词**（null = 没有待办）。
     *
     * 为什么是「投递 + 消费回调」而不是把 `query` 提升成受控参数：本页自己
     * `viewModel()` 持有一个私有 VM，而它的查询状态同时被输入框、历史、防抖、
     * 两源聚合四条路写。把 query 提升上来等于让 MainScreen 参与这四条路 ——
     * 那是把「跳搜索兜底」这一件事的代价扩散到整个搜索页。
     *
     * 语义是**一次性**的：消费后立刻回调 [onExternalQueryConsumed] 清空。
     * 否则用户切走再切回搜索 tab 会被再预填一次 —— 那不是他这次的动作。
     */
    externalQuery: String? = null,
    onExternalQueryConsumed: () -> Unit = {},
    // E：空查询态的榜单入口需要跳转到歌单/榜单详情。
    onPlaylistClick: (Long) -> Unit = {}
) {
    val viewModel: SearchViewModel = viewModel()
    val query by viewModel.query.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    // v3.4.11：分页。`hasMore` 由各音源给出（「这一页被填满」⇒ 可能还有），
    // `isLoadingMore` 与首屏的 `isLoading` 分开 —— 两者在界面上是两种提示。
    val hasMore by viewModel.hasMore.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    // v2.1.0 · E：结果来自哪些音源（(ncm 条数, qm 条数)；null = 还没搜过）。
    val sourceCounts by viewModel.sourceCounts.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val context = LocalContext.current
    val strings = LocalStrings.current
    // v3.1.0 · B：音源筛选（纯本地，不重新发请求 —— 见 SourceFilter 的 KDoc）。
    // 默认「双源」= 不过滤，与 v3.0.0 的行为逐字相同。
    var sourceFilter by remember { mutableStateOf(SourceFilter.ALL) }
    val biliEnabled = BiliSourceProvider.isEnabled
    val visibleSongs = remember(songs, sourceFilter) {
        sourceFilter.filter(songs) { it.musicSource }
    }
    // v3.2.0 · P0-D：下拉刷新。**复用**既有组件（`Modifier.pullToRefresh` +
    // `PullToRefreshIndicator`，v2.3.0 · B），唯一的使用点此前是歌单详情页 ——
    // 本页从来没接过，所以「没有下拉刷新」不是被跳过，而是从来没有入口。
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    // 刷新何时结束：搜索这一轮跑完（isLoading 落回 false）就把指示器收掉。
    // 判据必须由**发起刷新的那一层**给（组件自己不知道网络什么时候回来）——
    // 与 LocalPlaylistDetailScreen 的 `pullState.refreshing = false` 同一个契约。
    LaunchedEffect(isLoading) {
        if (!isLoading) pullState.refreshing = false
    }
    // v3.1.0 · P0-C：进入搜索结果就预取前 N 首的封面（N 按网络类型；只封面，不预取 URL）。
    LaunchedEffect(songs) {
        ListPrefetch.prefetchList(context, "search:${viewModel.query.value}", songs)
    }
    // v2.1.4：聚合搜索的排序要知道「用户有哪些平台的会员」（见 SearchRanking）。
    // 这里**现读、不缓存**：登录/登出后下一次搜索立刻按新的会员状态排，
    // 不需要任何失效逻辑。两个判据都来自服务端，取不到一律按非会员处理（保守那一侧）。
    viewModel.vipFlagsProvider = {
        com.takahashirinta.ncrust.auth.NeteaseVipStore.isVip(context) to
            com.takahashirinta.ncrust.qq.QqAuthStore.profile(context).isVip()
    }
    // 会员状态是低频数据（TTL 30 分钟），顺手在这里刷新一次：搜索页是用户主动进来的地方，
    // 在这里刷新比在每次搜索里同步等待一次网络请求划算得多（后者会把搜索拖慢一个 RTT）。
    LaunchedEffect(Unit) {
        if (com.takahashirinta.ncrust.auth.NeteaseVipStore.needsRefresh(context)) {
            com.takahashirinta.ncrust.auth.NeteaseVipStore.refresh(context)
        }
    }
    // v3.3.0 · 需求 2「用歌词搜索歌曲」：第 4 个 tab。
    // 位置放在「单曲」之后是有意的 —— 它返回的也是单曲（复用同一套 SongCard），
    // 挨着单曲比排在最右更容易被发现。
    val categories = listOf(
        strings.searchCategoryTracks,
        strings.searchCategoryLyrics,
        strings.searchCategoryAlbums,
        strings.searchCategoryArtists,
    )

    val currentThemeColor = themeColorForIndex(themeIndex)
    val desaturatedFill = desaturateColor(
        currentThemeColor,
        darkTheme = LocalMetroColors.current.background.luminance() < 0.5f
    )

    // 键盘治理: 点结果进详情/切 tab 后键盘不该还浮着
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    // 切 tab(离开组合)强制收键盘
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    // Search history state — loaded from SharedPreferences, refreshed whenever query clears
    var songHistory by remember { mutableStateOf(SearchHistoryManager.getSongs(context)) }
    var albumHistory by remember { mutableStateOf(SearchHistoryManager.getAlbums(context)) }
    var artistHistory by remember { mutableStateOf(SearchHistoryManager.getArtists(context)) }

    fun refreshHistory() {
        songHistory = SearchHistoryManager.getSongs(context)
        albumHistory = SearchHistoryManager.getAlbums(context)
        artistHistory = SearchHistoryManager.getArtists(context)
    }

    LaunchedEffect(query) {
        if (query.isEmpty()) refreshHistory()
    }

    // v2.6.1 · P0：消费外部投递的关键词（「转到歌手」身份不可信时的兜底落点）。
    //
    // key 用 externalQuery 而不是 Unit：投递方可能在**本页已经可见**时再投一次
    // （用户在搜索结果里连着长按两首 QQ 歌），那时页面不会重新进入组合，
    // 只有 key 变化才会重跑。消费后立刻清空，所以同一次投递不会重复触发。
    LaunchedEffect(externalQuery) {
        val q = externalQuery?.trim().takeIf { !it.isNullOrEmpty() } ?: return@LaunchedEffect
        viewModel.onQueryChanged(q)
        onExternalQueryConsumed()
    }

    val showHistory = query.isEmpty() &&
        (songHistory.isNotEmpty() || albumHistory.isNotEmpty() || artistHistory.isNotEmpty())

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景由 MainScreen 外层 Box 统一填充，此处不重复画一层
        Column(modifier = Modifier.fillMaxSize()) {
            // Search input
            // BasicTextField 本身没有 M3 TextField 的隐式 56dp min-height 与内 padding，
            // 得手动在 decorationBox 里补齐——heightIn(min=56.dp) 保住触控区高度，内容
            // 纵向居中、左右 16dp 内边距对齐 M3 视觉。否则搜索框会塌成一条 20dp 高的
            // 细条，看着像被压扁的 Chip。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(desaturatedFill)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { viewModel.onQueryChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = TextStyle(color = LocalMetroColors.current.onBackground, fontSize = 18.sp),
                    cursorBrush = SolidColor(LocalMetroColors.current.onBackground),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isEmpty()) {
                                MetroText(
                                    text = strings.searchPlaceholder,
                                    color = LocalMetroColors.current.onBackground.copy(alpha = 0.5f),
                                    style = TextStyle(fontSize = 18.sp),
                                )
                            }
                            innerTextField()
                            Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLoading) MetroProgressIndicator(
                                    sizeDp = 24.dp,
                                    color = currentThemeColor,
                                )
                                if (query.isNotEmpty()) MetroIconButton(onClick = { viewModel.clearQuery() }) {
                                    MetroIcon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = strings.clearSearchButton,
                                        tint = LocalMetroColors.current.onBackground.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                )
            }

            // 三态过渡：History（有历史时空 query）/ Results（输入非空）/ Empty（空 query 无历史）。
                // 用 Crossfade + SokuouTweens.CoverFade 消除清空搜索框时"结果列表 → 历史"的硬切。
                val searchContentState = when {
                    showHistory -> SearchContentState.History
                    query.isNotEmpty() -> SearchContentState.Results
                    else -> SearchContentState.Empty
                }
                // 宽屏搜索内容居中限宽(上限 760dp)，避免整行列表/历史横跨平板。
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                Crossfade(
                    targetState = searchContentState,
                    animationSpec = SokuouTweens.CoverFade,
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxHeight(),
                    label = "SearchContentCrossfade"
                ) { state -> when (state) {
                    SearchContentState.History -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                    if (songHistory.isNotEmpty()) {
                        item {
                            SearchHistorySectionHeader(
                                title = strings.searchCategoryTracks,
                                onClear = {
                                    SearchHistoryManager.clearSection(context, SearchHistoryManager.TYPE_SONG)
                                    refreshHistory()
                                },
                                clearLabel = strings.searchHistoryClear
                            )
                        }
                        items(songHistory, key = { "s_${SearchHistoryMigration.dedupeKey(it)}" }) { item ->
                            val song = item.toSongItem()
                            SearchHistoryItemCard(
                                item = item,
                                // v2.5.5 · D：单曲历史带音源角标（同名两源条目靠它区分）。
                                // 音源走 `effectiveSource`（老条目 source==null 时靠 bit62 推断），
                                // **不是**直接读 `item.source` 字符串 —— 那样老 QQ 条目会显示成 ncm。
                                sourceBadge = SongTags.historySourceBadge(
                                    isSongSection = true,
                                    source = SearchHistoryMigration.effectiveSource(item),
                                    strings = strings,
                                ),
                                onClick = {
                                    // v2.5.4 · B：老 QQ 条目（v2.5.4 之前只存了裸 id，
                                    // 而 songmid 不可逆）点下去**必然取不到链**。旧行为是
                                    // 静默入队 → 被跳歌 → 还弹一条方向错误的「可切到 ncm」
                                    // 提示（它其实已经是 QQ 了）。现在把标题填回搜索框、让用户
                                    // 重新点一次带 songmid 的结果：一次请求都不多发，
                                    // 也不会拿猜出来的 mid 去要一条坏链。
                                    if (SearchHistoryMigration.isIncomplete(item)) {
                                        Toast.makeText(
                                            context,
                                            strings.searchHistoryLegacyHint,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        dismissKeyboard()
                                        viewModel.onQueryChanged(item.title)
                                    } else {
                                        onSongClick(song)
                                    }
                                },
                                menuContent = { onDismiss ->
                                    MetroDropdownMenuItem(
                                        text = strings.playButton,
                                        textColor = LocalMetroColors.current.onBackground,
                                        // 同 onClick 的判据：不完整的老条目走「重搜」。
                                        onClick = {
                                            onDismiss()
                                            if (SearchHistoryMigration.isIncomplete(item)) {
                                                viewModel.onQueryChanged(item.title)
                                            } else {
                                                onSongClick(song)
                                            }
                                        },
                                    )
                                    // 「添加到下一首」「加入库」对不完整条目**不挂载**：
                                    // 它们会把一首取不到链、音源标识也不全的歌写进队列/收藏库
                                    // （收藏走裸 id，QQ 的合成 id 会被发给 ncm 的 like 接口）。
                                    // 不挂载而不是置灰 —— 见 AGENTS.md 触摸陷阱第 1/4 条。
                                    if (!SearchHistoryMigration.isIncomplete(item)) {
                                        MetroDropdownMenuItem(
                                            text = strings.actionInsertNext,
                                            textColor = LocalMetroColors.current.onBackground,
                                            onClick = { onDismiss(); onInsertNext(song) },
                                        )
                                        MetroDropdownMenuItem(
                                            text = strings.actionAddToLibrary,
                                            textColor = LocalMetroColors.current.onBackground,
                                            onClick = {
                                                onDismiss()
                                                // v2.6.0 · P0：成败由 saveSong 的返回值决定，不再无条件弹成功。
                                                if (LibraryManager.saveSong(context, song).isSuccess) {
                                                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                        )
                                    }
                                    MetroDropdownMenuItem(
                                        text = strings.searchHistoryDelete,
                                        textColor = Color.Red.copy(alpha = 0.85f),
                                        onClick = {
                                            onDismiss()
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_SONG, item)
                                            refreshHistory()
                                        },
                                    )
                                }
                            )
                        }
                    }

                    if (albumHistory.isNotEmpty()) {
                        item {
                            SearchHistorySectionHeader(
                                title = strings.searchCategoryAlbums,
                                onClear = {
                                    SearchHistoryManager.clearSection(context, SearchHistoryManager.TYPE_ALBUM)
                                    refreshHistory()
                                },
                                clearLabel = strings.searchHistoryClear
                            )
                        }
                        items(albumHistory, key = { "a_${SearchHistoryMigration.dedupeKey(it)}" }) { item ->
                            SearchHistoryItemCard(
                                item = item,
                                onClick = { onAlbumClick(item.id) },
                                menuContent = { onDismiss ->
                                    MetroDropdownMenuItem(
                                        text = strings.albumDetailTitle,
                                        textColor = LocalMetroColors.current.onBackground,
                                        onClick = { onDismiss(); onAlbumClick(item.id) },
                                    )
                                    MetroDropdownMenuItem(
                                        text = strings.searchHistoryDelete,
                                        textColor = Color.Red.copy(alpha = 0.85f),
                                        onClick = {
                                            onDismiss()
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_ALBUM, item)
                                            refreshHistory()
                                        },
                                    )
                                }
                            )
                        }
                    }

                    if (artistHistory.isNotEmpty()) {
                        item {
                            SearchHistorySectionHeader(
                                title = strings.searchCategoryArtists,
                                onClear = {
                                    SearchHistoryManager.clearSection(context, SearchHistoryManager.TYPE_ARTIST)
                                    refreshHistory()
                                },
                                clearLabel = strings.searchHistoryClear
                            )
                        }
                        items(artistHistory, key = { "r_${SearchHistoryMigration.dedupeKey(it)}" }) { item ->
                            SearchHistoryItemCard(
                                item = item,
                                onClick = { dismissKeyboard(); onArtistClick(item.id) },
                                menuContent = { onDismiss ->
                                    MetroDropdownMenuItem(
                                        text = strings.artistDetailTitle,
                                        textColor = LocalMetroColors.current.onBackground,
                                        onClick = { dismissKeyboard(); onDismiss(); onArtistClick(item.id) },
                                    )
                                    MetroDropdownMenuItem(
                                        text = strings.searchHistoryDelete,
                                        textColor = Color.Red.copy(alpha = 0.85f),
                                        onClick = {
                                            onDismiss()
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_ARTIST, item)
                                            refreshHistory()
                                        },
                                    )
                                }
                            )
                        }
                    }

                        }
                    }
                    SearchContentState.Results -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            MetroTabRow(
                                items = categories.map { MetroTabItem(it) },
                                selectedTabIndex = when (currentType) {
                                    1 -> 0
                                    1006 -> 1
                                    10 -> 2
                                    100 -> 3
                                    else -> 0
                                },
                                onTabSelected = { index ->
                                    viewModel.onTypeChanged(
                                        when (index) {
                                            0 -> 1
                                            1 -> 1006
                                            2 -> 10
                                            3 -> 100
                                            else -> 1
                                        }
                                    )
                                }
                            )

                error?.let {
                    MetroText(
                        text = strings.loadFailed(it),
                        color = Color.Red,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                when (currentType) {
                    // v3.3.0 · 需求 2：`1006`（歌词搜索）与 `1`（单曲搜索）**共用同一段渲染** ——
                    // 它返回的也是单曲（同一套 SongCard、同一套筛选与统计行），
                    // 单独写一段只会让两个分支逐日漂移。
                    // 两者真正的差别全在 ViewModel 的取数那一层（`searchByType` 里分派）。
                    1, 1006 -> {
                        // ------------------------------------------------------------------
                        // v3.2.0 · P0-D：筛选档与逐源统计行**移出 `LazyColumn`**，常驻在结果区顶部。
                        //
                        // 旧结构把两者画成列表的 item，而整条列表被
                        // `if (visibleSongs.isEmpty() && !isLoading)` 挡着 ⇒ 用户点「只看 B 站」
                        // 之后只要这一轮没有 B 站的行，整条列表（连筛选档一起）被换成空态 Box
                        // ⇒ 筛选档自己消失、没有任何路径点回「双源」⇒ 用户描述为「卡死 / 无法退出」。
                        //
                        // 判据抽成纯函数 `SourceFilter.shouldShowFilterRow`（有单测）：
                        // **只看「有没有可筛的东西」，绝不看筛选后的结果**（因果不能颠倒）。
                        // 详见 docs/verification/v3.2.0/probe-bili-search.md §3-D1/D6。
                        // ------------------------------------------------------------------
                        val filters = remember(biliEnabled) { SourceFilter.visible(biliEnabled) }
                        if (SourceFilter.shouldShowFilterRow(songs.size, biliEnabled)) {
                            SearchSourceFilterRow(
                                filters = filters,
                                selected = sourceFilter,
                                onSelect = { sourceFilter = it },
                            )
                        }
                        sourceCounts?.let { counts ->
                            SearchSourceSummaryRow(
                                counts = counts,
                                // 重试 = 「用当前关键词再走一轮」，与 v2.5.5 的 QQ 重试同一个入口，
                                // 不新加 ViewModel API（重复关键词本来就会重新发一轮请求）。
                                // v3.2.0 · P0-D：判据从 `qqUnavailable` 扩到 `anyUnavailable` ——
                                // B 站修好调度之后会**第一次真的**出现 TIMEOUT/ERROR，没有出口就是新死角。
                                onRetry = { viewModel.onQueryChanged(viewModel.query.value) },
                            )
                        }
                        // 结果区加载态（v3.2.0 · P0-D）：复用既有 `MetroProgressIndicator`，不新建组件。
                        // 两个来源都算「还有东西没到」：`isLoading`（本轮还没发布过）与
                        // `sourceCounts.hasPending`（某一源还在飞，含 B 站的 4s 预算）。
                        // 旧代码只有搜索框里那个 24dp 指示器，而它在**首次发布**后就被置 false
                        // （`SearchViewModel.kt:366`），于是「B 站还在飞」在结果区完全不可见。
                        if (isLoading || sourceCounts?.hasPending == true) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MetroProgressIndicator(sizeDp = 16.dp, color = currentThemeColor)
                                Spacer(Modifier.width(8.dp))
                                MetroText(
                                    text = strings.searchSourcePending,
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = TextStyle(fontSize = 12.sp),
                                )
                            }
                        }
                        // 空态的三分类：不空 / 本次搜索无结果 / 当前筛选下无结果。
                        // ⚠️ 加载中**不许**说「没有结果」（那是替用户提前下结论）——
                        // 上面那行「搜索中…」才是此刻的事实。
                        val emptyKind = SourceFilter.emptyKind(songs.size, visibleSongs.size)
                        when {
                            emptyKind == null -> {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 下拉刷新：刷新 = 用当前关键词再走一轮。
                                        // `atTop` 判据与歌单详情页逐字相同（第一项可见且偏移为 0），
                                        // 不看它会把「往下滚列表」误判成下拉刷新。
                                        .pullToRefresh(
                                            state = pullState,
                                            atTop = {
                                                listState.firstVisibleItemIndex == 0 &&
                                                    listState.firstVisibleItemScrollOffset == 0
                                            },
                                            // v3.4.11：走 `refresh()`（跳过 500ms 防抖）而不是
                                            // `onQueryChanged(同一个词)` —— 后者让松手后再等半秒，
                                            // 观感就是「下拉了没反应」。
                                            onRefresh = { viewModel.refresh() },
                                        ),
                                    contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                    flingBehavior = rememberMetroFlingBehavior()
                                ) {
                                    // 指示器是列表的**第一项**（既有用法，见 LocalPlaylistDetailScreen）：
                                    // 只在真的拉动或刷新中挂载，不做「alpha = 0 常挂载」
                                    // （AGENTS.md 触摸陷阱第 1 条）。
                                    item(key = "pull-refresh") { PullToRefreshIndicator(state = pullState) }
                                    // v2.5.0 · A：列表入场（淡入 + 上滑）。items → itemsIndexed
                                    // 只为拿到下标，key 显式传同一条（`it.id`）⇒ diff 行为不变。
                                    itemsIndexed(visibleSongs, key = { _, item -> item.id }) { index, item ->
                                        SongCard(
                                            song = item,
                                            style = SongCardStyle.LIST,
                                            coverSize = 72.dp,
                                            modifier = Modifier.listItemAppear(index),
                                            onClick = {
                                                SearchHistoryManager.addSong(context, item)
                                                dismissKeyboard(); onSongClick(item)
                                            },
                                            onShowMenu = {
                                                onShowSongMenu(item, listOf(
                                                    SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                                        SearchHistoryManager.addSong(context, item)
                                                        // v2.6.0 · P0：同上，成败由返回值决定。
                                                        if (LibraryManager.saveSong(context, item).isSuccess) {
                                                            Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                                        SearchHistoryManager.addSong(context, item)
                                                        onInsertNext(item)
                                                    },
                                                    SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                                        SearchHistoryManager.addSong(context, item)
                                                        onAppendToQueue(item)
                                                    }
                                                ))
                                            }
                                        )
                                    }
                                    // v3.4.11：「加载更多」。**必须有这个出口** ——
                                    // 此前搜索结果只有一页、界面上什么都没有，
                                    // 用户无从知道是被截断了还是没有更多（报障原话：
                                    // 「每次拉歌曲只拉三十首也太少了吧」）。
                                    // 列表滚到底时它就在那儿，点一次取下一页（追加，不替换）。
                                    item(key = "load-more") {
                                        SearchLoadMoreRow(
                                            hasMore = hasMore,
                                            isLoadingMore = isLoadingMore,
                                            label = strings.searchLoadMore,
                                            loadingLabel = strings.searchLoadingMore,
                                            onClick = { viewModel.loadMore() },
                                        )
                                    }
                                }
                            }
                            isLoading -> Spacer(Modifier.fillMaxSize())
                            else -> SearchResultsEmptyState(
                                // 「本次搜索没有结果」与「当前筛选下没有结果」必须是两句不同的话。
                                // FILTERED_OUT 的文案由 `SourceFilter.emptyText` 给（临时占位，
                                // 需要的 i18n key 见 v3.2.0 交付报告），它**指名当前档位**。
                                text = when (emptyKind!!) {
                                    SearchEmptyKind.NO_RESULT -> strings.searchSongsEmpty
                                    SearchEmptyKind.FILTERED_OUT -> sourceFilter.emptyText(strings)
                                },
                            )
                        }
                    }

                    10 -> {
                        if (albums.isEmpty() && !isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                MetroText(
                                    text = strings.searchAlbumsEmpty,
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = TextStyle(fontSize = 16.sp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(albums, key = { it.id }) { album ->
                                    AlbumSearchItem(
                                        album = album,
                                        onClick = {
                                            SearchHistoryManager.addAlbum(context, album)
                                            dismissKeyboard(); onAlbumClick(album.id)
                                        },
                                        menuContent = { onDismiss ->
                                            MetroDropdownMenuItem(
                                                text = strings.playAllButton,
                                                onClick = {
                                                    SearchHistoryManager.addAlbum(context, album)
                                                    onAlbumBatch(album.id, BatchQueueAction.PLAY_NOW)
                                                    onDismiss()
                                                }
                                            )
                                            MetroDropdownMenuItem(
                                                text = strings.actionInsertNext,
                                                onClick = {
                                                    SearchHistoryManager.addAlbum(context, album)
                                                    onAlbumBatch(album.id, BatchQueueAction.INSERT_NEXT)
                                                    onDismiss()
                                                }
                                            )
                                            MetroDropdownMenuItem(
                                                text = strings.actionAppendToQueue,
                                                onClick = {
                                                    SearchHistoryManager.addAlbum(context, album)
                                                    onAlbumBatch(album.id, BatchQueueAction.APPEND)
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    )
                                }
                                // v3.4.11：专辑 tab 同样只有一页（此前也是 30 条上限）。
                                item(key = "load-more") {
                                    SearchLoadMoreRow(
                                        hasMore = hasMore,
                                        isLoadingMore = isLoadingMore,
                                        label = strings.searchLoadMore,
                                        loadingLabel = strings.searchLoadingMore,
                                        onClick = { viewModel.loadMore() },
                                    )
                                }
                            }
                        }
                    }

                    100 -> {
                        if (artists.isEmpty() && !isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                MetroText(
                                    text = strings.searchArtistsEmpty,
                                    color = LocalMetroColors.current.onSurfaceVariant,
                                    style = TextStyle(fontSize = 16.sp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(artists, key = { it.id }) { artist ->
                                    ArtistSearchItem(
                                        artist = artist,
                                        onClick = {
                                            SearchHistoryManager.addArtist(context, artist)
                                            dismissKeyboard(); onArtistClick(artist.id)
                                        },
                                        menuContent = { onDismiss ->
                                            MetroDropdownMenuItem(
                                                text = strings.playAllButton,
                                                onClick = {
                                                    SearchHistoryManager.addArtist(context, artist)
                                                    onArtistBatch(artist.name, BatchQueueAction.PLAY_NOW)
                                                    onDismiss()
                                                }
                                            )
                                            MetroDropdownMenuItem(
                                                text = strings.actionInsertNext,
                                                onClick = {
                                                    SearchHistoryManager.addArtist(context, artist)
                                                    onArtistBatch(artist.name, BatchQueueAction.INSERT_NEXT)
                                                    onDismiss()
                                                }
                                            )
                                            MetroDropdownMenuItem(
                                                text = strings.actionAppendToQueue,
                                                onClick = {
                                                    SearchHistoryManager.addArtist(context, artist)
                                                    onArtistBatch(artist.name, BatchQueueAction.APPEND)
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    )
                                }
                                // v3.4.11：艺人 tab 同样只有一页（此前也是 30 条上限）。
                                item(key = "load-more") {
                                    SearchLoadMoreRow(
                                        hasMore = hasMore,
                                        isLoadingMore = isLoadingMore,
                                        label = strings.searchLoadMore,
                                        loadingLabel = strings.searchLoadingMore,
                                        onClick = { viewModel.loadMore() },
                                    )
                                }
                            }
                        }
                    }
                }
                        }
                    }
                    SearchContentState.Empty -> {
                        // E（方案 3 附加）：空查询时展示榜单，复用首页那份 ContentCache 快照，
                        // 未命中/过期才发一次请求（榜单匿名可读，不需要登录）。
                        var toplists by remember { mutableStateOf(ContentCache.toplistItems ?: emptyList()) }
                        LaunchedEffect(Unit) {
                            if (ContentCache.toplistItems == null || !ContentCache.isToplistFresh()) {
                                runCatching { PlaylistApi.getToplists() }.getOrNull()?.let {
                                    ContentCache.putToplist(it)
                                    toplists = it
                                }
                            }
                        }
                        if (toplists.isEmpty()) {
                            Spacer(Modifier.fillMaxSize())
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                item {
                                    Box(modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) {
                                        MetroText(
                                            strings.toplistSectionTitle,
                                            color = LocalMetroColors.current.onBackground,
                                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                                items(toplists, key = { it.id }) { tl ->
                                    ToplistRow(tl) { onPlaylistClick(tl.id) }
                                }
                            }
                        }
                    }
                } }
                }
        }
    }
}

/**
 * v3.2.0 · P0-D：音源筛选档。**常驻在结果区顶部**，不再画进 `LazyColumn`。
 *
 * 两个「为什么」：
 * - **为什么在列表外面**：放进列表就会被「筛选后为空 ⇒ 整条列表换成空态」这条判据带走 ——
 *   而筛选后为空恰恰是用户最需要它的时候（P0-D 的根因）。可见性判据由
 *   `SourceFilter.shouldShowFilterRow`（纯函数，有单测）在**调用点**决定。
 * - **为什么加载中也能点**：切换筛选是纯本地操作（不发请求、不取消任何协程，
 *   见 `SourceFilter` 的类文档），没有任何理由在加载中禁用它。
 */
@Composable
private fun SearchSourceFilterRow(
    filters: List<SourceFilter>,
    selected: SourceFilter,
    onSelect: (SourceFilter) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { filter ->
            val isSelected = filter == selected
            MetroText(
                text = filter.label(strings),
                color = if (isSelected) {
                    LocalMetroColors.current.primary
                } else {
                    LocalMetroColors.current.onSurfaceVariant
                },
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.clickable { onSelect(filter) },
            )
        }
    }
}

/**
 * v3.2.0 · P0-D：逐源统计行（含「还没回来」的三态）+ 重试入口。**常驻**，理由同筛选档。
 *
 * v2.5.5 · G 的语义一个字没改：某一源未返回时显示「搜索中…」/「搜索超时」/「搜索失败」，
 * 绝不把「还没回来」写成「0 首」。唯一的扩项是判据从 `qqUnavailable` 换成
 * `anyUnavailable`（= QQ 或 B 站），因为 B 站在修好调度之后**第一次真的**会出现
 * TIMEOUT/ERROR —— 而 v3.1.0 只给 QQ 留了重试出口。
 *
 * 重试是**手动**的：自动重试会在用户已经往下翻的时候突然往列表里插结果（v2.5.5 的既有取舍）。
 */
@Composable
private fun SearchSourceSummaryRow(counts: SourceCounts, onRetry: () -> Unit) {
    val strings = LocalStrings.current
    val summaryModifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
    val summaryText = counts.summary(strings)
    if (counts.anyUnavailable) {
        MetroText(
            text = summaryText + "  ·  " + strings.retry,
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 12.sp),
            modifier = summaryModifier.clickable(onClick = onRetry),
        )
    } else {
        MetroText(
            text = summaryText,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp),
            modifier = summaryModifier,
        )
    }
}

/**
 * v3.2.0 · P0-D：结果区空态。文案由调用点按 `SourceFilter.emptyKind` 分流 ——
 * 「本次搜索没有结果」与「当前筛选下没有结果」是两句不同的话（后者必须指名当前档位）。
 */
@Composable
private fun SearchResultsEmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        MetroText(
            text = text,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 16.sp),
        )
    }
}

/** E：搜索页空查询态的榜单行（封面 + 名称 + 曲目数）。 */
@Composable
private fun ToplistRow(playlist: PlaylistApi.PlaylistCard, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        coil.compose.AsyncImage(
            model = CoverUrls.small(playlist.coverUrl),
            contentDescription = playlist.name,
            // v2.5.0 · B：圆角 + 1dp 描边。形状按渲染边长：48dp < 160dp ⇒ AppShapes.small。
            modifier = Modifier
                .size(48.dp)
                .appCoverFrame(shape = AppShapes.small),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                playlist.name,
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1
            )
            val strings = LocalStrings.current
            MetroText(
                strings.trackCount(playlist.trackCount),
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 11.sp),
                maxLines = 1
            )
        }
    }
}

private enum class SearchContentState { History, Results, Empty }

@Composable
private fun SearchHistorySectionHeader(title: String, clearLabel: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            text = title,
            color = LocalMetroColors.current.onBackground,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        // TextButton 替换:直角矩形 tap target,无背景 -- 与 Metro 的 "borderless"
        // 原则契合。padding 补齐视觉高度。
        Box(
            modifier = Modifier
                .clickable(onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            MetroText(
                text = clearLabel,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryItemCard(
    item: SearchHistoryManager.HistoryItem,
    onClick: () -> Unit,
    menuContent: @Composable ColumnScope.(onDismiss: () -> Unit) -> Unit,
    /**
     * v2.5.5 · D：音源角标文案；`null` = 不显示。
     *
     * 判据在 [SongTags.historySourceBadge]（纯函数，有单测），这里只负责画。
     * 传 `null` 而不是空串：空串会留下一个 0 宽的 `MetroText` 节点（多一个命中层），
     * 而「不挂载」是 AGENTS.md 触摸陷阱第 1/4 条要求的形态。
     */
    sourceBadge: String? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = CoverUrls.small(item.coverUrl),
                contentDescription = null,
                // v2.5.0 · B：圆角 + 1dp 描边。形状按渲染边长：64dp < 160dp ⇒ small。
                modifier = Modifier
                    .size(64.dp)
                    .appCoverFrame(shape = AppShapes.small),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MetroText(
                    text = item.title,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.subtitle.isNullOrEmpty()) {
                    MetroText(
                        text = item.subtitle,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // v2.5.5 · D：音源角标**挂在行尾、不参与省略**。
            //
            // 为什么不做成副标题的一部分（`"$artist · $badge"`，列表行 `SongCard` 的写法）：
            // 历史列表里歌手名一长，省略号会把角标整段吃掉 —— 而「区分同名历史」
            // 正是这个角标存在的**唯一**理由，被吃掉就等于没做。
            // 这与 v2.1.0 · F 给播放页角标的约定同源（「角标定宽、歌手让位省略」）。
            if (sourceBadge != null) {
                Spacer(Modifier.width(8.dp))
                MetroText(
                    text = sourceBadge,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                    maxLines = 1,
                )
            }
        }
        MetroDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            containerColor = LocalMetroColors.current.surface,
        ) {
            menuContent { showMenu = false }
        }
    }
}

// v2.5.4 · B：重建逻辑搬到 `library/SearchHistoryMigration.toSongItem`。
// 搬家的理由不是"整洁"，而是**它必须能被单测够到** —— 音源恢复是否正确
// （bit62 推断、ncm 写成 null、老 QQ 条目不猜 songmid）全部靠那个纯函数上的用例钉住，
// 留在这里（一个 Composable 文件里的 private 扩展）就只能靠真机点一遍看角标。
private fun SearchHistoryManager.HistoryItem.toSongItem(): SongItem =
    SearchHistoryMigration.toSongItem(this)

@Composable
fun SongSearchItem(
    song: SongItem,
    onPlay: () -> Unit,
    onAddToLibrary: () -> Unit,
    onInsertNext: () -> Unit = {},
    onAppendToQueue: () -> Unit = {}
) {
    val strings = LocalStrings.current
    SongCard(
        song = song,
        style = SongCardStyle.LIST,
        coverSize = 56.dp,
        onClick = onPlay,
        actions = {
            MetroIconButton(onClick = onAddToLibrary) {
                MetroIcon(
                    imageVector = Icons.Default.Add,
                    contentDescription = strings.actionAddToLibrary,
                    tint = LocalMetroColors.current.onBackground,
                )
            }
            MetroIconButton(onClick = onInsertNext) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = strings.actionInsertNext,
                    tint = LocalMetroColors.current.onBackground,
                )
            }
            MetroIconButton(onClick = onAppendToQueue) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = strings.actionAddToPlaylist,
                    tint = LocalMetroColors.current.onBackground,
                )
            }
        }
    )
}

/**
 * 搜索结果列表底部的「加载更多」（v3.4.11）。
 *
 * ## 为什么是一个**显式按钮**而不是「滚到底自动加载」
 *
 * 自动加载要在 `LazyListState` 上挂一个「即将到底」的派生状态，而那个判据在
 * 「列表短于一屏」时永远为真 —— 于是短结果（比如 12 条）会**自动连点**后面的每一页，
 * 一屏都还没滚完就把后面全拉下来了。用户报的是「只有 30 首」，
 * 他想要的不是「替我把剩下的都拉完」，而是**一个能自己决定的出口**。
 *
 * 而且显式按钮让「还有没有下一页」这件事**在界面上是可见的**：
 * 按钮消失 = 到底了。此前那个状态在 UI 上完全不存在。
 *
 * ## 两条状态
 *
 * - [hasMore] 来自各个音源（`MusicSourceProvider.hasMorePages`），判据是
 *   「这一页被填满」⇒ 可能还有。它会在最后一页多给一次点击，而不会漏页。
 * - [isLoadingMore] 只影响这一行的文案与可点性（正在飞时点它不该再发一次请求）。
 */
@Composable
private fun SearchLoadMoreRow(
    hasMore: Boolean,
    isLoadingMore: Boolean,
    label: String,
    loadingLabel: String,
    onClick: () -> Unit,
) {
    if (!hasMore && !isLoadingMore) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(
            text = if (isLoadingMore) loadingLabel else label,
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 14.sp),
            modifier = Modifier
                // 命中区只增不减（AGENTS.md 触摸陷阱第 7 条）：文字本身只有十几 dp 高，
                // 不给内边距的话它是一个「看得见但不好点」的入口。
                .clickable(enabled = !isLoadingMore, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}
