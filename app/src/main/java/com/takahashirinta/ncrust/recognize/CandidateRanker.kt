/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.recognize

/**
 * 识别结果 → 跨源候选排序（v3.8.0 · 听歌识曲）。**纯逻辑，JVM 可单测。**
 *
 * ## 为什么排序必须独立于搜索
 *
 * 聚合搜索只负责"尽量多给"，它不知道识别侧的可信度；而识别侧知道歌名/艺人/时长，
 * 却不知道版权与会员。把两边拼在一起是唯一能同时避免两类错误的做法：
 *
 * - **把翻唱/Live/伴奏当成原曲**（听感事故）→ 用 [editionKinds] 硬判据；
 * - **把"搜到了"当成"能播"** → 用 `availability` 参与排序，但不直接决定"匹配与否"。
 *
 * ## 判级规则（可被单测逐条钉住）
 *
 * | 条件 | 等级 |
 * |---|---|
 * | 版本性质不等（Live vs 原版）| **REJECT**（不是"低分"，是不给） |
 * | 歌名高度相似 + 艺人相似 + 时长差 ≤3 s | HIGH |
 * | 歌名高度相似 + （艺人相似 或 时长差 ≤3 s） | MEDIUM |
 * | 歌名相似 | LOW |
 * | 其余 | REJECT |
 */
object CandidateRanker {

    /** 时长容差：不同音源的同一首歌通常差 1–2 秒，超过 3 秒基本就是另一个版本。 */
    const val DURATION_TOLERANCE_MS = 3_000L

    fun rank(match: RecogMatch, candidates: List<RecogCandidate>): List<RankedCandidate> =
        candidates.map { c -> grade(match, c) }
            .filter { it.confidence != MatchConfidence.REJECT }
            .sortedWith(
                // 先等级、再"能播优先"，最后按时长接近程度
                compareBy<RankedCandidate> { it.confidence.ordinal }
                    .thenByDescending { availabilityRank(it.candidate.availability) }
                    .thenBy { it.score }
                    .thenByDescending { it.score }
            )

    fun grade(match: RecogMatch, candidate: RecogCandidate): RankedCandidate {
        val titleSim = similarity(normalize(match.title), normalize(candidate.title))
        val artistSim = similarity(normalize(match.artist), normalize(candidate.artist))
        val durationDelta = if (match.durationMs > 0 && candidate.durationMs > 0) {
            kotlin.math.abs(match.durationMs - candidate.durationMs)
        } else {
            Long.MAX_VALUE
        }

        // ① 硬闸门：版本性质不一致直接拒（Live / 伴奏 / Remix 不算同一首）。
        if (!editionsCompatible(match, candidate)) {
            return RankedCandidate(candidate, MatchConfidence.REJECT, score = 0)
        }
        // ② 服务端明确说这是翻唱，而识别结果不是 ⇒ 拒（宁可没有）。
        if (candidate.versionTag == "COVER" && candidate.editionKinds.isEmpty()) {
            // 服务端判 COVER 但名字里没有版本词：仍然拒，避免把翻唱当原唱推给用户。
            return RankedCandidate(candidate, MatchConfidence.REJECT, score = 0)
        }

        val titleOk = titleSim >= 0.80f
        val artistOk = artistSim >= 0.70f
        val durationOk = durationDelta <= DURATION_TOLERANCE_MS

        val confidence = when {
            titleOk && artistOk && durationOk -> MatchConfidence.HIGH
            titleOk && (artistOk || durationOk) -> MatchConfidence.MEDIUM
            titleOk -> MatchConfidence.LOW
            else -> MatchConfidence.REJECT
        }

        // 分数只用于同等级内排序：越小越靠前。
        val score = ((1f - titleSim) * 100).toInt() +
            ((1f - artistSim) * 40).toInt() +
            (if (durationDelta == Long.MAX_VALUE) 30 else (durationDelta / 100).toInt())
        return RankedCandidate(candidate, confidence, score)
    }

    /** 版本性质是否兼容：识别侧没有版本信息时，只接受"候选也没有版本标记"的原版。 */
    private fun editionsCompatible(match: RecogMatch, candidate: RecogCandidate): Boolean {
        // 识别侧不带 edition 信息 ⇒ 只接受干净的原版候选
        val candidateEditions = candidate.editionKinds
        if (candidateEditions.isEmpty()) return true
        val titleHasEditionWord = candidateEditions.any { kind ->
            normalize(match.title).contains(kind)
        }
        // 歌名本身就带 Live/伴奏 字样 ⇒ 说明识别到的就是那个版本
        return titleHasEditionWord
    }

    /** 可播性排序权重：能播的排前面，但**不因此提升匹配等级**。 */
    private fun availabilityRank(availability: String): Int = when (availability) {
        "PLAYABLE" -> 3
        "UNKNOWN" -> 2
        "MEMBER_ONLY" -> 1
        else -> 0
    }

    /** 归一化：小写、去空白与常见标点、全角转半角（够用即可，深水区交给 `NameNormalizer`）。 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            when {
                ch.isWhitespace() -> {}
                ch in "（）【】《》「」\"'·-—–_.,，。!！?？:：;；/\\|()[]{}" -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 编辑距离相似度（1 - 距离/最长长度），对中文按字符比较即可。 */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return (1f - distance.toFloat() / longest).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.length]
    }
}
