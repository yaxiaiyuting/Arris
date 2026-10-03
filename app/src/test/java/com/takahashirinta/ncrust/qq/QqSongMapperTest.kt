package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.musicSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · B：QQ 搜索响应 → SongItem 的映射单测。
 *
 * 夹具是**真实响应**（2026-09 `music.search.SearchCgiService` 返回的《晴天》条目，
 * 只保留本映射用到的字段），不是编造的：QQ 的字段名（`singer`/`interval`/`file.media_mid`）
 * 与 ncm 完全不同，写错一个的表现是「能搜到、没封面、时长为 0」，只有真机肉眼可见。
 */
class QqSongMapperTest {

    /** 真实条目（截取字段，值原样）。 */
    private val realItem = JSONObject(
        """
        {
          "id": 97773,
          "mid": "0039MnYb0qxYhV",
          "name": "晴天",
          "title": "晴天",
          "interval": 269,
          "singer": [{"id": 4558, "mid": "0025NhlN2yWrP4", "name": "周杰伦"}],
          "album": {"id": 8220, "mid": "000MkMni19ClKG", "pmid": "000MkMni19ClKG_5", "name": "叶惠美"},
          "file": {"media_mid": "003Qui1q2u1Zho", "size_128mp3": 4317292, "size_320mp3": 10792943, "size_flac": 55397039},
          "pay": {"pay_down": 1, "pay_month": 1, "pay_play": 1, "price_track": 200}
        }
        """.trimIndent()
    )

    @Test
    fun `基本字段映射正确`() {
        val song = QqSongMapper.fromSongObject(realItem)!!
        assertEquals("晴天", song.name)
        assertEquals(MusicSource.QQMUSIC, song.musicSource)
        assertEquals("0039MnYb0qxYhV", song.sourceId)
        assertEquals("003Qui1q2u1Zho", song.mediaId)
        assertEquals(listOf("周杰伦"), song.artists?.map { it.name })
        assertEquals("叶惠美", song.album?.name)
    }

    @Test
    fun `id 是带 QQ 标志位的合成 id 且能反解回真实 songid`() {
        val song = QqSongMapper.fromSongObject(realItem)!!
        assertTrue(SourceIds.isQqId(song.id))
        assertEquals(97773L, SourceIds.qqRawId(song.id))
        // 与 ncm 的 id 空间不重叠 —— 离线缓存/歌词缓存/队列判重都靠这一点
        assertFalse(SourceIds.isQqId(97773L))
    }

    @Test
    fun `时长是秒转毫秒——interval 与ncm的 dt 单位不同`() {
        val song = QqSongMapper.fromSongObject(realItem)!!
        assertEquals(269_000L, song.duration)
    }

    @Test
    fun `封面按 album pmid 拼——带 _5 后缀时优先 pmid`() {
        val song = QqSongMapper.fromSongObject(realItem)!!
        assertEquals("https://y.qq.com/music/photo_new/T002R500x500M000000MkMni19ClKG_5.jpg", song.album?.picUrl)
    }

    @Test
    fun `没有 pmid 时回落 album mid`() {
        val item = JSONObject("""{"id":1,"mid":"m1","name":"x","album":{"id":2,"mid":"AMID","name":"a"}}""")
        val song = QqSongMapper.fromSongObject(item)!!
        assertEquals("https://y.qq.com/music/photo_new/T002R500x500M000AMID.jpg", song.album?.picUrl)
    }

    @Test
    fun `缺 id 时用 mid 的散列兜底——仍然是合法的 QQ id`() {
        val item = JSONObject("""{"mid":"0039MnYb0qxYhV","name":"x"}""")
        val song = QqSongMapper.fromSongObject(item)!!
        assertTrue(SourceIds.isQqId(song.id))
        assertTrue(SourceIds.qqRawId(song.id)!! > 0L)
    }

    @Test
    fun `缺 mid 的条目直接丢弃——它无法取链`() {
        assertNull(QqSongMapper.fromSongObject(JSONObject("""{"id":1,"name":"x"}""")))
        assertNull(QqSongMapper.fromSongObject(JSONObject("""{"id":1,"mid":"","name":"x"}""")))
        assertNull(QqSongMapper.fromSongObject(null))
    }

    @Test
    fun `没有 file 字段时 mediaId 回落 songmid`() {
        val item = JSONObject("""{"id":1,"mid":"M1","name":"x"}""")
        val song = QqSongMapper.fromSongObject(item)!!
        assertEquals("M1", song.mediaId)
    }

    @Test
    fun `media_mid 为空串时回落 songmid 而不是留空`() {
        val item = JSONObject("""{"id":1,"mid":"M1","name":"x","file":{"media_mid":""}}""")
        assertEquals("M1", QqSongMapper.fromSongObject(item)!!.mediaId)
    }

    // ---------- 响应级解析 ----------

    private fun searchResponse(bodyKey: String, itemsJson: String) = JSONObject(
        """{"req":{"code":0,"data":{"body":{"$bodyKey":$itemsJson}}}}"""
    )

    @Test
    fun `新版自适应形状 item_song_items 能解析`() {
        val json = searchResponse("item_song", """{"items":[$realItem]}""")
        val songs = QqSongMapper.songsFromSearchResponse(json)
        assertEquals(1, songs.size)
        assertEquals("晴天", songs[0].name)
    }

    @Test
    fun `旧版形状 song_list 也能解析——服务端按身份切换形状是常态`() {
        val json = searchResponse("song", """{"list":[$realItem]}""")
        assertEquals(1, QqSongMapper.songsFromSearchResponse(json).size)
    }

    @Test
    fun `坏响应返回空列表而不是抛异常`() {
        assertTrue(QqSongMapper.songsFromSearchResponse(null).isEmpty())
        assertTrue(QqSongMapper.songsFromSearchResponse(JSONObject("{}")).isEmpty())
        assertTrue(QqSongMapper.songsFromSearchResponse(JSONObject("""{"req":{"data":{}}}""")).isEmpty())
        // 列表里混入坏条目时只丢坏的那条
        val json = searchResponse("item_song", """{"items":[{"name":"没有 mid"},$realItem]}""")
        assertEquals(1, QqSongMapper.songsFromSearchResponse(json).size)
    }

    // ---------- 付费标记 ----------

    @Test
    fun `付费标记只用于提示，不用于阻止播放`() {
        assertTrue(QqSongMapper.isPaywalled(realItem))
        assertFalse(QqSongMapper.isPaywalled(JSONObject("""{"pay":{"pay_play":0}}""")))
        assertFalse(QqSongMapper.isPaywalled(null))
    }

    @Test
    fun `只有试听片段时能被识别出来`() {
        val trialOnly = JSONObject("""{"file":{"size_try":960887}}""")
        assertTrue(QqSongMapper.hasTrialOnly(trialOnly))
        // 有完整 128k 文件时不算试听
        assertFalse(QqSongMapper.hasTrialOnly(realItem))
        assertFalse(QqSongMapper.hasTrialOnly(null))
    }
}
