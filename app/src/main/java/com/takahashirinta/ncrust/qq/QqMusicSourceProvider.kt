/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B/C：qm 音源的 Provider 实现。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.player.ResolveOutcome
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.MusicSourceProvider
import com.takahashirinta.ncrust.source.SourceRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * qm 音源（v2.1.0 · B）。
 *
 * 与 [com.takahashirinta.ncrust.source.NeteaseSourceProvider] 的差别不只是换个 API：
 * QQ 这一侧**没有云歌单/收藏/播放上报**的对应物（本版也不做，见下），
 * 它提供的是「能搜、能放、能出歌词」这条最小可用链。
 *
 * ## 为什么 [isLoggedIn] 为 false 时**不**把自己从路由表里摘掉
 *
 * 摘掉的话，未登录用户搜索 qm 只能得到空结果，而**原因是「未登录」还是「搜不到」
 * 在 UI 上完全无法区分**。保留注册、让请求照发：
 * 实测匿名态搜索与歌词都能拿到数据（只有取链会被拒，`result=104003`），
 * 所以未登录用户至少能搜到歌、看到歌词，点播放时才提示需要登录 —— 这是更好的降级。
 *
 * ## 本版**不做**的事（避免误以为已支持）
 *
 * - 不把 QQ 歌曲加进 ncm 歌单/收藏（那需要「本地歌单」这个尚不存在的概念，
 *   而且 ncm 的歌单写接口会拒绝外部曲目）；
 * - 不把 QQ 的播放行为上报给任何一方（QQ 侧没有对应的 webLog 机制，也不该伪造）。
 */
object QqMusicSourceProvider : MusicSourceProvider {

    private const val TAG = "QqMusicSource"

    override val source: MusicSource = MusicSource.QQMUSIC

    override val isLoggedIn: Boolean get() = QqClient.isLoggedIn()

