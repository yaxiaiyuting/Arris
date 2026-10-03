/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 播放统计的**聚合模型**（快照 + 可变的累加器）。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.stats

/**
 * 一首歌的累计值。
 *
 * 键是 `TrackKey.tag`（`netease:123` / `qqmusic:456`），**不是裸 songId** ——
 * 两个平台的数字 id 完全可能撞号（`source/SourceIds` 的 KDoc 里已经写明这条），
 * 用裸 id 当键会把 QQ 的《晴天》合并进网易云的《晴天》。
 *
 * ## 为什么这里的字段是**非空**的（而落盘 DTO 是可空的）
 *
 * 「新字段一律可空 + 有默认值」（AGENTS.md v2.5.4 规则 1 / 歌词缓存那条迁移纪律）
 * 针对的是**会被 Gson 直接反序列化的类** —— Gson 走 Unsafe、不调用构造函数，
 * 老数据缺 key 时那个字段就是 null。本类**不是**那种类：
 * 落盘路径只经 `StatsCodec.SongDto`（那才是全字段可空 + 有默认值的形状），
 * `SongStat` 一律由 `StatsCodec.decodeSong` 逐字段补默认值后**显式构造**。
 *
 * 这样做的好处是「缺字段」这件事只在**一个地方**处理（DTO 的默认值），
 * 而不是散落到每个消费点上写 `?: 0L` —— 那种散落写法迟早会漏一处，
 * 而漏掉的那一处通常就是用来判 `countsAsPlay` 的 `durationMs`。
 *
 * 这份数据是本版**新开**的 prefs 文件（`ncrust_stats`），没有历史形状要认；
 * 但加字段是迟早的事，所以形状从一开始就按「加得起字段」的样子定。
 */
data class SongStat(
    val key: String,
    val name: String,
    val artist: String,
    val ms: Long,
    val plays: Long,
    val lastAtMs: Long,
    val durationMs: Long,
)

/**
 * 统计快照（**不可变**，UI 与编解码都用它）。
 *
 * ## 为什么是「聚合计数器」而不是「逐事件表」
 *
 * 量级估算（按重度用户）：
 * - 逐事件表：一次会话一行 ⇒ 每天 100 首 × 365 天 × 5 年 = **18 万行**。
 *   Gson 一个 18 万行的数组 ≈ 7 MB 字符串，SharedPreferences **每次写盘都要整份重写** ——
 *   这不是「能不能存下」的问题，是「每次落盘都要卡一次 IO」的问题。
 * - 聚合计数器：`totalMs` 是 1 个 Long；`byDay` 上限 [StatsLimits.MAX_DAY_BUCKETS] 天；
 *   平台最多几个；`songs` 上限 [StatsLimits.MAX_SONGS] 首。
 *   实测形状 ≈ 500 × 130 B + 400 × 30 B ≈ **80 KB**，且**与听了多少年无关**（有界）。
 *
 * 代价是**放弃了个体明细**（「上周三 14:23 听的那首歌」查不回来）。统计页要回答的是
 * 「听了多久 / 听了什么 / 在哪个平台」，聚合值就够；真要逐事件，得换 Room，而本项目没有 Room。
 */
data class StatsSnapshot(
    val totalMs: Long = 0L,
    /** 计入的播放次数（口径见 [StatsMath.countsAsPlay]）。 */
    val totalPlays: Long = 0L,
    /** 最早有记录的一天（ISO `YYYY-MM-DD`），没有任何数据时为 null。 */
    val firstDayKey: String? = null,
    /** `YYYY-MM-DD` → 毫秒。**升序插入**，且只保留最近若干天。 */
    val byDay: Map<String, Long> = emptyMap(),
    /** 音源 key → 毫秒。 */
    val bySource: Map<String, Long> = emptyMap(),
    /** 音源 key → 播放次数。 */
    val playsBySource: Map<String, Long> = emptyMap(),
    /** `TrackKey.tag` → 单曲累计。 */
    val songs: Map<String, SongStat> = emptyMap(),
) {
    /** 「平均每天」的分母（见 [StatsMath.averagePerActiveDay]）。 */
    val activeDays: Int get() = byDay.size

    val isEmpty: Boolean get() = totalMs <= 0L && songs.isEmpty()

    companion object {
        val EMPTY = StatsSnapshot()
    }
}

/** 各种上限的**唯一事实来源**（落盘体积与内存占用都由它决定）。 */
object StatsLimits {
    /** 单曲表上限（LRU：超限时淘汰 `lastAtMs` 最旧的）。 */
    const val MAX_SONGS = 500

    /** 日桶上限（保留最近这么多天；更早的数据只剩在 `totalMs` 这个标量里）。 */
    const val MAX_DAY_BUCKETS = 400

    /** 页面「听最多的歌曲」显示几首。 */
    const val TOP_SONGS = 20
}

/**
 * 可变累加器（只有 `StatsStore` 持有它，且只在主线程改）。
 *
 * 抽成独立的纯类而不是塞进 `StatsStore`，是为了让「合并维度 / 淘汰 / 取快照」
 * 这些真正会算错的地方能在 JVM 上直接测 —— `StatsStore` 那边只剩 SharedPreferences 与协程。
 */
class StatsAggregate {

    var totalMs: Long = 0L
        private set
    var totalPlays: Long = 0L
        private set
    var firstDayKey: String? = null
        private set

