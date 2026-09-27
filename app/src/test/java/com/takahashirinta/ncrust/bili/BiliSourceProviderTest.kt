/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
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
        // 契约：解析不出载荷就返回 null。**绝不退回网易云取链** ——
        // id 相同不代表是同一首歌（`MusicSourceProvider` 的既有契约）。
        assertNull(BiliSourceProvider.resolveUrl(broken, "lossless"))
        assertNull(BiliSourceProvider.fetchLyric(broken))
    }

    @Test
    fun `B 站不是可登录音源 —— 不出现在换源提示里`() {
        // otherThan 的语义是「另一个**能拿到这首歌**的地方」。
        // 把 B 站算进去会让网易云的无版权提示变成「去 B 站试试」，
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
        // 未知 key 仍然回落网易云（老版本 App 读到 "bilibili" 的行为）。
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
    fun `HTTP 412 的 HTML 正文算被拒`() {
        assertTrue(BiliApi.isSignatureRejected("<!DOCTYPE html><html lang=\"zh-cn\">...412..."))
        assertTrue(BiliApi.isSignatureRejected(""))
        assertTrue(BiliApi.isSignatureRejected(null))
    }

    @Test
    fun `结构化响应里的 -352 与 -403 与 -1200 都算被拒`() {
        // -352 是**风控校验失败**：实测「缺签名 / 错签名」就是这一档，而 HTTP 状态是 200。
        // 漏了它 ⇒ wbi 密钥每天轮换后搜索永远 0 条，且要等 6 小时 TTL 才可能自愈。
        assertTrue(BiliApi.isSignatureRejected("""{"code":-352,"message":"风控校验失败"}"""))
        assertTrue(BiliApi.isSignatureRejected("""{"code":-403,"message":"访问权限不够"}"""))
        assertTrue(BiliApi.isSignatureRejected("""{"code":-1200,"message":"被降级过滤的请求"}"""))
    }

    @Test
    fun `正常响应与其它错误码不算被拒`() {
        assertFalse(BiliApi.isSignatureRejected("""{"code":0,"data":{"result":[]}}"""))
        assertFalse(BiliApi.isSignatureRejected("""{"code":-101,"message":"账号未登录"}"""))
        assertFalse(BiliApi.isSignatureRejected("""{"code":4511001,"message":"音频未找到或已下架"}"""))
    }
}
