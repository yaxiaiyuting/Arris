/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0-C/P0-D：B 站搜索**调度**探针（真实网络 + 真实生产代码）。
 */

package com.takahashirinta.ncrust.probe

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.bili.BiliPrefs
import com.takahashirinta.ncrust.bili.BiliSourceProvider
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.source.trackKey
import com.takahashirinta.ncrust.source.musicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v3.2.0 的 P0-C（B 站搜不到歌）与 P0-D（切「只搜 B 站」卡死）都指向同一件事：
 * **B 站那条腿的同步阻塞网络调用被发在了主线程上**。
 *
 * 这个探针不测「接口通不通」（那由 `BiliV310ProbeTest` 负责，且 host 侧已实测通过），
 * 它只回答一个**只有设备能回答**的问题：
 *
 * > 同一段生产代码（`SourceRouter.searchSongs(BILIBILI, …)`），
 * > 在**主线程**上调用与在 **IO 线程**上调用，结果与耗时有差别吗？
 *
 * 两种可能的失败形状都算「探针命中」：
 * - **立即失败**（毫秒级返回 0 条）⇒ 主线程上的 socket 操作被 Android 的
 *   `BlockGuard` 政策直接拦掉（`NetworkOnMainThreadException`），异常被
 *   `BiliApi.searchVideos` 的 `catch (e: Exception)` 吞掉 ⇒ 用户看到「B站 0 首」；
 * - **长时间阻塞**（≥ 一个 RTT，甚至到 30s 超时）⇒ 主线程真的被网络 IO 冻住
 *   ⇒ 用户看到「卡死 / 没有加载动画 / 点不动」。
 *
 * 两者的**修法是同一条**（把 B 站调用挪到 `Dispatchers.IO`），但**现象不同**，
 * 所以必须把「实际是哪一种」如实记录下来，而不是二选一猜一个。
 *
 * 输出全部走 `System.out`（前缀 `PROBE-BILI320`），由 `connectedAndroidTest` 落进
 * `app/build/outputs/androidTest-results/**/`。异常类名还会出现在同目录的
 * `logcat-*.txt` 里（`BiliApi` 的 `Log.w(TAG, "search failed: …", e)` 是无条件的）。
 *
 * ⚠️ 它**不参与** `assembleRelease`（androidTest 源集），因此不违反「发布产物里没有探针」。
 * ⚠️ **只打印，不 assert 网络结果** —— 出口被风控时它不该把一个环境问题伪装成构建失败。
 * 唯一的断言是「id 唯一」这类**与网络无关的结构性质**。
 */
@RunWith(AndroidJUnit4::class)
class BiliV320ProbeTest {

    private val inst get() = InstrumentationRegistry.getInstrumentation()

    /**
     * P0-C 的核心判决：关键词搜索在主线程 vs 在 IO 线程。
     *
     * 判据是**一对数字**（n / elapsedMs），不是单个 n：
     * - 主线程那条如果 `n=0 且 elapsedMs≈0`，说明请求根本没有发出去（被政策拦掉）；
     * - 如果 `n=0 且 elapsedMs` 很大，说明主线程被真的阻塞过（P0-D 的「卡死」形状）；
     * - IO 那条只要 `n>0`，就证明**接口、签名、解析、映射全是好的**，
     *   唯一的差别只在「在哪个线程上调」。
     */
    @Test
    fun probeSearchDispatchMainVsIo() {
        val keyword = InstrumentationRegistry.getArguments().getString("biliKeyword") ?: "miku"
        BiliPrefs.setEnabledForTest(true)
        println("PROBE-BILI320-SEARCH begin keyword=$keyword")

        // ---- A：主线程（= App 里 `viewModelScope.launch`(Main.immediate) + `async` 的调用形态）
        var mainCount = -1
        var mainError: String? = null
        val tMain = SystemClock.elapsedRealtime()
        inst.runOnMainSync {
            try {
                mainCount = runBlocking {
                    SourceRouter.searchSongs(MusicSource.BILIBILI, keyword, 5)
                }.size
            } catch (e: Throwable) {
                mainError = e.javaClass.name + ": " + e.message
            }
        }
        val mainMs = SystemClock.elapsedRealtime() - tMain
        println("PROBE-BILI320-SEARCH on=main n=$mainCount elapsedMs=$mainMs error=$mainError")

        // ---- B：IO 线程（修好之后的形态）
        var ioCount = -1
        var ioError: String? = null
        val tIo = SystemClock.elapsedRealtime()
        runBlocking {
            try {
                ioCount = withContext(Dispatchers.IO) {
                    SourceRouter.searchSongs(MusicSource.BILIBILI, keyword, 5)
                }.size
            } catch (e: Throwable) {
                ioError = e.javaClass.name + ": " + e.message
            }
        }
        val ioMs = SystemClock.elapsedRealtime() - tIo
        println("PROBE-BILI320-SEARCH on=io n=$ioCount elapsedMs=$ioMs error=$ioError")
        println("PROBE-BILI320-SEARCH end")
    }

