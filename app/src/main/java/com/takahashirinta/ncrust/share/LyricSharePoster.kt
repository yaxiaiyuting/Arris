/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log

/**
 * 出图的**编排与降级**：把封面加载、渲染、落盘三步串起来，并保证任何一步失败都有下一条路。
 *
 * ## 降级链（每一级只试一次，没有重试）
 *
 * | 级 | 内容 | 触发条件 |
 * |---|---|---|
 * | ① | 1080×1920 **带封面** | 默认路径 |
 * | ② | 1080×1920 **无封面** | 封面 4s 超时 / 下载失败 / ① 抛异常 |
 * | ③ | 720×1280 无封面 | ① ② 抛 `OutOfMemoryError` 或其他 `Throwable` |
 * | ④ | **放弃出图** | ③ 也失败 ⇒ 返回 null，调用方退回「分享纯文本」 |
 *
 * 第 ④ 级是「有界」的关键：本对象**不循环重试**，三级走完就结束，
 * 上层拿到 null 只会做一件事 —— 分享文本。分享功能因此不可能因为出图失败而整个不可用。
 *
 * ## 线程
 *
 * 全部 `suspend` 且必须在 `Dispatchers.IO` 上调用（渲染是 CPU 密集 + 8MB 分配，
 * Coil 的 `execute` 自己会切线程，但 `Canvas` 绘制是本函数做的）。
 * 本对象**不自己切调度器** —— 由调用方（UI 层）统一决定，便于单测替换。
 */
object LyricSharePoster {

    private const val TAG = "LyricShare"

    /** 渲染请求（不含降级参数，降级由本对象自己决定）。 */
    data class Request(
        val track: LyricShareTrack,
        val lines: List<LyricShareLine>,
        val currentIndex: Int,
        val colors: LyricPosterColors,
        val showTranslation: Boolean,
        val showRomanization: Boolean,
        val credit: String,
    )

    /** 渲染结果。 */
    data class Rendered(
        val bitmap: Bitmap,
        /** 这张图有没有印上封面（false = 走了降级版式）。 */
        val withCover: Boolean,
        val scale: Float,
    )

    /** 出图并写进分享缓存；返回 null = 出图失败（调用方应退回纯文本分享）。 */
    suspend fun renderToShareCache(
        context: Context,
        request: Request,
        fileName: String,
    ): Uri? {
        val rendered = renderWithFallback(context, request) ?: return null
        return try {
            LyricShareActions.writeShareImage(context, rendered.bitmap, fileName)
        } finally {
            // 我们自己创建的位图，用完即回收：不回收的话连续出图会很快把堆吃满。
            // （注意：**不能**回收封面位图 —— 它属于 Coil 的内存缓存。）
            rendered.bitmap.recycle()
        }
    }

    /** 出图并存进相册（API 29+）。返回 false 时会回收位图。 */
    suspend fun renderToGallery(
        context: Context,
        request: Request,
        fileName: String,
    ): Boolean {
        val rendered = renderWithFallback(context, request) ?: return false
        return try {
            LyricShareActions.saveImageToGallery(context, rendered.bitmap, fileName)
        } finally {
            rendered.bitmap.recycle()
        }
    }

    /**
     * 三级降级渲染。返回 null = 三级全部失败。
     *
     * ⚠️ `catch (Throwable)` 在这里是**必须**的，不是偷懒：出图失败的形态包括
     * `OutOfMemoryError`（低端机 8MB 分配）与 `IllegalArgumentException`
     * （硬件位图 / 空文本），它们都不是 `Exception`。
     */
    private suspend fun renderWithFallback(context: Context, request: Request): Rendered? {
        val cover = LyricPosterCover.load(context, request.track.coverUrl)

        val attempts = buildList {
            if (cover != null) {
                add(Triple(cover, 1f, true))
            }
            add(Triple(null, 1f, false))
            if (!LyricPosterLayout.FALLBACK_SCALE.isOne()) {
                add(Triple(null, LyricPosterLayout.FALLBACK_SCALE, false))
            }
        }

        for ((bitmapCover, scale, withCover) in attempts) {
            val spec = LyricPosterRenderer.LyricPosterSpec(
                track = request.track,
                lines = request.lines,
                currentIndex = request.currentIndex,
                colors = request.colors,
                showTranslation = request.showTranslation,
                showRomanization = request.showRomanization,
                credit = request.credit,
                cover = bitmapCover,
                scale = scale,
            )
            val bitmap = try {
                LyricPosterRenderer.render(spec)
            } catch (t: Throwable) {
                Log.w(TAG, "poster render failed (cover=$withCover scale=$scale)", t)
                continue
            }
            // 成功也打一行：真机 / 模拟器上确认「入口有没有走到出图、用的是哪一档降级」时，
            // logcat 比截图快得多（`adb logcat -s LyricShare`）。
            Log.i(
                TAG,
                "poster rendered ${bitmap.width}x${bitmap.height} cover=$withCover scale=$scale lines=${request.lines.size}",
            )
            return Rendered(bitmap, withCover, scale)
        }
        Log.w(TAG, "poster render gave up after ${attempts.size} attempt(s)")
        return null
    }

    private fun Float.isOne(): Boolean = this >= 0.999f
}
