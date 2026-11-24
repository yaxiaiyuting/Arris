/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 库（收藏）页「单曲」tab 的**本地搜索**：纯逻辑，不碰 Android / 网络，JVM 可单测。
 */

package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem

/**
 * 库页「单曲」tab 的**本地过滤**：库里的歌已经在内存里，搜索不需要问任何人。
 *
 * ## 为什么是本地过滤，不是「重新发一轮搜索请求」
 *
 * 用户在这一页输入关键词时想问的是「**我收藏的**里面有没有这首」。走 ncm / qm 的
 * 搜索接口会把问题换成「**全网上**有没有这首」—— 答案里会混进大量不在收藏里的条目，
 * 而且每敲一个字就要发一轮网络请求、与这一页「滚到底分页拉详情」的逻辑抢同一份数据源。
 * 所以这里只过滤 `savedSongs`，一个请求都不发。
 *
 * ## 为什么多词是 AND（每个词都要命中）
 *
 * 用户输入「周杰伦 晴天」的意图是**缩小**范围。按 OR 处理的话，歌名带「晴天」、
 * 或者艺人里有「周杰伦」的都会留下 —— 输入越长结果越多，与「搜索」的直觉正好相反，
 * 用户会觉得这个搜索框根本没用。
 *
 * ## 为什么只搜歌名 / 艺人 / 专辑三段
 *
 * 这三段是列表里**看得见**的东西（单曲行画的就是这三行）。歌词、评论、别名不在本地，
 * 把它们算进「可搜」只会让用户搜不到自己明明看着的那首歌。
 *
 * 匹配在**每一段内部**分别进行，而不是把三段拼成一个长串再找：拼接会跨过字段边界
 * 造出假命中（歌名末尾的「小」+ 艺人开头的「青」会命中「小青」），而用户完全无法理解
 * 为什么那首歌会出现在结果里。
 */
object LibrarySongSearch {

    /**
     * 把用户输入折成**可比较的关键词**：去掉首尾空白、把连续空白压成一个空格。
     *
     * 两件事都必须做：
     * - **尾随空白**：输入法回车、手机上按空格都容易在末尾留下一个空格。不折掉的话，
     *   按空格切词会多出一个空 token，而空串 `contains("")` 恒真 ⇒ AND 语义被悄悄废掉
     *   （用户看到的是「搜索没起作用」，而不是「搜索坏了」）；
     * - **连续空白**：同理，连按两下空格会产生空 token。
     *
     * 判空（`keyword(q).isEmpty()`）也走这个函数，UI 因此不必再写第二份 trim 逻辑 ——
     * 两份逻辑迟早会漂移成「输入框显示在搜索、结果按未搜索处理」。
     *
     * 空白判据用 `Char.isWhitespace()` 而不是正则 `\s`：Java 的 `\s` 只认
     * 空格 / `\t` / `\n` / `\r` / `\f` / `\u000B`，**不认全角空格**（U+3000）——
     * 而中文输入法下用户「想打空格」时按出来的经常就是那个全角空格。
     */
    fun keyword(query: String): String {
        val out = StringBuilder(query.length)
        var pendingSpace = false
        for (ch in query) {
            if (ch.isWhitespace()) {
                // 只有已经写出过字符才记「待补一个空格」—— 首部空白因此被天然丢掉，
                // 尾部空白则因为没人来消费 pendingSpace 而不会落进结果。
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(ch)
        }
        return out.toString()
    }

    /**
     * 过滤 [songs]：关键词为空 ⇒ **原样返回 [songs]**（同一个对象，不做拷贝）。
     *
     * 返回同一个列表而不是 `songs.toList()` 是有意的：UI 要用「未过滤的列表是否为空」
     * 区分「库里本来就没有歌」与「这次没搜到」两档空态，两者共用同一份数据源
     * 就不会出现「两份判据互相抢文案」的漂移；顺带也省掉每次重组的一次整表拷贝。
     *
     * 大小写不敏感靠 `lowercase()`（**不带 Locale**）：它走 Unicode 默认映射，
     * 与系统语言无关。带 `Locale.getDefault()` 的话，土耳其语系统会把 `I` 折成 `ı`，
     * 同一份收藏在不同手机上会搜出不同结果 —— 这类 bug 只在特定 locale 上复现，最难查。
     *
     * @param songs 待过滤的曲目，命中项**保持原顺序**（仓库里的顺序就是用户熟悉的顺序）。
     * @param query 用户输入；空白 / 大小写由本函数自己折，调用方不需要预处理。
     */
    fun match(songs: List<SongItem>, query: String): List<SongItem> {
        val terms = keyword(query)
            .split(' ')
            .filter { it.isNotEmpty() }   // keyword 已保证不会出现空段，留着是防御：空 token 会让 AND 恒真
            .map { it.lowercase() }
        if (terms.isEmpty()) return songs
        return songs.filter { song ->
            val title = song.name.lowercase()
            val artists = song.artists.orEmpty().joinToString(" ") { it.name.lowercase() }
            val album = song.album?.name.orEmpty().lowercase()
            terms.all { term -> term in title || term in artists || term in album }
        }
    }
}
