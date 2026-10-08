package com.takahashirinta.ncrust.qq

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v2.1.0 · B：**打真实 qm 服务端**的探针测试。
 *
 * 与其它单测的区别：这些用例真的发 HTTP 请求。它们存在的理由是
 * 「请求形状对不对」这件事**只有服务端能回答** —— 字段名写错、数组不等长、
 * 信封带了 comm，本地怎么测都是绿的，线上表现却只是「搜不到 / 取不到链」。
 *
 * ## 这些用例验证什么（以及不验证什么）
 *
 * ✅ 验证：请求形状被服务端接受、响应能被本仓库的解析器映射成正确的 `SongItem`、
 *    匿名态的错误码与实测一致（`104003`）。
 * ❌ **不验证**：登录态的取链与歌词、会员档位能否真的取到文件 —— 本仓库没有
 *    qm 账号，这条边界在 release notes 的「未验证项」里写明了。
 *
 * 无网络时用 [assumeTrue] **跳过**而不是失败：CI 或离线开发机上跑全量单测不该因此变红。
 */
class QqLiveProbeTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 单独给 CDN 用的客户端：**读超时收紧到 8 秒**。
     *
     * 与 [http] 分开的理由是一条实测事实：`streamoc.music.tc.qq.com` 在部分网络里
     * 会接受连接却永不返回字节，用 15 秒的读超时会让这条用例白等 15 秒才判「跳过」。
     * 真正的音频响应是 2~4 MB，正常网络下 8 秒足够；**慢到 8 秒还没吐出任何字节**
     * 与「这个域名不可达」在测试语境下是同一件事。
     */
    private val cdnHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val appUa = "QQMusic 14090008(android 10)"
    private val webUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun networkAvailable(): Boolean = try {
        val req = Request.Builder().url("https://y.qq.com/").head().build()
        http.newCall(req).execute().use { true }
    } catch (e: Exception) {
        false
    }

    private fun post(url: String, body: JSONObject, ua: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", "https://y.qq.com/")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return JSONObject(text)
        }
    }

    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", webUa)
            .header("Referer", "https://y.qq.com/").build()
        http.newCall(req).execute().use { resp -> return JSONObject(resp.body?.string().orEmpty()) }
    }

    /** 实测里两类 comm 身份各自的固定字段（与 QqClient 的构造保持一致）。 */
    private fun appComm(): JSONObject = JSONObject()
        .put("ct", 11).put("cv", 14090008).put("v", 14090008)
        .put("chid", "10003505").put("tmeAppID", "qqmusic")
        .put("QIMEI36", "0123456789abcdef0123456789abcdef0123")
        .put("OpenUDID", "0123456789abcdef").put("udid", "0123456789abcdef")
        .put("aid", "0123456789abcdef").put("os_ver", "10").put("phonetype", "MI 10")
        .put("uin", "0").put("format", "json")

    // ---------- 搜索 ----------

    @Test
    fun `旧版搜索通道能拿到歌并被本仓库的映射器正确解析`() {
        assumeTrue("无网络，跳过", networkAvailable())
        val json = get(QqRequests.legacySearchUrl("晴天", 5, 1))
        val songs = QqSongMapper.songsFromLegacySearch(json)
        assertTrue("旧版通道应返回结果（实测 3/3 稳定），实际 ${songs.size}", songs.isNotEmpty())

        val first = songs.first()
        assertEquals("晴天", first.name)
        // 三个 id 各就各位：合成 id（带标志位）、songmid、media_mid
        assertTrue(first.id > 0)
        assertTrue(com.takahashirinta.ncrust.source.SourceIds.isQqId(first.id))
        assertTrue("songmid 必须有", !first.sourceId.isNullOrEmpty())
        assertTrue("media_mid 必须有（回落 songmid 时也非空）", !first.mediaId.isNullOrEmpty())
        assertTrue("时长应是秒转毫秒后的正数", (first.duration ?: 0L) > 0L)
        assertTrue("封面 URL 应已拼好", first.album?.picUrl?.startsWith("https://y.qq.com/music/photo_new/") == true)
        assertTrue("歌手应解析出来", first.artists?.isNotEmpty() == true)
    }

    @Test
    fun `搜索到的歌曲的 media_mid 与 songmid 是分开保存的`() {
        assumeTrue("无网络，跳过", networkAvailable())
        val songs = QqSongMapper.songsFromLegacySearch(get(QqRequests.legacySearchUrl("晴天", 10, 1)))
        assumeTrue("没搜到歌，跳过", songs.isNotEmpty())
        // 实测《晴天》两者不同；这里不断言「一定不同」（服务端数据会变），
        // 只断言「两个字段都真的被填过」，以及解析结果可用于拼文件名。
        for (s in songs.take(5)) {
            val fileName = QqQuality.fileNameFor(QqFileType.M500, s.mediaId!!)
            assertTrue("文件名应是 <前缀><media_mid>.mp3", fileName.startsWith("M500") && fileName.endsWith(".mp3"))
            assertFalse("文件名里不能混入 songmid 的旧值", fileName.contains("null"))
        }
    }

    // ---------- 取链（匿名态：形状被接受、按实测错误码拒绝） ----------

    @Test
    fun `批量取链的请求形状被服务端接受——匿名时按实测返回 104003 而不是模块错误`() {
        assumeTrue("无网络，跳过", networkAvailable())
        val songs = QqSongMapper.songsFromLegacySearch(get(QqRequests.legacySearchUrl("晴天", 1, 1)))
        assumeTrue("没搜到歌，跳过", songs.isEmpty().not())
        val song = songs.first()

        val types = QqQuality.attemptsFor("lossless")
        val body = JSONObject()
            .put("comm", appComm())
            .put("req", QqRequests.vkey(song.sourceId!!, song.mediaId!!, types, "0", "0123456789abcdef"))
        val resp = post("https://u.y.qq.com/cgi-bin/musicu.fcg", body, appUa)
        val req = resp.optJSONObject("req")
        assertTrue("响应里应有 req 对象（形状不被接受时这里是空的）", req != null)
        assertEquals("code=0 表示请求本身被受理", 0, req!!.optInt("code", -1))

        val list = req.optJSONObject("data")?.optJSONArray("midurlinfo")
        assertTrue("应返回 midurlinfo 数组", list != null && list.length() > 0)
        assertEquals("返回条目数应与请求的档位数一致", types.size, list!!.length())

        // 逐条核对：服务端回显的 filename 必须就是我们请求的那些（前缀与 mid 都对）
        var emptyPurl = 0
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i)
            val name = entry.optString("filename")
            val type = QqQuality.fileTypeOfFileName(name)
            assertTrue("回显的文件名前缀必须是我们请求过的档位之一: $name", type != null)
            assertEquals(
                "回显的文件名必须与请求的完全一致",
                QqQuality.fileNameFor(type!!, song.mediaId!!),
                name,
            )
            if (entry.optString("purl").isEmpty()) emptyPurl++
        }
        // 匿名态：实测要么全空（无权限 104003），要么服务端给了试听/免费档。
        // 两种都算「形状被接受」——真正要断言的是上面的回显一致。
        assertTrue("匿名态不应全部拿到 purl（那意味着服务端把付费曲给了游客）", emptyPurl > 0)
    }

    /**
     * **免费曲目的完整链路**：搜索 → 取链 → 拼 URL → CDN 真的返回音频字节。
     *
     * 这是本文件里最重要的一条：它证明「逆向出来的 API **确实能拿到歌曲资源**」，
     * 而不只是「请求形状被接受」。同时也钉住了 hotfix 3 修掉的那个 bug ——
     * 实测这些响应的 `sip` 是**空的**，早先的实现会在这里返回 null、把免费曲目丢掉。
     *
     * 只测免费曲目（`pay.pay_play == 0`）：VIP 曲目匿名必然被服务端拒绝（`104003` 空 purl），
     * 那是**权限**问题不是**接口**问题，拿它当失败会掩盖真正的回归。
     */
    @Test
    fun `免费曲目匿名能取到可播放 URL，且 CDN 真的返回音频字节`() {
        assumeTrue("无网络，跳过", networkAvailable())

        // 找一首**免费**曲目：必须按 `pay.pay_play == 0` 筛。
        // 不筛的话会挑到 VIP 曲，匿名必然拿不到 purl，用例就永远走「跳过」——
        // 那样等于没有这条测试（第一版就是这么写的，被跳过过一次才发现）。
        var mid: String? = null
        var mediaMid: String? = null
        outer@ for (kw in listOf("纯音乐", "轻音乐", "古典", "白噪音")) {
            val json = get(QqRequests.legacySearchUrl(kw, 30, 1))
            val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                val pay = it.optJSONObject("pay")?.optInt("pay_play", 1) ?: 1
                if (pay != 0) continue
                val m = it.optString("mid").takeIf { v -> v.isNotEmpty() } ?: continue
                mid = m
                mediaMid = it.optJSONObject("file")?.optString("media_mid")?.takeIf { v -> v.isNotEmpty() } ?: m
                break@outer
            }
        }
        assumeTrue("这次没搜到免费曲目（服务端曲库会变），跳过", mid != null)

        // 用生产代码同样的顺序逐档试，直到拿到 purl
        var playable: Pair<String, Int>? = null
        for (t in QqQuality.attemptsFor("standard")) {
            val body = JSONObject()
                .put("comm", appComm())
                .put("req", QqRequests.vkey(mid!!, mediaMid!!, listOf(t), "0", "0123456789abcdef"))
            val req = post("https://u.y.qq.com/cgi-bin/musicu.fcg", body, appUa).optJSONObject("req") ?: continue
            val info = req.optJSONObject("data")?.optJSONArray("midurlinfo")?.optJSONObject(0) ?: continue
            val purl = info.optString("purl")
            if (purl.isNotEmpty() && info.optInt("result", -1) == 0) {
                val sip = req.optJSONObject("data")?.optJSONArray("sip")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.isNotEmpty() } }
                playable = QqApi.composeUrl(purl, sip) to t.prefix.length
                break
            }
        }
        assumeTrue("这次没找到可匿名播放的免费曲目（服务端曲库会变），跳过", playable != null)

        // 关键断言：CDN 真的把音频给我们。
        //
        // ⚠️ 网络层失败**跳过**而不是失败（v3.4.9 实测踩到）：服务端下发的 `sip[0]` 可能是
        // `streamoc.music.tc.qq.com`，而那个域名在部分网络里**接受 TCP 连接后不返回任何字节**
        // （实测 `SocketTimeoutException: Read timed out` 于 10.1 s；同一时刻另外三个候选域名
        // `ws` / `isure` / `dl`.stream.qqmusic.qq.com 都在 330 ms 内返回 403）。
        // 这条用例要证明的是「拿到的 purl 指向真音频」，不是「这台机器的出口能连通腾讯的每一个
        // CDN 域名」—— 后者不该让一次全量单测变红（本文件开头的纪律：不可用时 `assumeTrue` 跳过）。
        // 拿到响应之后的一切（状态码、字节数、ID3/ftyp）**照旧是硬断言**，判据一点没放松。
        val result = try {
            cdnHttp.newCall(
                Request.Builder().url(playable!!.first)
                    .header("User-Agent", appUa).header("Referer", "https://y.qq.com/").build()
            ).execute()
        } catch (e: java.io.IOException) {
            assumeTrue(
                "CDN 域名不可达（${playable!!.first.take(90)}）：${e::class.java.simpleName}",
                false,
            )
            return
        }
        result.use { resp ->
            assertEquals("CDN 应返回 200", 200, resp.code)
            val body = resp.body!!
            val head = body.source().readByteArray(8)
            val len = body.contentLength()
            assertTrue("应拿到实际音频字节（实测 2~4 MB），实际 $len", len > 200_000)
            // ID3（mp3）或 ftyp（m4a）——两者都是「这是真音频」的硬证据
            val isId3 = head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
            val isFtyp = head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
            assertTrue("首字节应是 ID3 或 ftyp，实际 " + head.joinToString("") { "%02X".format(it) }, isId3 || isFtyp)
        }
    }

    // ---------- 歌词（匿名可用） ----------

    @Test
    fun `匿名能拿到歌词且 QRC 能解密解析——端到端走通 Kotlin 实现`() {
        assumeTrue("无网络，跳过", networkAvailable())
        val songs = QqSongMapper.songsFromLegacySearch(get(QqRequests.legacySearchUrl("晴天", 1, 1)))
        assumeTrue("没搜到歌，跳过", songs.isEmpty().not())
        val song = songs.first()
        val rawId = com.takahashirinta.ncrust.source.SourceIds.qqRawId(song.id) ?: 0L

        val body = JSONObject()
            .put("comm", appComm())
            .put("req", QqRequests.lyric(song.sourceId!!, rawId))
        val resp = post("https://u.y.qq.com/cgi-bin/musicu.fcg", body, appUa)
        val req = resp.optJSONObject("req") ?: return
        assertEquals(0, req.optInt("code", -1))
        val data = req.optJSONObject("data") ?: return
        val lyricHex = data.optString("lyric")
        assumeTrue("该曲这次没有歌词数据，跳过", lyricHex.isNotEmpty())

        // 关键一步：用**本仓库自己的**解密 + 解析实现处理真实响应
        val xml = QrcDecryptor.decrypt(lyricHex)
        assertTrue("解密必须成功（标准 3DES 在这里会失败）", xml != null)
        assertTrue("解出来应是 QRC 的 XML", xml!!.contains("LyricContent"))
        val lines = QrcParser.parseXml(xml)
        assertTrue("解析出的歌词行数应大于 10，实际 ${lines.size}", lines.size > 10)
        assertTrue("应有逐字数据", lines.any { it.words.isNotEmpty() })
        // 每一行的词区间都要与行文本严格对应（渲染层直接按它取版面路径）
        for (line in lines.filter { it.words.isNotEmpty() }) {
            for (w in line.words) {
                assertEquals(w.text, line.text.substring(w.charStart, w.charEndExclusive))
            }
        }
    }

    // ---------- 会员 ----------

    @Test
    fun `匿名能调通会员接口并拿到实测形状的 identity`() {
        assumeTrue("无网络，跳过", networkAvailable())
        val body = JSONObject()
            .put("comm", appComm())
            .put("req", QqRequests.vip())
        val req = post("https://u.y.qq.com/cgi-bin/musicu.fcg", body, appUa).optJSONObject("req") ?: return
        assertEquals("实测匿名也能调（code=0）", 0, req.optInt("code", -1))
        val identity = req.optJSONObject("data")?.optJSONObject("identity")
        assertTrue("应返回 identity 对象", identity != null)
        // 匿名态 vip 必为 0；这条同时验证了「未登录 ⇒ 非会员」这个语义
        assertEquals(0, identity!!.optInt("vip", 0))
    }
}
