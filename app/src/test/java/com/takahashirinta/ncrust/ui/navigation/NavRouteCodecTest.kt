/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · H08：路由参数的**编解码边界**契约（编码一次、解码一次）。
 */

package com.takahashirinta.ncrust.ui.navigation

import com.takahashirinta.ncrust.source.MusicSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * H08 的回归契约：**路由参数只在线性管道的两端各出现一次编解码**。
 *
 * ## 缺陷本体（为什么值得一个专门的测试文件）
 *
 * 导航库在把路径参数放进 `arguments` 之前就已经解码过了
 * （navigation-common 2.8.5 `NavDeepLink.getMatchingArguments` →
 * `android.net.Uri.decode(matcher.group(...))` → `parseArgument`；
 * 本机 AAR 的 `classes.jar` 反汇编可逐条看到那次调用）。
 * 旧实现读侧又调了一次 `java.net.URLDecoder.decode`，于是：
 *
 * | 名字 | 路由里的值 | 导航解码后（正确） | 再解一次（旧实现） |
 * |---|---|---|---|
 * | `100% Love` | `100%25+Love`… | `100% Love` | **抛 IllegalArgumentException** |
 * | `A+B` | `A%2BB` | `A+B` | `A B` |
 *
 * ## 这个文件钉住两件事
 *
 * 1. **写侧**：`NavRoutes` 生成的路径段，用 `Uri.decode` 的语义（只做百分号解码、
 *    **不把 `+` 当空格**）解码**恰好一次**之后，必须逐字节等于原文。
 *    这一条会同时抓住「form 编码把空格写成 `+`」的回归。
 * 2. **读侧**：`NavGraph.kt` 里**不许再出现 `URLDecoder`**（源码扫描）。
 *    导航已经解过的值再解一次，就是上表最右列。
 */
class NavRouteCodecTest {

    /**
     * `android.net.Uri.decode` 的语义：只解 `%XX`（UTF-8），**`+` 原样保留**。
     *
     * 单测里不能调真的 `android.net.Uri`（JVM 上是会抛异常的桩），所以这里实现
     * 同一套语义 —— 它比 `URLDecoder` 少的那一条（`+` → 空格）正是本缺陷的核心，
     * 用 `URLDecoder` 来测等于把要防的东西写进断言里。
     */
    private fun uriDecode(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            require(i + 2 < raw.length) { "非法转义：$raw" }
            val hi = Character.digit(raw[i + 1], 16)
            val lo = Character.digit(raw[i + 2], 16)
            require(hi >= 0 && lo >= 0) { "非法转义：$raw" }
            out.append(((hi shl 4) or lo).toChar())
            i += 3
        }
        // 逐字节还原：%XX 是一个字节，多字节 UTF-8 必须按字节拼回去再解码。
        return String(
            out.toString().toByteArray(Charsets.ISO_8859_1),
            Charsets.UTF_8,
        )
    }

    @Test
    fun `路径段编码在一次 Uri 语义解码后必须还原原文`() {
        val names = listOf(
            "周杰伦",
            "G.E.M.邓紫棋",
            "A/B 测试",          // 空格 + 斜杠：斜杠被编码才不会把路由切错段
            "100%",              // 百分号：旧实现在读侧二次解码时抛异常
            "A+B",               // 加号：旧实现被 form 解码改成空格
            "a b  c",            // 连续/首尾空格必须保真
            "rock&roll?x=1#frag", // 封面 URL 常见的保留字符
            "emoji 🎵 标题",
            "",                  // 空名字：路由里那一段仍然必须在
        )
        for (name in names) {
            val encoded = NavRouteCodec.encodePathSegment(name)
            assertEquals("编码后不许出现 form 语义的 `+`（会被 Uri.decode 当成字面加号）：$name", false, encoded.contains('+'))
            assertEquals("单次解码必须还原：$name", name, uriDecode(encoded))
        }
    }

    @Test
    fun `完整路由里各段都只解码一次就能拿到原值`() {
        val name = "A+B 100% 列表"
        val cover = "https://p1.music.126.net/a%20b/x.jpg?imageView=1&thumbnail=300"
        val route = NavRoutes.playlist(42L, name, cover)
        val parts = route.split('/')
        // playlist/{id}/{name}/{cover}：cover 里的 `/` `?` `&` `=` 都被编码，所以段数稳定。
        assertEquals("路由段数必须固定（否则导航匹配不上）", 4, parts.size)
        assertEquals("playlist", parts[0])
        assertEquals("42", parts[1])
        assertEquals("名字只解码一次", name, uriDecode(parts[2]))
        assertEquals("封面 URL 只解码一次", cover, uriDecode(parts[3]))
    }

    @Test
    fun `艺人路由与 QQ 歌单路由的名字段同样只解码一次`() {
        val artistName = "100% 周杰伦 + Friends"
        val artistRoute = NavRoutes.artist(MusicSource.QQMUSIC, "0025NhlN2yWrP4", artistName)
        assertEquals(
            "artist/{source}/{artistId}/{artistName}",
            artistName,
            uriDecode(artistRoute.split('/')[3]),
        )

        val playlistName = "我喜欢的音乐（100%）"
        val qqRoute = NavRoutes.qqPlaylistDetail("123", "456", 789L, playlistName)
        assertEquals(playlistName, uriDecode(qqRoute.split('/')[4]))
    }

    @Test
    fun `本地歌单三段标识里带冒号与斜杠也不丢`() {
        val route = NavRoutes.localPlaylist("qqmusic", "owner/1", "local:playlist 1")
        val parts = route.split('/')
        assertEquals(4, parts.size)
        assertEquals("qqmusic", uriDecode(parts[1]))
        assertEquals("owner/1", uriDecode(parts[2]))
        assertEquals("local:playlist 1", uriDecode(parts[3]))
    }

    @Test
    fun `NavGraph 不许对导航参数做第二次解码`() {
        val source = appSourceDir().walkTopDown()
            .firstOrNull { it.name == "NavGraph.kt" && it.parentFile.name == "navigation" }
            ?: throw AssertionError("没找到 ui/navigation/NavGraph.kt —— cwd=${System.getProperty("user.dir")}")
        val violations = source.readLines()
            .withIndex()
            .map { (i, raw) -> i + 1 to raw }
            .filter { (_, raw) ->
                val trimmed = raw.trim()
                // 注释行不算违规：KDoc 里**必须**能提「旧实现调过 URLDecoder.decode」，
                // 否则这条契约的说明本身就成了违规（与 ArtistRouteContractTest 同一口径）。
                val isComment = trimmed.startsWith("*") ||
                    trimmed.startsWith("/*") ||
                    trimmed.startsWith("//")
                !isComment && raw.substringBefore("//").contains("URLDecoder.decode")
            }
        assertFalse(
            "导航参数已经被 navigation 解过一次（NavDeepLink → android.net.Uri.decode）。" +
                "这里再解一次会把 `+` 变成空格、把含 `%` 的名字解崩（H08）。违规行：\n" +
                violations.joinToString("\n") { (line, text) -> "$line: ${text.trim()}" },
            violations.isNotEmpty(),
        )
    }

    /** 从当前工作目录往上找 `app/src/main/java`（与 ArtistRouteContractTest 同一套定位）。 */
    private fun appSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            if (dir.name == "app" && File(dir, "src/main/java").isDirectory) return File(dir, "src/main/java")
            dir = dir.parentFile
        }
        throw AssertionError("找不到 app/src/main/java —— cwd=${System.getProperty("user.dir")}")
    }
}
