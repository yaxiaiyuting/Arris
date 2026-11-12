/*
 * Copyright (c) 2026 yaxiaiyuting.
 * Arris fork contribution, licensed under GPLv3; see LICENSE.
 */

package com.takahashirinta.ncrust.player

/** Media3 的页码从零开始。旧浏览器的整表请求也走此处。 */
internal fun <T> List<T>.mediaBrowsePage(page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val first = page.toLong() * pageSize
    if (first >= size) return emptyList()
    val end = minOf(first + pageSize, size.toLong()).toInt()
    if (first == 0L && end == size) return this
    return subList(first.toInt(), end)
}
