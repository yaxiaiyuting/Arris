/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 2026-10-27 审查 J10：旧 QQ 字段兼容**不对称**（读兼容了 mid、没兼容 songid/songname/专辑）。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `QqSongMapper.fromSongObject` 的新旧形状兼容（J10）。纯 JSON 解析，无网络。
 *
 * ## 缺陷形状
 *
 * 同一个曲目有两种响应形状（旧版 `client_search_cp` 原样 vs `new_json=1` / `musicu`）。
 * 旧实现只给 **mid** 做了兼容，于是同一首歌从旧形状解析出来是
 * 「身份对（mid）、标题空、专辑丢、数字 id 落 FNV 散列兜底」，
 * 而 `TrackKey` 只比较 `(source, id)` ⇒ 同一首歌在收藏/队列里变成**两条**。
 *
 * 这组用例用同一首歌的两种形状做**交叉断言**：身份、标题、专辑必须逐项相等。
 */
class J10QqLegacyFieldsTest {

    /** 旧版扁平形状（`client_search_cp` 未加 `new_json=1` 时的字段名）。 */
    private val legacy = JSONObject(
        """
        {
          "songid": 97773,
          "songmid": "0039MnYb0qxYhV",
          "songname": "旧形状标题",
          "albumid": 22276,
          "albummid": "000MkMni19ClKG",
          "albumname": "旧形状专辑",
          "interval": 245,
          "singer": [{"id": 4558, "mid": "0025NhlN2yWrP4", "name": "旧形状歌手"}]
        }
        """.trimIndent(),
    )

    /** 新版嵌套形状（同一首歌）。 */
    private val modern = JSONObject(
        """
        {
          "id": 97773,
          "mid": "0039MnYb0qxYhV",
          "name": "旧形状标题",
          "interval": 245,
          "album": {"id": 22276, "mid": "000MkMni19ClKG", "name": "旧形状专辑"},
          "singer": [{"id": 4558, "mid": "0025NhlN2yWrP4", "name": "旧形状歌手"}]
        }
        """.trimIndent(),
    )

    @Test
    fun `新旧两种形状解析出同一个 TrackKey（同一首歌不许变成两条收藏）`() {
        val a = QqSongMapper.fromSongObject(legacy)
        val b = QqSongMapper.fromSongObject(modern)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(TrackKey.fromSong(b!!), TrackKey.fromSong(a!!))
        // 反解值必须是真实的 songid（97773），不是 mid 的 FNV 散列兜底 ——
        // 那正是「同一首歌两种形状 ⇒ 两条身份」的机制。
        assertEquals(97773L, SourceIds.qqRawId(a.id))
    }

    @Test
    fun `旧形状的标题不会被丢掉（songname）`() {
        val song = QqSongMapper.fromSongObject(legacy)!!
        assertEquals("旧形状标题", song.name)
    }

    @Test
    fun `旧形状的专辑身份与名字不会被丢掉（albummid albumname albumid）`() {
        val song = QqSongMapper.fromSongObject(legacy)!!
        val album = song.album
        assertNotNull("旧形状的专辑摊在 song 层，必须成组读出来", album)
        assertEquals("000MkMni19ClKG", album!!.mid)
        assertEquals("旧形状专辑", album.name)
        assertEquals(22276L, album.id)
        // 封面仍然由 albumMID（或 pmid）拼模板：这是既有口径，不因兼容而改变。
        assertTrue(
            "封面应来自 albumMID 模板：${album.picUrl}",
            album.picUrl.orEmpty().endsWith("M000000MkMni19ClKG.jpg"),
        )
    }

    @Test
    fun `旧形状的歌手 mid 依旧带出去（不因这次改动丢失）`() {
        val song = QqSongMapper.fromSongObject(legacy)!!
        assertEquals("0025NhlN2yWrP4", song.artists?.firstOrNull()?.mid)
        assertEquals("旧形状歌手", song.artists?.firstOrNull()?.name)
    }

    @Test
    fun `两种形状的曲目名专辑名一致（成组兼容而不是逐字段兜底）`() {
        val a = QqSongMapper.fromSongObject(legacy)!!
        val b = QqSongMapper.fromSongObject(modern)!!
        assertEquals(b.name, a.name)
        assertEquals(b.album?.mid, a.album?.mid)
        assertEquals(b.album?.name, a.album?.name)
        assertEquals(b.album?.id, a.album?.id)
        assertEquals(b.album?.picUrl, a.album?.picUrl)
        assertEquals(b.duration, a.duration)
        assertEquals(b.musicSource, a.musicSource)
    }

    /** 两种形状混在同一次搜索结果里时，按身份去重后只剩一条。 */
    @Test
    fun `同一次响应里混入两种形状时按身份去重只剩一条`() {
        val page = JSONObject(
            """
            {"code":0,"data":{"song":{"list":[
              {"songid":97773,"songmid":"0039MnYb0qxYhV","songname":"旧形状标题","interval":245},
              {"id":97773,"mid":"0039MnYb0qxYhV","name":"旧形状标题","interval":245}
            ]}}}
            """.trimIndent(),
        )
        val songs = QqSongMapper.songsFromLegacySearch(page)
        assertEquals(2, songs.size)
        assertEquals(1, songs.distinctBy { TrackKey.fromSong(it) }.size)
    }

    /** 缺 mid 的条目仍然整条丢掉（没法取链），兼容不等于放宽必填项。 */
    @Test
    fun `缺 mid 的旧条目仍然被丢掉`() {
        val broken = JSONObject("""{"songid": 1, "songname": "没有 mid"}""")
        assertNull(QqSongMapper.fromSongObject(broken))
    }
}
