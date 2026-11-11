/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import kotlin.random.Random

/** 生成固定当前曲为首项的乱序轮次，按主艺人分散相邻歌曲。 */
object ShuffleRound {

    /** `currentIndex` 固定在第一位，供切歌和无缝预载共用同一轮次。 */
    fun newRound(
        size: Int,
        currentIndex: Int,
        keyOf: (Int) -> Long?,
        random: Random = Random.Default,
    ): List<Int> {
        if (size <= 0) return emptyList()
        val current = currentIndex.coerceIn(0, size - 1)
        val order = ArrayList<Int>(size)
        for (index in 0 until size) {
            if (index != current) order.add(index)
        }
        order.shuffle(random)
        order.add(0, current)
        return disperse(order, keyOf, random)
    }

    /**
     * 其余曲目按艺人分桶，从上一首艺人之外的歌曲中等权抽取。
     * 剩余超过半数的可选艺人优先，以免后半程只能连播同一艺人。
     * 缺艺人信息的歌曲可连续播放。只剩同一艺人时从剩余歌曲中抽取。
     */
    fun disperse(
        order: List<Int>,
        keyOf: (Int) -> Long?,
        random: Random = Random.Default,
    ): List<Int> {
        if (order.size < 3) return order
        val head = order[0]
        val songsByArtist = LinkedHashMap<Long?, MutableList<Int>>()
        for (i in 1 until order.size) {
            val idx = order[i]
            songsByArtist.getOrPut(keyOf(idx)) { mutableListOf() }.add(idx)
        }

        val buckets = songsByArtist.entries.toList()
        val weights = BucketWeights(buckets)
        val out = ArrayList<Int>(order.size)
        out.add(head)
        var previous = keyOf(head)?.let { artist -> buckets.indexOfFirst { it.key == artist } } ?: -1
        for (remaining in (order.size - 1) downTo 1) {
            val half = remaining / 2
            // 超过一半的桶必定覆盖剩余歌曲按桶排列后的中间位置。
            val middle = weights.bucketAt(half)
            val majority = buckets[middle]
            val excluded = buckets.getOrNull(previous)?.value?.size ?: 0
            val chosen = when {
                middle != previous && majority.key != null && majority.value.size > half -> middle
                excluded == remaining -> previous
                else -> {
                    val rank = random.nextInt(remaining - excluded)
                    val excludedStart = if (excluded > 0) weights.countBefore(previous) else remaining
                    weights.bucketAt(if (rank < excludedStart) rank else rank + excluded)
                }
            }

            val bucket = buckets[chosen]
            val songs = bucket.value
            val pick = random.nextInt(songs.size)
            out.add(songs[pick])
            songs[pick] = songs.last()
            songs.removeAt(songs.lastIndex)
            weights.removeOne(chosen)
            previous = if (bucket.key == null) -1 else chosen
        }
        return out
    }

    /** 用树状数组查询前缀曲目数，并从曲目排名定位所属艺人桶。 */
    private class BucketWeights(buckets: List<Map.Entry<Long?, MutableList<Int>>>) {
        private val counts = IntArray(buckets.size + 1)
        private val highestStep = Integer.highestOneBit(buckets.size)

        init {
            for (slot in 1..buckets.size) {
                counts[slot] += buckets[slot - 1].value.size
                val parent = slot + Integer.lowestOneBit(slot)
                if (parent < counts.size) counts[parent] += counts[slot]
            }
        }

        fun countBefore(bucket: Int): Int {
            var slot = bucket
            var total = 0
            while (slot > 0) {
                total += counts[slot]
                slot -= Integer.lowestOneBit(slot)
            }
            return total
        }

        fun bucketAt(rank: Int): Int {
            var prefix = 0
            var remainingRank = rank
            var step = highestStep
            while (step > 0) {
                val next = prefix + step
                if (next < counts.size && counts[next] <= remainingRank) {
                    prefix = next
                    remainingRank -= counts[next]
                }
                step = step ushr 1
            }
            return prefix
        }

        fun removeOne(bucket: Int) {
            var slot = bucket + 1
            while (slot < counts.size) {
                counts[slot]--
                slot += Integer.lowestOneBit(slot)
            }
        }
    }
}
