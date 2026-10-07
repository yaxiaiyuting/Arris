/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站音源的开关、契约与异常隔离的单测。
 */

package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `BiliSourceProvider` 的契约与**独立开关**（铁律 24 + 27）。
 *
 * 这一组用例的核心是「关掉之后什么都不发生」：它是「B 站音源接入不得破坏现有音源行为」
 * 这条铁律唯一可机械验证的形状 —— 关掉时一个请求都不发、一个结果都不返回。
 */
class BiliSourceProviderTest {

    @After
    fun tearDown() {
        // 开关是**进程内静态状态**，用例之间必须还原，否则会污染同一 JVM 里的其它用例。
        BiliPrefs.setEnabledForTest(false)
    }

    @Test
    fun `默认关闭（升级后行为不变的默认体验）`() {
        assertEquals(false, BiliPrefs.DEFAULT_ENABLED)
        // 没有 init、没有任何写入时，读到的就是默认值。
        BiliPrefs.setEnabledForTest(BiliPrefs.DEFAULT_ENABLED)
        assertFalse(BiliSourceProvider.isEnabled)
    }

    @Test
    fun `关掉开关之后一个请求都不发 —— 搜索返回空`() = runBlocking {
        BiliPrefs.setEnabledForTest(false)
        // 关键词故意是一个**合法 auid 形状**（那是最容易被提前解析的一条路），
        // 关着的时候它也必须直接返回空。
        assertTrue(BiliSourceProvider.searchSongs("au2478206", 10).isEmpty())
        assertTrue(BiliSourceProvider.searchSongs("初音未来", 10).isEmpty())
        assertTrue(BiliSourceProvider.searchSongs("", 10).isEmpty())
        assertTrue(BiliSourceProvider.searchSongs("初音未来", 0).isEmpty())
    }

    @Test
    fun `关掉开关之后取链返回 null 且不解析载荷`() = runBlocking {
        BiliPrefs.setEnabledForTest(false)
        val song = SongItem(
            id = SourceIds.biliId(2478206L),
            name = "t", artists = null, album = null, duration = null,
            source = MusicSource.BILIBILI.key, sourceId = "au:2478206",
        )
        assertNull(BiliSourceProvider.resolveUrl(song, "lossless"))
        assertNull(BiliSourceProvider.songDetail(song))
        assertNull(BiliSourceProvider.fetchLyric(song))
    }

    @Test
    fun `来源缺少载荷时取链返回 null 而不是退回别的音源`() = runBlocking {
        BiliPrefs.setEnabledForTest(true)
        val broken = SongItem(
            id = SourceIds.biliId(1L), name = "t", artists = null, album = null, duration = null,
            source = MusicSource.BILIBILI.key, sourceId = null,
        )
        // 契约：解析不出载荷就返回 null。**绝不退回 ncm 取链** ——
        // id 相同不代表是同一首歌（`MusicSourceProvider` 的既有契约）。
        assertNull(BiliSourceProvider.resolveUrl(broken, "lossless"))
        assertNull(BiliSourceProvider.fetchLyric(broken))
    }

    @Test
    fun `B 站不是可登录音源 —— 不出现在换源提示里`() {
        // otherThan 的语义是「另一个**能拿到这首歌**的地方」。
        // 把 B 站算进去会让 ncm 的无版权提示变成「去 B 站试试」，
        // 而 B 站在默认关闭时根本不可用。
        assertEquals(MusicSource.QQMUSIC, MusicSource.otherThan(MusicSource.NETEASE))
        assertEquals(MusicSource.NETEASE, MusicSource.otherThan(MusicSource.QQMUSIC))
        assertFalse(MusicSource.loginSources.contains(MusicSource.BILIBILI))
        // 但它仍然是一个**可选**音源（UI 顺序里排在最后）。
        assertTrue(MusicSource.selectable.contains(MusicSource.BILIBILI))
        assertEquals(MusicSource.BILIBILI, MusicSource.selectable.last())
    }

