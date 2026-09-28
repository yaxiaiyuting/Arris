/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站扫码登录三个接口的**逐字段解析单测**。
 */

package com.takahashirinta.ncrust.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BiliAuthApi] 解析层的逐字段断言。
 *
 * ## 样本来源（**每一条都标了出处，不许混**）
 *
 * | 样本 | 出处 |
 * |---|---|
 * | [GENERATE_REAL]、[POLL_PENDING_REAL]、[POLL_EXPIRED_REAL]、[NAV_ANON_REAL] | **本轮实测**（2026-09-28 直连 `curl`，原始响应逐字节抄进 `docs/verification/v3.2.0/probe-bili-login.md`） |
 * | [POLL_SCANNED_DOC]、[POLL_SUCCESS_DOC]、[SET_COOKIE_DOC]、[NAV_LOGGED_IN_SAMPLE] | **社区文档 / 构造样本**：本轮**没有可用的 B 站账号**，`data.code = 0` 与 `86090` 需要真实扫码 ⇒ 形状抄自 BAC（bilibili-API-collect）的 QR 文档，值全部换成显式的假值 |
 *
 * 把两类分开写是**这条纪律的落点**：样本可以来自文档，但**不能假装是实测**。
 */
class BiliAuthParseTest {

    // ---------------------------------------------------------------- 真实样本（本轮实测）

    /** `GET passport.bilibili.com/x/passport-login/web/qrcode/generate` 的真实响应。 */
    private val GENERATE_REAL = """
    {"code":0,"message":"OK","ttl":1,"data":{"url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1\u0026callback=close\u0026qrcode_key=4789f53cc92fcb2191a94ba57aed76c3\u0026from=","qrcode_key":"4789f53cc92fcb2191a94ba57aed76c3"}}
    """.trimIndent()

    /** 刚生成的 key 第一次轮询（`data.code = 86101`）—— 实测。 */
    private val POLL_PENDING_REAL =
        """{"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86101,"message":"未扫码"}}"""

    /** 伪造 key / 不带 key / key 超时后的轮询（`data.code = 86038`）—— 实测。 */
    private val POLL_EXPIRED_REAL =
        """{"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86038,"message":"二维码已失效"}}"""

    /** `GET api.bilibili.com/x/web-interface/nav` 匿名 —— 实测（`code:-101` + `data.isLogin:false`）。 */
    private val NAV_ANON_REAL = """
    {"code": -101, "message": "账号未登录", "ttl": 1, "data": {"isLogin": false, "wbi_img": {"img_url": "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png", "sub_url": "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}, "ip_region": "CN"}}
    """.trimIndent()

    // ---------------------------------------------------------------- 文档/构造样本（非实测）

    /** `data.code = 86090`（已扫码未确认）—— BAC 文档样本，**本轮未实测**。 */
    private val POLL_SCANNED_DOC =
        """{"code":0,"message":"0","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86090,"message":"二维码已扫码未确认"}}"""

    /**
     * `data.code = 0`（成功）—— BAC 文档样本，**本轮未实测**。
     *
     * 值全部是显式假值（`FAKE…`）。真实值不可能出现在这个仓库里：
     * `SESSDATA` 一旦落进 git 就是永久泄露。
     */
    private val POLL_SUCCESS_DOC = """
    {"code":0,"message":"0","ttl":1,"data":{"url":"https://passport.biligame.com/crossDomain?DedeUserID=1234567\u0026DedeUserID__ckMd5=ckmd5fake\u0026Expires=1759000000\u0026SESSDATA=FAKE%2CSESSDATA%2CVALUE\u0026bili_jct=FAKEJCT\u0026gourl=https%3A%2F%2Fpassport.bilibili.com","refresh_token":"FAKEREFRESH","timestamp":1662363009601,"code":0,"message":""}}
    """.trimIndent()

