/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.Player

/**
 * v2.1.6 · 会话合并后的两处**纯逻辑**。抽出来是为了能上 JVM 单测 ——
 * 它们是这次承重改造里唯二「改错了不会崩、只会静默做错事」的地方。
 */
internal object MediaSessionMerge {

    /** 一条 media button 命令最终由谁执行。 */
    enum class Route {
        /** 交给应用的播放队列（[PlaybackService.onPlaybackEnded]）。 */
        APP_NEXT,

        /** 交给应用的播放队列（[PlaybackService.onPlaybackPrevious]）。 */
        APP_PREVIOUS,

        /** 交给 media3 的默认实现（播放 / 暂停 / seek …）。 */
        MEDIA3_DEFAULT,
    }

    /**
     * 媒体按键**上一首 / 下一首**必须由应用回答，不能落到 media3 默认的
     * `player.seekToNextMediaItem()`。
     *
     * 原因见 [PlaybackService] 里 `onPlayerCommandRequest` 的 KDoc：ExoPlayer 的播放列表
     * 不是用户的播放队列（只有「当前项 + 至多一首预载项」），默认实现会把「下一首」退化成
     * 「跳到那首预载的歌」，而队列索引 / 歌词 / 持久化状态全停在上一首 —— 就是串台。
     *
     * ⚠️ **这张表就是契约**：少写一条，那条命令就会静默走 media3 默认实现。
     * 平台侧对「下一首」可能发 `SEEK_TO_NEXT` 也可能发 `SEEK_TO_NEXT_MEDIA_ITEM`
     * （取决于会话的播放列表长度与 ROM），所以两条都要接。
     */
    fun route(playerCommand: Int): Route = when (playerCommand) {
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT -> Route.APP_NEXT

        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS -> Route.APP_PREVIOUS

        else -> Route.MEDIA3_DEFAULT
    }

    /**
     * 会话 metadata 要发布哪两行。
     *
     * [followLyrics] = `true`（默认）：与 v1.5.1 起的行为逐字节一致 —— 有歌词行时
     * 第一行是当前歌词、第二行是「歌名 · 艺人」。
     *
     * [followLyrics] = `false`：会话只讲歌的身份（歌名 / 艺人），歌词只留在通知正文里。
     * 这是 [PlaybackService] 里 `session_metadata_lyrics` 回退开关的那一侧 ——
     * 用来在真机上 A/B 对照「会话 metadata 随歌词刷新」是否值得。
     *
     * **通知正文那一份永远走 [MediaDisplayLines.of] 的歌词分支，不受这个开关影响。**
     */
    fun sessionLines(
        songTitle: String,
        songArtist: String,
        lyricLine: String?,
        followLyrics: Boolean,
    ): MediaDisplayLines.Lines =
        if (followLyrics) MediaDisplayLines.of(songTitle, songArtist, lyricLine)
        else MediaDisplayLines.of(songTitle, songArtist, null)
}
