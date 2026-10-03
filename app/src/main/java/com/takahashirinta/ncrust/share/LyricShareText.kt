/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 「歌词 → 纯文本」与「文件命名」的纯逻辑。
 *
 * ## 输出格式（复制与分享文本共用同一份）
 *
 * ```
 * 成都 - 赵雷
 *
 * [00:12.30] 让我掉下眼泪的
 * [00:16.80] 不止昨夜的酒
 * ...
 *
 * 来自 Ncrust
 * ```
 *
 * - 表头（歌名 - 歌手）与署名行各自有开关，默认都开（理由见 [LyricShareOptions] 的 KDoc）；
 * - 时间戳默认**不带**，带上时用 LRC 原生的 `[mm:ss.xx]`（厘秒）而不是 `(mm:ss)` ——
 *   复制出去的那段文本可以直接粘回任何 LRC 编辑器，这是「带时间戳」唯一真实的用途；
 * - 译文 / 音译各占**紧随其后的一行**、不加任何前缀：与播放器里的双语排版一致
 *   （加了 `[译]` 前缀反而没法整段复制回 LRC）；
 * - 译文与原文**逐字相同**时丢掉该行 —— 唱片公司偶尔把原文填进 tlyric，
 *   印两遍一模一样的句子是明显的错误（本仓库 `LyricSubtitleText.visibleRomanization`
 *   对音译做同一件事，这里保持一致）。
 */
object LyricShareText {

    /** 不带时间戳时，歌词行前面的缩进/前缀：无（纯文本）。 */
    private const val LINE_SEPARATOR = "\n"

    /**
     * 格式化整段可分享文本。
     *
     * 三段（表头 / 正文 / 署名）**各自独立、空段直接消失**，非空段之间恰好隔一个空行。
     * 这样「没有歌词」「没有歌名」「关掉署名」三种缺失任意组合都不会产出
     * 连续空行或只剩破折号的行 —— 那是这类模板最常见的脏输出。
     *
     * @param credit 末尾署名行（来自 `ShareStrings.creditLine`）。为空串时整行省略 ——
     *               文案在 i18n 里，本函数不写死任何自然语言。
     */
    fun format(
        track: LyricShareTrack,
        lines: List<LyricShareLine>,
        options: LyricShareOptions,
        credit: String = "",
    ): String {
        val head = if (options.includeHeader) track.title.trim() else ""
        val body = body(lines, options)
        val tail = if (options.includeCredit) credit.trim() else ""
        return listOf(head, body, tail)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    /** 只拼歌词正文（不带表头 / 署名）。抽出来是为了让 [format] 的两个开关可单独断言。 */
    fun body(lines: List<LyricShareLine>, options: LyricShareOptions): String {
        val sb = StringBuilder()
        appendBody(sb, lines, options)
        return sb.toString().trimEnd('\n')
    }

    private fun appendBody(sb: StringBuilder, lines: List<LyricShareLine>, options: LyricShareOptions) {
        for (line in lines) {
            val main = line.text.trim()
            if (main.isEmpty()) continue
            if (options.includeTimestamps) {
                sb.append(timestamp(line.timeMs)).append(' ')
            }
            sb.append(main).append(LINE_SEPARATOR)
            appendSubtitle(sb, main, line.translation, options.includeTranslation)
            appendSubtitle(sb, main, line.romanization, options.includeRomanization)
        }
    }

    private fun appendSubtitle(sb: StringBuilder, main: String, sub: String, enabled: Boolean) {
        if (!enabled) return
        val t = sub.trim()
        if (t.isEmpty() || t == main) return
        sb.append(t).append(LINE_SEPARATOR)
    }

    /**
     * LRC 口径的时间戳：`[mm:ss.xx]`（厘秒，两位）。
     *
     * 用厘秒而不是毫秒是因为它同时兼容 `[mm:ss.xx]` 与 `[mm:ss.xxx]` 两种读法
     * （见 `LrcParser.TIME_STAMP`），而毫秒写法在只认厘秒的编辑器里会被截断成乱码时间。
     */
    fun timestamp(timeMs: Long): String {
        val ms = timeMs.coerceAtLeast(0L)
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val centis = (ms % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
    }

    // ---------------------------------------------------------------- 文件命名

    /**
     * 生成图片文件名：`ncrust-lyrics-<歌名>-<yyyyMMdd-HHmmss>.png`。
     *
     * 三个约束：
     * - **必须含歌名**：用户在图库里翻到 `IMG_20260101.png` 是找不到东西的；
     * - **必须含时刻**：同一首歌连出两张不能互相覆盖（FileProvider 的 URI 也就不会撞）；
     * - **必须能过文件系统**：歌名里 `/`、`:`、`?`、换行、emoji 全都会炸，
     *   所以非法字符一律换成 `-`，连续 `-` 折叠，两端裁掉，超长截断到 [MAX_NAME_CHARS]。
     *
     * 时间由调用方以 [stamp] 生成并传入，本函数因此**完全确定**（单测不依赖当前时间）。
     */
    fun fileName(track: LyricShareTrack, stamp: String): String {
        val slug = slugify(track.name)
        return "ncrust-lyrics-$slug-$stamp.png"
    }

    /** 歌名合法化后的片段（空歌名 ⇒ `lyrics`，绝不产出 `--` 或空段）。 */
    fun slugify(raw: String): String {
        val sb = StringBuilder()
        var lastDash = false
        for (ch in raw) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch)
                lastDash = false
            } else if (!lastDash && sb.isNotEmpty()) {
                sb.append('-')
                lastDash = true
            }
            if (sb.length >= MAX_NAME_CHARS) break
        }
        return sb.toString().trim('-').ifEmpty { "lyrics" }
    }

    /** 文件名里的时刻戳：`yyyyMMdd-HHmmss`。时区可注入 ⇒ 单测传 UTC 即完全确定。 */
    fun stamp(nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(nowMs))

    /** 歌名片段长度上限：够长到能认出是哪首歌，又不至于撞上文件系统的 255 字节上限。 */
    const val MAX_NAME_CHARS = 24
}
