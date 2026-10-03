/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.1 · P0：艺人跳转的**唯一**身份判定落点。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.crosssource.MatchConfidence
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.ArtistItem

/**
 * 一次「转到歌手」的**决策结果**（v2.6.1）。
 *
 * 它把「点了之后到底去哪」从一个散在三处的副作用（谁拼路由、谁补 id、谁兜底）
 * 收敛成一个可以单测的值。三种形态互斥且穷尽：
 *
 * | 形态 | 含义 | UI 动作 |
 * |---|---|---|
 * | [Direct] | 拿到了**本源内**合法值域的身份 | 进该源艺人页 |
 * | [Search] | 拿不到可信身份，但知道艺人名 | 进搜索页并预填艺人名 |
 * | [Unavailable] | 连名字都没有 | 什么都不做（不跳错页） |
 *
 * **不存在第四种形态**（「跳到另一个源的某个艺人」）是**有意**的：
 * 本应用没有任何一条「跨源艺人跳转」的产品需求，而 v2.6.1 的 P0 正是
 * 「把一个源的数字 id 拿去另一个源查」造成的。把它排除在类型之外，
 * 比在实现里小心不提更可靠。
 */
sealed interface ArtistNav {

    /**
     * 进**本源**艺人页。
     *
     * @property source 目标音源。**构造它的唯一入口是 [ArtistNavigator]**，
     *   且恒等于歌曲自己的音源（见 [ArtistNavigator.resolve] 的不变量）。
     * @property id 该音源内的**字符串**身份：ncm 是十进制 id，qm 是 `singerMID`。
     *   恒满足 [ArtistNavigator.idDomainMatches]。
     * @property name 艺人名的快照，**只作展示与召回**（艺人页拿它当搜索关键词去拉对端热门曲）。
     *   **绝不参与身份判定** —— 名字可以空、可以错、可以重名，而 [id] 不行。
     *   之所以要把它带在决策里：QQ 的 `singerMID` 没有"按 mid 取名字"的接口，
     *   这个名字只有调用方手上那首歌才有；不带走，艺人页就只能显示「未知艺人」
     *   并且**静默地**召不回任何对端数据。
     */
    data class Direct(val source: MusicSource, val id: String, val name: String = "") : ArtistNav

    /**
     * 进搜索页并预填 [keyword]。
     *
     * 这条路的语义是「**找不到，但用户能理解**」：他会在搜索结果里看到正确的艺人，
     * 而不是被带到一个毫不相干的人那里。见 AGENTS.md 铁律 21。
     */
    data class Search(val keyword: String, val reason: ArtistNavReason) : ArtistNav

    /** 连一个能当关键词的艺人名都没有 —— 不做任何跳转。 */
    data object Unavailable : ArtistNav
}

/** 为什么要跳搜索。写进日志，让线上能区分「旧数据」与「服务端没给」。 */
enum class ArtistNavReason {
    /** 本源内有合法的字符串身份，直接跳。**不是**降级。 */
    SAME_SOURCE_ID,

    /** 本源内既没有数字 id 也没有字符串身份（冷启动恢复的曲目、只存名字的历史数据）。 */
    MISSING_ID,

    /**
     * 只有**数字**身份、没有该源可用的字符串身份 —— 本 P0 的确切形状。
     *
     * QQ 曲目只有 `singer.id`（QQ 域数字）时，唯一能"用"它的方式是把它当 ncm id 查，
     * 而实测那样会跳到**另一个歌手**（周杰伦 `4558` → 马洪波）。宁可跳搜索。
     */
    AMBIGUOUS_NUMERIC_ID,
}

