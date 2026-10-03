/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**WidgetStrings 组在 8 种语言里都可用**。
 *
 * 为什么单独一条：桌面卡片的文案**只在桌面上可见**，谁也不会为了看一句话把系统语言切
 * 8 遍再去桌面上找。而漏填一格的后果是实打实的：
 *   · 空串 → 卡片上那一行是空白（RemoteViews 没有 Compose 那种「空就不画」的语义）；
 *   · 漏翻 → 用户切成日语后卡片还是中文；
 *   · 播放/暂停撞词 → TalkBack 把「暂停」和「播放」念成同一个词，视障用户无法操作。
 */

package com.takahashirinta.ncrust.ui.widget

import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.i18n.deDE
import com.takahashirinta.ncrust.ui.i18n.en
import com.takahashirinta.ncrust.ui.i18n.jpJP
import com.takahashirinta.ncrust.ui.i18n.jpMY
import com.takahashirinta.ncrust.ui.i18n.koNK
import com.takahashirinta.ncrust.ui.i18n.ruRU
import com.takahashirinta.ncrust.ui.i18n.zhCN
import com.takahashirinta.ncrust.ui.i18n.zhTW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStringsTest {

    private val presets: List<Pair<String, Strings>> = listOf(
        "zh-CN" to zhCN,
        "zh-TW" to zhTW,
        "en-US" to en,
        "ja-JP" to jpJP,
        "ja-MY" to jpMY,
        "ko-KP" to koNK,
        "de-DE" to deDE,
        "ru-RU" to ruRU,
    )

    @Test
    fun `八种语言的八条卡片文案全部非空`() {
        presets.forEach { (code, strings) ->
            val w = strings.widget
            val fields = listOf(
                "widgetEmpty" to w.widgetEmpty,
                "widgetUnknownTitle" to w.widgetUnknownTitle,
                "widgetUnknownArtist" to w.widgetUnknownArtist,
                "widgetPlay" to w.widgetPlay,
                "widgetPause" to w.widgetPause,
                "widgetPrevious" to w.widgetPrevious,
                "widgetNext" to w.widgetNext,
                "widgetOpenApp" to w.widgetOpenApp,
            )
            fields.forEach { (name, value) ->
                assertTrue("$code 的 $name 是空串/空白 —— 卡片上会白留一行", value.isNotBlank())
                assertEquals(
                    "$code 的 $name 两端有空白（RemoteViews 不会 trim）",
                    value.trim(),
                    value,
                )
            }
        }
    }

    @Test
    fun `同一语言内八条互不撞词`() {
        presets.forEach { (code, strings) ->
            val w = strings.widget
            val all = listOf(
                w.widgetEmpty, w.widgetUnknownTitle, w.widgetUnknownArtist,
                w.widgetPlay, w.widgetPause, w.widgetPrevious, w.widgetNext, w.widgetOpenApp,
            )
            assertEquals("$code 的 8 条卡片文案里有重复：$all", 8, all.distinct().size)

            // 单独再钉两组：它们在同一张卡片上相邻出现，撞词 = 用户分不出点了哪个。
            assertTrue("$code 的播放/暂停撞词：${w.widgetPlay}", w.widgetPlay != w.widgetPause)
            assertTrue("$code 的上一首/下一首撞词：${w.widgetPrevious}", w.widgetPrevious != w.widgetNext)
            assertTrue(
                "$code 的「空态」与「未知曲目」撞词：${w.widgetEmpty}",
                w.widgetEmpty != w.widgetUnknownTitle,
            )
            assertTrue(
                "$code 的「未知曲目」与「未知歌手」撞词：${w.widgetUnknownTitle}",
                w.widgetUnknownTitle != w.widgetUnknownArtist,
            )
        }
    }

    @Test
    fun `打开应用的描述里保留品牌名 Ncrust（不翻译品牌）`() {
        presets.forEach { (code, strings) ->
            assertTrue(
                "$code 的 widgetOpenApp 丢了品牌名：${strings.widget.widgetOpenApp}",
                strings.widget.widgetOpenApp.contains("Ncrust"),
            )
        }
    }

    @Test
    fun `真的翻译过——不是八份中文抄来抄去`() {
        // 「八种语言必须两两不同」这条不能写死成 8：繁简同形词是真实存在的
        // （「播放」「暫停」「上一首」在 zh-CN / zh-TW / ja-JP 之间本来就可能同形）。
        // 判据取**至少 5 种取值**：真翻译过一定过，整块抄中文一定不过。
        val emptyValues = presets.map { it.second.widget.widgetEmpty }
        assertTrue(
            "widgetEmpty 只有 ${emptyValues.distinct().size} 种取值，疑似只改了文件名：" +
                "$emptyValues",
            emptyValues.distinct().size >= 5,
        )

        val playValues = presets.map { it.second.widget.widgetPlay }
        assertTrue(
            "widgetPlay 只有 ${playValues.distinct().size} 种取值：$playValues",
            playValues.distinct().size >= 5,
        )

        // 日文与俄文这两份最容易「忘填后回落成中文」：直接点名比对。
        assertTrue("ja-JP 的 widgetPlay 疑似回落成了中文", jpJP.widget.widgetPlay != zhCN.widget.widgetPlay)
        assertTrue("ru-RU 的 widgetPlay 疑似回落成了中文", ruRU.widget.widgetPlay != zhCN.widget.widgetPlay)
        assertTrue("ja-JP 的 widgetEmpty 疑似回落成了中文", jpJP.widget.widgetEmpty != zhCN.widget.widgetEmpty)
    }

    @Test
    fun `语言包的多个入口指向同一份组（转发路径没写歪）`() {
        // languagePresets 与直接引用必须是同一个实例语义 —— 卡片侧走的是
        // `stringsForCode(getSavedLanguageCode(ctx)).widget`，而不是直接拿 zhCN。
        val viaPreset = com.takahashirinta.ncrust.ui.i18n.stringsForCode("ja-JP").widget
        assertEquals(jpJP.widget.widgetPlay, viaPreset.widgetPlay)
        assertEquals(jpJP.widget.widgetEmpty, viaPreset.widgetEmpty)
        // 未知语言码回落 zh-CN（LanguageManager 的既有约定）。
        val fallback = com.takahashirinta.ncrust.ui.i18n.stringsForCode("xx-XX").widget
        assertEquals(zhCN.widget.widgetPlay, fallback.widgetPlay)
    }
}
