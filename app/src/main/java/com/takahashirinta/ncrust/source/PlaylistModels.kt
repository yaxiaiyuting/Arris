/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：歌单的跨源身份。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem

/**
 * 一个歌单的**跨源身份**（v2.2.0）。
 *
 * ## 为什么必须是三个字段，而不是一个 id
 *
 * ncm 的 `playlistId` 与 qm 的 `tid` 各自独立编号，撞号是必然的 —— 这与
 * [TrackKey] 存在的理由完全一样（见 v2.1.0 的 `media_mid ≠ mid` 与 v2.1.5 的跨源串台）。
 * 但歌单比曲目还多一层：**同一个歌单 id 在不同账号下的「目录号」是不同的** ——
 * qm 的 `dirId` 是**账号内**的目录号（`dirId=1` 是「我的第一个歌单」，
 * 换一个账号 `dirId=1` 就是**另一个歌单**）。
 *
 * 所以身份必须是 `(音源, 歌单 id, 归属账号)` 三元组：
 *
 * | 场景 | 只带 id 会怎样 | 带 ownerId 会怎样 |
 * |---|---|---|
 * | 两个 ncm 账号各自有一个 playlistId=1 的歌单 | 互相覆盖缓存 | 天然隔离 |
 * | QQ 账号切换后看同一个 dirId | 展示上一个账号的歌单 | 键不同 ⇒ 缓存不命中 ⇒ 拉新的 |
 *
 * [id] 用**全局唯一**的那个 id（qm 取 `tid`，不是 `dirId`），[ownerId] 只用来说明
 * 「这个歌单属于哪个账号」；两者一起才构成键。qm 的 `dirId` 是**载荷**（请求详情要用），
 * 放在 [Playlist.dirId]，不参与身份 —— 与 [TrackKey] 把 `sourceId`/`mediaId` 排除在相等性之外
 * 是同一条设计规则：**身份归身份，载荷归载荷**。
 *
 * @property source 音源。未知取值一律回落 [MusicSource.DEFAULT]（见 [MusicSource.fromKey]）。
 * @property id 该音源内**全局唯一**的歌单 id。qm = `tid`（服务端叫 `dissid`/`disstid`）。
 * @property ownerId 归属账号在该音源内的标识。qm = 登录 `uin`；未登录时用
 *   [OWNER_ANONYMOUS]（**不是**空串 —— 空串会让「未登录」与「字段缺失」混为一谈，
 *   那正是 v1.9.2 `romalrc` 踩过的坑）。
 */
data class PlaylistKey(
    val source: MusicSource,
    val id: String,
    val ownerId: String,
) {
    /** `qqmusic:<ownerId>:<tid>`。缓存 key、日志、诊断都用它，一眼能看出音源与账号。 */
    val tag: String get() = source.key + ":" + ownerId + ":" + id

    companion object {
        /** 未登录时的归属标识。**显式常量**，不要用空串代替。 */
        const val OWNER_ANONYMOUS = "anonymous"

        /** 该 key 是否属于某个已登录账号（未登录的缓存不与任何账号混用）。 */
        fun isAnonymous(ownerId: String): Boolean = ownerId == OWNER_ANONYMOUS
    }
}

/**
 * 一个歌单（v2.2.0）。
 *
 * @property key 跨源身份（见 [PlaylistKey]）。
 * @property name 歌单名。
 * @property coverUrl 封面。服务端可能给 `http://`（qm 实测如此），也可能没有。
 * @property trackCount 服务端声明的曲目数（用于列表展示；**不等于**本地已缓存的曲目数，
 *   后者可能因为分页只拉了一部分而更少）。
 * @property isOwned 是否本人自建。收藏（他人）的歌单为 false。
 * @property isFavorite 是否是「我喜欢」这个特殊歌单（qm `dirId == 201`）。
 * @property updatedAt 服务端更新时间（秒级时间戳；0 = 未知）。
 * @property dirId 音源内部的目录号。qm 请求详情时要用（`dirId=201` + `disstid=0`
 *   可以打开「我喜欢」）。**不参与身份**，见 [PlaylistKey] 的说明。
 */