/**
 * 「转到歌手」的唯一身份判定（v2.6.1 P0 修复）。
 *
 * ## 它修的到底是什么
 *
 * 真机实测（PCL110 / WGR-W09，v2.6.0-gpl）同一条菜单项有两种错法，**根因是同一个**：
 *
 * | 当前歌曲的数据来源 | `artists[0]` 的形状 | 点「转到歌手」的结果 |
 * |---|---|---|
 * | 搜索结果里**新鲜**加载的 QQ 曲目 | `id=4558`（QQ 数字）、无 mid | 跳到**马洪波**（ncm 4558） |
 * | **冷启动恢复**的 QQ 曲目 | `id=null`、只有名字 | **毫无反应**（静默失败） |
 *
 * 第一种错在「拿 QQ 的数字 id 当 ncm id 走老路由」；第二种错在
 * `resolveAndNavigate` 的补 id 回落是**ncm**的 `song/detail`，而 QQ 曲目的 id 带
 * bit62 标志位（[SourceIds.QQ_ID_FLAG]），问 ncm 必然查不到 ⇒ `artistId` 仍是 null
 * ⇒ `when` 一个分支都不匹配 ⇒ 什么都不发生。
 *
 * ## 三条不变量（改这个文件之前先读）
 *
 * 1. **绝不产出与歌曲音源不同的 [ArtistNav.Direct]**。跨源跳转不在本应用的
 *    产品范围内；把它排除在类型之外，比在实现里小心不提更可靠。
 * 2. **身份必须过值域闸门**（[idDomainMatches]）。QQ 的艺人字符串身份是 base62 的
 *    `singerMID`，ncm 是十进制 id —— 形状不同，所以「拿数字 QQ id 当 mid 用」
 *    这种错法在**闸门**上就被拦下，不需要调用方自觉。
 * 3. **置信度不足一律跳搜索**（[crossSourceJump]）。即便将来真要做跨源跳转，
 *    也必须拿到 `MatchConfidence.mergeable`（>= MEDIUM，全应用唯一阈值）
 *    **且**目标源内有合法值域的身份；任一不满足 ⇒ [ArtistNav.Search]。
 *
 * ## 为什么名字不能当身份
 *
 * v2.4.0 已经用实测钉过这条：ncm 上存在与 `邓紫棋` 同名的仿冒号（62017015，1 张专辑），
 * 按名字锚定会把真身 `G.E.M.邓紫棋`（7763）判成 `NONE`。所以 [ArtistItem.name] 在这里
 * **只**用来生成搜索关键词，**从不**参与 [ArtistNav.Direct] 的构造。
 */
object ArtistNavigator {

    /**
     * ncm id 的值域上界（不含）。
     *
     * v2.6.2 · P0：判定本身搬到了 [SourceIdDomain]（它现在同时服务艺人页与专辑页，
     * 因为「ncm 吃十进制、QQ 吃 base62 mid」是**音源的性质**，不是艺人的性质）。
     * 这个常量保留成别名，是为了不惊动 v2.6.1 的调用点与文档 —— 取值与语义一字未改。
     */
    const val NETEASE_ID_MAX: Long = SourceIdDomain.NETEASE_ID_MAX

    /**
     * 这个 id 是不是 [source] 域内的合法**字符串**身份。纯函数，JVM 可单测。
     *
     * v2.6.2 · P0：**实现已搬到 [SourceIdDomain]**（值域判据的唯一落点），
     * 本函数是它在「艺人」语境下的别名，**行为逐字未变** ——
     * `ArtistNavigatorTest` 的全部「值域闸门」用例原样覆盖了这次搬迁。
     *
     * 保留这个入口而不是让调用方直接调 [SourceIdDomain] 的理由：艺人跳转的 KDoc
     * 与单测都按这个语义写的，改名会把「v2.6.1 修了什么」这段历史从代码里抹掉。
     */
    fun idDomainMatches(source: MusicSource, id: String?): Boolean =
        SourceIdDomain.matches(source, id)

    /** [MatchConfidence.mergeable] 的**唯一**消费口 —— 不要在调用方写 `>= HIGH` 这类比较。 */
    fun crossSourceJumpAllowed(confidence: MatchConfidence?): Boolean =
        confidence?.mergeable == true

