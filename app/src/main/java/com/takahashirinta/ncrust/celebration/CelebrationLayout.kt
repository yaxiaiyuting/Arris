package com.takahashirinta.ncrust.celebration

import android.graphics.RectF
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Both native art and Compose copy use the measured stage, including system insets. */
internal class CelebrationPlacement {
    private val stage = RectF()
    private var rootX = 0f
    private var rootY = 0f
    var renderer: CelebrationArtView? = null
    fun root(x: Float, y: Float) { rootX = x; rootY = y; update() }
    fun stage(x: Float, y: Float, width: Float, height: Float) {
        stage.set(x, y, x + width, y + height)
        update()
    }
    private fun update() {
        if (!stage.isEmpty) renderer?.placeScene(stage.left-rootX, stage.top-rootY, stage.width(), stage.height())
    }
}

/**
 * Measure copy/actions first. The stage gets the remaining space; small screens can scroll.
 *
 * ## v3.6.3：footer 预留高度**跨阶段动画**，不再让插画在按钮出现时瞬移
 *
 * 用户反馈「Tux 彩蛋错位」「按钮超出屏幕」。实测（320×568dp、系统栏占 92dp）：
 *
 *  - footer 从 ARRIVING 的 12dp 长到 WAITING 的 152~176dp，而 artHeight = 剩余空间 ⇒
 *    footer 顶边从 536 跳到 372（−164dp），舞台高度 383 → 219（−43%），标题不动。
 *    这就是「错位」：不是某个元素放错，而是**插画被下方内容挤小、整体向上跳**。
 *  - 旧代码给插画留了 **200dp 硬地板**：153（标题）+ 176（按钮）+ 200 = 529 > 476（视口），
 *    于是「手动吹灭」掉到折线以下，页面只能滚。
 *
 * 修法两条：
 *  1. [footerReserve] 由调用方按阶段给出，这里用 320ms 动画过渡；舞台高度跟着连续变化，
 *     Tux 是「慢慢让出空间」而不是一帧跳上去；
 *  2. 插画地板降到 [ART_MIN_HEIGHT]（72dp）。小屏上「标题 + 按钮 + 一点插画」必须同时可见，
 *     插画可以小，按钮不能出屏。真的还放不下时才允许滚动（大字体 + 小屏的兜底）。
 */
@Composable
internal fun CelebrationFrame(
    placement: CelebrationPlacement,
    heading: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    footerReserve: Dp = 12.dp,
) {
    val reserve by animateDpAsState(
        targetValue = footerReserve,
        animationSpec = tween(durationMillis = 320),
        label = "celebrationFooterReserve",
    )
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal=24.dp, vertical=10.dp)) {
        Layout(
            modifier=Modifier.widthIn(max=520.dp).fillMaxWidth().align(Alignment.Center)
                .verticalScroll(rememberScrollState()).heightIn(min=maxHeight),
            content={
                Box { heading() }
                Box(Modifier.onGloballyPositioned {
                    val p=it.positionInRoot()
                    placement.stage(p.x,p.y,it.size.width.toFloat(),it.size.height.toFloat())
                })
                // 预留高度是**测量约束**的一部分：footer 内容比它矮时，舞台拿到的是
                // 「视口 − 标题 − 预留」，所以 footer 长高不会把插画挤得跳一下。
                // 内容底对齐：预留高度从 12dp 长到 200dp 的过程中，按钮**留在原地**，
                // 让插画去让空间；顶对齐会让按钮跟着预留区一起往上滑（另一种「错位」）。
                Box(Modifier.fillMaxWidth().heightIn(min=reserve), contentAlignment=Alignment.BottomCenter) { footer() }
            },
        ) { children, constraints ->
            val loose=Constraints(minWidth=constraints.maxWidth, maxWidth=constraints.maxWidth)
            val top=children[0].measure(loose)
            val bottom=children[2].measure(loose)
            val viewport = if (constraints.minHeight == Constraints.Infinity) 0 else constraints.minHeight
            val artHeight=celebrationArtHeightPx(viewport, top.height, bottom.height, ART_MIN_HEIGHT.roundToPx())
            val art=children[1].measure(Constraints.fixed(constraints.maxWidth,artHeight))
            layout(constraints.maxWidth,top.height+art.height+bottom.height) {
                top.placeRelative(0,0)
                art.placeRelative(0,top.height)
                bottom.placeRelative(0,top.height+art.height)
            }
        }
    }
}

/** 插画的最小可见高度。低于它就把空间让给标题与按钮（按钮永远优先）。 */
internal val ART_MIN_HEIGHT = 72.dp

/**
 * v3.6.3：舞台高度 = 视口 − 标题 − footer，**但不低于 [minArtPx]**（纯函数，JVM 可单测）。
 *
 * 旧实现的硬地板是 200dp：320×568 上 153（标题）+ 176（按钮）+ 200 = 529 > 476（视口），
 * 「手动吹灭」必然掉到折线以下。地板降到 72dp 后同一工况是 123dp 插画、总高恰好 476。
 *
 * @return 像素高度；视口未知（0）时退回 [minArtPx]。
 */
internal fun celebrationArtHeightPx(viewportPx: Int, topPx: Int, bottomPx: Int, minArtPx: Int): Int =
    max(minArtPx, viewportPx - topPx - bottomPx)
