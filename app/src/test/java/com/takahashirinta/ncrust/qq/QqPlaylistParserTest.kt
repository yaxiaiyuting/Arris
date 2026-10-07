/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：QQ 歌单响应解析的单测。夹具是**真机实测响应**（字段名与取值原样，
 * 只有昵称/歌单名/歌名等用户内容被替换成占位文本）。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqPlaylistParser] 的单测（v2.2.0）。
 *
 * 夹具的来源：`docs/verification/v2.2.0/qq-playlist-probe/probe-*.json`
 * （2026-09-25，设备 `3B15CD00GB700000`，四轮真机实测）。
 * **字段名与真实响应逐字一致** —— 这正是本测试的价值所在：第 1 轮探针就是因为
 * 按参考实现的别名（`dissid`/`dirid`/`songnum`）去读，在真实响应上全部拿到 null。
 */
class QqPlaylistParserTest {

    private val owner = "10001"

    /** 真实 `GetPlaylistByUin` 响应（截取两条，字段名与取值原样；歌单名已替换）。 */
    private val realListResponse = JSONObject(
        """
        {"code":0,"data":{"total":2,"bFinish":true,"v_delTid":[],
         "v_playlist":[
          {"dirId":201,"dirName":"我喜欢","tid":6364233546,"opType":5,"songNum":1,
           "createTime":1790314465,"updateTime":1618134646,"dirShow":1,"status":11,
           "picUrl":"http://y.gtimg.cn/music/photo_new/T002R300x300M000003L8Oq94De6Fy.jpg",
           "bigpicUrl":"http://y.gtimg.cn/music/photo_new/T002R500x500M000003L8Oq94De6Fy.jpg",
           "uin":"10001","sortWeight":0,"strTagIdList":"","invalid":false,"nick":"",
           "albumPicUrl":"","desc":"","avatar":"","identIcon":"","layerUrl":"",
           "ext1":"","ext2":"","tagNameList":"","ext":null,"fav_cnt":0,"play_cnt":0,
           "comment_cnt":0,"avatar_null":""},
          {"dirId":1,"dirName":"新建歌单1","tid":8591804616,"opType":5,"songNum":22,
           "createTime":1660772575,"updateTime":1660772594,"dirShow":1,"status":11,
           "picUrl":"http://y.gtimg.cn/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg",
           "bigpicUrl":"","uin":"10001","sortWeight":10001,"invalid":false,"nick":""}
         ]}}
        """.trimIndent()
    )

    /** 真实 `CgiGetDiss` 响应（`dirinfo` 是**下划线**命名，`songlist` 里 `mid != file.media_mid`）。 */
    private val realDetailResponse = JSONObject(
        """
        {"code":0,"songlist_size":10,"total_song_num":22,"hasmore":1,
         "dirinfo":{"id":8591804616,"dirid":1,"title":"新建歌单1",
                    "picurl":"https://y.gtimg.cn/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg",
                    "songnum":22,"host_uin":"10001","encrypt_uin":"ABCDEF0123456789ABCDEF0123",
                    "dirtype":1,"disstype":0,"owndir":1},
         "songlist":[
          {"id":263167477,"mid":"002n3u4D2u8DLn","name":"甲","title":"甲","type":1,
           "interval":253,"songtype":13,
           "singer":[{"id":4558,"mid":"0025NhlN2yWrP4","name":"歌手甲"}],
           "album":{"id":8220,"mid":"000MkMni19ClKG","pmid":"000MkMni19ClKG_5","name":"专辑甲"},
           "file":{"media_mid":"002n3u4D2u8DLn","size_128mp3":4317292},
           "pay":{"pay_play":0}},
          {"id":102792543,"mid":"0036avMK009ptj","name":"乙","title":"乙","type":1,
           "interval":240,"songtype":13,
           "singer":[{"id":4558,"mid":"0025NhlN2yWrP4","name":"歌手甲"}],
           "album":{"id":8221,"mid":"000MkMni19ClKH","pmid":"000MkMni19ClKH_5","name":"专辑乙"},
           "file":{"media_mid":"002OVQbr00xbs6","size_128mp3":4000000},
           "pay":{"pay_play":1}}
         ]}
        """.trimIndent()
    )

    private val realUserInfoResponse = JSONObject(
        """
        {"code":0,"data":{"retCode":0,"identify":0,
          "info":{"nick":"某用户","logo":"https://thirdwx.qlogo.cn/mmopen/abc/132",
                  "logos":null,"bgPic":"","ifpicurl":"","registerDate":0}}}
        """.trimIndent()
    )

