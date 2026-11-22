/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.1.10：**`RST_STREAM` 类失败要按「流级」处理，不是「地址族级」**。
 *
 * ## 真机证据（PCL110 / Android 17 / CMCC Wi-Fi，2026-11-22 23:5x，v4.1.9 / vc241）
 *
 * 3 首歌的取链，**3/3 完全同型**（同一份 logcat，见报告）：
 *
 * ```
 * fetch start songId=26239571 level=jymaster
 * url/v1 ttfb=-1ms total=331ms failed=<R8 混淆后的 StreamResetException>
 *        host=interface3.music.163.com fam=v4 conn=138ms tls=70ms reused=false
 *        tried=117.135.207.67:443 msg=stream was reset: CANCEL
 * url/v1 ttfb=274ms total=406ms            ← h1 回退成功
 * level=jymaster ok in 741ms songId=26239571
 * ```
 *
 * 即：**新建的 h2 连接上的第一个流会被对端用 `CANCEL` 复位**（~120ms 后），
 * 而 `awaitPlaybackRead` 的 HTTP/1.1 回退**每次都能救回来**。
 * 代价是每首歌白付一次连接 + 一次往返（+400ms 级），并且每首歌都在服务端
 * 记一笔 `RST_STREAM` —— 这正是任务里「eapi 侧频繁 REFUSED_STREAM / CANCEL」的一部分。
 *
 * ## 本类做什么
 *
 * 记住「哪个 host 最近用 h2 被复位过」，在 [TTL_MS] 内对该 host **直接用 HTTP/1.1**。
 * 三条纪律：
 *
 * 1. **只由真实复位触发**（[record] 只在异常 message 是 `stream was reset: …` 时被调用），
 *    不做任何「猜这个网不通 h2」的预判；
 * 2. **有 TTL**（[TTL_MS] = 5 分钟），到点自动回到 h2 —— v3.7.6 已经把「全局强制 h1」
 *    撤过一次（h2 本身是好的，那次的问题是请求风暴），所以这里只做**按 host、限时**的偏置；
 * 3. **换网清空**（[clear]，由 `SongUrlFetcher.onNetworkRouteChanged` 调）：
 *    换一张网可能换一整套中间设备，旧记忆不作数。
 *
 * 纯逻辑 + 时间注入 ⇒ JVM 单测直接钉住 TTL 边界与分类规则。
 */
internal object StreamResetMemo {

    /** 见过一次复位之后，对这个 host 优先走 HTTP/1.1 的时长。 */
    const val TTL_MS = 5 * 60_000L

    /** 表上限：本应用的活跃 host 不超过 6 个，16 已经足够且不会长。 */
    private const val MAX_HOSTS = 16

    private val seen = ConcurrentHashMap<String, Long>()

    /**
     * 这条异常是不是「流级复位」（`REFUSED_STREAM` / `CANCEL` / `INTERNAL_ERROR` …）。
     *
     * 只看 message：OkHttp 的 `StreamResetException` 在 release 里被 R8 改名
     * （实测日志里 `failed=C`），**类名不可依赖**；`stream was reset: <CODE>` 是稳定文案。
     * 同理 `REFUSED_STREAM` 也可能以裸文案出现（旧实现里就是这么判的）。
     */
    fun isStreamReset(e: Throwable?): Boolean {
        val message = e?.message ?: return false
        if (message.startsWith("stream was reset:", ignoreCase = true)) return true
        return message.contains("REFUSED_STREAM") || message.contains("stream was reset")
    }

    /** 记录一次复位；同一 host 只保留最近一次的时间戳。 */
    fun record(host: String, nowMs: Long) {
        if (host.isEmpty()) return
        if (seen.size >= MAX_HOSTS && !seen.containsKey(host)) seen.clear()
        seen[host] = nowMs
    }

    /** 这个 host 现在是否应该直接用 HTTP/1.1。 */
    fun prefersHttp1(host: String, nowMs: Long): Boolean {
        val at = seen[host] ?: return false
        if (nowMs - at > TTL_MS) {
            seen.remove(host)
            return false
        }
        return true
    }

    fun clear() {
        seen.clear()
    }

    /** 单测 / 日志用：当前有记忆的 host 数。 */
    internal fun size(): Int = seen.size
}
