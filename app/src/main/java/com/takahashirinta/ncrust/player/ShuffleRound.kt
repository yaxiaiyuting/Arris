/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import kotlin.random.Random

/**
 * v2.0.0 · T1-C：乱序播放的**轮次生成**（纯逻辑，JVM 可单测）。
 *
 * ## 为什么需要这个文件
 *
 * 用户报告「随机的逻辑有问题，容易同一语种扎堆，刚刚随机到十首日语歌」。真机调研结论：
 *
 *  1. **洗牌算法本身没有缺陷** —— Kotlin `shuffled()` 落到 `java.util.Collections.shuffle`，
 *     是标准无偏 Durstenfeld Fisher-Yates（`swap(i-1, rnd.nextInt(i))`），
 *     不存在 `swap(i, random(0,n))` 那种有偏写法，也没有固定/秒级种子；
 *  2. 但**乱序模式没有被记住**（`playMode` 只存在 `remember` 里）：任何 Activity 重建
 *     （低内存回收、"不保留活动"、主题/语言切换）都会让它静默回到"顺序循环"，
 *     于是队列按**原始顺序**播 —— 网易歌单/每日推荐的原始顺序本来就常按语种/地区/专辑成块，
 *     这才是"十首日语连播"最可能的来源（见 MainActivity 里 `playMode` 的持久化修复）；
 *  3. **轮末语义**：旧实现在一轮播完后要么重洗整池（新序列可能立刻重播刚播完的那首），
 *     要么把"当前曲钉在下标 0"当成下一首（无缝路径直接重播当前曲）。
 *
 * 本文件负责 3 与「防扎堆」的确定性部分，全部是纯函数；乱序模式的持久化在
 * `PlaybackStateManager` / `MainActivity`。
 *
 * ## 防扎堆的口径（**不是**语种均衡）
 *
 * 用**主艺人 id**（`SongItem.artists.first().id`）作为分散键：相邻两首不来自同一主艺人。
 * 语言是伪概念（ncm 没有语言字段），按语种均衡需要猜语言，本 fork 明确不做；
 * 而"同一艺人连播"是真实存在的观感问题（歌单里同艺人常成块出现），且零网络成本。
 *
 * **必须能优雅退化**：池子太小、整张专辑都是同一艺人时约束不可满足 ——
 * [disperse] 在找不到可交换位置时**放弃这一处**，绝不死循环、绝不为了满足约束而丢歌。
 */
object ShuffleRound {

    /**
     * 生成一轮乱序序列。
     *
     * `currentIndex` **钉在下标 0**：这是本应用既有的事实契约（`shuffledIndices[0]` 恒等于
     * 正在播的那首），预载/手动下一首/无缝过渡都依赖它。调用方播放下标 1..n-1，
     * 一轮播完后再生成新的一轮。
     *
     * @param size 队列长度
     * @param currentIndex 当前正在播放的下标（会被夹到合法范围）
     * @param keyOf 分散键：返回 null 表示"这首不参与约束"（缺艺人信息时）
     * @param random 可注入，便于单测复现
     */
    fun newRound(
        size: Int,
        currentIndex: Int,
        keyOf: (Int) -> Long?,
        random: Random = Random.Default,
    ): List<Int> {
        if (size <= 0) return emptyList()
        val current = currentIndex.coerceIn(0, size - 1)
        val rest = (0 until size).filter { it != current }.shuffled(random)
        return disperse(listOf(current) + rest, keyOf, random)
    }

    /**
     * 防扎堆排列：把「相邻两首同主艺人」的相邻对拆开。
     *
     * 算法是经典的「最多剩余优先」（同 LeetCode `reorganizeString` 的贪心）：
     * 每一步从**剩余数量最多的、且与上一首不同艺人**的桶里随机取一首；
     * 同数量桶之间随机打破平局，桶内取哪一首也随机 —— 所以结果依旧是随机的，
     * 只是把"同艺人挤在一起"这件事压掉。
     *
     * **下标 0 保持不动**：本应用的契约是 `shuffledIndices[0]` 恒等于正在播的那一首，
     * 预载/无缝过渡都依赖它，所以只重排 1..n-1。
     *
     * **优雅退化**：若某一步只剩"与上一首同艺人"的桶（池子太小 / 整张专辑同一艺人），
     * 就照取不误 —— 绝不死循环、绝不丢歌、绝不为了满足约束而无限尝试。
     * `keyOf` 返回 null 表示"这首不参与约束"（缺艺人信息），它永远不会与任何东西相撞。
     *
     * `order.size < 3` 直接原样返回（两首歌之间没有可操作空间）。
     */
    fun disperse(
        order: List<Int>,
        keyOf: (Int) -> Long?,
        random: Random = Random.Default,
    ): List<Int> {
        if (order.size < 3) return order
        val head = order[0]
        val buckets = LinkedHashMap<Long?, MutableList<Int>>()
        for (i in 1 until order.size) {
            val idx = order[i]
            buckets.getOrPut(keyOf(idx)) { mutableListOf() }.add(idx)
        }
        val out = ArrayList<Int>(order.size)
        out.add(head)
        var prev: Long? = keyOf(head)
        var remaining = order.size - 1
        while (remaining > 0) {
            var best: MutableList<Int>? = null
            var bestKey: Long? = null
            var bestSize = 0
            var ties = 0
            for ((key, list) in buckets) {
                if (list.isEmpty()) continue
                // null 桶（缺艺人信息）永不与 prev 相撞
                if (prev != null && key == prev) continue
                if (best == null || list.size > bestSize) {
                    best = list; bestKey = key; bestSize = list.size; ties = 1
                } else if (list.size == bestSize) {
                    ties++
                    if (random.nextInt(ties) == 0) { best = list; bestKey = key }
                }
            }
            if (best == null) {
                // 约束不可满足：只剩上一首的同艺人。退化取任意非空桶，保证仍然输出 n 首。
                val entry = buckets.entries.firstOrNull { it.value.isNotEmpty() } ?: break
                best = entry.value
                bestKey = entry.key
            }
            val list = best
            val pick = random.nextInt(list.size)
            out.add(list[pick])
            list[pick] = list[list.size - 1]
            list.removeAt(list.size - 1)
            prev = bestKey
            remaining--
        }
        return out
    }
}