    /** 成功态的 `Set-Cookie`（BAC 文档抓包，值换成假值）。 */
    private val SET_COOKIE_DOC = listOf(
        "SESSDATA=FAKE%2CSESSDATA%2CVALUE; Path=/; Domain=bilibili.com; Expires=Sat, 04 Mar 2023 07:30:09 GMT; HttpOnly; Secure",
        "bili_jct=FAKEJCT; Path=/; Domain=bilibili.com; Expires=Sat, 04 Mar 2023 07:30:09 GMT",
        "DedeUserID=1234567; Path=/; Domain=bilibili.com; Expires=Sat, 04 Mar 2023 07:30:09 GMT",
        "DedeUserID__ckMd5=ckmd5fake; Path=/; Domain=bilibili.com; Expires=Sat, 04 Mar 2023 07:30:09 GMT",
        "sid=FAKESID; Path=/; Domain=bilibili.com; Expires=Sat, 04 Mar 2023 07:30:09 GMT",
    )

    /** 登录态 `nav` —— **构造样本**（本轮无账号），字段名来自社区文档。 */
    private val NAV_LOGGED_IN_SAMPLE = """
    {"code":0,"message":"0","ttl":1,"data":{"isLogin":true,"uname":"测试用户","mid":1234567,"vipStatus":1,"vipType":2,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}
    """.trimIndent()

    // ---------------------------------------------------------------- generate

    @Test
    fun `generate 实测响应逐字段`() {
        val code = BiliAuthApi.parseQrGenerate(GENERATE_REAL)
        assertEquals(
            "二维码内容就是 data.url（会原样渲染成二维码）",
            "https://account.bilibili.com/h5/account-h5/auth/scan-web" +
                "?navhide=1&callback=close&qrcode_key=4789f53cc92fcb2191a94ba57aed76c3&from=",
            code?.url,
        )
        assertEquals("4789f53cc92fcb2191a94ba57aed76c3", code?.qrcodeKey)
        assertEquals("实测 qrcode_key 恒为 32 字符", 32, code?.qrcodeKey?.length)
    }

    @Test
    fun `generate 缺 url 或 缺 qrcode_key 一律返回 null`() {
        // 画一张内容为空的二维码 = 让用户白扫一次，必须在这里挡住。
        assertNull(BiliAuthApi.parseQrGenerate("""{"code":0,"data":{"qrcode_key":"k"}}"""))
        assertNull(BiliAuthApi.parseQrGenerate("""{"code":0,"data":{"url":"https://x"}}"""))
        assertNull(BiliAuthApi.parseQrGenerate("""{"code":0,"data":{}}"""))
        assertNull(BiliAuthApi.parseQrGenerate("""{"code":0}"""))
        assertNull("顶层 code != 0", BiliAuthApi.parseQrGenerate("""{"code":-412,"data":{"url":"u","qrcode_key":"k"}}"""))
    }

    // ---------------------------------------------------------------- poll：四档状态

    @Test
    fun `轮询 86101 是未扫码（实测样本）`() {
        assertEquals(BiliQrPoll.Pending, BiliAuthApi.parseQrPoll(POLL_PENDING_REAL))
    }

    @Test
    fun `轮询 86038 是已失效（实测样本）`() {
        assertEquals(BiliQrPoll.Expired, BiliAuthApi.parseQrPoll(POLL_EXPIRED_REAL))
    }

    @Test
    fun `轮询 86090 是已扫码未确认（文档样本 本轮未实测）`() {
        assertEquals(BiliQrPoll.Scanned, BiliAuthApi.parseQrPoll(POLL_SCANNED_DOC))
    }

