/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0：B 站**视频字幕 → 歌词**。纯逻辑（只依赖 org.json），JVM 可单测。
 */

package com.takahashirinta.ncrust.bili

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条视频字幕。
 *
 * @property fromMs 起始时刻（毫秒，绝对）。B 站给的是**浮点秒**，这里在解析时就换掉 ——
 *   让下游（LRC 生成、单测）只面对整数毫秒，不会出现「0.1+0.2」那种比较翻车。
 * @property toMs 结束时刻（毫秒）。**只用于判断这一条是不是纯音乐/间奏**，
 *   不写进 LRC（LRC 的每行只有起始时间）。
 * @property text 正文。AI 字幕会把歌词包在 `♪ … ♪` 里，**这里原样保留**，
 *   剥离发生在 [BiliSubtitle.lyricLrc] —— 解析层不替调用方做展示决策。
 * @property music 服务端的「这是音乐」置信度，实测取值只有 `0.0` 与 `1.0`（浮点近似，
 *   形如 `0.9999998387097035`）。**这是结构化的歌词信号**，见 [BiliSubtitle.LookalikeCue]。
 */
data class BiliSubtitleCue(
    val fromMs: Long,
    val toMs: Long,
    val text: String,
    val music: Double = 0.0
)

/**
 * B 站视频字幕的解析与「字幕 → 歌词」判定。
 *
 * ## 为什么要有这个对象（v3.3.0 的来由）
 *
 * v3.1.0 的结论是「视频音轨在 B 站上**没有歌词数据源**」，于是视频曲目一律显示
 * 「暂无歌词」。那个结论**只对了一半**：漏掉了**字幕（subtitle）**这条数据源。
 *
 * 实测（2026-10，本仓库自己的 curl，登录态 `isLogin=true`）：
 *
 * | 事实 | 实测值 |
 * |---|---|
 * | `player/wbi/v2` 能列出字幕 | 是，且**只需要 `bvid` + `cid`**（不必先问 `view` 拿 `aid`） |
 * | 匿名能否拿到字幕**列表** | 能，但**列表恒为空** —— 拿到字幕**需要登录态** |
 * | 字幕从哪来 | 绝大多数是 **`ai-zh`（B 站 AI 自动生成）**，不是 UP 主手传的 CC 字幕 |
 * | 字幕正文里有歌词吗 | **有，而且是带时间轴的逐句歌词** |
 * | 抽样覆盖率 | 12 条音乐视频：**8 条有字幕**，其中 **5 条判定为歌词** |
 *
 * 真实样本（`BV1cN4y167BJ`＝赵雷《成都》官方 MV，`aid=877869754 cid=1381738635`）：
 * ```
 * [  7.94-  8.96] 昨晚他来了吗          ← music = 0.0     （MV 开头的人声对白）
 * [ 59.46- 66.84] ♪ 让我掉下眼泪的不止昨夜的酒 ♪   ← music ≈ 1.0（歌词）
 * [ 67.30- 74.82] ♪ 让我依依不舍的不止你的温柔 ♪
 * ```
 *
 * ## 判据：用 `music` 字段，不是用「有没有 ♪」
 *
 * 直觉做法是「正文里含 `♪` 就算歌词」。**实测它在本样本上与 `music` 字段 100% 一致
 * （46/46）**，所以两者都能用 —— 但 `music` 是**服务端给的结构化置信度**，
 * 而 `♪` 是**正文里的约定字符**，后者会被任何一次字幕格式调整打断。
 * 因此主判据取 `music`，`♪` 只作为**兜底**（见 [isLyricCue]）：
 * 手传 CC 字幕可能不带 `music` 字段，但会带 `♪`；两条一起认，覆盖面严格更大。
 *
 * ## 为什么必须过滤，而不是「把字幕全当歌词」
 *
 * 音乐视频的字幕**不只有歌词**：MV 有开场对白/采访，合集视频有主持人串场。
 * 把它们当歌词会让歌词面板在歌还没开始时显示一段对白，且时间轴整体错位。
 * 过滤按 [LYRIC_RATIO_MIN] 的**比例**而不是绝对条数，因为两种形态都要能用：
 * 一首 4 分钟的歌约 40~60 条，而「144 首金曲合集」实测有 **6997 条**。
 *
 * ## 已知边界（如实记录，不要在文档里把它们写成已解决）
 *
 * - **合集视频会串歌**：`BV1nEa46oEtk`（5160 条）/ `BV1BKhH6qEh9`（6997 条）这类
 *   「150 首歌曲合集」整体是歌词，但它是**几十首歌拼在一起**的连续时间轴。
 *   本模块判定它会返回歌词，而那份歌词对**当前播放的那一首**是从头错到尾的。
 *   修它需要「按曲目切分时间轴」，而那要求知道每首歌的起止 —— B 站不提供，
 *   且本仓库的 B 站曲目是**按视频**建模的（`bv:<bvid>:<cid>`），没有「合集内第几首」这个概念。
 *   所以这一条**本版不做**，只在 [LYRIC_CUE_MAX] 处做了有界保护（不是修复）。
 */
