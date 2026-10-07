/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：ncm 音源的 Provider 实现。
 */

package com.takahashirinta.ncrust.source

import android.util.Log
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.player.ResolveOutcome
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.search.TrackAccess

/**
 * ncm 音源（v2.1.0 · A）。
 *
 * **本类只做转发，不含任何新逻辑** —— 这是 v2.1.0 最重要的一条纪律：
 * 抽象层的引入不允许改变既有 ncm 功能的行为。取链仍走 [SongUrlFetcher.fetch]
 * （8 档降级阶梯、FLAC 设备门控、离线缓存 key 挂载、降级原因判定全在里面），
 * 搜索仍走 [RetrofitClient.api] 的 `cloudsearch/pc`，详情仍走 [PlaylistApi.getSongsByIds]。
 *
 * 这么薄的转发看起来「多了一层」，但它买到的是：调用方不必再知道
 * 「ncm 的取链函数在 player 包里、搜索在 network 包里」这种位置知识，
 * 也让 qm 的实现有一个必须对齐的契约（见 [MusicSourceProvider]）。
 */
object NeteaseSourceProvider : MusicSourceProvider {

    private const val TAG = "NeteaseSource"

    override val source: MusicSource = MusicSource.NETEASE

    /** cookie 非空即视为已登录 —— 与 v2.0.2 的判据一致（不做真实校验请求）。 */
    override val isLoggedIn: Boolean
        get() = !RetrofitClient.getCookie().isNullOrBlank()

    override suspend fun searchSongs(keyword: String, limit: Int): List<SongItem> =
        runCatching {
            RetrofitClient.api.search(keyword = keyword, type = 1, limit = limit)
                .result?.songs.orEmpty()
        }.onFailure { Log.w(TAG, "search failed", it) }
            .getOrDefault(emptyList())

    override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? =
        SongUrlFetcher.fetch(song.id, level)

    /**
     * v3.2.0 · P0：**带失败分类**的取链。
     *
     * ## ncm 是唯一能合法产出「无版权」的音源
     *
     * [ResolveFailureKind.COPYRIGHT_GONE] 的定义要求「音源**显式声明**」，
     * 而全仓库唯一满足这一条的信号就是 [SongItem.noCopyright]（`noCopyrightRcmd != null`）：
     * v2.3.0 的探针在 591 条样本里只见到 2 条带它，但**零假阳性** ——
     * 带上它时该曲确实取不到链，且服务端自己给了替代说明。
     *
     * 反过来说：**没有这个字段时不许猜**。QQ 侧没有对应字段，所以那边永远不产出这一档
     * （见 `qq/QqRejection.kt` 的映射表）。
     *
     * 判据顺序：
     * 1. `noCopyright != null` ⇒ 无版权（服务端显式声明，最高优先级）；
     * 2. `fee ∈ {1,4}` ⇒ 会员专享 / 数字专辑 —— 走 [TrackAccess] 的既有判据，
     *    不在这里再写一遍 `fee` 的字面量（`fee=8` 是「免费播放 + 高音质需会员」，
     *    把它算成会员墙会让一批免费歌被误报）；
     * 3. 其余 ⇒ [ResolveFailureKind.UNKNOWN]（**不跳歌**）。
     */
    override suspend fun resolveUrlOutcome(song: SongItem, level: String): ResolveOutcome {
        val result = resolveUrl(song, level)
        if (result != null) return ResolveOutcome.ok(result)
        val kind = when {
            // ① 服务端显式声明无版权 —— 唯一的合法来源。
            song.noCopyright != null -> ResolveFailureKind.COPYRIGHT_GONE
            // ② 会员专享 / 数字专辑。
            TrackAccess.ofNeteaseFee(song.fee).isGated -> ResolveFailureKind.NEED_VIP
            // ③ 读不懂：停下等用户，不动队列。
            else -> ResolveFailureKind.UNKNOWN
        }
        Log.w(
            TAG,
            "resolve failed id=${song.id} level=$level -> $kind " +
                "(fee=${song.fee} noCopyright=${song.noCopyright != null})",
        )
        return ResolveOutcome.failed(kind, MusicSource.NETEASE, rawCode = song.fee)
    }

    /**
     * 元数据补全。`getSongsByIds` 内部走 `/eapi/v3/song/detail`，
     * 返回的 [SongItem] 天然没有 source 字段（= ncm），与入参一致。
     */
    override suspend fun songDetail(song: SongItem): SongItem? =
        runCatching { PlaylistApi.getSongsByIds(listOf(song.id)).firstOrNull() }
            .onFailure { Log.w(TAG, "songDetail failed for id=${song.id}", it) }
            .getOrNull()
}
