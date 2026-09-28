/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站扫码登录的**状态机**（有界轮询 / 取消不是失败 / 过期可区分）。
 */

package com.takahashirinta.ncrust.bili

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * B 站扫码登录的状态机（v3.2.0 · P1）。
 *
 * ## 它复用了 [com.takahashirinta.ncrust.qq.QqQrLogin] 的哪几条设计（逐条）
 *
 * | # | `QqQrLogin` 的设计 | 本类的落点 |
 * |---|---|---|
 * | 1 | **状态枚举语义同构**：`WAITING` / `SCANNED` / `CONFIRMED` / `EXPIRED` / `FAILED`，过期是**独立**状态（用户要能点「刷新二维码」） | [State] 的五个状态逐字同名同义，另外补 `IDLE`（还没开始）与 `LOADING`（正在申请二维码）两个**入口态**、`CANCELLED`（取消不是失败）—— 那三个是 `QqQrLogin` 由 UI 的 `LaunchedEffect` 承担的部分（`QqQrLoginDialog` 的 `qr == null` / `loadFailed`），本版把它们收进状态机 |
 * | 2 | **码表显式**：`QqQrLogin.statusOf` 把 `65/66/67/0/其它` 映射成状态，且**注释里钉死了 65/67 曾经写反的教训**（写反的后果不是文案难看，而是用户一扫就显示"已过期"、永远走不到确认） | [BiliAuthApi.POLL_CODE_EXPIRED] / [BiliAuthApi.POLL_CODE_PENDING] / [BiliAuthApi.POLL_CODE_SCANNED] / [BiliAuthApi.POLL_CODE_SUCCESS] 四个常量 + [BiliAuthApi.parseQrPoll] 的 `when`，`else` 分支落 `FAILED` 而不是"当成未扫码继续轮询"（后者会让一个未知码变成永远转圈） |
 * | 3 | **「服务端明确回绝」与「网络抖动」分开**：`PollResult.Unavailable` 重试无意义（恒定 403 被显示成"网络不稳定，仍在重试…"是 v2.1.0 的用户可见 bug），`PollResult.NetworkError` 下一轮值得再试 | [BiliQrPoll.Failed.Reason] 三档：`MALFORMED`（非 JSON / 缺 `data.code`）、`SERVER`（不认识的业务码 / 成功态却没凭据）**重试无意义**；`NETWORK` 是抖动。连续计数分开（[MAX_CONSECUTIVE_UNAVAILABLE] = 3、[MAX_CONSECUTIVE_NETWORK_ERRORS] = 5），与 `QqQrLoginDialog` 的 `unavailableStreak` / `networkStreak` 同一个形状 |
 * | 4 | **有界轮询**：`QqQrLoginDialog` 用 `deadline = now + QR_TTL_SECONDS` 的 `while` + 固定 `delay(POLL_INTERVAL_MS)`，绝不无限重试 | [start] 的循环：总时长 [QR_TTL_MS]（实测 180s）、间隔 [POLL_INTERVAL_MS]、次数上限 [MAX_POLLS]，**三个上限任一先到就停**；每个上限都是一条独立的判据（时钟被篡改/`sleep` 被替换都不会变成死循环） |
 * | 5 | **「取消不是失败」**：`QqQrClient`/`QqQrLogin` 的每个 `catch` 都把 `CancellationException` 原样抛出（v2.1.0 吞掉它会让「超时」显示成「真的 0 条」）；`QqQrLogin.PollResult` 里没有"取消"这一档 | [cancel] → [State.CANCELLED]（与 `FAILED` 分开）；[start] / [pollOnce] 的每一个 `catch` 都 `throw e`；`sleep`（默认 `delay`）被取消时异常**穿透**出去，绝不落成 `FAILED` |
 * | 6 | **常量显式**：`QR_TTL_SECONDS` / `QR_SIZE_PARAM` 是 `const val` 而不是散落的字面量 | [POLL_INTERVAL_MS] / [QR_TTL_MS] / [MAX_POLLS] / [QR_TTL_SECONDS] / 两个连续失败上限全部 `const val`，单测逐条钉住它们的**关系**（`MAX_POLLS * POLL_INTERVAL_MS` 覆盖 TTL） |
 * | 7 | **失败不吞**：`QqQrLogin.parsePtuiCb` 解析不出返回 null，但**由调用方决定**是继续还是降级（解析失败 ≠ 登录失败） | [BiliQrPoll.Failed] 里带 `detail`（不含凭据），[lastFailure] 对 UI 可见 —— 界面能显示"为什么在重试"，而不是只有一个转圈 |
 *
 * ## 它**没有**复用的部分（必须换）
 *
 * - QQ 侧要 `ptqrtoken = hash33(qrsig)`、要 PNG 图片、要内存 CookieJar，B 站**全都不需要**：
 *   二维码内容是**一个 URL 字符串**（`data.url`），轮询只要 `qrcode_key` 一个 query 参数；
 * - 凭据载体完全不同：QQ 走 WebView 换票（`ptuiCB('0',…)` 给跳转地址），B 站直接下发
 *   `Set-Cookie` + `crossDomain` URL（两个载体都要读，见 [BiliCredential.merge]）。
 *
 * ## 为什么是 `class` 而不是 `object`
 *
 * 状态机必须能在 JVM 单测里被**完整驱动**：注入假的 [BiliAuthEndpoints]、假的时钟、假的
 * `sleep`，就能在没有手机、没有账号、没有网络的情况下走完「未扫码 → 已扫码 → 成功」
 * 与「超时 → 过期」「取消」「连续失败降级」全部路径。
 * `object` 会把这些状态变成进程级单例，用例之间互相污染。
 *
 * ## 线程模型
 *
 * 状态字段是 `@Volatile`：`start()` 跑在调用方的协程里（UI 是 `LaunchedEffect`/`viewModelScope`），
 * 而 `cancel()` / [state] 的读取来自 UI 线程。**本类自己不做任何线程切换** ——
 * 真正阻塞的网络在 [BiliAuthApi] 里已经换到 `Dispatchers.IO`（P0-C 的纪律）。
 *
 * ## 它不知道 `SharedPreferences` 的存在
 *
 * 落盘只有一处：[finishLogin]（`Context` 也只出现在那里，且只传给 [BiliAuthStore]）。
 * 这样状态机本身是纯逻辑，单测不需要 `Context`。
 */
