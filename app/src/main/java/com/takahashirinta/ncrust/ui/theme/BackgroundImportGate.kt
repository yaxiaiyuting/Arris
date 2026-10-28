/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.theme

/** Only file publication is locked; decoding/encoding stays outside this gate. */
internal class BackgroundImportGate {
    private var generation = 0L
    @Synchronized fun begin(): Long = ++generation
    @Synchronized fun commit(ticket: Long, publish: () -> Unit): Boolean {
        if (ticket != generation) return false
        publish()
        return true
    }
    @Synchronized fun invalidate(clear: () -> Unit) { generation++; clear() }
}
