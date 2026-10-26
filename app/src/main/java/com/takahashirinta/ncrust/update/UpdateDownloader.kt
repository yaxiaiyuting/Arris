/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 移植自上游 Ncrust c14b1be / 3e462c4 的 Range 续传器。本 Fork 修复：
 * 续传元数据从「下载成功才写」改为「开始下载就写」，取消 / 进程被杀后残包仍可续传，
 * 而不是被当成无元数据残包删除重下；完整下载成功后清掉元数据，避免下次 416 空跑。
 */

package com.takahashirinta.ncrust.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 用户取消下载（与网络失败区分开：取消不该提示「下载失败」）。 */
class DownloadCancelledException : IOException("下载已取消")

/**
 * 断点续传的元数据（**纯数据 + 纯编解码**，便于单测）。
 *
 * 只凭文件名 + 本地长度判定续传是不够的：`docs/RELEASES.md` 明确允许**同名资产
 * 原地替换**，旧包的前缀接上新包的后缀会拼出坏包，而长度检查还可能刚好通过。
 * 所以续传必须绑定到稳定的资产身份（GitHub 资产 id / URL）与期望总长，
 * 身份不符就当作没有残包，安全重下。
 */
internal data class ResumeMarker(
    val url: String,
    val identity: String,
    val expectedSize: Long,
    val etag: String?
) {
    /** 只有 URL、身份一致，且双方都知道的总长相同时才允许续传。 */
    fun matches(url: String, identity: String, expectedSize: Long): Boolean {
        if (this.url != url) return false
        if (this.identity != identity) return false
        if (this.expectedSize > 0L && expectedSize > 0L && this.expectedSize != expectedSize) return false
        return true
    }

    fun encode(): String = buildString {
        append("version=1\n")
        append("url=").append(url).append('\n')
        append("identity=").append(identity).append('\n')
        append("size=").append(expectedSize).append('\n')
        append("etag=").append(etag.orEmpty()).append('\n')
    }

    companion object {
        fun decode(text: String?): ResumeMarker? {
            if (text.isNullOrBlank()) return null
            val map = HashMap<String, String>()
            for (line in text.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            if (map["version"] != "1") return null
            val url = map["url"] ?: return null
            val identity = map["identity"] ?: return null
            val size = map["size"]?.toLongOrNull() ?: return null
            val etag = map["etag"]?.takeIf { it.isNotEmpty() }
            return ResumeMarker(url, identity, size, etag)
        }
    }
}

/** HTTP `Content-Range` 的解析结果；[total] 为 -1 表示服务端用 `*` 表示总量未知。 */
internal data class ContentRange(val start: Long, val end: Long, val total: Long)

/** 解析 `bytes <start>-<end>/<total>`；非法 / 缺失返回 null。 */
internal fun parseContentRange(header: String?): ContentRange? {
    if (header.isNullOrBlank()) return null
    val value = header.trim()
    if (!value.startsWith("bytes", ignoreCase = true)) return null
    val rest = value.substringAfter(' ').trim()
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val rangePart = rest.substring(0, slash)
    val totalPart = rest.substring(slash + 1).trim()
    val dash = rangePart.indexOf('-')
    if (dash <= 0) return null
    val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
    val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
    val total = if (totalPart == "*") -1L else totalPart.toLongOrNull() ?: return null
    return ContentRange(start, end, total)
}

/**
 * 应用内下载 APK：支持断点续传（HTTP Range）、取消，以及**基于资产身份**的安全续传。
 *
 * 断点续传是必需的而不是优化——GitHub 资产在国内经常慢到中途断流，每次失败都从头来
 * 就等于永远下不完。规则：
 *  - 残包旁边留一份 [ResumeMarker]；只有 URL / 资产身份 / 期望总长都吻合才续传，
 *    否则（含**早期没有元数据的残包**）删掉残包从头下；
 *  - 续传时带 `If-Range`（存下来的 ETag）：资产被换过、服务端回 200 就整体覆盖；
 *  - 服务端回 206 时必须校验 `Content-Range` 的起点正是请求的续传点、总量与期望一致，
 *    否则残包不可信，清掉重下——最多重试 [MAX_RESTARTS] 次，绝不陷入坏前缀死循环。
 *
 * GitHub 资产会 302 到 `objects.githubusercontent.com`，OkHttp 默认跟随重定向。
 */
class UpdateDownloader(private val directory: File) {

    // 独立 client：绝不能复用 RetrofitClient 的（那个会往请求里塞网易云 cookie，
    // 把登录态发给 GitHub 是不可接受的）。
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var currentCall: okhttp3.Call? = null

    @Volatile
    private var cancelled = false

    /**
     * S10：每个目标文件只允许"最新一次尝试"交付。
     *
     * 旧实现所有尝试共用同一个输出文件：A 被取消后仍打开覆盖流，会把 B 刚下完的包截成 0 字节。
     * 现在每次尝试写自己的 `*.part-<attempt>`，交付前再核对"我还是不是最新尝试"。
     */
    private val attemptSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val latestAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun cancel() {
        cancelled = true
        runCatching { currentCall?.cancel() }
    }

    /**
     * 下载到 `directory/<fileName>`，返回落盘文件。
     * [identity] 是稳定资产身份（资产 id / URL），决定本地残包能否被续传；
     * [expectedSize] 是期望总长（0 表示未知）。
     * [onProgress] 的第二个参数为 0 表示总长未知（服务器没给 Content-Length）。
     */
    fun download(
        url: String,
        fileName: String,
        identity: String = url,
        expectedSize: Long = 0L,
        onProgress: (Long, Long) -> Unit
    ): File {
        directory.mkdirs()
        val target = File(directory, fileName)
        // 每次尝试独占一个 part 文件：旧任务即使还在跑，也只动自己那份。
        val attempt = attemptSeq.incrementAndGet()
        latestAttempt[fileName] = attempt
        val part = File(directory, "$fileName.part-$attempt")
        val markerFile = File(directory, markerNameFor(fileName))
        fun superseded() = latestAttempt[fileName] != attempt
        // 清掉同一目标的其它 part（上一轮被杀的残留），只保留本次尝试的那份。
        runCatching {
            directory.listFiles()
                ?.filter { it.name.startsWith("$fileName.part-") && it.name != part.name }
                ?.forEach { it.delete() }
        }

        // 1. 决定本地残包能否续传：有字节、有元数据、身份完全吻合，三者缺一不可。
        val stored = readMarker(markerFile)
        // 续传只对**已完成的目标文件**有意义；part 文件是本次尝试私有的，从 0 开始。
        val localBytes = if (target.isFile) target.length() else 0L
        // S11：URL 回退身份（没有稳定资产 id）只在**有验证器**时才允许拼接；
        // 否则同一 URL 换了内容也能拼出"v1 前缀 + v2 后缀"的混合包。
        val identityIsUrlFallback = identity == url
        val validatorPresent = !stored?.etag.isNullOrBlank()
        val canResume = localBytes > 0L && stored != null &&
            stored.matches(url, identity, expectedSize) &&
            (!identityIsUrlFallback || validatorPresent)
        if (localBytes > 0L && !canResume) {
            target.delete() // 无元数据 / 身份不符的旧残包：安全重下，绝不拼接
        }
        markerFile.delete()

        var resumeFrom = if (canResume) localBytes else 0L
        var ifRange = if (canResume) stored?.etag else null
        var restarts = 0

        // 关键：先把「这份残包属于谁」落盘，再开始收字节。
        // 上游只在成功返回前写 marker，导致取消 / 进程被杀后残包没有身份，下次只能整包重下。
        // 这里写入的是本次请求的身份与期望总长；ETag 在没续传时为空，服务端会以 200 整体覆盖，
        // 不会拿旧前缀去拼新后缀。
        writeMarker(markerFile, ResumeMarker(url, identity, expectedSize, ifRange))

        try {
        while (true) {
            if (cancelled) throw DownloadCancelledException()
            var restart = false
            execute(url, resumeFrom, ifRange).use { response ->
                if (cancelled) throw DownloadCancelledException()

                if (response.code == 416 && resumeFrom > 0L) {
                    // 本地残留已经超过服务端那份（资产被换过）→ 清掉重来。
                    part.delete()
                    resumeFrom = 0L
                    ifRange = null
                    restart = true
                    return@use
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("响应没有正文")

                val append = response.code == 206 && resumeFrom > 0L
                val range = if (append) parseContentRange(response.header("Content-Range")) else null
                if (append) {
                    val totalOk = expectedSize <= 0L ||
                        range == null ||
                        range.total <= 0L ||
                        range.total == expectedSize
                    // 206 的起点必须正是请求的续传点；总量若双方都知道也要吻合。
                    if (range == null || range.start != resumeFrom || !totalOk) {
                        part.delete()
                        resumeFrom = 0L
                        ifRange = null
                        restart = true
                        return@use
                    }
                }

                // S11：收到响应头就把实体校验信息原子写进续传标记，**在写任何字节之前**。
                // 旧实现只写"传入/已有"的 ETag，响应里的 ETag 从来没落过盘，
                // 于是下次续传没有 If-Range，v1 前缀 + v2 后缀能拼成一个混合包。
                val responseEtag = response.header("ETag")
                val effectiveEtag = responseEtag ?: ifRange
                if (effectiveEtag != null) {
                    writeMarker(markerFile, ResumeMarker(url, identity, expectedSize, effectiveEtag))
                }
                if (!append && ifRange == null && effectiveEtag != null) {
                    // 整包下载也要把验证器记下来，供下一次续传使用。
                    ifRange = effectiveEtag
                }

                if (!append) {
                    part.delete() // 200：整体覆盖
                    resumeFrom = 0L
                } else if (!part.isFile || part.length() != resumeFrom) {
                    // 续传：本次 part 还什么都没有，先把已下载的前缀原样拷过来，
                    // 再从 resumeFrom 继续写（part 是本次尝试私有的，绝不能直接用 target 追加）。
                    part.delete()
                    runCatching {
                        target.inputStream().use { input ->
                            part.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                        }
                    }
                }

                val contentLength = body.contentLength()
                val total = when {
                    append && range != null && range.total > 0L -> range.total
                    append && contentLength > 0L -> resumeFrom + contentLength
                    contentLength > 0L -> contentLength
                    else -> 0L
                }

                var downloaded = if (append) resumeFrom else 0L
                onProgress(downloaded, total)
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (cancelled) throw DownloadCancelledException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                    out.flush()
                }
                // 服务器给了总长却没收满 → 传输被截断，别把半截 APK 当成功。
                if (total > 0L && downloaded != total) {
                    throw IOException("下载不完整：$downloaded/$total")
                }
                if (cancelled) throw DownloadCancelledException()

                // 完整落盘后清掉续传元数据：下一次若再从 0 开始（用户重下 / 校验失败重试），
                // 不应把「已经完整的旧文件」当成残包去发 Range=全长（服务端会回 416）。
                if (superseded() || cancelled) {
                    // 这次尝试已经被更新的下载取代（或用户取消）：绝不交付，删掉自己的 part。
                    part.delete()
                    throw DownloadCancelledException()
                }
                // 原子交付：先落到目标旁边再改名，避免"目标文件存在但只有半截"。
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    // rename 失败也不能回退成"写进目标"（那正是 S10 的竞态）。
                    part.delete()
                    throw IOException("下载交付失败：无法重命名 ${part.name}")
                }
                markerFile.delete()
                return target
            }
            if (!restart) throw IOException("下载状态异常")
            restarts++
            if (restarts > MAX_RESTARTS) throw IOException("续传多次失败")
        }
        } catch (e: Throwable) {
            // 失败/取消：把本次 part 归还成目标文件，保持"残包留在目标上、下次可续传"的契约。
            // 只有仍是最新尝试才归还 —— 被新下载取代的旧任务不许动目标。
            if (!superseded() && part.isFile && part.length() > 0L) {
                runCatching {
                    if (target.exists()) target.delete()
                    part.renameTo(target)
                }
            } else {
                runCatching { part.delete() }
            }
            throw e
        }
    }

    private fun execute(url: String, resumeFrom: Long, ifRange: String?): okhttp3.Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateService.USER_AGENT)
            .header("Accept", "application/octet-stream")
        if (resumeFrom > 0L) {
            builder.header("Range", "bytes=$resumeFrom-")
            if (!ifRange.isNullOrBlank()) builder.header("If-Range", ifRange)
        }
        val call = client.newCall(builder.build())
        currentCall = call
        return call.execute()
    }

    private fun markerNameFor(fileName: String): String = "$fileName.identity"

    private fun readMarker(file: File): ResumeMarker? {
        if (!file.isFile) return null
        return ResumeMarker.decode(runCatching { file.readText() }.getOrNull())
    }

    private fun writeMarker(file: File, marker: ResumeMarker) {
        runCatching { file.writeText(marker.encode()) }
    }

    private companion object {
        /** 续传被服务端 / 内容变动打断后，允许的最多重下次数。 */
        const val MAX_RESTARTS = 2
    }
}
