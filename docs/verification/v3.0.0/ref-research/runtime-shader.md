# `android.graphics.RuntimeShader`（AGSL）用于音频可视化：可用性、代价与替代方案

> 目标：为 Ncrust（GPLv3 fork，**minSdk 24 / Android 7.0**，验收真机是 Galaxy S6 / SM-G9209 / Android 7.0）
> 回答一个问题：**要不要用 `RuntimeShader`（AGSL）做音频反应式视觉，以什么形式用。**
>
> 本文每条外部事实都带 URL。**本轮拿不到一手证据的一律标「未核实」**；凡标「实测」者为本轮直接读出：
> ① `$ANDROID_HOME/platforms/android-36/data/api-versions.xml`（Android SDK 自带的 API 等级数据库 ——
> 判断"某 API 从哪一级开始有"最权威的本地证据）；
> ② `$ANDROID_HOME/platforms/android-36/android.jar`（`javap`，看真实签名/枚举常量）；
> ③ AOSP 镜像源码 `aosp-mirror/platform_frameworks_base`；
> ④ `~/.gradle/caches/.../androidx.compose.ui/*/1.7.6/*.aar`（`javap`，本 fork 实际编译依赖的版本）。

---

## 0. 结论摘要（TL;DR）

| 问题 | 答案 |
|---|---|
| `RuntimeShader` 从哪一级开始有？ | **API 33（Android 13）**。实测 `api-versions.xml`：`<class name="android/graphics/RuntimeShader" since="33">` |
| `RenderEffect` 呢？ | **API 31（Android 12）** |
| `RenderEffect.createRuntimeShaderEffect` 呢？ | **API 33**（它收 `RuntimeShader`，不可能更早） |
| API < 33 有 `RuntimeShader` 的回落方案吗？ | **没有任何回落。** 见 §1.4 —— 这不是"官方不推荐"，是"类不存在" |
| 在 Compose 里怎么用？ | `android.graphics.RenderEffect` → `asComposeRenderEffect(...)` → `Modifier.graphicsLayer { renderEffect = … }`。Compose 自带 `RenderEffect` 抽象，但**没有** RuntimeShader 的工厂（§2.1） |
| 能不能不重组就换 uniform？ | 能：`setFloatUniform` 是普通 setter，只要**不在组合阶段写 Compose state** 就行（§2.3） |
| 低端机能跑全屏 AGSL 吗？ | **无公开数据，需实测**；且**本 fork 的验收机跑不了**（S6 = API 24 < 33）—— 见 §4 |
| 本 fork 该不该用？ | **v3.0.0 不应落地**；也不要把它做成"只在 API 33+ 才存在的 showcase 档"（§6） |
| API 24 上能用什么？ | `Brush.linearGradient`/`radialGradient`、`BitmapShader`、**预模糊位图**（本仓库已有 `CoverBlur`）。`BlurMaskFilter` 在硬件加速画布上**被忽略**，`Modifier.blur` 在 API < 31 上**是 no-op**（§5） |

---

## 1. 可用性（Availability）

### 1.1 API 等级（实测，不是回忆）

Android SDK 自带一份 API 等级数据库（`platforms/android-XX/data/api-versions.xml`），
本轮直接 grep 得到：

```
<class name="android/graphics/RuntimeShader" since="33">
<class name="android/graphics/RenderEffect"  since="31">
<class name="android/view/FrameMetrics"      since="24">
<class name="android/graphics/BitmapShader"  since="1">
<class name="android/graphics/BlurMaskFilter" since="1">
<class name="android/graphics/Shader$TileMode" since="1">
```
> 来源：`$ANDROID_HOME/platforms/android-36/data/api-versions.xml`（本轮实测）。
> 与官方文档一致：`RuntimeShader` 的 **class 级 `since` 是 33**，
> 这一点经 Microsoft Learn 的 Xamarin 绑定元数据交叉验证：
> `[Android.Runtime.Register("android.graphics.RuntimeShader", ApiSince=33, …)]`
> （[learn.microsoft.com · RuntimeShader](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.runtimeshader)）；
> `RenderEffect` 同为 `ApiSince=31`，`createRuntimeShaderEffect` 为 `ApiSince=33`
> （[learn.microsoft.com · RenderEffect](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.rendereffect)、
> [createRuntimeShaderEffect](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.rendereffect.createruntimeshadereffect)）。
> 官方文档页：[RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)、
> [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)。

