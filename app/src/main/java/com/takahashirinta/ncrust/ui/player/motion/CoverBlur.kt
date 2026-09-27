/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2.9.0 · A 档：**封面背景模糊 —— 全部像素运算都在这里**（纯 Kotlin，无 Android 依赖）。
 *
 * ## 铁律 24：背景模糊必须用低分辨率降采样，不得对原图直接高斯模糊
 *
 * 一张 1000×1000 的封面做「原图高斯模糊」有两个代价，任何一个都足以否掉这个方案：
 *
 * 1. **CPU 侧**：1000²×4 字节 = 4 MB 的像素缓冲，3 遍可分离模糊 = 6 次全缓冲遍历。
 *    在 S6（Cortex-A57，2015）上是**几百毫秒**量级 —— 那是切歌时肉眼可见的一次卡顿；
 * 2. **GPU 侧**：`RenderEffect`（API 31+）虽然把开销丢给 GPU，但它作用在**全屏图层**上，
 *    每帧都要对一整屏像素做一遍模糊。而背景本身**每首歌只变一次** —— 每帧重算是纯浪费。
 *
 * 所以本方案把顺序倒过来：**先降采样，再模糊，最后把结果当作一张位图铺满全屏**。
 *
 * | 步骤 | 尺寸 | 代价 |
 * |---|---|---|
 * | 盒式平均降采样 | 原图 → [DOWNSAMPLE_PX]² = **32×32** | 一次 O(源像素) 遍历 |
 * | 三遍可分离盒式模糊 | 32×32，半径 [BLUR_RADIUS_PX] | 3 遍 × 2 方向 × 1024 像素 ≈ 6 k 次加法 |
 * | 上采样铺满 | 32×32 → 全屏 | **GPU 一次纹理采样**（`FilterQuality.High` 三次插值） |
 *
 * 32×32 的三遍盒式模糊一次约 **几十微秒**，而且**每首歌只算一次**（[CoverBlurCache]）。
 * 结果是：低端机上没有可测量的切歌卡顿，且不需要 API 31+（`RenderEffect` 那条路在
 * Android 7.0 上根本不存在 —— minSdk 是 24）。
 *
 * ## 为什么是盒式模糊而不是高斯
 *
 * 三遍盒式模糊在数学上已经非常接近高斯（中心极限定理），而它是**可分离 + 可用滑动窗口**
 * 的：单遍 O(n)（与半径**无关**），不像朴素高斯那样 O(n·r)。半径 12 时两者像素质量肉眼无差，
 * 代价差一个数量级。
 *
 * ## 为什么降采样用「盒式平均」而不是 `createScaledBitmap` 的双线性
 *
 * 双线性降采样在 1000→32（31 倍）时会**跳过绝大多数源像素**（只采样到 32×32=1024 个点，
 * 即 0.1% 的像素），画面上的结果是"背景颜色取决于是哪几个像素被抽到"——同一张封面上
 * 移动一个像素，背景色就可能跳变。盒式平均取的是每个目标像素覆盖的**全部**源像素的均值，
 * 稳定且不会丢色。代价只有一次 O(源像素) 遍历。
 */
object CoverBlur {

    /**
     * 降采样边长（px）。**32** 的依据：
     *  - 铁律 24 与任务书 §4.1 都点名 ~32px；
     *  - 32×32 = 1024 像素，三遍盒式模糊约 6 k 次整数加法（微秒级）；
     *  - 上采样到 1080p 是 34 倍，配合 [BLUR_RADIUS_PX] = 12 的预模糊，
     *    最终画面是「大色块 + 平滑过渡」，正是背景层需要的观感（背景**不该**有细节）。
     *
     * 不随屏幕分辨率变化：背景模糊是**观感参数**不是**分辨率参数**，
     * 让它跟着屏幕走只会让「同一首歌在高分屏上背景不一样」。
     */
    const val DOWNSAMPLE_PX: Int = 32

    /**
     * 盒式模糊半径（以 32px 网格为单位）。**12** 的依据：
     *  - 32px 网格上的半径 12 覆盖了 25px 直径，即背景四分之三的宽度 —— 细节被彻底抹掉；
     *  - 再大（≥16）会把整张图压成单色，背景与纯色背景没有区别，白花一次计算；
     *  - 再小（≤6）能看出封面构图（大色块边界），与前景封面重复、抢主视觉。
     */
    const val BLUR_RADIUS_PX: Int = 12

    /** 盒式模糊遍数：3 遍已非常接近高斯（中心极限定理），第 4 遍开始肉眼不可辨。 */
    const val BLUR_PASSES: Int = 3

    private const val A_SHIFT = 24
    private const val R_SHIFT = 16
    private const val G_SHIFT = 8
    private const val B_SHIFT = 0
    private const val CHANNEL_MASK = 0xFF

