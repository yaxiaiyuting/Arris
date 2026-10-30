/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WSS 版中继客户端（v3.8.0 · 一起听公网）。
 *
 * 与 [com.takahashirinta.ncrust.listen.RoomRelayClient]（裸 TCP）**共用同一套 [RoomWire] 编解码**，
 * 只有传输方式不同：局域网直连用 TCP，公网走 Cloudflare Worker 的 WebSocket。
 *
 * ## 为什么要两个实现
 *
 * TCP 那版是为"同一 WiFi、没有服务器"的场景写的（也用来跑 JVM 单测）。
 * 公网必须走 WSS：能穿透 NAT、能复用 443、也能享受 Worker 的免费额度模型
 * （**发出去的消息不计费**，进来的按 20:1 折算）。
 */
class RoomRelayWs(
    private val url: String,
    private val client: OkHttpClient = defaultClient(),
    /** 可选：把中继域名固定到这些 IP（绕开被污染的 DNS）。 */
    fixedIps: List<String> = emptyList(),
) {
    private val fixedIpsForHost: List<String> = fixedIps
    private var socket: WebSocket? = null

    @Volatile var onMessage: ((RoomWire) -> Unit)? = null
    @Volatile var onClosed: ((code: Int, reason: String) -> Unit)? = null
    @Volatile var onOpen: (() -> Unit)? = null

    /** 连接；失败通过 [onClosed] 回调（不抛异常，调用方不用 try/catch）。 */
    fun connect() {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        // 固定 IP：域名留给 SNI/Host，连接目标换成给到的 IP（DNS 被污染时的通路）
        val useClient = if (fixedIpsForHost.isEmpty()) client else client.newBuilder()
            .dns(com.takahashirinta.ncrust.network.AddressPreference.fixedFor(
                java.net.URI(url).host, fixedIpsForHost,
            ))
            .build()
        socket = useClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open $url")
                onOpen?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = RoomWire.decode(text) ?: return
                onMessage?.invoke(msg)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure ${t.message} http=${response?.code}")
                onClosed?.invoke(response?.code ?: -1, t.message.orEmpty())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed $code $reason")
                onClosed?.invoke(code, reason)
            }
        })
    }

    fun send(message: RoomWire): Boolean = socket?.send(message.encode()) ?: false

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }

    companion object {
        private const val TAG = "RoomRelayWs"

        /** 用应用自己的 UA：Cloudflare 会拦 `Python-urllib` 这类默认 UA（实测 403）。 */
        private const val UA = "Arris/1.0 (Android; listen-together)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 优先 IPv4：真机（华为平板 / 部分移动网络）能解析出 IPv6 但连不通，
            // 表现为 "Failed to connect to …/2001::…:443"。OkHttp 默认会逐个尝试，
            // 但失败重试会拖到超时；这里直接把 IPv6 排到后面。
            .dns(com.takahashirinta.ncrust.network.AddressPreference.ipv4First)
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接不设读超时
            .build()
    }
}
