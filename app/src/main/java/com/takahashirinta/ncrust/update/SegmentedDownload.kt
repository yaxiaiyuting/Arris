/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.update

import android.util.Log
import com.takahashirinta.ncrust.network.AddressPreference
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 分段多线程下载（v3.8.15）。
 *
 * ## 为什么需要它
 *
 * APK 16 MB，单连接在裸连（手机 → Cloudflare，RTT 450–560ms、丢包 50–66%）上要几分钟。
 * 服务端 `/get/` 路径支持 `Range`（实测 8 段并发各返回精确 1 MB），所以客户端可以并发拉分段。
 *
 * ## 边界（什么时候**不**用）
 *
 * - 服务端不支持 Range（探测不到 `Content-Range` 的总长）→ 交给原来的单流下载器；
 * - 目标已存在可续传的残包（`.part` + 校验标记）→ 也走单流：续传语义已经写好，
 *   不要为了快几秒去冒险拼包；
 * - 任何一段失败 → 全体取消，调用方回落到单流下载（可用、只是慢）。
 *
 * ## 正确性
 *
 * 每段用**自己的** `RandomAccessFile` 打开同一个文件、seek 到自己的区间写入 —— 区间互不重叠。
 * 收尾校验：文件长度必须等于探测到的总长，且 ETag 与探测时一致（不一致说明资产被替换）。
 */
internal object SegmentedDownload {

    private const val TAG = "SegmentedDownload"

    /** 并发段数：4 段在丢包链路上收益已经明显，再多对 CF 免费线路反而更容易被限。 */
    private const val SEGMENTS = 4

    /** 每段至少这么大，避免出现大量极小的段。 */
    /** 4.0.12：单条镜像的探测预算。取 2.5s —— 国内正常 RTT 500ms 内，死链也就多等这么久。 */
    private const val PROBE_TIMEOUT_MS = 2500L

    /** 探测时顺带取的字节数（256KB）：一次性拿到 206 校验 + 速率。 */
    private const val PROBE_SPAN_LAST = 262143L

    /** 拿到这么多条可用源就开跑，不等剩下的。 */
    private const val PROBE_MIN_READY = 1

    private const val MIN_SEGMENT = 1L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        com.takahashirinta.ncrust.network.AppHttp.builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 探测结果。[rateBps] 是这次探测顺手测出来的**首块速率**（字节/秒）。
     *
     * 为什么要在探测里测速：不同源的实际速率差一个数量级（实测本站经 CF 40~850KB/s 波动、
     * gh-proxy 2.7MB/s、Gitee 不支持 Range）。既然已经把 Range 请求发出去了，就让它多带
     * 256KB 回来顺便量一次 —— 比"按固定优先级选源"靠谱，而且每次下载都按当时的网络重排。
     */
    data class Probe(val total: Long, val etag: String?, val rateBps: Long = 0L)

    class Batch {
        private val calls = mutableSetOf<okhttp3.Call>()
        @Volatile var cancelled = false
            private set

        fun cancel() {
            val pending = synchronized(calls) {
                cancelled = true
                calls.toList()
            }
            pending.forEach { it.cancel() }
        }

        fun <T> execute(call: okhttp3.Call, read: (okhttp3.Response) -> T): T {
            synchronized(calls) {
                if (cancelled) throw DownloadCancelledException()
                calls.add(call)
            }
            try {
                return call.execute().use(read)
            } finally {
                synchronized(calls) { calls.remove(call) }
            }
        }
    }

    private fun strongEtag(value: String?): String? =
        value?.trim()?.takeIf { it.length >= 2 && it.first() == '"' && it.last() == '"' }