    // ------------------------------------------------------------ 错误码 ----

    @Test
    fun `业务码到结果分类的映射`() {
        assertTrue(QqPlaylistParser.classify(0) is QqPlaylistResult.Ok)
        assertTrue("未登录", QqPlaylistParser.classify(1000) is QqPlaylistResult.NeedLogin)
        assertTrue("非法 uin 也归到登录问题", QqPlaylistParser.classify(80030) is QqPlaylistResult.NeedLogin)
        assertTrue("目录不存在", QqPlaylistParser.classify(10004) is QqPlaylistResult.NotFound)
        assertEquals(
            QqPlaylistResult.Failure(80050),
            QqPlaylistParser.classify(80050),
        )
    }

    /**
     * **登录态过期**：`GetLoginUserInfo` 返回 1000 是实测中唯一可靠的过期信号
     * （其余读接口在没有 cookie 时照样返回数据，见探针 §5）。
     */
    @Test
    fun `用户信息接口返回 1000 时判为登录过期`() {
        val expired = JSONObject("""{"code":1000,"subcode":0,"msg":"need login"}""")
        assertEquals(QqPlaylistResult.NeedLogin, QqPlaylistParser.parseUserInfo(expired))
    }

    @Test
    fun `歌单列表返回 1000 时判为登录过期`() {
        val expired = JSONObject("""{"code":1000}""")
        assertEquals(
            QqPlaylistResult.NeedLogin,
            QqPlaylistParser.parseOwnedPlaylists(expired, owner),
        )
    }

    @Test
    fun `收藏列表返回 80050 时判为客户端 bug 而不是未登录`() {
        // 传了裸 uin（应当传 encrypt_uin）。这是客户端错误，不该让用户去重新登录。
        val bad = JSONObject("""{"code":80050}""")
        assertEquals(
            QqPlaylistResult.Failure(80050),
            QqPlaylistParser.parseFavPlaylists(bad, owner),
        )
    }

    // -------------------------------------------------------------- 列表 ----

    @Test
    fun `解析自建歌单列表并用 camelCase 字段`() {
        val r = QqPlaylistParser.parseOwnedPlaylists(realListResponse, owner)
        assertTrue(r is QqPlaylistResult.Ok)
        val list = (r as QqPlaylistResult.Ok).value
        assertEquals(2, list.size)

        val fav = list[0]
        // ★ key.id 用的是 **tid**（全局唯一），不是 dirId（账号内编号）。
        //   用 dirId 当 id 会让两个账号的 dirId=1 撞成同一个缓存槽位。
        assertEquals("tid 才是全局 id", "6364233546", fav.key.id)
        assertEquals(owner, fav.key.ownerId)
        assertEquals(MusicSource.QQMUSIC, fav.key.source)
        assertEquals(201L, fav.dirId)
        assertTrue("dirId=201 就是我喜欢的标记", fav.isFavorite)
        assertTrue(fav.isOwned)
        assertEquals(1, fav.trackCount)
        assertEquals(1_618_134_646L, fav.updatedAt)

        val own = list[1]
        assertEquals("8591804616", own.key.id)
        assertEquals(1L, own.dirId)
        assertFalse(own.isFavorite)
        assertEquals(22, own.trackCount)
    }

    /** 实测列表接口给的是 `http://`；不升到 https 在部分 ROM 上封面会被静默拦掉。 */
    @Test
    fun `列表封面从 http 升到 https`() {
        val list = (QqPlaylistParser.parseOwnedPlaylists(realListResponse, owner) as QqPlaylistResult.Ok).value
        assertTrue(list[0].coverUrl!!.startsWith("https://"))
        assertFalse(list[0].coverUrl!!.startsWith("http://"))
    }

    /** 既没有 `tid` 也没有 `dirId` 的条目无法定身份 ⇒ 丢弃，不造一个假 id。 */
    @Test
    fun `缺少 id 的条目被丢弃`() {
        val resp = JSONObject(
            """{"code":0,"data":{"v_playlist":[{"dirName":"没有 id 的歌单"},{"dirId":9,"dirName":"有 dirId"}]}}"""
        )
        val list = (QqPlaylistParser.parseOwnedPlaylists(resp, owner) as QqPlaylistResult.Ok).value
        assertEquals(1, list.size)
        assertEquals("dir:9", list[0].key.id)
    }

