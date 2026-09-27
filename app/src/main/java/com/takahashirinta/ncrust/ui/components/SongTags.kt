/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · C/D：一首歌在列表里要显示哪些角标。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.search.TrackVersionTag
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.ui.i18n.Strings

/** 角标的**语义类别** —— 决定配色，不决定文案。 */
enum class SongTagKind {
    /** 音源归属（网易云 / QQ 音乐）。 */
    SOURCE,

    /** 版权可用性（可播放 / 需会员 / 无版权）。 */
    AVAILABILITY,

    /** 版本性质（原唱 / 翻唱）。 */
    VERSION,
}

/**
 * 列表行上的一个角标。
 *
 * @property text 已经本地化好的文案。
 * @property kind 语义类别。UI 按它挑颜色，**不按文案内容判断**（否则换语言就失效）。
 */
data class SongTag(val text: String, val kind: SongTagKind)

/**
 * 音源归属 + 版权可用性 + 原唱/翻唱的**唯一**装配点（v2.3.0 · C/D）。
 *
 * ## 为什么抽成纯函数
 *
 * 三条规则都是「什么情况下**不**显示」，而「不显示」是最容易在 UI 里被顺手写成
 * 「显示一个默认值」的地方：
 *
 * 1. **音源两源都标**（v2.3.0 的改变）。v2.1.0 · E 只标 QQ，理由是「网易云是原本的唯一音源，
 *    每行都挂标签会变成噪音」。到了聚合搜索把两源混在同一个列表里之后，这个理由不成立了 ——
 *    实测同一关键词下两源会返回**完全同名**的条目（《晴天》在网易云是 `186016`，
 *    在 QQ 是 `00083kc41YcFuR`，见 `probe-source-attribution.md` §2），
 *    不标音源用户无法判断哪一行是哪个源。
 * 2. **版权可用性只在能确证时标**。[TrackAvailability.UNKNOWN] 必须**什么都不显示** ——
 *    这是任务书 5.2「不允许在搜索阶段就假设某源可播」的技术落点。
 * 3. **版本标签只在能确证时标**。[TrackVersionTag.UNKNOWN] 同理（`0` / `3` / QQ 全部留白）。
 *
 * 抽出来之后这三条都能在没有 Compose 的情况下断言（`SongTagsTest`）。
 */
object SongTags {

    /** 音源名。两个音源**都**给文案，不再对网易云返回空串。 */
    fun sourceLabel(source: MusicSource, strings: Strings): String = when (source) {
        MusicSource.QQMUSIC -> strings.sourceQqMusic
        MusicSource.NETEASE -> strings.sourceNetease
        // v3.1.0：B 站也要有名字 —— 搜索聚合之后「这一行来自哪里」必须一眼可见
        // （v2.1.0 · E 的原话：纯网易云的结果看不出「来自哪里」）。
        MusicSource.BILIBILI -> strings.source.sourceBilibili
    }

    /** 版权可用性文案；[TrackAvailability.UNKNOWN] 返回 null（不显示）。 */
    fun availabilityLabel(availability: TrackAvailability, strings: Strings): String? =
        when (availability) {
            TrackAvailability.PLAYABLE -> strings.tagPlayable
            TrackAvailability.MEMBER_ONLY -> strings.tagMemberOnly
            TrackAvailability.NO_COPYRIGHT -> strings.tagNoCopyright
            TrackAvailability.UNKNOWN -> null
        }

    /** 版本性质文案；[TrackVersionTag.UNKNOWN] 返回 null（不显示）。 */
    fun versionLabel(tag: TrackVersionTag, strings: Strings): String? = when (tag) {
        TrackVersionTag.ORIGINAL -> strings.tagOriginal
        TrackVersionTag.COVER -> strings.tagCover
        TrackVersionTag.UNKNOWN -> null
    }

    /**
     * 完整角标列表，顺序 = 显示顺序：**音源 → 可用性 → 版本**。
     *
     * 音源排第一是有意的：它是这一行「属于谁」的第一信息；可用性第二（能不能放）；
     * 版本第三（是不是原唱）。三者都不抢歌名的主视觉。
     */
    fun of(song: SongItem, strings: Strings): List<SongTag> = of(song, strings, null)

