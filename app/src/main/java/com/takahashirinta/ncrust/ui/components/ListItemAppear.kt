/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · A/C：列表 item 入场动效（浅入 + 上滑），有界且零重组。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.takahashirinta.ncrust.ui.theme.AppMotion

/**
 * v2.5.0 · A：列表 item 入场动效的**有界性判定**（纯逻辑，JVM 可单测）。
 *
 * ## 为什么需要「有界」这件事被单独抽出来
 *
 * 铁律 15：**UI 动效不得影响播放性能。** 本应用的目标下限是 Android 7.0 / 3GB RAM。
 * 「列表 item 出现时淡入 + 上滑」如果对**每一个** item 都执行，在快速甩动
 * 一屏 8~10 行的列表时，每帧会有十几个 `Animatable` 同时在跑 ——
 * 这是低端机上最典型的掉帧来源，而掉帧会直接影响正在播放的音频线程的调度余量。
 *
 * 所以本版的做法是**只对开头的一小段播放**：
 *  - 下标 `<` [MAX_ANIMATED_INDEX] ⇒ 播入场动效（用户的视线本来就在顶部，
 *    这一段是「首屏填充」的观感收益所在）；
 *  - 其余 ⇒ **直接以终态出现**（`alpha = 1`、`translationY = 0`），零动画开销。
 *
 * 滚动到深处时 item 是**首次组合**的，天然不属于前 12 个，所以甩动列表
 * 一个动画都不会起。
 *
 * ## 为什么是 12
 *
 * 取「一屏能看到的行数 + 一点余量」：360dp 宽的窄屏上单曲行约 64dp、
 * 1440px 的 PCL110 上约 10 行，12 覆盖首屏并留一行缓冲。它**不是**调优出来的魔法数，
 * 而是一个明确的「首屏」语义 —— 改它不需要重新取证，改它的**语义**才需要。
 */
object ListItemAppear {
    /** 播放入场动效的最大下标（不含）。 */
    const val MAX_ANIMATED_INDEX: Int = 12

    /**
     * 该下标是否播入场动效。负下标（理论上不出现）不播。
     */
    fun shouldAnimate(index: Int): Boolean = index in 0 until MAX_ANIMATED_INDEX
}

/**
 * 列表 item 入场：**淡入 + 上滑**（任务书 §3.3）。
 *
 * ## 零重组
 *
 * 动画值放在 [Animatable] 里，**只在 `graphicsLayer { }` 里读** ——
 * 与项目「GPU 零重组」约定一致（`AppMotion` 的 KDoc 里写明：spec 只描述时间，
 * 消费侧仍必须零重组）。整个入场过程**不触发任何重组**，只有这一层重绘。
 *
 * ## 一次就结束，不常驻
 *
 * `LaunchedEffect(Unit)` 只跑一次（key 是 `Unit`，不是 `index`）；
 * item 滚出屏幕被回收、再滚回来时是**新的一次组合**，会再播一次 ——
 * 这是有意的：那一次出现对用户同样是「新出现」。
 *
 * ## 与命中测试的关系（`AGENTS.md`「Compose 触摸陷阱」第 1 条）
 *
 * `graphicsLayer { alpha = … }` **不退出命中测试** —— 也就是说入场的那 220ms 内，
 * 一个「看不见」的 item 已经可以点。这里**有意接受**这个代价：
 *  - 它只影响列表开头 12 个 item，且窗口只有 220ms；
 *  - 反面做法（`alpha < 1` 时不挂载 `clickable`）会让「刚进页面立刻点第一行」
 *    在前 220ms 内**点了没反应**，那是一个更难解释的 bug；
 *  - 播放器卡片的死带问题（同一份文档）是 `fillMaxSize` + `translationY` 造成的，
 *    与本修饰符无关：这里的 `translationY` 只有 8dp，且随动画归零。
 *
 * @param index 该 item 在列表中的下标（决定是否播）
 * @param enabled 全局开关；为 false 时直接终态（给测试 / 低功耗场景留的出口）
 */
@Composable
fun Modifier.listItemAppear(index: Int, enabled: Boolean = true): Modifier {
    val animate = enabled && ListItemAppear.shouldAnimate(index)
    // 起点直接给终态时 Animatable 不参与任何帧调度 —— 这就是「有界」的落实点。
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    val risePx = with(LocalDensity.current) { AppMotion.LIST_ITEM_RISE_DP.toPx() }

    if (animate) {
        LaunchedEffect(Unit) {
            progress.animateTo(1f, AppMotion.listItemEnter)
        }
    }

    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * risePx
    }
}