    /**
     * 跨源跳转闸门（不变量 3）。**两道都要过**，否则返回 null（调用方据此跳搜索）。
     *
     * 探针结论：v2.6.1 的这条 P0 **没有**走 v2.4.0 的跨源匹配 ——
     * 它连匹配都没做，直接把 QQ 的数字 id 交给了 ncm 路由。所以本函数在当前代码里
     * 恒返回 null（没有可用的跨源结论）。保留它是因为**下一处**跨源跳转一定会用到它，
     * 而「阈值写在调用方」正是 v2.4.0 铁律 2 点名要避免的形状。
     *
     * @param confidence 缓存的匹配结论。`null` = 没有结论（不是「匹配失败」）。
     * @param targetSource 打算跳到哪个源。
     * @param targetId 打算跳过去的身份串。
     */
    fun crossSourceJump(
        confidence: MatchConfidence?,
        targetSource: MusicSource,
        targetId: String?,
    ): ArtistNav.Direct? {
        if (!crossSourceJumpAllowed(confidence)) return null
        if (!idDomainMatches(targetSource, targetId)) return null
        // 跨源跳转**不带名字**：那个名字来自另一个源，用它当搜索关键词会把
        // 目标源的召回带偏（而名字本来就不该参与身份判定）。
        return ArtistNav.Direct(targetSource, targetId!!.trim(), "")
    }

    /**
     * **「转到歌手」的唯一决策入口**（纯函数）。
     *
     * 不变量 1 由构造保证：两条 `Direct` 出口都写死成 `source = song.musicSource`，
     * 函数体内没有任何一处读「另一个源」的 id。
     */
    fun resolve(song: SongItem): ArtistNav {
        val source = song.musicSource
        val artist = song.artists?.firstOrNull()
        val keyword = artist?.name?.trim()?.takeIf { it.isNotEmpty() }

        // 本源内的字符串身份优先 → 直接跳。ncm 看 id，QQ 看 mid。
        val sameSourceId: String? = when (source) {
            MusicSource.NETEASE -> artist?.id?.toString()
            MusicSource.QQMUSIC -> artist?.mid
            // v3.1.0：B 站的「作者」是 UP 主，音频区接口只给名字不给 mid，
            // 而本应用又没有 UP 主主页。返回 null ⇒ 走下面的 keyword 搜索降级
            // （与本源内没有可用身份的既有行为一致），绝不猜一个数字 mid 去别的源查。
            MusicSource.BILIBILI -> null
        }
        if (idDomainMatches(source, sameSourceId)) {
            return ArtistNav.Direct(source, sameSourceId!!.trim(), keyword.orEmpty())
        }

        // 走到这里说明**本源内**没有可用身份。两条降级路都不许猜：
        //
        //  · QQ 曲目只剩 `singer.id`（QQ 域数字）时，唯一能"用"它的方式就是拿它去
        //    ncm 查 —— 那正是本 P0（4558 → 马洪波）。它属于「只有跨源猜才能用」
        //    的身份，因此必须过 [crossSourceJump] 闸门；而探针结论是这条路上没有
        //    任何可用的匹配结论（`MatchCacheStore` 的键是 `(source, id)`，
        //    数字 singerID 不是任何一个源的有效键）⇒ 一律跳搜索。
        //  · 连数字 id 都没有（冷启动恢复）时同理，且连跨源猜都无从下手。
        val numericOnly = artist?.id?.takeIf { it > 0L }?.toString()
        val viaCrossSource = if (source == MusicSource.QQMUSIC && numericOnly != null) {
            // confidence=null：没有任何针对这个数字 id 的匹配结论可用（见 KDoc）。
            crossSourceJump(null, MusicSource.QQMUSIC, null)
        } else {
            null
        }
        viaCrossSource?.let { return it }

        if (keyword == null) return ArtistNav.Unavailable
        val reason = if (artist?.id != null && artist.id > 0L) {
            ArtistNavReason.AMBIGUOUS_NUMERIC_ID
        } else {
            ArtistNavReason.MISSING_ID
        }
        return ArtistNav.Search(keyword, reason)
    }
}
