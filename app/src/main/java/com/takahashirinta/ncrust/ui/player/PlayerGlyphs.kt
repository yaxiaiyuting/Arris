/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Playback marks share a 24-unit grid and a single stroke weight. */
internal object PlayerGlyphs {
    val Play = glyph("Play", solid = "M8 5.5Q8 4.7 8.8 5.1L19 11.2Q20.3 12 19 12.8L8.8 18.9Q8 19.3 8 18.5Z")
    val Pause = glyph("Pause", solid = "M8 5H9.6Q10.6 5 10.6 6V18Q10.6 19 9.6 19H8Q7 19 7 18V6Q7 5 8 5Z M14.4 5H16Q17 5 17 6V18Q17 19 16 19H14.4Q13.4 19 13.4 18V6Q13.4 5 14.4 5Z")
    val Previous = glyph("Previous", line = "M5 6V18", solid = "M18 5.7Q18 5 17.3 5.4L7.9 11.2Q6.7 12 7.9 12.8L17.3 18.6Q18 19 18 18.3Z")
    val Next = glyph("Next", line = "M19 6V18", solid = "M6 5.7Q6 5 6.7 5.4L16.1 11.2Q17.3 12 16.1 12.8L6.7 18.6Q6 19 6 18.3Z")

    val Lyrics = glyph("Lyrics", "M6 5H18Q20 5 20 7V14Q20 16 18 16H10L5 19V16Q4 15.5 4 14V7Q4 5 6 5Z M8 9H16 M8 12H13")
    val Karaoke = glyph("Karaoke", "M4 8V4H8 M16 4H20V8 M20 16V20H16 M8 20H4V16 M8 10H16 M9.5 14H14.5")
    val Back = glyph("Back", "M19 12H5 M11 6L5 12L11 18", mirror = true)
    val Queue = glyph("Queue", "M4 6H20 M4 11H20 M4 16H11", "M15 14L20 17.5L15 21Z", mirror = true)
    val Add = glyph("Add", "M12 5V19 M5 12H19")
    val Saved = glyph("Saved", "M5 12.5L9.5 17L19 7.5")
    val Expand = glyph("Expand", "M5 9V5H9 M15 5H19V9 M19 15V19H15 M9 19H5V15")
    val Shrink = glyph("Shrink", "M5 9H9V5 M19 9H15V5 M15 19V15H19 M9 19V15H5")
    val Dismiss = glyph("Dismiss", "M6.5 9.5L12 15L17.5 9.5")
    val Rotate = glyph("Rotate", "M8 4.8L15.7 2.8L18.7 14.1L11 16.1Z M5.1 9.1A8 8 0 0 0 16 20.1 M12.5 20.1H16V16.6")
    val RotationLocked = glyph("RotationLocked", "M7 13V5Q7 3 9 3H15Q17 3 17 5V9 M7 16V19Q7 21 9 21H12 M14 14H20Q21 14 21 15V19Q21 20 20 20H14Q13 20 13 19V15Q13 14 14 14Z M15 14V12A2 2 0 0 1 19 12V14")
    val Repeat = glyph("Repeat", "M17 3L20 6L17 9 M20 6H8A4 4 0 0 0 4 10 M7 21L4 18L7 15 M4 18H16A4 4 0 0 0 20 14")
    val RepeatOne = glyph("RepeatOne", "M17 3L20 6L17 9 M20 6H8A4 4 0 0 0 4 10 M7 21L4 18L7 15 M4 18H16A4 4 0 0 0 20 14 M10.5 11.5L12 10.5V14")
    val Shuffle = glyph("Shuffle", "M4 6H6.5C12 6 12 18 17.5 18H20 M17 15L20 18L17 21 M4 18H6.5Q8.5 18 10 15 M14 9Q15.5 6 17.5 6H20 M17 3L20 6L17 9")
    val Infinity = glyph("Infinity", "M12 12C9.7 8.2 8.4 7.5 6.7 7.5A4.5 4.5 0 0 0 6.7 16.5C11 16.5 13 7.5 17.3 7.5A4.5 4.5 0 0 1 17.3 16.5C15.6 16.5 14.3 15.8 12 12Z")
    val Remove = glyph("Remove", "M7 7L17 17 M17 7L7 17")
    val Drag = glyph("Drag", "M5 9H19 M5 15H19")
    val Share = glyph("Share", "M9 11L15 7 M9 13L15 17 M8 12A2 2 0 1 0 4 12A2 2 0 1 0 8 12Z M20 6A2 2 0 1 0 16 6A2 2 0 1 0 20 6Z M20 18A2 2 0 1 0 16 18A2 2 0 1 0 20 18Z")
    val ClearQueue = glyph("ClearQueue", "M5 6H19 M9 6V4H15V6 M7 6L8.2 20H15.8L17 6 M10.5 10V16 M13.5 10V16")

    private fun glyph(
        name: String,
        line: String? = null,
        solid: String? = null,
        mirror: Boolean = false,
    ): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {
        if (line != null) addPath(
            pathData = PathParser().parsePathString(line).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        if (solid != null) addPath(
            pathData = PathParser().parsePathString(solid).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
