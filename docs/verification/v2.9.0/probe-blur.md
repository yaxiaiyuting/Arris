# v2.9.0 探针 · 背景模糊（降采样 + 盒式模糊 + URL 键缓存）

> **性质**：只读探针（**实现已由另一个人写好**，本文只做侦察与引用，不发明设计）。
> 本轮**没有**修改任何源文件、**没有**编译、**没有**跑单测、**没有**接真机。
> 所有结论要么是 `file:line` 引用 + 逐字原文，要么是「代码算式」的推导；**没有任何本轮实测的耗时数字**。
> **证据分级**：**【事实】** = `file:line` / 原文引用；**【估算】** = 源码 KDoc 自述量级或由算式推出的量级（**本轮未量化**）；**【未验证】** = 明确没确认。
> **配套**：`probe-ui-motion.md` §3.1/§4.3（模糊在 v2.8.0 基线上「零命中」+ 封面位图通路）、
> `probe-landscape-motion.md` §2（背景层插入点）、`probe-tier-migration.md`（档位与总开关）、`probe-perf-budget.md`（每帧预算）。

---

## 0. 结论速览

| # | 问题 | 答案 | 落点 |
|---|---|---|---|
| 1 | 为什么不直接模糊原图？ | 1000² 原图 = 4 MB 缓冲、3 遍可分离 = 6 次全缓冲遍历（源码自述在 S6 上是**几百毫秒量级**）；`RenderEffect` 虽然走 GPU 但作用在**全屏图层**上且**每帧**一遍，而背景**每首歌只变一次** | `CoverBlur.kt:21-40` |
| 2 | 降到多少？ | **32×32**（`DOWNSAMPLE_PX = 32`），不随屏幕分辨率变化 | `CoverBlur.kt:57-67` |
| 3 | 为什么不用 `createScaledBitmap` 的双线性？ | 1000→32 是 **31 倍**，双线性只采到 1024 个源像素 = **0.1%**，背景色会随抽样点跳变；盒式平均取每个目标像素覆盖的**全部**源像素均值 | `CoverBlur.kt:48-53` |
| 4 | 模糊怎么做的？ | 三遍可分离**盒式**模糊，滑动窗口**单遍 O(n) 与半径无关**，边界 **Clamp**，纯 Kotlin 整数运算 | `CoverBlur.kt:134-208` |
| 5 | API 24 怎么办？ | **不需要降级**：不依赖 `RenderEffect` / `RenderScript` / `Modifier.blur`，**所有 API 级别同一份实现**（全仓 grep 这三个 API 只在 KDoc 里出现） | `CoverBlur.kt:19`、`:27`、`:39`；§3.4 |
| 6 | 缓存键是什么？ | **封面 URL**（`CoverUrls.large`），不是 bitmap 身份哈希；`LruCache` **4 条** × **4 KB** = 16 KB | `CoverBlur.kt:222-244` |
| 7 | 缓存在哪算？ | 命中**同步零成本**；未命中在 `Dispatchers.Default`（`Bitmap.getPixels` 对 1000² 要拷 4 MB，源码自述「十几毫秒量级」） | `CoverBlur.kt:256-279` |
| 8 | 失败怎么办？ | `blurred()` **永不抛**，任何失败返回 `null` ⇒ 回退**纯色背景**；而纯函数层对**尺寸不匹配**抛 `IllegalArgumentException`（开发期错误） | `CoverBlur.kt:233-238`、`:96-99`、`:143-150` |
| 9 | 歌词可读性？ | 模糊图之上压一层主题背景色，`BACKDROP_SCRIM_ALPHA = 0.62` | `MotionBackdrop.kt:140-146`、`:203` |
| 10 | 单测覆盖？ | `CoverBlurTest` **13** 个用例（盒式平均 / Clamp / 恒等 / 不修改输入 / 夹取 / 一步到位 / 默认常量…） | `CoverBlurTest.kt`；§8 |

---

## 1. 为什么不对原图直接模糊（铁律 24）

