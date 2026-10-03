package com.takahashirinta.ncrust.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NeteaseCookie] 的行为契约。
 *
 * ## 这些用例对应的是用户反馈第 6 条的真实机制
 *
 * 「需要很多次退出登录再登录才能播放 VIP 资源或者音质」是一个**概率性**症状，
 * 而概率性症状最难在单测里复现 —— 除非把「掷骰子」的那部分挤出去。
 * 掷骰子的是 `onPageFinished` 的触发时机（在 `MainActivity` 里改成了轮询，无法单测），
 * 而**合并会不会丢键、半截会话算不算登录**是纯逻辑，可以逐条钉死。
 * 本文件钉的就是后半部分。
 *
 * 样本取自真机实测的 cookie 形状（`.scratch/pcl-prefs/shared_prefs/ncrust_prefs.xml`：
 * 20 个键，含 `MUSIC_U` / `__csrf` / `__remember_me` / `NMTID`，**无** `os`/`appver`）。
 */
class NeteaseCookieTest {

    private val realShaped =
        "MUSIC_U=00ABCDEF1234567890==; __csrf=abcdef1234567890abcdef1234567890; " +
            "__remember_me=true; NMTID=00Oxyz123; _ntes_nuid=1a2b3c4d5e"

    // ---------------------------------------------------------------- 解析 ----

    @Test
    fun `解析出全部键`() {
        val map = NeteaseCookie.parse(realShaped)

        assertEquals(5, map.size)
        assertEquals("00ABCDEF1234567890==", map["MUSIC_U"])
        assertEquals("abcdef1234567890abcdef1234567890", map["__csrf"])
        assertEquals("true", map["__remember_me"])
    }

    @Test
    fun `值里的等号不被当成新的键值对`() {
        // MUSIC_U 的真实值带 base64 的 `=` 填充。按第一个 `=` 切分才不会把它切碎。
        val map = NeteaseCookie.parse("MUSIC_U=abc=def==; other=1")

        assertEquals(2, map.size)
        assertEquals("abc=def==", map["MUSIC_U"])
        assertEquals("1", map["other"])
    }

    @Test
    fun `换行与分号两种分隔符都认`() {
        // WebView 的 getCookie 给一行；逐条 Set-Cookie 给的是多行。
        val multiLine = "MUSIC_U=aaa\n__csrf=bbb\r\nNMTID=ccc"
        val map = NeteaseCookie.parse(multiLine)

        assertEquals(3, map.size)
        assertEquals("aaa", map["MUSIC_U"])
        assertEquals("bbb", map["__csrf"])
        assertEquals("ccc", map["NMTID"])
    }

    @Test
    fun `空输入与垃圾输入不抛异常`() {
        assertTrue(NeteaseCookie.parse(null).isEmpty())
        assertTrue(NeteaseCookie.parse("").isEmpty())
        assertTrue(NeteaseCookie.parse("   ").isEmpty())
        assertTrue(NeteaseCookie.parse(";;;").isEmpty())
        // 没有 `=` 的片段不是键值对，丢掉
        assertTrue(NeteaseCookie.parse("garbage; MUSIC_U").isEmpty())
    }

    @Test
    fun `键两侧的空白被 trim`() {
        val map = NeteaseCookie.parse("  MUSIC_U = v  ;  __csrf = c  ")

        assertEquals("v", map["MUSIC_U"])
        assertEquals("c", map["__csrf"])
    }

    // ---------------------------------------------------------------- 合并 ----

    @Test
    fun `合并保留两侧的键 同名取新值`() {
        val old = "MUSIC_U=old; NMTID=keep; _ntes_nuid=nuid"
        val new = "MUSIC_U=new; __csrf=token"

        val merged = NeteaseCookie.parse(NeteaseCookie.merge(old, new))

        assertEquals("同名取新值", "new", merged["MUSIC_U"])
        assertEquals("旧侧独有的键必须保留", "keep", merged["NMTID"])
        assertEquals("旧侧独有的键必须保留", "nuid", merged["_ntes_nuid"])
        assertEquals("新侧的键要进来", "token", merged["__csrf"])
        assertEquals(4, merged.size)
    }

    @Test
    fun `合并是反复重登不丢键的关键`() {
        // 登录会分几批写 cookie：导航响应一批、登录接口一批。
        // 覆盖写（旧实现）会丢掉先到的那批 —— 这是「反复重登」的机制之一。
        var acc: String? = null
        acc = NeteaseCookie.merge(acc, "NMTID=00O1; _ntes_nuid=1a2b")
        acc = NeteaseCookie.merge(acc, "MUSIC_U=ticket==")
        acc = NeteaseCookie.merge(acc, "__csrf=csrfvalue")

        val map = NeteaseCookie.parse(acc)
        assertEquals("三次分批写入后四个键都必须在", 4, map.size)
        assertTrue(NeteaseCookie.isUsable(acc))
    }

