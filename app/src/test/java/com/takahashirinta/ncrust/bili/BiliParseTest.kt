/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站响应解析与 TTL 的单测。样本全部是**真实响应**（见 EVIDENCE.md）。
 */

package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.musicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BiliParse] 的逐字段断言。
 *
 * JSON 样本取自 `docs/verification/v3.1.0/bili-research/EVIDENCE.md` 里**真实抓到的响应**
 * （2026-09-28 直连实测），只做了截断 —— 截断的部分都有注释说明。
 */
class BiliParseTest {

    // ---------------------------------------------------------------- 搜索（视频）

    /** `/x/web-interface/wbi/search/type` 的真实形状（截断掉无关字段）。 */
    private val searchJson = """
    {"code":0,"data":{"seid":"1","page":1,"page_size":20,"numResults":1000,
    "result":[
      {"type":"video","id":117329648162166,"aid":117329648162166,"bvid":"BV1bhau6REvc",
       "title":"mmd<em class=\"keyword\">初音未来</em>VS德莱文和黑岩","author":"翼权",
       "pic":"//i2.hdslb.com/bfs/archive/abc.jpg","duration":"4:03","play":1000},
      {"type":"ketang","id":32217,"aid":32217,"bvid":"","title":"手绘教程",
       "author":"小艺手绘","pic":"//i0.hdslb.com/x.jpg","duration":"10:00"},
      {"type":"video","id":116083369449336,"aid":116083369449336,"bvid":"BV1dtZ5BkET8",
       "title":"初音未来【睡前必看系列","author":"千代敏儿",
       "pic":"http://i1.hdslb.com/bfs/archive/def.jpg","duration":"1:03:22"}
    ]}}
    """.trimIndent()

    @Test
    fun `搜索只认 video 条目 并且标题去高亮标签`() {
        val tracks = BiliParse.parseSearchTracks(searchJson, limit = 20)
        assertEquals("ketang 条目必须被丢掉（它没有可提取的音轨）", 2, tracks.size)
        assertEquals("mmd初音未来VS德莱文和黑岩", tracks[0].title)
        assertEquals("翼权", tracks[0].author)
        assertEquals("BV1bhau6REvc", tracks[0].bvid)
        assertEquals(117329648162166L, tracks[0].aid)
    }

    @Test
    fun `封面补全协议头 并且 http 抬成 https`() {
        val tracks = BiliParse.parseSearchTracks(searchJson, limit = 20)
        assertEquals("https://i2.hdslb.com/bfs/archive/abc.jpg", tracks[0].coverUrl)
        assertEquals("https://i1.hdslb.com/bfs/archive/def.jpg", tracks[1].coverUrl)
    }

    @Test
    fun `duration 两种形状都认`() {
        assertEquals(4 * 60_000L + 3_000L, BiliParse.parseDuration("4:03"))
        assertEquals(3600_000L + 3 * 60_000L + 22_000L, BiliParse.parseDuration("1:03:22"))
        // 音频区给的是秒
        assertEquals(112_000L, BiliParse.parseDuration(112))
        assertEquals(112_000L, BiliParse.parseDuration("112"))
        // 认不出时返回 0（调用方据此不显示时长，而不是显示 0:00）
        assertEquals(0L, BiliParse.parseDuration(null))
        assertEquals(0L, BiliParse.parseDuration(""))
        assertEquals(0L, BiliParse.parseDuration("abc"))
    }

    @Test
    fun `limit 会截断`() {
        assertEquals(1, BiliParse.parseSearchTracks(searchJson, limit = 1).size)
        assertEquals(0, BiliParse.parseSearchTracks(searchJson, limit = 0).size)
    }

    @Test
    fun `坏响应不抛异常 只返回空`() {
        // 契约：解析层绝不抛。B 站回 HTML（412 那种）时上层只该看到"没有结果"。
        assertTrue(BiliParse.parseSearchTracks(null, 10).isEmpty())
        assertTrue(BiliParse.parseSearchTracks("", 10).isEmpty())
        assertTrue(BiliParse.parseSearchTracks("<html>412</html>", 10).isEmpty())
        assertTrue(BiliParse.parseSearchTracks("{\"code\":-1200}", 10).isEmpty())
    }

    @Test
    fun `title 清洗覆盖实体与其它标签`() {
        assertEquals("a&b", BiliParse.cleanTitle("a&amp;b"))
        // 其它标签一律去掉（`<br>` 也不留 —— 列表渲染的是纯文本）
        assertEquals("ab", BiliParse.cleanTitle("a<br>b"))
        assertEquals("引号\"", BiliParse.cleanTitle("引号&quot;"))
    }

    // ---------------------------------------------------------------- view（cid）

