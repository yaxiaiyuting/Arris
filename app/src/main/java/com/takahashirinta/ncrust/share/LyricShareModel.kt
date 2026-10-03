/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

/**
 * 歌词复制 / 分享 / 出图（用户需求第 9 条）的**纯逻辑模型**。
 *
 * 本文件刻意不 import 任何 `android.*` / `androidx.*`：它要能在 JVM 单测里直接跑
 * （`app/src/test/.../share/` 下的用例全部只依赖它）。Android 侧的两份实现分别在
 * [LyricPosterRenderer]（Canvas 出图）与 [LyricShareActions]（剪贴板 / Intent / FileProvider）。
 *
 * ## 为什么不用 `SongItem`
 *
 * 分享只需要「海报上要印的四个字段」。用 `SongItem` 会把 30+ 个字段（音源、权益、时长、
 * 各种 id）一起拖进排版逻辑，纯函数也就没法用一个三行的字面量去测了。
 * [LyricShareTrack] 是**展示模型**，由调用方（歌词面板）从当前播放曲目投影出来。
 */

/**
 * 出图 / 分享要印在作品上的曲目信息。
 *
 * @param name 歌名（可能为空串 —— 冷启动恢复的曲目只有 id + 名字，没有艺人）
 * @param artist 歌手，多个用 `/` 连接（与 `SongMenuSheet` 的展示口径一致）
 * @param album 专辑名，可空；只用于文本分享的可选署名行
 * @param coverUrl 封面原图 URL（**未**做尺寸包装）。出图时由 [LyricPosterCover] 按需取小图。
 */
data class LyricShareTrack(
    val name: String,
    val artist: String = "",
    val album: String = "",
    val coverUrl: String? = null,
) {
    /** 分享文本 / 系统分享弹窗的标题：`歌名 - 歌手`（缺一就只留有的那个）。 */
    val title: String
        get() = when {
            name.isNotBlank() && artist.isNotBlank() -> "$name - $artist"
            name.isNotBlank() -> name
            else -> artist
        }
}

/**
 * 一行可分享的歌词：原文 + 已按 `timeMs` 配对好的译文 / 音译。
 *
 * **配对在调用方完成**（歌词面板里本来就有 `translatedLyrics.associateBy { it.timeMs }`）。
 * 这里不再存 `List<LrcLine>`，是为了让「选哪几句、怎么截断」这些判定彻底与歌词引擎解耦 ——
 * `LrcParser` / `YrcParser` 正在被并行改动，本包不依赖它们的任何字段。
 */
data class LyricShareLine(
    val timeMs: Long,
    val text: String,
    val translation: String = "",
    val romanization: String = "",
)

/** 分享范围：当前唱段（前后各 N 行）/ 整首歌词。 */
enum class LyricShareScope {
    CURRENT_SECTION,
    WHOLE_SONG,
}

/**
 * 分享选项。
 *
 * ## 三个默认值的理由（需求里点名要的「给理由」）
 *
 * - **不带时间戳**（`includeTimestamps = false`）：复制歌词的第一用途是粘到聊天 / 动态 /
 *   备忘录里，`[01:06.45]` 在那些地方只是噪声。但「导出成 LRC 片段」是真实存在的第二用途，
 *   所以格式化器**支持**它、弹层里也留了开关，只是默认关。
 * - **带歌名 / 歌手出处**（`includeHeader = true`）：一段没有出处的歌词，粘到任何地方都
 *   无法回溯是哪首歌 —— 分享的第一性目的是「让人知道这是哪首歌」。这与「不带时间戳」
 *   并不矛盾：出处是**信息**，时间戳只在播放器里有意义。
 * - **译文 / 音译跟随屏幕上的显示状态**（由调用方把当前的显示开关作为初值传进来）：
 *   「复制下来的」与「看到的」不一致是这类功能最常见的投诉。
 *   弹层里两个开关都能改，所以「跟随」是默认而不是强制。
 */
data class LyricShareOptions(
    val scope: LyricShareScope = LyricShareScope.CURRENT_SECTION,
    val includeTranslation: Boolean = true,
    val includeRomanization: Boolean = false,
    val includeTimestamps: Boolean = false,
    /** 是否带上「歌名 - 歌手」表头。 */
    val includeHeader: Boolean = true,
    /** 是否带上末尾的「来自 Ncrust」署名行（文案由调用方给，见 `ShareStrings.creditLine`）。 */
    val includeCredit: Boolean = true,
)

/**
 * 出图用的颜色集合。
 *
 * 值全部是 **ARGB `Int`**（`android.graphics.Color` 的口径），但本类本身不依赖 Android ——
 * 于是「海报配色对不对」也能在 JVM 上断言。调用方从 `LocalMetroColors.current` 取色。
 */
data class LyricPosterColors(
    val background: Int,
    val primary: Int,
    val onBackground: Int,
    val onSurfaceVariant: Int,
    val divider: Int,
    /** 主色块上的前景色（本版只用在顶部色条的占位，保留以免后续加主色底时再改签名）。 */
    val onPrimary: Int,
)
