# Compose 粒子系统：帧时钟驱动 + 每帧零分配（参考研究）

> 目标：为 Ncrust（GPLv3 fork，minSdk 24 / Android 7.0，Compose BOM 2024.12.01 = compose-ui 1.7.6，
> Compose compiler ext 1.5.14，Kotlin 1.9.24，**无 androidx.compose.material3**）回答一个问题：
> **怎样在 Jetpack Compose 里做一个由帧时钟驱动、稳态每帧零分配的粒子系统。**
>
> 本文的每条外部事实都带 URL；**凡本轮没有拿到一手证据的，一律标「未核实」**，不做推断式断言。
> 涉及 Compose API 的签名与 API 等级，凡标「实测」者均为本轮在**本机编译产物 / SDK 数据**上直接读出：
> `~/.gradle/caches/.../androidx.compose.ui/ui-graphics-android/1.7.6/ui-graphics-release.aar`、
> `ui-android/1.7.6`、`ui-geometry-android/1.7.6`（`javap`），以及
> `$ANDROID_HOME/platforms/android-36/data/api-versions.xml`（Android SDK 自带的 API 等级数据库）。
>
> 与 Ncrust 现状的关系：本 fork 已有 `MotionClock`（唯一帧时钟）、`MotionBackdropState`（定长池粒子）、
> `VisualizerFrameMonitor`（`FrameMetrics` 监控）三件套，本文的推荐方案是在它们之上补齐，而不是另起一套。

---

## 0. 结论摘要（TL;DR）

| 议题 | 结论 | 关键证据 |
|---|---|---|
| 数据布局 | **SoA（并行 `FloatArray`）+ 定长容量 + `liveCount` 前缀不变量 + swap-remove**；不要 `List<Particle>` 数据类 | §1 |
| 单粒子绘制 | `DrawScope.drawCircle(color, radius, center, alpha)` —— 形参经 value class 脱糖后**全是原始类型，零装箱零分配** | §2.1（实测） |
| `drawPoints` | **在 Compose 1.7.6 上不要用于大量粒子**：形参是 `List<Offset>`（`Offset` 是 value class，进 List 必然装箱），且 Android 实现是**逐点** `nativeCanvas.drawPoint()`，没有批处理 | §2.3（实测 + 源码） |
| Brush | `Brush.radialGradient(...)` 写在 `Canvas { }`/`drawBehind` 的 draw lambda 里 = **每个粒子每帧一个新 Brush**；正确做法是 `remember` 或 **`Modifier.drawWithCache`** 建一次 | §2.2 |
| 帧时钟 | `withFrameNanos` 只用来推进**动画相位**；它的 delta **不能**当帧时长用（KDoc 明说可能是"目标帧时间"而非"现在"） | §3.1–3.2 |
| 帧时长测量 | `Window.addOnFrameMetricsAvailableListener` + `FrameMetrics`，**API 24**，正好等于 minSdk | §3.3（`api-versions.xml` 实测） |
| 随机性 | 自己写 6 行 xorshift32（状态存 `Int`/`Long`），**单测锁死序列**；`kotlin.random.Random` 不是不能用，但确定性契约弱、且 `java.util.Random` 每次取值一次 CAS | §4 |
| 低端 GPU | 大半径半透明圆的代价 ∝ r² × 数量（填充率 + overdraw）；官方经验值是**每帧不超过屏幕像素的 2.5 倍** | §5 |
| 可借鉴实现 | Konfetti(ISC) 与 Quarks(Apache-2.0) 的**配置模型 + dt 驱动契约**可借鉴；两者的**每帧对象分配**不可借鉴 | §6 |

---

## 1. 对象池（object pool）模式

### 1.1 固定容量 + alive 标志 + 空闲表

最小形态：**实例创建时一次性分配** N 个槽位，之后**永不扩容、永不 new**。

```
// 实例级（构造/remember 时各一次）——不是每帧
private val px   = FloatArray(CAP)   // 位置 x（归一化 0..1）
private val py   = FloatArray(CAP)
private val vx   = FloatArray(CAP)
private val vy   = FloatArray(CAP)
private val life = FloatArray(CAP)   // 1.0 → 0.0
private val seed = IntArray(CAP)     // 每粒子自己的随机相位（可选）
```

槽位状态的两种存法：

| 方案 | 结构 | 优点 | 代价 |
|---|---|---|---|
| **alive 标志 + 空闲表** | `alive: BooleanArray(CAP)` + 空闲槽栈 `IntArray(CAP)` + `freeTop: Int` | **槽位下标稳定**：粒子的"身份"= 下标，跨帧可被外部引用（尾迹、GPU 缓冲、上一帧位置） | 每帧遍历要跳过死槽（`if (alive[i])`），或额外维护紧凑列表；`alive` 是并列数组，多一次访存 |
| **swap-remove 紧凑前缀** | 只维护 `liveCount: Int`；不变量：`[0, liveCount)` 全活 | 遍历是**连续前缀**，无分支、可向量化；不需要 `alive` 数组 | **槽位下标不稳定**：任何持有"粒子下标"的外部引用都会指错（见 §1.3 警告） |

Ncrust 的 `MotionBackdropState` 目前用的是第三种：**游标式定长池**（`particleCursor` 环形复用 +
`life` 归零表示死亡，见 `app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionEnvelope.kt:206-211`）。
它的好处是"槽位稳定 + 不需要空闲表"，代价是**新粒子会覆盖尚未死亡的旧粒子**（在固定容量下这是有意的取舍：
粒子数超过容量时牺牲最老的而不是丢弃新的）。

> 引用（本仓库内部证据，非外部规范）：
> `app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionEnvelope.kt`、
> `.../MotionBackdrop.kt:198-210`（draw 阶段按 `particleCapacity` 遍历并 `drawCircle`）。

### 1.2 为什么 SoA 的 `FloatArray` 胜过 `List<Particle>` 数据类

这不是风格问题，是三笔可算的账：

