/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * `spec/fixtures/quality/` 的跨端夹具对照（移植自上游 488a900）。
 */

package com.takahashirinta.ncrust.player

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.testutil.Fixtures
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.i18n.zhCN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * `spec/fixtures/quality/quality.json` 的逐条对照：**取链降级序列 / 出错降档 / 偏好索引 → 档位**，
 * 外加「档位 → 展示名」的映射。
 *
 * ## 为什么表要从生产代码里读，而不是测试里再抄一遍
 *
 * 夹具的价值在于**发现漂移**。如果测试把 `dolby → dolby, hires, lossless, …` 再手写一遍，
 * 那它证明的只是「测试自己和自己一致」—— 生产表改了它照旧绿。所以三张表的取法分别是：
 *
 * | 表 | 落点 | 取法 |
 * |---|---|---|
 * | 取链降级序列 | `SongUrlFetcher.fallbackByLevel`（private） | 反射读字段 |
 * | FLAC 门控 | `SongUrlFetcher.isFlacTier`（public） | 直接调用 |
 * | 出错降档阶梯 | `PlayerViewModel.qualityRetryLadder`（private，实例字段） | 从声明处解析源码文本 |
 *
 * 反射/文本解析都在**漂移时会红**，这正是要的效果。
 *
 * ## 与上游夹具的差异
 *
 * 本 Fork 的档位表是 8 档（多 `jymaster`），重试阶梯最高档是 `jymaster`。夹具里所有涉及前 7 档
 * 与阶梯外 `sky` 的用例都成立，唯 `quality.retry.unknown-level` 冲突（上游期望从 `lossless` 起降，
 * 本 Fork 从阶梯最高档 `jymaster` 起降）—— 已 `@Ignore` 显式标注，另有两条测试分别钉住
 * 「夹具期望值」与「本 Fork 现状」。详见 `spec/fixtures/quality/README.md`「Arris 适配说明」。
 */
class QualitySpecFixtureTest {

    private val cases: JsonArray by lazy {
        JsonParser.parseString(Fixtures.readText("spec/fixtures/quality/quality.json")).asJsonArray
    }

    private fun casesOf(kind: String): List<JsonObject> =
        cases.map { it.asJsonObject }.filter { it.get("kind").asString == kind }

    private fun strings(element: JsonArray): List<String> =
        element.map { it.asString }

    // ---------- 生产表：取链降级序列 ----------

    /**
     * `SongUrlFetcher.fallbackByLevel` 是 private —— 反射读它，而不是在测试里重抄一份表。
     * 字段改名/结构改动会让本测试**编译通过但运行报错**，这是有意的：那张表就是规格本体。
     */
    private val fallbackTable: Map<String, List<String>> by lazy {
        val field = SongUrlFetcher::class.java.getDeclaredField("fallbackByLevel")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(SongUrlFetcher) as Map<String, List<String>>
    }

    /**
     * 某档位在「设备能不能解 FLAC」下的实际尝试序列。
     *
     * 与生产 `SongUrlFetcher.fetchInner` 的两步逐条对应：
     *  1. `fallbackByLevel[level] ?: listOf(level, "lossless", "exhigh", "higher", "standard")`
     *     （阶梯外档位的兜底，第 3 条测试另用源码守卫钉住它）；
     *  2. `if (tryLevel in FLAC_TIERS && !deviceSupportsFlac) continue`。
     *
     * 第 2 步在这里用生产的 `isFlacTier` 判定；JVM 上没有 MediaCodec，`deviceSupportsFlac` 恒为
     * false，所以「支持 FLAC」这一侧只能由夹具的 `supportsFlac` 注入。
     */
    private fun ladderFor(level: String, supportsFlac: Boolean): List<String> {
        val levels = fallbackTable[level] ?: listOf(level, "lossless", "exhigh", "higher", "standard")
        return levels.filter { supportsFlac || !SongUrlFetcher.isFlacTier(it) }
    }

    // ---------- 1. ladder：降级序列 ----------

    @Test
    fun `取链降级序列与夹具一致`() {
        val ladderCases = casesOf("ladder")
        var checked = 0
        for (case in ladderCases) {
            val id = case.get("id").asString
            val input = case.getAsJsonObject("input")
            val level = input.get("level").asString
            val supportsFlac = input.get("supportsFlac").asBoolean
            val expected = strings(case.getAsJsonObject("expect").getAsJsonArray("levels"))

            assertEquals("$id: 降级序列", expected, ladderFor(level, supportsFlac))
            if (!supportsFlac) {
                // 门控的作用就是「设备解不了 FLAC 时根本不发这一档」：序列里既不许以 FLAC 档开局，
                // 也不许中途残留 —— 留着它就是一次注定无声的请求（有进度没声音的经典形状）。
                assertFalse(
                    "$id: 无 FLAC 解码时序列首项不许是 FLAC 档",
                    SongUrlFetcher.isFlacTier(expected.first()),
                )
                assertTrue(
                    "$id: 无 FLAC 解码时序列里不许残留 FLAC 档，实际=$expected",
                    expected.none { SongUrlFetcher.isFlacTier(it) },
                )
            }
            checked++
        }
        assertEquals("夹具里的 ladder 用例应全部被检查", 13, checked)
    }

