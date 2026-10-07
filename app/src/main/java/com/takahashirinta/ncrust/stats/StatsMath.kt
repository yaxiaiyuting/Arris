/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 播放统计的**全部算术**：有效收听时长、日分桶、播放计数阈值、Top-N 与占比。
 * **纯逻辑，无 Android 依赖，JVM 可单测**（见 `app/src/test/.../stats/StatsMathTest.kt`）。
 */

package com.takahashirinta.ncrust.stats

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 「有效收听时长」的定义——**这个 object 就是那条定义**，别在别处再写一遍。
 *
 * ## 为什么必须区分「真实收听」与「歌曲时长」
 *
 * 最省事的做法是「这首歌播过 ⇒ 加上它的 duration」。那是**错的**：
 * 用户把进度条拖到 90% 再听 5 秒，等于凭空给统计灌了 3 分钟。统计页一旦会说谎，
 * 它就不再是统计页了。所以本文件的每一毫秒都来自**播放器位置的正向增量**。
 *
 * ## 逐条边界（任务书要求逐条说明，这里就是那一条条）
 *
 * | 情形 | 处理 | 理由 |
 * |---|---|---|
 * | **暂停** | 停表。暂停期间不产生任何增量（`playing=false` 直接返回 0） | 「收听时长」是耳朵的时间，不是挂机的时间 |
 * | **seek 前跳** | **不计入**。判据不是「增量大小」而是「增量 vs 墙上时钟」（见 [listenDelta]） | 拖进度条不是收听；用 delta 与 wall-delta 对比能在不丢失真实播放的前提下把前跳挡掉 |
 * | **seek 回退** | 增量 ≤ 0 ⇒ 不计。**已累计的部分不回滚** | 用户确实已经听过那一段；回退再听一遍也不是新时长（否则拖来拖去就能刷时长） |
 * | **单曲循环** | **分别计**。进度从尾部回到开头且上一采样点已接近结尾 ⇒ 关闭旧会话、开新会话 | 与 Spotify 的 stream 口径一致：重复听是重复消费，不是同一段时长的延长 |
 * | **倍速播放** | 本应用**没有**倍速功能（全仓库 grep `setPlaybackSpeed` / `PlaybackParameters` 零命中，2026-02 核对），所以不需要任何折算 | 若将来加了倍速，「位置增量」仍然等于媒体时间增量 —— 那时要决定的是「按媒体时间还是按真实时间统计」，属于产品口径，**不要**在没决定之前偷偷折算 |
 * | **进度回退（用户拖回开头）** | 与单曲循环的区别见 [RESTART_BACK_MS] 与 `StatsRecorder`：只有当**上一采样点已经接近结尾**（≥90% 时长）时才当成新一次播放，中途拖回开头**不算**新播放 | 保守方向：宁可少算一次播放（用户看不出来），也不要每拖一次就 +1（用户一眼看出这是假的） |
 * | **APP 被杀死** | 内存里的累计值每 **30 秒**落盘一次，且切歌 / 退到后台（`onActivityStopped`）时立即落盘 ⇒ **最多丢 30 秒** | 有界：内存里不存「逐事件流水」，只存**聚合计数器**（毫秒级 Long + 若干 Map），本身就与时长无关；见 `StatsStore` |
 * | **听了 5 秒就切走** | 时长**照记**（用户真的听了 5 秒），但**不计一次「播放」**。判据 [countsAsPlay]：听满 **80%**（与 `PlayReporter` 的判据同源）**或 ≥ 30 秒** | 30 秒是行业口径（Spotify 一个 stream 的门槛）；80% 那条用来覆盖短曲 / 完整播完（自然播完 = 100%）。两条取「或」，所以 20 秒的间奏听完了也算一次 |
 *
 * ## 为什么用 `java.util.Calendar` 而不是 `java.time`
 *
 * `minSdk = 24`，而 `app/build.gradle.kts` **没有开 core library desugaring**
 * （`compileOptions` 里只有 source/targetCompatibility）。`java.time` 要 API 26 ——
 * 本项目的真机主力 S6/G9209 正是 **Android 7.0 = API 24**，用了它会在那台机器上直接
 * `NoClassDefFoundError`。所以这里一律走 `java.util.Calendar` / `TimeZone`（API 1 起就有）。
 */
object StatsMath {

