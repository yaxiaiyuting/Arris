/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 fork 整体以 GPLv3 分发。
 *
 * v3.4.9：QQ 音乐官方 App 扫码登录的 MQTT 会话（CONNECT → SUBSCRIBE → 等推送）。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * QQ 音乐官方 App 扫码的 MQTT 会话（v3.4.9）。
 *
 * ## 它换来了什么（这个类存在的**唯一**理由）
 *
 * 原实现（`QqQrLoginDialog`）走 QQ 互联扫码，最后一步交给 WebView 让 `y.qq.com`
 * 自己去换 `qqmusic_key` —— 拿到的**只有 cookie**。而 cookie 里没有
 * `refreshKey` / `refreshToken`（见 [QqRefreshCredential] 的能力边界表），
 * 所以那条路的登录态**不可续期**，一周后照样要重登。
 *
 * 这条路的最后一步不一样：服务端会把 `qqmusic_uin` 与 `qqmusic_key` 推给我们，
 * 我们再拿它们去 `Login`（`tmeLoginType = 6`）换**完整凭证 JSON** ——
 * 那份 JSON 里就有续期凭证，**直接复用本版已经写好的 `QqTokenRefresher`**。
 *
 * ## 协议（全部来自参考实现的源码，不是猜的）
 *
 * ```
 * ① CreateQRCode                        → qrCodeID（91 字符）+ 二维码 PNG(base64)
 * ② wss://mu.y.qq.com/ws/handshake
 *    CONNECT  AUTH_METHOD="pass"
 *             user props: tmeAppID=qqmusic, business=management,
 *                         hashTag=<qrCodeID>, clientTag=management.user, userID=<qrCodeID>
 *    SUBSCRIBE management.qrcode_login/{qrCodeID}   qos=0
 *              user props: authorization=tmelogin, pubsub=unicast
 * ③ 等服务端 PUBLISH（user property `type`）：
 *      scanned / canceled / timeout / loginFailed / cookies
 *    `cookies` 的 payload 里是 {cookies:{qqmusic_uin:{value},qqmusic_key:{value}}}
 * ④ Login(param={musicid, qrCodeID, token=<qqmusic_key>}, comm={tmeLoginType:6})
 *    → 完整凭证 JSON（含 refreshKey）
 * ```
 *
 * ## 为什么裸 TCP 不行（实测，别再试第二次）
 *
 * `mu.y.qq.com:443` 上用裸 MQTT over TCP 发 CONNECT，回的是
 * `HTTP/1.1 400 Bad Request`（nginx）—— 服务端只认 WebSocket。
 * 所以 Paho Android 那类只做 TCP 的 MQTT 库在这里连不上。
 *
 * ## 一处**刻意不做**的事：重连
 *
 * 扫码是一个**十几秒的交互**，连接断了用户就在旁边看着。自动重连会引入
 * 「重连后 qrCodeID 还是不是同一个」这个新问题（话题名绑在 qrCodeID 上），
 * 而它的正确答案只有服务端知道。所以这里断线即失败、由界面提示重新出码 ——
 * 与既有扫码路径的降级口径一致（`QqQrLogin.PollResult.Unavailable` 那条）。
 */
