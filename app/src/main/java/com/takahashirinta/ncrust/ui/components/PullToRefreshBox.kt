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
import androidx.compose.foundation.layout.size
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
import com.takahashirinta.ncrust.ui.theme.AppShapes
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
    @Suppress("UNUSED_PARAMETER") loading: () -> Boolean = { false },
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
                // 触发后归零：指示器由 `refreshing` 维持挂载（visible 不依赖 dragPx），
                // 归零不会让它闪；留着位移反而会在刷新结束后被下一次滚动事件二次消费。
                state.reset()
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
        // 这里**不动** refreshing：松手撤销会让「刚触发的那次刷新」失去指示器
        // （单测 releaseKeepsTheRefreshingFlag 钉住了这条契约）。清标志由各屏幕在加载
        // 结束时负责（LaunchedEffect(isLoading)），本地歌单那种「没有远端来源」的点则在
        // runSync 里立刻清。
        return Velocity.Zero
    }
}

/**
 * 下拉时浮在内容区顶部的进度指示器（v4.2.0 重写）。
 *
 * ## 为什么从「列表第一项」改成「悬浮层」
 *
 * v2.3.0 起它的用法是 `LazyColumn` 的**第一项**（`item(key = "pull-refresh")`），
 * 指示器是一张 40dp 高的全宽底板，并且用 `translationY = -(1 - reveal) * height`
 * 让它从自己的槽位里**往上滑**。真机（ArtistDetail 页）暴露了两个结构性缺陷：
 *
 * 1. **被上面的 header 切掉一半**：往上滑出槽位后，它画的正是 header（同名 tab 行）
 *    占着的位置，转圈只露出下半圈 —— 用户看到的就是「卡片中间卡着一个半截圆圈」；
 * 2. **一碰就闪**：`reveal` 有 `coerceAtLeast(0.15f)` 下限，于是手指刚动 1px 就把
 *    一项 40dp 的 item 插进列表 ⇒ 内容整体下跳 40dp；松手 `reset()` 又把这项摘掉 ⇒
 *    再跳回去。一次手势里挂载/卸载多次，观感是「闪一下、跳一下、又闪一下」。
 *
 * 现在它是**纯悬浮层**：不占布局、不参与 `LazyColumn` 的 diff，因此
 * **内容一个像素都不会跳**；同时它是 40dp 的小方块（Kanesumi 直角），
 * 上下左右都在自己范围内，**不存在被谁切掉的可能**。
 *
 * ## 挂载条件
 *
 * 只在真的拉动了（`progress > 0`）或正在刷新时挂载 —— 不做「`alpha = 0` 常挂载」
 * （见 AGENTS.md 的 Compose 触摸陷阱第 1 条：`alpha = 0` 不退出命中测试，
 * 一个看不见的指示器会变成一块摸不着的死区）。
 *
 * ## 为什么不用 `animateXxxAsState`
 *
 * 位置完全跟手：位移本身就是连续量，直接映射成 alpha / scale 已经足够顺滑；
 * 任何时间轴动画都会在「挂载的那一帧从头播一次」，而这正是此前
 * 「动画反复触发」的来源。
 *
 * 调用方负责把它放进一个 `Box` 并对齐到 `Alignment.TopCenter`
 * （见 [DetailScaffold] 的 `pullToRefreshState` 参数）。
 */
@Composable
fun PullToRefreshOverlay(
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMetroColors.current
    // 0.06 而不是 0：手指刚碰到屏幕时的 1~2px 抖动不该让指示器闪一下。
    val reveal = if (state.refreshing) 1f else PullToRefresh.progress(state.dragPx)
    if (reveal <= 0.06f) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 状态栏的那段留白**由调用方给**（详情页在根 Box 里画，需要避开状态栏；
            // 搜索页的宿主本来就落在标题栏之下，再补一次会把指示器推到第一行结果上）。
            .height(48.dp)
            .graphicsLayer {
                alpha = reveal.coerceIn(0f, 1f)
                // 微微放大：0.86 → 1，让「拉动进度」除了透明度还有一个可读的量。
                val scale = 0.86f + 0.14f * reveal
                scaleX = scale
                scaleY = scale
                // 只做几 dp 的上浮，目的是「像从上面掉下来」；不再整块滑出可视区。
                translationY = -(1f - reveal) * 10.dp.toPx()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.surface, AppShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            MetroProgressIndicator(
                sizeDp = 22.dp,
                color = colors.primary,
                // indeterminate 的指示器不需要进度参数；下拉过程中它只要「转起来」就够表达状态。
            )
        }
    }
}
