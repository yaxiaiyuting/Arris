package com.takahashirinta.ncrust.qq

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.1：qm 手机号验证码登录的纯逻辑单测。
 *
 * 这一层的每个函数都对应一条**实测出来的协议约束**（2026-09，`tools/probe-qq-phone-login.py`），
 * 而不是我的推测。写错任何一条的表现都是「用户点了发送/登录，界面报一个看不懂的错」，
 * 或者更糟 —— 落一份缺票据的 cookie，让界面显示「已登录」而一取链就说没权限。
 */
class QqPhoneLoginTest {

    // ---------- 手机号规范化 ----------

    @Test
    fun `大陆手机号的常见写法都能规范化`() {
        val expected = "13800138000"
        for (raw in listOf(
            "13800138000",
            "138 0013 8000",
            "138-0013-8000",
            "+86 138 0013 8000",
            "+8613800138000",
            "8613800138000", // 带国家码但没写 +
            " 13800138000 ",
        )) {
            assertEquals("raw=$raw", expected, QqPhoneLogin.normalizePhone(raw))
        }
    }

    @Test
    fun `不合法的号码本地就拦下——省掉一次注定 104400 的往返`() {
        for (raw in listOf(
            "",
            "   ",
            "1380013800", // 10 位
            "138001380001", // 12 位
            "23800138000", // 大陆手机号必须 1 开头
            "1234",
            "abcdefghijk",
            "8613800138", // 带 86 但主体不足 11 位
        )) {
            assertNull("raw=$raw 不该通过本地校验", QqPhoneLogin.normalizePhone(raw))
        }
    }

    @Test
    fun `非大陆区号按长度校验，不猜国家码`() {
        assertEquals("4155552671", QqPhoneLogin.normalizePhone("415 555 2671", "1"))
        // 带区号前缀的会被剥掉：areaCode 是单独发过去的，phoneNo 里不该再带国家码
        assertEquals("4155552671", QqPhoneLogin.normalizePhone("14155552671", "1"))
        // 太短：剥完只剩 3 位，不够一个号码，所以不剥；原串 4 位本身也不合格
        assertNull(QqPhoneLogin.normalizePhone("1234", "1"))
        // 太长的边界要按「剥完之后的长度」算：16 位剥掉国家码正好 15 位 = E.164 上限，**是合法的**；
        // 所以要 17 位才越界。这条用例原本写成 16 位，是把边界算错了。
        assertEquals("234567890123456", QqPhoneLogin.normalizePhone("1234567890123456", "1"))
        assertNull(QqPhoneLogin.normalizePhone("12345678901234567", "1"))
    }

    // ---------- 验证码规范化 ----------

    @Test
    fun `验证码只留数字，长度收在 4 到 8 位`() {
        assertEquals("123456", QqPhoneLogin.normalizeCode("123456"))
        assertEquals("123456", QqPhoneLogin.normalizeCode(" 123 456 "))
        // 粘贴带前缀文案时也要能提出验证码
        assertEquals("123456", QqPhoneLogin.normalizeCode("验证码：123456"))
        assertEquals("1234", QqPhoneLogin.normalizeCode("1234"))
        assertNull(QqPhoneLogin.normalizeCode("123"))
        assertNull(QqPhoneLogin.normalizeCode("123456789"))
        assertNull(QqPhoneLogin.normalizeCode(""))
        assertNull(QqPhoneLogin.normalizeCode("abcdef"))
    }

    // ---------- 响应码归类（实测） ----------

    @Test
    fun `发验证码的响应码归类`() {
        assertEquals(QqPhoneLogin.SendOutcome.SENT, QqPhoneLogin.classifySend(0))
        assertEquals(QqPhoneLogin.SendOutcome.NEED_CAPTCHA, QqPhoneLogin.classifySend(20276))
        assertEquals(QqPhoneLogin.SendOutcome.TOO_FREQUENT, QqPhoneLogin.classifySend(100001))
        assertEquals(QqPhoneLogin.SendOutcome.BAD_NUMBER, QqPhoneLogin.classifySend(104400))
        // 10006 = 请求本身畸形（实测是 areaCode 传了数字）——按失败报，不要谎称号码有问题
        assertEquals(QqPhoneLogin.SendOutcome.FAILED, QqPhoneLogin.classifySend(10006))
        assertEquals(QqPhoneLogin.SendOutcome.FAILED, QqPhoneLogin.classifySend(-1))
    }