data class Playlist(
    val key: PlaylistKey,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val isOwned: Boolean,
    val isFavorite: Boolean,
    val updatedAt: Long,
    val dirId: Long = 0L,
)

/**
 * 歌单里的一首歌（v2.2.0）。
 *
 * [trackKey] **复用**歌词/播放系统已有的 [TrackKey]，不另起一套身份 —— 这是硬要求：
 * 歌单里的每一首歌最终都要能直接进队列、直接取链、直接取词，而那条链路上的一切
 * 都以 [TrackKey] 为准（[TrackKey.equals] 只比 `(source, id)`）。
 *
 * @property source 冗余一份音源，方便 UI 在不构造 [TrackKey] 时直接分组/加角标。
 *   它必须与 `trackKey.source` 相等（[PlaylistTrack.of] 保证）。
 * @property order 歌单内序号，从 0 开始，按服务端返回顺序。**分页拼接时按它排序**，
 *   不要依赖列表拼接顺序 —— 并发补页时后者不保证。
 * @property addedAt 加入歌单的时间。qm 的详情接口**不返回**这个字段，恒为 null
 *   （不要用 0 冒充「1970 年加入」）。
 */
data class PlaylistTrack(
    val source: MusicSource,
    val trackKey: TrackKey,
    val order: Int,
    val addedAt: Long? = null,
) {
    companion object {
        /**
         * 从 [SongItem] 构造。**一首歌的身份只有这一个入口**，避免「歌单里一套、队列里另一套」。
         *
         * `song.id <= 0` 的条目没有可用的数字身份，返回 null（调用方跳过），
         * 不要造一个假的 id 出来 —— 那会让队列去重、离线缓存、续播进度全部指向错误的曲目。
         */
        fun of(source: MusicSource, song: SongItem, order: Int, addedAt: Long? = null): PlaylistTrack? {
            if (song.id <= 0L) return null
            return PlaylistTrack(
                source = source,
                trackKey = TrackKey.fromSong(song),
                order = order,
                addedAt = addedAt,
            )
        }
    }
}

/**
 * 按音源分组后的歌单（v2.2.0）。**UI 只按这个结构渲染，绝不做跨源合并。**
 *
 * @property source 本组音源。
 * @property playlists 本组歌单，顺序与服务端返回一致（不重排、不跨源插队）。
 */
data class PlaylistGroup(
    val source: MusicSource,
    val playlists: List<Playlist>,
)

/**
 * 把歌单**按音源分组**（v2.2.0）。纯逻辑，JVM 可单测。
 *
 * 刻意**不做**的事：
 * - 不合并不同音源的同名歌单；
 * - 不按名字/曲目数跨源排序（那会让「QQ 的歌单」和「ncm 的同名歌单」看起来像一个东西）；
 * - 不丢弃空组（调用方需要知道「这个音源有 0 个歌单」并显示空状态，而不是整组消失）。
 *
 * 组顺序按 [MusicSource.loginSources]（与 UI 上音源切换的顺序一致，稳定可预期），
 * 组内保持传入顺序。
 *
 * ## v3.1.0：默认源从 `selectable` 换成 `loginSources`
 *
 * B 站接入后 `selectable` 有三个值，而 B 站**没有歌单同步能力**（音频区收藏夹本轮不做，
 * 见 `LocalPlaylistRepository.loadRemoteSongs`）。继续用 `selectable` 会让收藏页
 * 凭空多出一个永远空的「B 站」分组 —— 一个只在接入新源时出现、用户又点不出东西的分组。
 * 换成 `loginSources` 之后，库页的形态与 v3.0.0 **逐字相同**（ncm + QQ 两组）。
 */
fun groupPlaylistsBySource(
    playlists: List<Playlist>,
    sources: List<MusicSource> = MusicSource.loginSources,
): List<PlaylistGroup> {
    val bySource = playlists.groupBy { it.key.source }
    return sources.map { src -> PlaylistGroup(src, bySource[src].orEmpty()) }
}
