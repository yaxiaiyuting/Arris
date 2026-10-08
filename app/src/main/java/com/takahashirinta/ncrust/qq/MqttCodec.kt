/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：MQTT 5.0 **报文编解码**（纯逻辑，无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.qq

import java.io.ByteArrayOutputStream

/**
 * MQTT 5.0 的报文编解码（v3.4.9）。**只实现用到的那一小片，但实现得很死板。**
 *
 * ## 为什么要在 Kotlin 里自己写一个 MQTT 客户端
 *
 * qm 的「官方 App 扫码登录」最后一步**只能是 MQTT 推送**（HTTP 侧没有等价物：
 * 换凭证要的 `qrCodeID` 与 `token` 只从推送里来）。而服务端**只认 WebSocket**：
 * 实测 `mu.y.qq.com:443` 上用裸 MQTT over TCP 发 CONNECT，回的是
 * `HTTP/1.1 400 Bad Request`（nginx）—— 也就是说 Paho Android 那类纯 TCP 的
 * MQTT 库在这里**根本连不上**。
 *
 * 于是只剩两条路：引入一个支持 WebSocket 传输的 MQTT 库，或者自己按需要写。
 * 选后者，理由有三条：
 * 1. **范围是封闭的** —— 我们只发 CONNECT / SUBSCRIBE / PINGREQ，只收 CONNACK /
 *    SUBACK / PUBLISH / PINGRESP / DISCONNECT。不发布、不保留会话、不做遗嘱、
 *    不做 QoS 重传、不做重连退避（这套东西的复杂度全在那些地方）。
 * 2. **每条报文都能被真实字节钉住** —— 本仓库对 QRC 那套非标准 3DES 用的就是
 *    「自实现 + 与独立实现逐字节比对」，这里沿用同一条纪律。
 * 3. **不引入一个只为登录服务的重型依赖** —— 本 App 的体积是有预算的
 *    （README 写着 9.8 MB），而 MQTT 库会带进它自己的一整套会话状态机。
 *
 * ## 与协议文本的对应（写给下一个要改这里的人）
 *
 * 所有多字节整数都是**大端**（MQTT 规范 §1.5）。四类字段的编码在这里各有一个函数，
 * 谁都不许绕过它们自己拼字节：
 *
 * | 类型 | 编码 | 本文件的函数 |
 * |---|---|---|
 * | 变长字节整数（剩余长度） | 每字节低 7 位 + 续接位，**最多 4 字节** | [encodeVarInt] / [decodeVarInt] |
 * | UTF-8 字符串 | 2 字节长度 + 字节 | [encodeString] |
 * | 二进制数据 | 2 字节长度 + 字节 | [encodeBinary] |
 * | 属性 | 属性 ID + 该属性的值（类型由 ID 决定） | [encodeProperties] / [decodeProperties] |
 *
 * ## 一处**刻意的取舍**：属性表的类型必须显式声明
 *
 * MQTT 5.0 的属性**不是自描述的** —— 报文里只有属性 ID，值怎么读完全取决于
 * 「这个 ID 在规范里是什么类型」。所以 [PROPERTY_TYPES] 是这张表，
 * 它写错的表现不是崩溃，而是**后面所有字节全部错位**（读出一个荒谬的长度然后
 * 抛异常，或者更糟：解得出一堆看似合理其实是垃圾的属性）。
 *
 * 这张表只登记我们**可能遇到**的属性。遇到未登记的 ID 时 [decodeProperties]
 * 抛异常而不是猜一个长度跳过 —— 猜的后果同上，而且是静默的。
 */
object MqttCodec {

    // ---------------- 报文类型 ----------------

    const val CONNECT = 0x1
    const val CONNACK = 0x2
    const val PUBLISH = 0x3
    const val PUBACK = 0x4
    const val SUBSCRIBE = 0x8
    const val SUBACK = 0x9
    const val PINGREQ = 0xC
    const val PINGRESP = 0xD
    const val DISCONNECT = 0xE

