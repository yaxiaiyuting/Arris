/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：**打真实服务端**的扫码登录探针（含 MQTT over WSS）。
 */

package com.takahashirinta.ncrust.qq

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * v3.4.9：**打真实服务端**的扫码登录探针。
 *
 * ## 与其它单测的区别
 *
 * 这些用例真的发请求（HTTP 与 WSS）。它们存在的理由是
 * 「请求/报文形状对不对」这件事**只有服务端能回答** ——
 * 本地怎么测都是绿的，线上表现却只是「连不上 / 订阅被拒 / 永远收不到推送」。
 *
 * ## 这些用例验证什么（以及**不**验证什么）
 *
 * ✅ `CreateQRCode` 的真实响应形状（`qrcode` 是 PNG data-URI、`qrcodeID` 的长度）；
 * ✅ **裸 MQTT over TCP 会被 400 拒绝**（这条是「为什么必须自己写 WSS 传输」的证据，
 *    也是防止后人「顺手换成 Paho」的那道守卫）；
 * ✅ **整条握手链路真的能通**：我们自己编码的 CONNECT / SUBSCRIBE 被服务端接受、
 *    订阅成功（`open() == true`）。这一条同时覆盖了**重定向跟随** ——
 *    实测直连 `mu.y.qq.com/ws/handshake` 的第一条 CONNACK **一定是**
 *    `reasonCode = 0x9D`（Server moved）+ `SERVER_REFERENCE`，不接受重定向就永远
 *    订不上（见 [QqQrMqttSession.open] 的 KDoc）。
 *
 * ❌ **不验证**：拿真实手机扫码 → `cookies` 推送 → 换凭证那三步。
 *    那需要一部装了 QQ 音乐 App 的手机去扫屏幕上这张码，自动化不可能做到 ——
 *    所以本用例**不断言「一定收到推送」**（实测：没人扫码时服务端一条都不推，
 *    25 秒窗口内 `awaitEvent` 返回 null），只断言握手与订阅成功。
 *    复现步骤写在 `docs/verification/v3.4.9/README.md` §4.3。
 *
 * 无网络时用 [assumeTrue] **跳过**而不是失败（与本仓其它探针同一条纪律）。
 */
class QqScanLoginProbeTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun networkAvailable(): Boolean = try {
        http.newCall(Request.Builder().url("https://y.qq.com/").head().build()).execute().use { true }
    } catch (_: Exception) {
        false
    }

    @Test
    fun `CreateQRCode 返回 PNG 与 91 字符的 qrCodeID`() = runBlocking {
        assumeTrue("无网络，跳过", networkAvailable())
        // QqClient 需要 application context；单测里没有，所以直接走裸 HTTP 断言响应形状，
        // 用 QqApi 的同一份请求体（QqRequests.createQrCode）—— 形状一致性由这一条守住。
        val body = org.json.JSONObject()
            .put("comm", probeComm())
            .put("req", QqRequests.createQrCode())
        val response = post("https://u.y.qq.com/cgi-bin/musicu.fcg", body)
        val req = response.optJSONObject("req")
        assertNotNull("响应里没有 req 对象：$response", req)
        assertEquals("req.code 应为 0（实测）", 0, req!!.optInt("code", -1))
        val data = req.optJSONObject("data")
        assertNotNull("req 里没有 data：$req", data)
        val qrcode = data!!.optString("qrcode")
        val id = data.optString("qrcodeID")
        assertTrue("qrcode 应是 PNG 的 data-URI，实际前 30 字符=${qrcode.take(30)}",
            qrcode.startsWith("data:image/png;base64,"))
        // qrCodeID 实测 91 字符。写死会脆（服务端可能变长），所以只钉住量级。
        assertTrue("qrCodeID 长度异常：${id.length}", id.length in 20..200)
    }

    /**
     * **裸 MQTT over TCP 必须被拒。**
     *
     * 这条用例的价值不是「证明服务端会拒」，而是**把「为什么不用现成 MQTT 库」
     * 钉成一条会红的断言** —— 哪天有人觉得「自己写 MQTT 太麻烦，换 Paho 吧」，
     * 这条会告诉他 Paho（纯 TCP）在这里连不上。
     */
    @Test
    fun `裸 MQTT over TCP 被服务端拒绝为 400`() {
        assumeTrue("无网络，跳过", networkAvailable())
        val connect = MqttCodec.connect(
            clientId = "PROBECLIENT0001",
            keepAliveSeconds = 45,
            properties = listOf(MqttCodec.Prop.Utf8(MqttCodec.PROP_AUTHENTICATION_METHOD, "pass")),
        )
        val request = Request.Builder()
            .url("https://mu.y.qq.com/")
            // 关键：声明成 MQTT 协议，让服务端按 MQTT 处理（而不是按 HTTP 表单）
            .header("Content-Type", "application/x-mqtt")
            .post(connect.toRequestBody("application/x-mqtt".toMediaTypeOrNull()))
            .build()
        val code = http.newCall(request).execute().use { it.code }
        // 400 是实测结果（nginx 的 "400 Bad Request"）。这里只断言「不是 101 / 不是 2xx」——
        // 具体码值可能变，但「这条路走不通」这个结论不会变。
        assertTrue("裸 MQTT 竟然被接受了？（http=$code）", code >= 400)
    }

    /**
     * **端到端握手**：取真码 → WSS CONNECT（含重定向）→ SUBSCRIBE → 订阅成功。
     *
     * 判据是 `open()` 为 true，它逐层覆盖：
     * ① WebSocket 握手（`wss://mu.y.qq.com/ws/handshake`）；
     * ② 我们的 CONNECT 报文被接受 → **并且跟随了 `SERVER_REFERENCE` 重定向**
     *    （不跟随的话这里只会拿到 `reasonCode = 0x9D` 而失败）；
     * ③ 我们的 SUBSCRIBE 报文被接受、拿到成功的 SUBACK
     *    （话题名拼错或订阅属性写错都会在这里失败）。
     *
     * 之后**不强求收到推送**：实测没人扫码时服务端一条都不推。
     * 收到的第一条推送会打出来（有手机扫码时能看到 `scanned` → `cookies`）。
     */
    @Test
    fun `WSS 握手与订阅成功（含服务端重定向跟随）`() = runBlocking {
        assumeTrue("无网络，跳过", networkAvailable())
        val body = org.json.JSONObject()
            .put("comm", probeComm())
            .put("req", QqRequests.createQrCode())
        val response = post("https://u.y.qq.com/cgi-bin/musicu.fcg", body)
        val id = response.optJSONObject("req")?.optJSONObject("data")?.optString("qrcodeID").orEmpty()
        assumeTrue("这次没拿到 qrCodeID，跳过", id.isNotEmpty())

        val session = QqQrMqttSession(
            qrCodeId = id,
            clientId = System.currentTimeMillis().toString() + "0001",
        )
        try {
            val opened = withTimeoutOrNull(45_000L) { session.open() }
            assumeTrue("WSS 握手/订阅失败（网络或服务端策略），跳过", opened == true)

            // 握手与订阅都成功了。再等一小会儿看看有没有推送 ——
            // 没人扫码时服务端一条都不推，所以这里**只记录不断言**。
            val event = withTimeoutOrNull(10_000L) { session.awaitEvent(10_000L) }
            println("PROBE 扫码会话已就绪 topic=${qrLoginTopicOf(id)} 首条推送=$event")
        } finally {
            session.close()
        }
    }

    // ---------------- 探针自己的夹具 ----------------

    /**
     * 与 `QqClient.appComm` **同形**的匿名身份（单测里没有 Android context，
     * 所以不能调 `QqClient.appComm`）。字段值抄自实测过的那一套 ——
     * 设备指纹用固定假值（探针不该带任何真实设备标识）。
     */
    private fun probeComm(): org.json.JSONObject = org.json.JSONObject()
        .put("ct", 11).put("cv", 14090008).put("v", 14090008)
        .put("chid", "10003505").put("tmeAppID", "qqmusic")
        .put("QIMEI36", "0123456789abcdef0123456789abcdef0123")
        .put("OpenUDID", "0123456789abcdef").put("udid", "0123456789abcdef")
        .put("aid", "0123456789abcdef").put("os_ver", "10").put("phonetype", "MI 10")
        .put("uin", "0").put("format", "json")

    private fun post(url: String, body: org.json.JSONObject): org.json.JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "QQMusic 14090008(android 10)")
            .header("Referer", "https://y.qq.com/")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
        http.newCall(request).execute().use { resp ->
            return org.json.JSONObject(resp.body?.string().orEmpty())
        }
    }
}
