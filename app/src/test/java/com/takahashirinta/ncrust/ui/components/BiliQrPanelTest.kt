/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：B站扫码登录「打开即失效」的回归防线。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.bili.BiliQrLogin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 二维码区**该画什么**的纯函数（[biliQrPanel]）逐格断言。
 *
 * ## 这一组用例挡的是什么（真机 P0）
 *
 * v3.2.0 的二维码区把「没有位图」一律显示成「二维码已失效，请刷新」，
 * 而正常情况下"还没有位图"发生在**申请中 / 渲染中**（`IDLE` / `LOADING` / `WAITING`）——
 * 用户打开浮层第一眼看到的就是"已失效"，即使二维码 0.3 秒后就会画出来。
 *
 * 所以这里钉住三条**互不重叠**的判据：
 *  1. 只有 `EXPIRED` 才允许显示「已失效」；
 *  2. 申请/渲染失败显示**明确错误**（`FAILED` 档），不显示「已失效」；
 *  3. 有位图就画码 —— 即使状态还在 `WAITING` / `SCANNED` / `CONFIRMED`。
 *
 * 判据与文案的对应关系写在 `BiliQrLoginDialog` 的 `when` 里
 * （`EXPIRED → biliLoginExpired`、`FAILED → sourceQrLoadFailed`、`LOADING → loading`）。
 */
class BiliQrPanelTest {

    private val states = BiliQrLogin.State.values().toList()

    @Test
    fun `只有 EXPIRED 显示已失效`() {
        for (state in states) {
            for (hasBitmap in listOf(true, false)) {
                val panel = biliQrPanel(state, hasBitmap = hasBitmap, renderFailed = false)
                if (state == BiliQrLogin.State.EXPIRED) {
                    assertEquals(
                        "EXPIRED 必须落在 EXPIRED 档（state=$state bitmap=$hasBitmap）",
                        BiliQrPanel.EXPIRED, panel,
                    )
                } else {
                    assertEquals(
                        "除 EXPIRED 之外任何状态都不得显示「已失效」（state=$state bitmap=$hasBitmap）",
                        false, panel == BiliQrPanel.EXPIRED,
                    )
                }
            }
        }
    }

    @Test
    fun `有位图就画码 —— 等待 已扫码 成功三态都算`() {
        for (state in listOf(
            BiliQrLogin.State.WAITING,
            BiliQrLogin.State.SCANNED,
            BiliQrLogin.State.CONFIRMED,
        )) {
            assertEquals(
                "有位图必须画码（state=$state）",
                BiliQrPanel.QR, biliQrPanel(state, hasBitmap = true, renderFailed = false),
            )
        }
    }

    @Test
    fun `没有位图且一切正常时是加载中 —— 不是已失效`() {
        for (state in listOf(
            BiliQrLogin.State.IDLE,
            BiliQrLogin.State.LOADING,
            BiliQrLogin.State.WAITING,
        )) {
            assertEquals(
                "还没画出来只能显示加载中（state=$state）",
                BiliQrPanel.LOADING, biliQrPanel(state, hasBitmap = false, renderFailed = false),
            )
        }
    }

    @Test
    fun `生成失败显示明确错误 —— 即使状态机还在 WAITING`() {
        // 这正是 v3.2.0 的那个形状：位图生成失败/没执行，而状态是 WAITING。
        // 新实现必须把它判成 FAILED（明确错误 + 刷新入口），不能落进"已失效"。
        assertEquals(
            BiliQrPanel.FAILED,
            biliQrPanel(BiliQrLogin.State.WAITING, hasBitmap = false, renderFailed = true),
        )
        // 申请就失败（状态机落 FAILED）
        assertEquals(
            BiliQrPanel.FAILED,
            biliQrPanel(BiliQrLogin.State.FAILED, hasBitmap = false, renderFailed = false),
        )
    }

    @Test
    fun `renderFailed 优先于其它一切`() {
        for (state in states) {
            assertEquals(
                "生成失败优先（state=$state）",
                BiliQrPanel.FAILED,
                biliQrPanel(state, hasBitmap = true, renderFailed = true),
            )
        }
    }

    @Test
    fun `过期时不画旧码 —— 避免用户扫一张已经失效的码`() {
        assertEquals(
            BiliQrPanel.EXPIRED,
            biliQrPanel(BiliQrLogin.State.EXPIRED, hasBitmap = true, renderFailed = false),
        )
    }
}