    /**
     * v2.4.0 · E：带**探测结果覆盖**的角标装配。
     *
     * ## 为什么需要这个重载，而不是把可用性写回 `SongItem`
     *
     * 聚合页的可用性是**探测出来的临时事实**（VIP 到期、版权下架、地区变化都会改，
     * 见 [com.takahashirinta.ncrust.crosssource.AggregatedSong] 的 KDoc），而
     * `SongItem` 是**落盘结构**（队列 JSON、本地歌单、离线索引都在存它）。
     * 把探测结果写进 `SongItem` 一定会出现「上周探测过能播、今天显示可播放、点下去 404」；
     * 所以列表行必须能在**不改 song** 的前提下显示探测结果 —— 这个参数就是那条通道。
     *
     * @param availabilityOverride 非 null 时**优先于** [TrackAvailability.of] 的推导值。
     *   传 null 与既有的 2 参重载**逐字节等价**（`SongTagsTest` 钉住这一点：
     *   默认行为不能因为多了一条通道就变）。
     */
    fun of(
        song: SongItem,
        strings: Strings,
        availabilityOverride: TrackAvailability?,
    ): List<SongTag> {
        val out = ArrayList<SongTag>(3)
        out += SongTag(sourceLabel(song.musicSource, strings), SongTagKind.SOURCE)
        availabilityLabel(availabilityOverride ?: TrackAvailability.of(song), strings)
            ?.let { out += SongTag(it, SongTagKind.AVAILABILITY) }
        versionLabel(TrackVersionTag.of(song), strings)
            ?.let { out += SongTag(it, SongTagKind.VERSION) }
        return out
    }

    /**
     * 翻唱行的**原曲副标题**（「原唱：<艺人> · <曲名>」），不需要时返回 null。
     *
     * 三个条件缺一不可：
     * 1. 版本标签确证是翻唱（[TrackVersionTag.COVER]）；
     * 2. 服务端确实给了 `originSongSimpleData`（实测翻唱里只有 51% 有）；
     * 3. 原曲名非空。
     *
     * **刻意不猜**：拿不到原曲信息时只显示「翻唱」两个字，不去用曲名/艺人名反推。
     */
    fun coverOriginLine(song: SongItem, strings: Strings): String? {
        if (TrackVersionTag.of(song) != TrackVersionTag.COVER) return null
        val origin = song.originSong ?: return null
        val name = origin.name?.takeIf { it.isNotBlank() } ?: return null
        val artist = origin.artists
            ?.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
            ?.joinToString("/")
            .orEmpty()
        // 原曲艺人缺失时只显示曲名（不编一个「未知歌手」出来）。
        return if (artist.isEmpty()) {
            strings.tagCoverOrigin(strings.unknownArtist, name)
        } else {
            strings.tagCoverOrigin(artist, name)
        }
    }

    /**
     * v2.5.5 · D：**搜索历史**条目该不该显示音源角标、显示什么。
     *
     * ## 为什么只有单曲分区显示
     *
     * 「用户能区分同名历史」这条需求只在**单曲**上成立：同一关键词下两源会返回
     * 完全同名的条目（《晴天》在网易云是 `186016`、在 QQ 是另一套 songmid），
     * 而历史列表里两行的封面、标题、歌手可能**逐字相同** —— 除了音源没有别的线索。
     *
     * 专辑与艺人的历史条目**没有这一层歧义**：它们的 `HistoryItem.source` 从来没有被写过
     * （`addAlbum` / `addArtist` 不传它），`effectiveSource` 只能靠 bit62 反推 ——
     * 而专辑/艺人的 id 都是网易云的普通 id，反推结果恒为「网易云」。
     * 给每一条专辑历史都挂一个恒定的「网易云」标签是纯噪音。
     *
     * ## 判据抽成纯函数
     *
     * 与 [of] 同源的理由：「什么情况下**不**显示」是最容易在 UI 里被顺手写成
     * 「显示一个默认值」的地方。这里把它变成一条 [SongTagsTest] 能钉住的断言 ——
     * 尤其是「老条目（`source == null` 但 id 的 bit62 置位）必须显示 **QQ 音乐**、
     * 不能回落成网易云」这一条，它正是 v2.5.4 修过的那个 bug 的 UI 侧。
     *
     * @param isSongSection 该条目属于单曲分区（`SearchHistoryManager.TYPE_SONG`）。
     * @param source 由 `SearchHistoryMigration.effectiveSource(item)` 算出 ——
     *   **不要**在调用点直接读 `item.source` 字符串（老条目那里是 null）。
     */
    fun historySourceBadge(isSongSection: Boolean, source: MusicSource, strings: Strings): String? =
        if (isSongSection) sourceLabel(source, strings) else null
}
