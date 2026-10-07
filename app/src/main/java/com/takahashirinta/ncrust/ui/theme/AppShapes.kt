/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · A：全局圆角规范。本文件是本仓库**唯一**允许出现 `RoundedCornerShape` 的地方。
 */

package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * v2.5.0 · A：全局圆角规范（**唯一落点**）。
 *
 * ## 为什么需要一个对象，而不是各调用点直接写 `RoundedCornerShape(12.dp)`
 *
 * 探针实测（`docs/verification/v2.5.0/probe-theme.md` §3.1）：本仓库在 v2.5.0 之前
 * **一处 `RoundedCornerShape` 都没有** —— 因为设计正典是「直角、无圆角」。
 * 也就是说，「圆角散落不统一」这个问题**在引入圆角的那一刻才会产生**。
 * 所以这条规范的验收不是「把散落的收敛」，而是「**一开始就不散落**」。
 *
 * 落实方式有两条，缺一不可：
 *  1. 本对象是唯一落点；
 *  2. `AppShapesSingleSourceTest` 扫源码树：`ui/theme/AppShapes.kt` 之外出现
 *     `RoundedCornerShape(` / `CircleShape` 即让测试变红。
 *     —— 把「无散落硬编码」变成**可测事实**，而不是一句约定
 *     （同 v2.3.0「落盘 key 名断言」、v2.4.0「NameNormalizer 逐字对应」的做法）。
 *
 * ## 取值来源
 *
 * 前五档与 Material 3 官方 shape scale **逐值一致**（4 / 8 / 12 / 16 / 28 dp），
 * 核实过程见 `probe-splayer-ref.md`。任务书 §3.1 给的就是这组值，本版逐值照收。
 *
 * `pill` 是本版**新增的第六个 token**，不在 M3 的五档里。加它的理由：
 * 任务书要求「按钮：full（药丸形）」「搜索框：药丸形圆角」「音源标签：药丸形圆角」，
 * 而 M3 的五档没有 `full`。若不建 token，调用点就会写
 * `RoundedCornerShape(percent = 50)` —— 那正是上面第 2 条要禁止的散落硬编码。
 *
 * ## 用 `object` 而不是 `data class` + CompositionLocal
 *
 * 本版不提供运行时替换 Shape 规范的能力（没有这个需求），所以不需要
 * `LocalAppShapes`。少一层 CompositionLocal = 少一次 remember 分配，
 * 也少一个「读错了 Local」的出错面。将来真需要（例如自定义主题包）再加，
 * 那时所有调用点写法不变（`AppShapes.medium` 仍然是属性访问）。
 *
 * ## 与 Kanesumi 库的边界（有意为之，不是遗漏）
 *
 * Kanesumi（外部库，组合构建）的 `Metro*` 组件内部画的是直角，且其 `AGENTS.md`
 * 把「无圆角」列为铁律。本版按用户裁定**不改 Kanesumi 仓库** ——
 * 那会让发布产物依赖另一个仓库的未发布提交，破坏「`git clone` 即可复现」这条纪律
 * （v1.5.0 把 `MetroLyricsPanel` 搬进本仓库正是同一条理由）。
 * 因此 app 侧对 Kanesumi 组件只能通过调用点的 `Modifier.clip(AppShapes.x)` 施加圆角；
 * 库内部的弹窗/按钮背板保持直角。这条边界写进了未验证/遗留清单。
 */
object AppShapes {
    /**
     * 4dp。角标、药丸内层、极小控件。
     *
     * 与 M3 `extraSmall` 一致。
     */
    val extraSmall: Shape = RoundedCornerShape(4.dp)

    /**
     * 8dp。**列表项**（任务书 §3.1「列表项：small（8dp）」）。
     *
     * 之所以列表项比卡片小一档：列表项是**行**，圆角过大时相邻行的圆角会互相「咬」，
     * 在密集列表里看起来像一串珠子。8dp 在 1dp 描边下仍然能读出圆角。
     */
    val small: Shape = RoundedCornerShape(8.dp)

    /**
     * 12dp。**卡片**（任务书 §3.1「卡片：medium（12dp）」）。
     *
     * 用于歌单/专辑/艺人卡片、封面缩略图容器。
     */
    val medium: Shape = RoundedCornerShape(12.dp)

    /**
     * 16dp。**封面**与**歌词面板**（任务书 §3.1「封面：large（16dp）+ 边框」）。
     *
     * 封面用比卡片更大的一档，是因为封面是**图片**：图片的圆角在视觉上会比
     * 同数值的纯色容器「小」一档（边缘没有颜色连续性可依），所以图片习惯上取大一档。
     */
    val large: Shape = RoundedCornerShape(16.dp)

    /**
     * 28dp。**弹窗**（任务书 §3.1「弹窗：extraLarge（28dp）」）。
     *
     * 注意：本版 app 侧的弹窗由 Kanesumi `MetroDialog` / `MetroBottomSheet` 绘制，
     * 它们的背板在库内部、保持直角。本 token 供 app 自有的
     * 内容块（如 `BackgroundActivityDialog` 的自绘卡片）使用。
     */
    val extraLarge: Shape = RoundedCornerShape(28.dp)

    /**
     * 药丸形（50%）。**按钮 / 搜索框 / 音源标签**（任务书 §3.1 / §5.1 / §5.4）。
     *
     * 用 `percent = 50` 而不是写死一个大 dp：药丸形要跟着控件高度走，
     * 写死 dp 在高度变化时就不再是药丸（例如小屏上的紧凑按钮）。
     *
     * ## 名字为什么是 `full` 而不是 `pill`
     *
     * 任务书 §3.1 写的就是「按钮：**full**（药丸形）」，而 `full` 也正是
     * Material 3 官方 shape scale 里这一档的名字
     * （`ShapeTokens.kt`：`none = 0dp` 与 `full = 50%`，两者与上面五档并列）。
     * 核实过程与出处见 `probe-splayer-ref.md` §4。
     * 用官方名而不是自造 `pill`，将来若真接入 material3，调用点不需要改名。
     */
    val full: Shape = RoundedCornerShape(percent = 50)
}