    @Test
    fun `空列表不报错`() {
        val empty = JSONObject("""{"code":0,"data":{"v_playlist":[],"total":0}}""")
        val list = (QqPlaylistParser.parseOwnedPlaylists(empty, owner) as QqPlaylistResult.Ok).value
        assertTrue(list.isEmpty())
    }

    // -------------------------------------------------------------- 详情 ----

    /**
     * **本探针最关键的一条**：`songlist[].mid` 是播放身份、`file.media_mid` 是文件名身份，
     * 两者**实测经常不同**（这一页 10 首里 6 首不同）。必须各存各的。
     */
    @Test
    fun `详情解析保留 songmid 与 media_mid 的区别`() {
        val key = PlaylistKey(MusicSource.QQMUSIC, "8591804616", owner)
        val r = QqPlaylistParser.parseDetailPage(realDetailResponse, key, pageOffset = 0)
        val page = (r as QqPlaylistResult.Ok).value

        assertEquals(2, page.tracks.size)
        assertEquals(2, page.songs.size)
        assertEquals("歌曲与身份必须一一对应", page.songs.size, page.tracks.size)

        // 第 1 首：两者相同
        assertEquals("002n3u4D2u8DLn", page.songs[0].sourceId)
        assertEquals("002n3u4D2u8DLn", page.songs[0].mediaId)
        // 第 2 首：两者**不同** —— 这正是 v2.1.0 的教训
        assertEquals("0036avMK009ptj", page.songs[1].sourceId)
        assertEquals("002OVQbr00xbs6", page.songs[1].mediaId)
        assertEquals("002OVQbr00xbs6", page.tracks[1].trackKey.mediaId)

        // 身份与序号
        assertEquals(0, page.tracks[0].order)
        assertEquals(1, page.tracks[1].order)
        assertTrue("数字 id 必须带 QQ 标志位", page.tracks[1].trackKey.id > 0L)
        assertEquals(MusicSource.QQMUSIC, page.tracks[1].trackKey.source)
    }

    @Test
    fun `详情解析出 total hasmore dirId encryptUin 与封面`() {
        val key = PlaylistKey(MusicSource.QQMUSIC, "8591804616", owner)
        val page = (QqPlaylistParser.parseDetailPage(realDetailResponse, key, 0) as QqPlaylistResult.Ok).value
        assertEquals(22, page.total)
        assertTrue(page.hasMore)
        assertEquals(1L, page.dirId)
        assertEquals(8591804616L, page.tid)
        assertEquals("新建歌单1", page.name)
        assertEquals("ABCDEF0123456789ABCDEF0123", page.encryptUin)
        assertTrue(page.coverUrl!!.startsWith("https://"))
    }

    /**
     * **分页 offset**：第 2 页的 `order` 必须从 `song_begin` 起算。
     * 若每页都从 0 开始，多页合并时序号会互相覆盖，列表顺序就乱了。
     */
    @Test
    fun `第二页的 order 从页偏移起算`() {
        val key = PlaylistKey(MusicSource.QQMUSIC, "8591804616", owner)
        val page = (QqPlaylistParser.parseDetailPage(realDetailResponse, key, pageOffset = 10) as QqPlaylistResult.Ok).value
        assertEquals(10, page.tracks[0].order)
        assertEquals(11, page.tracks[1].order)
    }

    /** 末页：`hasmore=0` + 歌曲数少于页大小。 */
    @Test
    fun `末页 hasmore 为 false`() {
        val last = JSONObject(
            """{"code":0,"total_song_num":22,"hasmore":0,"dirinfo":{"id":8591804616,"dirid":1},
                "songlist":[{"id":5,"mid":"m5","file":{"media_mid":"m5"},"interval":100}]}"""
        )
        val key = PlaylistKey(MusicSource.QQMUSIC, "8591804616", owner)
        val page = (QqPlaylistParser.parseDetailPage(last, key, 21) as QqPlaylistResult.Ok).value
        assertFalse(page.hasMore)
        assertEquals(21, page.tracks[0].order)
    }

    /**
     * 越界 `song_begin`：实测 `code=0` + 0 首 + `hasmore=0`（**不报错**）。
     * 客户端据此安全结束分页。
     */
    @Test
    fun `越界请求返回空页且不报错`() {
        val beyond = JSONObject(
            """{"code":0,"total_song_num":22,"hasmore":0,
                "dirinfo":{"id":8591804616,"dirid":1},"songlist":[]}"""
        )
        val key = PlaylistKey(MusicSource.QQMUSIC, "8591804616", owner)
        val r = QqPlaylistParser.parseDetailPage(beyond, key, 1000)
        assertTrue(r is QqPlaylistResult.Ok)
        val page = (r as QqPlaylistResult.Ok).value
        assertTrue(page.tracks.isEmpty())
        assertFalse("空页必须能被识别为结束", page.hasMore)
    }

