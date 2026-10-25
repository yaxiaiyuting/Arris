/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.takahashirinta.ncrust.auth.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Debug-only: h2 vs h1.1 matrix for the eapi endpoints. Writes files/protocol-probe.json.
 * Never ships in release (lives in src/debug and only debug manifest declares it).
 */
class ProtocolProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { runMatrix() }
            val out = JSONObject().put("rows", JSONArray(rows))
            openFileOutput("protocol-probe.json", MODE_PRIVATE).use { it.write(out.toString(2).toByteArray()) }
            finish()
        }
    }

    private fun client(protocol: Protocol, ownPool: Boolean) = OkHttpClient.Builder()
        // OkHttp 要求列表里必须有 HTTP_1_1（h2 通过 ALPN 协商）；记录实际协商结果。
        .protocols(if (protocol == Protocol.HTTP_2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1))
        .apply { if (ownPool) connectionPool(okhttp3.ConnectionPool()) }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun rawRequest(url: String, cookie: String): Request {
        val params = com.takahashirinta.ncrust.network.crypto.EapiCrypto.encryptParams(
            url, mapOf("ids" to "[419485656]", "level" to "lossless", "encodeType" to "flac"))
        return Request.Builder().url(url)
            .post(FormBody.Builder().add("params", params).build())
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 Chrome/91.0.4472.164")
            .header("Referer", "https://music.163.com/")
            .header("Cookie", listOfNotNull(cookie.takeIf { it.isNotBlank() },
                ClientIdentity.extraCookieFor(cookie).takeIf { it.isNotBlank() }).joinToString("; "))
            .build()
    }

    private fun measure(rows: MutableList<JSONObject>, name: String, client: OkHttpClient, request: Request) {
        val started = System.currentTimeMillis()
        val row = JSONObject().put("case", name)
        try {
            client.newCall(request).execute().use { r ->
                row.put("code", r.code).put("proto", r.protocol.toString())
                    .put("ms", System.currentTimeMillis() - started).put("bodyLen", r.body?.string()?.length ?: 0)
            }
        } catch (e: Exception) {
            row.put("ms", System.currentTimeMillis() - started)
                .put("error", e.javaClass.simpleName + ": " + (e.message ?: "").take(90))
        }
        rows += row
    }

    private fun probeSharedPool(name: String, path: String) {
        val cookie = CookieManager.getCookie(this) ?: ""
        val c = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(RetrofitClient.sharedConnectionPool)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        measure(sharedRows, name, c, rawRequest("https://interface3.music.163.com" + path, cookie))
    }
    private fun probeWithListener(name: String, path: String) {
        val cookie = CookieManager.getCookie(this) ?: ""
        val c = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool())
            .eventListenerFactory(HttpTimingListener.factory)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        measure(sharedRows, name, c, rawRequest("https://interface3.music.163.com" + path, cookie))
    }
    private fun probeDispatcher(name: String, path: String) {
        val cookie = CookieManager.getCookie(this) ?: ""
        val c = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool())
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 12 })
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        measure(sharedRows, name, c, rawRequest("https://interface3.music.163.com" + path, cookie))
    }
    private fun probeDefaultClient(name: String, path: String) {
        val cookie = CookieManager.getCookie(this) ?: ""
        // 用应用真实的 plainClient 配置，但强制 h2：等价于"修复前"的状态。
        val c = RetrofitClient.probeClientWithProtocols(
            listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        measure(sharedRows, name, c, rawRequest("https://interface3.music.163.com" + path, cookie))
    }
    private val sharedRows = mutableListOf<JSONObject>()

    /**
     * 走**真实取链函数**连续取 10 首：验证 h2 在应用代码路径下（含可取消、传输失败即停、
     * 全局冷却）不会退化成"每次点击六档全试"。记录每首耗时、实际档位与是否失败。
     */
    private suspend fun stressFetch(): List<JSONObject> {
        RetrofitClient.init(this)
        CookieManager.init(this)
        val ids = listOf(419485656L, 27198266L, 4438153L, 511975460L, 1280569L,
            5222671L, 455653437L, 570837763L, 1808235517L, 29393417L)
        val out = mutableListOf<JSONObject>()
        val t0 = System.currentTimeMillis()
        for (id in ids) {
            val started = System.currentTimeMillis()
            val row = JSONObject().put("case", "S fetch $id").put("songId", id)
            try {
                val r = com.takahashirinta.ncrust.player.SongUrlFetcher.fetch(id, "jymaster")
                row.put("granted", r?.actualLevel ?: "(null)")
                    .put("br", r?.br ?: 0L).put("transportFail",
                        com.takahashirinta.ncrust.player.SongUrlFetcher.lastAttemptFailedOnTransport(id))
            } catch (e: Exception) {
                row.put("error", e.javaClass.simpleName + ": " + (e.message ?: "").take(60))
            }
            row.put("ms", System.currentTimeMillis() - started)
            out += row
        }
        out += JSONObject().put("case", "S total").put("ms", System.currentTimeMillis() - t0)
        return out
    }

    private suspend fun runMatrix(): List<JSONObject> {
        val cookie = CookieManager.getCookie(this) ?: ""
        val rows = mutableListOf<JSONObject>()
        suspend fun probe(
            name: String, protocol: Protocol, host: String, path: String,
            fullHeaders: Boolean, realParams: Boolean, songId: Long,
        ) {
            val url = host + path
            val params = if (realParams) {
                com.takahashirinta.ncrust.network.crypto.EapiCrypto.encryptParams(url, mapOf("ids" to "[$songId]", "level" to "lossless", "encodeType" to "flac"))
            } else "00"
            val b = Request.Builder().url(url).post(FormBody.Builder().add("params", params).build())
            if (fullHeaders) {
                b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 Chrome/91.0.4472.164")
                b.header("Referer", "https://music.163.com/")
                b.header("Cookie", listOfNotNull(cookie.takeIf { it.isNotBlank() },
                    ClientIdentity.extraCookieFor(cookie).takeIf { it.isNotBlank() }).joinToString("; "))
            } else {
                b.header("User-Agent", "Arris-probe")
            }
            val started = System.currentTimeMillis()
            val row = JSONObject().put("case", name).put("protocol", protocol.toString())
                .put("host", host).put("path", path).put("fullHeaders", fullHeaders)
                .put("cookieLen", if (fullHeaders) cookie.length else 0)
            try {
                client(protocol, ownPool = true).newCall(b.build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    row.put("code", r.code).put("proto", r.protocol.toString())
                        .put("ms", System.currentTimeMillis() - started).put("bodyLen", body.length)
                        .put("head", body.take(80))
                }
            } catch (e: Exception) {
                row.put("ms", System.currentTimeMillis() - started)
                    .put("error", e.javaClass.simpleName + ": " + (e.message ?: "").take(80))
            }
            rows += row
        }

        val urlPath = "/eapi/song/enhance/player/url/v1"
        probe("A h2 full real", Protocol.HTTP_2, "https://interface3.music.163.com", urlPath, true, true, 419485656)
        probe("B h2 full tinyBody", Protocol.HTTP_2, "https://interface3.music.163.com", urlPath, true, false, 419485656)
        probe("C h2 minimal tinyBody", Protocol.HTTP_2, "https://interface3.music.163.com", urlPath, false, false, 419485656)
        probe("D h2 noCookie real", Protocol.HTTP_2, "https://interface3.music.163.com", urlPath, true, true, 419485656)
        probe("E h1 full real (control)", Protocol.HTTP_1_1, "https://interface3.music.163.com", urlPath, true, true, 419485656)
        probe("F h2 other path", Protocol.HTTP_2, "https://interface3.music.163.com", "/eapi/v3/song/detail", true, true, 419485656)
        probe("G h2 interface host", Protocol.HTTP_2, "https://interface.music.163.com", "/eapi/w/nuser/account/get", true, false, 0)
        probe("H h2 interface3 root", Protocol.HTTP_2, "https://interface3.music.163.com", "/", false, false, 0)
        // 逐项复现"应用里为什么会失败"：一次只改一个因子。
        probeSharedPool("I shared pool", urlPath)
        probeWithListener("J timing listener", urlPath)
        probeDispatcher("K perHost=12", urlPath)
        probeDefaultClient("L baseClient-derived", urlPath)
        rows += sharedRows
        rows += stressFetch()
        return rows
    }
}
