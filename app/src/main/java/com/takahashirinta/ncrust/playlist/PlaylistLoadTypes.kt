/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：歌单加载结果的**类型契约**（UI 与数据层共用）。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.PlaylistTrack

/**
 * 一次加载的降级标记。`null` = 一切正常；非 null = 数据能用但**不完整/不新鲜**，UI 要提示。
 *
 * 把「降级」做成**数据的一部分**而不是另开一个错误态，是为了让「离线可看」这条需求
 * 真正成立：离线时我们**既有数据、又有话要说**，两者要同时抵达 UI。
 */
enum class PlaylistDegradation {
    /** 网络失败，展示的是缓存。 */
    OFFLINE,

    /** 登录态失效，展示的是缓存（或空）。 */
    NEED_LOGIN,

    /** 分页被 [QqPlaylistApi.MAX_PAGES] 截断，没拉全。 */
    TRUNCATED,
}

/**
 * 加载结果。[data] 为空列表 + [degradation] != null 是**合法组合**
 * （离线且没有缓存 —— UI 显示空状态 + 降级提示，而不是一个没有出口的错误页）。
 */
data class PlaylistLoadOutcome<T>(
    val data: T,
    val fromCache: Boolean,
    val fresh: Boolean,
    val degradation: PlaylistDegradation?,
)

/**
 * 硬失败：**连缓存都没有**，UI 只能给出口（重试 / 去登录 / 回退）。
 * 与 [PlaylistLoadOutcome] 的区别是「有没有东西可显示」。
 */
sealed interface PlaylistHardFailure {
    data object NeedLogin : PlaylistHardFailure
    data object Network : PlaylistHardFailure
    data object NotFound : PlaylistHardFailure
    data class Other(val code: Int) : PlaylistHardFailure
}

/** 加载结果：要么有东西可显示（可能降级），要么硬失败。 */
sealed interface PlaylistResult<out T> {
    data class Data<T>(val outcome: PlaylistLoadOutcome<T>) : PlaylistResult<T>
    data class Failed(val reason: PlaylistHardFailure) : PlaylistResult<Nothing>
}

/**
 * 歌单详情的完整数据：**可渲染的歌曲** + **可判等的身份**。
 *
 * 两者一一对应、等长（都由同一个 `QqSongMapper` 结果派生）。分开是因为它们的消费者不同：
 * UI 要 [songs]（歌名/歌手/封面），竞态闸门要 [tracks]（[PlaylistTrack.trackKey]）。
 * 合成一个类而不是让调用方自己对应下标 —— 那样迟早会有人按下标硬凑。
 */
data class PlaylistDetailData(
    val songs: List<SongItem>,
    val tracks: List<PlaylistTrack>,
)
