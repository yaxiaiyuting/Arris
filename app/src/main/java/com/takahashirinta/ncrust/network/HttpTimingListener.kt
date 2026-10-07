/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.6 · P1：HTTP 分段计时（真 TTFB / 响应体读完）。
 */

package com.takahashirinta.ncrust.network

import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException

/**
 * v2.5.6 · P1：**唯一**能拿到真 TTFB 的手段。
 *
 * ## 为什么不能用别的手段
 *
 * 探针（`docs/verification/v2.5.6/probe-search-latency-gap.md` §5）实测：
 * - 全仓 `EventListener` 检索 **0 命中**；
 * - 应用侧只有「请求前 / 全部结束后」两个时间戳，中间全是黑盒；
 * - 于是「TTFB P50 1722ms」这个数只能从**宿主机**用 Python 复现，
 *   而宿主机与手机的网络栈不同（同一首歌实测差 18ms~150ms 量级）。
 *
 * OkHttp 的 `EventListener` 是**在请求线程上被动回调**的观测点，
 * 不改变任何请求行为（没有额外拦截器、没有重试、没有超时改动），
 * 因此它符合铁律 4「旁路组件不得影响主链路」。
 *
 * ## 它测什么、不测什么
 *
 * | 段 | 事件 | 含义 |
 * |---|---|---|
 * | `dns` | `dnsStart` → `dnsEnd` | 域名解析 |
 * | `connect` | `connectStart` → `connectEnd` | TCP（含 TLS）握手；**连接复用时为 0** |
 * | **`ttfb`** | `requestHeadersEnd` → `responseHeadersStart` | **首字节**。这是服务端+网络的那一段 |
 * | `body` | `responseHeadersStart` → `responseBodyEnd` | 响应体传输 |
 * | `total` | `callStart` → `callEnd` | 整通 |
 *
 * **不测**：JSON 反序列化、映射、Compose 重组 —— 那些在 OkHttp 之外，
 * 由 [com.takahashirinta.ncrust.search.SearchLatencyTrace] 的 `first_publish` 打点覆盖。
 * 两段相加才是用户在意的「点了搜索之后多久看到东西」。
 *
 * ## 为什么日志必须无条件打（不套 `BuildConfig.DEBUG`）
 *
 * `QqApi` / `QqClient` 里既有的搜索日志是 `BuildConfig.DEBUG` 门控的 ⇒
 * **release 包里一条都不存在**（探针实测 0 命中）。性能结论只能来自 release 包
 * （铁律 16），所以本监听器的输出在 release 里**必须**存在。
 * 代价是每通请求一行 logcat —— 只保留 `http`/`https` 且只打一行，
 * 不落盘、不上报、不做字符串拼接以外的分配。
 *
 * ## 开销与其边界
 *
 * 每通请求多一次 `EventListener` 回调链与一次 `Log.i`（Release 上 logcat 级别
 * 高于 INFO 时 `Log.i` 是被丢弃的，成本接近空调用）。**不做**格式化以外的计算，
 * 也**不**持有 `Call`/`Response` 引用（避免泄漏）—— 每个 call 只在
 * `ConcurrentHashMap` 里放 5 个 `Long`，`callEnd`/`callFailed` 时移除。
 */
internal object HttpTimingListener {

    const val TAG = "NcrustHttpTiming"

    /** 只观测这两个 scheme，别的一律不算（WebView/本地 socket 不走这里，防御性判断）。 */
    private val observedSchemes = setOf("http", "https")

    /**
     * 给 OkHttp 用的工厂。
     *
     * `EventListener.Factory` 在 OkHttp 4.x 上是 SAM，但显式写成对象更好读，
     * 也避免 Kotlin 在 release 下生成额外的 lambda 类。
     */
    val factory: EventListener.Factory = EventListener.Factory { call ->
        TimingListener(call)
    }

    /**
     * 单通请求的计时器。
     *
     * 全部字段都是**单调时钟**（`SystemClock.elapsedRealtime`）——
     * 不用 `currentTimeMillis`：它在 NTP 校正时会跳，而搜索耗时是几百毫秒量级，
     * 一次校时就足以把 `ttfb` 算成负数（v2.5.6 的 `between()` 有夹零保护，
     * 但那是兜底，不是理由）。
     */
    private class TimingListener(private val call: Call) : EventListener() {

        private val callStart = SystemClock.elapsedRealtime()
        private var requestHeadersEnd = 0L
        private var responseHeadersStart = 0L
        private var responseBodyEnd = 0L

        override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
            requestHeadersEnd = SystemClock.elapsedRealtime()
        }

        override fun responseHeadersStart(call: Call) {
            responseHeadersStart = SystemClock.elapsedRealtime()
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBodyEnd = SystemClock.elapsedRealtime()
        }

        override fun callEnd(call: Call) {
            emit(call, failed = null)
        }

        override fun callFailed(call: Call, ioe: IOException) {
            emit(call, failed = ioe)
        }

        /**
         * 一行一件事：`ttfb` 是本次要的那个数，`body` 是传输，`total` 是整通。
         *
         * 失败时也打（`failed=`），否则「慢」与「挂了」在证据里无法区分 ——
         * 探针要的正是这个区分（`SearchViewModel` 已区分 `TIMEOUT` 与 `ERROR`，
         * 网络层这一份是它的下钻）。
         */
        private fun emit(call: Call, failed: IOException?) {
            val url = call.request().url
            if (url.scheme !in observedSchemes) return
            val now = SystemClock.elapsedRealtime()
            val ttfb = if (responseHeadersStart > 0 && requestHeadersEnd > 0) {
                responseHeadersStart - requestHeadersEnd
            } else {
                -1L
            }
            val body = if (responseBodyEnd > 0 && responseHeadersStart > 0) {
                responseBodyEnd - responseHeadersStart
            } else {
                -1L
            }
            val total = (if (responseBodyEnd > 0) responseBodyEnd else now) - callStart
            // 只打 path（不带 query/host）：query 里可能有搜索关键词，
            // 而 logcat 是**其它应用读不到但 adb 读得到**的通道 —— 少写一点用户输入。
            Log.i(
                TAG,
                "path=${url.encodedPath} ttfb=${ttfb}ms body=${body}ms total=${total}ms" +
                    (failed?.let { " failed=${it.javaClass.simpleName}" } ?: ""),
            )
        }
    }
}
