/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget，用户需求第 4 条 = 第 8 条）。
 *
 * ## 交付形态
 *
 * 单 `<receiver>` + 单 `appwidget-provider`（`@xml/ncrust_widget_info`），
 * **按运行时尺寸在 4 份布局里选一份**：
 *
 * | 档位 | 布局 | 内容 |
 * |---|---|---|
 * | 小 2×1 | `widget_player_small` | 封面 + 一行标题 + 播放/暂停 |
 * | 中 4×1 | `widget_player_medium` | 封面 + 标题/歌手 + 三键 |
 * | 大 4×2 | `widget_player_large` | 上一档 + 真进度条 + 计时（Chronometer 宿主自己走秒） |
 * | 空态 | `widget_player_empty` | 音符 + 一行文案（没有播放内容时） |
 *
 * ## 数据从哪来
 *
 * **首选 `PlaybackStateManager.getState()`（SharedPreferences）**：
 * `onUpdate` / `onAppWidgetOptionsChanged` 都跑在**本应用进程**里（AppWidgetProvider 是被系统
 * 显式广播到本包的），所以读 prefs 是零跨进程成本的，而且它在服务被杀掉之后依然可用 ——
 * 这正是「桌面卡片重启后还能显示上一首」的来源。
 *
 * 活的播放数据（封面位图、主色、精确进度）只有服务手上有，所以由
 * [PlaybackService] 在**两个调用点**主动推过来（见 `push` 的文档）。
 * 两条路径共用同一个渲染器，不会出现「服务推的」与「系统问的」长得不一样。
 */

package com.takahashirinta.ncrust.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.ui.i18n.WidgetStrings
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode

class NcrustWidgetProvider : AppWidgetProvider() {

