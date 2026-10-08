/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：MQTT 5.0 报文编解码的单测（**黄金字节**，不是自洽往返）。
 */

package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v3.4.9：`MqttCodec` 的单测。
 *
 * ## 判据是**黄金字节**，不是「自己编码自己解码能对上」
 *
 * 自洽往返测不出本类真正的风险：属性表的类型写错时，**编码和解码会一起错**，
 * 于是往返全绿而真机上一个报文都解不出来。所以这里钉的是**参考实现实际发过的字节**
 * （`mu.y.qq.com/ws/handshake` 那条链路上，按 `L-1124/QQMusicApi` 的
 * `_connect_mobile_mqtt` / `subscribe` 的字段与顺序，逐字节算出来的十六进制）。
 *
 * 测试用的 `qrCodeID` / `clientId` 是**固定的假值**（`TESTQRCODEID0123456789` /
 * `PROBECLIENT0001`），长度与真实值对齐（真实的 qrCodeID 是 91 字符）
 * 但内容是编的 —— 黄金字节里不该出现任何真账号相关的东西。
 */
class MqttCodecTest {

    private val qr = "TESTQRCODEID0123456789"
    private val clientId = "PROBECLIENT0001"

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---------------- 变长整数（剩余长度） ----------------

    /**
     * 边界值逐字节对上规范 §1.5.5 的表。
     *
     * 这四个值不是随手挑的：**它们正好是「几字节编码」的四个分界点**。
     * 写错任何一个（比如 `127` 编成两字节）会让报文长度被服务端解析成另一个数，
     * 表现是「连接建立后立刻被断开」，日志里只有 nginx 的 400。
     */
    @Test
    fun `变长整数的四个分界值逐字节对上规范`() {
        assertEquals("00", hex(MqttCodec.encodeVarInt(0)))
        assertEquals("7f", hex(MqttCodec.encodeVarInt(127)))
        assertEquals("8001", hex(MqttCodec.encodeVarInt(128)))
        assertEquals("ff7f", hex(MqttCodec.encodeVarInt(16_383)))
        assertEquals("808001", hex(MqttCodec.encodeVarInt(16_384)))
        assertEquals("ffff7f", hex(MqttCodec.encodeVarInt(2_097_151)))
        assertEquals("80808001", hex(MqttCodec.encodeVarInt(2_097_152)))
        assertEquals("ffffff7f", hex(MqttCodec.encodeVarInt(268_435_455)))
    }

    @Test
    fun `变长整数解码与编码互逆`() {
        for (v in listOf(0, 1, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152, 268_435_455)) {
            val (decoded, used) = MqttCodec.decodeVarInt(MqttCodec.encodeVarInt(v), 0)
            assertEquals("value=$v", v, decoded)
            assertEquals("value=$v 的编码长度", MqttCodec.encodeVarInt(v).size, used)
        }
    }

