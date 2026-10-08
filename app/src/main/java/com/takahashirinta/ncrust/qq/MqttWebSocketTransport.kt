/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：MQTT 5.0 over WebSocket 的**传输层**（OkHttp WebSocket）。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * MQTT 5.0 over WebSocket 的传输层（v3.4.9）。
 *
 * ## 为什么不直接用 OkHttp 的 `newWebSocket` 就完事
 *
 * 因为**两个协议层不能混在一起**：WebSocket 给的是「消息」（一个帧可能是半条 MQTT 报文，
 * 也可能是三条），而 MQTT 给的是「报文」。把 `onMessage` 的每一次回调都当成一条报文，
 * 在服务端把 CONNACK 与一条 PUBLISH 拼进同一个帧时就会解析错位 ——
 * 而那种错位不会崩，只会**静默丢掉那条推送**（表现是「扫了码但界面不动」）。
 *
 * 所以这里只做一层薄薄的事：把收到的字节**按序累进一个缓冲区**，交给 [MqttCodec.parse]
 * 切出完整报文，切不出来的部分留着等下一片。
 *
 * ## 会话 vs 连接：**它们是两层**（v3.4.9 真机路径上踩过一次）
 *
 * 服务端会在第一条 CONNACK 上要求重定向（`SERVER_REFERENCE`，见 [QqQrMqttSession.open]），
 * 也就是**一次会话里可能建立多条 WebSocket 连接**。第一版把这两个生命周期写成了一个
 * （`incoming` 通道在连接关闭时被 `close()`），于是重定向时旧连接的回调把通道关掉、
 * 新连接的报文再也送不进来 —— 报错是 `SocketException: Socket closed`。
 *
 * 现在：**通道属于会话**（只有 [close] 才关），**连接属于 [connect]**（每次换一个
 * [Connection] 实例、连缓冲区一起换），[reset] 只清上一条连接的残留而不动通道。
 *
 * ## 与 [QqClient] 的关系：**独立通道**
 *
 * 与扫码登录的既有实现（[QqQrClient]）同样的理由：登录流程有它自己的生命周期，
 * 把它塞进业务请求那条通道会让两边的连接池、超时、Cookie 处理互相污染。
 * **只共享 OkHttp 这个库，不共享配置。**
 *
 * ## 为什么不用 OkHttp 的 `pingInterval`
 *
 * MQTT 有自己的 PINGREQ/PINGRESP（在**应用层**），WebSocket 也有 ping 帧（在**协议层**）。
 * 两者服务端不一定都认：MQTT 的 keep-alive 是**协议协商**出来的
 * （CONNACK 可以用 `SERVER_KEEP_ALIVE` 改掉我们请求的值），
 * 所以保活必须由 [QqQrMqttSession] 按协商结果发 PINGREQ，
 * 不能让 OkHttp 用固定间隔发 WS ping 顶替。
 */
internal class MqttWebSocketTransport(private val clientId: String) {

