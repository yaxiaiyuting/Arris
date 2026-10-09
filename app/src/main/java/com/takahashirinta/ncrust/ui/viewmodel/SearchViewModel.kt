package com.takahashirinta.ncrust.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takahashirinta.ncrust.auth.NeteaseVipStore
import com.takahashirinta.ncrust.network.*
import com.takahashirinta.ncrust.bili.BiliSourceProvider
import com.takahashirinta.ncrust.qq.QqAccountAvailability
import com.takahashirinta.ncrust.qq.QqAuthStore
import com.takahashirinta.ncrust.qq.QqApi
import com.takahashirinta.ncrust.qq.QqClient
import com.takahashirinta.ncrust.search.RankedSong
import com.takahashirinta.ncrust.search.SearchLatencyTrace
import com.takahashirinta.ncrust.search.SearchRanking
import com.takahashirinta.ncrust.search.TrackAccess
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.source.trackKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.takahashirinta.ncrust.ui.components.SourceCounts
import com.takahashirinta.ncrust.ui.components.SourceSearchStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

class SearchViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /**
     * 两个平台的会员状态（v2.1.4），决定聚合结果怎么排（见 [SearchRanking]）。
     *
     * ## 为什么做成「注入的 lambda」而不是在这里读 SharedPreferences
     *
     * 这个 ViewModel 是纯 `ViewModel()`（没有 Application），而会员状态存在
     * SharedPreferences 里、需要 Context。为它换 `AndroidViewModel` 会牵动
     * `viewModel()` 的构造方式与既有的调用点；直接把 `Context` 传进来又会把
     * 一个 Application 级引用长期挂在这个 ViewModel 上。
     *
     * 做成 lambda 之后：① 每次搜索**现读**，登录/登出后立刻生效，不需要缓存失效逻辑；
     * ② 单测里可以直接换成一个返回固定值的 lambda，不必碰 Android。
     * 默认值 (`false to false`) 是保守的那一侧 —— 排序退化成 v2.1.3 的行为。
     */
    var vipFlagsProvider: () -> Pair<Boolean, Boolean> = { false to false }

    private val _songs = MutableStateFlow<List<SongItem>>(emptyList())
    val songs: StateFlow<List<SongItem>> = _songs

    private val _albums = MutableStateFlow<List<AlbumSearchItem>>(emptyList())
    val albums: StateFlow<List<AlbumSearchItem>> = _albums

    private val _artists = MutableStateFlow<List<ArtistSearchItem>>(emptyList())
    val artists: StateFlow<List<ArtistSearchItem>> = _artists

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * 上一次单曲搜索里两个音源各出了多少条（v2.1.0 · E）。
     *
     * 存在的理由很直接：`SongCard` 只在**非 ncm**的行上加音源标识，
     * 于是「ncm 的行」和「QQ 的行」在列表里长得一样，用户无法判断结果来自哪里 ——
     * 真机反馈原话「现在有了稻香，似乎是 ncm 的搜索结果？」。
     * 一行小字把两个来源的条数都说清楚，比给每一行都挂标签更省地方。
     *
     * `null` = 还没搜过（不显示那一行）。
     */
    /**
     * v2.5.5 · G：逐源统计（含**状态**）。
     *
     * 旧类型是 `Pair<Int, Int>?` —— 它只能表达计数，于是「QQ 还没回来」被迫写成 0，
     * 界面上就是「qm 0 首」那句假话。新类型见 [SourceCounts]。
     */
    private val _sourceCounts = MutableStateFlow<SourceCounts?>(null)
    val sourceCounts: StateFlow<SourceCounts?> = _sourceCounts

    private val _currentType = MutableStateFlow(1)
    val currentType: StateFlow<Int> = _currentType

    private var searchJob: Job? = null

    /**
     * **每页条数**（v3.4.11）。
     *
     * ## 为什么是 30（而不是原来那样「就是 30，没有下页」）
     *
     * 这个数字以前是「一次搜索的全部」—— `NcmApi.search` 的默认 `limit=30`、
     * `offset` 恒为 0，界面上没有任何「还有更多」的出口。用户报障原话：
     * 「每次拉歌曲只拉三十首也太少了吧。」
     *
     * 现在它是**一页**的大小：首屏仍然是 30 条（首屏延迟不变），
     * 但列表底部会出现「加载更多」，一页一页往后取。
     *
     * 为什么不直接把首屏改成 100：`cloudsearch` 的 `limit` 越大首屏越慢，
     * 而搜索是交互式功能 —— 30 + 主动加载更多比 100 + 干等更合适。
     * 更要紧的是 **QQ 那条腿的分页粒度就是 30**（旧版 GET 的 `n=` 与 `p=` 耦合），
     * 首屏改 100 会让「第一页 100 条、第二页 30 条」这种口径错位从一开始就存在。
     */
    private val PAGE_SIZE = com.takahashirinta.ncrust.search.SearchPaging.PAGE_SIZE

    /**
     * 当前累计到的页码（**1 起**）。`loadMore()` 会把它 +1 后重新取那一页。
     */
    private var currentPage = 1

    /**
     * 三个 tab 各自「还有没有下一页」（v3.4.11）。
     *
     * 判据由各个音源给出（见 `MusicSourceProvider.hasMorePages`）：
     * 「这一页被填满」⇒ 可能还有。它会在最后一页多给用户一次点击，
     * 而不会漏掉任何一页 —— 后者才是缺陷。
     */
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    /** 「加载更多」正在飞。与 [isLoading] 分开：首屏加载与翻页在界面上是两种提示。 */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    /**
     * 上一轮**已发布**的逐源原始结果（未排序、未过滤）。
     *
     * 翻页时要靠它把「新一页的排名结果」并进「已发布的排名结果」：
     * 两边都过同一个 [SearchRanking.order]，所以追加后的相对顺序是稳定的 ——
     * 直接 `+` 会得到一个「第一页按会员排好、第二页按请求顺序接在后面」的列表，
     * 那是**两个排序口径拼在一起**，用户能看出来。
     */
    /**
     * v3.4.11：这一轮**服务端声明的总数**（逐源）。
     *
     * 由各 Provider 通过 `searchSongs(..., totalOut)` 填进来（实测两个音源都给：
     * ncm `result.songCount` / QQ `data.song.totalnum`）。
     * 界面用它显示「ncm 273 首」，而不是「这一轮取回多少条」——
     * 后者是用户报的「没有实时更新」的根源。
     */
    private val lastTotals = mutableMapOf<String, Int>()

    private var lastSongs: List<SongItem> = emptyList()
    private var lastQqSongs: List<SongItem> = emptyList()
    private var lastBiliSongs: List<SongItem> = emptyList()

    /**
     * 补充源（qm）的时间预算（v2.1.0 · hotfix 3）。
     *
     * 搜索是**交互式**功能，用户对「多久算慢」的容忍度是秒级。补充源晚到不如不到 ——
     * 主源的结果必须先让用户看见（见 [searchByType] 的顺序说明）。
     */
    private val QQ_SEARCH_BUDGET_MS = 5_000L

    /**
     * v3.3.0 · 需求 2：QQ **歌词搜索**每次取的条数。
     *
     * 取 30 与聚合路径给 QQ 的条数（`SourceRouter.searchSongs(QQMUSIC, keyword, 30)`）
     * 保持一致 —— 两个入口给同一个音源的条数不该不同，否则「切换 tab 后结果变少」
     * 会被读成一个缺陷。
     */
    private val QQ_LYRIC_LIMIT = 30

    /**
     * v3.1.0 · B：B 站搜索的时间预算。
     *
     * 比 QQ 的 5 秒更短是有依据的：B 站这条链路要**先取 wbi 密钥再签名请求**，
     * 冷启动时是两通（探针实测每通 TTFB 100~180ms，两通 < 500ms），
     * 但风控命中时会返回 412 并触发一次密钥刷新重试 —— 那条路要 4 通。
     * 4 秒足够覆盖它，同时不让「B 站挂了」把搜索结果拖长。
     */
    private val BILI_SEARCH_BUDGET_MS = 4_000L

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            if (newQuery.isBlank()) {
                clearResults()
                return@launch
            }
            searchByType(_currentType.value)
        }
    }

    /**
     * 下拉刷新（v3.4.11）：**用当前关键词再走一轮**，且**跳过 500ms 防抖**。
     *
     * 为什么不能直接复用 `onQueryChanged(当前关键词)`：那条路是给「用户正在打字」用的，
     * 它先 `delay(500)` 再搜 —— 下拉刷新时用户已经松手了，还要再等半秒才看到反应，
     * 观感上就是「下拉了没反应」（用户报障里那句「下拉还不刷新新的歌」有一半是这个）。
     *
     * 语义上它等价于 `onQueryChanged(同一个词)`：页码归 1、累计清空、
     * 两源重新取第一页。**没有「换一批」** —— 搜索接口本身不接受随机种子，
     * 同一个关键词在第 1 页返回的就是同一批歌。所以「下拉刷新」在这里的
     * 真实含义是「重新取一次」，而不是「换一批」。
     */
    fun refresh() {
        val keyword = _query.value
        if (keyword.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchByType(_currentType.value, append = false)
        }
    }

    fun onTypeChanged(type: Int) {
        _currentType.value = type
        if (_query.value.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                searchByType(type)
            }
        }
    }

    /**
     * v2.5.5 · G：把两侧结果排序后发布。
     *
     * 从 `searchByType` 的 `1 ->` 分支里提出来只是因为那个分支现在多了一层
     * `coroutineScope { }`，内联的局部函数会横跨协程边界 —— 语义没变，
     * v2.1.4 的会员排序与 v2.3.0 的沉底规则**一个字没动**。
     */
    private fun publish(
        neteaseList: List<SongItem>,
        qqList: List<SongItem>,
        // v3.1.0 · B：默认空列表 ⇒ 所有旧调用点与单测零改动，且**行为与 v3.0.0 相同**。
        biliList: List<SongItem> = emptyList(),
    ): List<SongItem> {
        val (neteaseVip, qqVip) = vipFlagsProvider()
        // v2.3.0 · C：`order` = v2.1.4 的 `rank`（会员买在哪家哪家先出）
        // + 把「服务端显式声明无版权」的行沉底。两者作用在不同的层，见其 KDoc。
        // v3.1.0 · B：三源重载把 B 站**追加**在最后（B 站没有会员信号，
        // 不参与交错；`bili` 为空时与两源版本逐字相同）。
        val ranked = SearchRanking.order(
            netease = neteaseList.map {
                RankedSong(
                    it,
                    TrackAccess.ofNeteaseFee(it.fee),
                    TrackAvailability.of(it),
                )
            },
            qq = qqList.map {
                RankedSong(
                    it,
                    TrackAccess.ofQqMemberOnly(it.memberOnly),
                    TrackAvailability.of(it),
                )
            },
            bili = biliList.map {
                // B 站没有版权字段（`TrackAvailability.of` 对它是恒 UNKNOWN），
                // 会员判定同样没有依据 ⇒ UNKNOWN。两者都是「不参与重排」的中性取值。
                RankedSong(
                    it,
                    TrackAccess.UNKNOWN,
                    TrackAvailability.of(it),
                )
            },
            neteaseVip = neteaseVip,
            qqVip = qqVip,
        ).map { it.value }.distinctBy { it.trackKey }
        _songs.value = ranked
        return ranked
    }

    // ---------------- v3.4.11：分页（「加载更多」）----------------

    /**
     * 翻页时要靠它把「新一页的排名结果」并进「已发布的排名结果」。
     *
     * 两边都过同一个 [SearchRanking.order]，所以追加后的相对顺序是稳定的 ——
     * 直接 `+` 会得到一个「第一页按会员排好、第二页按请求顺序接在后面」的列表，
     * 那是**两个排序口径拼在一起**，用户能看出来。
     */
    private var accumulatedSongs: List<SongItem> = emptyList()

    /** 新一轮搜索（关键词变了 / 换 tab / 下拉刷新）时清空累计。**与 append 成对使用**。 */
    private fun resetSongs() {
        lastTotals.clear()
        accumulatedSongs = emptyList()
        lastSongs = emptyList()
        lastQqSongs = emptyList()
        lastBiliSongs = emptyList()
    }

    /**
     * 把这一页的逐源结果并进累计，并返回**排名后的完整列表**（已发布到 [_songs]）。
     *
     * @param append `false` = 新的一轮（丢弃累计，只留这一页）；
     *   `true` = 加载更多（追加到累计，逐源各自拼接再**整体重排**）。
     *   注意「拼接后整体重排」而不是「把新结果接在旧列表后面」——
     *   后者会让第二页的会员专享曲排在第一页的非会员曲之后，而按会员排序是本应用的既有契约。
     */
    private fun accumulateAndPublish(
        netease: List<SongItem>,
        qq: List<SongItem>,
        bili: List<SongItem>,
        append: Boolean,
    ): List<SongItem> {
        if (!append) {
            lastSongs = netease
            lastQqSongs = qq
            lastBiliSongs = bili
        } else {
            lastSongs = lastSongs + netease
            lastQqSongs = lastQqSongs + qq
            lastBiliSongs = lastBiliSongs + bili
        }
        val fresh = publish(lastSongs, lastQqSongs, lastBiliSongs)
        accumulatedSongs = if (append) dedupeSongs(accumulatedSongs + fresh) else fresh
        _songs.value = accumulatedSongs
        return accumulatedSongs
    }

    /** 按身份去重（跨页重复：服务端分页边界可能重发同一条）。工具方法，纯函数语义。 */
    private fun dedupeSongs(list: List<SongItem>): List<SongItem> =
        com.takahashirinta.ncrust.search.SearchPaging.distinct(list)

    /**
     * 「加载更多」：把页码 +1，重跑当前 tab 的搜索并以**追加**方式发布。
     *
     * 幂等与并发：正在飞（[isLoadingMore]）或没有下一页（[hasMore]）时直接返回 ——
     * 「列表滚到底连点三次」不该发三次请求。
     */
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return
        if (_query.value.isBlank()) return
        _isLoadingMore.value = true
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            currentPage += 1
            try {
                searchByType(_currentType.value, append = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 翻页失败**不回滚页码**：下一轮 loadMore 会再试同一页（页码回滚会让
                // 「失败 → 再点」变成「重取上一页」，用户看到的还是同一批歌）。
                android.util.Log.w("SearchViewModel", "loadMore failed page=$currentPage", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * 跑一轮搜索。
     *
     * @param append v3.4.11：`true` = 这是「加载更多」，把结果**追加**到当前列表
     *   （而不是替换）。三个 tab 共用这一个开关。
     */
    private suspend fun searchByType(type: Int, append: Boolean = false) {
        if (!append) {
            _isLoading.value = true
            // 新一轮（关键词变了 / 换 tab / 下拉刷新）：页码归 1、累计清空。
            // ⚠️ 这两行必须在**取数之前** —— 否则第一页会带着上一轮的 offset 发出去。
            currentPage = 1
            resetSongs()
        }
        _error.value = null
        try {
            when (type) {
                1 -> {
                    // v2.1.0 · E：**聚合搜索** —— ncm 与 qm。
                    //
                    // ## hotfix 3 留下的顺序契约（**本版没有改它**）
                    //
                    // 第一版写成「先 await ncm、再 await QQ，最后一起发布」。这在 QQ 那条
                    // 通道慢或不可达时是灾难：OkHttp 的 connect/read 超时是 15/20 秒，
                    // ncm 的结果明明已经到手，却要陪着一起等 —— 用户看到的就是**一直转圈**。
                    // 现在的顺序：
                    //  ① ncm（主源）拿到就**立刻发布并停止转圈**；
                    //  ② QQ（补充源）带**硬预算**地追加，超时就放弃这一轮；
                    //  ③ 两个源都空且 ncm 报过错 ⇒ 把错误交出去，界面不留一块哑掉的空白。
                    //
                    // ## v2.5.5 · G 改了两件事（都是「并发」与「状态」，不是「顺序」）
                    //
                    // **(1) 两个请求并发发起。** 旧实现是「await ncm → 再发 QQ」——
                    // 整体耗时是**和**而不是**最大值**。用户报告的「ncm 秒出、QQ 5 秒后到」
                    // 里，那 5 秒中其实有一段是白白串行等出来的。
                    // `async`（默认 start = DEFAULT，立即开始）把两段重叠起来，
                    // 而**发布顺序一个字没改**：仍然是 ncm 一到就 publish。
                    //
                    // **(2) 统计量能表达「还没回来」。** 旧代码在 ncm 到手时写
                    // `_sourceCounts.value = netease.size to 0` —— 那个 0 在界面上是
                    // 「qm 0 首」，而它的真实含义是「QQ 还没回来」。
                    // 用户据此以为 QQ 搜不到那首歌。现在写 [SourceSearchStatus.PENDING]，
                    // 界面显示「搜索中…」，QQ 回来后原地更新成计数。
                    val keyword = _query.value
                    val startedAt = System.currentTimeMillis()
                    val qqAllowed = QqClient.isLoggedIn() || QqAccountAvailability.allowAnonymousSearch
                    // v3.1.0 · B：B 站由**用户开关**决定（铁律 24）。关着时不发请求，
                    // 统计行显示「未启用」而不是「0 首」—— 与 QQ 的 SKIPPED 同一条纪律。
                    val biliAllowed = BiliSourceProvider.isEnabled
                    // v2.5.6 · P1：本轮分段耗时。`dispatch` 打在两个 `async` 真正启动之前 ——
                    // 它必须包含「请求已经发出去了但首字节还没回来」那一段，
                    // 否则 TTFB 会被算漏（探针 §5 指出这正是旧埋点最缺的一格）。
                    val trace = SearchLatencyTrace(keyword)
                    trace.mark(SearchLatencyTrace.MARK_DISPATCH)

                    coroutineScope {
                        // ncm：结果与异常一起回传，不用共享可变变量跨协程写。
                        // ⚠️ 只发**一次**请求：`runCatching` 包住调用，异常从 `exceptionOrNull()` 取，
                        // 绝不能为了拿异常而再调一次 `search(...)`（那会把一次搜索变成两次）。
                        val neteaseDeferred = async {
                            val outcome = runCatching {
                                // ⚠️ v2.5.6 曾在这里改用过 `searchApi`（多一个 20s `callTimeout`），
                                // 真机上**造成回归**：该网络下每通请求要 ~30s 才送出请求头，
                                // 20s 的 callTimeout 于是拦掉了本来会成功的搜索 ⇒ ncm 恒 0 首。
                                // 已撤销，回到共用的 `api`。根因与证据见 `RetrofitClient` 里那段撤销注释。
                                RetrofitClient.api.search(
                                    keyword = keyword,
                                    type = 1,
                                    limit = PAGE_SIZE,
                                    // v3.4.11：翻页靠的就是这个 offset（接口本来就是分页的）。
                                    offset = com.takahashirinta.ncrust.search.SearchPaging
                                        .offsetOf(currentPage, PAGE_SIZE),
                                ).result?.also { r ->
                                    // v3.4.11：总数（实测 `s=周杰伦` → songCount=273）。
                                    r.songCount?.let { lastTotals["netease"] = it }
                                }?.songs
                            }
                            val failure = outcome.exceptionOrNull()
                            if (failure != null) {
                                android.util.Log.w("SearchViewModel", "netease search failed", failure)
                            }
                            outcome.getOrNull().orEmpty() to failure
                        }
                        // QQ：**硬预算**。超时/异常都只是「这一轮没有 QQ 结果」，
                        // 绝不能让它把已经可用的搜索结果拖住或清掉。
                        //
                        // 注意：这里不能用 `runCatching { withTimeoutOrNull { ... } }` ——
                        // runCatching 的 lambda 不是 suspend 的，里面调不了挂起函数。
                        val qqDeferred = if (qqAllowed) async {
                            try {
                                val r = withTimeoutOrNull(QQ_SEARCH_BUDGET_MS) {
                                    SourceRouter.searchSongs(
                                        MusicSource.QQMUSIC,
                                        keyword,
                                        PAGE_SIZE,
                                        page = currentPage,
                                        totalOut = lastTotals,
                                    )
                                }
                                // `withTimeoutOrNull` 返回 null = 预算用完 ⇒ 记成 TIMEOUT
                                // 而不是「0 首」。这两件事在界面上必须能区分。
                                r?.let { QqOutcome(it, timedOut = false) } ?: QqOutcome(emptyList(), timedOut = true)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // 取消不是失败：用户改了关键词 / 离开页面时取消这一轮，
                                // 把它记成「失败」会让界面在下一次搜索开始前闪一条错误。
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("SearchViewModel", "qq search failed", e)
                                // ★ 区分「预算用完」与「真的失败」：两者对用户的处置相同
                                //   （都要点重试），但把「连不上」说成「超时」是替他编原因。
                                QqOutcome(emptyList(), timedOut = false, failed = true)
                            }
                        } else {
                            null
                        }
                        // B 站：与 QQ 同一条纪律（硬预算 + 超时/失败都只是「这一轮没有它」）。
                        // ⚠️ 它**不能**把已有结果拖住：预算 4s、且发布顺序一个字没改。
                        val biliDeferred = if (biliAllowed) async {
                            try {
                                val r = withTimeoutOrNull(BILI_SEARCH_BUDGET_MS) {
                                    // B 站不分页（接口没有页码），永远只有第一页 —— 见它的 KDoc。
                                    SourceRouter.searchSongs(MusicSource.BILIBILI, keyword, 20)
                                }
                                r?.let { BiliOutcome(it, timedOut = false) }
                                    ?: BiliOutcome(emptyList(), timedOut = true)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("SearchViewModel", "bili search failed", e)
                                BiliOutcome(emptyList(), timedOut = false, failed = true)
                            }
                        } else {
                            null
                        }

                        // ① **先到先发布**（v2.5.6 · P1）—— 本版对搜索延迟唯一有效的客户端改动。
                        //
                        // ## 旧实现的缺陷（真机证据，不是推理）
                        //
                        // 旧代码第一行是 `val (netease, _) = neteaseDeferred.await()`：
                        // ncm 那条腿**无论多慢**都是发布前的唯一闸门 —— QQ 先回来也白搭，
                        // 因为 `publish()` 在 await 之后。探针在 PLC110 上抓到的形状是
                        // `elapsed=30006ms netease=0 qq=30 qqTimedOut=false`：
                        // QQ 的 30 条在 ≤5 秒就已经到手，界面却空了 30 秒。
                        // 根因是 ncm 那条 OkHttp 只有 read/connect 超时、**没有 callTimeout**。
                        // （v2.5.6 一度给它加过 20s `callTimeout`，但真机证明它会拦掉本来会成功的
                        //  请求 —— 已撤销，见 `RetrofitClient` 里那段撤销注释。）
                        //
                        // ## 现在的语义
                        //
                        // 谁先回来谁先上屏（转圈随之结束），另一条回来后再合并发布一次。
                        // **「ncm 先到」这条主路径的行为与 v2.5.5 逐字相同** ——
                        // 探针实测 6/6 样本都是 ncm 先到，所以这是一个「只影响异常路径」的改动。
                        //
                        // ## 为什么用 `select` 而不是「先 await QQ 带超时、再 await ncm」
                        //
                        // 那种写法会给 QQ 引入一个**额外的**前置等待，把 ncm 先到的常见情形变慢。
                        // `select` 是「谁先完成用谁」，对两条腿都不加延迟。
                        var netease: List<SongItem>? = null
                        var neteaseError: Throwable? = null
                        var qqOutcome: QqOutcome? = null
                        var biliOutcome: BiliOutcome? = null

                        if (qqDeferred != null || biliDeferred != null) {
                            kotlinx.coroutines.selects.select {
                                neteaseDeferred.onAwait { (songs, failure) ->
                                    netease = songs
                                    neteaseError = failure
                                    trace.mark(SearchLatencyTrace.MARK_NETEASE_DONE)
                                }
                                qqDeferred?.onAwait { outcome ->
                                    qqOutcome = outcome
                                    trace.mark(SearchLatencyTrace.MARK_QQ_DONE)
                                }
                                biliDeferred?.onAwait { outcome ->
                                    biliOutcome = outcome
                                    trace.mark(SearchLatencyTrace.MARK_BILI_DONE)
                                }
                            }
                        } else {
                            val (songs, failure) = neteaseDeferred.await()
                            netease = songs
                            neteaseError = failure
                            trace.mark(SearchLatencyTrace.MARK_NETEASE_DONE)
                        }

                        // v2.1.4：发布前先按「用户有哪些平台的会员」排一次。此时另一条腿可能还没到，
                        // 但已到的那一侧的会员专享可以先排上去；等齐了会再排一次。
                        // 排序是纯函数且幂等，排两次不会抖。
                        //
                        // ⚠️ 旧实现这一处**没有** `_query.value == keyword` 守卫，本版补上：
                        // 用户已经改了关键词时，这一屏结果下一秒就会被新一轮覆盖，
                        // 写进去只会闪一下过期数据。补守卫**不会**留下空白 ——
                        // 新的一轮搜索自己会发布。
                        if (_query.value == keyword) {
                            // v3.4.11：`append` 时并进累计并整体重排（见 accumulateAndPublish）。
                            accumulateAndPublish(
                                netease.orEmpty(),
                                qqOutcome?.songs.orEmpty(),
                                biliOutcome?.songs.orEmpty(),
                                append = append,
                            )
                            _albums.value = emptyList()
                            _artists.value = emptyList()
                            _sourceCounts.value = SourceCounts(
                                neteaseCount = netease?.size ?: 0,
                                // 还没回来的那一侧写 PENDING，**不是** DONE + 0 ——
                                // 「搜索中…」与「0 首」在界面上必须是两句话（v2.5.5 · G 的既有契约）。
                                neteaseStatus = if (netease != null) {
                                    SourceSearchStatus.DONE
                                } else {
                                    SourceSearchStatus.PENDING
                                },
                                qqCount = lastQqSongs.size,
                                qqStatus = qqStatusOf(qqOutcome, qqAllowed),
                                biliCount = biliOutcome?.songs?.size ?: 0,
                                biliStatus = biliStatusOf(biliOutcome, biliAllowed),
                            )
                            // 转圈到此结束 —— 屏幕上已经有东西了。
                            _isLoading.value = false
                            trace.mark(SearchLatencyTrace.MARK_FIRST_PUBLISH)
                        }

                        // ② 等另一条腿（预算已经卡死，这里不会无限等）。
                        val neteaseArrivedSecond = netease == null
                        if (neteaseArrivedSecond) {
                            val (songs, failure) = neteaseDeferred.await()
                            netease = songs
                            neteaseError = failure
                            trace.mark(SearchLatencyTrace.MARK_NETEASE_DONE)
                        }
                        if (qqDeferred != null && qqOutcome == null) {
                            qqOutcome = qqDeferred.await()
                            trace.mark(SearchLatencyTrace.MARK_QQ_DONE)
                        }
                        if (biliDeferred != null && biliOutcome == null) {
                            biliOutcome = biliDeferred.await()
                            trace.mark(SearchLatencyTrace.MARK_BILI_DONE)
                        }

                        val neteaseList = netease.orEmpty()
                        val qq = qqOutcome?.songs.orEmpty()
                        val bili = biliOutcome?.songs.orEmpty()

                        // ③ 合并发布**只在内容真的会变时**做。
                        //
                        // 第一次发布时缺的那一侧现在到了，但它是**空**的 ⇒ 合并结果与
                        // 已发布的那一份逐字节相同，重发只会给 StateFlow 塞一个内容相同的新
                        // list 实例，白白触发一次列表重组（铁律 17）。
                        // v2.5.5 原来的守卫是 `qq.isNotEmpty()`（只覆盖「QQ 后到」），
                        // 本版把它推广到两侧 —— 「ncm 后到且为空」同样不需要重发。
                        val shouldRepublish = if (neteaseArrivedSecond) {
                            neteaseList.isNotEmpty()
                        } else {
                            qq.isNotEmpty() || bili.isNotEmpty()
                        }
                        if (shouldRepublish && _query.value == keyword) {
                            accumulateAndPublish(neteaseList, qq, bili, append = append)
                            trace.mark(SearchLatencyTrace.MARK_MERGED_PUBLISH)
                        }
                        // v3.4.11：单曲 tab 的「还有没有下一页」。
                        //
                        // 判据是**这一页有没有被填满**（三条腿任一条满就算）：
                        // 两个音源的搜索响应都没有稳定的总条数字段可按，所以只能这样判 ——
                        // 代价是最后一页会多给一次点击，而**不会漏页**（后者才是缺陷）。
                        // 与 `MusicSourceProvider.hasMorePages` 是同一个判据，
                        // 这里内联是因为答案已经在手上（不必为了问一句再发一次请求）。
                        if (_query.value == keyword) {
                            _hasMore.value = com.takahashirinta.ncrust.search.SearchPaging
                                .hasMoreAny(neteaseList.size, qq.size, pageSize = PAGE_SIZE)
                        }
                        if (_query.value == keyword) {
                            _sourceCounts.value = SourceCounts(
                                neteaseTotal = lastTotals["netease"],
                                qqTotal = lastTotals["qqmusic"],
                                // ⚠️ 这里填的是**累计已加载**（`lastSongs`），不是「本页」。
                                // 服务端给了总数时界面显示总数（那一栏与翻页无关）；
                                // **没给**时回落到「已载 N 首」，而那个 N 必须是累计 ——
                                // 填本页条数会让它翻页前后都显示 30，看起来像「没有实时更新」
                                // （用户报的正是这句话）。
                                neteaseCount = lastSongs.size,
                                neteaseStatus = SourceSearchStatus.DONE,
                                qqCount = qq.size,
                                qqStatus = qqStatusOf(qqOutcome, qqAllowed),
                                biliCount = bili.size,
                                biliStatus = biliStatusOf(biliOutcome, biliAllowed),
                            )
                        }
                        // ④ 两个源都没结果，且主源确实报过错 ⇒ 让界面能显示错误/重试，
                        // 而不是一块什么都没有的空白。
                        if (neteaseList.isEmpty() && qq.isEmpty() && bili.isEmpty() && neteaseError != null) {
                            _error.value = neteaseError?.message
                        }
                        val (nVip, qVip) = vipFlagsProvider()
                        trace.mark(SearchLatencyTrace.MARK_DONE)
                        android.util.Log.i(
                            "SearchViewModel",
                            "aggregate query='$keyword' netease=${neteaseList.size} qq=${qq.size} " +
                                "bili=${bili.size} biliAllowed=$biliAllowed " +
                                "qqTimedOut=${qqOutcome?.timedOut} qqAllowed=$qqAllowed " +
                                "vip(netease=$nVip qq=$qVip) " +
                                "elapsed=${System.currentTimeMillis() - startedAt}ms",
                        )
                        // v2.5.6 · P1：分段耗时。**这一行在 release 包里必须存在** ——
                        // 「TTFB 到 UI 更新之间花在哪」是铁律 20/新规则 3 要求有数据支撑的结论，
                        // 而 debug 包的数字不得作基线（铁律 16）。
                        android.util.Log.i(SearchLatencyTrace.TAG, trace.summary())
                    }
                }
                // ── v3.3.0 · 需求 2：**按歌词搜索**（两个音源原生都支持）────────────────
                //
                // 用户原话「用户可以用歌词搜索有对应歌词的音乐」。这条需求的关键是
                // **它不是一个音源的能力，而是三个音源各自的能力之和**：
                //
                // | 音源 | 歌词搜索 | 实测依据 |
                // |---|---|---|
                // | ncm | `cloudsearch/pc` 的 **`type=1006`** | 搜「让我掉下眼泪的」→ `songCount:60`，首条《成都》- 赵雷 |
                // | qm | 旧版 `client_search_cp` 的 **`t=7`** | 响应把条目放在 `data.lyric.list`，首条同为《成都》- 赵雷 |
                // | B 站 | **没有** | v3.1.0 的探针已穷举：`search_type=music`/`audio` 与非法值 `foobar` 返回**同一个** `-1200 被降级过滤的请求` ⇒ 取值非法；音频区只有播/词/元数据接口，搜索类全部下线（`docs/verification/v3.1.0/bili-bili-audio-api.md` §6.2）。搜索走视频区，而视频没有「歌词」这个可检索字段 |
                //
                // 所以这里聚合**两路**，B 站如实缺席 —— 而不是假装它也有、或拿视频标题去凑。
                // 两路都用各自**原生**的能力，没有一方是靠猜关键词实现的。
                //
                // 并发与发布顺序沿用单曲搜索的既有纪律（见上面 `1 ->` 分支的长注释）：
                // ncm 是主源、拿到就发布并停止转圈；QQ 是补充源、带硬预算。
                1006 -> {
                    coroutineScope {
                        val keyword = _query.value
                        val qqAllowed = QqClient.isLoggedIn() || QqAccountAvailability.allowAnonymousSearch
                        // 先声明「这一轮 QQ 会不会发请求」，让统计行能区分
                        // 「搜了但 0 首」（DONE+0）与「这一轮没发起」（SKIPPED）。
                        _sourceCounts.value = SourceCounts(
                            neteaseCount = 0,
                            neteaseStatus = SourceSearchStatus.PENDING,
                            qqCount = 0,
                            qqStatus = if (qqAllowed) SourceSearchStatus.PENDING else SourceSearchStatus.SKIPPED,
                            biliCount = 0,
                            biliStatus = SourceSearchStatus.SKIPPED,
                        )
                        val neteaseDeferred = async {
                            runCatching {
                                RetrofitClient.api.searchLyric(keyword = keyword, type = 1006)
                            }.getOrNull()?.result?.songs ?: emptyList()
                        }
                        // 两路都用**不带 dispatcher 参数**的 `async`：与上面 `1 ->` 分支同形。
                        // 加 `async(Dispatchers.IO)` 在本仓库解析不到 `await`（既有代码从没这么用过），
                        // 而且网络调用本身已在 OkHttp 的 IO 线程上执行，不需要在这里换线程。
                        val qqDeferred = async {
                            if (!qqAllowed) return@async emptyList()
                            // ⚠️ **不要**在 `withTimeoutOrNull` 后面接 `?: emptyList()`：
                            // 那会把「超时」压成「0 条」，而这两个状态在统计行上必须分开
                            // （一个要给可点的重试，另一个是真的没有结果）。
                            withTimeoutOrNull(QQ_SEARCH_BUDGET_MS) {
                                runCatching { QqApi.searchSongsByLyric(keyword, QQ_LYRIC_LIMIT) }
                                    .getOrNull() ?: emptyList()
                            }
                        }

                        // ① ncm 是主源：到手就发布并停止转圈（与 `1 ->` 分支同一条纪律）。
                        val neteaseList = neteaseDeferred.await()
                        publish(neteaseList = neteaseList, qqList = emptyList())
                        _sourceCounts.value = SourceCounts(
                            neteaseCount = neteaseList.size,
                            neteaseStatus = SourceSearchStatus.DONE,
                            qqCount = 0,
                            qqStatus = if (qqAllowed) SourceSearchStatus.PENDING else SourceSearchStatus.SKIPPED,
                            biliCount = 0,
                            biliStatus = SourceSearchStatus.SKIPPED,
                        )
                        _isLoading.value = false

                        // ② 等 QQ（预算已卡死，不会无限等）。
                        // ② 等 QQ（预算已卡死，不会无限等）。
                        //
                        // 类型写成 `Deferred<List<SongItem>?>`：**null 表示预算用完**（超时），
                        // 空列表表示「搜到了但确实 0 条」。两者必须分开 ——
                        // 拿「空列表」当超时的代理，会让一次真的 0 结果显示成「搜索超时」，
                        // 那正是 v2.5.5 修掉的「用 0 代表未知」的同一个形状。
                        val qqResult: List<SongItem>? = qqDeferred.await()
                        val qqList = qqResult.orEmpty()
                        publish(neteaseList = neteaseList, qqList = qqList)
                        _sourceCounts.value = SourceCounts(
                            neteaseCount = neteaseList.size,
                            neteaseStatus = SourceSearchStatus.DONE,
                            qqCount = qqList.size,
                            qqStatus = when {
                                !qqAllowed -> SourceSearchStatus.SKIPPED
                                qqResult == null -> SourceSearchStatus.TIMEOUT
                                else -> SourceSearchStatus.DONE
                            },
                            biliCount = 0,
                            biliStatus = SourceSearchStatus.SKIPPED,
                        )
                        android.util.Log.i(
                            "SearchViewModel",
                            "lyric search query='$keyword' netease=${neteaseList.size} qq=${qqList.size} " +
                                "qqAllowed=$qqAllowed bili=SKIPPED(平台无此能力)",
                        )
                    }
                }

                // v3.4.11：专辑与艺人两个 tab 同样分页（它们此前也**只有 30 条**，
                // 与单曲 tab 是同一个缺陷形状）。`offset` 的语义与单曲一致。
                10 -> {
                    _sourceCounts.value = null
                    val offset = com.takahashirinta.ncrust.search.SearchPaging
                        .offsetOf(currentPage, PAGE_SIZE)
                    val response = RetrofitClient.api.searchAlbum(
                        keyword = _query.value,
                        type = 10,
                        limit = PAGE_SIZE,
                        offset = offset,
                    )
                    val page = response.result?.albums ?: emptyList()
                    // 跨页去重：服务端分页边界可能重发同一条（`id` 是它的身份）。
                    _albums.value = if (append) {
                        (_albums.value + page).distinctBy { it.id }
                    } else {
                        page
                    }
                    _hasMore.value = com.takahashirinta.ncrust.search.SearchPaging
                        .hasMore(page.size, PAGE_SIZE)
                    // v3.4.11：统计行现在**三个 tab 常驻**（用户在搜索栏下方看到的是一整条），
                    // 所以专辑 tab 也要填它，否则切过去那一行是空的。
                    // 专辑搜索目前**只有 ncm 这一路**（`searchAlbum` 是 ncm 独有），
                    // QQ/B 站如实标成「未登录/未启用」，不编一个数字出来。
                    _sourceCounts.value = SourceCounts(
                        neteaseCount = _albums.value.size,
                        neteaseTotal = response.result?.albumCount,
                        neteaseStatus = SourceSearchStatus.DONE,
                        qqCount = 0,
                        // QQ 侧恒 SKIPPED：专辑搜索这一路**没有** QQ 的实现（如实标注，
                        // 而不是写个 if 让它在两个相同分支里假装有区别）。
                        qqStatus = SourceSearchStatus.SKIPPED,
                        biliCount = 0,
                        biliStatus = SourceSearchStatus.SKIPPED,
                    )
                    _songs.value = emptyList()
                    _artists.value = emptyList()
                }
                100 -> {
                    _sourceCounts.value = null
                    val offset = com.takahashirinta.ncrust.search.SearchPaging
                        .offsetOf(currentPage, PAGE_SIZE)
                    val response = RetrofitClient.api.searchArtist(
                        keyword = _query.value,
                        type = 100,
                        limit = PAGE_SIZE,
                        offset = offset,
                    )
                    val page = response.result?.artists ?: emptyList()
                    _artists.value = if (append) {
                        (_artists.value + page).distinctBy { it.id }
                    } else {
                        page
                    }
                    _hasMore.value = com.takahashirinta.ncrust.search.SearchPaging
                        .hasMore(page.size, PAGE_SIZE)
                    // 同专辑 tab：统计行常驻，这里要填（艺人搜索同样只有 ncm 一路）。
                    _sourceCounts.value = SourceCounts(
                        neteaseCount = _artists.value.size,
                        neteaseTotal = response.result?.artistCount,
                        neteaseStatus = SourceSearchStatus.DONE,
                        qqCount = 0,
                        qqStatus = SourceSearchStatus.SKIPPED,
                        biliCount = 0,
                        biliStatus = SourceSearchStatus.SKIPPED,
                    )
                    _songs.value = emptyList()
                    _albums.value = emptyList()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ★ v2.5.5 · G：**取消必须原样抛出**，不能落进下面的 `catch (e: Exception)`。
            //
            // `searchJob.cancel()` 在用户每敲一个字（500ms debounce 之后）都会触发一次。
            // 旧写法把 `CancellationException` 当成普通异常：写 `_error`、并且在结果为空时
            // 调 `clearResults()` —— 表现是「正在打字时界面闪一下错误 / 上一轮结果被清空」。
            // 协程的取消是**控制流**不是错误，吞掉它还会破坏结构化并发
            // （父作用域无法感知子协程已取消）。
            throw e
        } catch (e: Exception) {
            _error.value = e.message
            // v2.1.0 · E：只有在**一条结果都没有**时才清空。聚合搜索下一侧失败很正常
            // （QQ 未登录、被限流），此时把另一侧已经拿到的结果清掉是纯损失。
            if (_songs.value.isEmpty() && _albums.value.isEmpty() && _artists.value.isEmpty()) {
                clearResults()
            }
        } finally {
            _isLoading.value = false
        }
    }

    fun clearQuery() {
        _query.value = ""
        clearResults()
    }

    /**
     * v2.5.6 · P1：QQ 那一侧的状态映射，**唯一**定义处。
     *
     * 本版把「先到先发布」拆成了两次发布（先到的 + 合并的），于是同一段 `when`
     * 会在两处被需要。抽成一个函数而不是抄两遍：
     * 抄两遍的版本在 v2.5.5 已经有先例 —— 那正是「PENDING 被写成 DONE + 0」的温床。
     *
     * `outcome == null` 的语义是「**还没回来**」，必须映射成 [SourceSearchStatus.PENDING]
     * 而不是 `DONE + 0`：界面上「搜索中…」与「0 首」是两句话，
     * 后者是对用户的**假话**（v2.5.5 · G 修复的那个缺陷）。
     */
    private fun qqStatusOf(outcome: QqOutcome?, allowed: Boolean): SourceSearchStatus = when {
        !allowed -> SourceSearchStatus.SKIPPED
        outcome == null -> SourceSearchStatus.PENDING
        outcome.timedOut -> SourceSearchStatus.TIMEOUT
        outcome.failed -> SourceSearchStatus.ERROR
        else -> SourceSearchStatus.DONE
    }

    /**
     * v3.1.0 · B：B 站那一侧的状态映射。与 [qqStatusOf] **逐条同构**，
     * 只是把「未登录」换成了「用户没启用」——两者在界面上都是「这一轮没发起」。
     */
    private fun biliStatusOf(outcome: BiliOutcome?, allowed: Boolean): SourceSearchStatus = when {
        !allowed -> SourceSearchStatus.SKIPPED
        outcome == null -> SourceSearchStatus.PENDING
        outcome.timedOut -> SourceSearchStatus.TIMEOUT
        outcome.failed -> SourceSearchStatus.ERROR
        else -> SourceSearchStatus.DONE
    }

    private fun clearResults() {
        _songs.value = emptyList()
        _albums.value = emptyList()
        _artists.value = emptyList()
        // 计数必须跟着一起清，否则清空搜索框后还会留着一行
        // 「ncm 20 首 · qm 6 首」挂在那儿（清空与清结果永远是同一件事）。
        _sourceCounts.value = null
    }
}

/**
 * v2.5.5 · G：QQ 补充源这一轮的结果。
 *
 * 单独一个类型（而不是 `List<SongItem>`）是为了把「超时」这件事**带出来**：
 * 旧代码只有一个列表，超时与「确实 0 条」在类型上完全一样，
 * 于是界面只能显示「qm 0 首」—— 而对超时来说那句话是错的。
 */
/**
 * v3.1.0 · B：B 站补充源这一轮的结果。与 [QqOutcome] 同构（超时与「确实 0 条」
 * 在类型上必须分得开 —— 那是 v2.5.5 用一整版修出来的东西，不能在新源上重犯）。
 */
private data class BiliOutcome(
    val songs: List<SongItem>,
    val timedOut: Boolean,
    val failed: Boolean = false,
)

private data class QqOutcome(
    val songs: List<SongItem>,
    /** `withTimeoutOrNull` 返回 null ⇒ 预算用完。 */
    val timedOut: Boolean,
    /**
     * 抛了异常 ⇒ 真的失败（网络错误 / 服务端报错）。
     *
     * 与 [timedOut] 分开：两者对用户的处置相同（都要点重试），
     * 但把「连不上」显示成「超时」是替用户编原因。
     */
    val failed: Boolean = false,
)