    /** 超出 4 字节范围时**抛**而不是截断（截断会得到一个能被解析成别的值的长度）。 */
    @Test
    fun `变长整数越界显式失败`() {
        try {
            MqttCodec.encodeVarInt(268_435_456)
            fail("越界应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("268435456"))
        }
        try {
            MqttCodec.encodeVarInt(-1)
            fail("负数应当抛异常")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------------- 字符串 / 二进制 ----------------

    /**
     * 长度是**字节数**不是字符数。
     *
     * 这条是用例里唯一一条「中文」的：user property 的值里可能出现中文，
     * 按 `String.length` 写长度会算短，后续所有字节错位。
     */
    @Test
    fun `字符串长度按字节算而不是按字符算`() {
        val s = "中文" // 2 个字符、6 个字节
        assertEquals("0006e4b8ade69687", hex(MqttCodec.encodeString(s)))
        val (decoded, used) = MqttCodec.readString(unhex("0006e4b8ade69687"), 0)
        assertEquals(s, decoded)
        assertEquals(8, used)
    }

    @Test
    fun `长度前缀被截断时显式失败而不是返回半个字符串`() {
        try {
            MqttCodec.readString(unhex("0010e4b8ad"), 0) // 声明 16 字节，只给了 3
            fail("应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("16"))
        }
    }

    // ---------------- CONNECT：黄金字节 ----------------

    /** 与参考实现 `_connect_mobile_mqtt` 的字段、顺序、编码完全一致的 178 字节。 */
    private val goldenConnect = "10af0100044d5154540502002d920115000470617373260008746d6541707049" +
        "44000771716d75736963260008627573696e657373000a6d616e6167656d656e7426000768617368546167" +
        "0016" + "544553545152434f4445494430313233343536373839" +
        "260009636c69656e74546167000f6d616e6167656d656e742e75736572" +
        "26000675736572494400" + "16" + "544553545152434f4445494430313233343536373839" +
        "00" + "0f" + "50524f4245434c49454e5430303031"

    @Test
    fun `CONNECT 报文与参考实现逐字节一致`() {
        val props = listOf(
            MqttCodec.Prop.Utf8(MqttCodec.PROP_AUTHENTICATION_METHOD, "pass"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "tmeAppID", "qqmusic"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "business", "management"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "hashTag", qr),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "clientTag", "management.user"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "userID", qr),
        )
        val packet = MqttCodec.connect(clientId, 45, props)
        assertEquals(goldenConnect.replace(" ", ""), hex(packet))
        // 固定头 + 剩余长度 + 177 字节可变部分
        assertEquals(0x10, packet[0].toInt() and 0xFF)
        assertEquals(2, MqttCodec.decodeVarInt(packet, 1).second)
    }

    /**
     * CONNECT 的字段顺序是规范写死的：协议名 → 级别 → 标志 → keep-alive → 属性 → clientId。
     *
     * 单独钉一遍顺序（而不是只看整体哈希）：顺序错了整体字节当然也不对，
     * 但那时失败信息只会说「不相等」，看不出错在哪一段。
     */
    @Test
    fun `CONNECT 的可变部分字段顺序符合规范`() {
        val packet = MqttCodec.connect(clientId, 45, emptyList())
        // 跳过固定头（1 + 1，剩余长度 1 字节因为体很小）
        val body = packet.copyOfRange(2, packet.size)
        assertEquals("00044d515454", hex(body.copyOfRange(0, 6))) // "\0\0\4MQTT"
        assertEquals(5, body[6].toInt())                          // 协议级别
        assertEquals(0x02, body[7].toInt())                       // clean start
        assertEquals("002d", hex(body.copyOfRange(8, 10)))        // keep-alive = 45
        assertEquals(0, body[10].toInt())                         // 无属性
        assertEquals("000f", hex(body.copyOfRange(11, 13)))       // clientId 长度 = 15
        assertEquals(clientId, String(body.copyOfRange(13, body.size), Charsets.UTF_8))
    }

    // ---------------- SUBSCRIBE：黄金字节 ----------------

    @Test
    fun `SUBSCRIBE 报文与参考实现逐字节一致且标志必须为 2`() {
        val topic = qrLoginTopicOf(qr)
        val props = listOf(
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "authorization", "tmelogin"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "pubsub", "unicast"),
        )
        val packet = MqttCodec.subscribe(1, topic, qos = 0, properties = props)
        val expected = "8260" +
            "0001" +                                     // 包标识
            "2c" +                                       // 属性长度 = 44
            "26000d617574686f72697a6174696f6e0008746d656c6f67696e" +
            "2600067075627375620007756e6963617374" +
            "002e6d616e6167656d656e742e7172636f64655f6c6f67696e2f" + hex(qr.toByteArray()) +
            "00"                                         // QoS 0
        assertEquals(expected, hex(packet))
        // 低 4 位标志**必须是 2**（规范强制，写 0 会被服务端直接断开）
        assertEquals(0x2, packet[0].toInt() and 0x0F)
    }

    @Test
    fun `话题名绑定在 qrCodeID 上`() {
        assertEquals("management.qrcode_login/ABC", qrLoginTopicOf("ABC"))
    }

    @Test
    fun `PINGREQ 就是两个字节`() {
        assertEquals("c000", hex(MqttCodec.pingReq()))
    }

    // ---------------- 属性编解码 ----------------

    @Test
    fun `属性表覆盖到我们可能收到的每一种类型`() {
        // 每一步都断言「类型查表」的结果，任何一条被改成别的类型都会红。
        assertEquals(MqttCodec.PropType.UTF8, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_AUTHENTICATION_METHOD])
        assertEquals(MqttCodec.PropType.BINARY, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_AUTHENTICATION_DATA])
        assertEquals(MqttCodec.PropType.STRING_PAIR, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_USER_PROPERTY])
        assertEquals(MqttCodec.PropType.UTF8, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_SERVER_REFERENCE])
        assertEquals(MqttCodec.PropType.UTF8, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_REASON_STRING])
        assertEquals(MqttCodec.PropType.TWO_BYTE, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_SERVER_KEEP_ALIVE])
        assertEquals(MqttCodec.PropType.FOUR_BYTE, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_SESSION_EXPIRY_INTERVAL])
        assertEquals(MqttCodec.PropType.BYTE, MqttCodec.PROPERTY_TYPES[MqttCodec.PROP_MAXIMUM_QOS])
    }

    /**
     * **重复的 user property 不许被折叠。**
     *
     * qm 的登录推送里 `type` 就挂在 user property 上，而一个报文里可以有多个不同的
     * user property。用 `Map` 存会静默丢掉重复项 —— 表现是「扫了码但界面永远不动」，
     * 因为客户端把那条推送当成了无关报文。
     */
    @Test
    fun `重复的 user property 按顺序全部保留`() {
        val props = listOf(
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "type", "cookies"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "type", "again"),
            MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "other", "x"),
        )
        val encoded = MqttCodec.encodeProperties(props)
        val (decoded, used) = MqttCodec.decodeProperties(encoded, 0)
        assertEquals(encoded.size, used)
        assertEquals(3, decoded.userProperties.size)
        assertEquals(listOf("type" to "cookies", "type" to "again", "other" to "x"), decoded.userProperties)
        // userProperty() 取**第一个**（而不是最后一个）—— 顺序即语义
        assertEquals("cookies", decoded.userProperty("type"))
    }

    @Test
    fun `会话过期与保持连接属性各自落到具名字段`() {
        val props = listOf(
            MqttCodec.Prop.FourByte(MqttCodec.PROP_SESSION_EXPIRY_INTERVAL, 300L),
            MqttCodec.Prop.TwoByte(MqttCodec.PROP_SERVER_KEEP_ALIVE, 30),
            MqttCodec.Prop.Utf8(MqttCodec.PROP_SERVER_REFERENCE, "mqtt://other:443/ws/handshake"),
            MqttCodec.Prop.Utf8(MqttCodec.PROP_REASON_STRING, "moved"),
        )
        val (decoded, _) = MqttCodec.decodeProperties(MqttCodec.encodeProperties(props), 0)
        assertEquals(300L, decoded.sessionExpiryInterval)
        assertEquals(30, decoded.serverKeepAlive)
        assertEquals("mqtt://other:443/ws/handshake", decoded.serverReference)
        assertEquals("moved", decoded.reasonString)
    }

    @Test
    fun `空属性集编码为一个零字节`() {
        val encoded = MqttCodec.encodeProperties(emptyList())
        assertArrayEquals(byteArrayOf(0), encoded)
        val (decoded, used) = MqttCodec.decodeProperties(encoded, 0)
        assertEquals(1, used)
        assertTrue(decoded.userProperties.isEmpty())
    }

    /**
     * 未登记的属性 ID **必须抛**，不许猜一个长度跳过。
     *
     * 猜错的后果是后面所有字节错位（解出一堆看似合理其实是垃圾的属性），
     * 而那是最难查的一类问题：报文「解析成功」了，业务字段却是错的。
     */
    @Test
    fun `未登记的属性 ID 显式失败`() {
        // 属性长度 3、ID 0x99（表里没有）、后面两个字节
        val bytes = unhex("03990000")
        try {
            MqttCodec.decodeProperties(bytes, 0)
            fail("未登记的属性 ID 应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("0x99"))
        }
    }

    /** 调用方按错的类型编码也要被拦下（这条守的是「表与调用点一致」）。 */
    @Test
    fun `按错误的类型编码属性会被拦下`() {
        try {
            // AUTHENTICATION_METHOD 是 UTF8，这里按 TWO_BYTE 编
            MqttCodec.encodeProperties(listOf(MqttCodec.Prop.TwoByte(MqttCodec.PROP_AUTHENTICATION_METHOD, 1)))
            fail("类型不一致应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("认证") || e.message!!.contains("类型"))
        }
    }

    // ---------------- 重定向路径（v3.4.9 实测踩坑） ----------------

    /**
     * `SERVER_REFERENCE` 是**路径段**，不是新主机。
     *
     * 实测那条 CONNACK 的属性区原文（2026-10-08，本机）：
     * `1c 0014 "11.154.131.152:29001"` —— 参考实现的 `_build_redirect_path`
     * 把它**拼在当前路径后面**，重连到
     * `wss://mu.y.qq.com/ws/handshake/11.154.131.152:29001`，由服务端按这一段
     * 把连接路由到对应节点。**主机名不变。**
     *
     * 这条写错（当成新主机）的表现是「连不上」—— 而那与「网络不通」在日志里一样。
     */
    @Test
    fun `重定向把 serverReference 拼成路径段而不是换主机`() {
        assertEquals(
            "/ws/handshake/11.154.131.152:29001",
            qrMqttRedirectPath("/ws/handshake", "11.154.131.152:29001"),
        )
    }

    /** 末尾多余的斜杠不许变成双斜杠（那会让服务端的路由匹配失败）。 */
    @Test
    fun `重定向路径不产生双斜杠`() {
        assertEquals(
            "/ws/handshake/1.2.3.4:29001",
            qrMqttRedirectPath("/ws/handshake/", "1.2.3.4:29001"),
        )
    }

    /**
     * 已经重定向过一次时**替换**最后一段，而不是继续加长。
     *
     * 判据与参考实现的 `if ":" in parts[-1]` 逐字一致 —— 服务端给的地址形如
     * `host:port`（含冒号），所以「最后一段含冒号」就等于「这段是上一次的重定向目标」。
     */
    @Test
    fun `二次重定向替换最后一段而不是无限加长`() {
        assertEquals(
            "/ws/handshake/5.6.7.8:29001",
            qrMqttRedirectPath("/ws/handshake/1.2.3.4:29001", "5.6.7.8:29001"),
        )
    }

    // ---------------- 报文切分（帧边界 ≠ 报文边界） ----------------

    /**
     * 一个缓冲区里有多条报文时，[MqttCodec.parse] 必须能一条一条切出来。
     *
     * 这是 `MqttWebSocketTransport` 的核心假设：服务端可以把 CONNACK 与一条 PUBLISH
     * 拼进同一个 WebSocket 帧。按帧当报文解析会**静默丢掉**第二条。
     */
    @Test
    fun `一个缓冲区里的多条报文被逐条切出`() {
        val pings = MqttCodec.pingReq() + MqttCodec.pingReq() + MqttCodec.pingReq()
        var offset = 0
        var count = 0
        while (true) {
            val parsed = MqttCodec.parse(pings, offset) ?: break
            assertEquals(MqttCodec.PINGREQ, parsed.first.type)
            offset += parsed.second
            count++
        }
        assertEquals(3, count)
        assertEquals(pings.size, offset)
    }

    /** 报文被切断时返回 null（调用方留着尾巴等下一片），**不许**抛也不许返回半个。 */
    @Test
    fun `不完整的报文返回 null 而不是半个报文`() {
        val full = MqttCodec.connect(clientId, 45, emptyList())
        for (cut in 1 until full.size) {
            assertNull("切到 $cut 字节时应当返回 null", MqttCodec.parse(full.copyOfRange(0, cut), 0))
        }
        // 一个字节都不少时能解出来
        val parsed = MqttCodec.parse(full, 0)!!
        assertEquals(MqttCodec.CONNECT, parsed.first.type)
        assertEquals(full.size, parsed.second)
    }

    // ---------------- CONNACK / SUBACK / PUBLISH ----------------

    @Test
    fun `CONNACK 解码出会话标志 原因码 与属性`() {
        // type=0x20, len=…, ackFlags=0, reason=0, props: user property ("a","b") + server keep alive 30
        val props = MqttCodec.encodeProperties(
            listOf(
                MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "a", "b"),
                MqttCodec.Prop.TwoByte(MqttCodec.PROP_SERVER_KEEP_ALIVE, 30),
            ),
        )
        val body = byteArrayOf(0x00, 0x00) + props
        val frame = MqttCodec.parse(MqttCodec.packet(MqttCodec.CONNACK, 0, body), 0)!!.first
        val ack = MqttCodec.decodeConnAck(frame)
        assertEquals(false, ack.sessionPresent)
        assertEquals(0, ack.reasonCode)
        assertEquals(30, ack.properties.serverKeepAlive)
        assertEquals("b", ack.properties.userProperty("a"))
    }

    /** 被拒的 CONNACK（原因码非 0）：必须能读出原因码与 `SERVER_REFERENCE`（重定向）。 */
    @Test
    fun `被拒的 CONNACK 能读出原因码与重定向地址`() {
        val props = MqttCodec.encodeProperties(
            listOf(MqttCodec.Prop.Utf8(MqttCodec.PROP_SERVER_REFERENCE, "mqtt://mu2.y.qq.com:443/ws/handshake")),
        )
        val body = byteArrayOf(0x00, 0x9D.toByte()) + props
        val frame = MqttCodec.parse(MqttCodec.packet(MqttCodec.CONNACK, 0, body), 0)!!.first
        val ack = MqttCodec.decodeConnAck(frame)
        assertEquals(0x9D, ack.reasonCode)
        assertEquals("mqtt://mu2.y.qq.com:443/ws/handshake", ack.properties.serverReference)
    }

    @Test
    fun `SUBACK 的成功与失败原因码都被区分`() {
        val ok = MqttCodec.packet(
            MqttCodec.SUBACK, 0,
            byteArrayOf(0x00, 0x01, 0x00) + byteArrayOf(0x00),
        )
        val okAck = MqttCodec.decodeSubAck(MqttCodec.parse(ok, 0)!!.first)
        assertEquals(1, okAck.packetId)
        assertEquals(listOf(0), okAck.reasonCodes)
        assertTrue(MqttCodec.subAckSucceeded(okAck.reasonCodes.first()))

        // 0x80 = 未指定错误，0x87 = 不被允许
        val bad = MqttCodec.packet(
            MqttCodec.SUBACK, 0,
            byteArrayOf(0x00, 0x01, 0x00) + byteArrayOf(0x87.toByte()),
        )
        val badAck = MqttCodec.decodeSubAck(MqttCodec.parse(bad, 0)!!.first)
        assertEquals(listOf(0x87), badAck.reasonCodes)
        assertTrue(!MqttCodec.subAckSucceeded(badAck.reasonCodes.first()))
    }

    /**
     * PUBLISH 的 `type` 在 **user property** 上、payload 才是 JSON。
     *
     * 这两个位置写反是本条链路最典型的错误：`type` 读成 null 会被当成无关报文丢掉，
     * 表现是「扫了码但界面永远停在等待」。
     */
    @Test
    fun `PUBLISH 的 type 从 user property 读出 payload 是 JSON`() {
        val payload = """{"cookies":{"qqmusic_uin":{"value":"123"},"qqmusic_key":{"value":"KEY"}}}"""
        val props = MqttCodec.encodeProperties(
            listOf(MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "type", "cookies")),
        )
        val topic = "management.qrcode_login/$qr"
        // QoS 0 ⇒ 首字节 0x30，没有包标识
        val body = MqttCodec.encodeString(topic) + props + payload.toByteArray(Charsets.UTF_8)
        val frame = MqttCodec.parse(MqttCodec.packet(MqttCodec.PUBLISH, 0, body), 0)!!.first

        val publish = MqttCodec.decodePublish(frame)
        assertEquals(topic, publish.topicName)
        assertNull(publish.packetId)
        assertEquals("cookies", publish.properties.userProperty("type"))
        assertEquals(payload, publish.payload.toString(Charsets.UTF_8))
    }

    /** QoS 1 的 PUBLISH 带 2 字节包标识 —— 漏读它会把包标识当成话题长度，全盘错位。 */
    @Test
    fun `QoS 1 的 PUBLISH 会读掉包标识`() {
        val topic = "t"
        val props = MqttCodec.encodeProperties(
            listOf(MqttCodec.Prop.PairProp(MqttCodec.PROP_USER_PROPERTY, "type", "scanned")),
        )
        val body = MqttCodec.encodeString(topic) + byteArrayOf(0x00, 0x2A) + props +
            "{}".toByteArray(Charsets.UTF_8)
        val frame = MqttCodec.parse(MqttCodec.packet(MqttCodec.PUBLISH, 0x02, body), 0)!!.first
        val publish = MqttCodec.decodePublish(frame)
        assertEquals(topic, publish.topicName)
        assertEquals(42, publish.packetId)
        assertEquals("scanned", publish.properties.userProperty("type"))
    }

    /** 话题名为空（服务端用 topic alias）时返回 null，而不是空串 —— 两者语义不同。 */
    @Test
    fun `话题名为空的 PUBLISH 返回 null 话题`() {
        val props = MqttCodec.encodeProperties(emptyList())
        val body = MqttCodec.encodeString("") + props + "{}".toByteArray(Charsets.UTF_8)
        val frame = MqttCodec.parse(MqttCodec.packet(MqttCodec.PUBLISH, 0, body), 0)!!.first
        assertNull(MqttCodec.decodePublish(frame).topicName)
    }
}
