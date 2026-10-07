/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：从 `PlayerCard` 拆出的**背景层**（两张纯色底板 + 动效背景 + 唯一帧时钟 +
 * 背景级波形）。
 *
 * ## 为什么是 `BoxScope` 扩展
 *
 * 背景级波形用 `.align(Alignment.BottomCenter)` 贴在卡片底部 —— 那是 `BoxScope` 的 API。
 * 包一层 `Box` 会多一个布局节点，`align` 的参照物也随之改变，**不是逐字节等价**。
 *
 * ## 唯一帧时钟的挂载点不能动（否则动画要么停摆要么跑两遍）
 *
 * [MotionFrameClock] 在整棵播放器树里**恰好挂载一次**：
 *  - 竖屏手机上 `visualizerSlot` 恒 false（波形组件不挂载），而背景呼吸 / 节拍脉冲 / 粒子
 *    在竖屏也要跑 ⇒ 帧时钟不能长在波形里；
 *  - 两处各挂一次会让动画相位每帧前进两次。
 * 本函数被 `PlayerCard` **无条件**调用（不在任何 `if` 里），所以这个不变量与拆分前一致。
 */

package com.takahashirinta.ncrust.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.takahashirinta.ncrust.ui.player.motion.MotionBackdrop
import com.takahashirinta.ncrust.ui.player.motion.MotionFrameClock
import com.takahashirinta.ncrust.ui.player.motion.MotionEffects
import com.takahashirinta.ncrust.ui.theme.AppMotion
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import kotlinx.coroutines.flow.StateFlow

/**
 * 卡片根 `Box` 的**背景层**（原 `PlayerCard.kt` 696–799 行，逐行搬移）。
 *
 * 顺序即 z 序：纯黑底 → 折叠卡背 → 动效背景 → （无 UI 的帧时钟）→ 背景级波形。
 * 它们都在两张纯色底板**之上**、`Column`（封面/歌词/控件等全部前景）**之下**。
 * 本层不挂任何 `pointerInput` / `clickable`，**不新增任何命中面**
 * （AGENTS.md 触摸陷阱第 1/4 条）。
 */
