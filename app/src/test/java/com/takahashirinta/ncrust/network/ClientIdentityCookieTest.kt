package com.takahashirinta.ncrust.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClientIdentity.extraCookieFor] 的行为契约（v3.3.0 新增）。
 *
 * ## 为什么这个纯函数值得单独钉住
 *
 * 服务端按 **Cookie 里的客户端身份**判定音质上限（见 `ClientIdentity` 的文件头）：
 * 缺 `os`/`appver` 时 hires / 母带会被**静默回落**成 lossless，而 `code` 仍是 200 ——
 * 客户端看不出任何异常。所以「身份字段有没有真的发出去」直接决定
 * 用户反馈第 6 条（「需要很多次退出登录再登录才能播放 VIP 资源或者音质」）
 * 里的那一半：**时好时坏**。
 *
 * 旧实现 `.filterNot { k in existing }` 的缺陷是：用户 cookie 里只要出现同名键
 * （哪怕是 `appver=` 这种**空值**），我们就不再声明身份。这条用例把它钉死。
 *
 * ## 另一条同样重要的契约：**不许碰会话字段**
 *
 * `MUSIC_U` / `__csrf` 是登录态本身，动一个字节就可能把用户登出，
 * 或者让所有写操作 403（缺 `__csrf` 的后果见 AGENTS.md 的 Playlist 写操作一节）。
 */
class ClientIdentityCookieTest {

    /** 真机实测形状（`.scratch/pcl-prefs/shared_prefs/ncrust_prefs.xml`，20 键，无 os/appver）。 */
    private val realShaped =
        "MUSIC_U=00ABCDEF==; __csrf=abcdef1234567890; __remember_me=true; NMTID=00Oxyz123; _ntes_nuid=1a2b3c"

    private fun fields(cookie: String): Map<String, String> =
        cookie.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    // ---------------------------------------------------------------- 会话字段 ----

    @Test
    fun `会话字段一个字节都不许动`() {
        val out = fields(ClientIdentity.extraCookieFor(realShaped))

        assertEquals("00ABCDEF==", out["MUSIC_U"])
        assertEquals("abcdef1234567890", out["__csrf"])
        assertEquals("true", out["__remember_me"])
        assertEquals("00Oxyz123", out["NMTID"])
        assertEquals("1a2b3c", out["_ntes_nuid"])
    }

    @Test
    fun `用户字段在前 身份字段在后`() {
        val out = ClientIdentity.extraCookieFor(realShaped)

        assertTrue("MUSIC_U 应排在 os 之前", out.indexOf("MUSIC_U=") < out.indexOf("os="))
        // 末尾按声明顺序补齐 os / appver / osver / deviceId
        assertTrue(out.indexOf("os=") < out.indexOf("appver="))
        assertTrue(out.indexOf("appver=") < out.indexOf("osver="))
        assertTrue(out.indexOf("osver=") < out.indexOf("deviceId="))
    }

    // ---------------------------------------------------------------- 核心修复 ----

    @Test
    fun `用户 cookie 里的空值身份键不得让我们放弃声明身份`() {
        // ★ 这是本版修的缺陷本身。旧实现在这里会把 os/appver/osver 全部跳过 ⇒
        // 服务端看到的身份仍是「未知」⇒ hires 静默降级成 lossless，而 code 还是 200。
        val hostile = "MUSIC_U=t; __csrf=c; os=; appver=; osver="

        val out = fields(ClientIdentity.extraCookieFor(hostile))

        assertEquals("os 必须被我们覆盖", ClientIdentity.OS, out["os"])
        assertEquals("appver 必须被我们覆盖", ClientIdentity.APPVER, out["appver"])
        assertEquals("osver 必须被我们覆盖", ClientIdentity.OSVER, out["osver"])
        assertTrue("会话字段仍然保留", out.containsKey("MUSIC_U"))
    }

    @Test
    fun `用户 cookie 里的错误身份值被覆盖`() {
        // 用户可能在官方网页端登录过，cookie 里带着官方 web 的身份（os=pc 但 appver 很旧）。
        val officialWeb = "MUSIC_U=t; os=pc; appver=2.0.0; osver=6.1"

        val out = fields(ClientIdentity.extraCookieFor(officialWeb))

        assertEquals(ClientIdentity.APPVER, out["appver"])
        assertEquals(ClientIdentity.OSVER, out["osver"])
    }

