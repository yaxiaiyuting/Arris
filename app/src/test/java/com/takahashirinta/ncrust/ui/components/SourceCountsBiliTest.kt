/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：三源聚合搜索的单测（统计行 / 排序 / 筛选）。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.search.RankedSong
import com.takahashirinta.ncrust.search.SearchRanking
import com.takahashirinta.ncrust.search.TrackAccess
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.i18n.en
import com.takahashirinta.ncrust.ui.i18n.zhCN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SourceCounts` 的**第三源**（v3.1.0 · B）。
 *
 * 最重要的一条不变量：**B 站关闭时统计行与 v3.0.0 逐字相同**。
 * 它是「B 站接入不得破坏现有音源行为」（铁律 27）在用户可见层面的落点。
 */
class SourceCountsBiliTest {

    @Test
    fun `B 站关闭时统计行逐字不变`() {
        val counts = SourceCounts(
            neteaseCount = 30, neteaseStatus = SourceSearchStatus.DONE,
            qqCount = 12, qqStatus = SourceSearchStatus.DONE,
        )
        // 默认 biliStatus = SKIPPED ⇒ 走两源模板，连一次多余拼接都不做。
        assertEquals("网易云 30 首 · QQ 音乐 12 首", counts.summary(zhCN))
        assertEquals(counts.summary(zhCN), counts.summary(zhCN))
        assertFalse(counts.hasPending)
    }

    @Test
    fun `B 站启用后统计行带上第三段`() {
        val counts = SourceCounts(
            neteaseCount = 30, neteaseStatus = SourceSearchStatus.DONE,
            qqCount = 0, qqStatus = SourceSearchStatus.DONE,
            biliCount = 5, biliStatus = SourceSearchStatus.DONE,
        )
        assertEquals("网易云 30 首 · QQ 音乐 0 首 · B站 5 首", counts.summary(zhCN))
    }

    @Test
    fun `B 站还没回来时显示搜索中 而不是 0 首`() {
        // 这是 v2.5.5 · G 用一整版修出来的纪律，**不能在新源上重犯**。
        val counts = SourceCounts(biliStatus = SourceSearchStatus.PENDING)
        assertTrue(counts.hasPending)
        val line = counts.summary(zhCN)
        assertTrue("必须是「搜索中…」：$line", line.contains("搜索中"))
        assertFalse("绝不能把「还没回来」写成 0 首：$line", line.contains("B站 0 首"))
    }

    @Test
    fun `B 站的三态各自有独立文案`() {
        // 注意 QQ 侧必须显式给 DONE —— 它的默认值是 PENDING（「搜索中…」），
        // 那正是 v2.5.5 刻意留下的默认，不该被这条用例顺手改掉。
        fun counts(status: SourceSearchStatus) = SourceCounts(
            neteaseStatus = SourceSearchStatus.DONE,
            qqStatus = SourceSearchStatus.DONE,
            biliStatus = status,
        )
        assertEquals("网易云 0 首 · QQ 音乐 0 首 · B站 搜索超时", counts(SourceSearchStatus.TIMEOUT).summary(zhCN))
        assertEquals("网易云 0 首 · QQ 音乐 0 首 · B站 搜索失败", counts(SourceSearchStatus.ERROR).summary(zhCN))
        // ★ SKIPPED 是**唯一**不产生第三段的取值：B 站没启用时统计行必须与 v3.0.0
        // 逐字相同（铁律 27）。所以「未启用」这句文案**不会**出现在统计行上 ——
        // `searchSourceSkipped` 仍被 `sideText` 用着（`biliText` 可单独取用），
        // 只是 `summary` 在 SKIPPED 时短路了。
        assertEquals("网易云 0 首 · QQ 音乐 0 首", counts(SourceSearchStatus.SKIPPED).summary(zhCN))
        assertEquals("网易云 0 首 · QQ 音乐 0 首 · B站 搜索中…", counts(SourceSearchStatus.PENDING).summary(zhCN))
    }

    @Test
    fun `八种语言下三源统计行都不为空 且第三段确实拼上去了`() {
        val counts = SourceCounts(
            neteaseCount = 1, qqCount = 2, biliCount = 3,
            biliStatus = SourceSearchStatus.DONE,
        )
        com.takahashirinta.ncrust.ui.i18n.languagePresets.forEach { preset ->
            val two = SourceCounts(neteaseCount = 1, qqCount = 2).summary(preset.strings)
            val three = counts.summary(preset.strings)
            assertTrue("${preset.code}: 三源行必须比两源行长", three.length > two.length)
            assertTrue("${preset.code}: 三源行必须以两源行为前缀", three.startsWith(two))
        }
    }

    @Test
    fun `isDone 覆盖三个音源`() {
        val counts = SourceCounts(
            neteaseStatus = SourceSearchStatus.DONE,
            qqStatus = SourceSearchStatus.TIMEOUT,
            biliStatus = SourceSearchStatus.SKIPPED,
        )
        assertTrue(counts.isDone(MusicSource.NETEASE))
        assertFalse(counts.isDone(MusicSource.QQMUSIC))
        assertFalse(counts.isDone(MusicSource.BILIBILI))
    }

    @Test
    fun `英文文案也对（防止把中文串漏进 en）`() {
        val counts = SourceCounts(
            neteaseCount = 3, neteaseStatus = SourceSearchStatus.DONE,
            qqCount = 4, qqStatus = SourceSearchStatus.DONE,
            biliCount = 5, biliStatus = SourceSearchStatus.DONE,
        )
        val line = counts.summary(en)
        assertTrue("B 站那一段必须拼上：$line", line.endsWith("Bilibili 5"))
        assertTrue("英文行里不该出现中文：$line", line.none { it.code > 0x2E80 })
        assertEquals(
            "三源行的前缀必须是两源行",
            SourceCounts(
                neteaseCount = 3, neteaseStatus = SourceSearchStatus.DONE,
                qqCount = 4, qqStatus = SourceSearchStatus.DONE,
            ).summary(en),
            line.substringBefore(" · Bilibili"),
        )
    }
}

/**
 * 三源排序：**B 站追加在最后，前两源的顺序一个字节不改**。
 */
class SearchRankingThreeSourceTest {

    private fun n(id: Int, access: TrackAccess = TrackAccess.FREE) =
        RankedSong<Int>(id, access, TrackAvailability.UNKNOWN)

    @Test
    fun `B 站为空时与两源版本逐字相同`() {
        val netease = listOf(n(1), n(2))
        val qq = listOf(n(11), n(12))
        assertEquals(
            SearchRanking.order(netease, qq, neteaseVip = false, qqVip = false),
            SearchRanking.order(netease, qq, bili = emptyList(), neteaseVip = false, qqVip = false),
        )
        assertEquals(
            SearchRanking.order(netease, qq, neteaseVip = true, qqVip = true),
            SearchRanking.order(netease, qq, bili = emptyList(), neteaseVip = true, qqVip = true),
        )
    }

    @Test
    fun `B 站结果追加在最后`() {
        val out = SearchRanking.order(
            netease = listOf(n(1)),
            qq = listOf(n(11)),
            bili = listOf(n(21), n(22)),
            neteaseVip = false,
            qqVip = false,
        )
        assertEquals(listOf(1, 11, 21, 22), out.map { it.value })
    }

    @Test
    fun `B 站不参与会员交错（它没有会员信号）`() {
        // 两家都有会员时，前两源**交错**（v2.1.4 的语义），B 站仍然整段追加。
        val out = SearchRanking.order(
            netease = listOf(n(1, TrackAccess.MEMBER_ONLY), n(2)),
            qq = listOf(n(11, TrackAccess.MEMBER_ONLY), n(12)),
            bili = listOf(n(21), n(22)),
            neteaseVip = true,
            qqVip = true,
        )
        assertEquals(listOf(1, 11, 2, 12, 21, 22), out.map { it.value })
    }

    @Test
    fun `B 站内部保持传入顺序（稳定）`() {
        val bili = (1..10).map { n(100 + it) }
        val out = SearchRanking.order(emptyList(), emptyList(), bili, false, false)
        assertEquals(bili.map { it.value }, out.map { it.value })
    }
}

/**
 * 音源筛选（`SourceFilter`）。
 */
class SourceFilterTest {

    @Test
    fun `ALL 不过滤 其余各取一源`() {
        assertTrue(SourceFilter.ALL.accepts(MusicSource.NETEASE))
        assertTrue(SourceFilter.ALL.accepts(MusicSource.QQMUSIC))
        assertTrue(SourceFilter.ALL.accepts(MusicSource.BILIBILI))

        assertTrue(SourceFilter.NETEASE.accepts(MusicSource.NETEASE))
        assertFalse(SourceFilter.NETEASE.accepts(MusicSource.BILIBILI))

        assertTrue(SourceFilter.BILIBILI.accepts(MusicSource.BILIBILI))
        assertFalse(SourceFilter.BILIBILI.accepts(MusicSource.NETEASE))
        assertFalse(SourceFilter.BILIBILI.accepts(MusicSource.QQMUSIC))
    }

    @Test
    fun `过滤保序 且 ALL 原样返回同一个列表实例`() {
        val items = listOf(
            MusicSource.NETEASE to 1,
            MusicSource.BILIBILI to 2,
            MusicSource.NETEASE to 3,
            MusicSource.QQMUSIC to 4,
        )
        assertEquals(
            listOf(1, 3),
            SourceFilter.NETEASE.filter(items) { it.first }.map { it.second },
        )
        assertEquals(
            listOf(2),
            SourceFilter.BILIBILI.filter(items) { it.first }.map { it.second },
        )
        // 顺序必须原样保留（筛选不重排 —— 顺序是 SearchRanking 的职责）。
        assertEquals(
            listOf(1, 2, 3, 4),
            SourceFilter.ALL.filter(items) { it.first }.map { it.second },
        )
    }

    @Test
    fun `B 站档只在音源启用时出现`() {
        assertEquals(
            listOf(SourceFilter.ALL, SourceFilter.NETEASE, SourceFilter.QQMUSIC),
            SourceFilter.visible(biliEnabled = false),
        )
        assertEquals(
            listOf(SourceFilter.ALL, SourceFilter.NETEASE, SourceFilter.QQMUSIC, SourceFilter.BILIBILI),
            SourceFilter.visible(biliEnabled = true),
        )
    }

    @Test
    fun `四档文案都取得到 且互不相同`() {
        val labels = SourceFilter.values().map { it.label(zhCN) }
        assertEquals(4, labels.size)
        assertEquals("双源", labels[0])
        assertEquals("只看 B 站", labels[3])
        assertEquals("四档文案不该有重复：$labels", 4, labels.toSet().size)
    }

    // ---------------------------------------------------------------- v3.2.0 · P0-D ----
    //
    // 这一组的每一条都对应 P0-D 的一个真实症状（切「只搜 B 站」之后卡死）。
    // 断言全是**纯逻辑**，不涉及 Compose（本仓库没有 Robolectric）——
    // 判据从 UI 里抽出来就是为了能被这一组钉住。

    @Test
    fun `筛选后为空时筛选档仍然挂载 —— P0-D 的回归判据`() {
        // 用户点了「只看 B 站」，而这一轮 B 站一条都没有：
        // 总数 30（网易云的 30 首），筛选后 0。
        // 旧实现的判据是「筛选后的结果」，于是筛选档自己消失、没有任何路径点回「双源」。
        assertTrue(
            "筛选后为空时筛选档必须还在（否则用户没有任何路径切回「双源」）",
            SourceFilter.shouldShowFilterRow(totalSongs = 30, biliEnabled = true),
        )
        // B 站关掉时同样成立（那时只剩双源/网易云/QQ 三档，仍然有得选）。
        assertTrue(SourceFilter.shouldShowFilterRow(totalSongs = 30, biliEnabled = false))
    }

    @Test
    fun `一条结果都没有时不画筛选档 —— 点下去只会得到另一个空列表`() {
        assertFalse(SourceFilter.shouldShowFilterRow(totalSongs = 0, biliEnabled = true))
        assertFalse(SourceFilter.shouldShowFilterRow(totalSongs = 0, biliEnabled = false))
    }

    @Test
    fun `可用档位多于一个才画筛选档`() {
        // 当前实现下这条恒成立（关掉 B 站仍有双源/网易云/QQ 三档）。
        // 它守的是**将来**：若某个版本只剩一档，画一排单选档位没有意义。
        assertTrue(SourceFilter.visible(biliEnabled = false).size > 1)
        assertTrue(SourceFilter.visible(biliEnabled = true).size > 1)
    }

    @Test
    fun `空态分流 —— 本次搜索无结果 vs 当前筛选下无结果`() {
        assertEquals(SearchEmptyKind.NO_RESULT, SourceFilter.emptyKind(totalSongs = 0, visibleSongs = 0))
        assertEquals(SearchEmptyKind.FILTERED_OUT, SourceFilter.emptyKind(totalSongs = 30, visibleSongs = 0))
        // 不空 ⇒ 没有空态。
        assertEquals(null, SourceFilter.emptyKind(totalSongs = 30, visibleSongs = 30))
        assertEquals(null, SourceFilter.emptyKind(totalSongs = 30, visibleSongs = 2))
    }

    @Test
    fun `筛选为空的文案必须指名当前档位 且与无结果文案不同`() {
        val filtered = SourceFilter.BILIBILI.emptyText(zhCN)
        val noResult = zhCN.searchSongsEmpty
        assertNotEquals("两个空态必须是两句不同的话", noResult, filtered)
        // 指名档位（v3.2.0 定稿走 `searchFilterEmpty`，档位名由 `SourceFilter.label` 喂进去）。
        assertTrue("应当含档位名：$filtered", filtered.contains(SourceFilter.BILIBILI.label(zhCN)))
        // ⚠️ 不再断言「含 0 首」：那是 i18n 归口期间的占位拼接留下的形状，
        // 而「0 首」读起来像统计行、与空态说明是两件事（见 `SourceFilter.emptyText` 的 KDoc）。
        // 反过来钉住它**没有**退回统计行的形状。
        assertTrue(
            "空态不该退回「统计行」的形状：$filtered",
            !filtered.contains(zhCN.searchSourceCount(0)),
        )
    }

    @Test
    fun `筛选为空的文案在 8 种语言下都不漏中文`() {
        val filters = listOf(SourceFilter.ALL, SourceFilter.NETEASE, SourceFilter.QQMUSIC, SourceFilter.BILIBILI)
        for ((tag, s) in listOf(
            "zhCN" to zhCN,
            "en" to en,
            "deDE" to com.takahashirinta.ncrust.ui.i18n.deDE,
            "jpJP" to com.takahashirinta.ncrust.ui.i18n.jpJP,
            "jpMY" to com.takahashirinta.ncrust.ui.i18n.jpMY,
            "koNK" to com.takahashirinta.ncrust.ui.i18n.koNK,
            "ruRU" to com.takahashirinta.ncrust.ui.i18n.ruRU,
            "zhTW" to com.takahashirinta.ncrust.ui.i18n.zhTW,
        )) {
            for (f in filters) {
                val text = f.emptyText(s)
                assertTrue("$tag/${f.name} 的文案不该为空", text.isNotBlank())
                // 文案由两个**已本地化**的既有片段拼成 ⇒ 非中文语言下不该出现中文的「首」
                // （zhTW 与 zhCN 同形，所以排除）。
                if (tag != "zhCN" && tag != "zhTW") {
                    assertFalse("$tag/${f.name} 漏了未翻译的中文片段：$text", text.contains("首"))
                }
            }
        }
    }
}