    // LinkedHashMap：保持「首次出现顺序」，于是 Top-N 在并列时是稳定的
    // （sortedByDescending 是稳定排序，并列项的相对顺序 = 这里的插入顺序）。
    private val byDay = LinkedHashMap<String, Long>()
    private val bySource = LinkedHashMap<String, Long>()
    private val playsBySource = LinkedHashMap<String, Long>()
    private val songs = LinkedHashMap<String, SongStat>()

    /**
     * 记一段**有效收听**。`slices` 由 [StatsMath.splitAcrossDays] 切好 ——
     * 累加器不再关心时区，这是刻意的：时区只在「采样的那一刻」有意义，
     * 跨零点的那一段必须在采样时就切开，事后没有足够信息补切。
     *
     * `name` / `artist` 为空时**保留旧值**而不是覆盖：无缝预载切歌时
     * `currentSongName` 有时比位置更新晚一帧到，用空串覆盖会让 Top-N 里
     * 已经显示正常的歌名突然变成空白。
     */
    fun addListen(
        slices: List<DaySlice>,
        songKey: String,
        sourceKey: String,
        name: String,
        artist: String,
        durationMs: Long,
        nowMs: Long,
    ) {
        var added = 0L
        for (slice in slices) {
            if (slice.ms <= 0L) continue
            byDay[slice.dayKey] = (byDay[slice.dayKey] ?: 0L) + slice.ms
            added += slice.ms
            val first = firstDayKey
            if (first == null || slice.dayKey < first) firstDayKey = slice.dayKey
        }
        if (added <= 0L) return

        totalMs += added
        bySource[sourceKey] = (bySource[sourceKey] ?: 0L) + added

        val prev = songs[songKey]
        songs[songKey] = SongStat(
            key = songKey,
            name = name.ifEmpty { prev?.name ?: "" },
            artist = artist.ifEmpty { prev?.artist ?: "" },
            ms = (prev?.ms ?: 0L) + added,
            plays = prev?.plays ?: 0L,
            lastAtMs = nowMs,
            // 时长取「见过的最大值」：`duration` 在起播的头几百毫秒里常常还是 0，
            // 用 0 覆盖会让 [StatsMath.countsAsPlay] 的比例判据永久失效。
            durationMs = if (durationMs > 0L) durationMs else (prev?.durationMs ?: 0L),
        )
    }

    /**
     * 记一次「播放」。
     *
     * 单曲条目**可能不存在**（一次都没到过 500 ms 采样就计数了，理论上不会发生，
     * 但 `addPlay` 不假设调用顺序）—— 那种情况只加平台与总数，不凭空造一条单曲记录：
     * 造出来的条目 `ms = 0`、`name = ""`，显示在 Top-N 里就是一行空白。
     */
    fun addPlay(songKey: String, sourceKey: String) {
        totalPlays += 1
        playsBySource[sourceKey] = (playsBySource[sourceKey] ?: 0L) + 1
        val prev = songs[songKey] ?: return
        songs[songKey] = prev.copy(plays = prev.plays + 1)
    }

    /**
     * 淘汰（**有界性的落点**）。两处独立上限，都在写盘前调用一次。
     *
     * 注意 `totalMs` / `totalPlays` **不参与淘汰** —— 它们是标量，
     * 所以「总共听了多久」永远精确，只有「按天/按曲的明细」会被裁剪。
     */
    fun prune(
        maxSongs: Int = StatsLimits.MAX_SONGS,
        maxDayBuckets: Int = StatsLimits.MAX_DAY_BUCKETS,
    ) {
        if (songs.size > maxSongs) {
            songs.entries
                .sortedByDescending { it.value.lastAtMs }
                .drop(maxSongs)
                .forEach { songs.remove(it.key) }
        }
        if (byDay.size > maxDayBuckets) {
            // ISO 键的字典序 == 时间序 ⇒ 直接排序后丢掉最旧的那些。
            byDay.keys.sorted().dropLast(maxDayBuckets).forEach { byDay.remove(it) }
        }
    }

    fun snapshot(): StatsSnapshot = StatsSnapshot(
        totalMs = totalMs,
        totalPlays = totalPlays,
        firstDayKey = firstDayKey,
        byDay = byDay.toMap(),
        bySource = bySource.toMap(),
        playsBySource = playsBySource.toMap(),
        songs = songs.toMap(),
    )

    companion object {
        /** 从落盘的快照恢复。 */
        fun from(snapshot: StatsSnapshot): StatsAggregate = StatsAggregate().also { agg ->
            agg.totalMs = snapshot.totalMs.coerceAtLeast(0L)
            agg.totalPlays = snapshot.totalPlays.coerceAtLeast(0L)
            agg.firstDayKey = snapshot.firstDayKey
            for ((k, v) in snapshot.byDay) if (v > 0L) agg.byDay[k] = v
            for ((k, v) in snapshot.bySource) if (v > 0L) agg.bySource[k] = v
            for ((k, v) in snapshot.playsBySource) if (v > 0L) agg.playsBySource[k] = v
            for ((k, v) in snapshot.songs) if (v.ms > 0L || v.plays > 0L) agg.songs[k] = v
            // 落盘里可能存着旧上限留下的更多条目（用户降级回老版本、或上限被调小），
            // 恢复时先裁一次，保证内存里的形状与当前上限一致。
            agg.prune()
        }
    }
}
