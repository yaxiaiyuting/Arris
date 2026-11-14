/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.takahashirinta.ncrust.MainActivity
import com.takahashirinta.ncrust.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class LiveUpdateKind(val id: Int) {
    PLAYBACK(0x4E01),
    DOWNLOAD(0x4E02),
    RECOGNITION(0x4E03),
    ROOM(0x4E04),
    AUDIO_CACHE(0x4E05),
}

internal enum class LiveUpdateStatus {
    UNSUPPORTED,
    APP_DISABLED,
    NOTIFICATIONS_DISABLED,
    PROMOTION_DISABLED,
    CHANNEL_DISABLED,
    IDLE,
    ACTIVE_NOT_PROMOTED,
    PROMOTED,
    UNKNOWN,
}

internal fun progressPercent(position: Long, total: Long): Int? =
    if (total > 0L) (position.coerceIn(0L, total).toDouble() / total * 100).toInt() else null

/** Promoted ProgressStyle cards use their own channel and never replace the media service notification. */
internal object LiveUpdateNotifier {
    internal const val CHANNEL_ID = "arris_live_updates"
    private const val OLD_CHANNEL_ID = "ncrust_live_update"
    // Use the extra directly with compileSdk 36. The constant and Builder API are public in 36.1.
    internal const val REQUEST_PROMOTION = "android.requestPromotedOngoing"
    private const val VENDOR_PROMOTION_SETTINGS = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
    private const val PREFS = "ncrust_settings"
    private const val KEY_ENABLED = "live_update_enabled"
    private const val DELETE_TOKEN = "live_update_token"
    private const val DELETE_ID = "live_update_id"
    internal const val EXTRA_OPEN = "com.takahashirinta.ncrust.LIVE_UPDATE_OPEN"
    private const val PROMOTION_READBACK_MS = 600L

    private data class Content(
        val title: String,
        val text: String,
        val subText: String?,
        val percent: Int?,
        val clockBase: Long?,
    )
    private class Entry(val owner: Any, val token: Int, var content: Content) {
        var posted: Content? = null
        var dismissed = false
    }

    private val entries = mutableMapOf<LiveUpdateKind, Entry>()
    private var nextToken = android.os.SystemClock.elapsedRealtime().toInt()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val _status = MutableStateFlow(
        if (Build.VERSION.SDK_INT >= 36) LiveUpdateStatus.UNKNOWN else LiveUpdateStatus.UNSUPPORTED,
    )
    val status = _status.asStateFlow()
    private var readback: Runnable? = null

