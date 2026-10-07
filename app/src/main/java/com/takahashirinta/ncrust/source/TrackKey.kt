/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.5：跨源切歌歌词串台的根因是「当前歌的音源」散在三个可变字段里、
 * 且**只有显式 playSong 才更新**。本文件把「一首曲目的身份」收敛成一个值对象。
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem

/**
 * 一首曲目的**统一身份**（v2.1.5）。纯逻辑，无 Android 依赖，JVM 可单测。
 *
 * ## 为什么需要它
 *
 * v2.1.4 及更早，「当前播放的是哪一首歌」由 `PlayerViewModel` 里三个并列的
 * `var` 表示（`currentSongSourceKey` / `currentSongSourceId` / `currentSongMediaId`），
 * 而**只有 `playSong()` 会写它们**。无缝预载的自动接续走的是另一条路
 * （`preloadNextSong` → `PlaybackService.onMediaItemTransition` → `onSongTransitioned`），
 * 那条路只更新了 `currentSongId`，三个音源字段全部留在**上一首**的取值上。
 *
 * 后果不是「显示旧歌词」这么轻：`fetchLyrics(songId)` 当时是按
 * `currentSongSourceKey` 这个**全局字段**决定走哪个平台的取词接口的。QQ 播完自动切到 ncm 时，
 * 它仍然以为在放 QQ，于是拿着**上一首 QQ 曲目的 songmid** 去问 QQ 要歌词 ——
 * 拿回来的是上一首的 QRC，而且因为「请求确实是当前代」而通过了所有闸门，
 * 最终以**新歌的 songId** 落进歌词状态。用户看到的就是「音频已经是 ncm，歌词还是 QQ 那首」。
 *
 * 把身份收成一个不可变值之后，「这一份歌词属于哪首歌」与「播放器现在在哪首歌」的比较
 * 就只有一个答案，不会再出现「id 换了、音源没换」这种半更新状态。
 *
 * ## 相等性 = `(source, id)`，**不含** [sourceId] / [mediaId]
 *
 * 这两条是有意的：
 *
 * 1. **必须含 `source`**：ncm 的 songId 与 qm 的 songid 各自独立编号，
 *    撞号是迟早的事。`TrackKey(NETEASE, 123)` 与 `TrackKey(QQMUSIC, 123)` 必须是两首歌
 *    —— 这正是本版要修的跨源串台。
 * 2. **不能含 `sourceId` / `mediaId`**：它们是**取链用的载荷**（QQ 的 songmid / media_mid），
 *    不是身份。同一个 QQ 曲目在不同路径上「知道」的载荷可以不一样（冷启动从持久化状态恢复时
 *    可能缺 songmid，随后由 songDetail 补齐）。若把它们算进相等性，同一首歌会被判成「切歌了」，
 *    触发一次多余的歌词重取；更糟的是反过来 —— 一旦某条路径漏传，`currentTrackKey` 与
 *    响应里的 key 判等失败，**本该显示的歌词会被整包丢弃**。
 *
 * 所以这里**显式覆写** `equals`/`hashCode`（data class 默认会用上全部构造参数）。
 * 新增字段时请先问一句：「它是身份，还是载荷？」
 *
 * @property source 音源。未知取值一律回落 [MusicSource.DEFAULT]（见 [MusicSource.fromKey]）。
 * @property id 该音源内部的数字 id。[SourceIds.qqId] 造出来的 QQ id 带 bit62 标志位，
 *   与 ncm 的 id 空间结构性隔离。
 * @property sourceId 音源内部标识：qm 是 **songmid**（取链与取词都必须带）。
 *   ncm 恒为 null。
 * @property mediaId qm 的 **media_mid**（部分取链参数用）。ncm 恒为 null。
 */
