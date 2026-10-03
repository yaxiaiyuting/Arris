/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：跨源身份匹配的**值对象与置信度**。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 跨源匹配的**置信度**（v2.4.0 · E，铁律 17 的技术落点）。
 *
 * ## 为什么必须有它
 *
 * 两个音源之间**没有任何权威的共同键**（`probe-artist-mapping.md` P1：无 ISRC、无指纹、
 * 无共享 id 空间）。所以「这两条是同一个东西」永远是一个**推断**，
 * 而推断必须有强弱之分 —— 否则「同名」与「同专辑 + 同曲目 + 同时长」会被一视同仁。
 *
 * ## `mergeable` 是唯一的合并闸门
 *
 * [mergeable] 为 false 时**任何调用方都不得把两侧合并展示、也不得据此选默认音源**。
 * 这条以属性而不是「调用方自己比较」的形式给出，是为了让「低于阈值不合并」
 * 变成一个可以被单测钉住、且只有一处定义的事实。
 *
 * 阈值取 `MEDIUM`（而不是 `HIGH`）是探针给的：单曲样本 230 首里
 * `MEDIUM` 占 46 条（20%），它们的判据是「曲名 + 艺人一致，时长口径不可比」——
 * 这是**真实存在的一档**，把它们全部拒掉会让双源聚合在一半的专辑上失效。
 * 而 `LOW`（仅曲名一致）实测 0 条，`NONE`（只有一侧有）26 条 —— 后者本来就不该合并。
 */
enum class MatchConfidence {

    /** 所有判据一致（艺人：唯一同名 + 专辑高度重合；专辑：名 + 艺人 + 曲目数 + 曲目名；单曲：名 + 艺人 + 时长 + 版本标记）。 */
    EXACT,

    /** 结构判据一致，但有一条辅助判据不同（例如专辑名需要归一化、单曲版本标记不同）。 */
    HIGH,

    /** 主判据一致（名称 + 艺人），辅助判据缺失或不可比。 */
    MEDIUM,

    /** 只有名称一致。**绝不合并**（铁律 17）。 */
    LOW,

    /** 无法匹配。**绝不合并**，且**不写缓存**。 */
    NONE,
    ;

    /**
     * 是否允许合并展示 / 据此选默认音源。
     *
     * `EXACT` / `HIGH` / `MEDIUM` 为 true，`LOW` / `NONE` 为 false。
     * **这是全应用唯一的合并阈值定义**，不要在调用方写 `confidence >= HIGH`。
     */
    val mergeable: Boolean get() = this == EXACT || this == HIGH || this == MEDIUM

    /** 用户在「为什么这两条被认为是一样的」里看到的等级文案（本地化在 UI 层，这里只给 key）。 */
    val labelKey: String get() = name.lowercase()
}

/**
 * 艺人在某个音源里的身份（v2.4.0 · E）。
 *
 * @property source 音源。
 * @property id 该音源内的艺人标识：**ncm 是十进制 id 的字符串形式，qm 是 `singerMID`**。
 *   两者形状不同（一个是 `"6452"`，一个是 `"0025NhlN2yWrP4"`），
 *   所以这里用 `String` 而不是 `Long` —— 用 Long 会逼着调用方给 QQ 的 mid 编一个假数字 id，
 *   那正是「给错专辑 / 错单曲」的温床。
 * @property name 该源显示的名字（**保留原始写法**，不归一化；归一化只发生在匹配算法内部）。
 */
data class ArtistKey(
    val source: MusicSource,
    val id: String,
    val name: String,
) {
    /** `netease:6452` / `qqmusic:0025NhlN2yWrP4`。缓存 key、日志、诊断都用它。 */
    val tag: String get() = source.key + ":" + id

    /** 身份相等只看 `(source, id)` —— 名字可以改，id 不会。 */
    override fun equals(other: Any?): Boolean =
        this === other || (other is ArtistKey && source == other.source && id == other.id)

    override fun hashCode(): Int = 31 * source.hashCode() + id.hashCode()

    override fun toString(): String = "$tag($name)"
}

/**
 * 专辑在某个音源里的身份（v2.4.0 · E）。
 *
 * @property artistKey 该专辑的主艺人。跨源专辑匹配要求两侧艺人先配上（或至少同名），
 *   所以把它带在 key 上，避免调用方各自去猜。
 */
data class AlbumKey(
    val source: MusicSource,
    val id: String,
    val name: String,
    val artistKey: ArtistKey? = null,
) {
    val tag: String get() = source.key + ":" + id

    override fun equals(other: Any?): Boolean =
        this === other || (other is AlbumKey && source == other.source && id == other.id)

    override fun hashCode(): Int = 31 * source.hashCode() + id.hashCode()

    override fun toString(): String = "$tag($name)"
}

/**
 * 一次匹配的结果（v2.4.0 · E）。
 *
 * @property confidence 置信度。
 * @property reason **可追溯性**（铁律 17 的第三条要求）：一句人能看懂的依据，
 *   例如「同名 + 专辑重合 33 张」。UI 的「为什么这两条被认为是一样的」直接显示它。
 *   它是**中文诊断文本**，不参与本地化 —— 它的读者是排障的人，不是终端用户；
 *   终端用户看到的是 [MatchConfidence.labelKey] 对应的本地化等级文案。
 */