**`RuntimeShader` 的公开方法**（实测 AOSP 源码
[`RuntimeShader.java`](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/RuntimeShader.java)）：

| 方法 | 对应 AGSL 类型 |
|---|---|
| `RuntimeShader(String shader)` | 构造即编译 AGSL 源码（见 §3.6） |
| `setFloatUniform(String, float/float,float/…/float[])` | `float` / `vec2` / `vec3` / `vec4` / `float[]` / `mat2..mat4` |
| `setIntUniform(String, int/…/int[])` | `int` / `ivec2..ivec4` / `int[]` |
| `setColorUniform(String, @ColorInt int / @ColorLong long / Color)` | `layout(color) uniform vec4` |
| `setInputShader(String, Shader)` | `uniform shader`（含 `BitmapShader`、其它 `RuntimeShader`） |
| `setInputBuffer(String, BitmapShader)` | 同上，但**不做色彩空间转换、不做预乘**（用于当数据而不是颜色用的位图） |
| `setInputColorFilter` / `setInputXfermode` | 源码标了 `@FlaggedApi(FLAG_RUNTIME_COLOR_FILTERS_BLENDERS)` ⇒ **不是 API 33 就有的通用能力**（具体从哪一版公开 **未核实**） |

`Shader.TileMode`（实测 `javap android.jar`）是枚举，取值 **`CLAMP`、`DECAL`、`MIRROR`、`REPEAT`**（四个，不是三个），
class 自 **API 1** 起存在。

### 1.2 AGSL 是什么

