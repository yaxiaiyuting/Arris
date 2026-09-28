/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站扫码登录状态机的**有界性 / 取消 / 幂等**单测。
 */

package com.takahashirinta.ncrust.bili

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BiliQrLogin] 的状态机单测。**完全离线、无 `Context`、无真机**：
 * 注入假的 [BiliAuthEndpoints] + 假的时钟 + 假的 `sleep`，把每一条路径走完。
 *
 * 这一组用例对应 `QqQrLogin` 那套设计的四条可执行判据（见 [BiliQrLogin] 的类文档）：
 * 状态语义同构、错误分类、有界轮询、取消不是失败。
 */
class BiliAuthQrLoginTest {

    private companion object {
        const val KEY = "4789f53cc92fcb2191a94ba57aed76c3"
        val QR = BiliQrCode(url = "https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=$KEY", qrcodeKey = KEY)
        val CRED = BiliCredential(sessdata = "FAKESESS", biliJct = "FAKEJCT", dedeUserId = "1234567")
    }

    /** 假的后端：可编排队列、可注入异常、**记录每一次调用**。 */
    private class FakeApi(
        var generate: BiliQrCode? = QR,
        var generateThrows: Throwable? = null,
        var pollThrows: Throwable? = null,
        var defaultPoll: BiliQrPoll = BiliQrPoll.Pending,
    ) : BiliAuthEndpoints {
        val queue = ArrayDeque<BiliQrPoll>()
        var generateCalls = 0
        var pollCalls = 0
        var navCalls = 0
        val polledKeys = mutableListOf<String>()

        override suspend fun qrGenerate(): BiliQrCode? {
            generateCalls++
            generateThrows?.let { throw it }
            return generate
        }

        override suspend fun qrPoll(qrcodeKey: String): BiliQrPoll {
            pollCalls++
            polledKeys.add(qrcodeKey)
            pollThrows?.let { throw it }
            return if (queue.isEmpty()) defaultPoll else queue.removeFirst()
        }

        override suspend fun navWithCookie(cookie: String): BiliNavResult {
            navCalls++
            return BiliNavResult.LoggedIn(BiliNav(isLogin = true, uname = "测试用户", mid = 1234567))
        }
    }

    /** 假时钟：`sleep` 直接把时间推过去 —— 用例因此是**确定性的**（没有真实等待）。 */
    private class FakeClock {
        var now = 0L
        val clock: () -> Long = { now }
        val sleep: suspend (Long) -> Unit = { now += it }
    }

    private fun login(api: FakeApi, c: FakeClock) = BiliQrLogin(api = api, clock = c.clock, sleep = c.sleep)

    // ---------------------------------------------------------------- 正常路径

    @Test
    fun `未扫码 到 已扫码 到 成功 —— 状态序列与凭据`() = runBlocking {
        val api = FakeApi().apply {
            queue.addAll(listOf(BiliQrPoll.Pending, BiliQrPoll.Scanned, BiliQrPoll.Success(CRED, 1_662_363_009_601L)))
        }
        val c = FakeClock()
        val target = login(api, c)
        val seen = mutableListOf<BiliQrLogin.State>()

        val end = target.start { seen += it }

        assertEquals(BiliQrLogin.State.CONFIRMED, end)
        assertEquals(
            "状态序列必须逐步可见（UI 靠它更新文案）",
            listOf(
                BiliQrLogin.State.LOADING,
                BiliQrLogin.State.WAITING,
                BiliQrLogin.State.SCANNED,
                BiliQrLogin.State.CONFIRMED,
            ),
            seen,
        )
        assertEquals(3, target.pollCount)
        assertEquals(3, api.pollCalls)
        assertEquals("每次轮询带的是当前这张二维码的 key", listOf(KEY, KEY, KEY), api.polledKeys)
        assertNotNull(target.credential)
        assertEquals("FAKESESS", target.credential?.sessdata)
        assertNull(target.lastFailure)
    }

    @Test
    fun `二维码获取失败直接落 FAILED 且一次都不轮询`() = runBlocking {
        val api = FakeApi(generate = null)
        val c = FakeClock()
        val target = login(api, c)
        assertEquals(BiliQrLogin.State.FAILED, target.start())
        assertEquals(0, api.pollCalls)
        assertEquals(0, target.pollCount)
    }

    // ---------------------------------------------------------------- 过期

