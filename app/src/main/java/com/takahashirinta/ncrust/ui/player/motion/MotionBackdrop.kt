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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
                            // 「亮度 ±5%」的落地方向是**安静时最多压暗 5%、满响度回到 1.0**
                            // （而不是 0.95..1.05 —— 那会被 `coerceIn(0,1)` 夹掉上半段，
                            // 实际变成"响度到一半就不再有变化"，S6 实测复核时发现）。
                            alpha = (1f - AppMotion.BREATH_ALPHA_AMPLITUDE +
                                AppMotion.BREATH_ALPHA_AMPLITUDE * level).coerceIn(0f, 1f)
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
            // 可读性遮罩（见 KDoc 第 2 条），**纵向渐变而不是一层平铺**。
            //
            // 为什么不用平铺（v2.9.0 真机观感修正）：平铺 62% 会把整屏压成同一个亮度，
            // 彩色背景因此读起来像"一块脏灰"，而且顶部标题栏与底部控制区**本来就更需要**
            // 对比度、中间歌词区反而更需要背景色。渐变一次解决两件事：
            //   · 顶部 0.82 —— 标题栏/收起按钮压在最亮处容易糊；
            //   · 中部 0.72 —— 颜色从这里露出来（这是"美化"的全部意义）；
            //   · 底部 0.86 —— 进度条与传输键的对比度，同时让歌词面板自身的黑色渐隐带
            //     （`NcrustLyricsPanel` 的 [background, Transparent]）接得上、不出现硬接缝。
            // 三个值都是**压暗比例**：1.0 = 完全纯色（等于关掉动效）。
            //
            // ⚠️ 中部**不能靠"压得更暗"来求稳**：第一次实现用平铺 0.62，S6 截图复核的结论是
            // 「一块脏灰」—— 灰的原因是**没有色度**，不是太亮。所以本版把颜色的来源放在
            // `CoverBlur.SATURATION`（2.6）上，遮罩只负责对比度。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to fallback.copy(alpha = BACKDROP_SCRIM_TOP),
                            0.42f to fallback.copy(alpha = BACKDROP_SCRIM_MIDDLE),
                            1.00f to fallback.copy(alpha = BACKDROP_SCRIM_BOTTOM),
                        )
                    )
            )
        }

        // ---- v3.0.0：瞬态冲击波 / 光晕 + 中高频粒子（画在遮罩之上，才看得见）----
        if (motion.shockwave || motion.haloBloom || motion.particles) {
            val haloColor = LocalMetroColors.current.primary
            val particleColor = LocalMetroColors.current.onBackground
            val strokeWidth = with(density) { 2.dp.toPx() }
            // 径向渐变 Brush **必须缓存**：在 draw lambda 里 `Brush.radialGradient(...)` 等于
            // 每帧新建一个 Brush + 一个 colors List + 一个 native SkShader（违反零分配）。
            // 这里按「颜色 + 半径」缓存，只在主题色或尺寸变化时重建。
            val gradientCache = remember(haloColor) { RadialGradientCache(haloColor) }
            Canvas(Modifier.fillMaxSize()) {
                // 在 draw 阶段读：只让这块画布失效重绘，不触发重组。
                MotionClock.generation
                val backdrop = MotionClock.backstage
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val center = Offset(size.width / 2f, size.height * HALO_CENTER_Y_FRACTION)
                val maxRadius = size.minDimension * HALO_MAX_RADIUS_FRACTION
                val brush = if (motion.haloBloom || motion.shockwave) {
                    gradientCache.obtain(maxRadius)
                } else {
                    null
                }
                // 冲击波画在光晕**下面**（z 序）：它是"一下"，光晕是"余韵"。
                if (motion.shockwave && brush != null) {
                    for (i in 0 until backdrop.shockCapacity) {
                        val progress = backdrop.shockProgressAt(i)
                        if (progress < 0f) continue
                        val radius = maxRadius * backdrop.shockScaleAt(i) * progress
                        if (radius < 1f) continue
                        // 渐晕：中心不透明、边缘透明，越扩越淡。
                        // `scale` 让同一个缓存 Brush 适配任意半径（画布矩阵同时作用于 shader）。
                        drawCircle(
                            brush = brush,
                            radius = radius,
                            center = center,
                            alpha = (1f - progress).coerceIn(0f, 1f) * SHOCK_MAX_ALPHA,
                        )
                    }
                }
                if (motion.haloBloom && brush != null) {
                    for (i in 0 until backdrop.haloCapacity) {
                        val progress = backdrop.haloProgressAt(i)
                        if (progress < 0f) continue
                        val radius = maxRadius * backdrop.haloScaleAt(i) * progress
                        if (radius < 1f) continue
                        // 光晕是**描边环**（与冲击波的实心渐晕区分开）：
                        // 越扩越淡，配合半径增长读起来像"散开"而不是"画圈"。
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
 * v3.0.0：**径向渐变 Brush 的缓存**（零分配的关键一环）。
 *
 * ## 为什么不能每帧 `Brush.radialGradient(...)`
 *
 * 那个调用会分配三样东西：一个 `Brush`、一个 `List<Color>`、以及在绘制时创建的
 * native `SkShader`。放在 `Canvas {}` 的 draw lambda 里就是**每帧每槽位一次**。
 *
 * ## 为什么按半径缓存一次就够
 *
 * 缓存的是「半径 = [maxRadius] 的径向渐变」，绘制时按进度缩放**画布**：
 * Skia 的 shader 受画布矩阵影响，所以同一个 Brush 能画出任意半径的圆。
 * 半径变化（旋转 / 分栏 / 进出大屏）时重建一次，之后每帧零分配。
 *
 * @param color 光晕颜色（主题色）。主题色变化时 `remember(color)` 会重建本对象。
 */
private class RadialGradientCache(private val color: Color) {
    private var cachedRadius = Float.NaN
    private var cached: Brush? = null

    fun obtain(radiusPx: Float): Brush {
        val radius = if (radiusPx > 0f) radiusPx else 1f
        val existing = cached
        if (existing != null && cachedRadius == radius) return existing
        val brush = Brush.radialGradient(
            // 中心亮、边缘透明：这就是「渐晕」的全部内容。
            // 中间那一段（0.45）保持较高不透明度，让圆看起来是"一圈厚的光"而不是"一个点"。
            colorStops = arrayOf(
                0.00f to color.copy(alpha = 1f),
                0.45f to color.copy(alpha = 0.55f),
                1.00f to color.copy(alpha = 0f),
            ),
            center = Offset.Zero,
            radius = radius,
        )
        cachedRadius = radius
        cached = brush
        return brush
    }
}

/**
 * v2.9.0 · A 档：**模糊背景之上的可读性遮罩**（「歌词可读性优先」的落点，任务书 §7.3）。
 *
 * 三个值从上到下：标题栏区压得最暗、中间歌词区放亮（露出封面颜色）、底部控制区再压暗。
 * 压暗色用**主题背景色**而不是纯黑 —— 深色主题下就是 OLED 黑，浅色主题下是暖白，
 * 两个主题因此都不会跑偏。
 *
 * ⚠️ 不要与另外两个数字混淆：
 *  - `PlayerCard.WAVE_BACKDROP_ALPHA`（0.32）= **背景级波形**那一层的不透明度（B 档）；
 *  - `AppMotion.BREATH_ALPHA_AMPLITUDE`（0.05）= 背景随响度的明暗幅度。
 */
private const val BACKDROP_SCRIM_TOP = 0.82f
private const val BACKDROP_SCRIM_MIDDLE = 0.72f
private const val BACKDROP_SCRIM_BOTTOM = 0.86f

/** C 档光晕：中心纵向位置（屏幕高的比例）。0.42 ≈ 封面中心偏上，与前景封面呼应。 */
private const val HALO_CENTER_Y_FRACTION = 0.42f

/** C 档光晕：最大半径（屏幕短边的比例）。0.7 会扩到屏幕之外，读起来像"充满"而不是"一个圈"。 */
private const val HALO_MAX_RADIUS_FRACTION = 0.7f

/** C 档光晕：起始最大不透明度。0.35 足够可见，又不会在浅色封面上糊成一团。 */
private const val HALO_MAX_ALPHA = 0.35f

/**
 * v3.0.0 冲击波：起始最大不透明度。
 *
 * 比光晕（0.35）低一档（0.28）：冲击波是**实心渐晕**，覆盖面积远大于一圈描边，
 * 同样的 alpha 会让画面明显发白。两次重击叠加时也只是短暂到 0.56，仍在可读范围内。
 */
private const val SHOCK_MAX_ALPHA = 0.28f

/** C 档粒子半径（dp）。2dp：一眼能看到，又不会被读成"画面脏了"。 */
private const val PARTICLE_RADIUS_DP = 2f

/** C 档粒子最大不透明度。 */
private const val PARTICLE_MAX_ALPHA = 0.5f
