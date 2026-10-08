/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：qm 登录态续期的纯逻辑单测（凭证解析 / 合并 / 续期时机 / 请求体 / 码表）。
 */

package com.takahashirinta.ncrust.qq

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.9：qm 静默续期的纯逻辑单测。
 *
 * ## 这些用例守的是什么
 *
 * 用户报障是「qq 音乐登录身份在一周左右就会掉」。那条链路上**唯一**能在 JVM 里
 * 被钉住的环节就是这一层：凭证怎么解析、缺字段算不算能用、什么时候该续期、
 * 请求体长什么样、哪些响应码意味着「别再试了」。
 *
 * 真机上**故意不做**的两件事（写在这里免得下一个人以为漏了）：
 * 1. 不用有效凭证去换真票据 —— 那需要一个真实账号的长期凭证，
 *    而且在开发机上跑一次就消耗一次刷新额度；
 * 2. 不给真实号码发短信验证码（v2.1.1 的同一条纪律）。
 *
 * 所以这里的判据全部是**协议契约**（字段名、参数名、码表），
 * 来源是 `L-1124/QQMusicApi` 的 `Credential` 模型与 `refresh_credential()` 的三套参数分支，
 * 以及本仓库已经落盘的真实 cookie 形状（`ncrust_qq_prefs` 样本，见 `QqCookieTest`）。
 */
class QqRefreshCredentialTest {

    // ---------------- 解析 ----------------

    /** 一个完整的成功响应 `data`（字段名按服务端下发的 camelCase 写）。 */
    private fun fullData(): JSONObject = JSONObject()
        .put("musicid", 1152921504891728649L)
        .put("str_musicid", "1152921504891728649")
        .put("musickey", "W_X_63B0ain1XYN5_iJ5Pv2eBYrZiQHBxGK_ZOT8FUlm8neJKo1qQlKMvrWIXF7wQ0bpuTtcnApdHbx0ERETpSS5LhwcnuO3ed8LdasvbAYzH2BSkJX0uizgEusvorRagV7qSkqW7Qyy9sEnoub_fykaP_BnBIksGog")
        .put("refreshKey", "refresh-key-value")
        .put("refreshToken", "refresh-token-value")
        .put("accessToken", "access-token-value")
        .put("openid", "openid-value")
        .put("unionid", "unionid-value")
        .put("loginType", 1)
        .put("musickeyCreateTime", 1790277358L)
        .put("keyExpiresIn", 604800L)
        .put("needRefreshKeyIn", 86400L)
        .put("encryptUin", "encrypt-uin-value")

    @Test
    fun `完整凭证的每个字段都被读出来`() {
        val c = QqRefreshCredential.fromLoginData(fullData())!!
        assertEquals(1152921504891728649L, c.musicId)
        assertEquals("1152921504891728649", c.strMusicId)
        assertNotNull(c.musicKey)
        assertEquals("refresh-key-value", c.refreshKey)
        assertEquals("refresh-token-value", c.refreshToken)
        assertEquals("access-token-value", c.accessToken)
        assertEquals("openid-value", c.openId)
        assertEquals("unionid-value", c.unionId)
        assertEquals(QqRefreshCredential.LOGIN_TYPE_WECHAT, c.loginType)
        assertEquals(1790277358L, c.keyCreatedAt)
        assertEquals(604800L, c.keyExpiresIn)
        assertEquals(86400L, c.needRefreshKeyIn)
        assertEquals("encrypt-uin-value", c.encryptUin)
        assertTrue(c.isRefreshable())
        assertTrue(c.missingFields().isEmpty())
    }

    @Test
    fun `data 为 null 返回 null——不存在半份凭证`() {
        assertNull(QqRefreshCredential.fromLoginData(null))
    }

    /**
     * 实测的**失败骨架**（`Login` 回 `code=1000` 时 `data` 字段齐全但值全空）。
     *
     * 与 `QqPhoneLoginTest` 里那条用例同一个样本形状：绝不能让「一次失败的登录」
     * 覆盖掉上一份还能用的凭证。
     */
    @Test
    fun `失败骨架不是一份可用凭证`() {
        val skeleton = JSONObject()
            .put("musicid", 0)
            .put("musickey", "")
            .put("refreshKey", "")
            .put("refreshToken", "")
            .put("accessToken", "")
            .put("musickeyCreateTime", 0)
            .put("keyExpiresIn", 0)
        val c = QqRefreshCredential.fromLoginData(skeleton)!!
        assertFalse(c.isRefreshable())
        assertTrue(c.missingFields().contains("musicid"))
        assertTrue(c.missingFields().contains("musickey"))
        // ⚠️ refreshKey/refreshToken **不在**缺项里：它们是「服务端想不想下发」的事，
        // 不是我们的门槛 —— 实测只带 musicid+musickey+openid 就能换回新票
        // （见 `QqRefreshCredential.isRefreshable` 的 KDoc 与 `RenewProbeLiveTest`）。
        assertFalse(c.missingFields().contains("refreshKey"))
    }