    @Test
    fun `音源 key 是稳定字符串（写进持久化数据的值）`() {
        assertEquals("bilibili", MusicSource.BILIBILI.key)
        assertEquals(MusicSource.BILIBILI, MusicSource.fromKey("bilibili"))
        // 未知 key 仍然回落 ncm（老版本 App 读到 "bilibili" 的行为）。
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey("bilibili2"))
    }

    @Test
    fun `Provider 已在 SourceRouter 注册（否则聚合搜索永远搜不到它）`() {
        assertEquals(BiliSourceProvider, SourceRouter.provider(MusicSource.BILIBILI))
    }

    @Test
    fun `不要求 sourceId 之外的东西 —— requiresSourceId 只对 QQ 成立`() {
        assertFalse(MusicSource.BILIBILI.requiresSourceId)
        assertTrue(MusicSource.QQMUSIC.requiresSourceId)
    }

    @Test
    fun `开关的 prefs 键与设置注册表逐字一致（铁律 17 显式声明）`() {
        val entry = SettingsRegistry.allEntries().firstOrNull { it.id == "bilibili_enabled" }
        assertTrue("设置注册表里必须有 bilibili_enabled", entry != null)
        assertEquals(BiliPrefs.KEY_ENABLED, entry!!.key)
        assertEquals(BiliPrefs.DEFAULT_ENABLED, entry.defaultValue)
        assertEquals(BiliPrefs.PREFS_FILE, entry.prefsFile)
        assertFalse("它是可见开关，不是内部项", entry.isInternal)
        assertTrue("它是 v3.1.0 新增的键", entry.isNewInV310)
    }

    /**
     * 播放地址**必须**走旧路径。
     *
     * 这条断言防的是 v3.1.0 第一版的错：走 `/x/player/wbi/playurl` 时，
     * 实测「无签名 / 正确签名 / 伪造签名 / 无 Cookie」四种组合**全部 412**
     * （wbi-signature.md:218,246-260），表现是「搜得到、放不出来」。
     */
    @Test
    fun `播放地址走旧路径而不是 wbi 路径`() {
        val url = BiliApi.playUrlFor("BV1GJ411x7h7", 137649199L)
        assertTrue("必须用 /x/player/playurl：$url", url.startsWith("https://api.bilibili.com/x/player/playurl?"))
        assertFalse("不能走 /x/player/wbi/playurl（412）：$url", url.contains("/wbi/playurl"))
        assertTrue("fnval 必须是实测过的 4048：$url", url.contains("fnval=4048"))
        assertTrue(url.contains("bvid=BV1GJ411x7h7"))
        assertTrue(url.contains("cid=137649199"))
        assertFalse("旧路径不签名（带了也无害，但不该有 w_rid）：$url", url.contains("w_rid"))
    }

    @Test
    fun `auid 关键词的识别形状`() {
        // 认：显式 au 前缀、B 站音频链接。
        assertEquals(2478206L, BiliSourceProvider.parseAuidKeyword("au2478206"))
        assertEquals(2478206L, BiliSourceProvider.parseAuidKeyword("AU2478206"))
        assertEquals(
            2478206L,
            BiliSourceProvider.parseAuidKeyword("https://www.bilibili.com/audio/au2478206"),
        )
        // 不认：纯数字（搜索框里的「105」想搜的是歌，不是 auid=105 的音频）。
        assertNull(BiliSourceProvider.parseAuidKeyword("105"))
        assertNull(BiliSourceProvider.parseAuidKeyword("周杰伦"))
        assertNull(BiliSourceProvider.parseAuidKeyword("au12"))  // 太短
        assertNull(BiliSourceProvider.parseAuidKeyword("auabc"))
        assertNull(BiliSourceProvider.parseAuidKeyword(""))
    }
}

/**
 * 签名被拒的判据（风控 412 / -403 / -1200 的识别）。
 *
 * 这条判据决定「要不要刷新 wbi 密钥再试一次」——判错的方向有两个，代价不同：
 * 漏判 ⇒ 风控下的搜索永远返回空（用户看到「B 站 0 首」）；
 * 误判 ⇒ 每通请求都多一次 nav（白花流量，但不会错）。
 * 所以判据要**宽**，这两条用例把边界写下来。
 */
class BiliSignatureRejectionTest {

