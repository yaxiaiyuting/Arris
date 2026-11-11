/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn

import com.takahashirinta.ncrust.bili.BiliCdn
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
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
 * 上限默认 1 GiB，可在 prefs 里改（`ncrust_settings` 的 `offline_cache_mb`）。
 * LRU 淘汰由 media3 的 [LeastRecentlyUsedCacheEvictor] 负责。
 */
// v3.1.0 · B：本文件用到 media3 的 `@UnstableApi`（`ResolvingDataSource` /
// `SimpleCache` / `CacheDataSource` 等），而 lint 的 `UnsafeOptInUsageError`
// 要求**逐处**opt-in。约定与 `PlaybackService` 一致：在类型上标一次。
// 这些 API 在 media3 1.5 上是稳定的，标 `UnstableApi` 是库方的兼容承诺方式，不是风险信号。
@OptIn(UnstableApi::class)
object OfflineAudioCache {
    private const val TAG = "OfflineAudioCache"

    /** 缓存目录名（相对 filesDir）。 */
    private const val DIR = "offline/audio"

    /** 默认上限：1 GiB。 */
    const val DEFAULT_MAX_BYTES = 1024L * 1024 * 1024

    /** 上限的合法区间（MB）。设置页的选择器与 [maxBytes] 的夹取共用这一处定义。 */
    const val MIN_MB = 64
    const val MAX_MB = 16384

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
     * 缓存读写与回源使用同一套键。回源共享 MediaHttp 的 DNS 和连接池，
     * B 站媒体由单独的上游工厂提供对应 User-Agent。
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val c = get(app)
        // 音频流统一走 OkHttp（v3.8.9）：默认的 DefaultHttpDataSource 用 HttpURLConnection，
        // 不认我们的 DNS 策略、没有读超时、连接池也不受 evictConnections 管理 ——
        // 网络切换后会一直卡在 BUFFERING（真机实测：取链 745ms，播放卡十几秒才出声）。
        val plainHttp = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.takahashirinta.ncrust.player.MediaHttp.client,
        )
        val upstream: DataSource.Factory = SourceRoutingDataSource.Factory(
            plain = DefaultDataSource.Factory(app, plainHttp),
            bili = DefaultDataSource.Factory(
                app,
                // ★ 唯一的 UA 注入点。@OptIn 见文件头。
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                    com.takahashirinta.ncrust.player.MediaHttp.client,
                ).setUserAgent(BiliCdn.USER_AGENT),
            ),
        )
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(c).setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
            .setCacheKeyFactory { spec -> cacheKeyFor(spec.uri.toString(), spec.uri.host) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun cacheKeyFor(url: String, host: String?): String =
        BiliCdn.cacheKeyFor(url, host) ?: OfflineKeys.keyOf(url) ?: url

    /** 只读当前缓存的完整文件长度元数据；不是已缓存片段的长度，不触发网络。 */
    fun contentLengthForUrl(url: String): Long = runCatching {
        val uri = android.net.Uri.parse(url)
        val key = cacheKeyFor(url, uri.host)
        val current = cache ?: return@runCatching 0L
        androidx.media3.datasource.cache.ContentMetadata.getContentLength(current.getContentMetadata(key))
            .coerceAtLeast(0L)
    }.getOrDefault(0L)

    /** 缓存里是否已有这个 key（离线可播的判据）。 */
    fun contains(context: Context, key: String): Boolean =
        runCatching { get(context).keys.contains(key) }.getOrDefault(false)

    /** 离线起播前检查从字节 0 开始的连续缓存。SimpleCache 的同键片段已按偏移排序。 */
    fun coversStart(context: Context, key: String): Boolean = runCatching {
        OfflineKeys.coversOrderedStart(
            get(context).getCachedSpans(key),
            position = { it.position },
            length = { it.length },
            isCached = { it.isCached },
        )
    }.getOrDefault(false)

    /** 已用字节数。 */
    fun sizeBytes(context: Context): Long =
        runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /** 对账只有成功枚举后才清理离线索引。失败返回 null。 */
    fun keysOrNull(context: Context): Set<String>? =
        runCatching { get(context).keys.toSet() }.getOrNull()

    /** 这首歌在缓存里的所有档位 key（按 SimpleCache 的枚举顺序）。 */
    fun songKeys(context: Context, songId: Long): List<String> =
        runCatching { get(context).keys.filter { OfflineKeys.songIdOf(it) == songId } }
            .getOrDefault(emptyList())

    /** 用同一份缓存键快照测量列表中的歌曲占用。缺少可测片段时由界面显示未知大小。 */
    internal fun bytesBySong(context: Context, songIds: Set<Long>): Map<Long, Long> {
        if (songIds.isEmpty()) return emptyMap()
        return runCatching {
            val current = get(context)
            OfflineKeys.sumBytesBySong(current.keys, songIds) { key ->
                runCatching {
                    var total = 0L
                    var measured = false
                    for (span in current.getCachedSpans(key)) {
                        if (span.isCached) {
                            total += span.length
                            measured = true
                        }
                    }
                    if (measured) total else null
                }.getOrNull()
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * 删掉这首歌**所有档位**的缓存片段，并同步删离线 URL 清单与离线曲目索引条目
     * （v2.0.0 · T3；三条清单必须一起动，否则留下指向空缓存的死条目）。
     *
     * ⚠️ 删**正在播放**的那首会触发回源网络：正在读的 span 没了，CacheDataSource 会转去
     * 请求上游 —— 断网时就是一次播放错误。因此 UI 对当前播放曲目禁用删除（见设置页的
     * 离线缓存管理）；这里不做拦截，是因为调用方才知道「哪首在播」。
     *
     * @return 是否完成删除。没有残留片段时也可完成索引清理。
     */
    fun removeSong(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        val app = context.applicationContext
        // E01：缓存操作**失败**与「缓存里确实没有片段」不是一回事。
        // 旧写法把异常折成 false 之后仍然继续，紧接着就把离线索引删了 ——
        // 磁盘上字节还在、管理页里的条目却先消失（用户以为删干净了）。
        try {
            val c = get(app)
            songKeys(app, songId).forEach(c::removeResource)
        } catch (e: Exception) {
            Log.w(TAG, "removeSong failed, keep offline index: songId=$songId", e)
            return false
        }
        // 缓存里没有片段也要清两条清单：用户点了「删除」，列表里就不能再留着它。
        OfflineLibrary.remove(app, songId)
        // 已不存在的缓存是幂等成功；异常路径已在上面返回false并保留索引。
        return true
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
        // 拿不到缓存真相就不裁剪：宁可留下几条过期索引，也不能把用户的离线库清空。
        val live = keysOrNull(app) ?: return 0
        val liveSongIds = live.mapNotNullTo(HashSet()) { OfflineKeys.songIdOf(it) }
        return OfflineLibrary.retain(app, liveSongIds)
    }

    /**
     * 清空音频缓存。播放中的歌曲正在读的片段被删掉不会崩 —— CacheDataSource 会回源网络。
     * 同时清掉离线 URL 清单与离线曲目索引，否则会留下指向已删缓存的死条目、
     * 并且「缓存占用」与「可清理范围」的口径就对不上了（v1.6.0 起的不变量）。
     */
    fun clear(context: Context): Boolean {
        val app = context.applicationContext
        // E01：清空同样要求「真的删掉了」才动索引。缓存目录打不开时先返回，
        // 否则两份索引先被清掉、字节却还在，管理页再也列不出这些文件（只能靠重装清）。
        val cleared = runCatching {
            val c = get(app)
            c.keys.toList().forEach { c.removeResource(it) }
            true
        }.getOrElse {
            Log.w(TAG, "clear failed, keep offline indexes", it)
            false
        }
        if (!cleared) return false
        OfflineUrlStore.clear(app)
        OfflineLibrary.clear(app)
        return true
    }
}
