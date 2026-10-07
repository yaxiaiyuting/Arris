/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · B：跨音源 id 上报闸门。**纯逻辑 + 纯 JVM 计数器**，不碰 Android、不碰网络。
 */

package com.takahashirinta.ncrust.player

import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.source.SourceIds
import java.util.concurrent.atomic.AtomicLong

/**
 * 「这个 id 能不能上报给那个服务」——**唯一判据落点**。
 *
 * ## 它修的是什么
 *
 * v2.5.4 的探针发现：`PlayReporter`（**ncm**的 webLog 上报）的入口卫语句只有
 * `cookie 里有 MUSIC_U` 与 `songId > 0` 两条，**没有任何音源判据**。而 qm 的
 * 数字 id 由 [SourceIds.qqId] 合成（`bit62` 恒置位）：
 *
 * ```
 * 1L shl 62 = 4611686018427387904   ← 正数，远大于 0
 * QQ songid 0039MnYb0qxYhV ⇒ 4611686018427387904 or 1234567890 = 4611686019657955794
 * ```
 *
 * 也就是说**一个 QQ 曲目的合成 id 会原样通过那两条卫语句**，被 POST 到
 * `clientlogusf.music.163.com/api/feedback/weblog`。ncm 拿到的是一条它自己
 * id 空间里不存在的 `id`，配上一段它没有的播放时长 —— 这正是「跨源数据泄露」
 * 的形状：不是崩溃，不是用户可见的错，而是**把一个不该给的数据给了另一个服务**。
 *
 * ## 判据为什么只能是 bit62
 *
 * 与 [SourceIds.sourceOfId] 同源：ncm 的 songId 是十进制百万~十亿量级
 * （远小于 `2^40`），**永远不可能**触到位 62 —— 所以「带标志位 ⇒ qm」
 * 是结构性的、不会误判的结论，而不是启发式。
 *
 * **不许用 id 区间启发式**：QQ 的裸 songid 与 ncm 的 id 同样是 9~10 位十进制，
 * 区间完全重叠（`docs/verification/v2.5.4/probe-search-history.md` 的实测）。
 * 也不许用散列反推 —— [SourceIds.qqId] 的兜底散列是不可逆的。
 *
 * ## 两个方向都定义，但只有一边有调用方
 *
 * [Target.QQ] 这一侧**目前没有消费者**：仓库里没有任何向 QQ 上报播放行为的实现
 * （`grep -rn "weblog\|reportPlay" app/src/main/java` 只命中 ncm 那条链路）。
 * 仍然把它写进枚举，是因为 AGENTS.md 的新规则是**双向**的
 * （「跨源 id 不得上报给非本源服务」），而一条只写在文档里的规则不会在
 * 有人新加 QQ 上报时拦住他。有了 [mayReport] 这个唯一落点，
 * 那一天只需要 `ReportGate.mayReport(Target.QQ, id)` 一行即可复用全部判据与单测。
 *
 * ## v3.4.5 · P0：判据从「不是 QQ」改成「是 ncm」（B 站漏网）
 *
 * v2.5.5 写这道闸门时只有两个音源，所以 `!isQq` 恰好等价于「是 ncm」。
 * 但 **B 站音源是 v3.1.0（两天后）才进来的**，它的 id 是 bit61 置位、bit62 清零
 * ⇒ `isQq` 为 false ⇒ **被放行到 ncm 的 webLog**。根因与实测见 [SourceIds.isNeteaseId]。
 *
 * 因此这里把契约从「**恰好**被一个目标接受」修正为「**至多**被一个目标接受」：
 * 带标志位但**没有对应上报目标**的音源（当前是 B 站）现在**两个目标都拒绝** ——
 * 上报本来就无处可去，丢掉才是对的。任何新增音源在补齐自己的上报目标之前，
 * 默认是被挡住的，而不是默认放行。
 */
object ReportGate {

    /** 上报目标（= 服务）。一个目标一个 id 空间。 */
    enum class Target {
        /** ncm `clientlogusf.music.163.com/api/feedback/weblog`。 */
        NETEASE_WEBLOG,

