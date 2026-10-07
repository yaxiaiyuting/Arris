/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0：**取链失败的分类与分流**（铁律 20/21 的守卫）。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.qq.QqRejection
import com.takahashirinta.ncrust.qq.QqRejectionInput
import com.takahashirinta.ncrust.qq.classifyQqBatch
import com.takahashirinta.ncrust.qq.classifyQqRejection
import com.takahashirinta.ncrust.qq.toResolveFailureKind
import com.takahashirinta.ncrust.source.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.0 · P0 的守卫：**「权限不足」不许被说成「无版权」，失败不许自动跳歌。**
 *
 * 这个文件是整个 P0 的可执行定义。它不测网络、不测 Android —— 它测的是
 * 「给定服务端这样回答，客户端该做什么」，而那正是 v3.1.0 缺的那一层。
 *
 * 本文件的样本全部来自 2026-09-28 的真机 / 真实账号实测
 * （`docs/verification/v3.2.0/probe-qq-copyright.md`）：
 * - 带 VIP 票据：8/8 档 `result=0` + 非空 purl；
 * - 完全匿名：8/8 档 `result=104003` + 空 purl；
 * - 免费歌匿名：`C400`/`M500` 两档 `result=0`，其余 6 档 `104003`；
 * - 《Hotel California》登录态：`AI00` 回 `104003`，`Q000` 回 0 + purl。
 */
class ResolveFailureTest {

    // ---------------------------------------------------------------- QQ 单条分类

    @Test
    fun `结果码 104003 且本地无票据 —— 判为需要登录 而不是无版权`() {
        val r = classifyQqRejection(
            QqRejectionInput(resultCode = 104003, loggedIn = false),
        )
        assertEquals(QqRejection.NEED_LOGIN, r)
        // ★ 本 P0 的核心断言：**绝不许**把权限不足翻译成版权结论。
        assertEquals(ResolveFailureKind.NEED_LOGIN, r.toResolveFailureKind())
        assertFalse(
            "104003 不许映射成无版权",
            r.toResolveFailureKind() == ResolveFailureKind.COPYRIGHT_GONE,
        )
    }

    @Test
    fun `结果码 104003 且本地有票据 —— 判为权益不足 同样不是无版权`() {
        val r = classifyQqRejection(
            QqRejectionInput(resultCode = 104003, loggedIn = true),
        )
        assertEquals(QqRejection.NEED_VIP, r)
        assertEquals(ResolveFailureKind.NEED_VIP, r.toResolveFailureKind())
    }

    @Test
    fun `结果码 0 但没有 purl —— 是这个档位没有文件 不是权限也不是版权`() {
        val r = classifyQqRejection(QqRejectionInput(resultCode = 0, loggedIn = true))
        assertEquals(QqRejection.NO_FILE, r)
        assertEquals(ResolveFailureKind.UNKNOWN, r.toResolveFailureKind())
    }

    @Test
    fun `传输层失败 —— 判为网络 而不是任何一种业务拒绝`() {
        val r = classifyQqRejection(
            QqRejectionInput(transportFailed = true, httpStatus = null, loggedIn = true),
        )
        assertEquals(QqRejection.NETWORK, r)
        assertEquals(ResolveFailureKind.NETWORK, r.toResolveFailureKind())
        // 网络失败必须**可重试**，这是它与其它失败处置不同的地方。
        assertEquals(ResolveFailureAction.RETRY, resolveFailureAction(r.toResolveFailureKind()))
    }

    @Test
    fun `HTTP 401 与 403 —— 判为凭证过期 而不是网络`() {
        for (code in listOf(401, 403)) {
            val r = classifyQqRejection(
                QqRejectionInput(transportFailed = true, httpStatus = code, loggedIn = true),
            )
            assertEquals("http=$code", QqRejection.AUTH_EXPIRED, r)
            assertEquals(
                ResolveFailureAction.STOP_AND_INFORM,
                resolveFailureAction(r.toResolveFailureKind()),
            )
        }
    }

