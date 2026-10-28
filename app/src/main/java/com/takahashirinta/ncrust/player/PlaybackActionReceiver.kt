/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.takahashirinta.ncrust.MainActivity

/** Private PendingIntent target: a retained widget can safely recreate a process-local command. */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        if (action !in setOf("resume","pause","previous","next")) return
        val service = PlaybackService.instance
        if (service == null || !service.hasPlayableItem()) {
            if (action != "pause") openPlayer(context, resume = action == "resume")
            return
        }
        runCatching {
            context.startService(PlaybackCommands.intent(context).putExtra("action",action))
        }.onFailure { if (action != "pause") openPlayer(context, resume = action == "resume") }
    }
    private fun openPlayer(context: Context, resume: Boolean) {
        runCatching {
            context.startActivity(Intent(context,MainActivity::class.java).apply {
                action = if (resume) ACTION_RESUME else Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }
    companion object { const val ACTION_RESUME = "com.takahashirinta.ncrust.WIDGET_RESUME" }
}
