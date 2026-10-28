/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：QQ 歌单的「缓存优先 + 只读同步 + 离线可看 + 登录态降级」。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.PlaylistCacheCodec
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistDetailData
import com.takahashirinta.ncrust.playlist.PlaylistHardFailure
import com.takahashirinta.ncrust.playlist.PlaylistLoadOutcome
import com.takahashirinta.ncrust.playlist.PlaylistResult

/**
 * QQ 歌单仓库（v2.2.0）。
 *
 * ## 加载策略（对齐本项目既有的 `ContentCache` 读取约定）
 *
 * 1. **先读缓存**：有就立刻返回，UI 不显示空白加载态；
 * 2. **再决定要不要联网**：`fresh && !forceRefresh` ⇒ 直接结束（**不做自动刷新**）；
 * 3. 联网成功 ⇒ 写缓存并返回；
 * 4. 联网失败 ⇒ **回落到缓存**（离线可看），带 [PlaylistDegradation.OFFLINE]；
 * 5. 联网说「登录态失效」⇒ 回落到缓存**并**带 [PlaylistDegradation.NEED_LOGIN]；
 * 6. 缓存也没有且联网失败 ⇒ [PlaylistResult.Failed]。
 *
 * ## 为什么「不做自动刷新」
 *
 * 任务书第 4.1 条要求「不做自动刷新，手动下拉刷新」。所以 [loadList] 的
 * `forceRefresh = false` 时，**只要缓存新鲜就一个请求都不发** ——
 * 这既省流量，也让「用户没点刷新，列表就不会变」成为一个可预期的行为。
 * 缓存过期（[PlaylistCacheCodec.DEFAULT_TTL_MS]）时会自动联网一次，这是「失效」而不是「刷新」。
 */
object QqPlaylistRepository {

    private const val TAG = "QqPlaylistRepo"

    /** TTL 之后缓存算「过期」，会触发一次自动联网。见类文档第 2 条。 */
    var ttlMs: Long = PlaylistCacheCodec.DEFAULT_TTL_MS

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 当前账号标识。
     *
     * 未登录时返回 [PlaylistKey.OWNER_ANONYMOUS] —— **不是空串**：
     * 空串会让「未登录」与「字段缺失」混为一谈（v1.9.2 `romalrc` 的教训）。
     */
    fun currentOwnerId(context: Context? = appContext): String {
        val ctx = context ?: return PlaylistKey.OWNER_ANONYMOUS
        val uin = QqAuthStore.uin(ctx)
        return if (uin != null && uin > 0L) uin.toString() else PlaylistKey.OWNER_ANONYMOUS
    }

    private fun now() = System.currentTimeMillis()

    // ---------------------------------------------------------------- 列表 ----

