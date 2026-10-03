/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.2 · P0：专辑跳转的**唯一**身份判定落点。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.crosssource.MatchConfidence
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem

/**
 * 一次「转到专辑」的**决策结果**（v2.6.2）。
 *
 * 它是 v2.6.1 的 [ArtistNav] 的孪生兄弟，形状**故意做成一样**：
 * 把「点了之后到底去哪」从一个散在三处的副作用（谁拼路由、谁补 id、谁兜底）
 * 收敛成一个可以单测的值。三种形态互斥且穷尽：
 *
 * | 形态 | 含义 | UI 动作 |
 * |---|---|---|
 * | [Direct] | 拿到了**本源内**合法值域的身份 | 进该源专辑页 |
 * | [Search] | 拿不到可信身份，但知道一个能当关键词的串 | 进搜索页并预填 |
 * | [Unavailable] | 连关键词都构造不出来 | 什么都不做（不跳错页） |
 *
 * **不存在第四种形态**（「跳到另一个源的某张专辑」）是**有意**的：
 * 本应用没有任何一条「跨源专辑跳转」的产品需求，而 v2.6.2 的 P0 正是
 * 「把一个源的数字 id 拿去另一个源查」造成的（陈奕迅 `22276` → 陈小云）。
 * 把它排除在类型之外，比在实现里小心不提更可靠。
 */
sealed interface AlbumNav {

    /**
     * 进**本源**专辑页。
     *
     * @property source 目标音源。**构造它的唯一入口是 [AlbumNavigator]**，
     *   且恒等于歌曲自己的音源（见 [AlbumNavigator.resolve] 的不变量）。
     * @property id 该音源内的**字符串**身份：ncm 是十进制专辑 id，qm 是
     *   `albumMID`。恒满足 [AlbumNavigator.idDomainMatches]。
     */
    data class Direct(val source: MusicSource, val id: String) : AlbumNav

    /**
     * 进搜索页并预填 [keyword]。
     *
     * 这条路的语义是「**找不到，但用户能理解**」：他会在搜索结果里看到那张正确的专辑，
     * 而不是被带到一张毫不相干的专辑那里（AGENTS.md 铁律 14/15）。
     */
    data class Search(val keyword: String, val reason: AlbumNavReason) : AlbumNav

    /** 连一个能当关键词的串都构造不出来 —— 不做任何跳转。 */
    data object Unavailable : AlbumNav
}

/**
 * 为什么要跳搜索。写进日志，让线上能区分「老数据」与「服务端没给」。
 *
 * 与 [ArtistNavReason] 同构，但多带了一条：[MISSING_ALBUM_META]。
 * 原因是实测里 QQ 的**老队列条目连专辑名都没有**（`al` 的 key 集合只有 `picUrl`），
 * 于是"跳搜索"这个动作本身也需要一条回落链（专辑名 → 曲名 + 艺人名）。
 */
enum class AlbumNavReason {
    /** 本源内有合法的字符串身份，直接跳。**不是**降级。 */
    SAME_SOURCE_ID,

    /**
     * 本源内**既没有**字符串身份也没有数字 id —— 冷启动恢复、老队列、搜索历史重开。
     *
     * 实测形状（S6 真机 `ncrust_playback_state`）：
     * `{"al":{"picUrl":"…T002R500x500M000002Neh8l0uciQZ_3.jpg"}}` —— 连 `id` 都没有。
     */
    MISSING_ID,

    /**
     * 只有**数字** id、没有该源可用的字符串身份 —— 本 P0 的确切形状。
     *
     * QQ 曲目只有 `album.id`（QQ 域数字）时，唯一能"用"它的方式是把它当 ncm
     * 专辑 id 查，而实测那样会跳到**另一张真专辑**（陈奕迅 `22276` → 陈小云
     * 《百万金曲 陈小云2 苦恋梦 免失志》）。宁可跳搜索。
     */
    AMBIGUOUS_NUMERIC_ID,