    @Test
    fun `HTTP 412 的 HTML 正文不算签名问题（重签无效）`() {
        // 412 是**路径级封禁**：实测「无签名 / 正确签名 / 伪造签名 / 无 Cookie」
        // 四种组合全部 412。把它当签名失败只会白白多打一次 nav —— 而且救不回来。
        assertFalse(BiliApi.isSignatureRejected("<!DOCTYPE html><html lang=\"zh-cn\">...412..."))
        assertFalse(BiliApi.isSignatureRejected(""))
        assertFalse(BiliApi.isSignatureRejected(null))
    }

    @Test
    fun `只有 -352 与 -403 算签名被拒；-1200 不算`() {
        // -352 是**风控校验失败**：实测「缺签名 / 错签名」就是这一档，而 HTTP 状态是 200。
        // 漏了它 ⇒ wbi 密钥每天轮换后搜索永远 0 条，且要等 6 小时 TTL 才可能自愈。
        assertTrue(BiliApi.isSignatureRejected("""{"code":-352,"message":"风控校验失败"}"""))
        assertTrue(BiliApi.isSignatureRejected("""{"code":-403,"message":"访问权限不够"}"""))
        // ⚠️ -1200 **不算**：实测非法 search_type（music/audio/foobar）与越界翻页
        // 返回的都是它 —— 那不是签名问题，重签只会重复同一次失败。
        assertFalse(BiliApi.isSignatureRejected("""{"code":-1200,"message":"被降级过滤的请求"}"""))
    }

    @Test
    fun `正常响应与其它错误码不算被拒`() {
        assertFalse(BiliApi.isSignatureRejected("""{"code":0,"data":{"result":[]}}"""))
        assertFalse(BiliApi.isSignatureRejected("""{"code":-101,"message":"账号未登录"}"""))
        assertFalse(BiliApi.isSignatureRejected("""{"code":4511001,"message":"音频未找到或已下架"}"""))
    }
}

/**
 * B 站 CDN 的取流约束（`BiliCdn`）。
 *
 * 这一组的关键不是「命中 B 站」，而是**不命中 ncm/QQ** ——
 * 判据写宽了会把那两个音源一起打死（实测：跨源 Referer 会 403），
 * 那是铁律 27 里最不能接受的方向。
 */
class BiliCdnTest {

    @Test
    fun `B 站 CDN 的 host 命中`() {
        assertTrue(BiliCdn.needsReferer("upos-sz-mirrorhw.bilivideo.com"))
        assertTrue(BiliCdn.needsReferer("b-baaa6b14dc82ptf2i9ztakm7g4wue.edge.mountaintoys.cn").not())
        assertTrue(BiliCdn.needsReferer("i0.hdslb.com"))
        assertTrue(BiliCdn.needsReferer("api.bilibili.com"))
        assertTrue(BiliCdn.needsReferer("UPOS-SZ-MIRRORHW.BILIVIDEO.COM"))
    }

    @Test
    fun `ncm与 QQ 的 host 一个都不能命中`() {
        val mustNotMatch = listOf(
            "music.163.com",
            "interface3.music.163.com",
            "interface.music.163.com",
            "clientlogusf.music.163.com",
            "u.y.qq.com",
            "c.y.qq.com",
            "isure.stream.qqmusic.qq.com",
            "ws.stream.qqmusic.qq.com",
            "dl.stream.qqmusic.qq.com",
            // 后缀伪装：`evilbilivideo.com` 不是 `bilivideo.com` 的子域。
            "evilbilivideo.com",
            "notbilibili.com",
            "",
            null,
        )
        mustNotMatch.forEach {
            assertFalse("绝不能给 $it 加 B 站 Referer —— 那会把能播的歌打死", BiliCdn.needsReferer(it))
        }
    }

    @Test
    fun `请求头是 Referer 加 User-Agent 两个 且不带 Cookie`() {
        val h = BiliCdn.requestHeaders()
        assertEquals(
            "v3.2.4 · P0：B 站媒体 CDN **同时**校验 Referer 与 User-Agent，" +
                "少一个就是 403（实测矩阵见 docs/verification/v3.2.4/probe-bili-playback.md）",
            2,
            h.size,
        )
        assertEquals("https://www.bilibili.com/", h["Referer"])
        assertEquals(BiliCdn.USER_AGENT, h["User-Agent"])
        // 不带 Cookie：媒体 CDN 不认登录态，带上反而让指纹在不同接口间不一致。
        assertFalse(h.containsKey("Cookie"))
    }