```kotlin
// CoverBlur.kt:21-30（原文，节选）
 * ## 铁律 24：背景模糊必须用低分辨率降采样，不得对原图直接高斯模糊
 *
 * 一张 1000×1000 的封面做「原图高斯模糊」有两个代价，任何一个都足以否掉这个方案：
 *
 * 1. **CPU 侧**：1000²×4 字节 = 4 MB 的像素缓冲，3 遍可分离模糊 = 6 次全缓冲遍历。
 *    在 S6（Cortex-A57，2015）上是**几百毫秒**量级 —— 那是切歌时肉眼可见的一次卡顿；
 * 2. **GPU 侧**：`RenderEffect`（API 31+）虽然把开销丢给 GPU，但它作用在**全屏图层**上，
 *    每帧都要对一整屏像素做一遍模糊。而背景本身**每首歌只变一次** —— 每帧重算是纯浪费。
```

| 代价 | 量化 | 来源 |
|---|---|---|
| 源像素缓冲 | `1000 × 1000 × 4 B = 4,000,000 B ≈ 3.81 MiB`（源码记作「4 MB」） | `CoverBlur.kt:25` |
| 可分离模糊的缓冲遍历 | 3 遍 × 2 方向 = **6 次全缓冲遍历** | `CoverBlur.kt:25` |
| S6 上的耗时 | **几百毫秒量级**（**源码自述，非本轮实测** —— 本文不把它当测量结论） | `CoverBlur.kt:25-26` |
| GPU 路线的代价 | `RenderEffect` 需 **API 31+**，且作用在**全屏图层**上、**每帧**一遍；背景的实际变化频率是**每首歌一次** | `CoverBlur.kt:27-28` |

**本方案的顺序是倒过来的**（这是铁律 24 的全部内容）：

```kotlin
// CoverBlur.kt:30-40（原文，节选）
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
```

**【事实】一处需要读者知道的算式修正**：上表「≈ 6 k 次加法」按「3 遍 × 2 方向 × 1024 像素」计，
但实现是**逐通道**做的（4 个通道各跑一遍 `blurChannel`），所以一次模糊实际调用 `blurChannel`
**12 次**（`CoverBlur.kt:178-180` 的 KDoc 原文：「本函数在模糊一张背景时会被调用 12 次（4 通道 × 3 遍）」），
滑动窗口步数因此是 `3 遍 × 4 通道 × 2 方向 × 1024 ≈ 24.6 k`。量级仍是源码说的「几十微秒」，
但**「6 k」这个数字读的时候要乘 4**。（这是本文的**推导**，不是实测。）

**GPU 侧为什么否决 `RenderEffect`**（三条，全部有原文或代码依据）：

1. **API 门槛**：`RenderEffect` 需要 **API 31+**（`CoverBlur.kt:27`），而本 app `minSdk = 24`（AGENTS.md 工具链表）；
   S6 是 **API 24 / Android 7.0**（HARD FACT）⇒ 那条路上**根本不存在这个 API**。
2. **频率不匹配**：背景每首歌变一次；`RenderEffect` 挂在图层上则每帧付一次（`CoverBlur.kt:27-28`）。
3. **可断言性**：预模糊 + URL 键缓存让「同一首歌不重复计算」成为一个**可以断言的事实**
   （`CoverBlurCache.hits/misses`，`CoverBlur.kt:246-254`），而「每帧重算」永远无法这样断言。

---

## 2. 降采样方案

### 2.1 为什么是 32px

```kotlin
// CoverBlur.kt:57-67（原文）
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
```

配套两个常量：

| 常量 | 值 | 依据（原文） | 行号 |
|---|---|---|---|
| `DOWNSAMPLE_PX` | `32` | 见上 | `:67` |
| `BLUR_RADIUS_PX` | `12` | 「32px 网格上的半径 12 覆盖了 25px 直径，即背景四分之三的宽度 —— 细节被彻底抹掉」；≥16 压成单色、≤6 能看出封面构图会抢主视觉 | `:69-75` |
| `BLUR_PASSES` | `3` | 「3 遍已非常接近高斯（中心极限定理），第 4 遍开始肉眼不可辨」 | `:77-78` |

### 2.2 为什么用**盒式平均**而不是 `createScaledBitmap` 的双线性