@Composable
internal fun BoxScope.PlayerCardBackdrop(
    progress: Animatable<Float, AnimationVector1D>,
    statusBarPx: Float,
    motion: MotionEffects,
    coverArtKey: String?,
    coverArtBitmap: Bitmap?,
    screenHeightPx: Float,
    isPlaying: Boolean,
    hasSong: Boolean,
    expandedMounted: Boolean,
    visualizerSlot: Boolean,
    waveBackdropHeightDp: androidx.compose.ui.unit.Dp,
    /** 缓冲状态**传引用**：订阅发生在帧循环内部，不在组合期（见 AudioVisualizerSlot 的 KDoc）。 */
    isBufferingFlow: StateFlow<Boolean>,
) {
        // 全屏纯黑背景。展开时 translationY=0，卡片顶与封面顶/内容区顶等高；
        // 折叠时整体下移一个 statusBar 高，使顶边对齐 miniBar 内容。miniBar 自带
        // statusBarsPadding（内容被下推 statusBar 高），背景若不跟着下移，miniBar
        // 上方就会多出一条 statusBar 高的 surface 色块——即"手机 miniBar 变高一片"。
        // statusBarPx 在车机(WindowInsets=0)为 0，天然不影响车机。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = statusBarPx * (1f - progress.value)
                }
                .background(LocalMetroColors.current.background)
        )
        // 折叠态卡背：卡片整体下移后，miniBar 下方露出的是这张黑底（原底部导航/系统栏
        // 位置）。折叠时用 surface 盖住，与 miniBar 同色，避免底部黑块；展开时透明。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
                    translationY = statusBarPx * (1f - progress.value)
                }
                .background(LocalMetroColors.current.surface)
        )

        // ── v2.9.0：全屏动效背景层（竖屏 + 横屏共用同一个实现）────────────────────────
        //
        // 位置：两张纯色底板**之上**、Column（封面/歌词/控件等全部前景）**之下**。
        // 所以它是真正的"背景"：z 序最低的前景内容全都浮在它上面，且它自己没有任何
        // pointerInput ⇒ 不新增任何命中面（AGENTS.md 触摸陷阱第 1 条）。
        //
        // 关掉「界面动效」总开关、或自动降级到 UI_ALL_OFF 时**整层不挂载** ——
        // 不是 `alpha = 0`（那仍然参与绘制与命中测试）。铁律「关掉 = 零开销」由结构保证。
        if (motion.anyUiMotion) {
            MotionBackdrop(
                coverKey = coverArtKey,
                coverBitmap = coverArtBitmap,
                motion = motion,
                parallaxProvider = { progress.value },
                // 视差行程按屏幕高给（不是固定 dp）：横竖屏切换时行程跟着屏幕走，
                // 两处尺寸来源（LocalConfiguration 与 PlayerCard）不会打架。
                parallaxTravelPx = screenHeightPx * AppMotion.PARALLAX_TRAVEL_FRACTION,
                modifier = Modifier.graphicsLayer {
                    // 与前景同样的折叠淡出曲线（复用既有 (p-0.7)/0.3 的形状）。
                    alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
                },
            )
        }

        // ── v2.9.0 · 唯一的帧时钟（波形 + 界面动效共用一条循环）──────────────────────
        // 为什么必须挂在这里：竖屏手机上波形组件根本不挂载（`visualizerSlot` 恒 false），
        // 而背景呼吸/节拍脉冲/粒子在竖屏也要跑。两处各起一条循环会让动画相位每帧前进两次。
        // 详见 `ui/player/motion/MotionClock.kt` 的 KDoc。它不产生任何 UI 节点。
        //
        // v3.2.0：`waveformMounted` 是**必须**显式传的。简化档（简洁档）现在没有任何逐帧的
        // 界面动效，而波形柱是**每一档**都随 RMS 推进的（v2.8.0 的帧循环本来就长在它里面）
        // —— 不把「波形挂载」单独告诉帧时钟，简洁档的波形会冻住（探针 §2 的连带发现）。
        MotionFrameClock(
            activeProvider = { isPlaying && !isBufferingFlow.value },
            enabled = hasSong && expandedMounted,
            waveformMounted = visualizerSlot || motion.fullScreenWaveform,
        )

        // ── v2.9.0 · B 档：**背景级波形**（横屏铺满底部横跨全屏 / 竖屏在歌词后面流动）──
        //
        // 它解决的问题是任务书 §7.2 的原话：「波形从左侧扩展到全屏底部横跨」。
        // 实现上**不动任何既有布局**（§7.3）：左栏那条波形原样留在原地，
        // 这一条是**额外**的一层背景 —— 所以"不改变现有横屏布局结构"这条要求由结构保证，
        // 而不是靠"改得小心"。
        //
        // 复用 `AudioVisualizerBars` 而不是再写一份画法：A/B/C 三档的渲染差异
        // （圆角柱 / 峰值 / 渐变流动 / 光点 / 冲击波 / 粒子 / 3D）都已经在那里，
        // 复制一份必然分叉。绘制成本是每帧多一次 Canvas（B 档默认关，S6 这类低端机
        // 默认简洁档 ⇒ 这一层根本不挂载）。
        if (motion.fullScreenWaveform) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(waveBackdropHeightDp),
            ) {
                // 压暗到 WAVE_BACKDROP_ALPHA：背景级波形的职责是"有东西在流动"，
                // 抢过前景就违反了 §7.3「歌词可读性优先」。前景内容全部画在它之上。
                AudioVisualizerBars(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) *
                                WAVE_BACKDROP_ALPHA
                        },
                )
                // v2.9.0（真机观感修正）：**上沿渐隐**。
                // 平铺的一条带子在真机上是一条硬边（PCL110 截图复核：横跨整屏的一条直线，
                // 看起来像渲染错误而不是设计）。这里在带子的上半部压一层由不透明到透明的
                // 渐变，波形因此"从背景里浮出来"而不是"被裁了一刀"。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to LocalMetroColors.current.background,
                                1.0f to Color.Transparent,
                            )
                        )
                )
            }
        }
}

// v2.9.0 · B 档：背景级波形的不透明度。0.26 的依据：在 OLED 黑底上这个比例的主题色
// 已经能明确读出"整块背景在流动"，而压在它上面的歌词行（onBackground 全白）
// 对比度仍远高于 WCAG AA 的 4.5:1 —— 任务书 §7.3「歌词可读性优先」。
// v3.2.1：随背景层一起搬到本文件（唯一消费者就是这里的背景级波形，不留第二份定义）。
private const val WAVE_BACKDROP_ALPHA = 0.26f