    /**
     * P0-C · 音频区那条腿（`au<auid>` 关键词 ⇒ `BiliApi.audioInfo`）同样在主线程上跑一次。
     *
     * 它证明「不是只有视频搜索这一条路有问题」—— `BiliSourceProvider` 的
     * **每一条**对外路径都直接调 `BiliApi`，而 `BiliApi` 的每个方法都是
     * `client.newCall(...).execute()`（同步阻塞）。只修搜索那一处是不够的。
     */
    @Test
    fun probeAudioZoneDetailMainVsIo() {
        // 用 v3.1.0 单测里那个真实存在的 auid；关键词形状必须带 `au` 前缀才会走音频区那条腿。
        val auKeyword = InstrumentationRegistry.getArguments().getString("biliAuKeyword") ?: "au2478206"
        BiliPrefs.setEnabledForTest(true)
        println("PROBE-BILI320-AUDIO begin keyword=$auKeyword")

        var mainCount = -1
        var mainError: String? = null
        val tMain = SystemClock.elapsedRealtime()
        inst.runOnMainSync {
            try {
                mainCount = runBlocking {
                    SourceRouter.searchSongs(MusicSource.BILIBILI, auKeyword, 1)
                }.size
            } catch (e: Throwable) {
                mainError = e.javaClass.name + ": " + e.message
            }
        }
        val mainMs = SystemClock.elapsedRealtime() - tMain

        var ioCount = -1
        var ioError: String? = null
        val tIo = SystemClock.elapsedRealtime()
        runBlocking {
            try {
                ioCount = withContext(Dispatchers.IO) {
                    SourceRouter.searchSongs(MusicSource.BILIBILI, auKeyword, 1)
                }.size
            } catch (e: Throwable) {
                ioError = e.javaClass.name + ": " + e.message
            }
        }
        val ioMs = SystemClock.elapsedRealtime() - tIo
        println("PROBE-BILI320-AUDIO on=main n=$mainCount elapsedMs=$mainMs error=$mainError")
        println("PROBE-BILI320-AUDIO on=io n=$ioCount elapsedMs=$ioMs error=$ioError")
        println("PROBE-BILI320-AUDIO end")
    }

    /**
     * P0-C · 搜索结果映射到 `TrackKey` / `LazyColumn` key 的正确性与**唯一性**。
     *
     * 这一条与网络无关（`SourceIds.biliId` / `trackKey` 都是纯函数），放在这里是因为
     * 它用的是**真实响应**跑出来的那一批 id —— `LazyColumn(key = { it.id })` 撞 key 会
     * 直接抛 `IllegalArgumentException: Key was already used`（P0-D 的「卡死」候选之一），
     * 而只有真实数据能证明它不会撞。
     */
    @Test
    fun probeSearchResultKeyMapping() {
        val keyword = InstrumentationRegistry.getArguments().getString("biliKeyword") ?: "miku"
        BiliPrefs.setEnabledForTest(true)
        val songs = runBlocking {
            withContext(Dispatchers.IO) { SourceRouter.searchSongs(MusicSource.BILIBILI, keyword, 20) }
        }
        println("PROBE-BILI320-KEYS n=${songs.size}")
        songs.take(5).forEach {
            println("PROBE-BILI320-KEYS item id=${it.id} source=${it.musicSource.key} trackKey=${it.trackKey} sourceId=${it.sourceId}")
        }
        val ids = songs.map { it.id }
        val trackKeys = songs.map { it.trackKey }
        println("PROBE-BILI320-KEYS idDistinct=${ids.distinct().size}/${ids.size} trackKeyDistinct=${trackKeys.distinct().size}/${trackKeys.size}")
        // 结构性质，与出口/网络无关 ⇒ 可以断言。
        org.junit.Assert.assertEquals("LazyColumn 的 key（item.id）必须唯一", ids.size, ids.distinct().size)
        org.junit.Assert.assertEquals("trackKey 必须唯一", trackKeys.size, trackKeys.distinct().size)
        org.junit.Assert.assertTrue(
            "B 站结果的音源必须是 bilibili",
            songs.all { it.musicSource == MusicSource.BILIBILI },
        )
        println("PROBE-BILI320-KEYS end")
    }

