/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.2 · P0：QQ `album` 对象 → `AlbumItem` 的 **albumMID 保留**契约。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.AlbumNav
import com.takahashirinta.ncrust.source.AlbumNavigator
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2 · P0：**「QQ 的 `album.mid` 在映射层被丢掉」是这次的根因**，
 * 而且丢法比"漏读"更隐蔽 —— 它被读出来了，只是**只喂给了封面 URL**。
 *
 * v2.6.1 修艺人时留下的那句注释（`AlbumDetailScreen.kt` 的「QQ 一侧拿不到 `singerMID`」）
 * 是**对专辑接口的观察**；本次探针实测 `album` 对象的字段集是
 * `{id, mid, name, pmid, subtitle, time_public, title, title_highlight}`
 * —— `mid` 一直在，`pmid` 也在，而它们**是两个不同的东西**：
 *
 * | 字段 | 是什么 | 用途 |
 * |---|---|---|
 * | `mid` | albumMID（`004Z85XP1c25b7`） | **身份** —— `musicu.fcg` 的 `albumMid` 参数认它 |
 * | `pmid` | **封面照片 id**（`004Z85XP1c25b7_5`，尾部 `_N` 是封面序号） | 拼封面 URL |
 * | `id` | QQ 域数字 albumID（`22276`） | 诊断；**拿去 ncm 查就是本次 P0** |
 *
 * 夹具是 2026-09 的**真实响应形状**（匿名可复现），其中《富士山下》一条
 * 与真机复现、`logcat` 里的 `/api/v1/album/22276` 逐字对应。
 */
class QqAlbumMidMappingTest {

    /** 真实条目（`client_search_cp?new_json=1` 的《富士山下》，字段原样）。 */
    private val searchItem = JSONObject(
        """
        {
          "id": 260678,
          "mid": "003aAPj81VWrbL",
          "name": "富士山下",
          "interval": 258,
          "singer": [{"id": 143, "mid": "003Nz2So3XXYek", "name": "陈奕迅"}],
          "album": {"id": 22276, "mid": "004Z85XP1c25b7", "pmid": "004Z85XP1c25b7_5",
                    "name": "What's Going On...?"},
          "file": {"media_mid": "003aAPj81VWrbL"}
        }
        """.trimIndent()
    )

    /** 没有 `pmid` 的形状（部分接口只给 `mid`）——封面回落 `mid`，身份仍然是 `mid`。 */
    private val noPmidItem = JSONObject(
        """
        {"id": 1251167, "mid": "0029Zemv0kR1ur", "name": "葡萄成熟时", "interval": 258,
         "singer": [{"id": 143, "mid": "003Nz2So3XXYek", "name": "陈奕迅"}],
         "album": {"id": 7879, "mid": "003J6fvc0bVJon", "name": "U 87"}}
        """.trimIndent()
    )

    // ------------------------------------------------------------ 1. 映射层

    @Test
    fun `搜索映射保留 album_mid`() {
        val album = QqSongMapper.fromSongObject(searchItem)!!.album!!
        assertEquals(22276L, album.id)
        assertEquals("What's Going On...?", album.name)
        assertEquals(
            "album.mid 就在同一个 JSONObject 上，丢掉它 = 「转到专辑」只能拿数字 id 去 ncm 猜",
            "004Z85XP1c25b7", album.mid,
        )
    }

    @Test
    fun `封面继续用 pmid 拼——身份与封面是两个职责`() {
        val album = QqSongMapper.fromSongObject(searchItem)!!.album!!
        assertEquals(
            "封面 URL 必须与 v2.6.1 逐字节相同（本期不动封面）",
            "https://y.qq.com/music/photo_new/T002R500x500M000004Z85XP1c25b7_5.jpg",
            album.picUrl,
        )
        assertNotEquals(
            "身份字段绝不能等于封面照片 id —— 后者带 _N 后缀，不是 albumMid",
            album.picUrl, album.mid,
        )
    }