internal class QqQrMqttSession(
    private val qrCodeId: String,
    private val clientId: String,
) {

    private companion object {
        const val TAG = "QqQrMqtt"

        /** 请求的 keep-alive（秒）。**CONNACK 可以用 `SERVER_KEEP_ALIVE` 改掉它**。 */
        const val REQUESTED_KEEP_ALIVE = 45

        /** 等 CONNACK / SUBACK 的超时。两者都是「发出去就该立刻回」的报文。 */
        const val HANDSHAKE_TIMEOUT_MS = 15_000L

        /**
         * 最多跟随几次重定向。
         *
         * 参考实现用 5（`max_redirects` 的默认值），这里取 3 ——
         * 扫码是十几秒的交互，跟在重定向后面转三次以上还不如直接告诉用户重试。
         * 上限存在的意义是**防环**（服务端给出一个指向自己的 reference 时不能无限重连）。
         */
        const val MAX_REDIRECTS = 3

        /** 订阅的话题名，见文件末尾的 [qrLoginTopicOf]。 */
        fun topicOf(qrCodeId: String): String = qrLoginTopicOf(qrCodeId)
    }

    /** 一条推送的语义。 */
    sealed class Event {
        /** `scanned`：手机已扫，等用户在手机上确认。 */
        data object Scanned : Event()

        /** `canceled`：用户在手机上取消了。 */
        data object Canceled : Event()

        /** `timeout`：二维码过期。 */
        data object Expired : Event()

        /** `loginFailed`：服务端拒绝（payload 里有原因，只进日志）。 */
        data class Failed(val detail: String) : Event()

        /** `cookies`：确认成功，带着换凭证要用的 uin 与 token。 */
        data class Credentials(val uin: String, val token: String) : Event()
    }

    private val transport = MqttWebSocketTransport(clientId)

    /** 服务端协商后的 keep-alive（CONNACK 没给就用我们请求的）。 */
    @Volatile
    private var keepAliveSeconds: Int = REQUESTED_KEEP_ALIVE

    /**
     * 「SUBACK 之前先到的推送」。`null` = 没有。
     *
     * 必须留住它：那可能是 `cookies`（用户手速极快时）或 `scanned`，
     * 丢掉它就意味着「扫了码但界面不动，直到下一次推送或超时」。
     */
    private var pendingEarlyEvent: Event? = null

    /** 订阅用的包标识。恒为 1：这条连接上只有一次订阅。 */
    private val subscribePacketId = 1

    /**
     * 建立连接、发 CONNECT、等 CONNACK、发 SUBSCRIBE、等 SUBACK。
     *
     * ## 必须跟随 `SERVER_REFERENCE` 重定向（v3.4.9 实测）
     *
     * **直连 `mu.y.qq.com/ws/handshake` 的第一条 CONNACK 一定不是成功**：
     * 服务端回 `reasonCode = 0x9D`（Server moved）并在属性里给出一个
     * `SERVER_REFERENCE`，真正接受这条会话的是**那台节点**。实测抓到的完整响应：
     *
     * ```
     * 20 59 00 9d 56                       CONNACK, reason=0x9D, props len=86
     *    26 0006 "server"    0013 "11.168.20.203_29001"
     *    26 0009 "channelID" 0013 "2108223752734814208"
     *    1c 0014 "11.154.131.152:29001"   ← SERVER_REFERENCE
     * ```
     *
     * 而它的用法**不是**「换一台主机」：参考实现把 `SERVER_REFERENCE`
     * 当作**路径段**拼在原来的路径后面（`_build_redirect_path`），
     * 也就是重连到 `wss://mu.y.qq.com/ws/handshake/11.154.131.152:29001` ——
     * 由服务端按这个段把连接路由到对应节点。
     *
     * 这条踩坑的代价很大：不跟随重定向的表现是**「CONNACK 被拒」**
     * （而它其实是一次正常的指路），用户看到的是「扫码登录失败」。
     *
     * @return 成功与否。**CONNACK 的原因码会打进日志** ——
     *   失败时不打原因码的话，「扫不了码」在真机上就只剩「没反应」这一个现象。
     */
    suspend fun open(): Boolean {
        var path = MqttWebSocketTransport.defaultPath()
        var redirects = 0
        while (true) {
            if (!transport.connect(path = path, timeoutMs = HANDSHAKE_TIMEOUT_MS)) return false
            if (!sendConnect()) return false
            when (val ack = awaitConnAck()) {
                is ConnAckResult.Accepted -> {
                    if (!sendSubscribe()) return false
                    return awaitSubAck()
                }
                is ConnAckResult.Redirected -> {
                    if (redirects >= MAX_REDIRECTS) {
                        Log.w(TAG, "重定向次数超过 $MAX_REDIRECTS，放弃")
                        return false
                    }
                    redirects++
                    path = qrMqttRedirectPath(path, ack.serverReference)
                    Log.i(TAG, "服务端要求重定向（0x${ack.reasonCode.toString(16)}）→ $path")
                    transport.reset()
                }
                is ConnAckResult.Rejected -> return false
            }
        }
    }

    /** CONNACK 的三种结局。重定向必须与「拒绝」分开 —— 前者要重连，后者要放弃。 */
    private sealed class ConnAckResult {
        data object Accepted : ConnAckResult()
        data class Redirected(val serverReference: String, val reasonCode: Int) : ConnAckResult()
        data class Rejected(val reasonCode: Int) : ConnAckResult()
    }

    private fun sendConnect(): Boolean {
        val props = listOf(
            MqttCodec.Prop.Utf8(MqttCodec.PROP_AUTHENTICATION_METHOD, "pass"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "tmeAppID", "qqmusic"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "business", "management"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "hashTag", qrCodeId),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "clientTag", "management.user"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "userID", qrCodeId),
        )
        val packet = MqttCodec.connect(clientId, REQUESTED_KEEP_ALIVE, props)
        return transport.send(packet)
    }

    private suspend fun awaitConnAck(): ConnAckResult {
        val ack = awaitConnAckFrame() ?: return ConnAckResult.Rejected(-1)
        val ref = ack.properties.serverReference
        // 0x9C = Use another server，0x9D = Server moved。两者都必须跟随 ——
        // 只认 0x9D 会在服务端换用 0x9C 时静默退化回「CONNACK 被拒」。
        if ((ack.reasonCode == 0x9C || ack.reasonCode == 0x9D) && !ref.isNullOrEmpty()) {
            return ConnAckResult.Redirected(ref, ack.reasonCode)
        }
        if (ack.reasonCode != 0) {
            Log.w(
                TAG,
                "CONNACK 被拒 reason=0x${ack.reasonCode.toString(16)}" +
                    " ref=$ref reason=${ack.properties.reasonString}",
            )
            return ConnAckResult.Rejected(ack.reasonCode)
        }
        // 服务端可以改 keep-alive —— 必须认它，否则我们按 45 秒发心跳而它按更短的时间
        // 判我们掉线，表现是「扫码中途连接莫名断开」。
        ack.properties.serverKeepAlive?.let {
            keepAliveSeconds = it
            if (BuildConfig.DEBUG) Log.d(TAG, "服务端协商 keepAlive=${it}s")
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "CONNACK ok")
        return ConnAckResult.Accepted
    }

    /**
     * 等一条 CONNACK，**跳过陈旧的「连接没了」哨兵**。
     *
     * ## 这个循环是必需的（v3.4.9 实测踩到，症状极难看出）
     *
     * 重定向时要先把旧连接拆掉（[MqttWebSocketTransport.reset]），而 OkHttp 的
     * `cancel()` 会让旧连接的回调**异步**再投一条 DISCONNECT 哨兵进来 ——
     * 它可能落在 `reset()` 的清队列**之后**。于是新连接上等 CONNACK 的第一次
     * `receive()` 拿到的是那条哨兵（`type=14`），第一版就据此判「CONNACK 被拒」，
     * 表现是**每当服务端要求重定向就必然失败**。
     *
     * 判据是「暂时忽略哨兵、继续等」而不是「把哨兵当成功」：
     * 真正的断开会让这个循环一直等到超时（那时返回 null，调用方按失败处理），
     * 所以不会把「连接死了」误判成「连上了」。
     */
    private suspend fun awaitConnAckFrame(): MqttCodec.ConnAck? {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) return null
            val frame = nextFrame(left) ?: return null
            when (frame.type) {
                MqttCodec.CONNACK -> return MqttCodec.decodeConnAck(frame)
                // 旧连接拆掉时投进来的哨兵：不是这次握手的结论。
                MqttCodec.DISCONNECT -> if (BuildConfig.DEBUG) {
                    Log.d(TAG, "跳过陈旧的断连哨兵，继续等 CONNACK")
                }
                else -> {
                    Log.w(TAG, "期望 CONNACK，收到 type=${frame.type}")
                    return null
                }
            }
        }
    }

    private fun sendSubscribe(): Boolean {
        val topic = topicOf(qrCodeId)
        val props = listOf(
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "authorization", "tmelogin"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "pubsub", "unicast"),
        )
        // QoS 0：与参考实现一致。这些是「状态通知」，丢了就等下一次或超时，
        // 用 QoS 1/2 换来的重传保证对一条十几秒的交互没有意义。
        val packet = MqttCodec.subscribe(subscribePacketId, topic, qos = 0, properties = props)
        if (BuildConfig.DEBUG) Log.d(TAG, "SUBSCRIBE $topic")
        return transport.send(packet)
    }

    /**
     * 等 SUBACK。
     *
     * ⚠️ 循环而不是取一条就判：服务端**可能在 SUBACK 之前先推一条 PUBLISH**
     * （实测推送与确认走同一条通道）。取一条就判会把那条推送当成「不是 SUBACK」
     * 直接失败 —— 表现是「第一次扫码总是失败，重试就好了」那种最招人烦的形状。
     */
    private suspend fun awaitSubAck(): Boolean {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) return false
            val frame = nextFrame(left) ?: return false
            when (frame.type) {
                MqttCodec.SUBACK -> return subAckAccepted(frame)
                MqttCodec.PUBLISH -> {
                    // 先到的推送不能丢：塞回给 awaitEvent 用（会话级的通道还是同一条）。
                    if (pendingEarlyEvent == null) pendingEarlyEvent = eventOf(frame)
                    else Log.w(TAG, "SUBACK 前收到多条推送，只保留第一条")
                }
                MqttCodec.DISCONNECT -> {
                    Log.w(TAG, "SUBACK 之前连接就断了")
                    return false
                }
                else -> if (BuildConfig.DEBUG) Log.d(TAG, "SUBACK 前忽略报文 type=${frame.type}")
            }
        }
    }

    private fun subAckAccepted(frame: MqttCodec.Frame): Boolean {
        val ack = MqttCodec.decodeSubAck(frame)
        val ok = ack.packetId == subscribePacketId && ack.reasonCodes.all { MqttCodec.subAckSucceeded(it) }
        if (!ok) Log.w(TAG, "SUBACK 失败 packetId=${ack.packetId} codes=${ack.reasonCodes}")
        if (BuildConfig.DEBUG) Log.d(TAG, "SUBACK ok=$ok")
        return ok
    }

    /**
     * 等下一条**登录事件**。
     *
     * 循环而不是取一条就返回：这条连接上还会有 PINGRESP 之类与我们无关的报文，
     * 「读一条，如果不是推送就当成没事件」会让超时判断凭空多算一轮。
     *
     * @param timeoutMs 本次等待窗口。返回 `null` = 超时（调用方按「二维码过期」处理）。
     */
    suspend fun awaitEvent(timeoutMs: Long): Event? {
        // SUBACK 之前先到的那条推送（见 awaitSubAck 的注释）优先消费。
        pendingEarlyEvent?.let { early ->
            pendingEarlyEvent = null
            return early
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) return null
            val frame = nextFrame(left) ?: return null
            when (frame.type) {
                MqttCodec.PINGRESP -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "PINGRESP")
                }
                MqttCodec.PUBLISH -> {
                    eventOf(frame)?.let { return it }
                }
                MqttCodec.DISCONNECT -> {
                    Log.w(TAG, "服务端断开（DISCONNECT）")
                    return Event.Failed("server disconnect")
                }
                else -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "忽略报文 type=${frame.type}")
                }
            }
        }
    }

    /**
     * 一条 PUBLISH → 一条 [Event]。
     *
     * `type` 在 **user property** 上（不在 payload 里），payload 才是 JSON ——
     * 这两个位置写反是本条链路最典型的错误，而它的表现是
     * 「扫了码但界面永远停在等待」，因为 `type` 读成 null 会被当成无关报文丢掉。
     */
    private fun eventOf(frame: MqttCodec.Frame): Event? {
        val publish = MqttCodec.decodePublish(frame)
        val type = publish.properties.userProperty("type")
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "PUBLISH type=$type payloadLen=${publish.payload.size}")
        }
        return when (type) {
            "scanned" -> Event.Scanned
            "canceled" -> Event.Canceled
            "timeout" -> Event.Expired
            "loginFailed" -> Event.Failed(publish.payload.toString(Charsets.UTF_8).take(200))
            "cookies" -> {
                val json = runCatching { JSONObject(publish.payload.toString(Charsets.UTF_8)) }.getOrNull()
                // 形状：{"cookies":{"qqmusic_uin":{"value":"…"},"qqmusic_key":{"value":"…"}}}
                val cookies = json?.optJSONObject("cookies")
                val uin = cookies?.optJSONObject("qqmusic_uin")?.optString("value").orEmpty()
                val token = cookies?.optJSONObject("qqmusic_key")?.optString("value").orEmpty()
                if (uin.isEmpty() || token.isEmpty()) {
                    // 形状变了 —— 明确失败，不要拿半个凭据去换票（那只会换来一个空 data）。
                    Log.w(TAG, "cookies 推送里缺 qqmusic_uin / qqmusic_key")
                    Event.Failed("cookies payload incomplete")
                } else {
                    Event.Credentials(uin, token)
                }
            }
            else -> null
        }
    }

    /**
     * 发一次 PINGREQ。
     *
     * 由上层按协商出来的 keep-alive 节奏调用。**不发**的后果是服务端按自己的
     * keep-alive 判我们掉线并断开 —— 而用户在扫码确认那一侧看到的是「确认了但没反应」。
     */
    fun ping(): Boolean = transport.send(MqttCodec.pingReq())

    /** 协商后的 keep-alive（秒）。上层据此决定 PINGREQ 的间隔。 */
    fun keepAliveSeconds(): Int = keepAliveSeconds

    fun close() {
        runCatching { transport.send(MqttCodec.disconnect()) }
        transport.close()
    }

    /** 下一条报文。超时返回 `null`（调用方按「没事件」处理）。 */
    private suspend fun nextFrame(timeoutMs: Long): MqttCodec.Frame? = try {
        withTimeout(timeoutMs) { transport.incoming.receive() }
    } catch (_: TimeoutCancellationException) {
        null
    } catch (_: ClosedReceiveChannelException) {
        null
    }
}