```kotlin
// CoverBlur.kt:48-53（原文）
 * ## 为什么降采样用「盒式平均」而不是 `createScaledBitmap` 的双线性
 *
 * 双线性降采样在 1000→32（31 倍）时会**跳过绝大多数源像素**（只采样到 32×32=1024 个点，
 * 即 0.1% 的像素），画面上的结果是"背景颜色取决于是哪几个像素被抽到"——同一张封面上
 * 移动一个像素，背景色就可能跳变。盒式平均取的是每个目标像素覆盖的**全部**源像素的均值，
 * 稳定且不会丢色。代价只有一次 O(源像素) 遍历。
```

算式核对（**【推导】**）：

| 量 | 值 |
|---|---|
| 缩放倍数 | `1000 / 32 = 31.25`（源码记作「31 倍」） |
| 双线性实际采到的源像素 | `32 × 32 = 1024` |
| 占源像素比例 | `1024 / 1,000,000 = 0.1024% ≈ 0.1%`（与源码一致） |
| 盒式平均覆盖 | 每个目标像素覆盖 `≈31.25 × 31.25 ≈ 977` 个源像素，**全部**参与均值 |

### 2.3 盒式平均的实现（整数、无插值、无伽马）

```kotlin
// CoverBlur.kt:96-132（原文，节选）
fun downsample(src: IntArray, srcW: Int, srcH: Int, dst: Int = DOWNSAMPLE_PX): IntArray {
    require(srcW > 0 && srcH > 0) { "srcW/srcH must be > 0" }
    require(src.size >= srcW * srcH) { "pixel array is smaller than srcW*srcH" }
    require(dst > 0) { "dst must be > 0" }
    val out = IntArray(dst * dst)
    // 每个目标像素覆盖的源区间 [x0, x1)。用整数边界，避免浮点累积误差。
    for (dy in 0 until dst) {
        val y0 = dy * srcH / dst
        val y1 = ((dy + 1) * srcH / dst).coerceAtLeast(y0 + 1).coerceAtMost(srcH)
        …
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                …  // 四个通道各自用 Long 累加，p ushr 位偏移 + 0xFF 掩码
                val count = if (n == 0) 1 else n
                out[dy * dst + dx] = ((a / count).toInt() shl A_SHIFT) or …
```

- **整数边界**：`dy * srcH / dst` 与 `(dy+1) * srcH / dst`，注释明说「避免浮点累积误差」（`:101`）；同时用
  `coerceAtLeast(y0 + 1)` 保证**源比目标小时**每个目标像素至少覆盖一个源像素（`:104`/`:107`）；
- **四通道独立**、`Long` 累加、`0xFF` 掩码（`:108-123`）；`n == 0` 的除零保护（`:124`）；
- **有意不做伽马校正**（`:86-95` 原文）：「直接对编码值求平均会让结果略暗（线性空间平均才是物理正确的）。
  这里有意接受：背景层是**装饰**，而做伽马校正要每像素两次 pow（1024 次 pow 在 S6 上约 0.5ms，不值得）。
  若将来发现浅色封面背景偏暗，**改的是这里、不是别处**。」

---

## 3. 模糊实现

### 3.1 三遍可分离盒式模糊

```kotlin
// CoverBlur.kt:134-172（原文，节选）
 * 三遍可分离盒式模糊（滑动窗口，**单遍 O(n)，与半径无关**）。
 *
 * 边界处理用 **Clamp（重复边缘像素）**而不是补零：补零会在画面四边压出一圈暗边，
 * 而上采样到全屏之后那条暗边正好落在屏幕边缘，非常显眼。
fun blur(pixels: IntArray, size: Int, radius: Int = BLUR_RADIUS_PX, passes: Int = BLUR_PASSES): IntArray {
    require(size > 0) { "size must be > 0" }
    require(pixels.size >= size * size) { "pixel array is smaller than size*size" }
    if (radius <= 0 || passes <= 0) return pixels.copyOf(size * size)

    val current = pixels.copyOf(size * size)
    // 分离成 4 个通道分别做：整数运算、无浮点、循环内零分配（三个缓冲循环外建一次）。
    val channel = IntArray(size * size); val blurred = IntArray(size * size); val scratch = IntArray(size * size)
    val shifts = intArrayOf(A_SHIFT, R_SHIFT, G_SHIFT, B_SHIFT)
    repeat(passes) {
        for (shift in shifts) {
            for (i in 0 until size * size) channel[i] = (current[i] ushr shift) and CHANNEL_MASK
            blurChannel(channel, blurred, scratch, size, radius)
            for (i in 0 until size * size) {
                // 先清掉该通道的旧值，再或上新值。
                current[i] = (current[i] and (CHANNEL_MASK shl shift).inv()) or (blurred[i] shl shift)
            }
        }
    }
    return current
}
```