    /** 探测服务端是否支持 Range；不支持返回 null。 */
    /**
     * 4.0.9：多源探测。返回「可用源 + 探测结果」；一条都不支持 Range 就返回 null。
     * 顺序由调用方给（本站优先，反代最后）。
     */
    /**
     * 4.0.10：**按源逐个探测**，只留下真正支持 206 的源。
     *
     * 为什么需要：Gitee 的附件端点会忽略 `Range`、直接回 200 整个文件（实测 10.4 MB / 8.5s）。
     * 这种源不能参与分段（每段都会拿到完整文件、校验必挂），只能当单流回退。
     * 段分配前先筛一遍，避免"每段先撞一次 200 再换源"的浪费。
     */
    fun probeAll(sources: List<String>, batch: Batch): List<Pair<String, Probe>> {
        if (sources.isEmpty()) return emptyList()
        // 4.0.12：**并行探测 + 短超时 + 够用就走**。
        //
        // 4.0.11 是串行探的：一条死掉的反代要等它的连接超时（实测 ghfast.top 单条约 10s），
        // 于是"抓包很快、进度却卡在 0% 十几秒"——那段时间全花在等死链上，一个字节都还没写真。
        // 现在：所有源同时探，每条最多 [PROBE_TIMEOUT_MS]；拿到 [PROBE_MIN_READY] 条可用源就
        // 立刻开始分段，剩下的在后台补（补进来的也能用，但不阻塞开始）。
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val ready = LinkedHashMap<String, Probe>()
        val threads = sources.map { u ->
            thread(name = "seg-probe") {
                if (done.get()) return@thread
                val p = runCatching { probe(u, batch) }.getOrNull() ?: return@thread
                synchronized(ready) {
                    ready[u] = p
                    if (ready.size >= PROBE_MIN_READY) done.set(true)
                }
            }
        }
        // 最先到的那条也算数：只要有一条能分段，就不必等别人。
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        var waiting = true
        while (waiting && System.currentTimeMillis() < deadline) {
            val enough = synchronized(ready) { ready.size >= PROBE_MIN_READY }
            val allDead = threads.none { it.isAlive }
            if (enough || allDead) waiting = false else Thread.sleep(40)
        }
        done.set(true)
        val snapshot = synchronized(ready) { ready.toList() }
        Log.i(TAG, "probeAll: ${snapshot.size}/${sources.size} 条可用，用时 ${PROBE_TIMEOUT_MS}ms 上限")
        return snapshot
    }

    fun probeAny(sources: List<String>, batch: Batch): Pair<String, Probe>? {
        for (u in sources) {
            val p = runCatching { probe(u, batch) }.getOrNull() ?: continue
            Log.i(TAG, "probe ok via ${u.take(40)} total=${p.total}")
            return u to p
        }
        return null
    }

