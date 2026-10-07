/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P0 网络调研探针：把「一通请求到底慢在哪一段」量出来。
 *
 * 只做观测，不改任何生产行为：本类不参与 assembleRelease，只跑 connectedAndroidTest。
 */

package com.takahashirinta.ncrust.probe

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 分段计时的落点。**全部是单调时钟**（`SystemClock.elapsedRealtime`）。
 *
 * 段与段的边界取 OkHttp 的 `EventListener`，与生产代码里的
 * `network/HttpTimingListener.kt` 同一套语义，只是把「请求头出去之前」那一段再切开。
 */
@RunWith(AndroidJUnit4::class)
class NetTimingProbeTest {

    private data class Sample(
        val label: String,
        val proto: String,
        val code: Int,
        val dnsMs: Long,
        val connectMs: Long,
        val tlsMs: Long,
        /** callStart → connectionAcquired。含排队 + DNS + TCP + TLS。 */
        val waitMs: Long,
        /** connectionAcquired → requestHeadersStart。 */
        val acquireToReqMs: Long,
        /** requestHeadersStart → requestHeadersEnd。真正把请求头写出去。 */
        val writeMs: Long,
        /** requestHeadersEnd → responseHeadersStart。 */
        val ttfbMs: Long,
        val bodyMs: Long,
        val totalMs: Long,
        val reused: Boolean,
        val err: String? = null,
    )

    private class Recorder(private val label: String) : EventListener() {
        val callStart = SystemClock.elapsedRealtime()
        var dnsStart = 0L
        var dnsEnd = 0L
        var connectStart = 0L
        var connectEnd = 0L
        var tlsStart = 0L
        var tlsEnd = 0L
        var acquired = 0L
        var reqStart = 0L
        var reqEnd = 0L
        var respStart = 0L
        var bodyEnd = 0L
        var reused = false
        var proto = "?"
        var code = -1

