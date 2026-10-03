/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.1 · P0：艺人跳转的**路由契约**单测（必须带 source + source 与 id 值域必须匹配）。
 */

package com.takahashirinta.ncrust.ui.navigation

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.ArtistNav
import com.takahashirinta.ncrust.source.ArtistNavigator
import com.takahashirinta.ncrust.source.MusicSource
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.1 · P0：把「艺人跳转必须带音源」从一句注释变成**会变红的契约**。
 *
 * ## 背景（为什么这条值得单独一个文件）
 *
 * v2.4.0 就加了带音源的两段路由 `artist/{source}/{artistId}`，但到 v2.6.0 为止
 * 它**只有两个调用点，且都来自硬编码 ncm 的专辑页** —— 也就是说
 * 「QQ 主源歌手页」在真机上**根本不可达**，而 5 个入口全都走那条
 * 把音源写死成 ncm 的老路由。这个 P0 的形状就是「新路由建好了，老入口没人改」。
 *
 * 所以这里做三件事：
 *
 * 1. [带音源的艺人路由形状固定] —— 路由串本身的形状（回归时会立刻看出来）；
 * 2. [解析器产出的每一个 Direct 都能编码进路由并按原值还原] —— 端到端往返；
 * 3. [源码扫描：所有 NavRoutes_artist 调用点都必须带音源] —— 这条防的是
 *    **将来**有人再加一个入口时顺手用了老的单参数重载。它不是风格检查：
 *    老重载在 composable 里把 source 写成 NETEASE，用错的表现就是本 P0。
 */
class ArtistRouteContractTest {

    // ------------------------------------------------------------ 1. 路由形状

    @Test
    fun `带音源的艺人路由形状固定`() {
        assertEquals(
            "artist/qqmusic/0025NhlN2yWrP4/%E5%91%A8%E6%9D%B0%E4%BC%A6",
            NavRoutes.artist(MusicSource.QQMUSIC, "0025NhlN2yWrP4", "周杰伦"),
        )
        // 不传名字时第四段是空串（仍然**必须存在**：路径参数没有"可省略"这回事）
        assertEquals("artist/qqmusic/0025NhlN2yWrP4/", NavRoutes.artist(MusicSource.QQMUSIC, "0025NhlN2yWrP4"))
        assertEquals("artist/netease/6452/", NavRoutes.artist(MusicSource.NETEASE, "6452"))
        // 路由模板本身也钉住：改成一段就没法带音源了。
        assertEquals("artist/{source}/{artistId}/{artistName}", NavRoutes.ARTIST_SRC)
        assertEquals("artist/{artistId}", NavRoutes.ARTIST)
    }

    /**
     * v2.6.1：名字必须能从路由里**原样还原**，且**不影响身份**。
     *
     * 名字带中文，不编码就会让整条路由在导航库里被切错段；
     * 而名字即使被改坏，`(source, id)` 也必须一个字节都不变 ——
     * 名字是载荷，不是身份（与 `TrackKey` 的 `sourceId`/`mediaId` 同一条纪律）。
     */
    @Test
    fun `艺人名 URL 编码往返保真且不影响身份`() {
        for (name in listOf("周杰伦", "G.E.M.邓紫棋", "A/B 测试", "100%", "")) {
            val route = NavRoutes.artist(MusicSource.QQMUSIC, "0025NhlN2yWrP4", name)
            val parsed = parseArtistSrc(route)
            assertNotNull("名字=$name 的路由解析失败：$route", parsed)
            assertEquals("名字必须原样还原：$name", name, parsed!!.third)
            assertEquals(MusicSource.QQMUSIC, parsed.first)
            assertEquals("0025NhlN2yWrP4", parsed.second)
        }
    }

    /** 路由只吃 `ArtistNav.Direct` 的 (source, id, name) 三元组，不吃别的形状。 */
    @Test
    fun `Direct 可以直接编码进路由`() {
        val direct = ArtistNav.Direct(MusicSource.QQMUSIC, "0025NhlN2yWrP4", "周杰伦")
        assertEquals(NavRoutes.artist(MusicSource.QQMUSIC, "0025NhlN2yWrP4", "周杰伦"), NavRoutes.artist(direct))
    }

    @Test
    fun `带音源的路由与老路由不会互相匹配`() {
        // 导航库按路径段数匹配：老模板吃 1 段、新模板吃 2 段。
        // 这条用例锁住 v2.4.0 的设计前提（两套路由可以并存）。
        assertFalse(
            "artist/4558 是 1 段，不能匹配 artist/{source}/{artistId}",
            matches(NavRoutes.ARTIST_SRC, NavRoutes.artist(4558L)),
        )
        assertTrue(matches(NavRoutes.ARTIST, NavRoutes.artist(4558L)))
        assertTrue(matches(NavRoutes.ARTIST_SRC, NavRoutes.artist(MusicSource.NETEASE, "6452")))
        assertFalse(matches(NavRoutes.ARTIST, NavRoutes.artist(MusicSource.NETEASE, "6452")))
    }