    @Test
    fun `轮询 0 是成功 且凭据来自 Set-Cookie（文档样本 本轮未实测）`() {
        val poll = BiliAuthApi.parseQrPoll(POLL_SUCCESS_DOC, SET_COOKIE_DOC)
        assertTrue("必须是 Success，实际 $poll", poll is BiliQrPoll.Success)
        val cred = (poll as BiliQrPoll.Success).credential
        assertEquals("FAKE%2CSESSDATA%2CVALUE", cred.sessdata)
        assertEquals("FAKEJCT", cred.biliJct)
        assertEquals("1234567", cred.dedeUserId)
        assertEquals("ckmd5fake", cred.dedeUserIdCkMd5)
        assertEquals("FAKESID", cred.sid)
        assertEquals(1662363009601L, poll.timestampMs)
        assertTrue(cred.isUsable())
    }

    @Test
    fun `凭据只在 data_url 里时也能取到 —— 两个载体都必须处理`() {
        // 把 Set-Cookie 全部去掉，只留 data.url（跨域登录 URL）这一个载体。
        val poll = BiliAuthApi.parseQrPoll(POLL_SUCCESS_DOC, emptyList())
        assertTrue("只读 Set-Cookie 的实现在这里会失败", poll is BiliQrPoll.Success)
        val cred = (poll as BiliQrPoll.Success).credential
        assertEquals("FAKE%2CSESSDATA%2CVALUE", cred.sessdata)
        assertEquals("FAKEJCT", cred.biliJct)
        assertEquals("1234567", cred.dedeUserId)
        assertEquals("ckmd5fake", cred.dedeUserIdCkMd5)
        // `sid` 只在 Set-Cookie 里有 ⇒ data.url 这条载体取不到它，但不能因此判失败。
        assertEquals("", cred.sid)
    }

    @Test
    fun `两个载体都有时逐字段合并 空值不覆盖有值`() {
        // Set-Cookie 只有 SESSDATA，data.url 只有 bili_jct ⇒ 合并后两个都在。
        val poll = BiliAuthApi.parseQrPoll(
            """{"code":0,"data":{"url":"https://passport.biligame.com/crossDomain?bili_jct=FROMURL&gourl=x","code":0,"timestamp":1}}""",
            listOf("SESSDATA=FROMLOGIN; Path=/; Domain=bilibili.com"),
        )
        val cred = (poll as BiliQrPoll.Success).credential
        assertEquals("FROMLOGIN", cred.sessdata)
        assertEquals("FROMURL", cred.biliJct)
        // 反过来：headers 里的空值不能把 url 里的有值字段冲掉。
        val merged = BiliCredential(sessdata = "S", biliJct = "J").merge(BiliCredential(sessdata = "", biliJct = ""))
        assertEquals("S", merged.sessdata)
        assertEquals("J", merged.biliJct)
    }

    @Test
    fun `SESSDATA 里的 2C 原样保留 —— 绝不做 URL 解码`() {
        // 真实 SESSDATA 里的逗号以 %2C 形式存在；解码成 "," 之后服务端比对的就是另一个字符串。
        val viaHeader = BiliAuthApi.credentialFromSetCookie(listOf("SESSDATA=a%2Cb%2Cc; Path=/"))
        assertEquals("a%2Cb%2Cc", viaHeader.sessdata)
        val viaUrl = BiliAuthApi.credentialFromCrossDomainUrl(
            "https://passport.biligame.com/crossDomain?SESSDATA=a%2Cb%2Cc&bili_jct=j",
        )
        assertEquals("a%2Cb%2Cc", viaUrl.sessdata)
        assertNotEquals("a,b,c", viaUrl.sessdata)
    }

    @Test
    fun `Cookie 头只认白名单字段 且不含 Path 之类的属性`() {
        val cred = BiliAuthApi.credentialFromSetCookie(SET_COOKIE_DOC + listOf("buvid3=SHOULD_NOT_BE_HERE; Path=/"))
        assertEquals(
            "SESSDATA=FAKE%2CSESSDATA%2CVALUE; bili_jct=FAKEJCT; DedeUserID=1234567; " +
                "DedeUserID__ckMd5=ckmd5fake; sid=FAKESID",
            cred.cookieHeader(),
        )
        assertFalse("白名单外的字段不许进 Cookie 头", cred.cookieHeader().contains("SHOULD_NOT_BE_HERE"))
        assertFalse("属性不是凭据", cred.cookieHeader().contains("Path="))
    }