    /**
     * 加载用户歌单列表（自己的 + 收藏的）。
     *
     * 收藏歌单需要 `encrypt_uin`，而它只能从**详情**响应里取到（实测：传裸 uin 得 `80050`）。
     * 所以顺序是：自己的列表 → 用「我喜欢」的 `dirId=201` 拉一页详情拿 `encrypt_uin` → 收藏列表。
     * 中间任何一步失败都**不影响**已经拿到的自己的歌单（收藏列表只是附加项）。
     */
    suspend fun loadList(forceRefresh: Boolean): PlaylistResult<List<Playlist>> {
        val ctx = appContext ?: return PlaylistResult.Failed(PlaylistHardFailure.Network)
        val ownerId = currentOwnerId(ctx)

        val cached = QqPlaylistStore.readList(ctx, ownerId, now())
        val cachedList = (cached as? PlaylistCacheCodec.ListRead.Ok)
        if (!forceRefresh && cachedList != null && cachedList.fresh) {
            return PlaylistResult.Data(
                PlaylistLoadOutcome(cachedList.playlists, fromCache = true, fresh = true, degradation = null)
            )
        }

        // ★ 联网前先验登录态。
        //
        // 这一条是**真机实测逼出来的**，不是防御性编程：探针 §5.1/§5.3 证实
        // `GetPlaylistByUin` 是「按 uin 公开可读」的 —— 票据被改坏、甚至完全不带 cookie，
        // 它**照样返回 code=0 + 歌单列表**。所以「列表拿成功」永远不能证明登录态有效，
        // 而「列表拿失败」也永远不会报 `1000`。
        //
        // 实测过程：把 qqmusic_key / qm_keyst 改成 INVALIDTICKET 后点刷新，
        // 界面**没有任何过期提示**（PCL110 截图 16-login-expired-after-refresh.png），
        // 因为服务端对公开数据根本不看票据。唯一可靠的判据是 `GetLoginUserInfo`（无 cookie ⇒ `1000`）。
        //
        // 代价：缓存过期/手动刷新时多一次请求。缓存新鲜时**一个请求都不发**（上面已提前返回），
        // 所以日常浏览不受影响。
        playlistAuthFailure(QqPlaylistApi.checkLoginValid())?.let { (degradation, failure) ->
            return degradedOrFail(cachedList?.playlists, degradation, failure)
        }
        val session = QqClient.pinReadSession()
        if (currentOwnerId(ctx) != ownerId) return PlaylistResult.Failed(PlaylistHardFailure.NeedLogin)

        return when (val r = QqPlaylistApi.fetchOwnedPlaylists(ownerId, session)) {
            is QqPlaylistResult.Ok -> {
                val fav = fetchFavoritesSafely(ownerId, r.value, session)
                val retained = if (fav.degradation != null) cachedList?.playlists.orEmpty().filterNot { it.isOwned } else emptyList()
                val merged = (r.value + fav.playlists + retained).distinctBy { it.key }
                val stored = QqAuthStore.withAuthLock {
                    if (currentOwnerId(ctx) != ownerId || session?.isCurrent() == false) false else {
                        QqPlaylistStore.writeList(ctx, ownerId, merged, if (fav.degradation == null) now() else 0L); true
                    }
                }
                if (!stored) return PlaylistResult.Failed(PlaylistHardFailure.NeedLogin)
                PlaylistResult.Data(
                    PlaylistLoadOutcome(merged, fromCache = false, fresh = fav.degradation == null, degradation = fav.degradation)
                )
            }
            QqPlaylistResult.NeedLogin -> degradedOrFail(
                cachedList?.playlists, PlaylistDegradation.NEED_LOGIN, PlaylistHardFailure.NeedLogin,
            )
            is QqPlaylistResult.Failure -> {
                // 业务码失败（非登录、非网络）：有缓存就降级展示，否则报错。
                if (cachedList != null && cachedList.playlists.isNotEmpty()) {
                    PlaylistResult.Data(
                        PlaylistLoadOutcome(
                            cachedList.playlists, fromCache = true, fresh = false,
                            degradation = PlaylistDegradation.OFFLINE,
                        )
                    )
                } else {
                    PlaylistResult.Failed(PlaylistHardFailure.Other(r.code))
                }
            }
            QqPlaylistResult.NotFound -> PlaylistResult.Failed(PlaylistHardFailure.NotFound)
            QqPlaylistResult.Network -> degradedOrFail(
                cachedList?.playlists, PlaylistDegradation.OFFLINE, PlaylistHardFailure.Network,
            )
        }
    }

    private fun degradedOrFail(
        cachedPlaylists: List<Playlist>?,
        degradation: PlaylistDegradation,
        failure: PlaylistHardFailure,
    ): PlaylistResult<List<Playlist>> =
        if (cachedPlaylists != null) {
            PlaylistResult.Data(
                PlaylistLoadOutcome(
                    cachedPlaylists, fromCache = true,
                    fresh = false, degradation = degradation,
                )
            )
        } else {
            PlaylistResult.Failed(failure)
        }

    private data class FavoritesLoad(val playlists: List<Playlist>, val degradation: PlaylistDegradation? = null)