    // ------------------------------------------- 2. 解析器 → 路由：往返一致

    @Test
    fun `解析器产出的每一个 Direct 都能编码进路由并按原值还原`() {
        val songs = listOf(
            // 主路径：QQ 有 singerMID
            song(MusicSource.QQMUSIC, ArtistItem(id = 4558L, name = "周杰伦", mid = "0025NhlN2yWrP4")),
            // 主路径：ncm 十进制
            song(MusicSource.NETEASE, ArtistItem(id = 6452L, name = "周杰伦")),
            // P0 本体：只有数字 QQ id
            song(MusicSource.QQMUSIC, ArtistItem(id = 4558L, name = "周杰伦")),
            // 冷启动恢复：只有名字
            song(MusicSource.QQMUSIC, ArtistItem(name = "周杰伦")),
            // 边界：QQ mid 形如 base62 但很短
            song(MusicSource.QQMUSIC, ArtistItem(id = 1L, name = "某人", mid = "00aBc")),
        )

        for (s in songs) {
            when (val nav = ArtistNavigator.resolve(s)) {
                is ArtistNav.Direct -> {
                    val route = NavRoutes.artist(nav)
                    val parsed = parseArtistSrc(route)
                    assertNotNull("带音源路由必须能被解析回来：$route", parsed)
                    assertEquals("路由里的 source 必须与决策一致", nav.source, parsed!!.first)
                    assertEquals("路由里的 id 必须与决策一致", nav.id, parsed.second)
                    assertEquals("路由里的名字必须与决策一致", nav.name, parsed.third)
                    assertTrue(
                        "路由里的 id 必须满足该音源的值域：$route",
                        ArtistNavigator.idDomainMatches(nav.source, parsed.second),
                    )
                    // 本 P0 的核心断言：QQ 的数字 singerID（4558）不许出现在任何路由里。
                    assertFalse(
                        "QQ 曲目的路由不许带数字 singerID：$route",
                        nav.source == MusicSource.QQMUSIC && parsed.second == "4558",
                    )
                }

                is ArtistNav.Search -> assertTrue(
                    "降级为搜索时必须有关键词：$nav",
                    nav.keyword.isNotBlank(),
                )

                ArtistNav.Unavailable -> Unit
            }
        }
    }

    // ------------------------------------------------- 3. 源码扫描：入口闸门

