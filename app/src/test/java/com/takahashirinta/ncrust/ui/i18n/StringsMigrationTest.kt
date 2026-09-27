/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.3 · P0：**`Strings` 拆分的迁移单测**。
 *
 * ## 这个测试在证明什么
 *
 * v2.5.3 把 120 条文案从 `Strings` 的主构造器（当时 245 = 255 个 dex 槽**用满**）
 * 搬进了 `SettingsStrings` / `AboutStrings` / `PlayerUiStrings` 三个分组，
 * 并给每一条都在类体里补了同名**转发属性**，因此老调用点 `strings.xxx` 一行都不用改。
 *
 * 「搬家」这种改动的危险之处在于**它不会编译失败**：漏搬一条、把两条写串、
 * 某个语言文件少搬一行 —— Kotlin 的具名实参 + 有默认值的参数会让这些全部静默通过。
 * 120 条 × 8 种语言 ≈ 960 个字符串，靠人眼 diff 不可靠。
 *
 * 所以做法是：拿**拆分前**（v2.5.2）用同一套反射逻辑导出的黄金快照
 * `strings-snapshot-v2.5.2.json` 逐值比对。快照由一次性工具生成
 * （源码留档在 `docs/verification/v2.5.3/tools/StringsSnapshotDumpTest.kt`），
 * 记录的是**运行时的真实取值**，不是源码文本 —— 30+ 条 lambda 文案是用固定哨兵实参
 * 实际调用一次、记下输出串的。
 *
 * ## 覆盖的四件事（对应任务书 §3.1）
 *
 * | 要求 | 用例 |
 * |---|---|
 * | 旧 key 访问路径 | `v2_5_2 的全部路径逐值不变（搬家 ≠ 改文案）` |
 * | 新 key 访问路径 | `旧 key 与新分组路径一一对应且取值相同` + `三个新分组都被真正填充` |
 * | 默认值回落 | `漏填的语言回落构造器默认值（aboutDesignSystemLabel）` |
 * | 多语言一致性 | `8 种语言的路径集合完全一致` + `跨语言该不同的文案确实不同` + `跨语言全同的路径集合与 v2_5_2 一致` |
 *
 * ## 为什么把取值压成字符串再比
 *
 * 快照来自 JSON（Gson 把数字读成 `Double`），当前值来自反射（`Int`/`Long`）。
 * 直接把 `Map` 判等会被 `4242` vs `4242.0` 这种表示差异绊住，而那不是文案差异。
 * 统一先过 [canon] 压成一个确定性的字符串，比较的就是「用户看到的那个串」。
 */

