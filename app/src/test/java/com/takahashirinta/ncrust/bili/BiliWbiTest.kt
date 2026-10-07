/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：Wbi 签名与档位映射的单测（铁律 25）。
 */

package com.takahashirinta.ncrust.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BiliWbi] 的**固定向量**测试。
 *
 * ## 这些期望值是怎么来的（不是手算、不是猜）
 *
 * 由 `docs/verification/v3.1.0/bili-research/wbi_sign_reference.py`（同一张乱序表、
 * 同一条排序+过滤规则、Python 的 `urllib.parse.urlencode`）在固定 `wts=1790533664`
 * 下算出，并**真的用它请求过** `x/web-interface/wbi/search/type`（HTTP 200、`code:0`）。
 * 也就是说这里的每一个十六进制串都对应一次成功的线上请求 —— 而不是"跑一遍实现抄下来"
 * 的自洽断言（那种断言在算法整体写错时同样会通过）。
 *
 * `img_key` / `sub_key` 取 2026-09-28 实测 `nav` 返回的那一对（见 EVIDENCE）。
 */
class BiliWbiTest {

    private val imgUrl = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
    private val subUrl = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"

    /** 实测得到的 mixin_key。 */
    private val mixin = "ea1db124af3c7062474693fa704f4ff8"

    private val wts = 1790533664L

    // ---------------------------------------------------------------- 密钥

    @Test
    fun `fileStem 从 URL 取文件名主体`() {
        assertEquals("7cd084941338484aae1ad9425b84077c", BiliWbi.fileStem(imgUrl))
        // 裸 key（没有斜杠与点）原样返回：nav 的字段形状变过一次，两种都要认。
        assertEquals("7cd084941338484aae1ad9425b84077c", BiliWbi.fileStem("7cd084941338484aae1ad9425b84077c"))
        assertEquals("", BiliWbi.fileStem(null))
        assertEquals("", BiliWbi.fileStem(""))
    }

    @Test
    fun `mixinKey 与实测值一致`() {
        assertEquals(mixin, BiliWbi.mixinKey(imgUrl, subUrl))
    }

    @Test
    fun `mixinKey 在缺 key 时返回空串 而不是半截的 key`() {
        // 拿一个半截的 key 去签名会**必然 412**，所以宁可返回空串让调用方判"不可用"。
        assertEquals("", BiliWbi.mixinKey(null, subUrl))
        assertEquals("", BiliWbi.mixinKey(imgUrl, null))
        assertEquals("", BiliWbi.mixinKey("", ""))
        assertEquals("", BiliWbi.mixinKey("short.png", "also-short.png"))
    }

    // ---------------------------------------------------------------- 签名向量

    @Test
    fun `签名向量 A —— 视频搜索（含中文关键词）`() {
        val q = BiliWbi.buildQuery(
            mapOf(
                "search_type" to "video",
                "keyword" to "周杰伦",
                "page" to "1",
                "page_size" to "20",
            ),
            imgUrl, subUrl, wts,
        )
        assertEquals(
            "keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&page=1&page_size=20&search_type=video" +
                "&wts=1790533664&w_rid=7d94efb15a071de3563e56d14419efe9",
            q,
        )
    }

    @Test
    fun `签名向量 B —— 取播放地址`() {
        val q = BiliWbi.buildQuery(
            mapOf(
                "bvid" to "BV1bU4y1n7Nx",
                "cid" to "674294080",
                "fnval" to "16",
                "fnver" to "0",
                "fourk" to "1",
            ),
            imgUrl, subUrl, wts,
        )
        assertEquals(
            "bvid=BV1bU4y1n7Nx&cid=674294080&fnval=16&fnver=0&fourk=1" +
                "&wts=1790533664&w_rid=34d449c4e352fcd4902b7dc8d8289564",
            q,
        )
    }

    /**
     * **这一条是「URL 编码用 `URLEncoder` 而不是 `Uri.encode`」的判据。**
     *
     * 带空格的搜索是最常见的用法（「周杰伦 晴天」），两种编码在这里分道扬镳：
     * `URLEncoder` 给 `+`、`Uri.encode` 给 `%20`。签名对不上就是 412。
     */
    @Test
    fun `签名向量 C —— 空格编成加号（与社区参考实现逐字一致）`() {
        val q = BiliWbi.buildQuery(
            mapOf("keyword" to "a b", "search_type" to "video", "page" to "1"),
            imgUrl, subUrl, wts,
        )
        assertTrue("空格必须是 +，不是 %20（后者签名必错）", q.contains("keyword=a+b"))
        assertEquals(
            "keyword=a+b&page=1&search_type=video&wts=1790533664" +
                "&w_rid=4b39be8f3565015e7195c8d71920123d",
            q,
        )
    }

