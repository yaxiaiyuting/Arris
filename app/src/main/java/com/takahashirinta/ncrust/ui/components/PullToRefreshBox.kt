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

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
): Modifier = this.nestedScroll(PullToRefreshConnection(state, atTop, onRefresh))

/**
 * 下拉刷新的手势接线（J16 抽出来是为了能被单测直接驱动）。
 *
 * ## 为什么必须处理 `onPreFling`（松手/取消）
 *
 * 旧实现只在两条路上把 [PullToRefreshState.dragPx] 归零：上滑（`onPreScroll` 里
 * `available.y < 0`）和「不在顶部 / 位移非正」（`onPostScroll` 的 else 分支）。
 * **松手不在这两条路上** —— 手指抬起后没有任何滚动事件，`dragPx` 就停在原值，
 * 于是：
 *
 * - 指示器继续挂着（`dragPx > 0` 是它的挂载条件，看着像"卡住的下拉"）；
 * - 更糟的是**两次独立的下拉会互相借用距离**：第一次 80px 没到 160px 阈值，
 *   松手不清零，第二次再拉 80px 就凑够 160px 触发刷新 —— 用户只拉了两次"半个动作"。
 *
 * `onPreFling` 是平台在**每一次拖动结束时**（含零速度松手）都会派发的一站，
 * 放在这里归零即"一次手势一次累计"。不消费任何速度，滚动/惯性行为不变。
 */
internal class PullToRefreshConnection(
    private val state: PullToRefreshState,
    private val atTop: () -> Boolean,
    private val onRefresh: () -> Unit,
) : NestedScrollConnection {

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
        // 刷新进行中：把本次手势剩下的位移**全部忽略**。
        // 不加这条，用户一次拉得很长会持续累加 dragPx —— 越过阈值先触发一次刷新、随后
        // 又被 reset 归零、再被后续位移重新累加，于是指示器淡出又淡入、刷新也可能重复触发
        // （用户报的「拉很长导致动画多次触发」）。
        if (state.refreshing) return Offset.Zero
        if (available.y > 0f && atTop()) {
            // 封顶到 MAX_DRAG_PX：进度是归一化的，再多的位移没有视觉收益，
            // 留着只会让"回弹距离"和真实位移脱节。
            state.dragPx = (state.dragPx + available.y).coerceAtMost(PullToRefresh.MAX_DRAG_PX)
            if (PullToRefresh.shouldTrigger(state.dragPx, true, state.refreshing)) {
                // 触发后**不清零**：清零会让指示器在刷新期间闪一下（presence 1→0→1）。
                // 位移留到 onPreFling 或刷新结束时再归零。
                state.refreshing = true
                onRefresh()
            }
        } else {
            state.reset()
        }
        return Offset.Zero
    }

    /**
     * 松手 / 手势被取消 ⇒ 本次下拉结束，累计位移归零。
     *
     * 只归零位移，不动 [PullToRefreshState.refreshing]：刚触发的那次刷新要照常继续。
     */
    override suspend fun onPreFling(available: Velocity): Velocity {
        state.reset()
        return Velocity.Zero
    }
}

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
    val colors = LocalMetroColors.current
    val visible = state.refreshing || state.dragPx > 0f
    // 收起时的淡出：没有它，指示器是"啪"地消失，看起来像布局跳动。
    val presence by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "pullToRefreshPresence",
    )
    // 展开时跟手：下拉多少就露多少（`progress` 已把 maxDragPx 归一化到 0..1），
    // 只在下拉刚开始时给一个下限，避免出现"一条几乎看不见的缝"。
    val dragReveal = if (state.refreshing) 1f else PullToRefresh.progress(state.dragPx).coerceAtLeast(0.15f)
    val reveal = dragReveal * presence
    if (reveal <= 0.01f) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            // 一层半透明底板 + 底边细线：指示器是"盖在内容上"的一层，而不是内容自己长出来的。
            // 之前没有这层，转圈直接叠在列表文字上，观感很怪（用户反馈「没有遮罩过渡」）。
            .graphicsLayer {
                alpha = reveal
                translationY = -(1f - reveal) * size.height
            }
            .background(colors.surface.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        MetroProgressIndicator(
            color = colors.primary,
            // indeterminate 的指示器不需要进度参数；下拉过程中它只要「转起来」就够表达状态。
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.surfaceVariant),
        )
    }
}
