/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.4 · 聚合搜索的排序 + 会员专享判定。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.search

/**
 * 一首歌的「播放是否被会员墙挡住」（v2.1.4）。
 *
 * ## 为什么需要它
 *
 * 聚合搜索把两个音源的结果拼在一张表里，而**拼的顺序此前是硬编码的**：
 * ncm 在前、QQ 接在后。用户明明有 QQ 绿钻，搜出来的歌却要往下翻半天才看到 QQ 的 ——
 * 会员买在哪一家，哪一家的结果就该先被看见。
 *
 * ## 与「音质是否需要会员」是两件事
 *
 * 这里只回答「**能不能播**」。一首免费曲的无损档位需要会员属于**音质**权益
 * （ncm 实测 `fee=8`：播放免费、无损要会员），不算 [MEMBER_ONLY] ——
 * 把那种歌归成会员专享会让排序去推一首本来谁都能放的歌。
 */
enum class TrackAccess {
    /**
     * 播放**需要会员**（会员专享）。
     *
     * - ncm：`fee == 1`（VIP 专享）或 `fee == 4`（数字专辑，买了才能听）。
     *   `fee == 8` **不在此列**：那是「免费播放 + 高音质需会员」。
     * - qm：`pay.pay_play == 1`。
     */
    MEMBER_ONLY,

    /** 免费可播（ncm `fee == 0` / `fee == 8`）。 */
    FREE,

    /**
     * 服务端没给判据 / 我们读不懂。
     *
     * **必须与 [FREE] 分开**：把「不知道」当「免费」会让排序把一批可能被墙的歌排到前面，
     * 用户点下去只会跳歌。不知道就与 [FREE] 同组靠后，这是唯一不撒谎的排法。
     */
    UNKNOWN,
    ;

    /** 播放是否被会员墙挡住。 */
    val isGated: Boolean get() = this == MEMBER_ONLY

    companion object {
        /**
         * ncm 的 `fee` → 播放是否被墙（v2.1.4）。**判据只此一处。**
         *
         * 实测语义（2026-09，登录态，`api/cloudsearch/pc`）：
         * - `0` 免费、`8` 免费播放但高音质需会员 ⇒ 都**不是**会员专享；
         * - `1` VIP 专享、`4` 数字专辑 ⇒ 是。
         *
         * 其它值（腾讯/网易以后可能加档）返回 [UNKNOWN] 而不是猜 ——
         * 猜错的方向是把免费曲标成专享（用户白翻一屏），或者反过来（推一首点不开的歌）。
         * 注意 `fee == 8` 是最容易被误判的一个：它带「付费」字样却是**免费可播**的，
         * 实测《稻香(深情版)》就是 `fee=8`。
         */
        fun ofNeteaseFee(fee: Int?): TrackAccess = when (fee) {
            0, 8 -> FREE
            1, 4 -> MEMBER_ONLY
            else -> UNKNOWN
        }

        /** qm 的 `pay.pay_play == 1` → 会员专享（映射时已算成布尔，这里只翻译）。 */
        fun ofQqMemberOnly(memberOnly: Boolean?): TrackAccess = when (memberOnly) {
            true -> MEMBER_ONLY
            false -> FREE
            null -> UNKNOWN
        }
    }
}

/**
 * 排序用的最小封装：把一个**任意类型**的结果与它的会员判定绑在一起。
 *
 * 用泛型而不是直接吃 `SongItem`，是为了让排序规则可以脱离 Retrofit/Gson 注解单测 ——
 * 否则测「排序」要先构造十几个无关字段，测试会写成「构造数据」而不是「验证规则」。
 * 调用方（`SearchViewModel`）用一行 `map { RankedSong(it, accessOf(it)) }` 适配。
 *
 * v2.3.0 · C 新增 [availability]，**带默认值**：v2.1.4 的既有构造点与单测
 * （`SearchRankingTest`）一个字节都不用改，默认值 [TrackAvailability.UNKNOWN] 恰好就是
 * 「没有额外信息 ⇒ 不参与重排」的中性取值。
 */
data class RankedSong<T>(
    val value: T,
    val access: TrackAccess,
    val availability: TrackAvailability = TrackAvailability.UNKNOWN,
)

/** 聚合搜索保持各来源内的相关性顺序，按会员状态排列，再把明确无版权的结果移到组尾。 */
object SearchRanking {
    fun <T> rank(
        netease: List<RankedSong<T>>,
        qq: List<RankedSong<T>>,
        neteaseVip: Boolean,
        qqVip: Boolean,
    ): List<RankedSong<T>> {
        if (!neteaseVip && !qqVip) {
            if (netease.isEmpty()) return qq
            if (qq.isEmpty()) return netease
            return netease + qq
        }

        val result = ArrayList<RankedSong<T>>(netease.size + qq.size)
        if (neteaseVip && qqVip) {
            appendInterleaved(result, netease, qq, gated = true)
            appendInterleaved(result, netease, qq, gated = false)
        } else {
            val preferred = if (neteaseVip) netease else qq
            val other = if (neteaseVip) qq else netease
            for (song in preferred) if (song.access.isGated) result.add(song)
            for (song in preferred) if (!song.access.isGated) result.add(song)
            result.addAll(other)
        }
        return result
    }

    private fun <T> appendInterleaved(
        out: MutableList<RankedSong<T>>,
        left: List<RankedSong<T>>,
        right: List<RankedSong<T>>,
        gated: Boolean,
    ) {
        val leftRows = left.iterator()
        val rightRows = right.iterator()
        while (true) {
            val first = leftRows.nextWithAccess(gated)
            val second = rightRows.nextWithAccess(gated)
            if (first == null && second == null) return
            if (first != null) out.add(first)
            if (second != null) out.add(second)
        }
    }

    private fun <T> Iterator<RankedSong<T>>.nextWithAccess(gated: Boolean): RankedSong<T>? {
        while (hasNext()) {
            val song = next()
            if (song.access.isGated == gated) return song
        }
        return null
    }

    /** 只移动服务端明确标为无版权的行。各部分的原相对顺序保留。 */
    fun <T> demoteNoCopyright(group: List<RankedSong<T>>): List<RankedSong<T>> {
        if (group.none { it.availability == TrackAvailability.NO_COPYRIGHT }) return group
        val result = ArrayList<RankedSong<T>>(group.size)
        for (song in group) if (song.availability != TrackAvailability.NO_COPYRIGHT) result.add(song)
        for (song in group) if (song.availability == TrackAvailability.NO_COPYRIGHT) result.add(song)
        return result
    }

    /** B 站在网易云和 QQ 的结果之后单独排列。 */
    fun <T> order(
        netease: List<RankedSong<T>>,
        qq: List<RankedSong<T>>,
        bili: List<RankedSong<T>>,
        neteaseVip: Boolean,
        qqVip: Boolean,
    ): List<RankedSong<T>> {
        val main = order(netease, qq, neteaseVip, qqVip)
        return if (bili.isEmpty()) main else main + demoteNoCopyright(bili)
    }

    fun <T> order(
        netease: List<RankedSong<T>>,
        qq: List<RankedSong<T>>,
        neteaseVip: Boolean,
        qqVip: Boolean,
    ): List<RankedSong<T>> = demoteNoCopyright(rank(netease, qq, neteaseVip, qqVip))
}