    @Test
    fun `服务端说 86038 —— 立刻终止在 EXPIRED（UI 据此给刷新二维码）`() = runBlocking {
        val api = FakeApi().apply { queue.addAll(listOf(BiliQrPoll.Pending, BiliQrPoll.Expired)) }
        val c = FakeClock()
        val target = login(api, c)
        assertEquals(BiliQrLogin.State.EXPIRED, target.start())
        assertEquals(2, api.pollCalls)
        // 过期之后不再发请求（终态幂等）。
        assertEquals(BiliQrLogin.State.EXPIRED, target.pollOnce())
        assertEquals(2, api.pollCalls)
    }

    @Test
    fun `本地总时长上限先到 —— 轮询次数被 MAX_POLLS 钉死 不是无限循环`() = runBlocking {
        val api = FakeApi(defaultPoll = BiliQrPoll.Pending)
        val c = FakeClock()
        val target = login(api, c)
        val end = target.start()
        assertEquals(BiliQrLogin.State.EXPIRED, end)
        assertEquals("总时长上限 = 次数上限（两个上限都要在）", BiliQrLogin.MAX_POLLS, target.pollCount)
        assertEquals(BiliQrLogin.MAX_POLLS, api.pollCalls)
        assertEquals(
            "假时钟被 sleep 推到 TTL 时应当刚好用完次数上限",
            BiliQrLogin.QR_TTL_MS,
            c.now,
        )
    }

    @Test
    fun `三个上限之间的关系（改了间隔却忘改次数会立刻变红）`() {
        assertEquals(180L * 1000L, BiliQrLogin.QR_TTL_MS)
        assertEquals("实测 180 秒（+178s 仍是 86101、+189s 已 86038）", 180, BiliQrLogin.QR_TTL_SECONDS)
        assertEquals(BiliQrLogin.QR_TTL_MS / BiliQrLogin.POLL_INTERVAL_MS, BiliQrLogin.MAX_POLLS.toLong())
        assertEquals("与 QQ/网易云两侧同口径的 2 秒", 2_000L, BiliQrLogin.POLL_INTERVAL_MS)
    }

    // ---------------------------------------------------------------- 错误分类（重试无意义 vs 抖动）