    /**
     * **这一条是「先过滤、再编码」的顺序判据。**
     *
     * `URLEncoder` 会把 `'` 编成 `%27`、`(` 编成 `%28` —— 一旦顺序反过来，
     * 剔除规则就一个字符都命中不了（B 站按**原始字符**判，见 [BiliWbi.filterValue]）。
     */
    @Test
    fun `签名向量 D —— 敏感字符先剔除再编码`() {
        val q = BiliWbi.buildQuery(
            mapOf("keyword" to "it's (ok)*!", "search_type" to "video", "page" to "1"),
            imgUrl, subUrl, wts,
        )
        // `'` `(` `)` `*` `!` 全部消失，只剩 `its ok` → `its+ok`；
        // 若顺序写反，这里会是 `it%27s+%28ok%29%2A%21`。
        assertTrue("敏感字符必须被剔除：$q", q.contains("keyword=its+ok"))
        assertFalse(q.contains("%27"))
        assertFalse(q.contains("%28"))
        assertEquals(
            "keyword=its+ok&page=1&search_type=video&wts=1790533664" +
                "&w_rid=bda8ed5005a062b77b28fdb5a31762ae",
            q,
        )
    }

    @Test
    fun `wts 是秒 且会被入参覆盖`() {
        val q = BiliWbi.buildQuery(
            mapOf("page" to "1", "wts" to "999", "w_rid" to "deadbeef"),
            imgUrl, subUrl, wts,
        )
        // 入参里的 wts / w_rid 一律被覆盖 —— 调用方不该自己拼这两个。
        assertTrue(q.contains("wts=1790533664"))
        assertFalse(q.contains("wts=999"))
        assertFalse(q.contains("deadbeef"))
    }

    @Test
    fun `mixinKey 为空时返回未签名 query（失败必须可见）`() {
        // 不签名会让服务端回 412 —— 那是**看得见**的失败；
        // 在这里返回 null 只会让「搜索没反应」变成一个没有线索的现象。
        val q = BiliWbi.signedQuery(mapOf("a" to "1"), mixinKey = "", nowSec = wts)
        assertEquals("a=1&wts=1790533664", q)
        assertFalse(q.contains("w_rid"))
    }

    @Test
    fun `md5Hex 与已知向量一致`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", BiliWbi.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", BiliWbi.md5Hex("abc"))
    }

    @Test
    fun `乱序表是 64 个互不相同的下标`() {
        // 表写错（漏一个、重一个）会让签名整体错位，而症状只是"偶尔 412"。
        val tab = BiliWbi.MIXIN_KEY_ENC_TAB
        assertEquals(64, tab.size)
        assertEquals("乱序表必须恰好是 0..63 的一个排列", (0..63).toSet(), tab.toSet())
    }
}

/**
 * 档位映射（`MusicSourceProvider` 的契约：本应用档位 → B 站 `qn`）。
 */
class BiliQualityTest {

    @Test
    fun `档位映射逐条对齐`() {
        assertEquals(BiliQn.Q128, BiliQuality.mapFromLevel("standard"))
        assertEquals(BiliQn.Q192, BiliQuality.mapFromLevel("higher"))
        assertEquals(BiliQn.Q320, BiliQuality.mapFromLevel("exhigh"))
        assertEquals(BiliQn.FLAC, BiliQuality.mapFromLevel("lossless"))
        // 比 FLAC 更高的档位在 B 站**不存在** ⇒ 映射到它自己的最高档，而不是"不请求"。
        assertEquals(BiliQn.FLAC, BiliQuality.mapFromLevel("hires"))
        assertEquals(BiliQn.FLAC, BiliQuality.mapFromLevel("jyeffect"))
        assertEquals(BiliQn.FLAC, BiliQuality.mapFromLevel("jymaster"))
        assertEquals(BiliQn.FLAC, BiliQuality.mapFromLevel("dolby"))
        // 未知档位：先试最高、再降（与 SongUrlFetcher 的 else 分支同一取舍）。
        assertEquals(BiliQn.FLAC, BiliQuality.mapFromLevel("nonsense"))
    }

    @Test
    fun `降级阶梯有序 去重 且以最低档收尾`() {
        assertEquals(listOf(BiliQn.FLAC, BiliQn.Q320, BiliQn.Q192, BiliQn.Q128), BiliQuality.fallbackLadder("lossless"))
        assertEquals(listOf(BiliQn.Q320, BiliQn.Q192, BiliQn.Q128), BiliQuality.fallbackLadder("exhigh"))
        assertEquals(listOf(BiliQn.Q192, BiliQn.Q128), BiliQuality.fallbackLadder("higher"))
        assertEquals(listOf(BiliQn.Q128), BiliQuality.fallbackLadder("standard"))
        // 每一档恰好一次（铁律 5：失败处理必须有界）。
        BiliQuality.fallbackLadder("dolby").let {
            assertEquals(it.size, it.toSet().size)
            assertEquals(BiliQn.Q128, it.last())
        }
    }

    @Test
    fun `qn 取值与实测一致`() {
        assertEquals(0, BiliQn.Q128.qn)
        assertEquals(1, BiliQn.Q192.qn)
        assertEquals(2, BiliQn.Q320.qn)
        assertEquals(3, BiliQn.FLAC.qn)
        assertNull(BiliQn.of(9))
    }
}