class BiliQrLogin(
    private val api: BiliAuthEndpoints = BiliAuthApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * 登录流程的状态。前五个与 [com.takahashirinta.ncrust.qq.QqQrLogin.QrStatus] 同名同义
     * （见类文档第 1 条），后三个是扫码状态机自己需要的入口/终态。
     */
    enum class State {
        /** 还没开始（[reset] 之后）。 */
        IDLE,

        /** 正在申请二维码 —— 界面上这是"加载中"，**不是**"等待扫码"。 */
        LOADING,

        /** `86101`：还没被扫。 */
        WAITING,

        /** `86090`：已扫码，等手机端确认。 */
        SCANNED,

        /** `0`：拿到凭据，[credential] 里就是它。 */
        CONFIRMED,

        /** `86038` 或本地上限先到：**二维码已失效，需要用户刷新**。 */
        EXPIRED,

        /** 重试无意义的一类失败，或连续失败到上限。 */
        FAILED,

        /** 用户主动取消。**不是失败**（[State.FAILED] 的文案与动作都不同）。 */
        CANCELLED,
    }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 成功后的凭据（未成功为 null）。落盘由 [finishLogin] 负责。 */
    @Volatile
    var credential: BiliCredential? = null
        private set

    /** 当前这张二维码（`url` 给 UI 渲染，`qrcodeKey` 给轮询）。 */
    @Volatile
    var qrCode: BiliQrCode? = null
        private set

    /** 最近一次失败（成功/未扫码时清空）。UI 可以据此显示**为什么**在重试。 */
    @Volatile
    var lastFailure: BiliQrPoll.Failed? = null
        private set

    /** 已经发出的轮询次数（上限 [MAX_POLLS]，单测直接钉它）。 */
    @Volatile
    var pollCount: Int = 0
        private set

    @Volatile
    private var cancelled: Boolean = false

    // ---------------------------------------------------------------- 状态机 ----

    /**
     * 申请一张新二维码，把状态推到 [State.WAITING]（**不发任何轮询**）。
     *
     * 需要它的理由是 [pollOnce]：想自己控制轮询节奏的 UI 必须先有 `qrcode_key`。
     * [start] 就是「[prepare] + 有界轮询」，两步都可以单独用。
     *
     * @return [State.WAITING]（成功拿到二维码）或 [State.FAILED]（申请失败）。
     */
    suspend fun prepare(onState: ((State) -> Unit)? = null): State {
        // 幂等：成功之后再 prepare 不会重新申请二维码。
        if (state == State.CONFIRMED) return state
        cancelled = false
        credential = null
        lastFailure = null
        pollCount = 0
        emit(State.LOADING, onState)

        val code = try {
            api.qrGenerate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "申请二维码失败", e)
            null
        }
        if (code == null) {
            emit(State.FAILED, onState)
            return State.FAILED
        }
        qrCode = code
        emit(State.WAITING, onState)
        return State.WAITING
    }

    /**
     * 走完一整轮扫码登录：申请二维码 → 有界轮询到终态。
     *
     * @param onState 每次状态变化回调一次（UI 拿它写自己的 Compose state）。
     *   回调在**调用者的线程**上同步执行，必须轻量。
     * @return 终态：[State.CONFIRMED] / [State.EXPIRED] / [State.FAILED] / [State.CANCELLED]，
     *   或者已经确认成功时原样返回 [State.CONFIRMED]（**幂等：不再发任何请求**）。
     *
     * ⚠️ 协程被取消时抛 [CancellationException]（**原样抛出**，不落成 [State.FAILED]）。
     *
     * ⚠️ 幂等只针对 [State.CONFIRMED]（成功不可重入）。在 [State.EXPIRED] / [State.FAILED] /
     * [State.CANCELLED] 之后再调 [start] 表示**「再来一张」**：会重新申请二维码
     * （这正是用户点「重试 / 刷新二维码」的语义）。想回到初始态用 [reset]。
     */
    suspend fun start(onState: ((State) -> Unit)? = null): State {
        if (prepare(onState) != State.WAITING) return state
        val code = qrCode ?: return state

        val deadline = clock() + QR_TTL_MS
        var unavailableStreak = 0
        var networkStreak = 0

        while (true) {
            if (cancelled) return emitTerminal(State.CANCELLED, onState)
            // 三个上限，任一先到就停（有界轮询）。
            if (pollCount >= MAX_POLLS || clock() >= deadline) {
                return emitTerminal(State.EXPIRED, onState)
            }
            // `sleep` 被取消时异常穿透出去 —— 取消不在这里被翻译成失败。
            sleep(POLL_INTERVAL_MS)
            if (cancelled) return emitTerminal(State.CANCELLED, onState)
            pollCount++

            val result = poll(code.qrcodeKey)
            if (result is BiliQrPoll.Failed) {
                when (result.reason) {
                    BiliQrPoll.Failed.Reason.NETWORK -> networkStreak++
                    BiliQrPoll.Failed.Reason.MALFORMED,
                    BiliQrPoll.Failed.Reason.SERVER,
                    -> unavailableStreak++
                }
            } else {
                networkStreak = 0
                unavailableStreak = 0
            }
            val next = apply(result, tolerateNetwork = true, networkStreak = networkStreak, unavailableStreak = unavailableStreak)
            if (next == State.CONFIRMED || next == State.EXPIRED || next == State.FAILED) {
                return emitTerminal(next, onState)
            }
            emitIfChanged(next, onState)
        }
    }

    /**
     * 只轮询一次（给想自己控制节奏的 UI）。
     *
     * 单步语义：网络失败**直接**落 [State.FAILED]（连续容错是 [start] 的职责）。
     * 已经是终态时**原样返回、不发请求**（幂等）。
     */
    suspend fun pollOnce(): State {
        if (isTerminal(state)) return state
        val key = qrCode?.qrcodeKey
        if (key.isNullOrBlank()) return state
        pollCount++
        val next = apply(poll(key), tolerateNetwork = false, networkStreak = 0, unavailableStreak = 0)
        emitIfChanged(next, null)
        return next
    }

    /**
     * 用户取消。**这不是失败**：[state] 变成 [State.CANCELLED]，正在跑的 [start] 会在
     * 下一个检查点（`sleep` 之后）返回它。
     *
     * 同时会取消调用方协程的常规做法仍然有效（`LaunchedEffect` 退出 ⇒ `delay` 抛
     * `CancellationException`），两条路都通向"停下来"，但**都不经过 [State.FAILED]**。
     */
    fun cancel() {
        cancelled = true
        if (!isTerminal(state)) state = State.CANCELLED
    }

    /** 回到起点，可以重新开始（UI 的「刷新二维码」按钮）。 */
    fun reset() {
        cancelled = false
        credential = null
        qrCode = null
        lastFailure = null
        pollCount = 0
        state = State.IDLE
    }

    /**
     * 登录成功后的收尾：**凭据落盘 → 拉一次 `nav` 资料 → 资料落盘**。
     *
     * 这是本类唯一需要 `Context` 的地方（只用于 [BiliAuthStore]）。
     *
     * 两个**有意的**取舍：
     * 1. 没有成功凭据时返回 null 且**不发请求、不落盘**；
     * 2. 刚落盘就被 `nav` 判成未登录（`code:-101`）⇒ **把凭据清掉**。
     *    留着它会让界面显示「已登录」而每一个请求都 `-101` —— 那正是
     *    `QqPhoneLogin.cookieFromCredential` 那条纪律说的「宁可让登录明确失败，
     *    也不要落一份缺票据的 cookie」。
     */
    suspend fun finishLogin(context: android.content.Context): BiliNavResult? {
        val cred = credential ?: return null
        BiliAuthStore.save(context, cred)
        val result = api.navWithCookie(cred.cookieHeader())
        when (result) {
            is BiliNavResult.LoggedIn -> BiliAuthStore.saveProfile(context, result.nav.profile())
            BiliNavResult.NotLoggedIn -> BiliAuthStore.clear(context)
            is BiliNavResult.Failed, is BiliNavResult.Unknown -> Unit
        }
        Log.i(TAG, "finishLogin: ${result::class.java.simpleName}")
        return result
    }

    // ---------------------------------------------------------------- 内部 ----

    /** 一次轮询（异常在这里被折叠成 [BiliQrPoll.Failed]，**取消除外**）。 */
    private suspend fun poll(key: String): BiliQrPoll =
        try {
            api.qrPoll(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.NETWORK, e.javaClass.simpleName)
        }

    /**
     * 把一次轮询结果落到状态上。
     *
     * @param tolerateNetwork [start] 传 true：连续失败在各自的上限内**保持原状态**
     *   （不闪 FAILED），到上限才落 [State.FAILED]。
     */
    private fun apply(
        result: BiliQrPoll,
        tolerateNetwork: Boolean,
        networkStreak: Int,
        unavailableStreak: Int,
    ): State = when (result) {
        BiliQrPoll.Pending -> {
            lastFailure = null
            State.WAITING
        }
        BiliQrPoll.Scanned -> {
            lastFailure = null
            State.SCANNED
        }
        BiliQrPoll.Expired -> State.EXPIRED
        is BiliQrPoll.Success -> {
            lastFailure = null
            credential = result.credential
            State.CONFIRMED
        }
        is BiliQrPoll.Failed -> {
            lastFailure = result
            val tolerated = tolerateNetwork && when (result.reason) {
                BiliQrPoll.Failed.Reason.NETWORK -> networkStreak < MAX_CONSECUTIVE_NETWORK_ERRORS
                BiliQrPoll.Failed.Reason.MALFORMED,
                BiliQrPoll.Failed.Reason.SERVER,
                -> unavailableStreak < MAX_CONSECUTIVE_UNAVAILABLE
            }
            if (tolerated) state else State.FAILED
        }
    }

    private fun isTerminal(s: State): Boolean =
        s == State.CONFIRMED || s == State.EXPIRED || s == State.FAILED || s == State.CANCELLED

    private fun emit(next: State, onState: ((State) -> Unit)?) {
        state = next
        onState?.invoke(next)
    }

    private fun emitIfChanged(next: State, onState: ((State) -> Unit)?) {
        if (state != next) emit(next, onState)
    }

    private fun emitTerminal(next: State, onState: ((State) -> Unit)?): State {
        emitIfChanged(next, onState)
        return next
    }

    companion object {
        private const val TAG = "BiliQrLogin"

        /** 轮询间隔。与 QQ/网易云两侧一致（2 秒）。 */
        const val POLL_INTERVAL_MS = 2_000L

        /** 二维码有效期（秒）。**实测**：同一个 key 在 +178s 还是 `86101`、+189s 已是 `86038` ⇒ 180s。 */
        const val QR_TTL_SECONDS = 180

        /** 总时长上限（毫秒）。 */
        const val QR_TTL_MS = QR_TTL_SECONDS * 1000L

        /**
         * 轮询次数上限。**与 [QR_TTL_MS] / [POLL_INTERVAL_MS] 对齐**：
         * 90 × 2s = 180s。单测钉住这个关系 —— 改了间隔却忘了改次数，
         * 表现就是「二维码还没过期客户端就先报过期」（或反过来多打 30 秒空请求）。
         */
        const val MAX_POLLS = 90

        /** 连续「服务端回绝/看不懂」上限。对齐 `QqQrLoginDialog.DEGRADE_AFTER_UNAVAILABLE = 3`。 */
        const val MAX_CONSECUTIVE_UNAVAILABLE = 3

        /** 连续「网络抖动」上限。比上面宽：抖动是真的会自己好的。 */
        const val MAX_CONSECUTIVE_NETWORK_ERRORS = 5
    }
}