object BiliSubtitle {

    /**
     * 歌词条目的上限。
     *
     * 存在的理由是**有界**（仓库硬规则：任何循环/累积路径都要有熔断），
     * 而不是「正常歌不会超过」。实测合集视频有 **6997** 条，正常单曲 40~70 条。
     * 取 1200 的意义：单曲**绝无可能**触及（20 分钟的歌也就 400 条左右），
     * 而合集视频能被截断在「一份还算合理的 LRC」而不是把整首歌词面板灌满。
     *
     * ⚠️ 这是**有界保护，不是正确性修复** —— 被截断的合集仍然是从头错到尾的歌词，
     * 见类文档「已知边界」。
     */
    const val LYRIC_CUE_MAX = 1200

    /**
     * 判定为歌词所需的最低占比。
     *
     * 实测两类样本的分部是**两极**的，所以阈值不敏感：
     * - 真歌词：`BV1BDk2YCEHF` 58/58 = 1.00、`BV1DoaA6CErB` 955/963 = 0.99、
     *   `BV1ws4y1j7jY` 21/24 = 0.875、`BV1cN4y167BJ` 46/52 = 0.885；
     * - 人声对白（MV 合集）：`BV1mV411y78a` 0/71 = 0.00、`BV1XW411w7TB` 0/42 = 0.00、
     *   `BV1fx411371v` 0/47 = 0.00。
     *
     * 取 0.5 而不是更紧的值，是为了给「MV 前半段对白、后半段唱歌」这种形态留出余地。
     */
    const val LYRIC_RATIO_MIN = 0.5

    /**
     * 判定为歌词所需的**最少**条目数。
     *
     * 防止「一条 `♪` 的短预告」被当成一首歌的歌词。3 条 ≈ 一句副歌的最低限度。
     */
    const val LYRIC_CUE_MIN = 3

