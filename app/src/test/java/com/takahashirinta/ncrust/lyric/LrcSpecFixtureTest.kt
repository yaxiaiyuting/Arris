/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * `spec/fixtures/lrc/` 的跨端夹具对照（移植自上游 488a900）。
 */

package com.takahashirinta.ncrust.lyric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.player.MediaLyricOptions
import com.takahashirinta.ncrust.player.MediaLyricTrack
import com.takahashirinta.ncrust.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * `spec/fixtures/lrc/lrc.json`：LRC 时间戳解析 + `tlyric` 按时间戳精确合并。
 *
 * ## 一条与本 Fork 冲突的用例（`lrc.parse.first-timestamp-only`）
 *
 * 上游的解析器只取行首**第一个**时间戳，其余原样留在正文里。本 Fork 在 v3.3.0 改成展开行首
 * **每一个**时间戳 —— 起因是真实样本（B 站音频区 au39《成都》）22 行里 13 行是多戳、
 * 共 44 个时间点，旧实现只产出 22 条、**丢掉 21 个时间点**，屏上还留着 `[02:44.32]` 字面量
 * （副歌整段消失）。所以这条夹具按 `@Ignore` 显式跳过，夹具期望值**不被改写**，
 * 本 Fork 的行为另有 `Arris 现状` 一条单独钉住。详见 `spec/fixtures/lrc/README.md`。
 *
 * ## 合并为什么用 `MediaLyricTrack(..., toleranceMs = 0)`
 *
 * 歌词面板的配对是**精确** `timeMs`（`LyricsView.kt`：`translatedLyrics.associateBy { it.timeMs }`
 * → `tMap[line.timeMs]?.text ?: ""`），而那段表达式在 composable 里，JVM 调不到。
 * `MediaLyricTrack` 是生产的纯逻辑类，默认 1500ms 容差属于**系统媒体通知**那条路径的
 * 「最近邻」语义；把容差设成 0 就退化成同一套精确判定（`delta > 0` 即丢弃）。
 * 另有一条源码守卫钉住面板那句配对表达式，防止两条语义悄悄分家。
 */
class LrcSpecFixtureTest {

    private val cases: JsonArray by lazy {
        JsonParser.parseString(Fixtures.readText("spec/fixtures/lrc/lrc.json")).asJsonArray
    }

    private fun casesOf(kind: String): List<JsonObject> =
        cases.map { it.asJsonObject }.filter { it.get("kind").asString == kind }

    /** 与本 Fork 冲突、已显式跳过的用例（见类文档与 README）。 */
    private val conflicting = setOf("lrc.parse.first-timestamp-only")

    private fun lines(element: JsonArray): List<JsonObject> =
        element.map { it.asJsonObject }

    // ---------- 1. parse ----------

    @Test
    fun `解析用例与夹具一致`() {
        var checked = 0
        for (case in casesOf("parse")) {
            val id = case.get("id").asString
            if (id in conflicting) continue
            val expected = lines(case.getAsJsonObject("expect").getAsJsonArray("lines"))
            val actual = LrcParser.parse(case.getAsJsonObject("input").get("lrc").asString)

            assertEquals("$id: 行数", expected.size, actual.size)
            expected.forEachIndexed { index, line ->
                assertEquals("$id: 第 $index 行时间戳", line.get("timeMs").asLong, actual[index].timeMs)
                assertEquals("$id: 第 $index 行文本", line.get("text").asString, actual[index].text)
            }
            // 夹具的解析答案全部按时间升序给出 ⇒ 解析器必须是有序的（多戳行展开后天然乱序）。
            assertEquals(
                "$id: 输出必须按 timeMs 升序",
                actual.map { it.timeMs }.sorted(),
                actual.map { it.timeMs },
            )
            // 文本里不许残留时间戳字面量（否则屏上会出现方括号）。
            assertTrue(
                "$id: 正文不得包含时间戳字面量，实际=${actual.map { it.text }}",
                actual.none { it.text.contains('[') && it.text.contains(']') },
            )
            checked++
        }
        assertEquals("parse 用例数（含 1 条 @Ignore 的多时间戳用例）", 4, checked + conflicting.size)
    }

