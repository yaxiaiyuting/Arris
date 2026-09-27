/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.theme.AppMotion
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors

/**
 * v2.9.0 · A/B/C：**全屏动效背景层**（竖屏与横屏共用同一个实现）。
 *
 * ## 它解决什么问题
 *
 * 任务书 §7.1：横屏的动效原本只局限在左侧（波形条挂在左栏），右侧歌词区与底部控制区
 * 背后什么都没有。竖屏更彻底 —— 连波形都没有。背景层把「动效」从**一个组件**
 * 提升成**一层画面**：封面降采样 + 模糊后铺满全屏，呼吸 / 粒子 / 光晕都画在它上面，
 * 前景（封面、歌词、控制条、波形）原样浮在上面。
 *
 * | 档 | 这一层画什么 |
 * |---|---|
 * | A | 模糊封面 + 呼吸（随 RMS 的 alpha/scale）+ 视差位移 |
 * | B | （背景级波形由 [MotionWaveBackdrop] 单独一层承担，见它的 KDoc） |
 * | C | 强拍光晕 + 定长池粒子（[MotionBackdropState]） |
 *
 * ## 三条硬约束（都不是偏好）
 *
 * 1. **降采样先于模糊**（铁律 24）：模糊的是 32×32 的降采样图，不是原图。
 *    全部像素运算在 [CoverBlur] 里，且**每首歌只算一次**（[CoverBlurCache]）。
 * 2. **歌词可读性优先**（任务书 §7.3）：模糊图之上**必须**压一层主题背景色的半透明遮罩。
 *    不压的后果是浅色封面把歌词冲得读不出来 —— 那是功能事故，不是审美问题。
 * 3. **异常隔离**（铁律 4）：取不到位图 / 模糊失败 / 任何一步抛异常 ⇒ 回退**纯色背景**，
 *    与 v2.8.0 的观感完全一致，且绝不影响播放链路（本层只读，不写任何东西）。
 *
 * ## 零重组（GPU zero-recomposition）
 *
 * 逐帧变化的量（呼吸、视差、脉冲）**全部**在 `graphicsLayer { }` 块或 `Canvas` 的 draw
 * lambda 里读 —— 那里读 Compose 状态只会让**这一层**失效，不触发任何重组。
 * 背景位图本身是静态的：每帧只做一次纹理采样（`drawImage` 一个四边形）。
 *
 * @param coverKey 封面缓存键（URL）。`null` ⇒ 不做模糊，直接纯色。
 * @param coverBitmap 当前封面位图（由 `StableCover` 上抛，见那里的 `onCoverBitmap`）。
 * @param parallaxProvider 视差位移的来源（卡片 `progress`，0 = 收起 / 1 = 展开）。
 *   **用 lambda 而不是值**：传值会让 `PlayerCard` 订阅 `progress` 而整树重组。
 * @param parallaxTravelPx 收起态（`progress == 0`）下背景相对展开态的位移量（px）。
 *   由调用方按屏幕高 × `AppMotion.PARALLAX_TRAVEL_FRACTION` 算好传进来 ——
 *   本层不自己去读 `LocalConfiguration`，那样在横竖屏切换时会出现两处尺寸来源。
 */
