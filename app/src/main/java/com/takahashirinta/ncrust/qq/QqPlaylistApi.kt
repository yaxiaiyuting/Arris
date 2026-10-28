/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：qm 用户歌单的**只读**接口层。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.PlaylistTrack

/**
 * qm 用户歌单的只读接口（v2.2.0）。
 *
 * ## 纪律：**本文件里只有读接口**
 *
 * `AddPlaylist` / `DelPlaylist` / `AddSonglist` / `DelSonglist` / `FavPlaylist` /
 * `CancelFavPlaylist` **一个都没有出现**，而且不应该出现 —— 本版明确不做写操作。
 * 它们的 module/method 只登记在探针文档
 * （`docs/verification/v2.2.0/qq-playlist-probe/PROBE.md` §7）里，不落到代码。
 *
 * ## 为什么每个方法都返回 [QqPlaylistResult] 而不是 null
 *
 * 四种失败在 UI 上的处置完全不同（去登录 / 回退 / 重试 / 报 bug）。用 null 会把它们
 * 混成一种，用户看到的就是「什么都点不动」。
 *
 * ## 登录态
 *
 * **只有 [checkLoginValid] 能判「登录态还有效吗」。** 实测（探针 §5）：
 * 无 cookie 时 `GetPlaylistByUin` / `CgiGetDiss` / `vip_login_base` **照样返回数据**，
 * 只有 `GetLoginUserInfo` 会返回 `code=1000`。所以：
 *
 * - 列表/详情拿到 `NeedLogin` ⇒ 可信，直接进登录过期态；
 * - 列表/详情成功 ⇒ **不能**据此认为登录态有效（它公开可读）；
 * - 需要确认登录态时，**单独**调 [checkLoginValid]。
 */
object QqPlaylistApi {

    private const val TAG = "QqPlaylistApi"

    /** 详情默认每页数量。实测 500/1000/2000 都被接受，这里取 200 兼顾首屏延迟与往返次数。 */
    const val DEFAULT_PAGE_SIZE = 200

    /**
     * 最多拉多少页。200 × 20 = 4000 首。
     *
     * 有上限是**有意的**：实测本账号/榜单都没有 500+ 的歌单，「大歌单分页」这条路径
     * 在真机上是**未充分验证**的（见探针 §3.3）。一个无上限的循环遇到服务端
     * 「永远 hasmore=1」的异常响应会把内存打满，所以这里必须有刹车。
     */
    const val MAX_PAGES = 20

    // ------------------------------------------------------------------ 用户 ----

    /**
     * 登录态自证 + 昵称/头像。
     *
     * 这是**唯一**可靠的登录态判据（见类文档）。返回 [QqPlaylistResult.NeedLogin]
     * 表示票据已失效，UI 应给「重新登录」入口。
     */
    suspend fun fetchUserInfo(): QqPlaylistResult<QqUserBrief> {
        QqTokenRefresher.refreshIfNeeded(QqClient.appContextOrNull())
        return QqRefreshRetry.request(
            once = {
                QqClient.musicu(QqRequests.loginUserInfo(), appIdentity = true)
                    ?.let(QqPlaylistParser::parseUserInfo) ?: QqPlaylistResult.Network
            },
            rejected = { it == QqPlaylistResult.NeedLogin },
            refresh = {
                QqTokenRefresher.refreshAfterRejection(QqClient.appContextOrNull()) == QqTokenRefresher.RefreshResult.REFRESHED
            },
            revision = QqAuthStore::currentAuthRevision,
            loggedIn = QqClient::isLoggedIn,
        )
    }

    suspend fun checkLoginValid(): QqPlaylistResult<QqUserBrief> = fetchUserInfo()

    // -------------------------------------------------------------- 歌单列表 ----

    /**
     * 用户自己的歌单（含 `dirId=201`「我喜欢」）。
     *
     * @param ownerId 当前账号 uin。它进 [PlaylistKey]，是双账号隔离的根据。
     */
    suspend fun fetchOwnedPlaylists(ownerId: String, session: QqReadSession? = null): QqPlaylistResult<List<Playlist>> {
        val resp = QqClient.musicu(QqRequests.playlistList(ownerId), appIdentity = true, session = session)
            ?: return QqPlaylistResult.Network
        return QqPlaylistParser.parseOwnedPlaylists(resp, ownerId)
    }

    /**
     * 收藏（他人）的歌单。
     *
     * @param encryptUin 由 [fetchDetailPage] 的 `dirinfo.encrypt_uin` 提供。
     *   传裸 uin 会得到 `80050`（实测），所以调用方必须先拿到它。
     */
    suspend fun fetchFavPlaylists(
        ownerId: String,
        encryptUin: String,
        offset: Int = 0,
        size: Int = 50,
        session: QqReadSession? = null,
    ): QqPlaylistResult<List<Playlist>> {
        val resp = QqClient.musicu(
            QqRequests.playlistFavList(encryptUin, offset, size),
            appIdentity = true, session = session,
        ) ?: return QqPlaylistResult.Network
        return QqPlaylistParser.parseFavPlaylists(resp, ownerId)
    }

    // -------------------------------------------------------------- 歌单详情 ----

