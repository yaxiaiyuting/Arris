/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.4 · P0：B 站取流的**真实请求头**探针（真实网络 + 真实生产数据源链）。
 */

package com.takahashirinta.ncrust.probe

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.bili.BiliApi
import com.takahashirinta.ncrust.bili.BiliCdn
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v3.2.4 的 P0（「B 站歌曲无法播放」）**先探针、后改码**。
 *
 * 这个探针只回答两个**只有设备能回答**的问题：
 *
 * 1. **App 真正发出去的 HTTP 头是什么？** —— 用一个本地 `ServerSocket` 把 media3 数据源链
 *    引过来，逐字读它写的请求行与每一个 header。这一条不能用 curl 代替：curl 的头是
 *    我们自己摆的，而 P0 的争议点恰恰是「media3 默认发什么」。
 * 2. **生产的那条数据源链（`OfflineAudioCache.dataSourceFactory`，也就是
 *    `PlaybackService.kt:392` 造 ExoPlayer 时用的那一个）打真实 B 站直链，拿到几？**
 *    这一条是验收口径：它成功 ⇒ 用户点播放就出声。
 *
 * 输出走 `System.out`（前缀 `PROBE-V324`），由 `connectedAndroidTest` 落进
 * `app/build/outputs/androidTest-results/` 下。它**不进** `assembleRelease`（androidTest 源集）。
 *
 * ⚠️ 本文件在**修复之前**就要能编译并跑出结论，所以它**不引用任何修复期才存在的新 API**：
 * 「换 UA」那一臂是在探针里就地搭的（`DefaultHttpDataSource.Factory().setUserAgent`），
 * 而不是调用生产代码的新重载。
 */