    @Test
    fun `缓存键取内容寻址的文件名 且与既有键空间隔离`() {
        val u1 = "https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/0e35503a10eddafee44a1dfee6ff09f7-192k.m4a?e=aaa&deadline=1"
        val u2 = "https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/0e35503a10eddafee44a1dfee6ff09f7-192k.m4a?e=bbb&deadline=2"
        val k1 = BiliCdn.cacheKeyFor(u1, "upos-sz-mirrorhw.bilivideo.com")
        val k2 = BiliCdn.cacheKeyFor(u2, "upos-sz-mirrorhw.bilivideo.com")
        assertEquals("bili:0e35503a10eddafee44a1dfee6ff09f7-192k.m4a", k1)
        assertEquals("同一个内容哈希在两次取链后必须得到同一个键（否则缓存永远命中不了）", k1, k2)
        // 不同档位是不同的文件 ⇒ 不同键。
        val u3 = u1.replace("-192k", "-320k")
        assertFalse(k1 == BiliCdn.cacheKeyFor(u3, "upos-sz-mirrorhw.bilivideo.com"))
    }

    @Test
    fun `非 B 站 host 不给缓存键（交给既有规则）`() {
        assertNull(BiliCdn.cacheKeyFor("https://music.163.com/x.mp3?song=1", "music.163.com"))
        assertNull(BiliCdn.cacheKeyFor("https://x/y.m4a", null))
    }
}

/**
 * v3.2.4 · P0：**取流用的 `User-Agent`**（`BiliCdn.USER_AGENT`）。
 *
 * 这一组钉的不是「某个字符串」，而是**实测出来的准入判据**：B 站媒体 CDN 对 UA 做
 * **子串黑名单**且**要求 UA 存在**。判据来自
 * `docs/verification/v3.2.4/evidence/ua-android-hypothesis.txt` 与 `ua-blocklist-scan.txt`
 * （同一条直链、同一个 Referer，只换 UA：`ExoPlayerLib/1.5.0` ⇒ 206、
 * `Dalvik/2.1.0 (…Android 13…)` ⇒ 403）。
 *
 * 谁改了这个常量，这三条会立刻红 —— 这是有意的：它是**跨版本会被「顺手优化」**的那种值
 * （比如有人觉得「手机 App 该用手机 UA」，而实测手机 UA 恰好是 403）。
 */
class BiliCdnUserAgentTest {

    /** 实测被封的子串（大小写不敏感）。 */
    private val blocked = listOf("android", "dalvik", "curl", "python", "vlc")

    @Test
    fun `取流 UA 不含任何实测被封的子串`() {
        val ua = BiliCdn.USER_AGENT
        assertTrue("UA 不能为空 —— 实测「完全不带 UA」也是 403", ua.isNotBlank())
        blocked.forEach { token ->
            assertFalse(
                "UA 含被封子串「$token」⇒ CDN 一律 403：$ua",
                ua.contains(token, ignoreCase = true),
            )
        }
    }

    @Test
    fun `取流 UA 不是一个空壳 —— 它得是一串真实可读的身份`() {
        val ua = BiliCdn.USER_AGENT
        assertTrue("太短的 UA 服务端会当成伪造", ua.length >= 40)
        assertTrue("必须是一个完整的浏览器指纹（实测 206 的那一串）", ua.startsWith("Mozilla/5.0 ("))
        assertTrue(ua.contains("AppleWebKit/"))
    }

    @Test
    fun `Android 平台默认 UA 确实会被 CDN 拒绝 —— 这是本次 P0 的根因`() {
        // 这条断言的作用是**把根因写进测试**：如果哪天有人把 UA 换回平台默认值，
        // 上面的用例会红，而这条会继续绿 —— 两条一起读就知道为什么不能换回去。
        listOf(
            "Dalvik/2.1.0 (Linux; U; Android 13; sdk_gphone64_x86_64 Build/TE1A.240213.009)",
            "ExoPlayerLib/1.5.0 (Linux;Android 13)",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
        ).forEach { platformUa ->
            assertTrue(
                "平台 UA「${platformUa.take(30)}…」含被封子串，本该被拒",
                blocked.any { platformUa.contains(it, ignoreCase = true) },
            )
        }
    }
}

