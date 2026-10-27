/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * J02 回归：验证成功与本地接受是两种结果。
 */

package com.takahashirinta.ncrust.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J02：`MainActivity` 把「服务端 `account/get` 通过」当成「本地已接受」：
 * `CookieManager.saveCookieMerged(...)` 的返回值被丢掉，界面照样
 * `SessionMonitor.onLoginSucceeded` + 关弹窗。
 *
 * 审查给的真实形状：本地已有完整 A 会话（`MUSIC_U` + `__csrf`），扫码 803 的
 * `Set-Cookie` 只带 `MUSIC_U=B`（增量）⇒ `shouldReplace` 拒绝覆盖 ⇒ 本地仍是 A，
 * 界面却说登录成功。
 *
 * 这里驱动真实的 [LoginCredentialAcceptance]（判定用的是同一个
 * `NeteaseCookie.parse` / `shouldReplace` 语义），断言三种结果被区分开。
 */
class LoginCredentialAcceptanceTest {

    private val completeA = "MUSIC_U=AAA; __csrf=csrfA; NMTID=nm; __remember_me=true"
    private val onlyMusicUB = "MUSIC_U=BBB"

    /** 保存被拒 + 本地不覆盖候选 ⇒ **拒绝**（不能当登录成功收尾）。 */
    @Test
    fun `aRejectedIncompleteCandidateIsNotSuccessful()`() {
        // saveCookieMerged 返回 false，读回的仍然是完整的 A 会话。
        assertFalse(
            LoginCredentialAcceptance.isApplied(
                savedByStore = false,
                stored = completeA,
                candidate = onlyMusicUB,
            )
        )
    }

    /** 保存被拒，但本地凭据已经覆盖候选（同会话重复轮询 / 同号重扫）⇒ 等价于接受。 */
    @Test
    fun `anUnchangedAlreadyStoredCandidateIsAccepted()`() {
        assertTrue(
            LoginCredentialAcceptance.isApplied(
                savedByStore = false,
                stored = completeA,
                candidate = "MUSIC_U=AAA; __csrf=csrfA",
            )
        )
    }

    /** 保存成功 ⇒ 接受，不再回读判定。 */
    @Test
    fun `aSuccessfulSaveIsAccepted()`() {
        assertTrue(
            LoginCredentialAcceptance.isApplied(
                savedByStore = true,
                stored = completeA,
                candidate = onlyMusicUB,
            )
        )
    }

    /**
     * `saveCookie` 只返回 Unit（返回值未知 ⇒ 传 null）：必须靠**读回**确认。
     *
     * 这条覆盖 `SettingsAccountSection` 那条登录路径 —— 它原本无条件
     * `hasCookie = true` + 关弹窗。
     */
    @Test
    fun `anUnknownSaveResultIsDecidedByReadingBack()`() {
        assertTrue(
            LoginCredentialAcceptance.isApplied(
                savedByStore = null,
                stored = "MUSIC_U=BBB; __csrf=csrfB",
                candidate = onlyMusicUB,
            )
        )
        assertFalse(
            LoginCredentialAcceptance.isApplied(
                savedByStore = null,
                stored = completeA,
                candidate = onlyMusicUB,
            )
        )
    }

    /** 空候选 / 空本地都不得判成"已生效"（否则会把空凭据当成登录成功）。 */
    @Test
    fun `emptyInputsAreNeverApplied()`() {
        assertFalse(LoginCredentialAcceptance.isApplied(false, completeA, null))
        assertFalse(LoginCredentialAcceptance.isApplied(false, completeA, "   "))
        assertFalse(LoginCredentialAcceptance.isApplied(null, null, onlyMusicUB))
        assertFalse(LoginCredentialAcceptance.isApplied(null, "", onlyMusicUB))
    }
}