    /**
     * 位置增量允许比墙上时钟**多**出来的毫秒数。
     *
     * 2 Hz 采样下增量与墙上时钟本该几乎相等；这 1 秒是留给「采样抖动 + 播放器位置上报粒度」的。
     * 调小 ⇒ 正常播放也会被判成前跳（统计偏低）；调大 ⇒ 小幅拖动会被当成收听（统计偏高）。
     */
    const val WALL_TOLERANCE_MS = 1_000L

    /**
     * **单个采样点最多能记多少收听时长**。
     *
     * 这是第二道闸：即使墙上时钟被用户往前拨了一小时（wall-delta 也跟着变一小时），
     * 一次「位置跳了 10 秒以上」仍然不会被记成收听。采样周期是 500 ms，
     * 所以任何一次真实增量都不可能接近这个值。
     */
    const val MAX_COUNTABLE_DELTA_MS = 10_000L

    /** 达到这个绝对时长就算一次「播放」，与比例判据取或。 */
    const val MIN_PLAY_MS = 30_000L

    /** 比例判据：听满 80% 算一次播放（分子/分母写成常量，避免浮点比较的边界抖动）。 */
    private const val PLAY_RATIO_NUM = 8L
    private const val PLAY_RATIO_DEN = 10L

    /** 进度回退超过这个毫秒数才算「回到开头重播」，而不是一次普通的向后拖动。 */
    const val RESTART_BACK_MS = 3_000L

    /**
     * 日分桶的**条数上限**（有界性）。
     *
     * `splitAcrossDays` 的正常输入是「两个相邻采样点」（增量 ≤ 10 秒 ⇒ 最多跨 2 天）。
     * 但如果系统时钟被拨到几年后，循环就会跑到上千次 —— 这里给它一个天花板，
     * 超出的部分**并入最后一天**，绝不无限展开。
     */
    private const val MAX_DAY_SLICES = 400

    // ------------------------------------------------------------ 收听时长 ----

    /**
     * 相邻两个位置采样之间「真正在收听」的毫秒数。
     *
     * 判据只有三行，但每一行都对着一个真实故障：
     *
     * 1. `!playing` ⇒ 0：暂停/缓冲/后台停表。**不**用「位置没动」当判据 ——
     *    位置在缓冲时也不动，但那时用户没在听，两者在统计上必须一样（都是 0）。
     * 2. `delta <= 0` ⇒ 0：进度回退**不重复计**。已经累计的那一段也不回滚 ——
     *    用户确实听过，回滚会让「拖回开头重听」变成负贡献，比多算更反直觉。
     * 3. `delta > wallDelta + tolerance` ⇒ 0：**这是 seek 前跳的判据**。
     *    位置前进的速度在物理上不可能超过墙上时钟（没有倍速功能，见类 KDoc），
     *    所以「位置跑得比时钟快」只有一个解释：有人把位置**搬**过去了。
     *    用 wall-delta 而不是固定阈值，是为了不把「主线程被卡了 3 秒、位置一次补上 3 秒」
     *    误判成 seek —— 那种情况 delta ≈ wallDelta，应当照记。
     * 4. `delta > maxCountableMs` ⇒ 0：第二道闸，防墙上时钟被往前拨（见常量 KDoc）。
     *
     * @param prevPosMs 上一次采样的播放位置
     * @param posMs 本次采样的播放位置
     * @param wallDeltaMs 两次采样之间的**墙上时钟**毫秒数（由 [wallDeltaOf] 归一化，恒 ≥ 0）
     * @param playing 本次采样时播放器是否处于播放态
     */
    fun listenDelta(
        prevPosMs: Long,
        posMs: Long,
        wallDeltaMs: Long,
        playing: Boolean,
        toleranceMs: Long = WALL_TOLERANCE_MS,
        maxCountableMs: Long = MAX_COUNTABLE_DELTA_MS,
    ): Long {
        if (!playing) return 0L
        val delta = posMs - prevPosMs
        if (delta <= 0L) return 0L
        if (delta > maxCountableMs) return 0L
        val allowed = wallDeltaMs + toleranceMs
        if (delta > allowed) return 0L
        return delta
    }

