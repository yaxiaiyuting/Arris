/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import androidx.compose.runtime.Immutable

/**
 * TTML 文件 <head> 里的 AMLL 元数据（v1.9.0）。
 *
 * AMLL TTML DB 的每个文件都带一组 `<amll:meta key="..." value="..."/>`，
 * 其中 [ncmMusicId] 是**ncm 歌曲 ID**，与 Arris 的 `songId` 同一套编号 ——
 * 这是「按 songId 直接拉取」能成立的根据，也是校验「拉回来的确实是这首歌」的唯一凭据。
 */
@Immutable
data class TtmlMeta(
    val ncmMusicId: String? = null,
    val musicName: String? = null,
    val artists: String? = null,
    val album: String? = null,
    /** `ttmlAuthorGithubLogin`，用于问题追溯。 */
    val author: String? = null,
)

/**
 * 一份解析完成的 TTML 歌词（v1.9.0）。
 *
 * **[lines] 直接复用既有的 [LrcLine] / [LrcWord] 模型** —— 这是本版「渲染层零改动」的关键：
 * TTML 的 `<p>` 落成一行 [LrcLine]，`<span>` 落成该行的 [LrcWord]（带字符区间），
 * 于是 [LyricsView] / [NcrustLyricsPanel] / `SweepTrack` 完全不需要知道歌词来自 TTML 还是 yrc。
 *
 * [translations] / [romans] 是独立的行级轨道（对应 `ttm:role="x-translation"` / `"x-roman"`），
 * 它们**不参与** [lines] 的文本，按时间戳与 [lines] 配对展示，语义与 ncm 的 `tlyric` 一致。
 */
@Immutable
data class TtmlDoc(
    val lines: List<LrcLine>,
    val translations: List<LrcLine> = emptyList(),
    val romans: List<LrcLine> = emptyList(),
    val meta: TtmlMeta = TtmlMeta(),
)

/**
 * TTML 解析入口（v1.9.0）。**纯 Kotlin，无 Android 框架依赖**，JVM 可单测。
 *
 * 契约：
 * - 输入是 AMLL TTML DB 的 `.ttml` 原文（UTF-8 字符串）；
 * - 输出 [TtmlDoc]；**无法解析 / 没有任何歌词行 / 输入为空** 一律返回 `null`，
 *   **绝不抛异常**（拉取到脏数据只应导致回退到下一级歌词源，不应让播放器崩）；
 * - 不含 `<span>` 逐字信息的文件（纯逐句 TTML）也能解析：[LrcLine.words] 为空即可，
 *   调用方据此决定是否值得用 TTML 替换 LRC（见 [hasWordLevel]）。
 */
object TtmlParser {

    /**
     * 解析 TTML 原文。失败返回 `null`。
     *
     * 实现委托给 [TtmlScanner]（本文件的公开 API 是冻结契约，扫描器可独立演进）。
     */
    fun parse(xml: String): TtmlDoc? = TtmlScanner.parse(xml)

    /**
     * 这份文档是否真的带逐字信息。
     *
     * 「有逐字」= 至少一行有非空 [LrcLine.words]。**判据必须看词，不能看行** ——
     * AMLL DB 里存在只有 `<p>` 没有 `<span>` 的逐句投稿，那种文件对
     * 「提升逐字覆盖率」这个目标毫无价值，用它替换 LRC 只会白白丢掉 ncm 的行级数据。
     */
    fun hasWordLevel(doc: TtmlDoc): Boolean = doc.lines.any { it.words.isNotEmpty() }
}
