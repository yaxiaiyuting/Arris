/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**一次渲染所需的全部数据**，以及它的进程内缓存。
 */

package com.takahashirinta.ncrust.ui.widget

import android.graphics.Bitmap

/**
 * 渲染一次桌面卡片所需的全部数据。**不可变**。
 *
 * 为什么不让渲染器自己去 `PlaybackService` 上读：`RemoteViews` 的构建发生在
 * 服务的主线程上，而卡片的数据来源有三个（服务的私有字段 / `PlaybackStateManager`
 * 的落盘 / 桌面进程重启后的空手），把「从哪读」与「怎么画」分开之后，
 * 渲染器就变成一个纯函数式的 `snapshot -> RemoteViews`，不需要知道服务的生命周期。
 */
data class WidgetSnapshot(
    /** false = 空态（从没播过 / 用户按过停止）。 */
    val hasContent: Boolean,
    val songId: Long,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** 已缩到卡片尺寸的封面；null = 没有封面（此时显示主色占位块）。 */
    val artwork: Bitmap?,
    /** 封面主色（`PlaybackService.currentDominantColor`）。 */
    val accent: Int,
) {
    /** 这个快照在某个档位下的推送指纹。 */
    fun pushKey(size: WidgetSize): WidgetPushKey = WidgetPushKey(
        hasContent = hasContent,
        songId = songId,
        isPlaying = isPlaying,
        positionSec = (positionMs / 1000L).toInt(),
        size = size,
        hasArtwork = artwork != null,
    )

    companion object {
        /**
         * 拿不到主色时的兜底：云杉绿，与 `PlaybackService.currentDominantColor` 的初值一致。
         *
         * 卡片上**必须有颜色** —— Kanesumi 风格里那一抹主色是「像 Arris」的唯一手段
         * （RemoteViews 画不了波形、画不了圆角、画不了渐变），没有它卡片就是一块黑砖。
         *
         * 注意这里是 `val` 而不是 `const val`：`0xFF1DB954.toInt()` 是函数调用，
         * 不满足 const 的「编译期常量」要求（写成 `-14987580` 能过编译，但没人读得懂）。
         */
        val DEFAULT_ACCENT: Int = 0xFF1DB954.toInt()

        /** 空态：卡片上显示占位图标 + 一行文案。 */
        fun empty(): WidgetSnapshot = WidgetSnapshot(
            hasContent = false,
            songId = -1L,
            title = "",
            artist = "",
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            artwork = null,
            accent = DEFAULT_ACCENT,
        )
    }
}

/**
 * 上一次**发送位图**的记账（[WidgetArtworkPolicy] 的输入）。
 *
 * @param layoutRes 发送时用的是哪一份布局。
 *   ★ 这一项不能省：宿主在**布局 id 变化时会重新 inflate**（不是复用 View 树），
 *   新 inflate 出来的 ImageView 手上没有位图。而「4×1 拖成 2×2」这种尺寸变化
 *   在 [WidgetLayoutSpec] 里可能落到**同一档**（都是 40dp 封面）⇒ 只比 targetPx 的话
 *   会判定成「刚发过、不用再发」，用户看到的就是「拖一下卡片，封面没了 15 秒」。
 * @param atSec `elapsedRealtime` 秒。用 `elapsedRealtime` 而不是 `currentTimeMillis`：
 *   后者会被用户改时间/NTP 校正，可能倒退，而这里只需要「两个时刻之间过了多久」。
 */
data class WidgetArtworkStamp(
    val songId: Long,
    val targetPx: Int,
    val layoutRes: Int,
    val atSec: Long,
)

/**
 * 进程内的「上次推到桌面的状态」。
 *
 * ## 为什么需要它
 *
 * 1. **节流**：[WidgetPushGate] 要拿上一次的指纹做比较，而服务是每秒调一次推送的，
 *    指纹必须存在某个地方；
 * 2. **尺寸变化**：`onAppWidgetOptionsChanged` 是**系统回调**，它不带任何播放数据 ——
 *    用户拖动卡片边框时，我们只能拿「上一次的快照」重新按新档位渲染。
 *    没有这份缓存，拖动边框就会把卡片刷成空态。
 *
 * ## 线程模型
 *
 * 全部访问都在主线程（`PlaybackService` 的 ticker 跑在 `Dispatchers.Main` 的 scope 上，
 * `AppWidgetProvider` 的回调也在主线程）。仍然加锁：`@Volatile` 保证跨线程可见性，
 * 方法级 `synchronized` 保证 map 的复合操作不会被并发撕裂 —— 这点代价在 1Hz 的节奏下
 * 可以忽略，而「偶发一次错乱导致卡片显示上一首」是极难排查的真机 bug。
 */
