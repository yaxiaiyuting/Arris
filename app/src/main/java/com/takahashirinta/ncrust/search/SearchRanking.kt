/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
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
 * 网易云在前、QQ 接在后。用户明明有 QQ 绿钻，搜出来的歌却要往下翻半天才看到 QQ 的 ——
 * 会员买在哪一家，哪一家的结果就该先被看见。
 *
 * ## 与「音质是否需要会员」是两件事
 *
 * 这里只回答「**能不能播**」。一首免费曲的无损档位需要会员属于**音质**权益
 * （网易云实测 `fee=8`：播放免费、无损要会员），不算 [MEMBER_ONLY] ——
 * 把那种歌归成会员专享会让排序去推一首本来谁都能放的歌。
 */
enum class TrackAccess {
    /**
     * 播放**需要会员**（会员专享）。
     *
     * - 网易云：`fee == 1`（VIP 专享）或 `fee == 4`（数字专辑，买了才能听）。
     *   `fee == 8` **不在此列**：那是「免费播放 + 高音质需会员」。
     * - QQ 音乐：`pay.pay_play == 1`。
     */
    MEMBER_ONLY,

    /** 免费可播（网易云 `fee == 0` / `fee == 8`）。 */
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
         * 网易云的 `fee` → 播放是否被墙（v2.1.4）。**判据只此一处。**
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

        /** QQ 音乐的 `pay.pay_play == 1` → 会员专享（映射时已算成布尔，这里只翻译）。 */
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

/**
 * 聚合搜索的排序（v2.1.4）。
 *
 * ## 规则（按用户给的语义直接落地）
 *
 * | 用户有会员的音源 | 结果顺序 |
 * |---|---|
 * | 只有 QQ | QQ 的会员专享 → QQ 的其余 → 网易云的会员专享 → 网易云的其余 |
 * | 只有网易云 | 网易云的会员专享 → 网易云的其余 → QQ 的会员专享 → QQ 的其余 |
 * | 两家都有 | **交错（拉链式）**：两家的会员专享轮流在前，然后是两家的其余 |
 * | 都没有 / 都未登录 | **保持既有顺序不变**（网易云在前）—— 这个功能不该改变免费用户看到的顺序 |
 *
 * ## 为什么「两家都有」是交错而不是「都堆在前面」
 *
 * 用户原话是「如果两个都有，就同时给出来」。堆成「网易云会员曲 → QQ 会员曲 → 其余」
 * 会让排在最前的那一家永远占满首屏；交错才能让两家的会员曲**同时**出现在前面，
 * 而且交错是稳定、可预测的（同一次搜索的两次发布不会出现不同顺序）。
 *
 * ## 两个刻意的不变量
 *
 * 1. **只在「同一档」内部重排**（会员专享一组、其余一组），组内顺序原样保留 ——
 *    服务端算出来的相关性排序不会被我们重新洗一遍。
 * 2. **两家都没会员时一个字节都不改**。本功能是给会员的便利，不是给所有人的改动；
 *    免费用户的搜索结果顺序必须与 v2.1.3 完全一致。
 */
object SearchRanking {

    fun <T> rank(
        netease: List<RankedSong<T>>,
        qq: List<RankedSong<T>>,
        neteaseVip: Boolean,
        qqVip: Boolean,
    ): List<RankedSong<T>> {
        if (!neteaseVip && !qqVip) return netease + qq

        // 只有「有会员的那一家」的会员专享会被挑出来；另一家即使有会员专享也不动它
        // （用户没有那家的会员，推它的会员曲等于推一首点不开的歌）。
        val nGated = if (neteaseVip) netease.filter { it.access.isGated } else emptyList()
        val qGated = if (qqVip) qq.filter { it.access.isGated } else emptyList()
        val nRest = netease - nGated.toSet()
        val qRest = qq - qGated.toSet()

        if (neteaseVip && !qqVip) return nGated + nRest + qq
        if (qqVip && !neteaseVip) return qGated + qRest + netease
        return interleave(nGated, qGated) + interleave(nRest, qRest)
    }

    /**
     * 拉链式交错：`[a1, b1, a2, b2, …]`，多出来的一截原样接在尾部。
     *
     * 长度不等时不做特殊处理 —— 谁长谁把尾巴接上，顺序仍然稳定可预测。
     */
    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<T>(a.size + b.size)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out
    }

