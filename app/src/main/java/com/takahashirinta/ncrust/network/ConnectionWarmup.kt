/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
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
import java.util.concurrent.TimeUnit

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
 * 也就是「首通贵 ~365ms、之后每通省下这 365ms」。而网易云的搜索 / 取链 / 歌词分布在
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
     * 要预热的 host。**顺序即优先级**（前两个是取链与读接口的实际落点）。
     *
     * `interface3.music.163.com` 是 `SongUrlFetcher` 的取链 host
     * （`RetrofitClient.INTERFACE_URL`），而冷启动时**没有任何别的请求会碰它** ——
     * 它是这条优化里唯一真正「从零到一」的那一格。
     */
    private val HOSTS = listOf(
        "https://interface3.music.163.com/",
        "https://music.163.com/",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 预热全部 host。**调用方负责放进一个不等它的协程里。**
     *
     * 并发上限走 [BoundedParallel.DEFAULT_MAX_CONCURRENCY]：两个 host 用不上 4，
     * 但走同一个原语是为了让「并发上限只有一处定义」（本仓库的既有纪律）。
     */
    suspend fun warmUp() {
        // lambda 必须显式标注成 suspend：`runAll` 收的是 `suspend () -> T?`，
        // 而这里要调 `warmOne`（suspend）。让类型推断去猜会得到一个非挂起的 lambda。
        val tasks: List<Pair<String, suspend () -> Boolean?>> = HOSTS.map { url ->
            val block: suspend () -> Boolean? = {
                warmOne(url)
                true
            }
            "preconnect:$url" to block
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
            .header("User-Agent", "Ncrust-warmup/1.0")
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
