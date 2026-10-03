/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · C：QQ「无 songid」兜底路径的**本地**频率统计。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.qq

import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.source.SourceIds
import java.util.concurrent.atomic.AtomicLong

/**
 * QQ 兜底路径的计数字段（快照）。
 *
 * ## 探针纠正了任务书的一个前提，这里如实照搬那个结论
 *
 * 任务书写的是「QQ 无 songid 兜底**路径**」（容易读成「取链时兜底」）。
 * 实测（`docs/verification/v2.5.4/probe-qq-fallback.md` §0/§1）**取链根本不读 songid** ——
 * `QqApi.fetchPlayUrl` 第一行就是 `val songMid = song.sourceId ?: return null`，
 * songmid 缺失是**硬失败**，没有兜底、没有重试。
 *
 * 真正的「无 songid 兜底」在**解析期**，而且只影响**身份**：
 * `SourceIds.qqId(rawSongId, sourceId)` 在 `rawSongId <= 0` 时用 songmid 的
 * FNV-1a 散列造一个合成 id。所以这里统计的是：
 *
 * | 要回答的问题 | 字段 |
 * |---|---|
 * | QQ 解析出的曲目里，有多少条服务端**没给 songid** | [parsedNoSongId] / [parsedTotal] |
 * | 这些曲目**取链**成不成功 | [resolveOk] / [resolveFail]（分母是 [parsedNoSongId] 那批） |
 * | 这些曲目**真的播出来了没有** | [fallbackPlaybackConfirmed] vs [fallbackPlaybackFailed] |
 *
 * ## 为什么要 `fallbackPlaybackConfirmed`
 *
 * 只统计「兜底次数」会得到一个无法行动的数字。真正要回答的是
 * 「兜底发生率 × 兜底曲目的可播率」：兜底出现 5% 但它们全播不出来 ⇒ P0 缺陷；
 * 兜底出现 5% 而可播率与普通曲目一致 ⇒ 只是一个统计事实。
 *
 * ## 隐私（铁律：统计不上报）
 *
 * 这些数字**只落本地私有目录**（`ncrust_qq_probe` / `stats`），
 * 本文件与 [QqProbeStore] 都**不引用任何网络类型**。评审时可以用一条 grep 证明：
 * `grep -rn "RetrofitClient\|QqClient\|HttpURLConnection" QqFallbackStats.kt` 必须为空。
 *
 * ## 加字段 = 加迁移逻辑 = 加单测（AGENTS.md）
 *
 * 每个字段**可空 + 有默认值**：Gson 走 Unsafe 反序列化、不调用构造函数，
 * 老 JSON 里缺的 key 读出来就是 `null`，由 [QqFallbackCounters.canonical] 归零。
 * 「缺失」与「显式 0」在这里**语义相同**（都是"没记到"），所以不需要
 * `LyricsCache` 那种「缺失按 miss 重取」的自愈 —— 但这条区分必须在代码里写明，
 * 否则下一个加字段的人会以为可以省掉默认值。
 */
