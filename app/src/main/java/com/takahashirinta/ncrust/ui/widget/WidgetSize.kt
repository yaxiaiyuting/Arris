/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**尺寸分桶**。
 *
 * ## 为什么是「单 provider + 运行时分桶」而不是 5 个 provider
 *
 * 系统给每个 `appwidget-provider` 的尺寸是**用户拖出来的格子数**，同一个 provider 可以被
 * 放成 2×1 / 4×1 / 4×2 / 5×2……。如果用 5 个 provider 去覆盖这 5 种摆法，代价是：
 *   · 应用抽屉里出现 5 个同名条目，用户不知道该选哪个；
 *   · 每加一档就要再写一份 `<receiver>` + 一份 provider info + 一份布局；
 *   · 用户在桌面上把 4×1 拖成 2×2 时，系统**不会**换 provider —— 会得到一个被拉伸的 4×1，
 *     也就是说「按 provider 分尺寸」这条路本来就走不通。
 * 所以只声明一个 provider（`targetCellWidth/Height` = 4×2 + `resizeMode=horizontal|vertical`），
 * 布局在**每一次渲染**时按 `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` 现场选。
 *
 * ## 这个文件是纯逻辑（无 android.* 依赖）
 *
 * 分桶规则写在这里、由 JVM 单测钉住边界（`WidgetSizeTest`）。真机上「尺寸算错」的表现是
 * 「桌面卡片排版错乱 / 标题被裁掉」，而它在单测里只是几行断言 —— 便宜的那一层先测。
 */

package com.takahashirinta.ncrust.ui.widget

/**
 * 桌面卡片的三档排版。
 *
 * 档位是**能力档**而不是「格子数」：同一个 `LARGE` 既服务 4×2，也服务 4×3 / 5×2……
 * 格子数只是用户的心智模型，代码只关心「这块地方放得下什么」。
 */
enum class WidgetSize {
    /** 2×1：封面 + 一行标题 + 播放/暂停。 */
    SMALL,

    /** 4×1：封面 + 标题/歌手 + 三键（上一首 / 播放暂停 / 下一首）。 */
    MEDIUM,

    /** 4×2：在 [MEDIUM] 之上再加进度条 + 计时。 */
    LARGE,
}

/**
 * 某一档排版**实际包含**哪些控件。
 *
 * 为什么要有这个结构：`RemoteViews.setTextViewText(id, …)` 对**布局里不存在的 id**
 * 会抛 `ActionException`（"View not found"），而这个异常发生在**桌面进程**里 apply 的时候，
 * 应用侧只看到一条 logcat 警告、卡片直接白掉。所以「这一档有没有歌手行 / 有没有三键 /
 * 有没有进度条」必须是**可判定的数据**，由渲染器照着它决定发哪些 action ——
 * 而不是「布局里我都写上，反正用不到」。
 */
data class WidgetCapabilities(
    val size: WidgetSize,
    /** 第二行（歌手）。 */
    val showArtist: Boolean,
    /** 上一首 / 下一首（小档只有播放暂停）。 */
    val showTransport: Boolean,
    /** 进度条 + 计时行。 */
    val showProgress: Boolean,
    /** 封面边长（dp）。渲染器按它 × 屏幕密度算出位图目标像素。 */
    val coverDp: Int,
)

/**
 * 尺寸 → 档位。
 *
 * ## 阈值出处（不是拍脑袋）
 *
 * `AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH` 报的是**宿主给的可用 dp**，而系统自己的
 * 「n 格 ≈ 多少 dp」换算是 `70 * n - 30`：1 格 = 40dp、2 格 = 110dp、3 格 = 180dp、
 * 4 格 = 250dp（高度同理，1 行 = 40dp、2 行 = 110dp）。所以：
 *
 * | 档位 | 判据 | 典型摆法 |
 * |---|---|---|
 * | `MEDIUM` | 宽 ≥ 250dp 且 高 < 110dp | 4×1 |
 * | `LARGE` | 宽 ≥ 250dp 且 高 ≥ 110dp | 4×2 |
 * | `SMALL` | 其余（含一切未知 / 非法尺寸） | 2×1、2×2 |
 *
 * 这个判据是**单调**的：宽度只会让它升到 MEDIUM，高度只会让它从 MEDIUM 升到 LARGE，
 * 不会出现「拖大一点反而变窄档」的抖变。
 *
 * ## 未知尺寸一律落到 SMALL
 *
 * 宿主可能给 0（`getAppWidgetOptions` 在某些 launcher / 刚添加时返回空 Bundle）、
 * 也可能给负数。**不能**用 `getInt(key, 250)` 那种「缺省当成大卡」的写法：
 * 猜大了 = 布局里 4 个控件挤进 2 格 → 文字被裁、按钮点不到。宁可先给最小档，
 * 下一次 `onAppWidgetOptionsChanged` 到达时自然升级。
 */
object WidgetSizeResolver {

    /** 2 格宽（系统换算 `70 * 2 - 30`）。 */
    const val WIDTH_2_CELLS_DP = 110

    /** 1 行高（系统换算 `70 * 1 - 30`）。 */
    const val HEIGHT_1_CELL_DP = 40

    /** 4 格宽（系统换算 `70 * 4 - 30`）。低于它的宽度放不下「三键 + 封面 + 两行文字」。 */
    const val MEDIUM_MIN_WIDTH_DP = 250

    /** 2 行高（系统换算 `70 * 2 - 30`）。低于它的高度放不下「进度条 + 计时行」。 */
    const val LARGE_MIN_HEIGHT_DP = 110

    fun resolve(minWidthDp: Int, minHeightDp: Int): WidgetSize = when {
        minWidthDp >= MEDIUM_MIN_WIDTH_DP && minHeightDp >= LARGE_MIN_HEIGHT_DP -> WidgetSize.LARGE
        minWidthDp >= MEDIUM_MIN_WIDTH_DP -> WidgetSize.MEDIUM
        else -> WidgetSize.SMALL
    }

    /**
     * `minWidth/minHeight`（dp）→ 宿主口径的**格子数**。
     *
     * 只用于日志与单测：把「2×1 / 4×1 / 4×2 三档」这件用户语言里的事，
     * 在代码里留下一份可验证的换算（`resolve(110, 40) == SMALL` 这一条之所以成立，
     * 依据就是这里）。运行时不参与排版决策。
     */
    fun cellsFor(minWidthDp: Int, minHeightDp: Int): Pair<Int, Int> {
        val columns = ((minWidthDp + 30).coerceAtLeast(0)) / 70
        val rows = ((minHeightDp + 30).coerceAtLeast(0)) / 70
        return columns.coerceAtLeast(1) to rows.coerceAtLeast(1)
    }
}

/** 档位 → 控件能力。纯数据，渲染器照它决定发哪些 RemoteViews action。 */
object WidgetLayoutSpec {

    fun capabilitiesFor(size: WidgetSize): WidgetCapabilities = when (size) {
        WidgetSize.SMALL -> WidgetCapabilities(
            size = size,
            showArtist = false,
            showTransport = false,
            showProgress = false,
            coverDp = 40,
        )

        WidgetSize.MEDIUM -> WidgetCapabilities(
            size = size,
            showArtist = true,
            showTransport = true,
            showProgress = false,
            coverDp = 40,
        )

        WidgetSize.LARGE -> WidgetCapabilities(
            size = size,
            showArtist = true,
            showTransport = true,
            showProgress = true,
            coverDp = 56,
        )
    }
}