    /**
     * 盒式平均降采样：`srcW×srcH` 的 ARGB 像素 → `dst×dst`。
     *
     * **不做插值、不做伽马校正**：源是 sRGB 编码值，直接对编码值求平均会让结果略暗
     * （线性空间平均才是物理正确的）。这里有意接受：背景层是**装饰**，
     * 而做伽马校正要每像素两次 pow（1024 次 pow 在 S6 上约 0.5ms，不值得）。
     * 若将来发现浅色封面背景偏暗，改的是这里、不是别处。
     *
     * @throws IllegalArgumentException 源尺寸与数组长度不符（调用方应当已经保证）。
     */
    fun downsample(src: IntArray, srcW: Int, srcH: Int, dst: Int = DOWNSAMPLE_PX): IntArray {
        require(srcW > 0 && srcH > 0) { "srcW/srcH must be > 0" }
        require(src.size >= srcW * srcH) { "pixel array is smaller than srcW*srcH" }
        require(dst > 0) { "dst must be > 0" }
        val out = IntArray(dst * dst)
        // 每个目标像素覆盖的源区间 [x0, x1)。用整数边界，避免浮点累积误差。
        for (dy in 0 until dst) {
            val y0 = dy * srcH / dst
            val y1 = ((dy + 1) * srcH / dst).coerceAtLeast(y0 + 1).coerceAtMost(srcH)
            for (dx in 0 until dst) {
                val x0 = dx * srcW / dst
                val x1 = ((dx + 1) * srcW / dst).coerceAtLeast(x0 + 1).coerceAtMost(srcW)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (y in y0 until y1) {
                    val row = y * srcW
                    for (x in x0 until x1) {
                        val p = src[row + x]
                        a += (p ushr A_SHIFT) and CHANNEL_MASK
                        r += (p ushr R_SHIFT) and CHANNEL_MASK
                        g += (p ushr G_SHIFT) and CHANNEL_MASK
                        b += (p ushr B_SHIFT) and CHANNEL_MASK
                        n++
                    }
                }
                val count = if (n == 0) 1 else n
                out[dy * dst + dx] = ((a / count).toInt() shl A_SHIFT) or
                    ((r / count).toInt() shl R_SHIFT) or
                    ((g / count).toInt() shl G_SHIFT) or
                    ((b / count).toInt() shl B_SHIFT)
            }
        }
        return out
    }

    /**
     * 三遍可分离盒式模糊（滑动窗口，**单遍 O(n)，与半径无关**）。
     *
     * 边界处理用 **Clamp（重复边缘像素）**而不是补零：补零会在画面四边压出一圈暗边，
     * 而上采样到全屏之后那条暗边正好落在屏幕边缘，非常显眼。
     *
     * @param pixels 边长 `size` 的**方形** ARGB 像素（降采样之后就是 32×32）。
     * @return 新数组（不原地修改：调用方可能还需要原图，且原地改在单测里更难断言）。
     */
    fun blur(
        pixels: IntArray,
        size: Int,
        radius: Int = BLUR_RADIUS_PX,
        passes: Int = BLUR_PASSES,
    ): IntArray {
        require(size > 0) { "size must be > 0" }
        require(pixels.size >= size * size) { "pixel array is smaller than size*size" }
        if (radius <= 0 || passes <= 0) return pixels.copyOf(size * size)

        val current = pixels.copyOf(size * size)
        // 分离成 4 个通道分别做：整数运算、无浮点、循环内零分配（三个缓冲循环外建一次）。
        val channel = IntArray(size * size)
        val blurred = IntArray(size * size)
        val scratch = IntArray(size * size)
        val shifts = intArrayOf(A_SHIFT, R_SHIFT, G_SHIFT, B_SHIFT)
        repeat(passes) {
            for (shift in shifts) {
                for (i in 0 until size * size) {
                    channel[i] = (current[i] ushr shift) and CHANNEL_MASK
                }
                blurChannel(channel, blurred, scratch, size, radius)
                for (i in 0 until size * size) {
                    // 先清掉该通道的旧值，再或上新值。
                    current[i] = (current[i] and (CHANNEL_MASK shl shift).inv()) or (blurred[i] shl shift)
                }
            }
        }
        return current
    }

    /**
     * 单通道二维盒式模糊 = 横向一遍 + 纵向一遍（可分离）。
     * 两遍都用滑动窗口和，**每遍 O(size²)**，与 [radius] 无关。
     *
     * [scratch] 由调用方提供并复用 —— 本函数在模糊一张背景时会被调用 12 次
     * （4 通道 × 3 遍），每次新分配 4 KB 是没必要的。
     */
    private fun blurChannel(src: IntArray, dst: IntArray, scratch: IntArray, size: Int, radius: Int) {
        val r = radius.coerceAtMost(size - 1)
        val tmp = scratch
        val window = 2 * r + 1
        // 横向
        for (y in 0 until size) {
            val row = y * size
            var sum = 0
            for (k in -r..r) sum += src[row + k.coerceIn(0, size - 1)]
            for (x in 0 until size) {
                tmp[row + x] = sum / window
                val outIndex = (x - r).coerceIn(0, size - 1)
                val inIndex = (x + r + 1).coerceIn(0, size - 1)
                sum += src[row + inIndex] - src[row + outIndex]
            }
        }
        // 纵向
        for (x in 0 until size) {
            var sum = 0
            for (k in -r..r) sum += tmp[k.coerceIn(0, size - 1) * size + x]
            for (y in 0 until size) {
                dst[y * size + x] = sum / window
                val outIndex = (y - r).coerceIn(0, size - 1)
                val inIndex = (y + r + 1).coerceIn(0, size - 1)
                sum += tmp[inIndex * size + x] - tmp[outIndex * size + x]
            }
        }
    }

