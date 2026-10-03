/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds

/**
 * 「待播槽位」不变量（v1.5.2 串台修复）。
 *
 * ExoPlayer 的播放列表里，当前项之后**至多允许一首**预载项，且 PlaybackService 的
 * pendingNext* 元数据必须与它一一对应。抽成不依赖 Android 的纯逻辑是为了能在 JVM 单测里
 * 把这条不变量钉死 —— 它一旦被破坏，表现就是用户报告的「串台」：
 *
 * 复现链（v1.5.1 用户报告 + API 24 真机 logcat 复现）：
 *  1. MainScreen 在**切歌瞬间**（preloadNextSong 调用点 1）与**进入最后 60s**
 *     （needsPreload 调用点 2）各预载一次同一首下一曲；
 *  2. 19f2969 为了让「URL 已缓存」时也能入队，删掉了上游那条「缓存命中就整体 return」
 *     的去重，于是第二次预载又 addMediaItem 了一遍，列表变成 [当前, 下一首, 下一首']；
 *  3. 当前项播完先播「下一首」（元数据正确），而那个重复项被播到时，pendingNext*
 *     已经被后来的预载改成了「下下首」⇒ 耳朵里是上一首、通知栏/歌词显示下一首。
 *
 * 本对象只做判定，不碰 player；调用方（PlaybackService）负责按 Decision 增删项。
 */
object PreloadSlot {
    /** media3 MediaItem 的 mediaId 前缀：song:<id>。 */
    const val MEDIA_ID_PREFIX = "song:"

    /** 一次 preload 请求相对槽位的取舍。 */
    enum class Decision {
        /** 槽位空 → 直接追加。 */
        APPEND,

        /** 槽位里是别的歌 / 同一首但 URL 已更新（降档重试）→ 先移除旧待播项再追加。 */
        REPLACE,

        /** 槽位里已经是完全相同的这一项 → 幂等忽略，**绝不重复入队**。 */
        IGNORE,
    }

    /**
     * 预载项的 mediaId（v2.1.5：**带音源**）。
     *
     * v2.1.4 及更早这里恒为 `song:<id>`。对 qm 来说那是一个**错的**编码：
     * QQ 的 id 带 `1L shl 62` 标志位，`song:4611686018427…` 会被
     * [SourceIds.parseMediaId] 解析成「ncm 的一首巨大 id 的歌」。后果有两级：
     * 轻的是车机 / 通知路径按 ncm 去取链（404 → 跳歌）；
     * 重的是自动接续时**无法从 item 本身回答「这是哪个音源的歌」**，
     * 只能退回信任旁路变量 —— 而那正是 v2.1.5 要修的串台源头。
     *
     * ncm 一侧的形状**逐字节不变**（`song:123`），所以老媒体项、车机 browse tree、
     * 以及所有既有持久化数据都不受影响。
     */
    fun mediaIdFor(source: MusicSource, songId: Long, url: String): String =
        if (songId > 0) (SourceIds.mediaId(source, songId) ?: url) else url

    /** ncm 专用重载，保持 v2.1.0 之前的行为（既有调用点与单测零改动）。 */
    fun mediaIdFor(songId: Long, url: String): String =
        mediaIdFor(MusicSource.NETEASE, songId, url)

    /**
     * 从 mediaId 反解 `(音源, 歌曲 id)`。解析不出音源返回 null ——
     * **不猜成 ncm**，用错音源取链会拿到 404 或别人的歌。
     */
    fun identityFromMediaId(mediaId: String?): Pair<MusicSource, Long>? =
        SourceIds.parseMediaId(mediaId)

    fun songIdFromMediaId(mediaId: String?): Long? = identityFromMediaId(mediaId)?.second

    /**
     * @param pendingSongId 槽位里待播项的歌曲 id；无 id（老路径）时为 -1
     * @param pendingUrl    槽位里待播项的 URL；**槽位是否被占用只看它**（null = 空槽）
     */
    fun decide(
        pendingSongId: Long,
        pendingUrl: String?,
        incomingSongId: Long,
        incomingUrl: String,
    ): Decision {
        if (pendingUrl == null) return Decision.APPEND
        val same = if (pendingSongId > 0 && incomingSongId > 0) {
            pendingSongId == incomingSongId && pendingUrl == incomingUrl
        } else {
            pendingUrl == incomingUrl
        }
        return if (same) Decision.IGNORE else Decision.REPLACE
    }

    /**
     * transition 守卫：ExoPlayer 真正起播的那一项，是否就是槽位里预载的那一首。
     * 只有 true 才允许把 pendingNext* 写进通知栏 / 歌词 / ViewModel。
     *
     * v2.1.5 起**音源也参与判定**：`(source, id)` 两件套都对上才算同一首。
     * ncm 的 `123` 与 QQ 的 `123` 是不同的歌，只比 id 会把它们判成同一首 ——
     * 那正是「跨源切歌后元数据/歌词留在上一首」的另一半原因。
     *
     * @param itemSource 起播项的 mediaId 反解出的音源；反解不出（老形状 / 无 id 项）传 null
     */
    fun transitionMatches(
        pendingSource: MusicSource,
        pendingSongId: Long,
        pendingUrl: String?,
        itemSource: MusicSource?,
        itemSongId: Long?,
        itemUrl: String?,
    ): Boolean {
        if (pendingUrl == null) return false
        // 槽位带 songId 时只认 (音源, songId)：起播项没有 id（不是我们入队的那一项）一律拒绝，
        // 不能因为 URL 恰好相同就放行 —— 那是「宁可不更新，也不显示错歌」的底线。
        if (pendingSongId > 0) return itemSongId == pendingSongId && itemSource == pendingSource
        // 老路径 / 无 id 的预载项：退回 URL 比对。
        return itemUrl != null && itemUrl == pendingUrl
    }

    /**
     * ncm 默认重载（v1.5.2 起的既有形状）。**只为不破坏既有单测**：
     * 生产代码一律走上面那个带音源的版本。
     */
    fun transitionMatches(
        pendingSongId: Long,
        pendingUrl: String?,
        itemSongId: Long?,
        itemUrl: String?,
    ): Boolean = transitionMatches(
        MusicSource.NETEASE, pendingSongId, pendingUrl,
        itemSongId?.let { MusicSource.NETEASE }, itemSongId, itemUrl,
    )
}
