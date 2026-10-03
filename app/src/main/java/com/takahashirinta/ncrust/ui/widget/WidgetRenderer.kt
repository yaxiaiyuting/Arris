/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**把快照画成 RemoteViews**。
 *
 * ## RemoteViews 的硬边界（踩之前先看这里）
 *
 * 桌面卡片不是 Compose：它在**宿主进程**（launcher / 桌面）里被 inflate 与 apply，
 * 用的还是宿主自己的主题。因此能用的只有 `@RemoteView` 白名单里的那几个类
 * （`FrameLayout` / `LinearLayout` / `RelativeLayout` / `GridLayout` + `TextView` /
 * `ImageView` / `ImageButton` / `Button` / `ProgressBar` / `Chronometer` / `ViewFlipper` /
 * `ListView` 等），**没有 Compose、没有自定义 View**。
 *
 * 由此直接推出一条需求侧的结论：**波形在桌面卡片上做不出来**。
 *   · 波形（`ui/player/waveform/`）是 `Canvas` + `drawPath` 逐帧绘制的，
 *     RemoteViews 没有任何「自绘」通道；
 *   · 唯一的替代是「把波形光栅化成一张位图塞进 ImageView」，那是**静态截图**：
 *     每秒推一张位图 = 每秒一次 ~150KB 的跨进程拷贝 + 桌面进程一次解码，
 *     代价与收益完全不成比例（而且暂停时它就冻住了）。
 * 所以本版在卡片上**不做波形**：主色条 + 真进度条 + 直角排版，已经足够「像 Ncrust」。
 * 这一条是设计决定，不是遗漏。
 */

package com.takahashirinta.ncrust.ui.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.takahashirinta.ncrust.MainActivity
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.player.PlaybackService
import com.takahashirinta.ncrust.ui.i18n.WidgetStrings

/**
 * 桌面卡片上的三个动作 + 整卡点击的 `PendingIntent`。
 *
 * 三键**逐字复用** `PlaybackService.buildPI` 的做法：`PendingIntent.getService`
 * 指向 `PlaybackService` 自己、`putExtra("action", …)`，由 `onStartCommand` 的
 * `when` 分支消费（`"previous"` / `"pause"` / `"resume"` / `"next"`）。
 * request code 同样取 `action.hashCode()` —— 与通知栏那三个是**同一个** PendingIntent，
 * 不额外增加系统里的 PendingIntent 数量。
 *
 * `FLAG_IMMUTABLE` 必须保留：API 31+ 起可变性是强制的，漏了会在创建时抛
 * `IllegalArgumentException`（targetSdk 36 下必崩）。
 */
internal object WidgetIntents {

    /** 整卡点击：request code 与通知栏那条刻意区分开（Intent 形状不同，避免互相覆盖）。 */
    private const val REQ_OPEN_APP = 0x4E43

    fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // 与桌面图标同形状：已经在栈里就把它带到前台，而不是再开一个 MainActivity。
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun service(context: Context, action: String): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).apply {
            putExtra("action", action)
        }
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

internal object WidgetRenderer {

    /** 空态时主色条用的中性灰：此时没有封面，任何主色都是编的。 */
    private const val EMPTY_ACCENT = 0x33FFFFFF

    fun layoutResFor(size: WidgetSize): Int = when (size) {
        WidgetSize.SMALL -> R.layout.widget_player_small
        WidgetSize.MEDIUM -> R.layout.widget_player_medium
        WidgetSize.LARGE -> R.layout.widget_player_large
    }

    /**
     * 构建一份 RemoteViews。
     *
     * ★ 只对**当前档位布局里真的存在**的 id 发 action（判据来自
     * [WidgetLayoutSpec.capabilitiesFor]）：对不存在的 id 发 action 会在宿主进程里抛
     * `ActionException`，应用侧一声不响，用户看到的是白卡片。
     *
     * @param artworkToShip 这一次要随之发送的封面位图；null = 沿用宿主手上那张
     *   （见 [WidgetArtworkPolicy.shouldShip]），或本来就没有封面。
     */
    fun build(
        context: Context,
        size: WidgetSize,
        snapshot: WidgetSnapshot,
        strings: WidgetStrings,
        artworkToShip: Bitmap?,
    ): RemoteViews {
        if (!snapshot.hasContent) return buildEmpty(context, strings)

        val caps = WidgetLayoutSpec.capabilitiesFor(size)
        val rv = RemoteViews(context.packageName, layoutResFor(size))

        // ① 主色：整张卡片上唯一能做「像 Ncrust」的手段（Kanesumi 直角 + 主色块）。
        rv.setInt(R.id.widget_accent, "setBackgroundColor", snapshot.accent)
        rv.setInt(R.id.widget_cover_box, "setBackgroundColor", snapshot.accent)
        rv.setInt(R.id.widget_play, "setBackgroundColor", snapshot.accent)

        // ② 文案。缺什么补什么 —— 卡片上不能出现空行（见 WidgetTextFormat）。
        rv.setTextViewText(
            R.id.widget_title,
            WidgetTextFormat.titleOrFallback(snapshot.title, strings.widgetUnknownTitle),
        )
        if (caps.showArtist) {
            rv.setTextViewText(
                R.id.widget_artist,
                WidgetTextFormat.artistOrFallback(snapshot.artist, strings.widgetUnknownArtist),
            )
        }

        // ③ 整卡点击 → 打开应用。
        rv.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.openApp(context))
        rv.setContentDescription(R.id.widget_root, strings.widgetOpenApp)