    @Test
    fun `parseCid 认单P与多P`() {
        assertEquals(674294080L, BiliParse.parseCid("""{"code":0,"data":{"cid":674294080}}"""))
        assertEquals(
            111L,
            BiliParse.parseCid("""{"code":0,"data":{"cid":0,"pages":[{"cid":111}]}}"""),
        )
        assertNull(BiliParse.parseCid("""{"code":0,"data":{}}"""))
        assertNull(BiliParse.parseCid("nonsense"))
    }

    // ---------------------------------------------------------------- playurl（DASH）

    @Test
    fun `DASH 只取音频 且取带宽最高那条`() {
        val json = """
        {"code":0,"data":{"dash":{
          "duration":243,
          "audio":[
            {"id":30280,"baseUrl":"https://upos.example/low.m4s?deadline=1790540864","bandwidth":64000,"mimeType":"audio/mp4","codecs":"mp4a.40.2"},
            {"id":30232,"baseUrl":"https://upos.example/high.m4s?deadline=1790540864","bandwidth":132000,"mimeType":"audio/mp4","codecs":"mp4a.40.2"}
          ],
          "video":[{"baseUrl":"https://upos.example/video.m4s","bandwidth":9999999}]
        }}}
        """.trimIndent()
        val stream = BiliParse.parseDashAudio(json, nowMs = 1_790_540_000_000L)
        assertNotNull(stream)
        assertTrue("必须取带宽最高的那条音频", stream!!.url.contains("high.m4s"))
        assertFalse("绝不能取视频流", stream.url.contains("video.m4s"))
        assertEquals(132_000L, stream.br)
        assertEquals("m4a", stream.container)
    }

    @Test
    fun `DASH 在 baseUrl 缺失时退 backupUrl`() {
        val json = """
        {"code":0,"data":{"dash":{"audio":[
          {"bandwidth":132000,"mimeType":"audio/mp4","codecs":"mp4a.40.2",
           "backupUrl":["https://backup.example/a.m4s?deadline=1790540864"]}
        ]}}}
        """.trimIndent()
        val stream = BiliParse.parseDashAudio(json, nowMs = 1_790_540_000_000L)
        assertEquals("https://backup.example/a.m4s?deadline=1790540864", stream?.url)
    }

    @Test
    fun `DASH 没有 audio 数组时返回 null`() {
        assertNull(BiliParse.parseDashAudio("""{"code":0,"data":{"dash":{"video":[]}}}"""))
        assertNull(BiliParse.parseDashAudio("""{"code":0,"data":{}}"""))
    }

    // ---------------------------------------------------------------- 音频区 song/info

    /** `/audio/music-service-c/web/song/info?sid=2478206` 的真实响应（`intro` 截断）。 */
    private val audioInfoJson = """
    {"code":0,"data":{"id":2478206,"uid":5669526,"uname":"MitchieM",
    "author":"初音未来, MEIKO · Mitchie M",
    "title":"【Mitchie M】Nechusho No!No! (feat. 初音未来 & MEIKO)",
    "cover":"http://i0.hdslb.com/bfs/music/dee637baa58ec632ebf928312dfd8986611a465b.jpg",
    "lyric":"[00:00.00] 作词 : Mitchie M\n[00:01.00] 作曲 : Mitchie M",
    "duration":112,"bvid":"","aid":0,"cid":0,"statistic":{"sid":2478206,"play":252991}}}
    """.trimIndent()

    @Test
    fun `音频区详情逐字段解析`() {
        val t = BiliParse.parseAudioInfo(audioInfoJson)
        assertNotNull(t)
        assertEquals(2478206L, t!!.auid)
        assertEquals("初音未来, MEIKO · Mitchie M", t.author)
        assertEquals(112_000L, t.durationMs)
        // `http://i0.hdslb.com/...` → `https://i0.hdslb.com/...`（http 抬成 https）
        assertEquals(
            "https://i0.hdslb.com/bfs/music/dee637baa58ec632ebf928312dfd8986611a465b.jpg",
            t.coverUrl,
        )
        assertTrue("歌词必须是 LRC 原文", t.lyric!!.startsWith("[00:00.00]"))
        assertTrue(t.isAudioZone)
    }

    @Test
    fun `音频区下架的音频返回 null`() {
        // 实测响应：{"code":4511001,"data":null,"message":"音频未找到或已下架"}
        assertNull(
            BiliParse.parseAudioInfo(
                """{"code":4511001,"data":null,"message":"音频未找到或已下架","msg":"音频未找到或已下架"}""",
            ),
        )
    }

    @Test
    fun `audio 用 author 优先 uname 兜底`() {
        val noAuthor = """{"code":0,"data":{"id":1,"uname":"UP主","title":"t","duration":10,"lyric":""}}"""
        assertEquals("UP主", BiliParse.parseAudioInfo(noAuthor)?.author)
    }

