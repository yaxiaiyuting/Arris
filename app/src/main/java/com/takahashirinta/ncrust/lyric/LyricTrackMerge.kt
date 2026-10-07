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
 * 一条副文本轨（译文 / 音译）**最终由哪些源拼成**（v1.9.2）。
 *
 * [TTML] / [NETEASE] 表示整条轨只有这一个源；[MIXED] = TTML 的轨只覆盖了一部分行，
 * 缺口由 ncm 那一份补上（两源文本在同一条轨里共存）。它只是**观测字段**：
 * 写进歌词缓存供归因，不参与任何决策 —— 显示哪一份永远由 [LyricTrackMerge] 当场算。
 */
enum class LyricTrackSource { TTML, NETEASE, MIXED }

/** 写进 [LyricsCache] 的稳定标记（枚举名）。缓存侧只当观测字段读，不反解。 */
val LyricTrackSource.cacheTag: String get() = name

/**
 * 合并后的一条轨道：内容 + 来源；[lines] 为空时 [source] 必为 null。
 *
 * [lines] 里的每一行都是可以直接交给渲染层的 [LrcLine]：**时间戳已经落在主轨的时间轴上**
 * （见 [LyricTrackMerge]），所以 LyricsView 那句 `translatedLyrics.associateBy { it.timeMs }`
 * 不需要任何改动就能把译文挂到正确的原句下面。
 */
@Immutable
data class LyricTrack(
    val lines: List<LrcLine> = emptyList(),
    val source: LyricTrackSource? = null,
)

/**
 * 译文轨 / 音译轨的**分轨合并**（v1.9.2）。**纯逻辑**：无 Android、无 IO、无状态，JVM 可单测。
 *
 * ## 它修的是什么
 *
 * v1.9.0 的 PlayerViewModel.applyTtmlLyrics() 在 TTML 胜出时**整体覆盖** translatedLyrics：
 *
 * `
 * translatedLyrics.value = doc.translations.filter { it.timeMs in lineTimes }
 * `
 *
 * TTML 那份没有译文（x-translation 一处都没有）时这个列表就是空的 ⇒ 第一相刚从 ncm 拿到的
 * tlyric 被**整轨清空**。实测（tools/research-ttml/data/ 的 667 首池 + 42 份 TTML）：
 * 该判据命中 **1 首** = 22704409 DAY BY DAY（TTML 79 行逐字、0 翻译；ncm tlyric 79 行）。
 * 音译轨同理，且更普遍：42 份 TTML 里只有 4 首带 x-roman，而 ncm romalrc 在日文 90% / 韩文 100%。
 *
 * ## 三条规则（为什么是这样）
 *
 * 1. **TTML 行优先**：TTML 那份能落到主轨时间轴上的行**原样保留**（顺序、内容、重复行都不动）。
 *    所以「TTML 有译文」的歌输出与 v1.9.0 **逐行相同** —— 这是「有翻译时行为不变」的保证，
 *    也是本版不动渲染层的前提。
 * 2. **缺口按文本/行序回退**：主轨里**没有任何 TTML 副文本**的行才算缺口。缺口用
 *    [YrcAligner.lcsPairs]（归一化去空白 + 最长公共子序列，保序）在**两源主轨的行文本**上配对，
 *    配对成功且 ncm 那一行确有内容时才补一行，并且**把时间戳改写成主轨那一行的时间戳**。
 *    绝不能按时间戳配对：两源行时间中位差 +169ms、单曲 −551ms~+561ms（调研报告 §5.9），
 *    按时间戳会把译文配到别的句子上。
 * 3. **逐行丢弃**：文本对不上（两源分行方式不同、多一行元信息、翻译缺失）就是**这一行没有副文本** ——
 *    不猜、不过桥、不按位置硬塞。与 v1.5.0 以来「宁可没有逐字，也不给错位高亮」是同一条语义。
 *
 * ## 为什么是「逐行」而不是「整轨二选一」
 *
 * 轨级二选一在实测数据上会**丢东西**：1959528822 紫荆花盛开 的 TTML 有 16 行 x-roman、
 * ncm 有 41 行 romalrc（且 TTML 那 16 行文本与 ncm 逐字相同，只是子集）。轨级规则会取 16 行、
 * 把 ncm 多出来的部分丢掉 —— 那正是本版要修的「丢音译」。逐行合并的收益是**严格不劣**：
 * TTML 覆盖满时一行不加、一行不减；有缺口时只会多出行。
 *
 * ## 不变量
 *
 * - 输出的每一行 timeMs 都来自 [main]（副文本轨自己的时间戳一律不外泄）；
 * - [main] 里每个时间戳最多产出一行副文本（重复时间戳只保留第一次配对）；
 * - 输入的四个列表**只读不改**（[LrcLine] 是 @Immutable，函数无副作用）；
 * - 空输入永远返回空轨（[LyricTrack.lines] 空 ⇒ [LyricTrack.source] = null），
 *   不产生「空轨 + 有来源」这种假信息。
 */