@RunWith(AndroidJUnit4::class)
class BiliV324ProbeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 与 `BiliApi.UA` 同值的桌面 UA（探针内自备，不依赖生产常量）。 */
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 用本地 HTTP 服务器俘获 **media3 数据源链自己写出来的** 完整请求头。
     *
     * 服务器不会返回任何可播放的字节（只回一个 403），所以这个用例**不判定播放成败**，
     * 它只把「默认发什么」这件事变成可存档的字符串。
     */
    @Test
    fun probeDefaultRequestHeaders() {
        val server = ServerSocket(0)
        val port = server.localPort
        val captured = AtomicReference<String>("(nothing captured)")
        val got = CountDownLatch(1)
        val reader = Thread {
            runCatching {
                val socket: Socket = server.accept()
                val lines = StringBuilder()
                BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8")).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        lines.append(line).append('\n')
                        if (line.isEmpty()) break
                    }
                }
                captured.set(lines.toString())
                socket.getOutputStream().write(
                    ("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(),
                )
                socket.getOutputStream().flush()
                socket.close()
            }
            got.countDown()
        }
        reader.isDaemon = true
        reader.start()

        // 与生产完全同构的一条链：OfflineAudioCache.dataSourceFactory（含 CacheDataSource +
        // ResolvingDataSource）→ DefaultDataSource → DefaultHttpDataSource。
        runCatching {
            val ds = OfflineAudioCache.dataSourceFactory(ctx).createDataSource()
            val spec = DataSpec.Builder()
                .setUri(Uri.parse("http://127.0.0.1:$port/probe.m4a"))
                .setPosition(0)
                .setLength(1024)
                .build()
            ds.open(spec)
            ds.close()
        }
        got.await(10, TimeUnit.SECONDS)
        runCatching { server.close() }

        val raw = captured.get()
        println("PROBE-V324-HEADERS-BEGIN")
        raw.trim().split('\n').forEach { println("PROBE-V324-HEADERS| $it") }
        println("PROBE-V324-HEADERS-END")
        val uaLine = raw.split('\n').firstOrNull { it.startsWith("User-Agent:", ignoreCase = true) }
        println("PROBE-V324-HEADERS ua_line=${uaLine ?: "(none)"}")
        println(
            "PROBE-V324-HEADERS ua_contains_android=" +
                (uaLine?.contains("android", ignoreCase = true) == true),
        )
        println(
            "PROBE-V324-HEADERS referer_line=" +
                (raw.split('\n').firstOrNull { it.startsWith("Referer:", ignoreCase = true) }
                    ?: "(none)"),
        )
    }

    /**
     * 打**真实 B 站直链**。四种配置各打一次，差别**只有一个变量**：
     *
     * | 配置 | 变量 |
     * |---|---|
     * | `A_production_as_is` | 现状（`BiliCdn.requestHeaders()` 只给 Referer，UA 由 media3 决定） |
     * | `B_probe_manual_ua` | 只把 UA 换成桌面 UA（**其余与 A 逐字相同**）—— 这一臂就是修复方向 |
     * | `C_probe_manual_ua_no_referer` | 在 B 的基础上去掉 Referer（证明 Referer 仍必需） |
     *
     * ⚠️ 判据是 **HTTP 状态码**，不是「有没有异常」。403 会以
     * `InvalidResponseCodeException` 抛出，探针把它翻成状态码再比。
     */
    @Test
    fun probeProductionChainAgainstRealCdn() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        val stream = runBlocking { BiliApi.audioStream(auid, 2) } ?: run {
            println("PROBE-V324-CDN SKIP: 没有拿到直链（网络或下架）")
            return
        }
        val host = runCatching { java.net.URI(stream.url).host }.getOrNull()
        println("PROBE-V324-CDN url=${stream.url.take(110)}")
        println(
            "PROBE-V324-CDN host=$host needsReferer=${BiliCdn.needsReferer(host)} " +
                "cacheKey=${BiliCdn.cacheKeyFor(stream.url, host)}",
        )
        println("PROBE-V324-CDN requestHeaders=${BiliCdn.requestHeaders()}")
        println("PROBE-V324-CDN probe_desktop_ua=$desktopUa")

        fun attempt(label: String, factory: DataSource.Factory) {
            val outcome = runCatching {
                val ds = factory.createDataSource()
                val spec = DataSpec.Builder()
                    .setUri(Uri.parse(stream.url))
                    .setPosition(0)
                    .setLength(100_000)
                    .build()
                val n = ds.open(spec)
                val buf = ByteArray(4096)
                val read = ds.read(buf, 0, buf.size)
                ds.close()
                "OK opened=$n firstRead=$read"
            }.getOrElse { e ->
                val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                "FAIL ${e.javaClass.simpleName} http=${code ?: "-"} msg=${e.message?.take(70)}"
            }
            println("PROBE-V324-CDN variant=$label -> $outcome")
        }

        /** 探针自搭的一条链：只比生产链多一个 `setUserAgent`。 */
        fun withUserAgent(ua: String, referer: String?): DataSource.Factory {
            val http = DefaultHttpDataSource.Factory().setUserAgent(ua)
            val upstream = ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx, http)) { spec ->
                if (referer != null && BiliCdn.needsReferer(spec.uri.host)) {
                    spec.withRequestHeaders(mapOf("Referer" to referer))
                } else {
                    spec
                }
            }
            return upstream
        }

        attempt("A_production_as_is", OfflineAudioCache.dataSourceFactory(ctx))
        attempt("B_probe_manual_ua", withUserAgent(desktopUa, BiliCdn.REFERER))
        attempt("C_probe_manual_ua_no_referer", withUserAgent(desktopUa, null))
    }

    /**
     * v3.2.4 · P0 的**修复验收**：生产数据源工厂必须
     * ①对 B 站媒体选出「带 B 站 UA 的那一支」，②对其余 URI 一个字节都不改。
     *
     * 用两个**记录型**假数据源替掉真实的 HTTP 层：这样断言的是**路由决策**本身
     * （真网络那部分由 [probeProductionChainAgainstRealCdn] 覆盖），
     * 而且完全不发请求。判据是 `open` 落到了哪一支、以及那一支收到的 `DataSpec` 里
     * 有没有 Referer/UA —— 「选源问一个判据、加头问另一个判据」这种错位会立刻被抓到。
     */
    @Test
    fun probeSourceRoutingDecision() {
        class Recording : DataSource {
            var opened = 0
            var sawReferer = false
            var sawUserAgent = false
            private var uri: android.net.Uri? = null
            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                opened++
                uri = dataSpec.uri
                sawReferer = dataSpec.httpRequestHeaders.keys.any { it.equals("Referer", true) }
                sawUserAgent = dataSpec.httpRequestHeaders.keys.any { it.equals("User-Agent", true) }
                return 0L
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
            override fun getUri(): android.net.Uri? = uri
            override fun close() = Unit
        }

        val plain = Recording()
        val bili = Recording()
        val factory = com.takahashirinta.ncrust.cache.SourceRoutingDataSource.Factory(
            plain = DataSource.Factory { plain },
            bili = DataSource.Factory { bili },
        )
        val ds = factory.createDataSource()

        fun probe(label: String, url: String) {
            val spec = DataSpec.Builder().setUri(Uri.parse(url)).build()
            ds.open(spec)
            ds.close()
            println("PROBE-V324-ROUTE case=$label plain=${plain.opened} bili=${bili.opened}")
        }

        com.takahashirinta.ncrust.bili.BiliCdn.clearLearnedHostsForTest()
        val base = "https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/abc-320k.m4a?deadline=1"
        probe("bili_whitelist_host", base)
        probe("netease", "https://m801.music.126.net/xx/abc.mp3")
        probe("qq", "https://isure.stream.qqmusic.qq.com/abc.m4a")
        // 第三方 PCDN：标记之前不命中、标记之后命中 —— 这一条正是 v3.2.4 补的缺口。
        val pcdn = "https://b-aaa.edge.mountaintoys.cn/upgcxcode/abc-320k.m4s?deadline=1"
        probe("pcdn_before_mark", pcdn)
        com.takahashirinta.ncrust.bili.BiliCdn.markStream(pcdn)
        probe("pcdn_after_mark", pcdn)

        println("PROBE-V324-ROUTE bili_saw_referer=${bili.sawReferer} bili_saw_ua=${bili.sawUserAgent}")
        println("PROBE-V324-ROUTE plain_saw_referer=${plain.sawReferer} plain_saw_ua=${plain.sawUserAgent}")
    }

    /**
     * 设备出口的原始 `HttpURLConnection`，UA 逐条换，**其余一个字节不动**。
     *
     * v3.1.0 的探针在这一步**手工写了 `User-Agent: ExoPlayerLib/1.5.0`**，
     * 于是「Referer 是唯一闸门」这个结论是带着第二个变量做出来的。
     * 这里把变量拆开：Referer 固定为 B 站的，只换 UA。
     */
    @Test
    fun probeRawUaMatrixFromDevice() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        val stream = runBlocking { BiliApi.audioStream(auid, 2) } ?: run {
            println("PROBE-V324-RAW SKIP: 没有直链")
            return
        }
        fun raw(label: String, userAgent: String?, referer: String?) {
            val result = runCatching {
                val c = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                if (userAgent != null) c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Range", "bytes=0-1023")
                if (referer != null) c.setRequestProperty("Referer", referer)
                val code = c.responseCode
                c.disconnect()
                "http=$code"
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message?.take(60)}" }
            println("PROBE-V324-RAW variant=$label -> $result")
        }
        val biliReferer = "https://www.bilibili.com/"
        raw("A_no_ua_header_bili_referer", null, biliReferer)
        raw("B_dalvik_ua_bili_referer", "Dalvik/2.1.0 (Linux; U; Android 13; probe)", biliReferer)
        raw("C_desktop_ua_bili_referer", desktopUa, biliReferer)
        raw("D_desktop_ua_no_referer", desktopUa, null)
        raw("E_exoplayer_1_5_ua_bili_referer", "ExoPlayerLib/1.5.0", biliReferer)
        raw("F_android_word_only_ua", "Android", biliReferer)
    }
}