    fun probe(url: String, batch: Batch): Probe? {
        // 探测同时测速：要 256KB（不是 0-0），这样一次往返既能验证 206/ETag/总长，
        // 又能拿到这个源在当前网络下的真实吞吐。256KB 对 10MB 的包是 2.5% 的开销。
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateService.USER_AGENT)
            .header("Range", "bytes=0-$PROBE_SPAN_LAST")
            .build()
        return runCatching {
            batch.execute(client.newCall(request)) { resp ->
                if (resp.code != 206) {
                    Log.i(TAG, "probe: code=${resp.code} → 不走分段")
                    return@execute null
                }
                val started = android.os.SystemClock.elapsedRealtime()
                val range = parseContentRange(resp.header("Content-Range"))
                if (range == null || range.start != 0L || range.total <= 0L) {
                    Log.i(TAG, "probe: 没有 Content-Range 总长 → 不走分段")
                    return@execute null
                }
                val etag = strongEtag(resp.header("ETag")) ?: return@execute null
                val read = resp.body?.bytes()?.size?.toLong() ?: 0L
                val elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                val rate = read * 1000L / elapsed
                if (read <= 0L) return@execute null
                Log.i(TAG, "probe: ${url.takeLast(32)} rate=${rate / 1024}KB/s total=${range.total}")
                Probe(range.total, etag, rate)
            }
        }.getOrNull()
    }

    /**
     * 并发下载到 [target]。成功返回后文件已就绪（长度与 ETag 都校验过）。
     * 失败抛 [IOException]，调用方回落单流。
     */
    fun download(
        sources: List<String>,
        target: File,
        probe: Probe,
        batch: Batch,
        onProgress: (Long, Long) -> Unit,
    ) {
        if (batch.cancelled) throw DownloadCancelledException()
        val total = probe.total
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val hosts = sources.ifEmpty { listOf("") }
        Log.i(TAG, "start total=$total segments=$SEGMENTS sources=${hosts.size}")

        target.parentFile?.mkdirs()
        if (target.isFile) target.delete()
        RandomAccessFile(target, "rw").use { it.setLength(total) }

        val done = AtomicLong(0L)
        val span = maxOf(MIN_SEGMENT, (total + SEGMENTS - 1) / SEGMENTS)
        val ranges = mutableListOf<Pair<Long, Long>>()
        var from = 0L
        while (from <= total - 1) {
            val to = minOf(from + span - 1, total - 1)
            ranges += from to to
            from = to + 1
        }

        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = ranges.mapIndexed { index, (s, e) ->
            thread(name = "seg-dl-$index") {
                // 4.0.9：段与源**轮转**分配 —— 同一条线上并发 4 段容易互相抢带宽，
                // 分到不同镜像才是真正的多线路。失败时换下一条源重试这一段。
                var lastError: Throwable? = null
                val segWritten = java.util.concurrent.atomic.AtomicLong(0L)
                for (attempt in hosts.indices) {
                    if (batch.cancelled) break
                    val host = hosts[(index + attempt) % hosts.size]
                    try {
                        fetchSegment(host, target, s, e, probe.etag, batch, done, total, segWritten, onProgress)
                        return@thread
                    } catch (t: Throwable) {
                        if (t is DownloadCancelledException) throw t
                        lastError = t
                        // 这一段可能已经写了一部分：把总计回退掉，重试会覆盖同一区间。
                        done.addAndGet(-segWritten.getAndSet(0L))
                        Log.w(TAG, "seg $s via ${host.take(32)} failed (${t.javaClass.simpleName}), next source")
                    }
                }
                errors += (lastError ?: IOException("段 $s 无可用源"))
                batch.cancel()
            }
        }
        threads.forEach { it.join() }

        if (errors.isNotEmpty()) {
            val first = errors.first()
            Log.w(TAG, "segment failed: ${first.javaClass.simpleName} ${first.message?.take(80)}")
            throw IOException("分段下载失败", first)
        }
        if (batch.cancelled) throw DownloadCancelledException()
        if (target.length() != total) throw IOException("长度不符 ${target.length()} != $total")
        Log.i(TAG, "done total=$total elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
    }

    private fun fetchSegment(
        url: String,
        target: File,
        start: Long,
        endInclusive: Long,
        etag: String?,
        batch: Batch,
        done: AtomicLong,
        total: Long,
        segWritten: java.util.concurrent.atomic.AtomicLong,
        onProgress: (Long, Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateService.USER_AGENT)
            .header("Range", "bytes=$start-$endInclusive")
            .apply { if (!etag.isNullOrBlank()) header("If-Range", etag) }
            .build()
        val call = client.newCall(request)
        batch.execute(call) { resp ->
            if (resp.code != 206) throw IOException("段 $start 返回 ${resp.code}")
            // I04：**先核对实体身份与范围，再写盘**。旧实现只看 206 + 字节数，
            // 于是"等长的错误范围 / 另一版资产"也会被判成功，拼出来的包是坏的。
            val range = parseContentRange(resp.header("Content-Range"))
                ?: throw IOException("段 $start 没有 Content-Range")
            if (range.start != start || range.end != endInclusive || range.total != total) {
                throw IOException(
                    "段 $start 范围不符：收到 ${range.start}-${range.end}/${range.total}" +
                        "，期望 $start-$endInclusive/$total"
                )
            }
            val segEtag = strongEtag(resp.header("ETag"))
            if (segEtag == null || segEtag != etag) {
                throw IOException("段 $start ETag 变了（$segEtag ≠ $etag）")
            }
            val body = resp.body ?: throw IOException("段 $start 没有正文")
            val buffer = ByteArray(64 * 1024)
            var offset = start
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(start)
                while (true) {
                    if (batch.cancelled) throw DownloadCancelledException()
                    val read = body.source().read(buffer)
                    if (read == -1) break
                    if (batch.cancelled) throw DownloadCancelledException()
                    if (read.toLong() > endInclusive + 1 - offset) throw IOException("段 $start 正文越界")
                    raf.write(buffer, 0, read)
                    offset += read
                    val now = done.addAndGet(read.toLong())
                    onProgress(now, total)
                }
            }
            if (offset != endInclusive + 1) throw IOException("段 $start 长度不足 ${offset - start}")
        }

    }
}
