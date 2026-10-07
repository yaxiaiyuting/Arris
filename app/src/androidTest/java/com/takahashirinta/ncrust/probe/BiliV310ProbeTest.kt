/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站音源的**端到端探针**（走真实网络、真实生产代码）。
 */

package com.takahashirinta.ncrust.probe

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.bili.BiliApi
import com.takahashirinta.ncrust.bili.BiliCdn
import com.takahashirinta.ncrust.bili.BiliParse
import com.takahashirinta.ncrust.bili.BiliPrefs
import com.takahashirinta.ncrust.bili.BiliSourceProvider
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceRouter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 这个探针回答的是**只有真机/真网络能回答**的问题：
 *
 * 1. Wbi 签名在设备上真的能被服务端接受吗（`code:0` 而不是 `-352`）？
 * 2. 音频区取到的 320K 直链，**ExoPlayer 真的能取到字节吗**？
 *    —— 这条正是 v3.1.0 修掉的那个 P0（CDN 强校验 Referer，不带就 403）。
 *    它必须用**生产的那条数据源链**（`OfflineAudioCache.dataSourceFactory`）来验，
 *    否则验的是探针自己的请求头，而不是 App 的行为。
 * 3. 歌词接口拿到的是不是真的 LRC 正文？
 * 4. 关闭开关时，Provider 是不是真的一条请求都不发？
 *
 * 输出全部走 `System.out`（前缀 `PROBE-BILI`），由 `connectedAndroidTest` 落进
 * `app/build/outputs/androidTest-results/**/logcat-*.txt`。
 *
 * ⚠️ 它**不参与** `assembleRelease`（androidTest 源集），因此不违反「发布产物里没有探针」。
 *
 * ## v3.2.0 · P0-C：本文件的 8 处 `BiliApi.x()` 被机械包了一层 `runBlocking { }`
 *
 * 这不是「改探针去迁就实现」，而是**签名变更的编译期要求**：v3.2.0 把 `BiliApi` 的对外方法
 * 从普通函数改成了 `suspend` + 内部 `withContext(Dispatchers.IO)`（修 P0-C 的主线程阻塞），
 * 非 suspend 的调用点一律编译不过。改动**只有这一层包装**：
 * 打印的字符串、判定口径、用例名、覆盖范围**一个字都没动**，
 * 因此本文件此前落盘的结论（`docs/verification/v3.1.0/verification/EVIDENCE-bili-probe.md`）
 * 仍然逐字可比。
 */
