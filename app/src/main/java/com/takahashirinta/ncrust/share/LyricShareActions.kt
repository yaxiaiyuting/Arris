/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 歌词分享要用到的**全部系统交互**：剪贴板、`ACTION_SEND`、FileProvider、MediaStore。
 *
 * 单独放一个文件的理由：这些是本功能里唯一「会在真机上以奇怪方式失败」的部分
 * （没有接收方、没有权限、缓存目录写不进去），集中在一处便于逐个 try/catch，
 * 也便于让上层只看到 `Boolean` / `Uri?` 而不是一堆异常。
 */
object LyricShareActions {

    private const val TAG = "LyricShare"

    /**
     * 出图落盘的子目录（`cacheDir/share/`）。
     *
     * 为什么是 **cacheDir** 而不是 filesDir / 相册：
     * - FileProvider 分享**不需要任何存储权限**（对比：写相册在 API ≤ 28 需要
     *   `WRITE_EXTERNAL_STORAGE`，本版按边界只加了 `<provider>`、没加权限声明）；
     * - 系统在空间紧张时会自己回收 cache，用户不会在文件管理器里看到一堆导出残留。
     *
     * 与 `res/xml/file_paths.xml` 里的 `<cache-path name="share_images" path="share/" />`
     * **必须成对改动** —— 对不上时 `FileProvider.getUriForFile` 会抛
     * `IllegalArgumentException: Failed to find configured root`。
     */
    const val SHARE_DIR = "share"

    /** 产出文件名里带的时刻戳格式见 [LyricShareText.stamp]。 */
    private const val MIME_PNG = "image/png"
    /** 分享用的通配类型：朋友圈那条链只对通配类型做匹配（见 [shareImage]）。 */
    private const val MIME_IMAGE = "image/*"

    /** 缓存里最多留几张历史海报；超出的按修改时间从旧到新删。 */
    private const val SHARE_CACHE_MAX_FILES = 20

    /** 缓存文件最长保留 24 小时。 */
    private const val SHARE_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    // ---------------------------------------------------------------- 剪贴板

