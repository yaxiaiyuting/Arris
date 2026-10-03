/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**卡片上的文案格式化**。
 *
 * 桌面卡片与 Compose 界面最大的不同是：**这里没有可空渲染**。
 * 一个空串在 Compose 里就是「这一行不显示」，在 RemoteViews 里就是「桌面上白留一行」——
 * 用户看到的是一张缺了一块的卡片。所以「缺什么补什么」必须发生在渲染之前，
 * 而且必须可测。
 *
 * 纯逻辑（无 android.* 依赖），由 `WidgetTextFormatTest` 覆盖。
 */

package com.takahashirinta.ncrust.ui.widget

object WidgetTextFormat {

    /**
     * 毫秒 → `m:ss`（超过 1 小时给 `h:mm:ss`）。
     *
     * 与 `MainActivity` 里 Compose 侧的 `formatDuration` 同口径，**刻意不共用**：
     * 那个函数在 UI 层、签名收 `Long?` 并带各自的空态约定，而卡片要的是「永远给得出一个
     * 能显示的串」。共用会让「改播放器时长格式」顺手改掉桌面卡片（或反过来）。
     *
     * 负数 / 未知时长（media3 的 `C.TIME_UNSET` 是 `Long.MIN_VALUE`）一律按 0 处理 ——
     * 卡片上出现 `-1:-1` 是最典型的「没测过真机」痕迹。
     */
    fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) {
            "$hours:${pad(minutes)}:${pad(seconds)}"
        } else {
            "$minutes:${pad(seconds)}"
        }
    }

    private fun pad(value: Long): String = if (value < 10L) "0$value" else value.toString()

    /** 标题兜底：空白标题（ncm 有纯音乐曲目）换成 [fallback]，绝不发空串。 */
    fun titleOrFallback(title: String?, fallback: String): String =
        title?.takeIf { it.isNotBlank() } ?: fallback

    /** 歌手兜底。 */
    fun artistOrFallback(artist: String?, fallback: String): String =
        artist?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * `ProgressBar` 的两个数值。
 *
 * 为什么把 `max` 固定成 1000 而不是直接拿时长当 max：RemoteViews 的
 * `setProgressBar(id, max, progress, false)` 会把两个 int 原样送进宿主，
 * 宿主再调 `ProgressBar.setMax/setProgress`。时长是 `Long`（毫秒），
 * 直接当 max 会溢出成负数；而 1000 档的分辨率（3 分钟的歌 ≈ 5.5 档/秒）
 * 已经比「每秒推一次」的刷新率更细，用户看不出来。
 */
data class WidgetProgressValues(val max: Int, val progress: Int)

object WidgetProgressSpec {

    /** 进度条总档数。见 [WidgetProgressValues] 的说明。 */
    const val MAX = 1000

    /**
     * 进度值；**时长未知时返回 null**（直播流 / 还没读到 metadata / `TIME_UNSET`）。
     *
     * 返回 null 的含义是「这一档不要画进度条」，而不是「画一条 0% 的」——
     * 一条永远不动的 0% 进度条比没有进度条更像坏了。
     */
    fun values(positionMs: Long, durationMs: Long): WidgetProgressValues? {
        if (durationMs <= 0L) return null
        val clamped = positionMs.coerceIn(0L, durationMs)
        val progress = (clamped.toDouble() / durationMs.toDouble() * MAX).toInt()
        return WidgetProgressValues(MAX, progress.coerceIn(0, MAX))
    }
}