    @Test
    fun `连续服务端回绝到上限就停 —— 重试没有意义（HTTP 恒 200 也不会变）`() = runBlocking {
        val api = FakeApi(defaultPoll = BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.MALFORMED, "HTML"))
        val c = FakeClock()
        val target = login(api, c)
        assertEquals(BiliQrLogin.State.FAILED, target.start())
        assertEquals(BiliQrLogin.MAX_CONSECUTIVE_UNAVAILABLE, api.pollCalls)
        assertEquals(BiliQrPoll.Failed.Reason.MALFORMED, target.lastFailure?.reason)
    }

    @Test
    fun `连续网络抖动到上限也停 —— 有界，不是无限重试`() = runBlocking {
        val api = FakeApi(defaultPoll = BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.NETWORK, "timeout"))
        val c = FakeClock()
        val target = login(api, c)
        assertEquals(BiliQrLogin.State.FAILED, target.start())
        assertEquals(BiliQrLogin.MAX_CONSECUTIVE_NETWORK_ERRORS, api.pollCalls)
        assertTrue(
            "网络抖动的容忍窗口必须比「服务端回绝」宽（前者真的会自己好）",
            BiliQrLogin.MAX_CONSECUTIVE_NETWORK_ERRORS > BiliQrLogin.MAX_CONSECUTIVE_UNAVAILABLE,
        )
    }

    @Test
    fun `容错窗口内的网络抖动不落 FAILED —— 界面不闪红字 恢复后照常成功`() = runBlocking {
        val api = FakeApi().apply {
            queue.addAll(
                listOf(
                    BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.NETWORK, "timeout"),
                    BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.NETWORK, "timeout"),
                    BiliQrPoll.Scanned,
                    BiliQrPoll.Success(CRED),
                ),
            )
        }
        val c = FakeClock()
        val target = login(api, c)
        val seen = mutableListOf<BiliQrLogin.State>()
        assertEquals(BiliQrLogin.State.CONFIRMED, target.start { seen += it })
        assertFalse("抖动期间不许出现 FAILED", seen.contains(BiliQrLogin.State.FAILED))
        assertEquals(4, api.pollCalls)
    }

    @Test
    fun `成功之后再 poll 或 start 都不再发请求（幂等）`() = runBlocking {
        val api = FakeApi().apply { queue.add(BiliQrPoll.Success(CRED)) }
        val c = FakeClock()
        val target = login(api, c)
        assertEquals(BiliQrLogin.State.CONFIRMED, target.start())
        val polls = api.pollCalls
        val gens = api.generateCalls

        assertEquals(BiliQrLogin.State.CONFIRMED, target.pollOnce())
        assertEquals(BiliQrLogin.State.CONFIRMED, target.start())
        assertEquals("成功是终态：不许再轮询", polls, api.pollCalls)
        assertEquals("成功是终态：不许重新申请二维码", gens, api.generateCalls)
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun `取消不是失败 —— cancel 之后落 CANCELLED 而不是 FAILED`() = runBlocking {
        val api = FakeApi(defaultPoll = BiliQrPoll.Pending)
        val c = FakeClock()
        // 假 sleep 在第 2 次「睡」的时候模拟用户点了取消（必须取消**同一个**实例）。
        var slept = 0
        var target: BiliQrLogin? = null
        val login = BiliQrLogin(
            api = api,
            clock = c.clock,
            sleep = { ms ->
                slept++
                if (slept >= 2) target?.cancel()
                c.now += ms
            },
        )
        target = login
        val seen = mutableListOf<BiliQrLogin.State>()
        val end = login.start { seen += it }
        assertEquals(BiliQrLogin.State.CANCELLED, end)
        assertEquals(BiliQrLogin.State.CANCELLED, login.state)
        assertFalse("取消绝不能显示成失败", seen.contains(BiliQrLogin.State.FAILED))
        assertFalse("取消也不该走到过期", seen.contains(BiliQrLogin.State.EXPIRED))
        assertEquals("取消发生在第 2 次轮询之前 ⇒ 只发出 1 次请求", 1, api.pollCalls)
    }

    @Test
    fun `CancellationException 原样抛出 —— 不吞成 FAILED`() = runBlocking {
        val api = FakeApi(pollThrows = CancellationException("用户关掉了弹窗"))
        val c = FakeClock()
        val target = login(api, c)
        var caught: CancellationException? = null
        try {
            target.start()
            fail("取消必须穿透出去，不能被 catch (e: Exception) 吞掉")
        } catch (e: CancellationException) {
            caught = e
        }
        assertEquals("用户关掉了弹窗", caught?.message)
        assertFalse("取消不是失败：状态里不许出现 FAILED", target.state == BiliQrLogin.State.FAILED)
    }

    @Test
    fun `申请二维码阶段的取消同样原样抛出`() = runBlocking {
        val api = FakeApi(generateThrows = CancellationException("停止"))
        val target = login(api, FakeClock())
        try {
            target.start()
            fail("取消必须穿透出去")
        } catch (e: CancellationException) {
            assertEquals("停止", e.message)
        }
    }

    @Test
    fun `reset 之后可以重新开始（刷新二维码）`() = runBlocking {
        val api = FakeApi().apply { queue.addAll(listOf(BiliQrPoll.Expired, BiliQrPoll.Success(CRED))) }
        val c = FakeClock()
        val target = login(api, c)
        assertEquals(BiliQrLogin.State.EXPIRED, target.start())
        target.reset()
        assertEquals(BiliQrLogin.State.IDLE, target.state)
        assertNull(target.qrCode)
        assertEquals(0, target.pollCount)
        assertEquals(BiliQrLogin.State.CONFIRMED, target.start())
        assertEquals(2, api.generateCalls)
    }

    // ---------------------------------------------------------------- 单步入口

    @Test
    fun `pollOnce 单步 —— 一次一个请求 且没有二维码时一个请求都不发`() = runBlocking {
        val api = FakeApi(defaultPoll = BiliQrPoll.Pending)
        val target = login(api, FakeClock())
        // 还没 prepare：没有 qrcode_key，不能凭空发请求。
        assertEquals(BiliQrLogin.State.IDLE, target.pollOnce())
        assertEquals(0, api.pollCalls)

        // prepare 之后（WAITING）才轮询，而且 pollOnce 只发**一次**。
        assertEquals(BiliQrLogin.State.WAITING, target.prepare())
        assertEquals(BiliQrLogin.State.WAITING, target.pollOnce())
        assertEquals(1, api.pollCalls)
        assertEquals("pollOnce 不该重新申请二维码", 1, api.generateCalls)
    }

    @Test
    fun `pollOnce 网络失败直接落 FAILED（连续容错是 start 的职责）`() = runBlocking {
        val api = FakeApi(defaultPoll = BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.NETWORK, "timeout"))
        val target = login(api, FakeClock())
        assertEquals(BiliQrLogin.State.WAITING, target.prepare())
        assertEquals(BiliQrLogin.State.FAILED, target.pollOnce())
        assertEquals("单步语义：一次失败就是失败，不自己重试", 1, api.pollCalls)
        val polls = api.pollCalls
        assertEquals("终态之后一个请求都不许发", BiliQrLogin.State.FAILED, target.pollOnce())
        assertEquals(polls, api.pollCalls)
    }

    @Test
    fun `prepare 幂等 —— 成功之后再 prepare 不重新申请二维码`() = runBlocking {
        val api = FakeApi().apply { queue.add(BiliQrPoll.Success(CRED)) }
        val target = login(api, FakeClock())
        assertEquals(BiliQrLogin.State.CONFIRMED, target.start())
        val gens = api.generateCalls
        assertEquals(BiliQrLogin.State.CONFIRMED, target.prepare())
        assertEquals(gens, api.generateCalls)
    }
    // ------------------------------------------------ v3.2.1 · P0：prepare / poll 分离
    //
    // 真机 P0「B站登录打开即失效」的根因是界面把 `start()`（= prepare + 轮询到终态）
    // 当成一次短调用，位图被排到它之后 ⇒ 180 秒内二维码区永远是空的。
    // 修法是状态机暴露 `poll()`，界面按「prepare → 渲染 → poll」串。
    // 下面四条把 `poll()` 的契约钉死（顺序、幂等、空操作、次数）。

    @Test
    fun `poll 不重复申请二维码 —— generate 只发一次`() = runBlocking {
        val api = FakeApi().apply {
            queue.addAll(listOf(BiliQrPoll.Pending, BiliQrPoll.Success(CRED, 1_662_363_009_601L)))
        }
        val c = FakeClock()
        val target = login(api, c)
        val seen = mutableListOf<BiliQrLogin.State>()

        assertEquals(BiliQrLogin.State.WAITING, target.prepare { seen += it })
        assertEquals("prepare 之后就已经有码可渲染", true, target.qrCode != null)
        assertEquals(BiliQrLogin.State.CONFIRMED, target.poll { seen += it })

        assertEquals("generate 只能发一次", 1, api.generateCalls)
        assertEquals(
            "prepare + poll 的前两帧仍是 LOADING → WAITING（UI 靠它先渲染二维码）",
            listOf(BiliQrLogin.State.LOADING, BiliQrLogin.State.WAITING),
            seen.take(2),
        )
        assertEquals(2, target.pollCount)
        assertEquals(2, api.pollCalls)
    }

    @Test
    fun `start 与 prepare 加 poll 的状态序列逐项相同`() = runBlocking {
        fun script() = listOf(BiliQrPoll.Pending, BiliQrPoll.Scanned, BiliQrPoll.Success(CRED, 1L))
        val apiA = FakeApi().apply { queue.addAll(script()) }
        val apiB = FakeApi().apply { queue.addAll(script()) }
        val seenA = mutableListOf<BiliQrLogin.State>()
        val seenB = mutableListOf<BiliQrLogin.State>()

        login(apiA, FakeClock()).start { seenA += it }
        val target = login(apiB, FakeClock())
        target.prepare { seenB += it }
        target.poll { seenB += it }

        assertEquals("拆开之后状态序列必须逐项相同", seenA, seenB)
        assertEquals(apiA.pollCalls, apiB.pollCalls)
    }

    @Test
    fun `没有二维码时 poll 是空操作 —— 一个请求都不发`() = runBlocking {
        val api = FakeApi()
        val target = login(api, FakeClock())
        // 初始就是 IDLE；没有二维码时 poll 必须**原样返回**，既不申请也不轮询。
        assertEquals(BiliQrLogin.State.IDLE, target.poll())
        assertEquals(0, api.pollCalls)
        assertEquals(0, api.generateCalls)
    }

    @Test
    fun `申请失败时 poll 不会把 FAILED 变成别的状态`() = runBlocking {
        val api = FakeApi(generateThrows = RuntimeException("boom"))
        val target = login(api, FakeClock())
        assertEquals(BiliQrLogin.State.FAILED, target.prepare())
        assertEquals("失败之后 poll 不得发请求、也不得改状态", BiliQrLogin.State.FAILED, target.poll())
        assertEquals(0, api.pollCalls)
    }

    @Test
    fun `已是终态时 poll 幂等 —— 不再发请求`() = runBlocking {
        val api = FakeApi()
        val target = login(api, FakeClock())
        target.prepare()
        api.queue.addLast(BiliQrPoll.Expired)
        assertEquals(BiliQrLogin.State.EXPIRED, target.poll())
        val callsAfterExpire = api.pollCalls
        assertEquals(BiliQrLogin.State.EXPIRED, target.poll())
        assertEquals("终态之后一次都不再轮询", callsAfterExpire, api.pollCalls)
    }

}