    /**
     * `loginType` 缺失时按 `musickey` 前缀推断。
     *
     * 这条不是锦上添花：`loginType` 决定 [QqRequests.refreshCredential] 的参数集，
     * 推断错了就是「微信账号按 QQ 账号去刷新」—— 服务端不会报错，只会回一个换不出票的结果。
     */
    @Test
    fun `loginType 缺失时按 musickey 前缀推断微信与 QQ`() {
        // ⚠️ `JSONObject.remove` 返回被删掉的值，不是 this —— 不能链式写。
        val noLoginType = fullData().apply { remove("loginType") }
        val wechat = QqRefreshCredential.fromLoginData(noLoginType)!!
        assertEquals(QqRefreshCredential.LOGIN_TYPE_WECHAT, wechat.loginType)

        val qqData = fullData().apply {
            put("musickey", "Q_H_L_abcdefg")
            remove("loginType")
        }
        val qq = QqRefreshCredential.fromLoginData(qqData)!!
        assertEquals(QqRefreshCredential.LOGIN_TYPE_QQ, qq.loginType)
    }

    @Test
    fun `musicid 缺失时回落到 str_musicid`() {
        val c = QqRefreshCredential.fromLoginData(
            fullData().put("musicid", 0).put("str_musicid", "123456789"),
        )!!
        assertEquals(123456789L, c.musicId)
    }

    /** 负数/异常时间戳按「未知」处理，不许把 `coerceAtLeast` 省掉。 */
    @Test
    fun `负数时间戳归零而不是原样留下`() {
        val c = QqRefreshCredential.fromLoginData(
            fullData().put("musickeyCreateTime", -1L).put("keyExpiresIn", -1L),
        )!!
        assertEquals(0L, c.keyCreatedAt)
        assertEquals(0L, c.keyExpiresIn)
        // 「未知」不是「已过期」：不许因为时间戳异常就去刷新。
        assertFalse(c.isExpired(nowSeconds = 1L))
        assertFalse(c.needsRefresh(nowSeconds = 1L))
        assertNull(c.remainingSeconds(nowSeconds = 1L))
    }

    /**
     * **真机形状的凭证必须判成「可续期」**（v3.4.9 真机实测后的回归钉）。
     *
     * 设备上那次扫码登录服务端回的字段是：`openid` / `musicid` / `musickey` /
     * `str_musicid` / `loginType=1` / `keyExpiresIn=259200`，
     * 而 `refresh_key` / `refresh_token` / `access_token` **三个键都在、值都空**。
     *
     * 第一版因此把它判成不可续期（界面上写着「不会自动续期」），
     * 而实测「只带 musicid + musickey + openid 去续期」是**成功**的
     * （`req.code = 0`，且这次响应里的 `refresh_token` 有值）。
     * 这条用例把那个教训钉住：**门槛写严了，会正好把唯一能续的那条路挡在门外**。
     */
    @Test
    fun `真机形状的微信扫码凭证（无 refreshKey 无 refreshToken）可续期`() {
        val deviceShaped = QqRefreshCredential(
            musicId = 1152921504891728649L,
            musicKey = "W_X_" + "a".repeat(159),
            openId = "oWvuLjgj_PD_6gfsMoCVlwb9ip04g",
            strMusicId = "1152921504891728649",
            loginType = QqRefreshCredential.LOGIN_TYPE_WECHAT,
            keyCreatedAt = 1791482430L,
            keyExpiresIn = 259_200L,
            needRefreshKeyIn = 0L,
        )
        assertTrue("真机形状必须判成可续期", deviceShaped.isRefreshable())
        assertTrue(deviceShaped.missingFields().isEmpty())
    }

