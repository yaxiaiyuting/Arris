/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * E04 回归：QQ 验证码仅更新已有 Cookie 值时，自动检测必须仍然完成。
 */

package com.takahashirinta.ncrust.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E04：`QqCaptchaOverlay` 的完成条件原来是「出现**新键**」。
 *
 * 审查给的触发条件：WebView 里已经有验证码相关 cookie 名，完成验证只**更新这些键的值**，
 * 不新增键。旧判据 `now.keys.any { it !in base }` 对这种形状恒为 false ⇒
 * 自动检测一直不结束（只能靠手动「验证完成」按钮兜底，而只有 `onVerified` 才会重发短信）。
 *
 * 这里驱动真实的 [QqCaptchaCookieGate]（不是复制一份公式）。
 */
class QqCaptchaCookieGateTest {

    private fun snapshot(raw: String) = QqCaptchaCookieGate.snapshot(raw)

    /** E04 的主体：键不变、只有值变了。 */
    @Test
    fun `aChangedValueOnAnExistingKeyCountsAsVerified()`() {
        val baseline = snapshot("uin=123; p_skey=old; qqmusic_key=OLD")
        val now = snapshot("uin=123; p_skey=new; qqmusic_key=NEW")

        assertTrue(
            "验证结果常是给已有键换值，必须算完成（旧实现只看键名，永远等不到）",
            QqCaptchaCookieGate.changedSince(baseline, now),
        )
    }

    /** 新增键仍然算（旧实现唯一覆盖的形状，不能回退）。 */
    @Test
    fun `aBrandNewKeyStillCounts()`() {
        val baseline = snapshot("uin=123")
        val now = snapshot("uin=123; p_skey=fresh")

        assertTrue(QqCaptchaCookieGate.changedSince(baseline, now))
    }

    /** 完全没变化 ⇒ 未完成；否则「进页面即完成」，会拿没验证过的 cookie 去重发短信。 */
    @Test
    fun `anUnchangedCookieIsNotVerified()`() {
        val baseline = snapshot("uin=123; p_skey=old")
        val now = snapshot("uin=123; p_skey=old")

        assertFalse(QqCaptchaCookieGate.changedSince(baseline, now))
    }

    /** 读到空 cookie 不能当成完成（否则把空凭据交回去重发短信）。 */
    @Test
    fun `anEmptySnapshotIsNotVerified()`() {
        assertFalse(QqCaptchaCookieGate.changedSince(emptyMap(), emptyMap()))
        assertFalse(QqCaptchaCookieGate.changedSince(snapshot("uin=123"), emptyMap()))
    }

    /** 基线解析沿用 `QqCookie.parse` 的容错：分号/空白/值里带 `=` 都要认。 */
    @Test
    fun `baselineParsingToleratesTheRealCookieShapes()`() {
        val baseline = snapshot("uin=123; qm_keyst=abc==;;  p_skey=x")
        assertTrue(baseline.containsKey("uin"))
        assertTrue(baseline.containsKey("qm_keyst"))
        assertTrue(baseline.containsKey("p_skey"))

        val now = snapshot("uin=123; qm_keyst=abc==; p_skey=y")
        assertTrue(QqCaptchaCookieGate.changedSince(baseline, now))
    }
}