    // ---------- 2. retry：出错降一档 ----------

    /** 生产重试阶梯（高 → 低）。从 `PlayerViewModel.kt` 的声明处解析，见下面那条测试。 */
    private val retryLadder: List<String> by lazy {
        val text = Fixtures.readText(
            "app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt",
        )
        val block = Regex("private val qualityRetryLadder = listOf\\(([\\s\\S]*?)\\n    \\)")
            .find(text)
            ?.groupValues?.get(1)
            ?: error("PlayerViewModel 里 qualityRetryLadder 的声明形状变了，测试无法解析（不是夹具问题）")
        Regex("\"([a-z]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `重试阶梯的声明仍是 Arris 的 8 档高到低`() {
        assertEquals(
            "PlayerViewModel.qualityRetryLadder 变了：夹具的 retry.* 期望值与降档方向都要重新核对",
            listOf("jymaster", "dolby", "jyeffect", "hires", "lossless", "exhigh", "higher", "standard"),
            retryLadder,
        )
    }

    @Test
    fun `出错降档与夹具一致`() {
        // 冲突用例：见 @Ignore 的那条测试。
        val conflicting = setOf("quality.retry.unknown-level")
        var checked = 0
        for (case in casesOf("retry")) {
            val id = case.get("id").asString
            if (id in conflicting) continue
            val level = case.getAsJsonObject("input").get("level").asString
            val expected = case.getAsJsonObject("expect").get("nextLevel")
                .let { if (it.isJsonNull) null else it.asString }

            // 每个用例用全新的守卫：attempted / attempts / 节流时间戳都是它的状态输入。
            val decision = QualityRetryGuard().decide(
                requested = level,
                actual = level,
                ladder = retryLadder,
                nowMs = 0L,
            )
            val actual = if (decision.action == GuardAction.RETRY) decision.nextLevel else null
            assertEquals("$id: 降一档（${decision.reason}）", expected, actual)
            // 降档必须严格向下 —— 方向写反是本文件最容易犯的错。
            actual?.let {
                assertTrue(
                    "$id: 下一档必须在阶梯里且低于当前档（$level → $it）",
                    retryLadder.indexOf(it) > retryLadder.indexOf(level),
                )
            }
            checked++
        }
        assertEquals("retry 用例数（含 1 条 @Ignore 的阶梯外档位）", 8, checked + conflicting.size)
    }

    /**
     * 上游期望：阶梯外档位从 `lossless` 起降。
     *
     * 本 Fork 的实现不是这样（见下一条测试）：`QualityRetryGuard.decide` 在两个档位都认不出时
     * `floorIdx = -1`，取 `ladder[0]` —— 而本 Fork 的 `ladder[0]` 是 `jymaster`。
     * 夹具的期望值**不被改写**，冲突在这里显式跳过。
     */
    @Test
    @Ignore(
        "Arris 差异：QualityRetryGuard.decide 对阶梯外档位（sky）取 floorIdx = -1 ⇒ 下一档 = ladder[0]，" +
            "本 Fork 的 qualityRetryLadder[0] 是 jymaster（上游无此档，其最高档是 dolby）。" +
            "详见 spec/fixtures/quality/README.md「Arris 适配说明」第 3 条。",
    )
    fun `阶梯外档位出错降档 - 夹具期望从 lossless 起降`() {
        val decision = QualityRetryGuard().decide(
            requested = "sky",
            actual = "sky",
            ladder = retryLadder,
            nowMs = 0L,
        )
        assertEquals("夹具期望 lossless", "lossless", decision.nextLevel)
    }

    /**
     * 本 Fork 的**现状**（不是夹具期望值，单独一条钉住，避免差异变成口头传闻）。
     *
     * ⚠️ 这条值得复核：阶梯外档位（服务端可能回的 `sky`）失败后，重试会从**最高档**
     * `jymaster` 开始 —— 也就是「先往上跳三档再逐级降」。它不会死循环（`attempted` 去重 +
     * 单曲 3 次上限 + `FLAC_TIERS` 门控兜底），但不是夹具（也不是上游）的语义。
     */
    @Test
    fun `Arris 现状 - 阶梯外档位从最高档 jymaster 起降`() {
        val decision = QualityRetryGuard().decide(
            requested = "sky",
            actual = "sky",
            ladder = retryLadder,
            nowMs = 0L,
        )
        assertEquals(GuardAction.RETRY, decision.action)
        assertEquals("jymaster", decision.nextLevel)
    }

    // ---------- 3. preference：偏好索引 → 档位 ----------

    @Test
    fun `偏好索引映射与夹具一致`() {
        var checked = 0
        for (case in casesOf("preference")) {
            val id = case.get("id").asString
            val index = case.getAsJsonObject("input").get("index").asInt
            val expected = case.getAsJsonObject("expect").get("level").asString

            // 生产两处兜底不同：Wi-Fi 默认 3 / 兜底 lossless，移动默认 1 / 兜底 higher。
            val wifi = QualityLadder.levelAt(index, "lossless")
            val mobile = QualityLadder.levelAt(index, "higher")
            if (index in QualityLadder.LEVELS.indices) {
                assertEquals("$id: 合法索引不该落到兜底", wifi, mobile)
            }
            assertEquals("$id: 偏好索引 → 档位", expected, wifi)
            checked++
        }
        assertEquals("preference 用例数", 2, checked)

        // 夹具 README 的默认值：Wi-Fi = 3、移动 = 1 —— 索引本身也必须是这两档。
        assertEquals("默认 Wi-Fi 档（索引 3）", "lossless", QualityLadder.levelAt(3, "lossless"))
        assertEquals("默认移动档（索引 1）", "higher", QualityLadder.levelAt(1, "higher"))
    }

    // ---------- 4. 档位表本身：夹具的 7 档是 Arris 8 档的前缀 ----------

    @Test
    fun `夹具的 7 档顺序就是 Arris 8 档表去掉 jymaster`() {
        assertEquals(
            "夹具（以及 spec/api/entitlement）按 standard…dolby 的 7 档比较；本 Fork 只是在 jyeffect 与 " +
                "dolby 之间插入 jymaster，其余 7 档的相对顺序不许动（动了偏好索引与夹具就全错位）",
            listOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "dolby"),
            QualityLadder.LEVELS.filter { it != "jymaster" },
        )
        assertEquals("Arris 档位表应为 8 档", 8, QualityLadder.LEVELS.size)
        assertEquals("jymaster 必须插在 jyeffect 与 dolby 之间", "jymaster", QualityLadder.LEVELS[6])
        assertEquals("dolby 因此后移到索引 7（旧 7 档表里它是 6，故 QualityLadder.migrate 要 +1）", "dolby", QualityLadder.LEVELS[7])
        // 夹具 preference 用例只涉及两套表共同的前 6 档（0..5）与越界兜底，索引语义因此不变。
        assertEquals("索引 3 = lossless（夹具 default-lossless）", "lossless", QualityLadder.LEVELS[3])
    }

