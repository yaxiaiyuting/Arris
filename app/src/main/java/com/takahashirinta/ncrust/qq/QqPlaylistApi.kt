/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
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
        val resp = QqClient.musicu(QqRequests.loginUserInfo(), appIdentity = true)
            ?: return QqPlaylistResult.Network
        return QqPlaylistParser.parseUserInfo(resp)
    }

    /** 只判登录态，不关心资料。 */
    suspend fun checkLoginValid(): Boolean =
        fetchUserInfo() is QqPlaylistResult.Ok

    // -------------------------------------------------------------- 歌单列表 ----

    /**
     * 用户自己的歌单（含 `dirId=201`「我喜欢」）。
     *
     * @param ownerId 当前账号 uin。它进 [PlaylistKey]，是双账号隔离的根据。
     */
    suspend fun fetchOwnedPlaylists(ownerId: String): QqPlaylistResult<List<Playlist>> {
        val resp = QqClient.musicu(QqRequests.playlistList(ownerId), appIdentity = true)
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
    ): QqPlaylistResult<List<Playlist>> {
        val resp = QqClient.musicu(
            QqRequests.playlistFavList(encryptUin, offset, size),
            appIdentity = true,
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
    ): QqPlaylistResult<QqPlaylistPage> {
        val resp = QqClient.musicu(
            QqRequests.playlistDetail(disstid, dirId, songBegin, songNum),
            appIdentity = true,
        ) ?: return QqPlaylistResult.Network
        return QqPlaylistParser.parseDetailPage(resp, key, songBegin)
    }

    /**
     * 顺序拉完整个歌单（分页累积）。
     *
     * ## 三条实测出来的边界规则
     *
     * 1. **结束判据是 `hasmore`，不是「返回数 < 页大小」**：末页 `hasmore=0`；
     * 2. **返回 0 首也当结束**：实测越界 `song_begin` 返回 `code=0` + 0 首 + `hasmore=0`，
     *    所以「空页」必须能终止循环，否则会一直翻下去；
     * 3. **`order` 用全局 offset**：第 n 页的 `order` 从 `song_begin` 起算
     *    （由 [QqPlaylistParser.parseDetailPage] 保证），合并时不会互相覆盖。
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
    ): QqPlaylistResult<QqPlaylistPage> {
        val all = ArrayList<PlaylistTrack>()
        val allSongs = ArrayList<com.takahashirinta.ncrust.network.SongItem>()
        var begin = 0
        var pages = 0
        var last: QqPlaylistPage? = null
        var truncated = false
        while (pages < maxPages) {
            when (val r = fetchDetailPage(key, disstid, dirId, begin, pageSize)) {
                is QqPlaylistResult.Ok -> {
                    val page = r.value
                    last = page
                    all.addAll(page.tracks)
                    allSongs.addAll(page.songs)
                    onPage(page)
                    pages++
                    // 「空页」与「hasmore=0」都可终止（实测两者都会出现在正常路径上）。
                    if (!page.hasMore || page.tracks.isEmpty()) {
                        truncated = false
                        break
                    }
                    begin += page.tracks.size
                    if (pages >= maxPages) truncated = true
                }
                // 第一页失败 ⇒ 整个请求失败；后续页失败 ⇒ 返回已经拿到的部分，
                // 但**标记 hasMore=true**，让 UI 知道「还没拉全」而不是「就这么多」。
                else -> if (pages == 0) return r else {
                    Log.w(TAG, "detail paging stopped early at begin=$begin: $r")
                    return QqPlaylistResult.Ok(
                        (last ?: emptyPage(key)).copy(tracks = all, songs = allSongs, hasMore = true)
                    )
                }
            }
        }
        val base = last ?: emptyPage(key)
        return QqPlaylistResult.Ok(
            base.copy(tracks = all, songs = allSongs, hasMore = base.hasMore || truncated)
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
    )
}