    /**
     * 两次采样之间的墙上时钟差，**时钟回拨时返回 0**。
     *
     * 用户改系统时间 / NTP 校正都可能让 `now < prev`。返回 0 而不是负数：
     * 负的 wall-delta 会让 [listenDelta] 的 `allowed` 变成负数，
     * 于是**正常播放也会被判成前跳**，统计从此一条都不记（而且看起来像"统计坏了"）。
     * 归零之后，正常的 500 ms 增量仍然落在 `tolerance` 里，采样不中断。
     */
    fun wallDeltaOf(prevNowMs: Long, nowMs: Long): Long =
        if (nowMs > prevNowMs) nowMs - prevNowMs else 0L

    /**
     * 这一次收听算不算「一次播放」。
     *
     * 两条取或（理由见 [StatsMath] 的 KDoc）：
     * - **比例**：听满 80% —— 与 `player/PlayReporter` 的 `COMPLETION_THRESHOLD` 同一个数，
     *   两条链路的「听完」口径必须一致，否则用户会看到「上报了但没统计」；
     * - **绝对**：≥ 30 秒 —— 覆盖长曲（10 分钟的歌听 80% 要 8 分钟，太苛刻）。
     *
     * 用整数乘法而不是 `Float`：`0.8f * duration` 在时长 3~4 位数的区间上会有 ±1 ms 的舍入，
     * 而那正好落在「听满 80% 的那一帧」上 —— 单测会因此变成偶发红。
     *
     * @param listenedMs 本会话累计的**有效收听**时长
     * @param durationMs 歌曲时长；`<= 0`（元数据没到）时比例判据不成立
     */
    fun countsAsPlay(
        listenedMs: Long,
        durationMs: Long,
        minPlayMs: Long = MIN_PLAY_MS,
    ): Boolean {
        if (listenedMs <= 0L) return false
        if (listenedMs >= minPlayMs) return true
        if (durationMs <= 0L) return false
        return listenedMs * PLAY_RATIO_DEN >= durationMs * PLAY_RATIO_NUM
    }

    // -------------------------------------------------------------- 日分桶 ----

    /**
     * 某个时刻落在**哪一天**（本地民用日），形状 `YYYY-MM-DD`。
     *
     * 用本地时区而不是 UTC：用户说的「今天听了 2 小时」指的是他自己的今天。
     * ISO 形状（补零 + 固定宽度）让字符串的字典序 == 时间序，
     * 于是 `StatsAggregate` 里的「最早一天」「最近 N 天」都能直接比较字符串，
     * 不需要再解析回日期。
     */
    fun dayKey(epochMs: Long, zone: TimeZone): String {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = epochMs
        return formatDayKey(cal)
    }

    /**
     * 把 `[startMs, endMs)` 这段收听切成「按天」的片。
     *
     * 跨零点的那一分钟必须切开，否则「最近 14 天」的柱子会出现「23:59 听的两小时
     * 全算在昨天」这种看不懂的形状。正常输入（相邻采样点）最多切出 2 片。
     *
     * 三条边界：
     * - `endMs <= startMs`（时钟回拨 / 空区间）⇒ 返回**一条 0 毫秒**的记录，
     *   挂在 start 那天。**不抛异常、不返回空列表** —— 调用方拿到空列表会以为
     *   "这段没发生"，而它其实发生了，只是时长不可知。
     * - 夏令时：日界用 `Calendar` 把本地时间归零再加一天得到。在有「零点不存在」
     *   的时区（如 America/Santiago 的春季切换）上，`Calendar` 的宽松模式会给出
     *   当天的第一个真实时刻，那正是这一天的开始。
     * - 片数上限 [MAX_DAY_SLICES]：超出后剩余全部并入最后一片（有界，绝不无限展开）。
     */
    fun splitAcrossDays(startMs: Long, endMs: Long, zone: TimeZone): List<DaySlice> {
        if (endMs <= startMs) return listOf(DaySlice(dayKey(startMs, zone), 0L))
        val out = ArrayList<DaySlice>(2)
        val cal = Calendar.getInstance(zone)
        var cursor = startMs
        while (cursor < endMs) {
            cal.timeInMillis = cursor
            val key = formatDayKey(cal)
            // 归零到当天 00:00 再加一天 = 次日 00:00（DST 由 Calendar 自行处理）。
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val nextDayStart = cal.timeInMillis

            val atLimit = out.size >= MAX_DAY_SLICES - 1
            val sliceEnd = when {
                atLimit -> endMs
                nextDayStart in (cursor + 1) until endMs -> nextDayStart
                else -> endMs
            }
            val ms = sliceEnd - cursor
            if (ms > 0L) out.add(DaySlice(key, ms))
            // 有界性守卫：`nextDayStart` 若因为时区整日跳过而不大于 cursor，
            // sliceEnd 已经落到 endMs，这里再兜一次，保证循环必定终止。
            if (sliceEnd <= cursor) break
            cursor = sliceEnd
        }
        return out
    }