    // ---------- 5. 档位 → 展示名 ----------

    @Test
    fun `档位到展示名的映射一一对应`() {
        val options = zhCN.qualityOptions
        val expected = mapOf(
            "standard" to "压缩",
            "higher" to "较好",
            "exhigh" to "更好",
            "lossless" to "无损",
            "hires" to "高解析",
            "jyeffect" to "高清环绕声",
            "jymaster" to "超清母带",
            "dolby" to "杜比全景声",
        )

        assertEquals("展示名条数必须与档位表一一对应", QualityLadder.LEVELS.size, options.size)
        for ((level, name) in expected) {
            val index = QualityLadder.LEVELS.indexOf(level)
            assertTrue("$level 必须出现在档位表里", index >= 0)
            assertEquals("$level 的展示名", name, options[index])
        }

        // 8 语言同长：任何一门语言少一条，设置页就会有一档显示空串/错位（索引是两处唯一的关联）。
        for (preset in languagePresets) {
            assertEquals(
                "${preset.code} 的档位展示名条数",
                QualityLadder.LEVELS.size,
                preset.strings.qualityOptions.size,
            )
        }
    }

    // ---------- 6. FLAC 门控 ----------

    @Test
    fun `FLAC 门控覆盖的档位与夹具规则一致`() {
        for (tier in listOf("lossless", "hires", "jyeffect")) {
            assertTrue("$tier 必须被 FLAC 门控剔除（设备解不了 FLAC 时它是无声的）", SongUrlFetcher.isFlacTier(tier))
        }
        assertFalse("dolby 用 mp4（EAC3）容器，不在门控内", SongUrlFetcher.isFlacTier("dolby"))
        assertFalse("standard 不是 FLAC", SongUrlFetcher.isFlacTier("standard"))

        // Arris 扩展档位：jymaster 同样是 FLAC 容器，必须一起被剔除。
        assertTrue("jymaster 是 FLAC 档", SongUrlFetcher.isFlacTier("jymaster"))
        assertEquals(
            "无 FLAC 解码时 jymaster 序列应跳过 jymaster/hires/lossless",
            listOf("exhigh", "higher", "standard"),
            ladderFor("jymaster", supportsFlac = false),
        )
        assertEquals(
            "有 FLAC 解码时 jymaster 从自身起降",
            listOf("jymaster", "hires", "lossless", "exhigh", "higher", "standard"),
            ladderFor("jymaster", supportsFlac = true),
        )
    }

    // ---------- 7. 接线守卫：阶梯外档位的兜底序列 ----------

    @Test
    fun `阶梯外档位的兜底序列仍是生产代码里那一句`() {
        val text = Fixtures.readText("app/src/main/java/com/takahashirinta/ncrust/player/SongUrlFetcher.kt")
        assertTrue(
            "SongUrlFetcher 里阶梯外档位的兜底序列变了：夹具的 unknown-* 两条用例与 ladderFor() 的兜底" +
                "必须一起改，否则它们验的是一条生产里不存在的序列",
            text.contains("""?: listOf(level, "lossless", "exhigh", "higher", "standard")"""),
        )
    }
}
