/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · A/B：两件可复用的视觉修饰符 —— 封面边框包裹、按压回弹。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import kotlinx.coroutines.launch

/**
 * v2.5.0 · B：**专辑封面边框包裹**（任务书 §4.1）。
 *
 * 做三件事，顺序不能反：
 *  1. `clip(shape)` —— 先把图裁成圆角，否则图片的直角会从描边外露出来；
 *  2. `border(1.dp, outlineVariant, shape)` —— 描边**沿同一 shape** 画，
 *     不传 shape 会画成直角方框（既有 13 处 `border(...)` 全部是这个写法）；
 *  3. 两者都在 `graphicsLayer` 的裁剪层上完成，**不改变内容的测量尺寸** ——
 *     这一点很关键：封面尺寸由调用点决定，本修饰符不能让它偏移 1dp。
 *
 * ## 为什么描边用 `outlineVariant` 而不是 `primary`
 *
 * 描边的职责是「把图片与背景分开」，不是强调。用主题色描边会在**每一张**封面上
 * 压一层品牌色 —— 与 v2.5.0 的取色目标（颜色应当来自封面本身）直接冲突。
 * `outlineVariant` 是主题里就有的弱描边色（`NcrustColors.kt:44`），
 * 深浅色下都够用，且不会与封面自身的颜色打架。
 *
 * ## 性能代价（必须记录，铁律 15）
 *
 * `clip()` 会引入一个 `graphicsLayer`（离屏裁剪）。播放页大封面本来就在
 * `graphicsLayer` 动画链上，多这一层是**可测量**的开销。
 * 因此本版在真机上对「展开/收起播放器的帧时间」做了对照记录，
 * 见 `verification/`；若某设备上出现掉帧，第一个要回退的就是大封面的 clip。
 *
 * @param shape 圆角；大封面用 [AppShapes.large]，列表/菜单小封面用 [AppShapes.small]
 * @param width 描边宽度；默认 1dp
 */
@Composable
fun Modifier.appCoverFrame(
    shape: Shape = AppShapes.large,
    width: Dp = 1.dp,
): Modifier {
    val outline = LocalNcrustColors.current.outlineVariant
    return this
        .clip(shape)
        .border(width = width, color = outline, shape = shape)
}

/**
 * v2.5.0 · A：**按压回弹**（任务书 §3.3「按钮点击：scale 1.05x 回弹」）。
 *
 * ## 为什么不用 `.clickable` 自带的状态
 *
 * `Modifier.clickable` 的按压态是通过 `InteractionSource` 暴露的，消费它需要
 * `collectIsPressedAsState()` —— 那是一个**组合期状态**，按下/松开各触发一次重组。
 * 本应用对播放器卡片有「禁止逐帧重组」的硬约定，虽然本修饰符只用于卡片外的按钮，
 * 但混用两套写法会让后来者分不清哪一套是对的。
 *
 * 这里改成 `pointerInput` 直接看指针事件，动画值放 [Animatable]，
 * **只在 `graphicsLayer { }` 里读** —— 全程零重组。
 *
 * ## 绝不消费事件
 *
 * 用 `PointerEventPass.Initial` 且**不调用** `consume()`：
 * 本修饰符只**观察**按压，事件继续正常传给同一节点上/下层的 `clickable`。
 * 这一点是硬要求 —— 一旦消费，按钮就点不动了。
 *
 * ## 与 `Modifier.clickable` 的挂载顺序
 *
 * 本修饰符放在 `clickable` **之前**（更靠外层）时，缩放作用于包含点击区的整块；
 * 放在之后则只缩放视觉。两种都可以用，调用点按视觉需要选。
 *
 * @param enabled 为 false 时完全不挂载 `pointerInput`（零开销）
 * @param pressedScale 按下时的放大倍数
 */
@Composable
fun Modifier.appPressScale(
    enabled: Boolean = true,
    pressedScale: Float = AppMotion.PRESS_SCALE,
): Modifier {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    if (!enabled) return this

    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(pressedScale) {
            awaitEachGesture {
                // requireUnconsumed = false：即使内层已经处理过按下，我们仍然要观察它。
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                scope.launch { scale.animateTo(pressedScale, AppMotion.pressScale) }
                try {
                    // 一直等到这一根手指抬起（或手势被取消）。
                    // `awaitEachGesture` 会在手势结束时自动收尾，这里只需要阻塞到抬起，
                    // 且**不能** break 出去 —— 否则收尾的 animateTo(1f) 会与下一次按下竞争。
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull()
                        if (change == null || !change.pressed) break
                    }
                } finally {
                    // 回弹放在 finally：手势被取消（例如列表开始滚动）时也必须归位，
                    // 否则按钮会永久停在放大态 —— 那是「点了之后一直大着」的观感 bug。
                    scope.launch { scale.animateTo(1f, AppMotion.pressScale) }
                }
            }
        }
}