    @Test
    fun `需要单曲购买 —— 由服务端两个字段同时成立才判 处置是不跳歌`() {
        val r = classifyQqRejection(
            QqRejectionInput(resultCode = 0, pneedbuy = 1, isbuy = 0, loggedIn = true),
        )
        assertEquals(QqRejection.NEED_PURCHASE, r)
        assertEquals(
            ResolveFailureAction.STOP_AND_INFORM,
            resolveFailureAction(r.toResolveFailureKind()),
        )
    }

    @Test
    fun `已经买过（isbuy 等于 1）时不算需要购买 —— 判据不许只看 pneedbuy`() {
        val r = classifyQqRejection(
            QqRejectionInput(resultCode = 104003, pneedbuy = 1, isbuy = 1, loggedIn = true),
        )
        assertEquals(QqRejection.NEED_VIP, r)
    }

    @Test
    fun `读不懂的结果码一律 UNKNOWN —— 不猜原因 处置同样是不跳歌`() {
        for (code in listOf(-352, -403, -1200, 101404, 999999)) {
            val r = classifyQqRejection(QqRejectionInput(resultCode = code, loggedIn = true))
            assertEquals("code=$code", QqRejection.UNKNOWN, r)
            assertEquals(
                ResolveFailureAction.STOP_AND_INFORM,
                resolveFailureAction(r.toResolveFailureKind()),
            )
        }
    }

    // ---------------------------------------------------------------- QQ 批量收敛

    @Test
    fun `登录态下一个档位返回 0 而另一个返回 104003 —— 取到 purl 就是能播`() {
        // 实测形状：《Hotel California》AI00=104003 / Q000=0。
        // 客户端按「请求优先级」挑档位，所以只要**有**一档成功就不该报失败。
        val r = classifyQqBatch(
            listOf(
                QqRejectionInput(resultCode = 104003, loggedIn = true),
                // 这一档真的拿到了 purl（实测 Q000 的形状）。
                QqRejectionInput(hasPurl = true, resultCode = 0, loggedIn = true),
            ),
        )
        assertEquals(QqRejection.NONE, r)
    }

    @Test
    fun `全部档位都被拒时取最有行动价值的那一条 —— 凭证优先于无文件`() {
        val r = classifyQqBatch(
            listOf(
                QqRejectionInput(resultCode = 0, loggedIn = true),          // NO_FILE
                QqRejectionInput(resultCode = 104003, loggedIn = true),     // NEED_VIP
                QqRejectionInput(resultCode = 0, loggedIn = true),          // NO_FILE
            ),
        )
        assertEquals(QqRejection.NEED_VIP, r)
    }

    @Test
    fun `空输入不崩 —— 返回 UNKNOWN`() {
        assertEquals(QqRejection.UNKNOWN, classifyQqBatch(emptyList()))
    }

    // ---------------------------------------------------------------- 失败 → 处置（铁律 21 的表）

    @Test
    fun `铁律 21 —— 只有结构性缺失允许自动跳歌 其余一律不跳`() {
        val allowed = ResolveFailureKind.values().filter {
            resolveFailureAction(it) == ResolveFailureAction.SKIP_ALLOWED
        }
        assertEquals(
            "允许跳歌的失败类型有且只有 UNRESOLVABLE",
            listOf(ResolveFailureKind.UNRESOLVABLE),
            allowed,
        )
    }

    @Test
    fun `网络失败是唯一允许重试的一类`() {
        val retryable = ResolveFailureKind.values().filter {
            resolveFailureAction(it) == ResolveFailureAction.RETRY
        }
        assertEquals(listOf(ResolveFailureKind.NETWORK), retryable)
    }

    @Test
    fun `无版权也不跳歌 —— 提示用户去换音源 而不是替他跳过这首歌`() {
        assertEquals(
            ResolveFailureAction.STOP_AND_INFORM,
            resolveFailureAction(ResolveFailureKind.COPYRIGHT_GONE),
        )
        assertTrue(suggestsOtherSource(ResolveFailureKind.COPYRIGHT_GONE))
    }