object WidgetStateStore {

    @Volatile
    var last: WidgetSnapshot? = null
        private set

    private val keys = HashMap<Int, WidgetPushKey>()
    private val artworkStamps = HashMap<Int, WidgetArtworkStamp>()

    /**
     * 每张卡片当前的档位。
     *
     * 缓存它的唯一理由是省一次 `AppWidgetManager.getAppWidgetOptions`（Binder 往返）——
     * 推送路径每秒都要问一次「这张卡片现在是什么档位」。失效点很明确：
     * `onUpdate`（添加/系统刷新）与 `onAppWidgetOptionsChanged`（用户拖边框）都会重新解析，
     * 其余时刻尺寸不可能变 —— 宿主不会在我们不知情的情况下改卡片尺寸。
     */
    private val sizes = HashMap<Int, WidgetSize>()

    /** 记录一次快照（无论有没有真的推给某个卡片 —— 指纹比较的基准必须是「最新数据」）。 */
    @Synchronized
    fun recordSnapshot(snapshot: WidgetSnapshot) {
        last = snapshot
    }

    @Synchronized
    fun sizeFor(widgetId: Int): WidgetSize? = sizes[widgetId]

    @Synchronized
    fun recordSize(widgetId: Int, size: WidgetSize) {
        sizes[widgetId] = size
    }

    @Synchronized
    fun keyFor(widgetId: Int): WidgetPushKey? = keys[widgetId]

    @Synchronized
    fun recordKey(widgetId: Int, key: WidgetPushKey) {
        keys[widgetId] = key
    }

    @Synchronized
    fun artworkStampFor(widgetId: Int): WidgetArtworkStamp? = artworkStamps[widgetId]

    @Synchronized
    fun recordArtworkStamp(widgetId: Int, stamp: WidgetArtworkStamp) {
        artworkStamps[widgetId] = stamp
    }

    /**
     * 作废位图记账（卡片上没有封面时调用）。
     *
     * 语义是「宿主手上那张图的**有效窗口到此为止**」：下一次封面回来时必须重新发一次。
     * 不这样做的话，「停止 → 立刻重播同一首」这条路径会被判成「同一首、刚发过 ⇒ 不用发」，
     * 而那张卡片刚刚才从空态布局切回内容布局（宿主重新 inflate 过），
     * 结果是封面消失十几秒。
     */
    @Synchronized
    fun forgetArtworkStamp(widgetId: Int) {
        artworkStamps.remove(widgetId)
    }

    /** 卡片被删除时清账，避免 widgetId 被系统复用后带着上一位用户的指纹。 */
    @Synchronized
    fun forget(widgetId: Int) {
        keys.remove(widgetId)
        artworkStamps.remove(widgetId)
        sizes.remove(widgetId)
    }

    /** 全部卡片都没了：连快照一起丢，否则重新添加的卡片会先闪一下很久以前的歌。 */
    @Synchronized
    fun forgetAll() {
        keys.clear()
        artworkStamps.clear()
        sizes.clear()
        last = null
    }
}

/**
 * 「桌面上有没有我们的卡片」的缓存（见 [WidgetPresencePolicy]）。
 *
 * `ids` 是 `AppWidgetManager.getAppWidgetIds` 的结果，只在
 * ① 距上次查询超过 [WidgetPresencePolicy.REFRESH_INTERVAL_SEC] 秒、
 * ② `onUpdate` / `onDeleted` / `onEnabled` / `onDisabled` 这些**系统明确告知桌面发生变化**的回调里
 * 刷新。这样 2Hz 的 ticker 在「桌面上没有卡片」时连一次 Binder 都不发。
 */
object WidgetPresence {

    @Volatile
    private var ids: IntArray = IntArray(0)

    @Volatile
    private var lastRefreshSec: Long = -1L

    /** @return 是否到了该重新查询的时刻。 */
    fun shouldRefresh(nowSec: Long): Boolean =
        WidgetPresencePolicy.shouldRefresh(nowSec, lastRefreshSec)

    fun cached(): IntArray = ids

    fun refresh(newIds: IntArray, nowSec: Long) {
        ids = newIds.copyOf()
        lastRefreshSec = nowSec
    }

    /** 系统回调明确说「桌面变了」时调用：把缓存作废，下一次推送立刻重新查询。 */
    fun invalidate() {
        lastRefreshSec = -1L
    }
}
