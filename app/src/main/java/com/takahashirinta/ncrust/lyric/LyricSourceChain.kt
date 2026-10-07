/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

/**
 * 歌词来源（v1.9.0）。
 *
 * 三者的能力不同，所以回退链的判据不是「谁先拿到」而是「谁能给出逐字」：
 * - [YRC]：ncm 自身的逐字轨，覆盖率不完整（新歌大多没有），与 LRC 行序一一对应；
 * - [TTML]：AMLL TTML DB 的补充源，能补上 yrc 缺失的曲目，但同样存在只有整句没有 span 的投稿；
 * - [LRC]：整行歌词，任何一首歌都至少可能有这一份，是回退链的兜底，永远排最后。
 */
enum class LyricSourceKind { YRC, TTML, LRC }

/**
 * 回退链偏好（v1.9.0）。
 *
 * @property ttmlEnabled 是否允许取用 TTML。关掉时 TTML 不仅排在最后，而是**根本不进
 *   [LyricSourceChain.order]** —— 用户关掉的源不该继续发网络请求，否则「关了还在拉」既费流量
 *   又让隐私开关形同虚设。
 * @property ttmlFirst true = TTML 优先（对齐 SPlayer 的 auto 策略：谁的逐字质量好用谁的）；
 *   false = YRC 优先（ncm 原生逐字与整行文本同源、时间戳口径一致，不希望被第三方投稿覆盖时选它）。
 */
data class LyricSourcePrefs(
    val ttmlEnabled: Boolean = true,
    val ttmlFirst: Boolean = true,
)

/**
 * 一个**已经解析完**的候选源（v1.9.0）。
 *
 * 之所以要调用方先解析、这里只做决策：解析要碰网络与 XML，决策却是纯逻辑。
 * 把决策需要的全部事实压缩成 [lineCount] / [hasWordLevel] 两个字段之后，
 * 回退链的每条规则都能在 JVM 单测里复现，不需要 mock 网络也不需要真机。
 *
 * @property lineCount 解析出的歌词行数；0 表示这个源实际是空的（解析失败、纯音乐、只有元数据）。
 * @property hasWordLevel 是否真的带逐字。**必须由解析器判定「有没有词」，不能拿「解析成功」冒充** ——
 *   只有整句、没有逐字 span 的 TTML 投稿用 [lineCount] 是看不出来的，拿它替换 LRC 只会白白降级。
 */
data class LyricCandidate(
    val kind: LyricSourceKind,
    val lineCount: Int,
    val hasWordLevel: Boolean,
)

/**
 * 「YRC → TTML → LRC」三级回退链的纯决策逻辑（v1.9.0）。
 *
 * 本对象不做 IO、不碰 Android，只有两个纯函数：[order] 决定尝试顺序，[pick] 在拿到手的候选里选一个。
 * 调用方的契约是 **先按 [order] 取数、再调 [pick]**，这样「不该用的源」在取数阶段就被拦掉了；
 * [pick] 只认传入列表的顺序（它拿不到 [LyricSourcePrefs]），所以传入之前必须已按 [order] 排好，
 * 顺序之外的 kind 不得进入列表。
 */
object LyricSourceChain {

    /**
     * 该偏好下的尝试顺序，自左向右优先级递减。
     *
     * [LyricSourceKind.LRC] 恒排最后：它是行级源，一旦被选中就永远拿不到逐字，
     * 所以只能在没有更细粒度的源可用时兜底，不能因为「先返回」而抢占。
     */
    fun order(prefs: LyricSourcePrefs): List<LyricSourceKind> = when {
        !prefs.ttmlEnabled -> listOf(LyricSourceKind.YRC, LyricSourceKind.LRC)
        prefs.ttmlFirst -> listOf(LyricSourceKind.TTML, LyricSourceKind.YRC, LyricSourceKind.LRC)
        else -> listOf(LyricSourceKind.YRC, LyricSourceKind.TTML, LyricSourceKind.LRC)
    }

    /**
     * 在按 [order] 排好序的候选里选一个，全不可用返回 null。
     *
     * 两轮扫描，而不是「按顺序取第一个有行的」：
     *  1. 先找 lineCount > 0 且带逐字的第一个 —— 逐字是这一版唯一值得升级的东西；
     *  2. 都没有，再找 lineCount > 0 的第一个；
     *  3. 再没有 → null（调用方据此走「暂无歌词」，绝不能拿空文档顶替 LRC）。
     *
     * **为什么逐字要跨源优先，而不是按 order 逐源判断**：第一轮会把「排后面的逐字源」捞到
     * 「排前面的整行源」之前。比如 TTML 只有整句、YRC 有逐字时，即使 TTML 排在前面，
     * 也该选 YRC —— 这正是「只要还有任何有逐字的候选可用，就不该选 LRC」这条语义的推广：
     * 宁可要一个位置靠后的逐字源，也不要一个位置靠前的整行源把逐字能力丢掉。
     */
    fun pick(ordered: List<LyricCandidate>): LyricCandidate? =
        ordered.firstOrNull { it.lineCount > 0 && it.hasWordLevel }
            ?: ordered.firstOrNull { it.lineCount > 0 }
}
