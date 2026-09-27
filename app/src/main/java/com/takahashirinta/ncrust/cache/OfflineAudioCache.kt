/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import androidx.annotation.OptIn

import com.takahashirinta.ncrust.bili.BiliCdn
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File

/**
 * 已播放音频流的本地缓存（v1.6.0 · D1 · Phase 1）。
 *
 * ## 定位（合规前提）
 *
 * 这**不是**「官方下载」，也不碰任何 DRM：它只是把**用户已经点播过、已经播放过的音频流**
 * 按 HTTP 语义落一份到本地，等价于 ExoPlayer 的 CacheDataSource，用于弱网/断网时避免重下。
 * 未播放过的歌不会产生任何本地文件；没有任何绕过会员/版权校验的路径（URL 仍然每次向服务端要，
 * 服务端不给就是不给）；离线可播范围**严格等于**「这台设备上真正播过的歌」。
 *
 * ## 三个实现决定
 *
 * 1. **放在 filesDir/offline/audio 而不是 cacheDir**（v1.5.0 调研结论）：系统/清理类 App
 *    会清 cacheDir；而「清除缓存」按钮只删 cacheDir/{WebView,http}，不会误删这里 ——
 *    离线音频是可预期保留的数据，不该被系统的常规缓存回收清掉。设置页的「缓存占用」里
 *    会把它一并统计并允许用户清除（[clear]）。
 * 2. **自定义 cache key**（[OfflineKeys]）：网易的播放 URL 每次取都变，media3 默认按 URL
 *    做 key 会导致缓存永不命中 —— 一首歌放十遍存十份。
 * 3. **FLAG_IGNORE_CACHE_ON_ERROR**：写缓存失败（空间不足 / 目录被占）绝不能影响播放，
 *    出错就退化成纯网络播放。
 *
 * 上限默认 512 MiB，可在 prefs 里改（`ncrust_settings` 的 `offline_cache_mb`）。
 * LRU 淘汰由 media3 的 [LeastRecentlyUsedCacheEvictor] 负责。
 */
// v3.1.0 · B：本文件用到 media3 的 `@UnstableApi`（`ResolvingDataSource` /
// `SimpleCache` / `CacheDataSource` 等），而 lint 的 `UnsafeOptInUsageError`
// 要求**逐处**opt-in。约定与 `PlaybackService` 一致：在类型上标一次。
// 这些 API 在 media3 1.5 上是稳定的，标 `UnstableApi` 是库方的兼容承诺方式，不是风险信号。
@OptIn(UnstableApi::class)
object OfflineAudioCache {

    /** 缓存目录名（相对 filesDir）。 */
    private const val DIR = "offline/audio"

    /** 默认上限：512 MiB。 */
    const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024

    /** 上限的合法区间（MB）。设置页的选择器与 [maxBytes] 的夹取共用这一处定义。 */
    const val MIN_MB = 64
    const val MAX_MB = 8192

    private const val MB = 1024L * 1024L

    private const val PREFS = "ncrust_settings"
    private const val KEY_MAX_MB = "offline_cache_mb"

    @Volatile
    private var cache: SimpleCache? = null