    /** 系统在**添加卡片 / 到达 updatePeriodMillis / 主动刷新**时广播过来。 */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext
        // 系统明确告知「桌面现在的卡片就是这些」—— 这是唯一可信的卡片清单来源。
        WidgetPresence.refresh(appWidgetIds, nowSec())
        appWidgetIds.forEach { id ->
            // 强制渲染（不过推送闸门）：刚添加/刚重启时桌面上一片空白，必须画一次。
            render(app, appWidgetManager, id, snapshotFor(app), resolveSize(appWidgetManager, id))
        }
    }

    /**
     * 尺寸变化（用户拖卡片边框 / 桌面换布局）。
     *
     * 这个回调**不带任何播放数据** —— 用 [snapshotFor] 拿进程内缓存的上一次快照重画，
     * 所以「拖动边框」不会把卡片刷成空态。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        val app = context.applicationContext
        WidgetPresence.invalidate()
        render(app, appWidgetManager, appWidgetId, snapshotFor(app), resolveSize(appWidgetManager, appWidgetId))
    }

    /** 卡片被删除：清掉这个 widgetId 的记账（widgetId 会被系统复用）。 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetStateStore.forget(it) }
        WidgetPresence.invalidate()
    }

    override fun onEnabled(context: Context) {
        WidgetPresence.invalidate()
    }

    /** 最后一张卡片被删除。 */
    override fun onDisabled(context: Context) {
        WidgetPresence.refresh(IntArray(0), nowSec())
        WidgetStateStore.forgetAll()
    }

    companion object {

        const val TAG = "NcrustWidget"

        private fun nowSec(): Long = SystemClock.elapsedRealtime() / 1000L

        // ------------------------------------------------------------------ 推送入口

        /**
         * 由 [com.takahashirinta.ncrust.player.PlaybackService] 调用的**推送入口**。
         *
         * 调用点只有两个（都在服务里），且**调用频率远高于真实推送频率** ——
         * 闸门（[WidgetPushGate]）会在这里把它们压到「播放/暂停切换、切歌、整数秒变化、
         * 尺寸变化、封面有无变化」五种情况。调用方不必自己算时机。
         *
         * 为什么让服务把数据当参数传进来，而不是让 widget 去服务上读：
         * `currentArtworkBitmap` / `currentDominantColor` 是服务的私有状态，
         * 桌面卡片没有、也不该有读它们的通道；而且服务的位图会在暂停 N 秒后被主动释放
         * （`scheduleArtworkIdleRelease`），widget 侧根本无法复现那份生命周期。
         */
        fun push(
            context: Context,
            songId: Long,
            title: String,
            artist: String,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
            artwork: Bitmap?,
            accent: Int,
        ) {
            val app = context.applicationContext
            val now = nowSec()
            val manager = appWidgetManager(app) ?: return
            val ids = widgetIds(app, manager, now)
            // 桌面上没有卡片：一次 Binder 都不发（见 WidgetPresencePolicy）。
            if (ids.isEmpty()) return

            val snapshot = WidgetSnapshot(
                // 「有内容」的判据是歌名或 songId —— 只判 songId 会让某些
                // mediaId 不是数字的来源（qm 走 mediaId）在桌面上永远空态。
                hasContent = songId > 0L || title.isNotBlank(),
                songId = songId,
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                artwork = artwork,
                accent = accent,
            )
            // 记账放在闸门之前：指纹比较的基准必须是「最新数据」，
            // 否则被闸门挡下的那一次会让下一次的比较基准永远落后一拍。
            WidgetStateStore.recordSnapshot(snapshot)

            ids.forEach { id ->
                val size = WidgetStateStore.sizeFor(id) ?: resolveSize(manager, id)
                val key = snapshot.pushKey(size)
                if (!WidgetPushGate.shouldPush(WidgetStateStore.keyFor(id), key)) return@forEach
                render(app, manager, id, snapshot, size)
            }
        }

        /**
         * 按**落盘状态**重画（服务退出时调用）。
         *
         * 为什么单独一个入口而不是复用 [push]：服务 `onDestroy` 之后，播放状态已经不再变化，
         * 但卡片必须立刻反映「没了」——「用户按了停止，桌面上还挂着上一首的进度条」
         * 是最典型的收尾不干净。这条路径**绕过闸门**强制画一次。
         */
        fun pushFromPersistedState(context: Context) {
            val app = context.applicationContext
            val manager = appWidgetManager(app) ?: return
            val ids = widgetIds(app, manager, nowSec())
            if (ids.isEmpty()) return
            val snapshot = fromPersistedState(app)
            WidgetStateStore.recordSnapshot(snapshot)
            ids.forEach { id ->
                render(app, manager, id, snapshot, resolveSize(manager, id))
            }
        }

        // ------------------------------------------------------------------ 内部

        private fun appWidgetManager(context: Context): AppWidgetManager? =
            runCatching { AppWidgetManager.getInstance(context) }
                .onFailure { Log.w(TAG, "AppWidgetManager unavailable", it) }
                .getOrNull()

        private fun widgetIds(
            context: Context,
            manager: AppWidgetManager,
            now: Long,
        ): IntArray {
            if (WidgetPresence.shouldRefresh(now)) {
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(context, NcrustWidgetProvider::class.java))
                }.getOrNull() ?: IntArray(0)
                WidgetPresence.refresh(ids, now)
            }
            return WidgetPresence.cached()
        }

        /** 宿主给的可用尺寸 → 档位。见 [WidgetSizeResolver] 的阈值出处。 */
        private fun resolveSize(manager: AppWidgetManager, widgetId: Int): WidgetSize {
            val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return WidgetSizeResolver.resolve(width, height).also {
                WidgetStateStore.recordSize(widgetId, it)
            }
        }

        /**
         * 当前该画什么：**优先用进程内的新鲜快照，回落落盘状态**。
         *
         * 判据是 `songId` 相同 —— 服务的每一次切歌/播放态变化都会经 `updateNotify` 推一次，
         * 所以「同一首」时内存快照一定不比 prefs 旧，而且它多带封面与主色。
         * prefs 说没有播放内容（用户按过停止）时一律空态，绝不回落到内存快照 ——
         * 那正是「停止之后卡片还挂着上一首」的形状。
         */
        private fun snapshotFor(context: Context): WidgetSnapshot {
            val persisted = PlaybackStateManager.getState(context)
            if (persisted == null) return WidgetSnapshot.empty()
            val cached = WidgetStateStore.last
            if (cached != null && cached.hasContent && cached.songId == persisted.songId) return cached
            return fromPersistedState(context)
        }

        /**
         * 从 `PlaybackStateManager` 造快照。
         *
         * `isPlaying` **一律 false**、进度一律 0：这里是「服务的状态已经不可信」的路径
         * （冷启动后重新添加卡片 / 服务已退出）。prefs 里那个 `is_playing` 是上一次
         * 服务还活着时写下的，拿它当真会出现「卡片显示正在播放的进度条、点了暂停才发现
         * 根本没在播」。宁可显示静止态：用户点一下 ▶ 就真的播起来了。
         */
        private fun fromPersistedState(context: Context): WidgetSnapshot {
            val saved = PlaybackStateManager.getState(context) ?: return WidgetSnapshot.empty()
            if (saved.songName.isBlank() && saved.songId <= 0L) return WidgetSnapshot.empty()
            return WidgetSnapshot(
                hasContent = true,
                songId = saved.songId,
                title = saved.songName,
                artist = saved.songArtist,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                artwork = null,
                accent = WidgetSnapshot.DEFAULT_ACCENT,
            )
        }

        /**
         * 渲染一张卡片。
         *
         * @param snapshot 要画的内容。
         * @param size 已经解析好的档位（避免在 1Hz 的推送路径上重复查 options）。
         */
        private fun render(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            snapshot: WidgetSnapshot,
            size: WidgetSize,
        ) {
            val artwork = artworkToShip(context, widgetId, snapshot, size)
            val views = WidgetRenderer.build(context, size, snapshot, widgetStrings(context), artwork)
            val ok = runCatching { manager.updateAppWidget(widgetId, views) }
                .onFailure { Log.w(TAG, "updateAppWidget($widgetId) failed", it) }
                .isSuccess
            // 只有真的更新成功才记账：失败时留着旧指纹，下一次 tick 会再试一遍，
            // 而不是把这张卡片静默地冻结在旧画面上直到下一次切歌。
            if (ok) WidgetStateStore.recordKey(widgetId, snapshot.pushKey(size))
        }

        /**
         * 这一次要不要把封面位图一起发过去（[WidgetArtworkPolicy]）。
         *
         * @return null = 不带位图（沿用宿主手上那张）。
         */
        private fun artworkToShip(
            context: Context,
            widgetId: Int,
            snapshot: WidgetSnapshot,
            size: WidgetSize,
        ): Bitmap? {
            val artwork = snapshot.artwork
            if (artwork == null) {
                // 没有封面：卡片上画的是主色占位块。把位图记账作废 ——
                // 宿主手上那张图的有效窗口到此为止，封面回来时必须重发一次
                // （「停止 → 立刻重播同一首」「暂停久了封面被释放 → 恢复播放」
                //   这两条路上的布局都换过一次，宿主那边是重新 inflate 的空 ImageView）。
                WidgetStateStore.forgetArtworkStamp(widgetId)
                return null
            }
            val metrics = context.resources.displayMetrics
            val targetPx = WidgetArtworkPolicy.targetPx(
                WidgetLayoutSpec.capabilitiesFor(size).coverDp,
                metrics.density,
            )
            // 布局 id 也要进「目标变了没」的判据：布局 id 一变宿主就重新 inflate，
            // 而 4×1 → 2×2 这类尺寸变化可能落在同一档（同为 40dp 封面）。
            val layoutRes = WidgetRenderer.layoutResFor(size)
            val stamp = WidgetStateStore.artworkStampFor(widgetId)
            val now = nowSec()
            if (stamp != null) {
                val sameSong = stamp.songId == snapshot.songId
                val sameTarget = stamp.targetPx == targetPx && stamp.layoutRes == layoutRes
                val elapsed = (now - stamp.atSec).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                if (!WidgetArtworkPolicy.shouldShip(sameSong, sameTarget, elapsed)) return null
            }
            val scaled = WidgetArtwork.squareScaled(artwork, targetPx, metrics.densityDpi) ?: return null
            WidgetStateStore.recordArtworkStamp(
                widgetId,
                WidgetArtworkStamp(snapshot.songId, targetPx, layoutRes, now),
            )
            return scaled
        }

        /**
         * 卡片文案跟随应用内的语言设置（`ncrust_settings` 的 `language_code`）。
         *
         * 刻意**不用** Android 资源字符串（`res/values-xx/strings.xml`）：本应用是运行时 i18n，
         * 语言切换不重建 Activity；如果卡片走资源字符串，用户把语言切成日语、卡片却还是中文，
         * 而这个 bug 只在「换语言后再看桌面」时出现 —— 用同一份 `Strings` 就没有这个缝。
         */
        private fun widgetStrings(context: Context): WidgetStrings =
            stringsForCode(getSavedLanguageCode(context)).widget
    }
}