    /**
     * 有 id 的可信度问题之外，**关键词也只能靠回落**：专辑名缺失/空白。
     *
     * 单列一条是为了让线上日志能区分「服务端没给专辑名」与「这是老数据」——
     * 两者的处置相同（跳搜索），但排查方向完全不同。
     */
    MISSING_ALBUM_META,
}

/**
 * 「转到专辑」的唯一身份判定（v2.6.2 P0 修复）。
 *
 * ## 它修的到底是什么
 *
 * 与 v2.6.1 的艺人 P0 **同构**，四个环节一一对应：
 *
 * | 环节 | 艺人（v2.6.1 已修） | 专辑（本版） |
 * |---|---|---|
 * | ① 映射层丢掉源内字符串身份 | `singer[].mid` 被丢 | `album.mid` 只用于拼封面 |
 * | ② 跳转层不看 `song.musicSource` | 只读 `artists[0].id` | 只读 `album.id` |
 * | ③ 老路由 source 写死 | `artist/{id}` → `NETEASE` | `album/{id}` → `NETEASE` |
 * | ④ 身份不可信时静默失败 | 补 id 回落打**ncm**接口 ⇒ 查空 | **连回落都没有** ⇒ 直接 no-op |
 *
 * 真机实测（PCL110 / S6，v2.6.0 与 v2.6.1）同一条菜单项的两种错法：
 *
 * | 当前歌曲的数据来源 | `song.album` 的形状 | 点「转到专辑」的结果 |
 * |---|---|---|
 * | 搜索结果里**新鲜**加载的 QQ 曲目 | `{id:22276, mid:"004Z85XP1c25b7", name:"What's Going On...?"}` | 跳到**《百万金曲 陈小云2 苦恋梦 免失志》**（ncm 22276） |
 * | **老队列 / 冷启动恢复**的 QQ 曲目 | `{picUrl:"…T002R500x500M000002Neh8l0uciQZ_3.jpg"}` | **毫无反应**（静默失败） |
 *
 * ## 三条不变量（改这个文件之前先读）
 *
 * 1. **绝不产出与歌曲音源不同的 [AlbumNav.Direct]**。跨源跳转不在本应用的
 *    产品范围内；把它排除在类型之外，比在实现里小心不提更可靠。
 * 2. **身份必须过值域闸门**（[idDomainMatches]，实现是 [SourceIdDomain.matches]）。
 *    QQ 的专辑字符串身份是 base62 的 `albumMID`，ncm 是十进制 id —— 形状不同，
 *    所以「拿数字 QQ albumID 当专辑身份用」这种错法在**闸门**上就被拦下。
 *    闸门还顺带挡住了 QQ 的 `pmid`（封面照片 id，含 `_`）—— 它**不是**身份。
 * 3. **置信度不足一律跳搜索**（[crossSourceJump]）。即便将来真要做跨源跳转，
 *    也必须拿到 `MatchConfidence.mergeable`（>= MEDIUM，全应用唯一阈值）
 *    **且**目标源内有合法值域的身份；任一不满足 ⇒ [AlbumNav.Search]。
 *
 * ## 为什么名字不能当身份
 *
 * 与艺人那条同一依据：v2.4.0 已实测「同名仿冒」真实存在（ncm 上的同名艺人
 * 把真身判成 `NONE`）。专辑一侧更明显 —— 本次真机复现里 QQ《富士山下》与
 * ncm《富士山下》的专辑名在**归一化前**就不相等（`What's Going On...?`
 * vs `What's Going On…?`，一个是三个点、一个是省略号）。
 * 所以 [AlbumItem.name] 在这里**只**用来生成搜索关键词，**从不**参与
 * [AlbumNav.Direct] 的构造。
 */
object AlbumNavigator {

    /** ncm id 值域上界的别名（真正的定义在 [SourceIdDomain]，见那里的 KDoc）。 */
    const val NETEASE_ID_MAX: Long = SourceIdDomain.NETEASE_ID_MAX

