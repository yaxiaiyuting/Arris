/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Fine concentric lines used by the listening artwork. The artwork draws inside its own clip. */
internal fun DrawScope.drawArrisGrooves(center: Offset, radius: Float, ink: Color) {
    val stroke = Stroke(0.75.dp.toPx())
    for (index in 0..7) {
        drawCircle(
            color = ink.copy(alpha = if (index % 3 == 0) 0.19f else 0.095f),
            radius = radius * (0.42f + index * 0.083f),
            center = center,
            style = stroke,
        )
    }
}
