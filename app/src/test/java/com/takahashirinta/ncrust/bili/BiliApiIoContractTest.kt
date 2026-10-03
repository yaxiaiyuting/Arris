/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0-C：B 站网络层的**源码形状**守卫（结构防线）。
 */

package com.takahashirinta.ncrust.bili

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.0 · P0-C 的**第二道防线**：源码扫描。
 *
 * ## 为什么行为测试（`BiliApiDispatchTest`）之外还要这一条
 *
 * 行为测试只能覆盖**已经存在**的方法。P0-C 的形状是「有人新写/改写一个 B 站网络方法，
 * 忘了换线程」—— 那种缺陷不会让任何既有用例变红，而会在真机上表现为
 * 「B 站又搜不到歌了」且没有任何错误。所以这里把**形状本身**变成断言：
 *
 * 1. `BiliApi` 的对外函数只允许两类：**纯内存读取**（`hasWbiKeys`）与
 *    **`suspend` + 内部 `withContext(Dispatchers.IO)`** 的网络方法。
 *    多出第三个非 suspend 的对外函数 ⇒ 变红（新加网络方法必须登记进 [NETWORK_METHODS]）；
 * 2. 阻塞的传输层（`get`/`post`/`signedGet`/`wbiKeys`/`buvid3`）必须是 `private`
 *    —— 「谁能在什么线程上调它」因此在本文件里一眼看完；
 * 3. `BiliSourceProvider` 的**每一条对外路径都先判开关**（v3.1.0 铁律 24）：
 *    开关判据必须出现在任何 `BiliApi.` 调用之前。本版把调用点搬进了 helper，
 *    顺序有可能被顺手改坏 —— 这条守着「关掉时一个请求都不发」。
 *
 * ## 它**不能**证明什么（如实写）
 *
 * - 不能证明 `withContext(Dispatchers.IO)` 真的被执行到（那是行为测试的事）；
 * - 不能覆盖 `BiliApi` 之外的阻塞调用（例如将来有人在 `BiliSourceProvider` 里直接
 *   `client.newCall`）。它只守住今天这两个文件里**已经写下来**的契约。
 */
class BiliApiIoContractTest {

    private companion object {
        /** 对外（非 private / 非 internal）的 `suspend` 网络方法。**新增一个必须加进这里。** */
        val NETWORK_METHODS = setOf(
            "audioLyric",
            "searchVideos",
            "videoCid",
            "videoAudioStream",
            "audioInfo",
            "audioStream",
            "probeReachable",
            // v3.3.0：视频字幕（`player/wbi/v2` 取列表 + 字幕 CDN 取正文）。
            // 两者**刻意分开**：失败语义不同（「这个视频没有字幕」vs「字幕下载失败」），
            // 合并成一个函数会让本版要修的「失败被误报成没有歌词」失去区分能力。
            "videoSubtitleUrl",
            "subtitleBody",
        )

        /**
         * 允许的非 suspend 对外函数：读 `@Volatile` 缓存，不发网络。
         *
         * v3.3.0：`normalizeSubtitleUrl` 本来被我按直觉写进了 `BiliApi`，
         * 被这道守卫拦下 —— 拦得对。`BiliApi` 是**传输层**，在这一层放一个
         * 「既不碰网络也不读缓存」的对外函数，等于开了个例外口子，
         * 而**下一个**人往里加什么都不会再被这道守卫看见。
         * 它现在住在 `BiliSubtitle.normalizeSubtitleUrl`（纯逻辑对象，JVM 可断言）。
         */
        val PURE_PUBLIC_METHODS = setOf("hasWbiKeys")

        /** 阻塞的传输层 —— 必须是 private（同文件内一眼看完调用点）。 */
        val BLOCKING_TRANSPORT = setOf("get", "post", "signedGet", "wbiKeys", "buvid3")
    }

    // ------------------------------------------------------------------ 1. 对外形状

    @Test
    fun `BiliApi 的每一个对外网络方法都是 suspend 且内部换到 Dispatchers_IO`() {
        val funs = functionsOf("com/takahashirinta/ncrust/bili/BiliApi.kt")
        for (name in NETWORK_METHODS) {
            val fn = funs.firstOrNull { it.name == name }
                ?: throw AssertionError("$name 不见了 —— 若已改名，请同步 NETWORK_METHODS")
            assertTrue("$name 必须是 suspend（否则调用点可以落在主线程上）", fn.isSuspend)
            assertTrue(
                "$name 必须在函数体内 withContext(Dispatchers.IO)（P0-C 的唯一保证点）",
                fn.body.contains("withContext(Dispatchers.IO)"),
            )
        }
    }