    /**
     * 这个 id 是不是 [source] 域内的合法**字符串**身份。
     *
     * v2.6.2：实现委托 [SourceIdDomain.matches] —— 值域判据只有**一份**，
     * 艺人页与专辑页共用。两个源的性质（ncm 十进制 / QQ base62 mid）不因
     * 被跳的是艺人还是专辑而改变，抄第二份只会得到一处会漂移的规则。
     */
    fun idDomainMatches(source: MusicSource, id: String?): Boolean =
        SourceIdDomain.matches(source, id)

    /**
     * 这张专辑的**身份是否可信**（AGENTS.md 铁律 14/15 的落点）。
     *
     * 语义是「本源内有没有一个过了值域闸门的字符串身份」：
     *
     * - ncm：`album.id` 转成十进制串后过闸门；
     * - QQ：`album.mid`（albumMID）过闸门 ——
     *   **`null` 就是「身份不可信」**：它同时覆盖「本字段出现之前的旧数据」
     *   与「服务端这次没给」，而两者的正确处置是同一个（跳搜索）。
     *
     * ⚠️ 判「老数据」只看**字段缺失**（null），**不能**用「字段是空串」——
     * 这是 v1.9.2 音译字段踩过的坑（空串往往是"服务端确实没有"的权威结论，
     * 误判会让每次都重取）。所以映射层把空串统一回落成 null（见
     * `QqSongMapper.fromSongObject`），本函数也只认 null。
     */
    fun identityTrusted(source: MusicSource, album: AlbumItem?): Boolean =
        idDomainMatches(source, albumIdentityOf(source, album))

    /**
     * 取 [source] 域内的**字符串身份**；没有就返回 null。
     *
     * 这是「哪个字段是身份」的**唯一**落点：ncm 看 `album.id`，
     * QQ 看 `album.mid`。写成函数而不是让到处 `when (source)`，是为了让
     * 将来再加一个源时**编译期**就能看到这里要改（`when` 是穷尽的）。
     *
     * ⚠️ QQ 一侧**只认 `mid`**：`album.id` 是 QQ 域数字（拿它去 ncm 查就是本 P0），
     * `picUrl` 里那串 pmid 是封面照片 id（服务端碰巧能容忍，但不是契约）。
     */
    fun albumIdentityOf(source: MusicSource, album: AlbumItem?): String? {
        if (album == null) return null
        return when (source) {
            MusicSource.NETEASE -> album.id?.takeIf { it > 0L }?.toString()
            MusicSource.QQMUSIC -> album.mid?.trim()?.takeIf { it.isNotEmpty() }
            // v3.1.0：B 站音频区没有专辑页面 —— 「转到专辑」对它没有目标。
            // 返回 null 让调用方走「不可跳转」的既有分支（不猜、不搜同名）。
            MusicSource.BILIBILI -> null
        }
    }

    /** [MatchConfidence.mergeable] 的**唯一**消费口 —— 不要在调用方写 `>= HIGH` 这类比较。 */
    fun crossSourceJumpAllowed(confidence: MatchConfidence?): Boolean =
        confidence?.mergeable == true

    /**
     * 跨源跳转闸门（不变量 3）。**两道都要过**，否则返回 null（调用方据此跳搜索）。
     *
     * 与 [ArtistNavigator.crossSourceJump] 同形。探针结论：v2.6.2 的这条 P0
     * **没有**走 v2.4.0 的跨源匹配 —— 它连匹配都没做，直接把 QQ 的数字 albumID
     * 交给了 ncm 路由。所以本函数在当前代码里恒返回 null（没有可用的跨源结论）。
     * 保留它是因为**下一处**跨源跳转一定会用到它，而「阈值写在调用方」正是
     * v2.4.0 铁律 2 点名要避免的形状。
     *
     * @param confidence 缓存的匹配结论。`null` = 没有结论（不是「匹配失败」）。
     * @param targetSource 打算跳到哪个源。
     * @param targetId 打算跳过去的身份串。
     */
    fun crossSourceJump(
        confidence: MatchConfidence?,
        targetSource: MusicSource,
        targetId: String?,
    ): AlbumNav.Direct? {
        if (!crossSourceJumpAllowed(confidence)) return null
        if (!idDomainMatches(targetSource, targetId)) return null
        return AlbumNav.Direct(targetSource, targetId!!.trim())
    }

