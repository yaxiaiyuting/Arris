/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

/** px，卡片根坐标系；动画只消费尺寸与锚点，不改变插值公式。 */
data class CoverGeometry(val sizePx: Float, val centerXPx: Float, val centerYPx: Float)

/**
 * 窄屏大封面的可用带：内容底边扣除控件（含上方 Spacer / 把手）、完整标题块、最小留白。
 *
 * 每档都预留标题，不能为保留满宽而把 Regular 的标题高度当成 0。
 * 优先保留原锚点（整屏高 * 0.3 + 24dp），只有越出可用带才夹回带内。
 * 调用方传入布局测量值；单测中的 dp 数字仅为估算输入。
 *
 * @param availableWidthPx 内容区宽（px）。封面水平居中、边长不超过它。
 * @param contentHeightPx 内容区高（px，含状态栏 —— 与卡片根坐标系一致）。
 * @param topBarBottomPx 顶栏下沿（px）。封面不得钻到顶栏下面（矮屏时尤其明显）。
 * @param controlsHeightPx 底部控件 + Spacer + 把手的总高（px）。
 * @param titleReservePx 大封面模式歌名/歌手块实测高（px）。
 * @param minGapPx 封面与控件/标题之间的最小留白（px）。
 * @param preferredCenterYPx 原锚点（整屏高 * 0.3 + 24dp）。
 */
fun narrowCoverGeometry(
    availableWidthPx: Float,
    contentHeightPx: Float,
    topBarBottomPx: Float,
    controlsHeightPx: Float,
    titleReservePx: Float,
    minGapPx: Float,
    preferredCenterYPx: Float,
): CoverGeometry {
    val top = topBarBottomPx.coerceAtLeast(0f)
    val bottom = (contentHeightPx - controlsHeightPx.coerceAtLeast(0f) -
        titleReservePx.coerceAtLeast(0f) - minGapPx.coerceAtLeast(0f)).coerceAtLeast(top)
    val width = availableWidthPx.coerceAtLeast(0f)
    val size = minOf(width, bottom - top).coerceAtLeast(1f)
    val minCenter = top + size / 2f
    // 封面恰好占满高度带时，小数像素的加减舍入可能让上界比下界小一个 ULP。
    // coerceIn 不接受倒置区间；保持同一锚点下界，避免非整数 density 下布局崩溃。
    val maxCenter = (bottom - size / 2f).coerceAtLeast(minCenter)
    return CoverGeometry(
        sizePx = size,
        centerXPx = width / 2f,
        centerYPx = preferredCenterYPx.coerceIn(minCenter, maxCenter),
    )
}
