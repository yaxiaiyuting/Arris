/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 2026-10-27 审查 H17：通用 B 站 GET 把凭据发给字幕 CDN。
 */

package com.takahashirinta.ncrust.bili

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H17：**凭据按目标 host 白名单发放**。完全离线：注入的客户端直接返回罐头响应，
 * 唯一的观测点是每个请求的 `Cookie` / `Referer` / `Origin` 头。
 *
 * ## 缺陷形状
 *
 * `BiliApi.get(url)` 不看目标 host，一律拼上 `BiliAuthStore.requestCookieHeader()`
 * （SESSDATA / bili_jct / DedeUserID）与 `Referer: https://www.bilibili.com`。
 * 而 `subtitleBody(url)` 的 url 是**服务端给的**（实测 `//aisubtitle.hdslb.com/…`，
 * `BiliSubtitle.normalizeSubtitleUrl` 也接受任意 http(s) 地址），
 * 于是「登录会话」的发送范围由响应内容决定 —— 字幕 CDN 收到了账号 Cookie。
 *
 * ## 修法与判据
 *
 * 只有 `*.bilibili.com`（账号 / 接口域）才带凭据；CDN 与第三方地址一律匿名。
 * 这里同时钉住两件事：**CDN 不带**、**账号域照旧带**（不能把登录功能一起修坏）。
 */
class H17BiliCredentialHostTest {

    private val seen = mutableListOf<Request>()

    @Before
    fun setUp() {
        seen.clear()
        BiliApi.clientForTest = recordingClient("""{"code":0,"data":{}}""")
        BiliAuthStore.setMirrorForTest(
            BiliCredential(sessdata = "FAKESESS", biliJct = "FAKEJCT", dedeUserId = "1234567"),
        )
        BiliApi.clearWbiKeysForTest()
        BiliApi.clearBuvidForTest()
    }

    @After
    fun tearDown() {
        BiliApi.clientForTest = null
        BiliApi.clearWbiKeysForTest()
        BiliApi.clearBuvidForTest()
        BiliAuthStore.setMirrorForTest(null)
    }

    private fun recordingClient(body: String) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            seen += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    // ------------------------------------------------------------------ 纯判据

    @Test
    fun `账号域判定 只认 bilibili_com 及其子域`() {
        assertTrue(BiliApi.isAccountDomain("https://api.bilibili.com/x/web-interface/nav"))
        assertTrue(BiliApi.isAccountDomain("https://www.bilibili.com/audio/music-service-c/url?sid=1"))
        assertTrue(BiliApi.isAccountDomain("https://passport.bilibili.com/x/passport-login/web/qrcode/poll"))
        // CDN / 媒体域：直链自带签名，不需要账号会话
        assertFalse(BiliApi.isAccountDomain("https://aisubtitle.hdslb.com/bfs/ai_subtitle/prod/x.json"))
        assertFalse(BiliApi.isAccountDomain("https://i0.hdslb.com/bfs/wbi/x.png"))
        assertFalse(BiliApi.isAccountDomain("https://upos-sz-mirrorcos.bilivideo.com/x.m4s"))
        // 相似域名（后缀欺骗）绝不能算账号域
        assertFalse(BiliApi.isAccountDomain("https://evil-bilibili.com/x"))
        assertFalse(BiliApi.isAccountDomain("https://bilibili.com.evil.test/x"))
        // 解析不了的一律匿名
        assertFalse(BiliApi.isAccountDomain(null))
        assertFalse(BiliApi.isAccountDomain(""))
        assertFalse(BiliApi.isAccountDomain("not a url"))
    }

    // ------------------------------------------------------------------ 行为

    /** 字幕正文在 CDN 上：一个凭据头都不许带。 */
    @Test
    fun `字幕 CDN 请求不带会话凭据与Referer`() = runBlocking {
        val body = BiliApi.subtitleBody("https://aisubtitle.hdslb.com/bfs/ai_subtitle/prod/abc.json?auth_key=1")
        assertNotNull("请求必须真的发出去（否则这条用例是空转的）", body)
        val req = seen.single()
        assertTrue(req.url.host.endsWith("hdslb.com"))
        assertNull("SESSDATA 绝不能发给字幕 CDN", req.header("Cookie"))
        assertNull("Referer 也必须按域收敛", req.header("Referer"))
        assertNull(req.header("Origin"))
        // UA 仍然要带（CDN 会按 UA 做风控，那不是凭据）。
        assertTrue(req.header("User-Agent").orEmpty().isNotEmpty())
    }

    /** 协议相对的 CDN 地址同样匿名（normalizeSubtitleUrl 会补 https）。 */
    @Test
    fun `协议相对的字幕地址同样匿名`() = runBlocking {
        BiliApi.subtitleBody("//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/def.json")
        assertNull(seen.single().header("Cookie"))
    }

    /** 任意第三方 http(s) 地址也不许拿到凭据。 */
    @Test
    fun `第三方地址不带凭据`() = runBlocking {
        BiliApi.subtitleBody("https://example.test/subtitle.json")
        val req = seen.single()
        assertEquals("example.test", req.url.host)
        assertNull(req.header("Cookie"))
        assertNull(req.header("Referer"))
    }

    /** 账号域照旧带凭据（不能把登录修坏）：nav 是登录态的唯一判据。 */
    @Test
    fun `B站账号域照旧带上会话凭据`() = runBlocking {
        BiliApi.probeReachable()
        val req = seen.last()
        assertEquals("api.bilibili.com", req.url.host)
        assertEquals("SESSDATA=FAKESESS; bili_jct=FAKEJCT; DedeUserID=1234567", req.header("Cookie"))
        // Referer 常量的取值带尾斜杠（`REFERER`），这里按"是不是 B 站首页"判，别钉字面量形状。
        assertEquals("https://www.bilibili.com", req.header("Referer")?.trimEnd('/'))
        assertEquals("https://www.bilibili.com", req.header("Origin")?.trimEnd('/'))
    }

    /** 音频区取流（`api.bilibili.com`）仍带凭据 —— 大会员档位依赖身份。 */
    @Test
    fun `音频区取流仍在账号域带凭据`() = runBlocking {
        BiliApi.audioInfo(39L)
        val req = seen.single()
        // 音频区曲目信息端点在 www.bilibili.com 上（不在 api 子域），同样是账号域。
        assertEquals("www.bilibili.com", req.url.host)
        assertTrue(req.header("Cookie").orEmpty().contains("SESSDATA=FAKESESS"))
    }
}