    /** 空歌单：`songlist` 数组存在但为空 —— 与「越界」同形，语义都是「没有更多」。 */
    @Test
    fun `空歌单解析为空列表`() {
        val empty = JSONObject(
            """{"code":0,"total_song_num":0,"hasmore":0,
                "dirinfo":{"id":1,"dirid":2,"title":"空歌单"},"songlist":[]}"""
        )
        val key = PlaylistKey(MusicSource.QQMUSIC, "1", owner)
        val page = (QqPlaylistParser.parseDetailPage(empty, key, 0) as QqPlaylistResult.Ok).value
        assertTrue(page.songs.isEmpty())
        assertTrue(page.tracks.isEmpty())
        assertEquals(0, page.total)
    }

    /** `songlist` 整个缺失也要能解析（不能 NPE）。 */
    @Test
    fun `songlist 字段缺失时不崩`() {
        val missing = JSONObject("""{"code":0,"dirinfo":{"id":1,"dirid":2}}""")
        val key = PlaylistKey(MusicSource.QQMUSIC, "1", owner)
        val page = (QqPlaylistParser.parseDetailPage(missing, key, 0) as QqPlaylistResult.Ok).value
        assertTrue(page.songs.isEmpty())
        assertFalse(page.hasMore)
    }

    /** 缺 `mid` 的曲目无法取链 ⇒ 丢弃（QqSongMapper 的既有契约）。 */
    @Test
    fun `缺少 songmid 的曲目被丢弃`() {
        val bad = JSONObject(
            """{"code":0,"total_song_num":2,"hasmore":0,"dirinfo":{"id":1,"dirid":2},
                "songlist":[{"id":1,"name":"没有 mid"},{"id":2,"mid":"m2","file":{"media_mid":"m2"}}]}"""
        )
        val key = PlaylistKey(MusicSource.QQMUSIC, "1", owner)
        val page = (QqPlaylistParser.parseDetailPage(bad, key, 0) as QqPlaylistResult.Ok).value
        assertEquals(1, page.songs.size)
        assertEquals("m2", page.songs[0].sourceId)
        // order 保留的是**服务端位置**（原数组下标），不是「丢弃后的新下标」。
        // 被丢掉的那首在歌单里仍然占第 0 位，后面这首就是第 1 位 ——
        // 按「压缩后的下标」编号会让顺序语义与歌单本身脱钩。
        assertEquals("丢弃条目后 order 仍指向原位置", 1, page.tracks[0].order)
    }

    @Test
    fun `详情返回 10004 时判为不存在`() {
        val notFound = JSONObject("""{"code":10004,"dirinfo":{"dirid":0,"id":0},"songlist":[]}""")
        val key = PlaylistKey(MusicSource.QQMUSIC, "1", owner)
        assertEquals(QqPlaylistResult.NotFound, QqPlaylistParser.parseDetailPage(notFound, key, 0))
    }

    // ---------------------------------------------------------- 用户信息 ----

    @Test
    fun `解析昵称与头像`() {
        val r = QqPlaylistParser.parseUserInfo(realUserInfoResponse)
        val info = (r as QqPlaylistResult.Ok).value
        assertEquals("某用户", info.nick)
        assertTrue(info.avatarUrl!!.startsWith("https://"))
    }

    @Test
    fun `昵称或头像缺失时返回 null 而不是空串`() {
        val partial = JSONObject("""{"code":0,"data":{"info":{"nick":"","logo":""}}}""")
        val info = (QqPlaylistParser.parseUserInfo(partial) as QqPlaylistResult.Ok).value
        assertNull(info.nick)
        assertNull(info.avatarUrl)
    }

    // -------------------------------------------------------- 封面归一化 ----

    @Test
    fun `封面归一化的三种输入`() {
        assertEquals("https://a/b.jpg", QqPlaylistParser.normalizeCover("http://a/b.jpg"))
        assertEquals("https://a/b.jpg", QqPlaylistParser.normalizeCover("https://a/b.jpg"))
        assertNull(QqPlaylistParser.normalizeCover(""))
        assertNull(QqPlaylistParser.normalizeCover(null))
        assertNull(QqPlaylistParser.normalizeCover("   "))
    }
}
