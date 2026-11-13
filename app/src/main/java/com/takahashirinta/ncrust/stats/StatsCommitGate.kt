/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * J01：播放统计落盘的**提交序**（清空 / 落盘共用一条单调序号）。
 */

package com.takahashirinta.ncrust.stats

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong

/**
 * The store issues tickets while holding its aggregate lock. Taking a snapshot or clearing
 * memory can proceed while another ticket is writing to disk.
 */
internal class StatsCommitGate {
    private val diskLock = Any()
    private val ticketSeq = AtomicLong()
    private val clearedThrough = AtomicLong()
    private var committedTicket = 0L

    fun newTicket(): Long = ticketSeq.incrementAndGet()

    fun beginClear(): Long = newTicket().also { ticket ->
        clearedThrough.accumulateAndGet(ticket, ::maxOf)
    }

    fun commit(prefs: SharedPreferences, ticket: Long, json: String): Boolean = synchronized(diskLock) {
        if (ticket <= clearedThrough.get() || ticket <= committedTicket) return false
        val written = prefs.edit().putString(StatsStore.KEY_SNAPSHOT, json).commit()
        committedTicket = ticket
        written
    }

    fun deleteAfterClear(prefs: SharedPreferences, ticket: Long): Boolean = synchronized(diskLock) {
        if (ticket != clearedThrough.get() || ticket <= committedTicket) return false
        val written = prefs.edit().remove(StatsStore.KEY_SNAPSHOT).commit()
        committedTicket = ticket
        written
    }
}