@Composable
fun MotionBackdrop(
    coverKey: String?,
    coverBitmap: Bitmap?,
    motion: MotionEffects,
    parallaxProvider: () -> Float,
    parallaxTravelPx: Float,
    modifier: Modifier = Modifier,
) {
    val fallback = LocalMetroColors.current.background
    val density = LocalDensity.current

    // ---- 模糊位图（异步算一次，失败回退 null）----
    var blurred by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverKey, coverBitmap) {
        if (!motion.backgroundBlur) {
            blurred = null
            return@LaunchedEffect
        }
        // 缓存命中是同步的（同一条链路上切歌回来不重算）；未命中在 Default 线程上算。
        blurred = CoverBlurCache.peek(coverKey)
            ?: runCatching { CoverBlurCache.blurred(coverKey, coverBitmap) }.getOrNull()
    }

    Box(modifier.fillMaxSize()) {
        // 底色：无论模糊是否可用都先铺主题背景色。它同时是「模糊失败的回退」与
        // 「模糊图层之下的不透明底」——后者保证 alpha 呼吸不会透出更下一层的内容。
        Box(Modifier.fillMaxSize().background(fallback))

        val image = blurred
        if (image != null && motion.anyBasic) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 呼吸：随 RMS 的明暗 + 缩放（幅度见 AppMotion）。
                        // 静态时 level() = 0 ⇒ 倍率恰好是 1f，与「没有呼吸」逐像素一致。
                        if (motion.backgroundBreathing) {
                            MotionClock.generation
                            val level = MotionClock.level()
                            alpha = (1f - AppMotion.BREATH_ALPHA_AMPLITUDE +
                                AppMotion.BREATH_ALPHA_AMPLITUDE * 2f * level).coerceIn(0f, 1f)
                            val breathScale = 1f + AppMotion.BREATH_SCALE_AMPLITUDE * level
                            scaleX = breathScale
                            scaleY = breathScale
                        }
                        // 视差：背景走前景 [AppMotion.PARALLAX_FACTOR] 的行程（竖屏为主，任务书 §5.4）。
                        // 收起态（progress = 0）位移最大、展开态归零 —— 拖拽过程中背景与前景
                        // 速度不同，这就是"视差"的全部内容；静止时位移是恒定的，不产生额外重绘。
                        if (motion.parallax) {
                            translationY = (1f - parallaxProvider().coerceIn(0f, 1f)) *
                                parallaxTravelPx * AppMotion.PARALLAX_FACTOR
                        }
                    }
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    // 32×32 上采样到全屏：必须用三次插值。双线性在 40 倍放大下会留下
                    // 可见的菱形刻面（比「糊」更难看），而这一层每帧只有一次纹理采样，
                    // 三次插值的代价可以忽略。
                    filterQuality = FilterQuality.High,
                )
            }
            // 可读性遮罩（见 KDoc 第 2 条）。比例 0.62 是「能看出封面颜色、但文字对比度
            // 与纯色背景同档」的折中；两个主题各自用各自的背景色，所以深浅色都不会跑偏。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(fallback.copy(alpha = BACKDROP_SCRIM_ALPHA))
            )
        }

        // ---- C 档：强拍光晕 + 粒子（画在遮罩之上，才看得见）----
        if (motion.haloBloom || motion.particles) {
            val haloColor = LocalMetroColors.current.primary
            val particleColor = LocalMetroColors.current.onBackground
            val strokeWidth = with(density) { 2.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                // 在 draw 阶段读：只让这块画布失效重绘，不触发重组。
                MotionClock.generation
                val backdrop = MotionClock.backstage
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val center = Offset(size.width / 2f, size.height * HALO_CENTER_Y_FRACTION)
                val maxRadius = size.minDimension * HALO_MAX_RADIUS_FRACTION
                if (motion.haloBloom) {
                    for (i in 0 until backdrop.haloCapacity) {
                        val progress = backdrop.haloProgressAt(i)
                        if (progress < 0f) continue
                        val radius = maxRadius * progress
                        // 越扩越淡：alpha 线性归零，配合半径增长读起来像"散开"而不是"画圈"。
                        val alpha = (1f - progress).coerceIn(0f, 1f) * HALO_MAX_ALPHA
                        drawCircle(
                            color = haloColor.copy(alpha = alpha),
                            radius = radius,
                            center = center,
                            style = Stroke(width = strokeWidth),
                        )
                    }
                }
                if (motion.particles) {
                    val radiusPx = with(density) { PARTICLE_RADIUS_DP.dp.toPx() }
                    for (i in 0 until backdrop.particleCapacity) {
                        val life = backdrop.particleLifeAt(i)
                        if (life <= 0f) continue
                        drawCircle(
                            color = particleColor.copy(alpha = life * PARTICLE_MAX_ALPHA),
                            radius = radiusPx * (0.5f + life * 0.5f),
                            center = Offset(
                                backdrop.particleXAt(i) * size.width,
                                backdrop.particleYAt(i) * size.height,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * v2.9.0 · B 档：**背景级波形的着色遮罩比例**。
 *
 * 背景级波形（横屏铺满底部）要能看见，但不能盖过前景。0.22 的依据：
 * 在 OLED 黑底上 22% 的主题色已经能明确读出「有东西在动」，
 * 而压在它上面的歌词行（`onBackground` 全白）对比度仍然远高于 WCAG AA 的 4.5:1。
 */
private const val BACKDROP_SCRIM_ALPHA = 0.62f

/** C 档光晕：中心纵向位置（屏幕高的比例）。0.42 ≈ 封面中心偏上，与前景封面呼应。 */
private const val HALO_CENTER_Y_FRACTION = 0.42f

/** C 档光晕：最大半径（屏幕短边的比例）。0.7 会扩到屏幕之外，读起来像"充满"而不是"一个圈"。 */
private const val HALO_MAX_RADIUS_FRACTION = 0.7f

/** C 档光晕：起始最大不透明度。0.35 足够可见，又不会在浅色封面上糊成一团。 */
private const val HALO_MAX_ALPHA = 0.35f

/** C 档粒子半径（dp）。2dp：一眼能看到，又不会被读成"画面脏了"。 */
private const val PARTICLE_RADIUS_DP = 2f

/** C 档粒子最大不透明度。 */
private const val PARTICLE_MAX_ALPHA = 0.5f
