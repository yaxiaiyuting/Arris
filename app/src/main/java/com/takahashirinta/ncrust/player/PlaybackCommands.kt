/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.Intent
import java.util.UUID

/** Process-local, unguessable capability. It is never persisted or logged. */
internal class PlaybackCommandCapability {
    val value: String = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    fun accepts(candidate: String?): Boolean = candidate != null && candidate == value
}

internal object PlaybackCommands {
    private const val KEY = "com.takahashirinta.ncrust.INTERNAL_PLAYBACK_CAPABILITY"
    private val capability = PlaybackCommandCapability()
    fun intent(context: Context): Intent = Intent(context, PlaybackService::class.java).apply { putExtra(KEY,capability.value) }
    fun authorized(intent: Intent?): Boolean = capability.accepts(intent?.getStringExtra(KEY))
}