        // ④ 播放/暂停：同一个按钮，图标与动作都跟着 isPlaying 走。
        val playing = snapshot.isPlaying
        rv.setImageViewResource(
            R.id.widget_play,
            if (playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
        )
        rv.setContentDescription(
            R.id.widget_play,
            if (playing) strings.widgetPause else strings.widgetPlay,
        )
        rv.setOnClickPendingIntent(
            R.id.widget_play,
            WidgetIntents.service(context, if (playing) "pause" else "resume"),
        )

        // ⑤ 上一首 / 下一首：只有中/大档有。
        if (caps.showTransport) {
            rv.setImageViewResource(R.id.widget_prev, R.drawable.widget_ic_prev)
            rv.setContentDescription(R.id.widget_prev, strings.widgetPrevious)
            rv.setOnClickPendingIntent(R.id.widget_prev, WidgetIntents.service(context, "previous"))

            rv.setImageViewResource(R.id.widget_next, R.drawable.widget_ic_next)
            rv.setContentDescription(R.id.widget_next, strings.widgetNext)
            rv.setOnClickPendingIntent(R.id.widget_next, WidgetIntents.service(context, "next"))
        }

        // ⑥ 封面。没有封面时显的是主色占位块 + 音符（封面框的底色就是主色）。
        if (snapshot.artwork != null) {
            rv.setViewVisibility(R.id.widget_cover_icon, View.GONE)
            rv.setViewVisibility(R.id.widget_cover, View.VISIBLE)
            if (artworkToShip != null) {
                rv.setImageViewBitmap(R.id.widget_cover, artworkToShip)
            }
        } else {
            rv.setViewVisibility(R.id.widget_cover, View.GONE)
            rv.setViewVisibility(R.id.widget_cover_icon, View.VISIBLE)
        }

        // ⑦ 进度 + 计时：只有大档有。
        if (caps.showProgress) {
            val bar = WidgetProgressSpec.values(snapshot.positionMs, snapshot.durationMs)
            if (bar == null) {
                // 时长未知（直播流 / metadata 还没到）：整行不画。
                // 一条永远停在 0% 的进度条比没有进度条更像坏了。
                rv.setViewVisibility(R.id.widget_progress_row, View.GONE)
            } else {
                rv.setViewVisibility(R.id.widget_progress_row, View.VISIBLE)
                rv.setProgressBar(R.id.widget_progress, bar.max, bar.progress, false)
                // Chronometer 自己走秒：宿主每秒自己 updateText，**不需要我们推**。
                // base = 开机以来的毫秒 - 已播毫秒 ⇒ 它显示的正好是当前播放位置；
                // started = isPlaying ⇒ 暂停时它停在原位（而不是继续涨）。
                rv.setChronometer(
                    R.id.widget_elapsed,
                    SystemClock.elapsedRealtime() - snapshot.positionMs.coerceAtLeast(0L),
                    null,
                    snapshot.isPlaying,
                )
                rv.setTextViewText(
                    R.id.widget_duration,
                    WidgetTextFormat.formatTime(snapshot.durationMs),
                )
            }
        }

        return rv
    }

    /**
     * 空态：占位图标 + 一行文案 + 整卡点击。
     *
     * 需求第 7 条「不能崩、不能空白」。这一档**刻意**用独立布局：
     * 在内容布局上把每个控件逐个设成空串/隐藏也能达到效果，但那样每加一个控件就要
     * 记得在新分支里补一次隐藏 —— 独立布局是「新增控件不可能忘记处理空态」的物理保证。
     */
    private fun buildEmpty(context: Context, strings: WidgetStrings): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_player_empty)
        rv.setInt(R.id.widget_accent, "setBackgroundColor", EMPTY_ACCENT)
        rv.setTextViewText(R.id.widget_empty_text, strings.widgetEmpty)
        rv.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.openApp(context))
        rv.setContentDescription(R.id.widget_root, strings.widgetOpenApp)
        return rv
    }
}