/**
 * 「QQ 音乐 App 扫码」订阅的话题名（v3.4.9）。
 *
 * 抽成文件级函数而不是藏在私有 companion 里，是为了让**单测能直接钉住它** ——
 * 这个名字写错的表现是「连接与订阅都成功，但永远收不到推送」，
 * 而那与「用户没扫码」在日志里完全同形（`QqQrMqttSession` 的 KDoc 里记着这一点）。
 *
 * **它绑定在 `qrCodeID` 上**：换一张二维码就必须换话题、重新订阅。
 * 这也是本版不做重连的原因之一（重连后 qrCodeID 还是不是同一个，只有服务端知道）。
 */
internal fun qrLoginTopicOf(qrCodeId: String): String = "management.qrcode_login/$qrCodeId"

/**
 * 按 `SERVER_REFERENCE` 算出重定向后的**握手路径**（v3.4.9）。
 *
 * 服务端给的这个值形如 `11.154.131.152:29001`（实测），它的语义是
 * **路径段**而不是新主机 —— 参考实现的 `_build_redirect_path` 就是把它
 * 拼在当前路径后面，重连到 `wss://mu.y.qq.com/ws/handshake/11.154.131.152:29001`，
 * 由服务端按这一段把连接路由到对应节点。所以主机名**不变**。
 *
 * 已经是「带冒号的最后一段」时就替换它（多次重定向时不会无限加长路径）——
 * 与参考实现的 `if ":" in parts[-1]` 判断逐字一致。
 *
 * 抽成文件级纯函数是为了可单测：这段逻辑写错的表现是
 * 「重定向后连不上」，而那与「网络不通」在日志里长得一样。
 */
internal fun qrMqttRedirectPath(currentPath: String, serverReference: String): String {
    val base = currentPath.trimEnd('/')
    val parts = base.split('/').toMutableList()
    if (parts.isNotEmpty() && parts.last().contains(':')) {
        parts[parts.size - 1] = serverReference
        return parts.joinToString("/")
    }
    return "$base/$serverReference"
}
