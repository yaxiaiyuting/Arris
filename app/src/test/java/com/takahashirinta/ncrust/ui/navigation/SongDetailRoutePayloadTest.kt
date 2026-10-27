/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.1 · H13：单曲详情路由的**载荷契约**（QQ 单曲页播放不可用的回归）。
 */

package com.takahashirinta.ncrust.ui.navigation

import com.takahashirinta.ncrust.source.MusicSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H13 的回归契约：**QQ 单曲页需要的载荷必须由路由带过来**。
 *
 * ## 缺陷本体（为什么值得一个专门的文件）
 *
 * 单曲页的锚点原先只用 `(source, id)` 构造（`SongDetailScreen.routeAnchorSong`），
 * `sourceId`/`mediaId` 恒为 null。对 QQ 这不是"少个显示字段"：
 *
 * | 消费者 | 缺 songmid 时的行为 |
 * |---|---|
 * | `QqMusicSourceProvider.songDetail`（:135） | `song.takeIf { it.sourceId != null }` ⇒ null |
 * | `QqApi.fetchPlayUrl`（:218） | `if (songMid == null) return null` ⇒ 取链硬失败 |
 * | `CrossSourceMatcher` | 关键词 = 曲名，曲名为空 ⇒ 搜索零结果（静默） |
 *
 * 结果就是「QQ 单曲详情页里的播放不可用」。修法：`NavRoutes.song(...)` 增加四个**可选**载荷
 * （songmid / media_mid / 曲名 / 艺人），页面据此构造可取链、可召回的锚点。
 *
 * ## 这份测试钉住四件事
 *
 * 1. **不带载荷时路由逐字节等于旧形状**（老调用点/手写深链的行为不变）；
 * 2. **载荷只解码一次**就能还原原文（H08 同一条纪律：编码一次、解码一次）；
 * 3. **空载荷不进查询串**（ncm 侧 sourceId/mediaId 恒为 null，不能长出空段）；
 * 4. **目的地为四个查询参数声明了 `defaultValue`** —— 这是「老路由仍然能打开页面」的
 *    真正前提：navigation-common 2.8.5 的 `NavDeepLink.getMatchingArguments` 之后会调
 *    `missingRequiredArguments`，**只有带默认值（或可空）的参数才被跳过**。少一个默认值，
 *    不带查询串的老路由会被判成「缺必填参数」⇒ 匹配失败 ⇒ 页面打不开。
 */
class SongDetailRoutePayloadTest {

    /** `android.net.Uri.decode` 的语义：只解 `%XX`，**不把 `+` 当空格**（JVM 里不能调真 Uri）。 */
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
        return String(out.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }

    /** 把 `a=1&b=2` 拆成「键 → 一次解码后的值」。 */
    private fun queryPairs(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            key to uriDecode(pair.substringAfter('=', ""))
        }

    @Test
    fun `不带载荷时路由与 v3_8_0 逐字节相同`() {
        // 老调用点（MainActivity 之外的剪贴板识别、深链、测试）产出的就是这一串。
        assertEquals("song/qqmusic/123", NavRoutes.song(MusicSource.QQMUSIC, "123"))
        assertEquals("song/netease/1959528822", NavRoutes.song(MusicSource.NETEASE, "1959528822"))
        // 老路由里不该出现查询串 —— 一旦出现，说明「空载荷」被当成了真实载荷。
        assertFalse(NavRoutes.song(MusicSource.QQMUSIC, "123").contains('?'))
    }

    @Test
    fun `四个载荷只解码一次就能还原原文`() {
        val name = "100% A+B / 晴天 & 雨天"
        val artist = "周杰伦 + Friends"
        val sourceId = "0039MnYb0qxYhV"
        val mediaId = "003Qui1q2lZho"
        val route = NavRoutes.song(
            source = MusicSource.QQMUSIC,
            id = "123",
            sourceId = sourceId,
            mediaId = mediaId,
            name = name,
            artist = artist,
        )

        // 路径段数必须是 3：查询串里的 `/` 被编码，不会把路由切错段。
        val path = route.substringBefore('?')
        assertEquals("路径段数必须固定", 3, path.split('/').size)
        assertEquals("song", path.split('/')[0])
        assertEquals("qqmusic", path.split('/')[1])
        assertEquals("123", path.split('/')[2])

        val pairs = queryPairs(route.substringAfter('?'))
        assertEquals(sourceId, pairs["sourceId"])
        assertEquals(mediaId, pairs["mediaId"])
        assertEquals("曲名只解码一次", name, pairs["name"])
        assertEquals("艺名只解码一次", artist, pairs["artist"])
        // form 语义的 `+`（会被 Uri.decode 当字面加号）不许出现。
        assertFalse("编码不许出现 form 语义的 `+`", route.substringAfter('?').contains('+'))
    }

    @Test
    fun `空载荷不进查询串`() {
        // ncm 侧 sourceId/mediaId 恒为 null、name 可能为空：不能长出 `?sourceId=&name=`
        // 这种空段（空段的语义是"有载荷但为空"，与"没有载荷"混在一起会让页面难以判断）。
        assertEquals(
            "song/netease/42",
            NavRoutes.song(MusicSource.NETEASE, "42", sourceId = null, mediaId = null, name = "", artist = ""),
        )
        assertEquals(
            "song/netease/42",
            NavRoutes.song(MusicSource.NETEASE, "42", sourceId = "  ", mediaId = "", name = " ", artist = ""),
        )
        // 只带一部分载荷时，只有非空的那几项出现。
        val onlyName = NavRoutes.song(MusicSource.QQMUSIC, "7", name = "晴天")
        assertEquals("song/qqmusic/7?name=%E6%99%B4%E5%A4%A9", onlyName)
    }

    @Test
    fun `目的地模板的路径部分与无载荷路由同形`() {
        // 载荷走查询段的**全部意义**就在这里：路径模板不变，老路由才继续匹配同一个目的地。
        assertEquals("song/{source}/{songId}", NavRoutes.SONG_SRC.substringBefore('?'))
        val declared = NavRoutes.SONG_SRC.substringAfter('?').split('&').map { it.substringBefore('=') }
        assertEquals(listOf("sourceId", "mediaId", "name", "artist"), declared)
    }

    @Test
    fun `四个查询参数都声明了默认值`() {
        val navGraph = appSourceDir().walkTopDown()
            .firstOrNull { it.name == "NavGraph.kt" && it.parentFile.name == "navigation" }
            ?: throw AssertionError("没找到 ui/navigation/NavGraph.kt —— cwd=${System.getProperty("user.dir")}")
        val lines = navGraph.readLines()

        for (arg in listOf("sourceId", "mediaId", "name", "artist")) {
            val declaration = lines.firstOrNull { it.contains("navArgument(\"$arg\")") }
                ?: throw AssertionError("NavGraph.kt 没有声明路由参数 `$arg`")
            assertTrue(
                "`$arg` 必须带 defaultValue：否则不带查询串的老路由会被 " +
                    "missingRequiredArguments 判成缺必填参数，页面直接打不开。声明是：$declaration",
                declaration.contains("defaultValue"),
            )
        }
    }

    /** 从当前工作目录往上找 `app/src/main/java`（与 NavRouteCodecTest 同一套定位）。 */
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
