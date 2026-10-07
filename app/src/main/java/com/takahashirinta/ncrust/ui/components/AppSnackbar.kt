/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.0 · D：应用级 Snackbar。本仓库此前**没有任何 Snackbar 设施**
 *     （全仓库 `Snackbar` 命中 0 处，反馈一律走 `Toast`），本文件是它的唯一落点。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import androidx.compose.ui.unit.Dp

/**
 * v2.5.0 · D：Snackbar 状态。
 *
 * ## 为什么不直接用 `Toast`
 *
 * 任务书 §6.2 明确要求「点击后给出 Snackbar 提示」。而且这里确实有 Toast 做不到的事：
 * **同一个动作有四种不同的结果**（插入 / 搬移 / 已在下一首 / 直接起播），
 * 用 Toast 连续弹四次会互相覆盖、用户看不见前一次。
 * Snackbar 有明确的「当前一条」语义，重复触发时**替换**而不是排队 —— 这正是这里要的。
 *
 * ## 为什么自己写而不是引依赖
 *
 * 本工程**没有** `material3` 依赖（`probe-theme.md` §1.2），所以拿不到
 * `SnackbarHost` / `SnackbarHostState`。为一个提示条引入 material3 会破坏
 * 「零 M3」这条项目铁律。实现成本很低（一个 state + 一个非交互浮层），自己写更划算。
 *
 * ## 单条语义（不是队列）
 *
 * [show] 每次用自增的 [Entry.id] 覆盖当前条目。宿主以 id 为 key 重启动画，
 * 所以「连点两次」的表现是提示条**重新滑入**，而不是排队等前一条消失 ——
 * 后一条才是用户刚做的动作，排队播放会让界面显示过期的结论。
 */
@Stable
class AppSnackbarState {

    /** 一次提示。 */
    data class Entry(val id: Int, val text: String, val durationMs: Long)

    /**
     * 当前正在显示的一条；null = 不显示。
     *
     * setter 是 `internal` 而不是 `private`：宿主 [AppSnackbarHost] 在动画播完后
     * 需要把它清空，而「清空」只能由宿主做 —— 只有宿主知道动画真的结束了。
     * 对外仍只暴露 [show] / [dismiss] 两个入口。
     */
    internal var current by mutableStateOf<Entry?>(null)

    private var seq: Int = 0

    /**
     * 显示一条提示。会**替换**当前正在显示的那一条。
     *
     * @param durationMs 停留时长（不含滑入滑出），默认 [DEFAULT_DURATION_MS]
     */
    fun show(text: String, durationMs: Long = DEFAULT_DURATION_MS) {
        if (text.isBlank()) return
        seq += 1
        current = Entry(seq, text, durationMs)
    }

    /** 立即收起（例如页面切换时）。 */
    fun dismiss() {
        current = null
    }

    companion object {
        /**
         * 默认停留 2200ms。
         *
         * 比 Toast.LENGTH_SHORT（2000ms）略长一点：这里的文案平均比 Toast 长
         * （「已添加到下一首播放」9 字），而 Snackbar 自己不参与系统 Toast 队列，
         * 没有「被下一条顶掉」的风险，可以给足阅读时间。
         */
        const val DEFAULT_DURATION_MS: Long = 2200L
    }
}

@Composable
fun rememberAppSnackbarState(): AppSnackbarState = remember { AppSnackbarState() }

/**
 * v2.5.0 · D：应用级 Snackbar 宿主。
 *
 * ## ⚠️ 它是**非交互**的，这是安全性的前提
 *
 * 本应用的播放器卡片是 `fillMaxSize` + `graphicsLayer` 平移到屏幕底部的独立图层，
 * 因为「看不见的地方还能点」踩过一整类坑（`AGENTS.md`「Compose 触摸陷阱」）。
 * 一个画在屏幕底部、覆盖在内容之上的浮层，如果挂了任何 `pointerInput` / `clickable`，
 * 就会在**它所在的那一条**上造出新的死带 —— 而且只在提示出现的 2 秒内出现，
 * 是最难复现的那一类。
 *
 * 所以本宿主**刻意不挂载任何指针输入**：没有 `pointerInput`、没有 `clickable`、
 * 没有 `semantics { onClick }`。Compose 的命中测试只考虑挂了指针输入的节点，
 * 因此这一层对触摸**完全透明** —— 提示显示期间，它下面的按钮照常可以点。
 *
 * 代价（如实记录）：**Snackbar 不能带操作按钮**。本版不需要
 * （提示都是「已经做完了」的确认，没有可撤销的动作），所以这个代价是零。
 * 将来若要加「撤销」，必须先解决上面那条死带问题，不能直接加 `clickable`。
 *
 * ## 无障碍
 *
 * `liveRegion = LiveRegionMode.Polite` + 文字本身就是内容 —— 读屏会在不打断
 * 当前朗读的前提下念出这条提示。这也是不能用「纯 draw 画一个浮层」的原因。
 *
 * @param modifier 由调用方定位（通常是根 Box 的 `Modifier.align(BottomCenter)` +
 *   底部安全区留白）。宿主自身不决定位置，因为「底部该留多少」由调用方的叠层决定。
 */
@Composable
fun AppSnackbarHost(
    state: AppSnackbarState,
    modifier: Modifier = Modifier,
) {
    val entry = state.current ?: return
    val colors = LocalNcrustColors.current
    val typography = LocalMetroTypography.current

    // 0 = 完全收起，1 = 完全显示。只在 graphicsLayer 里读 ⇒ 全程零重组。
    val progress = remember { Animatable(0f) }
    // 必须是 `Dp` 再 `.toPx()`：直接对 Float 调 toPx 编译不过（Float 上没有这个扩展）。
    // 用 Dp 常量而不是裸像素数，密度无关性也才对。
    val slidePx = with(LocalDensity.current) { SNACKBAR_SLIDE_DP.toPx() }

    // key 是 entry.id：连点两次会**重启**这段动画（重新滑入），不是排队。
    LaunchedEffect(entry.id) {
        progress.snapTo(0f)
        // 滑入 / 滑出都走 AppMotion —— 本版新增动效不得散写 tween（probe-motion.md §P3）。
        progress.animateTo(1f, AppMotion.effects)
        delay(entry.durationMs)
        progress.animateTo(0f, AppMotion.effects)
        // 只有「还是这一条」时才清空 —— 否则会把刚替换上来的新提示误删。
        if (state.current?.id == entry.id) state.current = null
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * slidePx
            }
            // 药丸形：与任务书 §3.1「按钮：full（药丸形）」同一套 token。
            .clip(AppShapes.full)
            .background(colors.surfaceContainerHighest)
            .widthIn(min = 120.dp, max = 320.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        // `MetroText` 没有 `textAlign` 形参（它只把 style 透传给 BasicText），
        // 所以居中要**写进 TextStyle** —— 这不是绕路，BasicText 本来就按 style 里的
        // textAlign 排版，效果与"给 MetroText 加一个参数"完全一致。
        MetroText(
            text = entry.text,
            color = colors.onSurface,
            style = typography.bodyMedium.copy(textAlign = TextAlign.Center),
        )
    }
}

/** 滑入位移。24dp ≈ 一个列表行高的三分之一，读得出「从下面上来」但不夸张。 */
private val SNACKBAR_SLIDE_DP: Dp = 24.dp