    /** MQTT 5.0 的协议级别（CONNECT 的协议名字段里那个 `5`）。 */
    const val PROTOCOL_LEVEL = 5

    /**
     * 变长字节整数编码（MQTT 规范 §1.5.5）：**最多 4 字节**，最大 268 435 455。
     *
     * 超出范围时抛异常而不是截断 —— 截断会得到一个能被服务端解析成**另一个值**的长度，
     * 那比崩溃难查得多（我们自己也用这个函数解析**服务端发来**的长度，见 [decodeVarInt]）。
     */
    fun encodeVarInt(value: Int): ByteArray {
        require(value in 0..268_435_455) { "变长整数超出范围：$value" }
        val out = ByteArrayOutputStream(4)
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v > 0) b = b or 0x80
            out.write(b)
        } while (v > 0)
        return out.toByteArray()
    }

    /** 变长字节整数解码。返回 `值 to 消耗的字节数`。 */
    fun decodeVarInt(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var multiplier = 1
        var value = 0
        var consumed = 0
        while (true) {
            require(consumed < 4) { "变长整数超过 4 字节（offset=$offset）" }
            require(offset + consumed < bytes.size) { "变长整数被截断（offset=$offset）" }
            val b = bytes[offset + consumed].toInt() and 0xFF
            value += (b and 0x7F) * multiplier
            consumed++
            if (b and 0x80 == 0) break
            multiplier *= 128
        }
        return value to consumed
    }

    // ---------------- 基础字段 ----------------

    /**
     * UTF-8 字符串：2 字节长度 + 内容。
     *
     * 长度用 `codeUnits`？**不** —— MQTT 的长度是**字节数**。
     * 客户端 id / 话题名（`management.qrcode_login/{91 字符的 qrCodeID}`）都是 ASCII，
     * 所以两者一致；但用户属性里可能出现中文，按字符数写就会算短、
     * 把后续字节错位（所以这里显式用 `toByteArray` 的长度）。
     */
    fun encodeString(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        require(raw.size <= 65_535) { "MQTT 字符串超过 65535 字节：${raw.size}" }
        return ByteArrayOutputStream(raw.size + 2).apply {
            write((raw.size ushr 8) and 0xFF)
            write(raw.size and 0xFF)
            write(raw)
        }.toByteArray()
    }

    /** 二进制数据：2 字节长度 + 内容。与 [encodeString] 同形，分开命名只为可读性。 */
    fun encodeBinary(raw: ByteArray): ByteArray {
        require(raw.size <= 65_535) { "MQTT 二进制超过 65535 字节：${raw.size}" }
        return ByteArrayOutputStream(raw.size + 2).apply {
            write((raw.size ushr 8) and 0xFF)
            write(raw.size and 0xFF)
            write(raw)
        }.toByteArray()
    }

    /** 读一个 2 字节长度前缀的字节串。返回 `内容 to 消耗的字节数`。 */
    fun readLengthPrefixed(bytes: ByteArray, offset: Int): Pair<ByteArray, Int> {
        require(offset + 2 <= bytes.size) { "长度前缀被截断（offset=$offset）" }
        val len = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        require(offset + 2 + len <= bytes.size) {
            "长度前缀声明 $len 字节，但只剩 ${bytes.size - offset - 2} 字节"
        }
        return bytes.copyOfRange(offset + 2, offset + 2 + len) to (len + 2)
    }

    /** [readLengthPrefixed] 的字符串版本。 */
    fun readString(bytes: ByteArray, offset: Int): Pair<String, Int> {
        val (raw, used) = readLengthPrefixed(bytes, offset)
        return String(raw, Charsets.UTF_8) to used
    }

    // ---------------- 属性 ----------------

    /** 属性 ID（MQTT 5.0 规范 §2.2.2.2 的表格，只登记我们会遇到的）。 */
    const val PROP_PAYLOAD_FORMAT_INDICATOR = 0x01
    const val PROP_CONTENT_TYPE = 0x03
    const val PROP_RESPONSE_TOPIC = 0x08
    const val PROP_CORRELATION_DATA = 0x09
    const val PROP_SUBSCRIPTION_IDENTIFIER = 0x0B
    const val PROP_SESSION_EXPIRY_INTERVAL = 0x11
    const val PROP_ASSIGNED_CLIENT_IDENTIFIER = 0x12
    const val PROP_SERVER_KEEP_ALIVE = 0x13
    const val PROP_AUTHENTICATION_METHOD = 0x15
    const val PROP_AUTHENTICATION_DATA = 0x16
    const val PROP_REQUEST_PROBLEM_INFORMATION = 0x17
    const val PROP_WILL_DELAY_INTERVAL = 0x18
    const val PROP_REQUEST_RESPONSE_INFORMATION = 0x19
    const val PROP_RESPONSE_INFORMATION = 0x1A
    const val PROP_SERVER_REFERENCE = 0x1C
    const val PROP_REASON_STRING = 0x1F
    const val PROP_RECEIVE_MAXIMUM = 0x21
    const val PROP_TOPIC_ALIAS_MAXIMUM = 0x22
    const val PROP_TOPIC_ALIAS = 0x23
    const val PROP_MAXIMUM_QOS = 0x24
    const val PROP_RETAIN_AVAILABLE = 0x25
    const val PROP_USER_PROPERTY = 0x26
    const val PROP_MAXIMUM_PACKET_SIZE = 0x27
    const val PROP_WILDCARD_SUBSCRIPTION_AVAILABLE = 0x28
    const val PROP_SUBSCRIPTION_IDENTIFIER_AVAILABLE = 0x29
    const val PROP_SHARED_SUBSCRIPTION_AVAILABLE = 0x2A

    /** 属性值类型。**这张表是报文能否被正确解析的唯一依据**（见 KDoc）。 */
    enum class PropType { BYTE, TWO_BYTE, FOUR_BYTE, VAR_INT, UTF8, BINARY, STRING_PAIR }

    val PROPERTY_TYPES: Map<Int, PropType> = mapOf(
        PROP_PAYLOAD_FORMAT_INDICATOR to PropType.BYTE,
        PROP_CONTENT_TYPE to PropType.UTF8,
        PROP_RESPONSE_TOPIC to PropType.UTF8,
        PROP_CORRELATION_DATA to PropType.BINARY,
        PROP_SUBSCRIPTION_IDENTIFIER to PropType.VAR_INT,
        PROP_SESSION_EXPIRY_INTERVAL to PropType.FOUR_BYTE,
        PROP_ASSIGNED_CLIENT_IDENTIFIER to PropType.UTF8,
        PROP_SERVER_KEEP_ALIVE to PropType.TWO_BYTE,
        PROP_AUTHENTICATION_METHOD to PropType.UTF8,
        PROP_AUTHENTICATION_DATA to PropType.BINARY,
        PROP_REQUEST_PROBLEM_INFORMATION to PropType.BYTE,
        PROP_WILL_DELAY_INTERVAL to PropType.FOUR_BYTE,
        PROP_REQUEST_RESPONSE_INFORMATION to PropType.BYTE,
        PROP_RESPONSE_INFORMATION to PropType.UTF8,
        PROP_SERVER_REFERENCE to PropType.UTF8,
        PROP_REASON_STRING to PropType.UTF8,
        PROP_RECEIVE_MAXIMUM to PropType.TWO_BYTE,
        PROP_TOPIC_ALIAS_MAXIMUM to PropType.TWO_BYTE,
        PROP_TOPIC_ALIAS to PropType.TWO_BYTE,
        PROP_MAXIMUM_QOS to PropType.BYTE,
        PROP_RETAIN_AVAILABLE to PropType.BYTE,
        PROP_USER_PROPERTY to PropType.STRING_PAIR,
        PROP_MAXIMUM_PACKET_SIZE to PropType.FOUR_BYTE,
        PROP_WILDCARD_SUBSCRIPTION_AVAILABLE to PropType.BYTE,
        PROP_SUBSCRIPTION_IDENTIFIER_AVAILABLE to PropType.BYTE,
        PROP_SHARED_SUBSCRIPTION_AVAILABLE to PropType.BYTE,
    )

    /**
     * 解码后的属性集。
     *
     * `userProperties` **保持顺序且允许重名**：Qm 的登录推送里 `type` 就挂在
     * user property 上（`("type","cookies")`），而同一个报文里可以有多个不同的
     * user property —— 用 `Map<String,String>` 会静默丢掉重复项，
     * 而「type 被丢掉」的表现就是「二维码扫了但客户端永远不动」。
     */
    data class Properties(
        val userProperties: List<Pair<String, String>> = emptyList(),
        val authMethod: String? = null,
        val serverReference: String? = null,
        val reasonString: String? = null,
        val serverKeepAlive: Int? = null,
        val sessionExpiryInterval: Long? = null,
        val assignedClientId: String? = null,
        /** 收到的不认识/不关心的属性（按 ID → 已解码的字符串形态，只用于日志）。 */
        val others: Map<Int, String> = emptyMap(),
    ) {
        /** 第一个名为 [name] 的用户属性值。 */
        fun userProperty(name: String): String? =
            userProperties.firstOrNull { it.first == name }?.second
    }

    /** 需要写进报文的一条属性。 */
    sealed class Prop {
        data class ByteProp(val id: Int, val value: Int) : Prop()
        data class TwoByte(val id: Int, val value: Int) : Prop()
        data class FourByte(val id: Int, val value: Long) : Prop()
        data class VarIntProp(val id: Int, val value: Int) : Prop()
        data class Utf8(val id: Int, val value: String) : Prop()
        data class Binary(val id: Int, val value: ByteArray) : Prop() {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Binary && id == other.id && value.contentEquals(other.value))

            override fun hashCode(): Int = 31 * id + value.contentHashCode()
        }
        data class PairProp(val id: Int, val key: String, val value: String) : Prop()
    }

    /** 一组属性的编码：先是**属性总长度**（变长整数），再是逐条属性。 */
    fun encodeProperties(props: List<Prop>): ByteArray {
        if (props.isEmpty()) return byteArrayOf(0)
        val body = ByteArrayOutputStream()
        for (p in props) {
            when (p) {
                is Prop.ByteProp -> {
                    requireRegistered(p.id, PropType.BYTE)
                    body.write(p.id)
                    body.write(p.value and 0xFF)
                }
                is Prop.TwoByte -> {
                    requireRegistered(p.id, PropType.TWO_BYTE)
                    body.write(p.id)
                    body.write((p.value ushr 8) and 0xFF)
                    body.write(p.value and 0xFF)
                }
                is Prop.FourByte -> {
                    requireRegistered(p.id, PropType.FOUR_BYTE)
                    body.write(p.id)
                    for (shift in intArrayOf(24, 16, 8, 0)) {
                        body.write(((p.value ushr shift) and 0xFF).toInt())
                    }
                }
                is Prop.VarIntProp -> {
                    requireRegistered(p.id, PropType.VAR_INT)
                    body.write(p.id)
                    body.write(encodeVarInt(p.value))
                }
                is Prop.Utf8 -> {
                    requireRegistered(p.id, PropType.UTF8)
                    body.write(p.id)
                    body.write(encodeString(p.value))
                }
                is Prop.Binary -> {
                    requireRegistered(p.id, PropType.BINARY)
                    body.write(p.id)
                    body.write(encodeBinary(p.value))
                }
                is Prop.PairProp -> {
                    requireRegistered(p.id, PropType.STRING_PAIR)
                    body.write(p.id)
                    body.write(encodeString(p.key))
                    body.write(encodeString(p.value))
                }
            }
        }
        val raw = body.toByteArray()
        return ByteArrayOutputStream(raw.size + 4).apply {
            write(encodeVarInt(raw.size))
            write(raw)
        }.toByteArray()
    }

    private fun requireRegistered(id: Int, expected: PropType) {
        val actual = PROPERTY_TYPES[id]
        require(actual == expected) {
            "属性 0x${id.toString(16)} 的类型是 $actual，调用方按 $expected 编码 —— " +
                "这条不一致会让报文的后续字节全部错位"
        }
    }

    /** 属性解码。未登记的属性 ID **抛异常**（见 KDoc：猜长度是静默错位）。 */
    fun decodeProperties(bytes: ByteArray, offset: Int): Pair<Properties, Int> {
        val (total, lenBytes) = decodeVarInt(bytes, offset)
        var cursor = offset + lenBytes
        val end = cursor + total
        require(end <= bytes.size) { "属性长度 $total 超出报文（offset=$offset）" }
        val users = ArrayList<Pair<String, String>>()
        var authMethod: String? = null
        var serverReference: String? = null
        var reasonString: String? = null
        var serverKeepAlive: Int? = null
        var sessionExpiry: Long? = null
        var assignedClientId: String? = null
        val others = LinkedHashMap<Int, String>()
        while (cursor < end) {
            val id = bytes[cursor].toInt() and 0xFF
            cursor++
            val type = PROPERTY_TYPES[id]
                ?: throw IllegalArgumentException("未登记的 MQTT 属性 ID 0x${id.toString(16)}")
            when (type) {
                PropType.BYTE -> {
                    others[id] = (bytes[cursor].toInt() and 0xFF).toString()
                    cursor += 1
                }
                PropType.TWO_BYTE -> {
                    val v = ((bytes[cursor].toInt() and 0xFF) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
                    cursor += 2
                    if (id == PROP_SERVER_KEEP_ALIVE) serverKeepAlive = v else others[id] = v.toString()
                }
                PropType.FOUR_BYTE -> {
                    var v = 0L
                    for (i in 0..3) v = (v shl 8) or (bytes[cursor + i].toLong() and 0xFF)
                    cursor += 4
                    if (id == PROP_SESSION_EXPIRY_INTERVAL) sessionExpiry = v else others[id] = v.toString()
                }
                PropType.VAR_INT -> {
                    val (v, used) = decodeVarInt(bytes, cursor)
                    cursor += used
                    others[id] = v.toString()
                }
                PropType.UTF8 -> {
                    val (s, used) = readString(bytes, cursor)
                    cursor += used
                    when (id) {
                        PROP_AUTHENTICATION_METHOD -> authMethod = s
                        PROP_SERVER_REFERENCE -> serverReference = s
                        PROP_REASON_STRING -> reasonString = s
                        PROP_ASSIGNED_CLIENT_IDENTIFIER -> assignedClientId = s
                        else -> others[id] = s
                    }
                }
                PropType.BINARY -> {
                    val (b, used) = readLengthPrefixed(bytes, cursor)
                    cursor += used
                    others[id] = "binary(${b.size}B)"
                }
                PropType.STRING_PAIR -> {
                    val (k, usedK) = readString(bytes, cursor)
                    val (v, usedV) = readString(bytes, cursor + usedK)
                    cursor += usedK + usedV
                    users += k to v
                }
            }
        }
        require(cursor == end) { "属性解析错位：读到 $cursor，声明结束于 $end" }
        return Properties(
            userProperties = users,
            authMethod = authMethod,
            serverReference = serverReference,
            reasonString = reasonString,
            serverKeepAlive = serverKeepAlive,
            sessionExpiryInterval = sessionExpiry,
            assignedClientId = assignedClientId,
            others = others,
        ) to (lenBytes + total)
    }

    // ---------------- 报文 ----------------

    /**
     * 组装一条报文：固定头（类型 + 标志）+ 剩余长度 + 可变部分。
     *
     * 固定头第一个字节的高 4 位是类型、低 4 位是**该类型专属的标志**：
     * CONNECT / CONNACK / SUBACK / PINGREQ / PINGRESP / DISCONNECT 恒为 0，
     * PUBLISH 的低 4 位是 `dup(3) qos(2..1) retain(0)`，SUBSCRIBE **必须是 0b0010**
     * （规范强制，服务端会校验 —— 写 0 会被直接断开）。
     */
    fun packet(type: Int, flags: Int, variableHeaderAndPayload: ByteArray): ByteArray =
        ByteArrayOutputStream(variableHeaderAndPayload.size + 5).apply {
            write(((type shl 4) or (flags and 0x0F)) and 0xFF)
            write(encodeVarInt(variableHeaderAndPayload.size))
            write(variableHeaderAndPayload)
        }.toByteArray()

    /** 一次解析的结果：报文类型、标志、可变部分。 */
    data class Frame(val type: Int, val flags: Int, val body: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Frame && type == other.type && flags == other.flags && body.contentEquals(other.body))

        override fun hashCode(): Int = (type * 31 + flags) * 31 + body.contentHashCode()
    }

    /**
     * 从字节流里切出**一条完整报文**。
     *
     * 返回 `报文 to 消耗的字节数`；字节不够时返回 `null`（调用方继续累积）。
     * 这是把「WebSocket 帧」与「MQTT 报文」两层的边界分开的地方：
     * 一个 WS 帧里可能有多个 MQTT 报文，一个 MQTT 报文也可能跨 WS 帧 ——
     * **两层都不做这种假设**，所以这里按字节缓冲区解析而不是按帧解析。
     */
    fun parse(bytes: ByteArray, offset: Int = 0): Pair<Frame, Int>? {
        if (bytes.size - offset < 2) return null
        val header = bytes[offset].toInt() and 0xFF
        val (remaining, lenBytes) = try {
            decodeVarInt(bytes, offset + 1)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val bodyStart = offset + 1 + lenBytes
        if (bytes.size - bodyStart < remaining) return null
        return Frame(
            type = (header ushr 4) and 0x0F,
            flags = header and 0x0F,
            body = bytes.copyOfRange(bodyStart, bodyStart + remaining),
        ) to (1 + lenBytes + remaining)
    }

    // ---------------- 具体报文 ----------------

    /**
     * CONNECT（规范 §3.1）。
     *
     * 字段顺序是**规范写死的**，不能重排：协议名 → 协议级别 → 连接标志 →
     * 保持连接 → 属性 → 客户端 id。
     *
     * 连接标志取 `0x02`（Clean Start），其余位全 0：
     * 不做遗嘱、不用用户名密码（认证走属性里的 `AUTH_METHOD=pass` 与 user property）。
     */
    fun connect(
        clientId: String,
        keepAliveSeconds: Int,
        properties: List<Prop>,
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(encodeString("MQTT"))
            write(PROTOCOL_LEVEL)
            write(0x02) // clean start
            write((keepAliveSeconds ushr 8) and 0xFF)
            write(keepAliveSeconds and 0xFF)
            write(encodeProperties(properties))
            write(encodeString(clientId))
        }.toByteArray()
        return packet(CONNECT, 0, body)
    }

    /** SUBSCRIBE（规范 §3.8）：包标识 + 属性 + 一组 `话题 + 订阅选项`。标志**必须是 2**。 */
    fun subscribe(
        packetId: Int,
        topicFilter: String,
        qos: Int,
        properties: List<Prop>,
    ): ByteArray {
        require(qos in 0..2) { "QoS 超出范围：$qos" }
        val body = ByteArrayOutputStream().apply {
            write((packetId ushr 8) and 0xFF)
            write(packetId and 0xFF)
            write(encodeProperties(properties))
            write(encodeString(topicFilter))
            write(qos and 0x03)
        }.toByteArray()
        return packet(SUBSCRIBE, 0x02, body)
    }

    /** PINGREQ（规范 §3.12）：**没有可变部分**，整条报文就两个字节。 */
    fun pingReq(): ByteArray = packet(PINGREQ, 0, ByteArray(0))

    /** DISCONNECT（规范 §3.14）：正常断开，原因码 0 时属性可省。 */
    fun disconnect(): ByteArray = packet(DISCONNECT, 0, byteArrayOf(0))

    /**
     * PUBLISH 的解码结果（规范 §3.3）。
     *
     * `topicName` 在 MQTT 5 里**可以为空**（服务端用 topic alias 时），
     * 所以它是可空的 —— 而我们的判据（话题名 = `management.qrcode_login/{id}`）
     * 不能因此失效：见 [QqMqttClient] 里对 alias 的处理。
     */
    data class Publish(
        val topicName: String?,
        val packetId: Int?,
        val properties: Properties,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Publish && topicName == other.topicName && packetId == other.packetId &&
                    properties == other.properties && payload.contentEquals(other.payload)
                )

        override fun hashCode(): Int =
            ((topicName?.hashCode() ?: 0) * 31 + (packetId ?: 0)) * 31 +
                properties.hashCode() * 31 + payload.contentHashCode()
    }

    /** 解码一条 PUBLISH 的可变部分。 */
    fun decodePublish(frame: Frame): Publish {
        require(frame.type == PUBLISH) { "不是 PUBLISH 报文：type=${frame.type}" }
        val qos = (frame.flags ushr 1) and 0x03
        var cursor = 0
        val (topic, usedTopic) = readString(frame.body, cursor)
        cursor += usedTopic
        val packetId = if (qos > 0) {
            val id = ((frame.body[cursor].toInt() and 0xFF) shl 8) or (frame.body[cursor + 1].toInt() and 0xFF)
            cursor += 2
            id
        } else {
            null
        }
        val (props, usedProps) = decodeProperties(frame.body, cursor)
        cursor += usedProps
        return Publish(
            topicName = topic.takeIf { it.isNotEmpty() },
            packetId = packetId,
            properties = props,
            payload = frame.body.copyOfRange(cursor, frame.body.size),
        )
    }

    /** CONNACK 的解码结果：`sessionPresent` + 原因码 + 属性。 */
    data class ConnAck(val sessionPresent: Boolean, val reasonCode: Int, val properties: Properties)

    fun decodeConnAck(frame: Frame): ConnAck {
        require(frame.type == CONNACK) { "不是 CONNACK 报文：type=${frame.type}" }
        require(frame.body.size >= 3) { "CONNACK 太短：${frame.body.size} 字节" }
        val ackFlags = frame.body[0].toInt() and 0xFF
        val reason = frame.body[1].toInt() and 0xFF
        val (props, _) = decodeProperties(frame.body, 2)
        return ConnAck(sessionPresent = (ackFlags and 0x01) != 0, reasonCode = reason, properties = props)
    }

    /** SUBACK 的解码结果：包标识 + 属性 + 每个话题一个原因码。 */
    data class SubAck(val packetId: Int, val reasonCodes: List<Int>)

    fun decodeSubAck(frame: Frame): SubAck {
        require(frame.type == SUBACK) { "不是 SUBACK 报文：type=${frame.type}" }
        require(frame.body.size >= 4) { "SUBACK 太短：${frame.body.size} 字节" }
        val packetId = ((frame.body[0].toInt() and 0xFF) shl 8) or (frame.body[1].toInt() and 0xFF)
        val (_, usedProps) = decodeProperties(frame.body, 2)
        val codes = ArrayList<Int>()
        var cursor = 2 + usedProps
        while (cursor < frame.body.size) {
            codes += frame.body[cursor].toInt() and 0xFF
            cursor++
        }
        return SubAck(packetId, codes)
    }

    /** SUBACK 原因码：`0x00/0x01/0x02` 是成功（对应三个 QoS）。 */
    fun subAckSucceeded(code: Int): Boolean = code in 0..2
}
