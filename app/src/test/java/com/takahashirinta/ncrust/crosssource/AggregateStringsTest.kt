/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0：双源聚合文案组的**预算与非空**回归。
 *
 * 为什么单开一个文件而不是改 `StringsConstructorBudgetTest`：
 * 那条纪律（主构造器 245 参数上限）由它自己钉住，这里只补 v2.4.0 新增的那一组。
 * 两者都不该被对方的改动带红。
 */

package com.takahashirinta.ncrust.crosssource

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

class AggregateStringsTest {

    private val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)

    @Test
    fun `八种语言的聚合文案都非空（空串会在界面上留一块没有字的角标）`() {
        presets.forEach { s ->
            assertTrue(s.source.aggFilterBoth.isNotBlank())
            assertTrue(s.source.aggFilterNetease.isNotBlank())
            assertTrue(s.source.aggFilterQq.isNotBlank())
            assertTrue(s.source.aggUnmatched.isNotBlank())
            assertTrue(s.source.aggProbing.isNotBlank())
            assertTrue(s.source.aggVersionsTitle.isNotBlank())
            assertTrue(s.source.aggSongDetailTitle.isNotBlank())
            assertTrue(s.source.aggSongDetailAction.isNotBlank())
            assertTrue(s.source.aggDefaultPlayable.isNotBlank())
            assertTrue(s.source.aggNoPlayable.isNotBlank())
            assertTrue(s.source.aggConfidenceExact.isNotBlank())
            assertTrue(s.source.aggConfidenceHigh.isNotBlank())
            assertTrue(s.source.aggConfidenceMedium.isNotBlank())
            assertTrue(s.source.aggConfidenceLow.isNotBlank())
            assertTrue(s.source.aggConfidenceNone.isNotBlank())
        }
    }

    @Test
    fun `带参数的文案在传入内容后仍然非空（参数没被漏掉）`() {
        presets.forEach { s ->
            assertTrue(s.source.aggPreferredSource("QQ").isNotBlank())
            assertTrue(s.source.aggConfidence("X").isNotBlank())
            assertTrue(s.source.aggMatchReason("X").isNotBlank())
            assertTrue(s.source.aggOnlyOn("QQ").isNotBlank())
        }
    }

    @Test
    fun `聚合说明文案是原样透传的（不吞掉探测给的理由）`() {
        // `aggAvailabilityNote` 的入参是**代码生成的中文诊断**（「此源（netease）…」），
        // 各语言都原样显示 —— 它来自探测结果，翻译它等于二次加工一个事实。
        presets.forEach { s ->
            assertEquals("探测说明", s.source.aggAvailabilityNote("探测说明"))
        }
    }

    @Test
    fun `SourceStrings 仍在 dex 单方法预算内 嵌套组上限 78`() {
        val clazz = Class.forName("com.takahashirinta.ncrust.ui.i18n.SourceStrings")
        val widest = clazz.declaredConstructors.maxOf { it.parameterCount }
        // v3.1.0 · B：+3 条（`sourceBilibili` / `sourceSummaryBili` / `aggFilterBili`）⇒ 62。
        // 上限抬到 **66** 而不是贴着 62：留 4 个槽位的余量。
        // v3.2.0 · P1：+12 条（B 站扫码登录 11 条 + 筛选空态 1 条）⇒ 74。
        //
        // ⚠️ 为什么**再一次抬上限**而不是拆新组（这个决定必须留痕，否则下一个人只会看到
        //    「上限又被抬了」）：
        //  · dex 的硬上限是单方法 255 个参数寄存器，本组 74 个连零头都用不到；
        //  · 本仓库自己的**组预警线是 80**（`StringsConstructorBudgetTest`），74 仍在线上；
        //  · 拆一个新组要吃 `Strings` 主构造器**一个**槽位（现在 137），而新组的 12 条
        //    与 `source` 组是同一条业务线（账号与音源），拆出去只会让「B 站登录的文案在
        //    哪个组」变成一个需要 grep 才知道的事实。
        //  真正该拆的信号是「本组越过 80」或「主构造器逼近 150」——两者都还没到。
        assertTrue(
            "SourceStrings 构造参数 $widest 超过 78 —— v2.4.0 加了 20 条聚合文案、" +
                "v3.1.0 加了 3 条 B 站文案、v3.2.0 加了 12 条 B 站登录与筛选空态文案。" +
                "再加就该拆新组了（拆组前必须先给 Strings 主构造器腾出槽位）。",
            widest <= 78,
        )
    }

    @Test
    fun `Strings 主构造器没有被本版撑爆 v2_4_0 一个字都没往主构造器加`() {
        val clazz: Class<*> = Strings::class.java
        val primary = clazz.declaredConstructors.filter { !it.isSynthetic }.maxOf { it.parameterCount }
        assertTrue("Strings 主构造器参数 $primary 超过 245", primary <= 245)
    }
}
