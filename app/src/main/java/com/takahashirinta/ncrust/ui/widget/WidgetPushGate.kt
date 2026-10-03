/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**推送节流**（本版 P0）。
 *
 * ## 为什么节流是 P0
 *
 * `PlaybackService` 的进度 ticker 是 **2Hz**（`delay(500)`）。桌面卡片如果照搬这个节奏，
 * 就是「每秒 2 次 Binder 事务 + 2 次 RemoteViews 反序列化 + 2 次跨进程 View 重建」：
 *   · `AppWidgetManager.updateAppWidget` 是**跨进程**调用，每次都要把整棵 RemoteViews
 *     的 action 列表 parcelled 过去，**封面位图每次都重新拷贝一遍**（这就是 1MB 事务上限的来源）；
 *   · 宿主（launcher）进程要按 action 列表逐条反射调用，桌面上的每一次都会掉帧；
 *   · 应用侧为此产生大量短命对象 → GC 抖动，而音乐播放场景本来就要求零卡顿（AGENTS.md 原则 2）。
 * 所以推送判据必须「**只有内容真的变了才推**」，而且这个判据要能被单测钉住。
 *
 * ## 四条推送时机（用户需求第 4 条）
 *
 * 1. **播放/暂停切换** —— 按钮图标与计时状态都要换；
 * 2. **切歌** —— 标题/歌手/封面/时长全换；
 * 3. **整数秒变化** —— 进度条与计时（且**只在播放中**）；
 * 4. **尺寸变化** —— 换布局档位。
 *
 * 除此之外一律不推。特别注意第 3 条的逆命题：**暂停时停止推送**。
 * 暂停时进度不会变（除非用户 seek），2Hz 推一条不会变的进度条纯属浪费电池；
 * 真机上用户对「暂停后还在后台每秒唤醒一次」是能感知到耗电的。
 *
 * 第 5 条（封面从无到有 / 从有到无）是需求四条之外**必须补**的一条，理由见
 * [WidgetPushKey.hasArtwork]：不补的话「暂停时封面来迟」与「暂停久了封面被释放再恢复」
 * 这两条路都会让卡片停在音符占位上，而它看起来像 bug 而不是节流。
 *
 * 这个文件是纯逻辑（无 android.* 依赖），由 `WidgetPushGateTest` 覆盖全部边界。
 */

package com.takahashirinta.ncrust.ui.widget

/**
 * 一次推送的**内容指纹**。
 *
 * 只放「会改变卡片外观」的字段，而且刻意不放到毫秒：进度条的分辨率就是秒级
 * （见 [WidgetPushGate]），把 `positionMs` 放进来会让 500ms 的 tick 每次都判定为「变了」，
 * 节流立刻失效。
 */
data class WidgetPushKey(
    /** 有没有在播的内容（false = 空态占位）。 */
    val hasContent: Boolean,
    val songId: Long,
    val isPlaying: Boolean,
    /** 秒级进度：`positionMs / 1000`。 */
    val positionSec: Int,
    val size: WidgetSize,
    /**
     * 此刻**有没有封面位图**。
     *
     * 为什么要进指纹：服务会在暂停 N 秒后主动释放封面位图
     * （`scheduleArtworkIdleRelease`），恢复播放时再从 URL 拉回来。
     * 只按「秒数 + 播放态」判据的话，暂停态下这张「迟到/回来了」的封面会被闸门吃掉 ——
     * 用户看到的是「卡片一直显示音符占位，按一下播放才有封面」。
     * 封面从无到有（或从有到无）是**内容真的变了**，与切歌同级，必须推。
     */
    val hasArtwork: Boolean,
)

object WidgetPushGate {

    /**
     * 该不该把 [next] 推给桌面。
     *
     * 判据顺序**有意**如此（先判「与播放无关的切换」，再判「暂停态早退」）：
     * 暂停态也必须推的那两种情况是「切歌」与「暂停本身」——
     * 用户按下暂停，卡片必须立刻变成 ▶；用户在暂停状态下选下一首，标题也必须立刻换。
     * 只有「暂停 + 内容没变」才是真的一律不推。
     *
     * @param previous 上一次**真的推成功**的指纹；null = 本进程还没推过（必然要推一次）。
     */
    fun shouldPush(previous: WidgetPushKey?, next: WidgetPushKey): Boolean {
        if (previous == null) return true
        if (previous.hasContent != next.hasContent) return true
        if (previous.songId != next.songId) return true
        if (previous.isPlaying != next.isPlaying) return true
        if (previous.size != next.size) return true
        // 封面来迟/被释放后回来：与切歌同级的内容变化，暂停时也要推。
        if (previous.hasArtwork != next.hasArtwork) return true
        // 走到这里说明「还是同一首、播放状态没变、档位没变、封面没变」。
        if (!next.isPlaying) return false
        return previous.positionSec != next.positionSec
    }
}