| 性质 | 实现 | 行号 |
|---|---|---|
| 滑动窗口 | `sum += src[in] - src[out]`，每个输出像素 O(1) 更新 ⇒ 单遍 **O(size²)**、**与半径无关** | `:190-195`、`:201-206` |
| 可分离 | 横向一遍写 `scratch`，纵向一遍读 `scratch` 写 `dst` | `:186-196` / `:198-207` |
| 边界 **Clamp** | 三处 `coerceIn(0, size - 1)`：窗口初始化（`:189`/`:200`）、滑出（`:192`/`:203`）、滑入（`:193`/`:204`） | 同上 |
| 半径夹取 | `val r = radius.coerceAtMost(size - 1)` —— 半径 ≥ 边长时不越界（单测 `半径大于边长时被夹取而不是越界`，`CoverBlurTest.kt:152`） | `:182` |
| 半径或遍数为 0 | 恒等：`return pixels.copyOf(size * size)`（**不原地改**，与「关掉就是没功能」一致） | `:151` |
| 不改输入 | 先 `copyOf` 再算，返回新数组（注释：「调用方可能还需要原图，且原地改在单测里更难断言」） | `:141`、`:153` |
| 零分配 | 三个 `IntArray` 在**循环外**建一次；`blurChannel` 的 `scratch` 由调用方复用（「每次新分配 4 KB 是没必要的」，`:178-180`） | `:155-157` |
| 一步到位 | `downsampleAndBlur(...)`（生产路径用的就是它） | `:210-218` |

### 3.2 为什么盒式而不是高斯

> 三遍盒式模糊在数学上已经非常接近高斯（中心极限定理），而它是**可分离 + 可用滑动窗口**
> 的：单遍 O(n)（与半径**无关**），不像朴素高斯那样 O(n·r)。半径 12 时两者像素质量肉眼无差，
> 代价差一个数量级。 —— `CoverBlur.kt:42-46`

### 3.3 Android 侧只剩「取像素 → 算 → 建位图」

```kotlin
// CoverBlur.kt:299-311（原文，节选）
private fun compute(bitmap: Bitmap): ImageBitmap {
    require(!bitmap.isRecycled) { "cover bitmap is recycled" }
    val w = bitmap.width; val h = bitmap.height
    require(w > 0 && h > 0) { "cover bitmap has zero size" }
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = CoverBlur.downsampleAndBlur(pixels, w, h)
    val size = CoverBlur.DOWNSAMPLE_PX
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, size, 0, 0, size, size)
    return result.asImageBitmap()
}
```

源像素缓冲是**本方案唯一的大分配**（`IntArray(w*h)`，1000² = 4 MB），
源码明确写了取舍：「它在**每次切歌**发生一次，且立即被 GC 回收（不缓存源像素）。
这是「不缓存中间态」的有意取舍：缓存 1000² 的像素数组（4 MB）比重新读一次像素贵得多」（`:294-297`）。

### 3.4 「API 24~30 不需要降级」—— 这是对任务书 §2.3 的**加强**

全仓 grep（`app/src/main/java/**/*.kt`）对 `Modifier.blur` / `RenderEffect` / `RenderScript` 的结果：
**只有 `CoverBlur.kt:27` 与 `:39` 两处**，且都在 **KDoc 注释里**（解释为什么**不用**它们）——
**没有任何一处代码调用**。这与 v2.8.0 基线的侦察结论一致（`probe-ui-motion.md:276-286`：「这个仓库从来没有过模糊」）。

因此：

| 任务书原本的预期 | 实际情况 |
|---|---|
| §2.3「低版本（API 24~30）降级方案」 | **不存在降级分支**：纯 Kotlin 整数运算，与 `Build.VERSION.SDK_INT` **完全无关**；API 24（S6）与 API 36（PCL110）跑的是**同一份代码、同一条路径** |
| 低版本观感会不同 | 不会：唯一与设备相关的量是封面位图本身的尺寸（`w × h`），而它只影响**一次**降采样的耗时，不影响输出（输出恒为 32×32） |

