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
        if (!QqPlaylistApi.checkLoginValid()) {
            return degradedOrFail(
                cachedList?.playlists, PlaylistDegradation.NEED_LOGIN, PlaylistHardFailure.NeedLogin,
            )
        }

        return when (val r = QqPlaylistApi.fetchOwnedPlaylists(ownerId)) {
            is QqPlaylistResult.Ok -> {
                val fav = fetchFavoritesSafely(ownerId, r.value)
                val merged = r.value + fav
                QqPlaylistStore.writeList(ctx, ownerId, merged, now())
                PlaylistResult.Data(
                    PlaylistLoadOutcome(merged, fromCache = false, fresh = true, degradation = null)
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

    /**
     * 尽力而为地取收藏歌单。任何失败都吞掉并返回空 —— 它不该让「我自己的歌单」也失败。
     * **失败要留日志**（否则「收藏歌单为什么一直空」无法排查），但只打错误码不打内容。
     */
    private suspend fun fetchFavoritesSafely(
        ownerId: String,
        owned: List<Playlist>,
    ): List<Playlist> {
        val fav = owned.firstOrNull { it.isFavorite } ?: return emptyList()
        val euin = when (
            val d = QqPlaylistApi.fetchDetailPage(
                key = fav.key, disstid = 0L, dirId = QqPlaylistParser.FAVORITE_DIR_ID,
                songBegin = 0, songNum = 1,
            )
        ) {
            is QqPlaylistResult.Ok -> d.value.encryptUin
            else -> null
        }
        if (euin.isNullOrBlank()) {
            Log.i(TAG, "favorite playlists skipped: no encrypt_uin (detail lookup failed)")
            return emptyList()
        }
        return when (val r = QqPlaylistApi.fetchFavPlaylists(ownerId, euin)) {
            is QqPlaylistResult.Ok -> r.value
            else -> {
                Log.i(TAG, "favorite playlists fetch failed: $r")
                emptyList()
            }
        }
    }

    // ---------------------------------------------------------------- 详情 ----

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
        if (!QqPlaylistApi.checkLoginValid()) {
            return detailDegradedOrFail(
                cachedDetail, PlaylistDegradation.NEED_LOGIN, PlaylistHardFailure.NeedLogin,
            )
        }

        val disstid = key.id.toLongOrNull() ?: 0L
        return when (
            val r = QqPlaylistApi.fetchAllPages(
                key = key, disstid = disstid, dirId = dirId, onPage = onPage,
            )
        ) {
            is QqPlaylistResult.Ok -> {
                val page = r.value
                QqPlaylistStore.writeDetail(
                    ctx, key, page.songs, now(),
                    complete = !page.hasMore, total = page.total,
                )
                PlaylistResult.Data(
                    PlaylistLoadOutcome(
                        PlaylistDetailData(page.songs, page.tracks),
                        fromCache = false, fresh = true,
                        // 「拉完了但一首都没有」是**空歌单**，不是降级；只有被截断才算降级。
                        degradation = if (page.hasMore) PlaylistDegradation.TRUNCATED else null,
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