    /**
     * **「转到专辑」的唯一决策入口**（纯函数）。
     *
     * 不变量 1 由构造保证：`Direct` 的两个出口都写死成 `source = song.musicSource`，
     * 函数体内没有任何一处读「另一个源」的 id。
     */
    fun resolve(song: SongItem): AlbumNav {
        val source = song.musicSource
        val album = song.album

        // 本源内的字符串身份优先 → 直接跳。ncm 看 id，QQ 看 albumMID。
        val sameSourceId = albumIdentityOf(source, album)
        if (idDomainMatches(source, sameSourceId)) {
            return AlbumNav.Direct(source, sameSourceId!!.trim())
        }

        // 走到这里说明**本源内**没有可用身份。两条降级路都不许猜：
        //
        //  · QQ 曲目只剩 `album.id`（QQ 域数字）时，唯一能"用"它的方式就是拿它去
        //    ncm 查 —— 那正是本 P0（22276 → 陈小云）。它属于「只有跨源猜才能用」
        //    的身份，因此必须过 [crossSourceJump] 闸门；而探针结论是这条路上没有
        //    任何可用的匹配结论（`MatchCacheStore` 的键是 `(source, id)`，
        //    数字 albumID 不是任何一个源的有效键）⇒ 一律跳搜索。
        //  · 连数字 id 都没有（老队列 / 冷启动恢复）时同理，且连跨源猜都无从下手。
        val numericOnly = album?.id?.takeIf { it > 0L }?.toString()
        val viaCrossSource = if (source == MusicSource.QQMUSIC && numericOnly != null) {
            // confidence=null：没有任何针对这个数字 id 的匹配结论可用（见 KDoc）。
            crossSourceJump(null, MusicSource.QQMUSIC, null)
        } else {
            null
        }
        viaCrossSource?.let { return it }

        // 关键词回落链：专辑名 → 「曲名 + 艺人名」。
        //
        // 第二条不是锦上添花，是**实测必需**：S6 真机上的 QQ 老队列条目
        // `al` 的 key 集合只有 `{picUrl}`（连 name 都没有），而
        // `MainActivity` 冷启动恢复构造的更是 `AlbumItem(id=null, name="", …)`。
        // 没有这条回落，这些曲目会退化成 [AlbumNav.Unavailable] —— 也就是
        // 本 P0 的第二种症状「点了没反应」，正是本版要消灭的东西。
        val albumName = album?.name?.trim()?.takeIf { it.isNotEmpty() }
        val songKeyword = songKeywordOf(song)
        val keyword = albumName ?: songKeyword

        if (keyword == null) return AlbumNav.Unavailable
        val reason = when {
            albumName == null -> AlbumNavReason.MISSING_ALBUM_META
            numericOnly != null -> AlbumNavReason.AMBIGUOUS_NUMERIC_ID
            else -> AlbumNavReason.MISSING_ID
        }
        return AlbumNav.Search(keyword, reason)
    }

    /**
     * 「曲名 + 主艺人名」——专辑名缺失时的搜索关键词。
     *
     * 刻意**只**用它当关键词，不拿它推断任何身份：搜索页把它填进输入框，
     * 用户看到的是这首歌的正确结果，自己点进专辑。跨源匹配一条都没走。
     */
    private fun songKeywordOf(song: SongItem): String? {
        val name = song.name.trim().takeIf { it.isNotEmpty() } ?: return null
        val artist = song.artists?.firstOrNull()?.name?.trim()?.takeIf { it.isNotEmpty() }
        return if (artist == null) name else "$name $artist"
    }
}
