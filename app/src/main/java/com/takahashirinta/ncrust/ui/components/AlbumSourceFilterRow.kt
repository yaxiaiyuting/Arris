/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.0：专辑列表的**音源筛选行**（全部 / 只看 ncm / 只看 qm）。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 专辑列表的音源筛选行（v3.4.0）。**与搜索结果页的 `SearchSourceFilterRow` 同一形状**：
 *
 * | | 搜索页 `SearchSourceFilterRow` | 本行 |
 * |---|---|---|
 * | 容器 | `Row` + `fillMaxWidth` | 同 |
 * | 外边距 | `start/end 16dp`、`top 8dp` | 同 |
 * | 档间距 | `Arrangement.spacedBy(8.dp)` | 同 |
 * | 选中色 | `primary`（未选中 `onSurfaceVariant`） | 同 |
 * | 字号 | `TextStyle(fontSize = 12.sp)` | 同 |
 * | 文案出口 | `SourceFilter.label(strings)` | [AlbumSourceTag.filterLabel]（内部就是它，只有 `ALL` 档换了词） |
 *
 * ## 为什么是「同一形状的新组件」而不是直接调用搜索页那个
 *
 * `SearchSourceFilterRow` 是 `SearchScreen.kt` 里的 **private** composable，
 * 而本版的工作边界不允许改 `SearchScreen.kt`（另一个子代理正在改 i18n / 全仓改名）。
 * 复制它的形状、把档位文案与命中区收敛进 [AlbumSourceTag]，是当前边界下
 * **不发明新交互**的做法；等 `SearchScreen.kt` 可改时，把它的调用点换成
 * `AlbumSourceFilterRow(filters = SourceFilter.visible(biliEnabled), …)` 即可让两处合成一处
 * （本组件已经支持 `filters` 参数，搜索页那四档不用改形状）。
 *
 * ## 与搜索页的两处**有意**差异
 *
 * 1. **命中区只增不减**：搜索页那行是裸 `MetroText.clickable`，命中区就等于文字本身
 *    （12sp 的一行 ≈ 17dp 高），低于本仓库「小控件 ≥48×24dp」的触摸契约
 *    （AGENTS.md 触摸陷阱第 7 条）。这里给每个档位套一个
 *    `widthIn(min = 48.dp) + heightIn(min = 24.dp)` 的命中盒，文字仍**左对齐**在同一位置
 *    ⇒ 视觉几乎无变化（短档位后面多出一点留白），但不会再出现「看得到点不着」。
 * 2. **`selectable` 而不是 `clickable`**：这是一组**单选**档位，`selectable(selected = …)`
 *    把「当前选中哪一档」交给无障碍服务（`clickable` 不会说这件事）。
 *    指示（涟漪）与 `clickable` 走同一个 `LocalIndication`，视觉一致。
 *
 * ## 常驻不变量（v3.2.0 · P0-D 的教训）
 *
 * ⚠️ **调用方必须用「未筛选的总数」决定本行挂不挂载**（[AlbumSourceTag.shouldShowFilterRow]），
 * 不能用「筛选后的结果」：否则用户点「只看 qm」得到空列表时，
 * 筛选行会跟着一起消失，**没有任何路径点回「全部」**。
 * 本组件自己不做任何可见性判断 —— 那是调用点的责任，也是一条有单测的纯函数。
 *
 * @param selected 当前档位。
 * @param onSelect 切换档位（**纯本地过滤，不发请求**）。
 * @param filters 要显示的档位，默认 [AlbumSourceTag.albumFilters]（全部 / 只看 ncm / 只看 qm）。
 */
@Composable
fun AlbumSourceFilterRow(
    selected: SourceFilter,
    onSelect: (SourceFilter) -> Unit,
    modifier: Modifier = Modifier,
    filters: List<SourceFilter> = AlbumSourceTag.albumFilters,
) {
    val strings = LocalStrings.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filters.forEach { filter ->
            val isSelected = filter == selected
            Box(
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .heightIn(min = 24.dp)
                    .selectable(selected = isSelected, onClick = { onSelect(filter) }),
                contentAlignment = Alignment.CenterStart,
            ) {
                MetroText(
                    text = AlbumSourceTag.filterLabel(filter, strings),
                    color = if (isSelected) {
                        LocalMetroColors.current.primary
                    } else {
                        LocalMetroColors.current.onSurfaceVariant
                    },
                    style = TextStyle(fontSize = 12.sp),
                )
            }
        }
    }
}
