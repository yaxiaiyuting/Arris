/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站账号存储的**字段名守卫**（铁律 17）与凭据隐私守卫。
 */

package com.takahashirinta.ncrust.bili

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BiliAuthStore` 的**源码扫描守卫** + 纯逻辑断言。
 *
 * ## 为什么这些必须是源码扫描而不是行为测试
 *
 * 它们要守的是「**盘上的字段名**」与「**凭据不进日志**」这两类**结构性**事实：
 * - 字段名错一个字符（`bili_jct` 写成 `biliJct`、`DedeUserID` 写成 `dede_user_id`）
 *   在 JVM 单测里完全没有症状 —— `SharedPreferences` 是键值对，写入与读取用的是同一个常量，
 *   行为测试永远绿。真机上的症状是「升级一次之后登录态丢了」或者
 *   「`bili_jct` 取不到、所有写操作失败」；
 * - 「凭据不进日志」更是没有行为可测：唯一的判据是**源码里那句日志到底打了什么**。
 *
 * 范式与 `BiliSourceProviderTest.开关的 prefs 键与设置注册表逐字一致（铁律 17 显式声明）`
 * 一致：把源码当数据读，逐字比对。
 *
 * ## 它**不能**证明什么（如实写）
 *
 * - 不能证明真机上 `SharedPreferences` 真的写成功了（那是仪器化测试的事，本轮没跑）；
 * - 不能覆盖 `BiliAuthStore.kt` 之外的写入点（例如将来有人在别处直接 `getSharedPreferences`）。
 *   它守住的是**今天写下来的这一份契约**。
 */
class BiliAuthStoreTest {

    @org.junit.After
    fun tearDown() {
        // 进程内镜像是**静态状态**，用例之间必须还原，否则会污染同一 JVM 里的其它用例
        // （例如 BiliAuthRequestTest 依赖「未显式登录时镜像为空」）。
        BiliAuthStore.setMirrorForTest(null)
    }

    private companion object {
        /**
         * 盘上的字段名。**这一张表就是契约**：改一个字符就得同时改这里与实现 —— 那正是目的。
         *
         * 前五个是 cookie 字段（沿用 B 站自己的名字，`DedeUserID` 那种大小写必须逐字一致，
         * 因为将来从别处导入 cookie 时用的是同名 key）；后五个是本应用自己的资料字段，
         * 与 `QqAuthStore` 的 `qq_*` 前缀风格保持一致（这里是 `vip_type` / `vip_status`）。
         */
        val EXPECTED_KEYS = mapOf(
            "KEY_SESSDATA" to "SESSDATA",
            "KEY_BILI_JCT" to "bili_jct",
            "KEY_DEDE_USER_ID" to "DedeUserID",
            "KEY_DEDE_USER_ID_CK_MD5" to "DedeUserID__ckMd5",
            "KEY_SID" to "sid",
            "KEY_UNAME" to "uname",
            "KEY_MID" to "mid",
            "KEY_VIP_TYPE" to "vip_type",
            "KEY_VIP_STATUS" to "vip_status",
            "KEY_PROFILE_AT" to "profile_at",
        )

        /** 本版 P1 只碰这三个文件（+ 测试）。日志守卫扫它们。 */
        val P1_FILES = listOf(
            "bili/BiliAuthStore.kt",
            "bili/BiliAuthApi.kt",
            "bili/BiliQrLogin.kt",
        )
    }

    // ---------------------------------------------------------------- 铁律 17：字段名

    @Test
    fun `盘上字段名与声明逐字一致（铁律 17 显式声明）`() {
        val src = source("bili/BiliAuthStore.kt")
        val declared = Regex("""const val (KEY_[A-Z0-9_]+)\s*=\s*"([^"]*)"""")
            .findAll(src)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "字段名常量与契约表必须逐字一致（新增字段要同时改这里，这是有意的）",
            EXPECTED_KEYS,
            declared,
        )
    }

    @Test
    fun `每个字段名常量都被真的读写用到 —— 没有死键`() {
        val src = source("bili/BiliAuthStore.kt")
        for (name in EXPECTED_KEYS.keys) {
            val uses = Regex("""\b$name\b""").findAll(src).count()
            assertTrue(
                "$name 只出现在声明里（$uses 次）—— 声明了却没人用，说明盘上的键与代码已经漂移",
                uses >= 2,
            )
        }
    }

    @Test
    fun `prefs 的读写只用常量 不许出现字面量 key`() {
        val src = source("bili/BiliAuthStore.kt")
        val literalKeyCall = Regex(
            """\.(putString|putLong|putInt|putBoolean|getString|getLong|getInt|getBoolean|remove)\(\s*"""",
        )
        val hit = literalKeyCall.find(src)
        assertNull(
            "出现了一个用字面量当 key 的 prefs 调用（${hit?.value}）：" +
                "写死的 key 与常量声明会在下一次改名时静默错开",
            hit,
        )
    }

    @Test
    fun `save 写入的字段集合 == credential 读取的集合`() {
        val src = source("bili/BiliAuthStore.kt")
        val written = keysIn(src, "save")
        val read = keysIn(src, "credential")
        assertEquals("写进去却没读出来（或反之）的字段就是登录态丢失的来源", written, read)
        assertTrue(
            "SESSDATA 是核心凭据，必须在两个集合里",
            written.contains("KEY_SESSDATA") && read.contains("KEY_SESSDATA"),
        )
    }

    @Test
    fun `clear 清掉的字段集合 == save 与 saveProfile 写过的字段集合`() {
        val src = source("bili/BiliAuthStore.kt")
        val saved = keysIn(src, "save") + keysIn(src, "saveProfile")
        val cleared = keysIn(src, "clear")
        assertEquals(
            "登出漏清一个字段的症状是：换账号后界面还显示上一个账号的昵称/会员状态",
            saved,
            cleared,
        )
    }

    @Test
    fun `profile 读取的字段集合 == saveProfile 写入的集合`() {
        val src = source("bili/BiliAuthStore.kt")
        assertEquals(
            keysIn(src, "saveProfile") - "KEY_PROFILE_AT",
            keysIn(src, "profile"),
        )
    }

    // ---------------------------------------------------------------- 凭据不进日志

    @Test
    fun `凭据值不出现在任何日志语句里（源码扫描）`() {
        // 允许出现的只有「字段名 + 长度」；任何直接插值凭据变量/常量的日志都是泄露。
        val forbidden = listOf(
            "\$sessdata", "\${sessdata", "\$biliJct", "\${biliJct",
            "\$credential", "\${credential", "\$cred", "\${cred",
            "\$cookieHeader", "\${cookieHeader", "\$cookie", "\${cookie",
            "\$rawCookie", "\${rawCookie", "\$SESSDATA", "\${SESSDATA",
        )
        for (rel in P1_FILES) {
            val lines = source(rel).lines()
            lines.forEachIndexed { i, line ->
                if (!line.contains("Log.") && !line.contains("println(")) return@forEachIndexed
                for (bad in forbidden) {
                    assertFalse(
                        "$rel:${i + 1} 的日志里直接插值了凭据（$bad）：$line",
                        line.contains(bad),
                    )
                }
                // 提到 SESSDATA 的日志只允许打长度。
                if (line.contains("SESSDATA", ignoreCase = true)) {
                    assertTrue(
                        "$rel:${i + 1} 提到 SESSDATA 的日志必须只打长度：$line",
                        line.contains(".length") || line.contains("len="),
                    )
                }
            }
        }
    }

    @Test
    fun `凭据的 toString 只打长度不打值`() {
        val src = source("bili/BiliAuthStore.kt")
        val toStringBody = memberBody(src, "toString")
        assertTrue("BiliCredential 必须覆盖 toString（默认实现会把 SESSDATA 打出来）", toStringBody.contains("override"))
        for (field in listOf("sessdata", "biliJct", "dedeUserIdCkMd5", "sid")) {
            assertTrue(
                "toString 里的 $field 必须以 .length 的形式出现，不能直接插值",
                toStringBody.contains("$field.length"),
            )
        }
        assertFalse("toString 里不许出现 cookieHeader()", toStringBody.contains("cookieHeader()"))
    }

    // ---------------------------------------------------------------- 纯逻辑

    @Test
    fun `未登录时 requestCookieHeader 为 null —— 匿名请求一个 Cookie 都不带`() {
        BiliAuthStore.setMirrorForTest(null)
        assertNull(BiliAuthStore.requestCookieHeader())
        assertFalse(BiliAuthStore.isLoggedInInMemory())
        // 空凭据（有字段但 SESSDATA 为空）同样算未登录。
        BiliAuthStore.setMirrorForTest(BiliCredential(biliJct = "J", dedeUserId = "1"))
        assertNull(BiliAuthStore.requestCookieHeader())
        assertFalse(BiliAuthStore.isLoggedInInMemory())
    }

    @Test
    fun `登录后 requestCookieHeader 是 SESSDATA 打头的形状`() {
        BiliAuthStore.setMirrorForTest(
            BiliCredential(sessdata = "S", biliJct = "J", dedeUserId = "7"),
        )
        assertEquals("SESSDATA=S; bili_jct=J; DedeUserID=7", BiliAuthStore.requestCookieHeader())
        assertTrue(BiliAuthStore.isLoggedInInMemory())
        BiliAuthStore.setMirrorForTest(null)
    }

    @Test
    fun `mergeCookieHeaders —— 两者都空时返回 null（这是匿名零 Cookie 的保证点）`() {
        assertNull(BiliAuthStore.mergeCookieHeaders(null, null))
        assertNull(BiliAuthStore.mergeCookieHeaders("", "   "))
        assertNull(BiliAuthStore.mergeCookieHeaders(null, ""))
        assertEquals("buvid3=x", BiliAuthStore.mergeCookieHeaders(null, "buvid3=x"))
        assertEquals("SESSDATA=S", BiliAuthStore.mergeCookieHeaders("SESSDATA=S", null))
        assertEquals(
            "登录凭据在前、指纹在后，中间恰好一个分号+空格",
            "SESSDATA=S; bili_jct=J; buvid3=x",
            BiliAuthStore.mergeCookieHeaders("SESSDATA=S; bili_jct=J", "buvid3=x"),
        )
    }

    @Test
    fun `needsProfileRefresh 的 TTL 是 10 分钟 且未登录恒为 false`() {
        assertEquals("与 QqAuthStore.PROFILE_TTL_MS 同口径", 10 * 60 * 1000L, BiliAuthStore.PROFILE_TTL_MS)
        // 未登录：没资料可拉，一次请求都不该发（否则进设置页就是一个注定 -101 的往返）。
        assertFalse(BiliAuthStore.shouldRefreshProfile(loggedIn = false, profileAtMs = 0L, nowMs = Long.MAX_VALUE / 2))
        // 从未拉过（at = 0）且已登录、时间也已过 TTL ⇒ 需要拉。
        assertTrue(BiliAuthStore.shouldRefreshProfile(true, 0L, BiliAuthStore.PROFILE_TTL_MS + 1))
        // 边界：恰好等于 TTL 算新鲜（与 QqAuthStore 的 `now - at > TTL` 逐字一致）。
        val at = 1_000_000L
        assertFalse(BiliAuthStore.shouldRefreshProfile(true, at, at + BiliAuthStore.PROFILE_TTL_MS))
        assertTrue(BiliAuthStore.shouldRefreshProfile(true, at, at + BiliAuthStore.PROFILE_TTL_MS + 1))
    }

    @Test
    fun `isVip 只看有没有会员 不写死档位`() {
        assertFalse(BiliProfile().isVip())
        assertFalse("uname 有值但会员字段为 0 ⇒ 非会员", BiliProfile(uname = "u", mid = 1).isVip())
        assertTrue("vipStatus=1（大会员）", BiliProfile(vipStatus = 1).isVip())
        assertTrue("vipType=2（年度）", BiliProfile(vipType = 2).isVip())
        assertTrue(
            "服务端将来新增档位（比如 vipType=3）也必须算会员 —— 写死 0/1/2 的实现在这里变红",
            BiliProfile(vipType = 3).isVip(),
        )
    }

    @Test
    fun `存储只碰自己的 prefs 文件 —— 另外三家一个都不动`() {
        val src = source("bili/BiliAuthStore.kt")
        assertTrue("必须是独立文件", src.contains("""const val PREFS = "ncrust_bili_prefs""""))
        for (other in listOf("\"ncrust_prefs\"", "\"ncrust_qq_prefs\"", "\"ncrust_settings\"")) {
            assertFalse(
                "BiliAuthStore 不许引用 $other：共用存储会让登出一家把另一家的登录态一起清掉",
                src.contains(other),
            )
        }
    }

    // ---------------------------------------------------------------- 源码扫描小工具

    /** 读源码文本（**找不到文件直接失败**：静默跳过 = 假防线）。 */
    private fun source(rel: String): String {
        val f = File(appSourceRoot(), rel)
        assertTrue("源码文件不见了：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 取 `rel` 里名为 [name] 的成员（`fun` / `override fun`）的函数体文本。 */
    private fun memberBody(src: String, name: String): String {
        val lines = src.lines()
        val head = Regex(
            """^ {4}(?:(?:private|internal|public|protected|override)\s+)*(?:suspend\s+)?fun\s+""" +
                Regex.escape(name) + """\s*\(""",
        )
        val start = lines.indexOfFirst { head.containsMatchIn(it) }
        assertTrue("找不到成员 $name —— 守卫必须跟着它改名，不许静默跳过", start >= 0)
        val boundary = Regex(
            """^ {4}(?:/\*\*|@|(?:private|internal|public|protected|override)\s|fun\s|const\s|val\s|var\s|data\s|object\s|enum\s|class\s)""",
        )
        var end = start + 1
        while (end < lines.size && !boundary.containsMatchIn(lines[end])) end++
        return lines.subList(start, end).joinToString("\n")
    }

    /** [memberBody] 里出现过的 `KEY_*` 常量集合。 */
    private fun keysIn(src: String, name: String): Set<String> =
        Regex("""\bKEY_[A-Z0-9_]+\b""").findAll(memberBody(src, name)).map { it.value }.toSet()

    /** 从 `user.dir` 逐级向上找 `app/src/main/java/com/takahashirinta/ncrust`；找不到直接失败（静默跳过 = 假防线）。 */
    private fun appSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/takahashirinta/ncrust")
            if (candidate.isDirectory) return candidate
            if (dir.name == "app") {
                val local = File(dir, "src/main/java/com/takahashirinta/ncrust")
                if (local.isDirectory) return local
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 app/src/main/java/com/takahashirinta/ncrust（user.dir=${System.getProperty("user.dir")}）" +
                "—— 源码扫描防线失效，必须修好而不是跳过。",
        )
    }
}
