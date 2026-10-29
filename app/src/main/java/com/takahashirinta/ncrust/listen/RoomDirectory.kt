/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 扫描到的房间（[RoomDirectory.scan] 的结果项）。 */
data class RoomSummary(
    val roomId: String,
    val host: String,
    val members: Int,
    val title: String,
)

/**
 * 房间目录（v3.8.0）：`GET /rooms` → 当前活跃房间列表。
 *
 * 服务端用一个 Durable Object 维护"哪些房间现在有人"，超过 90 秒没心跳的房间会被清掉，
 * 所以这里的列表天然是"活的"——不需要客户端自己判断超时。
 */
object RoomDirectory {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 默认中继地址（我们部署的 Worker）。可在实验室里改。 */
    const val DEFAULT_BASE = "https://arris-listen.arris-listen.workers.dev"

    /** [base] 形如 `https://xxx.workers.dev` 或 `wss://xxx.workers.dev`。 */
    fun scan(base: String = DEFAULT_BASE): List<RoomSummary> {
        val http = base.replace("wss://", "https://").replace("ws://", "http://").trimEnd('/')
        val request = Request.Builder()
            .url("$http/rooms")
            .header("User-Agent", "Arris/1.0 (Android; 一起听)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val rooms = JSONObject(body).optJSONArray("rooms") ?: return emptyList()
            return (0 until rooms.length()).mapNotNull { i ->
                val o = rooms.optJSONObject(i) ?: return@mapNotNull null
                RoomSummary(
                    roomId = o.optString("roomId"),
                    host = o.optString("host"),
                    members = o.optInt("members"),
                    title = o.optString("title"),
                )
            }.filter { it.roomId.isNotBlank() }
        }
    }

    /** 把 base 转成房间 WebSocket 地址。 */
    fun roomUrl(base: String, roomId: String, role: String, name: String): String {
        val ws = base.replace("https://", "wss://").replace("http://", "ws://").trimEnd('/')
        val n = java.net.URLEncoder.encode(name, "UTF-8")
        return "$ws/room/$roomId?role=$role&name=$n"
    }
}