    @Test
    fun `换凭证的响应码归类——1000 是失败不是成功`() {
        assertEquals(QqPhoneLogin.LoginOutcome.OK, QqPhoneLogin.classifyLogin(0))
        // 实测：假验证码 + 非法号码 → req.code = 1000，且 data 是完整骨架（值全空）。
        // 把 1000 误判成成功会是灾难性的：界面会说「登录成功」而其实什么都没拿到。
        assertEquals(QqPhoneLogin.LoginOutcome.CODE_WRONG, QqPhoneLogin.classifyLogin(1000))
        assertEquals(QqPhoneLogin.LoginOutcome.TOO_FREQUENT, QqPhoneLogin.classifyLogin(100001))
        assertEquals(QqPhoneLogin.LoginOutcome.NEED_CAPTCHA, QqPhoneLogin.classifyLogin(20276))
        // 104400 = 信封/登录方式错误（实测：去掉 comm.tmeLoginMethod 就是它）——那是客户端 bug
        assertEquals(QqPhoneLogin.LoginOutcome.FAILED, QqPhoneLogin.classifyLogin(104400))
    }

    /**
     * 用户报出来的 bug：v2.1.2 的手机号登录在失败分支复用了**扫码**那条文案
     * （`sourceQrFailed` = 「扫码登录失败」），于是短信登录失败时界面显示「扫码登录失败」。
     * 这条用例钉住的是「认不出的码要落到**中性**的 FAILED 桶」——
     * 界面据此显示「登录失败，请稍后重试」，而不是任何带前提的文案。
     */
    @Test
    fun `认不出的登录码落到中性失败桶而不是被猜成别的原因`() {
        for (code in listOf(1, 2, 7, 999, 104400, -1)) {
            assertEquals("code=$code", QqPhoneLogin.LoginOutcome.FAILED, QqPhoneLogin.classifyLogin(code))
        }
    }

    @Test
    fun `账号受限与设备超限有各自的桶`() {
        for (code in listOf(20277, 20278, 20450)) {
            assertEquals("code=$code", QqPhoneLogin.LoginOutcome.ACCOUNT_RESTRICTED, QqPhoneLogin.classifyLogin(code))
        }
        assertEquals(QqPhoneLogin.LoginOutcome.DEVICE_LIMIT, QqPhoneLogin.classifyLogin(20279))
        // 104604 = 登录频率限制，与 100001 同义
        assertEquals(QqPhoneLogin.LoginOutcome.TOO_FREQUENT, QqPhoneLogin.classifyLogin(104604))
        // ⚠️ 两个枚举同名不同型（SendOutcome / LoginOutcome），别拿一个去比另一个
        assertEquals(QqPhoneLogin.SendOutcome.TOO_FREQUENT, QqPhoneLogin.classifySend(104604))
    }

    // ---------- 凭证 → cookie ----------

    @Test
    fun `完整凭证能拼出 cookie`() {
        val data = JSONObject()
            .put("musicid", 1234567890L)
            .put("musickey", "W_Xabc123")
            .put("musickeyCreateTime", 1790270000L)
            .put("str_musicid", "1234567890")
        val cookie = QqPhoneLogin.cookieFromCredential(data)!!
        assertTrue(cookie, cookie.contains("uin=1234567890"))
        assertTrue(cookie, cookie.contains("qqmusic_uin=1234567890"))
        assertTrue(cookie, cookie.contains("qqmusic_key=W_Xabc123"))
        assertTrue(cookie, cookie.contains("qm_keyst=W_Xabc123"))
        assertTrue(cookie, cookie.contains("psrf_musickey_createtime=1790270000"))
        // 拼出来的串必须能被现有的 cookie 判据认成「已登录」——否则落盘了也没用
        assertTrue(QqCookie.isLoggedIn(cookie))
        assertEquals(1234567890L, QqCookie.uinOf(cookie))
        assertEquals("W_Xabc123", QqCookie.musicKeyOf(cookie))
    }

    @Test
    fun `缺票据或缺 uid 一律返回 null——绝不落半份登录态`() {
        val noKey = JSONObject().put("musicid", 123L).put("musickey", "")
        assertNull(QqPhoneLogin.cookieFromCredential(noKey))

        val noUid = JSONObject().put("musicid", 0L).put("musickey", "K")
        assertNull(QqPhoneLogin.cookieFromCredential(noUid))

        assertNull(QqPhoneLogin.cookieFromCredential(null))
        assertNull(QqPhoneLogin.cookieFromCredential(JSONObject()))
    }

    @Test
    fun `musicid 缺失时回落到 str_musicid`() {
        val data = JSONObject()
            .put("musicid", 0L)
            .put("str_musicid", "987654321")
            .put("musickey", "K")
        val cookie = QqPhoneLogin.cookieFromCredential(data)!!
        assertTrue(cookie, cookie.contains("uin=987654321"))
    }

