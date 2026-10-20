/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.13：续期迟到响应闸门的纯逻辑单测。
 */

package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 这些用例守的是用户报障里最难在真机上归因的一类：
 *
 * > 续期请求发出去了，响应回来时用户已经登出 / 同账号重登 / 换了账号，
 * > 旧实现直接把旧 cookie 与旧凭证写回去 —— 登录态被悄悄改回旧账号/旧票，
 * > 全程没有任何错误码。
 *
 * 闸门是纯函数，所以每条分支都能在这里钉死；[QqTokenRefresher] 只负责在
 * 登录态锁内调用它，并在 `COMMIT` 时才写盘。
 */
class QqRefreshCommitGuardTest {

    private val started = QqRefreshCredential(
        musicId = 1001L,
        musicKey = "key-1",
        loginType = 0,
        loginTypeResolved = true,
    )

    @Test
    fun `登录态没变时允许提交`() {
        assertEquals(
            QqRefreshCommitGuard.Decision.COMMIT,
            QqRefreshCommitGuard.decide(
                started = started,
                current = started,
                currentUin = 1001L,
                loggedIn = true,
                startedRevision = 7L,
                currentRevision = 7L,
            ),
        )
    }

    @Test
    fun `请求期间登出则拒绝提交`() {
        assertEquals(
            QqRefreshCommitGuard.Decision.NOT_LOGGED_IN,
            QqRefreshCommitGuard.decide(
                started = started,
                current = null,
                currentUin = null,
                loggedIn = false,
                startedRevision = 7L,
                currentRevision = 8L,
            ),
        )
    }

    @Test
    fun `版本变了说明有更新的登录或刷新——拒绝提交`() {
        assertEquals(
            QqRefreshCommitGuard.Decision.SUPERSEDED,
            QqRefreshCommitGuard.decide(
                started = started,
                current = started,
                currentUin = 1001L,
                loggedIn = true,
                startedRevision = 7L,
                currentRevision = 8L,
            ),
        )
    }

    @Test
    fun `同账号重登换了一份凭证——旧响应拒绝提交`() {
        val relogin = started.copy(musicKey = "key-2")
        assertEquals(
            QqRefreshCommitGuard.Decision.CREDENTIAL_CHANGED,
            QqRefreshCommitGuard.decide(
                started = started,
                current = relogin,
                currentUin = 1001L,
                loggedIn = true,
                startedRevision = 9L,
                currentRevision = 9L,
            ),
        )
    }

    @Test
    fun `换号后 cookie 的 uin 对不上——拒绝提交`() {
        assertEquals(
            QqRefreshCommitGuard.Decision.ACCOUNT_CHANGED,
            QqRefreshCommitGuard.decide(
                started = started,
                current = started,
                currentUin = 2002L,
                loggedIn = true,
                startedRevision = 7L,
                currentRevision = 7L,
            ),
        )
    }

    @Test
    fun `凭证被清空但 cookie 仍判已登录——拒绝提交`() {
        assertEquals(
            QqRefreshCommitGuard.Decision.CREDENTIAL_CHANGED,
            QqRefreshCommitGuard.decide(
                started = started,
                current = null,
                currentUin = 1001L,
                loggedIn = true,
                startedRevision = 7L,
                currentRevision = 7L,
            ),
        )
    }

    @Test
    fun `musicid 未知的老凭证不做账号比较`() {
        val unknownId = started.copy(musicId = 0L)
        assertEquals(
            QqRefreshCommitGuard.Decision.COMMIT,
            QqRefreshCommitGuard.decide(
                started = unknownId,
                current = unknownId,
                currentUin = 2002L,
                loggedIn = true,
                startedRevision = 7L,
                currentRevision = 7L,
            ),
        )
    }
}