    internal companion object {
        const val TAG = "QqMqtt"
        const val HOST = "mu.y.qq.com"
        const val PORT = 443
        const val PATH = "/ws/handshake"

        /** 默认握手路径。给 [QqQrMqttSession] 在重定向循环里当起点用。 */
        fun defaultPath(): String = PATH

        /**
         * 握手头。**两个都是参考实现实际发过的**（`_connect_mobile_mqtt` 的 `headers`）——
         * 少了 `Origin` 的行为没有实测过，所以照发，不省。
         */
        const val ORIGIN = "https://y.qq.com"
        const val REFERER = "https://y.qq.com/"

        /** 收包缓冲的上限。超过它说明对面在灌我们没在消费的报文，宁可断开。 */
        const val MAX_BUFFER_BYTES = 1 shl 20
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 读超时**不用 OkHttp 的**：MQTT 的长连接本来就可能长时间没有数据
        // （等用户扫码），用 30 秒读超时会把一条正常的空闲连接掐掉。
        // 真正的超时由上层的 keep-alive（PINGREQ）与整体等待窗口负责。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 收到的每一条**完整 MQTT 报文**。**属于会话，不属于连接**（见 KDoc）。
     * 只有 [close] 才关它。
     */
    val incoming: Channel<MqttCodec.Frame> = Channel(Channel.UNLIMITED)

    /** 当前连接。`@Volatile`：回调在 OkHttp 的读线程上，[reset] 在协程线程上。 */
    @Volatile
    private var active: Connection? = null

    init {
        // clientId 目前只用于日志；协议上它写在 CONNECT 报文里（由 QqQrMqttSession 编码）。
        // 显式引用一次，免得「参数未使用」被顺手删掉 —— 删掉就没有任何地方能回答
        // 「这条连接是谁」。
        if (BuildConfig.DEBUG) Log.d(TAG, "transport created clientId=$clientId")
    }

    /**
     * 建立**一条**连接并等 WebSocket 握手完成。
     *
     * @param host 主机名。**保持 `mu.y.qq.com` 不变** —— 服务端给的 `SERVER_REFERENCE`
     *   是**路径段**而不是新主机（见 [qrMqttRedirectPath]）。
     * @param path 握手路径。默认 [PATH]；被重定向后是 `[PATH]/<serverReference>`。
     * @return 握手是否成功。**失败一律返回 false 而不是抛** —— 调用方的契约是
     *   「连不上就回落到别的登录方式」，异常在那条路上没有额外信息。
     */
    suspend fun connect(
        host: String = HOST,
        path: String = PATH,
        timeoutMs: Long = 15_000L,
    ): Boolean {
        val url = "wss://$host:$PORT$path"
        val request = Request.Builder()
            .url(url)
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            // OkHttp 自己会加 Sec-WebSocket-* 与 Upgrade/Connection，不要手写它们。
            .build()
        // 每次连接一个**新的** Connection（含自己的 opened / socket / 缓冲区 / 监听器）——
        // 重定向时旧连接的回调只会影响属于它的那个 Connection。
        val conn = Connection()
        active = conn
        val ws = http.newWebSocket(request, conn)
        conn.socket = ws
        val ok = try {
            withTimeout(timeoutMs) { conn.opened.await() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "WebSocket 握手超时：$url")
            runCatching { ws.cancel() }
            false
        }
        if (!ok) incoming.trySend(mqttDisconnectedFrame())
        return ok
    }

    /** 发一条已经编码好的 MQTT 报文。返回是否真的发出去了。 */
    fun send(packet: ByteArray): Boolean = active?.socket?.send(packet.toByteString()) ?: false

    /**
     * 换一条连接（跟随 `SERVER_REFERENCE` 重定向时用）。
     *
     * 必须**连缓冲区一起换**：旧连接上残留的字节（那条 CONNACK 之后服务端又推的东西）
     * 会被新连接上的等待循环读到 —— 表现是「重定向之后收到的第一条『推送』
     * 其实是上一条连接的残渣」。**不动 [incoming]**（它属于会话，见 KDoc）。
     */
    fun reset() {
        active?.let { runCatching { it.socket?.cancel() } }
        active = null
        while (incoming.tryReceive().isSuccess) {
            // 故意空转：把上一条连接的未消费报文丢掉
        }
    }

    fun close() {
        active?.let { runCatching { it.socket?.close(1000, null) } }
        active = null
        incoming.close()
    }

    /**
     * **一条** WebSocket 连接（一次握手）。会话级的 [incoming] 由它写入。
     *
     * 它同时负责「把字节流切成 MQTT 报文」—— 放在这里而不是外层，是因为缓冲区
     * 必须**跟着连接走**（见 [reset] 的注释）。
     */
    private inner class Connection : WebSocketListener() {

        val opened = CompletableDeferred<Boolean>()

        @Volatile
        var socket: WebSocket? = null

        private val slicer = FrameSlicer()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WS 已连接")
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            for (frame in slicer.feed(bytes.toByteArray())) {
                if (!incoming.trySend(frame).isSuccess) return
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // MQTT over WebSocket **必须**用二进制帧。收到文本帧说明对面不是 MQTT，
            // 直接忽略比把它当字节解析安全（把 UTF-8 文本当 MQTT 报文会解出垃圾）。
            Log.w(TAG, "收到文本帧（非预期），忽略 len=${text.length}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WS closing code=$code reason=$reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, "WS closed code=$code reason=$reason")
            opened.complete(false)
            // ⚠️ **不关 incoming**：通道属于会话，后面可能还有一条重定向连接要用它。
            // 只告诉等待方「这条连接没了」。
            incoming.trySend(mqttDisconnectedFrame())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t::class.java.simpleName} ${t.message} http=${response?.code}")
            opened.complete(false)
            incoming.trySend(mqttDisconnectedFrame())
        }
    }

    /**
     * 把任意切分的字节片还原成**一整条 MQTT 报文**。
     *
     * 独立成一个小类而不是散在回调里：它的状态（尾巴）必须与连接一一对应，
     * 而「多条报文挤在一帧里」与「一条报文跨两帧」两种情况都要能过 ——
     * 后者在推送较大时真的会发生（实测那条 `cookies` 推送 91 字节，
     * 而服务端对订阅确认的响应也走同一条通道）。
     */
    private class FrameSlicer {
        private val tail = ByteArrayOutputStream(1024)

        @Synchronized
        fun feed(chunk: ByteArray): List<MqttCodec.Frame> {
            tail.write(chunk)
            if (tail.size() > MAX_BUFFER_BYTES) {
                Log.w(TAG, "MQTT 收包缓冲超过 ${MAX_BUFFER_BYTES}B，丢弃这一条连接")
                tail.reset()
                return emptyList()
            }
            val bytes = tail.toByteArray()
            val frames = ArrayList<MqttCodec.Frame>(2)
            var offset = 0
            while (true) {
                val parsed = try {
                    MqttCodec.parse(bytes, offset)
                } catch (e: Exception) {
                    // 解析异常 = 这条连接上的字节流已经不可信（属性错位之类）。
                    // 继续读只会解出更多垃圾，所以清掉尾巴、让上层按「连接没了」处理。
                    Log.w(TAG, "MQTT 报文解析失败", e)
                    tail.reset()
                    return frames
                } ?: break
                offset += parsed.second
                frames += parsed.first
            }
            if (offset > 0) {
                tail.reset()
                if (offset < bytes.size) tail.write(bytes, offset, bytes.size - offset)
            }
            return frames
        }
    }
}

/**
 * 「连接没了」的哨兵报文。
 *
 * 为什么用一条**构造出来的 DISCONNECT**，而不是 `Channel.close()`：通道属于会话
 * （见 [MqttWebSocketTransport] 的 KDoc），关掉它会让重定向后的新连接什么都送不进来。
 * 而 `Channel.receive()` 需要一个值才能被唤醒 —— 一条 DISCONNECT 在语义上正好是
 * 「这条连接结束了」，且 [QqQrMqttSession.awaitEvent] 已经把它当失败处理。
 *
 * 它是一条**真实存在**的报文类型（不是发明一种新消息）：`MqttCodec.parse` 能正常
 * 切出它，接收侧不需要任何特殊分支。
 */
private fun mqttDisconnectedFrame(): MqttCodec.Frame =
    MqttCodec.Frame(MqttCodec.DISCONNECT, 0, ByteArray(0))