    @Test
    fun `成功态却没有 SESSDATA 时判成 SERVER 失败 而不是落一份假凭据`() {
        val poll = BiliAuthApi.parseQrPoll(
            """{"code":0,"data":{"url":"https://passport.biligame.com/crossDomain?bili_jct=j","code":0,"timestamp":1}}""",
            listOf("bili_jct=j; Path=/"),
        )
        assertTrue(poll is BiliQrPoll.Failed)
        assertEquals(BiliQrPoll.Failed.Reason.SERVER, (poll as BiliQrPoll.Failed).reason)
    }

    // ---------------------------------------------------------------- poll：畸形 / 未知

    @Test
    fun `412 的 HTML 正文不崩 —— 判成 MALFORMED 而不是抛异常`() {
        val poll = BiliAuthApi.parseQrPoll("<html><body>412 Precondition Failed</body></html>", emptyList())
        assertEquals(BiliQrPoll.Failed.Reason.MALFORMED, (poll as BiliQrPoll.Failed).reason)
        assertEquals(
            "空的 Set-Cookie 与空 body 一样不能崩",
            BiliQrPoll.Failed.Reason.MALFORMED,
            (BiliAuthApi.parseQrPoll(null, emptyList()) as BiliQrPoll.Failed).reason,
        )
    }