    @Test
    fun `所有艺人导航调用点都必须带音源`() {
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.substringBefore("//")
                val trimmed = line.trim()
                // 跳过注释行：KDoc 里**必须**能提「老重载叫什么」，
                // 否则这条契约的说明本身就成了违规。只扫真代码。
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.isEmpty()) {
                    return@forEachIndexed
                }
                val idx = line.indexOf("NavRoutes.artist(")
                if (idx < 0) return@forEachIndexed
                val args = line.substring(idx + "NavRoutes.artist(".length)
                val firstArg = args.substringBefore(',').trim().removeSuffix(")")
                if (!carriesArtistSource(firstArg)) {
                    offenders += "${f.path}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "这些调用点用了不带音源的老重载 `NavRoutes.artist(artistId: Long)` —— " +
                "它在 composable 里把 source 写死成 NETEASE，QQ 曲目会被送到同号的 ncm 艺人页" +
                "（真机实测：周杰伦 4558 → 马洪波）。正确写法有两种：\n" +
                "  ① `NavRoutes.artist(MusicSource.NETEASE, id.toString(), name)`（按构造就是某源的入口）；\n" +
                "  ② `NavRoutes.artist(someArtistNavDirect)`（身份判定走 ArtistNavigator）。\n" +
                "违规行：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 判据本身也要能被测 —— 否则正则写松一点，这条守卫就变成永远绿的装饰。
     *
     * 这条用例把**正例与反例**都喂进同一个谓词：将来有人为了"让它过"而放宽判据，
     * 反例那一半会立刻红。
     */
    @Test
    fun `带源判据认得出正例也认得出反例`() {
        // 反例：这些都是"裸 id"，必须被拦。
        val bad = listOf(
            "artistId",
            "id",
            "albumArtistId",
            "artist.id",
            "artist.id.toString()",
            "4558L",
            "song.artists?.firstOrNull()?.id ?: 0L",
        )
        for (a in bad) {
            assertFalse("这个表达式是裸 id，必须被判为违规：$a", carriesArtistSource(a))
        }
        // 正例：显式音源，或一个 ArtistNav.Direct 决策对象。
        val good = listOf(
            "MusicSource.NETEASE, artistId.toString()",
            "MusicSource.NETEASE, id.toString()",
            "target",
            "nav",
            "someArtistNavDirect",
            "ref",
            "source, id",
        )
        for (a in good) {
            assertTrue("这个表达式带了音源/身份对象，不该被判为违规：$a", carriesArtistSource(a))
        }
    }

    @Test
    fun `源码扫描真的扫到了调用点——否则上一条是空转`() {
        // 阳性对照：没有它，「正则写错 ⇒ 一个都没匹配 ⇒ 永远绿」会静默发生。
        // 只数**非注释**行，与上一条用同一个过滤口径。
        val hits = kotlinSources().sumOf { f ->
            f.readLines().count { raw ->
                val t = raw.substringBefore("//").trim()
                !t.startsWith("*") && !t.startsWith("/*") && t.contains("NavRoutes.artist(")
            }
        }
        assertTrue("扫描路径或匹配串写错了，一个调用点都没找到", hits >= 1)
    }

    // ------------------------------------------------------------------ 工具

    /**
     * `artist/{source}/{artistId}/{artistName}` → `(MusicSource, id, name)`；形状不对返回 null。
     *
     * `split('/')` 会丢掉末尾的空段（`"a/b/"` → `["a","b"]`），所以先按**段数**判断，
     * 空名字那一档单独补回来 —— 否则"名字为空"会被误判成"路由形状不对"。
     */
    private fun parseArtistSrc(route: String): Triple<MusicSource, String, String>? {
        val parts = route.split('/')
        if (parts.size < 3 || parts[0] != "artist") return null
        val source = MusicSource.values().firstOrNull { it.key == parts[1] } ?: return null
        val name = parts.getOrNull(3).orEmpty()
        return Triple(source, parts[2], URLDecoder.decode(name, StandardCharsets.UTF_8.toString()))
    }

    /** 极简的导航模板匹配：只支持 `{name}` 占位（本仓库的路由形状够用）。 */
    private fun matches(template: String, route: String): Boolean {
        val t = template.split('/')
        val r = route.split('/')
        if (t.size != r.size) return false
        return t.indices.all { i -> t[i].startsWith("{") || t[i] == r[i] }
    }

    private fun song(source: MusicSource, artist: ArtistItem?) = SongItem(
        id = 0x4000_0000_0001_79ADL,
        name = "晴天",
        artists = artist?.let { listOf(it) },
        album = null,
        duration = null,
        source = if (source == MusicSource.NETEASE) null else source.key,
    )

    /**
     * 第一个实参"带了音源身份"吗。
     *
     * 判据分两类，**不是**风格检查：
     *  ① 显式点名音源（`MusicSource.X` / `*.source` / `musicSource`）——
     *     本仓库连"按构造就是 ncm"的三个入口都要求写出来，因为
     *     「按构造正确」正是那个 P0 里唯一没被写下来的东西；
     *  ② 交出一个 `ArtistNav.Direct`（`NavRoutes.artist(direct)` 重载）——
     *     身份的合法性已经由 `ArtistNavigator` 保证过了。
     *
     * 只要第一个实参**像裸 id**（`artistId` / `id` / `*.id` / 数字字面量 / 表达式
     * 直接以 `.id` 或 `toString()` 结尾），就判违规。
     */
    private fun carriesArtistSource(firstArg: String): Boolean {
        val a = firstArg.trim()
        if (a.isEmpty()) return false
        if (a.startsWith("MusicSource.")) return true
        if (a.contains("musicSource")) return true
        if (Regex("""(^|[^A-Za-z0-9_])source([^A-Za-z0-9_]|$)""").containsMatchIn(a)) return true
        if (a.endsWith(".source")) return true
        // 裸 id 的形状：数字字面量 / 一切以 id 结尾的标识符或成员访问 / toString() 收尾的表达式。
        if (Regex("""^\d+[Ll]?$""").matches(a)) return false
        // camelCase 的 `…Id` / 裸 `id`。**不**用"以 id 结尾"（那会把 `valid` 也误判成裸 id）。
        if (Regex("""^(id|[a-z][A-Za-z0-9_]*Id)$""").matches(a)) return false
        if (a.endsWith(".id")) return false
        if (a.endsWith("toString()")) return false
        if (a.contains(".id ") || a.contains("?.id")) return false
        // 其余形状视为「已经是一个身份对象」（ArtistNav.Direct）。名字里带 artist/direct/ref/target/nav
        // 都算；这条比正则宽松，所以上面那条反例用例是它的护栏。
        return true
    }

    /** 与 PersistenceFieldNameContractTest 同一套路径兜底（IDE / 命令行 / 模块目录三种 cwd）。 */
    private fun kotlinSources(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate.walkTopDown().filter { it.extension == "kt" }.toList()
            if (dir.name == "app" && File(dir, "src/main/java").isDirectory) {
                return File(dir, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 app/src/main/java —— cwd=${System.getProperty("user.dir")}")
    }
}