    /**
     * P0-C 的**机制**证据：本设备/本 targetSdk 下，主线程能不能做一次 socket 连接？
     *
     * 这条与 B 站代码无关（一次裸 `Socket.connect`），所以它给出的是一个**平台事实**：
     * 如果它抛 `NetworkOnMainThreadException`，那么 v3.1.0 的
     * `BiliApi.get()`（`client.newCall(request).execute()`）**只要落在主线程上就必然拿不到字节** ——
     * 而 `BiliApi.wbiKeys()` 的 `runCatching { get(NAV_URL) }.getOrNull()` 会把这个异常
     * **静默吞掉**并返回 null，于是表现为「B站 0 首、没有任何错误」。
     *
     * 反过来说：如果这里不抛、只是慢，那么 P0-D 的「卡死」就是主线程真被冻住。
     * 两种形状的修法相同（挪到 IO），但报告里必须写清是哪一种 —— 这条探针就是判据。
     */
    @Test
    fun probeMainThreadSocketPolicy() {
        var mainError: String? = null
        var mainConnected = false
        val t0 = SystemClock.elapsedRealtime()
        inst.runOnMainSync {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("api.bilibili.com", 443), 5_000)
                    mainConnected = s.isConnected
                }
            } catch (e: Throwable) {
                mainError = e.javaClass.name + ": " + e.message
            }
        }
        val mainMs = SystemClock.elapsedRealtime() - t0

        var ioError: String? = null
        var ioConnected = false
        val t1 = SystemClock.elapsedRealtime()
        runBlocking {
            withContext(Dispatchers.IO) {
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("api.bilibili.com", 443), 5_000)
                        ioConnected = s.isConnected
                    }
                } catch (e: Throwable) {
                    ioError = e.javaClass.name + ": " + e.message
                }
            }
        }
        val ioMs = SystemClock.elapsedRealtime() - t1
        println("PROBE-BILI320-SOCKET on=main connected=$mainConnected elapsedMs=$mainMs error=$mainError")
        println("PROBE-BILI320-SOCKET on=io connected=$ioConnected elapsedMs=$ioMs error=$ioError")
    }

    /**
     * P0-C 的**「修复前会怎样」判据**：用**同一个 HTTP 库的同一个调用形状**在主线程上发一次请求。
     *
     * v3.1.0 的 `BiliApi.get()` 就是 `OkHttpClient.newCall(request).execute()`（同步阻塞），
     * 而它在 `SearchViewModel` 的 `Main.immediate` 作用域里被调用。本探针不去改生产代码，
     * 而是**照抄那个调用形状**（同一个 URL / UA / Referer / 超时口径），在真机主线程上跑一次，
     * 把「会抛什么、耗时多少」如实打出来 —— 这就是「旧代码在真机上」的直接对照，
     * 比 [probeMainThreadSocketPolicy] 的裸 socket 更贴近生产路径。
     *
     * 预期（若成立）：主线程 **毫秒级** 抛 `NetworkOnMainThreadException`（不是「卡 30 秒」）——
     * 于是 v3.1.0 的「B站 0 首」是**立即失败**，而 P0-D 的「卡死」只能由 UI 结构（筛选档消失）解释。
     */
    @Test
    fun probeBlockingGetOnMainLikeOldCode() {
        val url = "https://api.bilibili.com/x/web-interface/nav"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        fun request() = okhttp3.Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .build()

        var mainError: String? = null
        var mainLen = -1
        val t0 = SystemClock.elapsedRealtime()
        inst.runOnMainSync {
            try {
                client.newCall(request()).execute().use { mainLen = it.body?.string()?.length ?: 0 }
            } catch (e: Throwable) {
                mainError = e.javaClass.name + ": " + e.message
            }
        }
        val mainMs = SystemClock.elapsedRealtime() - t0

        var ioError: String? = null
        var ioLen = -1
        val t1 = SystemClock.elapsedRealtime()
        runBlocking {
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request()).execute().use { ioLen = it.body?.string()?.length ?: 0 }
                } catch (e: Throwable) {
                    ioError = e.javaClass.name + ": " + e.message
                }
            }
        }
        val ioMs = SystemClock.elapsedRealtime() - t1
        println("PROBE-BILI320-RAWSYNC on=main bodyLen=$mainLen elapsedMs=$mainMs error=$mainError")
        println("PROBE-BILI320-RAWSYNC on=io bodyLen=$ioLen elapsedMs=$ioMs error=$ioError")
    }

    /**
     * P0-D · 关掉开关时**仍然一个请求都不发**（v3.1.0 铁律 24 的回归探针）。
     *
     * 本版把 `BiliApi` 的对外方法改成 `suspend` + 内部 `withContext(Dispatchers.IO)`，
     * 这条判据必须仍然是「先判开关、再谈调度」—— 判据的**顺序**不能被改动。
     */
    @Test
    fun probeToggleStillShortCircuits() {
        BiliPrefs.setEnabledForTest(false)
        val t0 = SystemClock.elapsedRealtime()
        val off = runBlocking { BiliSourceProvider.searchSongs("miku", 5) }
        val offMs = SystemClock.elapsedRealtime() - t0

        BiliPrefs.setEnabledForTest(true)
        val t1 = SystemClock.elapsedRealtime()
        val on = runBlocking { withContext(Dispatchers.IO) { BiliSourceProvider.searchSongs("miku", 5) } }
        val onMs = SystemClock.elapsedRealtime() - t1
        BiliPrefs.setEnabledForTest(false)

        println("PROBE-BILI320-TOGGLE off=true n=${off.size} elapsedMs=$offMs")
        println("PROBE-BILI320-TOGGLE off=false n=${on.size} elapsedMs=$onMs")
        // 判据取**相对量**（与 BiliV310ProbeTest 同口径）：关掉时必须是短路的。
        org.junit.Assert.assertTrue("关掉时不该有任何结果", off.isEmpty())
        org.junit.Assert.assertTrue("关掉时应当是短路的（off=${offMs}ms on=${onMs}ms）", offMs * 5 < onMs)
    }
}
