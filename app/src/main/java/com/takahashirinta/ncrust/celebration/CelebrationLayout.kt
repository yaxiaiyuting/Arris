package com.takahashirinta.ncrust.celebration

import android.graphics.RectF
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
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

/** Measure copy/actions first. The stage gets the remaining space; small screens can scroll. */
@Composable
internal fun CelebrationFrame(placement: CelebrationPlacement, heading: @Composable () -> Unit, footer: @Composable () -> Unit) {
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
                Box { footer() }
            },
        ) { children, constraints ->
            val loose=Constraints(minWidth=constraints.maxWidth, maxWidth=constraints.maxWidth)
            val top=children[0].measure(loose)
            val bottom=children[2].measure(loose)
            val artHeight=max(200.dp.roundToPx(), constraints.minHeight-top.height-bottom.height)
            val art=children[1].measure(Constraints.fixed(constraints.maxWidth,artHeight))
            layout(constraints.maxWidth,top.height+art.height+bottom.height) {
                top.placeRelative(0,0)
                art.placeRelative(0,top.height)
                bottom.placeRelative(0,top.height+art.height)
            }
        }
    }
}