**【事实】这条要在 release note 里写成「不是降级，而是全版本同一实现」**，否则读者会以为低版本少了什么。

---

## 4. 缓存策略

### 4.1 键为什么是 URL

```kotlin
// CoverBlur.kt:224-232（原文，节选）
 * ## 缓存键为什么是 URL 而不是 Bitmap
 *
 * 同一首歌的封面 URL 在会话内是稳定的（`CoverUrls.large`），而 `Bitmap` 实例每次
 * Coil 解码都可能不同（缓存被清、尺寸变化）。用 URL 作键才能表达「同一首歌不重复模糊」
 * 这条要求；用身份哈希作键会在每次重新解码后重算一遍。
 *
 * 缓存容量 [CACHE_ENTRIES] = **4**：够覆盖「当前 + 之前几首」（用户回退上一首不重算），
 * 又小到不可能成为内存问题（每张 32×32×4 字节 = 4 KB，4 张 = 16 KB）。
 */
```

| 项 | 值 | 依据 |
|---|---|---|
| 键 | 封面 URL（由 `StableCover` 上抛，`PlayerCard.kt:1748-1755`；键就是 `CoverUrls.large(...)` 的 model，`PlayerCard.kt:1741`） | `CoverBlur.kt:224-228` |
| 容量 | `CACHE_ENTRIES = 4` | `:242` |
| 单条内存 | `32 × 32 × 4 B = 4096 B = 4 KB`；4 条 = **16 KB** | `:231`、`:241` |
| 实现 | `LruCache<String, ImageBitmap>(CACHE_ENTRIES)` | `:244` |
| 可观测 | `hits` / `misses`（`@Volatile`，私有 setter） | `:246-254` |
| 只查不算 | `peek(key)`：「给「同一帧内多次取用」的调用点省一次哈希」 | `:281-282` |

### 4.2 命中零成本 / 未命中在 `Dispatchers.Default`

```kotlin
// CoverBlur.kt:256-279（原文，节选）
 * **计算跑在 `Dispatchers.Default` 上**：`Bitmap.getPixels` 对一张 1000×1000 的封面
 * 要拷 4 MB，在 S6 上是**十几毫秒**量级 —— 放在组合/绘制线程上就是一次肉眼可见的掉帧。
 * 缓存命中时不切线程（直接返回，零代价）。
suspend fun blurred(key: String?, bitmap: Bitmap?): ImageBitmap? {
    if (key.isNullOrBlank() || bitmap == null) return null
    cache.get(key)?.let { hits++; return it }          // ★ 命中：不切线程
    val result = withContext(Dispatchers.Default) {    // ★ 未命中：Default 线程
        runCatching { compute(bitmap) }.getOrNull()
    } ?: return null
    misses++
    cache.put(key, result)
    return result
}
```

调用点的写法（**先同步查缓存，再走 suspend**，避免已算过的歌还切一次协程）：

```kotlin
// MotionBackdrop.kt:88-96（原文，节选）
LaunchedEffect(coverKey, coverBitmap) {
    if (!motion.backgroundBlur) { blurred = null; return@LaunchedEffect }
    // 缓存命中是同步的（同一条链路上切歌回来不重算）；未命中在 Default 线程上算。
    blurred = CoverBlurCache.peek(coverKey)
        ?: runCatching { CoverBlurCache.blurred(coverKey, coverBitmap) }.getOrNull()
}
```

**「十几毫秒量级」是源码自述的【估算】，不是本轮实测**；可确定的是它的**数量级依据**：
1000² 像素 = 1,000,000 个 `Int` = 4 MB 的一次性拷贝（`getPixels`）+ 一个 4 MB 的 `IntArray` 分配。
放在组合/绘制线程上，这 4 MB 的拷贝与随之而来的 GC 压力就是一次可见掉帧 —— 这是把它挪到
`Dispatchers.Default` 的全部理由。

---

## 5. 为什么不用 API 31+ 的 `Modifier.blur` / `RenderEffect`

逐条给理由（都可核对）：