    /** A failed optional read is partial data, never a confirmed empty collection. */
    private suspend fun fetchFavoritesSafely(ownerId: String, owned: List<Playlist>, session: QqReadSession?): FavoritesLoad {
        val favorite = owned.firstOrNull { it.isFavorite }
            ?: return FavoritesLoad(emptyList(), PlaylistDegradation.TRUNCATED)
        val detail = QqPlaylistApi.fetchDetailPage(favorite.key, 0L, QqPlaylistParser.FAVORITE_DIR_ID, 0, 1, session)
        val euin = (detail as? QqPlaylistResult.Ok)?.value?.encryptUin
        if (euin.isNullOrBlank()) return FavoritesLoad(emptyList(),
            playlistAuthFailure(detail)?.first ?: PlaylistDegradation.OFFLINE)
        val all = LinkedHashMap<PlaylistKey, Playlist>()
        val pageSize = 50
        var offset = 0
        repeat(40) {
            if (session?.isCurrent() == false) return FavoritesLoad(emptyList(), PlaylistDegradation.NEED_LOGIN)
            when (val page = QqPlaylistApi.fetchFavPlaylists(ownerId, euin, offset, pageSize, session)) {
                is QqPlaylistResult.Ok -> {
                    val oldSize = all.size
                    page.value.forEach { all[it.key] = it }
                    if (page.value.size < pageSize) return FavoritesLoad(all.values.toList())
                    if (all.size == oldSize) return FavoritesLoad(all.values.toList(), PlaylistDegradation.TRUNCATED)
                    offset += page.value.size
                }
                else -> {
                    Log.i(TAG, "favorite playlists incomplete: $page")
                    return FavoritesLoad(all.values.toList(), playlistAuthFailure(page)?.first ?: PlaylistDegradation.OFFLINE)
                }
            }
        }
        return FavoritesLoad(all.values.toList(), PlaylistDegradation.TRUNCATED)
    }

    // ---------------------------------------------------------------- 详情 ----

    /**
     * 一份详情结果**是不是取全了**（H21）。
     *
     * 旧判定只看 `hasMore`。于是「服务端声明 `total_song_num=100`、却根本没给 `songlist`
     * 数组」这种结构异常被当成**权威空表**：0 首 + `hasMore=0` ⇒ `complete=true`、
     * 写进缓存，上层（本地同步）也就无从判断"其实一首都没拿到"。
     *
     * 现在同时校验**声明总数**、**实际拿到的条数**与 **`songlist` 是否存在**：
     * 只有"不再有下一页"且"条数对得上"（或服务端没声明总数）且"数组在"才算完整。
     *
     * @param declaredTotal 服务端声明的曲目总数（`total_song_num`，缺失时为 0）。
     * @param collected 本次实际拿到的**原始条目数**（[QqPlaylistPage.rawCount]）。
     *   用原始条目而不是映射后条数：被 `QqSongMapper` 丢掉的条目在歌单里仍然占位置，
     *   服务端也是按它们计数 —— 拿映射后条数比会把这类歌单**永远**判成不完整。
     *   分页游标同样必须用原始条目数前进，否则 offset 重叠（见 [QqPlaylistApi.fetchAllPages]）。
     * @param hasMore 服务端是否还有下一页。
     * @param hasSonglist `songlist` 数组是否存在。缺数组 = 结构异常，**一律不算完整**；
     *   默认 true 让既有三参调用点（以及历史缓存形状）语义不变。
     */
    internal fun detailIsComplete(
        declaredTotal: Int,
        collected: Int,
        hasMore: Boolean,
        hasSonglist: Boolean = true,
    ): Boolean =
        hasSonglist && !hasMore && (declaredTotal <= 0 || collected >= declaredTotal)