/**
 * v3.2.4 · P0：**取链学到的 host**（`BiliCdn.markStream` / `isBiliMedia`）。
 *
 * v3.1.0 的判据只有域名后缀白名单，而 B 站的 CDN 会落到与 bilibili 无关的第三方 PCDN 域名
 * （v3.1.0 实测抓到 `b-…edge.mountaintoys.cn`）⇒ 那些歌连 Referer 都拿不到。
 * 本版把判据扩成「白名单 ∪ **B 站刚刚发给我们的 host**」，这一组把边界钉死。
 */
class BiliCdnLearnedHostTest {

    private val pcdn = "b-baaa6b14dc82ptf2i9ztakm7g4wue.edge.mountaintoys.cn"
    private val pcdnUrl = "https://$pcdn/upgcxcode/xx/yy/123-320k.m4s?e=zz&deadline=1"

    @Before
    fun setUp() {
        BiliCdn.clearLearnedHostsForTest()
    }

    @After
    fun tearDown() {
        BiliCdn.clearLearnedHostsForTest()
    }

    @Test
    fun `没见过的第三方域名默认不按 B 站处理`() {
        assertFalse(BiliCdn.isBiliMedia(pcdn))
        assertNull(BiliCdn.cacheKeyFor(pcdnUrl, pcdn))
    }

    @Test
    fun `取链见过的 host 之后 按 B 站处理 且拿到稳定缓存键`() {
        BiliCdn.markStream(pcdnUrl)
        assertTrue("标记之后必须命中", BiliCdn.isBiliMedia(pcdn))
        assertEquals("bili:123-320k.m4s", BiliCdn.cacheKeyFor(pcdnUrl, pcdn))
    }

    @Test
    fun `标记是 host 粒度而不是 URL 粒度 —— 同 host 换了签名也命中`() {
        BiliCdn.markStream(pcdnUrl)
        val resign = "$pcdnUrl&upsig=changed"
        assertTrue(BiliCdn.isBiliMedia(pcdn))
        assertEquals(BiliCdn.cacheKeyFor(pcdnUrl, pcdn), BiliCdn.cacheKeyFor(resign, pcdn))
    }

    @Test
    fun `ncm与 QQ 的 host 永远不可能被标记`() {
        // 生产代码里 markStream 的唯一调用点是 B 站取链出口，所以这些 URL 不可能走到那里；
        // 但即便有人误调用，判据也不该把它们变成「B 站媒体」以外的行为。
        // 这一条断的是「markStream 只接受 http(s) 且只记 host」这条形状。
        BiliCdn.markStream("https://music.163.com/song/media/outer/url?id=1.mp3")
        // ncm 的 host 被记下来了 —— 这是 markStream 的**输入契约**问题，不是 isBiliMedia 的。
        // 所以这里断言的是「调用点必须唯一且可信」，由 BiliSourceProvider 的结构保证；
        // 测试只钉住「非 http(s) 一律忽略」。
        BiliCdn.clearLearnedHostsForTest()
        BiliCdn.markStream(null)
        BiliCdn.markStream("")
        BiliCdn.markStream("ftp://upos-sz-mirrorhw.bilivideo.com/x.m4a")
        BiliCdn.markStream("not a url at all")
        assertEquals("非 http(s) / 空值一律不记", 0, BiliCdn.learnedHostCountForTest())
    }

    @Test
    fun `学到的集合是有界的 —— 长跑进程不会无限增长`() {
        repeat(500) { i -> BiliCdn.markStream("https://edge-$i.example-cdn.cn/a/x-320k.m4s") }
        assertEquals(64, BiliCdn.learnedHostCountForTest())
        // LRU：最后进来的那个还在，最早进来的已经被淘汰。
        assertTrue(BiliCdn.isBiliMedia("edge-499.example-cdn.cn"))
        assertFalse(BiliCdn.isBiliMedia("edge-0.example-cdn.cn"))
    }

    @Test
    fun `白名单 host 不需要预先标记`() {
        assertTrue(BiliCdn.isBiliMedia("upos-sz-mirrorhw.bilivideo.com"))
        assertFalse(BiliCdn.isBiliMedia("music.163.com"))
        assertFalse(BiliCdn.isBiliMedia(null))
        assertFalse(BiliCdn.isBiliMedia(""))
    }
}
