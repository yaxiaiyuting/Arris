/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

/**
 * 系统媒体面板 / 通知里那两行文字该怎么排（v1.6.0 · 用户反馈修正）。**纯逻辑**，JVM 可单测。
 *
 * ## 背景
 *
 * v1.5.1 的「媒体面板显示歌词」把当前歌词行**拼在艺人后面**（第二行 = `艺人 · 歌词`），
 * 歌名留在第一行。真机（PCL110 / ColorOS / Android 16）上用户看到的正是
 * `67` / `DJ R4 · You have a mental proble..` —— 用户认为这两行的位置反了：
 * 他要的是**第一行显示当前歌词**（真正在变的那条信息），**第二行显示「歌名 · 艺人」**。
 *
 * ## 规则（就这一条）
 *
 * | 条件 | 第一行 | 第二行 |
 * |---|---|---|
 * | 有歌词行（开关开 + 该曲有歌词 + 当前有行） | **当前歌词行** | **歌名 · 艺人** |
 * | 没歌词行（开关关 / 无歌词 / 行未就绪） | 歌名 | 艺人 |
 *
 * 边界：
 * - 歌词行是空白（`"  "`）→ 当作没有歌词（不要去显示一条空行）；
 * - 艺人缺失（纯音乐 / 数据不全）→ 第二行只写歌名，**不留** `"歌名 · "` 这种尾巴；
 * - 歌名缺失 → 第二行只写艺人；
 * - 两者都缺 → 第二行空串（面板会自己隐藏空行）。
 *
 * 纯函数、无 Android 依赖：这三行表就是全部契约，改行为必须先改这里的单测。
 */
internal object MediaDisplayLines {

    /** 面板/通知的两行文字。 */
    data class Lines(val title: String, val subtitle: String)

    fun of(songTitle: String, songArtist: String, lyricLine: String?): Lines {
        val lyric = lyricLine?.takeIf { it.isNotBlank() }
            ?: return Lines(title = songTitle, subtitle = songArtist)
        val subtitle = when {
            songArtist.isBlank() -> songTitle
            songTitle.isBlank() -> songArtist
            else -> songTitle + " · " + songArtist
        }
        return Lines(title = lyric, subtitle = subtitle)
    }
}