    @Test
    fun `合并时空值覆盖空值语义正确`() {
        // 「键存在但值为空」是**有效输入**（服务端会用它覆盖默认身份），必须保留；
        // 「键不存在」则不该被引入。
        val merged = NeteaseCookie.parse(NeteaseCookie.merge("a=1; b=2", "a=; c=3"))

        assertEquals("空值要覆盖掉旧值（这是有效输入）", "", merged["a"])
        assertEquals("2", merged["b"])
        assertEquals("3", merged["c"])
        assertEquals(3, merged.size)
    }

    @Test
    fun `合并 null 与空串`() {
        assertEquals("MUSIC_U=x", NeteaseCookie.merge(null, "MUSIC_U=x"))
        assertEquals("MUSIC_U=x", NeteaseCookie.merge("MUSIC_U=x", null))
        assertEquals("", NeteaseCookie.merge(null, null))
    }

    // ---------------------------------------------------------------- 判据 ----

    @Test
    fun `可用登录态要求 MUSIC_U 与 __csrf 同时存在`() {
        assertTrue(NeteaseCookie.isUsable("MUSIC_U=t; __csrf=c"))
        assertFalse("只有票据不算可用 —— 写操作会恒 403", NeteaseCookie.isUsable("MUSIC_U=t"))
        assertFalse("只有 csrf 更不算", NeteaseCookie.isUsable("__csrf=c"))
    }

    @Test
    fun `空值的必需键等同于缺失`() {
        // 服务端拿到空 csrf 照样 403，所以 `__csrf=` 不能算「有」。
        assertFalse(NeteaseCookie.isUsable("MUSIC_U=t; __csrf="))
        assertFalse(NeteaseCookie.isUsable("MUSIC_U=; __csrf=c"))
    }

    @Test
    fun `真机形状的 cookie 判为可用`() {
        assertTrue(NeteaseCookie.isUsable(realShaped))
    }

    @Test
    fun `可选键不影响可用性`() {
        // 扫码登录路径实测只回增量，可能没有 MUSIC_A / NMTID / __remember_me。
        // 把它们算进判据会把**能用的**会话判成未登录（与本节要修的缺陷反方向）。
        assertTrue(NeteaseCookie.isUsable("MUSIC_U=t; __csrf=c"))
        assertTrue(NeteaseCookie.isUsable("MUSIC_U=t; __csrf=c; MUSIC_A=a; NMTID=n"))
    }

    @Test
    fun `hasSession 只判票据 比 isUsable 宽松`() {
        assertTrue(NeteaseCookie.hasSession("MUSIC_U=t"))
        assertFalse("只有票据时 hasSession 为真但 isUsable 为假", NeteaseCookie.isUsable("MUSIC_U=t"))
        assertFalse(NeteaseCookie.hasSession("__csrf=c"))
    }

    // ---------------------------------------------------------------- 覆盖决策 ----

    @Test
    fun `不许用半截会话覆盖完整会话`() {
        // 轮询会读到中间态。若无条件写回，轮询会把完整会话**覆盖成半截的**
        // —— 那正好是本版要修的缺陷的镜像。
        val good = "MUSIC_U=t; __csrf=c"
        val half = "MUSIC_U=t2"

        assertFalse("半截不得覆盖完整", NeteaseCookie.shouldReplace(good, half))
    }

    @Test
    fun `可用会话总是值得写入`() {
        val good = "MUSIC_U=t; __csrf=c"
        val fresher = "MUSIC_U=t2; __csrf=c2"

        assertTrue("新的是更新鲜的，值得替换", NeteaseCookie.shouldReplace(good, fresher))
        assertTrue(NeteaseCookie.shouldReplace(null, good))
        assertTrue(NeteaseCookie.shouldReplace(half(), good))
    }

    @Test
    fun `两者都不可用时仍记下票据 让状态可见`() {
        // 至少留下 MUSIC_U，让「已登录但写操作会 403」这个状态能被看到，
        // 而不是完全空白（完全空白会让用户以为根本没登录、反复重登）。
        assertTrue(NeteaseCookie.shouldReplace(null, "MUSIC_U=t"))
        assertTrue(NeteaseCookie.shouldReplace("NMTID=n", "MUSIC_U=t"))
    }

    @Test
    fun `空的新 cookie 永远不值得写`() {
        assertFalse(NeteaseCookie.shouldReplace(null, null))
        assertFalse(NeteaseCookie.shouldReplace(null, ""))
        assertFalse(NeteaseCookie.shouldReplace("MUSIC_U=t; __csrf=c", null))
    }

    private fun half() = "MUSIC_U=t"

    // ---------------------------------------------------------------- 诊断 ----

    @Test
    fun `describe 只给键名与长度 不泄漏值`() {
        // 凭据值能直接冒充用户，所以日志里绝不能出现。
        val desc = NeteaseCookie.describe(realShaped)

        assertTrue("应含键名", desc.contains("MUSIC_U"))
        assertTrue("应含长度", desc.contains("len="))
        assertFalse("绝不能含票据的值", desc.contains("00ABCDEF1234567890"))
        assertFalse("绝不能含 csrf 的值", desc.contains("abcdef1234567890abcdef1234567890"))
    }

    @Test
    fun `describe 对空输入给可读结果`() {
        assertEquals("(空)", NeteaseCookie.describe(null))
        assertEquals("(空)", NeteaseCookie.describe(""))
    }
}
