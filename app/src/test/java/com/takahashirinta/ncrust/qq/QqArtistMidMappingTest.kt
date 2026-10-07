/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.1 · P0：QQ `singer[]` → `ArtistItem` 的 **mid 保留**契约。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.source.ArtistNav
import com.takahashirinta.ncrust.source.ArtistNavigator
import com.takahashirinta.ncrust.source.MusicSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.1 · P0：**「QQ 一侧拿不到 singerMID」这句话是错的**，而它写在了代码注释里。
 *
 * v2.4.0 的 `AlbumDetailScreen` 注释断言 QQ 曲目「只带一个数字艺人 id」，
 * 于是映射层只取了 `singer.id`、丢掉了同一个 `JSONObject` 上的 `singer.mid`。
 * 后果是「转到歌手」只能拿数字 id 去 ncm 查 —— 周杰伦 `4558` → **马洪波**。
 *
 * 本文件的夹具是 2026-09 的**真实响应形状**（匿名可复现）：
 * `singer[]` 条目的字段集是 `{id, mid, name, pmid, title, title_highlight, type, uin}`。
 * 也就是说 `mid` **一直在同一个对象上**，从来没缺过。
 *
 * 这一条是「探针先行」的直接产物：任务书假设是「匹配错误 / 缓存污染」，
 * 实测是**映射层丢字段**。单测钉住映射层，比在跳转层加兜底更靠前。
 */
class QqArtistMidMappingTest {

    /** 真实条目（`client_search_cp?new_json=1` 的《晴天》，字段原样）。 */
    private val searchItem = JSONObject(
        """
        {
          "id": 97773,
          "mid": "0039MnYb0qxYhV",
          "name": "晴天",
          "interval": 269,
          "singer": [{"id": 4558, "mid": "0025NhlN2yWrP4", "name": "周杰伦",
                      "pmid": "", "title": "周杰伦", "type": 0, "uin": 0}],
          "album": {"id": 8220, "mid": "000MkMni19ClKG", "name": "叶惠美"},
          "file": {"media_mid": "003Qui1q2u1Zho"}
        }
        """.trimIndent()
    )

    /** 合唱曲：多个 `singer`，第一个是主艺人。 */
    private val duetItem = JSONObject(
        """
        {
          "id": 108708068,
          "mid": "002PZvTU4YHsBI",
          "name": "布拉格广场",
          "interval": 294,
          "singer": [{"id": 4558, "mid": "0025NhlN2yWrP4", "name": "周杰伦"},
                     {"id": 143, "mid": "003Nz2So3XXYek", "name": "陈奕迅"}],
          "album": {"id": 8220, "mid": "000MkMni19ClKG", "name": "看我72变"}
        }
        """.trimIndent()
    )

    // ------------------------------------------------------------ 1. 映射层

    @Test
    fun `搜索映射保留 singer_mid 与数字 id`() {
        val song = QqSongMapper.fromSongObject(searchItem)!!
        val artist = song.artists!!.single()
        assertEquals("周杰伦", artist.name)
        assertEquals(4558L, artist.id)
        assertEquals(
            "singer.mid 就在同一个 JSONObject 上，丢掉它 = 「转到歌手」只能拿数字 id 去 ncm 猜",
            "0025NhlN2yWrP4", artist.mid,
        )
    }

    @Test
    fun `合唱曲每一个 singer 都带自己的 mid`() {
        val song = QqSongMapper.fromSongObject(duetItem)!!
        assertEquals(listOf("0025NhlN2yWrP4", "003Nz2So3XXYek"), song.artists!!.map { it.mid })
        assertEquals(listOf(4558L, 143L), song.artists!!.map { it.id })
    }

    @Test
    fun `缺 mid 的老形状回落为 null 而不是空串`() {
        // 老版本落盘的数据、或某个接口只给数字 id 时，语义是「没有字符串身份」。
        // 回落成空串会让 ArtistNavigator 的 notEmpty 判断与「字段缺失」混成一种形状
        // —— 这正是 v1.9.2 音译字段踩过的那个坑。
        val noMid = JSONObject(
            """{"id":1,"mid":"aBcDeFgHiJkLmN","name":"x","interval":10,
                "singer":[{"id":4558,"name":"周杰伦"}]}""".trimIndent()
        )
        val song = QqSongMapper.fromSongObject(noMid)!!
        assertNull(song.artists!!.single().mid)
    }

    @Test
    fun `空串 mid 也回落为 null`() {
        val blankMid = JSONObject(
            """{"id":1,"mid":"aBcDeFgHiJkLmN","name":"x","interval":10,
                "singer":[{"id":4558,"mid":"","name":"周杰伦"}]}""".trimIndent()
        )
        assertNull(QqSongMapper.fromSongObject(blankMid)!!.artists!!.single().mid)
    }

    // ------------------------------------------------- 2. 两条映射路径逐值一致

    @Test
    fun `QqCatalogMapper 与 QqSongMapper 的艺人映射逐值一致`() {
        // 本仓库的既有纪律：同一件事有两条链路时，必须有一条断言它们逐值相同
        // （v2.6.0 的 isRemoteLikeEligible / ReportGate 就是这么钉的）。
        // 两条路各自解析 `singer[]`，分叉的表现是「搜索进来的歌跳得对、
        // 歌单/歌手页进来的歌跳错」—— 只在特定入口复现。
        assertEquals(
            QqSongMapper.fromSongObject(searchItem)!!.artists,
            QqCatalogMapper.artistsOf(searchItem),
        )
        assertEquals(
            QqSongMapper.fromSongObject(duetItem)!!.artists,
            QqCatalogMapper.artistsOf(duetItem),
        )
    }

    // ------------------------------------------------- 3. 端到端：映射 → 决策

    @Test
    fun `映射出来的 QQ 曲目会被决策成 QQ 艺人页而不是ncm的同号艺人`() {
        val song = QqSongMapper.fromSongObject(searchItem)!!
        val nav = ArtistNavigator.resolve(song)
        assertEquals(ArtistNav.Direct(MusicSource.QQMUSIC, "0025NhlN2yWrP4", "周杰伦"), nav)
        assertTrue(
            "决策出的 id 必须是 singerMID（base62），不是数字 singerID(4558)",
            ArtistNavigator.idDomainMatches(MusicSource.QQMUSIC, (nav as ArtistNav.Direct).id),
        )
    }

    @Test
    fun `缺 mid 的 QQ 曲目会被决策成搜索——不猜艺人`() {
        val noMid = JSONObject(
            """{"id":1,"mid":"aBcDeFgHiJkLmN","name":"x","interval":10,
                "singer":[{"id":4558,"name":"周杰伦"}]}""".trimIndent()
        )
        val nav = ArtistNavigator.resolve(QqSongMapper.fromSongObject(noMid)!!)
        assertEquals(ArtistNav.Search("周杰伦", com.takahashirinta.ncrust.source.ArtistNavReason.AMBIGUOUS_NUMERIC_ID), nav)
    }
}
