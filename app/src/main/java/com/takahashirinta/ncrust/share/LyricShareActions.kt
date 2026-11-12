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
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/** 歌词分享的剪贴板、系统分享和图库操作。 */
object LyricShareActions {
    private const val TAG = "LyricShare"
    private const val MIME_PNG = "image/png"
    // 微信朋友圈匹配图片通配类型。
    private const val MIME_IMAGE = "image/*"
    private const val GALLERY_RELATIVE_PATH = "Pictures/Arris"
    private const val SHARE_RELATIVE_PATH = "$GALLERY_RELATIVE_PATH/Shared"
    private const val SHARE_MEDIA_TTL_SECONDS = 600L

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
     * `ACTION_SEND` 使用图库的 `content://` URI。
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

    /** 分享图通过图库发布，供接收方重新读取。 */
    fun publishShareImage(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        prunePublishedImages(context)
        return writeMediaImage(context, bitmap, fileName, SHARE_RELATIVE_PATH)
    }

    fun saveImageToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean =
        writeMediaImage(context, bitmap, fileName, GALLERY_RELATIVE_PATH) != null

    private fun writeMediaImage(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        relativePath: String,
    ): Uri? {
        val resolver = context.contentResolver
        var pending: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            pending = uri
            val written = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (!written) throw java.io.IOException("Could not write image")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Image published to $relativePath: $uri")
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "Could not publish image to $relativePath", t)
            pending?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    /** 删掉我们自己发布、且超过 [SHARE_MEDIA_TTL_SECONDS] 的分享图。 */
    private fun prunePublishedImages(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() / 1000L - SHARE_MEDIA_TTL_SECONDS
            val resolver = context.contentResolver
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.DATE_ADDED} < ?"
            val args = arrayOf("$SHARE_RELATIVE_PATH/", cutoff.toString())
            resolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                selection,
                args,
            )
        }.onFailure { Log.w(TAG, "prunePublishedImages failed", it) }
    }
}
