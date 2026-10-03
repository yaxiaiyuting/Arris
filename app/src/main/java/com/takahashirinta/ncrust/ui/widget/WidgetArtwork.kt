/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**封面位图的缩放**。
 *
 * ## 为什么必须缩（而不是把通知栏那张直接塞进去）
 *
 * `RemoteViews` 的每一次 `updateAppWidget` 都要把 action 列表跨进程 parcel 一遍，
 * 而 `setImageViewBitmap` 的位图是**按像素值拷贝**的。Binder 事务的硬上限是 1MB
 * （`TransactionTooLargeException` 会在**宿主进程**里抛，表现为卡片直接白掉 + 应用侧
 * 只看到一条 warning），所以：
 *
 * | 来源 | 尺寸 | ARGB_8888 体积 | 结果 |
 * |---|---|---|---|
 * | 原图（网易云 500² 以上） | 500×500 | 1000KB | 贴着上限，随时炸 |
 * | `PlaybackService` 的通知封面 | ≤512×512 | ≤1024KB | 同上，**不能直接用** |
 * | 本类的产物（40/48/56dp × 密度，夹到 192px） | ≤192×192 | ≤147KB | 安全 |
 *
 * 顺带一个只有真机上才看得出来的好处：卡片上的封面本来就只有 40~56dp，
 * 送一张 512² 的图过去，宿主还要为它做一次降采样 —— 那是每秒钟都在浪费的 CPU。
 */

package com.takahashirinta.ncrust.ui.widget

import android.graphics.Bitmap
import android.util.Log

internal object WidgetArtwork {

    private const val TAG = NcrustWidgetProvider.TAG

    /**
     * 把封面压成**正方形 + 目标像素**。
     *
     * 先按短边中心裁成正方形，再缩到 [targetPx]：卡片上的封面框是正方形，
     * 让 `ImageView` 的 `centerCrop` 去裁的话，宿主每帧都要算一次矩阵；
     * 在这里裁掉，送过去的位图与显示区域是 1:1。
     *
     * @param densityDpi 屏幕密度。见文件末尾关于 `bitmap.density` 的说明。
     * @return null 表示这次拿不到可用的封面（调用方按「没有封面」渲染主色占位块）。
     */
    fun squareScaled(source: Bitmap?, targetPx: Int, densityDpi: Int): Bitmap? {
        if (source == null || targetPx <= 0) return null
        return runCatching {
            if (source.isRecycled) return null
            val side = minOf(source.width, source.height)
            if (side <= 0) return null

            val square = if (source.width == side && source.height == side) {
                source
            } else {
                Bitmap.createBitmap(
                    source,
                    (source.width - side) / 2,
                    (source.height - side) / 2,
                    side,
                    side,
                )
            }

            // createScaledBitmap 在目标尺寸与源相同时会**原样返回源对象**，
            // 而 createBitmap 在裁剪区域等于整张图时也会返回源对象 —— 于是这里必须判一次
            // 「拿到的到底是不是别人的位图」：是的话复制一份再改 density。
            // 直接给 currentArtworkBitmap 改 density 会连带影响通知栏那张大图标
            // （BitmapDrawable 会按 density 再缩一次），是一个极难定位的跨模块副作用。
            val scaled = if (square.width == targetPx && square.height == targetPx) {
                square
            } else {
                Bitmap.createScaledBitmap(square, targetPx, targetPx, true)
            }
            val owned = if (scaled === source) {
                source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                scaled
            }

            // 让宿主按 1:1 像素画：位图密度 = 屏幕密度 ⇒ BitmapDrawable 不再二次采样。
            // 不设的话，源位图继承的是 PlaybackService 那张图自身的密度，
            // 在 4.0x 屏上会被再放大一次（糊）或缩小一次（尺寸对不上封面框）。
            owned.density = densityDpi
            owned
        }.onFailure {
            // 缩封面失败绝不能让桌面卡片连带服务一起挂：降级成「没有封面」。
            Log.w(TAG, "artwork downscale failed (targetPx=$targetPx)", it)
        }.getOrNull()
    }
}