    /**
     * v2.3.0 · C：把**服务端显式声明无版权**的行降到本组末尾，其余顺序**一个字节不改**。
     *
     * ## 为什么只降 [TrackAvailability.NO_COPYRIGHT] 这一档
     *
     * 任务书 5.3 的原话是「同一首歌两源都有时，优先展示有版权的音源」。探针结论是
     * **这句话的前提不成立**：两个平台各自独立编号，接口里没有任何跨源标识
     * （无 ISRC、无指纹，见 `probe-source-attribution.md` §1），所以程序上**无法**知道
     * 「这两行是同一首歌」。既然配不成对，「优先展示有版权的那个源」就没有可执行的落点。
     *
     * 退而求其次时**不能**粗暴地按 `isPlayable` 全量重排：搜索「周杰伦」时网易云前排
     * 几乎全是 `fee=1`（无版权/VIP），全量重排会把一堆**翻唱与 Live** 顶到最前面，
     * 相关性排序被我们洗掉，用户更难找到他要的那首。那是**负收益**。
     *
     * 所以只做一件收益为正、风险为零的事：**服务端自己说「这条没版权、我给你换个版本」的行**
     * （实测整池 591 条里只有 2 条，0.3%）沉到本组末尾。它有三个好性质：
     *
     * 1. **稳定分区**（[List.partition] 保序）⇒ 同档内服务端相关性顺序原样保留；
     * 2. **只降不升** ⇒ 绝不会把冷门结果顶到精确匹配前面；
     * 3. 只作用于**同一音源组内部** ⇒ 不引入任何跨源行为（铁律：不做跨源合并）。
     *
     * [TrackAvailability.PLAYABLE] 与 [TrackAvailability.MEMBER_ONLY] / [UNKNOWN]
     * **一律不参与重排**：前者是「确证能播」，但把它提前同样会洗掉相关性排序；
     * 后两者本来就不可靠（`st == -1` 那一档 15/30 是能播的）。
     */
    fun <T> demoteNoCopyright(group: List<RankedSong<T>>): List<RankedSong<T>> {
        if (group.none { it.availability == TrackAvailability.NO_COPYRIGHT }) return group
        val (bad, good) = group.partition { it.availability == TrackAvailability.NO_COPYRIGHT }
        return good + bad
    }

    /**
     * v2.3.0 · C：在 [rank] 之上叠加重排。
     *
     * 两件事**分别作用于不同的层**，互不干扰：
     * - [rank] 决定**两个音源之间**的顺序（会员买在哪家，哪家先出）；
     * - [demoteNoCopyright] 只决定**同一音源组内部**无版权行的位置。
     */
    /**
     * v3.1.0 · B：**三源**版本。B 站的结果**追加在最后**，前两源的顺序一个字节不改。
     *
     * ## 为什么 B 站不参与 [rank] 的会员交错
     *
     * [rank] 的全部依据是「用户买在哪一家的会员」——B 站音源在本版**没有登录态**
     * （匿名可用，见 `bili-research/bili-auth.md`），既没有会员信号，也没有
     * `TrackAccess` 的判据（音频区/视频搜索都不返回版权字段）。把它塞进交错里
     * 只会让「有会员的那一家先出」这条规则变得不再可预测。
     *
     * ## 为什么是「追加」而不是「另起一屏」
     *
     * 用户报告的原始诉求是「能搜到 B 站的东西」并且「按音源归属标注、可筛选」。
     * 追加 + 行上的音源角标 + 统计行的第三段（`B站 5 首`）三件事一起，
     * 既让 B 站可见，又让**关掉 B 站开关时整张表与 v3.0.0 逐字相同**
     * （`bili` 为空时直接返回两源结果，连一次 `demoteNoCopyright` 都不多做）。
     */
    fun <T> order(
        netease: List<RankedSong<T>>,
        qq: List<RankedSong<T>>,
        bili: List<RankedSong<T>>,
        neteaseVip: Boolean,
        qqVip: Boolean,
    ): List<RankedSong<T>> {
        val two = order(netease, qq, neteaseVip, qqVip)
        if (bili.isEmpty()) return two
        // B 站内部仍然按无版权沉底（虽然目前恒为 UNKNOWN ⇒ 这是个恒等变换，
        // 写出来是为了「将来 B 站有版权字段时不需要再想一遍」）。
        return two + demoteNoCopyright(bili)
    }

    fun <T> order(
        netease: List<RankedSong<T>>,
        qq: List<RankedSong<T>>,
        neteaseVip: Boolean,
        qqVip: Boolean,
    ): List<RankedSong<T>> {
        val ranked = rank(netease, qq, neteaseVip, qqVip)
        if (ranked.none { it.availability == TrackAvailability.NO_COPYRIGHT }) return ranked
        // 交错之后两个源的行是混着的，所以按 source 分组降级会改变跨源顺序 ——
        // 这里改用「整体稳定分区」：无版权的行沉到**整张表**末尾。
        // 之所以敢这么做：无版权的行本来就该在最后，而它是 0.3% 的少数，
        // 沉底不会改变其余任何两行的相对顺序。
        return demoteNoCopyright(ranked)
    }
}