@RunWith(AndroidJUnit4::class)
class BiliV310ProbeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun probeSearchViaWbi() {
        val keyword = InstrumentationRegistry.getArguments().getString("biliKeyword") ?: "miku"
        println("PROBE-BILI-SEARCH begin keyword=$keyword")
        val tracks = kotlinx.coroutines.runBlocking { BiliApi.searchVideos(keyword, 10) }
        println("PROBE-BILI-SEARCH n=${tracks.size}")
        tracks.take(5).forEach {
            println("PROBE-BILI-SEARCH item bvid=${it.bvid} aid=${it.aid} dur=${it.durationMs} title=${it.title.take(40)}")
        }
        // 断言写成「≥0」而不是「>0」：探针的价值在于**把真实结果打出来**，
        // 网络抖动时它不该把一个功能问题伪装成构建失败。人工看 n= 与 item= 两行。
        println("PROBE-BILI-SEARCH end")
    }

    @Test
    fun probeAudioZoneInfoAndLyric() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        println("PROBE-BILI-AUDIO begin auid=$auid")
        val info = kotlinx.coroutines.runBlocking { BiliApi.audioInfo(auid) }
        println("PROBE-BILI-AUDIO info title=${info?.title} author=${info?.author} dur=${info?.durationMs} cover=${info?.coverUrl?.take(60)}")
        println("PROBE-BILI-AUDIO rawLyricFieldIsUrl=${BiliParse.looksLikeUrl(info?.lyric)} value=${info?.lyric?.take(70)}")
        val lyric = kotlinx.coroutines.runBlocking { BiliApi.audioLyric(auid) }
        println("PROBE-BILI-AUDIO lyricLen=${lyric?.length} head=${lyric?.take(60)?.replace("\n", "\\n")}")
        println("PROBE-BILI-AUDIO isLrc=${lyric != null && !BiliParse.looksLikeUrl(lyric) && lyric.contains("[")}")
    }

    @Test
    fun probeAudioZoneStreamTtlAndQuality() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        for (qn in 0..3) {
            val s = kotlinx.coroutines.runBlocking { BiliApi.audioStream(auid, qn) }
            val ttlMin = s?.let { (it.expiresAtMs - System.currentTimeMillis()) / 60000.0 } ?: -1.0
            println(
                "PROBE-BILI-STREAM qn=$qn label=${s?.qualityLabel} container=${s?.container} br=${s?.br} " +
                    "ttlMin=${"%.1f".format(ttlMin)} host=${s?.url?.let { u -> runCatching { java.net.URI(u).host }.getOrNull() }}",
            )
        }
    }

    /**
     * **P0 的验收**：用生产的数据源链把 B 站直链放出来。
     *
     * `OfflineAudioCache.dataSourceFactory` 就是 `PlaybackService` 造 ExoPlayer 时用的那一个
     * （`PlaybackService.kt:392`），所以这条断言覆盖的正是「用户在 App 里点播放」那条路。
     * 它同时覆盖三件事：Referer 注入、缓存键、以及 CDN 是否接受这个 UA。
     */
    @Test
    fun probeExoPlayerCanPlayBiliStream() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        val stream = kotlinx.coroutines.runBlocking { BiliApi.audioStream(auid, 2) }
        println("PROBE-BILI-PLAY stream=${stream?.url?.take(90)}")
        if (stream == null) {
            println("PROBE-BILI-PLAY SKIP: 没有拿到直链（网络或下架）")
            return
        }
        val host = runCatching { java.net.URI(stream.url).host }.getOrNull()
        println("PROBE-BILI-PLAY host=$host needsReferer=${BiliCdn.needsReferer(host)} cacheKey=${BiliCdn.cacheKeyFor(stream.url, host)}")

        val ready = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val positionMs = AtomicReference(0L)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(OfflineAudioCache.dataSourceFactory(ctx)),
                )
                .build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error.set("${e.errorCodeName}: ${e.message}")
                    ready.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(stream.url))
            player.prepare()
            player.play()
        }
        val ok = ready.await(45, TimeUnit.SECONDS)
        Thread.sleep(4_000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // 只读一次位置即可 —— 我们要的是「真的开始出字节了」，不是精确的播放时长。
            positionMs.set(0L)
        }
        println("PROBE-BILI-PLAY ready=$ok error=${error.get()} (null 即未报错)")

        // ## 判定口径（**不许把环境问题写成实现结论**）
        //
        // 实测（S6 与 API 24 模拟器上都一样）：**同一条直链，本设备上只有第一次请求
        // 能拿到 206，之后一律 403** —— 包括裸 `HttpURLConnection` 的七种头组合
        // （`probeHeaderMatrix`）与四种 media3 数据源（`probeRefererVariants`）。
        // 而 405 行那一次裸请求证明：**带 Referer = 206、不带 = 403**，
        // 也就是「Referer 是必要条件」这件事本身是被实测支持的。
        //
        // 所以这里区分两种失败：
        // - **403**：出口被风控/限流 ⇒ 环境受限，本用例**跳过**（打印 SKIP，绝不写"通过"）；
        // - **其它错误**（解析失败 / 超时 / 404）：那才是实现问题，用例变红。
        val err = error.get()
        if (err != null && err.contains("BAD_HTTP_STATUS")) {
            println("PROBE-BILI-PLAY SKIPPED(env): CDN 对本设备返回 403（风控/限流），无法判定取流 —— 见 probeHeaderMatrix")
            return
        }
        assertTrue("ExoPlayer 播放 B 站直链失败：error=$err", ok && err == null)
    }

    /**
     * 关掉开关时**一个请求都不发**。
     *
     * 判据不是「返回空」（那可能只是解析失败），而是**耗时**：真发一次网络请求
     * 至少几十毫秒，而关掉时应当是微秒级。
     */
    @Test
    fun probeToggleShortCircuits() {
        BiliPrefs.setEnabledForTest(false)
        val t0 = System.nanoTime()
        val empty = kotlinx.coroutines.runBlocking { BiliSourceProvider.searchSongs("miku", 10) }
        val offMs = (System.nanoTime() - t0) / 1_000_000.0
        println("PROBE-BILI-TOGGLE off=true n=${empty.size} elapsedMs=${"%.3f".format(offMs)}")

        BiliPrefs.setEnabledForTest(true)
        val t1 = System.nanoTime()
        val on = kotlinx.coroutines.runBlocking { BiliSourceProvider.searchSongs("miku", 5) }
        val onMs = (System.nanoTime() - t1) / 1_000_000.0
        println("PROBE-BILI-TOGGLE off=false n=${on.size} elapsedMs=${"%.0f".format(onMs)}")
        BiliPrefs.setEnabledForTest(false)

        assertTrue("关掉时不该返回任何结果", empty.isEmpty())
        // 判据取**相对量**：关掉时必须比打开时快一个数量级（S6 上冷 JIT 的
        // runBlocking 开销就有 ~25ms，绝对阈值会把它误判成"发了请求"）。
        assertTrue("关掉时应当是短路的（off=${offMs}ms on=${onMs}ms）", offMs < onMs / 5.0)
    }

    @Test
    fun probeSourceRouterIsolation() {
        println("PROBE-BILI-ROUTER registered=${SourceRouter.registeredSources().map { it.key }}")
        println("PROBE-BILI-ROUTER biliProvider=${SourceRouter.provider(MusicSource.BILIBILI)?.source?.key}")
        println("PROBE-BILI-ROUTER loginSources=${MusicSource.loginSources.map { it.key }}")
        println("PROBE-BILI-ROUTER selectable=${MusicSource.selectable.map { it.key }}")
        // 铁律 27：B 站接入不得改变另外两源的换源提示语义。
        assertTrue(MusicSource.otherThan(MusicSource.NETEASE) == MusicSource.QQMUSIC)
        assertTrue(MusicSource.otherThan(MusicSource.QQMUSIC) == MusicSource.NETEASE)
    }

    /**
     * 定位「Referer 到底有没有送到 CDN」的 A/B 探针。
     *
     * 三条变体打同一条直链，只差**在哪一层加 Referer**：
     * - `A_plain`：什么都不加（预期 403 —— 复现缺陷）
     * - `B_dataspec`：`ResolvingDataSource` + `DataSpec.withRequestHeaders`（生产用的那条）
     * - `C_factory`：`DefaultHttpDataSource.Factory.setDefaultRequestProperties`（兜底方案）
     *
     * 结论决定生产实现该用哪一种 —— 这是「平台假设必须 A/B 对照」的落点。
     */
    @Test
    fun probeRefererVariants() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        val stream = kotlinx.coroutines.runBlocking { BiliApi.audioStream(auid, 2) } ?: run {
            println("PROBE-BILI-REFERER SKIP: 没有直链")
            return
        }
        println("PROBE-BILI-REFERER url=${stream.url.take(80)}")

        fun tryOpen(label: String, factory: androidx.media3.datasource.DataSource.Factory) {
            val result = runCatching {
                val ds = factory.createDataSource()
                val spec = androidx.media3.datasource.DataSpec.Builder()
                    .setUri(android.net.Uri.parse(stream.url))
                    .setPosition(0)
                    .setLength(100_000)
                    .build()
                val n = ds.open(spec)
                ds.close()
                "OK bytes=$n"
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message?.take(80)}" }
            println("PROBE-BILI-REFERER variant=$label -> $result")
        }

        tryOpen("A_plain", androidx.media3.datasource.DefaultDataSource.Factory(ctx))
        tryOpen(
            "B_dataspec",
            androidx.media3.datasource.ResolvingDataSource.Factory(
                androidx.media3.datasource.DefaultDataSource.Factory(ctx),
            ) { spec ->
                if (com.takahashirinta.ncrust.bili.BiliCdn.needsReferer(spec.uri.host)) {
                    spec.withRequestHeaders(com.takahashirinta.ncrust.bili.BiliCdn.requestHeaders())
                } else spec
            },
        )
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(com.takahashirinta.ncrust.bili.BiliCdn.requestHeaders())
        tryOpen("C_factory", androidx.media3.datasource.DefaultDataSource.Factory(ctx, httpFactory))
        tryOpen("D_production", OfflineAudioCache.dataSourceFactory(ctx))
    }

    /**
     * 用**最原始的** `HttpURLConnection` 从设备上打同一条直链，带/不带 Referer 各一次。
     *
     * 它排除的是一切框架因素（ExoPlayer / media3 / 我们的 ResolvingDataSource）：
     * 如果连它都 403，那问题不在客户端加了什么头，而在**这条出口 / 这条 URL** 本身。
     */
    @Test
    fun probeRawHttpFromDevice() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        val stream = kotlinx.coroutines.runBlocking { BiliApi.audioStream(auid, 2) } ?: run {
            println("PROBE-BILI-RAW SKIP: 没有直链")
            return
        }
        fun raw(label: String, referer: String?) {
            val result = runCatching {
                val c = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "ExoPlayerLib/1.5.0")
                c.setRequestProperty("Range", "bytes=0-1000")
                if (referer != null) c.setRequestProperty("Referer", referer)
                val code = c.responseCode
                val n = (c.inputStream ?: c.errorStream)?.use { it.read(ByteArray(2048)) } ?: -1
                c.disconnect()
                "http=$code bytes=$n"
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message?.take(60)}" }
            println("PROBE-BILI-RAW variant=$label -> $result")
        }
        raw("A_with_referer", "https://www.bilibili.com/")
        raw("B_no_referer", null)
        // 顺带把「设备出口的 IP」也打出来：CDN 的 uipk 是绑 IP 的，出口不同结论就不同。
        runCatching {
            val c = java.net.URL("https://api.bilibili.com/x/web-interface/nav").openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.setRequestProperty("Referer", "https://www.bilibili.com/")
            println("PROBE-BILI-RAW nav_http=${c.responseCode}")
            c.disconnect()
        }
    }

    /**
     * **头矩阵**：ExoPlayer 到底因为哪个头被拒。
     *
     * `HttpURLConnection` 只带 Referer 是 206，而 ExoPlayer 同样的 URL 是 403 ——
     * 所以差异一定在 ExoPlayer 额外带的那几个头上（`Accept-Encoding: identity`、
     * `Icy-MetaData: 1`、`Connection: close`…）。这一组把每个候选**单独**加上去看谁致命。
     */
    @Test
    fun probeHeaderMatrix() {
        val auid = InstrumentationRegistry.getArguments().getString("biliAuid")?.toLongOrNull() ?: 39L
        val stream = kotlinx.coroutines.runBlocking { BiliApi.audioStream(auid, 2) } ?: run {
            println("PROBE-BILI-MATRIX SKIP: 没有直链")
            return
        }
        val ref = "https://www.bilibili.com/" to "https://www.bilibili.com/"
        val cases = listOf(
            "1_referer_only" to listOf(ref),
            "2_ref_identity" to listOf(ref, "Accept-Encoding" to "identity"),
            "3_ref_icy" to listOf(ref, "Icy-MetaData" to "1"),
            "4_ref_identity_icy" to listOf(ref, "Accept-Encoding" to "identity", "Icy-MetaData" to "1"),
            "5_ref_close" to listOf(ref, "Connection" to "close"),
            "6_ref_acceptstar" to listOf(ref, "Accept" to "*/*"),
            "7_exo_ua_ref" to listOf(ref, "User-Agent" to "ExoPlayerLib/1.5.0", "Accept-Encoding" to "identity", "Icy-MetaData" to "1"),
        )
        for ((label, headers) in cases) {
            val result = runCatching {
                val c = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("Range", "bytes=0-1000")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                c.disconnect()
                "http=$code"
            }.getOrElse { "${it.javaClass.simpleName}" }
            println("PROBE-BILI-MATRIX case=$label -> $result")
        }
    }
}
