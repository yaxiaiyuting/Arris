/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

/**
 * 成员侧「要不要跟随」的判定（v3.8.1）——抽成纯函数，因为这里踩过**最严重的一次线上语义错误**：
 *
 * 旧实现的第一行是 `val local = src.current() ?: return`，意思是「本机没在播的歌就什么都不做」。
 * 而「刚打开 App 进房间」恰恰是本机没歌的常态 —— 于是成员永远不开始播，
 * 表现就是用户说的「连接是连上了，但根本不同步」。
 *
 * 所以判定必须允许 `local == null`，并把它当成「需要起播」。
 */
object FollowPolicy {

    /** 同一个平台 ID 才算同一首歌；任一侧没有 ID 时退回标题比较。 */
    fun isSameSong(local: PlaybackSnapshot?, remote: PlaybackSnapshot): Boolean {
        if (local == null) return false
        val l = local.songId
        val r = remote.songId
        return if (l != null && r != null) l == r else local.title == remote.title
    }

    /** 偏差超过容差才 seek：每秒一次的 seek 会把播放打断成「咔哒」声。 */
    fun needsSeek(local: PlaybackSnapshot?, remote: PlaybackSnapshot, toleranceMs: Long): Boolean {
        if (local == null) return false // 起播由 START 负责，seek 留到真正上播之后
        return kotlin.math.abs(remote.positionMs - local.positionMs) > toleranceMs
    }

    /** 需要纠正播放/暂停态（仅在已在播同一首歌时有意义）。 */
    fun needsTransportFix(local: PlaybackSnapshot?, remote: PlaybackSnapshot): Boolean =
        local != null && local.playing != remote.playing
}
