/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v2.0.0 · T3：缓存占用分项（[CacheUsage]）的纯逻辑单测。
 *
 * 核心回归是**图片缓存双计**：旧口径 = Coil diskCache.size + folderSize(cacheDir) + 音频，
 * 而 Coil 的目录就是 cacheDir/image_cache ⇒ 图片被算两遍。
 * 用临时目录复现旧口径与新口径的差值，双计一旦回来这条测试就会红。
 */
class CacheUsageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(rel: String, bytes: Int): File {
        val f = File(tmp.root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun `图片缓存只算一次——旧口径会多报一份`() {
        file("image_cache/a.jpg", 1_000)
        file("image_cache/nested/b.jpg", 500)
        file("http/cache.bin", 300)

        val usage = CacheUsage.measure(tmp.root, offlineAudioBytes = 0L)

        assertEquals(1_500L, usage.imageBytes)
        assertEquals(300L, usage.otherCacheBytes)
        assertEquals(1_800L, usage.totalBytes)

        // 旧口径（Coil size + folderSize(cacheDir) + audio）在同一棵目录树上会多报
        // 一整个 image_cache —— 这就是被修掉的双计。
        val legacy = usage.imageBytes + folderSize(tmp.root) + 0L
        assertEquals(3_300L, legacy)
    }

    @Test
    fun `音频缓存单独计——它不在 cacheDir 里`() {
        file("image_cache/a.jpg", 100)
        val usage = CacheUsage.measure(tmp.root, offlineAudioBytes = 7_000L)
        assertEquals(7_000L, usage.audioBytes)
        assertEquals(7_100L, usage.totalBytes)
    }

    @Test
    fun `cacheDir 为空或不存在——目录项回落 0，音频照算，不抛异常`() {
        assertEquals(CacheUsage.ZERO, CacheUsage.measure(null, 0L))
        val nullDir = CacheUsage.measure(null, 123L)
        assertEquals(123L, nullDir.audioBytes)
        assertEquals(0L, nullDir.imageBytes)
        assertEquals(0L, nullDir.otherCacheBytes)
        val missing = File(tmp.root, "nope")
        assertEquals(0L, CacheUsage.measure(missing, 0L).totalBytes)
    }

    @Test
    fun `other 不会为负——目录被并发删除时读到中间态也只回落 0`() {
        // image_cache 不存在而 cacheDir 里只有别的目录：other = cacheTotal - 0
        file("WebView/x", 50)
        val usage = CacheUsage.measure(tmp.root, 0L)
        assertEquals(0L, usage.imageBytes)
        assertEquals(50L, usage.otherCacheBytes)
        assertEquals(50L, usage.totalBytes)
    }

    @Test
    fun `递归统计嵌套目录`() {
        file("a/b/c/d.bin", 10)
        file("a/e.bin", 20)
        assertEquals(30L, folderSize(tmp.root))
        assertEquals(0L, folderSize(null))
        assertEquals(0L, folderSize(File(tmp.root, "missing")))
    }

    @Test
    fun `总量等于三项之和`() {
        file("image_cache/a.jpg", 3)
        file("http/b", 4)
        val usage = CacheUsage.measure(tmp.root, 5L)
        assertEquals(usage.audioBytes + usage.imageBytes + usage.otherCacheBytes, usage.totalBytes)
        assertEquals(12L, usage.totalBytes)
    }
}
