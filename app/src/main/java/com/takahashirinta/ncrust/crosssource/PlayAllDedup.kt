/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P1：「全部播放」的**跨源混播去重**（纯逻辑，无 Android 依赖，JVM 可单测）。
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource

/**
 * 歌手页「全部播放」的播放列表构建（v2.6.0 · P1）。
 *
 * ## 它解决的问题
 *
 * 「全部播放」把**当前歌手聚合结果**（`ArtistPage.songs`）换成播放队列。
 * 而这一页的列表天然可能包含同一首歌的两个源版本 —— 直接装队会让用户
 * **把同一首歌听两遍**。
 *
 * ## 复用 v2.4.0 的匹配规则，**不新建判据**
 *
 * 去重判据就是 `CrossSourceMatcher.gradeTrack` 的结论 + [MatchConfidence.mergeable]
 * 这一个阈值（`crosssource` 包 KDoc 里那条「唯一阈值」的定义）。
 * 本类**不做任何自己的相似度比较** —— 理由：
 *
 * 1. 那套判据是 v2.4.0 用 230 首真实样本标定过的（时长容差 2s、
 *    版本标记、艺人重叠），另写一份必然与它对不上；
 * 2. 「唯一阈值」这条纪律的存在意义就是**不允许第二个判据**：
 *    两处阈值一旦分叉，页面上「合并成一行」与「只播一遍」就会不是同一件事，
 *    用户看到一行却听到两遍（或反过来）—— 那是比不去重更糟的状态。
 *
 * ## 铁律 23：置信度不足时**宁可保留两份，不可误杀**
 *
 * [MatchConfidence.mergeable] 为 false（`LOW` / `NONE`）时**一条都不合并**，
 * 两条都进队列。这不是保守，是判据本身的语义：`LOW` 的三种来源
 * （仅曲名一致 / 仅曲名 + 时长 / 艺人一致但时长差 > 2s）里，
 * 实测「艺人一致但时长差 > 30s」的 15 条**全部是同名不同版本**（伴奏 / Live / 加长版）
 * —— 合并它们等于把用户想听的那一首删掉。
 *
 * 本类因此**没有任何**「按歌名 + 歌手字符串相等就合并」的捷径：
 * 那种写法会把 `晴天` 与 `晴天 (Remix)`（时长相同 ⇒ HIGH ⇒ 合并是对的）
 * 与 `晴天` 与 `晴天 (Live)`（时长差很大 ⇒ LOW ⇒ **必须保留两份**）
 * 一视同仁，而后者是误杀。
 *
 * ## 两条正交的去重轴
 *
 * | 轴 | 判据 | 谁来保证 |
 * |---|---|---|
 * | A · 跨源同曲 | `mergeable` 的配对（v2.4.0） | 本类（聚合器只保证列表只出现一行，重排/过滤后仍需再保证） |
 * | B · 同源同号 | `TrackKey` 相等 | 本类（`replaceQueueAndPlay` **不判重**，原样装队） |
 *
 * 轴 B 是必要的：`ArtistPage.songs` 在**同源内**也可能出现两条 `TrackKey` 相同的行
 * （聚合器的去重键是 `TrackKey.fromSong`，而本类统一用 `TrackKey.ofSong`，
 * 两者对「`source` 字符串丢失的 QQ 条目」给出不同答案 —— `ofSong` 走 bit62 兜底，
 * 更严）。队列身份按铁律 15 必须是 `TrackKey`，所以这里按它再收一次。
 */
object PlayAllDedup {

    /**
     * 一次「全部播放」的构建结果。
     *
     * @property songs 去重后的播放顺序。**保序**：只删重复项，不重排。
     * @property mergedPairs 被合并掉的跨源对（`保留的 key to 去掉的 key`），诊断与单测用。
     * @property collapsedSameKeys 被轴 B 折叠掉的 key（同源同号重复），诊断与单测用。
     */
    data class Plan(
        val songs: List<SongItem>,
        val mergedPairs: List<Pair<TrackKey, TrackKey>> = emptyList(),
        val collapsedSameKeys: List<TrackKey> = emptyList(),
    ) {
        val isEmpty: Boolean get() = songs.isEmpty()
    }