data class TrackKey(
    val source: MusicSource,
    val id: Long,
    val sourceId: String? = null,
    val mediaId: String? = null,
) {

    /** `netease:123` / `qqmusic:456`。缓存 key、日志、诊断都用它，一眼能看出音源。 */
    val tag: String get() = SourceIds.trackKey(source, id)

    /**
     * 身份相等：**只看 `(source, id)`**。理由见类文档。
     *
     * 刻意不叫 `equals` 的「宽松版」而直接覆写：调用方（闸门、协调器、渲染层）
     * 比较的就是「是不是同一首歌」，让默认的 data class 语义在这里是错的。
     */
    override fun equals(other: Any?): Boolean =
        this === other || (other is TrackKey && source == other.source && id == other.id)

    override fun hashCode(): Int = 31 * source.hashCode() + id.hashCode()

    /** 日志用：把载荷也带上（排障时需要知道 songmid 到底传没传）。 */
    override fun toString(): String =
        tag + (sourceId?.let { " mid=$it" } ?: "") + (mediaId?.let { " media=$it" } ?: "")

    companion object {

        /** 哨兵：还没有任何曲目。`id <= 0` 恒不等于任何真实曲目。 */
        val NONE: TrackKey = TrackKey(MusicSource.DEFAULT, -1L)

        /**
         * 从「音源字符串 + id + 载荷」构造。
         *
         * `sourceKey` 为 null / 空串时**不直接回落 ncm**，而是先看 id 有没有 QQ 的标志位
         * （[SourceIds.sourceOfId]）：只存得下裸 id 的持久化路径（续播状态、离线索引恢复）
         * 恢复出来的曲目因此不会被拿去问错平台。标志位不存在时才回落
         * [MusicSource.DEFAULT]，那正是 v2.1.0 之前持久化数据的正确解释。
         *
         * 显式传了 `sourceKey` 时**以它为准**（调用方比标志位知道得多）。
         */
        fun of(
            sourceKey: String?,
            id: Long,
            sourceId: String? = null,
            mediaId: String? = null,
        ): TrackKey {
            val source = if (sourceKey.isNullOrEmpty()) {
                SourceIds.sourceOfId(id)
            } else {
                MusicSource.fromKey(sourceKey)
            }
            return TrackKey(source, id, sourceId, mediaId)
        }

        /**
         * 从队列里的 [SongItem] 取身份（播放/预载两条路都用它，保证同源）。
         *
         * ⚠️ **新代码请优先用 [ofSong]**：本函数信 [`SongItem.musicSource`]（那个
         * **字符串**字段），字符串缺失时一律回落 ncm。而队列条目确实存在
         * 「id 带 QQ 标志位、source 字符串丢了」的形态（搜索结果进历史记录那条路
         * 只存得下 id，见 `SearchHistoryManager.HistoryItem`），此时本函数会把一首
         * QQ 曲目认成 ncm。v2.1.5 之前它只用在「取词/取链」上，认错会明确失败；
         * v2.5.3 起队列**判重**也要用身份，认错音源就是认错歌，所以改了默认选择。
         *
         * 保留它是因为它的语义（只看显式声明）本身没有错，
         * 只是不该当默认 —— 既有测试与文档仍在引用它。
         */
        fun fromSong(song: SongItem): TrackKey =
            TrackKey(song.musicSource, song.id, song.sourceId, song.mediaId)

        /**
         * **v2.5.3 · P1：`SongItem` → 身份的唯一落点**
         * （队列判重、待播槽位、随机模式的共同入口）。
         *
         * 与 [fromSong] 的唯一区别在音源怎么定：这里走 [of]，
         * 于是 `source` 字符串为空时会先看 id 有没有 QQ 的 bit62 标志位
         * （[SourceIds.sourceOfId]）—— **只存得下裸 id 的持久化路径恢复出来的曲目，
         * 因此不会被当成 ncm 的同号歌曲**。
         *
         * 三条理由，按重要性：
         *  1. **结构性优先于声明性**：bit62 是 id 自带的、不会在序列化里丢；
         *     `source` 字符串会在「只存 id」的历史路径上丢。拿不会丢的那个当默认，
         *     错判面更小。
         *  2. **与既有语义一致**：[TrackKey.of] 从 v2.1.5 起就是这么定的
         *     （`PlaybackStateManager` 的续播恢复走的正是它）。队列判重跟上它之后，
         *     整条链路只剩**一套**身份规则。
         *  3. **不改变正常路径的行为**：`source` 有值时 [of] 以它为准，
         *     所以 ncm 曲目、以及任何显式标了音源的 QQ 曲目，取值与 [fromSong] 完全相同。
         */
        fun ofSong(song: SongItem): TrackKey =
            of(song.source, song.id, song.sourceId, song.mediaId)

        /**
         * 从 media3 的 `MediaItem.mediaId` 反解身份（v2.1.5）。
         *
         * 自动接续时**唯一可信的事实**是「ExoPlayer 真正起播的那一项」，
         * 而那一项的 mediaId 由 [SourceIds.mediaId] 编码了音源。用它反解，
         * 比信任 `preloadNextSong` 时抄下来的旁路变量更稳 —— 旁路是 last-write-wins，
         * 而 mediaId 跟着 item 走（v1.5.2 的既有结论）。
         *
         * 解析失败（老形状 `song:abc`、未知音源前缀、null）返回 null，
         * 由调用方自己决定兜底策略：**绝不猜成 ncm**，猜错就是拿别人的歌去取词。
         */
        fun fromMediaId(mediaId: String?): TrackKey? =
            SourceIds.parseMediaId(mediaId)?.let { (source, id) -> TrackKey(source, id) }
    }
}
