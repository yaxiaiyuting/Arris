/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * H04 回归：更新下载的「最新尝试」隔离必须**跨实例**成立。
 */

package com.takahashirinta.ncrust.update

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * H04：「更新文件的隔离」原来只在**同一个 `UpdateDownloader` 对象内**成立。
 *
 * 生产入口 `UpdateService.startDownload` 每次都 `UpdateDownloader(directory)` 新建实例，
 * 于是实例字段的 `attemptSeq` 从 0 重新数、`latestAttempt` 各自为政 —— 两次尝试都叫
 * `*.part-1`，两个实例也无法把对方识别为 superseded。旧任务收尾时会把新任务的 part
 * 改名成交付目标，新任务随后因 part 已被移走而失败，最后 APK 不存在。
 *
 * 这里用**两个真实的独立实例**（不是复用同一个对象）+ 一个会「卡住第一份响应正文」的
 * 本地 HTTP 服务，把时序钉死：
 *
 * 1. 实例 A 发出请求，服务端回响应头后**挂住正文**（A 处于在途状态）；
 * 2. 实例 B 对**同一个目标文件**再下一次，正常收完并交付；
 * 3. 放行 A 的正文 —— A 必须识别出"我已被取代"，抛 [DownloadCancelledException]，
 *    且绝不把字节写进目标、也不删/搬 B 的 part。
 */
class UpdateDownloaderCrossInstanceTest {

    private val fileName = "Arris-v9.9.9-gpl-release.apk"
    private val slowBody = ByteArray(900) { 0x41 } // 'A'
    private val fastBody = ByteArray(1000) { 0x42 } // 'B'

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("arris-update-cross-instance").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `anOlderDownloaderInstanceCannotDeliverOverTheNewerOne()`() {
        val server = GatedServer(slowBody, fastBody).also { it.start() }
        try {
            val url = "http://127.0.0.1:${server.port}/asset.apk"
            val target = File(directory, fileName)

            // 实例 A：在途（正文被服务端挂住）。
            val firstFailure = AtomicReference<Throwable?>(null)
            val first = Thread {
                firstFailure.set(
                    runCatching {
                        UpdateDownloader(directory).download(url, fileName) { _, _ -> }
                    }.exceptionOrNull()
                )
            }
            first.isDaemon = true
            first.start()
            assertTrue("实例 A 必须已经发出请求", server.firstBodyStarted.await(10, TimeUnit.SECONDS))

            // 实例 B：**另一个对象**，同一个目标文件。
            val delivered = UpdateDownloader(directory).download(url, fileName) { _, _ -> }
            assertArrayEquals("新实例必须交付完整的自己的包", fastBody, delivered.readBytes())

            // 放行 A：它必须认出自己已被取代。
            server.release()
            first.join(15_000)
            assertFalse("实例 A 必须已经收尾", first.isAlive)
            assertTrue(
                "旧尝试必须按「被取代」收场，实际是 ${firstFailure.get()}",
                firstFailure.get() is DownloadCancelledException,
            )

            assertArrayEquals(
                "旧尝试绝不许把字节写进新实例交付的目标",
                fastBody,
                target.readBytes(),
            )
            val leftovers = directory.listFiles()?.map { it.name }?.filter { it.contains(".part-") }.orEmpty()
            assertTrue("不允许留下 part 残留：$leftovers", leftovers.isEmpty())
        } finally {
            server.release()
            server.stop()
        }
    }

    /**
     * 键取**目标文件绝对路径**而不是裸文件名：不同目录下的同名文件互不构成竞态域。
     *
     * 这条同时是「隔离不能过头」的反向保护 —— 若实现改成按裸文件名登记，两个不同目录的
     * 下载会互相把对方判成 superseded，表现是「另一个目录的下载把这份目标删了」。
     */
    @Test
    fun `downloadsInDifferentDirectoriesDoNotSupersedeEachOther()`() {
        val other = Files.createTempDirectory("arris-update-other-dir").toFile()
        val server = GatedServer(slowBody, fastBody).also { it.start() }
        try {
            val url = "http://127.0.0.1:${server.port}/asset.apk"
            val firstFailure = AtomicReference<Throwable?>(null)
            val first = Thread {
                firstFailure.set(
                    runCatching {
                        UpdateDownloader(directory).download(url, fileName) { _, _ -> }
                    }.exceptionOrNull()
                )
            }
            first.isDaemon = true
            first.start()
            assertTrue(server.firstBodyStarted.await(10, TimeUnit.SECONDS))

            // 另一个目录里的同名目标：与 A 无关，必须能正常交付。
            val otherFile = UpdateDownloader(other).download(url, fileName) { _, _ -> }
            assertArrayEquals(fastBody, otherFile.readBytes())

            server.release()
            first.join(15_000)
            assertFalse(first.isAlive)
            assertTrue(
                "不同目录的同名下载不应互相取代，实际 ${firstFailure.get()}",
                firstFailure.get() == null,
            )
            assertArrayEquals(slowBody, File(directory, fileName).readBytes())
            assertNotNull(otherFile)
        } finally {
            server.release()
            server.stop()
            other.deleteRecursively()
        }
    }

    /**
     * 最小 HTTP/1.1 服务：**第一次**请求回 [slowBody] 但正文被 [release] 卡住，
     * 之后每次请求立刻回 [fastBody]。与 `UpdateDownloaderTest` 的 TinyHttpServer 一样
     * 手写 socket（Android 单测的编译类路径里没有 `jdk.httpserver`）。
     */
    private class GatedServer(
        private val slowBody: ByteArray,
        private val fastBody: ByteArray,
    ) {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val gate = CountDownLatch(1)
        private val served = AtomicInteger(0)

        /** 第一次请求的响应头已经发出、正文还没发（A 正在收字节）。 */
        val firstBodyStarted = CountDownLatch(1)

        val port: Int get() = socket.localPort

        fun start() {
            Thread(::loop).apply { isDaemon = true }.start()
        }

        fun release() = gate.countDown()

        fun stop() {
            runCatching { socket.close() }
        }

        private fun loop() {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return
                }
                // 每条连接独立线程：第一条会被 gate 卡住，绝不能挡住后面的连接。
                Thread { runCatching { handle(client) } }.apply { isDaemon = true }.start()
            }
        }

        private fun handle(client: Socket) {
            client.use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                if (reader.readLine() == null) return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val index = served.incrementAndGet()
                val body = if (index == 1) slowBody else fastBody
                val out = s.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                out.flush()
                if (index == 1) {
                    firstBodyStarted.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                }
                out.write(body)
                out.flush()
            }
        }
    }
}