    /**
     * 上游期望：`[00:01.00][00:02.00]word` 只产出 `(1000, "word")`，第二个时间戳留在正文里。
     *
     * 本 Fork 产出**两条**（1000 与 2000），文本都不含方括号 —— 这正是 v3.3.0 修掉的
     * 「21 个时间点丢失」的形态。夹具期望值不被改写，冲突在此显式跳过。
     */
    @Test
    @Ignore(
        "Arris 差异：LrcParser.parse 展开行首每一个时间戳（v3.3.0 修 B 站《成都》22 行丢 21 个时间点的真实缺陷），" +
            "[00:01.00][00:02.00]word 产出两条 (1000,word)/(2000,word)；上游只取第一个。" +
            "详见 spec/fixtures/lrc/README.md「Arris 适配说明」第 1 条。",
    )
    fun `一行多时间戳 - 夹具期望只取第一个时间戳`() {
        val case = casesOf("parse").first { it.get("id").asString == "lrc.parse.first-timestamp-only" }
        val actual = LrcParser.parse(case.getAsJsonObject("input").get("lrc").asString)
        val expected = lines(case.getAsJsonObject("expect").getAsJsonArray("lines"))
        assertEquals("夹具期望 1 行", 1, expected.size)
        assertEquals("夹具期望 (1000, \"[00:02.00]word\")", expected[0].get("text").asString, actual[0].text)
    }

    /** 本 Fork 的现状：多戳行展开成多条、正文不含时间戳（不是夹具期望值，单独一条记录差异）。 */
    @Test
    fun `Arris 现状 - 一行多时间戳展开成多条且正文不含方括号`() {
        val lines = LrcParser.parse("[00:01.00][00:02.00]word\n")
        assertEquals(
            "每个时间戳各产出一条，文本都是同一个词",
            listOf(1_000L to "word", 2_000L to "word"),
            lines.map { it.timeMs to it.text },
        )
    }

    // ---------- 2. merge ----------

    @Test
    fun `翻译合并用例与夹具一致`() {
        var checked = 0
        for (case in casesOf("merge")) {
            val id = case.get("id").asString
            val input = case.getAsJsonObject("input")
            val original = LrcParser.parse(input.get("lrc").asString)
            val translated = LrcParser.parse(input.get("tlyric").asString)
            val expected = lines(case.getAsJsonObject("expect").getAsJsonArray("lines"))

            assertEquals("$id: 原词行数应与期望一致", expected.size, original.size)
            // toleranceMs = 0 ⇒ 只接受时间戳**完全相等**的译文（面板语义）。
            val track = MediaLyricTrack.from(original, translated, toleranceMs = 0L)
            val options = MediaLyricOptions()
            expected.forEachIndexed { index, line ->
                val timeMs = line.get("timeMs").asLong
                assertEquals("$id: 第 $index 行时间戳", timeMs, original[index].timeMs)
                val frame = track.at(timeMs, enabled = true, options = options)
                assertEquals("$id: 第 $index 行原文", line.get("text").asString, frame.original)
                assertEquals(
                    "$id: 第 $index 行译文（缺译文 = 空串，孤立译文必须被忽略）",
                    line.get("translation").asString,
                    frame.translation.orEmpty(),
                )
            }
            checked++
        }
        assertEquals("merge 用例数", 1, checked)
    }

    /**
     * 无关时间戳的译文不许「就近借用」到别的行上 —— 这是合并用例的核心，单独再钉一次，
     * 免得将来把面板换成「最近邻」时只有一条断言变红而看不出是哪条语义坏了。
     */
    @Test
    fun `没有精确对应时间戳的译文不会贴到相邻行`() {
        val original = LrcParser.parse("[00:01.00]line1\n[00:02.00]line2\n[00:03.00]line3\n")
        val translated = LrcParser.parse("[00:01.00]trans1\n[00:03.00]trans3\n[00:09.00]orphan\n")
        for (tolerance in listOf(0L, 50L)) {
            val track = MediaLyricTrack.from(original, translated, toleranceMs = tolerance)
            val middle = track.at(2_000L, enabled = true, options = MediaLyricOptions())
            assertEquals("容差 ${tolerance}ms：中间那行没有译文", "line2", middle.original)
            assertEquals("容差 ${tolerance}ms：不许借用相邻行的译文", null, middle.translation)
        }
        // 孤立译文（00:09.00）不属于任何一行：最后一行仍拿它自己那条。
        val track = MediaLyricTrack.from(original, translated, toleranceMs = 0L)
        assertEquals("trans3", track.at(3_000L, enabled = true, options = MediaLyricOptions()).translation)
    }

    // ---------- 3. 接线守卫 ----------

    @Test
    fun `歌词面板的译文配对仍是按 timeMs 精确匹配`() {
        val text = Fixtures.readText("app/src/main/java/com/takahashirinta/ncrust/ui/player/LyricsView.kt")
        assertTrue(
            "LyricsView 的翻译配对表达式变了：夹具的 exact-time-alignment 语义（精确 timeMs、缺则空串）" +
                "要跟着重新核对，本文件的 MediaLyricTrack(toleranceMs = 0) 也要同步",
            text.contains("translatedLyrics.associateBy { it.timeMs }") &&
                text.contains("tMap[line.timeMs]?.text ?: \"\""),
        )
    }
}
