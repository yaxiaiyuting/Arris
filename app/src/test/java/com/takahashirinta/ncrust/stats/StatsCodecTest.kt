/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 用户需求第 10 条：播放统计落盘契约的守卫单测。
 */

package com.takahashirinta.ncrust.stats

import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatsCodec] 的守卫。
 *
 * ## 为什么这一组必须有（AGENTS.md v2.5.4 规则 1）
 *
 * 「持久化结构的字段名是对外契约，不许交给 R8 决定」。本仓库已经为此付过四次学费，
 * 症状全都是：同一个 APK 内读写自洽 ⇒ **不崩**，只在下一次混淆映射变化时
 * **静默清空用户数据**。所以这里的断言是**逐字段的、针对注解字面值的** ——
 * 谁把 `@SerializedName` 删了或改名了，用例先红，而不是用户的数据先消失。
 *
 * `proguard-rules.pro` 的全局规则
 * `-keepclassmembers,allowobfuscation class * { @SerializedName <fields>; }`
 * 保证注解里的字符串常量进 dex；但**它保不住「有没有写这个注解」** —— 那正是本文件在测的。
 */
class StatsCodecTest {

    /**
     * 把 DTO 的落盘名读出来：要求每个字段都带 `@SerializedName`，且**等于字段本名**。
     *
     * 注解先在**字段**上找（Gson 的 `ReflectiveTypeAdapterFactory` 读的就是字段），
     * 找不到再退到 getter 上找 —— Kotlin 对 Java 注解的落点规则取决于注解自己的
     * `@Target`，这里不赌它，两条路都认。真正的地面真相由下面「编码结果里出现的是字段本名」
     * 那条端到端用例给出：它不依赖任何反射语义。
     */
    private fun serializedNames(cls: Class<*>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in cls.declaredFields) {
            // v3.3.0：跳过**编译器管理的字段**（以 `$` 开头；实测是 Compose 编译器插入的
            // `$stable` 稳定性标记）。
            //
            // 它不是 synthetic（`isSynthetic == false`），所以光靠合成位拦不住；
            // 而它是编译器产物，**不可能也不该**加 `@SerializedName` ——
            // 加了会与编译器的插入逻辑打架。这两条用例最初就是被它判红的。
            //
            // 口径与 `contract/PersistenceFieldNameContractTest` **刻意保持一致**：
            // 同一类误报在两处出现过，只修一处就会在另一处继续红。
            // 业务字段不可能以 `$` 开头（Kotlin 语法不允许），所以这个过滤不漏真实字段。
            if (field.name.startsWith("$")) continue
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            val value = field.getAnnotation(SerializedName::class.java)?.value
                ?: getterOf(cls, field.name)?.getAnnotation(SerializedName::class.java)?.value
            assertNotNull(
                "字段 ${cls.simpleName}.${field.name} 缺少 @SerializedName（落盘 key 会由 R8 决定）",
                value,
            )
            assertEquals(
                "字段 ${cls.simpleName}.${field.name} 的 @SerializedName 必须等于字段本名",
                field.name,
                value,
            )
            out[field.name] = value!!
        }
        return out
    }

    private fun getterOf(cls: Class<*>, fieldName: String): java.lang.reflect.Method? {
        val getter = "get" + fieldName.replaceFirstChar { it.uppercaseChar() }
        return runCatching { cls.getDeclaredMethod(getter) }.getOrNull()
    }

    // ------------------------------------------------------- 写路径的契约 ----

    @Test
    fun `根对象的每个字段都有显式且同名的落盘 key`() {
        val names = serializedNames(StatsCodec.SnapshotDto::class.java)
        assertEquals(StatsCodec.snapshotKeys().toSet(), names.keys)
    }

    @Test
    fun `单曲条目的每个字段都有显式且同名的落盘 key`() {
        val names = serializedNames(StatsCodec.SongDto::class.java)
        assertEquals(StatsCodec.songKeys().toSet(), names.keys)
    }

    /** 真·端到端一条：编码出来的 JSON **里出现的就是字段本名**，不是 `a`/`b`。 */
    @Test
    fun `编码结果里出现的是字段本名而不是单字母`() {
        val json = StatsCodec.encode(sampleSnapshot())
        val root = JsonParser.parseString(json).asJsonObject
        for (key in StatsCodec.snapshotKeys()) {
            assertTrue("根对象缺少落盘 key $key（实际 keys=${root.keySet()}）", root.has(key))
        }
        val song = root.getAsJsonArray("songs")[0].asJsonObject
        for (key in StatsCodec.songKeys()) {
            assertTrue("单曲条目缺少落盘 key $key（实际 keys=${song.keySet()}）", song.has(key))
        }
        // 反向：不许出现 R8 那种单字母 key
        for (letter in 'a'..'z') {
            assertFalse("落盘里出现了单字母 key '$letter'", root.has(letter.toString()))
        }
    }

    @Test
    fun `往返编解码不丢任何维度`() {
        val original = sampleSnapshot()
        val decoded = StatsCodec.decode(StatsCodec.encode(original))
        assertEquals(original.totalMs, decoded.totalMs)
        assertEquals(original.totalPlays, decoded.totalPlays)
        assertEquals(original.firstDayKey, decoded.firstDayKey)
        assertEquals(original.byDay, decoded.byDay)
        assertEquals(original.bySource, decoded.bySource)
        assertEquals(original.playsBySource, decoded.playsBySource)
        assertEquals(original.songs, decoded.songs)
    }

    // ------------------------------------------------------- 坏数据的处置 ----

    @Test
    fun `空输入返回空快照`() {
        assertEquals(StatsSnapshot.EMPTY, StatsCodec.decode(null))
        assertEquals(StatsSnapshot.EMPTY, StatsCodec.decode(""))
    }

    @Test
    fun `顶层不是对象时不抛异常只返回空快照`() {
        assertEquals(StatsSnapshot.EMPTY, StatsCodec.decode("[1,2,3]"))
        assertEquals(StatsSnapshot.EMPTY, StatsCodec.decode("42"))
        assertEquals(StatsSnapshot.EMPTY, StatsCodec.decode("{ 这不是 json "))
    }

    /**
     * **坏条目逐条丢弃，不许整段丢光**（AGENTS.md v2.5.4 规则 1 第四条）。
     *
     * 旧写法 `catch { emptyList() }` 的后果是「一个字节坏了，用户全部统计消失」。
     */
    @Test
    fun `单曲表里的坏条目逐条丢弃而其余保留`() {
        val json = """
            {
              "totalMs": 5000,
              "songs": [
                {"key":"netease:1","name":"好条目","artist":"a","ms":5000,"plays":1,"lastAtMs":9,"durationMs":200000},
                {"name":"没有主键","ms":1000},
                {"key":"netease:2","ms":0,"plays":0},
                "这不是对象",
                {"key":"netease:3","name":"另一条好条目","ms":2000,"plays":0,"lastAtMs":8,"durationMs":100}
              ]
            }
        """.trimIndent()
        val decoded = StatsCodec.decode(json)
        assertEquals(5_000L, decoded.totalMs)
        assertEquals(setOf("netease:1", "netease:3"), decoded.songs.keys)
        assertEquals("好条目", decoded.songs.getValue("netease:1").name)
        assertEquals(100L, decoded.songs.getValue("netease:3").durationMs)
    }

    @Test
    fun `维度表里的非数字值被跳过而不是整表丢弃`() {
        val json = """
            {"byDay":{"2026-02-14":1234,"2026-02-15":"坏值","2026-02-16":null,"2026-02-17":-9},
             "bySource":{"netease":1234,"qqmusic":"oops"}}
        """.trimIndent()
        val decoded = StatsCodec.decode(json)
        assertEquals(mapOf("2026-02-14" to 1_234L), decoded.byDay)
        assertEquals(mapOf("netease" to 1_234L), decoded.bySource)
    }

    /** `songs` 被写成了对象（不是数组）时：返回空表，**不抛 ClassCastException**。 */
    @Test
    fun `单曲表类型不对时返回空表而不是崩溃`() {
        val decoded = StatsCodec.decode("""{"totalMs":10,"songs":{"key":"netease:1"}}""")
        assertEquals(10L, decoded.totalMs)
        assertTrue(decoded.songs.isEmpty())
    }

    /** 老数据（字段缺失）走默认值：缺失 ≠ 0 语义，但绝不能是 NPE / 崩溃。 */
    @Test
    fun `字段缺失的老数据按默认值读出`() {
        val decoded = StatsCodec.decode("""{"songs":[{"key":"netease:1","ms":10}]}""")
        assertEquals(0L, decoded.totalMs)
        assertEquals(0L, decoded.totalPlays)
        assertEquals(null, decoded.firstDayKey)
        assertTrue(decoded.byDay.isEmpty())
        val song = decoded.songs.getValue("netease:1")
        assertEquals("", song.name)
        assertEquals("", song.artist)
        assertEquals(0L, song.plays)
        assertEquals(0L, song.lastAtMs)
        assertEquals(0L, song.durationMs)
    }

    /** 未知字段（将来加的新维度、或降级安装后老版本读新数据）一律忽略，不许当成坏数据。 */
    @Test
    fun `未知字段被忽略而不影响已认识的字段`() {
        val decoded = StatsCodec.decode("""{"totalMs":7,"futureDimension":{"x":1},"songs":[]}""")
        assertEquals(7L, decoded.totalMs)
        assertTrue(decoded.songs.isEmpty())
    }

    private fun sampleSnapshot() = StatsSnapshot(
        totalMs = 7_200_000L,
        totalPlays = 12L,
        firstDayKey = "2026-02-01",
        byDay = mapOf("2026-02-01" to 3_600_000L, "2026-02-14" to 3_600_000L),
        bySource = mapOf("netease" to 5_000_000L, "qqmusic" to 2_200_000L),
        playsBySource = mapOf("netease" to 9L, "qqmusic" to 3L),
        songs = mapOf(
            "netease:1" to SongStat("netease:1", "曲一", "艺人一", 5_000_000L, 9L, 1_700_000_000_000L, 240_000L),
            "qqmusic:2" to SongStat("qqmusic:2", "曲二", "艺人二", 2_200_000L, 3L, 1_700_000_100_000L, 180_000L),
        ),
    )
}