    @Test
    fun `未登录与凭证过期不给换源提示 —— 在当前源登录一下就能解决`() {
        assertFalse(suggestsOtherSource(ResolveFailureKind.NEED_LOGIN))
        assertFalse(suggestsOtherSource(ResolveFailureKind.AUTH_EXPIRED))
        // 反过来，权益类失败给提示是有意义的（用户可能在另一家买了会员）。
        assertTrue(suggestsOtherSource(ResolveFailureKind.NEED_VIP))
        assertTrue(suggestsOtherSource(ResolveFailureKind.NEED_PURCHASE))
    }

    // ---------------------------------------------------------------- 重试闸（铁律 5）

    @Test
    fun `重试闸每首歌最多两次 且两次之间必须间隔`() {
        val gate = UrlRetryGate()
        gate.onNewSong("song-A")
        assertTrue(gate.requestRetry(1_000_000L))
        // 间隔不足 → 拒绝（但不消耗额度）。
        assertFalse(gate.requestRetry(1_000_100L))
        assertTrue(gate.requestRetry(1_000_000L + UrlRetryGate.MIN_INTERVAL_MS))
        // 额度用尽。
        assertFalse(gate.requestRetry(1_000_000L + 10 * UrlRetryGate.MIN_INTERVAL_MS))
        assertEquals(0, gate.remaining())
    }

    @Test
    fun `换歌清零重试额度 —— 下一首歌该有自己的两次机会`() {
        val gate = UrlRetryGate()
        gate.onNewSong("song-A")
        assertTrue(gate.requestRetry(0L))
        assertTrue(gate.requestRetry(UrlRetryGate.MIN_INTERVAL_MS))
        assertFalse(gate.requestRetry(2 * UrlRetryGate.MIN_INTERVAL_MS))
        gate.onNewSong("song-B")
        assertEquals(UrlRetryGate.MAX_ATTEMPTS_PER_SONG, gate.remaining())
        assertTrue(gate.requestRetry(3 * UrlRetryGate.MIN_INTERVAL_MS))
    }

    @Test
    fun `用户手动操作也清零 —— 用户重新点播放时不该被上一轮的额度挡住`() {
        val gate = UrlRetryGate()
        gate.onNewSong("song-A")
        gate.requestRetry(0L)
        gate.requestRetry(UrlRetryGate.MIN_INTERVAL_MS)
        assertFalse(gate.requestRetry(2 * UrlRetryGate.MIN_INTERVAL_MS))
        gate.onUserAction()
        assertTrue(gate.requestRetry(3 * UrlRetryGate.MIN_INTERVAL_MS))
    }

    // ---------------------------------------------------------------- ResolveOutcome 的不变量

    @Test
    fun `ResolveOutcome 的成功与失败在类型上互斥`() {
        val ok = ResolveOutcome.ok(
            SongUrlResult(url = "https://example.invalid/a.mp3", actualLevel = "higher"),
        )
        assertTrue(ok.isOk)
        assertEquals(null, ok.failure)
        assertEquals("https://example.invalid/a.mp3", ok.result?.url)

        val failed = ResolveOutcome.failed(ResolveFailureKind.NEED_VIP, MusicSource.QQMUSIC, 104003)
        assertFalse(failed.isOk)
        assertEquals(null, failed.result)
        assertEquals(ResolveFailureKind.NEED_VIP, failed.failure?.kind)
        assertEquals(104003, failed.failure?.rawCode)
    }

    @Test
    fun `诊断串不含凭证 —— 只有分类 音源 与业务码`() {
        val f = ResolveFailure(
            ResolveFailureKind.NEED_VIP,
            MusicSource.QQMUSIC,
            rawCode = 104003,
            rawMessage = "需要付费",
        )
        assertEquals("kind=NEED_VIP source=qqmusic code=104003", f.diag())
    }
}
