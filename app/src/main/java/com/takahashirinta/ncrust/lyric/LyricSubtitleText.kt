/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

/**
 * v1.9.3：歌词副文本（译文 / 音译）的**显示规则** —— 纯函数、零 Compose 依赖，JVM 可单测。
 *
 * 为什么这点规则也值得抽出来单测：本版解禁了渲染层（NcrustLyricsPanel），而渲染层在本仓库
 * 没有 JVM 单测（Compose UI 测试要真机/模拟器）。把「哪些副文本该出现、以什么顺序出现」
 * 全部收进这里，渲染层就退化成「按返回的列表原样挂载」—— 于是本版唯一的显示决策是有测试
 * 保护的，真机只负责验证「画出来长什么样」。
 *
 * 与 v1.9.2 的关系：音译轨（PlayerViewModel.romanizedLyrics 那一份数据）一行都不动，
 * 本文件只决定「读出来的音译要不要显示」。关掉开关时任何输入都返回空串，
 * 渲染层的副文本槽根本不挂载 —— 这是「默认关闭 = 与 v1.9.2 逐字节一致」的保证。
 */
object LyricSubtitleText {

    /** 一行歌词最多两条副文本：译文 + 音译。与 [subtitleLines] 的返回上限一致。 */
    const val MAX_SUBTITLES = 2

    /**
     * 决定「音译」这一行是否真的显示，返回应当渲染的文本（不显示时返回**空串**）。
     *
     * 三条丢弃规则，取舍一律是「宁可少一行，也不要多一行噪音」：
     *
     *  1. 开关关掉（默认）→ 空串。老用户升级后渲染路径与 v1.9.2 逐字节一致；
     *  2. 纯空白 → 空串。**这是「无音译的歌打开开关也不产生空行」的关键**：
     *     ncm romalrc 与 TTML 的 x-roman 都可能出现空文本行（间奏 / 纯音乐段），
     *     而 Compose 里挂一个空 MetroText 会照样占掉一行行高 + 2dp 间距；
     *  3. 与原文 trim 后逐字相同 → 空串。英文歌的 romalrc 有时就是原文本身，
     *     同一句话在原文下方再显示一遍，等于把副文本槽变成噪音源。
     *
     * 返回的是**原始字符串**（不做 trim / 不改写数据）：数据层与渲染层之间不引入新的文本变换，
     * 用户看到的就是服务端给的音译。
     */
    fun visibleRomanization(main: String, romanization: String, show: Boolean): String {
        if (!show) return ""
        val trimmed = romanization.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == main.trim()) return ""
        return romanization
    }

    /**
     * 一行歌词的副文本，按**渲染顺序**返回：译文在上、音译在下。
     *
     * 顺序与 ncm 官方客户端的「原文 / 翻译 / 音译」三层一致，用户不需要重新学；
     * 空串与纯空白一律不占槽位（返回值里绝不会出现空元素）。
     */
    fun subtitleLines(translation: String, romanization: String): List<String> {
        val out = ArrayList<String>(MAX_SUBTITLES)
        if (translation.isNotBlank()) out.add(translation)
        if (romanization.isNotBlank()) out.add(romanization)
        return out
    }

    /**
     * 无障碍（liveRegion）播报文本：原文 + 副文本逐行拼接。
     *
     * 音译为空时与 v1.9.2 的表达式逐字节一致（translation 空 → 只有原文；否则原文 + 换行 + 译文）——
     * 也就是说关掉音译开关，TalkBack 播报的内容一个字都不变。
     */
    fun a11yText(main: String, translation: String, romanization: String): String {
        val subs = subtitleLines(translation, romanization)
        if (subs.isEmpty()) return main
        val sb = StringBuilder(main.length + 32)
        sb.append(main)
        for (s in subs) {
            sb.append('\n').append(s)
        }
        return sb.toString()
    }
}