    /**
     * 解析字幕正文 JSON。
     *
     * B 站返回的形状（实测，`aisubtitle.hdslb.com`）：
     * ```json
     * { "font_size":0.4, "type":"json", "lang":"zh",
     *   "body":[ {"from":7.94,"to":8.96,"sid":1,"location":2,"content":"昨晚他来了吗","music":0.0}, … ] }
     * ```
     *
     * 容错契约（与 `BiliParse` 同一条纪律：**解析函数不发网络、不抛异常**）：
     * - 整体不是 JSON / 没有 `body` / `body` 不是数组 ⇒ 返回空列表；
     * - 单条缺 `content` 或正文空白 ⇒ **跳过这一条**（不产出一条空歌词行）；
     * - 单条缺 `from` ⇒ 跳过（没有起始时间的歌词无法定位）；
     * - `to` 缺失时取 `from`（下游只用它判断「是不是纯音乐间奏」，不会拿它当行尾）。
     */
    fun parseCues(body: String?): List<BiliSubtitleCue> {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("body") ?: return emptyList()
        val out = ArrayList<BiliSubtitleCue>(minOf(arr.length(), LYRIC_CUE_MAX))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val content = o.optString("content").trim()
            if (content.isEmpty()) continue
            if (!o.has("from")) continue
            val fromMs = secondsToMs(o.optDouble("from", -1.0))
            if (fromMs < 0L) continue
            val toMs = secondsToMs(o.optDouble("to", o.optDouble("from", 0.0)))
            out.add(
                BiliSubtitleCue(
                    fromMs = fromMs,
                    toMs = if (toMs >= fromMs) toMs else fromMs,
                    text = content,
                    music = o.optDouble("music", 0.0),
                )
            )
        }
        return out
    }

    /**
     * 这一条字幕是不是歌词。
     *
     * 主判据是 `music > 0`（服务端结构化置信度）；兜底判据是正文含 `♪`。
     * 两者取**或** —— 覆盖面严格更大，且两条在实测样本上从未互相矛盾。
     *
     * ⚠️ 阈值取 `MUSIC_MIN` 而不是 `== 1.0`：实测值是 `0.9999998387097035` 这种
     * **浮点近似 1**。写成 `music == 1.0` 会在真机上一条都判不出来 ——
     * 这是「用浮点等值比较当判据」的典型翻车形状（仓库已有同类教训）。
     */
    fun isLyricCue(cue: BiliSubtitleCue): Boolean =
        cue.music > MUSIC_MIN || cue.text.contains(NOTE_MARK)

    /**
     * 从字幕里挑出歌词，并判断「这到底是不是一份歌词」。
     *
     * 返回的列表**保持时间升序**（B 站字幕本来就是升序，这里不做排序，
     * 避免把「服务端顺序异常」这种真实故障掩盖成一次静默修复）。
     *
     * 不满足 [LYRIC_CUE_MIN] / [LYRIC_RATIO_MIN] 时返回**空列表** ——
     * 空列表的语义是「这份字幕不是歌词」，由调用方决定怎么向用户表达
     * （见 `BiliSourceProvider.fetchLyric`）。
     */
    fun lyricCues(cues: List<BiliSubtitleCue>): List<BiliSubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        val kept = cues.filter { isLyricCue(it) }
        if (kept.size < LYRIC_CUE_MIN) return emptyList()
        // 占比按**原始**条数算（分母是这份字幕的全部条目）。
        if (kept.size.toDouble() / cues.size.toDouble() < LYRIC_RATIO_MIN) return emptyList()
        return if (kept.size > LYRIC_CUE_MAX) kept.subList(0, LYRIC_CUE_MAX).toList() else kept
    }

    /**
     * 把字幕转成 **LRC 正文**，交给既有的 `LrcParser`（不新写解析器）。
     *
     * 两件事：把 `♪` 标记剥掉（那是 B 站的唱歌标记，不是歌词内容），
     * 以及把毫秒写成 `[mm:ss.xx]`。**不做**下面这些，且每条都有理由：
     *
     * - **不**合并相邻行：AI 字幕的断句已经是「一句一行」，
     *   合并会把「让我依依不舍的不止你的温柔」这种长句再拼长，反而更难读；
     * - **不**生成 `endMs`：LRC 语法里没有行尾时间，硬塞会污染 `LrcLine` 的字段语义
     *   （`endMs` 目前只有 TTML 会填，见 `TtmlScanner` 的说明）；
     * - **不**去重：副歌重复出现是**正确**的歌词形态，合并会把第二遍副歌删掉
     *   （时间轴还在，歌词没了）。
     *
     * 正文里若出现换行，**按行拆成多条**同时间戳的 LRC 行 —— 这正好用上 v3.3.0
     * 在 `LrcParser` 修掉的「一行多时间戳」能力的逆形态。
     */
    fun lyricLrc(cues: List<BiliSubtitleCue>): String {
        val sb = StringBuilder()
        for (cue in cues) {
            val text = stripNoteMark(cue.text)
            if (text.isEmpty()) continue
            val stamp = formatStamp(cue.fromMs)
            for (line in text.split('\n')) {
                val t = line.trim()
                if (t.isEmpty()) continue
                sb.append(stamp).append(t).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 字幕 URL 的协议补全。
     *
     * 实测服务端返回的是**协议相对** URL：
     * `//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/877869754…?auth_key=…`。
     * 直接交给 OkHttp 会抛 `IllegalArgumentException: Expected URL scheme` ——
     * 一个只在真机上出现、单测里看不见的崩溃形状。所以这条转换必须是纯函数 + 有单测。
     *
     * 未知形状返回**空串**（而不是猜一个 host）：猜错会变成一个静默的 404，
     * 而空串会让 `BiliApi.subtitleBody` 直接返回 null（= 「取不到」），语义诚实。
     */
    fun normalizeSubtitleUrl(raw: String?): String {
        val u = raw?.trim().orEmpty()
        if (u.isEmpty()) return ""
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://") || u.startsWith("https://") -> u
            // 只认路径，按字幕 CDN 补全 —— 不猜其它 host。
            u.startsWith("/") -> "$SUBTITLE_CDN_BASE$u"
            else -> ""
        }
    }

    private const val SUBTITLE_CDN_BASE = "https://aisubtitle.hdslb.com"

    /**
     * `♪`（U+266A）与 `♫`（U+266B）。AI 字幕用它标出「这一段是唱的」。
     */
    private const val NOTE_MARK = "♪"

    /**
     * 浮点**秒** → 整数毫秒。**必须四舍五入，不能截断。**
     *
     * 这不是洁癖，是实测过的差一毫秒：
     * `1.001 * 1000.0` 在双精度下是 `1000.9999999999999`，`.toLong()` 得到 **1000**。
     * 这一类值占千分位精度取值的**大约一半**（所有奇数毫秒：
     * 1.001/1.003/1.005/… 全部命中），而 B 站的 AI 字幕恰好大量使用毫秒级精度
     * （实测 `7.94`、`59.46`、`66.84` 是巧合干净的，但 `from`/`to` 是任意浮点）。
     *
     * 差 1ms 本身用户看不出来，但它是**系统性单向偏移**：歌词整体早 1ms，
     * 且在「LRC 与原字幕逐条 diff」这类排查里会表现为几千条不一致，
     * 把真正的缺陷淹没掉。所以在这里一次修干净。
     */
    internal fun secondsToMs(seconds: Double): Long =
        if (seconds.isNaN() || seconds.isInfinite()) 0L else Math.round(seconds * 1000.0)

    /** `music` 字段的判定阈值：> 0 即可，但用区间中点避免浮点等值比较。 */
    private const val MUSIC_MIN = 0.5

    /**
     * 剥掉正文里的演唱标记与它留下的空白。
     *
     * 实测形态是 `♪ 让我掉下眼泪的不止昨夜的酒 ♪`（前后各一个，中间还有空格），
     * 目标是 `让我掉下眼泪的不止昨夜的酒`。
     * 正文中间出现的 `♪` 也一并剥掉 —— 它是标记不是歌词，留着会被用户看到。
     */
    internal fun stripNoteMark(raw: String): String =
        raw.replace(NOTE_MARK, "").replace("♫", "").trim()

    /**
     * 毫秒 → `[mm:ss.xx]`。
     *
     * 用**两位**厘秒与既有 LRC 源（网易云/QQ）一致 —— `LrcParser` 对 2 位与 3 位都认，
     * 但保持同一形状能让「同一首歌两个来源的 LRC 做 diff」这类排查少一个变量。
     * 分钟数**不截断到 2 位**：现场曲目会超过 99 分钟（`[78:30.00]` 是合法输入，
     * `LrcParser` 也按两位读，见它的单测）。
     */
    internal fun formatStamp(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalCs = safe / 10L
        val cs = totalCs % 100L
        val totalSec = totalCs / 100L
        val s = totalSec % 60L
        val m = totalSec / 60L
        return "[%02d:%02d.%02d]".format(m, s, cs)
    }
}