| # | 理由 | 依据 |
|---|---|---|
| 1 | **每帧全屏重算 vs 每首歌一次**：`RenderEffect` 作用在图层上，图层每帧都要合成一遍；而背景的语义是「每首歌变一次」 | `CoverBlur.kt:27-28` |
| 2 | **低版本上是无操作/降级**，会造成「同一份代码在两代设备上观感不同」：`Modifier.blur` 在 API < 31 是 no-op、`RenderEffect` 需要 API 31+，而 `minSdk = 24` | `probe-ui-motion.md:724-726`（既有探针结论）；`CoverBlur.kt:39-40` |
| 3 | **「同一首歌不重复计算」要成为可断言的事实**：预模糊 + URL 键缓存给出了 `hits` / `misses` 两个计数器与 `CACHE_ENTRIES = 4` 的硬上界；`RenderEffect` 路线没有任何等价的断言面 | `CoverBlur.kt:246-254`、`:242` |
| 4 | **minSdk 24 上这条路根本不存在**（S6 = API 24 / Android 7.0，HARD FACT）⇒ 若走 GPU 路线，本版必须**同时**维护两条渲染路径，而两条路径的观感一致性无法在本轮的设备矩阵上证伪 | `CoverBlur.kt:39-40` |

---

## 6. 异常隔离：两层，判据不同

### 6.1 运行时失败面 ⇒ `null` ⇒ 回退纯色

```kotlin
// CoverBlur.kt:233-238（原文）
 * ## 异常隔离（铁律 4）
 *
 * [blurred] **永不抛异常**：任何一步失败（OOM、recycle 过的 bitmap、宽高为 0、
 * 颜色空间不支持 `getPixels`）都返回 `null`，调用方据此回退纯色背景。
 * 背景层失败绝不允许影响播放或播放页的任何其它部分。
```

具体有三个隔离点：

| 位置 | 隔离手段 | 行号 |
|---|---|---|
| `blurred()` 的计算体 | `runCatching { compute(bitmap) }.getOrNull()` ⇒ 失败返回 `null`（**注意：这里 `misses` 不加**，因为没写缓存） | `:273-275` |
| 调用点 | `runCatching { CoverBlurCache.blurred(...) }.getOrNull()`（第二层兜底，防 `suspend` 边界上的意外） | `MotionBackdrop.kt:95` |
| `MotionBackdrop` 的背景铺陈 | **先铺主题背景色**，模糊图**叠在它上面** ⇒ 模糊为 `null` 时观感 = v2.8.0 的纯色背景 | `MotionBackdrop.kt:98-104` |

```kotlin
// MotionBackdrop.kt:98-104（原文，节选）
Box(modifier.fillMaxSize()) {
    // 底色：无论模糊是否可用都先铺主题背景色。它同时是「模糊失败的回退」与
    // 「模糊图层之下的不透明底」——后者保证 alpha 呼吸不会透出更下一层的内容。
    Box(Modifier.fillMaxSize().background(fallback))

    val image = blurred
    if (image != null && motion.anyBasic) {
```

`compute()` 内部的四个 `require`（recycled / 宽高为 0，`:300`/`:303`）**抛出的异常会被 `runCatching` 吞掉**，
所以在生产路径上它们是「回退纯色」的触发器，而不是崩溃点。

### 6.2 编程错误 ⇒ **抛 `IllegalArgumentException`**

纯函数层（`downsample` / `blur`）的 `require` **不吞**：尺寸与数组长度不匹配是**开发期错误**。

```kotlin
// CoverBlur.kt:96-99 / :149-150（原文）
require(srcW > 0 && srcH > 0) { "srcW/srcH must be > 0" }
require(src.size >= srcW * srcH) { "pixel array is smaller than srcW*srcH" }
require(dst > 0) { "dst must be > 0" }
…
require(size > 0) { "size must be > 0" }
require(pixels.size >= size * size) { "pixel array is smaller than size*size" }
```

**这个分界必须写清楚**（本文的核心结论之一）：

