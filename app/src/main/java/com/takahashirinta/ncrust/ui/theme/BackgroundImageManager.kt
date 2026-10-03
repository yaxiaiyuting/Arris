/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（v1.2.0 · B3 自定义背景图）：
 *   - 本文件为新增：SAF 选图 → 降采样到屏幕分辨率 → RGB_565 解码 → 存为私有目录 JPEG。
 *   - 内存压力下可临时隐藏背景以释放位图，回前台恢复。
 */
package com.takahashirinta.ncrust.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义背景图的存取管理（v1.2.0 · B3）。
 *
 * 存储策略：原图**不**保留。导入时按屏幕分辨率降采样、以 **RGB_565** 解码
 * （背景图不需要 alpha，内存直接减半），再以 JPEG 存进 app 私有目录。
 * 因此常驻内存只有「屏幕尺寸的一张 RGB_565 位图」≈ 1080×1920×2 ≈ 4.1 MB，
 * 远小于按原图解码（手机照片可达 4000×3000×4 ≈ 48 MB）。
 *
 * 显示走 Coil（项目既有图片库），不裸用 BitmapFactory —— 这里的 BitmapFactory
 * 只用于**一次性转码**，不参与界面渲染。
 */
object BackgroundImageManager {
    private const val TAG = "BackgroundImageManager"
    private const val PREFS = "ncrust_settings"
    private const val KEY_ENABLED = "custom_bg_enabled"
    private const val DIR = "background"
    private const val FILE_NAME = "bg.jpg"
    private const val JPEG_QUALITY = 88

    /**
     * 状态版本号：导入 / 关闭 / 内存压力 时自增，UI 观察它触发重组。
     * 用版本号而非布尔状态，避免把 Activity 级的文件状态缓存成单例。
     */
    val revision = MutableStateFlow(0)

    /** 内存压力下临时隐藏背景（释放那张位图），回到前台时恢复。 */
    val suppressed = MutableStateFlow(false)

    fun backgroundFile(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

    /** 是否应当绘制背景图（开关已开 **且** 文件存在）。 */
    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false) &&
            backgroundFile(context).isFile

    private fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        revision.value++
    }

    /**
     * 从 SAF Uri 导入背景图。整个过程在 IO 线程，返回是否成功。
     *
     * 两次解码：第一次只读边界（inJustDecodeBounds）算 inSampleSize，
     * 第二次才真正解码 —— 绝不把原图整张读进内存。
     */
    suspend fun importFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "import: cannot read bounds for $uri")
                return@runCatching false
            }

            val metrics = context.resources.displayMetrics
            val targetW = metrics.widthPixels.coerceAtLeast(1)
            val targetH = metrics.heightPixels.coerceAtLeast(1)
            var sample = 1
            // 目标：解码结果不小于屏幕，同时尽量小（inSampleSize 只接受 2 的幂，取最大可用值）。
            while (bounds.outWidth / (sample * 2) >= targetW &&
                bounds.outHeight / (sample * 2) >= targetH
            ) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (decoded == null) {
                Log.w(TAG, "import: decode failed for $uri")
                return@runCatching false
            }

            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val tmp = File(dir, "$FILE_NAME.tmp")
            FileOutputStream(tmp).use { out ->
                decoded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            val w = decoded.width
            val h = decoded.height
            decoded.recycle()

            val dst = backgroundFile(context)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "import ok: ${w}x${h} sample=$sample bytes=${dst.length()}")
            true
        }.getOrElse {
            Log.e(TAG, "import failed", it)
            false
        }.also { ok ->
            if (ok) setEnabled(context, true)
        }
    }

    /** 关闭并删除背景图，回落到纯色背景。 */
    fun clear(context: Context) {
        setEnabled(context, false)
        runCatching {
            File(context.filesDir, DIR).deleteRecursively()
        }
        suppressed.value = false
        revision.value++
    }

    /** 内存压力：隐藏背景，让那张位图可以被回收。 */
    fun onMemoryPressure() {
        suppressed.value = true
    }

    /** 回到前台：恢复背景显示（从磁盘缓存重新解码，不走网络）。 */
    fun onForeground() {
        if (suppressed.value) suppressed.value = false
    }
}