    /** 服务端**明确索要** refreshKey（`needRefreshKeyIn > 0`）而它缺失时，才算不可续期。 */
    @Test
    fun `服务端索要 refreshKey 而它缺失时判成不可续期`() {
        val needsKey = QqRefreshCredential(
            musicId = 1L,
            musicKey = "mk",
            needRefreshKeyIn = 86_400L,
        )
        assertFalse(needsKey.isRefreshable())
        assertEquals(listOf("refreshKey"), needsKey.missingFields())
    }

    // ---------------- 时机 ----------------

    @Test
    fun `剩余时间与到期判定都基于 签发时间 加 寿命`() {
        val c = QqRefreshCredential(keyCreatedAt = 1_000L, keyExpiresIn = 600L)
        assertEquals(300L, c.remainingSeconds(nowSeconds = 1_300L))
        assertFalse(c.isExpired(nowSeconds = 1_599L))
        assertTrue(c.isExpired(nowSeconds = 1_600L))
    }

    /**
     * 「寿命未知」必须与「已经过期」区分开。
     *
     * 参考实现（`L-1124/QQMusicApi` 的 `Credential.is_expired()`）在时间戳为 0 时
     * 会算出 `now >= 0` ⇒ **恒为已过期**。本仓库不能照搬：那会让每一次冷启动都发一次
     * 注定失败的刷新请求（老凭证没有这两个字段），而失败的表现是
     * 「明明没到期却被当成过期」—— 比不刷新更难查。
     */
    @Test
    fun `寿命未知时既不判过期也不主动刷新`() {
        val unknown = QqRefreshCredential(refreshKey = "rk", musicKey = "mk", musicId = 1L)
        assertFalse(unknown.isExpired(nowSeconds = Long.MAX_VALUE / 2))
        assertFalse(unknown.needsRefresh(nowSeconds = Long.MAX_VALUE / 2))
        assertNull(unknown.remainingSeconds(nowSeconds = 0L))
    }

    /**
     * 主动续期必须**提前**：剩余量掉到 12 小时**或更少**就该换，而不是等到过期那一秒。
     *
     * 边界语义（含等号）钉在这里：剩余量**恰好** 12 小时就已经该刷了。
     * 它决定了「用户每晚关机、第二天开机」会不会刚好越过那条线 ——
     * 把等号去掉就等于把窗口缩短一秒，而那正是最容易被后人「顺手改成 `>`」的地方。
     *
     * ⚠️ 夹具里的 `keyCreatedAt` 必须**大于 0**：它是「寿命已知 / 未知」的开关，
     * 用 `0L` 会让整个函数在第一道守卫就返回 false，用例于是测不到边界
     * （写这条时真的踩过一次 —— 断言失败的原因看起来像算术错，其实是守卫）。
     */
    @Test
    fun `提前 12 小时进入需要主动续期的窗口`() {
        val margin = QqRefreshCredential.REFRESH_MARGIN_SECONDS
        val created = 1_000_000L
        val expiry = created + 7L * 24 * 3600
        val c = QqRefreshCredential(keyCreatedAt = created, keyExpiresIn = 7L * 24 * 3600)
        assertFalse("还剩 12 小时零 1 秒：不该刷", c.needsRefresh(nowSeconds = expiry - margin - 1))
        assertTrue("恰好剩 12 小时（含等号）：该刷", c.needsRefresh(nowSeconds = expiry - margin))
        assertTrue("剩得比 12 小时还少：该刷", c.needsRefresh(nowSeconds = expiry - margin + 1))
        assertTrue("已经过期当然要刷", c.needsRefresh(nowSeconds = expiry + 1))
        // 但「过期」与「该刷」是两件事 —— 后者包含前者，反之不成立。
        assertFalse(c.isExpired(nowSeconds = expiry - margin))
    }

    // ---------------- 合并 ----------------

