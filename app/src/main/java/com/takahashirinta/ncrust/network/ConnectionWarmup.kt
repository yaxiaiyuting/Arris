/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P1-B：连接预热（DNS 预解析 + TCP/TLS 预建）。
 */

package com.takahashirinta.ncrust.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress

/**
 * 连接预热（v3.1.0 · P1-B）。**收益有上界，成本也极低，所以做；但它不是主要优化。**
 *
 * ## 收益从哪来（探针实测，不是估算）
 *
 * `docs/verification/v3.1.0/net-research/net-latency-breakdown.md` §4：
 *
 * ```
 * 冷连接：dns=19 connect=139 tls=99  ⇒ callStart→connectionAcquired = 162~261ms，total=509ms
 * 复用后：dns=-1  connect=-1  tls=-1 ⇒ wait = 2~9ms，ttfb 不变
 * ```
 *
 * 也就是「首通贵 ~365ms、之后每通省下这 365ms」。而 ncm 的搜索 / 取链 / 歌词分布在
 * **两个 host**（`music.163.com` 与 `interface3.music.163.com`），所以冷启动时
 * 取链的那一条路要**现付一次建连**。
 *
 * ## 做法（以及为什么不是「预建 SSE / 预热池」）
 *
 * OkHttp **没有**公开 API 能把一条 `Connection` 直接塞进池子里；能进池的只有
 * 「一通真实完成、且服务端没有 `Connection: close` 的请求」。所以预热 =
 * 对目标 host 发一个**无副作用、极小**的请求，让它自然回池：
 *
 * - 路径取 `/`（根路径）。它对本应用没有语义，返回 404 / 403 都一样 ——
 *   关键是**响应完整读完**（OkHttp 只有在响应体读完时才会把连接还回池）。
 * - 顺手 `InetAddress.getAllByName` 预解析域名：那 19~50ms 的 DNS 段也省掉，
 *   而且即使请求本身失败，解析结果也已经进了 JVM 的正向缓存。
 * - 短超时（[TIMEOUT_SECONDS]）：预热是**尽力而为**，绝不能自己变成一个慢请求。
 *
 * ## 三条边界（不许越）
 *
 * 1. **失败完全静默**：预热失败不影响任何功能（铁律 4）。这里连日志都只打 `Log.d`。
 * 2. **有界**：2 个 host、每个一次、每次 5 秒超时，总成本上界 ~10 秒，
 *    且跑在 `AppWarmup` 已有的 8 秒预算**之外**的独立协程里（谁都不等它）。
 * 3. **B 站不预热**：B 站音源默认关闭，未启用的音源不该产生任何流量
 *    （铁律 24 / 27 的具体落点）。
 */
object ConnectionWarmup {

    private const val TAG = "ConnectionWarmup"

    /** 预热请求的超时。**故意比业务请求短得多** —— 它自己慢下来就违背了目的。 */
    private const val TIMEOUT_SECONDS = 5L

    /**
     * 要预热的 host。**顺序即优先级**（第一个是取链的实际落点）。
     *
     * ## v3.3.2：从「手抄常量」改成**由业务端点推导**（本版修的性能缺陷）
     *
     * `interface3.music.163.com` 是 `SongUrlFetcher` 的取链 host
     * （`RetrofitClient.INTERFACE_URL`），而冷启动时**没有任何别的请求会碰它** ——
     * 它是这条优化里唯一真正「从零到一」的那一格。
     *
     * 旧实现是一张手抄的表，与业务端点**没有任何机制保证一致**：改了端点常量、
     * 或加了一个新 host，预热会静默地继续预热老的（甚至预热一个没人调的 host），
     * 而这类错**不会报错**，只会让优化等于零。现在输入直接取 `RetrofitClient`
     * 的端点常量，经 [WarmupTargetPlanner] 去重/有界，并由
     * `WarmupTargetPlannerTest` 钉住边界。
     *
     * 顺序：**取链域最先**（播放路径上最靠前、也最可能是冷启动后第一条），
     * 其次 eapi 默认域，最后 REST 域（首页/歌词已经在用它，冷启动时通常已经热了）。
     */
    private val HOSTS: List<String> by lazy {
        WarmupTargetPlanner.plan(
            candidates = listOf(
                RetrofitClient.INTERFACE_URL,   // 取链：interface3.music.163.com
                RetrofitClient.API_URL,         // eapi 默认域：interface.music.163.com
                RetrofitClient.BASE_URL,        // REST（歌词/详情）：music.163.com
            ),
            // B 站音源默认关闭，未启用的音源不该产生任何流量（铁律 24 / 27）。
            // 目前 B 站走的是它自己的客户端与 host，不在上面三项里；这条 skip 是**显式**的护栏：
            // 将来若把 B 站 host 加进 candidates，必须同时决定它在什么条件下才允许预热。
            skip = setOf("api.bilibili.com", "api.bilibili.com/"),
        )
    }

