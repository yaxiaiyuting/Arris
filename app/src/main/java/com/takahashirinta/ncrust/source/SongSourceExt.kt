/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：把「这首歌属于哪个音源」的判定收在一处。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem

/**
 * 该曲所属音源。null / 未知值 ⇒ [MusicSource.NETEASE]（v2.1.0 之前的数据）。
 *
 * 命名成 `musicSource` 而不是 `source` 是有意的：[SongItem] 上已经有一个同名的
 * `String?` 字段，扩展属性重名会让调用方分不清拿到的是原始字符串还是枚举。
 */
val SongItem.musicSource: MusicSource
    get() = MusicSource.fromKey(source)

/**
 * 该曲**是否具备取链的必要信息**。
 *
 * 只有 qm 可能为 false（它必须带 songmid）；ncm 恒为 true。
 * 调用方拿到 false 时应当把这首歌当「不可播放」（跳歌 / 灰显），
 * **不要**退回 ncm 取链 —— id 相同不代表是同一首歌。
 */
val SongItem.isResolvable: Boolean
    get() = !musicSource.requiresSourceId || !sourceId.isNullOrEmpty()

/** `netease:123` / `qqmusic:456`。用于缓存 key、日志与诊断。 */
val SongItem.trackKey: String
    get() = SourceIds.trackKey(musicSource, id)

/**
 * media3 `MediaItem` 用的 id：ncm 仍是 `song:123`（与 v2.0.2 逐字节相同），
 * qm 是 `song:qqmusic:456`。[id] 非正时返回 null。
 */
val SongItem.mediaIdOrNull: String?
    get() = SourceIds.mediaId(musicSource, id)

/**
 * 队列内判重用的身份串。
 *
 * **不能用 [SongItem.id] 单独判重**：qm 的数字 songid 与 ncm 的 songId 各自独立编号，
 * 撞号是迟早的事，撞上就是「点了 QQ 的歌，播放器跳到了 ncm 那首同名 id 的歌」。
 *
 * ## v2.5.3 · P1：从「另写一份」改成「就是 [TrackKey] 的那一份」
 *
 * 原先它是 `SourceIds.trackKey(musicSource, id)` —— 与 [TrackKey] **各算各的**，
 * 区别在音源怎么定：这里信 `source` 字符串（缺失回落 ncm），
 * `TrackKey.of` 会再看 id 的 bit62 标志位。两份规则意味着
 * 「同一个队列里，判重按一套、待播槽位按另一套」，正是 AGENTS.md 点名的形状。
 *
 * 现在改为 `TrackKey.ofSong(this).tag`：
 *  · **判重的键与待播槽位/歌词闸门用的身份，由构造保证是同一个值**；
 *  · `dedupeKey` 这个属性也**第一次真的接到了生产调用点**上
 *    （v2.1.0 定义它、v2.1.0 加了守卫测试，但队列去重一直用的是裸 `song.id`）。
 *
 * 取值变化只发生在「id 带 QQ 标志位、而 `source` 字符串为空」这一种形态上：
 * 旧值 `netease:<合成id>`，新值 `qqmusic:<合成id>`。这是**修正**（那确实是一首 QQ 曲目），
 * 且因为 id 里的标志位使两者数值上仍不可能与任何真实 ncm 曲目相撞，
 * 所以没有任何既有队列会因此判重失败。
 */
val SongItem.dedupeKey: String
    get() = trackKeyOf().tag

/**
 * v2.5.3 · P1：该曲在**队列里的身份**（[TrackKey.ofSong] 的扩展写法）。
 *
 * 队列的判重、重定位、待播槽位、随机模式全部走它 —— 见 `player/QueueKeys.kt`。
 * **不要**在队列代码里直接写 `song.id`：那正是本版要收敛掉的东西。
 */
fun SongItem.trackKeyOf(): TrackKey = TrackKey.ofSong(this)

/**
 * 从 `(source, id, sourceId)` 三件套构造一个 [SongItem]（只有 id 与来源，没有元数据）。
 *
 * 用在「只知道 id 就要把歌塞进队列」的场合（例如从 mediaId 反解、Android Auto browse tree）。
 * 元数据缺失时展示层会退回「加载中」而不是崩，这与既有行为一致。
 */
fun songRefOf(
    source: MusicSource,
    id: Long,
    sourceId: String? = null,
    mediaId: String? = null,
): SongItem = SongItem(
    id = id,
    name = "",
    artists = null,
    album = null,
    duration = null,
    // ncm 一侧刻意写 null 而不是 "netease"：这样它与 v2.1.0 之前持久化的条目
    // 在 data class 意义上完全相等，队列判重/收藏命中/离线命中都不受影响。
    source = if (source == MusicSource.NETEASE) null else source.key,
    sourceId = sourceId,
    mediaId = mediaId,
)