    @Test
    fun `实测的失败骨架不会被误当成成功凭证`() {
        // 逐字来自探针：Login 用假验证码时服务端回的 35 字段骨架（值全空）。
        val skeleton = JSONObject(
            """{"openid":"","refresh_token":"","access_token":"","expired_at":0,"musicid":0,
               "musickey":"","musickeyCreateTime":0,"first_login":0,"errMsg":"","sessionKey":"",
               "unionid":"","str_musicid":"","errtip":"","nick":"","logo":"","feedbackURL":"",
               "encryptUin":"","userip":"223.116.80.255","lastLoginTime":0,"keyExpiresIn":0,
               "refresh_key":"","loginType":0,"prompt2bind":0,"logoffStatus":0,"otherAccounts":[],
               "otherPhoneNo":"","token":"","isPrized":0,"isShowDevManage":0,"errTip2":"","tip3":"",
               "encryptedPhoneNo":"","phoneNo":"100****0000","bindAccountType":0,"needRefreshKeyIn":0}""",
        )
        assertNull(QqPhoneLogin.cookieFromCredential(skeleton))
    }

    @Test
    fun `签发时间为 0 时不写进 cookie`() {
        val data = JSONObject().put("musicid", 1L).put("musickey", "K").put("musickeyCreateTime", 0L)
        val cookie = QqPhoneLogin.cookieFromCredential(data)!!
        assertTrue("0 是「未知」，不是「1970 年签发」", !cookie.contains("musickey_createtime"))
    }

    // ---------- 图形验证码（20276） ----------

    /**
     * 实测的失败响应里 `data` 就有 `securityURL` 这个 key（只是空串）——
     * 所以它在 `req.data` 下、名字全大写 URL，是钉死的事实而不是猜的。
     */
    @Test
    fun `从 req_data 里取图形验证码地址`() {
        val req = JSONObject(
            """{"code":20276,"data":{"errMsg":"need captcha",
               "securityURL":"https://y.qq.com/xyz/verify?token=abc","errTip":""}}""",
        )
        assertEquals("https://y.qq.com/xyz/verify?token=abc", QqPhoneLogin.securityUrlOf(req))
    }

    @Test
    fun `普通失败时 securityURL 是空串——必须当成没有而不是空地址`() {
        // 逐字来自探针实测的 104400 响应：
        // {"code":104400,"data":{"errMsg":"…phoneNo is invalid","securityURL":"","errTip":""}}
        val req = JSONObject(
            """{"code":104400,"data":{"errMsg":"failed to SendSMSAuthCode: ec=104400",
               "securityURL":"","errTip":""}}""",
        )
        assertNull(QqPhoneLogin.securityUrlOf(req))
    }

    @Test
    fun `没有 data 时取不到验证地址而不是抛异常`() {
        assertNull(QqPhoneLogin.securityUrlOf(null))
        assertNull(QqPhoneLogin.securityUrlOf(JSONObject("""{"code":10006}""")))
    }

    // ---------- 请求体形状（这些字段写错了服务端不报错，只会静默拒绝） ----------

    @Test
    fun `发码请求的 areaCode 必须是字符串——传数字会被判 10006`() {
        val param = QqRequests.sendPhoneAuthCode("13800138000").getJSONObject("param")
        // 实测：areaCode 传 JSON 数字 86 → req.code=10006（请求本身畸形，无 data）。
        // 这是全仓库最容易写错的一处：Kotlin 里 .put("areaCode", 86) 编译完全没问题。
        assertTrue("areaCode 必须是 String", param.get("areaCode") is String)
        assertEquals("86", param.getString("areaCode"))
        assertEquals("13800138000", param.getString("phoneNo"))
        assertEquals("qqmusic", param.getString("tmeAppid"))
    }

    @Test
    fun `发码请求的 module 与 method 是实测值`() {
        val req = QqRequests.sendPhoneAuthCode("13800138000")
        assertEquals("music.login.LoginServer", req.getString("module"))
        assertEquals("SendPhoneAuthCode", req.getString("method"))
    }

    @Test
    fun `换凭证请求的 loginMode 恒为 1`() {
        val req = QqRequests.phoneLogin("13800138000", "123456")
        assertEquals("music.login.LoginServer", req.getString("module"))
        assertEquals("Login", req.getString("method"))
        val param = req.getJSONObject("param")
        // loginMode=1 是「手机验证码登录」；2 是 refresh_token 续期。写混了会拿不到凭证。
        assertEquals(1, param.getInt("loginMode"))
        assertEquals("123456", param.getString("code"))
        assertEquals("13800138000", param.getString("phoneNo"))
    }
}