    /**
     * 加载某个歌单的全部曲目（分页累积）。
     *
     * @param dirId 目录号。qm 用它寻址（「我喜欢」= 201）；收藏来的歌单没有自己的
     *   `dirId`，传 0 让服务端按 `disstid` 解析。
     * @param onPage 每页回调，让 UI 渐进显示大歌单。
     */
    suspend fun loadDetail(
        key: PlaylistKey,
        dirId: Long,
        forceRefresh: Boolean,
        onPage: (QqPlaylistPage) -> Unit = {},
    ): PlaylistResult<PlaylistDetailData> {
        val ctx = appContext ?: return PlaylistResult.Failed(PlaylistHardFailure.Network)

        val cached = QqPlaylistStore.readDetail(ctx, key, now())
        val cachedDetail = cached as? PlaylistCacheCodec.DetailRead.Ok
        if (!forceRefresh && cachedDetail != null && cachedDetail.fresh) {
            // 缓存命中且新鲜：**一个请求都不发**。注意 `complete=false` 时也返回 ——
            // 「拉了一半的缓存」仍然比空列表有用，用 degradation 告诉 UI 还没拉全。
            val deg = if (cachedDetail.complete) null else PlaylistDegradation.TRUNCATED
            return PlaylistResult.Data(
                PlaylistLoadOutcome(
                    PlaylistDetailData(cachedDetail.songs, cachedDetail.tracks),
                    fromCache = true, fresh = true, degradation = deg,
                )
            )
        }

        // 同 loadList：详情接口同样是公开可读的（探针 §5.3 实测 dirid=201 无 cookie 也返回数据），
        // 所以联网前必须单独验一次登录态，否则「登录过期」在详情页永远不会出现。
        playlistAuthFailure(QqPlaylistApi.checkLoginValid())?.let { (degradation, failure) ->
            return detailDegradedOrFail(cachedDetail, degradation, failure)
        }
        val session = QqClient.pinReadSession()
        if (currentOwnerId(ctx) != key.ownerId) return PlaylistResult.Failed(PlaylistHardFailure.NeedLogin)

        val disstid = key.id.toLongOrNull() ?: 0L
        return when (
            val r = QqPlaylistApi.fetchAllPages(
                key = key, disstid = disstid, dirId = dirId, onPage = onPage, session = session,
            )
        ) {
            is QqPlaylistResult.Ok -> {
                if (session?.isCurrent() == false) return PlaylistResult.Failed(PlaylistHardFailure.NeedLogin)
                val page = r.value
                // 完整性由 detailIsComplete 统一判定：分页截断、缺 songlist、条数对不上
                // 都算"没取全"（H21）。旧实现在这里只看 hasMore，且拿**映射后**条数比总数。
                val complete = detailIsComplete(
                    declaredTotal = page.total,
                    collected = page.rawCount,
                    hasMore = page.hasMore,
                    hasSonglist = page.hasSonglist,
                )
                // 部分结果**不覆盖**已知完整的那份缓存：一次网络抖动/结构异常不该把之前
                // 取全的数据降级成不完整（用户下次打开看到的是更少的曲目）。
                if (complete || cachedDetail?.complete != true) {
                    val stored = QqAuthStore.withAuthLock {
                        if (session?.isCurrent() == false) false else {
                            QqPlaylistStore.writeDetail(ctx, key, page.songs, now(), complete = complete, total = page.total)
                            true
                        }
                    }
                    if (!stored) return PlaylistResult.Failed(PlaylistHardFailure.NeedLogin)
                }
                PlaylistResult.Data(
                    PlaylistLoadOutcome(
                        PlaylistDetailData(page.songs, page.tracks),
                        fromCache = false, fresh = true,
                        // 权威空表（total 与条数一致）仍然是 null；只有"没取全"才降级。
                        degradation = if (complete) null else PlaylistDegradation.TRUNCATED,
                    )
                )
            }
            QqPlaylistResult.NeedLogin -> detailDegradedOrFail(
                cachedDetail, PlaylistDegradation.NEED_LOGIN, PlaylistHardFailure.NeedLogin,
            )
            QqPlaylistResult.NotFound -> detailDegradedOrFail(
                cachedDetail, PlaylistDegradation.OFFLINE, PlaylistHardFailure.NotFound,
            )
            QqPlaylistResult.Network -> detailDegradedOrFail(
                cachedDetail, PlaylistDegradation.OFFLINE, PlaylistHardFailure.Network,
            )
            is QqPlaylistResult.Failure -> detailDegradedOrFail(
                cachedDetail, PlaylistDegradation.OFFLINE,
                PlaylistHardFailure.Other(r.code),
            )
        }
    }

    private fun detailDegradedOrFail(
        cachedDetail: PlaylistCacheCodec.DetailRead.Ok?,
        degradation: PlaylistDegradation,
        failure: PlaylistHardFailure,
    ): PlaylistResult<PlaylistDetailData> =
        if (cachedDetail != null) {
            PlaylistResult.Data(
                PlaylistLoadOutcome(
                    PlaylistDetailData(cachedDetail.songs, cachedDetail.tracks),
                    fromCache = true, fresh = false, degradation = degradation,
                )
            )
        } else {
            PlaylistResult.Failed(failure)
        }

    // ------------------------------------------------------------------ 维护 ----

    /** 设置页「清除缓存」调用。 */
    fun clearAll() {
        appContext?.let { QqPlaylistStore.clear(it) }
    }

    /** 登出 QQ 时调用：只清本账号的缓存。 */
    fun clearCurrentOwner() {
        val ctx = appContext ?: return
        QqPlaylistStore.clearOwner(ctx, currentOwnerId(ctx))
    }
}
