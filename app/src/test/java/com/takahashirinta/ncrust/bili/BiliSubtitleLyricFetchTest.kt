package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.lyric.LrcParser
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v3.3.1：**B 站视频轨取词的字幕链路**（端到端，假传输层，完全离线）。
 *
 * ## 这条测试是为一个真实事故写的（用户实测「B站歌词还是拉取不上，账号已登录」）
 *
 * v3.3.0 我实现了「视频字幕当歌词」，判据与解析都有单测 ——
 * **但取词链路一次请求都没发过**，而所有既有用例都是绿的。
 *
 * 根因：视频搜索接口**不返回 cid**（`parseSearchTracks` 只给 bvid/aid），
 * 所以 `toSourceId` 产出 `bv:<bvid>:**0**`，而 `parseSourceId` 里的
 * `takeIf { it > 0L }` 又把它还原成 `null` —— **正常播放路径下 `payload.cid` 恒为 null**。
 *
 * 取流那条路早就知道这件事（`fetchStream` 写的是
 * `payload.cid ?: videoCid(bvid)`），而我在取词那条路上只写了 `?: return null`
 * ⇒ 静默返回 null ⇒ 界面显示「暂无歌词」，与「这首歌确实没有字幕」**完全同形**。
 *
 * 教训是仓库已有的那一条：**同一个前提在两条链路上不能有两种写法**。
 * 所以这条用例的判据不是「解析对不对」（那有别的用例），而是
 * **「请求到底发出去了没有、cid 有没有被补问出来」** ——
 * 也就是上一版唯一没人守着的那一环。
 *
 * ## 为什么完全离线也成立
 *
 * 与 `BiliApiDispatchTest` 同款：注入的 client 的 interceptor 直接回罐头响应、
 * **不调用 `chain.proceed`** ⇒ 没有 DNS、没有 socket、没有超时。
 * 罐头响应的形状照实测原文抄（`view` → cid；`player/wbi/v2` → ai-zh 字幕；字幕正文 → LRC）。
 */
class BiliSubtitleLyricFetchTest {

    private companion object {
        const val BVID = "BV1cN4y167BJ"
        const val CID = 1381738635L

        /** 实测形状：`view` 给 `data.cid`（多 P 视频走 `pages[0].cid`，这里给单 P）。 */
        val VIEW = """{"code":0,"message":"OK","data":{"aid":877869754,"bvid":"$BVID","cid":$CID}}"""

        /**
         * 实测形状（登录态）：`player/wbi/v2` 的 `data.subtitle.subtitles[0]`。
         *
         * `lan` 是 **`ai-zh`**（B 站 AI 自动生成），这是绝大多数音乐视频的实际形态。
         * `subtitle_url` 是**协议相对**的（`//` 开头）—— 补协议那一步是
         * `BiliSubtitle.normalizeSubtitleUrl` 的职责，这里如实照抄实测形状。
         */
        val PLAYER_V2 = """{"code":0,"message":"OK","data":{"aid":877869754,"cid":$CID,""" +
            """"subtitle":{"allow_submit":false,"subtitles":[""" +
            """{"id":1387771279457024256,"lan":"ai-zh","lan_doc":"中文","is_lock":false,""" +
            """"subtitle_url":"//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/877869754$CID.json"}]}}}"""

        /**
         * 字幕正文。前 6 条是 MV 开头的**人声对白**（`music=0.0`），后 6 条是**歌词**
         * （`music≈1.0` + `♪` 包裹）—— 形状照 `BV1cN4y167BJ` 的实测原文。
         */
        val SUBTITLE_BODY = """{"font_size":0.4,"type":"json","lang":"zh","body":[""" +
            """{"from":7.94,"to":8.96,"content":"昨晚他来了吗","music":0.0},""" +
            """{"from":10.88,"to":11.72,"content":"我也不知道","music":0.0},""" +
            """{"from":11.72,"to":14.02,"content":"好像是没来吧","music":0.0},""" +
            """{"from":14.54,"to":15.43,"content":"没事儿","music":0.0},""" +
            """{"from":15.43,"to":16.24,"content":"下礼拜你专场","music":0.0},""" +
            """{"from":16.24,"to":18.76,"content":"他一定会来的","music":0.0},""" +
            """{"from":59.46,"to":66.84,"content":"♪ 让我掉下眼泪的不止昨夜的酒 ♪","music":0.9999998387097035},""" +
            """{"from":67.30,"to":74.82,"content":"♪ 让我依依不舍的不止你的温柔 ♪","music":0.9999998387097035},""" +
            """{"from":75.28,"to":79.11,"content":"♪ 余路还要走多久 ♪","music":0.9999998387097035},""" +
            """{"from":79.11,"to":82.71,"content":"♪ 你攥着我的手 ♪","music":0.9999998387097035},""" +
            """{"from":83.17,"to":91.16,"content":"♪ 让我感到为难的是挣扎的自由 ♪","music":0.9999998387097035},""" +
            """{"from":93.06,"to":96.89,"content":"♪ 分别总是在9月 ♪","music":0.9999998387097035}]}"""

        /** 已登录时的字幕列表；未登录时服务端返回**有 data 但 subtitles 为空数组**（实测）。 */
        val PLAYER_V2_ANONYMOUS = """{"code":0,"message":"OK","data":{"subtitle":{"subtitles":[]}}}"""
    }