    /**
     * 拉一页详情。
     *
     * @param disstid 全局歌单 id（[PlaylistKey.id]）。传 0 表示「用 dirId 寻址」——
     *   实测两种寻址等价，「我喜欢」用 `disstid=0, dirid=201` 打开最稳。
     */
    suspend fun fetchDetailPage(
        key: PlaylistKey,
        disstid: Long,
        dirId: Long,
        songBegin: Int,
        songNum: Int = DEFAULT_PAGE_SIZE,
        session: QqReadSession? = null,
    ): QqPlaylistResult<QqPlaylistPage> {
        if (session != null && !session.isCurrent()) return QqPlaylistResult.NeedLogin
        val resp = QqClient.musicu(
            QqRequests.playlistDetail(disstid, dirId, songBegin, songNum),
            appIdentity = true, session = session,
        ) ?: return QqPlaylistResult.Network
        if (session != null && !session.isCurrent()) return QqPlaylistResult.NeedLogin
        return QqPlaylistParser.parseDetailPage(resp, key, songBegin)
    }

    /**
     * 顺序拉完整个歌单（分页累积）。
     *
     * ## 四条实测出来的边界规则
     *
     * 1. **结束判据是 `hasmore`，不是「返回数 < 页大小」**：末页 `hasmore=0`；
     * 2. **返回 0 个原始条目也当结束**：实测越界 `song_begin` 返回 `code=0` + 0 首 + `hasmore=0`，
     *    所以「空页」必须能终止循环，否则会一直翻下去；
     * 3. **`order` 用全局 offset**：第 n 页的 `order` 从 `song_begin` 起算
     *    （由 [QqPlaylistParser.parseDetailPage] 保证），合并时不会互相覆盖；
     * 4. **游标按原始条目数前进（H21）**：`begin += page.rawCount`，不是映射后的
     *    `page.tracks.size`。有解析不出的条目时后者少走一格，下一页 offset 与上一页
     *    重叠（原探针实测 A、A、B）。缺 `songlist` 数组时既不能前进也不能当成
     *    「空页/完整」：本函数把它记进 [QqPlaylistPage.hasSonglist]，
     *    由 `QqPlaylistRepository.detailIsComplete` 判成不完整。
     *
     * @param onPage 每拉到一页就回调一次，让 UI 能**渐进显示**（大歌单不必等全部拉完）。
     *   回调在调用者的协程上下文里同步执行，实现方不要在里面做重活。
     * @return 累积后的完整页（`tracks` 为全部曲目，`hasMore` 表示是否因为 [MAX_PAGES]
     *   而被截断 —— 截断时**保持 true**，UI 不该以为已经拉完）。
     */
    suspend fun fetchAllPages(
        key: PlaylistKey,
        disstid: Long,
        dirId: Long,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxPages: Int = MAX_PAGES,
        onPage: (QqPlaylistPage) -> Unit = {},
        session: QqReadSession? = QqClient.pinReadSession(),
    ): QqPlaylistResult<QqPlaylistPage> {
        val all = ArrayList<PlaylistTrack>()
        val allSongs = ArrayList<com.takahashirinta.ncrust.network.SongItem>()
        var begin = 0
        var pages = 0
        var last: QqPlaylistPage? = null
        var truncated = false
        // H21：聚合页必须自己累计「原始条目数」与「songlist 是否出现过」——
        // copy() 只保留**最后一页**的那两个字段，照搬会让整张歌单的完整性判错。
        var rawTotal = 0
        var pagesHaveSonglist = true
        while (pages < maxPages) {
            if (session != null && !session.isCurrent()) return QqPlaylistResult.NeedLogin
            when (val r = fetchDetailPage(key, disstid, dirId, begin, pageSize, session)) {
                is QqPlaylistResult.Ok -> {
                    val page = r.value
                    last = page
                    all.addAll(page.tracks)
                    allSongs.addAll(page.songs)
                    rawTotal += page.rawCount
                    pagesHaveSonglist = pagesHaveSonglist && page.hasSonglist
                    onPage(page)
                    if (session != null && !session.isCurrent()) return QqPlaylistResult.NeedLogin
                    pages++
                    // 终止：① 服务端说没有下一页；② 本页连**原始条目**都没有 —— 越界空页、
                    // 以及缺 `songlist` 的结构异常。后者再翻也是同一个 offset，必须停。
                    if (!page.hasMore || page.rawCount == 0) {
                        truncated = false
                        break
                    }
                    // ★ H21：游标按**原始条目数**前进。用映射后的 `tracks.size` 会在有
                    //   解析不出的条目时少走一格，下一页的 offset 与上一页重叠
                    //   （原探针实测拿到 A、A、B）。
                    begin += page.rawCount
                    if (pages >= maxPages) truncated = true
                }
                // 第一页失败 ⇒ 整个请求失败；后续页失败 ⇒ 返回已经拿到的部分，
                // 但**标记 hasMore=true**，让 UI 知道「还没拉全」而不是「就这么多」。
                else -> if (pages == 0) return r else {
                    Log.w(TAG, "detail paging stopped early at begin=$begin: $r")
                    return QqPlaylistResult.Ok(
                        (last ?: emptyPage(key)).copy(
                            tracks = all, songs = allSongs,
                            rawCount = rawTotal, hasSonglist = pagesHaveSonglist,
                            hasMore = true,
                        )
                    )
                }
            }
        }
        val base = last ?: emptyPage(key)
        return QqPlaylistResult.Ok(
            base.copy(
                tracks = all, songs = allSongs,
                rawCount = rawTotal, hasSonglist = pagesHaveSonglist,
                hasMore = base.hasMore || truncated,
            )
        )
    }

    private fun emptyPage(key: PlaylistKey) = QqPlaylistPage(
        tracks = emptyList(),
        songs = emptyList(),
        total = 0,
        hasMore = false,
        dirId = 0L,
        tid = key.id.toLongOrNull() ?: 0L,
        name = null,
        coverUrl = null,
        encryptUin = null,
        // 兜底页没有服务端的 songlist：按结构异常处理（宁可报不完整，也别报"取全了"）。
        rawCount = 0,
        hasSonglist = false,
    )
}