        /** qm（**目前无实现**，见类 KDoc）。 */
        QQ,
    }

    /**
     * 这个 id 能不能上报给这个目标。
     *
     * - [Target.NETEASE_WEBLOG]：**必须是 ncm 自己的 id**（[SourceIds.isNeteaseId]）；
     * - [Target.QQ]：`id > 0` 且**是** QQ 合成 id。
     *
     * `id <= 0` 一律拒绝（两个方向都是）—— 它不是任何真实曲目。
     *
     * ## v3.4.5 · P0：ncm 侧从「不是 QQ」改成「是 ncm」
     *
     * 旧判据是 `!isQq`。B 站音源（v3.1.0）的 id 是 bit61 置位、bit62 清零，
     * 所以 `isQq` 为 false ⇒ 被**放行**到 ncm 的 webLog。改成正向白名单后，
     * 「带标志位但没有对应上报目标」的音源（当前是 B 站）**两个目标都拒绝**：
     * 上报本就无处可去，丢掉才是对的。完整根因见 [SourceIds.isNeteaseId]。
     */
    fun mayReport(target: Target, songId: Long): Boolean = when (target) {
        Target.NETEASE_WEBLOG -> SourceIds.isNeteaseId(songId)
        Target.QQ -> songId > 0L && SourceIds.isQqId(songId)
    }

    /**
     * 拦截原因（本地日志用）。允许上报时返回 `null`。
     *
     * 单独一个函数而不是让调用方自己拼字符串：日志文案要能**直接回答**
     * 「为什么这条被拦了」，而拼字符串的地方迟早会分叉。
     */
    fun blockReason(target: Target, songId: Long): String? = when {
        songId <= 0L -> "id<=0"
        mayReport(target, songId) -> null
        else -> "cross-source:${SourceIds.sourceOfId(songId).key}->${target.name}"
    }
}

/**
 * 被闸门拦下的次数（快照）。**只落本地私有目录，绝不上报。**
 *
 * 每个字段**可空 + 有默认值**：Gson 走 Unsafe 反序列化、不调用构造函数，
 * 老 JSON 里缺的 key 读出来是 `null`，由 [canonical] 归零
 * （「缺失」与「显式 0」在这里语义相同：都是「没记到」）。
 */