    override suspend fun searchSongs(
        keyword: String,
        limit: Int,
        page: Int,
        totalOut: MutableMap<String, Int>?,
    ): List<SongItem> =
        // v3.4.12：总数从**同一次**搜索响应里取（`QqApi.searchSongs` 的出参）——
        // v3.4.11 曾为它单独发一次请求，设备实测把 QQ 这条腿顶过了 5 秒预算、
        // 导致「只有 qq 音乐超时」。**显示用的数字不许有自己的网络往返。**
        runCatching { QqApi.searchSongs(keyword, limit, page, totalOut) }
            .onFailure { Log.w(TAG, "search failed", it) }
            .getOrDefault(emptyList())
            .also { results ->
                // v2.1.5 · 探针：把 QQ 侧的搜索结果（身份三件套 + 标题）打到 logcat。
                //
                // 存在的理由：跨源切歌的验收要求「QQ ↔ ncm 混合队列连续切换」，
                // 而**构造这样一条队列需要真实的 (songid, songmid, media_mid)** ——
                // songmid 只从 QQ 服务端来，界面上又不显示。没有这条日志，
                // 真机验证就只能靠反复点搜索结果猜哪一条是 QQ 的（列表只有 QQ 行带角标，
                // 且聚合结果里 QQ 的 30 条排在 ncm 的 30 条之后）。
                //
                // 它与 [com.takahashirinta.ncrust.source.TrackKey] 的 `toString()` 同形，
                // 所以日志里一眼就能对上「起播的是谁 / 请求为谁发的」。
                // 只读、只在 debug 包出现、不改变任何行为（`also` 不碰返回值）。
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "search '$keyword' -> ${results.size} hits: " + results.take(8).joinToString(
                            " | "
                        ) { s ->
                            "qqmusic:${s.id} mid=${s.sourceId} media=${s.mediaId} '${s.name}'"
                        }
                    )
                }
            }

    override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? =
        runCatching { QqApi.fetchPlayUrl(song, level) }
            .onFailure { Log.w(TAG, "resolveUrl failed for id=${song.id}", it) }
            .getOrNull()

    /**
     * v3.2.0 · P0：**带失败分类**的取链。播放链走这一条。
     *
     * 分类规则的唯一实现在 `qq/QqRejection.kt`（纯逻辑 + 单测），这里只做转发 ——
     * 把判据抄一份到这里，两份真相必然漂移（v2.1.5 / v2.6.0 各栽过一次同形状的坑）。
     *
     * ⚠️ 异常也走分类：`QqApi.resolveOutcome` 内部不抛，但 provider 的契约是「绝不抛」，
     * 所以外面再兜一层 —— 兜住之后归 [ResolveFailureKind.UNKNOWN]（不跳歌），
     * 绝不归 [ResolveFailureKind.UNRESOLVABLE]（会跳歌）。
     */
    override suspend fun resolveUrlOutcome(song: SongItem, level: String): ResolveOutcome =
        runCatching { QqApi.resolveOutcome(song, level) }
            .onFailure { Log.w(TAG, "resolveUrlOutcome failed for id=${song.id}", it) }
            .getOrElse {
                ResolveOutcome.failed(ResolveFailureKind.UNKNOWN, MusicSource.QQMUSIC)
            }

    /**
     * 元数据补全：QQ 侧没有「按 id 批量取详情」的轻量端点（取详情要走完整曲库接口），
     * 而队列里的条目本来就已经带全了元数据。所以这里**返回原对象**而不是发一次请求 ——
     * 这个契约在接口里允许（「失败返回 null，调用方保留原对象即可」），
     * 但返回原对象更省一次往返，也不会让调用方误以为拿到了新数据。
     */
    override suspend fun songDetail(song: SongItem): SongItem? = song.takeIf { it.sourceId != null }

    /**
     * QQ 搜索的下一页判据（v3.4.11）：**这一页被填满了**就认为还有下一页。
     *
     * 实测旧版 GET 的 `p=` 是页码、`n=` 是每页条数（见 [QqRequests.legacySearchUrl]），
     * 但它**不返回可用的总条数**，所以只能按「填满 ⇒ 可能还有」判断 ——
     * 代价是最后一页会多给一次点击，而不会漏页。
     *
     * 不额外发请求：判据直接用**已经取回的那一页**的长度，由调用方传进来即可
     * （见 `SearchViewModel` 里对 `pageSize` 的比较），这里保守地按 limit 判。
     */
    override suspend fun hasMorePages(keyword: String, limit: Int, page: Int): Boolean {
        val pageItems = searchSongs(keyword, limit, page)
        return pageItems.size >= limit.coerceIn(1, 60)
    }

    /**
     * 进程启动时接线：注册 Provider（音源路由认得 QQ）并给 [QqClient] 一个 application context。
     *
     * 与 `RetrofitClient.init(this)` 并列调用。**幂等**，重复调用无副作用。
     */
    fun install(context: Context) {
        QqClient.init(context)
        SourceRouter.register(this)
        // v2.5.4 · C：把上一进程落盘的兜底统计读回来（幂等，只读一次）。
        // 放在这里而不是 Application.onCreate：本函数已经是「进程启动时接线」的既有落点。
        runCatching { QqProbeStore.ensureSeeded(context) }
        // v2.5.5 · B：跨源上报闸门的拦截计数同样要读回来 —— 否则每次冷启动
        // 都会从 0 开始重新计，`onStop` 落盘的那份会被「加到一个空计数器上」的
        // 语义悄悄变成「只统计本次进程」（两条统计的纪律一致，见 ReportGateStore）。
        runCatching { com.takahashirinta.ncrust.player.ReportGateStore.ensureSeeded(context) }
        // v3.4.9：登录态的**主动续期**（票据寿命还剩不到 12 小时就静默换一张新票）。
        // 必须异步：它是一次网络往返，而这个函数跑在 MainActivity.onCreate 里。
        maybeRefreshLogin(context)
    }

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * v3.4.9：冷启动（以及每次 Activity 重新创建）时的主动续期。
     *
     * ## 为什么放在这里而不是「取链失败时」
     *
     * 取链失败时的被动续期（[QqApi] 的 [QqTokenRefresher.refreshAfterRejection]）是兜底，
     * 它对用户是**可见的**：先失败一次、界面弹一次「要会员/去登录」、然后才自愈。
     * 而这条链路最常见的场景是「用户一星期没开 App，再打开时票刚好过期」——
     * 那时候主动换票是**完全无感**的，代价只是一次后台请求。
     *
     * ## 幂等性由三道闸门保证（不是靠「只调用一次」）
     *
     * `install()` 与 `RetrofitClient.init` 同处，**每次 Activity 创建都会调**，
     * 所以这个函数必须能被反复调用而什么都不多做：
     * ① 同步的窗口检查（这里）—— 不在续期窗口内就直接返回，一个协程都不起；
     * ② [QqTokenRefresher] 的 `Mutex` 单飞 —— 并发触发时只有一次真的发请求；
     * ③ 刷新成功后 `needsRefresh()` 立刻变 false —— 窗口关上了。
     *
     * 用「进程内只跑一次的布尔标志」是**更差**的选择：进程活着的期间用户可能
     * 跨过整个到期窗口（后台常驻），那个标志会让第二次机会永远不出现。
     *
     * ## 为什么用一个自己的 scope
     *
     * 它必须活过 `onCreate`（网络往返），又不能挂在 Activity 的生命周期上 ——
     * 挂在 Activity 上时用户一进 App 就切走会把续期一起取消，
     * 表现是「偶尔能续上、偶尔不能」，而这种偶发性在真机上几乎无法归因。
     */
    private fun maybeRefreshLogin(context: Context) {
        if (!QqTokenRefresher.canRefresh(context)) return
        // 同步的窗口检查：把「每次冷启动都起一个协程去发现无事可做」也省掉。
        val remaining = QqTokenRefresher.remainingSeconds(context) ?: return
        if (remaining > QqRefreshCredential.REFRESH_MARGIN_SECONDS) return
        val app = context.applicationContext
        refreshScope.launch {
            runCatching { QqTokenRefresher.refreshIfNeeded(app) }
                .onFailure { Log.w(TAG, "冷启动续期失败", it) }
        }
    }
}