| 层 | 失败类型 | 行为 | 理由 |
|---|---|---|---|
| `CoverBlur.downsample` / `CoverBlur.blur`（纯函数） | **编程错误**（尺寸不匹配、数组过小） | **抛 `IllegalArgumentException`** | 「应当炸在开发期」（`CoverBlurTest.kt:24-25` 的测试 KDoc 原文） |
| `CoverBlurCache.blurred` / `compute`（运行时） | OOM / recycled bitmap / 宽高 0 / 颜色空间不支持 `getPixels` | **返回 `null`** | 背景层失败**绝不允许**影响播放或播放页其它部分（`CoverBlur.kt:237-238`） |
| `MotionBackdrop`（UI） | 位图拿不到 | 纯色背景 | 与 v2.8.0 观感完全一致（`CoverBlur.kt:57-58`） |

---

## 7. 可读性遮罩

模糊图之上**必须**压一层主题背景色：

```kotlin
// MotionBackdrop.kt:140-146（原文）
// 可读性遮罩（见 KDoc 第 2 条）。比例 0.62 是「能看出封面颜色、但文字对比度
// 与纯色背景同档」的折中；两个主题各自用各自的背景色，所以深浅色都不会跑偏。
Box(
    Modifier
        .fillMaxSize()
        .background(fallback.copy(alpha = BACKDROP_SCRIM_ALPHA))
)
```

```kotlin
// MotionBackdrop.kt:203（原文）
private const val BACKDROP_SCRIM_ALPHA = 0.62f
```

- **依据是任务书 §7.3「歌词可读性优先」**，源码 KDoc 把它列为三条硬约束之一：
  「模糊图之上**必须**压一层主题背景色的半透明遮罩。不压的后果是浅色封面把歌词冲得读不出来
  —— 那是功能事故，不是审美问题」（`MotionBackdrop.kt:55-56`）。
- 遮罩**只在有模糊图时画**（在 `if (image != null && motion.anyBasic)` 块内，`:104-147`）：
  纯色回退时背景本来就是主题背景色，不需要再叠一层。
- **C 档的粒子/光晕画在遮罩之上**（注释原文：「画在遮罩之上，才看得见」，`:149`）；
  它们的不透明度另有上限（`HALO_MAX_ALPHA = 0.35f`、`PARTICLE_MAX_ALPHA = 0.5f`，`:212`/`:218`）。
- 上采样用 `FilterQuality.High`（三次插值），理由写在代码里：双线性在 40 倍放大下会留下
  「可见的菱形刻面（比「糊」更难看）」，而这一层每帧只有一次纹理采样（`MotionBackdrop.kt:134-137`）。

> **【事实】一处 KDoc 复制粘贴残留（不影响行为，但会误导读文档的人）**：
> `BACKDROP_SCRIM_ALPHA` 上方的 KDoc 写的是「**背景级波形**的着色遮罩比例 … 0.22 的依据 …
> 在 OLED 黑底上 22% 的主题色已经能明确读出「有东西在动」」（`MotionBackdrop.kt:196-203`），
> 但常量本身是 `0.62f` 且实际用途是**背景模糊的可读性遮罩**。
> 背景级波形真正用的是 `PlayerCard.kt:1859` 的 `private const val WAVE_BACKDROP_ALPHA = 0.32f`。
> 也就是说：**同一段 KDoc 里的「0.22」既不等于 0.62、也不等于 0.32，三个数字互不相干**。
> 行为正确（代码用的是 0.62/0.32），但注释需要修 —— 这是本轮侦察发现的一处文档缺陷。

---

## 8. 单测覆盖（`CoverBlurTest`，13 个用例）