    /** 读取用户设置的上限（MB）。非法值一律回落默认，避免 prefs 被写坏时把缓存设成 0。 */
    fun maxBytes(context: Context): Long {
        val mb = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MAX_MB, (DEFAULT_MAX_BYTES / 1024 / 1024).toInt())
        }.getOrDefault((DEFAULT_MAX_BYTES / 1024 / 1024).toInt())
        return if (mb in MIN_MB..MAX_MB) mb.toLong() * MB else DEFAULT_MAX_BYTES
    }

    /** 当前生效的上限（MB）。设置页回显用；合法值恒在 [MIN_MB]..[MAX_MB]。 */
    fun maxMb(context: Context): Int = (maxBytes(context) / MB).toInt()

    /**
     * 写上限（MB），v2.0.0 · T3 设置页用。
     *
     * ⚠️ **只写 prefs**：SimpleCache 的淘汰器在构造时固化（见 [get]），本次进程内改不动 ——
     * 重建实例会打断正在播放的 ExoPlayer。所以 UI 必须如实提示「下次启动生效」。
     * 非法值直接忽略（不写坏 prefs）。
     */
    fun setMaxMb(context: Context, mb: Int) {
        if (mb !in MIN_MB..MAX_MB) return
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MAX_MB, mb).apply()
        }
    }

    /** 进程内单例。SimpleCache 独占目录，必须只建一份。 */
    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val app = context.applicationContext
            val dir = File(app.filesDir, DIR)
            val built = SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(maxBytes(app)),
                StandaloneDatabaseProvider(app),
            )
            cache = built
            return built
        }
    }

    /**
     * 给 ExoPlayer 用的数据源工厂：命中本地的片段不再走网络。
     *
     * ## v3.1.0 · B：两处**按 host 限定**的改动（铁律 27：不得破坏现有音源）
     *
     * 1. **上游挂一层 [ResolvingDataSource]**，只对 B 站 CDN 的 host 补
     *    `Referer: https://www.bilibili.com/`。实测：B 站媒体 CDN 不带 Referer
     *    一律 **403**（连带 ExoPlayer 指纹的 UA 也 403），而带上就是 206。
     *    反过来，给网易云/QQ 的请求加 B 站 Referer 也会把它们打死 ——
     *    所以判据是 host 白名单（[BiliCdn.needsReferer]），不是全局默认头。
     *    网易云与 QQ 的请求在这里**一个字节都不变**。
     * 2. **B 站 CDN 用内容寻址的稳定缓存键**：它的直链两小时后会换一条（query 全变），
     *    但路径里的内容哈希不变，所以键取文件名（[BiliCdn.cacheKeyFor]）。
     *    非 B 站 CDN 的键**逐字不变**。
     *
     * `ResolvingDataSource` 是 media3 自带的、专门用来在**打开数据源之前**
     * 改写 `DataSpec` 的那一层（`DataSpec.withRequestHeaders`），
     * 因此不需要自定义 `DataSource`，也不影响缓存写入路径。
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val c = get(app)
        val upstream: DataSource.Factory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(app),
        ) { spec ->
            val host = spec.uri.host
            if (BiliCdn.needsReferer(host)) spec.withRequestHeaders(BiliCdn.requestHeaders()) else spec
        }
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(c).setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
            .setCacheKeyFactory { spec ->
                // v3.1.0 · B：B 站 CDN 用**稳定键**（路径里的内容哈希 + 档位后缀），
                // 因为它的 query（签名与 deadline）每次取链都不同 —— 按完整 URL 做 key
                // 只会堆一批永远命中不了的片段。非 B 站 CDN 走既有规则，逐字不变。
                BiliCdn.cacheKeyFor(spec.uri.toString(), spec.uri.host)
                    ?: OfflineKeys.keyOf(spec.uri.toString())
                    ?: spec.uri.toString()
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 缓存里是否已有这个 key（离线可播的判据）。 */
    fun contains(context: Context, key: String): Boolean =
        runCatching { get(context).keys.contains(key) }.getOrDefault(false)

    /** 已用字节数。 */
    fun sizeBytes(context: Context): Long =
        runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /**
     * 缓存里现有的全部 cacheKey（v2.0.0 · T3）。统计与对账用。
     *
     * 取不到一律回落空集 —— 这个方法只被 UI / 对账路径调用，绝不能因为缓存目录异常
     * 把设置页搞崩。
     */
    fun keys(context: Context): Set<String> =
        runCatching { get(context).keys.toSet() }.getOrDefault(emptySet())

    /** 这首歌在缓存里的所有档位 key（按 SimpleCache 的枚举顺序）。 */
    fun songKeys(context: Context, songId: Long): List<String> =
        runCatching { get(context).keys.filter { OfflineKeys.songIdOf(it) == songId } }
            .getOrDefault(emptyList())

    /**
     * 这首歌的缓存占用（字节）。**量不到返回 null** —— 调用方据此显示「已缓存片段」，
     * 而不是编一个数字出来。
     *
     * 不用 `Cache.getCachedBytes(key, 0, MAX)`：那个 API 要求「从 position 起连续缓存」，
     * 而我们缓存的只是**播过的那几段**（seek 之后会留洞），从 0 起经常不连续、会量成 0。
     * 按 span 求和才是真实占用。
     */
    fun bytesForSong(context: Context, songId: Long): Long? = runCatching {
        val c = get(context)
        var total = 0L
        var measuredAny = false
        songKeys(context, songId).forEach { key ->
            runCatching { c.getCachedSpans(key) }.getOrNull()?.forEach { span ->
                if (span.isCached) {
                    total += span.length
                    measuredAny = true
                }
            }
        }
        if (measuredAny) total else null
    }.getOrNull()

    /**
     * 删掉这首歌**所有档位**的缓存片段，并同步删离线 URL 清单与离线曲目索引条目
     * （v2.0.0 · T3；三条清单必须一起动，否则留下指向空缓存的死条目）。
     *
     * ⚠️ 删**正在播放**的那首会触发回源网络：正在读的 span 没了，CacheDataSource 会转去
     * 请求上游 —— 断网时就是一次播放错误。因此 UI 对当前播放曲目禁用删除（见设置页的
     * 离线缓存管理）；这里不做拦截，是因为调用方才知道「哪首在播」。
     *
     * @return true = 至少删掉了一个缓存片段（清单同步删除与它无关，永远都做）。
     */
    fun removeSong(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        val app = context.applicationContext
        val removedSpans = runCatching {
            val c = get(app)
            var removed = false
            songKeys(app, songId).forEach { key ->
                c.removeResource(key)
                removed = true
            }
            removed
        }.getOrDefault(false)
        // 缓存里没有片段也要清两条清单：用户点了「删除」，列表里就不能再留着它。
        OfflineLibrary.remove(app, songId)
        return removedSpans
    }

    /**
     * 对账（v2.0.0 · T3）：离线曲目索引里那些「缓存已经没有任何片段」的歌是**谎报**
     * （典型成因是 LRU 淘汰了音频 span，而索引还留着条目），打开管理页时清掉。
     *
     * 只做单向对账（索引 → 缓存真相），不动 URL 清单：URL 清单的条目是否还有用，
     * 由 [OfflineAudioCache.contains] 在离线兜底时判定，多清一份反而会误伤
     * 「同曲另一档位还有缓存」的情况。
     *
     * @return 被丢掉的条目数。
     */
    fun reconcileLibrary(context: Context): Int {
        val app = context.applicationContext
        val liveSongIds = keys(app).mapNotNull { OfflineKeys.songIdOf(it) }.toSet()
        return OfflineLibrary.retain(app, liveSongIds)
    }

    /**
     * 清空音频缓存。播放中的歌曲正在读的片段被删掉不会崩 —— CacheDataSource 会回源网络。
     * 同时清掉离线 URL 清单与离线曲目索引，否则会留下指向已删缓存的死条目、
     * 并且「缓存占用」与「可清理范围」的口径就对不上了（v1.6.0 起的不变量）。
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        runCatching { get(app).keys.toList().forEach { get(app).removeResource(it) } }
        OfflineUrlStore.clear(app)
        OfflineLibrary.clear(app)
    }
}