    @Test
    fun `lyric 字段缺失是 null 空串是空串（两义性必须保住）`() {
        val missing = """{"code":0,"data":{"id":1,"uname":"u","title":"t","duration":10}}"""
        assertNull("没有 lyric 键 = 这个数据源没有歌词", BiliParse.parseAudioInfo(missing)?.lyric)
        val blank = """{"code":0,"data":{"id":1,"uname":"u","title":"t","duration":10,"lyric":""}}"""
        assertEquals("lyric 为空串 = 这首歌确实没有歌词", "", BiliParse.parseAudioInfo(blank)?.lyric)
    }

    // ---------------------------------------------------------------- 音频区 song/url + TTL

    /**
     * `/audio/music-service-c/web/url?sid=2478206&quality=1` 的真实响应形状。
     *
     * **关键实测事实**：`timeout` = 10800（3h），而 URL 的 `deadline` = now + 7200（2h）。
     * 两者不一致，且 URL 自己那个更保守 —— 铁律 26 的 TTL 必须按 deadline 算。
     */
    private val audioUrlJson = """
    {"code":0,"data":{"sid":2478206,"title":"","cover":"","type":1,"info":"",
    "timeout":10800,"size":168,
    "cdns":["https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/0e35503a10eddafee44a1dfee6ff09f7-192k.m4a?e=xxx\u0026uipk=5\u0026nbs=1\u0026deadline=1790540858\u0026gen=playurlv2"]}}
    """.trimIndent()

    @Test
    fun `音频流取 cdns 第一条 并从文件名反推档位与容器`() {
        val s = BiliParse.parseAudioStream(audioUrlJson, nowMs = 1_790_533_658_000L)
        assertNotNull(s)
        assertTrue(s!!.url.contains("192k.m4a"))
        assertEquals("192K", s.qualityLabel)
        assertEquals("m4a", s.container)
    }

    @Test
    fun `TTL 按 deadline 算 而不是按 timeout（实测两者差 1 小时）`() {
        val now = 1_790_533_658_000L
        val s = BiliParse.parseAudioStream(audioUrlJson, nowMs = now)!!
        // deadline = 1790540858 ⇒ 秒级；减 60s 安全边距。
        val expected = 1_790_540_858_000L - BiliParse.SAFETY_MARGIN_MS
        assertEquals(expected, s.expiresAtMs)
        // 若错信 timeout（10800s）会得到 now + 3h - 60s，比正确答案晚整整 1 小时。
        assertTrue(
            "按 timeout 算会比 deadline 晚 1 小时 —— 那 1 小时里 URL 已经 403 了",
            s.expiresAtMs < now + 10_800_000L,
        )
    }

    @Test
    fun `deadline 缺失时退回 timeout 再退回默认 TTL`() {
        val now = 1_000_000_000_000L
        assertEquals(now + 10_800_000L - BiliParse.SAFETY_MARGIN_MS, BiliParse.expiryFromUrl("https://x/a.m4a", 10800L, now))
        assertEquals(now + BiliParse.DEFAULT_TTL_MS - BiliParse.SAFETY_MARGIN_MS, BiliParse.expiryFromUrl("https://x/a.m4a", null, now))
        assertEquals(now + BiliParse.DEFAULT_TTL_MS - BiliParse.SAFETY_MARGIN_MS, BiliParse.expiryFromUrl("https://x/a.m4a", 0L, now))
    }

    @Test
    fun `deadline 已经过去时返回一个已过期的时刻`() {
        // 绝不"向上取一个最小值" —— 那等于把一条死链说成还能用 5 分钟。
        val now = 1_790_600_000_000L
        val expiry = BiliParse.expiryFromUrl("https://x/a.m4a?deadline=1790540858", null, now)
        assertTrue("已经过期的 URL 必须算过期", expiry < now)
    }

    @Test
    fun `deadlineOf 能解转义过的 query`() {
        // JSON 里 `\u0026`、URL 里 `&` —— 两种形态都要能取到 deadline。
        assertEquals(1790540858L, BiliParse.deadlineOf("https://x/a?e=1&deadline=1790540858&b=2"))
        assertEquals(1790540858L, BiliParse.deadlineOf("https://x/a?e=1%26deadline=1790540858"))
        assertNull(BiliParse.deadlineOf("https://x/a"))
        assertNull(BiliParse.deadlineOf("https://x/a?deadline="))
    }

    @Test
    fun `音频流在下架或空 cdns 时返回 null`() {
        assertNull(BiliParse.parseAudioStream("""{"code":4511001,"data":null}"""))
        assertNull(BiliParse.parseAudioStream("""{"code":0,"data":{"cdns":[]}}"""))
        assertNull(BiliParse.parseAudioStream("""{"code":0,"data":{"cdns":["",""]}}"""))
    }