data class ReportGateCounters(
    /** 被拦下的总次数。 */
    @SerializedName("blockedTotal") val blockedTotal: Long? = null,
    /** 其中「QQ 合成 id 试图上报给 ncm」的次数 —— v2.5.5 修的就是这一条。 */
    @SerializedName("blockedQqToNetease") val blockedQqToNetease: Long? = null,
    /**
     * v3.4.5 · P0：其中「B 站合成 id 试图上报给 ncm」的次数。
     *
     * 与 [blockedQqToNetease] 分开记，是因为两者**根因不同**：QQ 那次是判据压根不存在，
     * 这次是「负向判据 + 两天后新增音源」。混在一栏里，下一个看日志的人会把
     * B 站的量当成 QQ 的存量，从而误判闸门有没有生效。
     *
     * 新字段一律**可空 + 有默认值**（Gson 走 Unsafe 反序列化、不调用构造函数），
     * 老 JSON 缺这个 key 时读出来是 `null`，由 [canonical] 归零。
     */
    @SerializedName("blockedBiliToNetease") val blockedBiliToNetease: Long? = null,
    /** 其中「非 QQ id 试图上报给 QQ」的次数（当前恒为 0，见 [ReportGate.Target.QQ]）。 */
    @SerializedName("blockedNeteaseToQq") val blockedNeteaseToQq: Long? = null,
    /** 其中 `id <= 0` 的次数（不是跨源，是脏数据）。 */
    @SerializedName("blockedInvalidId") val blockedInvalidId: Long? = null,
    /** 放行并真的发了请求的次数（分母：用来算拦截率）。 */
    @SerializedName("reportedTotal") val reportedTotal: Long? = null,
    @SerializedName("firstSeenAtMs") val firstSeenAtMs: Long? = null,
    @SerializedName("lastUpdatedAtMs") val lastUpdatedAtMs: Long? = null,
    @SerializedName("schemaVersion") val schemaVersion: Int? = null,
) {
    /** 把 `null` 归零的快照。所有读取方都必须先过它。 */
    fun canonical(): ReportGateCounters = ReportGateCounters(
        blockedTotal = blockedTotal ?: 0L,
        blockedQqToNetease = blockedQqToNetease ?: 0L,
        blockedBiliToNetease = blockedBiliToNetease ?: 0L,
        blockedNeteaseToQq = blockedNeteaseToQq ?: 0L,
        blockedInvalidId = blockedInvalidId ?: 0L,
        reportedTotal = reportedTotal ?: 0L,
        firstSeenAtMs = firstSeenAtMs ?: 0L,
        lastUpdatedAtMs = lastUpdatedAtMs ?: 0L,
        schemaVersion = schemaVersion ?: SCHEMA_VERSION,
    )

    /**
     * 拦截率。分母 = 拦截 + 放行；分母为 0 时返回 0.0（不是 NaN）。
     *
     * 单独给一个数而不是让人自己算：这个是「闸门有没有把正常上报一起挡掉」的
     * 唯一观测量 —— 拦截率接近 1 说明判据反了。
     */
    fun blockRate(): Double {
        val c = canonical()
        val denom = (c.blockedTotal ?: 0L) + (c.reportedTotal ?: 0L)
        if (denom <= 0L) return 0.0
        return (c.blockedTotal ?: 0L).toDouble() / denom.toDouble()
    }

    /** 结论一句话（诊断入口与报告共用，避免两处各算一遍口径）。 */
    fun verdict(): String {
        val c = canonical()
        return when {
            (c.blockedTotal ?: 0L) <= 0L && (c.reportedTotal ?: 0L) <= 0L ->
                "样本为 0：还没有发生过任何上报或拦截"
            (c.blockedTotal ?: 0L) <= 0L ->
                "闸门从未触发（放行 ${c.reportedTotal} 次）"
            (c.reportedTotal ?: 0L) <= 0L ->
                "闸门拦下 ${c.blockedTotal} 次、放行 0 次 —— 判据可能反了"
            else ->
                "拦下 ${c.blockedTotal} 次（QQ→ncm ${c.blockedQqToNetease} / B站→ncm ${c.blockedBiliToNetease}）" +
                    " / 放行 ${c.reportedTotal} 次"
        }
    }

    companion object {
        /**
         * 持久化快照的 schema 版本。
         *
         * v1 → v2（v3.4.5 · P0）：新增 [blockedBiliToNetease]。加字段是**向后兼容**的
         * （老数据缺 key ⇒ `null` ⇒ [canonical] 归零），所以不写迁移逻辑；
         * 但版本号必须跟着走，否则将来分不清一份快照里到底有没有这一栏。
         */
        const val SCHEMA_VERSION = 2
    }
}

/**
 * 线程安全的计数器。**纯 JVM**，不碰 Android、不碰网络、不落盘。
 *
 * 自增来自 `ncrust-weblog` 线程（`PlayReporter` 的 fire-and-forget 线程），
 * 与 [com.takahashirinta.ncrust.qq.QqFallbackCounter] 同源的理由：
 * `+=` 不是原子操作、`@Volatile` 只保证可见性，而 `synchronized` 会引入锁。
 * 这里只有自增，`AtomicLong` 是唯一正确的选择。
 *
 * **埋点点位不做 IO**（AGENTS.md v2.5.4 规则 3）：本类只有内存自增，
 * 落盘在 [ReportGateStore] 里、由调用方在非播放关键路径上触发。
 */
class ReportGateCounter(private val clock: () -> Long = System::currentTimeMillis) {