object LyricTrackMerge {

    /**
     * 合并一条副文本轨。
     *
     * @param main 胜出主轨（TTML 胜出时是 TtmlDoc.lines），必须已按时间升序。
     * @param ttmlTrack TTML 侧同名轨道（TtmlDoc.translations / TtmlDoc.romans）；
     *   没有这一轨（或该版本不取）时传 emptyList()。
     * @param neteaseMain ncm 主轨（LRC 行，可能带 yrc 逐字 —— 逐字不参与文本对齐）。
     * @param neteaseTrack ncm 侧同名轨道（tlyric / romalrc 的解析结果），时间戳与 [neteaseMain] 同源。
     */
    fun merge(
        main: List<LrcLine>,
        ttmlTrack: List<LrcLine>,
        neteaseMain: List<LrcLine>,
        neteaseTrack: List<LrcLine>,
    ): LyricTrack {
        if (main.isEmpty()) return LyricTrack()

        // 主轨时间戳集合。渲染层是按 timeMs 精确 join 的（LyricsView），所以副文本的「可挂载」
        // 就等于「这个时间戳在主轨里存在」—— v1.9.0 的 filter 也是同一条判据。
        val mainTimes = HashSet<Long>(main.size * 2)
        for (line in main) mainTimes.add(line.timeMs)

        // 规则 1：TTML 行优先，顺序与重复行原样保留。
        val fromTtml = ttmlTrack.filter { it.timeMs in mainTimes }
        val covered = HashSet<Long>(fromTtml.size * 2)
        for (line in fromTtml) covered.add(line.timeMs)

        // 没有缺口 ⇒ 输出与 v1.9.0 逐行相同（提前返回，一行不多一行不少）。
        if (covered.size >= mainTimes.size) {
            return if (fromTtml.isEmpty()) LyricTrack() else LyricTrack(fromTtml, LyricTrackSource.TTML)
        }
        // ncm 那一侧根本没有这一轨：回退无从谈起，缺口就让它空着。
        if (neteaseTrack.isEmpty() || neteaseMain.isEmpty()) {
            return if (fromTtml.isEmpty()) LyricTrack() else LyricTrack(fromTtml, LyricTrackSource.TTML)
        }

        // 规则 2：ncm 轨按**文本/行序**回退。lcsPairs 保序、只看归一化后的文本是否完全相等，
        // 与 v1.6.0 逐字对齐同一份实现（不新写第二套对齐器）。
        val pairs = YrcAligner.lcsPairs(
            main.map { it.text },
            neteaseMain.map { it.text },
        )
        // ncm 副文本按时间戳 join 到它自己的主轨上 —— 这一步复刻渲染层现有的配对方式
        // （tlyric/romalrc 与 lrc 是同一份资产、时间戳同刻），不是跨源配对。
        val neteaseTextByTime = HashMap<Long, String>(neteaseTrack.size * 2)
        for (line in neteaseTrack) {
            if (line.text.isNotEmpty()) neteaseTextByTime[line.timeMs] = line.text
        }

        val fills = ArrayList<LrcLine>(minOf(pairs.size, main.size))
        val filled = HashSet<Long>(pairs.size * 2)
        for ((mainIndex, neteaseIndex) in pairs) {
            val mainLine = main.getOrNull(mainIndex) ?: continue
            // TTML 已经给这一行配了副文本（覆盖优先），跳过。
            if (mainLine.timeMs in covered) continue
            // 同一主轨时间戳只填一次（主轨若有重复时间戳，第一次配对赢）。
            if (!filled.add(mainLine.timeMs)) continue
            // 规则 3：这一行在 ncm 那份里没有内容 ⇒ 逐行丢弃，不填、不猜。
            val neteaseLine = neteaseMain.getOrNull(neteaseIndex) ?: continue
            val text = neteaseTextByTime[neteaseLine.timeMs] ?: continue
            if (text.isBlank()) continue
            // 关键：时间戳取主轨那一行的，副文本轨自己的时间戳一律不用。
            fills += LrcLine(timeMs = mainLine.timeMs, text = text)
        }

        if (fromTtml.isEmpty() && fills.isEmpty()) return LyricTrack()
        val lines = if (fromTtml.isEmpty()) {
            fills
        } else {
            // 两段各自有序，合并后按时间重排（稳定排序，同刻行保持各自原有先后）。
            (fromTtml + fills).sortedBy { it.timeMs }
        }
        val source = when {
            fills.isEmpty() -> LyricTrackSource.TTML
            fromTtml.isEmpty() -> LyricTrackSource.NETEASE
            else -> LyricTrackSource.MIXED
        }
        return LyricTrack(lines, source)
    }
}
