package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · C：qm cookie 纯逻辑单测。
 *
 * 这些用例守的是「登录成功了但一取链就说没权限」这一类只在真机上出现的故障：
 * 票据字段名不止一个、微信登录的 uin 带 `o` 前缀、登录流程拿到的是增量字段集。
 */
class QqCookieTest {

    private val webLogin =
        "uin=o0123456789; qqmusic_key=ABC123def456; qm_keyst=ABC123def456; " +
            "psrf_qqunionid=UU1; ptcz=zzz; RK=1"

    private val qqLogin = "uin=123456789; qqmusic_key=KEY999"

    // ---------- parse ----------

    @Test
    fun `parse 容错——空串、空段、含等号的值、无分号`() {
        assertEquals(emptyMap<String, String>(), QqCookie.parse(null))
        assertEquals(emptyMap<String, String>(), QqCookie.parse(""))
        assertEquals(emptyMap<String, String>(), QqCookie.parse("   "))
        assertEquals(mapOf("a" to "1", "b" to "2"), QqCookie.parse("a=1;;  ;b=2;"))
        // 票据里可能含 = （base64 填充），只按第一个 = 切
        assertEquals(mapOf("k" to "aa=bb=="), QqCookie.parse("k=aa=bb=="))
        assertEquals(mapOf("a" to "1"), QqCookie.parse("a=1"))
        // 没有 = 的段直接丢掉，不要造出一个空值字段；同串里的合法段照常解析
        assertEquals(emptyMap<String, String>(), QqCookie.parse("garbage"))
        assertEquals(mapOf("a" to "1"), QqCookie.parse("garbage;a=1"))
        assertEquals(mapOf("a" to "1"), QqCookie.parse("=1;a=1"))
    }

    // ---------- uin ----------

    @Test
    fun `微信登录的 o 前缀 uin 要剥成数字`() {
        assertEquals(123456789L, QqCookie.uinOf("uin=o0123456789; qqmusic_key=K"))
    }

    @Test
    fun `QQ 登录的纯数字 uin 直接用`() {
        assertEquals(123456789L, QqCookie.uinOf(qqLogin))
    }

    @Test
    fun `没有 uin 时回落 wxuin`() {
        assertEquals(987654321L, QqCookie.uinOf("wxuin=987654321; qqmusic_key=K"))
    }

    @Test
    fun `uin 优先于 wxuin`() {
        assertEquals(111L, QqCookie.uinOf("uin=111; wxuin=222; qqmusic_key=K"))
    }

    @Test
    fun `非法 uin 返回 null 而不是 0`() {
        assertNull(QqCookie.uinOf(null))
        assertNull(QqCookie.uinOf("qqmusic_key=K"))
        assertNull(QqCookie.uinOf("uin=; qqmusic_key=K"))
        assertNull(QqCookie.uinOf("uin=abc; qqmusic_key=K"))
        assertNull(QqCookie.uinOf("uin=o; qqmusic_key=K"))
        assertNull(QqCookie.uinOf("uin=oabc; qqmusic_key=K")) // o 后面不是数字，不剥
        assertNull(QqCookie.uinOf("uin=0; qqmusic_key=K")) // 0 视为未知
    }

    // ---------- 票据 ----------

    @Test
    fun `主票据缺失时回落备用票据`() {
        assertEquals("ALT1", QqCookie.musicKeyOf("uin=1; qm_keyst=ALT1"))
        assertEquals("MAIN", QqCookie.musicKeyOf("uin=1; qqmusic_key=MAIN; qm_keyst=ALT1"))
    }

    @Test
    fun `票据缺失返回 null`() {
        assertNull(QqCookie.musicKeyOf("uin=1"))
        assertNull(QqCookie.musicKeyOf("uin=1; qqmusic_key="))
        assertNull(QqCookie.musicKeyOf(null))
    }

    // ---------- 登录态 ----------

    @Test
    fun `登录态要求 uin 与票据同时存在`() {
        assertTrue(QqCookie.isLoggedIn(webLogin))
        assertTrue(QqCookie.isLoggedIn(qqLogin))
        // 只有票据没有 uin：无法发 musicu 请求，必须判未登录
        assertFalse(QqCookie.isLoggedIn("qqmusic_key=K"))
        // 只有 uin 没有票据：匿名可搜不可播，同样判未登录
        assertFalse(QqCookie.isLoggedIn("uin=123"))
        assertFalse(QqCookie.isLoggedIn(null))
        assertFalse(QqCookie.isLoggedIn(""))
    }

    @Test
    fun `过期票据仍留在 cookie 里时不会假装已登录`() {
        // qm_keyst 为空串是「服务端明确说没有」的常见形态，不能当成有票据。
        assertFalse(QqCookie.isLoggedIn("uin=123; qqmusic_key=; qm_keyst="))
    }

    // ---------- merge ----------

    @Test
    fun `merge 保留旧字段并用新值覆盖同名`() {
        val merged = QqCookie.merge("uin=123; keep=1; qqmusic_key=OLD", "qqmusic_key=NEW")
        val map = QqCookie.parse(merged)
        assertEquals("NEW", map["qqmusic_key"])
        assertEquals("123", map["uin"]) // 登录只回传增量字段，旧的 uin 不能丢
        assertEquals("1", map["keep"])
    }

    @Test
    fun `merge 对 null 与空串退化成单边拷贝`() {
        assertEquals("a=1", QqCookie.merge(null, "a=1"))
        assertEquals("a=1", QqCookie.merge("a=1", null))
        assertEquals("a=1", QqCookie.merge("a=1", ""))
        assertEquals("", QqCookie.merge(null, null))
    }

    @Test
    fun `merge 结果可再次解析（幂等）`() {
        val once = QqCookie.merge(webLogin, "qqmusic_key=NEW")
        val twice = QqCookie.merge(once, "qqmusic_key=NEW")
        assertEquals(once, twice)
    }

    // ---------- 请求侧过滤 ----------

    @Test
    fun `请求 cookie 只带认识的身份字段`() {
        val req = QqCookie.requestCookie(webLogin)
        val map = QqCookie.parse(req)
        assertEquals("o0123456789", map["uin"])
        assertEquals("ABC123def456", map["qqmusic_key"])
        assertEquals("UU1", map["psrf_qqunionid"])
        // 与本应用无关的第三方 cookie 不该被带到 qm 的请求里
        assertFalse(map.containsKey("ptcz"))
        assertFalse(map.containsKey("RK"))
    }

    @Test
    fun `请求 cookie 对空输入返回空串而不是 null`() {
        assertEquals("", QqCookie.requestCookie(null))
        assertEquals("", QqCookie.requestCookie("ptcz=zzz"))
    }
}