    @Test
    fun `缺 data 或 缺 data_code 判成 MALFORMED`() {
        assertEquals(
            BiliQrPoll.Failed.Reason.MALFORMED,
            (BiliAuthApi.parseQrPoll("""{"code":0}""") as BiliQrPoll.Failed).reason,
        )
        assertEquals(
            BiliAuthApi.parseQrPoll("""{"code":0,"data":{"url":"","refresh_token":""}}"""),
            BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.MALFORMED, "缺少 data.code"),
        )
    }

    @Test
    fun `顶层 code 非 0 判成 SERVER`() {
        val poll = BiliAuthApi.parseQrPoll("""{"code":-412,"message":"请求被拦截"}""") as BiliQrPoll.Failed
        assertEquals(BiliQrPoll.Failed.Reason.SERVER, poll.reason)
        assertTrue("诊断信息里要能看出是顶层码", poll.detail.contains("-412"))
    }

    @Test
    fun `未知的 data_code 判成 SERVER 而不是当成未扫码继续轮询`() {
        // 当成「未扫码」的后果是：一个谁也没见过的码会让界面永远转圈到超时。
        val poll = BiliAuthApi.parseQrPoll(
            """{"code":0,"data":{"code":99999,"message":"什么码"}}""",
        ) as BiliQrPoll.Failed
        assertEquals(BiliQrPoll.Failed.Reason.SERVER, poll.reason)
        assertTrue(poll.detail.contains("99999"))
    }

    // ---------------------------------------------------------------- nav

    @Test
    fun `nav 匿名实测响应 —— -101 判过期 且没有 uname 与会员字段`() {
        assertEquals(BiliNavResult.NotLoggedIn, BiliAuthApi.parseNav(NAV_ANON_REAL))
    }

    @Test
    fun `nav 登录态构造样本逐字段`() {
        val nav = (BiliAuthApi.parseNav(NAV_LOGGED_IN_SAMPLE) as BiliNavResult.LoggedIn).nav
        assertTrue(nav.isLogin)
        assertEquals("测试用户", nav.uname)
        assertEquals(1234567L, nav.mid)
        assertEquals(1, nav.vipStatus)
        assertEquals(2, nav.vipType)
        assertEquals("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png", nav.wbiImgUrl)
        assertEquals("https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png", nav.wbiSubUrl)
        assertTrue(nav.profile().isVip())
        assertEquals("测试用户", nav.profile().uname)
    }

    @Test
    fun `匿名 nav 的会员字段缺省就是非会员（绝不假设字段存在）`() {
        val nav = BiliAuthApi.parseNav(
            """{"code":0,"data":{"isLogin":true,"uname":"u","mid":1}}""",
        ) as BiliNavResult.LoggedIn
        assertEquals(0, nav.nav.vipStatus)
        assertEquals(0, nav.nav.vipType)
        assertFalse(nav.nav.profile().isVip())
        assertNull(nav.nav.wbiImgUrl)
    }

    @Test
    fun `nav 的风控码不算过期 —— -352 是 Failed 不是 NotLoggedIn`() {
        // 把风控当成「需要重新登录」的用户体验是：反复扫码、永远登不进去。
        val r = BiliAuthApi.parseNav("""{"code":-352,"message":"风控校验失败"}""")
        assertTrue(r is BiliNavResult.Failed)
        assertEquals(-352, (r as BiliNavResult.Failed).code)
    }

    @Test
    fun `nav 非 JSON 判 Unknown 且带上长度（诊断用）`() {
        val html = "<html>412</html>"
        val r = BiliAuthApi.parseNav(html) as BiliNavResult.Unknown
        assertTrue("长度要进诊断信息：${r.detail}", r.detail.contains(html.length.toString()))
        assertTrue(BiliAuthApi.parseNav(null) is BiliNavResult.Unknown)
    }

    @Test
    fun `nav 的 code 为 0 但 isLogin 为 false 也算未登录（防御性判据）`() {
        assertEquals(
            BiliNavResult.NotLoggedIn,
            BiliAuthApi.parseNav("""{"code":0,"message":"0","data":{"isLogin":false}}"""),
        )
    }

    // ---------------------------------------------------------------- 凭据本身的纯逻辑

    @Test
    fun `cookieHeader 的形状与空字段省略`() {
        assertEquals(
            "SESSDATA=S; bili_jct=J; DedeUserID=7",
            BiliCredential(sessdata = "S", biliJct = "J", dedeUserId = "7").cookieHeader(),
        )
        // 空的字段必须省略：`bili_jct=` 这种空值会让服务端的 CSRF 校验拿到空串。
        assertEquals("SESSDATA=S", BiliCredential(sessdata = "S").cookieHeader())
        assertEquals("", BiliCredential().cookieHeader())
        assertTrue(BiliCredential(sessdata = "S").isUsable())
        assertFalse("只有 bili_jct 不算已登录", BiliCredential(biliJct = "J").isUsable())
    }

    @Test
    fun `凭据的 toString 不含任何凭据值（结构性防泄露）`() {
        // 这一条比「源码扫描有没有打日志」更强：任何形式的字符串插值（含崩溃上报）
        // 都不可能把 SESSDATA 带出去。
        val cred = BiliCredential(
            sessdata = "S_VALUESHOULD_NOT_APPEAR",
            biliJct = "J_VALUESHOULD_NOT_APPEAR",
            dedeUserId = "1234567",
            dedeUserIdCkMd5 = "M_VALUESHOULD_NOT_APPEAR",
            sid = "SID_VALUESHOULD_NOT_APPEAR",
        )
        val text = cred.toString()
        assertFalse(text.contains("S_VALUESHOULD_NOT_APPEAR"))
        assertFalse(text.contains("J_VALUESHOULD_NOT_APPEAR"))
        assertFalse(text.contains("M_VALUESHOULD_NOT_APPEAR"))
        assertFalse(text.contains("SID_VALUESHOULD_NOT_APPEAR"))
        assertTrue("长度要留着，否则诊断不了", text.contains("len=${cred.sessdata.length}"))
        assertTrue("mid 不是秘密，可以打", text.contains("1234567"))
        // 数据类的 equals/hashCode 不受影响。
        assertEquals(cred, cred.copy())
    }
}