data class QqFallbackCounters(
    // —— 解析期（QqSongMapper.fromSongObject）——
    /** 解析出的 QQ 曲目总数（所有比率的**分母**）。 */
    @SerializedName("parsedTotal") val parsedTotal: Long? = null,
    /** ★ 服务端没给 songid（`rawSongId <= 0`）⇒ 走了 FNV-1a 散列兜底。 */
    @SerializedName("parsedNoSongId") val parsedNoSongId: Long? = null,
    /** ☆ `file.media_mid` 缺失 ⇒ `SongItem.mediaId` 回落 songmid。 */
    @SerializedName("parsedNoMediaMid") val parsedNoMediaMid: Long? = null,
    // —— 取链期（QqApi.fetchPlayUrl）——
    /** `fetchPlayUrl` 被调用次数。 */
    @SerializedName("resolveTotal") val resolveTotal: Long? = null,
    /** ✖ `sourceId` 为 null/空 ⇒ 立即返回 null（无兜底、无重试）。 */
    @SerializedName("resolveNoSongMid") val resolveNoSongMid: Long? = null,
    /** ☆ 实际用了 songmid 当 media_mid（`QqApi` 里那条唯一的取链兜底）。 */
    @SerializedName("resolveMediaMidFallback") val resolveMediaMidFallback: Long? = null,
    /** 拿到 purl（可能已经降档）。 */
    @SerializedName("resolveOk") val resolveOk: Long? = null,
    /** 所有档位 purl 都为空 ⇒ null。 */
    @SerializedName("resolveFail") val resolveFail: Long? = null,
    // —— 结果关联（PlayerViewModel 的 2Hz 判据 / 播放错误回调）——
    /** ★ 兜底曲目的进度真的过了确认阈值。 */
    @SerializedName("fallbackPlaybackConfirmed") val fallbackPlaybackConfirmed: Long? = null,
    /** ★ 兜底曲目报了 `onPlayerError` / `onAudioSinkError`。 */
    @SerializedName("fallbackPlaybackFailed") val fallbackPlaybackFailed: Long? = null,
    // —— 元数据 ——
    @SerializedName("firstSeenAtMs") val firstSeenAtMs: Long? = null,
    @SerializedName("lastUpdatedAtMs") val lastUpdatedAtMs: Long? = null,
    @SerializedName("schemaVersion") val schemaVersion: Int? = null,
) {
    /**
     * 把 `null` 归零的快照。**所有读取方都必须先过它** ——
     * 否则「老 JSON 缺字段」会在 UI 上变成 `null` 而不是 `0`，
     * 而 `0` 与 `null` 在这里语义相同（都没记到）。
     */
    fun canonical(): QqFallbackCounters = QqFallbackCounters(
        parsedTotal = parsedTotal ?: 0L,
        parsedNoSongId = parsedNoSongId ?: 0L,
        parsedNoMediaMid = parsedNoMediaMid ?: 0L,
        resolveTotal = resolveTotal ?: 0L,
        resolveNoSongMid = resolveNoSongMid ?: 0L,
        resolveMediaMidFallback = resolveMediaMidFallback ?: 0L,
        resolveOk = resolveOk ?: 0L,
        resolveFail = resolveFail ?: 0L,
        fallbackPlaybackConfirmed = fallbackPlaybackConfirmed ?: 0L,
        fallbackPlaybackFailed = fallbackPlaybackFailed ?: 0L,
        firstSeenAtMs = firstSeenAtMs ?: 0L,
        lastUpdatedAtMs = lastUpdatedAtMs ?: 0L,
        schemaVersion = schemaVersion ?: SCHEMA_VERSION,
    )

    /** 「无 songid 兜底」在解析出的 QQ 曲目里的占比。分母为 0 时返回 0（不是 NaN）。 */
    fun noSongIdRate(): Double {
        val total = parsedTotal ?: 0L
        if (total <= 0L) return 0.0
        return (parsedNoSongId ?: 0L).toDouble() / total.toDouble()
    }

    /**
     * 兜底曲目的**可播率**：分母 = 兜底曲目里取链成功的那批 + 失败的，
     * 分子 = 其中真的播出声了的。
     *
     * 用 `resolveOk + resolveFail` 而不是 `parsedNoSongId` 作分母是**有意的**：
     * 「解析出来但从未被播放过」的曲目不属于任何一侧，混进分母只会把可播率压低。
     */
    fun fallbackPlayableRate(): Double {
        val tried = (resolveOk ?: 0L) + (resolveFail ?: 0L)
        if (tried <= 0L) return 0.0
        return (fallbackPlaybackConfirmed ?: 0L).toDouble() / tried.toDouble()
    }

    /** 结论一句话（debug 读出与报告都用它，避免两处各算一遍口径）。 */
    fun verdict(): String {
        val c = canonical()
        val rate = c.noSongIdRate()
        return when {
            (c.parsedTotal ?: 0L) <= 0L -> "样本为 0：还没有解析过任何 QQ 曲目"
            rate <= 0.0 -> "兜底从未触发（${c.parsedNoSongId}/${c.parsedTotal}）"
            (c.resolveOk ?: 0L) + (c.resolveFail ?: 0L) <= 0L ->
                "触发过兜底但取链样本为 0，还判不出可靠性"
            c.resolveFail == 0L -> "兜底路径取链 100% 成功，可播率 " + pct(c.fallbackPlayableRate())
            else -> "兜底路径取链失败 " + (c.resolveFail ?: 0L) + " 次，可播率 " + pct(c.fallbackPlayableRate())
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        private fun pct(v: Double): String =
            ((v * 1000.0).toLong() / 10.0).toString() + "%"
    }
}

/**
 * 线程安全的计数器。**纯 JVM**，不碰 Android、不碰网络、不落盘。
 *
 * ## 为什么是 `AtomicLong` 而不是 `@Volatile var Long` / `synchronized`
 *
 * 三个事实决定了这个选择（全部有证据，不是风格偏好）：
 *
 * 1. 自增来自**两条不同的线程**：解析与取链在 `Dispatchers.IO`，播放确认在
 *    ExoPlayer 主线程的 2Hz 心跳里；
 * 2. `+=` 不是原子操作 —— `@Volatile` 只保证可见性、不保证读-改-写；
 * 3. `synchronized` 会让主线程 2Hz 的心跳与 IO 线程争锁。
 *    `AtomicLong.incrementAndGet()` 无锁、约纳秒级。
 *
 * 这条结论不是新发明的：`LyricRequestGate` 的注释里已经写过同一件事
 * （「线程安全用 AtomicLong 而不是 @Volatile + synchronized：这里的操作只有自增」）。
 */
class QqFallbackCounter(private val clock: () -> Long = System::currentTimeMillis) {

    private val parsedTotal = AtomicLong()
    private val parsedNoSongId = AtomicLong()
    private val parsedNoMediaMid = AtomicLong()
    private val resolveTotal = AtomicLong()
    private val resolveNoSongMid = AtomicLong()
    private val resolveMediaMidFallback = AtomicLong()
    private val resolveOk = AtomicLong()
    private val resolveFail = AtomicLong()
    private val fallbackPlaybackConfirmed = AtomicLong()
    private val fallbackPlaybackFailed = AtomicLong()
    private var firstSeenAtMs = 0L
    private var lastUpdatedAtMs = 0L

    private fun touch() {
        val now = clock()
        synchronized(this) {
            if (firstSeenAtMs == 0L) firstSeenAtMs = now
            lastUpdatedAtMs = now
        }
    }

    // ------------------------------------------------------------ 解析期 ----

    /** 每解析出一首 QQ 曲目调用一次。[hadSongId] = 服务端给了可用的数字 songid。 */
    fun onParsed(hadSongId: Boolean, hadMediaMid: Boolean) {
        parsedTotal.incrementAndGet()
        if (!hadSongId) parsedNoSongId.incrementAndGet()
        if (!hadMediaMid) parsedNoMediaMid.incrementAndGet()
        touch()
    }

    // ------------------------------------------------------------ 取链期 ----

    fun onResolveAttempt() {
        resolveTotal.incrementAndGet()
        touch()
    }

    fun onResolveMissingSongMid() {
        resolveNoSongMid.incrementAndGet()
    }

    fun onResolveMediaMidFallback() {
        resolveMediaMidFallback.incrementAndGet()
    }

    fun onResolveOk() {
        resolveOk.incrementAndGet()
        touch()
    }

    fun onResolveFail() {
        resolveFail.incrementAndGet()
        touch()
    }

    // -------------------------------------------------------- 结果关联 ----

    fun onPlaybackConfirmed() {
        fallbackPlaybackConfirmed.incrementAndGet()
        touch()
    }

    fun onPlaybackFailed() {
        fallbackPlaybackFailed.incrementAndGet()
        touch()
    }

    fun snapshot(): QqFallbackCounters = QqFallbackCounters(
        parsedTotal = parsedTotal.get(),
        parsedNoSongId = parsedNoSongId.get(),
        parsedNoMediaMid = parsedNoMediaMid.get(),
        resolveTotal = resolveTotal.get(),
        resolveNoSongMid = resolveNoSongMid.get(),
        resolveMediaMidFallback = resolveMediaMidFallback.get(),
        resolveOk = resolveOk.get(),
        resolveFail = resolveFail.get(),
        fallbackPlaybackConfirmed = fallbackPlaybackConfirmed.get(),
        fallbackPlaybackFailed = fallbackPlaybackFailed.get(),
        firstSeenAtMs = synchronized(this) { firstSeenAtMs },
        lastUpdatedAtMs = synchronized(this) { lastUpdatedAtMs },
        schemaVersion = QqFallbackCounters.SCHEMA_VERSION,
    )

    /**
     * 从落盘快照恢复（进程重启后继续累加，而不是从 0 开始 ——
     * 否则「跑一两天」这个要求根本无法满足：任何一次进程回收都会把样本清空）。
     */
    fun seed(from: QqFallbackCounters?) {
        val c = from?.canonical() ?: return
        parsedTotal.set(c.parsedTotal ?: 0L)
        parsedNoSongId.set(c.parsedNoSongId ?: 0L)
        parsedNoMediaMid.set(c.parsedNoMediaMid ?: 0L)
        resolveTotal.set(c.resolveTotal ?: 0L)
        resolveNoSongMid.set(c.resolveNoSongMid ?: 0L)
        resolveMediaMidFallback.set(c.resolveMediaMidFallback ?: 0L)
        resolveOk.set(c.resolveOk ?: 0L)
        resolveFail.set(c.resolveFail ?: 0L)
        fallbackPlaybackConfirmed.set(c.fallbackPlaybackConfirmed ?: 0L)
        fallbackPlaybackFailed.set(c.fallbackPlaybackFailed ?: 0L)
        synchronized(this) {
            firstSeenAtMs = c.firstSeenAtMs ?: 0L
            lastUpdatedAtMs = c.lastUpdatedAtMs ?: 0L
        }
    }

    /** 供单测清空（生产代码不用）。 */
    internal fun resetForTest() {
        seed(null)
        parsedTotal.set(0L)
        parsedNoSongId.set(0L)
        parsedNoMediaMid.set(0L)
        resolveTotal.set(0L)
        resolveNoSongMid.set(0L)
        resolveMediaMidFallback.set(0L)
        resolveOk.set(0L)
        resolveFail.set(0L)
        fallbackPlaybackConfirmed.set(0L)
        fallbackPlaybackFailed.set(0L)
        synchronized(this) {
            firstSeenAtMs = 0L
            lastUpdatedAtMs = 0L
        }
    }
}

/**
 * 进程内唯一计数器 + 那几处埋点的**唯一入口**。
 *
 * ## 为什么埋点不能散在各调用点
 *
 * 「这首歌的 id 是不是兜底造出来的」这个判据必须**只有一份**：
 * 它就是 [SourceIds.isSynthesizedQqId]（`qqId == QQ_ID_FLAG or hash(songmid)`）。
 * 若在 `QqSongMapper` 里记一次、在 `PlayerViewModel` 里再判一次，
 * 两处迟早漂移成「解析说兜底、播放说不是」，那统计就白做了。
 *
 * ## 开销（铁律：非核心组件不得破坏核心播放链路）
 *
 * 每个方法最多一次 `AtomicLong.incrementAndGet()` + 一次 `System.currentTimeMillis()`
 * （只在**首次**与**更新时间戳**时）。没有分配、没有锁竞争、没有 IO、没有网络。
 * `onPlaybackTick` 额外带一次 `@Volatile Long` 短路比较，与既有
 * `lastReportedSongId`（`PlayerViewModel`）完全同形。
 */
object QqProbeCounters {

    private val counter = QqFallbackCounter()

    /** 「本曲已经确认过」的短路值，避免 2Hz 心跳里重复自增。 */
    @Volatile
    private var lastConfirmedSongId: Long = -1L

    @Volatile
    private var lastFailedSongId: Long = -1L

    fun onParsed(hadSongId: Boolean, hadMediaMid: Boolean) =
        counter.onParsed(hadSongId, hadMediaMid)

    fun onResolveAttempt() = counter.onResolveAttempt()

    fun onResolveMissingSongMid() = counter.onResolveMissingSongMid()

    fun onResolveMediaMidFallback() = counter.onResolveMediaMidFallback()

    fun onResolveOk() = counter.onResolveOk()

    fun onResolveFail() = counter.onResolveFail()

    /**
     * 播放确认（进度越过阈值）。**只对兜底曲目计数** ——
     * 分母是「兜底曲目的取链尝试」，把普通曲目混进来会让可播率失真。
     */
    fun onPlaybackTick(songId: Long, sourceId: String?) {
        if (songId <= 0L || songId == lastConfirmedSongId) return
        if (!SourceIds.isSynthesizedQqId(songId, sourceId)) return
        lastConfirmedSongId = songId
        counter.onPlaybackConfirmed()
    }

    /** 播放失败（`onPlayerError` / `onAudioSinkError`），同样只对兜底曲目计数。 */
    fun onPlaybackFailure(songId: Long, sourceId: String?) {
        if (songId <= 0L || songId == lastFailedSongId) return
        if (!SourceIds.isSynthesizedQqId(songId, sourceId)) return
        lastFailedSongId = songId
        counter.onPlaybackFailed()
    }

    fun snapshot(): QqFallbackCounters = counter.snapshot()

    fun seed(from: QqFallbackCounters?) = counter.seed(from)

    internal fun resetForTest() {
        counter.resetForTest()
        lastConfirmedSongId = -1L
        lastFailedSongId = -1L
    }
}