    private val blockedTotal = AtomicLong()
    private val blockedQqToNetease = AtomicLong()
    private val blockedBiliToNetease = AtomicLong()
    private val blockedNeteaseToQq = AtomicLong()
    private val blockedInvalidId = AtomicLong()
    private val reportedTotal = AtomicLong()
    private var firstSeenAtMs = 0L
    private var lastUpdatedAtMs = 0L

    private fun touch() {
        val now = clock()
        if (firstSeenAtMs == 0L) firstSeenAtMs = now
        lastUpdatedAtMs = now
    }

    /**
     * 记一次拦截。[songId] 与 [target] 决定归到哪一栏。
     *
     * 归类判据与 [ReportGate.mayReport] 同源（都用 `SourceIds` 的谓词），
     * 不在这里重写一遍 `songId <= 0` 之类的条件。
     *
     * v3.4.5 · P0：ncm 侧的被拦 id 现在可能是 QQ **或 B 站**，所以必须先按
     * [SourceIds.isBiliId] 分一次栏 —— 否则 B 站的量会记进「QQ→ncm」，
     * 让日志读者以为闸门在拦 QQ 存量。
     */
    fun onBlocked(target: ReportGate.Target, songId: Long) {
        blockedTotal.incrementAndGet()
        when {
            songId <= 0L -> blockedInvalidId.incrementAndGet()
            target != ReportGate.Target.NETEASE_WEBLOG -> blockedNeteaseToQq.incrementAndGet()
            SourceIds.isBiliId(songId) -> blockedBiliToNetease.incrementAndGet()
            else -> blockedQqToNetease.incrementAndGet()
        }
        touch()
    }

    /** 记一次放行（真的发了请求）。 */
    fun onReported() {
        reportedTotal.incrementAndGet()
        touch()
    }

    fun snapshot(): ReportGateCounters = ReportGateCounters(
        blockedTotal = blockedTotal.get(),
        blockedQqToNetease = blockedQqToNetease.get(),
        blockedBiliToNetease = blockedBiliToNetease.get(),
        blockedNeteaseToQq = blockedNeteaseToQq.get(),
        blockedInvalidId = blockedInvalidId.get(),
        reportedTotal = reportedTotal.get(),
        firstSeenAtMs = firstSeenAtMs,
        lastUpdatedAtMs = lastUpdatedAtMs,
        schemaVersion = ReportGateCounters.SCHEMA_VERSION,
    )

    /**
     * 从落盘快照恢复（幂等：只补差值，不覆盖已有计数）。
     *
     * 进程重启后 [ReportGateStore.ensureSeeded] 调它一次。
     * 用「加上去」而不是「赋值」是因为 seed 可能发生在已有自增之后
     * （`PlayReporter` 是 fire-and-forget，不保证 seed 先跑）。
     */
    fun seed(base: ReportGateCounters) {
        val c = base.canonical()
        blockedTotal.addAndGet(c.blockedTotal ?: 0L)
        blockedQqToNetease.addAndGet(c.blockedQqToNetease ?: 0L)
        blockedBiliToNetease.addAndGet(c.blockedBiliToNetease ?: 0L)
        blockedNeteaseToQq.addAndGet(c.blockedNeteaseToQq ?: 0L)
        blockedInvalidId.addAndGet(c.blockedInvalidId ?: 0L)
        reportedTotal.addAndGet(c.reportedTotal ?: 0L)
        if (firstSeenAtMs == 0L) firstSeenAtMs = c.firstSeenAtMs ?: 0L
        if ((c.lastUpdatedAtMs ?: 0L) > lastUpdatedAtMs) lastUpdatedAtMs = c.lastUpdatedAtMs ?: 0L
    }
}

/**
 * 进程级单例。与 `QqProbeCounters` 同一手法（无 DI，service locator）。
 *
 * 名字取 `Stats` 而不是 `Counters`：`ReportGateCounters` 已经是上面那个**快照 data class**
 * （与 `QqFallbackCounters` / `QqProbeCounters` 的分工逐字对应 —— 前者是数据、后者是单例）。
 */
object ReportGateStats {
    val counter = ReportGateCounter()
}