| # | 用例名（逐字） | 行号 | 断言什么 |
|---|---|---|---|
| 1 | `降采样是盒式平均而不是抽样` | `:39` | 4×4「左半黑右半白」→ 2×2：左列 `red == 0`、右列 `red == 255`（抽样式会得到同样的两端，但 KDoc 说明的中间值路径由 #12 覆盖） |
| 2 | `降采样保留透明度与三通道` | `:55` | `argb(128,10,20,30)` → 1×1 后 A=128/R=10/G=20/B=30 逐通道不变 |
| 3 | `源比目标小的时候不会崩也不会越界` | `:66` | 1×1 → 32×32：输出 1024 个像素**全部**等于那一个源像素（依赖 `coerceAtLeast(x0+1)`） |
| 4 | `源尺寸为零时抛异常` | `:74` | `downsample(IntArray(0), 0, 0, dst = 4)` 抛 `IllegalArgumentException` |
| 5 | `像素数组比声明的尺寸小时抛异常` | `:79` | `downsample(IntArray(3), 4, 4, dst = 2)` 抛 `IllegalArgumentException` |
| 6 | `模糊抹平细节` | `:99` | 32×32 棋盘格：`roughness`（相邻红通道平均绝对差）模糊后 `< before / 4` |
| 7 | `多遍模糊比单遍更平滑` | `:113` | `passes = 3` 的 roughness **严格小于** `passes = 1` |
| 8 | `边界用 Clamp 不会把四角压暗` | `:126` | 纯白图模糊后**仍然是纯白**（补零会在四边压出暗边） |
| 9 | `半径为零或遍数为零时是恒等变换` | `:135` | `radius = 0` 与 `passes = 0` 的输出与输入**逐元素相等**（`assertArrayEqualsExact`，`:184-189`） |
| 10 | `模糊不修改输入数组` | `:143` | 先 `copyOf` 备份，模糊后输入数组逐元素不变 |
| 11 | `半径大于边长时被夹取而不是越界` | `:152` | `radius = 999` + 4×4 纯色 ⇒ 输出仍是 4×4 且颜色不变（依赖 `coerceAtMost(size - 1)`） |
| 12 | `降采样加模糊一步到位` | `:161` | 64×64 左红右蓝 → `downsampleAndBlur`：输出 32×32、左半偏红、右半偏蓝、**两边不相等**（防止模糊把画面压成单色） |
| 13 | `默认常量与任务书一致` | `:177` | `DOWNSAMPLE_PX == 32`、`BLUR_RADIUS_PX > 0`、`BLUR_PASSES >= 2`、`CoverBlurCache.CACHE_ENTRIES == 4` |

**覆盖不到的（诚实列出）**：

1. **`CoverBlurCache` 的 LRU / hits / misses / `peek` 没有任何单测**：`blurred()` 需要 `Bitmap` 与
   `Dispatchers.Default`，`LruCache` 是 `android.util` 类（JVM 单测下 `isReturnDefaultValues = true`
   只抹平返回值，`LruCache` 的真实 LRU 语义**测不到**）。**【未验证】**：容量 4 的淘汰顺序、
   重复取用同一 URL 时 `hits` 是否真的自增，本轮没有测试覆盖。
2. **`compute()` 与 `MotionBackdrop` 的 Android 路径无单测**（需要设备的 `Bitmap`）。
3. **伽马校正的观感**（`CoverBlur.kt:86-95` 承认会让结果略暗）**未验证**：没有在浅色封面上核对过背景是否偏暗。
4. **32px / 半径 12 / 3 遍这组参数的实际观感未验证**：三个常量都有 KDoc 依据，但**没有任何截图证据**
   （本轮无设备；S6 与 PCL110 都连着，但探针阶段不驱动它们）。

---

## 9. 未验证清单

1. **没有真机耗时**：`getPixels` 的「十几毫秒」、模糊的「几十微秒」都是**源码 KDoc 自述**，
   本轮**没有测过**（没有 systrace / Perfetto 切片）。要取证需要 `atrace` 或一个 JVM 微基准
   （后者在 `./gradlew test` 里可做，但 `CoverBlur` 是纯 Kotlin，**这是最省的一条取证路径**）。
2. **没有截图**：模糊背景、遮罩 0.62、`FilterQuality.High` 的观感、浅色封面是否偏暗，全部未取图。
3. **切歌时的实际卡顿未测**：`CoverBlurCache.blurred` 在 `Dispatchers.Default` 上算的**同时**，
   主线程正在做 `StableCover` 的切歌淡入（`AppMotion.coverFade = tween(400)`）——
   两者是否会在低端机上叠加出可见掉帧，本轮**未测**（HARD FACT 里 S6 的 P50 已经是 20 ms）。
4. **WGR-W09 无法参与**：该设备**当前锁屏、休眠且无 root**（HARD FACT）⇒ API 31 上的
   「如果改用 `RenderEffect` 会怎样」这类对照**做不了**（本实现也刻意不做这条对照）。
5. **`CoverBlurCache` 的容量 4 是否够**：只有 KDoc 的论证（「够覆盖当前 + 之前几首」），
   没有统计过真实用户的回退距离分布。
