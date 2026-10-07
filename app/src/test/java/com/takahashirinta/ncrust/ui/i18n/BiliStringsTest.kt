/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.8：`BiliStrings`（B 站音质与字幕，17 条）的**内容侧**机械防线。
 */

package com.takahashirinta.ncrust.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.8：**B 站音质与字幕**那一组的逐条断言。
 *
 * ## 分工（与 [WidgetStringsTest] 同形）
 *
 * - 组本身的**规模与 dex 槽位**记在 `StringsConstructorBudgetTest`（那是全仓库的总账）；
 * - 这里只管**内容**：8 种语言都填了没有、同一语言内有没有撞词、
 *   以及三条**事实约束**有没有被翻译掉。
 *
 * ## 为什么事实约束必须机械化
 *
 * 这一组文案直接对应本次用户报的三个问题。它们的错误形态不是「编译失败」，
 * 而是**翻译时把限定条件吃掉**：
 *
 * | 约束 | 被吃掉的后果 |
 * |---|---|
 * | 无损 / Hi-Res **需要大会员**且**只有部分视频**提供 | 没开会员的用户以为「开了这个开关就有无损」，反复报障 |
 * | FLAC 码率是 **2~3 Mbps** | 用户在弱网 / 流量套餐下以为「音质更好」是免费的 |
 * | 「不抓取」是**真的不发请求** | 用户以为只是界面上不显示，实际仍在向 B 站发请求 |
 *
 * 三条各自锚在一组关键词上（8 种语言各一个），漏一条就红。
 */
class BiliStringsTest {

    private val presets = listOf(
        "zh-CN" to zhCN, "zh-TW" to zhTW, "en-US" to en, "ja-JP" to jpJP,
        "ja-MY" to jpMY, "ko-KP" to koNK, "de-DE" to deDE, "ru-RU" to ruRU,
    )

    /** 17 条字段清单（加字段时**必须**同步这里，否则新字段无人监控）。 */
    private val fields: List<Pair<String, (BiliStrings) -> String>> = listOf(
        "biliQualityCapLabel" to { b: BiliStrings -> b.biliQualityCapLabel },
        "biliQualityCapDescription" to { b: BiliStrings -> b.biliQualityCapDescription },
        "biliQualityCapAuto" to { b: BiliStrings -> b.biliQualityCapAuto },
        "biliQualityCapHires" to { b: BiliStrings -> b.biliQualityCapHires },
        "biliQualityCapExhigh" to { b: BiliStrings -> b.biliQualityCapExhigh },
        "biliQualityCapHigher" to { b: BiliStrings -> b.biliQualityCapHigher },
        "biliPreferFlacLabel" to { b: BiliStrings -> b.biliPreferFlacLabel },
        "biliPreferFlacDescription" to { b: BiliStrings -> b.biliPreferFlacDescription },
        "biliSubtitleLangLabel" to { b: BiliStrings -> b.biliSubtitleLangLabel },
        "biliSubtitleLangDescription" to { b: BiliStrings -> b.biliSubtitleLangDescription },
        "biliSubtitleLangAuto" to { b: BiliStrings -> b.biliSubtitleLangAuto },
        "biliSubtitleLangZhHans" to { b: BiliStrings -> b.biliSubtitleLangZhHans },
        "biliSubtitleLangZhHant" to { b: BiliStrings -> b.biliSubtitleLangZhHant },
        "biliSubtitleLangEn" to { b: BiliStrings -> b.biliSubtitleLangEn },
        "biliSubtitleLangJa" to { b: BiliStrings -> b.biliSubtitleLangJa },
        "biliSubtitleLangKo" to { b: BiliStrings -> b.biliSubtitleLangKo },
        "biliSubtitleLangOff" to { b: BiliStrings -> b.biliSubtitleLangOff },
    )

