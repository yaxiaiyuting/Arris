package com.takahashirinta.ncrust.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Characterization only: passing assertions demonstrate defects on v3.7.7. */
class UpdateAuditProbe {
    @Test fun cancelledOldWriterCanTruncateCompletedReplacement() {
        val directory = Files.createTempDirectory("arris-update-audit").toFile()
        val pool = Executors.newSingleThreadExecutor()
        val oldAtProgress = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val server = LocalServer(bytes)
        try {
            val old = UpdateDownloader(directory)
            val pending = pool.submit<Throwable?> {
                try {
                    old.download(server.url, "same.apk", "asset-A", 1000) { done, _ ->
                        if (done == 0L) {
                            oldAtProgress.countDown()
                            check(releaseOld.await(8, TimeUnit.SECONDS))
                        }
                    }
                    null
                } catch (e: Throwable) { e }
            }
            assertTrue(oldAtProgress.await(5, TimeUnit.SECONDS))
            old.cancel()
            val ready = UpdateDownloader(directory).download(server.url, "same.apk", "asset-B", 1000) { _, _ -> }
            assertArrayEquals(bytes, ready.readBytes())
            releaseOld.countDown()
            assertTrue(pending.get(5, TimeUnit.SECONDS) is DownloadCancelledException)
            assertTrue(ready.isFile)
            assertEquals(0L, ready.length())
            println("AUDIT updater: cancelled A resumes after B completed, truncates B file 1000 -> 0 bytes")
        } finally {
            releaseOld.countDown(); pool.shutdownNow(); server.close(); directory.deleteRecursively()
        }
    }

    @Test fun truncatedDownloadDoesNotCaptureEtagAndCanMixFallbackIdentityBytes() {
        val directory = Files.createTempDirectory("arris-etag-audit").toFile()
        val first = ByteArray(1000) { 0x31 }
        val second = ByteArray(1000) { 0x72 }
        val server = LocalServer(first)
        try {
            server.etag = "\"v1\""
            server.truncateOnce = true
            assertThrows(IOException::class.java) {
                UpdateDownloader(directory).download(server.url, "fallback.apk", expectedSize = 1000) { _, _ -> }
            }
            val partial = File(directory, "fallback.apk").length()
            assertEquals(500L, partial)
            val marker = ResumeMarker.decode(File(directory, "fallback.apk.identity").readText())!!
            assertNull(marker.etag)
            server.body = second
            server.etag = "\"v2\""
            val result = UpdateDownloader(directory).download(server.url, "fallback.apk", expectedSize = 1000) { _, _ -> }
            assertEquals(listOf(null, "bytes=500-"), server.ranges.toList())
            assertEquals(listOf(null, null), server.ifRanges.toList())
            assertArrayEquals(first.copyOfRange(0, 500) + second.copyOfRange(500, 1000), result.readBytes())
            assertFalse(second.contentEquals(result.readBytes()))
            println("AUDIT updater: response ETag v1 not saved, next Range has no If-Range; same-URL/no-id fallback mixes old prefix and new suffix")
        } finally { server.close(); directory.deleteRecursively() }
    }

    private class LocalServer(@Volatile var body: ByteArray) {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        @Volatile var etag: String? = null
        @Volatile var truncateOnce = false
        val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val ifRanges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val url get() = "http://127.0.0.1:${socket.localPort}/asset.apk"
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                runCatching { respond(client) }
            }
        }.apply { isDaemon = true; start() }
        fun close() { socket.close(); thread.join(1000) }
        private fun respond(client: Socket) = client.use { s ->
            val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            reader.readLine() ?: return@use
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            ranges.add(headers["range"]); ifRanges.add(headers["if-range"])
            val data = body
            val start = if (headers["if-range"] != null && headers["if-range"] != etag) 0
                else headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val response = buildString {
                append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Length: ${data.size - start}\r\n")
                etag?.let { append("ETag: $it\r\n") }
                if (start > 0) append("Content-Range: bytes $start-${data.size - 1}/${data.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val end = if (truncateOnce) { truncateOnce = false; start + (data.size - start) / 2 } else data.size
            s.getOutputStream().apply {
                write(response.toByteArray(Charsets.ISO_8859_1)); write(data.copyOfRange(start, end)); flush()
            }
        }
    }
}