    /**
     * 最近 [days] 天的序列（**升序，缺口补 0**）。
     *
     * 补 0 是关键：不能只把 `byDay` 里的键排序后画出来 —— 那样「中间有两天没听」
     * 会被压缩掉，柱子看起来是连续的，读图的人会以为每天都在听。
     */
    fun recentDaySeries(
        byDay: Map<String, Long>,
        todayEpochMs: Long,
        days: Int,
        zone: TimeZone,
    ): List<DayPoint> {
        if (days <= 0) return emptyList()
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = todayEpochMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val keys = ArrayList<String>(days)
        for (i in 0 until days) {
            keys.add(formatDayKey(cal))
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return keys.asReversed().map { DayPoint(it, byDay[it] ?: 0L) }
    }

    /**
     * 柱子下面那个刻度文案（`2026-02-14` → `14`）。
     *
     * 只取「日」：14 根柱子的宽度不足以放下 `02-14`，而且月份在「最近 14 天」里
     * 最多跳一次，写月份反而更挤。纯数字 + 8 种语言通用，不需要进 `StatsStrings`。
     */
    fun dayAxisLabel(dayKey: String): String {
        val day = dayKey.substringAfterLast('-').trimStart('0')
        return day.ifEmpty { "0" }
    }

    // -------------------------------------------------------- 聚合 / 占比 ----

    /**
     * 占比（0..100 的整数百分比）。
     *
     * `total <= 0` 时返回 0 而不是除零/NaN：一个「还没有任何数据」的统计页
     * 不该显示 `NaN%`。
     */
    fun sharePercent(value: Long, total: Long): Int {
        if (total <= 0L || value <= 0L) return 0
        val pct = value.toDouble() * 100.0 / total.toDouble()
        return pct.toInt().coerceIn(0, 100)
    }

    /**
     * 按权重取前 [n] 项。`sortedByDescending` 是**稳定**排序 ⇒ 并列时保持输入顺序，
     * 于是「同一份数据每次渲染出来的 Top 10 完全一样」，不会在重组之间跳来跳去。
     */
    fun <T> topN(items: List<T>, n: Int, weight: (T) -> Long): List<T> =
        if (n <= 0) emptyList() else items.sortedByDescending(weight).take(n)

    /**
     * 日均时长 = 总时长 / **有收听的天数**。
     *
     * 为什么分母是「活跃天」而不是「从第一次记录到今天的自然天数」：
     * 后者会把「三个月前听过一次、今天又听」平均成每天几秒，是一个没人想看的数字。
     * 页面上必须把分母写出来（`statsDailyAverageHint`），否则用户会自己脑补成自然日。
     */
    fun averagePerActiveDay(totalMs: Long, activeDays: Int): Long {
        if (activeDays <= 0 || totalMs <= 0L) return 0L
        return totalMs / activeDays
    }

    // ---------------------------------------------------------- 时长格式 ----

    /** 拆成时/分/秒，供 UI 按语言组装文案（纯数字，不含任何本地化字符串）。 */
    fun durationParts(ms: Long): DurationParts {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L).coerceAtMost(359_999_999L)
        return DurationParts(
            hours = (totalSec / 3600L).toInt(),
            minutes = ((totalSec % 3600L) / 60L).toInt(),
            seconds = (totalSec % 60L).toInt(),
        )
    }

    private fun formatDayKey(cal: Calendar): String = String.format(
        // Locale.US 是**必须**的：默认 locale 在阿拉伯语等环境下会产出本地数字字形，
        // 于是 dayKey 不再是 ASCII、字典序也不再等于时间序（跨天比较全错）。
        Locale.US,
        "%04d-%02d-%02d",
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )
}

/** 一天的收听切片。 */
data class DaySlice(val dayKey: String, val ms: Long)

/** 柱状图上的一根柱子。 */
data class DayPoint(val dayKey: String, val ms: Long)

/** 时长拆分（UI 按语言拼）。 */
data class DurationParts(val hours: Int, val minutes: Int, val seconds: Int)