    @Test
    fun `BiliApi 的对外函数只有两类 —— 纯内存读取 与 suspend 网络方法`() {
        val publicFuns = functionsOf("com/takahashirinta/ncrust/bili/BiliApi.kt").filterNot { it.isPrivate || it.isInternal }
        val nonSuspend = publicFuns.filterNot { it.isSuspend }.map { it.name }.toSet()
        val suspendFuns = publicFuns.filter { it.isSuspend }.map { it.name }.toSet()
        assertEquals(
            "出现了未登记的非 suspend 对外函数（它可以在主线程上跑）—— " +
                "要么改成 suspend + withContext(Dispatchers.IO)，要么加进 PURE_PUBLIC_METHODS 并说明它不发网络",
            PURE_PUBLIC_METHODS,
            nonSuspend,
        )
        assertEquals("对外 suspend 方法的集合与登记表不一致", NETWORK_METHODS, suspendFuns)
    }

    @Test
    fun `阻塞的传输层必须是 private`() {
        val funs = functionsOf("com/takahashirinta/ncrust/bili/BiliApi.kt")
        for (name in BLOCKING_TRANSPORT) {
            val fn = funs.firstOrNull { it.name == name }
                ?: throw AssertionError("$name 不见了 —— 若已改名，请同步 BLOCKING_TRANSPORT")
            assertTrue("$name 是同步阻塞的传输层，必须 private", fn.isPrivate)
        }
        // 传输层本身不做线程切换（它**假设**调用者已经在 IO 上）；切换只发生在对外方法里。
        val get = funs.first { it.name == "get" }
        assertFalse(
            "get() 不该自己换线程：换了会让「谁在什么线程上调用」这件事无法从调用点看出来",
            get.body.contains("withContext("),
        )
    }

    // ------------------------------------------------------------------ 2. 开关顺序（铁律 24）

    @Test
    fun `BiliSourceProvider 的每一条对外路径都先判开关再发请求`() {
        val funs = functionsOf("com/takahashirinta/ncrust/bili/BiliSourceProvider.kt")
        for (name in listOf("searchSongs", "resolveUrl", "songDetail", "fetchLyric")) {
            val fn = funs.firstOrNull { it.name == name }
                ?: throw AssertionError("$name 不见了 —— 铁律 24 的守卫必须跟着它")
            val gate = fn.body.indexOf("isEnabled")
            val firstCall = fn.body.indexOf("BiliApi.")
            assertTrue("$name 必须判 isEnabled（关掉时一个请求都不发）", gate >= 0)
            if (firstCall >= 0) {
                assertTrue(
                    "$name 里 isEnabled 必须出现在第一个 BiliApi 调用之前（铁律 24：关掉=零请求）",
                    gate < firstCall,
                )
            }
        }
    }

    // ------------------------------------------------------------------ 源码扫描小工具

    private data class Fn(
        val name: String,
        val body: String,
        val isSuspend: Boolean,
        val isPrivate: Boolean,
        val isInternal: Boolean,
    )

    /**
     * 扫出 `relPath` 里**缩进恰好 4 空格**的 `fun` 声明（= object 的成员）。
     *
     * 缩进判据是有意的：函数体内部的局部函数缩进更深，因此不会被算进来。
     * 每个函数的「body」= 它的声明行到下一个成员声明之前 —— 足够回答
     * 「是不是 suspend」「里面有没有 withContext(Dispatchers.IO)」这两个问题。
     */
    private fun functionsOf(relPath: String): List<Fn> {
        val lines = File(appSourceRoot(), relPath).readText().lines()
        // 修饰符整体捕获（`override` / `suspend` / `inline` / `private` … 顺序任意）——
        // 只认 `override suspend fun` 会漏掉 `override fun`，那正是本测试第一版的 bug。
        // `^ {4}(?! )`：**恰好** 4 空格缩进 = object 的成员。
        // 少了那个负向断言，函数体里 8 空格缩进的局部函数（`signedGet` 里的 `fun attempt`）
        // 也会被算成「对外函数」—— 本测试第一版就踩了这个假阳性。
        val header = Regex("""^ {4}(?! )([A-Za-z ]*?)fun\s+([A-Za-z_]\w*)\s*\(""")
        val heads = lines.mapIndexedNotNull { i, line -> header.find(line)?.let { i to it } }
        return heads.mapIndexed { idx, (lineNo, match) ->
            val mods = match.groupValues[1]
            val end = if (idx + 1 < heads.size) heads[idx + 1].first else lines.size
            Fn(
                name = match.groupValues[2],
                body = lines.subList(lineNo, end).joinToString("\n"),
                isSuspend = mods.contains("suspend"),
                isPrivate = mods.contains("private"),
                isInternal = mods.contains("internal"),
            )
        }
    }

    /** 从 `user.dir` 逐级向上找 `app/src/main/java`；**找不到直接失败**（静默跳过 = 假防线）。 */
    private fun appSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            if (dir.name == "app" && File(dir, "src/main/java").isDirectory) {
                return File(dir, "src/main/java")
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）—— " +
                "源码扫描防线失效，必须修好而不是跳过。",
        )
    }
}
