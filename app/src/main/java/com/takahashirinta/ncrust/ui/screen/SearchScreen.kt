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
import com.takahashirinta.ncrust.ui.components.SourceFilter
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
    // v2.1.0 · E：结果来自哪些音源（(网易云条数, QQ 音乐条数)；null = 还没搜过）。
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
    val categories = listOf(strings.searchCategoryTracks, strings.searchCategoryAlbums, strings.searchCategoryArtists)

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
                                // **不是**直接读 `item.source` 字符串 —— 那样老 QQ 条目会显示成网易云。
                                sourceBadge = SongTags.historySourceBadge(
                                    isSongSection = true,
                                    source = SearchHistoryMigration.effectiveSource(item),
                                    strings = strings,
                                ),
                                onClick = {
                                    // v2.5.4 · B：老 QQ 条目（v2.5.4 之前只存了裸 id，
                                    // 而 songmid 不可逆）点下去**必然取不到链**。旧行为是
                                    // 静默入队 → 被跳歌 → 还弹一条方向错误的「可切到网易云」
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
                                    // （收藏走裸 id，QQ 的合成 id 会被发给网易云的 like 接口）。
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
                                    10 -> 1
                                    100 -> 2
                                    else -> 0
                                },
                                onTabSelected = { index ->
                                    viewModel.onTypeChanged(
                                        when (index) {
                                            0 -> 1
                                            1 -> 10
                                            2 -> 100
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
                    1 -> {
                        if (visibleSongs.isEmpty() && !isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                MetroText(
                                    text = strings.searchSongsEmpty,
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
                                // 音源来源小字（真机反馈：纯网易云的结果在界面上看不出「来自哪里」）。
                                // 放在列表**第一项**而不是外面套一层 Column —— 后者要动布局结构，
                                // 而这里只需要一行字。
                                // v3.1.0 · B：音源筛选行。只在**搜到过东西**时出现 ——
                                // 空结果时给一排筛选档，用户点下去只会得到另一个空列表。
                                if (songs.isNotEmpty()) {
                                    item(key = "source-filter") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            SourceFilter.visible(biliEnabled).forEach { filter ->
                                                val selected = filter == sourceFilter
                                                MetroText(
                                                    text = filter.label(strings),
                                                    color = if (selected) {
                                                        LocalMetroColors.current.primary
                                                    } else {
                                                        LocalMetroColors.current.onSurfaceVariant
                                                    },
                                                    style = TextStyle(fontSize = 12.sp),
                                                    modifier = Modifier.clickable {
                                                        sourceFilter = filter
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                sourceCounts?.let { counts ->
                                    item(key = "source-summary") {
                                        // v2.5.5 · G：统计行现在能表达「还没回来」。
                                        // 旧代码在 QQ 未返回时写 0 ⇒ 界面显示「QQ 音乐 0 首」，
                                        // 而那句话是假的（QQ 只是慢）。现在显示「搜索中…」。
                                        val summaryModifier = Modifier.padding(
                                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp,
                                        )
                                        val summaryText = counts.summary(strings)
                                        if (counts.qqUnavailable) {
                                            // 超时：给一条**可点**的重试提示。
                                            // 不用自动重试 —— 那会在用户已经往下翻的时候
                                            // 突然往列表里插结果；手动重试把时机交给用户。
                                            MetroText(
                                                text = summaryText + "  ·  " + strings.retry,
                                                color = LocalMetroColors.current.primary,
                                                style = TextStyle(fontSize = 12.sp),
                                                modifier = summaryModifier.clickable {
                                                    viewModel.onQueryChanged(viewModel.query.value)
                                                },
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
                                }
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
                            }
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
// （bit62 推断、网易云写成 null、老 QQ 条目不猜 songmid）全部靠那个纯函数上的用例钉住，
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
