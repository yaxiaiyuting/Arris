/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.qq

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 进程持有 QQ 音乐扫码会话，使扫码浮层重建后可以继续显示同一张码。 */
internal object QqScanSession {
    private const val TAG = "QqScanSession"
    private const val QR_TTL_MS = 840_000L
    private const val EVENT_WAIT_MS = 5_000L

    enum class Status { LOADING, WAITING, SCANNED, EXPIRED, QR_UNAVAILABLE, FAILED, DONE }

    data class State(val status: Status, val bitmap: Bitmap? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionLock = Mutex()
    private val _state = MutableStateFlow<State?>(null)
    val state = _state.asStateFlow()
    private var job: Job? = null

    @MainThread
    fun restart() {
        if (_state.value?.status == Status.DONE) return
        job?.cancel()
        _state.value = State(Status.LOADING)
        job = scope.launch {
            // 连点刷新时，等上一条连接的 finally 收尾后再发下一张码。
            sessionLock.withLock {
                try {
                    runSession()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "扫码会话失败", e)
                    _state.value = State(Status.FAILED)
                }
            }
        }
    }

    @MainThread
    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = null
    }

    @MainThread
    fun consumeLogin(): Boolean {
        if (_state.value?.status != Status.DONE) return false
        dismiss()
        return true
    }

    private suspend fun runSession() {
        val code = QqApi.requestScanQrCode()
        val bitmap = code?.let {
            withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(it.png, 0, it.png.size) }
        }
        if (code == null || bitmap == null) {
            _state.value = State(Status.QR_UNAVAILABLE)
            return
        }
        _state.value = State(Status.WAITING, bitmap)
        val session = QqQrMqttSession(
            qrCodeId = code.qrCodeId,
            clientId = System.currentTimeMillis().toString() + (1000..9999).random(),
        )
        val status = try {
            awaitLogin(session, code.qrCodeId, bitmap)
        } finally {
            session.close()
        }
        _state.value = State(status, bitmap)
    }

    private suspend fun awaitLogin(session: QqQrMqttSession, qrCodeId: String, bitmap: Bitmap): Status {
        if (!session.open()) return Status.FAILED
        var lastPingAt = SystemClock.elapsedRealtime()
        val deadline = lastPingAt + QR_TTL_MS
        val pingEveryMs = (session.keepAliveSeconds() * 1000L / 2).coerceAtLeast(EVENT_WAIT_MS)
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val now = SystemClock.elapsedRealtime()
            if (now - lastPingAt >= pingEveryMs) {
                session.ping()
                lastPingAt = now
            }
            when (val event = session.awaitEvent(EVENT_WAIT_MS)) {
                null -> Unit
                QqQrMqttSession.Event.Scanned -> _state.value = State(Status.SCANNED, bitmap)
                QqQrMqttSession.Event.Expired -> return Status.EXPIRED
                QqQrMqttSession.Event.Canceled, is QqQrMqttSession.Event.Failed -> return Status.FAILED
                is QqQrMqttSession.Event.Credentials -> {
                    // QqClient 的请求可取消，返回主线程后再由 QqApi 提交凭证。
                    val attempt = QqApi.loginWithScanCode(qrCodeId, event.uin, event.token)
                    return if (attempt.outcome == QqPhoneLogin.LoginOutcome.OK && attempt.cookie != null) {
                        Status.DONE
                    } else {
                        Status.FAILED
                    }
                }
            }
        }
        return Status.EXPIRED
    }
}