    fun launchSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        fun launch(action: String) = runCatching {
            context.startActivity(Intent(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }.isSuccess
        if (status.value == LiveUpdateStatus.NOTIFICATIONS_DISABLED ||
            status.value == LiveUpdateStatus.CHANNEL_DISABLED
        ) return launch(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        return launch(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS) ||
            launch(VENDOR_PROMOTION_SETTINGS) ||
            launch(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

    @Synchronized
    fun refreshPromotion(context: Context) {
        if (Build.VERSION.SDK_INT < 36) return
        _status.value = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return@runCatching LiveUpdateStatus.UNKNOWN
            var hasActive = false
            for (posted in manager.activeNotifications) {
                if (posted.notification.channelId != CHANNEL_ID ||
                    LiveUpdateKind.entries.none { it.id == posted.id }
                ) continue
                if (posted.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0) {
                    return@runCatching LiveUpdateStatus.PROMOTED
                }
                hasActive = true
            }
            blockedStatus(context, manager)
                ?: if (hasActive) LiveUpdateStatus.ACTIVE_NOT_PROMOTED else LiveUpdateStatus.IDLE
        }.getOrDefault(LiveUpdateStatus.UNKNOWN)
    }

    @Synchronized
    fun onForeground(context: Context) {
        if (Build.VERSION.SDK_INT < 36) return
        if (entries.isNotEmpty()) {
            val app = context.applicationContext
            runCatching {
                val manager = app.getSystemService(NotificationManager::class.java) ?: return@runCatching
                val blocked = blockedStatus(app, manager)
                entries.forEach { (kind, entry) ->
                    if (blocked != null) {
                        if (entry.posted != null) {
                            manager.cancel(kind.id)
                            entry.posted = null
                            scheduleReadback(app)
                        }
                    } else if (!entry.dismissed && entry.posted == null) {
                        publish(app, manager, kind, entry)
                    }
                }
            }.onFailure { Log.w("LiveUpdate", "Could not refresh active updates", it) }
        }
        refreshPromotion(context)
    }

    @Synchronized
    fun post(
        context: Context,
        kind: LiveUpdateKind,
        owner: Any,
        title: String,
        text: String,
        percent: Int? = null,
        subText: String? = null,
        elapsedMs: Long? = null,
    ) {
        if (Build.VERSION.SDK_INT < 36 || title.isBlank()) return
        val app = context.applicationContext
        val current = entries[kind]
        val sameOwner = current?.takeIf { it.owner == owner }
        if (sameOwner?.dismissed == true) return
        val candidateBase = elapsedMs?.let { System.currentTimeMillis() - it }
        val base = candidateBase?.let { value ->
            sameOwner?.content?.clockBase?.takeIf { kotlin.math.abs(it - value) < 2_000L } ?: value
        }
        val content = Content(title, text, subText, percent?.coerceIn(0, 100), base)
        val entry = sameOwner ?: Entry(owner, ++nextToken, content).also { entries[kind] = it }
        entry.content = content
        if (entry.posted == content) return

        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            if (blockedStatus(app, manager) != null) {
                if (current?.posted != null) {
                    manager.cancel(kind.id)
                    entry.posted = null
                    scheduleReadback(app)
                }
                return
            }
            publish(app, manager, kind, entry)
        }.onFailure { Log.w("LiveUpdate", "Could not post ${kind.name}", it) }
    }

    @RequiresApi(36)
    private fun blockedStatus(app: Context, manager: NotificationManager): LiveUpdateStatus? = when {
        !app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true) ->
            LiveUpdateStatus.APP_DISABLED
        !manager.areNotificationsEnabled() -> LiveUpdateStatus.NOTIFICATIONS_DISABLED
        !manager.canPostPromotedNotifications() -> LiveUpdateStatus.PROMOTION_DISABLED
        (manager.getNotificationChannel(CHANNEL_ID) ?: manager.getNotificationChannel(OLD_CHANNEL_ID))
            ?.importance == NotificationManager.IMPORTANCE_NONE -> LiveUpdateStatus.CHANNEL_DISABLED
        else -> null
    }

    @RequiresApi(36)
    private fun publish(app: Context, manager: NotificationManager, kind: LiveUpdateKind, entry: Entry) {
        val content = entry.content
        ensureChannel(manager)
        val firstPost = entry.posted == null
        manager.notify(kind.id, build(app, kind, entry.token, content))
        entry.posted = content
        if (firstPost) scheduleReadback(app)
    }

    @RequiresApi(36)
    private fun build(
        app: Context,
        kind: LiveUpdateKind,
        token: Int,
        content: Content,
    ): Notification {
        val style = Notification.ProgressStyle()
            .setProgress(content.percent ?: 0)
            .setProgressIndeterminate(content.percent == null)
        val delete = PendingIntent.getBroadcast(
            app, token,
            Intent(app, LiveUpdateDismissReceiver::class.java)
                .putExtra(DELETE_ID, kind.id).putExtra(DELETE_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            app, kind.id,
            Intent(app, MainActivity::class.java).putExtra(EXTRA_OPEN, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tux_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setContentIntent(open)
            .setDeleteIntent(delete)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTION, true) })
            .setShowWhen(content.clockBase != null)
            .apply {
                content.clockBase?.let { setWhen(it).setUsesChronometer(true) }
                if (kind != LiveUpdateKind.PLAYBACK) content.percent?.let { setShortCriticalText("$it%") }
            }
            .build()
    }

    @Synchronized
    fun cancel(context: Context, kind: LiveUpdateKind, owner: Any? = null) {
        if (Build.VERSION.SDK_INT < 36) return
        val entry = entries[kind] ?: return
        if (owner != null && owner != entry.owner) return
        entries.remove(kind)
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(kind.id) }
        scheduleReadback(context.applicationContext)
    }

    @Synchronized
    private fun dismissed(context: Context, id: Int, token: Int) {
        val kind = LiveUpdateKind.entries.firstOrNull { it.id == id } ?: return
        val entry = entries[kind] ?: return
        if (entry.token != token) return
        entry.dismissed = true
        entry.posted = null
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(kind.id) }
        scheduleReadback(context.applicationContext)
    }

    /** A killed process can leave ordinary notifications behind, but its tasks have ended. */
    fun clearAbandoned(context: Context) {
        if (Build.VERSION.SDK_INT < 36) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { LiveUpdateKind.entries.forEach { manager.cancel(it.id) } }
    }

    private fun scheduleReadback(app: Context) {
        readback?.let(main::removeCallbacks)
        val task = Runnable { refreshPromotion(app) }
        readback = task
        main.postDelayed(task, PROMOTION_READBACK_MS)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // The legacy MIN channel cannot be upgraded. Respect a user's explicit block when migrating.
        val importance = if (manager.getNotificationChannel(OLD_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            NotificationManager.IMPORTANCE_NONE
        } else NotificationManager.IMPORTANCE_LOW
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Arris 实时通知", importance).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun onDismissal(context: Context, intent: Intent) {
        dismissed(context, intent.getIntExtra(DELETE_ID, -1), intent.getIntExtra(DELETE_TOKEN, -1))
    }
}

class LiveUpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveUpdateNotifier.onDismissal(context, intent)
    }
}
