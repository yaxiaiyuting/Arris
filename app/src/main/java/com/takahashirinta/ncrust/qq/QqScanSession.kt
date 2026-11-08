/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.qq

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「QQ 音乐 App 扫码」的会话宿主 —— **活在进程里，不活在 composition 里**。
 *
 * ## 为什么必须有这个东西（4.0.16 修的 bug）
 *
 * 这条路要同时做两件事：用 MQTT/WebSocket 等手机端确认的推送，以及把推送里的
 * `(uin, token)` 换成 cookie + 续期凭证。原来整段跑在 `LaunchedEffect` 里，
 * 于是有两个必死的场景：
 *
 * 1. **扫码必须把 Arris 切到后台**（QQ 音乐要占前台，或者开小窗）。切后台后
 *    系统/vendor 省电会把 WebSocket 收掉 → `awaitEvent` 收到 `Failed` → 界面显示失败。
 * 2. **Activity 被回收**（ColorOS 上切走一会儿就会）→ composition 销毁 →
 *    `LaunchedEffect` 被取消 → `finally` 里 `session.close()` 把还在等确认的连接断掉。
 *    用户确认完切回来：浮层没了、二维码重画成新的一张、上一个 `qrCodeId` 已经作废
 *    —— 表现就是「扫了、也确认了，回来还是没登录」。
 *
 * 所以连接与轮询搬到这里：进程级 [scope]，UI 只负责渲染 [state]。
 * 只要进程活着，切后台、切回来、Activity 重建都不影响这条连接；
 * 凭证到达时**不需要界面在场**就能落盘（[QqApi.loginWithScanCode] 自带持久化）。
 *
 * 停止条件只有四个：拿到凭证、二维码过期、用户取消/关闭、连接明确失败。
 */
object QqScanSession {

    private const val TAG = "QqScanSession"

    /** 二维码寿命。与服务端 `expiresIn`（实测 900 秒）同一量级，留出刷新余量。 */
    private const val QR_TTL_MS = 840_000L

    /** 单次「等推送」窗口：心跳要在这个协程里发，5 秒粒度对 ~22 秒的心跳足够细。 */
    private const val EVENT_WAIT_MS = 5_000L

    enum class Status { LOADING, WAITING, SCANNED, EXPIRED, FAILED, DONE }

    data class State(
        val bitmap: Bitmap? = null,
        val status: Status = Status.LOADING,
        val loadFailed: Boolean = false,
        /** 只在「凭证已落盘」时置 true；UI 看到它就去刷新登录态。 */
        val loggedIn: Boolean = false,
        /** 失败时的可读原因（没有就是 null）。 */
        val failure: Failure? = null,
    ) {
        /** 正在跑（有连接或正在取码）。UI 用它决定要不要显示"等待扫码"。 */
        val running: Boolean
            get() = status == Status.LOADING || status == Status.WAITING || status == Status.SCANNED
    }

    enum class Failure {
        /** 取二维码失败（网络/服务端拒绝）。 */
        QR_UNAVAILABLE,

        /** MQTT 连接建不起来。 */
        SOCKET_UNAVAILABLE,

        /** 换凭证被服务端明确回绝（设备数超限、参数错…）。 */
        TICKET_REJECTED,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** UI 打开浮层时调用。已经在跑就不重开 —— 切后台再切回来不该换二维码。 */
    fun ensureStarted() {
        if (job?.isActive == true) return
        restart()
    }

    /** 显式换一张二维码（用户点「刷新二维码」，或上次失败后重试）。 */
    fun restart() {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            runLoop()
        }
    }

    /** 用户关掉浮层 / 走别的登录方式时调用。 */
    fun stop() {
        job?.cancel()
        job = null
        _state.value = State(status = Status.WAITING)
    }

    /** 登录态刷新完毕后由 UI 调用，避免下次进来又看到 DONE。 */
    fun acknowledgeLogin() {
        stop()
    }

    private suspend fun CoroutineScope.runLoop() {
        _state.value = State(status = Status.LOADING)
        val code = QqApi.requestScanQrCode()
        if (code == null) {
            Log.w(TAG, "requestScanQrCode 失败")
            _state.value = State(status = Status.FAILED, loadFailed = true, failure = Failure.QR_UNAVAILABLE)
            return
        }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(code.png, 0, code.png.size)
        if (bitmap == null) {
            _state.value = State(status = Status.FAILED, loadFailed = true, failure = Failure.QR_UNAVAILABLE)
            return
        }
        _state.value = State(bitmap = bitmap, status = Status.WAITING)

        // MQTT 会话的生命周期严格包在这个 try/finally 里：协程结束（成功/过期/取消）
        // 时连接必须一起断，否则会留下一条没人消费推送、还在按 keep-alive 发心跳的连接。
        val session = QqQrMqttSession(
            qrCodeId = code.qrCodeId,
            clientId = System.currentTimeMillis().toString() + (1000..9999).random().toString(),
        )
        try {
            if (!session.open()) {
                Log.w(TAG, "MQTT open 失败")
                _state.update { it.copy(status = Status.FAILED, failure = Failure.SOCKET_UNAVAILABLE) }
                return
            }
            val deadline = System.currentTimeMillis() + QR_TTL_MS
            var lastPingAt = System.currentTimeMillis()
            val pingEveryMs = (session.keepAliveSeconds() * 1000L / 2L).coerceAtLeast(5_000L)

            while (isActive && System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() - lastPingAt >= pingEveryMs) {
                    session.ping()
                    lastPingAt = System.currentTimeMillis()
                }
                when (val event = session.awaitEvent(EVENT_WAIT_MS)) {
                    null -> Unit
                    QqQrMqttSession.Event.Scanned ->
                        _state.update { it.copy(status = Status.SCANNED) }

                    QqQrMqttSession.Event.Canceled ->
                        return finish(Status.FAILED, Failure.SOCKET_UNAVAILABLE)

                    QqQrMqttSession.Event.Expired -> return finish(Status.EXPIRED, null)

                    is QqQrMqttSession.Event.Failed -> {
                        Log.w(TAG, "MQTT 失败：${event.detail}")
                        return finish(Status.FAILED, Failure.SOCKET_UNAVAILABLE)
                    }

                    is QqQrMqttSession.Event.Credentials -> {
                        val attempt = QqApi.loginWithScanCode(
                            qrCodeId = code.qrCodeId,
                            uin = event.uin,
                            token = event.token,
                        )
                        return if (attempt.outcome == QqPhoneLogin.LoginOutcome.OK && attempt.cookie != null) {
                            Log.i(TAG, "扫码登录成功，凭证已落盘")
                            _state.update { State(bitmap = it.bitmap, status = Status.DONE, loggedIn = true) }
                        } else {
                            finish(Status.FAILED, Failure.TICKET_REJECTED)
                        }
                    }
                }
            }
            finish(Status.EXPIRED, null)
        } finally {
            session.close()
        }
    }

    private fun finish(status: Status, failure: Failure?) {
        _state.update { it.copy(status = status, failure = failure) }
    }

    private fun MutableStateFlow<State>.update(block: (State) -> State) {
        value = block(value)
    }
}