    /**
     * 刷新响应**只回了一半字段**时，缺的那些必须保留旧值。
     *
     * 为什么这条是承重的：`refreshKey` 是长期凭证、`refreshToken` 是轮换凭证，
     * 参考实现把它们都当**可选**字段。若一次刷新把 `refreshKey` 写成空，
     * 下一次刷新就失败了 —— 用户看到的是「续期了一次之后照样掉登录」，
     * 而那比完全没有续期更难归因（他会以为修好了一半）。
     */
    @Test
    fun `刷新响应缺字段时保留旧值——否则只能续期一次`() {
        val old = QqRefreshCredential(
            musicId = 1L,
            musicKey = "old-key",
            refreshKey = "old-refresh-key",
            refreshToken = "old-refresh-token",
            accessToken = "old-access-token",
            openId = "old-openid",
            unionId = "old-unionid",
            strMusicId = "1",
            loginType = QqRefreshCredential.LOGIN_TYPE_WECHAT,
            keyCreatedAt = 100L,
            keyExpiresIn = 100L,
            needRefreshKeyIn = 50L,
            encryptUin = "old-encrypt-uin",
        )
        // 新响应只给了新票据与新签发时间（真实世界里常见的最小响应）。
        val new = QqRefreshCredential(
            musicId = 1L,
            musicKey = "new-key",
            keyCreatedAt = 200L,
            keyExpiresIn = 604800L,
            loginType = QqRefreshCredential.LOGIN_TYPE_WECHAT,
        )
        val merged = QqRefreshCredential.mergeWith(old, new)
        assertEquals("new-key", merged.musicKey)
        assertEquals(200L, merged.keyCreatedAt)
        assertEquals(604800L, merged.keyExpiresIn)
        // 这三条是「还能不能续第二次」的判据。
        assertEquals("old-refresh-key", merged.refreshKey)
        assertEquals("old-refresh-token", merged.refreshToken)
        assertEquals("old-access-token", merged.accessToken)
        assertEquals("old-openid", merged.openId)
        assertEquals("old-unionid", merged.unionId)
        assertEquals(50L, merged.needRefreshKeyIn)
        assertEquals("old-encrypt-uin", merged.encryptUin)
        assertTrue(merged.isRefreshable())
    }

    @Test
    fun `没有旧凭证时合并就是原样返回`() {
        val fresh = QqRefreshCredential(musicId = 7L, refreshKey = "rk", musicKey = "mk")
        assertEquals(fresh, QqRefreshCredential.mergeWith(null, fresh))
    }

    @Test
    fun `新值非空时以新值为准`() {
        val old = QqRefreshCredential(musicId = 1L, refreshKey = "old", musicKey = "old-k")
        val new = QqRefreshCredential(musicId = 1L, refreshKey = "new", musicKey = "new-k")
        val merged = QqRefreshCredential.mergeWith(old, new)
        assertEquals("new", merged.refreshKey)
        assertEquals("new-k", merged.musicKey)
    }

    // ---------------- 请求体 ----------------

    /**
     * 续期请求的**形状契约**：同一个 `Login` 方法，只有 `loginMode` 从 1 换成 2。
     *
     * 每个参数名都在这里钉死，因为写错了**服务端不会报参数错**，
     * 只会回一个换不出票的 `data`（或者 `code=1000`）——
     * 表现是「续期静默失败」，也就是用户报的那个 bug 换了个形式继续存在。
     */
    @Test
    fun `续期请求体带齐并集参数且 loginMode 为 2`() {
        val cookie = "uin=1152921504891728649; qqmusic_uin=1152921504891728649; " +
            "qqmusic_key=W_X_abc; qm_keyst=W_X_abc; psrf_musickey_createtime=1790277358; " +
            "psrf_access_token_expiresAt=604800"
        val credential = QqRefreshCredential.fromLoginData(fullData())!!
        val request = QqRequests.refreshCredential(credential, cookie)

        assertEquals(QqRequests.LOGIN_MODULE, request.getString("module"))
        assertEquals(QqRequests.LOGIN_METHOD, request.getString("method"))
        val param = request.getJSONObject("param")
        // —— 开关：整条链路的关键 ——
        assertEquals(2, param.getInt("loginMode"))
        assertEquals(2, QqRequests.LOGIN_MODE_REFRESH)
        // —— 身份 ——
        assertEquals(1152921504891728649L, param.getLong("musicid"))
        assertEquals("1152921504891728649", param.getString("str_musicid"))
        assertEquals("openid-value", param.getString("openid"))
        assertEquals("unionid-value", param.getString("unionid"))
        // —— 上一张票 ——
        assertEquals(credential.musicKey, param.getString("musickey"))
        // —— 续期凭证 ——
        assertEquals("refresh-key-value", param.getString("refresh_key"))
        assertEquals("refresh-token-value", param.getString("refresh_token"))
        assertEquals("access-token-value", param.getString("access_token"))
        // —— expired_in 是**绝对到期时刻**：签发时间 + access_token 寿命 ——
        assertEquals(1790277358L + 604800L, param.getLong("expired_in"))
    }