package com.takahashirinta.ncrust.ui.i18n

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsMigrationTest {

    private val gson = Gson()

    // ---------------------------------------------------------------- 载入

    private fun resource(name: String): String =
        (javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("测试资源缺失：app/src/test/resources/$name"))
            .bufferedReader(Charsets.UTF_8).readText()

    /**
     * 黄金快照（v2.5.2）→ `locale → path → 规范串`。
     *
     * ⚠️ 刻意**按 raw 解析再手工转换**，而不是用 `TypeToken<Map<String, Map<String,
     * Map<String, Any?>>>>`：快照里有一个 `__meta` 段，它的值形状是
     * `{String: String}`（版本号、说明），与 locale 段的 `{path: {t,v}}` 不同。
     * 用类型化 Token 会在 `__meta` 上直接抛 `JsonSyntaxException: Expected BEGIN_OBJECT
     * but was STRING` —— 那是解析器的形状假设，不是数据有问题。
     */
    private fun goldenValues(): Map<String, Map<String, String>> {
        val raw = gson.fromJson(resource("i18n/strings-snapshot-v2.5.2.json"), Map::class.java)
            as Map<*, *>
        val out = LinkedHashMap<String, Map<String, String>>()
        for ((locale, paths) in raw) {
            if (locale == "__meta") continue
            val leaves = paths as? Map<*, *> ?: continue
            out[locale.toString()] = leaves.entries.associate { (path, leaf) ->
                path.toString() to canon(leaf as? Map<*, *>)
            }
        }
        return out
    }

    /** 当前（v2.5.3）取值 → `locale → path → 规范串`。 */
    private fun currentValues(): Map<String, Map<String, String>> =
        languagePresets.associate { preset ->
            preset.code to StringsSnapshot.capture(preset.strings)
                .mapValues { (_, leaf) -> canon(leaf as? Map<*, *>) }
        }

    /** 旧 key → 新分组路径 的迁移映射表（由 `split-strings.py` 的 GROUPS 表生成）。 */
    private fun migrationMap(): List<Pair<String, String>> =
        resource("i18n/strings-migration-map-v2.5.3.tsv").lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t").let { c -> c[0] to c[1] } }

    /** JSON 数字一律走 Double，比较前折回整数。 */
    private fun num(v: Any?): String =
        if (v is Double && v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** 把一条取值描述压成确定性字符串：`s=…` / `fn/2(args)=…` / `l=[…]`。 */
    private fun canon(leaf: Map<*, *>?): String {
        if (leaf == null) return "<missing>"
        val t = leaf["t"]?.toString() ?: "?"
        val v = leaf["v"]
        val body = when (v) {
            null -> "null"
            is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            is List<*> -> v.joinToString(",", "[", "]") { it?.toString() ?: "null" }
            else -> v.toString()
        }
        return when (t) {
            // arity 走同一个数字规范化：JSON 里是 2.0，反射里是 2 —— 那不是文案差异。
            "fn" -> "fn/${num(leaf["arity"])}(${leaf["args"]})=$body"
            "group" -> "group($body)"
            else -> "$t=$body"
        }
    }

    /** 按反射名读一条属性（支持一层 `组.字段`）。 */
    private fun readPath(s: Strings, path: String): Any? {
        var target: Any = s
        for (p in path.split(".")) {
            val m = target.javaClass.methods.firstOrNull {
                it.parameterCount == 0 &&
                    it.name == "get" + p.replaceFirstChar { c -> c.uppercaseChar() }
            } ?: return null
            target = m.invoke(target) ?: return null
        }
        return target
    }

    // ---------------------------------------------------------------- 旧路径

    /**
     * **本版最重要的一条**：v2.5.2 快照里的每一个路径，在 v2.5.3 里都必须仍然存在、
     * 且取值**逐值相同**。
     *
     * 这一条同时覆盖三件事：① 转发属性没写漏（旧路径还在）；② 搬家没改文案；
     * ③ 8 种语言都没搬错（逐语言比对，任一种语言出错就红）。
     */
    @Test
    fun `v2_5_2 的全部路径逐值不变（搬家 ≠ 改文案）`() {
        val golden = goldenValues()
        val now = currentValues()
        assertEquals("语言集合变了", golden.keys, now.keys)

        val problems = mutableListOf<String>()
        var compared = 0
        for ((code, paths) in golden) {
            val cur = now.getValue(code)
            for ((path, expected) in paths) {
                val actual = cur[path]
                if (actual == null) {
                    problems += "$code: 旧路径 `$path` 消失了（转发属性漏写？）"
                    continue
                }
                compared++
                if (expected != actual) {
                    problems += "$code: `$path`\n      旧 = $expected\n      新 = $actual"
                }
            }
        }
        assertTrue(
            "比对了 $compared 个路径，发现 ${problems.size} 处问题：\n" +
                problems.take(15).joinToString("\n"),
            problems.isEmpty(),
        )
        assertTrue("比对的路径数太少（$compared）—— 快照可能没真正载入", compared > 3000)
    }

    // ---------------------------------------------------------------- 新路径

    /**
     * 旧 key 与新的分组路径**一一对应且取值相同** —— 这就是「旧 key → 新路径可追溯」
     * 的机器证明：两条访问路径指向同一个值，且映射表里每一条都能解析。
     */
    @Test
    fun `旧 key 与新分组路径一一对应且取值相同`() {
        val mapping = migrationMap()
        assertEquals("迁移映射表的条目数应当等于搬走的文案数", 120, mapping.size)
        assertEquals("映射表的旧 key 有重复", mapping.size, mapping.map { it.first }.toSet().size)
        assertEquals("映射表的新路径有重复", mapping.size, mapping.map { it.second }.toSet().size)

        val groups = mapping.map { it.second.substringBefore(".") }.toSet()
        assertEquals("应当有 3 个新分组", setOf("settings", "about", "playerUi"), groups)

        languagePresets.forEach { preset ->
            val s = preset.strings
            mapping.forEach { (oldKey, newPath) ->
                val flat = readPath(s, oldKey)
                val grouped = readPath(s, newPath)
                assertNotNull("${preset.code}: 旧路径 `$oldKey` 读不到", flat)
                assertNotNull("${preset.code}: 新路径 `$newPath` 读不到", grouped)
                assertEquals(
                    "${preset.code}: `$oldKey` 与 `$newPath` 取值不一致（转发属性写错了？）",
                    flat, grouped,
                )
            }
        }
    }

    /** 三个新组的**组参数确实挂上了**，且条目数与分组表一致。 */
    @Test
    fun `三个新分组都被真正填充`() {
        // v2.8.0：settings 64 → 78（二级菜单的 7 组 × 标题/副标题）。
        // about / playerUi 本版没动 —— 这三个数只随各自的文案变动。
        val expectedSizes = mapOf("settings" to 78, "about" to 25, "playerUi" to 31)
        languagePresets.forEach { preset ->
            val captured = StringsSnapshot.capture(preset.strings)
            expectedSizes.forEach { (g, n) ->
                assertEquals(
                    "${preset.code}: $g 组字段数不对",
                    n,
                    captured.keys.count { it.startsWith("$g.") },
                )
                val getter = preset.strings.javaClass.methods.firstOrNull {
                    it.parameterCount == 0 &&
                        it.name == "get" + g.replaceFirstChar { c -> c.uppercaseChar() }
                }
                assertNotNull("${preset.code}: 组 `$g` 的 getter 不存在", getter)
                assertNotNull("${preset.code}: 组 `$g` 是 null", getter!!.invoke(preset.strings))
            }
        }
    }

    // ---------------------------------------------------------------- 默认值回落

    /**
     * `aboutDesignSystemLabel` 是**唯一**带默认值的文案（`= "Design System"`）。
     *
     * 拆分前：5 个语言文件（en / ja-MY / ko-KP / de-DE / ru-RU）**没有**写这一条，
     * 靠构造器默认值回落。拆分后这条默认值必须跟着声明一起搬进 `AboutStrings` ——
     * 忘了搬，那 5 种语言会在编译期直接失败；搬成别的值，这条用例会红。
     *
     * 语义上「字段缺失（没翻译）」与「显式给了英文」必须继续可区分：
     * 前者的正确表现是回落到默认串，而不是回落到 zhCN 或空串。
     */
    @Test
    fun `漏填的语言回落构造器默认值（aboutDesignSystemLabel）`() {
        val specified = mapOf(
            "zh-CN" to "组件库",
            "zh-TW" to "元件庫",
            "ja-JP" to "UIコンポーネント",
        )
        val fallback = "Design System"

        assertEquals("语言数量不是 8", 8, languagePresets.size)
        languagePresets.forEach { preset ->
            val actual = preset.strings.aboutDesignSystemLabel
            assertEquals(
                "${preset.code} 的 aboutDesignSystemLabel",
                specified[preset.code] ?: fallback,
                actual,
            )
            // 组内路径与扁平路径必须看到同一个值（转发属性没写歪）。
            assertEquals(actual, preset.strings.about.aboutDesignSystemLabel)
        }
        assertEquals("显式给出该文案的语言应当正好是 3 种", 3, specified.size)
    }

    /**
     * 默认值本身要留在**分组**的构造器上（不是只留在 `Strings` 上）。
     *
     * 判据用「合成构造器是否存在」而不是「形参名」：本模块没开 `-java-parameters`，
     * JVM 反射拿到的是 `arg0/arg1/...`，按名字断言会永远为假（第一稿就是这么写的）。
     * 合成构造器 = `N + mask + DefaultConstructorMarker`，**只有存在带默认值的参数时才会生成**，
     * 所以它是「默认值确实在分组上」的充分证据；取值本身由
     * `漏填的语言回落构造器默认值` 那条逐语言钉住。
     */
    @Test
    fun `默认值声明跟着文案一起搬进了分组`() {
        val declared = AboutStrings::class.java.declaredConstructors
        assertTrue(
            "AboutStrings 应当存在合成构造器 —— 那是「至少有一个参数带默认值」的证据。" +
                "没有它说明 aboutDesignSystemLabel 的默认值没跟着搬进分组。",
            declared.any { it.isSynthetic },
        )
        val primary = declared.filter { !it.isSynthetic }.maxByOrNull { it.parameterCount }!!
        assertEquals("AboutStrings 主构造参数数", 25, primary.parameterCount)
        val synthetic = declared.first { it.isSynthetic }
        assertEquals(
            "合成构造器参数数应当 = 25 + ceil(25/32)=1 个 mask + 1 个 DefaultConstructorMarker",
            27, synthetic.parameterCount,
        )
        // 反过来：另外两个新组**没有**默认值，因此不该有合成构造器。
        listOf(SettingsStrings::class.java, PlayerUiStrings::class.java).forEach { c ->
            assertTrue(
                "${c.simpleName} 不该有合成构造器 —— 它没有任何带默认值的参数",
                c.declaredConstructors.none { it.isSynthetic },
            )
        }
    }

    // ---------------------------------------------------------------- 多语言

    /**
     * 8 种语言的**路径集合完全一致**。
     *
     * 这条挡的是「某个语言文件漏搬一条」—— 那会让该语言的路径集合与别人不同，
     * 而由于具名实参 + 默认值的存在，编译器**不会**报错。
     */
    @Test
    fun `8 种语言的路径集合完全一致`() {
        val now = currentValues()
        assertEquals("语言数量不是 8", 8, now.size)
        val reference = now.values.first().keys
        now.forEach { (code, paths) ->
            // ★ 必须取 `paths.keys`：`Set - Map` 在 Kotlin 里会解析成
            //   `Iterable<T>.minus(element: T)`（单元素重载），**不是集合差** ——
            //   它会把整个 Map 当成一个元素去删，结果是「一个都没删掉」，
            //   于是这条断言会以 617 == 617 的假象误报。本版第一稿就踩了这个坑。
            val missing = reference - paths.keys
            val extra = paths.keys - reference
            assertEquals(
                "$code 的路径集合与第一个语言不同：缺 ${missing.size} 条 ${missing.take(8)}，" +
                    "多 ${extra.size} 条 ${extra.take(8)}",
                reference, paths.keys,
            )
        }
        assertTrue(
            "路径总数太少：${reference.size}（v2.5.2 快照里是 494）",
            reference.size > 490,
        )
    }

    /** 跨语言**该不同的地方确实不同**（防「所有语言都回落到同一条串」）。 */
    @Test
    fun `跨语言该不同的文案确实不同（不是全体回落）`() {
        val now = currentValues()
        val codes = now.keys.sorted()
        val probes = listOf(
            "tabHome", "cancel", "playButton", "settings.qualitySectionTitle",
            "about.aboutTitle", "playerUi.queueTitle", "loadFailed", "playAllButton",
            "searchCategoryTracks", "settings.themeSectionTitle", "playerUi.emptyQueue",
            "about.aboutLicense",
        )
        probes.forEach { path ->
            val values = codes.map { now.getValue(it)[path] }
            assertEquals("$path 有语言缺值", 8, values.size)
            assertTrue(
                "$path 的 8 种语言取值只有 ${values.toSet().size} 种不同 —— 疑似有人把整组文案写成了同一份",
                values.toSet().size >= 6,
            )
        }
    }

    /**
     * 8 种语言里取值**全同**的路径集合：搬家只能让它**变大**，且变大必须可解释。
     *
     * 拆分后必然新增两类「全同」路径（它们不是回归）：
     *  1. **组标记**（`settings` / `about` / `playerUi`）—— 它们的「取值」是组类名，
     *     8 种语言当然一样；
     *  2. **搬了家但仍然同值的文案**（如 `settings.uidLabel`）—— 它在 v2.5.2 就是以
     *     扁平名 `uidLabel` 全同的；新路径只是它的另一个视图。
     *
     * 反过来，「消失」一条都不允许 —— 那意味着某条文案在某种语言里被改成了
     * 与别人相同的串（典型的「漏翻译后回落到了 zhCN」），是本版最该抓的回归。
     */
    @Test
    fun `跨语言全同的路径集合只增不减 且新增可解释`() {
        fun identical(map: Map<String, Map<String, String>>): Set<String> {
            val codes = map.keys.sorted()
            return map.values.first().keys
                .filter { path -> codes.map { map.getValue(it)[path] }.distinct().size == 1 }
                .toSet()
        }
        val golden = goldenValues()
        val now = currentValues()
        val sameGolden = identical(golden)
        val sameNow = identical(now)

        val disappeared = sameGolden - sameNow
        assertTrue(
            "这些路径在 v2.5.2 是跨语言全同的，现在不同了 —— 有语言被改动了：\n" +
                disappeared.take(20).map { "  $it → ${now.values.first()[it]}" }.joinToString("\n"),
            disappeared.isEmpty(),
        )

        val reference = now.values.first()
        val unexplained = (sameNow - sameGolden).filterNot { path ->
            val value = reference.getValue(path)
            // ① 组标记；② 扁平名在 v2.5.2 就已经全同（搬家不改变这件事）。
            value.startsWith("group(") || path.substringAfterLast('.') in sameGolden
        }
        assertTrue(
            "以下路径新变成「跨语言全同」，但既不是组标记、也不对应一条本来就全同的旧文案 —— " +
                "疑似有语言的文案被回落到同一个串：\n" +
                unexplained.take(20).map { "  $it = ${reference[it]}" }.joinToString("\n"),
            unexplained.isEmpty(),
        )
    }
}