    /**
     * 构建播放列表。
     *
     * @param rows 当前**已经过滤过的**聚合行（顺序 = 界面顺序，任务书 4.1「顺序：按当前排序」）。
     * @param preferred 页面给出的默认音源（`ArtistPage.preferredSource`）。仅用于
     *   **平级时**的 tie-break，不改变任何合并判据。
     */
    fun plan(
        rows: List<AggregatedSong>,
        preferred: MusicSource? = null,
    ): Plan {
        if (rows.isEmpty()) return Plan(emptyList())

        // ---------------------------------------------------------------- 轴 B
        // 同源同号先折叠（先出现者胜）。这一步同时给后面的配对一个「无重复」的输入。
        val byKey = LinkedHashMap<TrackKey, Int>()
        val collapsed = ArrayList<TrackKey>()
        val indexes = ArrayList<Int>(rows.size)
        rows.forEachIndexed { i, row ->
            val key = TrackKey.ofSong(row.song)
            if (byKey.containsKey(key)) {
                collapsed += key
            } else {
                byKey[key] = i
                indexes += i
            }
        }

        // ---------------------------------------------------------------- 轴 A
        // 跨源配对：锚点 = ncm 那一侧，候选 = QQ 那一侧（各保持界面顺序）。
        // `pairTracks` 是**一对一贪心**的，所以不会出现「两条抢同一个候选」。
        val anchorIdx = indexes.filter { rows[it].song.musicSource == MusicSource.NETEASE }
        val otherIdx = indexes.filter { rows[it].song.musicSource == MusicSource.QQMUSIC }
        val partner = HashMap<Int, Int>()
        if (anchorIdx.isNotEmpty() && otherIdx.isNotEmpty()) {
            val pairs = CrossSourceMatcher.pairTracks(
                anchor = anchorIdx.map { candidateOf(rows[it]) },
                other = otherIdx.map { candidateOf(rows[it]) },
            )
            val indexByKey = HashMap<TrackKey, Int>(otherIdx.size * 2)
            otherIdx.forEach { indexByKey[TrackKey.ofSong(rows[it].song)] = it }
            for (p in pairs) {
                if (!p.verdict.mergeable) continue
                val a = byKey[p.anchor.key] ?: continue
                val b = indexByKey[p.other.key] ?: continue
                if (a == b) continue
                partner[a] = b
                partner[b] = a
            }
        }

        // ------------------------------------------------------- 生成播放顺序
        val songs = ArrayList<SongItem>(indexes.size)
        val mergedPairs = ArrayList<Pair<TrackKey, TrackKey>>()
        val consumed = HashSet<Int>()
        for (i in indexes) {
            if (i in consumed) continue
            val row = rows[i]

            // 聚合器已经吸收掉的对端（`mergedKeys`）也要记账：它们是**这一行代表的其他源版本**，
            // 万一因为列表被重新过滤/拼接而各自成行，也不该再单独播一遍。
            // ⚠️ 只在 `confidence.mergeable` 时采信 —— 低置信度的 `mergedKeys` 是空列表，
            //    但这一条断言让「将来有人放宽聚合器」不会静默变成误杀。
            val absorbed = if (row.confidence.mergeable) row.mergedKeys else emptyList()
            absorbed.forEach { absorbedKey -> byKey[absorbedKey]?.let { idx -> consumed += idx } }

            val j = partner[i]
            val chosen: SongItem
            if (j != null) {
                consumed += j
                chosen = better(rows[i], rows[j], preferred)
                val dropped = if (chosen === rows[i].song) rows[j].song else rows[i].song
                mergedPairs += TrackKey.ofSong(chosen) to TrackKey.ofSong(dropped)
            } else {
                chosen = row.song
            }
            consumed += i
            songs += chosen
        }
        return Plan(songs = songs, mergedPairs = mergedPairs, collapsedSameKeys = collapsed)
    }

    /**
     * 两条**已被判定为同一首**的行里，播哪一份。
     *
     * tie-break 三级（**全部是确定性规则，没有随机、没有 last-write-wins**）：
     * 1. 更能播的那一份（`availabilityOrder` 小者胜）——「标着可播放却播不了」是
     *    这一页最伤人的失败模式，所以它排第一；
     * 2. 平级时取页面默认音源（`ArtistPage.preferredSource`，它由艺人级的可播放比例算出）；
     * 3. 仍平级时取**列表里先出现的那一份**（稳定，同一份数据每次结果一致）。
     */
    private fun better(a: AggregatedSong, b: AggregatedSong, preferred: MusicSource?): SongItem {
        val oa = CrossSourceMatcher.availabilityOrder(a.availability)
        val ob = CrossSourceMatcher.availabilityOrder(b.availability)
        if (oa != ob) return if (oa < ob) a.song else b.song
        if (preferred != null) {
            val pa = a.song.musicSource == preferred
            val pb = b.song.musicSource == preferred
            if (pa != pb) return if (pa) a.song else b.song
        }
        return a.song
    }

    private fun candidateOf(row: AggregatedSong): CrossSourceMatcher.TrackCandidate =
        CrossSourceMatcher.TrackCandidate(
            // ⚠️ 这里用 `TrackKey.ofSong`（带 bit62 回落），不是 `row.key`（= `fromSong`）：
            //    配对结果的 key 会拿去 `byKey` 里查，而那张表是 `ofSong` 建的 ——
            //    两个入口混用会让「source 字符串丢失的 QQ 条目」配上了却查不到。
            key = TrackKey.ofSong(row.song),
            name = row.song.name,
            artists = row.song.artists?.mapNotNull { it.name } ?: emptyList(),
            durationMs = row.song.duration?.takeIf { it > 0L },
            albumName = row.song.album?.name,
        )

    /**
     * 空列表时按钮的处置（任务书 4.1「空列表时按钮置灰或隐藏」）。
     *
     * 抽成一个函数而不是在 UI 里写 `songs.isEmpty()`：这个判据同时影响
     * 「按钮挂不挂」与「点了会不会起播」（`replaceQueueAndPlay` 对空列表静默返回），
     * 两处各写一份必然分叉 —— 而分叉的表现是「按钮亮着，点了没反应」。
     */
    fun isActionable(rows: List<AggregatedSong>): Boolean = rows.isNotEmpty()

    /**
     * 可播放性排序的一个稳定快照口径（诊断与 release notes 用）。
     *
     * 与 [CrossSourceMatcher.availabilityOrder] 同源，不重新定义顺序。
     */
    fun playableCount(songs: List<SongItem>, availabilityOf: (TrackKey) -> TrackAvailability): Int =
        songs.count { availabilityOf(TrackKey.ofSong(it)) == TrackAvailability.PLAYABLE }
}