    private val requested = Collections.synchronizedList(ArrayList<String>())
    private val count = AtomicInteger(0)

    @Before
    fun setUp() {
        requested.clear()
        count.set(0)
        BiliPrefs.setEnabledForTest(true)
    }

    @After
    fun tearDown() {
        BiliApi.clientForTest = null
        BiliPrefs.setEnabledForTest(false)
    }

    private fun installClient(anonymous: Boolean = false) {
        BiliApi.clientForTest = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requested += url
                count.incrementAndGet()
                val body = when {
                    url.contains("/x/web-interface/view") -> VIEW
                    url.contains("/x/player/wbi/v2") -> if (anonymous) PLAYER_V2_ANONYMOUS else PLAYER_V2
                    url.contains("aisubtitle.hdslb.com") -> SUBTITLE_BODY
                    else -> """{"code":0}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    /** 一条 B 站**视频轨**曲目。`sourceId` 用搜索路径的真实形状：cid 位是 **0**。 */
    private fun biliVideoSong(sourceId: String = "bv:$BVID:0"): SongItem = SongItem(
        id = SourceIds.biliId(877869754L),
        name = "【官方MV】赵雷 - 成都",
        artists = emptyList(),
        album = null,
        duration = 325_000L,
        source = MusicSource.BILIBILI.key,
        sourceId = sourceId,
        mediaId = null,
    )

    // ------------------------------------------------------------ 核心回归判据 ----

    @Test
    fun `搜索路径的 sourceId（cid 位为 0）也必须真的去取字幕`() {
        // ★ 这是上一版失效的那一步。cid 位是 0 ⇒ `payload.cid == null` ⇒
        // 取词必须**像取流一样补问一次 `view`**，否则整条链路一个请求都不发。
        installClient()

        val lrc = runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong()) }

        assertTrue(
            "必须真的发出了请求（上一版的缺陷就是一个请求都没发）：$requested",
            count.get() > 0,
        )
        assertTrue(
            "必须补问 `view` 拿 cid（搜索接口不返回它）：$requested",
            requested.any { it.contains("/x/web-interface/view") },
        )
        assertTrue(
            "必须请求 `player/wbi/v2` 取字幕列表：$requested",
            requested.any { it.contains("/x/player/wbi/v2") },
        )
        assertTrue(
            "必须真的下载字幕正文：$requested",
            requested.any { it.contains("aisubtitle.hdslb.com") },
        )
        assertTrue("必须返回歌词正文，实际=${lrc?.take(60)}", !lrc.isNullOrBlank())
    }

    @Test
    fun `补问 cid 之后 player 请求里带的是真的 cid`() {
        // 「请求发出去了」还不够 —— cid 传错（例如传 0）服务端会返回空字幕列表，
        // 表现与「没有字幕」同形。所以判据要落到**参数值**上。
        installClient()

        runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong()) }

        val playerUrl = requested.firstOrNull { it.contains("/x/player/wbi/v2") }
        assertTrue("应有 player 请求", playerUrl != null)
        assertTrue(
            "player 请求必须带补问出来的 cid=$CID，实际=$playerUrl",
            playerUrl!!.contains("cid=$CID"),
        )
        assertTrue("player 请求必须带 bvid=$BVID，实际=$playerUrl", playerUrl.contains("bvid=$BVID"))
    }

    @Test
    fun `cid 已经在载荷里时不再多问一次 view`() {
        // 预载路径会把 cid 带下来（`bv:<bvid>:<cid>`）。那时不该再花一次往返。
        installClient()

        runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong("bv:$BVID:$CID")) }

        assertTrue(
            "cid 已有时不该再问 view：$requested",
            requested.none { it.contains("/x/web-interface/view") },
        )
        assertTrue(
            "仍必须去取字幕",
            requested.any { it.contains("/x/player/wbi/v2") },
        )
    }

    // ------------------------------------------------------------ 内容与降级 ----

    @Test
    fun `返回的是歌词而不是开场对白`() {
        installClient()

        val lrc = runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong()) }!!
        val lines = LrcParser.parse(lrc)

        assertEquals("6 条歌词应全部保留", 6, lines.size)
        assertEquals("让我掉下眼泪的不止昨夜的酒", lines[0].text)
        assertEquals(59_460L, lines[0].timeMs)
        assertTrue(
            "MV 开头的人声对白不得混进歌词：${lines.map { it.text }}",
            lines.none { it.text.contains("昨晚他来了吗") || it.text.contains("我也不知道") },
        )
        assertTrue("♪ 标记必须被剥掉", lines.none { it.text.contains("♪") })
    }

    @Test
    fun `未登录时字幕列表为空 —— 返回空串（确实没有）而不是 null（取不到）`() {
        // 服务端在未登录时返回 `code:0` + 空的 `subtitles` 数组（实测），
        // 不是错误。这一条的语义必须是「这首没有歌词正文」——
        // 重试一百次也不会变，界面不该给可点的重试。
        installClient(anonymous = true)

        val result = runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong()) }

        assertEquals("未登录 = 没有字幕 = 空串", "", result)
    }

    @Test
    fun `网络失败返回 null 而不是空串 —— 保住「取不到」这一义`() {
        // `PlayerViewModel.loadBiliLyrics` 按这个区分处置：
        // null/异常 ⇒ fail（按钮保持可点、可重试）；空串 ⇒ markEmpty（稳定空态）。
        // 把失败折叠成空串会让重试按钮永久置灰 —— 那正是 v3.3.0 修掉的另一个缺陷。
        BiliApi.clientForTest = OkHttpClient.Builder()
            .addInterceptor { throw java.io.IOException("boom") }
            .build()

        val result = runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong()) }

        assertNull("传输失败必须是 null（取不到），不能是空串（确实没有）", result)
    }

    @Test
    fun `开关关闭时一个请求都不发`() {
        BiliPrefs.setEnabledForTest(false)
        installClient()

        val result = runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong()) }

        assertEquals("开关关闭时不得有任何请求", 0, count.get())
        assertNull(result)
    }

    @Test
    fun `sourceId 不可解析时不发请求也不崩`() {
        installClient()

        val result = runBlocking { BiliSourceProvider.fetchLyric(biliVideoSong("garbage")) }

        assertEquals("解析不出载荷 ⇒ 不发请求", 0, count.get())
        assertNull(result)
    }
}