        override fun dnsStart(call: Call, domainName: String) { dnsStart = SystemClock.elapsedRealtime() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsEnd = SystemClock.elapsedRealtime()
        }
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connectStart = SystemClock.elapsedRealtime()
        }
        override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
            connectEnd = SystemClock.elapsedRealtime()
        }
        override fun secureConnectStart(call: Call) { tlsStart = SystemClock.elapsedRealtime() }
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) { tlsEnd = SystemClock.elapsedRealtime() }
        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
            // 复用判定不用 `Connection` 的 API：判据是「这一通没有发生 DNS 与 TCP 连接」，
            // 见 [finish]。这样对 OkHttp 4.x 的小版本差异不敏感。
            acquired = SystemClock.elapsedRealtime()
        }
        override fun requestHeadersStart(call: Call) { reqStart = SystemClock.elapsedRealtime() }
        override fun requestHeadersEnd(call: Call, request: Request) { reqEnd = SystemClock.elapsedRealtime() }
        override fun responseHeadersStart(call: Call) { respStart = SystemClock.elapsedRealtime() }
        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
            proto = response.protocol.toString()
            code = response.code
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) { bodyEnd = SystemClock.elapsedRealtime() }

        fun finish(err: String?): Sample {
            val now = SystemClock.elapsedRealtime()
            fun d(a: Long, b: Long): Long = if (a > 0 && b > 0 && b >= a) b - a else -1L
            return Sample(
                label = label,
                proto = proto,
                code = code,
                dnsMs = d(dnsStart, dnsEnd),
                connectMs = d(connectStart, connectEnd),
                tlsMs = d(tlsStart, tlsEnd),
                waitMs = d(callStart, acquired),
                acquireToReqMs = d(acquired, reqStart),
                writeMs = d(reqStart, reqEnd),
                ttfbMs = d(reqEnd, respStart),
                bodyMs = d(respStart, bodyEnd),
                totalMs = (if (bodyEnd > 0) bodyEnd else now) - callStart,
                // 「复用」的判据：这一通没有发生 DNS 与 TCP 连接。
                reused = dnsStart == 0L && connectStart == 0L,
                err = err,
            )
        }
    }

    /** OkHttp 4.x 的 `EventListener.Factory` 是 Java 接口，方法名是 `create`。 */
    private class Factory(private val label: String) : EventListener.Factory {
        val last = AtomicInteger(0)
        @Volatile var recorder: Recorder? = null
        override fun create(call: Call): EventListener =
            Recorder(label).also { recorder = it; last.incrementAndGet() }
    }

    private fun client(factory: EventListener.Factory): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory(factory)
        .build()

    private fun run(
        client: OkHttpClient,
        factory: Factory,
        label: String,
        request: Request,
    ): Sample {
        return try {
            client.newCall(request).execute().use { it.body?.string() }
            factory.recorder!!.finish(null)
        } catch (e: Exception) {
            factory.recorder?.finish("${e.javaClass.simpleName}:${e.message}") ?: Sample(
                label, "?", -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, false, e.javaClass.simpleName,
            )
        }
    }

    private fun pct(xs: List<Long>, p: Int): Long {
        val v = xs.filter { it >= 0 }.sorted()
        if (v.isEmpty()) return -1
        val idx = ((v.size - 1) * p / 100.0).toInt().coerceIn(0, v.size - 1)
        return v[idx]
    }

    private fun report(samples: List<Sample>) {
        println("PROBE-NET-BEGIN")
        for (s in samples) {
            println(
                "PROBE-NET sample=${s.label} proto=${s.proto} code=${s.code} reused=${s.reused} " +
                    "dns=${s.dnsMs} connect=${s.connectMs} tls=${s.tlsMs} " +
                    "wait=${s.waitMs} acquireToReq=${s.acquireToReqMs} write=${s.writeMs} " +
                    "ttfb=${s.ttfbMs} body=${s.bodyMs} total=${s.totalMs}" +
                    (s.err?.let { " err=$it" } ?: ""),
            )
        }
        val byLabel = samples.groupBy { it.label }
        for ((label, group) in byLabel) {
            val ok = group.filter { it.err == null }
            println(
                "PROBE-NET-STAT label=$label n=${group.size} ok=${ok.size} reused=${ok.count { it.reused }} " +
                    "total p50=${pct(ok.map { it.totalMs }, 50)} p95=${pct(ok.map { it.totalMs }, 95)} " +
                    "p99=${pct(ok.map { it.totalMs }, 99)} " +
                    "ttfb p50=${pct(ok.map { it.ttfbMs }, 50)} p95=${pct(ok.map { it.ttfbMs }, 95)} " +
                    "p99=${pct(ok.map { it.ttfbMs }, 99)} " +
                    "connect p50=${pct(ok.map { it.connectMs }, 50)} " +
                    "tls p50=${pct(ok.map { it.tlsMs }, 50)} " +
                    "wait p50=${pct(ok.map { it.waitMs }, 50)} p95=${pct(ok.map { it.waitMs }, 95)} " +
                    "dns p50=${pct(ok.map { it.dnsMs }, 50)} " +
                    "write p50=${pct(ok.map { it.writeMs }, 50)} " +
                    "acquireToReq p50=${pct(ok.map { it.acquireToReqMs }, 50)}",
            )
        }
        println("PROBE-NET-END")
    }

    private fun env() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        println("PROBE-NET-ENV sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL} " +
            "fingerprint=${android.os.Build.FINGERPRINT}")
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        println(
            "PROBE-NET-ENV network=" +
                (caps?.let { caps -> "wifi=${caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)} " +
                    "cell=${caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)} " +
                    "downKbps=${caps.linkDownstreamBandwidthKbps} upKbps=${caps.linkUpstreamBandwidthKbps}" } ?: "none"),
        )
    }

    @Test
    fun probeNetTiming() {
        val iterations = InstrumentationRegistry.getArguments().getString("netProbeIterations")?.toIntOrNull() ?: 6
        env()

        val get = { url: String -> Request.Builder().url(url).get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "https://music.163.com/")
            .build() }
        val post = { url: String, body: String, referer: String ->
            Request.Builder().url(url).post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", referer)
                .build()
        }

        val targets: List<Pair<String, Request>> = listOf(
            // ncm：搜索（用户报告「慢」的那条路）
            "netease.search" to get("https://music.163.com/api/cloudsearch/pc?s=%E6%99%B4%E5%A4%A9&type=1&limit=10"),
            // ncm：取链（播放关键路径）
            "netease.songurl" to post(
                "https://interface3.music.163.com/eapi/song/enhance/player/url/v1",
                "params=probe",
                "https://music.163.com/",
            ),
            // ncm：歌词（REST）
            "netease.lyric" to get("https://music.163.com/api/song/lyric?id=186016&lv=-1&kv=-1&tv=-1"),
            // qm：搜索
            "qq.search" to post(
                "https://u.y.qq.com/cgi-bin/musicu.fcg",
                "{\"comm\":{\"ct\":24,\"cv\":0},\"req\":{\"module\":\"music.search.SearchCgiService\",\"method\":\"DoSearchForQQMusicDesktop\",\"param\":{\"query\":\"晴天\",\"num_per_page\":10,\"page_num\":1}}}",
                "https://y.qq.com/",
            ),
            // B 站：nav（Wbi 密钥来源）
            "bili.nav" to get("https://api.bilibili.com/x/web-interface/nav"),
            // B 站：音频区歌曲信息
            "bili.audio.info" to get("https://www.bilibili.com/audio/music-service-c/web/song/info?sid=22760301"),
        )

        val samples = mutableListOf<Sample>()
        for ((label, request) in targets) {
            val factory = Factory(label)
            val c = client(factory)
            // 每个目标一个**新 client** ⇒ 第一通一定是冷连接，之后的通次才可能复用。
            for (i in 0 until iterations) {
                samples += run(c, factory, label, request)
            }
        }
        report(samples)
    }

    /**
     * 并发探针：同一主机上 6 通请求同时发出，看**排队等待**是否就是
     * v2.5.6 观察到的「请求头 30s 才出去」。
     *
     * OkHttp 的 `Dispatcher` 对同一 host 的并发上限是 5（`maxRequestsPerHost`），
     * 第 6 通会一直排队到前面有位置 —— 这一段在 `callStart → connectionAcquired`
     * 里表现为一个很大的 `wait`。本探针把它单独量出来。
     */
    @Test
    fun probeConcurrencyQueueing() {
        val n = InstrumentationRegistry.getArguments().getString("netProbeConcurrency")?.toIntOrNull() ?: 6
        val factory = Factory("netease.concurrent")
        val c = client(factory)
        val recorders = java.util.Collections.synchronizedList(mutableListOf<Recorder>())
        val perCall = EventListener.Factory { call -> Recorder("netease.concurrent").also { recorders += it } }
        val cc = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .eventListenerFactory(perCall)
            .build()
        val latch = CountDownLatch(n)
        val start = SystemClock.elapsedRealtime()
        println("PROBE-NET-CONCURRENCY-BEGIN n=$n dispatcherMaxPerHost=5")
        for (i in 0 until n) {
            Thread {
                try {
                    val req = Request.Builder()
                        .url("https://music.163.com/api/cloudsearch/pc?s=test$i&type=1&limit=1")
                        .header("User-Agent", "ncrust-probe")
                        .build()
                    cc.newCall(req).execute().use { it.body?.string() }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await(90, TimeUnit.SECONDS)
        val wall = SystemClock.elapsedRealtime() - start
        val samples = recorders.map { it.finish(null) }
        report(samples)
        println("PROBE-NET-CONCURRENCY wall=${wall}ms n=${samples.size} maxWait=${samples.maxOfOrNull { it.waitMs } ?: -1}")
        println("PROBE-NET-CONCURRENCY-END")
    }

    /**
     * B 站请求头 A/B 对照（v3.1.0 · P1 接入前的**必做**一步）。
     *
     * 第一轮探针里 B 站三条 URL **全部 HTTP 403**，而同一台设备上的 ncm/QQ 都是 200。
     * 唯一可疑的差异是探针给**所有**请求都带了 `Referer: https://music.163.com/` ——
     * 拿 ncm 的 Referer 去请求 B 站，是最典型的「跨站来源」形状。
     *
     * 本用例把「Referer / Origin / UA」三件事分开对照，回答：
     * **B 站接入能不能复用 ncm 那个 `CookieInterceptor`（它无条件注入 ncm Referer）？**
     */
    @Test
    fun probeBiliHeaders() {
        val url = "https://api.bilibili.com/x/web-interface/nav"
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val variants = listOf(
            Triple("A_netease_referer", "https://music.163.com/", false),
            Triple("B_bili_referer", "https://www.bilibili.com/", true),
            Triple("C_no_referer", "", false),
        )
        println("PROBE-BILI-HEADER-BEGIN")
        for ((label, referer, origin) in variants) {
            val factory = Factory(label)
            val c = client(factory)
            val b = Request.Builder().url(url).get().header("User-Agent", ua)
            if (referer.isNotEmpty()) b.header("Referer", referer)
            if (origin) b.header("Origin", "https://www.bilibili.com")
            // 正文必须看：HTTP 200 + body `code:-101` 是「未登录」（可接受），
            // HTTP 403 是「被挡在门外」（不可接受）—— 这两件事在接入方案里结论相反。
            var body = ""
            var http = -1
            try {
                c.newCall(b.build()).execute().use { resp ->
                    http = resp.code
                    body = resp.body?.string().orEmpty().take(160)
                }
            } catch (e: Exception) {
                body = "${e.javaClass.simpleName}:${e.message}"
            }
            val s = factory.recorder?.finish(null)
            println(
                "PROBE-BILI-HEADER variant=$label referer=$referer origin=$origin " +
                    "http=$http total=${s?.totalMs ?: -1} ttfb=${s?.ttfbMs ?: -1} body=$body",
            )
        }
        println("PROBE-BILI-HEADER-END")
    }

    /**
     * HTTP/2 多路复用是不是真的用上了（连接层现状调研的一格）：
     * 同一个 client 连发 3 通，看第 2/3 通有没有新建连接、走的什么协议。
     */
    @Test
    fun probeProtocolAndReuse() {
        val factory = Factory("reuse")
        val c = client(factory)
        val samples = mutableListOf<Sample>()
        repeat(3) {
            samples += run(
                c, factory, "reuse",
                Request.Builder().url("https://music.163.com/api/cloudsearch/pc?s=reuse&type=1&limit=1")
                    .header("User-Agent", "ncrust-probe").build(),
            )
        }
        report(samples)
    }
}
