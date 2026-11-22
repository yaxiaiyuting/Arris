/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两族都失败后的冷却：冷却期内 [AddressPreference.onTransportFailure] **不再翻面**。
 *
 * 真机现场（2026-11-22 00:42–00:43，v4 长期黑洞）：每首歌先失败一次切到 v6、再失败一次切回 v4，
 * 于是每首歌都付 2×8s；冷却后每首最多付一次。
 */
class AddressFailoverCooldownTest {

    @After
    fun tearDown() {
        AddressPreference.clearFailoverState()
    }

    @Test
    fun `冷却期内失败不再翻面`() {
        AddressPreference.clearFailoverState()
        assertTrue("首次失败应切到 IPv6 优先", AddressPreference.onTransportFailure(nowMs = 1_000L))
        AddressPreference.noteBothFamiliesFailed(nowMs = 2_000L)

        // 冷却期内无论失败多少次，偏好保持不变（不来回翻）
        val first = AddressPreference.onTransportFailure(nowMs = 3_000L)
        val second = AddressPreference.onTransportFailure(nowMs = 4_000L)
        assertTrue("冷却期内应保持在 IPv6 优先", first && second)
    }

    @Test
    fun `冷却过期后恢复翻面`() {
        AddressPreference.clearFailoverState()
        AddressPreference.onTransportFailure(nowMs = 1_000L) // -> v6
        AddressPreference.noteBothFamiliesFailed(nowMs = 2_000L)
        val coolUntil = 2_000L + 3 * 60_000L
        // 冷却与「IPv6 优先」窗口都过期（都在 t≈181–182s 结束），所以下一次失败重新进入 v6 优先。
        assertFalse(AddressPreference.inBothFailedCooldown(nowMs = coolUntil + 1_000L))
        assertTrue(
            "过期后应恢复翻面（重新切到 IPv6 优先）",
            AddressPreference.onTransportFailure(nowMs = coolUntil + 1_000L),
        )
    }

    @Test
    fun `换网清掉冷却`() {
        AddressPreference.clearFailoverState()
        AddressPreference.onTransportFailure(nowMs = 1_000L)
        AddressPreference.noteBothFamiliesFailed(nowMs = 2_000L)
        assertTrue(AddressPreference.inBothFailedCooldown(nowMs = 3_000L))

        AddressPreference.clearFailoverState()
        assertFalse("换网后不应仍处于冷却", AddressPreference.inBothFailedCooldown(nowMs = 3_000L))
    }
}