    /**
     * 预热用的客户端。
     *
     * ## v3.3.2：**必须与业务客户端同源**（本版修的性能缺陷，两处都错了）
     *
     * 旧实现在这里 `OkHttpClient.Builder().build()` 出了一个**第三方客户端**。
     * 要让预热真的有用，需要同时满足两个条件，缺一不可：
     *
     * 1. **共用连接池** —— OkHttp 的 `ConnectionPool` 是 client 的私有字段，
     *    不共享时预热建好的连接进了另一个池子，业务请求根本查不到；
     * 2. **共用 `sslSocketFactory` / `certificatePinner` 的实例** —— 光共享池子**不够**。
     *    `RealConnection.isEligible` 先比 `Address`，而 `Address.equalsNonHost` 包含这两项；
     *    `OkHttpClient.Builder.build()` 在未显式指定时会为**每个 client 各造一份**，
     *    于是「共池的两个独立 client」Address 永不相等，池子里有同一 host 的空闲连接也匹配不上。
     *    实测（OkHttp 4.12.0，真实打 `https://interface3.music.163.com/`）：
     *    各自 build + 共池 ⇒ 业务通仍然 `connect=true, 131ms`（又建一次）；
     *    从同一基座 `newBuilder()` 派生 ⇒ 业务通 `connect=false, 0ms`。
     *
     * 所以这里走 [RetrofitClient.warmupDerivedClient]：它与 `plainClient` / `restClient`
     * 同出一个基座，Address 兼容；同时保留自己的 [TIMEOUT_SECONDS] 短超时，
     * 也**不挂** `HttpTimingListener`（预热打的是 `GET /`，不是业务请求，
     * 让它进 `NcrustHttpTiming` 只会给「TTFB 花在哪」这本账添噪声）。
     */
    private val client: OkHttpClient by lazy {
        RetrofitClient.warmupDerivedClient(TIMEOUT_SECONDS)
    }

    /**
     * 预热全部 host。**调用方负责放进一个不等它的协程里。**
     *
     * 并发上限走 [BoundedParallel.DEFAULT_MAX_CONCURRENCY]：三个 host 用不上 4，
     * 但走同一个原语是为了让「并发上限只有一处定义」（本仓库的既有纪律）。
     */
    suspend fun warmUp() {
        // lambda 必须显式标注成 suspend：`runAll` 收的是 `suspend () -> T?`，
        // 而这里要调 `warmOne`（suspend）。让类型推断去猜会得到一个非挂起的 lambda。
        val tasks: List<Pair<String, suspend () -> Boolean?>> = HOSTS.map { host ->
            val url = "https://$host/"
            val block: suspend () -> Boolean? = {
                warmOne(url)
                true
            }
            "preconnect:$host" to block
        }
        runCatching { BoundedParallel.runAll(tasks) }
            .onFailure { Log.d(TAG, "warmup skipped: ${it.javaClass.simpleName}") }
    }

    /**
     * 预热单个 host。
     *
     * ⚠️ 这里**刻意不用 `runCatching`**：它捕获 `Throwable`，会把协程取消一起吞掉
     * （v2.5.2 点名的形状，`AppWarmup` 里也有一份同样注释）。显式 `catch (e: Exception)`，
     * 且 `CancellationException` 在 `Exception` 之下 —— 所以这里对取消的处理是
     * **先 rethrow、再兜住其余**，顺序不能反。
     */
    private suspend fun warmOne(url: String) = withContext(Dispatchers.IO) {
        // ① DNS：即使请求那一步失败，解析结果也已经进了 JVM 缓存（下一次 dns 段就是 0ms）。
        val host = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
        try {
            InetAddress.getAllByName(host)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "dns warmup failed: $host (${e.javaClass.simpleName})")
        }

        // ② TCP + TLS + 回池：**读完响应体**才会把连接还进池子。
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Arris-warmup/1.0")
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                resp.body?.string()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "preconnect failed: $url (${e.javaClass.simpleName})")
        }
    }
}