    @Test
    fun `被覆盖的键保留原来的位置 不重复出现`() {
        // 位置固定是「用户字段在前」这条契约的一部分：键名不变、值变，不应挪到末尾。
        val withIdentity = "MUSIC_U=t; appver=old; __csrf=c; os=old"

        val out = ClientIdentity.extraCookieFor(withIdentity)

        assertEquals("每个键只能出现一次", 1, Regex("(^|;\\s*)appver=").findAll(out).count())
        assertEquals(1, Regex("(^|;\\s*)os=").findAll(out).count())
        // appver 仍在 MUSIC_U 之后、__csrf 之前（位置未挪动）
        assertTrue(out.indexOf("appver=") < out.indexOf("__csrf="))
    }

    // ---------------------------------------------------------------- deviceId ----

    @Test
    fun `deviceId 尊重用户已有的值`() {
        // deviceId 标识「这台设备上的这个会话」，用户 cookie 里那个才是服务端认识的。
        // 换掉它等于换了一个会话指纹 —— 所以只在用户没有时才补我们自己的。
        val withDevice = "MUSIC_U=t; deviceId=user-owned-device-id"

        val out = fields(ClientIdentity.extraCookieFor(withDevice))

        assertEquals("user-owned-device-id", out["deviceId"])
    }

    @Test
    fun `设备指纹的 cookie 字段名必须是 deviceId 而不是内部的 prefs 键名`() {
        // ★ 这条用例是为我在 v3.3.0 重构时犯的一个错写的：
        // 我拿 `KEY_DEVICE_ID`（**SharedPreferences 的键名** `client_device_id`）
        // 去拼 cookie，于是发出去的是 `client_device_id=…` —— 服务端不认识这个字段，
        // 设备指纹等于没发，而请求里看起来完全正常（有键有值）。
        // 这类「键名语义错配」不报错、不降级、也不影响 code，只会让指纹静默失效。
        val out = ClientIdentity.extraCookieFor("MUSIC_U=t")

        assertTrue("必须发出 deviceId=…（官方客户端的字段名）", out.contains("deviceId="))
        assertFalse(
            "绝不能把内部 prefs 键名当 cookie 字段名发出去",
            out.contains("client_device_id="),
        )
    }

    @Test
    fun `用户没有 deviceId 时补一个`() {
        val out = fields(ClientIdentity.extraCookieFor("MUSIC_U=t"))

        assertTrue("应补上 deviceId", out.containsKey("deviceId"))
    }

    // ---------------------------------------------------------------- 边界 ----

    @Test
    fun `空 cookie 不抛异常且给出完整身份`() {
        listOf(null, "", "   ").forEach { input ->
            val out = fields(ClientIdentity.extraCookieFor(input))
            assertEquals(ClientIdentity.OS, out["os"])
            assertEquals(ClientIdentity.APPVER, out["appver"])
            assertEquals(ClientIdentity.OSVER, out["osver"])
            assertTrue(out.containsKey("deviceId"))
        }
    }

    @Test
    fun `大小写不同的身份键同样被覆盖`() {
        // HTTP cookie 的键名大小写不敏感，服务端不会因为大小写不同就当它是另一个键。
        val upper = "MUSIC_U=t; APPVER=old; Os=old"

        val out = fields(ClientIdentity.extraCookieFor(upper))

        val appverValues = out.filterKeys { it.equals("appver", ignoreCase = true) }.values
        assertEquals("只应有一个 appver 视角的键", 1, appverValues.size)
        assertEquals(ClientIdentity.APPVER, appverValues.first())
    }

    @Test
    fun `没有等号的片段不产生半截键`() {
        val malformed = "MUSIC_U=t; garbage; __csrf=c"

        val out = ClientIdentity.extraCookieFor(malformed)

        assertFalse("垃圾片段不得进入结果", out.contains("garbage"))
        assertTrue(out.contains("__csrf=c"))
        assertTrue(out.contains("MUSIC_U=t"))
    }

    @Test
    fun `值里含等号的会话字段完好`() {
        // MUSIC_U 的真实值带 base64 的 `=` 填充（真机实测 222 字符、以 `==` 结尾）。
        val padded = "MUSIC_U=YWJjZGVmZ2hpamtsbW5vcA==; __csrf=c"

        val out = fields(ClientIdentity.extraCookieFor(padded))

        assertEquals("YWJjZGVmZ2hpamtsbW5vcA==", out["MUSIC_U"])
    }
}
