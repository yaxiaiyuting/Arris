/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 2026-10-27 审查 E08：B 站扫码控制器的取消状态被在途响应覆盖。
 */

package com.takahashirinta.ncrust.bili

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E08：**取消 / 重置之后，迟到的响应不许改状态、更不许写凭据**。
 *
 * ## 缺陷形状（报告原文）
 *
 * `prepare` 等到申请响应后不检查取消标志；`poll` / `pollOnce` 也只在等待间隔两侧检查 ——
 * 于是「用户在请求在飞的那一刻点了取消」会遇到：
 * - 申请响应回来 ⇒ 状态由 `CANCELLED` 变回 `WAITING`（登录框已经关了，控制器却还在等扫码）；
 * - 轮询成功响应回来 ⇒ 变 `CONFIRMED` 并**持有凭据**（下一步 `finishLogin` 就会落盘）。
 *   报告里那条用例的观感就是「关掉登录框之后反而登录成功了」。
 *
 * 修法是会话代次（`BiliQrLogin.generation`）：取消 / 重置 / 重开都推进它，
 * 每个 `await` 之后复核。这组用例用一个「门」控住在途请求，稳定复现那个窗口 ——
 * 没有真实网络、没有真实 sleep、没有 Context。
 */
class E08BiliQrCancelTest {

    private companion object {
        const val KEY = "4789f53cc92fcb2191a94ba57aed76c3"
        val QR = BiliQrCode(
            url = "https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=$KEY",
            qrcodeKey = KEY,
        )
        val CRED = BiliCredential(sessdata = "FAKESESS", biliJct = "FAKEJCT", dedeUserId = "1234567")
    }

    /**
     * 可控后端：`qrGenerate` / `qrPoll` 先在各自的 [CompletableDeferred] 上挂起，
     * 由用例决定何时放行 —— 于是「响应在飞时取消」这个窗口可以被**稳定**复现。
     */
    private class GatedApi : BiliAuthEndpoints {
        val generateGate = CompletableDeferred<Unit>()
        var pollGate = CompletableDeferred<Unit>()
        var pollResult: BiliQrPoll = BiliQrPoll.Success(CRED)
        var generateCalls = 0
        var pollCalls = 0

        override suspend fun qrGenerate(): BiliQrCode {
            generateCalls++
            generateGate.await()
            return QR
        }

        override suspend fun qrPoll(qrcodeKey: String): BiliQrPoll {
            pollCalls++
            pollGate.await()
            return pollResult
        }

        override suspend fun navWithCookie(cookie: String): BiliNavResult = BiliNavResult.NotLoggedIn
    }

    private fun controller(api: BiliAuthEndpoints) = BiliQrLogin(
        api = api,
        clock = { 0L },
        sleep = { }, // 不真的等 2 秒：用例自己控制节奏。
    )

    /** 让一条挂起的协程先跑到「请求已经发出去」的那一点，再动手取消。 */
    private suspend fun awaitCalls(predicate: () -> Boolean) {
        while (!predicate()) yield()
    }

    /** 取消发生在「申请二维码」的请求在飞时：迟到的响应不许把状态推回 WAITING。 */
    @Test
    fun `取消在途申请后 迟到的二维码响应不许把状态改回 WAITING`() = runBlocking {
        val api = GatedApi()
        val login = controller(api)

        var terminal: BiliQrLogin.State? = null
        val worker = CoroutineScope(Dispatchers.Unconfined).launch { terminal = login.prepare() }
        awaitCalls { api.generateCalls > 0 }
        login.cancel()
        api.generateGate.complete(Unit)
        worker.join()

        assertEquals("取消不是失败，也不是回到等待", BiliQrLogin.State.CANCELLED, terminal)
        assertEquals(BiliQrLogin.State.CANCELLED, login.state)
        assertNull("取消后不许留下任何二维码", login.qrCode)
    }

    /** `poll()` 的循环里被取消：终态必须是 CANCELLED，且凭据为 null。 */
    @Test
    fun `轮询循环中被取消 迟到的成功响应不许落成 CONFIRMED`() = runBlocking {
        val api = GatedApi()
        api.generateGate.complete(Unit)
        val login = controller(api)
        login.prepare()
        assertEquals(BiliQrLogin.State.WAITING, login.state)

        var terminal: BiliQrLogin.State? = null
        val worker = CoroutineScope(Dispatchers.Unconfined).launch { terminal = login.poll() }
        awaitCalls { api.pollCalls > 0 }
        login.cancel()
        api.pollGate.complete(Unit)
        worker.join()

        assertEquals(BiliQrLogin.State.CANCELLED, terminal)
        assertEquals(BiliQrLogin.State.CANCELLED, login.state)
        assertNull("取消之后绝不持有凭据（否则 finishLogin 会把上一轮凭据落盘）", login.credential)
    }

    /** `pollOnce()` 的单步请求同样有这个窗口。 */
    @Test
    fun `单步轮询在途时被取消 响应不许 apply`() = runBlocking {
        val api = GatedApi()
        api.generateGate.complete(Unit)
        val login = controller(api)
        login.prepare()

        var terminal: BiliQrLogin.State? = null
        val worker = CoroutineScope(Dispatchers.Unconfined).launch { terminal = login.pollOnce() }
        awaitCalls { api.pollCalls > 0 }
        login.cancel()
        api.pollGate.complete(Unit)
        worker.join()

        assertEquals(BiliQrLogin.State.CANCELLED, terminal)
        assertNull(login.credential)
    }

    /** 没有取消时，成功响应当然要正常落成 CONFIRMED（别把正常路径一起修坏）。 */
    @Test
    fun `未取消时成功响应正常落成 CONFIRMED`() = runBlocking {
        val api = GatedApi()
        api.generateGate.complete(Unit)
        api.pollGate.complete(Unit)
        val login = controller(api)
        login.prepare()
        val state = login.pollOnce()
        assertEquals(BiliQrLogin.State.CONFIRMED, state)
        assertEquals(CRED, login.credential)
    }

    /** 重开（reset）之后，上一轮在飞的响应同样不许写进新的会话。 */
    @Test
    fun `重置之后上一轮的迟到响应不许写进新会话`() = runBlocking {
        val api = GatedApi()
        api.generateGate.complete(Unit)
        val login = controller(api)
        login.prepare()

        var terminal: BiliQrLogin.State? = null
        val worker = CoroutineScope(Dispatchers.Unconfined).launch { terminal = login.pollOnce() }
        awaitCalls { api.pollCalls > 0 }
        login.reset()
        api.pollGate.complete(Unit)
        worker.join()

        assertEquals("上一轮的响应不属于这一轮会话", BiliQrLogin.State.CANCELLED, terminal)
        assertNull(login.credential)
    }
}