/**
 * 封面位图的**发送**策略（不是缩放策略）。
 *
 * ## 为什么可以「少发几次」
 *
 * `RemoteViews` 的 apply 语义是「**把这份 action 列表作用到现有视图上**」，
 * 布局 id 不变时宿主**复用已有的 View 树**、逐条执行 action —— 没被提到的控件保持原样。
 * 所以「这一秒不带 `setImageViewBitmap`」不会把封面清空，只是省掉了一次
 * 位图跨进程拷贝（192px² × 4B ≈ 147KB，按 1Hz 推就是 ~147KB/s 的无谓流量）。
 *
 * 什么时候必须带：
 *   · 指纹里没有这张图（刚添加卡片 / 进程重启 / 换歌）——[shouldShip] 的 `sameSong=false`；
 *   · 目标像素变了（用户把卡片拖大了，需要更高的分辨率）——`sameTarget=false`；
 *   · 距上次发送超过 [RESHIP_INTERVAL_SEC] —— 自愈：宿主进程可能重启过
 *     （launcher 被系统回收后重建，View 树是从 `initialLayout` 重新 inflate 的，
 *     此时它手上没有我们的封面），定期补发一次能把这种「封面莫名消失」在 15 秒内修回来。
 *
 * ## 这个取舍是**可测的**（[WidgetPushGateTest]）
 *
 * 它的反面（每次都发）不会错，只是贵；它的正面（带条件漏发）错了就是「封面不见了」。
 * 所以判据写成纯函数，边界逐个钉死。
 */
object WidgetArtworkPolicy {

    /** 定期补发间隔。15 秒是「用户察觉不到封面消失」与「少发 15 次位图」之间的折中。 */
    const val RESHIP_INTERVAL_SEC = 15

    /** 位图目标的像素下限：再小就是马赛克了（40dp 的封面在 mdpi 上只有 40px）。 */
    const val MIN_TARGET_PX = 48

    /**
     * 位图目标的像素上限。
     *
     * 192px = 147KB（ARGB_8888），远低于 Binder 的 1MB 事务硬限；
     * 而 56dp 的封面在 xxxhdpi（4.0x）上也只要 224px —— 所以 192 只会在极少数
     * 超高密度屏上略微欠采样（视觉上不可辨），换来的是事务体积恒定安全。
     */
    const val MAX_TARGET_PX = 192

    /**
     * 封面目标像素 = 卡片上的 dp 尺寸 × 屏幕密度，再夹到 [MIN_TARGET_PX] ~ [MAX_TARGET_PX]。
     *
     * 纯函数（密度从参数进来）——「压到卡片像素尺寸」这件事的正确性不依赖真机。
     */
    fun targetPx(coverDp: Int, density: Float): Int {
        val raw = (coverDp.coerceAtLeast(0) * density.coerceAtLeast(0f))
        return when {
            raw.isNaN() -> MIN_TARGET_PX
            raw <= MIN_TARGET_PX.toFloat() -> MIN_TARGET_PX
            raw >= MAX_TARGET_PX.toFloat() -> MAX_TARGET_PX
            else -> raw.toInt()
        }
    }

    /**
     * 这一次推送要不要带封面位图。
     *
     * @param sameSong 与上一次**发送位图**时是同一首（`songId` 相同）。
     * @param sameTarget 与上一次发送时的「目标」相同。调用方把**目标像素**与**布局 id**
     *   一起折进这个布尔：布局 id 一变，宿主就会重新 inflate（新 ImageView 手上没有位图），
     *   而 4×1 → 2×2 这类尺寸变化可能落在同一档（封面 dp 相同）—— 只比像素会漏。
     * @param secSinceLastShip 距上一次发送的秒数；从没发过时传 [Int.MAX_VALUE]。
     */
    fun shouldShip(
        sameSong: Boolean,
        sameTarget: Boolean,
        secSinceLastShip: Int,
    ): Boolean {
        if (!sameSong || !sameTarget) return true
        return secSinceLastShip >= RESHIP_INTERVAL_SEC
    }
}

/**
 * 「桌面上到底有没有我们的卡片」的**查询节流**。
 *
 * `AppWidgetManager.getAppWidgetIds` 是一次 Binder 往返。放在 2Hz 的 ticker 里就是
 * 「没人用桌面卡片的人也每秒被叫醒两次」。这里的判据是：
 * 距上次查询不足 [REFRESH_INTERVAL_SEC] 且上次查到 0 个卡片 ⇒ 直接跳过，
 * 连 Binder 都不发。**添加卡片必然走 `onUpdate`**（系统添加时就会调），
 * 那条路径会立刻把缓存刷成非空，所以这个 30 秒的窗口不会让新卡片漏推。
 */
object WidgetPresencePolicy {

    /** 缓存有效期。30 秒足够覆盖「用户刚删掉卡片」的窗口，也不会让查询变得频繁。 */
    const val REFRESH_INTERVAL_SEC = 30

    /** @param lastRefreshSec 上次查询的时刻（`elapsedRealtime` 秒）；负数 = 从未查询过。 */
    fun shouldRefresh(nowSec: Long, lastRefreshSec: Long): Boolean =
        lastRefreshSec < 0L || nowSec - lastRefreshSec >= REFRESH_INTERVAL_SEC
}