    /** 降采样 + 模糊一步到位（生产路径用的就是它）。 */
    fun downsampleAndBlur(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        dst: Int = DOWNSAMPLE_PX,
        radius: Int = BLUR_RADIUS_PX,
        passes: Int = BLUR_PASSES,
    ): IntArray = blur(downsample(src, srcW, srcH, dst), dst, radius, passes)
}

/**
 * v2.9.0 · A 档：模糊背景的**位图缓存**（Android 层，很薄）。
 *
 * ## 缓存键为什么是 URL 而不是 Bitmap
 *
 * 同一首歌的封面 URL 在会话内是稳定的（`CoverUrls.large`），而 `Bitmap` 实例每次
 * Coil 解码都可能不同（缓存被清、尺寸变化）。用 URL 作键才能表达「同一首歌不重复模糊」
 * 这条要求；用身份哈希作键会在每次重新解码后重算一遍。
 *
 * 缓存容量 [CACHE_ENTRIES] = **4**：够覆盖「当前 + 之前几首」（用户回退上一首不重算），
 * 又小到不可能成为内存问题（每张 32×32×4 字节 = 4 KB，4 张 = 16 KB）。
 *
 * ## 异常隔离（铁律 4）
 *
 * [blurred] **永不抛异常**：任何一步失败（OOM、recycle 过的 bitmap、宽高为 0、
 * 颜色空间不支持 `getPixels`）都返回 `null`，调用方据此回退纯色背景。
 * 背景层失败绝不允许影响播放或播放页的任何其它部分。
 */
object CoverBlurCache {

    /** 缓存条目数上限（每张 32×32 ARGB = 4 KB，4 张合计 16 KB）。 */
    const val CACHE_ENTRIES: Int = 4

    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    /** 单测/诊断：命中次数。 */
    @Volatile
    var hits: Int = 0
        private set

    /** 单测/诊断：未命中次数。 */
    @Volatile
    var misses: Int = 0
        private set

    /**
     * 取（或首次计算）某一首歌的模糊背景。
     *
     * **计算跑在 `Dispatchers.Default` 上**：`Bitmap.getPixels` 对一张 1000×1000 的封面
     * 要拷 4 MB，在 S6 上是**十几毫秒**量级 —— 放在组合/绘制线程上就是一次肉眼可见的掉帧。
     * 缓存命中时不切线程（直接返回，零代价）。
     *
     * @param key 缓存键：封面 URL。`null` 或空串 ⇒ 直接返回 `null`（不缓存、不计算）。
     * @param bitmap 当前封面位图。`null` ⇒ 返回 `null`（调用方回退纯色）。
     * @return 32×32 的模糊位图；失败一律 `null`。
     */
    suspend fun blurred(key: String?, bitmap: Bitmap?): ImageBitmap? {
        if (key.isNullOrBlank() || bitmap == null) return null
        cache.get(key)?.let {
            hits++
            return it
        }
        val result = withContext(Dispatchers.Default) {
            runCatching { compute(bitmap) }.getOrNull()
        } ?: return null
        misses++
        cache.put(key, result)
        return result
    }

    /** 只查缓存，不算（给「同一帧内多次取用」的调用点省一次哈希）。 */
    fun peek(key: String?): ImageBitmap? = if (key.isNullOrBlank()) null else cache.get(key)

    /** 单测用：清空缓存与计数。 */
    internal fun resetForTest() {
        cache.evictAll()
        hits = 0
        misses = 0
    }

    /**
     * 真正的计算：`Bitmap` → 32×32 降采样 → 三遍盒式模糊 → `ImageBitmap`。
     *
     * 全程只做一次 `getPixels`（一次 JNI 往返、一次源像素缓冲分配）。源图很大时
     * 这一个 `IntArray` 就是本方案唯一的大分配 —— 它在**每次切歌**发生一次，
     * 且立即被 GC 回收（不缓存源像素）。这是「不缓存中间态」的有意取舍：
     * 缓存 1000² 的像素数组（4 MB）比重新读一次像素贵得多。
     */
    private fun compute(bitmap: Bitmap): ImageBitmap {
        require(!bitmap.isRecycled) { "cover bitmap is recycled" }
        val w = bitmap.width
        val h = bitmap.height
        require(w > 0 && h > 0) { "cover bitmap has zero size" }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = CoverBlur.downsampleAndBlur(pixels, w, h)
        val size = CoverBlur.DOWNSAMPLE_PX
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, size, 0, 0, size, size)
        return result.asImageBitmap()
    }
}