data class MatchVerdict(
    val confidence: MatchConfidence,
    val reason: String,
) {
    val mergeable: Boolean get() = confidence.mergeable

    companion object {
        val NONE = MatchVerdict(MatchConfidence.NONE, "未找到可校验的候选")
    }
}

/**
 * 合并后的艺人（v2.4.0 · E）。
 *
 * @property primary 主身份 —— 用户**点进来的那一个**。它决定页面标题、头像与默认音源。
 * @property aliases 另一源上的同一艺人。`confidence < MEDIUM` 时**为空**。
 * @property albumOverlap 两侧专辑列表归一化后的重合张数（`<MEDIUM` 时为 0）。
 */
data class MergedArtist(
    val primary: ArtistKey,
    val aliases: List<ArtistKey>,
    val name: String,
    val confidence: MatchConfidence,
    val reason: String = "",
    val albumOverlap: Int = 0,
) {
    /** 参与合并的全部身份（主 + 别名）。切换「只看某源」时按它过滤。 */
    val allKeys: List<ArtistKey> get() = listOf(primary) + aliases

    fun keyOf(source: MusicSource): ArtistKey? = allKeys.firstOrNull { it.source == source }

    /** 是否真的是双源（只有配上别名才算）。 */
    val isDual: Boolean get() = aliases.isNotEmpty()
}

/**
 * 合并后的专辑（v2.4.0 · E）。
 *
 * @property trackOverlap 两侧曲目名集合归一化后的重合条数（可追溯 + 可用于 UI 说明）。
 */
data class MergedAlbum(
    val primary: AlbumKey,
    val aliases: List<AlbumKey>,
    val name: String,
    val confidence: MatchConfidence,
    val reason: String = "",
    val trackOverlap: Int = 0,
) {
    val allKeys: List<AlbumKey> get() = listOf(primary) + aliases

    fun keyOf(source: MusicSource): AlbumKey? = allKeys.firstOrNull { it.source == source }

    val isDual: Boolean get() = aliases.isNotEmpty()
}

/**
 * 合并后的单曲（v2.4.0 · E）。**复用 [TrackKey]**，不另起一套身份（铁律 3）。
 *
 * @property primary 用户看到 / 点播的那一个版本。
 * @property aliases 另一源上的同一首。`confidence < MEDIUM` 时为空。
 */
data class MergedTrack(
    val primary: TrackKey,
    val aliases: List<TrackKey>,
    val confidence: MatchConfidence,
    val reason: String = "",
) {
    val allKeys: List<TrackKey> get() = listOf(primary) + aliases

    fun keyOf(source: MusicSource): TrackKey? = allKeys.firstOrNull { it.source == source }

    val isDual: Boolean get() = aliases.isNotEmpty()
}

/**
 * 列表里的一行：一首歌 + 它的**可用性** + 它是怎么来的（v2.4.0 · D/E）。
 *
 * ## 为什么不把可用性写回 `SongItem`
 *
 * `SongItem` 是**落盘结构**（队列 JSON、本地歌单、离线索引都在存它）。
 * 可用性是**与账号和时刻绑定的临时事实**（VIP 到期、版权下架、地区变化都会改），
 * 把它持久化下来一定会出现「上周探测过能播、今天显示可播放、点下去 404」。
 * 所以它只活在页面状态里，随页面一起消失。
 *
 * @property availability 版权可用性。默认 [TrackAvailability.UNKNOWN]（什么都不显示）。
 * @property confidence 与另一源合并的置信度；`NONE` 表示这条**没有**跨源对应。
 * @property mergedKeys 与它合并的**另一源**的 [TrackKey]（可追溯；用户切源时按它跳转）。
 * @property availabilityReason 可用性是怎么来的（`pl>0` / `purl` / `st==-200` / 未探测…），诊断用。
 */
data class AggregatedSong(
    val song: com.takahashirinta.ncrust.network.SongItem,
    val availability: TrackAvailability = TrackAvailability.UNKNOWN,
    val confidence: MatchConfidence = MatchConfidence.NONE,
    val matchReason: String = "",
    val mergedKeys: List<TrackKey> = emptyList(),
    val availabilityReason: String = "",
) {
    val key: TrackKey get() = TrackKey.fromSong(song)

    /** 是否在**另一源**还有版本。 */
    val hasOtherSource: Boolean get() = mergedKeys.isNotEmpty()
}

/**
 * 用户可选的展示口径（v2.4.0 · 特性 A/B「用户可手动切源」）。
 *
 * 顺序即 UI 顺序。`BOTH` 是默认值 —— 本版的整个目的就是**不再默认单一音源**。
 */
enum class SourceFilter(val source: MusicSource?) {
    BOTH(null),
    NETEASE_ONLY(MusicSource.NETEASE),
    QQMUSIC_ONLY(MusicSource.QQMUSIC),
    ;

    fun accepts(source: MusicSource): Boolean = this.source == null || this.source == source

    /** 循环切到下一个口径（UI 上的一个按钮点一下就换一档）。 */
    fun next(): SourceFilter = entries[(ordinal + 1) % entries.size]
}