    @Test
    fun `字段清单与组的参数数一致（加字段必须同步这里）`() {
        assertEquals(
            "BiliStrings 的参数数与本测试的字段清单不一致 —— 加了字段请同步清单",
            17,
            BiliStrings::class.java.declaredConstructors
                .filter { !it.isSynthetic }
                .maxOf { it.parameterCount },
        )
        assertEquals(17, fields.size)
    }

    @Test
    fun `八种语言的 17 条文案都非空`() {
        presets.forEach { (code, strings) ->
            fields.forEach { (name, get) ->
                assertTrue("$code 的 $name 为空", get(strings.bili).isNotBlank())
            }
        }
    }

    /**
     * 同一语言内 17 条**互不撞词**。
     *
     * 三个下拉的候选尤其重要：四个音质上限里任意两个写成同一句话，
     * 用户就分不出自己在选哪个；七个字幕语言档位同理 ——
     * 尤其是「不抓取字幕」与其它六档撞词，用户会以为它只是「不显示」。
     */
    @Test
    fun `同一语言内 17 条互不撞词`() {
        presets.forEach { (code, strings) ->
            val values = fields.map { (_, get) -> get(strings.bili) }
            assertEquals("$code 的 17 条文案有重复：$values", 17, values.distinct().size)
        }
    }

    /** 四个音质上限档位必须两两不同（它们是同一个下拉里的四个选项）。 */
    @Test
    fun `四个音质上限档位两两不同`() {
        presets.forEach { (code, strings) ->
            val tiers = listOf(
                strings.bili.biliQualityCapAuto,
                strings.bili.biliQualityCapHires,
                strings.bili.biliQualityCapExhigh,
                strings.bili.biliQualityCapHigher,
            )
            assertEquals("$code 的音质上限档位有重复：$tiers", 4, tiers.distinct().size)
        }
    }

    /** 七个字幕语言档位必须两两不同（同上）。 */
    @Test
    fun `七个字幕语言档位两两不同`() {
        presets.forEach { (code, strings) ->
            val langs = listOf(
                strings.bili.biliSubtitleLangAuto,
                strings.bili.biliSubtitleLangZhHans,
                strings.bili.biliSubtitleLangZhHant,
                strings.bili.biliSubtitleLangEn,
                strings.bili.biliSubtitleLangJa,
                strings.bili.biliSubtitleLangKo,
                strings.bili.biliSubtitleLangOff,
            )
            assertEquals("$code 的字幕语言档位有重复：$langs", 7, langs.distinct().size)
        }
    }

    /** 每种语言的取值不能全都一样 —— 那说明整块文案只改了文件名。 */
    @Test
    fun `跨语言取值不是同一份`() {
        fields.forEach { (name, get) ->
            val values = presets.map { get(it.second.bili) }
            assertTrue(
                "$name 的 8 种语言取值全同（疑似只改了文件名没改内容）：${values.first()}",
                values.distinct().size >= 2,
            )
        }
    }

    // ---------------------------------------------------------------- 三条事实约束

    /**
     * 约束 1：音质上限的说明必须同时写出「需要大会员」与「只有部分视频提供」。
     *
     * 实测依据（`docs/verification/v3.1.0/bili-research` 的同一套方法，2026-10-07 复核）：
     * 同一视频同一 cid，匿名请求 `dash.flac` 恒为 `null`，登录 + 年度大会员才有内容；
     * 而抽样里带 `flac` 的只是少数视频。少写任何一条都会让用户产生错误预期。
     */
    @Test
    fun `音质上限说明写明需要大会员且只有部分视频`() {
        val premium = listOf("大会员", "大會員", "premium", "大会員", "대회원", "премиум")
        // 「只有一部分」在各语言里的说法：zh 部分 / ja 一部 / en some / ko 일부（**不是** 부분，
        // 朝鲜语里「一部分」写作 일부）/ de Teil / ru части.
        val partial = listOf("部分", "一部", "some", "teil", "части", "일부")
        presets.forEach { (code, strings) ->
            val desc = strings.bili.biliQualityCapDescription.lowercase()
            assertTrue(
                "$code 的 biliQualityCapDescription 没写「需要大会员」：${strings.bili.biliQualityCapDescription}",
                premium.any { desc.contains(it.lowercase()) },
            )
            assertTrue(
                "$code 的 biliQualityCapDescription 没写「只有部分视频提供」：${strings.bili.biliQualityCapDescription}",
                partial.any { desc.contains(it.lowercase()) },
            )
        }
    }

