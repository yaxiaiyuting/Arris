/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.takahashirinta.ncrust.MainActivity
import com.takahashirinta.ncrust.R

/**
 * Android 16（API 36）Live Updates：用原生 [Notification.ProgressStyle] 把「正在播什么 + 播到哪」
 * 送到状态栏芯片 / 锁屏的实时更新区（v1.6.0 · D3）。
 *
 * ## 调研结论（2026-09，API 36 平台 jar + PCL110 真机核对，不是文档推断）
 *
 * - 平台侧确实存在 `android.app.Notification.ProgressStyle`（`setProgress` /
 *   `setProgressSegments` / `setProgressTrackerIcon` / `setProgressIndeterminate`）。
 *   注意它**没有** `setProgressMax`，进度是 0–100 的百分比语义；
 * - 运行时闸门是 [NotificationManager.canPostPromotedNotifications]（API 36 新增）+
 *   权限 `android.permission.POST_PROMOTED_NOTIFICATIONS`（真机 `pm list permissions` 实测
 *   的名字是 POST_PROMOTED_NOTIFICATIONS，**不是**文档里常见的 ..._ONGOING_NOTIFICATIONS）；
 * - API 36 的 `Notification.Builder` **没有** `setRequestPromotedOngoing` —— 提升由系统按
 *   「ongoing + 有进度 + 用户允许」判定，应用只能提供具备提升条件的通知。
 *
 * ## 为什么是「另外一条通知」而不是改媒体通知
 *
 * 媒体通知必须保持 `MediaStyle`（媒体卡片 / 车机 / 通知栏按钮全靠它），而一条通知只能有一个
 * Style —— ProgressStyle 与 MediaStyle 无法并存。所以这里另发一条 **id 固定、ongoing、无声音无
 * 震动** 的进度通知：它在系统侧进入实时更新区，不占据通知栏的媒体卡片位置，也不会响铃。
 *
 * ## 兼容与降级
 *
 * - API < 36 或系统不允许提升（[canPostPromotedNotifications] 为 false）→ 整个类不产生任何通知；
 * - 用户可在 prefs 里关掉（`ncrust_settings` 的 `live_update_enabled`，默认开）；
 * - 任何异常都吞掉并降级 —— 实时更新是锦上添花，绝不能影响播放或媒体通知。
 */
object LiveUpdateNotifier {

    private const val TAG = "LiveUpdate"
    private const val CHANNEL_ID = "ncrust_live_update"
    private const val NOTIFICATION_ID = 0x4E01
    private const val PREFS = "ncrust_settings"
    private const val KEY_ENABLED = "live_update_enabled"

    /** 平台能力 + 系统授权 + 用户开关，三者同时满足才发。 */
    fun isActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val prefs = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return false
        if (!prefs.getBoolean(KEY_ENABLED, true)) return false
        if (!prefs.getBoolean("live_update_probed", false)) {
            prefs.edit().putBoolean("live_update_probed", true).apply()
            Log.i(TAG, "live update supported=" + canPost(context))
        }
        return canPost(context)
    }

    private fun canPost(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.canPostPromotedNotifications() == true
    }.getOrDefault(false)

    /**
     * 更新实时更新通知。[durationMs] <= 0 时用不确定进度（直播 / 还没拿到时长）。
     */
    fun update(
        context: Context,
        title: String,
        artist: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < 36) return
        if (!isActive(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            ensureChannel(nm)
            val percent = if (durationMs > 0L) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
            val style = Notification.ProgressStyle()
                .setProgress(percent)
                .setProgressIndeterminate(durationMs <= 0L)
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentIntent)
                .setStyle(style)
                .setProgress(100, percent, durationMs <= 0L)
                // 实时更新只呈现「正在进行」的事；暂停时仍然保持 ongoing（音乐还能继续播），
                // 但用 setSilent + 只更新一次，绝不再响铃/震动。
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // 静音靠 channel 的 IMPORTANCE_MIN（API 26+ 后 Builder 的 setSound/Vibrate 被 channel 覆盖，
                // 不设 setSilent —— API 36 的 Notification.Builder 没有这个方法）。
                .setShowWhen(false)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }.onFailure { Log.w(TAG, "post live update failed", it) }
    }

    /** 停止播放 / 服务销毁时撤掉，别在状态栏留一条不动的进度条。 */
    fun cancel(context: Context) {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "播放实时进度", NotificationManager.IMPORTANCE_MIN).apply {
                description = "状态栏实时更新区显示的播放进度"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}