    /**
     * 缺的字段一律传**空串**（与参考实现传字段默认值同义），而不是省略 ——
     * 「字段缺失」与「字段为空」在服务端是两种判据。
     *
     * ⚠️ 这份凭证**没有** `musickey`，所以断言空串；但 `refresh_key` 有值（`"rk"`）
     * 就必须原样发出去 —— 请求体里漏掉续期凭证是本链路最典型的静默失败。
     */
    @Test
    fun `缺签发时间时 expired_in 传 0 而不是猜`() {
        val credential = QqRefreshCredential(musicId = 1L, refreshKey = "rk")
        val param = QqRequests.refreshCredential(credential, null).getJSONObject("param")
        assertEquals(0L, param.getLong("expired_in"))
        assertEquals("", param.getString("openid"))
        assertEquals("", param.getString("unionid"))
        assertEquals("", param.getString("musickey"))
        assertEquals("rk", param.getString("refresh_key"))
        assertEquals("", param.getString("refresh_token"))
        assertEquals("", param.getString("access_token"))
        // str_musicid 回落到 cookie 的 uin，最后才回落到数字 musicid。
        assertEquals("1", param.getString("str_musicid"))
    }

    @Test
    fun `cookie 里的 uin 用来兜底 str_musicid`() {
        val credential = QqRefreshCredential(musicId = 0L, refreshKey = "rk", musicKey = "mk")
        val param = QqRequests.refreshCredential(credential, "uin=987654321; qqmusic_key=x")
            .getJSONObject("param")
        assertEquals("987654321", param.getString("str_musicid"))
    }

    // ---------------- 码表 ----------------

    /**
     * 续期侧的码表**不是**登录侧那张表：登录时 `1000` 是「验证码错」，
     * 续期时同一个码只有一个含义 —— **凭证不再被接受**（终态，别再试）。
     *
     * 依据是参考实现的 `_validate_result`：它把 `1000` / `104400` / `104401`
     * 一律抛成 `LoginAuthExpiredError`。
     */
    @Test
    fun `续期码表把 1000 读成凭证失效而不是可重试`() {
        assertEquals(QqTokenRefresher.RefreshOutcome.REFRESHED, QqTokenRefresher.classifyRefresh(0))
        for (code in listOf(1000, 104400, 104401)) {
            assertEquals(
                "code=$code",
                QqTokenRefresher.RefreshOutcome.AUTH_EXPIRED,
                QqTokenRefresher.classifyRefresh(code),
            )
        }
        for (code in listOf(100001, 104604)) {
            assertEquals(
                "code=$code",
                QqTokenRefresher.RefreshOutcome.RATE_LIMITED,
                QqTokenRefresher.classifyRefresh(code),
            )
        }
        for (code in listOf(20277, 20278, 20450)) {
            assertEquals(
                "code=$code",
                QqTokenRefresher.RefreshOutcome.ACCOUNT_RESTRICTED,
                QqTokenRefresher.classifyRefresh(code),
            )
        }
        assertEquals(
            QqTokenRefresher.RefreshOutcome.DEVICE_LIMIT,
            QqTokenRefresher.classifyRefresh(20279),
        )
    }

    @Test
    fun `认不出的续期码落到中性失败桶而不是被猜成失效`() {
        for (code in listOf(-1, 1, 999, 12345)) {
            assertEquals(
                "code=$code",
                QqTokenRefresher.RefreshOutcome.FAILED,
                QqTokenRefresher.classifyRefresh(code),
            )
        }
    }

    // ---------------- 日志纪律 ----------------

    /**
     * 诊断摘要**只给长度与布尔，绝不给值**。
     *
     * 这条是硬红线（与 `QqCookie.fieldNamesOf` 同一条纪律）：`refresh_key` / `access_token`
     * 是能换出新票据的凭证，一旦进了 logcat，用户截图/贴日志就等于泄露账号。
     */
    @Test
    fun `诊断摘要不泄露任何凭证值`() {
        val c = QqRefreshCredential.fromLoginData(fullData())!!
        val text = c.describe()
        for (secret in listOf(
            "refresh-key-value",
            "refresh-token-value",
            "access-token-value",
            "openid-value",
            "unionid-value",
            "encrypt-uin-value",
        )) {
            assertFalse("摘要里出现了凭证值：$secret", text.contains(secret))
        }
        assertFalse(text.contains(c.musicKey!!))
        assertTrue(text.contains("refreshKey=len17"))
        assertTrue(text.contains("refreshable=true"))
    }
}
