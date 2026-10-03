/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.screen

import android.content.Context
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import java.io.File

/**
 * 「缓存占用」的分项（v2.0.0 · T3）。
 *
 * ## 为什么要拆开：这是一个真实的双计 bug
 *
 * v1.6.0 起设置页的「缓存占用」 = Coil 磁盘缓存 size + folderSize(cacheDir) + 离线音频 size。
 * 但 Coil 的磁盘缓存目录就是 **cacheDir/image_cache**（见 NcrustApplication.newImageLoader），
 * 而 folderSize(cacheDir) 会**递归**把它算进去 ⇒ **图片缓存被算了两遍**：
 * 20MB 图片缓存会让设置页多报 20MB，而用户点「清除缓存」后这个多出来的量还在
 * （Coil 的 journal 文件仍占着那点空间），看起来就是「清不干净」。
 *
 * 修法不是「减去 Coil 的 size」，而是换口径：**先量三项，再相加**，三个来源互不重叠 ——
 * 目录树只走一遍（folderSize(cacheDir)），图片那部分从里面**切出来**而不是另加一份。
 *
 * ## 不变量：统计口径 == 可清理范围
 *
 * v1.6.0 的 D1 节与 UserScreen 的「清除缓存」注释都写了同一条不变量：设置页显示多少，
 * 「清除缓存」就必须能清掉多少。所以三项分别对应：
 *
 * | 项 | 目录 | 谁清 |
 * |---|---|---|
 * | [audioBytes] | `filesDir/offline/audio`（不在 cacheDir，系统清缓存不会误删） | [OfflineAudioCache.clear] |
 * | [imageBytes] | `cacheDir/image_cache` | Coil 的 `diskCache.clear()`（要走它的 API，不能绕过 journal 直接删文件） |
 * | [otherCacheBytes] | cacheDir 下除 image_cache 之外的一切（WebView / http / 其他） | 逐子项 `deleteRecursively()` |
 *
 * 纯逻辑部分（[measure]）不依赖 Android，JVM 单测用临时目录覆盖双计与边界。
 */
internal data class CacheUsage(
    val audioBytes: Long,
    val imageBytes: Long,
    val otherCacheBytes: Long,
) {
    val totalBytes: Long get() = audioBytes + imageBytes + otherCacheBytes

    companion object {
        /**
         * Coil 磁盘缓存目录名（相对 cacheDir）。
         * **必须**与 NcrustApplication.newImageLoader 里 `diskCache.directory(cacheDir.resolve(...))`
         * 的参数逐字一致 —— 不一致这个双计 bug 就会以另一种形式回来。
         */
        const val IMAGE_CACHE_DIR = "image_cache"

        val ZERO = CacheUsage(0L, 0L, 0L)

        /**
         * 纯逻辑：`cacheDir` 的目录树 + 调用方量好的离线音频占用 ⇒ 三项。
         *
         * - imageBytes = folderSize(cacheDir/image_cache)
         * - otherCacheBytes = folderSize(cacheDir) − imageBytes（可能为 0；理论上不会为负，
         *   但真机上目录被并发删除时可能读到中间态，所以 clamp 到 0）
         *
         * 音频缓存在 filesDir 下，跟 cacheDir 没有包含关系，必须由调用方单独量。
         */
        fun measure(cacheDir: File?, offlineAudioBytes: Long): CacheUsage {
            val imageBytes = folderSize(cacheDir?.let { File(it, IMAGE_CACHE_DIR) })
            val cacheDirBytes = folderSize(cacheDir)
            return CacheUsage(
                audioBytes = offlineAudioBytes.coerceAtLeast(0L),
                imageBytes = imageBytes,
                otherCacheBytes = (cacheDirBytes - imageBytes).coerceAtLeast(0L),
            )
        }
    }
}

/** Android 侧入口：离线音频那部分只有 [OfflineAudioCache] 量得出来。 */
internal fun measureCacheUsage(context: Context): CacheUsage =
    runCatching { CacheUsage.measure(context.cacheDir, OfflineAudioCache.sizeBytes(context)) }
        .getOrDefault(CacheUsage.ZERO)

/** 递归目录大小；目录不存在 / 不可读一律 0（统计失败不该让设置页崩）。 */
internal fun folderSize(dir: File?): Long {
    if (dir == null || !dir.isDirectory) return 0L
    var size = 0L
    dir.listFiles()?.forEach { f ->
        size += if (f.isDirectory) folderSize(f) else f.length()
    }
    return size
}
