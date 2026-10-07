/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B：把 v2.2.0 已有的 `PullToRefresh` **纯阈值逻辑**接到真实手势上。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.playlist.PullToRefresh
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors

/**
 * 下拉刷新的状态。持有「当前下拉了多少像素」与「是否正在刷新」。
 *
 * 抽成一个持有类而不是两个散落的 `remember { mutableStateOf }`：手势与指示器**必须**
 * 读同一份位移，否则指示器会与实际阈值不同步（这类控件最常见的观感 bug）。
 */
class PullToRefreshState {
    internal var dragPx by mutableFloatStateOf(0f)
    var refreshing by mutableStateOf(false)
        internal set

    /** 松手 / 刷新结束后归零。 */
    internal fun reset() {
        dragPx = 0f
    }
}

@Composable
fun rememberPullToRefreshState(): PullToRefreshState = remember { PullToRefreshState() }

/**
 * 把下拉刷新挂到一个**可滚动内容**上（v2.3.0 · B）。
 *
 * ## 为什么用 `nestedScroll` 而不是 `pointerInput`
 *
 * 内容是 `LazyColumn`（或别的可滚动容器），它自己就要吃掉竖直手势。
 * 用 `pointerInput { detectVerticalDragGestures }` 挂在父节点上会**抢走滚动**
 * （检测器会消费事件），或者反过来被列表吃掉而永远不触发 —— 两条路都踩过。
 * `nestedScroll` 是平台为这件事提供的正确接口：它在**列表消费之后**才收到剩余位移，
 * 而且本实现**一个像素都不消费**（`onPostScroll` 恒返回 `Offset.Zero`），
 * 所以滚动、fling、点击全部行为不变。
 *
 * ## 触发条件（全部来自 v2.2.0 已单测的纯逻辑）
 *
 * - 只在**列表已经在顶部**时累计位移（`atTop`）—— 否则「往下滚列表」会被误判成下拉刷新；
 * - 只有位移 ≥ [PullToRefresh.THRESHOLD_PX]（160px）才触发；
 * - 已经在刷新时不重复触发（连续下拉不会打出一串请求）。
 */
fun Modifier.pullToRefresh(
    state: PullToRefreshState,
    atTop: () -> Boolean,
    onRefresh: () -> Unit,
): Modifier = this.nestedScroll(
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 手指往上滑（available.y < 0）⇒ 立刻放弃这次下拉。
            if (available.y < 0f) state.reset()
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.Drag) return Offset.Zero
            if (available.y > 0f && atTop()) {
                state.dragPx += available.y
                if (PullToRefresh.shouldTrigger(state.dragPx, true, state.refreshing)) {
                    state.reset()
                    state.refreshing = true
                    onRefresh()
                }
            } else {
                state.reset()
            }
            return Offset.Zero
        }
    },
)

/**
 * 下拉时出现在列表顶部的进度指示器。
 *
 * 只在真的拉动了（`progress > 0`）或正在刷新时**挂载** —— 不做
 * 「`alpha = 0` 常挂载」（见 AGENTS.md 的 Compose 触摸陷阱第 1 条：
 * `alpha = 0` 不退出命中测试，一个看不见的指示器会变成一块摸不着的死区）。
 */
@Composable
fun PullToRefreshIndicator(
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
) {
    val visible = state.refreshing || state.dragPx > 0f
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        MetroProgressIndicator(
            color = LocalMetroColors.current.primary,
            // indeterminate 的指示器不需要进度参数；下拉过程中它只要「转起来」就够表达状态。
        )
    }
}