    /**
     * 纯文本复制。
     *
     * 返回值代表「有没有真的写进去」——服务端（这里指系统剪贴板服务）拿不到时返回 false，
     * 上层据此提示失败，而不是无条件弹「已复制」。
     */
    fun copyToClipboard(context: Context, label: String, text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            // 可观测性：`adb logcat -s LyricShare` 能确认「复制真的发生了」，不用去读剪贴板。
            Log.i(TAG, "clipboard set: ${text.length} chars")
            true
        } catch (t: Throwable) {
            // 个别 ROM 在剪贴板服务异常时会抛 RemoteException / SecurityException。
            Log.w(TAG, "copyToClipboard failed", t)
            false
        }
    }

    // ---------------------------------------------------------------- 分享

    /** `ACTION_SEND` + `text/plain`（走系统选择器）。 */
    fun shareText(context: Context, subject: String, text: String, chooserTitle: String): Boolean {
        if (text.isBlank()) return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        Log.i(TAG, "shareText chooser: ${text.length} chars")
        return startChooser(context, send, chooserTitle)
    }

    /**
     * `ACTION_SEND` + `image/png`，用 FileProvider 的 `content://` URI。
     *
     * `clipData` 与 `FLAG_GRANT_READ_URI_PERMISSION` **两个都要给**：
     * 前者是给「只读 `Intent.getClipData()`」的接收方（部分国产 IM 就是这么取的），
     * 后者是给标准实现 —— 少一个就会出现「分享出去是空白图」。
     */
    fun shareImage(context: Context, uri: Uri, chooserTitle: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            // 用 image/* 而不是 image/png：微信朋友圈那条转发链只对通配类型做匹配，
            // 精确到 png 时部分版本不会出现在「分享到朋友圈」列表里。
            type = MIME_IMAGE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "lyrics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 三条都要，少一条就可能在微信里变成「无法获取资源」：
        //  ① `send` 上的 flag —— 标准实现；
        //  ② `chooser` 上的 flag —— createChooser 会新建一层 Intent，部分系统只透传它自己的 flag；
        //  ③ 对**每个可接收的应用**显式 grantUriPermission —— 有的 IM 用自己进程的 ContentResolver
        //     直接打开 uri，而不是走系统临时授权，没这一步就拿到 SecurityException。
        grantImageRead(context, send, uri)
        Log.i(TAG, "shareImage chooser: $uri")
        return startChooser(context, send, chooserTitle)
    }

    /** 给所有能处理这条 ACTION_SEND 的应用显式授予读权限（见 [shareImage] 的第 ③ 条）。 */
    private fun grantImageRead(context: Context, send: Intent, uri: Uri) {
        val targets = runCatching {
            context.packageManager.queryIntentActivities(send, 0)
        }.getOrDefault(emptyList())
        targets.forEach { info ->
            runCatching {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun startChooser(context: Context, send: Intent, chooserTitle: String): Boolean {
        val chooser = Intent.createChooser(send, chooserTitle)
        // 见 shareImage 的第 ② 条：chooser 自己也要带上读权限 flag。
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Compose 的 LocalContext 在 Activity 里就是 Activity；不是的话必须补 NEW_TASK，
        // 否则 startActivity 直接抛 AndroidRuntimeException。
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            // 设备上一个能处理 ACTION_SEND 的应用都没有（精简 ROM / 车机）。
            Log.w(TAG, "no activity for chooser", t)
            false
        }
    }

    // ---------------------------------------------------------------- 落盘

    /**
     * 把海报写进 `cacheDir/share/<fileName>` 并返回 FileProvider URI。
     *
     * 返回 null = 写失败（磁盘满 / provider 配置对不上），上层走降级。
     */
    /**
     * 把海报**发布成系统图片**并返回分享用的 uri（v3.7.11）。
     *
     * ## 为什么分享不能只用 FileProvider
     *
     * 现场：FileProvider 的 `content://…fileprovider/share/xxx.png` + 三种授权都给了，
     * 微信朋友圈仍然提示「无法获取资源」。原因是朋友圈那条链会**自己再取一次**这张图
     * （它的转发/压缩流程在别的进程里跑），只认 `MediaStore` 里的 `content://media/…`
     * 条目 —— 第三方 provider 的临时授权在这种二次读取里会失效。
     *
     * 所以 API 29+ 一律走 MediaStore：先插一条 `Pictures/Arris` 的待发布记录，写完字节
     * 再清 `IS_PENDING`，返回的 uri 就是系统图库条目，任何 IM 都能读。API ≤ 28 没有
     * 免权限的 MediaStore 写入，仍退回 FileProvider（那里朋友圈不是主要场景）。
     */
    fun publishShareImage(context: Context, bitmap: android.graphics.Bitmap, fileName: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(context, bitmap, fileName)
        } else {
            writeShareImage(context, bitmap, fileName)
        }

    /** 分享图的系统图库目录（相对 `Pictures/`）与保留时长。 */
    // 3.7.13 用的是 Pictures/Arris/Shared；合并取更具体的子目录（清理判据也按它匹配，
    // 越具体越不会误删用户在 Pictures/Arris 下自己的图片）。
    private const val SHARE_RELATIVE_PATH = "Pictures/Arris/Shared"
    private const val SHARE_MEDIA_TTL_SECONDS = 600L

    private fun publishToMediaStore(
        context: Context,
        bitmap: android.graphics.Bitmap,
        fileName: String,
    ): Uri? {
        // 先清理过期条目：分享出去的图留在图库只是"中转"，不该长期堆着。
        prunePublishedImages(context)
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, SHARE_RELATIVE_PATH)
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                    throw java.io.IOException("compress failed")
                }
            } ?: throw java.io.IOException("no output stream")
            val done = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            Log.i(TAG, "share image published to MediaStore: $uri")
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "publishToMediaStore failed", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** 删掉我们自己发布、且超过 [SHARE_MEDIA_TTL_SECONDS] 的分享图。 */
    private fun prunePublishedImages(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() / 1000L - SHARE_MEDIA_TTL_SECONDS
            val resolver = context.contentResolver
            val selection = "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${android.provider.MediaStore.Images.Media.DATE_ADDED} < ?"
            val args = arrayOf("$SHARE_RELATIVE_PATH/", cutoff.toString())
            resolver.delete(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                selection,
                args,
            )
        }.onFailure { Log.w(TAG, "prunePublishedImages failed", it) }
    }

    fun writeShareImage(context: Context, bitmap: android.graphics.Bitmap, fileName: String): Uri? {
        return try {
            pruneShareCache(context)
            val dir = File(context.cacheDir, SHARE_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) return null
            }
            // 落盘路径与字节数进日志：验证出图时可以直接 `adb pull` 这个文件看效果。
            Log.i(TAG, "poster written: ${file.absolutePath} (${file.length()} bytes)")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (t: Throwable) {
            Log.w(TAG, "writeShareImage failed", t)
            null
        }
    }

    /**
     * 出图缓存的有界回收：先按 TTL 删，再按数量上限删。
     *
     * 每次写新图前调用一次，所以只要用户在分享，目录就不会无限长大。
     * 不放在 `Application.onCreate` 里：那是冷启动路径，不该为一个低频功能加磁盘遍历。
     */
    fun pruneShareCache(context: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            val dir = File(context.cacheDir, SHARE_DIR)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            files.filter { nowMs - it.lastModified() > SHARE_CACHE_TTL_MS }.forEach { it.delete() }
            val rest = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            if (rest.size > SHARE_CACHE_MAX_FILES) {
                rest.take(rest.size - SHARE_CACHE_MAX_FILES).forEach { it.delete() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pruneShareCache failed", t)
        }
    }

    // ---------------------------------------------------------------- 相册

    /**
     * 「保存到相册」在当前系统上是否可用。
     *
     * **只有 API 29+ 可用**：那一版起 `MediaStore` 的 `RELATIVE_PATH` + `IS_PENDING`
     * 让应用无需任何存储权限就能写进 `Pictures/`。API ≤ 28 必须持有
     * `WRITE_EXTERNAL_STORAGE`（运行时还要用户授权），而本版按边界**只加了 FileProvider、
     * 没有加任何新权限**，所以在旧系统上这一项直接不显示 —— 比显示一个必然失败的按钮诚实。
     * 分享（`ACTION_SEND` + FileProvider）在 API 24 上就是完好的，所以旧系统并不缺功能。
     */
    fun canSaveToGallery(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * 存进系统相册（`Pictures/Arris/`）。仅 API 29+ 调用，见 [canSaveToGallery]。
     */
    fun saveImageToGallery(context: Context, bitmap: android.graphics.Bitmap, fileName: String): Boolean {
        if (!canSaveToGallery()) return false
        val resolver = context.contentResolver
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Arris",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val target = uri ?: return false
            val ok = resolver.openOutputStream(target)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (!ok) {
                resolver.delete(target, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            Log.i(TAG, "saved to gallery: $target")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "saveImageToGallery failed", t)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }
}
