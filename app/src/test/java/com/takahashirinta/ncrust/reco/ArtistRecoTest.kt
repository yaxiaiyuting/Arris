/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 · A：自动锚点排序/去重/截断的纯逻辑单测。
 *
 * 背景：S6 真机（SM-G9209 / Android 7.0）实测目标艺人 122618229 可推导出 9 个锚点，
 * 但原实现只按插入顺序落盘、且没有数量上限（3 种子曲 × 20 相似曲 → 最多 60 个）。
 * 锚点集就是「口味命中」的判定集，越大越容易误命中，所以按频次排序后截断。
 */
class ArtistRecoTest {

    private val target = 122618229L

    @Test
    fun `去重——同一艺人多次命中只出现一次`() {
        val hits = linkedMapOf(11L to 3, 22L to 3, 33L to 1)
        val out = ArtistReco.rankAnchors(hits, target)
        assertEquals(listOf(11L, 22L, 33L), out)
        assertEquals(out.size, out.toSet().size)
    }

    @Test
    fun `排序——按命中频次降序`() {
        // 首次出现顺序是 1,2,3，但频次 3 最高、1 最低
        val hits = linkedMapOf(1L to 1, 2L to 2, 3L to 3)
        assertEquals(listOf(3L, 2L, 1L), ArtistReco.rankAnchors(hits, target))
    }

    @Test
    fun `排序稳定——同频次保持首次出现顺序`() {
        val hits = linkedMapOf(7L to 2, 8L to 2, 9L to 5, 10L to 2)
        assertEquals(listOf(9L, 7L, 8L, 10L), ArtistReco.rankAnchors(hits, target))
        // 同一份数据重复调用结果必须完全一致（可复现、可与历史对比）
        repeat(5) { assertEquals(listOf(9L, 7L, 8L, 10L), ArtistReco.rankAnchors(hits, target)) }
    }

    @Test
    fun `截断——最多保留上限个数且优先高频`() {
        val hits = LinkedHashMap<Long, Int>()
        for (i in 1..60) hits[i.toLong()] = if (i == 60) 9 else 1
        val out = ArtistReco.rankAnchors(hits, target)
        assertEquals(ArtistReco.AUTO_MAX_ANCHORS, out.size)
        assertEquals(60L, out.first()) // 唯一的 9 次命中排第一
        assertEquals(60, hits.size)            // 候选 60 个
        assertEquals(40, hits.size - out.size) // 砍掉 40 个，只留置信度最高的 20 个
    }

    @Test
    fun `过滤——目标艺人自身与非法 id 不进锚点集`() {
        val hits = linkedMapOf(target to 99, 0L to 99, -5L to 99, 42L to 1)
        assertEquals(listOf(42L), ArtistReco.rankAnchors(hits, target))
    }

    @Test
    fun `空输入——返回空列表而不是抛异常`() {
        assertTrue(ArtistReco.rankAnchors(emptyMap(), target).isEmpty())
    }

    @Test
    fun `真实场景——S6 实测的 9 个锚点全部保留`() {
        // 2026-09-22 S6 真机（登录态）实测 /eapi/v1/artist/songs + simiSong 推导结果，
        // 这里给每个锚点补上频次后验证：9 < 上限，所以一个都不该被裁掉。
        val real = linkedMapOf(
            60952064L to 3, // PFJ_5（该账号收藏里命中，卡片因此显示）
            53432819L to 2, // 333xd
            1132066L to 2,  // Fayzz
            12003027L to 1, // 奈热乐队
            29878L to 1,    // Boris Brejcha
            93805L to 1,    // HIM
            99989L to 1,    // Sum 41
            13518632L to 1, // Charix
            99999L to 1     // System of a Down
        )
        val out = ArtistReco.rankAnchors(real, target)
        assertEquals(9, out.size)
        assertEquals(60952064L, out.first())
        assertEquals(real.keys.toList(), out) // 频次降序后恰好回到原顺序
    }
}