    // ---------------------------------------------------------------- nav

    @Test
    fun `nav 解析 wbi 密钥`() {
        val json = """
        {"code":-101,"message":"账号未登录","data":{"isLogin":false,
        "wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
        "sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}
        """.trimIndent()
        val keys = BiliParse.parseNavWbiKeys(json)
        assertNotNull("未登录也必须给 wbi 密钥（匿名可用）", keys)
        assertEquals("7cd084941338484aae1ad9425b84077c", BiliWbi.fileStem(keys!!.first))
    }

    @Test
    fun `nav 缺字段返回 null`() {
        assertNull(BiliParse.parseNavWbiKeys("""{"code":0,"data":{}}"""))
        assertNull(BiliParse.parseNavWbiKeys("""{"code":0,"data":{"wbi_img":{"img_url":""}}}"""))
        assertNull(BiliParse.parseNavWbiKeys(null))
    }
}

/**
 * `BiliTrack` → `SongItem` 的映射，以及 id 隔离（v3.1.0 的 P0 级风险点）。
 */
class BiliTrackMappingTest {

    @Test
    fun `音频区曲目的 id 带 B 站标志位 且与网易云同号不会撞`() {
        val t = BiliTrack(auid = 2478206L, title = "t", author = "a", coverUrl = "", durationMs = 1000L)
        val song = t.toSongItem()!!
        assertEquals(MusicSource.BILIBILI, song.musicSource)
        assertTrue(SourceIds.isBiliId(song.id))
        assertEquals(2478206L, SourceIds.biliRawId(song.id))
        assertEquals("au:2478206", song.sourceId)
        // 关键：同一个数字在网易云域里是**另一首歌**，两者不相等。
        assertFalse(song.id == 2478206L)
        assertEquals(MusicSource.NETEASE, SourceIds.sourceOfId(2478206L))
    }

    @Test
    fun `视频音轨的 id 与载荷形状`() {
        val t = BiliTrack(bvid = "BV1bhau6REvc", aid = 117329648162166L, title = "t", author = "a", coverUrl = "", durationMs = 1000L)
        val song = t.toSongItem()!!
        assertEquals(MusicSource.BILIBILI, song.musicSource)
        assertEquals("bv:BV1bhau6REvc:0", song.sourceId)
        assertEquals(117329648162166L, SourceIds.biliRawId(song.id))
    }

    @Test
    fun `载荷往返解析`() {
        val audio = BiliTrack.parseSourceId("au:2478206")!!
        assertTrue(audio.isAudioZone)
        assertEquals(2478206L, audio.auid)
        val video = BiliTrack.parseSourceId("bv:BV1bhau6REvc:674294080")!!
        assertFalse(video.isAudioZone)
        assertEquals("BV1bhau6REvc", video.bvid)
        assertEquals(674294080L, video.cid)
        // 解析不出就返回 null，**不猜**（猜错就是拿别人的歌去取链）。
        assertNull(BiliTrack.parseSourceId(null))
        assertNull(BiliTrack.parseSourceId(""))
        assertNull(BiliTrack.parseSourceId("qqmusic:123"))
        assertNull(BiliTrack.parseSourceId("au:abc"))
        assertNull(BiliTrack.parseSourceId("au:0"))
    }

    @Test
    fun `造不出 id 的条目整个丢掉 而不是塞一个假 id`() {
        val t = BiliTrack(auid = null, bvid = null, aid = null, title = "t", author = "a", coverUrl = "", durationMs = 0L)
        assertNull(t.toSongItem())
        assertNull(t.sourceId)
    }

    @Test
    fun `转出的 SongItem 带着音源与时长（下游路由全靠这两个字段）`() {
        val t = BiliTrack(auid = 9L, title = "歌名", author = "UP", coverUrl = "https://c/x.jpg", durationMs = 112_000L)
        val song = t.toSongItem()!!
        assertEquals(MusicSource.BILIBILI.key, song.source)
        assertEquals("歌名", song.name)
        assertEquals("UP", song.artists!!.first().name)
        assertEquals("https://c/x.jpg", song.album!!.picUrl)
        assertEquals(112_000L, song.duration)
        // 空作者不该产出一个空名字的艺人（列表上会渲染成一行空白）。
        val blank = BiliTrack(auid = 10L, title = "x", author = "", coverUrl = "", durationMs = 0L).toSongItem()!!
        assertEquals("Bilibili", blank.artists!!.first().name)
        assertNull("时长为 0 时不写进 SongItem（展示层据此不显示 0:00）", blank.duration)
    }
}
