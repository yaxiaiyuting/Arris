package com.takahashirinta.ncrust.cache

/** 按时间淘汰最旧的条目，同一时间沿用 map 的迭代顺序。 */
internal inline fun <K, V> MutableMap<K, V>.trimOldest(
    maxEntries: Int,
    crossinline timestampOf: (V) -> Long,
) {
    val excess = size - maxEntries
    if (excess <= 0) return
    if (excess == 1) {
        val oldest = entries.minByOrNull { timestampOf(it.value) } ?: return
        remove(oldest.key)
    } else {
        entries.sortedBy { timestampOf(it.value) }.take(excess).forEach { remove(it.key) }
    }
}