    /**
     * 约束 2：优先无损的说明必须给出**码率量级**。
     *
     * 实测锚点：`BV1EC4y1R7ax` 的 `dash.flac.audio` bandwidth = **2247494**，
     * `BV1BZbSzZEGT` = **3154514**，ffprobe 解出 96 kHz / 2029320 bps。
     * 也就是 2~3 Mbps —— 是 320K AAC 的 10 倍量级。不写出来，用户不会知道
     * 这个默认打开的开关在流量上意味着什么。
     */
    @Test
    fun `优先无损的说明写明码率量级`() {
        val bitrate = listOf("mbps", "мбит")
        presets.forEach { (code, strings) ->
            val desc = strings.bili.biliPreferFlacDescription.lowercase()
            assertTrue(
                "$code 的 biliPreferFlacDescription 没写码率量级（Mbps / Мбит）：" +
                    strings.bili.biliPreferFlacDescription,
                bitrate.any { desc.contains(it) },
            )
        }
    }

    /**
     * 约束 3：字幕语言说明必须写明「不抓取」会**停止请求**。
     *
     * 代码侧的事实：选中 `off` 之后 `BiliSourceProvider.fetchLyric` 在取 `cid`
     * **之前**返回 null（见 `BiliSourceProvider` 的 `subtitlePref.fetches` 分支）——
     * 也就是一个字节都不会发给 B 站。写成「不显示字幕」是不诚实的。
     */
    @Test
    fun `字幕语言说明写明不抓取会停止请求`() {
        val words = listOf("请求", "請求", "request", "要求", "求め", "요구", "anfrage", "запрос")
        presets.forEach { (code, strings) ->
            val desc = strings.bili.biliSubtitleLangDescription.lowercase()
            assertTrue(
                "$code 的 biliSubtitleLangDescription 没写「停止请求」：${strings.bili.biliSubtitleLangDescription}",
                words.any { desc.contains(it.lowercase()) },
            )
        }
    }

    /** 「不抓取字幕」必须与其余六档都不同 —— 否则用户分不出它是不是只是「不显示」。 */
    @Test
    fun `不抓取字幕与其它档位不撞词`() {
        presets.forEach { (code, strings) ->
            val off = strings.bili.biliSubtitleLangOff
            listOf(
                strings.bili.biliSubtitleLangAuto,
                strings.bili.biliSubtitleLangZhHans,
                strings.bili.biliSubtitleLangZhHant,
                strings.bili.biliSubtitleLangEn,
                strings.bili.biliSubtitleLangJa,
                strings.bili.biliSubtitleLangKo,
            ).forEach { other ->
                assertNotEquals("$code：不抓取字幕与其它档位撞词了", other, off)
            }
        }
    }

    /** 标题不许与说明写成同一句（设置页里它们是上下两行）。 */
    @Test
    fun `三条标题与各自的说明不撞词`() {
        presets.forEach { (code, strings) ->
            val b = strings.bili
            assertNotEquals("$code：音质上限标题抄了说明", b.biliQualityCapLabel, b.biliQualityCapDescription)
            assertNotEquals("$code：优先无损标题抄了说明", b.biliPreferFlacLabel, b.biliPreferFlacDescription)
            assertNotEquals("$code：字幕语言标题抄了说明", b.biliSubtitleLangLabel, b.biliSubtitleLangDescription)
        }
    }
}