    @Test
    fun `没有 pmid 时封面回落 mid 而身份仍是 mid`() {
        val album = QqSongMapper.fromSongObject(noPmidItem)!!.album!!
        assertEquals("003J6fvc0bVJon", album.mid)
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R500x500M000003J6fvc0bVJon.jpg",
            album.picUrl,
        )
    }

    @Test
    fun `缺 mid 的老形状回落为 null 而不是空串`() {
        // 语义是「没有字符串身份」。回落成空串会让「字段缺失」与「服务端确实没有」
        // 混成一种形状 —— 这正是 v1.9.2 音译字段踩过的坑。
        val noMid = JSONObject(
            """{"id":1,"mid":"aBcDeFgHiJkLmN","name":"x","interval":10,
                "album":{"id":22276,"name":"What's Going On...?"}}""".trimIndent()
        )
        assertNull(QqSongMapper.fromSongObject(noMid)!!.album!!.mid)
    }

    @Test
    fun `空串 mid 也回落为 null`() {
        val blankMid = JSONObject(
            """{"id":1,"mid":"aBcDeFgHiJkLmN","name":"x","interval":10,
                "album":{"id":22276,"mid":"","name":"x"}}""".trimIndent()
        )
        assertNull(QqSongMapper.fromSongObject(blankMid)!!.album!!.mid)
    }

    @Test
    fun `没有 album 对象的条目 album 为 null 而不是造一个空壳`() {
        val noAlbum = JSONObject(
            """{"id":1,"mid":"aBcDeFgHiJkLmN","name":"x","interval":10,
                "singer":[{"id":143,"name":"陈奕迅"}]}""".trimIndent()
        )
        assertNull(QqSongMapper.fromSongObject(noAlbum)!!.album)
    }

    // ------------------------------------------------- 2. 两条映射路径逐值一致

    @Test
    fun `QqCatalogMapper 与 QqSongMapper 的专辑映射逐值一致`() {
        // 本仓库的既有纪律：同一件事有两条链路时，必须有一条断言它们逐值相同
        // （v2.6.1 的艺人 mid 就是这么钉的）。
        // 分叉的表现是「搜索进来的歌跳得对、歌单/歌手页进来的歌跳错」——
        // 只在特定入口复现，正是最难查的一类。
        assertEquals(
            QqSongMapper.fromSongObject(searchItem)!!.album,
            QqCatalogMapper.albumItemOf(searchItem),
        )
        assertEquals(
            QqSongMapper.fromSongObject(noPmidItem)!!.album,
            QqCatalogMapper.albumItemOf(noPmidItem),
        )
    }

    @Test
    fun `专辑曲目那条链路同样带出 album_mid`() {
        // `QqCatalogMapper.albumDetailFromSongList` 走的是 `mapSongs` → `QqSongMapper`，
        // 所以「专辑页里的曲目」天然继承同一份映射。这里用 `songInfo` 的真实形状自证一次。
        val songInfo = JSONObject(
            """
            {"id": 260678, "mid": "003aAPj81VWrbL", "name": "富士山下", "interval": 258,
             "singer": [{"id": 143, "mid": "003Nz2So3XXYek", "name": "陈奕迅"}],
             "album": {"id": 22276, "mid": "004Z85XP1c25b7", "pmid": "004Z85XP1c25b7_5",
                       "name": "What's Going On...?"}}
            """.trimIndent()
        )
        assertEquals("004Z85XP1c25b7", QqSongMapper.fromSongObject(songInfo)!!.album!!.mid)
    }

    // ------------------------------------------------- 3. 端到端：映射 → 决策

    @Test
    fun `映射出来的 QQ 曲目会被决策成 QQ 专辑页而不是ncm的同号专辑`() {
        val song = QqSongMapper.fromSongObject(searchItem)!!
        val nav = AlbumNavigator.resolve(song)
        assertEquals(AlbumNav.Direct(MusicSource.QQMUSIC, "004Z85XP1c25b7"), nav)
        assertTrue(
            "决策出的 id 必须是 albumMID（base62），不是数字 albumID(22276)",
            AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, (nav as AlbumNav.Direct).id),
        )
    }

    @Test
    fun `缺 mid 的 QQ 曲目会被决策成搜索——不猜专辑`() {
        val noMid = JSONObject(
            """{"id":1,"mid":"aBcDeFgHiJkLmN","name":"富士山下","interval":258,
                "singer":[{"id":143,"name":"陈奕迅"}],
                "album":{"id":22276,"name":"What's Going On...?"}}""".trimIndent()
        )
        val nav = AlbumNavigator.resolve(QqSongMapper.fromSongObject(noMid)!!)
        assertEquals(
            AlbumNav.Search("What's Going On...?", com.takahashirinta.ncrust.source.AlbumNavReason.AMBIGUOUS_NUMERIC_ID),
            nav,
        )
    }

    @Test
    fun `真机老队列形状的条目解出来没有任何可信身份`() {
        // S6 真机 `ncrust_playback_state` 队列第 847 项的**原文**（`al` 只有 `picUrl`）。
        //
        // ⚠️ 注意这一条**不走 `QqSongMapper`**：落盘的是 Gson 序列化后的 `SongItem`
        // （key 是 `al` / `ar`），不是 QQ 的接口响应（key 是 `album` / `singer`）。
        // 这正是"老缓存"与"新鲜加载"的分界 —— 前者少了映射层那一次，
        // 字段名与形状都由 `SongItem` 的 `@SerializedName` 决定。
        val persisted = """
            {"id": 4611686018427837109, "name": "稻香", "source": "qqmusic",
             "mid": "003aAYrm3GE0Ac", "media_id": "0020wJDo3cx0j3",
             "ar": [{"name": "周杰伦"}],
             "al": {"picUrl": "https://y.qq.com/music/photo_new/T002R500x500M000002Neh8l0uciQZ_3.jpg"}}
        """.trimIndent()
        val song = com.google.gson.Gson().fromJson(persisted, SongItem::class.java)
        assertNull("老条目的 al 里没有 mid ⇒ 身份不可信", song.album!!.mid)
        assertNull("老条目的 al 里连 id 都没有", song.album!!.id)
        assertEquals(MusicSource.QQMUSIC, song.musicSource)
        assertEquals(
            "老缓存必须跳搜索（关键词回落到「曲名 + 艺人名」），而不是静默没反应",
            AlbumNav.Search("稻香 周杰伦", com.takahashirinta.ncrust.source.AlbumNavReason.MISSING_ALBUM_META),
            AlbumNavigator.resolve(song),
        )
    }
}
