/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1：大屏模式方向策略的两个纯判定。
 *
 * 为什么单独测：设备平放在桌面上时无法在真机上验证「物理横过来 → 放宽成 SENSOR」与
 * 「转回竖屏 → 退出大屏」这两条路径（需要真的把手机转过去），所以判据必须由单测兜住。
 */
class BigScreenOrientationTest {

    // ---------- 物理朝向 ----------

    @Test
    fun `portrait holds are not landscape`() {
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(0))   // 正持竖屏
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(180)) // 倒持竖屏
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(44))
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(136))
    }

    @Test
    fun `landscape holds are landscape`() {
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(90))
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(270))
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(45))
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(315))
    }

    @Test
    fun `unknown and out of range degrees are not landscape`() {
        // 平放 / 传感器不可用时平台回 -1，必须当作"不是横向"（否则会立刻放宽、窗口弹回竖屏）
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(BigScreenOrientation.ORIENTATION_UNKNOWN))
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(360))
        assertFalse(BigScreenOrientation.isPhysicallyLandscape(1440)) // 多圈之后仍是 0°
        // 负角度先归一化再判定：-90° ≡ 270°（左横持）
        assertTrue(BigScreenOrientation.isPhysicallyLandscape(-90))
    }

    // ---------- 放宽判定 ----------

    @Test
    fun `relax only after the device is physically landscape`() {
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = false, degrees = 0))
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = false, degrees = -1))
        assertTrue(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = false, degrees = 90))
    }

    @Test
    fun `relax is idempotent and never fires outside big screen`() {
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = true, alreadyRelaxed = true, degrees = 90))
        assertFalse(BigScreenOrientation.shouldRelaxToSensor(bigScreen = false, alreadyRelaxed = false, degrees = 90))
    }

    // ---------- 退出判定 ----------

    @Test
    fun `rotating back to portrait exits big screen`() {
        assertTrue(BigScreenOrientation.shouldExitOnConfiguration(bigScreen = true, orientationLandscape = false))
        assertFalse(BigScreenOrientation.shouldExitOnConfiguration(bigScreen = true, orientationLandscape = true))
        assertFalse(BigScreenOrientation.shouldExitOnConfiguration(bigScreen = false, orientationLandscape = false))
    }

    // ---------- v1.8.0 · T4：方向策略总判定 ----------

    @Test
    fun `auto rotate off reproduces the v1_7_0 policy`() {
        // 手机：锁竖屏；大屏设备（平板/折叠/车机）：不限制方向。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.PORTRAIT,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = false, bigScreenRelaxed = false, isLargeScreen = false
            )
        )
        assertEquals(
            BigScreenOrientation.DesiredOrientation.UNSPECIFIED,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = false, bigScreenRelaxed = false, isLargeScreen = true
            )
        )
    }

    @Test
    fun `auto rotate on follows the sensor on phones and ignores the large screen branch`() {
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR,
            BigScreenOrientation.orientationFor(
                autoRotate = true, bigScreen = false, bigScreenRelaxed = false, isLargeScreen = false
            )
        )
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR,
            BigScreenOrientation.orientationFor(
                autoRotate = true, bigScreen = false, bigScreenRelaxed = false, isLargeScreen = true
            )
        )
    }

    @Test
    fun `big screen wins over auto rotate in both directions`() {
        // 进入的第一步：还没放宽 → 强制横屏（用户此刻还竖着拿手机）。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR_LANDSCAPE,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = true, bigScreenRelaxed = false, isLargeScreen = false
            )
        )
        // 已放宽 **且 auto-rotate 开** → SENSOR（v1.8.0 的双向语义不变）。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR,
            BigScreenOrientation.orientationFor(
                autoRotate = true, bigScreen = true, bigScreenRelaxed = true, isLargeScreen = false
            )
        )
    }

    // ---------- v2.0.0 · T1-A：auto-rotate 关时"保持横屏" ----------

    @Test
    fun `auto rotate off holds landscape in big screen even after relax`() {
        // 用户报告：「我希望保持横屏的时候它自动切换竖屏模式了」。
        // auto-rotate 关 = 用户是显式按 ⤢ 进来的 ⇒ 保持横屏，放宽与否都一样，
        // 绝不返回 SENSOR（那会在手机一歪时翻竖屏 → 退出大屏 → 又被锁回竖屏）。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR_LANDSCAPE,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = true, bigScreenRelaxed = false, isLargeScreen = false
            )
        )
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR_LANDSCAPE,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = true, bigScreenRelaxed = true, isLargeScreen = false
            )
        )
        // 大屏设备（平板/折叠展开）同样按"保持横屏"处理：UNSPECIFIED 会让横屏平板
        // 因为系统锁而翻竖屏，与用户意图相反。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR_LANDSCAPE,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = true, bigScreenRelaxed = true, isLargeScreen = true
            )
        )
    }

    @Test
    fun `auto rotate off outside big screen still locks portrait`() {
        // T1-A 只改"大屏里面"的那一档，退出大屏后必须回到 v1.7.0 的锁竖屏。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.PORTRAIT,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = false, bigScreenRelaxed = false, isLargeScreen = false
            )
        )
        // 放宽标记在大屏外是脏值，也不能影响判定（exitBigScreenMode 会清它，这里额外钉死）。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.PORTRAIT,
            BigScreenOrientation.orientationFor(
                autoRotate = false, bigScreen = false, bigScreenRelaxed = true, isLargeScreen = false
            )
        )
        // autoRotate 开着但还没放宽：仍然是"先转过去"，不能被 SENSOR 分支抢先。
        assertEquals(
            BigScreenOrientation.DesiredOrientation.SENSOR_LANDSCAPE,
            BigScreenOrientation.orientationFor(
                autoRotate = true, bigScreen = true, bigScreenRelaxed = false, isLargeScreen = false
            )
        )
    }

    // ---------- v1.8.0 · T4：自动进入大屏 ----------

    @Test
    fun `auto enter needs all four conditions`() {
        // 全开 = 竖屏播放器转横屏 → 自动进大屏（用户报的"竖屏到横屏不可以"就是这条）。
        assertTrue(
            BigScreenOrientation.shouldAutoEnterBigScreen(
                autoRotate = true, playerExpanded = true, windowLandscape = true, bigScreen = false
            )
        )
        // 开关关 → 转屏不触发（⤢ 按钮仍可手动进）。
        assertFalse(
            BigScreenOrientation.shouldAutoEnterBigScreen(
                autoRotate = false, playerExpanded = true, windowLandscape = true, bigScreen = false
            )
        )
        // 非播放界面（首页/库/搜索）转横屏 → 不进大屏。播放器收起时 playerExpanded = false。
        assertFalse(
            BigScreenOrientation.shouldAutoEnterBigScreen(
                autoRotate = true, playerExpanded = false, windowLandscape = true, bigScreen = false
            )
        )
        // 窗口还没转过去（旋转有延迟）→ 不在竖屏窗口里塞横屏两栏布局。
        assertFalse(
            BigScreenOrientation.shouldAutoEnterBigScreen(
                autoRotate = true, playerExpanded = true, windowLandscape = false, bigScreen = false
            )
        )
        // 已经在大屏里 → 幂等，不重复进。
        assertFalse(
            BigScreenOrientation.shouldAutoEnterBigScreen(
                autoRotate = true, playerExpanded = true, windowLandscape = true, bigScreen = true
            )
        )
    }

    /** 防抖窗口必须是一个"人感觉不到、但足够吞掉连续配置回调"的量级。 */
    @Test
    fun `auto enter settle window is short but non zero`() {
        assertTrue(BigScreenOrientation.AUTO_ENTER_SETTLE_MS in 100L..500L)
    }
}