1. **分配账**。`data class Particle(var x: Float, var y: Float, …)` 的每个实例都是**堆对象**
   （对象头 + 字段 + 8 字节对齐）。一个 6 个 Float 字段的粒子 ≈ 16B 头 + 24B 载荷 → 对齐后约 40–48B，
   再算上 `ArrayList` 的元素引用数组（每元素 4–8B）。60fps × 200 粒子 = **每秒 1.2 万次分配**，
   而硬件加速官方文档对这类写法的判词是：
   > "A common mistake is to create a new `Paint` or a new `Path` every time a rendering method is invoked.
   > **This forces the garbage collector to run more often** and also bypasses caches and optimizations in
   > the hardware pipeline."
   > — [Hardware acceleration · developer.android.com](https://developer.android.com/guide/topics/graphics/hardware-accel)（"Don't create render objects in draw methods" 一节）
2. **访存账**。SoA 下位置、速度、寿命各自连续：更新循环只碰 3–4 个数组，`CAP=64` 时总共约 1KB，
   L1 装得下。`List<Particle>` 是**指针追逐**：每个粒子一次潜在 cache miss。
   > 「SoA 比 AoS 更缓存友好」属于计算机体系结构通识；本轮**未**引用具体论文/文档逐字核对 —— 标 **未核实**
   > （但 §1.2 结论不依赖它：即使缓存行为相同，分配账与 GC 账已经足以定案）。
3. **不可变性陷阱**。既然叫"数据类"，最常见的用法是**不可变更新**：`particles[i] = particles[i].copy(y = y + vy*dt)`。
   这**每帧每粒子一次分配**，比可变对象更糟。若一定要用对象，就必须改成 `var` 字段原地修改，
   那也就放弃了 data class 的全部好处（`equals`/`copy`/解构在这个场景里全用不上）。

**顺带**：`Offset` 本身是 `@JvmInline value class`（载荷是一个 `Long`）。它**不做泛型实参**时（比如
`drawCircle(center = Offset(x, y))`）会被脱糖成 `long`，**不分配**；但**一旦进 `List<Offset>` 就必须装箱**。
本轮实测（`javap`）：`androidx.compose.ui.geometry.Offset` 确实存在 `box-impl(long)` 与 `unbox-impl()`，
即"装箱形态"在字节码层面真实存在 —— 这是 §2.3 结论的基础。

### 1.3 swap-remove：O(1) 删除

**做法**（删除下标 `i`）：

```
liveCount--                       // 先缩
if (i != liveCount) {             // 若删的不是最后一个
    // 把"最后一个活粒子"整体搬到 i 上（所有并行数组同步搬）
    px[i] = px[liveCount]; py[i] = py[liveCount]
    vx[i] = vx[liveCount]; vy[i] = vy[liveCount]
    life[i] = life[liveCount]
    // 若粒子还有整型/引用字段，一并搬
}
// 槽位 liveCount 之后的内容视为垃圾，不必清理（下次会被覆盖）
```

**为什么是 O(1)**：只做常数次数组读写，没有移动整段（对比 `ArrayList.remove(i)` 的 `System.arraycopy` 是 O(n)）。
**为什么正确**：不变量是"活粒子集合 = 前 `liveCount` 个槽位"，把最后一个活粒子搬到被删位置后，
该集合仍然等于前 `liveCount` 个槽位。
**必须一起搬**：所有并行数组（位置/速度/寿命/尺寸/色相…）**逐字段同步搬**，漏一个就是"位置和速度属于不同粒子"
的隐性错位 —— 这类 bug 在画面上表现为"粒子突然折返"，很难归因。

**代价与警告**：
- finalize/清理要小心：如果槽位里有"需要显式释放"的东西（`Bitmap`、`Path`、注册的回调），
  swap-remove 会让**最后一个槽位的资源被搬走**，你不能在删除时顺手释放它。
- **任何跨帧持有"粒子下标"的东西都会失效**（例如 GPU 顶点缓冲里记着 `index`、或者一条尾迹记着"我是第 7 号"）。
  Ncrust 的现状是"每帧从池里读一遍，画完即弃"，没有跨帧下标引用，所以可以安全使用 swap-remove。
- 「swap-remove / swapback」是社区叫法而非标准术语：Godot 社区就有一份同名提案
  ([godot-proposals#11566](https://github.com/godotengine/godot-proposals/issues/11566)、
  [Swapback Array 资源](https://store.godotengine.org/asset/plaught-armor/swapback-array/))。
  **术语未经标准化核对 —— 未核实**；算法本身如上，可自证。

### 1.4 该选哪种？

| 场景 | 选择 |
|---|---|
| 粒子彼此独立、每帧全量重画、无跨帧下标引用 | **swap-remove + `liveCount`**（最省、最快） |
| 需要"上一帧位置"做拖尾，且拖尾与粒子绑定 | **alive + 空闲表**（下标稳定），或干脆把"上一帧位置"也放进池里（`prevX/prevY` 数组） |
| 固定容量下允许"新粒子顶掉最老的" | **游标池**（Ncrust 现状），实现最简、分支最少 |

---

## 2. 在 Compose Canvas 上廉价地画大量粒子

### 2.1 `DrawScope.drawCircle` —— 默认选它

实测签名（`javap` on `ui-graphics-android/1.7.6`，Kotlin 声明按 JVM 名还原）：

```
// DrawScope
drawCircle(color: Color, radius: Float, center: Offset, alpha: Float,
           style: DrawStyle, colorFilter: ColorFilter?, blendMode: BlendMode)

// JVM 实际形态（value class 已脱糖）
public abstract void drawCircle-VaOC9Bg(long color, float radius, long center, float alpha,
                                        DrawStyle, ColorFilter, int blendMode);
```

**要点**：`color` 是 `long`、`center` 是 `long`（`Offset` 的 packed value）、其余是 `float`/引用。
**没有任何形参需要装箱**，所以在 draw 阶段调用它是**零分配**的 —— 这是它相对 `drawPoints` 的决定性优势。

官方也把这件事写进了文档：
> "Standard `DrawScope` methods (like `drawRect` and `drawCircle`) already reuse `Paint` objects internally
> without requiring developer allocation."
> — [Hardware acceleration](https://developer.android.com/guide/topics/graphics/hardware-accel)

**但要知道它的 GPU 侧代价**：
> "Don't modify shapes too often — **Complex shapes, paths, and circles for example, are rendered using
> texture masks.** Every time you create or modify a path, the hardware pipeline creates a new mask,
> which can be expensive."
> — 同上页

同一页的缩放表又把 `drawCircle`（默认 `Paint`、无 `PathEffect`）归为 "Simple Shapes"（API 17 起缩放正确）。
两句不矛盾（一句说**渲染实现**，一句说**缩放正确性**），但"圆用纹理遮罩实现"意味着**大量不同半径的圆**
可能触发大量遮罩生成 —— 这条在 Mali 系 GPU 上是否成为瓶颈，**本地无实测数据，需在 S6 上实测**（标 **需实测**）。

### 2.2 `Brush.radialGradient`：每次 draw 创建 vs 缓存

`Brush.radialGradient(...)` 每次调用都**构造一个新的 Brush 实例**。把它写在
`Canvas { }` / `Modifier.drawBehind { }` / `Modifier.drawBehind` 的 lambda 里，就是**每帧新建**；
写成"每个粒子一个渐变"，就是**每帧 N 个 Brush**。

**正确做法是 Compose 官方为这件事提供的 API：`Modifier.drawWithCache`**，其 KDoc 原文：

> "Draw into a [`DrawScope`] with content that is persisted across draw calls **as long as the size of the
> drawing area is the same or any state objects that are read have not changed**. … For example, a
> `LinearGradient` that is to occupy the full bounds of the drawing area **can be created once the size has
> been defined and referenced for subsequent draw calls without having to re-allocate**."
> — [`DrawModifier.kt` · androidx-main](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/DrawModifier.kt)
> 官方文档页：[drawWithCache](https://developer.android.com/reference/kotlin/androidx/compose/ui/draw/drawWithCache.modifier)

三种缓存位置的取舍：

| 位置 | 重建时机 | 适用 |
|---|---|---|
| `remember { }` | 组合期，key 变化时 | 与尺寸无关的常量对象（颜色、色板、`ImageBitmap`、PRNG 实例） |
| `Modifier.drawWithCache { onDrawBehind { … } }` | **尺寸/读取的状态变化时**；缓存跨 draw 存活 | 依赖 `size` 的对象：整屏渐变、随尺寸变化的路径/遮罩 |
| draw lambda 内 | **每次绘制** | ❌ 永远不要 |

> 注意 `drawWithCache` 的缓存会因为**你在 block 里读的 state 变化**而失效（`observeReads`，见上面源码），
> 所以**不要在 `drawWithCache` 的构建块里读每帧都变的 state** —— 那等于每帧重建。
> 每帧变的量应该在 `onDrawBehind` 的绘制块里读（这正是 Ncrust 现在读 `MotionClock.generation` 的位置）。

**关于 Brush 内部的 native Shader 是否复用**：Compose 的 `ShaderBrush` 家族会缓存创建出的 `Shader`，
但**换一个新 Brush 实例**就会走一次 `createShader`。本轮**未**逐行核对 1.7.6 的 `ShaderBrush` 缓存实现细节 ——
标 **未核实**。实践结论不变：**一个 Brush 只建一次**，就没这个问题。

### 2.3 `drawPoints` + `PointMode.Points` + `StrokeCap` —— 看起来很美，实测不适合

实测签名：

```
// JVM 形态（ui-graphics-android/1.7.6）
public abstract void drawPoints-F8ZwMP8(java.util.List<androidx.compose.ui.geometry.Offset> points,
        int pointMode, long color, float strokeWidth, int cap,
        PathEffect, float alpha, ColorFilter, int blendMode)
public abstract void drawPoints-Gsft0Ws(java.util.List<...Offset> points, int pointMode, Brush, float, int, ...)
```

`PointMode` 实测只有三个常量：`Points` / `Lines` / `Polygon`（`javap` 见 `access$getPoints$cp`、
`access$getLines$cp`、`access$getPolygon$cp`）。`StrokeCap` 实测为 `Butt` / `Round` / `Square`。

**两个否决理由**（都有实证）：

1. **`points` 形参是 `List<Offset>`，粒子位置每帧都变 ⇒ 每帧 N 次装箱。**
   `Offset` 是 `@JvmInline value class`（载荷 `long`），实测存在 `box-impl(long)`；
   而 JVM 方法签名收的是 `java.util.List`。要让 `List<Offset>` 里的元素跟着粒子走，
   就只能每帧对每个粒子 `list[i] = Offset(x, y)` → **每帧 N 个装箱对象**。这与"每帧零分配"直接冲突。
   （用 `ArrayList` 复用容器只省掉容器本身，省不掉元素的装箱。）
2. **Android 实现是逐点调用，没有批处理。** Compose 的 Android 画布实现：
   > ```kotlin
   > private fun drawPoints(points: List<Offset>, paint: Paint) {
   >     points.fastForEach { point -> internalCanvas.drawPoint(point.x, point.y, paint.nativePaint) }
   > }
   > ```
   > — [`AndroidCanvas.android.kt` · androidx-main](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui-graphics/src/androidMain/kotlin/androidx/compose/ui/graphics/AndroidCanvas.android.kt)

   即 `PointMode.Points` **不会**走到平台那个收 `float[]` 的批量 `Canvas.drawPoints`，
   而是 N 次 `drawPoint`（N 条 display list 记录）。`drawRawPoints(pointMode, FloatArray, paint)`
   在同一个文件里的实现**同样是逐点循环** —— 也就是说 **Compose 这条路上根本不存在批量点绘制**。
3. 附带一条 API 等级：`setStrokeCap() (for points)` 的"首次支持 API 等级"是 **19**
   （[Hardware acceleration 支持表](https://developer.android.com/guide/topics/graphics/hardware-accel)），
   minSdk 24 无碍。

> 另注：`androidx.compose.ui.graphics.Canvas` 接口上**确实**有 `drawRawPoints(int, float[], Paint)`
> （实测 public，未见 `@RequiresApi`/`@InternalComposeUiApi` 之类的运行期可见注解，
> 可从 `drawContext.canvas` 够到）。但既然它内部也是逐点 `drawPoint`，它并不能解决批处理问题；
> 且它不在 `DrawScope` 的公开文档面上，**其 API 稳定性未核实**。
>
> **结论：粒子数 > 约 32 时不要用 `drawPoints`。** 小数量、位置不常变的点（例如静态星点）可以用。

### 2.4 `drawImage` + 缓存的小位图 —— 大量粒子的首选替代

实测签名（同样全原始类型/引用，**无形参装箱**）：

```
public abstract void drawImage-gbVJVH8(ImageBitmap, long topLeft, float alpha,
                                       DrawStyle, ColorFilter, int blendMode);
```

用法：**实例创建时**把一个小的径向衰减精灵（比如 16×16 或 32×32 的软圆）画进一张 `ImageBitmap`，
之后每帧只 `drawImage(sprite, topLeft = Offset(x - r, y - r), alpha = life)`。
`topLeft` 是 value class（`long`）、`alpha` 是 `float` ⇒ **零分配、每粒子一次记录**。

两条纪律：
- **精灵只能建一次，之后绝不改内容**：
  > "Don't modify bitmaps too often — Every time you change the content of a bitmap, **it is uploaded again as
  > a GPU texture** the next time you draw it."
  > — [Hardware acceleration](https://developer.android.com/guide/topics/graphics/hardware-accel)
- 需要"不同颜色"时，**不要**在循环里 `ColorFilter.tint(...)`（每个粒子每帧一个新对象）。
  做法是**预生成 K 张着色精灵**（K = 色板大小）或把颜色烘进精灵。

### 2.5 "在 draw 里分配对象"为什么致命

三条官方依据并列：

1. `Canvas { }` / `drawBehind { }` 的 lambda **在绘制阶段执行，每帧可能执行多次**；在里面建对象即每帧建。
   官方性能页把"用 lambda 版 modifier 把 state 读取推迟到 draw 阶段"作为**推荐**写法
   （[Compose 性能最佳实践 · Defer reads as long as possible](https://developer.android.com/develop/ui/compose/performance/bestpractices)），
   理由正是"重绘比重组便宜" —— 但代价是 **draw 里的分配频率 = 帧率**。
2. 上面 §2.1/§2.4 引的 "Don't create render objects in draw methods"。
3. `drawWithCache` 的存在本身就是为了把对象分配从 draw 路径里拿出去（§2.2）。

---

## 3. 帧时钟

### 3.1 `withFrameNanos` / `MonotonicFrameClock`

`androidx.compose.runtime.withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R` 是 Compose 运行时
提供的挂起函数，取自 `CoroutineContext` 里的 `MonotonicFrameClock`。**KDoc 原文**（这是本节最重要的一段）：

> "Suspends until a new frame is requested, immediately invokes `onFrame` with the frame time in nanoseconds
> in the calling context of frame dispatch, then resumes with the result from `onFrame`.
>
> `frameTimeNanos` should be used when calculating animation time deltas from frame to frame as **it may be
> normalized to the target time for the frame, not necessarily a direct, "now" value**.
>
> The time base of the value provided by `withFrameNanos` is implementation defined. **Time values provided are
> strictly monotonically increasing; after a call to `withFrameNanos` completes it must not provide the same
> value again for a subsequent call.**"
> — [`MonotonicFrameClock.kt` · androidx-main](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/MonotonicFrameClock.kt)
> 文档页：[MonotonicFrameClock](https://developer.android.com/reference/kotlin/androidx/compose/runtime/MonotonicFrameClock)、
> [androidx.compose.runtime#withFrameNanos](https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#withFrameNanos(kotlin.Function1))

同一个文件还说明了：`withFrameNanos` 若当前 `CoroutineContext` 里没有 `MonotonicFrameClock`
会抛 `IllegalStateException`（所以它必须在 `LaunchedEffect` 等有 Compose 上下文的地方调用）。

**正确用法**（Ncrust 现状即如此）：`LaunchedEffect` 里 `while (true) { withFrameNanos { … } }`，
回调对象与状态数组**提升到循环外**复用
（见 `app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionClock.kt:203-232`）。

### 3.2 为什么 `withFrameNanos` 的 delta 不是可靠帧时长

三条理由，前两条来自上面的 KDoc 与实现定义，第三条是本仓库的实测记录：

1. **值可能是"目标帧时间"而不是"现在"**（KDoc 原文：*normalized to the target time for the frame,
   not necessarily a direct "now" value*）。你量到的是 `t_target(n) - t_target(n-1)`，
   而"这一帧实际花了多久"是另一回事。
2. **时间基准是 implementation defined**。同一段代码在 Android / 桌面 / 测试环境拿到的时间基准不保证一致，
   所以**基于它的单测无法复现**，也就无法用它做性能断言。
3. **本仓库的实际踩坑**：`VisualizerFrameMonitor` 的类注释记录了 S6（SM-G9209 / Android 7.0）上的
   "流水线式掉帧"：**帧回调仍然 60Hz、每帧延迟约 19ms —— 帧间隔量不出真实压力，v1.6.0 已因此回退**。
   > 证据（本仓库）：`app/src/main/java/com/takahashirinta/ncrust/ui/player/waveform/VisualizerFrameMonitor.kt` 顶部 KDoc；
   > 交叉引用 `AGENTS.md` 的帧时间监控一节。

一句话：**`withFrameNanos` 用来推进相位（`dt` 只影响动画快慢），不要用来判定"这一帧超时了"。**

### 3.3 用 `FrameMetrics` 量真实帧时长（API 24，正好等于 minSdk）

**API 等级实测**（Android SDK 自带的 API 数据库，比任何博客都权威）：

```
<class name="android/view/FrameMetrics" since="24">
<method name="addOnFrameMetricsAvailableListener(Landroid/view/Window$OnFrameMetricsAvailableListener;Landroid/os/Handler;)V" since="24"/>
```
> 来源：`$ANDROID_HOME/platforms/android-36/data/api-versions.xml`（本轮实测 grep）。
> 文档页：[FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)、
> [Window#addOnFrameMetricsAvailableListener](https://developer.android.com/reference/android/view/Window#addOnFrameMetricsAvailableListener(android.view.Window.OnFrameMetricsAvailableListener,%20android.os.Handler))

`FrameMetrics` 提供平台侧的**每帧各阶段真实耗时**（`TOTAL_DURATION`、`DRAW_DURATION`、
`GPU_DURATION`、`COMMAND_ISSUE_DURATION` 等，本轮 `javap android.jar` 实测这些常量存在）。
Ncrust 已经用它做"持续超标 → 一次性降级"的判据，判定逻辑抽在纯函数 `FrameBudgetPolicy` 里（可 JVM 单测），
监听器在判定成立后**立刻注销自己**。这是本文推荐的形态，粒子系统应当接进同一条降级阶梯，
而**不要**自己再写一套基于 `withFrameNanos` 间隔的判定。

---

## 4. 确定性 vs 随机性

### 4.1 为什么必须是"可播种的确定性随机"

- **单测**：只有固定种子 + 固定 dt 序列，`update()` 才是可断言的纯函数；否则测试只能断言"没崩"。
- **可复现的观感**：同一首歌、同一时间点，粒子应该落在同一处（用户在 S6 上看到的和你调试时看到的一致）。
- **零分配**：PRNG 状态就是几个 `Int`/`Long` 字段，取值不 new 任何东西。

### 4.2 内联 PRNG：xorshift32（状态存 `Int`，或存 `Long` 便于快照）

```
/** 6 行 xorshift32。state 必须非 0（0 是它的唯一不动点）。 */
private var rngState: Int = seed or 1          // 或 Long 版：state or 1L

private fun nextInt(): Int {
    var x = rngState
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    rngState = x
    return x
}

/** 取 [0,1) 的 Float，零分配。 */
private fun nextFloat(): Float = (nextInt() ushr 8) * (1.0f / (1 shl 24))
```

- 算法归属：xorshift 家族出自 Marsaglia 的 *Xorshift RNGs*
  （[Journal of Statistical Software, 2003](https://www.jstatsoft.org/article/view/v008i14)）。
  **本轮未取得原文逐字核对移位三元组 (13, 17, 5)** —— 标 **未核实**。
  但这件事**不影响可用性**：一旦落地，**用单测把输出序列钉死**，序列本身就成为本仓库的契约，
  不再依赖任何外部核对（这也是 §推荐方案 里"确定性单测"要断言 `stateHash` 的原因）。
- **0 是吸收态**：`x = 0` 时永远输出 0 —— 种子必须 `or 1`（或校验非 0），这是最容易漏的一个坑。
- 想要更好统计性质且仍零分配，可用 LCG（`state = state * 1664525 + 1013904223`）或 xorshift64*；
  但"高质量 PRNG 的统计检验"**不在本篇范围内**，粒子这种视觉噪声用 xorshift32 足够。

### 4.3 `kotlin.random.Random` 的真实代价（实测源码，不猜）

实测 Kotlin/JVM stdlib 的 `PlatformRandom.kt`（[JetBrains/kotlin · master](https://raw.githubusercontent.com/JetBrains/kotlin/master/libraries/stdlib/jvm/src/kotlin/random/PlatformRandom.kt)）：

```
@InlineOnly
internal actual inline fun defaultPlatformRandom(): Random = IMPLEMENTATIONS.defaultPlatformRandom()

internal abstract class AbstractPlatformRandom : Random() {
    abstract val impl: java.util.Random
    override fun nextInt(): Int = impl.nextInt()
    override fun nextFloat(): Float = impl.nextFloat()
    …
}

internal class FallbackThreadLocalRandom : AbstractPlatformRandom() {
    private val implStorage = object : ThreadLocal<java.util.Random>() {
        override fun initialValue(): java.util.Random = java.util.Random()
    }
    override val impl: java.util.Random get() = implStorage.get()
}
```

由此可得**可核实的三条**：

1. **不装箱**。`nextInt()`/`nextFloat()` 返回原始类型（上表 `override fun nextInt(): Int = impl.nextInt()`），
   所谓"`kotlin.random.Random` 会装箱"是**错的**。
2. **默认实例大概率不是共享的**。stdlib 提供了 `FallbackThreadLocalRandom`（每线程一个 `java.util.Random`）。
   但 `Random.Default` 到底走哪条要看 `IMPLEMENTATIONS.defaultPlatformRandom()` —— 那个对象**不在本文件里**，
   **本轮未核对 ⇒ 标「未核实」**。所以"`Random.Default` 有线程争用"这个说法**不能作为论据**。
3. **`java.util.Random` 自身有两个被文档承认的特性**：
   - 它是 **48 位 LCG，种子更新是原子的**：*(The method `next` is implemented by class `Random` by
     atomically updating the seed to `(seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1)`)*；
   - 文档明确警告争用：*"Instances of `java.util.Random` are threadsafe. However, **the concurrent use of the
     same `java.util.Random` instance across threads may encounter contention and consequent poor
     performance**."*，以及 *"Linear congruential pseudo-random number generators such as the one implemented
     by this class are known to have **short periods in the sequence of values of their low-order bits**."*
   > — [java.util.Random · Java SE 17 API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Random.html)

**结论**：`Random(seed)` 也能做到确定性（JDK 规范固定了算法），但它带来
① 一个 `java.util.Random` 对象（**实例级**，不是每帧，可接受）；
② 每次取值一次 **CAS**（每帧几百次取值时是纯开销）；
③ 你的序列契约依赖 JDK/ART 的实现（虽然规范固定，但**跨 Kotlin 版本 `Random(seed)` 到 `java.util.Random`
的映射未核实**）。
在"每帧几百次取值"的热路径上，**自己写 6 行 xorshift + 单测钉死序列**更便宜、更可控、更可测。

---

## 5. 低端 GPU 上的 alpha 混合代价

### 5.1 overdraw 与填充率（官方定义）

> "When an app draws the same pixel more than once within a single frame, this is called *overdraw*. …
> Overdraw becomes a performance problem when it wastes GPU time to render pixels that don't contribute to
> what the user sees on the screen."
>
> "Rendering transparent pixels on screen, known as *alpha rendering*, is **a key contributor to overdraw**.
> Unlike standard overdraw — when the system completely hides existing drawn pixels by drawing opaque pixels
> on top of them — **transparent objects require existing pixels to be drawn first**, so that the right
> blending equation can occur."
>
> "On less performant GPUs, available **fill-rate** — the speed at which the GPU can fill the frame buffer —
> can be low. As the number of pixels required to draw a frame increases, the GPU might take longer to process
> new commands…"
>
> — [Reduce overdraw · developer.android.com](https://developer.android.com/topic/performance/rendering/overdraw)

**每帧像素预算的经验值（同页）**：
> "A good rule of thumb with current hardware is to **not draw more than 2.5 times the number of pixels on
> screen per frame** (transparent pixels in a bitmap count!)"
> — [Hardware acceleration · Tips and tricks](https://developer.android.com/guide/topics/graphics/hardware-accel)

**为什么"很多大半径半透明圆"特别贵**：每个圆的覆盖像素数 ∝ **πr²**。
64 个半径 40dp 的软圆，在 1440×2560 的屏幕上按 ~1.2 的密度换算半径 ≈ 48px，
单圆覆盖 ≈ 7.2k px，64 个 ≈ 460k px，**但每个像素可能被多个圆叠加**（粒子云聚在一起时局部 overdraw 可达 5–10×），
于是实际着色像素数可达 2–5M —— **已经超过"2.5× 屏幕像素"预算**（3.69M × 2.5 ≈ 9.2M 是上限，
但 S6 的 Mali-T760 填充率本来就低，这个上限对它偏乐观）。**这组数字是本轮的算术估算，不是实测 —— 标「需实测」。**

另注官方对"低端设备"的态度（对本 fork 直接相关）：
> "Although low-end devices continue to improve in GPU performance, their displays remain at relatively low
> resolutions. **Unless optimizing for a known low-performance GPU device**, we recommend instead focusing on
> optimizing UI thread work…"
> — [Reduce overdraw](https://developer.android.com/topic/performance/rendering/overdraw)

Ncrust 的验收设备就是**已知的低性能 GPU 设备（Galaxy S6）**，所以上面那条"别管 overdraw"的豁免**不适用**。

### 5.2 廉价缓解手段（按性价比排序）

1. **限数量**：容量上限按档位给（0 / 32 / 64），并接进既有的降级阶梯。这是唯一"线性有效"的手段。
2. **缩半径**：代价 ∝ r²，半径减半 = 代价降到 1/4。视觉上"小而亮"的粒子通常比"大而淡"更省也更好看。
3. **别叠 alpha 图层**：官方明确说
   > "When you make a composable translucent using `Modifier.alpha` … it is typically rendered in an
   > off-screen buffer which **doubles the required fill-rate**. … For individual draw calls, apply alpha
   > directly to the drawing command (like with `color = Color.Red.copy(alpha = 0.5f)`) **without creating a
   > layer**."
   > — [Hardware acceleration](https://developer.android.com/guide/topics/graphics/hardware-accel)
   即：**逐 draw call 传 `alpha`**（`drawCircle(..., alpha = …)`），而不是给整个粒子层套 `Modifier.alpha`。
4. **不要超出 `SrcOver`**：非 `SrcOver` 的 `BlendMode` 往往需要离屏缓冲/额外混合阶段
   （官方性能页对 `CompositingStrategy.Offscreen` 的说明："To explicitly force an off-screen buffer for
   advanced drawing operations, such as custom blending within the layer…"）。粒子用默认 `SrcOver` 即可。
5. **不要 `saveLayer`**：每层 = 一次额外的全屏/全区域渲染目标（同上，填充率翻倍级别）。
6. **纯色优先**：能用 `drawCircle(color, alpha)` 就不用带渐变的 Brush（渐变 = 每像素更多运算）。
7. **灰字教训（官方同页）**：想要"半透明效果"时，**直接画不透明色**往往等效且更省
   （例：要灰色文字就画 `Color.Black.copy(alpha = 0.5f)` 的**不透明灰**，而不是给黑字套 0.5 alpha 层）。

---

## 6. 可比的真实实现

### 6.1 Konfetti（`DanielMartinus/Konfetti`）

| 项 | 值 |
|---|---|
| 许可 | **ISC**（[LICENSE](https://raw.githubusercontent.com/DanielMartinus/Konfetti/main/LICENSE)：*"ISC License, Copyright (c) 2017 Dion Segijn"*；README 也写明 *"Konfetti is released under the ISC license."*） |
| 制品 | `nl.dionsegijn:konfetti-compose:2.0.5`（[README](https://raw.githubusercontent.com/DanielMartinus/Konfetti/main/README.md)），另有 `konfetti-xml`；README 标注 API 16+ |
| 结构 | `konfetti/core/**/Party.kt`（**配置数据类**，不可变）→ `PartySystem.kt`（每 Party 的运行时状态 + emitter）→ `konfetti/compose/**/KonfettiView.kt`（Compose 入口） |
| 帧来源 | `LaunchedEffect(Unit) { while (true) { withInfiniteAnimationFrameMillis { frameMs -> … } } }`（`androidx.compose.animation.core`；**该 API 的稳定性/语义本轮未核实**） |
| 每帧分配 | **有，且 ∝ N**。核心一路是：<br>`particles.value = partySystems.map { it.render(dt, area) }.flatten()`（**新 List + map/flatten 中间集合**）<br>`PartySystem.render` 内：`activeParticles.filter { it.drawParticle }.map { it.toParticle() }`（**每帧每粒子 new 一个 `Particle`**，外加两次中间集合）<br>绘制侧：每粒子 `withTransform { rotate(...); scale(...) }`，两个 `Offset(...)` 枢轴 + lambda 闭包 |
| 渲染方式 | `Canvas(onDraw = { particles.value.forEach { … particle.shape.draw(drawScope, particle, imageStore) } })`，`Shape.Square/Circle/DrawableShape`（走 `DrawShapes.kt`） |
| 可迁移的部分 | ✅ **配置数据类与运行时状态分离**、✅ **`render(deltaTime, drawArea)` 的 dt 驱动契约**、✅ 有单测（`konfetti/core/src/test/**/PartySystemTest.kt`） |
| 不可迁移的部分 | ❌ 每帧 `toParticle()` 的对象分配；❌ `MutableState<List<Particle>>` 整体替换（每帧一次状态写入 + 全新列表）；❌ `removeAll { it.isDead() }`（O(n) 谓词过滤，不是 swap-remove） |

### 6.2 Quarks（`CuriousNikhil/compose-particle-system`）

| 项 | 值 |
|---|---|
| 许可 | **Apache-2.0**（[LICENSE](https://raw.githubusercontent.com/CuriousNikhil/compose-particle-system/master/LICENSE)：*"Apache License Version 2.0 … Copyright 2021 Nikhil Chaudhari"*） |
| 制品 | `me.nikhilchaudhari:quarks:{latest}`（[README](https://raw.githubusercontent.com/CuriousNikhil/compose-particle-system/master/README.md)） |
| 结构 | `particlesystem/**/ParticleSystem.kt`（`CreateParticles` composable）→ `emitters/{Emitter,ParticleExplodeEmitter,ParticleFlowEmitter}.kt` → `particle/{Particle,ParticleConfig,Vector2D}.kt`；配置对象为 `Velocity` / `Force` / `Acceleration` / `ParticleSize` / `ParticleColor` / `LifeTime` / `EmissionType` |
| 帧来源 | `LaunchedEffect(Unit) { while (condition) { withFrameNanos { dt.value = ((it - previousTime) / 1E7).toFloat(); previousTime = it } } }` —— **每帧写一次 Compose state**（`mutableStateOf(0f)`） |
| 模拟位置 | **在 draw lambda 里**：`Canvas(modifier) { emitter.render(this); emitter.applyForce(force.createForceVector()); emitter.update(dt.value) }` |
| 每帧分配 | **有**：`force.createForceVector()` 每帧新建向量；`emitter.update/render` 走对象集合 |
| 可迁移的部分 | ✅ **物理量作为配置对象暴露**（速度/力/加速度/寿命/发射方式）的 API 设计；✅ "爆炸一次" vs "持续流"两种发射模型的分离 |
| 不可迁移的部分 | ❌ **把仿真放进 draw lambda**（Compose 可能在不推进帧的情况下重绘 ⇒ 仿真被多推进；且每帧的分配发生在最热的路径上）；❌ 每帧写 state；❌ `startTime` 在 composable 体内赋值（每次重组都重置） |

### 6.3 迁移性判断（一句话）

> **两者都是"配置模型 + 每帧重建粒子列表"的架构。**
> 可以借的是 **API 形状**（配置数据类 / dt 驱动的 `render(dt)` / 发射器抽象），
> **不能借的是稳态数据路径**（对象列表 ↔ SoA `FloatArray`；每帧分配 ↔ 零分配）。
> 本 fork 的 `MotionEnvelope`/`MotionBackdropState` 已经在正确的道路上，**不需要引入这两个依赖**。

许可提示：ISC 与 Apache-2.0 都是宽松许可，与本 fork 的 GPLv3 分发不冲突（本 fork 只是**参考结构**，
并未复制代码）；若日后要复制粘贴代码，需按 GPLv3 的兼容性流程走一遍。
FSF 的许可兼容清单：<https://www.gnu.org/licenses/license-list.html> —— **本轮未逐字核对该页对 ISC/Apache-2.0 的措辞，标「未核实」**。

---

## 7. 未核实清单（明示）

| 条目 | 状态 |
|---|---|
| xorshift32 的移位三元组 (13,17,5) 与 Marsaglia 2003 原文逐字一致 | **未核实**（用单测钉死序列即可回避） |
| `Random.Default` 在 Android 上具体走哪条实现（`IMPLEMENTATIONS.defaultPlatformRandom()`） | **未核实**（stdlib 只提供了 `FallbackThreadLocalRandom` 这一个候选实现） |
| Compose 1.7.6 `ShaderBrush` 内部 native `Shader` 的复用/失效细节 | **未核实** |
| `androidx.compose.ui.graphics.Canvas.drawRawPoints` 的 API 稳定性（是否属内部 API） | **未核实** |
| `withInfiniteAnimationFrameMillis` 的稳定性与语义（Konfetti 在用） | **未核实** |
| "圆用纹理遮罩"在 Mali-T760 上是否真的成为瓶颈；每帧像素预算的估算值 | **需实测**（本文 §5.1 的数字是算术估算） |
| SoA vs AoS 的缓存优势的权威出处 | **未核实**（结论不依赖它） |
| FSF 许可清单对 ISC/Apache-2.0 与 GPLv3 兼容性的具体措辞 | **未核实** |

---

## 推荐方案（给 Ncrust）

### R1. 数据层：新文件 `ui/player/motion/ParticlePool.kt`（纯 Kotlin，JVM 可测）

- **零 Android / 零 Compose 依赖**：不 `import android.*`，也不 `import androidx.compose.*`
  （位置用裸 `Float`，**不要**在这一层用 `Offset`/`Color`）。
  理由：`app/src/test` 是 JVM 单测源集，模型层无 Android 依赖才能进 `./gradlew test`。
  本仓库已有先例：`MotionEnvelopeTest` / `MotionPrefsTest` / `PlayerDragSnapTest` 都是纯 JVM 单测。
- **定长池**：`CAPACITY = 64`（低档 32 / 关 0，由档位在**实例创建时**决定，运行期不扩容）。
  SoA 数组：`px, py, vx, vy, life, radius, tint`（`FloatArray`）+ `rngState: Int` + `liveCount: Int`。
- **删除用 swap-remove**：所有并行数组逐字段同步搬最后一个活粒子（§1.3）。
  **全仓库唯一允许持有"粒子内容"的地方就是这些数组**，不把下标泄给外部。
- **PRNG 内联**：xorshift32，`rngState` 非 0；提供 `fun reseed(seed: Long)`。
- **唯一的时间入口**：`fun update(dtMs: Float, spawnRate: Float, level: Float)`；
  内部做"发射判定 → 积分 → 寿命衰减 → swap-remove"。
  它是 `(state, dt, 参数)` 的纯函数式推进，**不读系统时间**（不 `System.nanoTime()`、不 `currentTimeMillis()`）。

### R2. 绘制层：`MotionBackdrop` 里新增一条分支（不新建 composable）

- 每帧**只读**：`MotionClock.generation`（失效信号）+ `backdrop.particleXAt(i)` 这类只读访问器，
  与现有 `MotionBackdrop.kt:198-210` 完全同构。
- **主路径**：逐粒子 `drawCircle(color = particleColor, radius = r, center = Offset(x, y), alpha = life * MAX_ALPHA)`。
  `Color`/`Offset` 都是 value class，脱糖成 `long` ⇒ **零装箱零分配**（§2.1 实测）。
- **备用路径（S6 实测发现圆的遮罩代价过高时再切）**：实例创建时预渲染一张 32×32 软圆 `ImageBitmap`，
  逐粒子 `drawImage(sprite, topLeft = Offset(x - r, y - r), alpha = life)`。
  精灵创建后**绝不修改内容**（§2.4）。
- **明确拒绝 `drawPoints`**：`List<Offset>` 每帧 N 次装箱 + Android 实现逐点 `drawPoint`（§2.3）。
  只有粒子数 ≤ 16 且位置低频变化时才考虑。
- **没有 Brush**：粒子不需要渐变。若某个档位确实要径向渐变，**只建一次**
  （`remember` 或 `Modifier.drawWithCache { }` 的构建块），**绝不**放进每帧的 draw lambda（§2.2）。

### R3. 帧时钟：接进现有的唯一一条循环

- 粒子推进放在 `MotionClock.frame(...)` 里（`MotionBackdropState.update(...)` 旁边），
  **不要**放进 draw lambda —— 这一点是硬约束：
  1. Compose 可以在不推进帧的情况下重绘（例如被上层 `invalidate` 连带），draw 里做仿真会**多推进一次**；
  2. Quarks 就是这么写的，它是本节的**反面教材**（§6.2）。
- **`dt` 的来源与口径**：沿用 `MotionClock` 现有口径（`withFrameNanos` 的差值，`coerceIn(1f, 100f)` ms，
  低端机重绘上限 30fps 由 `visualizerFrameIntervalMs` 决定）。
- **帧时长判定不新增逻辑**：继续用 `VisualizerFrameMonitor`（`FrameMetrics`，API 24）。
  粒子档位要参与降级阶梯，就把它当作 `MotionEffects` 的一个能力位交给既有的 `MotionDegrade` 流程。
- **暂停/缓冲不空转**：非播放态走 `delay(frameIntervalMs)`（现状），粒子自然衰减归零。

### R4. 每帧分配预算表（验收口径：稳态 **0 B/帧**）

| 分配物 | 时机 | 说明 |
|---|---|---|
| SoA `FloatArray` × k | **实例创建** | 唯一的池内存 |
| 缓存的 `ImageBitmap` 精灵（若启用备用路径） | **实例创建** | 之后只读 |
| 缓存的 `Brush`（若该档位需要渐变） | **实例创建 / `drawWithCache` 构建块** | 绝不在 draw lambda 里 |
| 帧回调 lambda `(Long) -> Unit`、`LongArray(2)` 时钟 | **帧循环外**（现状即如此） | `MotionClock.kt:207-216` |
| 每帧**新增**对象 | **0** | 没有 `Offset` 装箱、没有 `Brush`、没有 `ColorFilter`、没有 list/map/filter/lambda |

> 验收方式（现实可行的）：① 代码审查 + `grep` 确认 draw lambda 内无构造调用；
> ② 单测断言数组**同一性**（`assertSame` 池数组引用，跑 10000 帧后不变）；
> ③ 真机 `FrameMetrics` 的 `TOTAL_DURATION` 分位数不劣化（对照宏基准 `ExpandPlayerBenchmark`）。
> **不要**用 `Runtime.totalMemory()` 差值做单测断言（噪声远大于信号）。

### R5. 确定性单测（`app/src/test/java/.../motion/ParticlePoolTest.kt`）

| # | 断言 | 防的是什么 |
|---|---|---|
| 1 | 同一 seed + 同一 `dt` 序列 ⇒ 位置**逐位相同**（`assertEquals(bits, bits)`） | 随机性污染了主路径 |
| 2 | `liveCount` 永不超过 `CAPACITY`；`[0, liveCount)` 之外不参与绘制 | 越界 / 幽灵粒子 |
| 3 | swap-remove 后：活粒子集合不变（用 `(px,py)` 的多重集合对比） | 漏搬某个并行数组（症状是"粒子突然折返"） |
| 4 | `life` 衰减到 0 的粒子在**下一帧**必然不在 `[0, liveCount)` 内 | 死粒子泄漏（会白画一整屏） |
| 5 | `rngState != 0` 在 1e6 次取值后仍成立（0 是吸收态） | 种子写错导致全 0 |
| 6 | 跑 10⁴ 帧后，池数组引用 `assertSame` 未变 | 有人在热路径里偷偷 realloc |
| 7 | `update(dtMs = 0f)` 是恒等（不动位置、不消耗随机数） | 帧循环里出现 0ms 帧时粒子抖动 |

### R6. 与既有纪律的衔接

- **GPU 零重组**：粒子层只让 draw 失效（读 `MotionClock.generation`），**不写**任何在组合阶段被读的 state。
- **关掉开关 = 零开销**：档位为 0 时**不分配池、不建精灵、不跑循环**（与 `MotionFrameClock` 的
  `clockNeeded` 判据一致）。
- **异常隔离**：精灵创建/绘制包 `runCatching`，失败即回退到"没有粒子"，绝不影响播放链路（沿用 `MotionBackdrop` 的既有约束）。
- **不引入新依赖**：Konfetti / Quarks 都只是**结构参考**，不加入 `app/build.gradle.kts`。
