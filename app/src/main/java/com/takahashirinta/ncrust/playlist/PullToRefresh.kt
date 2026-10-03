/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：手动下拉刷新的**纯逻辑**（阈值与进度）。无 Android 依赖，JVM 可单测。
 */

package com.takahashirinta.ncrust.playlist

/**
 * 下拉刷新的判定（v2.2.0）。**纯函数**，与手势/Compose 解耦，便于单测。
 *
 * ## 为什么把这一点点算术单独抽出来
 *
 * 本仓库在 v1.7.0 踩过一次同类坑：**手势吸附阈值按「可拖动行程」核对**——
 * 当时判据用的是「progress >= 0.5」，而 progress 要走完 0.85 × 屏高，于是手指得跨
 * 340dp 才算数，用户的感觉是「拖不动」。教训是：**阈值要以像素/位移表达，
 * 并且必须能被单测钉住**，不能藏在 `pointerInput` 的闭包里靠手感调。
 *
 * 这里同理：阈值用**像素位移**表达，且**只在列表已经到顶时**才累计
 * （`atTop` 为 false 时一律不触发、进度归零），否则「往下滚列表」会被误判成「下拉刷新」——
 * 那是这类控件最常见的 bug。
 */
object PullToRefresh {

    /**
     * 触发刷新所需的位移（像素）。
     *
     * 160px 的来历：S6（1440×2560，density 3.5）上约 46dp；PCL110 上约 53dp。
     * 参照本仓库既有的触摸契约（小控件命中区 ≥48×24dp），取略大于 48dp 的值，
     * 既不会被误触，也不至于像 v1.7.0 那样要拖半个屏幕。
     */
    const val THRESHOLD_PX = 160f

    /** 指示器的最大位移（超过就不再增长，避免视觉上「拉出一大截」）。 */
    const val MAX_DRAG_PX = 320f

    /**
     * 是否应当触发刷新。
     *
     * @param dragPx 当前下拉位移（正数 = 向下拉）。
     * @param atTop 列表是否在顶部。**false 时恒不触发** —— 见类文档。
     * @param alreadyLoading 已经在加载中时不重复触发（防止连续下拉打出一串请求）。
     */
    fun shouldTrigger(dragPx: Float, atTop: Boolean, alreadyLoading: Boolean): Boolean =
        atTop && !alreadyLoading && dragPx >= THRESHOLD_PX

    /** 指示器进度 0..1，供绘制用。 */
    fun progress(dragPx: Float): Float = when {
        dragPx <= 0f -> 0f
        dragPx >= MAX_DRAG_PX -> 1f
        else -> dragPx / MAX_DRAG_PX
    }

    /** 是否已经越过阈值（用于把指示器切成「松手即刷新」的状态）。 */
    fun armed(dragPx: Float): Boolean = dragPx >= THRESHOLD_PX
}
