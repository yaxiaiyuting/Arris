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

    /** 小于这个体积不值得分段（额外请求开销会吃掉收益）。 */
    private const val MIN_SIZE = 4L * 1024 * 1024

    /** 每段至少这么大，避免出现大量极小的段。 */
    private const val MIN_SEGMENT = 1L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        com.takahashirinta.ncrust.network.AppHttp.builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class Probe(val total: Long, val etag: String?)

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
    fun probe(url: String, batch: Batch): Probe? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateService.USER_AGENT)
            .header("Range", "bytes=0-0")
            .build()
        return runCatching {
            batch.execute(client.newCall(request)) { resp ->
                if (resp.code != 206) {
                    Log.i(TAG, "probe: code=${resp.code} → 不走分段")
                    return@execute null
                }
                val range = parseContentRange(resp.header("Content-Range"))
                if (range == null || range.start != 0L || range.end != 0L || range.total <= 0L) {
                    Log.i(TAG, "probe: 没有 Content-Range 总长 → 不走分段")
                    return@execute null
                }
                val etag = strongEtag(resp.header("ETag")) ?: return@execute null
                Probe(range.total, etag)
            }
        }.getOrNull()
    }

    /**
     * 并发下载到 [target]。成功返回后文件已就绪（长度与 ETag 都校验过）。
     * 失败抛 [IOException]，调用方回落单流。
     */
    fun download(
        url: String,
        target: File,
        probe: Probe,
        batch: Batch,
        onProgress: (Long, Long) -> Unit,
    ) {
        if (batch.cancelled) throw DownloadCancelledException()
        val total = probe.total
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "start total=$total segments=$SEGMENTS url=${url.takeLast(40)}")

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
                try {
                    fetchSegment(url, target, s, e, probe.etag, batch, done, total, onProgress)
                } catch (t: Throwable) {
                    errors += t
                    batch.cancel()
                }
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