> "Android Graphics Shading Language (AGSL) is used by **Android 13 and above** to define the behavior of
> programmable `RuntimeShader` objects. AGSL shares much of its syntax with GLSL fragment shaders, but works
> within the Android graphics rendering system to both customize painting within `Canvas` and filter `View`
> content."
> — [Android Graphics Shading Language (AGSL) · developer.android.com](https://developer.android.com/develop/ui/views/graphics/agsl)

关键的心智模型（同页 / `RuntimeShader` javadoc 原文）：

> "**With GPU shading languages, you are programming a stage of the GPU pipeline. With AGSL, you are programming
> a stage of the `Canvas` or `RenderNode` drawing pipeline.**"
> "A `RuntimeShader`, like other `Shader` types, effectively contributes a **function** to the GPU's fragment shader."

也就是说：AGSL 不是"你自己写整个片元着色器然后直接跑"，而是**你往 Android 已经组装好的那个
巨大 fragment shader 里塞一个函数**（形状判定、裁剪、色彩管理、混合都在同一个 shader 里）。
后果：**你无法控制最终的着色器总开销**，只能控制自己那一段。

### 1.3 `RenderEffect` 与 `createRuntimeShaderEffect` 的分工

- `RenderEffect`（API 31）：**一个中间渲染步骤**，挂在 `RenderNode` 上，
  > "A `RenderEffect` can be configured on a `RenderNode` through `RenderNode#setRenderEffect(RenderEffect)`
  > and will be applied when drawn through `Canvas#drawRenderNode(RenderNode)`."
  > — [RenderEffect javadoc（经 MS Learn 镜像）](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.rendereffect)
- `RenderEffect.createRuntimeShaderEffect(shader, uniformShaderName)`（API 33）：
  > "Create a `RenderEffect` that executes the provided `RuntimeShader` and **passes the contents of the
  > `RenderNode` that this RenderEffect is installed on as an input to the shader**."
  > — [createRuntimeShaderEffect](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.rendereffect.createruntimeshadereffect)

⇒ 这条路子的语义是"**把某一层的内容当作纹理喂给你的 shader**"，天然是**整层 / 全屏**粒度 ——
不是"给 200 个粒子各加一点效果"。这一点直接决定了它的成本模型（§2.2）。

### 1.4 API < 33 有没有回落？——**没有**

**明确结论：没有任何回落方案。** 理由链（每步都可核）：

1. `RuntimeShader` 是 **platform 类**（`android.graphics`），不在 androidx。
   本轮**未发现**任何 androidx 制品提供 `RuntimeShader` 的兼容层/backport：
   `androidx.graphics:graphics-core` / `graphics-shapes` 的公开 API 面里没有它
   （[androidx graphics 版本说明](https://developer.android.com/jetpack/androidx/releases/graphics)；
   **"已穷尽核对 androidx 制品清单"这一点标「未核实」** —— 本轮的检索是定向检索，不是全量枚举）。
2. AGSL 的实现落在**平台图形栈内部**：`RuntimeShader` 构造时直接走 native
   （AOSP 源码：`mNativeInstanceRuntimeShaderBuilder = nativeCreateBuilder(shader)`，末尾
   `NativeAllocationRegistry.createMalloced(...)`），即 Skia/HWUI 侧的运行时着色器编译器。
   **第三方库无法把平台的着色器编译器搬进老系统**（这不是"没写"，是架构上不可行）——
   「Skia 的 `SkRuntimeEffect` 需要对应 Android 版本的 HWUI 支持」属于架构推理，**未逐条核对 Skia 文档，标「未核实」**；
   但即使这条推理有误，第 3 条也已经足够定案。
3. **类不存在 = 链接期就失败**。在 minSdk 24 的应用里直接 `RuntimeShader(src)`：
   代码能通过编译（compileSdk 36 有该类），但在 API 24–32 的设备上执行到那一行会因类解析失败抛
   `NoClassDefFoundError`（或其包装）。**所以唯一正确的写法是把每一处用法都关在
   `Build.VERSION.SDK_INT >= 33` 里**，并且**在 API < 33 上根本没有等价物可退回**。
   > `NoClassDefFoundError` 这一具体异常类型本轮**未实测验证**（无 API < 33 真机跑该分支），
   > 标 **未核实**；但"必须做版本判断"这一结论不依赖异常类型。

**给本 fork 的直接含义**：验收机 S6（Android 7.0 = **API 24**）**永远跑不到这条路径**。
任何"必须在 S6 上不掉帧"的硬规则，对一个**在 S6 上根本不存在**的效果是**无法验证**的（§4、§6）。

---

## 2. 在 Compose 里用它

### 2.1 传递路径：`android.graphics.RenderEffect` → Compose `RenderEffect` → `graphicsLayer`

Compose 的 `RenderEffect` **不是** `android.graphics.RenderEffect` 的 typealias，而是它自己的接口：
实测 `ui-graphics-android/1.7.6` 里有 `androidx.compose.ui.graphics.RenderEffect`（接口）、
`AndroidRenderEffect`（Android 实现）、`RenderEffectVerificationHelper`（真正去调平台 API 的地方）。
Compose 只提供了两个工厂：`BlurEffect(...)` 与 `OffsetEffect(...)`
（实测 `RenderEffectKt` 只有 `BlurEffect-3YTHUZs` / `OffsetEffect`）。

**所以用 RuntimeShader 必须自己跨过这座桥**（实测 1.7.6 的公开字节码）：

```
// androidx.compose.ui.graphics.AndroidRenderEffect_androidKt（javap 实测：public static final，
// 未见 @RequiresApi / @InternalComposeUiApi 之类的运行期可见注解）
fun asComposeRenderEffect(android.graphics.RenderEffect): androidx.compose.ui.graphics.RenderEffect
```

而落点就是 `GraphicsLayerScope.renderEffect`（实测 `ui-android/1.7.6` 的 `GraphicsLayerScope`
有 `getRenderEffect()/setRenderEffect(RenderEffect)`）：

```kotlin
// 只在 API 33+ 进入这个分支
val shader = RuntimeShader(AGSL_SRC)                 // 构造即编译
shader.setFloatUniform("uLevel", level)
val effect = RenderEffect.createRuntimeShaderEffect(shader, "uContent")  // 输入层内容
Box(
    Modifier.graphicsLayer { renderEffect = asComposeRenderEffect(effect) }
) { /* 要被打效果的内容 */ }
```

> Compose 文档面：[Graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)、
> [Modifier.graphicsLayer](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/graphicsLayer.modifier)。
> `asComposeRenderEffect` **未出现在 developer.android.com 的 Compose API 参考里**（本轮未检索到），
> 属于"字节码公开但文档面缺失"的函数 —— **它的长期 API 稳定性未核实**。

### 2.2 每帧的代价是什么

`renderEffect` 是**图层级**属性（§1.3：它吃的是 `RenderNode` 的内容）。所以：

1. **一次额外的离屏渲染目标**。官方对"渲染进独立层"的说法：
   > "Usage of this API renders the corresponding composable into a separate graphics layer."
   > — [`Modifier.blur` KDoc · androidx-main `Blur.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/Blur.kt)
   以及硬件加速页里关于离屏缓冲会**让填充率翻倍**的警告（§5 引用）。
   ⇒ 「RuntimeShader 一层 = 至少一次额外的全屏（或全层）合成与一次全屏片元着色」——
   **"至少一次"的具体数量未核实**（取决于 HWUI 是否能把 effect 直接融进目标 pass），
   但"要多一遍全屏级工作"这个量级是确定的。
2. **重新上 uniform 不需要重新录制内容**。Compose 自己的实现注释写得很清楚（同一份 `Blur.kt`）：
   > "changing only the radius **re-runs the layer block** (observed by the layer system) to push a new
   > renderEffect onto the existing RenderNode, **without re-recording the content display list**."
   ⇒ 每帧改 uniform 的代价是"重跑 layer block + 更新 RenderNode 属性"，**不是**"重新录制整个内容"。
3. **但 AOSP 侧的 setter 会丢弃 native 实例**：`RuntimeShader` 的每个 setter 末尾都调用
   `discardNativeInstance()`（AOSP 源码，见 §1.1 链接）。
   ⇒ 下一帧会**重新创建 native `Shader` 对象**；"Skia 是否按源码字符串缓存已编译的 `SkRuntimeEffect`"
   **未核实**。所以"每帧改 uniform"在 **Java/native 对象层面不是零成本**，这一点与粒子系统的
   "每帧零分配"纪律有直接冲突（至少是 native 分配）。

### 2.3 能不能不重组就换 uniform？——能，但要按 Ncrust 的纪律来

`setFloatUniform` / `setColorUniform` 都是普通 Java setter，**不涉及 Compose 的 snapshot 系统**。
所以"每帧换 uniform"**不需要重组**，前提是：

- 调用点不在组合阶段（不在 composable 函数体里）；
- 调用点不在"读 state 就会触发重组"的位置。

**本 fork 的正确位置是 `graphicsLayer { }` 块**（layer block，跑在**布局/绘制阶段之外**的组合外路径）：

```kotlin
Modifier.graphicsLayer {
    if (Build.VERSION.SDK_INT >= 33) {
        MotionClock.generation          // ← 读"每帧+1"的代际，使 layer block 每帧重跑
        shader.setFloatUniform("uLevel", MotionClock.level())
        renderEffect = asComposeRenderEffect(effect)
    }
}
```

这与 Ncrust 现有纪律完全一致（`MotionClock.kt:66-67` 的注释：*"只应在 draw / `graphicsLayer` 块里读
（在组合阶段读会变成每帧重组）"*），也是官方推荐的 "defer reads" 写法
（[Compose 性能最佳实践](https://developer.android.com/develop/ui/compose/performance/bestpractices)）。

**反面写法（必须避免）**：把 uniform 的值放进 `mutableStateOf` 再在 composable 里读 ——
那就是**每帧重组整棵子树**，与本 fork 的"GPU 零重组"原则正面冲突。

---

## 3. AGSL 的语言限制（能核实的都引原文）

### 3.1 控制流与函数

> "Control structures such as `if-else` statements work much like they do in C; the language also provides
> support for `switch` statements and **`for` loops with limitations. Some control structures require constant
> expressions that can be evaluated at compile time.**"
> "AGSL supports functions; every shader program begins with the `main` function. **User defined functions are
> supported, without support for recursion of any kind.** Functions use a 'value-return' calling convention;
> values passed to functions are copied into parameters when the function is called, and outputs are copied back;
> this is determined by the `in`, `out`, and `inout` qualifiers."
> — [AGSL · developer.android.com](https://developer.android.com/develop/ui/views/graphics/agsl)

⇒ **动态边界的循环不保证可用**（"for loops with limitations" + "constant expressions"）。
写音频可视化时最常见的需求（"按频段数循环""按粒子数循环"）必须**编译期常量上界 + 运行期 `break`**，
且 `break` 是否在所有设备上都被接受 **未核实**（文档没写）。

### 3.2 uniform 类型与数量

类型见 §1.1 表格（`float`/`int`/`layout(color) vec4`/`uniform shader` + 数组/矩阵）。
**uniform 的数量上限：未核实** —— 官方 AGSL 文档与 `RuntimeShader` javadoc 都**没有给出数字**
（SkSL 侧可能有上限，但未在 AGSL 文档中体现）。若要在真机上探边界，只能实测。

### 3.3 精度限定符

> "Qualifiers can be applied to types for **precision hints** in a way that's unique to shading languages."
> — [AGSL](https://developer.android.com/develop/ui/views/graphics/agsl)

文档只到"有精度提示"这一层；**AGSL 支持哪些限定符（`lowp`/`mediump`/`highp` 是否齐全）、
默认精度是多少 —— 未核实**。

### 3.4 `#include` / `#pragma`

**未核实。** 官方 AGSL 文档通篇没有提到预处理指令（既没说支持，也没说不支持）。
不要假设可以把着色器拆成多个文件用 `#include` 拼；**唯一的可靠做法是运行期用字符串拼装**
（那就引入 §3.5 的字符串长度问题）。

### 3.5 源码字符串长度上限

**未核实。** 官方文档未给出上限。工程上必须假设"有上限且因设备而异"（SkSL 编译器 + 驱动），
所以：**不要把巨大的查找表塞进源码字符串**（那是 uniform/`BitmapShader` 的活），
源码里只放常量上界与算法。

### 3.6 逐设备编译与失败模式

- **构造即编译**：`RuntimeShader(String)` 的构造体直接 `nativeCreateBuilder(shader)`（AOSP 源码，§1.1）。
- **编译失败的异常类型：未核实**（AOSP 里是 native 调用，抛什么由 native 侧决定；官方文档未说明）。
  ⇒ **唯一的工程纪律：把 `RuntimeShader(...)` 与 `setInputXxx` 全部包在 `runCatching` 里**，
  失败即回退到 Canvas 路径，绝不冒泡（这也是本 fork 既有的"异常隔离"铁律）。
- **首次编译的耗时/卡顿**：无官方数据，**未核实**。合理的工程结论是"不要在展开播放器的动画中途第一次构造它"
  （例如设置页里"预览一次"或提前预热），但**这是建议不是事实**。
- **没有离线预编译**：AGSL 只能在设备上编译 ⇒ 不同 GPU 驱动之间的行为差异无法在 CI 里覆盖 ——
  这是**结构性**问题（推理，非引用）。

### 3.7 两个 AGSL 特有的坑（官方原文）

1. **必须返回预乘 alpha**：
   > "In AGSL the color returned by the `main` function is expected to be **premultiplied**. … If your AGSL
   > shader will return transparent colors, be sure to multiply the RGB by A. The resulting color should be
   > `[R*A, G*A, B*A, A]`, not `[R, G, B, A]`."
2. **坐标系是左上原点（与 GLSL 相反）**：
   > "AGSL matches the screen coordinate system of the Android `Canvas` which has its origin as the **upper left
   > corner**."（GLSL 的 `gl_FragCoord` 是左下原点）
   另有色彩管理：工作色彩空间 = 目标缓冲的色彩空间，需要线性空间运算时用内建
   `toLinearSrgb` / `fromLinearSrgb`。
   > 以上三条均出自 [RuntimeShader javadoc](https://developer.android.com/reference/android/graphics/RuntimeShader)（本轮经 MS Learn 镜像取得全文）。

---

## 4. 低端设备上的代价：**无公开数据，需实测**

### 4.1 公开基准数据

本轮检索**没有找到**任何"全屏 AGSL/RuntimeShader 在 2015 年级低端 GPU（Mali-T760 / Galaxy S6 /
Snapdragon 6xx）上每帧耗时"的公开基准或博客。
**结论：无公开数据，需实测。** 任何"AGSL 在 S6 上大概能跑 X fps"的说法都不应被采信。

### 4.2 可算的量级（估算，不是测量）

Galaxy S6 是 1440×2560 = **3.69M 像素**。如果 AGSL 效果覆盖全屏：

```
每帧片元数         ≈ 3.69M（效果 pass）+ 3.69M（图层合成/拷贝，至少）
60fps 的片元吞吐   ≥ 4.4 亿片元/秒
```

再叠加官方给的 overdraw 经验上限（**每帧不超过屏幕像素的 2.5 倍**，
[Hardware acceleration](https://developer.android.com/guide/topics/graphics/hardware-accel)），
"全屏 AGSL + 原有 UI 内容 + 粒子层"很可能一开始就吃掉了大半预算。
**这段是算术估算，不是实测 —— 标「需实测」。**

### 4.3 本 fork 特有的、决定性的一条

Ncrust 的验收机是 **Galaxy S6 / SM-G9209 / Android 7.0 = API 24**（本仓库 `AGENTS.md` 与
`docs/verification/**` 的多轮真机记录均以该机为准）。而 `RuntimeShader` 需要 **API 33**。

⇒ **该效果在验收机上根本不存在**，因此：
- 不可能用 `FrameMetrics` 在 S6 上测出它的开销；
- 不可能验证它是否违反"动效不得让 S6 掉帧"这条硬规则；
- 于是任何"它应该不慢"的论证都只是信念。

这一条比任何性能数据都更能决定结论（§6）。

---

## 5. API 24 上真正可用的替代方案

| 手段 | API | API 24 可用？ | 依据 / 说明 |
|---|---|---|---|
| Compose `Brush.linearGradient` / `Brush.radialGradient`（Canvas 里画渐变） | 底层 `LinearGradient`/`RadialGradient` **自 API 1** | ✅ | 实测 `api-versions.xml`：`<class name="android/graphics/LinearGradient" since="1">` 等；Compose 侧 [Brush](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Brush) |
| `BitmapShader`（含 Compose `ImageShader`） | **API 1** | ✅ | 实测 `api-versions.xml`；适合"一张精灵图平铺/采样" |
| **预模糊位图**（先降采样再模糊，得到静态模糊底图） | 纯 Java/Kotlin | ✅ | **本仓库已在用**：`app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/CoverBlur.kt` + `CoverBlurCache`（每首歌只算一次）。这是"没有 RenderEffect 也能有模糊"的正确答案 |
| `BlurMaskFilter` | 类自 **API 1** | ❌ **实际无效** | 官方支持表里 `setMaskFilter()` 的"首次支持 API 等级"是 **✗（不支持）**：硬件加速画布**忽略**它。见下 |
| Compose `Modifier.blur` | 需 **API 31** | ❌ 静默 no-op | KDoc 原文："**Note this effect is only supported on Android 12 and above. Attempts to use this Modifier on older Android versions will be ignored.**"（[`Blur.kt` · androidx-main](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/Blur.kt)） |
| `RenderEffect.createBlurEffect` / `graphicsLayer { renderEffect = BlurEffect(...) }` | **API 31** | ❌ | 实测 `api-versions.xml`：`RenderEffect since="31"` |
| `RuntimeShader` / AGSL | **API 33** | ❌ | §1 |

**`BlurMaskFilter` 这一条要特别小心（任务书要求核实）**：类的存在（API 1）与"能不能用"是两件事。
Android 的硬件加速支持表把 `Paint.setMaskFilter()` 标为**不支持（✗）**：

> First supported API level — Paint: `setAntiAlias() (for text)` 18 / `setFilterBitmap()` 17 /
> **`setMaskFilter()` ✗** / `setPathEffect() (for lines)` 28 / `setShadowLayer() (other than text)` 28 …
> — [Hardware acceleration · Support for drawing operations](https://developer.android.com/guide/topics/graphics/hardware-accel)

同页给出的官方绕法正是本仓库已经在做的：

> "If a drawing operation you depend on isn't hardware accelerated, **render the affected drawing into an
> off-screen software `Bitmap` (or `ImageBitmap`) and draw the result.** The rest of your UI keeps the
> hardware-accelerated path."

⇒ 在 Compose（默认硬件加速）里给 `Paint` 设 `BlurMaskFilter` **不会报错，只会没效果**。
要模糊就**预先在软件位图上算好**（`CoverBlur` 路线），别指望它。

其他与 API 24 相关的绘制约束（同页支持表，供实现时避坑）：
`drawVertices()` 首次支持 **29**、`drawPicture()` **23**、`setPathEffect() (for lines)` **28**、
`Simple Shapes`（含 `drawCircle`）缩放正确性 **17**、`Complex Shapes`/`drawPath()` 缩放正确性 **28**。
⇒ 在 API 24–27 的设备上，**带 `PathEffect` 或复杂缩放的路径绘制会退化**；
粒子用"简单形状"（`drawCircle`）就没有这个问题。

---

## 附：本篇的「未核实」集中列表

| 条目 | 状态 |
|---|---|
| androidx 是否存在 `RuntimeShader` 兼容层（本轮为定向检索，非全量枚举） | **未核实**（结论"没有回落"由 §1.4 第 2/3 条独立支撑） |
| 在 API < 33 上执行 `RuntimeShader(...)` 抛出的具体异常类型 | **未核实**（结论"必须版本判断"不依赖它） |
| `asComposeRenderEffect` 的长期 API 稳定性（不在 Compose 官方 API 参考里） | **未核实** |
| `setInputColorFilter` / `setInputXfermode` 在哪个 API 等级对第三方应用公开（源码标 `@FlaggedApi`） | **未核实** |
| AGSL 的 uniform 数量上限、源码字符串长度上限、`#include`/`#pragma` 支持、精度限定符全集、动态边界 `break` 支持 | **未核实**（官方文档未涉及） |
| `RuntimeShader` 构造编译失败的异常类型、首次编译耗时 | **未核实** |
| Skia 是否按源码字符串缓存已编译的 `SkRuntimeEffect`（关系到"每帧改 uniform 会不会重建 native 对象"） | **未核实**（AOSP 侧 `discardNativeInstance()` 是确定的） |
| 每帧离屏 pass 的**确切**数量（"至少一次"是确定的量级） | **未核实** |
| 全屏 AGSL 在 Mali-T760 / Galaxy S6 级设备上的实测帧时间 | **无公开数据，需实测**（且本 fork 无法在 S6 上实测 —— 见 §4.3） |

---

## 推荐方案（给 Ncrust）

### R1 · 不该做的

- ❌ **不把 `RuntimeShader` 作为动效的默认/唯一实现**。它在 4 类设备（API 24–32）上完全不存在，
  等于把这些用户直接踢出该功能。
- ❌ **不做"只在 API 33+ 出现的 showcase 档"**。理由是**可验证性**，不是性能：
  1. 验收机（S6/API 24）跑不到，**任何"没掉帧"的结论都无法在验收机上取证**；
  2. 本 fork 的降级阶梯（`MotionDegrade` + `VisualizerFrameMonitor`）是**基于 S6 实测水位**设计的；
     一个新效果若只在 API 33+ 存在，它的水位由**另一批设备**决定，会污染现有判据的可解释性；
  3. 多一条渲染路径 = 多一套"关掉开关 = 零开销"的证明责任，而它**没有对应的验证环境**。
- ❌ **不用 `RuntimeShader` 做"给每个粒子加效果"**。§1.3 已经说明它是**图层级**的：
  一个 RenderEffect 作用于一个 RenderNode，不是 per-particle 的东西。
- ❌ **不用 `BlurMaskFilter`**（硬件加速下无效）、**不用 `Modifier.blur` 做 API 24 的模糊**（no-op）。

### R2 · 可以做的（如果将来一定要）

如果未来版本确实要做"炫技档"，形态只能是：

1. **装饰层，不是替代层**：现有的 Canvas 粒子/光晕/波形路径一行不改，AGSL 只是**叠在上面**的一层；
   关掉它时视觉与 v3.0.0 逐像素一致。
2. **三重门禁**：`Build.VERSION.SDK_INT >= 33` **且** 设备能力探测通过 **且** 用户在设置里显式打开
   （默认**关**）。任何一门不满足 ⇒ 不构造 `RuntimeShader`、不建图层、零开销。
3. **能力探测**：构造 `RuntimeShader` 包在 `runCatching` 里（编译失败即视为"本机不支持"），
   结果缓存成能力位；**不要在动画中途第一次构造**。
4. **可控的 uniform 预算**：只暴露 3–6 个 `float`/`vec4` uniform（电平、低频、节拍脉冲、时间、解析度），
   全部由 `MotionClock.generation` 在 `graphicsLayer { }` 块里驱动，**零重组**（§2.3）。
5. **可回退的验收**：即便在 API 33+ 上，也要能一键退回 Canvas 路径 —— 否则一旦某个机型上
   该效果异常，用户只能卸载。
6. **在文档里写清"本档在验收机（S6）上不存在"**：这是给未来维护者的最重要一条，
   避免有人拿"用户没反馈卡顿"当作"它不卡"的证据。

### R3 · 一句话结论

> **v3.0.0 不落地 `RuntimeShader`。** 不是因为"AGSL 不好"，而是因为
> **它在 minSdk 24 上不存在、在验收机上不存在、且没有任何回落方案** ——
> 一个无法在验收机上取证的效果，不该进入一条"动效不得让 S6 掉帧"的硬约束之下。
> 需要更强的视觉效果时，先在**预模糊位图 + Canvas 渐变 + 定长粒子池**这条已验证过的路上加码（§5）。

---

## 不可用清单（附理由）

| # | 手段 | 不可用理由 | 依据 |
|---|---|---|---|
| 1 | `android.graphics.RuntimeShader`（AGSL） | **类自 API 33 起才存在**；minSdk 24 上无任何等价回落；本 fork 验收机（S6/API 24）跑不到，无法验证 | 实测 `api-versions.xml since="33"`；[RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader)；[AGSL（"Android 13 and above"）](https://developer.android.com/develop/ui/views/graphics/agsl) |
| 2 | `RenderEffect.createRuntimeShaderEffect` | **API 33**（参数含 `RuntimeShader`，不可能更早） | 实测 `api-versions.xml` + MS Learn `ApiSince=33` |
| 3 | `RenderEffect`（含 `createBlurEffect`） | **API 31** | 实测 `api-versions.xml since="31"` |
| 4 | Compose `Modifier.blur` | 官方 KDoc：**Android 12 以下被忽略（no-op）** —— 不会报错，只是没效果 | [`Blur.kt` KDoc](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/Blur.kt) |
| 5 | `graphicsLayer { renderEffect = BlurEffect(...) }` | 同 4，底层需要 API 31 | 同上 + Compose `BlurEffect` 走 `RenderEffectVerificationHelper`（实测字节码） |
| 6 | `Paint.setMaskFilter` / `BlurMaskFilter` | **硬件加速画布不支持**（官方支持表标 ✗）。Compose 默认硬件加速 ⇒ 静默无效。官方建议改成"离屏软件位图预渲染" | [Hardware acceleration 支持表](https://developer.android.com/guide/topics/graphics/hardware-accel) |
| 7 | 用 `RuntimeShader` 做"每粒子效果" | `createRuntimeShaderEffect` 的语义是**把一个 RenderNode 的内容喂给 shader**，是图层级、不是逐粒子级 | [createRuntimeShaderEffect](https://learn.microsoft.com/en-us/dotnet/api/android.graphics.rendereffect.createruntimeshadereffect) |
| 8 | 在组合阶段写 uniform 值（`mutableStateOf` + composable 里读） | 会变成**每帧重组整棵子树**，违反本 fork「GPU 零重组」硬约束；官方推荐把 state 读取推迟到最晚 | [Compose 性能最佳实践 · Defer reads](https://developer.android.com/develop/ui/compose/performance/bestpractices) |
| 9 | `drawVertices`（想在 API 24 上自己批处理粒子） | 硬件加速下 `drawVertices()` 首次支持 **API 29**；API 24–28 上行为不可依赖 | [Hardware acceleration 支持表](https://developer.android.com/guide/topics/graphics/hardware-accel) |
| 10 | 依赖 AGSL 的 `#include`/`#pragma` 做代码组织 | 官方 AGSL 文档未提及预处理指令支持 | **未核实**（故不可依赖） |
| 11 | 假设 uniform 数量 / 源码字符串长度有明确上限 | 官方文档未给出任何数字 | **未核实**（故不可依赖，只能实测探测） |
